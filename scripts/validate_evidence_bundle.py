#!/usr/bin/env python3
"""Validate a content-addressed APK handoff and its evidence provenance."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Sequence


SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
GIT_OBJECT_RE = re.compile(r"^[0-9a-fA-F]{40}$")
PACKAGE_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$")
MANIFEST_LINE_RE = re.compile(r"^(?P<sha>[0-9a-fA-F]{64})  (?P<path>[^\r\n]+)$")

TOP_LEVEL_FIELDS = {
    "schemaVersion",
    "sourceCommit",
    "sourceTree",
    "buildCommand",
    "buildEnvironment",
    "artifact",
    "validationEvidence",
}
BUILD_ENVIRONMENT_FIELDS = {
    "os",
    "jdk",
    "gradle",
    "agp",
    "kotlin",
    "compileSdk",
    "targetSdk",
    "buildTools",
}
ARTIFACT_FIELDS = {"path", "sha256", "bytes", "variant", "package"}
VALIDATION_EVIDENCE_FIELDS = {
    "api33Instrumentation",
    "api37Instrumentation",
    "apkPolicy",
    "runtimeLicenses",
    "nativeOwnership",
}

GitRunner = Callable[..., subprocess.CompletedProcess[str]]


@dataclass(frozen=True)
class EvidenceBundleResult:
    errors: tuple[str, ...]
    entry_count: int = 0
    artifact_path: str | None = None
    artifact_sha256: str | None = None

    @property
    def passed(self) -> bool:
        return not self.errors


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _exact_fields(
    value: Any,
    expected: set[str],
    label: str,
    errors: list[str],
) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        errors.append(f"{label} must be an object")
        return None
    actual = set(value)
    missing = sorted(expected - actual)
    unknown = sorted(actual - expected)
    if missing:
        errors.append(f"{label} missing fields: {missing}")
    if unknown:
        errors.append(f"{label} unknown fields: {unknown}")
    return value


def _safe_relative_path(raw: Any, root: Path, label: str, errors: list[str]) -> tuple[str, Path] | None:
    if not isinstance(raw, str) or not raw:
        errors.append(f"{label} must be a non-empty POSIX relative path")
        return None
    if "\\" in raw:
        errors.append(f"{label} must use forward slashes: {raw!r}")
        return None
    pure = PurePosixPath(raw)
    if (
        pure.is_absolute()
        or pure.as_posix() != raw
        or any(part in {"", ".", ".."} or ":" in part for part in pure.parts)
    ):
        errors.append(f"{label} is unsafe or non-canonical: {raw!r}")
        return None
    candidate = (root / Path(*pure.parts)).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        errors.append(f"{label} escapes repository root: {raw!r}")
        return None
    return raw, candidate


def _resolve_control_path(path: Path, root: Path, label: str, errors: list[str]) -> Path | None:
    candidate = path if path.is_absolute() else root / path
    candidate = candidate.resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        errors.append(f"{label} escapes repository root: {path}")
        return None
    return candidate


def _parse_manifest(path: Path, root: Path, errors: list[str]) -> dict[str, tuple[str, Path]]:
    entries: dict[str, tuple[str, Path]] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        errors.append(f"cannot read evidence manifest: {exc}")
        return entries
    if not lines:
        errors.append("evidence manifest is empty")
        return entries
    for line_number, line in enumerate(lines, start=1):
        match = MANIFEST_LINE_RE.fullmatch(line)
        if match is None:
            errors.append(f"manifest line {line_number} is not '<sha256>  <path>'")
            continue
        raw_path = match.group("path")
        resolved = _safe_relative_path(
            raw_path,
            root,
            f"manifest line {line_number} path",
            errors,
        )
        if resolved is None:
            continue
        canonical, absolute = resolved
        key = canonical.casefold()
        if key in entries:
            errors.append(f"manifest has duplicate path: {canonical}")
            continue
        entries[key] = (match.group("sha").lower(), absolute)
    for expected_hash, absolute in entries.values():
        if not absolute.is_file():
            errors.append(f"manifest entry is missing or not a file: {absolute.relative_to(root).as_posix()}")
            continue
        actual_hash = _sha256(absolute)
        if actual_hash != expected_hash:
            errors.append(
                f"manifest hash mismatch for {absolute.relative_to(root).as_posix()}: "
                f"expected={expected_hash} actual={actual_hash}"
            )
    return entries


def _manifest_contains(
    entries: dict[str, tuple[str, Path]],
    relative_path: str,
    label: str,
    errors: list[str],
) -> None:
    if relative_path.casefold() not in entries:
        errors.append(f"{label} is not listed in the evidence manifest: {relative_path}")


def _validate_git_binding(
    root: Path,
    source_commit: Any,
    source_tree: Any,
    errors: list[str],
    git_runner: GitRunner,
) -> None:
    if not isinstance(source_commit, str) or GIT_OBJECT_RE.fullmatch(source_commit) is None:
        errors.append("sourceCommit must be a 40-hex commit id")
        return
    if not isinstance(source_tree, str) or GIT_OBJECT_RE.fullmatch(source_tree) is None:
        errors.append("sourceTree must be a 40-hex tree id")
        return
    common = {"cwd": root, "capture_output": True, "text": True, "check": False}
    try:
        exists = git_runner(
            ["git", "cat-file", "-e", f"{source_commit}^{{commit}}"],
            **common,
        )
        if exists.returncode != 0:
            errors.append(f"sourceCommit is not an available commit: {source_commit}")
            return
        observed = git_runner(
            ["git", "show", "-s", "--format=%T", source_commit],
            **common,
        )
    except OSError as exc:
        errors.append(f"cannot verify source Git binding: {exc}")
        return
    if observed.returncode != 0:
        errors.append(f"cannot read tree for sourceCommit: {source_commit}")
        return
    actual_tree = observed.stdout.strip()
    if actual_tree.casefold() != source_tree.casefold():
        errors.append(
            f"sourceTree mismatch: expected={source_tree.lower()} actual={actual_tree.lower()}"
        )


def validate_evidence_bundle(
    root: Path,
    manifest_path: Path,
    provenance_path: Path,
    *,
    git_runner: GitRunner = subprocess.run,
) -> EvidenceBundleResult:
    root = root.resolve()
    errors: list[str] = []
    manifest = _resolve_control_path(manifest_path, root, "manifest path", errors)
    provenance = _resolve_control_path(provenance_path, root, "provenance path", errors)
    if manifest is None or provenance is None:
        return EvidenceBundleResult(tuple(errors))

    entries = _parse_manifest(manifest, root, errors)
    try:
        raw = provenance.read_text(encoding="utf-8")
        document = json.loads(raw, object_pairs_hook=_reject_duplicate_json_keys)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        errors.append(f"cannot read artifact provenance: {exc}")
        return EvidenceBundleResult(tuple(errors), len(entries))

    top = _exact_fields(document, TOP_LEVEL_FIELDS, "provenance", errors)
    if top is None:
        return EvidenceBundleResult(tuple(errors), len(entries))
    if top.get("schemaVersion") != 1 or isinstance(top.get("schemaVersion"), bool):
        errors.append("schemaVersion must equal integer 1")

    _validate_git_binding(
        root,
        top.get("sourceCommit"),
        top.get("sourceTree"),
        errors,
        git_runner,
    )

    command = top.get("buildCommand")
    if (
        not isinstance(command, list)
        or not command
        or any(not isinstance(item, str) or not item for item in command)
    ):
        errors.append("buildCommand must be a non-empty argv string array")

    environment = _exact_fields(
        top.get("buildEnvironment"),
        BUILD_ENVIRONMENT_FIELDS,
        "buildEnvironment",
        errors,
    )
    if environment is not None:
        for key in sorted(BUILD_ENVIRONMENT_FIELDS):
            if not isinstance(environment.get(key), str) or not environment.get(key):
                errors.append(f"buildEnvironment.{key} must be a non-empty string")

    artifact = _exact_fields(top.get("artifact"), ARTIFACT_FIELDS, "artifact", errors)
    artifact_relative: str | None = None
    artifact_sha: str | None = None
    if artifact is not None:
        resolved_artifact = _safe_relative_path(
            artifact.get("path"), root, "artifact.path", errors
        )
        if resolved_artifact is not None:
            artifact_relative, artifact_file = resolved_artifact
            parts = PurePosixPath(artifact_relative).parts
            if not parts or parts[0] != "artifacts" or "build" in {part.casefold() for part in parts}:
                errors.append("artifact.path must be an immutable path below artifacts/, never a build output")
            if artifact_file.suffix.casefold() != ".apk":
                errors.append("artifact.path must name an APK")
        else:
            artifact_file = None

        raw_sha = artifact.get("sha256")
        if not isinstance(raw_sha, str) or SHA256_RE.fullmatch(raw_sha) is None:
            errors.append("artifact.sha256 must be 64 hex characters")
        else:
            artifact_sha = raw_sha.lower()
            if artifact_relative is not None and artifact_sha not in PurePosixPath(artifact_relative).name.casefold():
                errors.append("artifact filename must contain its full SHA-256 digest")

        byte_count = artifact.get("bytes")
        if isinstance(byte_count, bool) or not isinstance(byte_count, int) or byte_count <= 0:
            errors.append("artifact.bytes must be a positive integer")
        if not isinstance(artifact.get("variant"), str) or not artifact.get("variant"):
            errors.append("artifact.variant must be a non-empty string")
        package = artifact.get("package")
        if not isinstance(package, str) or PACKAGE_RE.fullmatch(package) is None:
            errors.append("artifact.package must be a valid Android application id")

        if artifact_file is not None and artifact_file.is_file():
            actual_sha = _sha256(artifact_file)
            actual_bytes = artifact_file.stat().st_size
            if artifact_sha is not None and actual_sha != artifact_sha:
                errors.append(
                    f"artifact hash mismatch: expected={artifact_sha} actual={actual_sha}"
                )
            if isinstance(byte_count, int) and not isinstance(byte_count, bool) and actual_bytes != byte_count:
                errors.append(
                    f"artifact byte count mismatch: expected={byte_count} actual={actual_bytes}"
                )
        elif artifact_file is not None:
            errors.append(f"artifact is missing or not a file: {artifact_relative}")

        if artifact_relative is not None:
            _manifest_contains(entries, artifact_relative, "artifact.path", errors)

    validation = _exact_fields(
        top.get("validationEvidence"),
        VALIDATION_EVIDENCE_FIELDS,
        "validationEvidence",
        errors,
    )
    if validation is not None:
        for key in sorted(VALIDATION_EVIDENCE_FIELDS):
            resolved = _safe_relative_path(
                validation.get(key), root, f"validationEvidence.{key}", errors
            )
            if resolved is not None:
                relative, absolute = resolved
                if not absolute.is_file():
                    errors.append(
                        f"validationEvidence.{key} is missing or not a file: {relative}"
                    )
                _manifest_contains(
                    entries,
                    relative,
                    f"validationEvidence.{key}",
                    errors,
                )

    provenance_relative = provenance.relative_to(root).as_posix()
    _manifest_contains(entries, provenance_relative, "provenance file", errors)
    return EvidenceBundleResult(
        tuple(errors),
        len(entries),
        artifact_relative,
        artifact_sha,
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    result = validate_evidence_bundle(args.root, args.manifest, args.provenance)
    if result.passed:
        print(
            "EVIDENCE_BUNDLE=PASS "
            f"entries={result.entry_count} artifact={result.artifact_path} "
            f"sha256={result.artifact_sha256}"
        )
        return 0
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"EVIDENCE_BUNDLE=FAIL errors={len(result.errors)}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
