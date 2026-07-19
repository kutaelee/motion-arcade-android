#!/usr/bin/env python3
"""Map every APK native library to one verified runtime-locked Maven AAR."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO


DEFAULT_CONFIGURATION = "debugRuntimeClasspath"
INVENTORY_SCHEMA_VERSION = 1
SUPPORTED_ANDROID_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
VERIFICATION_NAMESPACE = "https://schema.gradle.org/dependency-verification"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


class NativeRuntimeValidationError(ValueError):
    """Raised when native ownership cannot be proved without guessing."""


@dataclass(frozen=True)
class NativeMember:
    path: str
    size: int
    sha256: str


@dataclass(frozen=True)
class AarArtifact:
    coordinate: str
    filename: str
    size: int
    sha256: str
    native_members: tuple[NativeMember, ...]


def _sha256_stream(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def _sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return _sha256_stream(stream)


def _input_label(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPOSITORY_ROOT).as_posix()
    except ValueError:
        return path.name


def _require_file(path: Path, description: str) -> None:
    if not path.is_file():
        raise NativeRuntimeValidationError(f"{description} missing: {path}")


def _parse_runtime_lock(lock_file: Path, configuration: str) -> tuple[str, ...]:
    _require_file(lock_file, "Gradle lockfile")
    coordinates: list[str] = []
    seen: set[str] = set()
    configuration_seen = False
    explicit_empty = False
    for line_number, raw_line in enumerate(
        lock_file.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise NativeRuntimeValidationError(
                f"malformed Gradle lockfile line {line_number}: missing '='"
            )
        coordinate, raw_configurations = line.split("=", 1)
        configurations = raw_configurations.split(",")
        if (
            not coordinate
            or not raw_configurations
            or any(not item or item.strip() != item for item in configurations)
        ):
            raise NativeRuntimeValidationError(
                f"malformed Gradle lockfile line {line_number}"
            )
        if configuration not in configurations:
            continue
        configuration_seen = True
        if coordinate == "empty":
            explicit_empty = True
            continue
        if len(coordinate.split(":")) != 3 or any(
            not part for part in coordinate.split(":")
        ):
            raise NativeRuntimeValidationError(
                f"unsupported locked coordinate at line {line_number}: {coordinate}"
            )
        if coordinate in seen:
            raise NativeRuntimeValidationError(
                f"duplicate locked coordinate for {configuration}: {coordinate}"
            )
        seen.add(coordinate)
        coordinates.append(coordinate)
    if not configuration_seen:
        raise NativeRuntimeValidationError(
            f"configuration absent from Gradle lockfile: {configuration}"
        )
    if explicit_empty and coordinates:
        raise NativeRuntimeValidationError(
            f"configuration is both empty and populated: {configuration}"
        )
    if explicit_empty or not coordinates:
        raise NativeRuntimeValidationError(
            f"configuration has no locked coordinates: {configuration}"
        )
    return tuple(sorted(coordinates))


def _parse_verified_aar_hashes(
    metadata_file: Path,
) -> dict[str, frozenset[str]]:
    _require_file(metadata_file, "dependency verification metadata")
    try:
        root = ET.parse(metadata_file).getroot()
    except ET.ParseError as exc:
        raise NativeRuntimeValidationError(
            f"invalid dependency verification XML: {exc}"
        ) from exc
    namespace = {"g": VERIFICATION_NAMESPACE}
    if root.tag != f"{{{VERIFICATION_NAMESPACE}}}verification-metadata":
        raise NativeRuntimeValidationError(
            "unexpected dependency verification metadata namespace/root"
        )
    verify_metadata = root.findtext(
        "g:configuration/g:verify-metadata", namespaces=namespace
    )
    if verify_metadata != "true":
        raise NativeRuntimeValidationError(
            "dependency verification metadata must set verify-metadata=true"
        )

    result: dict[str, frozenset[str]] = {}
    for component in root.findall("g:components/g:component", namespace):
        group = component.get("group")
        name = component.get("name")
        version = component.get("version")
        if not group or not name or not version:
            raise NativeRuntimeValidationError(
                "dependency verification component is missing group/name/version"
            )
        coordinate = f"{group}:{name}:{version}"
        if coordinate in result:
            raise NativeRuntimeValidationError(
                f"duplicate dependency verification component: {coordinate}"
            )
        aar_hashes: set[str] = set()
        for artifact in component.findall("g:artifact", namespace):
            artifact_name = artifact.get("name")
            if not artifact_name or not artifact_name.lower().endswith(".aar"):
                continue
            hashes = {
                (element.get("value") or "").lower()
                for element in artifact.findall("g:sha256", namespace)
            }
            if not hashes or any(not SHA256_RE.fullmatch(item) for item in hashes):
                raise NativeRuntimeValidationError(
                    f"verified AAR has missing/invalid SHA-256: "
                    f"{coordinate} {artifact_name}"
                )
            aar_hashes.update(hashes)
        result[coordinate] = frozenset(aar_hashes)
    if not result:
        raise NativeRuntimeValidationError(
            "dependency verification metadata has no components"
        )
    return result


def _native_zip_path(name: str, roots: frozenset[str]) -> bool:
    path = PurePosixPath(name)
    return (
        len(path.parts) >= 3
        and path.parts[0] in roots
        and path.suffix == ".so"
        and not any(part in {"", ".", ".."} for part in path.parts)
        and "\\" not in name
    )


def _native_members(
    archive: zipfile.ZipFile,
    *,
    roots: frozenset[str],
    archive_label: str,
) -> tuple[NativeMember, ...]:
    malformed = sorted(
        info.filename
        for info in archive.infolist()
        if info.filename.lower().endswith(".so")
        and any(
            info.filename.startswith(f"{root}/")
            or info.filename.startswith(f"{root}\\")
            for root in roots
        )
        and not _native_zip_path(info.filename, roots)
    )
    if malformed:
        raise NativeRuntimeValidationError(
            f"malformed native ZIP entries in {archive_label}: {', '.join(malformed)}"
        )
    candidates = [
        info for info in archive.infolist() if _native_zip_path(info.filename, roots)
    ]
    duplicates = sorted(
        name for name, count in Counter(info.filename for info in candidates).items() if count > 1
    )
    if duplicates:
        raise NativeRuntimeValidationError(
            f"duplicate native ZIP entries in {archive_label}: {', '.join(duplicates)}"
        )
    members: list[NativeMember] = []
    for info in sorted(candidates, key=lambda item: item.filename):
        try:
            with archive.open(info) as stream:
                digest = _sha256_stream(stream)
        except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
            raise NativeRuntimeValidationError(
                f"cannot read native ZIP entry {archive_label}!{info.filename}: {exc}"
            ) from exc
        members.append(NativeMember(info.filename, info.file_size, digest))
    return tuple(members)


def _load_runtime_aars(
    coordinates: tuple[str, ...],
    cache_root: Path,
    verified_hashes: dict[str, frozenset[str]],
) -> tuple[AarArtifact, ...]:
    if not cache_root.is_dir():
        raise NativeRuntimeValidationError(f"Gradle Maven cache missing: {cache_root}")
    artifacts: list[AarArtifact] = []
    seen_artifacts: set[tuple[str, str]] = set()
    for coordinate in coordinates:
        if coordinate not in verified_hashes:
            raise NativeRuntimeValidationError(
                f"runtime-locked component absent from verification metadata: {coordinate}"
            )
        group, name, version = coordinate.split(":")
        coordinate_root = cache_root / group / name / version
        cached_aars = (
            sorted(coordinate_root.glob("*/*.aar"), key=lambda path: path.as_posix())
            if coordinate_root.is_dir()
            else []
        )
        allowed_hashes = verified_hashes[coordinate]
        if allowed_hashes and not cached_aars:
            raise NativeRuntimeValidationError(
                f"verified runtime AAR missing from Gradle cache: {coordinate}"
            )
        if cached_aars and not allowed_hashes:
            raise NativeRuntimeValidationError(
                f"cached runtime AAR has no AAR checksum in verification metadata: "
                f"{coordinate}"
            )
        for aar_path in cached_aars:
            aar_sha256 = _sha256_file(aar_path)
            if aar_sha256 not in allowed_hashes:
                raise NativeRuntimeValidationError(
                    f"cached runtime AAR SHA-256 is not verified: "
                    f"{coordinate} {aar_path.name} {aar_sha256}"
                )
            identity = (coordinate, aar_sha256)
            if identity in seen_artifacts:
                raise NativeRuntimeValidationError(
                    f"duplicate cached runtime AAR artifact: {coordinate} {aar_sha256}"
                )
            seen_artifacts.add(identity)
            try:
                with zipfile.ZipFile(aar_path) as archive:
                    members = _native_members(
                        archive,
                        roots=frozenset({"jni", "lib"}),
                        archive_label=f"{coordinate}/{aar_path.name}",
                    )
            except zipfile.BadZipFile as exc:
                raise NativeRuntimeValidationError(
                    f"invalid cached runtime AAR ZIP: {coordinate} {aar_path.name}: {exc}"
                ) from exc
            artifacts.append(
                AarArtifact(
                    coordinate=coordinate,
                    filename=aar_path.name,
                    size=aar_path.stat().st_size,
                    sha256=aar_sha256,
                    native_members=members,
                )
            )
    return tuple(
        sorted(artifacts, key=lambda item: (item.coordinate, item.sha256, item.filename))
    )


def _aar_member_tail(path: str) -> tuple[str, ...]:
    return PurePosixPath(path).parts[1:]


def _semantic_inventory(inventory: dict[str, Any]) -> dict[str, Any]:
    semantic = copy.deepcopy(inventory)
    apk = semantic.get("apk")
    if isinstance(apk, dict):
        apk.pop("path", None)
    inputs = semantic.get("inputs")
    if isinstance(inputs, dict):
        inputs.pop("lockFile", None)
        inputs.pop("verificationMetadata", None)
    return semantic


def _check_expected_inventory(
    expected_inventory: Path,
    inventory: dict[str, Any],
) -> None:
    _require_file(expected_inventory, "expected native runtime inventory")
    try:
        expected = json.loads(expected_inventory.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise NativeRuntimeValidationError(
            f"invalid expected native runtime inventory JSON: {exc}"
        ) from exc
    if not isinstance(expected, dict):
        raise NativeRuntimeValidationError(
            "expected native runtime inventory must be a JSON object"
        )
    expected_apk = expected.get("apk")
    expected_sha = expected_apk.get("sha256") if isinstance(expected_apk, dict) else None
    if not isinstance(expected_sha, str) or not SHA256_RE.fullmatch(expected_sha):
        raise NativeRuntimeValidationError(
            "expected native runtime inventory has no valid apk.sha256"
        )
    actual_sha = inventory["apk"]["sha256"]
    if expected_sha != actual_sha:
        raise NativeRuntimeValidationError(
            f"expected inventory APK SHA-256 mismatch: "
            f"expected={expected_sha} actual={actual_sha}"
        )
    if _semantic_inventory(expected) != _semantic_inventory(inventory):
        raise NativeRuntimeValidationError(
            "expected native runtime inventory differs from current ownership inventory"
        )


def validate_native_runtime(
    apk: Path,
    lock_file: Path,
    verification_metadata: Path,
    cache_root: Path,
    *,
    configuration: str = DEFAULT_CONFIGURATION,
    expected_inventory: Path | None = None,
    expected_abi: str | None = None,
) -> dict[str, Any]:
    """Return deterministic ownership inventory or raise on any proof gap."""

    _require_file(apk, "APK")
    coordinates = _parse_runtime_lock(lock_file, configuration)
    verified_hashes = _parse_verified_aar_hashes(verification_metadata)
    artifacts = _load_runtime_aars(coordinates, cache_root, verified_hashes)

    matches_by_sha: dict[str, list[tuple[AarArtifact, NativeMember]]] = defaultdict(list)
    for artifact in artifacts:
        for member in artifact.native_members:
            matches_by_sha[member.sha256].append((artifact, member))

    apk_sha256 = _sha256_file(apk)
    try:
        with zipfile.ZipFile(apk) as archive:
            zip_entry_count = len(archive.infolist())
            apk_members = _native_members(
                archive,
                roots=frozenset({"lib"}),
                archive_label=_input_label(apk),
            )
    except zipfile.BadZipFile as exc:
        raise NativeRuntimeValidationError(f"invalid APK ZIP: {exc}") from exc
    if not apk_members:
        raise NativeRuntimeValidationError("APK has no lib/**/*.so entries")
    if expected_abi is not None:
        if expected_abi not in SUPPORTED_ANDROID_ABIS:
            raise NativeRuntimeValidationError(
                f"unsupported expected Android ABI: {expected_abi}"
            )
        actual_abis = sorted(
            {PurePosixPath(member.path).parts[1] for member in apk_members}
        )
        if actual_abis != [expected_abi]:
            raise NativeRuntimeValidationError(
                "APK native ABI set mismatch: "
                f"expected={expected_abi} actual={','.join(actual_abis)}"
            )

    native_entries: list[dict[str, Any]] = []
    ownership_errors: list[str] = []
    for apk_member in apk_members:
        apk_tail = _aar_member_tail(apk_member.path)
        exact_matches = [
            (artifact, member)
            for artifact, member in matches_by_sha.get(apk_member.sha256, [])
            if _aar_member_tail(member.path) == apk_tail
        ]
        if not exact_matches:
            ownership_errors.append(
                f"unmatched APK native entry: {apk_member.path} {apk_member.sha256}"
            )
            continue
        if len(exact_matches) > 1:
            candidates = sorted(
                f"{artifact.coordinate}@{artifact.sha256}!{member.path}"
                for artifact, member in exact_matches
            )
            ownership_errors.append(
                f"ambiguous APK native entry: {apk_member.path} "
                f"matches={','.join(candidates)}"
            )
            continue
        owner = exact_matches[0][0].coordinate
        owner_artifacts: dict[tuple[str, str, int], list[str]] = defaultdict(list)
        for artifact, member in exact_matches:
            if artifact.coordinate == owner:
                owner_artifacts[
                    (artifact.filename, artifact.sha256, artifact.size)
                ].append(member.path)
        native_entries.append(
            {
                "abi": PurePosixPath(apk_member.path).parts[1],
                "apkPath": apk_member.path,
                "bytes": apk_member.size,
                "filename": PurePosixPath(apk_member.path).name,
                "owner": {
                    "artifacts": [
                        {
                            "bytes": size,
                            "filename": filename,
                            "sha256": digest,
                            "sourceEntries": sorted(source_entries),
                        }
                        for (filename, digest, size), source_entries in sorted(
                            owner_artifacts.items()
                        )
                    ],
                    "coordinate": owner,
                },
                "sha256": apk_member.sha256,
            }
        )
    if ownership_errors:
        raise NativeRuntimeValidationError("; ".join(sorted(ownership_errors)))

    owner_coordinates = sorted(
        {entry["owner"]["coordinate"] for entry in native_entries}
    )
    inventory: dict[str, Any] = {
        "apk": {
            "bytes": apk.stat().st_size,
            "nativeBytes": sum(member.size for member in apk_members),
            "nativeEntryCount": len(apk_members),
            "path": _input_label(apk),
            "sha256": apk_sha256,
            "zipEntryCount": zip_entry_count,
        },
        "inputs": {
            "aarArtifactsScanned": len(artifacts),
            "aarCoordinatesScanned": len(
                {artifact.coordinate for artifact in artifacts}
            ),
            "configuration": configuration,
            "lockFile": _input_label(lock_file),
            "lockFileSha256": _sha256_file(lock_file),
            "runtimeLockedCoordinateCount": len(coordinates),
            "verificationMetadata": _input_label(verification_metadata),
            "verificationMetadataSha256": _sha256_file(verification_metadata),
        },
        "nativeEntries": sorted(native_entries, key=lambda item: item["apkPath"]),
        "ownerCoordinates": owner_coordinates,
        "schemaVersion": INVENTORY_SCHEMA_VERSION,
        "summary": {
            "ambiguousEntries": 0,
            "duplicateEntries": 0,
            "matchedEntries": len(native_entries),
            "unmatchedEntries": 0,
            "verdict": "PASS",
        },
    }
    if expected_inventory is not None:
        _check_expected_inventory(expected_inventory, inventory)
    return inventory


def _default_cache_root() -> Path:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return gradle_home / "caches/modules-2/files-2.1"


def _write_inventory(path: Path, inventory: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(inventory, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def _resolve_from_root(root: Path, path: Path) -> Path:
    return path if path.is_absolute() else root / path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--lockfile", type=Path, default=Path("app/gradle.lockfile"))
    parser.add_argument(
        "--verification-metadata",
        type=Path,
        default=Path("gradle/verification-metadata.xml"),
    )
    parser.add_argument("--maven-cache", type=Path, default=_default_cache_root())
    parser.add_argument("--configuration", default=DEFAULT_CONFIGURATION)
    parser.add_argument("--expected-abi", choices=SUPPORTED_ANDROID_ABIS)
    parser.add_argument("--expected-inventory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        inventory = validate_native_runtime(
            _resolve_from_root(root, args.apk),
            _resolve_from_root(root, args.lockfile),
            _resolve_from_root(root, args.verification_metadata),
            args.maven_cache,
            configuration=args.configuration,
            expected_inventory=(
                _resolve_from_root(root, args.expected_inventory)
                if args.expected_inventory is not None
                else None
            ),
            expected_abi=args.expected_abi,
        )
    except (NativeRuntimeValidationError, OSError) as exc:
        print(f"NATIVE_RUNTIME=FAIL error={exc}", file=sys.stderr)
        return 1
    if args.output is None:
        print(json.dumps(inventory, indent=2, sort_keys=True))
    else:
        _write_inventory(args.output, inventory)
    print(
        f"NATIVE_RUNTIME=PASS apkSha256={inventory['apk']['sha256']} "
        f"nativeEntries={inventory['apk']['nativeEntryCount']} "
        f"owners={len(inventory['ownerCoordinates'])}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
