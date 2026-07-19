from __future__ import annotations

import json
import hashlib
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from scripts.audit_runtime_licenses import (
    AuditInputError,
    CHECKER_EXCEPTION_CLASS_COUNT,
    CHECKER_EXCEPTION_COORDINATE,
    CHECKER_EXCEPTION_EVIDENCE,
    CHECKER_EXCEPTION_JAR_SHA256,
    CHECKER_EXCEPTION_LICENSES,
    CHECKER_EXCEPTION_POM_SHA256,
    CHECKER_EXCEPTION_SOURCE_PATH,
    CHECKER_EXCEPTION_SOURCE_SHA256,
    CHECKER_EXCEPTION_SOURCE_URL,
    CHECKER_EXCEPTION_SOURCE_URL_SHA256,
    CHECKER_EXCEPTION_REVIEW_DATE,
    CHECKER_EXCEPTION_REVIEWER,
    CHECKER_EXCEPTION_STATUS,
    Coordinate,
    TechnicalEvidence,
    _verify_annotation_jar,
    audit_runtime_licenses,
    load_policy,
    parse_lockfile,
)


APACHE = ("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")
MIT = ("The MIT License", "https://opensource.org/licenses/MIT")
GPL_CLASSPATH = (
    "GNU General Public License, version 2 (GPL2), with the classpath exception",
    "https://www.gnu.org/software/classpath/license.html",
)
CHECKER_LICENSE_VALUES = list(CHECKER_EXCEPTION_LICENSES)
PROJECT_ROOT = Path(__file__).resolve().parents[2]
PINNED_SOURCE_FILE = PROJECT_ROOT / CHECKER_EXCEPTION_SOURCE_PATH


def _coordinate(value: str) -> Coordinate:
    return Coordinate.parse(value)


def _write_lock(path: Path, entries: list[tuple[str, str]]) -> None:
    lines = [
        "# Gradle lock fixture",
        *(f"{coordinate}={configuration}" for coordinate, configuration in entries),
        "empty=debugOnly",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _write_pom(
    cache: Path,
    coordinate: str,
    *,
    licenses: list[tuple[str, str]] | None = None,
    parent: str | None = None,
    hash_directory: str = "hash-a",
) -> Path:
    parsed = _coordinate(coordinate)
    directory = cache / parsed.group / parsed.artifact / parsed.version / hash_directory
    directory.mkdir(parents=True, exist_ok=True)
    parent_xml = ""
    if parent is not None:
        parent_coordinate = _coordinate(parent)
        parent_xml = (
            "<parent>"
            f"<groupId>{parent_coordinate.group}</groupId>"
            f"<artifactId>{parent_coordinate.artifact}</artifactId>"
            f"<version>{parent_coordinate.version}</version>"
            "</parent>"
        )
    licenses_xml = ""
    if licenses is not None:
        license_rows = "".join(
            f"<license><name>{name}</name><url>{url}</url></license>"
            for name, url in licenses
        )
        licenses_xml = f"<licenses>{license_rows}</licenses>"
    pom = directory / f"{parsed.artifact}-{parsed.version}.pom"
    pom.write_text(
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
        "<modelVersion>4.0.0</modelVersion>"
        f"{parent_xml}"
        f"<groupId>{parsed.group}</groupId>"
        f"<artifactId>{parsed.artifact}</artifactId>"
        f"<version>{parsed.version}</version>"
        f"{licenses_xml}"
        "</project>",
        encoding="utf-8",
    )
    return pom


def _annotation_class_bytes(
    internal_name: str,
    *,
    access_flags: int = 0x2601,
    unknown_constant_tag: bool = False,
) -> bytes:
    def utf8(value: str) -> bytes:
        encoded = value.encode("ascii")
        return b"\x01" + struct.pack(">H", len(encoded)) + encoded

    constant_pool = b"".join(
        (
            utf8(internal_name),
            b"\x07\x00\x01",
            utf8("java/lang/Object"),
            b"\x07\x00\x03",
            utf8("java/lang/annotation/Annotation"),
            b"\x07\x00\x05",
        )
    )
    if unknown_constant_tag:
        constant_pool = b"\x63" + constant_pool[1:]
    return b"".join(
        (
            struct.pack(">IHHH", 0xCAFEBABE, 0, 52, 7),
            constant_pool,
            struct.pack(">HHHH", access_flags, 2, 4, 1),
            struct.pack(">H", 6),
            struct.pack(">HHH", 0, 0, 0),
        )
    )


def _write_main_jar(
    cache: Path,
    coordinate: str,
    *,
    class_count: int = 10,
    access_flags: int = 0x2601,
    unknown_constant_tag: bool = False,
    truncate_class: bool = False,
    class_prefix: str = "fixture/Annotation",
    hash_directory: str = "jar-hash-a",
) -> Path:
    parsed = _coordinate(coordinate)
    directory = cache / parsed.group / parsed.artifact / parsed.version / hash_directory
    directory.mkdir(parents=True, exist_ok=True)
    jar = directory / f"{parsed.artifact}-{parsed.version}.jar"
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for index in range(class_count):
            name = f"{class_prefix}{index}"
            class_bytes = _annotation_class_bytes(
                name,
                access_flags=access_flags,
                unknown_constant_tag=unknown_constant_tag and index == 0,
            )
            if truncate_class and index == 0:
                class_bytes = class_bytes[:-1]
            archive.writestr(f"{name}.class", class_bytes)
    return jar


def _write_upstream_source(repository_root: Path, *, content: bytes | None = None) -> Path:
    source = repository_root / CHECKER_EXCEPTION_SOURCE_PATH
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes(
        PINNED_SOURCE_FILE.read_bytes() if content is None else content
    )
    return source


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def _rule(canonical_id: str, verdict: str, license_value: tuple[str, str]) -> dict[str, object]:
    return {
        "canonicalId": canonical_id,
        "verdict": verdict,
        "names": [license_value[0]],
        "urls": [license_value[1]],
    }


def _write_policy(
    path: Path,
    *,
    exceptions: list[dict[str, object]] | None = None,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "schemaVersion": 2,
                "configuration": "releaseRuntimeClasspath",
                "rules": [
                    _rule("Apache-2.0", "ALLOW_DECLARED", APACHE),
                    _rule("MIT", "ALLOW_DECLARED", MIT),
                    _rule(
                        "GPL-2.0-with-Classpath-exception",
                        "MANUAL_REVIEW",
                        GPL_CLASSPATH,
                    ),
                    _rule(
                        "Forbidden-Fixture-License",
                        "DISALLOWED",
                        ("Forbidden Fixture License", "https://invalid.example/forbidden"),
                    ),
                ],
                "exceptions": exceptions or [],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def _exception(
    coordinate: str,
    expected: list[tuple[str, str]],
    *,
    status: str = CHECKER_EXCEPTION_STATUS,
    evidence: str = CHECKER_EXCEPTION_EVIDENCE,
    reviewer: str = CHECKER_EXCEPTION_REVIEWER,
    review_date: str = CHECKER_EXCEPTION_REVIEW_DATE,
    pom_sha256: str = CHECKER_EXCEPTION_POM_SHA256,
    jar_sha256: str = CHECKER_EXCEPTION_JAR_SHA256,
    source_sha256: str = CHECKER_EXCEPTION_SOURCE_SHA256,
    source_path: str = CHECKER_EXCEPTION_SOURCE_PATH,
    source_url: str = CHECKER_EXCEPTION_SOURCE_URL,
    source_url_sha256: str = CHECKER_EXCEPTION_SOURCE_URL_SHA256,
    expected_class_count: int = CHECKER_EXCEPTION_CLASS_COUNT,
    required_flags: list[str] | None = None,
    sources: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    source_record = {
        "path": source_path,
        "sha256": source_sha256,
        "url": source_url,
        "urlSha256": source_url_sha256,
    }
    return {
        "coordinate": coordinate,
        "expectedLicenses": [
            {"name": name, "url": url} for name, url in expected
        ],
        "status": status,
        "evidence": evidence,
        "reviewer": reviewer,
        "date": review_date,
        "technicalEvidence": {
            "pomSha256": pom_sha256,
            "jarSha256": jar_sha256,
            "expectedClassCount": expected_class_count,
            "requiredClassAccessFlags": required_flags
            if required_flags is not None
            else ["ACC_ANNOTATION", "ACC_INTERFACE"],
            "upstreamLicenseSources": sources if sources is not None else [source_record],
        },
    }


class RuntimeLicenseAuditTest(unittest.TestCase):
    def _fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path, Path, Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        lock = root / "gradle.lockfile"
        cache = root / "maven-cache"
        policy = root / "config/runtime-license-policy.json"
        _write_policy(policy)
        return temporary, lock, cache, policy

    def _bound_exception_fixture(
        self,
        lock: Path,
        cache: Path,
        policy: Path,
        *,
        class_count: int = 10,
        access_flags: int = 0x2601,
        unknown_constant_tag: bool = False,
        truncate_class: bool = False,
    ) -> tuple[str, Path, Path, Path, dict[str, object]]:
        coordinate = CHECKER_EXCEPTION_COORDINATE
        _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
        pom = _write_pom(cache, coordinate, licenses=CHECKER_LICENSE_VALUES)
        jar = _write_main_jar(
            cache,
            coordinate,
            class_count=class_count,
            access_flags=access_flags,
            unknown_constant_tag=unknown_constant_tag,
            truncate_class=truncate_class,
        )
        source = _write_upstream_source(policy.parent.parent)
        exception = _exception(coordinate, CHECKER_LICENSE_VALUES)
        return coordinate, pom, jar, source, exception

    def test_happy_path(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _write_lock(lock, [("example:alpha:1.0", "releaseRuntimeClasspath")])
            _write_pom(cache, "example:alpha:1.0", licenses=[APACHE])

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertTrue(result.ok)
            component = result.components[0]
            self.assertEqual("LOCAL_SINGLE", component["declarationState"])
            self.assertEqual("Apache-2.0", component["licenses"][0]["canonicalId"])
            self.assertEqual("ALLOW_DECLARED", component["verdict"])

    def test_parent_license_inheritance_is_recursive_and_evidenced(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _write_lock(lock, [("example:child:1.0", "releaseRuntimeClasspath")])
            _write_pom(cache, "example:child:1.0", parent="example:parent:2.0")
            _write_pom(
                cache,
                "example:parent:2.0",
                parent="example:grandparent:3.0",
            )
            _write_pom(cache, "example:grandparent:3.0", licenses=[APACHE])

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertTrue(result.ok)
            component = result.components[0]
            self.assertEqual("INHERITED_SINGLE", component["declarationState"])
            self.assertEqual(
                "example:grandparent:3.0", component["licenseSourceCoordinate"]
            )
            self.assertEqual(
                [
                    "example:child:1.0",
                    "example:parent:2.0",
                    "example:grandparent:3.0",
                ],
                [item["coordinate"] for item in component["pomChain"]],
            )

    def test_explicit_empty_licenses_fail_without_inferred_inheritance(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _write_lock(lock, [("example:child:1.0", "releaseRuntimeClasspath")])
            _write_pom(
                cache,
                "example:child:1.0",
                licenses=[],
                parent="example:parent:2.0",
            )
            _write_pom(cache, "example:parent:2.0", licenses=[APACHE])

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("EMPTY", result.components[0]["declarationState"])
            self.assertEqual("FAIL_EMPTY", result.components[0]["verdict"])
            self.assertEqual(1, len(result.components[0]["pomChain"]))

    def test_missing_pom_fails_closed(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _write_lock(lock, [("example:missing:1.0", "releaseRuntimeClasspath")])

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("MISSING_POM", result.components[0]["declarationState"])
            self.assertEqual("FAIL_MISSING_POM", result.components[0]["verdict"])

    def test_missing_license_and_parent_is_undeclared(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = "example:undeclared:1.0"
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            _write_pom(cache, coordinate)

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("UNDECLARED", result.components[0]["declarationState"])
            self.assertEqual("FAIL_UNDECLARED", result.components[0]["verdict"])

    def test_partial_license_declaration_is_ambiguous(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = "example:partial:1.0"
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            _write_pom(
                cache,
                coordinate,
                licenses=[("Apache License, Version 2.0", "")],
            )

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("AMBIGUOUS", result.components[0]["declarationState"])
            self.assertEqual("FAIL_AMBIGUOUS", result.components[0]["verdict"])

    def test_disallowed_license_rule_fails_closed(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = "example:forbidden:1.0"
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            _write_pom(
                cache,
                coordinate,
                licenses=[("Forbidden Fixture License", "https://invalid.example/forbidden")],
            )

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("FAIL_DISALLOWED", result.components[0]["verdict"])

    def test_gpl_classpath_in_multiple_declarations_requires_manual_review(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _write_lock(lock, [("example:dual:1.0", "releaseRuntimeClasspath")])
            _write_pom(cache, "example:dual:1.0", licenses=[MIT, GPL_CLASSPATH])

            result = audit_runtime_licenses(lock, cache, policy)

            component = result.components[0]
            self.assertFalse(result.ok)
            self.assertEqual("LOCAL_MULTIPLE", component["declarationState"])
            self.assertEqual("FAIL_MANUAL_REVIEW", component["verdict"])
            self.assertEqual(
                ["GPL-2.0-with-Classpath-exception", "MIT"],
                sorted(item["canonicalId"] for item in component["licenses"]),
            )

    def test_checked_in_technical_exception_is_exactly_pinned(self) -> None:
        policy = load_policy(PROJECT_ROOT / "config/runtime-license-policy.json")

        self.assertEqual(1, len(policy.exceptions))
        exception = policy.exceptions[0]
        self.assertEqual(CHECKER_EXCEPTION_COORDINATE, str(exception.coordinate))
        self.assertEqual(
            CHECKER_EXCEPTION_POM_SHA256,
            exception.technical_evidence.pom_sha256,
        )
        self.assertEqual(
            CHECKER_EXCEPTION_JAR_SHA256,
            exception.technical_evidence.jar_sha256,
        )
        self.assertEqual(
            CHECKER_EXCEPTION_CLASS_COUNT,
            exception.technical_evidence.expected_class_count,
        )

    def test_legal_approval_narrative_and_status_drift_are_rejected(self) -> None:
        temporary, _, _, policy = self._fixture()
        with temporary:
            contradictory = _exception(
                CHECKER_EXCEPTION_COORDINATE,
                CHECKER_LICENSE_VALUES,
                evidence="LEGAL APPROVAL GRANTED; COMMERCIAL REDISTRIBUTION APPROVED.",
                reviewer="Project legal counsel",
                review_date="2099-12-31",
            )
            status_drift = _exception(
                CHECKER_EXCEPTION_COORDINATE,
                CHECKER_LICENSE_VALUES,
                status="MANUAL_REVIEW",
            )
            for label, exception in (
                ("contradictory_narrative", contradictory),
                ("status", status_drift),
            ):
                with self.subTest(label=label):
                    _write_policy(policy, exceptions=[exception])
                    with self.assertRaises(AuditInputError):
                        load_policy(policy)

    def test_bound_pom_and_jar_hash_field_mutations_fail(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _, _, _, _, base_exception = self._bound_exception_fixture(
                lock, cache, policy
            )
            for field in ("pomSha256", "jarSha256"):
                with self.subTest(field=field):
                    exception = json.loads(json.dumps(base_exception))
                    exception["technicalEvidence"][field] = "0" * 64
                    _write_policy(policy, exceptions=[exception])

                    with self.assertRaises(AuditInputError):
                        load_policy(policy)

    def test_joint_policy_artifact_count_and_source_drift_is_rejected(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = CHECKER_EXCEPTION_COORDINATE
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            pom = _write_pom(cache, coordinate, licenses=CHECKER_LICENSE_VALUES)
            jar = _write_main_jar(cache, coordinate, class_count=9)
            source = policy.parent.parent / (
                "docs/supply-chain/licenses/joint-drift-LICENSE.txt"
            )
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(b"joint drift fixture source\n")
            source_url = (
                "https://raw.githubusercontent.com/example/joint-drift/v9/LICENSE.txt"
            )
            exception = _exception(
                coordinate,
                CHECKER_LICENSE_VALUES,
                pom_sha256=_file_sha256(pom),
                jar_sha256=_file_sha256(jar),
                source_sha256=_file_sha256(source),
                source_path="docs/supply-chain/licenses/joint-drift-LICENSE.txt",
                source_url=source_url,
                source_url_sha256=hashlib.sha256(
                    source_url.encode("utf-8")
                ).hexdigest().upper(),
                expected_class_count=9,
            )
            _write_policy(policy, exceptions=[exception])

            with self.assertRaises(AuditInputError):
                audit_runtime_licenses(lock, cache, policy)

    def test_class_count_field_and_required_flags_mutations_fail(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _, _, _, _, base_exception = self._bound_exception_fixture(
                lock, cache, policy
            )
            count_exception = json.loads(json.dumps(base_exception))
            count_exception["technicalEvidence"]["expectedClassCount"] = 9
            _write_policy(policy, exceptions=[count_exception])
            with self.assertRaises(AuditInputError):
                load_policy(policy)

            flags_exception = json.loads(json.dumps(base_exception))
            flags_exception["technicalEvidence"]["requiredClassAccessFlags"] = [
                "ACC_INTERFACE",
                "ACC_ANNOTATION",
            ]
            _write_policy(policy, exceptions=[flags_exception])
            with self.assertRaises(AuditInputError):
                load_policy(policy)

    def test_source_path_hash_url_and_url_hash_mutations_fail(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _, _, _, _, base_exception = self._bound_exception_fixture(
                lock, cache, policy
            )
            mutations = (
                ("path", "docs/supply-chain/licenses/missing-LICENSE.txt"),
                ("sha256", "0" * 64),
                (
                    "url",
                    "https://raw.githubusercontent.com/example/project/v2/LICENSE.txt",
                ),
                ("urlSha256", "0" * 64),
            )
            for field, value in mutations:
                with self.subTest(field=field):
                    exception = json.loads(json.dumps(base_exception))
                    source = exception["technicalEvidence"]["upstreamLicenseSources"][0]
                    source[field] = value
                    _write_policy(policy, exceptions=[exception])
                    with self.assertRaises(AuditInputError):
                        load_policy(policy)

    def test_source_path_escape_and_missing_or_ambiguous_source_records_fail(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _, _, _, _, base_exception = self._bound_exception_fixture(
                lock, cache, policy
            )
            escape = json.loads(json.dumps(base_exception))
            escape["technicalEvidence"]["upstreamLicenseSources"][0]["path"] = (
                "../outside-LICENSE.txt"
            )
            missing = json.loads(json.dumps(base_exception))
            missing["technicalEvidence"]["upstreamLicenseSources"] = []
            ambiguous = json.loads(json.dumps(base_exception))
            ambiguous["technicalEvidence"]["upstreamLicenseSources"].append(
                dict(ambiguous["technicalEvidence"]["upstreamLicenseSources"][0])
            )
            for label, exception in (
                ("path_escape", escape),
                ("missing", missing),
                ("ambiguous", ambiguous),
            ):
                with self.subTest(label=label):
                    _write_policy(policy, exceptions=[exception])
                    with self.assertRaises(AuditInputError):
                        load_policy(policy)

    def test_missing_and_ambiguous_main_jar_artifacts_fail(self) -> None:
        for state in ("missing", "ambiguous"):
            with self.subTest(state=state):
                temporary, lock, cache, policy = self._fixture()
                with temporary:
                    coordinate, _, jar, _, exception = self._bound_exception_fixture(
                        lock, cache, policy
                    )
                    if state == "missing":
                        jar.unlink()
                    else:
                        _write_main_jar(
                            cache,
                            coordinate,
                            hash_directory="jar-hash-b",
                        )
                    _write_policy(policy, exceptions=[exception])

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual(
                        "FAIL_EXCEPTION_EVIDENCE", result.components[0]["verdict"]
                    )

    def test_missing_and_mutated_pinned_source_file_fail(self) -> None:
        for state in ("missing", "mutated"):
            with self.subTest(state=state):
                temporary, lock, cache, policy = self._fixture()
                with temporary:
                    _, _, _, source, exception = self._bound_exception_fixture(
                        lock, cache, policy
                    )
                    _write_policy(policy, exceptions=[exception])
                    if state == "missing":
                        source.unlink()
                    else:
                        source.write_bytes(b"mutated upstream evidence\n")

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual(
                        "FAIL_EXCEPTION_EVIDENCE", result.components[0]["verdict"]
                    )
                    self.assertTrue(
                        any(
                            "upstream source" in error
                            for error in result.components[0][
                                "technicalEvidenceVerification"
                            ]["errors"]
                        )
                    )

    def test_jar_class_count_and_annotation_flag_drift_fail(self) -> None:
        for label, class_count, access_flags, error_fragment in (
            ("class_count", 9, 0x2601, "class-count drift"),
            ("annotation_flag", 10, 0x0601, "missing ACC_ANNOTATION"),
        ):
            with self.subTest(label=label):
                temporary, lock, cache, policy = self._fixture()
                with temporary:
                    _, _, _, _, exception = self._bound_exception_fixture(
                        lock,
                        cache,
                        policy,
                        class_count=class_count,
                        access_flags=access_flags,
                    )
                    _write_policy(policy, exceptions=[exception])

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual(
                        "FAIL_EXCEPTION_EVIDENCE", result.components[0]["verdict"]
                    )
                    self.assertTrue(
                        any(
                            error_fragment in error
                            for error in result.components[0][
                                "technicalEvidenceVerification"
                            ]["errors"]
                        )
                    )

    def test_malformed_and_unknown_classfiles_fail(self) -> None:
        for label, kwargs, error_fragment in (
            ("unknown", {"unknown_constant_tag": True}, "unknown constant-pool tag"),
            ("truncated", {"truncate_class": True}, "truncated"),
        ):
            with self.subTest(label=label):
                temporary, lock, cache, policy = self._fixture()
                with temporary:
                    _, _, _, _, exception = self._bound_exception_fixture(
                        lock,
                        cache,
                        policy,
                        **kwargs,
                    )
                    _write_policy(policy, exceptions=[exception])

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual(
                        "FAIL_EXCEPTION_EVIDENCE", result.components[0]["verdict"]
                    )
                    self.assertTrue(
                        any(
                            error_fragment in error
                            for error in result.components[0][
                                "technicalEvidenceVerification"
                            ]["errors"]
                        )
                    )

    def test_jar_hash_and_class_parse_use_the_same_byte_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            coordinate = "example:snapshot:1.0"
            snapshot_jar = _write_main_jar(
                root / "snapshot-cache",
                coordinate,
                class_prefix="snapshot/Annotation",
            )
            target_cache = root / "target-cache"
            target_jar = _write_main_jar(
                target_cache,
                coordinate,
                class_prefix="alternate/Annotation",
            )
            snapshot_bytes = snapshot_jar.read_bytes()
            technical = TechnicalEvidence(
                "0" * 64,
                hashlib.sha256(snapshot_bytes).hexdigest().upper(),
                10,
                ("ACC_ANNOTATION", "ACC_INTERFACE"),
                (),
            )

            with mock.patch.object(Path, "read_bytes", return_value=snapshot_bytes):
                observed, errors = _verify_annotation_jar(
                    target_jar,
                    target_cache,
                    technical,
                )

            self.assertEqual((), errors)
            self.assertTrue(
                all(
                    item["name"].startswith("snapshot/Annotation")
                    for item in observed["classes"]
                )
            )

    def test_exception_declaration_and_coordinate_drift_fail_at_policy_load(self) -> None:
        temporary, _, _, policy = self._fixture()
        with temporary:
            for label, exception in (
                (
                    "licenses",
                    _exception(CHECKER_EXCEPTION_COORDINATE, [MIT]),
                ),
                (
                    "coordinate",
                    _exception("example:stale:9.9", CHECKER_LICENSE_VALUES),
                ),
            ):
                with self.subTest(label=label):
                    _write_policy(policy, exceptions=[exception])
                    with self.assertRaises(AuditInputError):
                        load_policy(policy)

    def test_inventory_is_sorted_by_coordinate_and_license(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            _write_lock(
                lock,
                [
                    ("z.group:zeta:1.0", "releaseRuntimeClasspath"),
                    ("a.group:alpha:1.0", "releaseRuntimeClasspath"),
                ],
            )
            _write_pom(cache, "z.group:zeta:1.0", licenses=[MIT, APACHE])
            _write_pom(cache, "a.group:alpha:1.0", licenses=[APACHE])

            inventory = audit_runtime_licenses(lock, cache, policy).inventory()

            self.assertEqual(
                ["a.group:alpha:1.0", "z.group:zeta:1.0"],
                [item["coordinate"] for item in inventory["components"]],
            )
            self.assertEqual(
                sorted(item["name"] for item in inventory["components"][1]["licenses"]),
                [item["name"] for item in inventory["components"][1]["licenses"]],
            )

    def test_multiple_cached_pom_candidates_are_ambiguous(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = "example:ambiguous:1.0"
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            _write_pom(cache, coordinate, licenses=[APACHE], hash_directory="hash-a")
            _write_pom(cache, coordinate, licenses=[MIT], hash_directory="hash-b")

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("AMBIGUOUS_POM", result.components[0]["declarationState"])
            self.assertEqual("FAIL_AMBIGUOUS_POM", result.components[0]["verdict"])

    def test_duplicate_license_containers_and_fields_are_invalid(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            for coordinate, duplicate_xml in (
                (
                    "example:duplicate-container:1.0",
                    "<licenses><license><name>Forbidden Fixture License</name>"
                    "<url>https://invalid.example/forbidden</url></license></licenses>",
                ),
                (
                    "example:duplicate-field:1.0",
                    "<name>Forbidden Fixture License</name>"
                    "<url>https://invalid.example/forbidden</url>",
                ),
            ):
                with self.subTest(coordinate=coordinate):
                    _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
                    pom = _write_pom(cache, coordinate, licenses=[APACHE])
                    text = pom.read_text(encoding="utf-8")
                    if "container" in coordinate:
                        text = text.replace("</project>", duplicate_xml + "</project>")
                    else:
                        text = text.replace("</license>", duplicate_xml + "</license>")
                    pom.write_text(text, encoding="utf-8")

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual("INVALID_POM", result.components[0]["declarationState"])
                    self.assertEqual("FAIL_INVALID_POM", result.components[0]["verdict"])

    def test_nested_markup_in_scalar_license_fields_is_invalid(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            for coordinate, field, replacement in (
                (
                    "example:nested-license-name:1.0",
                    "name",
                    "Apache License, Version 2.0<hidden>GPL</hidden>",
                ),
                (
                    "example:nested-license-url:1.0",
                    "url",
                    "https://www.apache.org/<hidden>licenses/GPL-2.0</hidden>",
                ),
            ):
                with self.subTest(field=field):
                    _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
                    pom = _write_pom(cache, coordinate, licenses=[APACHE])
                    text = pom.read_text(encoding="utf-8")
                    original = APACHE[0] if field == "name" else APACHE[1]
                    pom.write_text(
                        text.replace(f"<{field}>{original}</{field}>", f"<{field}>{replacement}</{field}>"),
                        encoding="utf-8",
                    )

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual("INVALID_POM", result.components[0]["declarationState"])
                    self.assertEqual("FAIL_INVALID_POM", result.components[0]["verdict"])

    def test_foreign_namespace_root_and_license_fields_are_invalid(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            for coordinate, pom_xml in (
                (
                    "example:foreign-root:1.0",
                    "<project xmlns=\"urn:evil\"><modelVersion>4.0.0</modelVersion>"
                    "<licenses><license><name>Apache License, Version 2.0</name>"
                    "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>"
                    "</license></licenses></project>",
                ),
                (
                    "example:foreign-license:1.0",
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" "
                    "xmlns:evil=\"urn:evil\"><modelVersion>4.0.0</modelVersion>"
                    "<evil:licenses><evil:license>"
                    "<evil:name>Apache License, Version 2.0</evil:name>"
                    "<evil:url>https://www.apache.org/licenses/LICENSE-2.0.txt</evil:url>"
                    "</evil:license></evil:licenses></project>",
                ),
            ):
                with self.subTest(coordinate=coordinate):
                    _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
                    pom = _write_pom(cache, coordinate, licenses=[APACHE])
                    pom.write_text(pom_xml, encoding="utf-8")

                    result = audit_runtime_licenses(lock, cache, policy)

                    self.assertFalse(result.ok)
                    self.assertEqual("INVALID_POM", result.components[0]["declarationState"])
                    self.assertEqual("FAIL_INVALID_POM", result.components[0]["verdict"])

    def test_unqualified_legacy_pom_is_explicitly_supported(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = "example:legacy:1.0"
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            pom = _write_pom(cache, coordinate, licenses=[APACHE])
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    ' xmlns="http://maven.apache.org/POM/4.0.0"', ""
                ),
                encoding="utf-8",
            )

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertTrue(result.ok)
            self.assertEqual("ALLOW_DECLARED", result.components[0]["verdict"])

    def test_utf16_doctype_and_internal_entities_are_rejected(self) -> None:
        temporary, lock, cache, policy = self._fixture()
        with temporary:
            coordinate = "example:utf16-doctype:1.0"
            _write_lock(lock, [(coordinate, "releaseRuntimeClasspath")])
            pom = _write_pom(cache, coordinate, licenses=[APACHE])
            pom.write_bytes(
                (
                    '<?xml version="1.0" encoding="UTF-16"?>'
                    '<!DOCTYPE project ['
                    '<!ENTITY licenseName "Apache License, Version 2.0">'
                    '<!ENTITY licenseUrl "https://www.apache.org/licenses/LICENSE-2.0.txt">'
                    ']>'
                    '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                    '<modelVersion>4.0.0</modelVersion>'
                    '<groupId>example</groupId><artifactId>utf16-doctype</artifactId>'
                    '<version>1.0</version><licenses><license>'
                    '<name>&licenseName;</name><url>&licenseUrl;</url>'
                    '</license></licenses></project>'
                ).encode("utf-16")
            )

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("INVALID_POM", result.components[0]["declarationState"])
            self.assertEqual("FAIL_INVALID_POM", result.components[0]["verdict"])


class LockAndPolicyParsingTest(unittest.TestCase):
    def test_legacy_policy_schema_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "policy.json"
            _write_policy(policy)
            value = json.loads(policy.read_text(encoding="utf-8"))
            value["schemaVersion"] = 1
            policy.write_text(json.dumps(value), encoding="utf-8")

            with self.assertRaises(AuditInputError):
                load_policy(policy)

    def test_lock_parser_selects_exact_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "gradle.lockfile"
            _write_lock(
                lock,
                [
                    ("example:runtime:1.0", "releaseRuntimeClasspath"),
                    ("example:not-runtime:1.0", "debugRuntimeClasspath"),
                ],
            )

            selection = parse_lockfile(lock, "releaseRuntimeClasspath")

            self.assertEqual((Coordinate.parse("example:runtime:1.0"),), selection.coordinates)

    def test_malformed_or_empty_target_lock_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "gradle.lockfile"
            lock.write_text("not-a-coordinate=releaseRuntimeClasspath\n", encoding="utf-8")
            with self.assertRaises(AuditInputError):
                parse_lockfile(lock, "releaseRuntimeClasspath")
            lock.write_text("empty=releaseRuntimeClasspath\n", encoding="utf-8")
            with self.assertRaises(AuditInputError):
                parse_lockfile(lock, "releaseRuntimeClasspath")

    def test_path_like_lock_and_parent_coordinates_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock = root / "gradle.lockfile"
            lock.write_text("..:outside:1=releaseRuntimeClasspath\n", encoding="utf-8")
            with self.assertRaises(AuditInputError):
                parse_lockfile(lock, "releaseRuntimeClasspath")

            cache = root / "cache"
            policy = root / "policy.json"
            child = "example:child:1.0"
            _write_policy(policy)
            _write_lock(lock, [(child, "releaseRuntimeClasspath")])
            pom = _write_pom(cache, child, parent="example:parent:1.0")
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<parent><groupId>example</groupId>",
                    "<parent><groupId>..</groupId>",
                ),
                encoding="utf-8",
            )

            result = audit_runtime_licenses(lock, cache, policy)

            self.assertFalse(result.ok)
            self.assertEqual("AMBIGUOUS_PARENT", result.components[0]["declarationState"])
            self.assertEqual("FAIL_AMBIGUOUS_PARENT", result.components[0]["verdict"])

    def test_wildcard_exception_coordinate_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "policy.json"
            for coordinate in ("example:*:1.0", "example:artifact:1.+"):
                with self.subTest(coordinate=coordinate):
                    _write_policy(
                        policy,
                        exceptions=[_exception(coordinate, [GPL_CLASSPATH])],
                    )
                    with self.assertRaises(AuditInputError):
                        load_policy(policy)

    def test_exception_missing_required_review_evidence_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "policy.json"
            exception = _exception("example:gpl:1.0", [GPL_CLASSPATH])
            del exception["reviewer"]
            _write_policy(policy, exceptions=[exception])

            with self.assertRaises(AuditInputError):
                load_policy(policy)

    def test_policy_cannot_drift_to_a_non_release_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "policy.json"
            _write_policy(policy)
            value = json.loads(policy.read_text(encoding="utf-8"))
            value["configuration"] = "debugRuntimeClasspath"
            policy.write_text(json.dumps(value), encoding="utf-8")

            with self.assertRaises(AuditInputError):
                load_policy(policy)

    def test_duplicate_canonical_id_rules_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "policy.json"
            _write_policy(policy)
            value = json.loads(policy.read_text(encoding="utf-8"))
            value["rules"][1]["canonicalId"] = value["rules"][0]["canonicalId"]
            value["rules"][1]["verdict"] = "DISALLOWED"
            policy.write_text(json.dumps(value), encoding="utf-8")

            with self.assertRaises(AuditInputError):
                load_policy(policy)


if __name__ == "__main__":
    unittest.main()
