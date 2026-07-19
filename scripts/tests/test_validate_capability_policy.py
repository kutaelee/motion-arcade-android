from __future__ import annotations

import ast
import builtins
from collections.abc import Iterator, Mapping
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.capability_policy_snapshot import (
    RepositorySnapshot,
    SnapshotError,
    capture_git_commit_snapshot,
    capture_repository_snapshot,
)
from scripts.validate_capability_policy import (
    AUTHORITY_MANIFEST_FILE,
    AUTHORITY_SCHEMA,
    AUTHORITY_STATUS,
    CURRENT_AUTHORITY_FILES,
    CURRENT_EVIDENCE_FILE,
    CURRENT_POLICY,
    EXPECTED_REVIEW_FILES,
    FORBIDDEN_GOVERNED_PATHS,
    FORBIDDEN_TOKENS,
    FROZEN_HISTORICAL_AUTHORIZATION,
    FROZEN_HISTORICAL_EVIDENCE,
    FROZEN_REJECTED_REVIEWS,
    GOVERNED_INPUTS,
    HISTORICAL_EVIDENCE_FILES,
    HISTORICAL_MANIFESTS,
    HISTORICAL_V12_MANIFEST_FILE,
    HISTORICAL_V12_MANIFEST_SHA256,
    REQUIRED_FIRST_LINES,
    REQUIRED_TOKENS,
    REVIEW_DIRECTORY,
    validate_capability_policy,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CURRENT_AUTHORIZATION_RECORD = FORBIDDEN_GOVERNED_PATHS[0]

INDEPENDENT_EXPECTED_INPUTS = frozenset(
    {
        "docs/adr/ADR-011-measured-capability-probe-policy.md",
        "docs/contracts/capability-policy-authority-v10.json",
        "docs/contracts/capability-policy-authority-v11.json",
        "docs/contracts/capability-policy-authority-v12.json",
        "docs/contracts/capability-policy-authority-v13.json",
        "docs/contracts/capability-policy-authority-v14.json",
        "docs/contracts/capability-policy-authority-v15.json",
        "docs/contracts/capability-policy-authorization-v14.json",
        "docs/contracts/capability-store-v3.md",
        "docs/contracts/capability-store-v4.md",
        "docs/contracts/native-close-fence-proof-v1.md",
        "docs/contracts/recovery-journal-v5.md",
        "docs/evidence/EVIDENCE_INDEX.md",
        "docs/evidence/runs/2026-07-15-slice-1b-policy/v10-precommit-validation.md",
        "docs/evidence/runs/2026-07-15-slice-1b-policy/v11-precommit-validation.md",
        "docs/evidence/runs/2026-07-15-slice-1b-policy/v12-precommit-validation.md",
        "docs/evidence/runs/2026-07-15-slice-1b-policy/v13-precommit-validation.md",
        "docs/evidence/runs/2026-07-15-slice-1b-policy/v14-precommit-validation.md",
        "docs/evidence/runs/2026-07-15-slice-1b-policy/v15-precommit-validation.md",
        "docs/execution/slice-1b-contract.md",
        "docs/execution/slice-status.md",
        *(f"docs/reviews/slice-1b-policy-review-{number:02d}.md" for number in range(1, 15)),
        *(
            f"docs/reviews/slice-1b-policy-v14-exact-review-{number:02d}.json"
            for number in range(1, 4)
        ),
    }
)


def _current_repository_candidate_commit(root: Path) -> str | None:
    """Select v15 C only after the v15 authorization record exists.

    Historical v14 authorization is an always-present governed input and must never
    select the obsolete v14 candidate.  Before A15 exists, callers intentionally use
    the mutable candidate-stage diagnostic.  At A15 and later, they validate exact C15.
    """

    path = root.joinpath(*CURRENT_AUTHORIZATION_RECORD.split("/"))
    if not path.exists():
        return None
    payload = json.loads(path.read_text(encoding="utf-8"))
    target = payload.get("target") if isinstance(payload, dict) else None
    candidate = target.get("candidate_commit") if isinstance(target, dict) else None
    if (
        not isinstance(candidate, str)
        or len(candidate) != 40
        or any(character not in "0123456789abcdef" for character in candidate)
    ):
        raise AssertionError("v15 authorization target candidate_commit is not exact sha1")
    return candidate


def _write_authority_manifest(root: Path) -> None:
    payload = {
        "authorities": [
            {
                "path": relative,
                "revision": CURRENT_POLICY,
                "sha256": hashlib.sha256((root / relative).read_bytes()).hexdigest(),
            }
            for relative in CURRENT_AUTHORITY_FILES
        ],
        "candidate_revision": CURRENT_POLICY,
        "implementation_authorized": False,
        "rejected_revisions": [
            {
                "review": relative,
                "review_sha256": hashlib.sha256((root / relative).read_bytes()).hexdigest(),
                "revision": f"capability-v{number}",
            }
            for number, relative in enumerate(EXPECTED_REVIEW_FILES, start=1)
        ],
        "schema": AUTHORITY_SCHEMA,
        "status": AUTHORITY_STATUS,
    }
    _rewrite_authority_manifest(root, payload)


def _write_current_evidence_linkage(root: Path) -> None:
    path = root / CURRENT_EVIDENCE_FILE
    text = path.read_text(encoding="utf-8")
    marker = "\n## Current authority SHA-256\n"
    if marker in text:
        text = text.split(marker, 1)[0].rstrip() + "\n"
    rows = [
        "",
        "## Current authority SHA-256",
        "",
        "| Authority | SHA-256 |",
        "| --- | --- |",
    ]
    for relative in (*CURRENT_AUTHORITY_FILES, AUTHORITY_MANIFEST_FILE):
        digest = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        rows.append(f"| `{relative}` | `{digest}` |")
    path.write_text(text.rstrip() + "\n" + "\n".join(rows) + "\n", encoding="utf-8")


def _rewrite_authority_manifest(root: Path, payload: dict[str, object]) -> None:
    path = root / AUTHORITY_MANIFEST_FILE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
            "utf-8"
        )
        + b"\n"
    )


def _write_minimum_valid_tree(root: Path) -> None:
    frozen_paths = {
        *(path for path, _sha256 in HISTORICAL_MANIFESTS),
        *(path for path, _sha256 in FROZEN_HISTORICAL_EVIDENCE),
        *(path for path, _sha256 in FROZEN_REJECTED_REVIEWS),
        *(path for path, _sha256 in FROZEN_HISTORICAL_AUTHORIZATION),
    }
    for relative, tokens in REQUIRED_TOKENS.items():
        if relative in frozen_paths:
            continue
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        lines: list[str] = []
        first_line = REQUIRED_FIRST_LINES.get(relative)
        if first_line is not None:
            lines.append(first_line)
        lines.extend(token for token in tokens if token != first_line)
        if relative == "docs/adr/ADR-011-measured-capability-probe-policy.md":
            policy_line = f"- Policy revision: `{CURRENT_POLICY}`"
            lines.remove(policy_line)
            lines[1:1] = ["fixture", "fixture", "fixture", "fixture", policy_line]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    for relative, expected_hash in (
        *HISTORICAL_MANIFESTS,
        *FROZEN_HISTORICAL_EVIDENCE,
        *FROZEN_REJECTED_REVIEWS,
        *FROZEN_HISTORICAL_AUTHORIZATION,
    ):
        historical_bytes = (REPOSITORY_ROOT / relative).read_bytes()
        if hashlib.sha256(historical_bytes).hexdigest() != expected_hash:
            raise AssertionError(f"repository frozen evidence hash drifted: {relative}")
        historical_path = root / relative
        historical_path.parent.mkdir(parents=True, exist_ok=True)
        historical_path.write_bytes(historical_bytes)
    _write_authority_manifest(root)
    _write_current_evidence_linkage(root)


def _append_and_rehash_authority(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.write_text(path.read_text(encoding="utf-8") + "\n" + text, encoding="utf-8")
    _write_authority_manifest(root)
    _write_current_evidence_linkage(root)


def _git_commit_all(root: Path) -> str:
    commands = (
        ("init", "--object-format=sha1"),
        ("config", "user.name", "Capability Policy Test"),
        ("config", "user.email", "capability-policy@example.invalid"),
        ("config", "core.autocrlf", "false"),
        ("add", "--all"),
        ("commit", "-m", "fixture"),
    )
    for command in commands:
        completed = subprocess.run(
            ["git", "-C", os.fspath(root), *command],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            text=True,
        )
        if completed.returncode != 0:
            raise AssertionError(f"git {' '.join(command)} failed: {completed.stdout}")
    completed = subprocess.run(
        ["git", "-C", os.fspath(root), "rev-parse", "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        text=True,
    )
    if completed.returncode != 0:
        raise AssertionError(f"git rev-parse failed: {completed.stdout}")
    return completed.stdout.strip()


class _RecordingMapping(Mapping[str, bytes]):
    def __init__(self, wrapped: Mapping[str, bytes]) -> None:
        self._wrapped = wrapped
        self.reads: set[str] = set()

    def __getitem__(self, key: str) -> bytes:
        self.reads.add(key)
        return self._wrapped[key]

    def __iter__(self) -> Iterator[str]:
        return iter(self._wrapped)

    def __len__(self) -> int:
        return len(self._wrapped)


class CapabilityPolicyConsistencyTest(unittest.TestCase):
    def test_minimum_current_authority_set_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)

            result = validate_capability_policy(root)

            self.assertEqual((), result.errors)

    def test_repository_current_policy_references_are_consistent(self) -> None:
        candidate = _current_repository_candidate_commit(REPOSITORY_ROOT)
        result = validate_capability_policy(REPOSITORY_ROOT, commit=candidate)

        self.assertEqual((), result.errors)
        self.assertEqual("worktree" if candidate is None else "git-commit", result.source)

    def test_repository_target_selection_is_dual_stage_and_ignores_v14_history(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            self.assertTrue(
                (root / "docs/contracts/capability-policy-authorization-v14.json").is_file()
            )
            self.assertIsNone(_current_repository_candidate_commit(root))

            candidate = "1" * 40
            current = root / CURRENT_AUTHORIZATION_RECORD
            current.write_text(
                json.dumps({"target": {"candidate_commit": candidate}}),
                encoding="utf-8",
            )

            self.assertEqual(candidate, _current_repository_candidate_commit(root))

    def test_v15_boundary_and_review14_are_current(self) -> None:
        self.assertEqual("capability-v15", CURRENT_POLICY)
        self.assertTrue(AUTHORITY_MANIFEST_FILE.endswith("authority-v15.json"))
        self.assertTrue(CURRENT_EVIDENCE_FILE.endswith("v15-precommit-validation.md"))
        self.assertEqual(14, len(EXPECTED_REVIEW_FILES))
        self.assertTrue(EXPECTED_REVIEW_FILES[-1].endswith("review-14.md"))

    def test_governed_input_inventory_is_independent_exact_and_complete(self) -> None:
        self.assertEqual(38, len(GOVERNED_INPUTS))
        self.assertEqual(38, len(set(GOVERNED_INPUTS)))
        self.assertEqual(INDEPENDENT_EXPECTED_INPUTS, frozenset(GOVERNED_INPUTS))
        self.assertLessEqual(set(REQUIRED_TOKENS), set(GOVERNED_INPUTS))
        self.assertLessEqual(set(CURRENT_AUTHORITY_FILES), set(GOVERNED_INPUTS))
        self.assertLessEqual(set(EXPECTED_REVIEW_FILES), set(GOVERNED_INPUTS))
        self.assertLessEqual({item[0] for item in HISTORICAL_MANIFESTS}, set(GOVERNED_INPUTS))

    def test_validator_calls_snapshot_with_exact_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            with mock.patch(
                "scripts.validate_capability_policy.capture_repository_snapshot",
                wraps=capture_repository_snapshot,
            ) as capture:
                result = validate_capability_policy(root)

            self.assertEqual((), result.errors)
            capture.assert_called_once_with(
                root.absolute(),
                GOVERNED_INPUTS,
                "docs/reviews",
                FORBIDDEN_GOVERNED_PATHS,
            )

    def test_exact_commit_gate_calls_git_snapshot_and_ignores_worktree_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            commit = _git_commit_all(root)
            (root / CURRENT_AUTHORITY_FILES[0]).write_text(
                "mutable worktree is not the formal target\n", encoding="utf-8"
            )
            with mock.patch(
                "scripts.validate_capability_policy.capture_git_commit_snapshot",
                wraps=capture_git_commit_snapshot,
            ) as capture:
                result = validate_capability_policy(root, commit=commit)

            self.assertEqual((), result.errors)
            self.assertEqual("git-commit", result.source)
            self.assertEqual(commit, result.commit)
            self.assertRegex(result.tree or "", r"^[a-f0-9]{40}$")
            capture.assert_called_once_with(
                root.absolute(),
                commit,
                GOVERNED_INPUTS,
                REVIEW_DIRECTORY,
                FORBIDDEN_GOVERNED_PATHS,
            )

    def test_every_captured_file_byte_mapping_is_consumed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            captured = capture_repository_snapshot(
                root.absolute(), GOVERNED_INPUTS, REVIEW_DIRECTORY, FORBIDDEN_GOVERNED_PATHS
            )
            recording = _RecordingMapping(captured.files)
            replacement = RepositorySnapshot(
                canonical_root=captured.canonical_root,
                files=recording,
                review_names=captured.review_names,
                governed_inputs=captured.governed_inputs,
            )
            with mock.patch(
                "scripts.validate_capability_policy.capture_repository_snapshot",
                return_value=replacement,
            ):
                result = validate_capability_policy(root)

            self.assertEqual((), result.errors)
            self.assertEqual(set(GOVERNED_INPUTS), recording.reads)

    def test_snapshot_failure_is_fail_closed_without_fallback(self) -> None:
        with mock.patch(
            "scripts.validate_capability_policy.capture_repository_snapshot",
            side_effect=SnapshotError("anchored read failed"),
        ):
            result = validate_capability_policy(Path("missing"))

        self.assertFalse(result.ok)
        self.assertEqual(
            ("cannot capture governed repository snapshot: anchored read failed",),
            result.errors,
        )

    def test_validator_has_no_post_capture_filesystem_dereference_surface(self) -> None:
        source_path = REPOSITORY_ROOT / "scripts/validate_capability_policy.py"
        source = source_path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        forbidden_methods = {
            "access",
            "exists",
            "glob",
            "is_dir",
            "is_file",
            "is_symlink",
            "iterdir",
            "lexists",
            "listdir",
            "lstat",
            "open",
            "read_bytes",
            "read_text",
            "resolve",
            "rglob",
            "samefile",
            "scandir",
            "stat",
            "walk",
        }
        attribute_calls = {
            node.func.attr
            for node in ast.walk(tree)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
        }
        direct_calls = {
            node.func.id
            for node in ast.walk(tree)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
        }
        imported_roots = {
            alias.name.split(".", 1)[0]
            for node in ast.walk(tree)
            if isinstance(node, ast.Import)
            for alias in node.names
        }

        self.assertTrue(
            forbidden_methods.isdisjoint(attribute_calls),
            forbidden_methods & attribute_calls,
        )
        self.assertNotIn("open", direct_calls)
        self.assertTrue({"glob", "os", "shutil", "subprocess"}.isdisjoint(imported_roots))
        self.assertEqual(1, source.count("capture_repository_snapshot("))
        self.assertEqual(1, source.count("capture_git_commit_snapshot("))

    def test_no_filesystem_primitive_is_called_after_snapshot_capture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            captured = capture_repository_snapshot(
                root.absolute(), GOVERNED_INPUTS, REVIEW_DIRECTORY, FORBIDDEN_GOVERNED_PATHS
            )

            forbidden = AssertionError("validator attempted filesystem I/O after capture")
            with (
                mock.patch(
                    "scripts.validate_capability_policy.capture_repository_snapshot",
                    return_value=captured,
                ),
                mock.patch.object(builtins, "open", side_effect=forbidden),
                mock.patch.object(os, "listdir", side_effect=forbidden),
                mock.patch.object(os, "lstat", side_effect=forbidden),
                mock.patch.object(os, "open", side_effect=forbidden),
                mock.patch.object(os, "scandir", side_effect=forbidden),
                mock.patch.object(os, "stat", side_effect=forbidden),
                mock.patch.object(Path, "exists", side_effect=forbidden),
                mock.patch.object(Path, "iterdir", side_effect=forbidden),
                mock.patch.object(Path, "read_bytes", side_effect=forbidden),
                mock.patch.object(Path, "read_text", side_effect=forbidden),
                mock.patch.object(Path, "resolve", side_effect=forbidden),
            ):
                result = validate_capability_policy(root)

            self.assertEqual((), result.errors)

    def test_missing_review_14_is_rejected_by_snapshot_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            (root / EXPECTED_REVIEW_FILES[-1]).unlink()

            result = validate_capability_policy(root)

            self.assertFalse(result.ok)
            self.assertIn("review-14.md", result.errors[0])
            self.assertIn("snapshot", result.errors[0])

    def test_unexpected_review_15_is_rejected_from_captured_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            (root / REVIEW_DIRECTORY / "slice-1b-policy-review-15.md").write_text(
                "not bound\n", encoding="utf-8"
            )

            result = validate_capability_policy(root)

            self.assertIn(
                "unexpected unbound capability-policy review: "
                "docs/reviews/slice-1b-policy-review-15.md",
                result.errors,
            )

    def test_case_variant_unexpected_review_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            (root / REVIEW_DIRECTORY / "SLICE-1B-POLICY-REVIEW-15.MD").write_text(
                "not bound\n", encoding="utf-8"
            )

            result = validate_capability_policy(root)

            self.assertTrue(
                any("unexpected unbound capability-policy review" in item for item in result.errors)
            )

    def test_malformed_snapshot_shapes_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            captured = capture_repository_snapshot(
                root.absolute(), GOVERNED_INPUTS, REVIEW_DIRECTORY, FORBIDDEN_GOVERNED_PATHS
            )
            files = dict(captured.files)
            cases = (
                RepositorySnapshot(
                    captured.canonical_root,
                    {**files, "unexpected.md": b"unexpected"},
                    captured.review_names,
                    captured.governed_inputs,
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    {key: value for key, value in files.items() if key != GOVERNED_INPUTS[0]},
                    captured.review_names,
                    captured.governed_inputs,
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    {**files, GOVERNED_INPUTS[0]: "not-bytes"},  # type: ignore[dict-item]
                    captured.review_names,
                    captured.governed_inputs,
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    files,
                    (*captured.review_names, 7),  # type: ignore[arg-type]
                    captured.governed_inputs,
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    files,
                    captured.review_names,
                    (*captured.governed_inputs, "unexpected.md"),
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    files,
                    captured.review_names,
                    tuple(reversed(captured.governed_inputs)),
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    files,
                    captured.review_names,
                    (*captured.governed_inputs[:-1], 7),  # type: ignore[arg-type]
                ),
                RepositorySnapshot(
                    captured.canonical_root,
                    files,
                    (*captured.review_names, "ＳＬＩＣＥ-1B-POLICY-REVIEW-01.MD"),
                    captured.governed_inputs,
                ),
            )
            for malformed in cases:
                with self.subTest(malformed=malformed), mock.patch(
                    "scripts.validate_capability_policy.capture_repository_snapshot",
                    return_value=malformed,
                ):
                    result = validate_capability_policy(root)
                    self.assertFalse(result.ok)
                    self.assertTrue(any("snapshot" in item for item in result.errors))

    def test_historical_v12_manifest_hash_is_frozen(self) -> None:
        self.assertEqual(
            HISTORICAL_V12_MANIFEST_SHA256,
            hashlib.sha256(
                (REPOSITORY_ROOT / HISTORICAL_V12_MANIFEST_FILE).read_bytes()
            ).hexdigest(),
        )

    def test_historical_v12_manifest_mutation_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / HISTORICAL_V12_MANIFEST_FILE
            path.write_bytes(path.read_bytes() + b"changed\n")

            result = validate_capability_policy(root)

            self.assertTrue(
                any("historical authority manifest hash mismatch" in item for item in result.errors)
            )

    def test_every_historical_rejection_ledger_hash_is_frozen(self) -> None:
        for relative, _expected_hash in FROZEN_HISTORICAL_EVIDENCE:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                path = root / relative
                path.write_bytes(path.read_bytes() + b"changed\n")

                result = validate_capability_policy(root)

                self.assertTrue(
                    any(
                        "historical rejection ledger hash mismatch" in item
                        and relative in item
                        for item in result.errors
                    )
                )

    def test_historical_v14_authorization_evidence_mutation_is_rejected(self) -> None:
        for relative, _expected_hash in (
            FROZEN_HISTORICAL_AUTHORIZATION[0],
            FROZEN_HISTORICAL_AUTHORIZATION[-1],
        ):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                path = root / relative
                path.write_bytes(path.read_bytes() + b"changed\n")

                result = validate_capability_policy(root)

                self.assertTrue(
                    any(
                        "historical authorization evidence hash mismatch" in item
                        and relative in item
                        for item in result.errors
                    )
                )

    def test_historical_v14_authorization_evidence_omission_is_rejected(self) -> None:
        relative, _expected_hash = FROZEN_HISTORICAL_AUTHORIZATION[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            (root / relative).unlink()

            result = validate_capability_policy(root)

            self.assertFalse(result.ok)
            self.assertIn(Path(relative).name, result.errors[0])

    def test_historical_v14_exact_review_alias_is_rejected(self) -> None:
        relative, _expected_hash = FROZEN_HISTORICAL_AUTHORIZATION[1]
        parent, name = relative.rsplit("/", 1)
        alias = f"{parent}/\uff53{name[1:]}"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            (root / alias).write_bytes((root / relative).read_bytes())

            result = validate_capability_policy(root)

            self.assertFalse(result.ok)
            self.assertIn("alias", "\n".join(result.errors))

    def test_review_mutation_and_current_manifest_rehash_cannot_replace_history(self) -> None:
        for relative, _expected_hash in (
            FROZEN_REJECTED_REVIEWS[0],
            FROZEN_REJECTED_REVIEWS[-1],
        ):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                path = root / relative
                path.write_bytes(path.read_bytes() + b"changed\n")
                _write_authority_manifest(root)
                _write_current_evidence_linkage(root)

                result = validate_capability_policy(root)

                self.assertTrue(
                    any(
                        "frozen rejected review hash mismatch" in item and relative in item
                        for item in result.errors
                    )
                )

    def test_authority_and_manifest_rehash_with_stale_evidence_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/execution/slice-1b-contract.md"
            path = root / relative
            path.write_text(path.read_text(encoding="utf-8") + "truthful fixture\n", encoding="utf-8")
            _write_authority_manifest(root)

            result = validate_capability_policy(root)

            self.assertTrue(
                any(
                    "current evidence authority linkage mismatch" in item
                    and relative in item
                    for item in result.errors
                )
            )
            self.assertTrue(
                any(
                    "current evidence authority linkage mismatch" in item
                    and AUTHORITY_MANIFEST_FILE in item
                    for item in result.errors
                )
            )

    def test_duplicate_or_hidden_current_evidence_linkage_is_rejected(self) -> None:
        for mode in ("duplicate", "hidden"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                evidence = root / CURRENT_EVIDENCE_FILE
                authority = CURRENT_AUTHORITY_FILES[0]
                digest = hashlib.sha256((root / authority).read_bytes()).hexdigest()
                row = f"| `{authority}` | `{digest}` |"
                text = evidence.read_text(encoding="utf-8")
                if mode == "duplicate":
                    text += row + "\n"
                else:
                    text = text.replace(row, f"<!-- {row} -->")
                evidence.write_text(text, encoding="utf-8")

                result = validate_capability_policy(root)

                self.assertTrue(
                    any(
                        "current evidence authority linkage mismatch" in item
                        and authority in item
                        for item in result.errors
                    )
                )

    def test_candidate_authorization_paths_and_portable_aliases_are_forbidden(self) -> None:
        cases = (
            FORBIDDEN_GOVERNED_PATHS[0],
            "docs/contracts/CAPABILITY-POLICY-AUTHORIZATION-V15.JSON",
            "docs/contracts/ｃａｐａｂｉｌｉｔｙ－ｐｏｌｉｃｙ－ａｕｔｈｏｒｉｚａｔｉｏｎ－ｖ１５．ｊｓｏｎ",
            "docs/reviews/SLICE-1B-POLICY-V15-EXACT-REVIEW-01.JSON",
        )
        for relative in cases:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("forbidden\n", encoding="utf-8")

                result = validate_capability_policy(root)

                self.assertFalse(result.ok)
                self.assertIn("forbidden", result.errors[0])

    def test_historical_manifest_missing_is_rejected_by_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            (root / HISTORICAL_V12_MANIFEST_FILE).unlink()

            result = validate_capability_policy(root)

            self.assertFalse(result.ok)
            self.assertIn("authority-v12.json", result.errors[0])

    def test_authority_byte_change_without_manifest_update_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / "docs/execution/slice-1b-contract.md"
            path.write_text(path.read_text(encoding="utf-8") + "changed\n", encoding="utf-8")

            result = validate_capability_policy(root)

            self.assertTrue(any("authority file hash mismatch" in item for item in result.errors))

    def test_manifest_crlf_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))

            result = validate_capability_policy(root)

            self.assertIn(
                "authority manifest bytes are not canonical sorted compact JSON", result.errors
            )

    def test_manifest_lone_surrogate_is_a_typed_validation_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            path.write_bytes(
                path.read_bytes().replace(b"capability-v15", b"capability-v15\\ud800", 1)
            )

            result = validate_capability_policy(root)

            self.assertFalse(result.ok)
            self.assertTrue(
                any("cannot canonicalize authority manifest" in item for item in result.errors)
            )

    def test_deeply_nested_manifest_is_a_typed_validation_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            path.write_bytes(b"[" * 4096 + b"0" + b"]" * 4096 + b"\n")

            result = validate_capability_policy(root)

            self.assertFalse(result.ok)
            self.assertTrue(any("authority manifest" in item for item in result.errors))

    def test_duplicate_manifest_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            path.write_bytes(
                path.read_bytes().replace(
                    b'{"authorities":', b'{"authorities":[],"authorities":', 1
                )
            )

            result = validate_capability_policy(root)

            self.assertTrue(any("duplicate JSON key" in item for item in result.errors))

    def test_candidate_manifest_cannot_authorize_implementation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["implementation_authorized"] = True
            _rewrite_authority_manifest(root, payload)

            result = validate_capability_policy(root)

            self.assertIn(
                "authority manifest must not authorize implementation before rereview",
                result.errors,
            )

    def test_authority_order_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["authorities"][0], payload["authorities"][1] = (
                payload["authorities"][1],
                payload["authorities"][0],
            )
            _rewrite_authority_manifest(root, payload)

            result = validate_capability_policy(root)

            self.assertTrue(
                any("authority manifest row identity mismatch" in item for item in result.errors)
            )

    def test_rejected_review_row_identity_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            path = root / AUTHORITY_MANIFEST_FILE
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["rejected_revisions"][-1]["revision"] = "capability-v13"
            _rewrite_authority_manifest(root, payload)

            result = validate_capability_policy(root)

            self.assertIn(
                "rejected-revision row identity mismatch for capability-v14", result.errors
            )

    def test_stale_execution_revision_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/execution/slice-1b-contract.md"
            _append_and_rehash_authority(root, relative, FORBIDDEN_TOKENS[relative][0] + "\n")

            result = validate_capability_policy(root)

            self.assertTrue(any("stale capability-policy token" in item for item in result.errors))

    def test_older_policy_revision_line_is_rejected_even_when_manifest_is_rehashed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/adr/ADR-011-measured-capability-probe-policy.md"
            _append_and_rehash_authority(
                root, relative, "- Policy revision: `capability-v13`\n"
            )

            result = validate_capability_policy(root)

            self.assertTrue(any("stale capability-policy token" in item for item in result.errors))

    def test_policy_revision_hidden_only_in_comment_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/adr/ADR-011-measured-capability-probe-policy.md"
            path = root / relative
            current = "- Policy revision: `capability-v15`"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    current, "<!-- " + current + " -->"
                ),
                encoding="utf-8",
            )
            _write_authority_manifest(root)

            result = validate_capability_policy(root)

            self.assertTrue(any("exact line" in item for item in result.errors))

    def test_rejected_v3_marker_must_name_exact_current_authorities(self) -> None:
        for required_path in (
            "`docs/contracts/native-close-fence-proof-v1.md`",
            "`docs/execution/slice-1b-contract.md`",
        ):
            with self.subTest(required_path=required_path), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                marker = root / "docs/contracts/capability-store-v3.md"
                marker.write_text(
                    marker.read_text(encoding="utf-8").replace(required_path, ""),
                    encoding="utf-8",
                )

                result = validate_capability_policy(root)

                self.assertTrue(any(required_path in item for item in result.errors))

    def test_required_contract_token_hidden_in_html_comment_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/contracts/capability-store-v4.md"
            token = "one separate one-byte read must return exactly zero"
            path = root / relative
            path.write_text(
                path.read_text(encoding="utf-8").replace(token, f"<!-- {token} -->"),
                encoding="utf-8",
            )
            _write_authority_manifest(root)

            result = validate_capability_policy(root)

            self.assertTrue(
                any("current capability-policy token missing" in item for item in result.errors)
            )

    def test_logical_markdown_authorization_findings_reach_integration_gate(self) -> None:
        fixtures = (
            "Implementation is **authorized**.\n",
            "We are authorized to implement Slice 1B.\n",
            "Capability - v - 9 remains applicable to this implementation.\n",
            "History is closed; Implementation may<br>commence.\n",
            "The claim is rejected, but implementation is [authorized](https://example.test).\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                relative = "docs/execution/slice-1b-contract.md"
                _append_and_rehash_authority(root, relative, fixture)

                result = validate_capability_policy(root)

                self.assertTrue(
                    any(
                        "defense in depth" in item and relative in item
                        for item in result.errors
                    )
                )

    def test_truthful_conditions_denials_and_examples_pass_integration(self) -> None:
        fixtures = (
            "Implementation is not authorized.\n",
            "Implementation may start only after exact-commit review passes.\n",
            "Before implementation is authorized, the stop gate remains.\n",
            "Never write: Implementation is authorized.\n",
            "Rejected proposal: the team may implement Slice 1B.\n",
            "```text\nImplementation is authorized.\n```\n",
            "> Implementation is authorized.\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _write_minimum_valid_tree(root)
                relative = "docs/execution/slice-1b-contract.md"
                _append_and_rehash_authority(root, relative, fixture)

                result = validate_capability_policy(root)

                self.assertEqual((), result.errors)

    def test_unsafe_post_close_soft_wrap_is_rejected_from_normalized_block(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/execution/slice-1b-contract.md"
            _append_and_rehash_authority(
                root, relative, "A post-close callback may be\nignored and serve the cache.\n"
            )

            result = validate_capability_policy(root)

            self.assertTrue(any("unsafe post-close/cache directive" in item for item in result.errors))

    def test_unsafe_post_close_text_in_fence_is_not_normative(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/execution/slice-1b-contract.md"
            _append_and_rehash_authority(
                root,
                relative,
                "````text\nA post-close callback may be ignored and serve the cache.\n````\n",
            )

            result = validate_capability_policy(root)

            self.assertEqual((), result.errors)

    def test_invalid_utf8_governed_markdown_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            relative = "docs/execution/slice-status.md"
            (root / relative).write_bytes(b"\xff")

            result = validate_capability_policy(root)

            self.assertTrue(any("cannot decode capability-policy file" in item for item in result.errors))

    def test_cli_runs_against_stage_appropriate_repository_target(self) -> None:
        candidate = _current_repository_candidate_commit(REPOSITORY_ROOT)
        command = [sys.executable, "-B", "scripts/validate_capability_policy.py"]
        if candidate is not None:
            command.extend(("--commit", candidate))
        completed = subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stdout)
        self.assertIn(
            "CAPABILITY_POLICY_WORKTREE_DIAGNOSTIC=PASS"
            if candidate is None
            else "CAPABILITY_POLICY_EXACT_COMMIT_GATE=PASS",
            completed.stdout,
        )
        self.assertIn("candidate=capability-v15", completed.stdout)
        self.assertIn("reviews=14", completed.stdout)
        self.assertIn(
            "formalGate=false" if candidate is None else "formalGate=true",
            completed.stdout,
        )

    def test_cli_exact_commit_gate_is_unambiguously_formal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_minimum_valid_tree(root)
            commit = _git_commit_all(root)
            completed = subprocess.run(
                [
                    sys.executable,
                    "-B",
                    os.fspath(REPOSITORY_ROOT / "scripts/validate_capability_policy.py"),
                    "--root",
                    os.fspath(root),
                    "--commit",
                    commit,
                ],
                cwd=root,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )

        self.assertEqual(0, completed.returncode, completed.stdout)
        self.assertIn("CAPABILITY_POLICY_EXACT_COMMIT_GATE=PASS", completed.stdout)
        self.assertIn(f"commit={commit}", completed.stdout)
        self.assertIn("formalGate=true", completed.stdout)


if __name__ == "__main__":
    unittest.main()
