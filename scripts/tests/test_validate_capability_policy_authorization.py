from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import validate_capability_policy_authorization as subject


SOURCE_ROOT = Path(__file__).resolve().parents[2]
_TEMPLATE_HOLDER: tempfile.TemporaryDirectory[str] | None = None
_TEMPLATE_ROOT: Path | None = None
_TEMPLATE_CANDIDATE: str | None = None


def canonical(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        .encode("utf-8")
        + b"\n"
    )


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _git_at(root: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
    completed = subprocess.run(
        ["git", "-C", os.fspath(root), *args],
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode:
        raise RuntimeError(
            f"fixture git failed {args!r}: {completed.stderr.decode('utf-8', 'replace')}"
        )
    return completed.stdout


def _write_at(root: Path, relative: str, raw: bytes) -> None:
    path = root.joinpath(*relative.split("/"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)


def _candidate_template() -> tuple[Path, str]:
    global _TEMPLATE_HOLDER, _TEMPLATE_ROOT, _TEMPLATE_CANDIDATE
    if _TEMPLATE_ROOT is not None and _TEMPLATE_CANDIDATE is not None:
        return _TEMPLATE_ROOT, _TEMPLATE_CANDIDATE
    _TEMPLATE_HOLDER = tempfile.TemporaryDirectory()
    root = Path(_TEMPLATE_HOLDER.name) / "template"
    subprocess.run(
        ["git", "init", "--quiet", os.fspath(root)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    source_objects_raw = _git_at(SOURCE_ROOT, "rev-parse", "--git-path", "objects").decode().strip()
    source_objects = Path(source_objects_raw)
    if not source_objects.is_absolute():
        source_objects = (SOURCE_ROOT / source_objects).resolve()
    alternates = root / ".git" / "objects" / "info" / "alternates"
    alternates.parent.mkdir(parents=True, exist_ok=True)
    alternates.write_bytes(os.fsencode(source_objects) + b"\n")
    _git_at(root, "config", "user.name", "Policy Test")
    _git_at(root, "config", "user.email", "policy-test@example.invalid")
    _git_at(root, "config", "core.autocrlf", "false")
    _git_at(
        root,
        "update-ref",
        f"refs/tags/{subject.ROLLBACK_TAG}",
        subject.ROLLBACK_TAG_OBJECT,
    )
    _write_at(root, ".gitattributes", b"* text=auto\n*.txt text eol=lf\n")

    authority_raw: dict[str, bytes] = {}
    for index, path in enumerate(subject.AUTHORITY_PATHS, start=1):
        raw = f"authority {index} {subject.POLICY_REVISION}\n".encode()
        _write_at(root, path, raw)
        authority_raw[path] = raw
    _write_at(
        root,
        subject.LEDGER_PATH,
        f"# final {subject.POLICY_REVISION} precommit ledger\nPASS\n".encode(),
    )
    for path in subject.VALIDATOR_PATHS:
        _write_at(root, path, b"# authorization validator fixture\n")
    for path, expected_hash in subject.FROZEN_HISTORICAL_V14_EVIDENCE:
        raw = SOURCE_ROOT.joinpath(*path.split("/")).read_bytes()
        if sha256(raw) != expected_hash:
            raise AssertionError(f"repository frozen v14 evidence hash drifted: {path}")
        _write_at(root, path, raw)
    rejected_rows: list[dict[str, str]] = []
    for number, path in enumerate(subject.REJECTED_REVIEW_PATHS, start=1):
        raw = f"# rejected capability-v{number}\n".encode()
        _write_at(root, path, raw)
        rejected_rows.append(
            {
                "review": path,
                "review_sha256": sha256(raw),
                "revision": f"capability-v{number}",
            }
        )
    manifest = {
        "authorities": [
            {
                "path": path,
                "revision": subject.POLICY_REVISION,
                "sha256": sha256(authority_raw[path]),
            }
            for path in subject.AUTHORITY_PATHS
        ],
        "candidate_revision": subject.POLICY_REVISION,
        "implementation_authorized": False,
        "rejected_revisions": rejected_rows,
        "schema": "capability-policy-authority-v1",
        "status": "CANDIDATE_REREVIEW_REQUIRED",
    }
    _write_at(root, subject.MANIFEST_PATH, canonical(manifest))
    _git_at(root, "add", "-A")
    _git_at(root, "commit", "--quiet", "-m", "candidate")
    candidate = _git_at(root, "rev-parse", "HEAD").decode("ascii").strip()
    _git_at(root, "branch", "candidate-fixture", candidate)
    _TEMPLATE_ROOT = root
    _TEMPLATE_CANDIDATE = candidate
    return root, candidate


class AuthorizationFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "repository"
        template, candidate = _candidate_template()
        self.git(
            "clone",
            "--quiet",
            "--shared",
            "-c",
            "core.autocrlf=false",
            os.fspath(template),
            os.fspath(self.root),
            cwd=None,
        )
        self.git("config", "user.name", "Policy Test")
        self.git("config", "user.email", "policy-test@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.candidate = candidate

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(
        self,
        *args: str,
        cwd: Path | None = ...,
        input_bytes: bytes | None = None,
        check: bool = True,
    ) -> bytes:
        command = ["git"]
        if cwd is ...:
            command += ["-C", os.fspath(self.root)]
        elif cwd is not None:
            command += ["-C", os.fspath(cwd)]
        command += list(args)
        completed = subprocess.run(
            command,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if check and completed.returncode:
            self.fail(
                f"command failed {command!r}: "
                f"{completed.stderr.decode('utf-8', 'replace')}"
            )
        return completed.stdout

    def write(self, relative: str, raw: bytes) -> None:
        path = self.root.joinpath(*relative.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def read(self, relative: str) -> bytes:
        return self.root.joinpath(*relative.split("/")).read_bytes()

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "--quiet", "-m", message)
        return self.git("rev-parse", "HEAD").decode("ascii").strip()

    def head(self) -> str:
        return self.git("rev-parse", "HEAD").decode("ascii").strip()

    def validate_authorization(
        self,
        candidate: str | None = None,
        head: str | None = None,
    ) -> subject.CapabilityPolicyAuthorizationResult:
        return subject.validate_capability_policy_authorization(
            self.root,
            self.candidate if candidate is None else candidate,
            self.head() if head is None else head,
        )

    def review_payloads(
        self, target: dict[str, object], target_hash: str
    ) -> list[dict[str, object]]:
        return [
            {
                "finding_counts": {"p0": 0, "p1": 0, "p2": 0},
                "read_only": True,
                "repository_mutated": False,
                "review_id": f"{number:02d}",
                "review_lane": subject.REVIEW_LANES[number - 1],
                "reviewer_id": f"reviewer-{number}",
                "schema": "capability-policy-exact-review-v1",
                "session_id": str(number) * 32,
                "target": target,
                "target_sha256": target_hash,
                "verdict": "APPROVE_NO_ACTIONABLE_FINDINGS",
            }
            for number in range(1, 4)
        ]

    def create_authorization(
        self,
        *,
        review_mutator=None,
        record_mutator=None,
        raw_overrides: dict[str, bytes] | None = None,
        omit: tuple[str, ...] = (),
        extra: dict[str, bytes] | None = None,
        target: dict[str, object] | None = None,
        target_hash: str | None = None,
    ) -> str:
        if target is None or target_hash is None:
            built_target, built_hash = subject.construct_candidate_target(
                self.root, self.candidate
            )
            target = built_target
            target_hash = built_hash
        reviews = self.review_payloads(target, target_hash)
        if review_mutator is not None:
            review_mutator(reviews)
        raw_overrides = raw_overrides or {}
        review_raw: dict[str, bytes] = {}
        for path, payload in zip(subject.REVIEW_PATHS, reviews, strict=True):
            raw = raw_overrides.get(path, canonical(payload))
            review_raw[path] = raw
            if path not in omit:
                self.write(path, raw)
        record: dict[str, object] = {
            "authorization_scope": "SLICE_1B_IMPLEMENTATION_ONLY",
            "excluded_claims": list(subject.EXCLUDED_CLAIMS),
            "implementation_authorized": True,
            "quorum": {
                "required": 3,
                "reviews": [
                    {"path": path, "sha256": sha256(review_raw[path])}
                    for path in subject.REVIEW_PATHS
                    if path not in omit
                ],
                "rule": subject.QUORUM_RULE,
            },
            "rollback_baseline": dict(subject.ROLLBACK_BASELINE),
            "schema": "capability-policy-authorization-v1",
            "status": "AUTHORIZED",
            "target": target,
            "target_sha256": target_hash,
        }
        if record_mutator is not None:
            record_mutator(record)
        if subject.AUTHORIZATION_PATH not in omit:
            self.write(
                subject.AUTHORIZATION_PATH,
                raw_overrides.get(subject.AUTHORIZATION_PATH, canonical(record)),
            )
        for path, raw in (extra or {}).items():
            self.write(path, raw)
        return self.commit("authorization")

    def assert_rejected(self, result, fragment: str | None = None) -> None:
        self.assertFalse(result.ok, result)
        if fragment is not None:
            self.assertIn(fragment, "\n".join(result.errors))


class CandidateTests(AuthorizationFixture):
    def test_positive_candidate_constructs_exact_target_but_does_not_authorize(self) -> None:
        result = subject.validate_capability_policy_candidate(self.root, self.candidate)
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(self.candidate, result.candidate_commit)
        self.assertEqual(subject.POLICY_REVISION, result.target["candidate_revision"])
        self.assertEqual(
            sha256(canonical(result.target)),
            result.target_sha256,
        )
        self.assertNotIn("implementation_authorized", result.target)
        self.assertEqual(
            {
                "authorities",
                "authority_manifest",
                "candidate_commit",
                "candidate_revision",
                "candidate_tree",
                "git_object_format",
                "precommit_ledger",
            },
            set(result.target),
        )

    def test_symbolic_or_non_lowercase_candidate_is_rejected(self) -> None:
        for value in ("HEAD", self.candidate.upper()):
            with self.subTest(value=value):
                result = subject.validate_capability_policy_candidate(self.root, value)
                self.assert_rejected(result, "exact lowercase 40-hex sha1")

    def test_malformed_candidate_type_is_rejected_without_crashing(self) -> None:
        result = subject.validate_capability_policy_candidate(
            self.root, None  # type: ignore[arg-type]
        )
        self.assert_rejected(result, "exact lowercase 40-hex sha1")
        with self.assertRaises(subject.AuthorizationValidationError) as caught:
            subject.construct_candidate_target(
                self.root, None  # type: ignore[arg-type]
            )
        self.assertIn("exact lowercase 40-hex sha1", str(caught.exception))

    def test_case_insensitive_git_environment_redirects_are_scrubbed(self) -> None:
        hostile = {
            "gIt_DiR": os.fspath(self.root / "missing-git-dir"),
            "Git_Config_Count": "1",
            "GIT_CONFIG_KEY_0": "core.repositoryformatversion",
            "GIT_CONFIG_VALUE_0": "999",
        }
        with mock.patch.dict(os.environ, hostile):
            result = subject.validate_capability_policy_candidate(
                self.root, self.candidate
            )
        self.assertTrue(result.ok, result.errors)

    def test_replace_ref_does_not_change_exact_candidate_object_verdict(self) -> None:
        self.git("replace", self.candidate, subject.ROLLBACK_COMMIT)
        result = subject.validate_capability_policy_candidate(self.root, self.candidate)
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(self.candidate, result.candidate_commit)

    def test_reserved_exact_path_and_portable_alias_are_rejected(self) -> None:
        parent, name = subject.REVIEW_PATHS[0].rsplit("/", 1)
        alias = f"{parent}/{name.upper()}"
        self.write(alias, b"ignored alias\n")
        self.git("add", alias)
        self.git("commit", "--quiet", "-m", "alias")
        self.candidate = self.git("rev-parse", "HEAD").decode("ascii").strip()
        result = subject.validate_capability_policy_candidate(self.root, self.candidate)
        self.assert_rejected(result, "reserved authorization path or alias")

    def test_historical_v14_mutation_is_rejected(self) -> None:
        path, _expected_hash = subject.FROZEN_HISTORICAL_V14_EVIDENCE[0]
        self.write(path, self.read(path) + b"mutated\n")
        self.candidate = self.commit("mutate historical v14 authorization")

        result = subject.validate_capability_policy_candidate(self.root, self.candidate)

        self.assert_rejected(result, "frozen historical v14 evidence hash mismatch")

    def test_historical_v14_omission_is_rejected(self) -> None:
        path, _expected_hash = subject.FROZEN_HISTORICAL_V14_EVIDENCE[1]
        self.git("rm", "--quiet", path)
        self.candidate = self.commit("omit historical v14 exact review")

        result = subject.validate_capability_policy_candidate(self.root, self.candidate)

        self.assert_rejected(result, "protected Git path is missing")

    def test_historical_v14_portable_alias_is_rejected(self) -> None:
        path, _expected_hash = subject.FROZEN_HISTORICAL_V14_EVIDENCE[0]
        parent, name = path.rsplit("/", 1)
        alias = f"{parent}/\uff43{name[1:]}"
        self.git("mv", path, alias)
        self.candidate = self.commit("alias historical v14 authorization")

        result = subject.validate_capability_policy_candidate(self.root, self.candidate)

        self.assert_rejected(result, "protected Git path has a portable alias")

    def test_true_candidate_manifest_is_rejected(self) -> None:
        payload = json.loads(self.read(subject.MANIFEST_PATH))
        payload["implementation_authorized"] = True
        self.write(subject.MANIFEST_PATH, canonical(payload))
        self.candidate = self.commit("invalid true manifest")
        result = subject.validate_capability_policy_candidate(self.root, self.candidate)
        self.assert_rejected(result, "implementation_authorized=false")

    def test_dirty_worktree_does_not_change_frozen_candidate_object_verdict(self) -> None:
        self.write("untracked.txt", b"dirty\n")
        result = subject.validate_capability_policy_candidate(self.root, self.candidate)
        self.assertTrue(result.ok, result.errors)


class AuthorizationPositiveTests(AuthorizationFixture):
    def test_positive_initial_authorization(self) -> None:
        def boundary_ids(reviews):
            reviews[0]["reviewer_id"] = "a"
            reviews[1]["reviewer_id"] = "b" * 64
            reviews[2]["reviewer_id"] = "c"

        authorization = self.create_authorization(review_mutator=boundary_ids)
        result = self.validate_authorization()
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(self.candidate, result.candidate_commit)
        self.assertEqual(authorization, result.authorization_commit)
        self.assertEqual(authorization, result.head_commit)

    def test_positive_linear_descendant_is_automatically_derived(self) -> None:
        authorization = self.create_authorization()
        self.write("app/src/main/kotlin/Implementation.kt", b"class Implementation\n")
        descendant = self.commit("implementation")
        self.assertNotEqual(authorization, descendant)
        result = self.validate_authorization()
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(authorization, result.authorization_commit)
        self.assertEqual(descendant, result.head_commit)

    def test_dirty_worktree_and_index_do_not_change_explicit_object_verdict(self) -> None:
        head = self.create_authorization()
        governed = subject.AUTHORITY_PATHS[0]
        self.write(governed, self.read(governed) + b"uncommitted mutation\n")
        self.write("untracked.txt", b"untracked mutation\n")
        self.git("add", governed)
        result = self.validate_authorization(head=head)
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(head, result.head_commit)

    def test_symbolic_head_move_and_checkout_bytes_do_not_change_explicit_h(self) -> None:
        head = self.create_authorization()
        self.git("switch", "--quiet", "--detach", self.candidate)
        self.assertEqual(self.candidate, self.head())
        result = self.validate_authorization(head=head)
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(head, result.head_commit)

    def test_moved_and_deleted_live_rollback_ref_do_not_change_object_verdict(self) -> None:
        head = self.create_authorization()
        self.git("update-ref", f"refs/tags/{subject.ROLLBACK_TAG}", self.candidate)
        moved = self.validate_authorization(head=head)
        self.assertTrue(moved.ok, moved.errors)
        self.git("update-ref", "-d", f"refs/tags/{subject.ROLLBACK_TAG}")
        deleted = self.validate_authorization(head=head)
        self.assertTrue(deleted.ok, deleted.errors)

    def test_cli_requires_head_only_for_authorization_and_reports_explicit_h(self) -> None:
        head = self.create_authorization()
        invalid_invocations = (
            (
                [
                    "--root",
                    os.fspath(self.root),
                    "--stage",
                    "authorization",
                    "--candidate-commit",
                    self.candidate,
                ],
                "--head-commit is required",
            ),
            (
                [
                    "--root",
                    os.fspath(self.root),
                    "--stage",
                    "candidate",
                    "--candidate-commit",
                    self.candidate,
                    "--head-commit",
                    head,
                ],
                "--head-commit is only valid",
            ),
        )
        for argv, message in invalid_invocations:
            with self.subTest(argv=argv), mock.patch("sys.stderr", new_callable=io.StringIO) as stderr:
                with self.assertRaises(SystemExit) as caught:
                    subject.main(argv)
                self.assertEqual(2, caught.exception.code)
                self.assertIn(message, stderr.getvalue())

        with mock.patch("sys.stdout", new_callable=io.StringIO) as stdout:
            exit_code = subject.main(
                [
                    "--root",
                    os.fspath(self.root),
                    "--stage",
                    "authorization",
                    "--candidate-commit",
                    self.candidate,
                    "--head-commit",
                    head,
                ]
            )
        self.assertEqual(0, exit_code)
        self.assertIn(f"head={head}", stdout.getvalue())


class AuthorizationNegativeTests(AuthorizationFixture):
    def test_missing_review_is_rejected(self) -> None:
        self.create_authorization(omit=(subject.REVIEW_PATHS[2],))
        result = self.validate_authorization()
        self.assert_rejected(result, "delta mismatch")

    def test_missing_and_extra_review_fields_are_rejected(self) -> None:
        def mutate(reviews):
            reviews[0].pop("read_only")
            reviews[1]["extra"] = "forbidden"

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "top-level fields mismatch")

    def test_stale_but_self_consistent_target_is_rejected(self) -> None:
        target, _target_hash = subject.construct_candidate_target(self.root, self.candidate)
        stale = dict(target)
        stale["candidate_tree"] = "0" * 40
        stale_hash = sha256(canonical(stale))
        self.create_authorization(target=stale, target_hash=stale_hash)
        result = self.validate_authorization()
        self.assert_rejected(result, "target does not match candidate objects")

    def test_duplicate_reviewer_session_and_lane_are_rejected(self) -> None:
        def mutate(reviews):
            reviews[1]["reviewer_id"] = reviews[0]["reviewer_id"]
            reviews[1]["session_id"] = reviews[0]["session_id"]
            reviews[1]["review_lane"] = reviews[0]["review_lane"]

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        joined = "\n".join(result.errors)
        self.assertFalse(result.ok)
        self.assertIn("distinct reviewer_id", joined)
        self.assertIn("distinct session_id", joined)
        self.assertIn("distinct review_lane", joined)

    def test_duplicate_review_id_is_rejected(self) -> None:
        def mutate(reviews):
            reviews[1]["review_id"] = "01"

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "review_id mismatch")

    def test_reviewer_id_length_and_trailing_punctuation_are_rejected(self) -> None:
        def mutate(reviews):
            reviews[0]["reviewer_id"] = "a" * 65
            reviews[1]["reviewer_id"] = "bad-"

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        joined = "\n".join(result.errors)
        self.assertFalse(result.ok)
        self.assertGreaterEqual(joined.count("reviewer_id is not lowercase ASCII"), 2)

    def test_malformed_identity_types_reject_without_crashing(self) -> None:
        def mutate(reviews):
            reviews[0]["review_id"] = ["01"]
            reviews[1]["reviewer_id"] = {"bad": "type"}
            reviews[2]["session_id"] = ["3" * 32]
            reviews[2]["review_lane"] = {"bad": "lane"}

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "non-string")

    def test_nonzero_finding_is_rejected(self) -> None:
        def mutate(reviews):
            reviews[2]["finding_counts"]["p1"] = 1

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "integer zero findings")

    def test_conditional_verdict_is_rejected(self) -> None:
        def mutate(reviews):
            reviews[0]["verdict"] = "APPROVE_NO_ACTIONABLE_FINDINGS_IF_TESTS_PASS"

        self.create_authorization(review_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "not unconditional approval")

    def test_wrong_parent_is_rejected(self) -> None:
        target, target_hash = subject.construct_candidate_target(self.root, self.candidate)
        self.write("intervening.txt", b"wrong parent\n")
        self.commit("intervening")
        self.create_authorization(target=target, target_hash=target_hash)
        result = self.validate_authorization()
        self.assert_rejected(result, "delta mismatch")

    def test_merge_authorization_commit_is_rejected(self) -> None:
        target, target_hash = subject.construct_candidate_target(self.root, self.candidate)
        self.git("switch", "--quiet", "-c", "side", self.candidate)
        self.write("side.txt", b"side\n")
        self.commit("side")
        self.git("switch", "--quiet", "-C", "main", self.candidate)
        self.git("merge", "--quiet", "--no-ff", "--no-commit", "side")
        self.create_authorization(target=target, target_hash=target_hash)
        result = self.validate_authorization()
        self.assert_rejected(result, "not linear")

    def test_extra_authorization_diff_is_rejected(self) -> None:
        self.create_authorization(extra={"unexpected.txt": b"extra\n"})
        result = self.validate_authorization()
        self.assert_rejected(result, "delta mismatch")

    def test_symlink_mode_review_is_rejected(self) -> None:
        self.create_authorization()
        path = subject.REVIEW_PATHS[0]
        blob = self.git("hash-object", "-w", "--stdin", input_bytes=b"target\n").decode().strip()
        self.git("update-index", "--add", "--cacheinfo", f"120000,{blob},{path}")
        self.git("commit", "--quiet", "-m", "symlink mode")
        result = self.validate_authorization()
        self.assert_rejected(result, "bytes or mode changed")

    def test_malformed_review_json_is_rejected(self) -> None:
        self.create_authorization(
            raw_overrides={subject.REVIEW_PATHS[1]: b'{"broken":\n'}
        )
        result = self.validate_authorization()
        self.assert_rejected(result, "exact review 02 is invalid")

    def test_cherry_picked_authorization_is_rejected(self) -> None:
        authorization = self.create_authorization()
        self.git("switch", "--quiet", "-c", "cherry", self.candidate)
        self.write("new-candidate.txt", b"different candidate\n")
        self.commit("different candidate")
        self.git("cherry-pick", "--quiet", authorization)
        result = self.validate_authorization()
        self.assert_rejected(result, "delta mismatch")

    def test_required_candidate_argument_must_match_record_target(self) -> None:
        head = self.create_authorization()
        result = self.validate_authorization(
            candidate=subject.ROLLBACK_COMMIT,
            head=head,
        )
        self.assert_rejected(result, "does not match the required exact candidate")

    def test_symbolic_or_non_lowercase_head_commit_is_rejected(self) -> None:
        head = self.create_authorization()
        for value in ("HEAD", head.upper()):
            with self.subTest(value=value):
                result = self.validate_authorization(head=value)
                self.assert_rejected(result, "exact lowercase 40-hex sha1")

    def test_malformed_candidate_type_in_authorization_is_rejected_without_crashing(self) -> None:
        head = self.create_authorization()
        result = subject.validate_capability_policy_authorization(
            self.root,
            None,  # type: ignore[arg-type]
            head,
        )
        self.assert_rejected(result, "exact lowercase 40-hex sha1")

    def test_malformed_head_type_is_rejected_without_crashing(self) -> None:
        self.create_authorization()
        result = subject.validate_capability_policy_authorization(
            self.root,
            self.candidate,
            None,  # type: ignore[arg-type]
        )
        self.assert_rejected(result, "exact lowercase 40-hex sha1")

    def test_wrong_exact_head_commit_is_rejected(self) -> None:
        self.create_authorization()
        result = self.validate_authorization(head=self.candidate)
        self.assert_rejected(
            result, "head commit H equals candidate and has no authorization commit"
        )

    def test_replace_ref_does_not_change_literal_authorization_ancestry(self) -> None:
        head = self.create_authorization()
        self.git("replace", head, subject.ROLLBACK_COMMIT)
        result = self.validate_authorization(head=head)
        self.assertTrue(result.ok, result.errors)

    def test_protected_portable_alias_added_after_authorization_is_rejected(self) -> None:
        self.create_authorization()
        original = subject.VALIDATOR_PATHS[0]
        parent, name = original.rsplit("/", 1)
        alias = f"{parent}/ｃ{name[1:]}"  # fullwidth c -> NFKC 'c'
        self.write(alias, b"alias\n")
        self.commit("protected alias")
        result = self.validate_authorization()
        self.assert_rejected(result, "portable whole-path aliases")

    def test_changed_governed_bytes_in_descendant_are_rejected(self) -> None:
        self.create_authorization()
        path = subject.REJECTED_REVIEW_PATHS[0]
        self.write(path, self.read(path) + b"changed\n")
        self.commit("mutate authority")
        result = self.validate_authorization()
        self.assert_rejected(result, "candidate-bound bytes or mode changed")

    def test_intermediate_change_then_revert_is_still_rejected(self) -> None:
        self.create_authorization()
        path = subject.VALIDATOR_PATHS[0]
        original = self.read(path)
        self.write(path, original + b"temporary mutation\n")
        self.commit("temporary mutation")
        self.write(path, original)
        self.commit("restore bytes")
        result = self.validate_authorization()
        self.assert_rejected(result, "candidate-bound bytes or mode changed")

    def test_wrong_rollback_record_is_rejected(self) -> None:
        def mutate(record):
            record["rollback_baseline"]["tree"] = "0" * 40

        self.create_authorization(record_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "rollback_baseline mismatch")

    def test_authorization_record_extra_field_is_rejected(self) -> None:
        def mutate(record):
            record["authorization_commit"] = "self-cycle"

        self.create_authorization(record_mutator=mutate)
        result = self.validate_authorization()
        self.assert_rejected(result, "top-level fields mismatch")


class GitRunnerUnitTests(unittest.TestCase):
    def test_portable_key_trims_windows_trailing_dot_and_space_components(self) -> None:
        canonical_path = subject.REVIEW_PATHS[0]
        parent, name = canonical_path.rsplit("/", 1)
        self.assertEqual(
            subject._portable_path_key(canonical_path),
            subject._portable_path_key(f"{parent}./{name}. "),
        )

    def test_git_timeout_fails_closed(self) -> None:
        with mock.patch.object(
            subject.subprocess,
            "run",
            side_effect=subprocess.TimeoutExpired(["git"], subject.GIT_TIMEOUT_SECONDS),
        ):
            with self.assertRaises(subject.AuthorizationValidationError) as caught:
                subject._git(Path("."), "status")
        self.assertIn("exceeded", str(caught.exception))

    def test_git_command_disables_replacements_and_scrubs_mixed_case_redirects(self) -> None:
        completed = subprocess.CompletedProcess(["git"], 0, b"", b"")
        with mock.patch.dict(
            os.environ,
            {"gIt_DiR": "attacker", "Git_Config_Count": "1"},
        ), mock.patch.object(subject.subprocess, "run", return_value=completed) as invoked:
            subject._git(Path("."), "status")
        args, kwargs = invoked.call_args
        self.assertIn("--no-replace-objects", args[0])
        self.assertFalse(
            any(
                key.upper().startswith("GIT_")
                for key in kwargs["env"]
                if key
                not in {
                    "GIT_NO_LAZY_FETCH",
                    "GIT_NO_REPLACE_OBJECTS",
                    "GIT_OPTIONAL_LOCKS",
                }
            )
        )
        self.assertEqual(subject.GIT_TIMEOUT_SECONDS, kwargs["timeout"])


if __name__ == "__main__":
    unittest.main()
