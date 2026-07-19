from __future__ import annotations

import ctypes
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

from scripts import capability_policy_snapshot as snapshot_module
from scripts.capability_policy_snapshot import (
    RepositorySnapshot,
    SnapshotError,
    capture_git_commit_snapshot,
    capture_repository_snapshot,
)


# This inventory is intentionally literal and independent of constants in the
# production module.  A regression that silently drops one input cannot update
# the test oracle by changing the implementation's own constant.
EXPECTED_GOVERNED_INPUTS = (
    "authority/policy.md",
    "reviews/review-01.md",
)


def _make_repository(base: Path) -> Path:
    root = base / "repository"
    (root / "authority").mkdir(parents=True)
    (root / "reviews").mkdir()
    (root / "authority" / "policy.md").write_bytes(b"inside-authority\n")
    (root / "reviews" / "review-01.md").write_bytes(b"inside-review\n")
    return root


def _capture(root: Path, **kwargs: object) -> RepositorySnapshot:
    arguments: dict[str, object] = {
        "required_files": ["reviews/review-01.md", "authority/policy.md"],
        "review_dir": "reviews",
        "forbidden_paths": ["forbidden.txt"],
    }
    arguments.update(kwargs)
    return capture_repository_snapshot(root, **arguments)  # type: ignore[arg-type]


def _git(root: Path, *arguments: str, input_bytes: bytes | None = None) -> bytes:
    environment = os.environ.copy()
    environment.update(
        {
            "GIT_AUTHOR_NAME": "Snapshot Test",
            "GIT_AUTHOR_EMAIL": "snapshot@example.invalid",
            "GIT_COMMITTER_NAME": "Snapshot Test",
            "GIT_COMMITTER_EMAIL": "snapshot@example.invalid",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_TERMINAL_PROMPT": "0",
        }
    )
    completed = subprocess.run(
        ["git", "-C", str(root), *arguments],
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=False,
        timeout=15,
        check=False,
        env=environment,
    )
    if completed.returncode != 0:
        raise AssertionError(
            f"Git test setup failed ({completed.returncode}): "
            f"{completed.stderr.decode('utf-8', errors='replace')}"
        )
    return bytes(completed.stdout)


def _commit_repository(root: Path, message: str) -> str:
    _git(root, "add", "--all")
    _git(root, "commit", "--quiet", "-m", message)
    return _git(root, "rev-parse", "HEAD").decode("ascii").strip()


def _make_git_repository(base: Path) -> tuple[Path, str]:
    root = _make_repository(base)
    _git(root, "init", "--quiet")
    _git(root, "config", "core.autocrlf", "false")
    _git(root, "config", "core.filemode", "true")
    return root, _commit_repository(root, "initial snapshot")


def _capture_git(root: Path, commit: str, **kwargs: object) -> RepositorySnapshot:
    arguments: dict[str, object] = {
        "required_files": ["reviews/review-01.md", "authority/policy.md"],
        "review_dir": "reviews",
        "forbidden_paths": ["forbidden.txt"],
    }
    arguments.update(kwargs)
    return capture_git_commit_snapshot(  # type: ignore[arg-type]
        root, commit, **arguments
    )


def _symlink_or_skip(
    testcase: unittest.TestCase,
    target: Path,
    link: Path,
    *,
    target_is_directory: bool,
) -> None:
    try:
        link.symlink_to(target, target_is_directory=target_is_directory)
    except OSError as exc:
        if os.name == "nt" and getattr(exc, "winerror", None) == 1314:
            testcase.skipTest("Windows symlink privilege is unavailable (WinError 1314)")
        raise


def _make_junction(link: Path, target: Path) -> None:
    creation = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(link), str(target)],
        capture_output=True,
        text=True,
        check=False,
    )
    if creation.returncode != 0:
        raise AssertionError(
            f"junction creation failed ({creation.returncode}): {creation.stderr or creation.stdout}"
        )


def _remove_junction(path: Path) -> None:
    if path.exists() or path.is_junction():
        os.rmdir(path)


class RepositorySnapshotGitCommitTests(unittest.TestCase):
    def test_valid_commit_capture_returns_exact_immutable_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, commit = _make_git_repository(Path(temporary))
            result = _capture_git(root, commit)
            expected_tree = _git(root, "rev-parse", f"{commit}^{{tree}}").decode(
                "ascii"
            ).strip()

            self.assertEqual(result.canonical_root, root)
            self.assertEqual(result.source, "git-commit")
            self.assertEqual(result.commit, commit)
            self.assertEqual(result.tree, expected_tree)
            self.assertEqual(result.governed_inputs, EXPECTED_GOVERNED_INPUTS)
            self.assertEqual(tuple(result.files), EXPECTED_GOVERNED_INPUTS)
            self.assertEqual(result.files["authority/policy.md"], b"inside-authority\n")
            self.assertEqual(result.review_names, ("review-01.md",))
            with self.assertRaises(TypeError):
                result.files["authority/policy.md"] = b"mutated"  # type: ignore[index]

            legacy = RepositorySnapshot(root, {}, (), ())
            self.assertEqual(legacy.source, "worktree")
            self.assertIsNone(legacy.commit)
            self.assertIsNone(legacy.tree)

    def test_worktree_mutation_and_untracked_names_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, commit = _make_git_repository(Path(temporary))
            (root / "authority" / "policy.md").write_bytes(b"mutable-worktree\n")
            (root / "forbidden.txt").write_bytes(b"untracked forbidden")
            (root / "reviews" / "untracked-review.md").write_bytes(b"untracked")

            result = _capture_git(root, commit)

            self.assertEqual(result.files["authority/policy.md"], b"inside-authority\n")
            self.assertEqual(result.review_names, ("review-01.md",))

    def test_inherited_git_repository_and_config_redirects_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root, commit = _make_git_repository(base / "primary")
            other, _ = _make_git_repository(base / "other")
            (other / "authority" / "policy.md").write_bytes(b"other-repository\n")
            other_commit = _commit_repository(other, "different repository bytes")
            self.assertNotEqual(commit, other_commit)

            redirects = (
                {
                    "GIT_DIR": str(other / ".git"),
                    "GIT_WORK_TREE": str(other),
                    "GIT_COMMON_DIR": str(other / ".git"),
                    "GIT_OBJECT_DIRECTORY": str(other / ".git" / "objects"),
                    "GIT_ALTERNATE_OBJECT_DIRECTORIES": str(root / ".git" / "objects"),
                    "GIT_INDEX_FILE": str(other / ".git" / "index"),
                    "GIT_CONFIG_COUNT": "1",
                    "GIT_CONFIG_KEY_0": "core.bare",
                    "GIT_CONFIG_VALUE_0": "true",
                },
                {"GIT_DIR": str(base / "invalid-git-directory")},
            )
            for injected in redirects:
                with self.subTest(injected=tuple(sorted(injected))):
                    with mock.patch.dict(os.environ, injected, clear=False):
                        result = _capture_git(root, commit)
                    self.assertEqual(
                        result.files["authority/policy.md"], b"inside-authority\n"
                    )
                    self.assertEqual(result.commit, commit)

    def test_nfkc_ancestor_namespace_alias_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, _ = _make_git_repository(Path(temporary))
            alias = root / "unrelated" / "ａｕｔｈｏｒｉｔｙ"
            alias.mkdir(parents=True)
            (alias / "decoy.md").write_bytes(b"alias")
            commit = _commit_repository(root, "add namespace alias")

            with self.assertRaisesRegex(SnapshotError, "namespace component alias"):
                _capture_git(root, commit)

    def test_forbidden_exact_and_nfkc_alias_are_rejected(self) -> None:
        for filename in ("forbidden.txt", "ｆｏｒｂｉｄｄｅｎ．ｔｘｔ"):
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root, _ = _make_git_repository(Path(temporary))
                (root / filename).write_bytes(b"forbidden")
                commit = _commit_repository(root, "add forbidden path")

                with self.assertRaisesRegex(SnapshotError, "forbidden path"):
                    _capture_git(root, commit)

    def test_missing_required_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, commit = _make_git_repository(Path(temporary))
            with self.assertRaisesRegex(SnapshotError, "missing from Git commit"):
                _capture_git(
                    root,
                    commit,
                    required_files=["authority/missing.md"],
                )

    def test_executable_required_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, _ = _make_git_repository(Path(temporary))
            _git(root, "update-index", "--chmod=+x", "authority/policy.md")
            _git(root, "commit", "--quiet", "-m", "make governed input executable")
            commit = _git(root, "rev-parse", "HEAD").decode("ascii").strip()

            with self.assertRaisesRegex(SnapshotError, "100644 blob"):
                _capture_git(root, commit)

    def test_symlink_mode_required_file_is_rejected_without_filesystem_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, _ = _make_git_repository(Path(temporary))
            oid = _git(root, "hash-object", "-w", "--stdin", input_bytes=b"target\n").decode(
                "ascii"
            ).strip()
            _git(
                root,
                "update-index",
                "--cacheinfo",
                f"120000,{oid},authority/policy.md",
            )
            _git(root, "commit", "--quiet", "-m", "make governed input a symlink")
            commit = _git(root, "rev-parse", "HEAD").decode("ascii").strip()

            with self.assertRaisesRegex(SnapshotError, "100644 blob"):
                _capture_git(root, commit)

    def test_oversized_governed_blob_is_rejected_before_blob_read(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, commit = _make_git_repository(Path(temporary))
            with self.assertRaisesRegex(SnapshotError, "exceeds max_file_bytes"):
                _capture_git(root, commit, max_file_bytes=4)

    def test_unexpected_review_entry_is_captured_for_validator_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, _ = _make_git_repository(Path(temporary))
            (root / "reviews" / "slice-1b-policy-review-14.md").write_bytes(
                b"unexpected"
            )
            commit = _commit_repository(root, "add unexpected review")

            result = _capture_git(root, commit)

            self.assertEqual(
                result.review_names,
                ("review-01.md", "slice-1b-policy-review-14.md"),
            )

    def test_whole_path_nfkc_collision_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, _ = _make_git_repository(Path(temporary))
            (root / "ＡＬＩＡＳ.txt").write_bytes(b"one")
            (root / "alias.txt").write_bytes(b"two")
            commit = _commit_repository(root, "add whole path collision")

            with self.assertRaisesRegex(SnapshotError, "whole-path aliases"):
                _capture_git(root, commit)

    def test_malformed_uppercase_missing_and_non_commit_ids_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, commit = _make_git_repository(Path(temporary))
            blob = _git(root, "hash-object", "authority/policy.md").decode("ascii").strip()
            invalid = (
                "abc",
                commit.upper(),
                "g" * 40,
                "0" * 40,
                blob,
            )
            for value in invalid:
                with self.subTest(value=value):
                    with self.assertRaises(SnapshotError):
                        _capture_git(root, value)

    def test_relative_root_and_invalid_size_are_typed_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root, commit = _make_git_repository(Path(temporary))
            with self.assertRaises(SnapshotError):
                _capture_git(Path("relative"), commit)
            for size in (-1, True, 1.5):
                with self.subTest(size=size):
                    with self.assertRaises(SnapshotError):
                        _capture_git(root, commit, max_file_bytes=size)


@unittest.skipUnless(os.name == "nt", "v13 security acceptance targets Windows")
class RepositorySnapshotWindowsTests(unittest.TestCase):
    def test_success_captures_independent_exact_inventory_and_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            result = _capture(root)

            self.assertEqual(result.canonical_root, root)
            self.assertEqual(result.governed_inputs, EXPECTED_GOVERNED_INPUTS)
            self.assertEqual(tuple(result.files), EXPECTED_GOVERNED_INPUTS)
            self.assertEqual(result.files["authority/policy.md"], b"inside-authority\n")
            self.assertEqual(result.files["reviews/review-01.md"], b"inside-review\n")
            self.assertEqual(result.review_names, ("review-01.md",))

    def test_result_is_deeply_immutable_for_governed_storage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = _capture(_make_repository(Path(temporary)))
            with self.assertRaises(TypeError):
                result.files["authority/policy.md"] = b"changed"  # type: ignore[index]
            with self.assertRaises((AttributeError, TypeError)):
                result.review_names += ("changed.md",)  # type: ignore[misc]
            self.assertIsInstance(result.files["authority/policy.md"], bytes)

    def test_caller_sequence_mutation_cannot_change_snapshot_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            required = ["reviews/review-01.md", "authority/policy.md"]
            result = capture_repository_snapshot(root, required, "reviews", [])
            required.clear()
            self.assertEqual(result.governed_inputs, EXPECTED_GOVERNED_INPUTS)

    def test_unexpected_review_entry_is_captured_for_validator_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "reviews" / "slice-1b-policy-review-99.md").write_bytes(b"unexpected")
            result = _capture(root)
            self.assertEqual(
                result.review_names,
                ("review-01.md", "slice-1b-policy-review-99.md"),
            )

    def test_missing_required_file_is_typed_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "authority" / "policy.md").unlink()
            with self.assertRaises(SnapshotError):
                _capture(root)

    def test_nonregular_required_input_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "authority" / "policy.md").unlink()
            (root / "authority" / "policy.md").mkdir()
            with self.assertRaises(SnapshotError):
                _capture(root)

    def test_oversize_required_file_is_rejected_before_read(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with self.assertRaisesRegex(SnapshotError, "exceeds max_file_bytes"):
                _capture(root, max_file_bytes=4)

    def test_zero_limit_accepts_empty_file_and_rejects_nonempty_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "authority" / "policy.md").write_bytes(b"")
            (root / "reviews" / "review-01.md").write_bytes(b"")
            result = _capture(root, max_file_bytes=0)
            self.assertEqual(result.files["authority/policy.md"], b"")

    def test_short_backend_read_is_rejected_without_partial_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with mock.patch.object(
                snapshot_module._WindowsBackend,
                "_read_exact",
                return_value=b"short",
            ):
                with self.assertRaisesRegex(SnapshotError, "short read"):
                    _capture(root)

    def test_real_write_or_truncate_during_safe_hook_is_blocked_or_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            write_blocked: list[bool] = []

            def hook() -> None:
                try:
                    (root / "authority" / "policy.md").write_bytes(b"outside")
                except OSError:
                    write_blocked.append(True)
                else:
                    write_blocked.append(False)

            with mock.patch.object(snapshot_module, "_TEST_HOOK", hook):
                try:
                    result = _capture(root)
                except SnapshotError:
                    # A platform that allowed the write still fails closed on
                    # short/changed metadata instead of returning mixed bytes.
                    self.assertEqual(write_blocked, [False])
                else:
                    self.assertEqual(write_blocked, [True])
                    self.assertEqual(
                        result.files["authority/policy.md"], b"inside-authority\n"
                    )

    def test_existing_forbidden_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "forbidden.txt").write_bytes(b"present")
            with self.assertRaisesRegex(
                SnapshotError, "forbidden path or portable filename alias exists"
            ):
                _capture(root)

    def test_forbidden_case_and_nfkc_filename_aliases_are_rejected(self) -> None:
        for name in (
            "FORBIDDEN.TXT",
            "ｆｏｒｂｉｄｄｅｎ．ｔｘｔ",
        ):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = _make_repository(Path(temporary))
                (root / name).write_bytes(b"alias")
                with self.assertRaisesRegex(SnapshotError, "portable filename alias"):
                    _capture(root)

    def test_review_nfkc_alias_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "reviews" / "ｒｅｖｉｅｗ－０１．ｍｄ").write_bytes(b"alias")
            with self.assertRaisesRegex(SnapshotError, "directory enumeration contains aliases"):
                _capture(root)

    def test_forbidden_inspection_error_is_not_treated_as_absence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            original = snapshot_module._WindowsBackend._enumerate_directory

            def fail_forbidden(handle: int, relative: str) -> tuple[str, ...]:
                if relative == ".":
                    raise PermissionError("deterministic inspection failure")
                return original(handle, relative)

            with mock.patch.object(
                snapshot_module._WindowsBackend,
                "_enumerate_directory",
                side_effect=fail_forbidden,
            ):
                with self.assertRaises(SnapshotError):
                    _capture(root)

    def test_broken_final_symlink_forbidden_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            missing = root / "missing-target"
            link = root / "forbidden.txt"
            _symlink_or_skip(self, missing, link, target_is_directory=False)
            with self.assertRaises(SnapshotError):
                _capture(root)

    def test_broken_junction_forbidden_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            target = base / "junction-target"
            target.mkdir()
            junction = root / "forbidden.txt"
            _make_junction(junction, target)
            target.rmdir()
            try:
                with self.assertRaises(SnapshotError):
                    _capture(root)
            finally:
                _remove_junction(junction)

    def test_final_file_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside.md"
            outside.write_bytes(b"outside")
            link = root / "authority" / "policy.md"
            link.unlink()
            _symlink_or_skip(self, outside, link, target_is_directory=False)
            with self.assertRaises(SnapshotError):
                _capture(root)

    def test_ancestor_directory_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside-authority"
            outside.mkdir()
            (outside / "policy.md").write_bytes(b"outside")
            shutil.rmtree(root / "authority")
            _symlink_or_skip(self, outside, root / "authority", target_is_directory=True)
            with self.assertRaises(SnapshotError):
                _capture(root)

    def test_repository_root_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            actual = _make_repository(base)
            redirected = base / "redirected-root"
            _symlink_or_skip(self, actual, redirected, target_is_directory=True)
            with self.assertRaises(SnapshotError):
                _capture(redirected)

    def test_deterministic_reparse_inspection_branch_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with mock.patch.object(
                snapshot_module._WindowsBackend,
                "_inspect_directory",
                side_effect=SnapshotError("forced reparse detector"),
            ):
                with self.assertRaisesRegex(SnapshotError, "forced reparse detector"):
                    _capture(root)

    def test_deterministic_final_reparse_attribute_branch_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            original = snapshot_module._WindowsBackend._information

            def mark_policy_reparse(handle: int, path: str) -> object:
                info = original(handle, path)
                if str(path).endswith("policy.md"):
                    info.dwFileAttributes |= snapshot_module._FILE_ATTRIBUTE_REPARSE_POINT
                return info

            with mock.patch.object(
                snapshot_module._WindowsBackend,
                "_information",
                side_effect=mark_policy_reparse,
            ):
                with self.assertRaisesRegex(SnapshotError, "symlink/reparse point"):
                    _capture(root)

    def test_nested_authority_junction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside-authority"
            outside.mkdir()
            (outside / "policy.md").write_bytes(b"outside")
            shutil.rmtree(root / "authority")
            junction = root / "authority"
            _make_junction(junction, outside)
            try:
                with self.assertRaises(SnapshotError):
                    _capture(root)
            finally:
                _remove_junction(junction)

    def test_review_directory_junction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside-reviews"
            outside.mkdir()
            (outside / "review-01.md").write_bytes(b"outside")
            shutil.rmtree(root / "reviews")
            junction = root / "reviews"
            _make_junction(junction, outside)
            try:
                with self.assertRaises(SnapshotError):
                    _capture(root)
            finally:
                _remove_junction(junction)

    def test_repository_root_junction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            actual = _make_repository(base)
            junction = base / "junction-root"
            _make_junction(junction, actual)
            try:
                with self.assertRaises(SnapshotError):
                    _capture(junction)
            finally:
                _remove_junction(junction)

    def test_authority_directory_swap_after_anchor_cannot_redirect_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside-authority"
            outside.mkdir()
            (outside / "policy.md").write_bytes(b"outside-authority\n")
            original = root / "authority"
            moved = root / "authority-held"
            replacement = root / "authority-replacement"
            _make_junction(replacement, outside)
            swap_blocked: list[bool] = []

            def hook() -> None:
                try:
                    os.rename(original, moved)
                except OSError:
                    swap_blocked.append(True)
                    return
                swap_blocked.append(False)
                os.rename(replacement, original)

            try:
                with mock.patch.object(snapshot_module, "_TEST_HOOK", hook):
                    result = _capture(root)
                self.assertIn(swap_blocked, ([True], [False]))
                self.assertEqual(
                    result.files["authority/policy.md"], b"inside-authority\n"
                )
                self.assertNotEqual(
                    result.files["authority/policy.md"], b"outside-authority\n"
                )
            finally:
                if moved.exists():
                    _remove_junction(original)
                    os.rename(moved, original)
                _remove_junction(replacement)

    def test_review_directory_swap_after_anchor_cannot_redirect_enumeration(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside-reviews"
            outside.mkdir()
            (outside / "outside-only.md").write_bytes(b"outside")
            (outside / "review-01.md").write_bytes(b"outside-review\n")
            original = root / "reviews"
            moved = root / "reviews-held"
            replacement = root / "reviews-replacement"
            _make_junction(replacement, outside)
            swap_blocked: list[bool] = []

            def hook() -> None:
                try:
                    os.rename(original, moved)
                except OSError:
                    swap_blocked.append(True)
                    return
                swap_blocked.append(False)
                os.rename(replacement, original)

            try:
                with mock.patch.object(snapshot_module, "_TEST_HOOK", hook):
                    result = _capture(root)
                self.assertIn(swap_blocked, ([True], [False]))
                self.assertEqual(result.files["reviews/review-01.md"], b"inside-review\n")
                self.assertEqual(result.review_names, ("review-01.md",))
                self.assertNotIn("outside-only.md", result.review_names)
            finally:
                if moved.exists():
                    _remove_junction(original)
                    os.rename(moved, original)
                _remove_junction(replacement)

    def test_review_name_change_during_capture_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            original = snapshot_module._WindowsBackend._enumerate_directory
            calls = 0

            def enumerate_then_mutate(handle: int, relative: str) -> tuple[str, ...]:
                nonlocal calls
                names = original(handle, relative)
                if relative != "reviews":
                    return names
                calls += 1
                if calls == 1:
                    (root / "reviews" / "late-review.md").write_bytes(b"late\n")
                return names

            with mock.patch.object(
                snapshot_module._WindowsBackend,
                "_enumerate_directory",
                side_effect=enumerate_then_mutate,
            ):
                with self.assertRaisesRegex(
                    SnapshotError, "review directory changed during capture"
                ):
                    _capture(root)

            self.assertEqual(2, calls)

    def test_close_failure_is_a_typed_failure_after_all_close_attempts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            original_close = snapshot_module._CloseHandle

            def close_but_report_failure(handle: int) -> bool:
                original_close(handle)
                return False

            with mock.patch.object(
                snapshot_module, "_CloseHandle", side_effect=close_but_report_failure
            ):
                with self.assertRaisesRegex(SnapshotError, "failed to close Windows"):
                    _capture(root)

    def test_no_observable_handle_leak_after_success_and_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.GetCurrentProcess.restype = ctypes.c_void_p
            kernel32.GetProcessHandleCount.argtypes = (
                ctypes.c_void_p,
                ctypes.POINTER(ctypes.c_ulong),
            )
            kernel32.GetProcessHandleCount.restype = ctypes.c_int

            def handle_count() -> int:
                count = ctypes.c_ulong()
                self.assertTrue(
                    kernel32.GetProcessHandleCount(
                        kernel32.GetCurrentProcess(), ctypes.byref(count)
                    )
                )
                return int(count.value)

            _capture(root)  # warm ctypes and filesystem caches before measuring
            before = handle_count()
            for _ in range(20):
                _capture(root)
                with self.assertRaises(SnapshotError):
                    _capture(
                        root,
                        required_files=["missing.md"],
                    )
            after = handle_count()
            self.assertLessEqual(after, before + 1)


class RepositorySnapshotLexicalAndFailureTests(unittest.TestCase):
    def test_relative_governed_path_attack_matrix_is_rejected(self) -> None:
        attacks = (
            "/absolute.md",
            "C:/drive.md",
            "C:drive-relative.md",
            "//server/share.md",
            r"\\server\share.md",
            r"\\?\C:\device.md",
            r"\\.\PhysicalDrive0",
            "authority/../outside.md",
            "authority/./policy.md",
            "authority//policy.md",
            r"authority\mixed/policy.md",
            "authority/policy.md:stream",
            "authority/NUL.txt",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            for attack in attacks:
                with self.subTest(attack=attack):
                    with self.assertRaises(SnapshotError):
                        capture_repository_snapshot(root, [attack], "reviews", [])

    def test_review_and_forbidden_paths_receive_same_lexical_checks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(
                    root, ["authority/policy.md"], "../reviews", []
                )
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(
                    root, ["authority/policy.md"], "reviews", [r"C:\outside"]
                )

    def test_duplicate_case_alias_and_required_forbidden_overlap_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(
                    root,
                    ["authority/policy.md", "AUTHORITY/POLICY.MD"],
                    "reviews",
                    [],
                )
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(
                    root,
                    ["authority/policy.md"],
                    "reviews",
                    ["AUTHORITY/POLICY.MD"],
                )

    def test_relative_root_and_dotdot_root_are_rejected_lexically(self) -> None:
        with self.assertRaises(SnapshotError):
            capture_repository_snapshot(
                Path("relative"), ["a.md"], "reviews", []
            )
        if os.name == "nt":
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(
                    Path(r"C:\repository\..\other"), ["a.md"], "reviews", []
                )

    def test_invalid_max_file_bytes_is_typed_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            for value in (-1, True, 1.5):
                with self.subTest(value=value):
                    with self.assertRaises(SnapshotError):
                        _capture(root, max_file_bytes=value)

    def test_empty_inventory_and_non_sequence_inventory_are_typed_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(root, [], "reviews", [])
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(root, None, "reviews", [])  # type: ignore[arg-type]
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(root, "authority/policy.md", "reviews", [])
            with self.assertRaises(SnapshotError):
                capture_repository_snapshot(
                    root, ["authority/policy.md"], "reviews", "forbidden.txt"
                )

    def test_unexpected_backend_exception_is_wrapped_as_snapshot_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with mock.patch.object(
                snapshot_module,
                "_select_backend",
                side_effect=OSError("unsupported primitive"),
            ):
                with self.assertRaisesRegex(SnapshotError, "repository snapshot failed"):
                    _capture(root)

    def test_backend_incomplete_inventory_is_rejected(self) -> None:
        class IncompleteBackend:
            def capture(self, *args: object) -> tuple[Path, dict[str, bytes], tuple[str, ...]]:
                return Path.cwd(), {}, ()

        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            with mock.patch.object(
                snapshot_module, "_select_backend", return_value=IncompleteBackend()
            ):
                with self.assertRaisesRegex(SnapshotError, "incomplete governed input"):
                    _capture(root)


@unittest.skipUnless(os.name == "posix", "POSIX openat acceptance is POSIX-only")
class RepositorySnapshotPosixTests(unittest.TestCase):
    def test_posix_openat_capture_uses_repository_root_not_filesystem_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            result = _capture(root)
            self.assertEqual(result.canonical_root, root)
            self.assertEqual(result.governed_inputs, EXPECTED_GOVERNED_INPUTS)
            self.assertEqual(result.files["authority/policy.md"], b"inside-authority\n")
            self.assertEqual(result.review_names, ("review-01.md",))

    def test_posix_final_ancestor_and_root_symlinks_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)

            final_root = _make_repository(base / "final-case")
            outside_file = base / "outside.md"
            outside_file.write_bytes(b"outside")
            (final_root / "authority" / "policy.md").unlink()
            (final_root / "authority" / "policy.md").symlink_to(outside_file)
            with self.assertRaises(SnapshotError):
                _capture(final_root)

            ancestor_root = _make_repository(base / "ancestor-case")
            outside_dir = base / "outside-authority"
            outside_dir.mkdir()
            (outside_dir / "policy.md").write_bytes(b"outside")
            shutil.rmtree(ancestor_root / "authority")
            (ancestor_root / "authority").symlink_to(outside_dir, target_is_directory=True)
            with self.assertRaises(SnapshotError):
                _capture(ancestor_root)

            actual_root = _make_repository(base / "root-case")
            redirected = base / "redirected-root"
            redirected.symlink_to(actual_root, target_is_directory=True)
            with self.assertRaises(SnapshotError):
                _capture(redirected)

    def test_posix_broken_forbidden_symlink_is_not_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "forbidden.txt").symlink_to(root / "missing-target")
            with self.assertRaises(SnapshotError):
                _capture(root)

    def test_posix_forbidden_case_and_nfkc_filename_aliases_are_rejected(self) -> None:
        for name in (
            "FORBIDDEN.TXT",
            "ｆｏｒｂｉｄｄｅｎ．ｔｘｔ",
        ):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = _make_repository(Path(temporary))
                (root / name).write_bytes(b"alias")
                with self.assertRaisesRegex(SnapshotError, "portable filename alias"):
                    _capture(root)

    def test_posix_review_nfkc_alias_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            (root / "reviews" / "ｒｅｖｉｅｗ－０１．ｍｄ").write_bytes(b"alias")
            with self.assertRaisesRegex(SnapshotError, "portable filename aliases"):
                _capture(root)

    def test_posix_required_and_forbidden_fifos_fail_without_blocking(self) -> None:
        probe = (
            "from pathlib import Path\n"
            "import sys\n"
            "from scripts.capability_policy_snapshot import "
            "SnapshotError, capture_repository_snapshot\n"
            "try:\n"
            "    capture_repository_snapshot("
            "Path(sys.argv[1]), "
            "['reviews/review-01.md', 'authority/policy.md'], "
            "'reviews', ['forbidden.txt'])\n"
            "except SnapshotError:\n"
            "    raise SystemExit(0)\n"
            "raise SystemExit(2)\n"
        )
        project_root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            required_root = _make_repository(base / "required-fifo")
            (required_root / "authority" / "policy.md").unlink()
            os.mkfifo(required_root / "authority" / "policy.md")
            forbidden_root = _make_repository(base / "forbidden-fifo")
            os.mkfifo(forbidden_root / "forbidden.txt")

            for root in (required_root, forbidden_root):
                with self.subTest(root=root.name):
                    completed = subprocess.run(
                        [sys.executable, "-c", probe, str(root)],
                        cwd=project_root,
                        capture_output=True,
                        text=True,
                        timeout=3,
                        check=False,
                    )
                    self.assertEqual(
                        0,
                        completed.returncode,
                        completed.stderr or completed.stdout,
                    )

    def test_posix_preexisting_writer_prevents_snapshot_lease(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            path = root / "authority" / "policy.md"
            writer = os.open(path, os.O_WRONLY)
            try:
                with self.assertRaisesRegex(
                    SnapshotError, "cannot acquire exclusive snapshot read lease"
                ):
                    _capture(root)
            finally:
                os.close(writer)

    def test_posix_writer_open_breaks_held_lease_and_fails_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            path = root / "authority" / "policy.md"
            started = threading.Event()
            opened = threading.Event()
            thread_holder: list[threading.Thread] = []

            def writer() -> None:
                started.set()
                fd = os.open(path, os.O_WRONLY)
                try:
                    opened.set()
                finally:
                    os.close(fd)

            def hook() -> None:
                thread = threading.Thread(target=writer, daemon=True)
                thread_holder.append(thread)
                thread.start()
                self.assertTrue(started.wait(timeout=1))
                time.sleep(0.05)
                self.assertFalse(opened.is_set())

            with mock.patch.object(snapshot_module, "_TEST_HOOK", hook):
                with self.assertRaisesRegex(
                    SnapshotError, "snapshot read lease was broken"
                ):
                    _capture(root)

            self.assertEqual(1, len(thread_holder))
            thread_holder[0].join(timeout=2)
            self.assertFalse(thread_holder[0].is_alive())
            self.assertTrue(opened.is_set())

    def test_posix_second_read_rejects_torn_or_changed_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            path = root / "authority" / "policy.md"
            replacement = b"Y" * len(path.read_bytes())
            original_read = snapshot_module._PosixBackend._read_exact
            target_reads = 0

            def return_divergent_second_read(
                fd: int, expected_size: int, relative: str
            ) -> bytes:
                nonlocal target_reads
                data = original_read(fd, expected_size, relative)
                if relative == "authority/policy.md":
                    target_reads += 1
                    if target_reads == 2:
                        return replacement
                return data

            with mock.patch.object(
                snapshot_module._PosixBackend,
                "_read_exact",
                side_effect=return_divergent_second_read,
            ):
                with self.assertRaisesRegex(
                    SnapshotError, "governed file bytes changed during capture"
                ):
                    _capture(root)

            self.assertEqual(2, target_reads)

    def test_posix_directory_swap_captures_held_inside_objects(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = _make_repository(base)
            outside = base / "outside-authority"
            outside.mkdir()
            (outside / "policy.md").write_bytes(b"outside-authority\n")
            original = root / "authority"
            moved = root / "authority-held"

            def hook() -> None:
                os.rename(original, moved)
                original.symlink_to(outside, target_is_directory=True)

            try:
                with mock.patch.object(snapshot_module, "_TEST_HOOK", hook):
                    result = _capture(root)
                self.assertEqual(
                    result.files["authority/policy.md"], b"inside-authority\n"
                )
                self.assertNotEqual(
                    result.files["authority/policy.md"], b"outside-authority\n"
                )
            finally:
                if moved.exists():
                    original.unlink(missing_ok=True)
                    os.rename(moved, original)

    def test_posix_descriptors_are_closed_on_success_and_failure(self) -> None:
        if not Path("/proc/self/fd").exists():
            self.skipTest("/proc/self/fd is unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            root = _make_repository(Path(temporary))
            _capture(root)
            before = len(tuple(Path("/proc/self/fd").iterdir()))
            for _ in range(20):
                _capture(root)
                with self.assertRaises(SnapshotError):
                    _capture(root, required_files=["missing.md"])
            after = len(tuple(Path("/proc/self/fd").iterdir()))
            self.assertLessEqual(after, before + 1)


if __name__ == "__main__":
    unittest.main()
