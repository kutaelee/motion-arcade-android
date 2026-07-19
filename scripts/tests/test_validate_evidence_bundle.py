from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.validate_evidence_bundle import validate_evidence_bundle


COMMIT = "a" * 40
TREE = "b" * 40


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class EvidenceBundleValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.run = self.root / "docs/evidence/runs/test"
        self.run.mkdir(parents=True)
        self.artifact_dir = self.root / "artifacts/slice-0a"
        self.artifact_dir.mkdir(parents=True)
        self.evidence = {
            "api33Instrumentation": "docs/evidence/runs/test/api33.xml",
            "api37Instrumentation": "docs/evidence/runs/test/api37.xml",
            "apkPolicy": "docs/evidence/runs/test/apk-policy.txt",
            "runtimeLicenses": "docs/evidence/runs/test/licenses.json",
            "nativeOwnership": "docs/evidence/runs/test/native.json",
        }
        for index, relative in enumerate(self.evidence.values()):
            path = self.root / relative
            path.write_text(f"evidence-{index}\n", encoding="utf-8")
        self._write_fixture()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _git_runner(argv: list[str], **_: object) -> subprocess.CompletedProcess[str]:
        if argv[1:3] == ["cat-file", "-e"]:
            return subprocess.CompletedProcess(argv, 0, "", "")
        if argv[1:4] == ["show", "-s", "--format=%T"]:
            return subprocess.CompletedProcess(argv, 0, f"{TREE}\n", "")
        return subprocess.CompletedProcess(argv, 1, "", "unexpected command")

    def _write_fixture(
        self,
        *,
        artifact_prefix: str = "artifacts/slice-0a",
        artifact_name_from_hash: bool = True,
        extra_provenance: dict[str, object] | None = None,
        source_tree: str = TREE,
    ) -> None:
        artifact_bytes = b"PK\x03\x04synthetic-apk"
        artifact_hash = hashlib.sha256(artifact_bytes).hexdigest()
        artifact_name = (
            f"motion-arcade-{artifact_hash}.apk"
            if artifact_name_from_hash
            else "motion-arcade.apk"
        )
        self.artifact_relative = f"{artifact_prefix}/{artifact_name}"
        self.artifact = self.root / self.artifact_relative
        self.artifact.parent.mkdir(parents=True, exist_ok=True)
        self.artifact.write_bytes(artifact_bytes)
        self.provenance = self.run / "artifact-provenance.json"
        document: dict[str, object] = {
            "schemaVersion": 1,
            "sourceCommit": COMMIT,
            "sourceTree": source_tree,
            "buildCommand": ["gradlew.bat", "--no-daemon", "assembleDebug"],
            "buildEnvironment": {
                "os": "Windows 11",
                "jdk": "17.0.17+8",
                "gradle": "9.4.1",
                "agp": "9.2.1",
                "kotlin": "2.4.0",
                "compileSdk": "37",
                "targetSdk": "37",
                "buildTools": "36.0.0",
            },
            "artifact": {
                "path": self.artifact_relative,
                "sha256": artifact_hash,
                "bytes": len(artifact_bytes),
                "variant": "debug",
                "package": "com.motionarcade.app.debug",
            },
            "validationEvidence": self.evidence,
        }
        if extra_provenance:
            document.update(extra_provenance)
        self.provenance.write_text(
            json.dumps(document, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        self._write_manifest()

    def _write_manifest(self, omitted: set[str] | None = None) -> None:
        omitted = omitted or set()
        relatives = [
            self.artifact_relative,
            *self.evidence.values(),
            self.provenance.relative_to(self.root).as_posix(),
        ]
        lines = [
            f"{_sha256(self.root / relative).upper()}  {relative}"
            for relative in relatives
            if relative not in omitted
        ]
        self.manifest = self.run / "EVIDENCE_BUNDLE.sha256"
        self.manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def _validate(self, runner=None):
        return validate_evidence_bundle(
            self.root,
            self.manifest.relative_to(self.root),
            self.provenance.relative_to(self.root),
            git_runner=runner or self._git_runner,
        )

    def test_valid_content_addressed_bundle_passes(self) -> None:
        result = self._validate()

        self.assertTrue(result.passed, result.errors)
        self.assertEqual(7, result.entry_count)

    def test_manifest_path_escape_is_rejected(self) -> None:
        with self.manifest.open("a", encoding="utf-8") as handle:
            handle.write(f"{'0' * 64}  ../outside\n")

        result = self._validate()

        self.assertTrue(any("unsafe or non-canonical" in error for error in result.errors))

    def test_duplicate_manifest_path_is_rejected_case_insensitively(self) -> None:
        with self.manifest.open("a", encoding="utf-8") as handle:
            handle.write(f"{_sha256(self.artifact)}  {self.artifact_relative.upper()}\n")

        result = self._validate()

        self.assertTrue(any("duplicate path" in error for error in result.errors))

    def test_mutable_build_artifact_path_is_rejected(self) -> None:
        self._write_fixture(artifact_prefix="app/build/outputs/apk/debug")

        result = self._validate()

        self.assertTrue(any("immutable path below artifacts" in error for error in result.errors))

    def test_artifact_filename_without_digest_is_rejected(self) -> None:
        self._write_fixture(artifact_name_from_hash=False)

        result = self._validate()

        self.assertTrue(any("filename must contain" in error for error in result.errors))

    def test_source_tree_mismatch_is_rejected(self) -> None:
        def wrong_tree_runner(argv: list[str], **_: object) -> subprocess.CompletedProcess[str]:
            if argv[1:3] == ["cat-file", "-e"]:
                return subprocess.CompletedProcess(argv, 0, "", "")
            return subprocess.CompletedProcess(argv, 0, f"{'c' * 40}\n", "")

        result = self._validate(wrong_tree_runner)

        self.assertTrue(any("sourceTree mismatch" in error for error in result.errors))

    def test_unlisted_validation_evidence_is_rejected(self) -> None:
        missing = self.evidence["api37Instrumentation"]
        self._write_manifest({missing})

        result = self._validate()

        self.assertTrue(any("api37Instrumentation is not listed" in error for error in result.errors))

    def test_artifact_drift_is_rejected(self) -> None:
        self.artifact.write_bytes(self.artifact.read_bytes() + b"drift")

        result = self._validate()

        self.assertTrue(any("artifact hash mismatch" in error for error in result.errors))
        self.assertTrue(any("artifact byte count mismatch" in error for error in result.errors))

    def test_unknown_provenance_field_is_rejected(self) -> None:
        document = json.loads(self.provenance.read_text(encoding="utf-8"))
        document["unreviewed"] = True
        self.provenance.write_text(json.dumps(document), encoding="utf-8")
        self._write_manifest()

        result = self._validate()

        self.assertTrue(any("unknown fields" in error for error in result.errors))


if __name__ == "__main__":
    unittest.main()
