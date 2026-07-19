#!/usr/bin/env python3
"""Validate the capability-v15 candidate and authorization transition.

The formal verdict is a property of immutable Git objects.  Candidate
validation accepts an exact candidate commit ``C``.  Authorization validation
additionally accepts an exact descendant commit ``H``, derives the first
literal single-parent descendant ``A``, and validates the gate files and all
preservation rules from those object IDs.  Neither API reads symbolic ``HEAD``,
the index, the worktree, Git status, or live refs.  Checkout cleanliness and the
live rollback tag ref are separate operational stop conditions before edits;
they are not authorization evidence.

The eight validator/snapshot/Markdown implementation-and-test blobs are not
executed from an untrusted commit.  Every A-and-later tree must retain their
exact C object IDs and modes so trusted tooling can execute the reviewed C
version externally.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any


POLICY_REVISION = "capability-v15"
MANIFEST_PATH = "docs/contracts/capability-policy-authority-v15.json"
LEDGER_PATH = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/"
    "v15-precommit-validation.md"
)
AUTHORITY_PATHS = (
    "docs/adr/ADR-011-measured-capability-probe-policy.md",
    "docs/contracts/capability-store-v4.md",
    "docs/contracts/native-close-fence-proof-v1.md",
    "docs/contracts/recovery-journal-v5.md",
    "docs/execution/slice-1b-contract.md",
)
AUTHORIZATION_PATH = "docs/contracts/capability-policy-authorization-v15.json"
REVIEW_PATHS = tuple(
    f"docs/reviews/slice-1b-policy-v15-exact-review-{number:02d}.json"
    for number in range(1, 4)
)
RESERVED_PATHS = (AUTHORIZATION_PATH, *REVIEW_PATHS)
FROZEN_HISTORICAL_V14_EVIDENCE = (
    (
        "docs/contracts/capability-policy-authorization-v14.json",
        "08aa2a854c011a1dd5137fe43e1a3a60ef21b6631357de2928d718571b910250",
    ),
    (
        "docs/reviews/slice-1b-policy-v14-exact-review-01.json",
        "001895b9012ebd9b772ec564390212413b4406196a9f9e5785b51b07238591cb",
    ),
    (
        "docs/reviews/slice-1b-policy-v14-exact-review-02.json",
        "295f5226c6592deba7f766d474f1bb042df1bf622c401c289e0df7236c0aa2e7",
    ),
    (
        "docs/reviews/slice-1b-policy-v14-exact-review-03.json",
        "41109d24afcfc18397b10f5f075d0008dd059f8110f62e72ea747adefc8cdc09",
    ),
)
HISTORICAL_V14_PATHS = tuple(path for path, _sha256 in FROZEN_HISTORICAL_V14_EVIDENCE)
VALIDATOR_PATHS = (
    "scripts/capability_policy_snapshot.py",
    "scripts/tests/test_capability_policy_snapshot.py",
    "scripts/capability_policy_markdown.py",
    "scripts/tests/test_capability_policy_markdown.py",
    "scripts/validate_capability_policy.py",
    "scripts/tests/test_validate_capability_policy.py",
    "scripts/validate_capability_policy_authorization.py",
    "scripts/tests/test_validate_capability_policy_authorization.py",
)
REJECTED_REVIEW_PATHS = tuple(
    f"docs/reviews/slice-1b-policy-review-{number:02d}.md"
    for number in range(1, 15)
)
CANDIDATE_IMMUTABLE_PATHS = (
    *AUTHORITY_PATHS,
    MANIFEST_PATH,
    LEDGER_PATH,
    *VALIDATOR_PATHS,
    *REJECTED_REVIEW_PATHS,
    *HISTORICAL_V14_PATHS,
)

REVIEW_LANES = (
    "authority-and-contract",
    "validator-adversarial",
    "evidence-and-governance",
)
EXCLUDED_CLAIMS = (
    "SLICE_1B_ACCEPTED",
    "G2",
    "G6",
    "G8",
    "PHYSICAL_DEVICE_VALIDATED",
    "RELEASE_READY",
)
QUORUM_RULE = "ALL_THREE_APPROVE_ZERO_FINDINGS_DISTINCT_REVIEWER_SESSION_AND_LANE"

ROLLBACK_TAG = "slice-1b-start"
ROLLBACK_TAG_OBJECT = "776d3717bc0bf5dc6e93f593256530214a334101"
ROLLBACK_COMMIT = "97e0c21345945cd55451406ef947537d465fc902"
ROLLBACK_TREE = "83c7a194aa9f6fc839b5aff55fb823a94eaf7400"
ROLLBACK_BASELINE = {
    "commit": ROLLBACK_COMMIT,
    "tag": ROLLBACK_TAG,
    "tag_object": ROLLBACK_TAG_OBJECT,
    "tree": ROLLBACK_TREE,
}

HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
SESSION_ID = re.compile(r"[0-9a-f]{32}\Z")
ASCII_ID = re.compile(r"[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?\Z")
MAX_JSON_BYTES = 65_536
MAX_GOVERNED_BLOB_BYTES = 4 * 1024 * 1024
GIT_TIMEOUT_SECONDS = 15


class AuthorizationValidationError(ValueError):
    """Raised when a target cannot be constructed from trusted Git objects."""


@dataclass(frozen=True)
class CapabilityPolicyAuthorizationResult:
    stage: str
    errors: tuple[str, ...]
    candidate_commit: str | None = None
    candidate_tree: str | None = None
    authorization_commit: str | None = None
    head_commit: str | None = None
    target: dict[str, Any] | None = None
    target_sha256: str | None = None

    @property
    def ok(self) -> bool:
        return not self.errors


@dataclass(frozen=True)
class _TreeEntry:
    mode: str
    kind: str
    oid: str
    path: str


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        .encode("utf-8")
        + b"\n"
    )


def _portable_path_key(value: str) -> str:
    return "/".join(
        unicodedata.normalize("NFKC", component).rstrip(" .").casefold()
        for component in value.replace("\\", "/").split("/")
    )


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> object:
    raise ValueError(f"non-finite JSON constant: {value}")


def _parse_canonical_json(
    raw: bytes,
    label: str,
    errors: list[str],
    *,
    max_bytes: int = MAX_JSON_BYTES,
) -> dict[str, Any] | None:
    try:
        if len(raw) > max_bytes:
            raise ValueError(f"exceeds {max_bytes} bytes")
        text = raw.decode("utf-8")
        payload = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
        if not isinstance(payload, dict):
            raise ValueError("root must be an object")
        if raw != _canonical_json_bytes(payload):
            raise ValueError("bytes are not canonical sorted compact JSON plus LF")
        return payload
    except (UnicodeError, json.JSONDecodeError, RecursionError, TypeError, ValueError) as exc:
        errors.append(f"{label}: {exc}")
        return None


def _git(root: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
    env = os.environ.copy()
    for key in tuple(env):
        if key.upper().startswith("GIT_"):
            env.pop(key, None)
    env["GIT_OPTIONAL_LOCKS"] = "0"
    env["GIT_NO_REPLACE_OBJECTS"] = "1"
    env["GIT_NO_LAZY_FETCH"] = "1"
    env["LC_ALL"] = "C"
    try:
        completed = subprocess.run(
            [
                "git",
                "--no-replace-objects",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "-c",
                "core.commitGraph=false",
                "-c",
                "submodule.recurse=false",
                "-C",
                os.fspath(root),
                *args,
            ],
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            env=env,
            timeout=GIT_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exc:
        raise AuthorizationValidationError(
            f"git {' '.join(args)} exceeded {GIT_TIMEOUT_SECONDS} seconds"
        ) from exc
    except OSError as exc:
        raise AuthorizationValidationError(f"cannot execute git: {exc}") from exc
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", "replace").strip()
        if len(detail) > 400:
            detail = detail[:400] + "..."
        raise AuthorizationValidationError(
            f"git {' '.join(args)} failed ({completed.returncode}): {detail}"
        )
    return completed.stdout


def _resolve_commit(root: Path, object_id: str) -> str:
    """Validate one literal SHA-1 commit object without revision resolution."""

    if not isinstance(object_id, str) or HEX40.fullmatch(object_id) is None:
        raise AuthorizationValidationError(
            "Git commit must be an exact lowercase 40-hex sha1"
        )
    try:
        kind = _git(root, "cat-file", "-t", object_id).decode("ascii", "strict").strip()
    except UnicodeError as exc:
        raise AuthorizationValidationError("Git object type is not ASCII") from exc
    if kind != "commit":
        raise AuthorizationValidationError(
            f"Git object is not a commit: {object_id} ({kind!r})"
        )
    return object_id


def _commit_identity(root: Path, commit: str) -> tuple[str, tuple[str, ...]]:
    """Read the literal commit object, bypassing replace refs and legacy grafts."""

    size_text = _git(root, "cat-file", "-s", commit).decode("ascii", "strict").strip()
    if not size_text.isdecimal():
        raise AuthorizationValidationError(f"Git commit size is malformed: {commit}")
    size = int(size_text, 10)
    if size > MAX_GOVERNED_BLOB_BYTES:
        raise AuthorizationValidationError(
            f"Git commit object exceeds {MAX_GOVERNED_BLOB_BYTES} bytes: {commit}"
        )
    raw = _git(root, "cat-file", "commit", commit)
    if len(raw) != size:
        raise AuthorizationValidationError(f"Git commit object read is incomplete: {commit}")
    header = raw.split(b"\n\n", 1)[0].splitlines()
    trees: list[str] = []
    parents: list[str] = []
    for raw_line in header:
        if raw_line.startswith(b"tree "):
            value = raw_line[5:].decode("ascii", "strict")
            trees.append(value)
        elif raw_line.startswith(b"parent "):
            value = raw_line[7:].decode("ascii", "strict")
            parents.append(value)
    if len(trees) != 1 or HEX40.fullmatch(trees[0]) is None:
        raise AuthorizationValidationError(f"Git commit tree header is malformed: {commit}")
    if any(HEX40.fullmatch(parent) is None for parent in parents):
        raise AuthorizationValidationError(f"Git commit parent header is malformed: {commit}")
    return trees[0], tuple(parents)


def _commit_tree(root: Path, commit: str) -> str:
    tree, _parents = _commit_identity(root, commit)
    return tree


def _tree_entries(
    root: Path, commit: str, relatives: tuple[str, ...]
) -> dict[str, _TreeEntry]:
    raw = _git(root, "ls-tree", "-z", commit, "--", *relatives)
    records = [record for record in raw.split(b"\0") if record]
    expected = set(relatives)
    result: dict[str, _TreeEntry] = {}
    for record in records:
        if b"\t" not in record:
            raise AuthorizationValidationError("malformed Git tree entry")
        metadata, raw_path = record.split(b"\t", 1)
        fields = metadata.decode("ascii", "strict").split(" ")
        if len(fields) != 3:
            raise AuthorizationValidationError("malformed Git tree metadata")
        try:
            decoded_path = raw_path.decode("utf-8")
        except UnicodeError as exc:
            raise AuthorizationValidationError("Git tree path is not UTF-8") from exc
        if decoded_path not in expected or decoded_path in result:
            raise AuthorizationValidationError(
                f"Git tree returned unexpected or duplicate path: {decoded_path!r}"
            )
        mode, kind, oid = fields
        if HEX40.fullmatch(oid) is None:
            raise AuthorizationValidationError(
                f"malformed Git object id for {decoded_path}"
            )
        result[decoded_path] = _TreeEntry(mode, kind, oid, decoded_path)
    return result


def _tree_entry(root: Path, commit: str, relative: str) -> _TreeEntry | None:
    return _tree_entries(root, commit, (relative,)).get(relative)


def _read_commit_blobs(
    root: Path,
    commit: str,
    relatives: tuple[str, ...],
    *,
    maximums: dict[str, int] | None = None,
    allowed_modes: frozenset[str] = frozenset(("100644",)),
    max_total_bytes: int | None = None,
) -> dict[str, bytes]:
    entries = _tree_entries(root, commit, relatives)
    for relative in relatives:
        entry = entries.get(relative)
        if entry is None:
            raise AuthorizationValidationError(
                f"required Git object path is missing: {relative}"
            )
        if entry.mode not in allowed_modes or entry.kind != "blob":
            raise AuthorizationValidationError(
                f"required Git object path has a forbidden mode/type: {relative} "
                f"mode={entry.mode} type={entry.kind}"
            )
    request = b"".join(entries[path].oid.encode("ascii") + b"\n" for path in relatives)
    size_response = _git(root, "cat-file", "--batch-check", input_bytes=request)
    size_lines = size_response.splitlines()
    if len(size_lines) != len(relatives):
        raise AuthorizationValidationError("git cat-file batch size response mismatch")
    expected_sizes: dict[str, int] = {}
    total_size = 0
    for relative, raw_line in zip(relatives, size_lines, strict=True):
        fields = raw_line.decode("ascii", "strict").split()
        if (
            len(fields) != 3
            or fields[0] != entries[relative].oid
            or fields[1] != "blob"
            or not fields[2].isdecimal()
        ):
            raise AuthorizationValidationError(
                f"malformed git blob size response for {relative}"
            )
        size = int(fields[2], 10)
        maximum = (maximums or {}).get(relative, MAX_GOVERNED_BLOB_BYTES)
        if size > maximum:
            raise AuthorizationValidationError(
                f"Git blob exceeds {maximum} bytes: {relative}"
            )
        expected_sizes[relative] = size
        total_size += size
        if max_total_bytes is not None and total_size > max_total_bytes:
            raise AuthorizationValidationError(
                f"Git blob set exceeds {max_total_bytes} total bytes"
            )
    response = _git(root, "cat-file", "--batch", input_bytes=request)
    offset = 0
    result: dict[str, bytes] = {}
    for relative in relatives:
        line_end = response.find(b"\n", offset)
        if line_end < 0:
            raise AuthorizationValidationError("truncated git cat-file batch header")
        header = response[offset:line_end].decode("ascii", "strict").split()
        if len(header) != 3 or header[0] != entries[relative].oid or header[1] != "blob":
            raise AuthorizationValidationError(
                f"unexpected git cat-file batch header for {relative}: {header!r}"
            )
        try:
            size = int(header[2], 10)
        except ValueError as exc:
            raise AuthorizationValidationError(
                f"malformed git cat-file size for {relative}"
            ) from exc
        if size != expected_sizes[relative]:
            raise AuthorizationValidationError(
                f"git blob size changed during batch read: {relative}"
            )
        start = line_end + 1
        end = start + size
        if end >= len(response) or response[end : end + 1] != b"\n":
            raise AuthorizationValidationError(
                f"truncated git cat-file batch payload for {relative}"
            )
        raw = response[start:end]
        result[relative] = raw
        offset = end + 1
    if offset != len(response):
        raise AuthorizationValidationError("unexpected trailing git cat-file batch bytes")
    return result


def _required_blob(
    root: Path,
    commit: str,
    relative: str,
    *,
    max_bytes: int | None = None,
) -> bytes:
    entry = _tree_entry(root, commit, relative)
    if entry is None:
        raise AuthorizationValidationError(f"required Git object path is missing: {relative}")
    if entry.mode != "100644" or entry.kind != "blob":
        raise AuthorizationValidationError(
            f"required Git object path is not mode-100644 blob: {relative} "
            f"mode={entry.mode} type={entry.kind}"
        )
    if max_bytes is not None:
        size_text = _git(root, "cat-file", "-s", entry.oid).decode("ascii", "strict").strip()
        if not size_text.isdecimal():
            raise AuthorizationValidationError(
                f"Git blob size is malformed: {relative}"
            )
        if int(size_text, 10) > max_bytes:
            raise AuthorizationValidationError(
                f"Git blob exceeds {max_bytes} bytes: {relative}"
            )
    raw = _git(root, "cat-file", "blob", entry.oid)
    if max_bytes is not None and len(raw) > max_bytes:
        raise AuthorizationValidationError(
            f"Git blob exceeds {max_bytes} bytes: {relative}"
        )
    return raw


def _all_tree_entries(root: Path, commit: str) -> dict[str, _TreeEntry]:
    raw = _git(root, "ls-tree", "-r", "-z", "--full-tree", commit)
    result: dict[str, _TreeEntry] = {}
    portable: dict[str, str] = {}
    for record in (item for item in raw.split(b"\0") if item):
        if b"\t" not in record:
            raise AuthorizationValidationError("malformed recursive Git tree entry")
        metadata, raw_path = record.split(b"\t", 1)
        fields = metadata.decode("ascii", "strict").split(" ")
        if len(fields) != 3:
            raise AuthorizationValidationError("malformed recursive Git tree metadata")
        try:
            path = raw_path.decode("utf-8")
        except UnicodeError as exc:
            raise AuthorizationValidationError(
                "candidate Git tree contains a non-UTF-8 path"
            ) from exc
        mode, kind, oid = fields
        if HEX40.fullmatch(oid) is None or path in result:
            raise AuthorizationValidationError("invalid or duplicate recursive Git tree entry")
        key = _portable_path_key(path)
        alias = portable.get(key)
        if alias is not None:
            raise AuthorizationValidationError(
                f"Git tree contains portable whole-path aliases: {alias!r}, {path!r}"
            )
        portable[key] = path
        result[path] = _TreeEntry(mode, kind, oid, path)
    return result


def _all_tree_paths(root: Path, commit: str) -> tuple[str, ...]:
    return tuple(_all_tree_entries(root, commit))


def _candidate_reserved_errors(root: Path, commit: str) -> list[str]:
    errors: list[str] = []
    try:
        paths = _all_tree_paths(root, commit)
    except AuthorizationValidationError as exc:
        return [str(exc)]
    folded_reserved = {_portable_path_key(path): path for path in RESERVED_PATHS}
    for actual in paths:
        expected = folded_reserved.get(_portable_path_key(actual))
        if expected is not None:
            errors.append(
                f"candidate tree contains reserved authorization path or alias: "
                f"actual={actual!r} reserved={expected!r}"
            )
    return errors


def _authorization_reserved_errors(root: Path, commit: str) -> list[str]:
    """Require exactly the four canonical gate paths and no portable aliases."""

    errors: list[str] = []
    try:
        paths = _all_tree_paths(root, commit)
    except AuthorizationValidationError as exc:
        return [str(exc)]
    folded_reserved = {_portable_path_key(path): path for path in RESERVED_PATHS}
    seen: set[str] = set()
    for actual in paths:
        expected = folded_reserved.get(_portable_path_key(actual))
        if expected is None:
            continue
        if actual != expected:
            errors.append(
                f"authorization tree contains portable gate-path alias: "
                f"actual={actual!r} expected={expected!r}"
            )
        else:
            seen.add(expected)
    for missing in sorted(set(RESERVED_PATHS) - seen):
        errors.append(f"authorization tree is missing fixed gate path: {missing}")
    return errors


def _protected_alias_errors(
    root: Path, commit: str, protected_paths: tuple[str, ...]
) -> list[str]:
    """Reject additional NFKC/casefold spellings of any protected path."""

    try:
        paths = _all_tree_paths(root, commit)
    except AuthorizationValidationError as exc:
        return [str(exc)]
    expected_by_key = {_portable_path_key(path): path for path in protected_paths}
    errors: list[str] = []
    seen: set[str] = set()
    for actual in paths:
        expected = expected_by_key.get(_portable_path_key(actual))
        if expected is None:
            continue
        if actual != expected:
            errors.append(
                f"protected Git path has a portable alias in {commit}: "
                f"actual={actual!r} expected={expected!r}"
            )
        else:
            seen.add(expected)
    for missing in sorted(set(protected_paths) - seen):
        errors.append(f"protected Git path is missing in {commit}: {missing}")
    return errors


def _validate_manifest(
    manifest_raw: bytes,
    authority_bytes: dict[str, bytes],
    rejected_review_bytes: dict[str, bytes],
    errors: list[str],
) -> None:
    payload = _parse_canonical_json(
        manifest_raw,
        "candidate authority manifest is invalid",
        errors,
        max_bytes=131_072,
    )
    if payload is None:
        return
    expected_keys = {
        "authorities",
        "candidate_revision",
        "implementation_authorized",
        "rejected_revisions",
        "schema",
        "status",
    }
    if set(payload) != expected_keys:
        errors.append("candidate authority manifest top-level fields mismatch")
    if payload.get("schema") != "capability-policy-authority-v1":
        errors.append("candidate authority manifest schema mismatch")
    if payload.get("candidate_revision") != POLICY_REVISION:
        errors.append("candidate authority manifest revision mismatch")
    if payload.get("status") != "CANDIDATE_REREVIEW_REQUIRED":
        errors.append("candidate authority manifest status must remain rereview-required")
    if payload.get("implementation_authorized") is not False:
        errors.append("candidate authority manifest must retain implementation_authorized=false")

    rows = payload.get("authorities")
    if not isinstance(rows, list) or len(rows) != len(AUTHORITY_PATHS):
        errors.append("candidate authority manifest authority rows mismatch")
    else:
        for path, row in zip(AUTHORITY_PATHS, rows, strict=True):
            expected = {
                "path": path,
                "revision": POLICY_REVISION,
                "sha256": _sha256(authority_bytes[path]),
            }
            if row != expected:
                errors.append(f"candidate authority manifest row mismatch: {path}")

    rejected = payload.get("rejected_revisions")
    if not isinstance(rejected, list) or len(rejected) != len(REJECTED_REVIEW_PATHS):
        errors.append("candidate authority manifest rejected-review rows mismatch")
    else:
        for number, (path, row) in enumerate(
            zip(REJECTED_REVIEW_PATHS, rejected, strict=True), start=1
        ):
            expected = {
                "review": path,
                "review_sha256": _sha256(rejected_review_bytes[path]),
                "revision": f"capability-v{number}",
            }
            if row != expected:
                errors.append(f"candidate rejected-review manifest row mismatch: {path}")


def _validate_frozen_historical_v14_evidence(
    historical_bytes: dict[str, bytes],
    errors: list[str],
) -> None:
    for path, expected_hash in FROZEN_HISTORICAL_V14_EVIDENCE:
        actual_hash = _sha256(historical_bytes[path])
        if actual_hash != expected_hash:
            errors.append(
                f"frozen historical v14 evidence hash mismatch: {path} "
                f"expected={expected_hash} actual={actual_hash}"
            )


def _build_candidate_target(
    root: Path, candidate: str, errors: list[str]
) -> tuple[dict[str, Any] | None, str | None, str | None]:
    try:
        if _git(root, "rev-parse", "--show-object-format").decode("ascii").strip() != "sha1":
            raise AuthorizationValidationError("repository Git object format must be sha1")
        tree = _commit_tree(root, candidate)
        all_paths = (
            *AUTHORITY_PATHS,
            MANIFEST_PATH,
            LEDGER_PATH,
            *VALIDATOR_PATHS,
            *REJECTED_REVIEW_PATHS,
            *HISTORICAL_V14_PATHS,
        )
        blobs = _read_commit_blobs(
            root,
            candidate,
            all_paths,
            maximums={MANIFEST_PATH: 131_072},
        )
        authority_bytes = {path: blobs[path] for path in AUTHORITY_PATHS}
        manifest_raw = blobs[MANIFEST_PATH]
        ledger_raw = blobs[LEDGER_PATH]
        rejected_review_bytes = {
            path: blobs[path] for path in REJECTED_REVIEW_PATHS
        }
        historical_v14_bytes = {path: blobs[path] for path in HISTORICAL_V14_PATHS}
    except (AuthorizationValidationError, UnicodeError) as exc:
        errors.append(str(exc))
        return None, None, None

    _validate_manifest(
        manifest_raw, authority_bytes, rejected_review_bytes, errors
    )
    _validate_frozen_historical_v14_evidence(historical_v14_bytes, errors)
    target: dict[str, Any] = {
        "authorities": [
            {"path": path, "sha256": _sha256(authority_bytes[path])}
            for path in AUTHORITY_PATHS
        ],
        "authority_manifest": {"path": MANIFEST_PATH, "sha256": _sha256(manifest_raw)},
        "candidate_commit": candidate,
        "candidate_revision": POLICY_REVISION,
        "candidate_tree": tree,
        "git_object_format": "sha1",
        "precommit_ledger": {"path": LEDGER_PATH, "sha256": _sha256(ledger_raw)},
    }
    target_hash = _sha256(_canonical_json_bytes(target))
    return target, target_hash, tree


def construct_candidate_target(
    root: Path, candidate_commit: str
) -> tuple[dict[str, Any], str]:
    """Construct and validate the canonical target from candidate Git objects."""

    if not isinstance(candidate_commit, str) or HEX40.fullmatch(candidate_commit) is None:
        raise AuthorizationValidationError(
            "candidate commit must be an exact lowercase 40-hex sha1"
        )
    candidate = _resolve_commit(root.absolute(), candidate_commit)
    if candidate != candidate_commit:
        raise AuthorizationValidationError("candidate commit did not resolve to itself")
    errors: list[str] = []
    target, target_hash, _tree = _build_candidate_target(root.absolute(), candidate, errors)
    if errors or target is None or target_hash is None:
        raise AuthorizationValidationError("; ".join(errors) or "cannot construct candidate target")
    return target, target_hash


def validate_capability_policy_candidate(
    root: Path, candidate_commit: str
) -> CapabilityPolicyAuthorizationResult:
    root = root.absolute()
    errors: list[str] = []
    candidate: str | None = None
    target: dict[str, Any] | None = None
    target_hash: str | None = None
    tree: str | None = None
    try:
        if not isinstance(candidate_commit, str) or HEX40.fullmatch(candidate_commit) is None:
            raise AuthorizationValidationError(
                "candidate commit must be an exact lowercase 40-hex sha1"
            )
        candidate = _resolve_commit(root, candidate_commit)
        if candidate != candidate_commit:
            raise AuthorizationValidationError("candidate commit did not resolve to itself")
        errors.extend(_candidate_reserved_errors(root, candidate))
        errors.extend(
            _protected_alias_errors(root, candidate, CANDIDATE_IMMUTABLE_PATHS)
        )
        target, target_hash, tree = _build_candidate_target(root, candidate, errors)
    except (AuthorizationValidationError, OSError, UnicodeError) as exc:
        errors.append(str(exc))
    return CapabilityPolicyAuthorizationResult(
        stage="candidate",
        errors=tuple(errors),
        candidate_commit=candidate,
        candidate_tree=tree,
        target=target,
        target_sha256=target_hash,
    )


def _commit_parents(root: Path, commit: str) -> tuple[str, ...]:
    _tree, parents = _commit_identity(root, commit)
    return parents


def _diff_name_status(root: Path, old: str, new: str) -> tuple[tuple[str, str], ...]:
    raw = _git(
        root,
        "diff-tree",
        "-r",
        "--no-commit-id",
        "--name-status",
        "--no-renames",
        "-z",
        old,
        new,
    )
    tokens = [token for token in raw.split(b"\0") if token]
    if len(tokens) % 2:
        raise AuthorizationValidationError("malformed Git diff-tree output")
    result: list[tuple[str, str]] = []
    for offset in range(0, len(tokens), 2):
        status_text = tokens[offset].decode("ascii", "strict")
        path = tokens[offset + 1].decode("utf-8", "strict")
        result.append((status_text, path))
    return tuple(result)


def _validate_exact_authorization_delta(
    root: Path, candidate: str, authorization: str, errors: list[str]
) -> None:
    try:
        parents = _commit_parents(root, authorization)
        if parents != (candidate,):
            errors.append(
                f"authorization commit must have exactly candidate parent: actual={parents!r}"
            )
        delta = _diff_name_status(root, candidate, authorization)
        expected = tuple(("A", path) for path in sorted(RESERVED_PATHS))
        if tuple(sorted(delta)) != expected:
            errors.append(
                f"authorization commit delta mismatch: expected={expected!r} actual={tuple(sorted(delta))!r}"
            )
        for path in RESERVED_PATHS:
            if _tree_entry(root, candidate, path) is not None:
                errors.append(f"authorization path already existed in candidate: {path}")
            _required_blob(root, authorization, path, max_bytes=MAX_JSON_BYTES)
    except (AuthorizationValidationError, UnicodeError) as exc:
        errors.append(str(exc))


def _extract_candidate_from_authorization_record(
    root: Path, head_commit: str, errors: list[str]
) -> tuple[str | None, dict[str, Any] | None]:
    try:
        raw = _required_blob(
            root, head_commit, AUTHORIZATION_PATH, max_bytes=MAX_JSON_BYTES
        )
    except AuthorizationValidationError as exc:
        errors.append(str(exc))
        return None, None
    payload = _parse_canonical_json(raw, "authorization record is invalid", errors)
    if payload is None:
        return None, None
    target = payload.get("target")
    candidate = target.get("candidate_commit") if isinstance(target, dict) else None
    if not isinstance(candidate, str) or HEX40.fullmatch(candidate) is None:
        errors.append("authorization target candidate_commit is not lowercase sha1")
        return None, payload
    try:
        resolved = _resolve_commit(root, candidate)
    except AuthorizationValidationError as exc:
        errors.append(str(exc))
        return None, payload
    if resolved != candidate:
        errors.append("authorization target candidate_commit is not an exact commit id")
        return None, payload
    return candidate, payload


def _derive_authorization_commit(
    root: Path, candidate: str, head_commit: str, errors: list[str]
) -> tuple[str | None, tuple[str, ...]]:
    """Walk literal commit-object parents from explicit H back to exact C."""

    try:
        reverse_chain: list[str] = []
        seen: set[str] = set()
        current = head_commit
        while current != candidate:
            if current in seen:
                errors.append("literal Git commit ancestry contains a cycle")
                return None, ()
            seen.add(current)
            reverse_chain.append(current)
            parents = _commit_parents(root, current)
            if len(parents) != 1:
                errors.append(
                    f"authorization descendant chain is not linear at {current}: {parents!r}"
                )
                return None, ()
            current = parents[0]
        chain = tuple(reversed(reverse_chain))
        if not chain:
            errors.append("head commit H equals candidate and has no authorization commit")
            return None, ()
    except AuthorizationValidationError as exc:
        errors.append(str(exc))
        return None, ()
    return chain[0], chain


def _validate_reviews(
    root: Path,
    authorization: str,
    expected_target: dict[str, Any],
    expected_target_hash: str,
    errors: list[str],
) -> tuple[dict[str, bytes], list[dict[str, Any]]]:
    raw_files: dict[str, bytes] = {}
    payloads: list[dict[str, Any]] = []
    expected_keys = {
        "finding_counts",
        "read_only",
        "repository_mutated",
        "review_id",
        "review_lane",
        "reviewer_id",
        "schema",
        "session_id",
        "target",
        "target_sha256",
        "verdict",
    }
    for number, path in enumerate(REVIEW_PATHS, start=1):
        try:
            raw = _required_blob(root, authorization, path, max_bytes=MAX_JSON_BYTES)
        except AuthorizationValidationError as exc:
            errors.append(str(exc))
            continue
        raw_files[path] = raw
        payload = _parse_canonical_json(raw, f"exact review {number:02d} is invalid", errors)
        if payload is None:
            continue
        payloads.append(payload)
        if set(payload) != expected_keys:
            errors.append(f"exact review {number:02d} top-level fields mismatch")
        if payload.get("schema") != "capability-policy-exact-review-v1":
            errors.append(f"exact review {number:02d} schema mismatch")
        if payload.get("review_id") != f"{number:02d}":
            errors.append(f"exact review {number:02d} review_id mismatch")
        reviewer = payload.get("reviewer_id")
        if not isinstance(reviewer, str) or ASCII_ID.fullmatch(reviewer) is None:
            errors.append(f"exact review {number:02d} reviewer_id is not lowercase ASCII")
        session = payload.get("session_id")
        if not isinstance(session, str) or SESSION_ID.fullmatch(session) is None:
            errors.append(f"exact review {number:02d} session_id is malformed")
        if payload.get("review_lane") not in REVIEW_LANES:
            errors.append(f"exact review {number:02d} review_lane is invalid")
        if payload.get("read_only") is not True:
            errors.append(f"exact review {number:02d} must be read_only=true")
        if payload.get("repository_mutated") is not False:
            errors.append(f"exact review {number:02d} must be repository_mutated=false")
        counts = payload.get("finding_counts")
        if not isinstance(counts, dict) or set(counts) != {"p0", "p1", "p2"}:
            errors.append(f"exact review {number:02d} finding_counts shape mismatch")
        elif any(type(counts[key]) is not int or counts[key] != 0 for key in ("p0", "p1", "p2")):
            errors.append(f"exact review {number:02d} must have integer zero findings")
        if payload.get("verdict") != "APPROVE_NO_ACTIONABLE_FINDINGS":
            errors.append(f"exact review {number:02d} verdict is not unconditional approval")
        if payload.get("target") != expected_target:
            errors.append(f"exact review {number:02d} target does not match candidate objects")
        if payload.get("target_sha256") != expected_target_hash:
            errors.append(f"exact review {number:02d} target_sha256 mismatch")

    if len(payloads) == 3:
        for key in ("review_id", "reviewer_id", "session_id", "review_lane"):
            values = [payload.get(key) for payload in payloads]
            if not all(isinstance(value, str) for value in values):
                errors.append(f"exact reviews contain non-string {key} values")
            elif len(set(values)) != 3:
                errors.append(f"exact reviews do not have distinct {key} values")
        lanes = [payload.get("review_lane") for payload in payloads]
        if not all(isinstance(lane, str) for lane in lanes) or set(lanes) != set(
            REVIEW_LANES
        ):
            errors.append("exact review lane set mismatch")
        targets = [_canonical_json_bytes(payload.get("target")) for payload in payloads]
        if len(set(targets)) != 1:
            errors.append("exact reviews do not bind one byte-equivalent target")
    return raw_files, payloads


def _validate_rollback_tag(root: Path, errors: list[str]) -> None:
    try:
        kind = _git(root, "cat-file", "-t", ROLLBACK_TAG_OBJECT).decode(
            "ascii", "strict"
        ).strip()
        if kind != "tag":
            errors.append("rollback tag object is not an annotated tag")
            return
        size_text = _git(root, "cat-file", "-s", ROLLBACK_TAG_OBJECT).decode(
            "ascii", "strict"
        ).strip()
        if not size_text.isdecimal():
            raise AuthorizationValidationError("rollback tag object size is malformed")
        size = int(size_text, 10)
        if size > MAX_GOVERNED_BLOB_BYTES:
            raise AuthorizationValidationError(
                f"rollback tag object exceeds {MAX_GOVERNED_BLOB_BYTES} bytes"
            )
        tag_payload = _git(root, "cat-file", "tag", ROLLBACK_TAG_OBJECT)
        if len(tag_payload) != size:
            raise AuthorizationValidationError("rollback tag object read is incomplete")
        try:
            tag_text = tag_payload.decode("utf-8")
        except UnicodeError as exc:
            raise AuthorizationValidationError("rollback tag payload is not UTF-8") from exc
        header = tag_text.split("\n\n", 1)[0].splitlines()
        object_rows = [line for line in header if line.startswith("object ")]
        type_rows = [line for line in header if line.startswith("type ")]
        tag_rows = [line for line in header if line.startswith("tag ")]
        if (
            object_rows != [f"object {ROLLBACK_COMMIT}"]
            or type_rows != ["type commit"]
            or tag_rows != [f"tag {ROLLBACK_TAG}"]
        ):
            errors.append("rollback annotated-tag payload identity mismatch")
        _resolve_commit(root, ROLLBACK_COMMIT)
        if _commit_tree(root, ROLLBACK_COMMIT) != ROLLBACK_TREE:
            errors.append("rollback baseline tree mismatch")
    except (AuthorizationValidationError, UnicodeError) as exc:
        errors.append(str(exc))


def _validate_authorization_record(
    root: Path,
    authorization: str,
    expected_target: dict[str, Any],
    expected_target_hash: str,
    review_raw: dict[str, bytes],
    pre_parsed: dict[str, Any] | None,
    errors: list[str],
) -> bytes | None:
    try:
        raw = _required_blob(root, authorization, AUTHORIZATION_PATH, max_bytes=MAX_JSON_BYTES)
    except AuthorizationValidationError as exc:
        errors.append(str(exc))
        return None
    payload = _parse_canonical_json(raw, "authorization record is invalid", errors)
    if payload is None:
        return raw
    if pre_parsed is not None and payload != pre_parsed:
        errors.append("authorization record changed during object validation")
    expected_keys = {
        "authorization_scope",
        "excluded_claims",
        "implementation_authorized",
        "quorum",
        "rollback_baseline",
        "schema",
        "status",
        "target",
        "target_sha256",
    }
    if set(payload) != expected_keys:
        errors.append("authorization record top-level fields mismatch")
    if payload.get("schema") != "capability-policy-authorization-v1":
        errors.append("authorization record schema mismatch")
    if payload.get("status") != "AUTHORIZED":
        errors.append("authorization record status mismatch")
    if payload.get("implementation_authorized") is not True:
        errors.append("authorization record must carry implementation_authorized=true")
    if payload.get("authorization_scope") != "SLICE_1B_IMPLEMENTATION_ONLY":
        errors.append("authorization record scope mismatch")
    if payload.get("excluded_claims") != list(EXCLUDED_CLAIMS):
        errors.append("authorization record excluded_claims mismatch")
    if payload.get("target") != expected_target:
        errors.append("authorization record target does not match candidate objects")
    if payload.get("target_sha256") != expected_target_hash:
        errors.append("authorization record target_sha256 mismatch")
    expected_quorum = {
        "required": 3,
        "reviews": [
            {"path": path, "sha256": _sha256(review_raw[path])}
            for path in REVIEW_PATHS
            if path in review_raw
        ],
        "rule": QUORUM_RULE,
    }
    if payload.get("quorum") != expected_quorum or len(review_raw) != 3:
        errors.append("authorization record quorum rows mismatch")
    if payload.get("rollback_baseline") != ROLLBACK_BASELINE:
        errors.append("authorization record rollback_baseline mismatch")
    return raw


def _validate_descendant_preservation(
    root: Path,
    candidate: str,
    authorization: str,
    head_commit: str,
    chain: tuple[str, ...],
    errors: list[str],
) -> None:
    candidate_bound_paths = CANDIDATE_IMMUTABLE_PATHS
    try:
        candidate_entries = _tree_entries(root, candidate, candidate_bound_paths)
        authorization_entries = _tree_entries(root, authorization, RESERVED_PATHS)
        if not chain or chain[0] != authorization or chain[-1] != head_commit:
            errors.append("literal authorization descendant chain identity mismatch")
            return
        for commit in chain:
            errors.extend(_authorization_reserved_errors(root, commit))
            errors.extend(
                _protected_alias_errors(root, commit, candidate_bound_paths)
            )
            current_entries = _tree_entries(
                root, commit, (*candidate_bound_paths, *RESERVED_PATHS)
            )
            for path in candidate_bound_paths:
                if current_entries.get(path) != candidate_entries.get(path):
                    errors.append(
                        f"candidate-bound bytes or mode changed after C in {commit}: {path}"
                    )
            for path in RESERVED_PATHS:
                if current_entries.get(path) != authorization_entries.get(path):
                    errors.append(
                        f"authorization-bound gate bytes or mode changed in descendant "
                        f"{commit}: {path}"
                    )
    except AuthorizationValidationError as exc:
        errors.append(str(exc))


def validate_capability_policy_authorization(
    root: Path,
    expected_candidate_commit: str,
    head_commit: str,
) -> CapabilityPolicyAuthorizationResult:
    """Validate authorization using only exact immutable commit IDs C and H."""

    root = root.absolute()
    errors: list[str] = []
    candidate: str | None = None
    validated_head: str | None = None
    candidate_tree: str | None = None
    authorization: str | None = None
    authorization_chain: tuple[str, ...] = ()
    target: dict[str, Any] | None = None
    target_hash: str | None = None
    try:
        if (
            not isinstance(expected_candidate_commit, str)
            or HEX40.fullmatch(expected_candidate_commit) is None
        ):
            raise AuthorizationValidationError(
                "expected candidate commit must be an exact lowercase 40-hex sha1"
            )
        if not isinstance(head_commit, str) or HEX40.fullmatch(head_commit) is None:
            raise AuthorizationValidationError(
                "head commit must be an exact lowercase 40-hex sha1"
            )
        candidate = _resolve_commit(root, expected_candidate_commit)
        validated_head = _resolve_commit(root, head_commit)
        if _git(root, "rev-parse", "--show-object-format").decode("ascii").strip() != "sha1":
            errors.append("repository Git object format must be sha1")
        record_candidate, pre_parsed_record = (
            _extract_candidate_from_authorization_record(
                root, validated_head, errors
            )
        )
        if record_candidate is not None and record_candidate != candidate:
            errors.append(
                "authorization record candidate does not match the required exact candidate: "
                f"expected={candidate} actual={record_candidate}"
            )
        authorization, authorization_chain = _derive_authorization_commit(
            root, candidate, validated_head, errors
        )
        if authorization is not None:
            errors.extend(_candidate_reserved_errors(root, candidate))
            errors.extend(
                _protected_alias_errors(
                    root, candidate, CANDIDATE_IMMUTABLE_PATHS
                )
            )
            errors.extend(_authorization_reserved_errors(root, authorization))
            _validate_exact_authorization_delta(root, candidate, authorization, errors)
            target, target_hash, candidate_tree = _build_candidate_target(root, candidate, errors)
            if target is not None and target_hash is not None:
                review_raw, _payloads = _validate_reviews(
                    root, authorization, target, target_hash, errors
                )
                auth_raw = _validate_authorization_record(
                    root,
                    authorization,
                    target,
                    target_hash,
                    review_raw,
                    pre_parsed_record,
                    errors,
                )
                del auth_raw
                _validate_descendant_preservation(
                    root,
                    candidate,
                    authorization,
                    validated_head,
                    authorization_chain,
                    errors,
                )
        _validate_rollback_tag(root, errors)
    except (AuthorizationValidationError, OSError, UnicodeError) as exc:
        errors.append(str(exc))
    return CapabilityPolicyAuthorizationResult(
        stage="authorization",
        errors=tuple(errors),
        candidate_commit=candidate,
        candidate_tree=candidate_tree,
        authorization_commit=authorization,
        head_commit=validated_head,
        target=target,
        target_sha256=target_hash,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--stage", choices=("candidate", "authorization"), default="candidate")
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--head-commit")
    args = parser.parse_args(argv)
    if args.stage == "candidate":
        if args.head_commit is not None:
            parser.error("--head-commit is only valid with --stage authorization")
        result = validate_capability_policy_candidate(args.root, args.candidate_commit)
    else:
        if args.head_commit is None:
            parser.error("--head-commit is required with --stage authorization")
        result = validate_capability_policy_authorization(
            args.root, args.candidate_commit, args.head_commit
        )
    if result.ok:
        if result.stage == "candidate":
            print(
                "CAPABILITY_POLICY_AUTHORIZATION_CANDIDATE=PASS "
                f"candidate={result.candidate_commit} tree={result.candidate_tree} "
                f"target_sha256={result.target_sha256} implementationAuthorized=false"
            )
        else:
            print(
                "CAPABILITY_POLICY_AUTHORIZATION=AUTHORIZED "
                f"candidate={result.candidate_commit} authorization={result.authorization_commit} "
                f"head={result.head_commit} "
                f"target_sha256={result.target_sha256} scope=SLICE_1B_IMPLEMENTATION_ONLY"
            )
        return 0
    print("CAPABILITY_POLICY_AUTHORIZATION=NOT_AUTHORIZED", file=sys.stderr)
    for error in result.errors:
        print(f"ERROR {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
