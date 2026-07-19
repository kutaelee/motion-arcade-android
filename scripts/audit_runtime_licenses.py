#!/usr/bin/env python3
"""Audit locked runtime coordinates against locally cached Maven POM licenses.

This tool is deliberately offline and fail-closed.  A POM declaration is metadata,
not legal advice or proof that redistribution is permitted.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from xml.parsers import expat
from dataclasses import dataclass
from datetime import date
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Iterable
from urllib.parse import urlsplit


INVENTORY_SCHEMA_VERSION = 2
DEFAULT_CONFIGURATION = "releaseRuntimeClasspath"
MAVEN_POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
REQUIRED_ANNOTATION_FLAGS = ("ACC_ANNOTATION", "ACC_INTERFACE")
REQUIRED_ANNOTATION_MASK = 0x2000 | 0x0200
MAX_CLASS_FILE_BYTES = 16 * 1024 * 1024
MIN_SUPPORTED_CLASS_MAJOR = 45
MAX_SUPPORTED_CLASS_MAJOR = 70
CHECKER_EXCEPTION_COORDINATE = "org.checkerframework:checker-compat-qual:2.5.3"
CHECKER_EXCEPTION_POM_SHA256 = (
    "F7FCDAC99EB33D169F6D52A35541C22FFD14F458ABBCF56F9C49EE1486598C9C"
)
CHECKER_EXCEPTION_JAR_SHA256 = (
    "D76B9AFEA61C7C082908023F0CBC1427FAB9ABD2DF915C8B8A3E7A509BCCBC6D"
)
CHECKER_EXCEPTION_CLASS_COUNT = 10
CHECKER_EXCEPTION_SOURCE_PATH = (
    "docs/supply-chain/licenses/checker-framework-2.5.3-LICENSE.txt"
)
CHECKER_EXCEPTION_SOURCE_SHA256 = (
    "37AC781FD633D592519CC231473B9A25EBD84C3BBE829ABB8F588D3355EDC63C"
)
CHECKER_EXCEPTION_SOURCE_URL = (
    "https://raw.githubusercontent.com/typetools/checker-framework/"
    "checker-framework-2.5.3/LICENSE.txt"
)
CHECKER_EXCEPTION_SOURCE_URL_SHA256 = (
    "FA90322DD81AE07B90F8E29E4AF3D851DA779A90F15D32BFE5D5E1D1BF25A991"
)
CHECKER_EXCEPTION_LICENSES = (
    (
        "GNU General Public License, version 2 (GPL2), with the classpath exception",
        "http://www.gnu.org/software/classpath/license.html",
    ),
    ("The MIT License", "http://opensource.org/licenses/MIT"),
)
CHECKER_EXCEPTION_STATUS = "TECHNICAL_EVIDENCE_ACCEPTED"
CHECKER_EXCEPTION_EVIDENCE = (
    "Technical metadata exception only: exact cached artifacts, annotation-interface "
    "class flags, and the retained official 2.5.3 tagged LICENSE are verified offline. "
    "This record is not legal advice or redistribution approval."
)
CHECKER_EXCEPTION_REVIEWER = "Codex project technical evidence review (not legal counsel)"
CHECKER_EXCEPTION_REVIEW_DATE = "2026-07-14"
POM_METADATA_CAVEAT = (
    "Maven POM license declarations are unverified metadata, not legal approval, "
    "license-text completeness, or proof of commercial redistribution permission."
)
TECHNICAL_EVIDENCE_CAVEAT = (
    "Exact artifact hashes, classfile annotation-interface flags, and retained upstream "
    "text were verified offline; this is technical evidence only, not legal advice or "
    "redistribution approval."
)
COORDINATE_RE = re.compile(
    r"^(?P<group>[A-Za-z0-9_.-]+):"
    r"(?P<artifact>[A-Za-z0-9_.-]+):"
    r"(?P<version>[A-Za-z0-9_.\-]+)$"
)
WILDCARD_MARKERS = ("*", "?", "[", "]", "{", "}")
PASS_VERDICTS = {"ALLOW_DECLARED", "EXCEPTION_TECHNICALLY_VERIFIED"}
FAILURE_STATES = {
    "MISSING_POM": "FAIL_MISSING_POM",
    "AMBIGUOUS_POM": "FAIL_AMBIGUOUS_POM",
    "CACHE_ESCAPE": "FAIL_CACHE_ESCAPE",
    "INVALID_POM": "FAIL_INVALID_POM",
    "PARENT_CYCLE": "FAIL_PARENT_CYCLE",
    "AMBIGUOUS_PARENT": "FAIL_AMBIGUOUS_PARENT",
    "EMPTY": "FAIL_EMPTY",
    "UNDECLARED": "FAIL_UNDECLARED",
    "AMBIGUOUS": "FAIL_AMBIGUOUS",
}


class AuditInputError(ValueError):
    """Raised when the lock or policy cannot be interpreted safely."""


@dataclass(frozen=True, order=True)
class Coordinate:
    group: str
    artifact: str
    version: str

    @classmethod
    def parse(cls, value: str) -> "Coordinate":
        match = COORDINATE_RE.fullmatch(value)
        if match is None or any(marker in value for marker in WILDCARD_MARKERS):
            raise AuditInputError(f"invalid exact Maven coordinate: {value!r}")
        parts = (match.group("group"), match.group("artifact"), match.group("version"))
        if any(part in {".", ".."} for part in parts):
            raise AuditInputError(f"path-like Maven coordinate component rejected: {value!r}")
        return cls(*parts)

    def __str__(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"


@dataclass(frozen=True, order=True)
class LicenseDeclaration:
    name: str
    url: str

    def as_dict(self) -> dict[str, str]:
        return {"name": self.name, "url": self.url}


@dataclass(frozen=True)
class LicenseRule:
    canonical_id: str
    verdict: str
    names: tuple[str, ...]
    urls: tuple[str, ...]


@dataclass(frozen=True)
class UpstreamLicenseSource:
    path: str
    sha256: str
    url: str
    url_sha256: str

    def as_dict(self) -> dict[str, str]:
        return {
            "path": self.path,
            "sha256": self.sha256,
            "url": self.url,
            "urlSha256": self.url_sha256,
        }


@dataclass(frozen=True)
class TechnicalEvidence:
    pom_sha256: str
    jar_sha256: str
    expected_class_count: int
    required_class_access_flags: tuple[str, ...]
    upstream_license_sources: tuple[UpstreamLicenseSource, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "expectedClassCount": self.expected_class_count,
            "jarSha256": self.jar_sha256,
            "pomSha256": self.pom_sha256,
            "requiredClassAccessFlags": list(self.required_class_access_flags),
            "upstreamLicenseSources": [
                source.as_dict() for source in self.upstream_license_sources
            ],
        }


@dataclass(frozen=True)
class LicenseException:
    coordinate: Coordinate
    expected_licenses: tuple[LicenseDeclaration, ...]
    status: str
    evidence: str
    reviewer: str
    review_date: str
    technical_evidence: TechnicalEvidence

    def as_dict(self) -> dict[str, Any]:
        return {
            "coordinate": str(self.coordinate),
            "date": self.review_date,
            "evidence": self.evidence,
            "expectedLicenses": [item.as_dict() for item in self.expected_licenses],
            "reviewer": self.reviewer,
            "status": self.status,
            "technicalEvidence": self.technical_evidence.as_dict(),
        }


@dataclass(frozen=True)
class Policy:
    configuration: str
    rules: tuple[LicenseRule, ...]
    exceptions: tuple[LicenseException, ...]


@dataclass(frozen=True)
class LockSelection:
    coordinates: tuple[Coordinate, ...]
    configuration: str


@dataclass(frozen=True)
class PomResolution:
    state: str
    licenses: tuple[LicenseDeclaration, ...]
    license_source: Coordinate | None
    pom_chain: tuple[dict[str, str], ...]
    caveats: tuple[str, ...]


@dataclass(frozen=True)
class AuditResult:
    configuration: str
    lock_file: str
    components: tuple[dict[str, Any], ...]
    errors: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.errors and all(
            component["verdict"] in PASS_VERDICTS for component in self.components
        )

    def inventory(self) -> dict[str, Any]:
        passed = sum(item["verdict"] in PASS_VERDICTS for item in self.components)
        return {
            "caveat": POM_METADATA_CAVEAT,
            "components": list(self.components),
            "configuration": self.configuration,
            "errors": list(self.errors),
            "lockFile": self.lock_file,
            "schemaVersion": INVENTORY_SCHEMA_VERSION,
            "summary": {
                "coordinateCount": len(self.components),
                "failed": len(self.components) - passed,
                "passed": passed,
                "verdict": "PASS" if self.ok else "FAIL",
            },
        }


def _require_exact_keys(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise AuditInputError(
            f"{context} keys mismatch; missing={missing or 'none'} extra={extra or 'none'}"
        )


def _nonempty_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise AuditInputError(f"{context} must be a non-empty string")
    return value.strip()


def _string_list(value: Any, context: str) -> tuple[str, ...]:
    if not isinstance(value, list):
        raise AuditInputError(f"{context} must be an array")
    result = tuple(_nonempty_string(item, f"{context}[]") for item in value)
    if len(set(result)) != len(result):
        raise AuditInputError(f"{context} contains duplicate aliases")
    return result


def _sha256_value(value: Any, context: str) -> str:
    text = _nonempty_string(value, context)
    if re.fullmatch(r"[0-9A-Fa-f]{64}", text) is None:
        raise AuditInputError(f"{context} must be a 64-character SHA-256 hex digest")
    return text.upper()


def _evidence_path(value: Any, context: str) -> str:
    text = _nonempty_string(value, context)
    if "\\" in text or any(marker in text for marker in WILDCARD_MARKERS):
        raise AuditInputError(f"{context} must be an exact canonical POSIX path")
    posix = PurePosixPath(text)
    windows = PureWindowsPath(text)
    if posix.is_absolute() or windows.is_absolute() or windows.drive:
        raise AuditInputError(f"{context} must be repository-relative")
    if posix.as_posix() != text or any(part in {"", ".", ".."} for part in posix.parts):
        raise AuditInputError(f"{context} contains non-canonical or escaping components")
    if posix.parts[:3] != ("docs", "supply-chain", "licenses") or len(posix.parts) < 4:
        raise AuditInputError(
            f"{context} must identify one file under docs/supply-chain/licenses"
        )
    return text


def _source_url(value: Any, context: str) -> str:
    text = _nonempty_string(value, context)
    try:
        parsed = urlsplit(text)
        port = parsed.port
    except ValueError as exc:
        raise AuditInputError(f"{context} is not a valid source URL: {exc}") from exc
    if (
        parsed.scheme != "https"
        or parsed.hostname != "raw.githubusercontent.com"
        or parsed.username is not None
        or parsed.password is not None
        or port is not None
        or not parsed.path.startswith("/")
        or parsed.query
        or parsed.fragment
        or any(marker in text for marker in WILDCARD_MARKERS)
    ):
        raise AuditInputError(
            f"{context} must be one exact HTTPS raw.githubusercontent.com URL"
        )
    return text


def _parse_technical_evidence(value: Any, context: str) -> TechnicalEvidence:
    if not isinstance(value, dict):
        raise AuditInputError(f"{context} must be an object")
    _require_exact_keys(
        value,
        {
            "pomSha256",
            "jarSha256",
            "expectedClassCount",
            "requiredClassAccessFlags",
            "upstreamLicenseSources",
        },
        context,
    )
    count = value["expectedClassCount"]
    if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 10_000:
        raise AuditInputError(f"{context}.expectedClassCount must be an integer from 1 to 10000")
    flags = _string_list(
        value["requiredClassAccessFlags"],
        f"{context}.requiredClassAccessFlags",
    )
    if flags != REQUIRED_ANNOTATION_FLAGS:
        raise AuditInputError(
            f"{context}.requiredClassAccessFlags must be {list(REQUIRED_ANNOTATION_FLAGS)}"
        )
    sources = value["upstreamLicenseSources"]
    if not isinstance(sources, list) or len(sources) != 1:
        state = "missing" if isinstance(sources, list) and not sources else "ambiguous"
        raise AuditInputError(
            f"{context}.upstreamLicenseSources must contain exactly one source; {state} source evidence"
        )
    source_value = sources[0]
    if not isinstance(source_value, dict):
        raise AuditInputError(f"{context}.upstreamLicenseSources[0] must be an object")
    _require_exact_keys(
        source_value,
        {"path", "sha256", "url", "urlSha256"},
        f"{context}.upstreamLicenseSources[0]",
    )
    url = _source_url(source_value["url"], f"{context}.upstreamLicenseSources[0].url")
    url_sha256 = _sha256_value(
        source_value["urlSha256"],
        f"{context}.upstreamLicenseSources[0].urlSha256",
    )
    observed_url_sha256 = hashlib.sha256(url.encode("utf-8")).hexdigest().upper()
    if observed_url_sha256 != url_sha256:
        raise AuditInputError(
            f"{context}.upstreamLicenseSources[0] URL SHA-256 binding mismatch"
        )
    source = UpstreamLicenseSource(
        _evidence_path(
            source_value["path"],
            f"{context}.upstreamLicenseSources[0].path",
        ),
        _sha256_value(
            source_value["sha256"],
            f"{context}.upstreamLicenseSources[0].sha256",
        ),
        url,
        url_sha256,
    )
    return TechnicalEvidence(
        _sha256_value(value["pomSha256"], f"{context}.pomSha256"),
        _sha256_value(value["jarSha256"], f"{context}.jarSha256"),
        count,
        flags,
        (source,),
    )


def _validate_exact_checker_exception(
    coordinate: Coordinate,
    expected_licenses: tuple[LicenseDeclaration, ...],
    technical: TechnicalEvidence,
    status: str,
    evidence: str,
    reviewer: str,
    review_date: str,
    context: str,
) -> None:
    if str(coordinate) != CHECKER_EXCEPTION_COORDINATE:
        raise AuditInputError(
            f"{context} only supports exact technical exception "
            f"{CHECKER_EXCEPTION_COORDINATE}; got {coordinate}"
        )
    pinned_licenses = tuple(
        sorted(LicenseDeclaration(name, url) for name, url in CHECKER_EXCEPTION_LICENSES)
    )
    if expected_licenses != pinned_licenses:
        raise AuditInputError(f"{context}.expectedLicenses drift from pinned Checker evidence")
    pinned_source = UpstreamLicenseSource(
        CHECKER_EXCEPTION_SOURCE_PATH,
        CHECKER_EXCEPTION_SOURCE_SHA256,
        CHECKER_EXCEPTION_SOURCE_URL,
        CHECKER_EXCEPTION_SOURCE_URL_SHA256,
    )
    pinned_technical = TechnicalEvidence(
        CHECKER_EXCEPTION_POM_SHA256,
        CHECKER_EXCEPTION_JAR_SHA256,
        CHECKER_EXCEPTION_CLASS_COUNT,
        REQUIRED_ANNOTATION_FLAGS,
        (pinned_source,),
    )
    if technical != pinned_technical:
        raise AuditInputError(f"{context}.technicalEvidence drift from pinned Checker evidence")
    narrative = (status, evidence, reviewer, review_date)
    pinned_narrative = (
        CHECKER_EXCEPTION_STATUS,
        CHECKER_EXCEPTION_EVIDENCE,
        CHECKER_EXCEPTION_REVIEWER,
        CHECKER_EXCEPTION_REVIEW_DATE,
    )
    if narrative != pinned_narrative:
        raise AuditInputError(
            f"{context} status/review record drift from pinned technical-only evidence"
        )


def _parse_expected_licenses(value: Any, context: str) -> tuple[LicenseDeclaration, ...]:
    if not isinstance(value, list) or not value:
        raise AuditInputError(f"{context} must be a non-empty array")
    declarations: list[LicenseDeclaration] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise AuditInputError(f"{context}[{index}] must be an object")
        _require_exact_keys(item, {"name", "url"}, f"{context}[{index}]")
        declarations.append(
            LicenseDeclaration(
                _nonempty_string(item["name"], f"{context}[{index}].name"),
                _nonempty_string(item["url"], f"{context}[{index}].url"),
            )
        )
    if len(set(declarations)) != len(declarations):
        raise AuditInputError(f"{context} contains duplicate license declarations")
    return tuple(sorted(declarations))


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise AuditInputError(f"duplicate JSON key rejected: {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise AuditInputError(f"non-standard JSON constant rejected: {value}")


def load_policy(path: Path) -> Policy:
    try:
        raw = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_unique_json_object,
            parse_constant=_reject_json_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AuditInputError(f"cannot read policy {path}: {exc}") from exc
    if not isinstance(raw, dict):
        raise AuditInputError("policy root must be an object")
    _require_exact_keys(
        raw,
        {"schemaVersion", "configuration", "rules", "exceptions"},
        "policy",
    )
    if raw["schemaVersion"] != 2 or isinstance(raw["schemaVersion"], bool):
        raise AuditInputError("policy schemaVersion must be integer 2")
    configuration = _nonempty_string(raw["configuration"], "policy.configuration")
    if configuration != DEFAULT_CONFIGURATION:
        raise AuditInputError(
            f"policy.configuration must be {DEFAULT_CONFIGURATION!r}, got {configuration!r}"
        )
    if not isinstance(raw["rules"], list) or not raw["rules"]:
        raise AuditInputError("policy.rules must be a non-empty array")

    rules: list[LicenseRule] = []
    canonical_ids: set[str] = set()
    name_owners: dict[str, str] = {}
    url_owners: dict[str, str] = {}
    for index, item in enumerate(raw["rules"]):
        if not isinstance(item, dict):
            raise AuditInputError(f"policy.rules[{index}] must be an object")
        _require_exact_keys(
            item,
            {"canonicalId", "verdict", "names", "urls"},
            f"policy.rules[{index}]",
        )
        canonical_id = _nonempty_string(
            item["canonicalId"], f"policy.rules[{index}].canonicalId"
        )
        if canonical_id in canonical_ids:
            raise AuditInputError(f"duplicate canonicalId rule rejected: {canonical_id}")
        canonical_ids.add(canonical_id)
        verdict = _nonempty_string(item["verdict"], f"policy.rules[{index}].verdict")
        if verdict not in {"ALLOW_DECLARED", "MANUAL_REVIEW", "DISALLOWED"}:
            raise AuditInputError(f"unsupported policy verdict for {canonical_id}: {verdict}")
        names = _string_list(item["names"], f"policy.rules[{index}].names")
        urls = _string_list(item["urls"], f"policy.rules[{index}].urls")
        if not names or not urls:
            raise AuditInputError(f"policy rule {canonical_id} needs name and URL aliases")
        for alias in (*names, *urls):
            if any(marker in alias for marker in WILDCARD_MARKERS):
                raise AuditInputError(f"wildcard policy alias rejected for {canonical_id}: {alias}")
        for name in names:
            normalized = _normalize_name(name)
            if normalized in name_owners:
                raise AuditInputError(
                    f"license name alias is ambiguous: {name!r} belongs to "
                    f"{name_owners[normalized]} and {canonical_id}"
                )
            name_owners[normalized] = canonical_id
        for url in urls:
            normalized = _normalize_url(url)
            if normalized in url_owners:
                raise AuditInputError(
                    f"license URL alias is ambiguous: {url!r} belongs to "
                    f"{url_owners[normalized]} and {canonical_id}"
                )
            url_owners[normalized] = canonical_id
        rules.append(LicenseRule(canonical_id, verdict, names, urls))

    if not isinstance(raw["exceptions"], list):
        raise AuditInputError("policy.exceptions must be an array")
    exceptions: list[LicenseException] = []
    seen_exception_coordinates: set[Coordinate] = set()
    for index, item in enumerate(raw["exceptions"]):
        if not isinstance(item, dict):
            raise AuditInputError(f"policy.exceptions[{index}] must be an object")
        _require_exact_keys(
            item,
            {
                "coordinate",
                "expectedLicenses",
                "status",
                "evidence",
                "reviewer",
                "date",
                "technicalEvidence",
            },
            f"policy.exceptions[{index}]",
        )
        coordinate_text = _nonempty_string(
            item["coordinate"], f"policy.exceptions[{index}].coordinate"
        )
        coordinate = Coordinate.parse(coordinate_text)
        if coordinate in seen_exception_coordinates:
            raise AuditInputError(f"duplicate policy exception: {coordinate}")
        seen_exception_coordinates.add(coordinate)
        status = _nonempty_string(item["status"], f"policy.exceptions[{index}].status")
        if status not in {"TECHNICAL_EVIDENCE_ACCEPTED", "MANUAL_REVIEW", "REJECTED"}:
            raise AuditInputError(f"unsupported exception status for {coordinate}: {status}")
        review_date = _nonempty_string(item["date"], f"policy.exceptions[{index}].date")
        try:
            parsed_date = date.fromisoformat(review_date)
        except ValueError as exc:
            raise AuditInputError(f"invalid exception date for {coordinate}: {review_date}") from exc
        if parsed_date.isoformat() != review_date:
            raise AuditInputError(f"exception date must use YYYY-MM-DD: {review_date}")
        evidence = _nonempty_string(
            item["evidence"], f"policy.exceptions[{index}].evidence"
        )
        reviewer = _nonempty_string(
            item["reviewer"], f"policy.exceptions[{index}].reviewer"
        )
        expected_licenses = _parse_expected_licenses(
            item["expectedLicenses"],
            f"policy.exceptions[{index}].expectedLicenses",
        )
        technical_evidence = _parse_technical_evidence(
            item["technicalEvidence"],
            f"policy.exceptions[{index}].technicalEvidence",
        )
        _validate_exact_checker_exception(
            coordinate,
            expected_licenses,
            technical_evidence,
            status,
            evidence,
            reviewer,
            review_date,
            f"policy.exceptions[{index}]",
        )
        exceptions.append(
            LicenseException(
                coordinate,
                expected_licenses,
                status,
                evidence,
                reviewer,
                review_date,
                technical_evidence,
            )
        )
    return Policy(configuration, tuple(rules), tuple(sorted(exceptions, key=lambda x: x.coordinate)))


def parse_lockfile(path: Path, configuration: str) -> LockSelection:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise AuditInputError(f"cannot read lock file {path}: {exc}") from exc
    coordinates: list[Coordinate] = []
    target_seen = False
    explicit_empty = False
    for line_number, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise AuditInputError(f"malformed lock line {line_number}: missing '='")
        left, right = line.split("=", 1)
        if not left or not right:
            raise AuditInputError(f"malformed lock line {line_number}: empty side")
        configurations = right.split(",")
        if any(not item or item.strip() != item for item in configurations):
            raise AuditInputError(f"malformed lock configurations on line {line_number}")
        if left == "empty":
            if configuration in configurations:
                target_seen = True
                explicit_empty = True
            continue
        coordinate = Coordinate.parse(left)
        if configuration in configurations:
            target_seen = True
            coordinates.append(coordinate)
    if not target_seen:
        raise AuditInputError(f"configuration is absent from lock file: {configuration}")
    if explicit_empty and coordinates:
        raise AuditInputError(f"configuration is both empty and populated: {configuration}")
    if explicit_empty or not coordinates:
        raise AuditInputError(f"configuration has no locked external coordinates: {configuration}")
    if len(set(coordinates)) != len(coordinates):
        duplicates = sorted(str(item) for item in coordinates if coordinates.count(item) > 1)
        raise AuditInputError(f"duplicate locked coordinates: {sorted(set(duplicates))}")
    return LockSelection(tuple(sorted(coordinates)), configuration)


def _local_name(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _namespace(element: ET.Element) -> str:
    if element.tag.startswith("{") and "}" in element.tag:
        return element.tag[1:].split("}", 1)[0]
    return ""


def _qualified_name(namespace: str, name: str) -> str:
    return f"{{{namespace}}}{name}" if namespace else name


def _direct_child(element: ET.Element, name: str) -> ET.Element | None:
    expected = _qualified_name(_namespace(element), name)
    return next((child for child in list(element) if child.tag == expected), None)


def _direct_children(element: ET.Element, name: str) -> list[ET.Element]:
    expected = _qualified_name(_namespace(element), name)
    return [child for child in list(element) if child.tag == expected]


def _child_text(element: ET.Element, name: str) -> str:
    child = _direct_child(element, name)
    return (child.text or "").strip() if child is not None else ""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _cached_pom(
    coordinate: Coordinate, cache_root: Path
) -> tuple[Path | None, str | None, tuple[str, ...]]:
    cache_root = cache_root.resolve()
    coordinate_dir = (
        cache_root / coordinate.group / coordinate.artifact / coordinate.version
    ).resolve()
    try:
        coordinate_dir.relative_to(cache_root)
    except ValueError:
        return None, "CACHE_ESCAPE", (
            f"coordinate path escapes Maven cache for {coordinate}",
        )
    candidates: list[Path] = []
    for candidate in coordinate_dir.glob("*/*.pom"):
        resolved = candidate.resolve()
        try:
            resolved.relative_to(cache_root)
        except ValueError:
            return None, "CACHE_ESCAPE", (
                f"cached POM resolves outside Maven cache for {coordinate}",
            )
        candidates.append(resolved)
    candidates.sort(key=lambda item: item.relative_to(cache_root).as_posix())
    if not candidates:
        return None, "MISSING_POM", (f"cached POM missing for {coordinate}",)
    if len(candidates) != 1:
        paths = tuple(item.relative_to(cache_root).as_posix() for item in candidates)
        return None, "AMBIGUOUS_POM", (
            f"multiple cached POM candidates for {coordinate}: {', '.join(paths)}",
        )
    return candidates[0], None, ()


def _cached_main_jar(
    coordinate: Coordinate, cache_root: Path
) -> tuple[Path | None, str | None, tuple[str, ...]]:
    cache_root = cache_root.resolve()
    coordinate_dir = (
        cache_root / coordinate.group / coordinate.artifact / coordinate.version
    ).resolve()
    try:
        coordinate_dir.relative_to(cache_root)
    except ValueError:
        return None, "CACHE_ESCAPE", (
            f"coordinate path escapes Maven cache for {coordinate}",
        )
    expected_name = f"{coordinate.artifact}-{coordinate.version}.jar"
    candidates: list[Path] = []
    for candidate in coordinate_dir.glob(f"*/{expected_name}"):
        resolved = candidate.resolve()
        try:
            resolved.relative_to(cache_root)
        except ValueError:
            return None, "CACHE_ESCAPE", (
                f"cached JAR resolves outside Maven cache for {coordinate}",
            )
        candidates.append(resolved)
    candidates.sort(key=lambda item: item.relative_to(cache_root).as_posix())
    if not candidates:
        return None, "MISSING_JAR", (f"cached main JAR missing for {coordinate}",)
    if len(candidates) != 1:
        paths = tuple(item.relative_to(cache_root).as_posix() for item in candidates)
        return None, "AMBIGUOUS_JAR", (
            f"multiple cached main JAR candidates for {coordinate}: {', '.join(paths)}",
        )
    return candidates[0], None, ()


class ClassFileError(ValueError):
    """Raised when a class entry is structurally invalid or unsupported."""


class _ClassReader:
    def __init__(self, data: bytes, label: str) -> None:
        self._data = memoryview(data)
        self._label = label
        self.position = 0

    @property
    def remaining(self) -> int:
        return len(self._data) - self.position

    def read(self, size: int, context: str) -> bytes:
        if size < 0 or self.position + size > len(self._data):
            raise ClassFileError(f"{self._label}: truncated {context}")
        start = self.position
        self.position += size
        return bytes(self._data[start : start + size])

    def u1(self, context: str) -> int:
        return self.read(1, context)[0]

    def u2(self, context: str) -> int:
        return struct.unpack(">H", self.read(2, context))[0]

    def u4(self, context: str) -> int:
        return struct.unpack(">I", self.read(4, context))[0]


def _validate_modified_utf8(data: bytes, label: str) -> None:
    index = 0
    while index < len(data):
        first = data[index]
        if first == 0:
            raise ClassFileError(f"{label}: raw NUL is invalid modified UTF-8")
        if first <= 0x7F:
            index += 1
            continue
        if first & 0xE0 == 0xC0:
            if index + 1 >= len(data) or data[index + 1] & 0xC0 != 0x80:
                raise ClassFileError(f"{label}: malformed two-byte modified UTF-8")
            value = ((first & 0x1F) << 6) | (data[index + 1] & 0x3F)
            if value < 0x80 and value != 0:
                raise ClassFileError(f"{label}: overlong modified UTF-8 sequence")
            index += 2
            continue
        if first & 0xF0 == 0xE0:
            if (
                index + 2 >= len(data)
                or data[index + 1] & 0xC0 != 0x80
                or data[index + 2] & 0xC0 != 0x80
            ):
                raise ClassFileError(f"{label}: malformed three-byte modified UTF-8")
            value = (
                ((first & 0x0F) << 12)
                | ((data[index + 1] & 0x3F) << 6)
                | (data[index + 2] & 0x3F)
            )
            if value < 0x800:
                raise ClassFileError(f"{label}: overlong modified UTF-8 sequence")
            index += 3
            continue
        raise ClassFileError(f"{label}: unsupported modified UTF-8 leading byte")


def _parse_annotation_class(data: bytes, entry_name: str) -> dict[str, Any]:
    if len(data) > MAX_CLASS_FILE_BYTES:
        raise ClassFileError(f"{entry_name}: class file exceeds size limit")
    reader = _ClassReader(data, entry_name)
    if reader.u4("magic") != 0xCAFEBABE:
        raise ClassFileError(f"{entry_name}: invalid classfile magic")
    minor = reader.u2("minor_version")
    major = reader.u2("major_version")
    if not MIN_SUPPORTED_CLASS_MAJOR <= major <= MAX_SUPPORTED_CLASS_MAJOR:
        raise ClassFileError(f"{entry_name}: unsupported classfile major version {major}")
    if major >= 56 and minor not in {0, 0xFFFF}:
        raise ClassFileError(f"{entry_name}: unsupported classfile minor version {minor}")

    constant_pool_count = reader.u2("constant_pool_count")
    if constant_pool_count < 2:
        raise ClassFileError(f"{entry_name}: invalid constant_pool_count")
    entries: list[tuple[int, tuple[Any, ...]] | None] = [None] * constant_pool_count
    index = 1
    while index < constant_pool_count:
        tag = reader.u1(f"constant_pool[{index}].tag")
        if tag == 1:
            length = reader.u2(f"constant_pool[{index}].utf8_length")
            raw = reader.read(length, f"constant_pool[{index}].utf8")
            _validate_modified_utf8(raw, f"{entry_name}: constant_pool[{index}]")
            entries[index] = (tag, (raw,))
        elif tag in {3, 4}:
            reader.read(4, f"constant_pool[{index}].number")
            entries[index] = (tag, ())
        elif tag in {5, 6}:
            if index + 1 >= constant_pool_count:
                raise ClassFileError(f"{entry_name}: wide constant at invalid final slot")
            reader.read(8, f"constant_pool[{index}].wide_number")
            entries[index] = (tag, ())
            index += 2
            continue
        elif tag in {7, 8, 16, 19, 20}:
            entries[index] = (tag, (reader.u2(f"constant_pool[{index}].index"),))
        elif tag in {9, 10, 11, 12, 17, 18}:
            entries[index] = (
                tag,
                (
                    reader.u2(f"constant_pool[{index}].index1"),
                    reader.u2(f"constant_pool[{index}].index2"),
                ),
            )
        elif tag == 15:
            entries[index] = (
                tag,
                (
                    reader.u1(f"constant_pool[{index}].reference_kind"),
                    reader.u2(f"constant_pool[{index}].reference_index"),
                ),
            )
        else:
            raise ClassFileError(f"{entry_name}: unknown constant-pool tag {tag}")
        index += 1

    def require_entry(cp_index: int, tags: set[int], context: str) -> tuple[int, tuple[Any, ...]]:
        if cp_index <= 0 or cp_index >= constant_pool_count:
            raise ClassFileError(f"{entry_name}: invalid constant-pool index for {context}")
        entry = entries[cp_index]
        if entry is None or entry[0] not in tags:
            raise ClassFileError(f"{entry_name}: wrong constant-pool type for {context}")
        return entry

    for cp_index, entry in enumerate(entries):
        if entry is None:
            continue
        tag, values = entry
        if tag in {7, 8, 16, 19, 20}:
            require_entry(values[0], {1}, f"constant_pool[{cp_index}]")
        elif tag in {9, 10, 11}:
            require_entry(values[0], {7}, f"constant_pool[{cp_index}].class")
            require_entry(values[1], {12}, f"constant_pool[{cp_index}].name_and_type")
        elif tag == 12:
            require_entry(values[0], {1}, f"constant_pool[{cp_index}].name")
            require_entry(values[1], {1}, f"constant_pool[{cp_index}].descriptor")
        elif tag == 15:
            kind, reference = values
            if not 1 <= kind <= 9:
                raise ClassFileError(f"{entry_name}: invalid method-handle reference kind")
            if kind <= 4:
                allowed = {9}
            elif kind in {5, 8}:
                allowed = {10}
            elif kind in {6, 7}:
                allowed = {10, 11} if major >= 52 else {10}
            else:
                allowed = {11}
            require_entry(reference, allowed, f"constant_pool[{cp_index}].reference")
        elif tag in {17, 18}:
            minimum_major = 55 if tag == 17 else 51
            if major < minimum_major:
                raise ClassFileError(f"{entry_name}: constant-pool tag {tag} before supported version")
            require_entry(values[1], {12}, f"constant_pool[{cp_index}].name_and_type")
        if tag in {15, 16} and major < 51:
            raise ClassFileError(f"{entry_name}: constant-pool tag {tag} before supported version")
        if tag in {19, 20} and major < 53:
            raise ClassFileError(f"{entry_name}: constant-pool tag {tag} before supported version")

    def class_name(cp_index: int, context: str) -> bytes:
        class_entry = require_entry(cp_index, {7}, context)
        utf8_entry = require_entry(class_entry[1][0], {1}, f"{context}.name")
        return utf8_entry[1][0]

    access_flags = reader.u2("access_flags")
    known_class_flags = 0xF631
    if access_flags & ~known_class_flags:
        raise ClassFileError(f"{entry_name}: unknown class access flag bits")
    if access_flags & REQUIRED_ANNOTATION_MASK != REQUIRED_ANNOTATION_MASK:
        raise ClassFileError(
            f"{entry_name}: missing ACC_ANNOTATION and/or ACC_INTERFACE"
        )
    if not access_flags & 0x0400 or access_flags & (0x0010 | 0x0020 | 0x4000 | 0x8000):
        raise ClassFileError(f"{entry_name}: invalid annotation-interface class flags")

    this_name = class_name(reader.u2("this_class"), "this_class")
    expected_name = entry_name[:-6].encode("ascii", errors="strict")
    if this_name != expected_name:
        raise ClassFileError(f"{entry_name}: class name does not match JAR entry")
    if class_name(reader.u2("super_class"), "super_class") != b"java/lang/Object":
        raise ClassFileError(f"{entry_name}: annotation interface must extend Object")
    interfaces_count = reader.u2("interfaces_count")
    interfaces = [
        class_name(reader.u2(f"interfaces[{item}]"), f"interfaces[{item}]")
        for item in range(interfaces_count)
    ]
    if interfaces != [b"java/lang/annotation/Annotation"]:
        raise ClassFileError(
            f"{entry_name}: annotation interface must directly implement java/lang/annotation/Annotation"
        )

    def parse_attributes(context: str) -> None:
        count = reader.u2(f"{context}.attributes_count")
        for attribute_index in range(count):
            require_entry(
                reader.u2(f"{context}.attributes[{attribute_index}].name_index"),
                {1},
                f"{context}.attributes[{attribute_index}].name",
            )
            length = reader.u4(f"{context}.attributes[{attribute_index}].length")
            reader.read(length, f"{context}.attributes[{attribute_index}].info")

    def parse_members(kind: str) -> None:
        count = reader.u2(f"{kind}s_count")
        for member_index in range(count):
            context = f"{kind}s[{member_index}]"
            reader.u2(f"{context}.access_flags")
            require_entry(reader.u2(f"{context}.name_index"), {1}, f"{context}.name")
            require_entry(
                reader.u2(f"{context}.descriptor_index"),
                {1},
                f"{context}.descriptor",
            )
            parse_attributes(context)

    parse_members("field")
    parse_members("method")
    parse_attributes("class")
    if reader.remaining:
        raise ClassFileError(f"{entry_name}: trailing bytes after classfile structure")
    return {
        "accessFlags": ["ACC_ANNOTATION", "ACC_INTERFACE"],
        "majorVersion": major,
        "minorVersion": minor,
        "name": entry_name,
    }


def _verify_annotation_jar(
    jar_path: Path,
    cache_root: Path,
    technical: TechnicalEvidence,
) -> tuple[dict[str, Any], tuple[str, ...]]:
    errors: list[str] = []
    try:
        jar_bytes = jar_path.read_bytes()
    except OSError as exc:
        return {}, (f"cannot read cached JAR: {exc}",)
    observed_sha256 = _sha256(jar_bytes).upper()
    if observed_sha256 != technical.jar_sha256:
        errors.append(
            f"JAR SHA-256 drift: expected {technical.jar_sha256}, observed {observed_sha256}"
        )
    class_results: list[dict[str, Any]] = []
    try:
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(set(names)) != len(names):
                errors.append("JAR contains duplicate entry names")
            class_infos = sorted(
                (info for info in infos if info.filename.endswith(".class")),
                key=lambda info: info.filename,
            )
            if len(class_infos) != technical.expected_class_count:
                errors.append(
                    "JAR class-count drift: expected "
                    f"{technical.expected_class_count}, observed {len(class_infos)}"
                )
            for info in class_infos:
                name = info.filename
                path = PurePosixPath(name)
                if (
                    "\\" in name
                    or not name.isascii()
                    or path.is_absolute()
                    or path.as_posix() != name
                    or any(part in {"", ".", ".."} for part in path.parts)
                ):
                    errors.append(f"non-canonical class entry path: {name!r}")
                    continue
                if info.flag_bits & 0x1:
                    errors.append(f"encrypted class entry is unsupported: {name}")
                    continue
                if info.file_size > MAX_CLASS_FILE_BYTES:
                    errors.append(f"class entry exceeds size limit: {name}")
                    continue
                try:
                    class_results.append(_parse_annotation_class(archive.read(info), name))
                except (ClassFileError, OSError, RuntimeError, zipfile.BadZipFile) as exc:
                    errors.append(str(exc))
    except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
        errors.append(f"invalid cached JAR: {exc}")
    cache_root = cache_root.resolve()
    try:
        cache_path = jar_path.resolve().relative_to(cache_root).as_posix()
    except ValueError:
        cache_path = jar_path.name
        errors.append("cached JAR path escapes Maven cache")
    observed = {
        "cachePath": cache_path,
        "classCount": len(class_results),
        "classes": class_results,
        "requiredClassAccessFlags": list(REQUIRED_ANNOTATION_FLAGS),
        "sha256": observed_sha256,
    }
    return observed, tuple(sorted(set(errors)))


def _verify_upstream_source(
    source: UpstreamLicenseSource,
    repository_root: Path,
) -> tuple[dict[str, str], tuple[str, ...]]:
    errors: list[str] = []
    repository_root = repository_root.resolve()
    unresolved = repository_root / PurePosixPath(source.path)
    resolved = unresolved.resolve()
    try:
        relative = resolved.relative_to(repository_root).as_posix()
    except ValueError:
        return {}, (f"upstream source path escapes repository: {source.path}",)
    if relative != source.path:
        errors.append(
            f"upstream source path resolves ambiguously: expected {source.path}, observed {relative}"
        )
    if not resolved.exists():
        errors.append(f"upstream source file missing: {source.path}")
        observed_sha256 = ""
    elif not resolved.is_file():
        errors.append(f"upstream source is not one regular file: {source.path}")
        observed_sha256 = ""
    else:
        try:
            observed_sha256 = _sha256(resolved.read_bytes()).upper()
        except OSError as exc:
            errors.append(f"cannot read upstream source {source.path}: {exc}")
            observed_sha256 = ""
        if observed_sha256 and observed_sha256 != source.sha256:
            errors.append(
                f"upstream source SHA-256 drift for {source.path}: "
                f"expected {source.sha256}, observed {observed_sha256}"
            )
    observed = {
        "path": source.path,
        "sha256": observed_sha256,
        "url": source.url,
        "urlSha256": source.url_sha256,
    }
    return observed, tuple(sorted(set(errors)))


def _validate_pom_structure(root: ET.Element) -> str | None:
    def scalar_error(element: ET.Element, label: str) -> str | None:
        if list(element):
            return f"POM {label} must be scalar text without child elements"
        return None

    root_namespace = _namespace(root)
    if root_namespace not in {"", MAVEN_POM_NAMESPACE}:
        return f"unsupported POM root namespace: {root_namespace or '(empty)'}"
    if root.tag != _qualified_name(root_namespace, "project"):
        return "POM root must be a supported <project> QName"
    root_fields = {"modelVersion", "parent", "licenses", "groupId", "artifactId", "version"}
    for child in list(root):
        if _local_name(child) in root_fields and _namespace(child) != root_namespace:
            return f"foreign namespace for POM <{_local_name(child)}>"
    model_versions = _direct_children(root, "modelVersion")
    if len(model_versions) != 1:
        return f"POM must contain exactly one modelVersion, found {len(model_versions)}"
    error = scalar_error(model_versions[0], "<modelVersion>")
    if error is not None:
        return error
    if (model_versions[0].text or "").strip() != "4.0.0":
        return "unsupported POM modelVersion"
    for name in ("parent", "licenses", "groupId", "artifactId", "version"):
        count = len(_direct_children(root, name))
        if count > 1:
            return f"duplicate POM <{name}> elements"
    for name in ("groupId", "artifactId", "version"):
        element = _direct_child(root, name)
        if element is not None:
            error = scalar_error(element, f"<{name}>")
            if error is not None:
                return error
    parent = _direct_child(root, "parent")
    if parent is not None:
        for child in list(parent):
            if (
                _local_name(child) in {"groupId", "artifactId", "version"}
                and _namespace(child) != root_namespace
            ):
                return f"foreign namespace for parent <{_local_name(child)}>"
        for name in ("groupId", "artifactId", "version"):
            elements = _direct_children(parent, name)
            if len(elements) > 1:
                return f"duplicate parent <{name}> elements"
            if elements:
                error = scalar_error(elements[0], f"parent <{name}>")
                if error is not None:
                    return error
    licenses = _direct_child(root, "licenses")
    if licenses is not None:
        for child in list(licenses):
            if _local_name(child) == "license" and _namespace(child) != root_namespace:
                return "foreign namespace for POM <license>"
        for index, license_element in enumerate(_direct_children(licenses, "license")):
            for child in list(license_element):
                if (
                    _local_name(child) in {"name", "url"}
                    and _namespace(child) != root_namespace
                ):
                    return f"foreign namespace for license[{index}] <{_local_name(child)}>"
            for name in ("name", "url"):
                elements = _direct_children(license_element, name)
                if len(elements) > 1:
                    return f"duplicate license[{index}] <{name}> elements"
                if elements:
                    error = scalar_error(elements[0], f"license[{index}] <{name}>")
                    if error is not None:
                        return error
    return None


def _parse_pom(path: Path) -> tuple[ET.Element | None, bytes, str | None]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        return None, b"", f"cannot read cached POM: {exc}"
    if b"<!DOCTYPE" in data.upper():
        return None, data, "POM DOCTYPE is rejected"
    preflight = expat.ParserCreate()

    class UnsafePomXml(Exception):
        pass

    def reject_doctype(*_args: object) -> None:
        raise UnsafePomXml

    def reject_external_entity(*_args: object) -> int:
        raise UnsafePomXml

    preflight.StartDoctypeDeclHandler = reject_doctype
    preflight.ExternalEntityRefHandler = reject_external_entity
    try:
        preflight.Parse(data, True)
    except UnsafePomXml:
        return None, data, "POM DOCTYPE or external entity is rejected"
    except expat.ExpatError as exc:
        return None, data, f"invalid POM XML: {exc}"
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        return None, data, f"invalid POM XML: {exc}"
    structure_error = _validate_pom_structure(root)
    if structure_error is not None:
        return None, data, structure_error
    return root, data, None


def _parent_coordinate(root: ET.Element) -> tuple[Coordinate | None, str | None]:
    parent = _direct_child(root, "parent")
    if parent is None:
        return None, None
    parts = tuple(_child_text(parent, name) for name in ("groupId", "artifactId", "version"))
    if any(not item for item in parts):
        return None, "parent coordinate has missing groupId/artifactId/version"
    value = ":".join(parts)
    if "${" in value:
        return None, f"unresolved parent coordinate expression: {value}"
    try:
        return Coordinate.parse(value), None
    except AuditInputError as exc:
        return None, str(exc)


def resolve_pom_licenses(coordinate: Coordinate, cache_root: Path) -> PomResolution:
    cache_root = cache_root.resolve()
    current = coordinate
    visited: set[Coordinate] = set()
    chain: list[dict[str, str]] = []
    depth = 0
    while True:
        if current in visited:
            return PomResolution(
                "PARENT_CYCLE",
                (),
                None,
                tuple(chain),
                (f"POM parent cycle at {current}",),
            )
        visited.add(current)
        pom_path, lookup_state, lookup_caveats = _cached_pom(current, cache_root)
        if lookup_state is not None or pom_path is None:
            return PomResolution(
                lookup_state or "MISSING_POM",
                (),
                None,
                tuple(chain),
                lookup_caveats,
            )
        root, pom_bytes, parse_error = _parse_pom(pom_path)
        chain.append(
            {
                "cachePath": pom_path.relative_to(cache_root).as_posix(),
                "coordinate": str(current),
                "sha256": _sha256(pom_bytes),
            }
        )
        if parse_error is not None or root is None:
            return PomResolution(
                "INVALID_POM", (), None, tuple(chain), (parse_error or "invalid POM",)
            )

        licenses_element = _direct_child(root, "licenses")
        if licenses_element is not None:
            license_nodes = [
                child for child in list(licenses_element) if _local_name(child) == "license"
            ]
            if not license_nodes:
                return PomResolution(
                    "EMPTY",
                    (),
                    current,
                    tuple(chain),
                    (f"explicit empty <licenses> in {current}; parent inheritance not inferred",),
                )
            licenses = tuple(
                sorted(
                    LicenseDeclaration(
                        _child_text(node, "name"),
                        _child_text(node, "url"),
                    )
                    for node in license_nodes
                )
            )
            if any(not item.name and not item.url for item in licenses):
                return PomResolution(
                    "EMPTY",
                    licenses,
                    current,
                    tuple(chain),
                    (f"empty <license> declaration in {current}",),
                )
            if any(not item.name or not item.url for item in licenses):
                return PomResolution(
                    "AMBIGUOUS",
                    licenses,
                    current,
                    tuple(chain),
                    (f"partial license declaration in {current}",),
                )
            locality = "LOCAL" if depth == 0 else "INHERITED"
            cardinality = "MULTIPLE" if len(licenses) > 1 else "SINGLE"
            caveats: tuple[str, ...] = ()
            if len(licenses) > 1:
                caveats = (
                    "multiple POM license declarations retained; license choice or combination is not inferred",
                )
            return PomResolution(
                f"{locality}_{cardinality}",
                licenses,
                current,
                tuple(chain),
                caveats,
            )

        parent, parent_error = _parent_coordinate(root)
        if parent_error is not None:
            return PomResolution(
                "AMBIGUOUS_PARENT", (), None, tuple(chain), (parent_error,)
            )
        if parent is None:
            return PomResolution(
                "UNDECLARED",
                (),
                None,
                tuple(chain),
                (f"no license declaration in {current} or cached parent chain",),
            )
        current = parent
        depth += 1


def _verify_exception_evidence(
    exception: LicenseException,
    resolution: PomResolution,
    cache_root: Path,
    repository_root: Path,
) -> tuple[dict[str, Any], tuple[str, ...]]:
    technical = exception.technical_evidence
    errors: list[str] = []
    pom_observed: dict[str, str] = {}
    if not resolution.pom_chain:
        errors.append(f"exact POM evidence missing for {exception.coordinate}")
    else:
        first = resolution.pom_chain[0]
        observed_coordinate = first.get("coordinate", "")
        observed_sha256 = first.get("sha256", "").upper()
        pom_observed = {
            "cachePath": first.get("cachePath", ""),
            "coordinate": observed_coordinate,
            "sha256": observed_sha256,
        }
        if observed_coordinate != str(exception.coordinate):
            errors.append(
                "exact POM coordinate drift: expected "
                f"{exception.coordinate}, observed {observed_coordinate or '(missing)'}"
            )
        if observed_sha256 != technical.pom_sha256:
            errors.append(
                f"POM SHA-256 drift: expected {technical.pom_sha256}, "
                f"observed {observed_sha256 or '(missing)'}"
            )

    jar_path, jar_state, jar_caveats = _cached_main_jar(exception.coordinate, cache_root)
    if jar_state is not None or jar_path is None:
        jar_observed: dict[str, Any] = {}
        errors.extend(jar_caveats or (jar_state or "cached main JAR unavailable",))
    else:
        jar_observed, jar_errors = _verify_annotation_jar(
            jar_path,
            cache_root,
            technical,
        )
        errors.extend(jar_errors)

    source_observed: list[dict[str, str]] = []
    for source in technical.upstream_license_sources:
        observed, source_errors = _verify_upstream_source(source, repository_root)
        source_observed.append(observed)
        errors.extend(source_errors)

    unique_errors = tuple(sorted(set(errors)))
    return (
        {
            "caveat": TECHNICAL_EVIDENCE_CAVEAT,
            "errors": list(unique_errors),
            "jar": jar_observed,
            "pom": pom_observed,
            "upstreamLicenseSources": source_observed,
            "verdict": "PASS" if not unique_errors else "FAIL",
        },
        unique_errors,
    )


def _normalize_name(value: str) -> str:
    return " ".join(value.split()).casefold()


def _normalize_url(value: str) -> str:
    return value.strip().rstrip("/")


def _rule_maps(policy: Policy) -> tuple[dict[str, LicenseRule], dict[str, LicenseRule]]:
    names: dict[str, LicenseRule] = {}
    urls: dict[str, LicenseRule] = {}
    for rule in policy.rules:
        names.update({_normalize_name(value): rule for value in rule.names})
        urls.update({_normalize_url(value): rule for value in rule.urls})
    return names, urls


def _classify_licenses(
    licenses: Iterable[LicenseDeclaration], policy: Policy
) -> tuple[list[dict[str, Any]], str, tuple[str, ...]]:
    name_rules, url_rules = _rule_maps(policy)
    classified: list[dict[str, Any]] = []
    errors: list[str] = []
    verdicts: list[str] = []
    for license_value in licenses:
        name_rule = name_rules.get(_normalize_name(license_value.name))
        url_rule = url_rules.get(_normalize_url(license_value.url))
        canonical_id: str | None = None
        policy_verdict = "UNCLASSIFIED"
        if name_rule is None or url_rule is None:
            errors.append(
                f"undeclared policy alias for license name={license_value.name!r} "
                f"url={license_value.url!r}"
            )
        elif (
            name_rule.canonical_id != url_rule.canonical_id
            or name_rule.verdict != url_rule.verdict
        ):
            policy_verdict = "AMBIGUOUS"
            errors.append(
                f"license name/URL map to different rules: "
                f"{name_rule.canonical_id}/{url_rule.canonical_id}"
            )
        else:
            canonical_id = name_rule.canonical_id
            policy_verdict = name_rule.verdict
        verdicts.append(policy_verdict)
        classified.append(
            {
                "canonicalId": canonical_id,
                "name": license_value.name,
                "policyVerdict": policy_verdict,
                "url": license_value.url,
            }
        )
    if "DISALLOWED" in verdicts:
        verdict = "FAIL_DISALLOWED"
    elif "MANUAL_REVIEW" in verdicts:
        verdict = "FAIL_MANUAL_REVIEW"
    elif "AMBIGUOUS" in verdicts:
        verdict = "FAIL_AMBIGUOUS"
    elif "UNCLASSIFIED" in verdicts:
        verdict = "FAIL_UNDECLARED"
    else:
        verdict = "ALLOW_DECLARED"
    return classified, verdict, tuple(sorted(errors))


def _audit_component(
    coordinate: Coordinate,
    cache_root: Path,
    policy: Policy,
    exception: LicenseException | None,
    repository_root: Path,
) -> dict[str, Any]:
    resolution = resolve_pom_licenses(coordinate, cache_root)
    licenses: list[dict[str, Any]] = [
        {
            "canonicalId": None,
            "name": item.name,
            "policyVerdict": "UNCLASSIFIED",
            "url": item.url,
        }
        for item in resolution.licenses
    ]
    caveats = list(resolution.caveats)
    verdict = FAILURE_STATES.get(resolution.state)
    if verdict is None:
        licenses, verdict, classification_caveats = _classify_licenses(
            resolution.licenses, policy
        )
        caveats.extend(classification_caveats)

    exception_dict: dict[str, Any] | None = None
    technical_verification: dict[str, Any] | None = None
    if exception is not None:
        exception_dict = exception.as_dict()
        technical_verification, technical_errors = _verify_exception_evidence(
            exception,
            resolution,
            cache_root,
            repository_root,
        )
        observed = tuple(sorted(resolution.licenses))
        if resolution.state in FAILURE_STATES:
            verdict = FAILURE_STATES[resolution.state]
            caveats.append("exception cannot replace missing, empty, or ambiguous POM evidence")
        elif observed != exception.expected_licenses:
            verdict = "FAIL_EXCEPTION_DRIFT"
            caveats.append("exception expectedLicenses do not match cached POM declarations")
        elif technical_errors:
            verdict = "FAIL_EXCEPTION_EVIDENCE"
            caveats.extend(technical_errors)
        elif exception.status == "MANUAL_REVIEW":
            verdict = "FAIL_MANUAL_REVIEW"
            caveats.append("exception status remains MANUAL_REVIEW")
        elif exception.status == "REJECTED":
            verdict = "FAIL_DISALLOWED"
            caveats.append("exception status is REJECTED")
        elif verdict == "ALLOW_DECLARED":
            verdict = "FAIL_EXCEPTION_DRIFT"
            caveats.append(
                "technical exception is stale because the declaration is already allowed"
            )
        elif exception.status == "TECHNICAL_EVIDENCE_ACCEPTED":
            verdict = "EXCEPTION_TECHNICALLY_VERIFIED"
            caveats.append(TECHNICAL_EVIDENCE_CAVEAT)

    component_caveat = POM_METADATA_CAVEAT
    if caveats:
        component_caveat += " " + " ".join(sorted(set(caveats)))
    return {
        "caveat": component_caveat,
        "coordinate": str(coordinate),
        "declarationState": resolution.state,
        "exception": exception_dict,
        "licenseSourceCoordinate": (
            str(resolution.license_source) if resolution.license_source is not None else None
        ),
        "licenses": licenses,
        "pomChain": list(resolution.pom_chain),
        "technicalEvidenceVerification": technical_verification,
        "verdict": verdict or "FAIL_AMBIGUOUS",
    }


def audit_runtime_licenses(
    lock_file: Path,
    cache_root: Path,
    policy_file: Path,
    *,
    configuration: str | None = None,
) -> AuditResult:
    policy = load_policy(policy_file)
    selected_configuration = configuration or policy.configuration
    if selected_configuration != policy.configuration:
        raise AuditInputError(
            f"requested configuration {selected_configuration!r} does not match "
            f"policy configuration {policy.configuration!r}"
        )
    selection = parse_lockfile(lock_file, selected_configuration)
    repository_root = policy_file.resolve().parent.parent
    exception_by_coordinate = {item.coordinate: item for item in policy.exceptions}
    locked = set(selection.coordinates)
    errors = [
        f"exception drift: coordinate is not locked for {selected_configuration}: {coordinate}"
        for coordinate in sorted(set(exception_by_coordinate) - locked)
    ]
    components = tuple(
        _audit_component(
            coordinate,
            cache_root,
            policy,
            exception_by_coordinate.get(coordinate),
            repository_root,
        )
        for coordinate in selection.coordinates
    )
    errors.extend(
        f"{item['coordinate']}: {item['verdict']}"
        for item in components
        if item["verdict"] not in PASS_VERDICTS
    )
    repository_root = Path(__file__).resolve().parents[1]
    try:
        lock_label = lock_file.resolve().relative_to(repository_root).as_posix()
    except ValueError:
        lock_label = lock_file.name
    return AuditResult(
        selected_configuration,
        lock_label,
        components,
        tuple(sorted(errors)),
    )


def _write_inventory(path: Path, inventory: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(inventory, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def _default_cache_root() -> Path:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return gradle_home / "caches/modules-2/files-2.1"


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    lock_file = root / "app/gradle.lockfile"
    policy_file = root / "config/runtime-license-policy.json"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-cache", type=Path, default=_default_cache_root())
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = audit_runtime_licenses(
            lock_file,
            args.maven_cache,
            policy_file,
            configuration=DEFAULT_CONFIGURATION,
        )
    except AuditInputError as exc:
        print(f"RUNTIME_LICENSE_AUDIT=ERROR error={exc}", file=sys.stderr)
        if args.output is not None:
            _write_inventory(
                args.output,
                {
                    "caveat": POM_METADATA_CAVEAT,
                    "components": [],
                    "configuration": DEFAULT_CONFIGURATION,
                    "errors": [str(exc)],
                    "lockFile": "app/gradle.lockfile",
                    "schemaVersion": INVENTORY_SCHEMA_VERSION,
                    "summary": {
                        "coordinateCount": 0,
                        "failed": 0,
                        "passed": 0,
                        "verdict": "ERROR",
                    },
                },
            )
        return 2
    inventory = result.inventory()
    if args.output is not None:
        _write_inventory(args.output, inventory)
    print(
        f"RUNTIME_LICENSE_AUDIT={'PASS' if result.ok else 'FAIL'} "
        f"configuration={result.configuration} "
        f"coordinates={len(result.components)} errors={len(result.errors)}"
    )
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
