from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from scripts.validate_native_runtime import (
    NativeRuntimeValidationError,
    validate_native_runtime,
)


CONFIGURATION = "debugRuntimeClasspath"


class NativeRuntimeFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.apk = root / "app.apk"
        self.lock = root / "app.gradle.lockfile"
        self.metadata = root / "verification-metadata.xml"
        self.cache = root / "cache"

    @staticmethod
    def _zip(path: Path, entries: list[tuple[str, bytes]]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in entries:
                archive.writestr(name, content)

    def write_apk(self, entries: list[tuple[str, bytes]]) -> None:
        self._zip(self.apk, entries)

    def write_dependencies(
        self,
        owners: list[tuple[str, list[tuple[str, bytes]]]],
    ) -> None:
        self.lock.write_text(
            "".join(
                f"{coordinate}={CONFIGURATION}\n" for coordinate, _ in owners
            ),
            encoding="utf-8",
        )
        components: list[str] = []
        for coordinate, entries in owners:
            group, name, version = coordinate.split(":")
            aar_name = f"{name}-{version}.aar"
            aar_path = self.cache / group / name / version / "content" / aar_name
            self._zip(aar_path, entries)
            digest = hashlib.sha256(aar_path.read_bytes()).hexdigest()
            components.append(
                f'<component group="{group}" name="{name}" version="{version}">'
                f'<artifact name="{aar_name}"><sha256 value="{digest}" />'
                f"</artifact></component>"
            )
        self.metadata.write_text(
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata></configuration>'
            f"<components>{''.join(components)}</components>"
            "</verification-metadata>",
            encoding="utf-8",
        )

    def validate(
        self,
        expected: Path | None = None,
        expected_abi: str | None = None,
    ) -> dict[str, object]:
        return validate_native_runtime(
            self.apk,
            self.lock,
            self.metadata,
            self.cache,
            configuration=CONFIGURATION,
            expected_inventory=expected,
            expected_abi=expected_abi,
        )


class NativeRuntimeValidationTest(unittest.TestCase):
    def test_expected_single_abi_matches_exact_apk_native_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"verified-native-library"
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", native_bytes)]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    )
                ]
            )

            inventory = fixture.validate(expected_abi="arm64-v8a")

            self.assertEqual("PASS", inventory["summary"]["verdict"])

    def test_expected_single_abi_rejects_universal_or_wrong_abi_apk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            arm = b"verified-arm-library"
            x86 = b"verified-x86-library"
            fixture.write_apk(
                [
                    ("lib/arm64-v8a/libgame.so", arm),
                    ("lib/x86_64/libgame.so", x86),
                ]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [
                            ("jni/arm64-v8a/libgame.so", arm),
                            ("jni/x86_64/libgame.so", x86),
                        ],
                    )
                ]
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError,
                "APK native ABI set mismatch: expected=arm64-v8a "
                "actual=arm64-v8a,x86_64",
            ):
                fixture.validate(expected_abi="arm64-v8a")

    def test_unknown_expected_abi_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"verified-native-library"
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", native_bytes)]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    )
                ]
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError,
                "unsupported expected Android ABI",
            ):
                fixture.validate(expected_abi="riscv64")

    def test_pass_maps_every_apk_native_entry_to_verified_runtime_aar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"verified-native-library"
            fixture.write_apk(
                [
                    ("AndroidManifest.xml", b"manifest"),
                    ("lib/arm64-v8a/libgame.so", native_bytes),
                ]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    )
                ]
            )

            inventory = fixture.validate()

            self.assertEqual("PASS", inventory["summary"]["verdict"])
            self.assertEqual(1, inventory["summary"]["matchedEntries"])
            self.assertEqual(
                "example:runtime:1.0",
                inventory["nativeEntries"][0]["owner"]["coordinate"],
            )
            self.assertEqual(1, inventory["inputs"]["aarArtifactsScanned"])

    def test_unmatched_apk_native_entry_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", b"not-in-the-runtime-aar")]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", b"different")],
                    )
                ]
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError, "unmatched APK native entry"
            ):
                fixture.validate()

    def test_ambiguous_runtime_aar_owners_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"same-native-library"
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", native_bytes)]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime-one:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    ),
                    (
                        "example:runtime-two:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    ),
                ]
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError, "ambiguous APK native entry"
            ):
                fixture.validate()

    def test_two_artifacts_for_same_coordinate_are_ambiguous(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"same-native-library"
            coordinate = "example:runtime:1.0"
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", native_bytes)]
            )
            fixture.write_dependencies(
                [(coordinate, [("jni/arm64-v8a/libgame.so", native_bytes)])]
            )
            second_aar = (
                fixture.cache
                / "example/runtime/1.0/second/runtime-1.0-second.aar"
            )
            fixture._zip(
                second_aar,
                [
                    ("jni/arm64-v8a/libgame.so", native_bytes),
                    ("second-artifact-marker", b"different-aar-hash"),
                ],
            )
            second_digest = hashlib.sha256(second_aar.read_bytes()).hexdigest()
            second_xml = (
                '<artifact name="runtime-1.0-second.aar">'
                f'<sha256 value="{second_digest}" /></artifact>'
            )
            fixture.metadata.write_text(
                fixture.metadata.read_text(encoding="utf-8").replace(
                    "</component>", f"{second_xml}</component>"
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError, "ambiguous APK native entry"
            ):
                fixture.validate()

    def test_duplicate_apk_native_paths_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"duplicate-native-library"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                fixture.write_apk(
                    [
                        ("lib/arm64-v8a/libgame.so", native_bytes),
                        ("lib/arm64-v8a/libgame.so", native_bytes),
                    ]
                )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    )
                ]
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError, "duplicate native ZIP entries"
            ):
                fixture.validate()

    def test_expected_inventory_apk_hash_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"verified-native-library"
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", native_bytes)]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    )
                ]
            )
            expected = copy.deepcopy(fixture.validate())
            expected["apk"]["sha256"] = "0" * 64
            expected_path = fixture.root / "expected.json"
            expected_path.write_text(
                json.dumps(expected),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError,
                "expected inventory APK SHA-256 mismatch",
            ):
                fixture.validate(expected_path)

    def test_cached_aar_hash_not_in_verification_metadata_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = NativeRuntimeFixture(Path(directory))
            native_bytes = b"verified-native-library"
            fixture.write_apk(
                [("lib/arm64-v8a/libgame.so", native_bytes)]
            )
            fixture.write_dependencies(
                [
                    (
                        "example:runtime:1.0",
                        [("jni/arm64-v8a/libgame.so", native_bytes)],
                    )
                ]
            )
            fixture.metadata.write_text(
                fixture.metadata.read_text(encoding="utf-8").replace(
                    hashlib.sha256(
                        next(fixture.cache.rglob("*.aar")).read_bytes()
                    ).hexdigest(),
                    "0" * 64,
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                NativeRuntimeValidationError,
                "cached runtime AAR SHA-256 is not verified",
            ):
                fixture.validate()


if __name__ == "__main__":
    unittest.main()
