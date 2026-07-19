#!/usr/bin/env python3
"""Validate permissions, forbidden components, and pinned model inside an APK."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


MODEL_PATH = "assets/pose_landmarker_lite.task"
MODEL_SIZE = 5_777_746
MODEL_SHA256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
EXPECTED_PACKAGE_NAMES = frozenset(("com.motionarcade.app", "com.motionarcade.app.debug"))
CAMERA_PERMISSION = "android.permission.CAMERA"
BANNED_MANIFEST_MARKERS = (
    "com.google.android.datatransport",
    "CctBackendFactory",
    "JobInfoSchedulerService",
    "AlarmManagerSchedulerBroadcastReceiver",
)
BANNED_PROCESS_ATTRIBUTES = (
    "android:process",
    "android:isolatedProcess",
    "android:multiprocess",
)
BANNED_SHARED_UID_ATTRIBUTES = (
    "android:sharedUserId",
    "android:sharedUserMaxSdkVersion",
)
BANNED_PERMISSION_DECLARATION_NODES = ("permission-tree", "permission-group")
PERMISSION_NAME_RE = re.compile(r"(?:^|\s)name=(?P<quote>['\"])(?P<name>[^'\"]+)(?P=quote)(?=\s|$)")


@dataclass(frozen=True)
class ApkPolicyResult:
    package_name: str | None
    permissions: tuple[str, ...]
    errors: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.errors


def _parse_permission_dump(output: str) -> tuple[str | None, tuple[str, ...]]:
    package_matches = re.findall(r"^package:\s+([^\s]+)\s*$", output, re.MULTILINE)
    if len(package_matches) > 1:
        raise ValueError("aapt2 permission dump contains multiple package lines")
    permissions: list[str] = []
    for line_number, raw_line in enumerate(output.splitlines(), start=1):
        line = raw_line.strip()
        if not line.startswith("uses-permission"):
            continue
        tag, separator, attributes = line.partition(":")
        if not separator or tag != "uses-permission":
            raise ValueError(
                f"aapt2 permission dump line {line_number} is outside the canonical permission form"
            )
        stripped_attributes = attributes.strip()
        name_matches = list(PERMISSION_NAME_RE.finditer(stripped_attributes))
        if len(name_matches) != 1:
            raise ValueError(
                f"aapt2 permission dump line {line_number} must contain exactly one name attribute"
            )
        name_match = name_matches[0]
        residual = (
            stripped_attributes[: name_match.start()]
            + stripped_attributes[name_match.end() :]
        ).strip()
        if residual:
            raise ValueError(
                f"aapt2 permission dump line {line_number} contains attributes other than name"
            )
        permissions.append(name_match.group("name"))
    return (package_matches[0] if package_matches else None), tuple(permissions)


def _run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=False, capture_output=True, text=True)


def _merged_manifest_errors(output: str) -> tuple[str, ...]:
    errors: list[str] = []
    for marker in BANNED_MANIFEST_MARKERS:
        if marker in output:
            errors.append(f"forbidden network/telemetry component in merged manifest: {marker}")
    for attribute in BANNED_PROCESS_ATTRIBUTES:
        if attribute in output:
            errors.append(f"forbidden multiprocess attribute in merged manifest: {attribute}")
    for attribute in BANNED_SHARED_UID_ATTRIBUTES:
        if attribute in output:
            errors.append(f"forbidden shared UID attribute in merged manifest: {attribute}")
    for node in BANNED_PERMISSION_DECLARATION_NODES:
        if re.search(rf"^\s*E:\s+{re.escape(node)}(?:\s|$)", output, re.MULTILINE):
            errors.append(f"forbidden permission declaration node in merged manifest: {node}")
    return tuple(errors)


def _declared_permission_policy_errors(
    output: str, package_name: str | None
) -> tuple[str, ...]:
    errors: list[str] = []
    declarations: list[tuple[str, str]] = []
    lines = output.splitlines()
    index = 0
    while index < len(lines):
        node = re.match(r"^(?P<indent>\s*)E:\s+permission(?:\s|$)", lines[index])
        if node is None:
            index += 1
            continue
        node_indent = len(node.group("indent"))
        names: list[str] = []
        protection_levels: list[str] = []
        index += 1
        while index < len(lines):
            child_node = re.match(r"^(?P<indent>\s*)E:\s+", lines[index])
            if child_node is not None and len(child_node.group("indent")) <= node_indent:
                break
            name_match = re.search(r"android:name\([^)]*\)=\"([^\"]+)\"", lines[index])
            if name_match is not None:
                names.append(name_match.group(1))
            protection_match = re.search(
                r"android:protectionLevel\([^)]*\)=(\S+)", lines[index]
            )
            if protection_match is not None:
                protection_levels.append(protection_match.group(1))
            index += 1
        if len(names) != 1 or len(protection_levels) != 1:
            errors.append(
                "merged manifest permission declaration must contain exactly one "
                "android:name and protectionLevel"
            )
            continue
        declarations.append((names[0], protection_levels[0]))

    if package_name is None:
        if declarations:
            errors.append("cannot bind merged permission declarations without package name")
        return tuple(errors)
    expected = f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    matching = [level for name, level in declarations if name == expected]
    unexpected = [name for name, _ in declarations if name != expected]
    if len(matching) != 1:
        errors.append(
            f"merged manifest must declare receiver permission exactly once: "
            f"{expected} count={len(matching)}"
        )
    elif matching[0] != "0x00000002":
        errors.append(
            f"merged receiver permission must use exact signature protectionLevel: "
            f"actual={matching[0]}"
        )
    for name in unexpected:
        errors.append(f"unapproved merged APK permission declaration: {name}")
    return tuple(errors)


def _permission_policy_errors(
    package_name: str | None, permissions: tuple[str, ...]
) -> tuple[str, ...]:
    errors: list[str] = []
    if package_name is None:
        errors.append("APK permission dump did not contain a package name")
    elif package_name not in EXPECTED_PACKAGE_NAMES:
        errors.append(f"unexpected APK package name: {package_name}")
    if permissions.count(CAMERA_PERMISSION) != 1:
        errors.append(
            f"merged APK must declare {CAMERA_PERMISSION} exactly once: "
            f"count={permissions.count(CAMERA_PERMISSION)}"
        )
    receiver_permission = (
        f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        if package_name is not None
        else None
    )
    if receiver_permission is not None and permissions.count(receiver_permission) != 1:
        errors.append(
            f"merged APK must request {receiver_permission} exactly once: "
            f"count={permissions.count(receiver_permission)}"
        )
    for permission in permissions:
        if permission == CAMERA_PERMISSION:
            continue
        if receiver_permission is not None and permission == receiver_permission:
            continue
        errors.append(f"unapproved merged APK permission: {permission}")
    if len(permissions) != len(set(permissions)):
        errors.append("merged APK permission dump contains duplicate permission entries")
    return tuple(errors)


def validate_apk_policy(
    apk: Path,
    aapt2: Path,
    *,
    runner: Callable[[list[str]], subprocess.CompletedProcess[str]] = _run,
) -> ApkPolicyResult:
    errors: list[str] = []
    if not apk.is_file():
        return ApkPolicyResult(None, (), (f"APK missing: {apk}",))
    if not aapt2.is_file():
        return ApkPolicyResult(None, (), (f"aapt2 missing: {aapt2}",))

    permission_result = runner([str(aapt2), "dump", "permissions", str(apk)])
    if permission_result.returncode != 0:
        return ApkPolicyResult(
            None,
            (),
            (f"aapt2 permission dump failed: {permission_result.stderr.strip()}",),
        )
    try:
        package_name, permissions = _parse_permission_dump(permission_result.stdout)
    except ValueError as exc:
        package_name, permissions = None, ()
        errors.append(f"cannot parse aapt2 permission dump: {exc}")
    errors.extend(_permission_policy_errors(package_name, permissions))

    manifest_result = runner(
        [str(aapt2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"]
    )
    if manifest_result.returncode != 0:
        errors.append(f"aapt2 manifest dump failed: {manifest_result.stderr.strip()}")
    else:
        errors.extend(_merged_manifest_errors(manifest_result.stdout))
        errors.extend(_declared_permission_policy_errors(manifest_result.stdout, package_name))

    try:
        with zipfile.ZipFile(apk) as archive:
            try:
                info = archive.getinfo(MODEL_PATH)
            except KeyError:
                errors.append(f"pinned model missing from APK: {MODEL_PATH}")
            else:
                digest = hashlib.sha256()
                with archive.open(info) as model:
                    for block in iter(lambda: model.read(1024 * 1024), b""):
                        digest.update(block)
                if info.file_size != MODEL_SIZE:
                    errors.append(f"APK model size mismatch: {info.file_size}")
                if digest.hexdigest() != MODEL_SHA256:
                    errors.append(f"APK model SHA-256 mismatch: {digest.hexdigest()}")
    except zipfile.BadZipFile as exc:
        errors.append(f"invalid APK ZIP: {exc}")

    return ApkPolicyResult(package_name, permissions, tuple(errors))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    args = parser.parse_args()
    result = validate_apk_policy(args.apk, args.aapt2)
    if result.ok:
        print(
            f"APK_POLICY=PASS package={result.package_name} "
            f"permissions={','.join(result.permissions)} modelSha256={MODEL_SHA256}"
        )
        return 0
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"APK_POLICY=FAIL errors={len(result.errors)}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
