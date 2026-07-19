#!/usr/bin/env python3
"""Fail-closed Native Close fence Phase-A generator and verifier.

This module deliberately owns its canonical codec and APK parser.  The independent
validator in ``validate_native_close_fence.py`` does not import this module.  Phase A
constructors create only policy/code/JNI/basis preimages.  There is intentionally no
constructor for semantic evidence, approval, a bundle, or a registry row.
"""

from __future__ import annotations

import argparse
import binascii
import hashlib
import re
import struct
import sys
import zlib
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Iterable, Mapping, Sequence


class NativeCloseFenceError(ValueError):
    """A malformed or cross-inconsistent Native Close artifact."""

    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


class ProofUnavailableReason(str, Enum):
    EMPTY_REGISTRY = "EMPTY_REGISTRY"
    MISSING_BUNDLE = "MISSING_BUNDLE"
    EXTERNAL_VERIFIER_UNIMPLEMENTED = "EXTERNAL_VERIFIER_UNIMPLEMENTED"


@dataclass(frozen=True)
class FenceVerification:
    authorized: bool
    reason: ProofUnavailableReason | None
    entry_count: int


@dataclass(frozen=True)
class CanonicalManifest:
    domain: str
    values: tuple[bytes, ...]


@dataclass(frozen=True)
class ApkEntry:
    raw_name: bytes
    flags: int
    method: int
    crc32: int
    compressed_size: int
    uncompressed_size: int
    local_offset: int
    payload_offset: int
    payload_end: int
    content_sha256: bytes

    @property
    def name(self) -> str:
        return self.raw_name.decode("ascii")


@dataclass(frozen=True)
class ApkView:
    entries: tuple[ApkEntry, ...]
    signing_block_present: bool
    central_offset: int
    central_size: int


SCHEMAS: dict[str, tuple[str, ...]] = {
    "native-close-policy-file-v1": ("logical_path", "file_length", "file_sha256"),
    "native-close-policy-set-v1": ("schema_revision", "policy_files"),
    "native-close-dex-entry-v1": (
        "package_output_name",
        "dex_entry_name",
        "uncompressed_length",
        "content_sha256",
    ),
    "native-close-code-image-v1": ("schema_revision", "dex_entries"),
    "native-close-build-variant-v1": ("build_type", "minified"),
    "native-close-proof-basis-v1": (
        "schema_revision",
        "native_close_policy_set_sha256",
        "dependency_artifacts_value_sha256",
        "build_variant",
        "native_close_code_image_sha256",
    ),
    "native-close-jni-entry-v1": (
        "package_output_name",
        "jni_entry_name",
        "uncompressed_length",
        "content_sha256",
    ),
    "native-close-installed-jni-set-v1": ("abi", "entries"),
    "native-close-evidence-entry-v1": (
        "category",
        "artifact_length",
        "artifact_sha256",
    ),
    "native-close-proof-evidence-v1": (
        "schema_revision",
        "producer_id",
        "proof_basis_sha256",
        "os_api",
        "abi",
        "installed_jni_set_sha256",
        "delegate_mask",
        "claim",
        "evidence_entries",
    ),
    "native-close-proof-approval-v1": (
        "schema_revision",
        "proof_evidence_sha256",
        "reviewer_id",
        "reviewed_commit",
        "reviewed_tree",
        "verdict",
        "p0_count",
        "p1_count",
        "p2_count",
    ),
    "native-close-proof-bundle-v1": (
        "schema_revision",
        "proof_basis",
        "proof_basis_sha256",
        "proof_evidence",
        "proof_evidence_sha256",
        "proof_approval",
        "proof_approval_sha256",
    ),
    "native-close-fence-entry-v1": (
        "proof_basis_sha256",
        "os_api",
        "abi",
        "installed_jni_set_sha256",
        "delegate_mask",
        "proof_bundle_sha256",
    ),
    "native-close-fence-registry-v1": ("schema_revision", "entries"),
}

POLICY_PATHS = (
    "docs/adr/ADR-011-measured-capability-probe-policy.md",
    "docs/contracts/capability-store-v4.md",
    "docs/contracts/native-close-fence-proof-v1.md",
    "docs/contracts/recovery-journal-v5.md",
    "docs/execution/slice-1b-contract.md",
)
ABIS = frozenset(("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
_DEX_NAME = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?[.]dex\Z")
_JNI_NAME = re.compile(r"lib/([^/]+)/([A-Za-z0-9._+\-]+[.]so)\Z")
_AGENT_ID = re.compile(r"agent:/[a-z0-9_]+(?:/[a-z0-9_]+)*\Z")
_GITHUB_ID = re.compile(r"github:[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?\Z")
_LOWER40 = re.compile(r"[0-9a-f]{40}\Z")
_LOWER64 = re.compile(r"[0-9a-f]{64}\Z")


def _fail(code: str, detail: str) -> None:
    raise NativeCloseFenceError(code, detail)


def _u32(value: int) -> bytes:
    if not 0 <= value <= 0xFFFFFFFF:
        _fail("UINT32_RANGE", str(value))
    return struct.pack(">I", value)


def _u64(value: int) -> bytes:
    if not 0 <= value <= 0xFFFFFFFFFFFFFFFF:
        _fail("UINT64_RANGE", str(value))
    return struct.pack(">Q", value)


def _read_u32(value: bytes, label: str) -> int:
    if len(value) != 4:
        _fail("UINT32_LENGTH", f"{label} has {len(value)} bytes")
    return struct.unpack(">I", value)[0]


def _read_u64(value: bytes, label: str) -> int:
    if len(value) != 8:
        _fail("UINT64_LENGTH", f"{label} has {len(value)} bytes")
    return struct.unpack(">Q", value)[0]


def _raw_hash(value: bytes, label: str, *, nonzero: bool = False) -> bytes:
    if len(value) != 32:
        _fail("HASH_LENGTH", f"{label} has {len(value)} bytes")
    if nonzero and not any(value):
        _fail("ZERO_HASH", label)
    return value


def _utf8(value: bytes, label: str) -> str:
    try:
        text = value.decode("utf-8", "strict")
    except UnicodeDecodeError as exc:
        _fail("UTF8", f"{label}: {exc}")
    if text.encode("utf-8") != value:
        _fail("UTF8_ROUND_TRIP", label)
    return text


def _ascii(value: bytes, label: str) -> str:
    try:
        return value.decode("ascii", "strict")
    except UnicodeDecodeError as exc:
        _fail("ASCII", f"{label}: {exc}")


def encode_manifest(domain: str, values: Sequence[bytes]) -> bytes:
    """Encode one exact fixed-order manifest; intended for Phase-A construction."""

    names = SCHEMAS.get(domain)
    if names is None:
        _fail("UNKNOWN_DOMAIN", domain)
    if len(values) != len(names):
        _fail("FIELD_COUNT", f"{domain}: {len(values)} != {len(names)}")
    domain_bytes = domain.encode("utf-8")
    parts = [domain_bytes, b"\x00", _u32(len(names))]
    for name, value in zip(names, values, strict=True):
        if not isinstance(value, bytes):
            _fail("FIELD_TYPE", f"{domain}.{name} is not bytes")
        name_bytes = name.encode("utf-8")
        parts.extend((_u32(len(name_bytes)), name_bytes, _u64(len(value)), value))
    return b"".join(parts)


class _Cursor:
    def __init__(self, data: bytes, label: str) -> None:
        self.data = data
        self.pos = 0
        self.label = label

    def take(self, count: int) -> bytes:
        if count < 0 or count > len(self.data) - self.pos:
            _fail("TRUNCATED", f"{self.label} at {self.pos}, need {count}")
        start = self.pos
        self.pos += count
        return self.data[start : self.pos]

    def u32(self) -> int:
        return struct.unpack(">I", self.take(4))[0]

    def u64(self) -> int:
        return struct.unpack(">Q", self.take(8))[0]

    def done(self) -> None:
        if self.pos != len(self.data):
            _fail("TRAILING_BYTES", f"{self.label}: {len(self.data) - self.pos}")


def parse_manifest(blob: bytes, expected_domain: str) -> CanonicalManifest:
    """Strictly parse, validate, and byte-identically re-encode a manifest."""

    names = SCHEMAS.get(expected_domain)
    if names is None:
        _fail("UNKNOWN_DOMAIN", expected_domain)
    prefix = expected_domain.encode("utf-8") + b"\x00"
    if not blob.startswith(prefix):
        _fail("DOMAIN", expected_domain)
    cursor = _Cursor(blob[len(prefix) :], expected_domain)
    count = cursor.u32()
    if count != len(names):
        _fail("FIELD_COUNT", f"{expected_domain}: {count} != {len(names)}")
    values: list[bytes] = []
    for expected_name in names:
        name_length = cursor.u32()
        if name_length > 256:
            _fail("FIELD_NAME_LENGTH", f"{expected_domain}: {name_length}")
        raw_name = cursor.take(name_length)
        if raw_name != expected_name.encode("utf-8"):
            _fail("FIELD_ORDER", f"{expected_domain}: expected {expected_name!r}")
        value_length = cursor.u64()
        if value_length > len(cursor.data) - cursor.pos:
            _fail("FIELD_VALUE_LENGTH", f"{expected_domain}.{expected_name}")
        values.append(cursor.take(value_length))
    cursor.done()
    manifest = CanonicalManifest(expected_domain, tuple(values))
    _validate_manifest(manifest)
    if encode_manifest(expected_domain, manifest.values) != blob:
        _fail("NONCANONICAL", expected_domain)
    return manifest


def _encode_list(items: Sequence[bytes]) -> bytes:
    return _u32(len(items)) + b"".join(_u32(len(item)) + item for item in items)


def _parse_list(
    value: bytes,
    domain: str,
    minimum: int,
    maximum: int,
    *,
    item_minimum: int = 1,
    item_maximum: int | None = None,
) -> tuple[tuple[bytes, CanonicalManifest], ...]:
    cursor = _Cursor(value, f"list<{domain}>")
    count = cursor.u32()
    if not minimum <= count <= maximum:
        _fail("LIST_COUNT", f"{domain}: {count} not in {minimum}..{maximum}")
    result: list[tuple[bytes, CanonicalManifest]] = []
    for index in range(count):
        length = cursor.u32()
        if length < item_minimum or (item_maximum is not None and length > item_maximum):
            _fail("ENTRY_LENGTH", f"{domain}[{index}]: {length}")
        raw = cursor.take(length)
        result.append((raw, parse_manifest(raw, domain)))
    cursor.done()
    return tuple(result)


def _framed(value: bytes, domain: str, maximum: int | None = None) -> tuple[bytes, CanonicalManifest]:
    cursor = _Cursor(value, f"framed<{domain}>")
    length = cursor.u32()
    if length <= 0 or (maximum is not None and length > maximum):
        _fail("FRAMED_LENGTH", f"{domain}: {length}")
    raw = cursor.take(length)
    cursor.done()
    return raw, parse_manifest(raw, domain)


def _package_output(value: bytes, label: str) -> str:
    text = _utf8(value, label)
    if not text or text in (".", "..") or any(ch in text for ch in ("/", "\\", "\x00")):
        _fail("PACKAGE_OUTPUT_NAME", text)
    return text


def _identity(value: bytes, label: str) -> str:
    text = _ascii(value, label)
    if not 7 <= len(value) <= 128 or not (_AGENT_ID.fullmatch(text) or _GITHUB_ID.fullmatch(text)):
        _fail("IDENTITY", text)
    return text


def _dex_number(name: str) -> int:
    if name == "classes.dex":
        return 1
    return int(name[7:-4])


def _entry_key(manifest: CanonicalManifest) -> tuple[bytes, bytes]:
    return manifest.values[0], manifest.values[1]


def _validate_manifest(manifest: CanonicalManifest) -> None:
    domain = manifest.domain
    v = manifest.values
    if domain == "native-close-policy-file-v1":
        path = _utf8(v[0], "logical_path")
        if path not in POLICY_PATHS:
            _fail("POLICY_PATH", path)
        if _read_u64(v[1], "file_length") <= 0:
            _fail("FILE_LENGTH", path)
        _raw_hash(v[2], "file_sha256")
    elif domain == "native-close-policy-set-v1":
        if v[0] != domain.encode("utf-8"):
            _fail("SCHEMA_REVISION", domain)
        entries = _parse_list(v[1], "native-close-policy-file-v1", 5, 5)
        paths = tuple(item.values[0].decode("utf-8") for _, item in entries)
        if paths != POLICY_PATHS:
            _fail("POLICY_ORDER", repr(paths))
    elif domain == "native-close-dex-entry-v1":
        _package_output(v[0], "package_output_name")
        name = _ascii(v[1], "dex_entry_name")
        if not _DEX_NAME.fullmatch(name):
            _fail("DEX_NAME", name)
        length = _read_u64(v[2], "uncompressed_length")
        if not 1 <= length <= 268_435_456:
            _fail("DEX_LENGTH", str(length))
        _raw_hash(v[3], "content_sha256")
    elif domain == "native-close-code-image-v1":
        if v[0] != domain.encode("utf-8"):
            _fail("SCHEMA_REVISION", domain)
        entries = _parse_list(v[1], "native-close-dex-entry-v1", 1, 64)
        manifests = [item for _, item in entries]
        keys = [_entry_key(item) for item in manifests]
        if keys != sorted(keys) or len(set(keys)) != len(keys):
            _fail("DEX_ORDER", repr(keys))
        if sum(_read_u64(item.values[2], "uncompressed_length") for item in manifests) > 536_870_912:
            _fail("DEX_TOTAL", "above 536870912")
        by_package: dict[bytes, set[int]] = {}
        for item in manifests:
            by_package.setdefault(item.values[0], set()).add(_dex_number(item.values[1].decode("ascii")))
        for package, numbers in by_package.items():
            if numbers != set(range(1, max(numbers) + 1)):
                _fail("DEX_GAP", package.decode("utf-8"))
    elif domain == "native-close-build-variant-v1":
        _utf8(v[0], "build_type")
        if len(v[1]) != 1 or v[1][0] not in (0, 1):
            _fail("MINIFIED", v[1].hex())
    elif domain == "native-close-proof-basis-v1":
        if v[0] != domain.encode("utf-8"):
            _fail("SCHEMA_REVISION", domain)
        _raw_hash(v[1], "native_close_policy_set_sha256")
        _raw_hash(v[2], "dependency_artifacts_value_sha256")
        parse_manifest(v[3], "native-close-build-variant-v1")
        _raw_hash(v[4], "native_close_code_image_sha256")
    elif domain == "native-close-jni-entry-v1":
        _package_output(v[0], "package_output_name")
        name = _ascii(v[1], "jni_entry_name")
        match = _JNI_NAME.fullmatch(name)
        if match is None or match.group(1) not in ABIS:
            _fail("JNI_NAME", name)
        length = _read_u64(v[2], "uncompressed_length")
        if not 1 <= length <= 268_435_456:
            _fail("JNI_LENGTH", str(length))
        _raw_hash(v[3], "content_sha256")
    elif domain == "native-close-installed-jni-set-v1":
        abi = _utf8(v[0], "abi")
        if abi not in ABIS:
            _fail("ABI", abi)
        entries = _parse_list(v[1], "native-close-jni-entry-v1", 1, 256)
        manifests = [item for _, item in entries]
        keys = [_entry_key(item) for item in manifests]
        if keys != sorted(keys) or len(set(keys)) != len(keys):
            _fail("JNI_ORDER", repr(keys))
        if sum(_read_u64(item.values[2], "uncompressed_length") for item in manifests) > 536_870_912:
            _fail("JNI_TOTAL", "above 536870912")
        logical_names = [item.values[1] for item in manifests]
        if len(logical_names) != len(set(logical_names)):
            _fail("JNI_LOGICAL_PATH_COLLISION", abi)
        for item in manifests:
            if item.values[1].decode("ascii").split("/", 2)[1] != abi:
                _fail("JNI_ABI_MISMATCH", item.values[1].decode("ascii"))
    elif domain == "native-close-evidence-entry-v1":
        if len(v[0]) != 1 or not 0 <= v[0][0] <= 7:
            _fail("EVIDENCE_CATEGORY", v[0].hex())
        length = _read_u64(v[1], "artifact_length")
        if not 1 <= length <= 4_194_304:
            _fail("ARTIFACT_LENGTH", str(length))
        _raw_hash(v[2], "artifact_sha256")
    elif domain == "native-close-proof-evidence-v1":
        if v[0] != domain.encode("utf-8"):
            _fail("SCHEMA_REVISION", domain)
        _identity(v[1], "producer_id")
        _raw_hash(v[2], "proof_basis_sha256")
        api = _read_u32(v[3], "os_api")
        if not 26 <= api <= 37:
            _fail("OS_API", str(api))
        abi = _utf8(v[4], "abi")
        if abi not in ABIS:
            _fail("ABI", abi)
        _raw_hash(v[5], "installed_jni_set_sha256")
        if v[6] != b"\x03":
            _fail("DELEGATE_MASK", v[6].hex())
        if v[7] != b"\x00":
            _fail("CLAIM", v[7].hex())
        entries = _parse_list(v[8], "native-close-evidence-entry-v1", 8, 64)
        manifests = [item for _, item in entries]
        keys = [(item.values[0][0], item.values[2]) for item in manifests]
        if keys != sorted(keys) or len(set(keys)) != len(keys):
            _fail("EVIDENCE_ORDER", repr(keys))
        if set(key[0] for key in keys) != set(range(8)):
            _fail("EVIDENCE_CATEGORIES", repr(keys))
        if sum(_read_u64(item.values[1], "artifact_length") for item in manifests) > 67_108_864:
            _fail("EVIDENCE_TOTAL", "above 67108864")
    elif domain == "native-close-proof-approval-v1":
        if v[0] != domain.encode("utf-8"):
            _fail("SCHEMA_REVISION", domain)
        _raw_hash(v[1], "proof_evidence_sha256")
        _identity(v[2], "reviewer_id")
        commit = _ascii(v[3], "reviewed_commit")
        tree = _ascii(v[4], "reviewed_tree")
        if not _LOWER40.fullmatch(commit) or not _LOWER40.fullmatch(tree):
            _fail("GIT_OBJECT", f"{commit}/{tree}")
        if v[5] != b"\x00":
            _fail("VERDICT", v[5].hex())
        for label, value in zip(("p0_count", "p1_count", "p2_count"), v[6:], strict=True):
            if _read_u32(value, label) != 0:
                _fail("FINDING_COUNT", label)
    elif domain == "native-close-proof-bundle-v1":
        _validate_bundle_manifest(manifest)
    elif domain == "native-close-fence-entry-v1":
        _raw_hash(v[0], "proof_basis_sha256", nonzero=True)
        api = _read_u32(v[1], "os_api")
        if not 26 <= api <= 37:
            _fail("OS_API", str(api))
        abi = _utf8(v[2], "abi")
        if abi not in ABIS:
            _fail("ABI", abi)
        _raw_hash(v[3], "installed_jni_set_sha256", nonzero=True)
        if v[4] != b"\x03":
            _fail("DELEGATE_MASK", v[4].hex())
        _raw_hash(v[5], "proof_bundle_sha256", nonzero=True)
    elif domain == "native-close-fence-registry-v1":
        if len(encode_manifest(domain, v)) > 131_072:
            _fail("REGISTRY_SIZE", str(len(encode_manifest(domain, v))))
        if v[0] != domain.encode("utf-8"):
            _fail("SCHEMA_REVISION", domain)
        entries = _parse_list(
            v[1], "native-close-fence-entry-v1", 0, 512, item_minimum=128, item_maximum=1024
        )
        manifests = [item for _, item in entries]
        keys = [_fence_key(item) for item in manifests]
        if keys != sorted(keys) or len(set(keys)) != len(keys):
            _fail("REGISTRY_ORDER", repr([key.hex() for key in keys]))
    else:
        _fail("UNKNOWN_DOMAIN", domain)


def _validate_bundle_manifest(manifest: CanonicalManifest) -> None:
    v = manifest.values
    domain = manifest.domain
    if v[0] != domain.encode("utf-8"):
        _fail("SCHEMA_REVISION", domain)
    basis_raw, basis = _framed(v[1], "native-close-proof-basis-v1", 4096)
    basis_hash = hashlib.sha256(basis_raw).digest()
    if _raw_hash(v[2], "proof_basis_sha256") != basis_hash:
        _fail("BASIS_HASH", "bundle field mismatch")
    evidence_raw, evidence = _framed(v[3], "native-close-proof-evidence-v1")
    evidence_hash = hashlib.sha256(evidence_raw).digest()
    if _raw_hash(v[4], "proof_evidence_sha256") != evidence_hash:
        _fail("EVIDENCE_HASH", "bundle field mismatch")
    approval_raw, approval = _framed(v[5], "native-close-proof-approval-v1")
    if _raw_hash(v[6], "proof_approval_sha256") != hashlib.sha256(approval_raw).digest():
        _fail("APPROVAL_HASH", "bundle field mismatch")
    if evidence.values[2] != basis_hash:
        _fail("EVIDENCE_BASIS_REFERENCE", "mismatch")
    if approval.values[1] != evidence_hash:
        _fail("APPROVAL_EVIDENCE_REFERENCE", "mismatch")
    producer = evidence.values[1].decode("ascii")
    reviewer = approval.values[2].decode("ascii")
    if producer.split(":", 1)[0] != reviewer.split(":", 1)[0] or producer == reviewer:
        _fail("REVIEWER_INDEPENDENCE", f"{producer}/{reviewer}")
    # Keep the parsed basis live: recursive parsing above is part of the contract.
    if encode_manifest(basis.domain, basis.values) != basis_raw:
        _fail("BASIS_NONCANONICAL", "unexpected")


def _fence_key(entry: CanonicalManifest) -> bytes:
    abi = entry.values[2]
    return entry.values[0] + entry.values[1] + _u32(len(abi)) + abi + entry.values[3]


def make_policy_file(logical_path: str, content: bytes) -> bytes:
    if logical_path not in POLICY_PATHS or not content:
        _fail("POLICY_INPUT", logical_path)
    return encode_manifest(
        "native-close-policy-file-v1",
        (logical_path.encode("utf-8"), _u64(len(content)), hashlib.sha256(content).digest()),
    )


def make_policy_set(policy_content: Mapping[str, bytes]) -> bytes:
    if tuple(sorted(policy_content, key=lambda item: item.encode("utf-8"))) != POLICY_PATHS:
        _fail("POLICY_INPUT_SET", repr(tuple(policy_content)))
    entries = tuple(make_policy_file(path, policy_content[path]) for path in POLICY_PATHS)
    result = encode_manifest(
        "native-close-policy-set-v1",
        (b"native-close-policy-set-v1", _encode_list(entries)),
    )
    parse_manifest(result, "native-close-policy-set-v1")
    return result


def make_build_variant(build_type: str, minified: bool) -> bytes:
    result = encode_manifest(
        "native-close-build-variant-v1", (build_type.encode("utf-8"), bytes((int(minified),)))
    )
    parse_manifest(result, "native-close-build-variant-v1")
    return result


def make_code_image(entries: Iterable[tuple[str, str, bytes]]) -> bytes:
    encoded: list[bytes] = []
    for package, name, content in entries:
        if not content:
            _fail("DEX_INPUT", f"{package}/{name}")
        encoded.append(
            encode_manifest(
                "native-close-dex-entry-v1",
                (
                    package.encode("utf-8"),
                    name.encode("ascii"),
                    _u64(len(content)),
                    hashlib.sha256(content).digest(),
                ),
            )
        )
    encoded.sort(key=lambda raw: _entry_key(parse_manifest(raw, "native-close-dex-entry-v1")))
    result = encode_manifest(
        "native-close-code-image-v1", (b"native-close-code-image-v1", _encode_list(encoded))
    )
    parse_manifest(result, "native-close-code-image-v1")
    return result


def make_installed_jni_set(abi: str, entries: Iterable[tuple[str, str, bytes]]) -> bytes:
    encoded: list[bytes] = []
    for package, name, content in entries:
        if not content:
            _fail("JNI_INPUT", f"{package}/{name}")
        encoded.append(
            encode_manifest(
                "native-close-jni-entry-v1",
                (
                    package.encode("utf-8"),
                    name.encode("ascii"),
                    _u64(len(content)),
                    hashlib.sha256(content).digest(),
                ),
            )
        )
    encoded.sort(key=lambda raw: _entry_key(parse_manifest(raw, "native-close-jni-entry-v1")))
    result = encode_manifest(
        "native-close-installed-jni-set-v1", (abi.encode("utf-8"), _encode_list(encoded))
    )
    parse_manifest(result, "native-close-installed-jni-set-v1")
    return result


def make_proof_basis(
    policy_set: bytes,
    dependency_artifacts_value: bytes,
    build_variant: bytes,
    code_image: bytes,
) -> bytes:
    parse_manifest(policy_set, "native-close-policy-set-v1")
    parse_manifest(build_variant, "native-close-build-variant-v1")
    parse_manifest(code_image, "native-close-code-image-v1")
    result = encode_manifest(
        "native-close-proof-basis-v1",
        (
            b"native-close-proof-basis-v1",
            hashlib.sha256(policy_set).digest(),
            hashlib.sha256(dependency_artifacts_value).digest(),
            build_variant,
            hashlib.sha256(code_image).digest(),
        ),
    )
    if len(result) > 4096:
        _fail("BASIS_SIZE", str(len(result)))
    parse_manifest(result, "native-close-proof-basis-v1")
    return result


def _le16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _le32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _le64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def _canonical_zip_name(raw: bytes) -> None:
    name = _ascii(raw, "zip_name")
    if not name or "\x00" in name or "\\" in name or name.startswith("/") or name.endswith("/"):
        _fail("ZIP_NAME", repr(name))
    components = name.split("/")
    if any(component in ("", ".", "..") for component in components):
        _fail("ZIP_PATH_COMPONENT", repr(name))


def _stream_payload(data: bytes, start: int, size: int, method: int, expected_size: int) -> tuple[int, bytes]:
    crc = 0
    digest = hashlib.sha256()
    produced = 0

    def consume(chunk: bytes) -> None:
        nonlocal crc, produced
        produced += len(chunk)
        if produced > expected_size:
            _fail("ZIP_UNCOMPRESSED_OVERFLOW", str(produced))
        crc = binascii.crc32(chunk, crc) & 0xFFFFFFFF
        digest.update(chunk)

    if method == 0:
        if size != expected_size:
            _fail("ZIP_STORED_SIZE", f"{size} != {expected_size}")
        for offset in range(start, start + size, 65_536):
            consume(data[offset : min(offset + 65_536, start + size)])
    else:
        try:
            inflater = zlib.decompressobj(-15)
            cursor = start
            end = start + size
            while cursor < end:
                chunk = data[cursor : min(cursor + 65_536, end)]
                cursor += len(chunk)
                pending = chunk
                while pending:
                    before = len(pending)
                    output = inflater.decompress(pending, 65_536)
                    consume(output)
                    pending = inflater.unconsumed_tail
                    if inflater.unused_data:
                        _fail("ZIP_DEFLATE_TRAILING", str(len(inflater.unused_data)))
                    if pending and len(pending) == before and not output:
                        _fail("ZIP_DEFLATE_STALLED", str(cursor))
            if inflater.unconsumed_tail or inflater.unused_data or not inflater.eof:
                _fail("ZIP_DEFLATE_RANGE", "stream did not end exactly")
            tail = inflater.flush()
            consume(tail)
        except zlib.error as exc:
            _fail("ZIP_DEFLATE", str(exc))
    if produced != expected_size:
        _fail("ZIP_UNCOMPRESSED_SIZE", f"{produced} != {expected_size}")
    return crc, digest.digest()


def parse_apk(apk_bytes: bytes) -> ApkView:
    """Parse the contract's exhaustive whole-file APK view without ``zipfile``."""

    file_length = len(apk_bytes)
    if file_length < 22:
        _fail("EOCD_SHORT", str(file_length))
    eocd = file_length - 22
    if _le32(apk_bytes, eocd) != 0x06054B50:
        _fail("EOCD_SIGNATURE", str(eocd))
    disk = _le16(apk_bytes, eocd + 4)
    start_disk = _le16(apk_bytes, eocd + 6)
    disk_entries = _le16(apk_bytes, eocd + 8)
    total_entries = _le16(apk_bytes, eocd + 10)
    central_size = _le32(apk_bytes, eocd + 12)
    central_offset = _le32(apk_bytes, eocd + 16)
    comment_length = _le16(apk_bytes, eocd + 20)
    if disk != 0 or start_disk != 0:
        _fail("EOCD_DISK", f"{disk}/{start_disk}")
    if disk_entries != total_entries or not 1 <= total_entries <= 65_534:
        _fail("EOCD_COUNT", f"{disk_entries}/{total_entries}")
    if comment_length != 0:
        _fail("EOCD_COMMENT", str(comment_length))
    if central_size == 0xFFFFFFFF or central_offset == 0xFFFFFFFF:
        _fail("EOCD_SENTINEL", "central size/offset")
    if central_offset + central_size != eocd:
        _fail("CENTRAL_RANGE", f"{central_offset}+{central_size}!={eocd}")
    if central_offset > eocd:
        _fail("CENTRAL_OFFSET", str(central_offset))

    central_cursor = central_offset
    provisional: list[dict[str, int | bytes]] = []
    names: set[bytes] = set()
    for index in range(total_entries):
        if central_cursor + 46 > eocd or _le32(apk_bytes, central_cursor) != 0x02014B50:
            _fail("CENTRAL_HEADER", f"entry {index} at {central_cursor}")
        version_made_by = _le16(apk_bytes, central_cursor + 4)
        creator_os = version_made_by >> 8
        flags = _le16(apk_bytes, central_cursor + 8)
        method = _le16(apk_bytes, central_cursor + 10)
        crc = _le32(apk_bytes, central_cursor + 16)
        compressed = _le32(apk_bytes, central_cursor + 20)
        uncompressed = _le32(apk_bytes, central_cursor + 24)
        name_length = _le16(apk_bytes, central_cursor + 28)
        extra_length = _le16(apk_bytes, central_cursor + 30)
        archive_comment = _le16(apk_bytes, central_cursor + 32)
        disk_start = _le16(apk_bytes, central_cursor + 34)
        external_attributes = _le32(apk_bytes, central_cursor + 38)
        local_offset = _le32(apk_bytes, central_cursor + 42)
        record_end = central_cursor + 46 + name_length + extra_length + archive_comment
        if record_end > eocd:
            _fail("CENTRAL_BOUNDS", f"entry {index}")
        raw_name = apk_bytes[central_cursor + 46 : central_cursor + 46 + name_length]
        if disk_start != 0 or compressed == 0xFFFFFFFF or uncompressed == 0xFFFFFFFF or local_offset == 0xFFFFFFFF:
            _fail("CENTRAL_ZIP64_OR_DISK", f"entry {index}")
        if extra_length != 0 or archive_comment != 0:
            _fail("CENTRAL_EXTRA_COMMENT", f"entry {index}")
        unix_file_type = (external_attributes >> 16) & 0o170000
        if external_attributes & 0x10 or (creator_os == 3 and unix_file_type == 0o040000):
            _fail("ZIP_DIRECTORY_ATTRIBUTE", f"entry {index}")
        # Upper external-attribute bits are creator-OS-specific.  Applying Unix mode
        # semantics to a non-Unix creator would invent a second, non-contractual view;
        # the host-independent DOS directory bit above remains enforced for all hosts.
        if flags not in (0, 0x0800) or method not in (0, 8):
            _fail("CENTRAL_FLAGS_METHOD", f"entry {index}: {flags:#x}/{method}")
        _canonical_zip_name(raw_name)
        if raw_name in names:
            _fail("ZIP_DUPLICATE_NAME", raw_name.decode("ascii"))
        names.add(raw_name)
        provisional.append(
            {
                "raw_name": raw_name,
                "flags": flags,
                "method": method,
                "crc": crc,
                "compressed": compressed,
                "uncompressed": uncompressed,
                "local_offset": local_offset,
            }
        )
        central_cursor = record_end
    if central_cursor != eocd:
        _fail("CENTRAL_CONSUMPTION", f"{central_cursor} != {eocd}")

    ranges: list[tuple[int, int, ApkEntry]] = []
    for index, item in enumerate(provisional):
        local = int(item["local_offset"])
        if local + 30 > central_offset or _le32(apk_bytes, local) != 0x04034B50:
            _fail("LOCAL_HEADER", f"entry {index} at {local}")
        flags = _le16(apk_bytes, local + 6)
        method = _le16(apk_bytes, local + 8)
        crc = _le32(apk_bytes, local + 14)
        compressed = _le32(apk_bytes, local + 18)
        uncompressed = _le32(apk_bytes, local + 22)
        name_length = _le16(apk_bytes, local + 26)
        extra_length = _le16(apk_bytes, local + 28)
        header_end = local + 30 + name_length + extra_length
        payload_end = header_end + compressed
        if header_end > central_offset or payload_end > central_offset or payload_end <= local:
            _fail("LOCAL_RANGE", f"entry {index}")
        local_name = apk_bytes[local + 30 : local + 30 + name_length]
        extra = apk_bytes[local + 30 + name_length : header_end]
        if (
            local_name != item["raw_name"]
            or flags != item["flags"]
            or method != item["method"]
            or crc != item["crc"]
            or compressed != item["compressed"]
            or uncompressed != item["uncompressed"]
        ):
            _fail("LOCAL_CENTRAL_MISMATCH", f"entry {index}")
        if extra and any(extra):
            _fail("LOCAL_EXTRA", f"entry {index}")
        calculated_crc, content_hash = _stream_payload(apk_bytes, header_end, compressed, method, uncompressed)
        if calculated_crc != crc:
            _fail("ZIP_CRC", f"entry {index}: {calculated_crc:#x} != {crc:#x}")
        entry = ApkEntry(
            raw_name=bytes(item["raw_name"]),
            flags=flags,
            method=method,
            crc32=crc,
            compressed_size=compressed,
            uncompressed_size=uncompressed,
            local_offset=local,
            payload_offset=header_end,
            payload_end=payload_end,
            content_sha256=content_hash,
        )
        ranges.append((local, payload_end, entry))
    ranges.sort(key=lambda item: item[0])
    expected_start = 0
    seen_offsets: set[int] = set()
    for start, end, _ in ranges:
        if start in seen_offsets or start != expected_start:
            _fail("LOCAL_ADJACENCY", f"{start} != {expected_start}")
        seen_offsets.add(start)
        expected_start = end

    signing = expected_start != central_offset
    if signing:
        _validate_signing_block(apk_bytes, expected_start, central_offset)
    return ApkView(
        entries=tuple(item[2] for item in ranges),
        signing_block_present=signing,
        central_offset=central_offset,
        central_size=central_size,
    )


def _validate_signing_block(data: bytes, start: int, end: int) -> None:
    length = end - start
    if length < 44:
        _fail("SIGNING_LENGTH", str(length))
    first_size = _le64(data, start)
    footer_size = _le64(data, end - 24)
    if data[end - 16 : end] != b"APK Sig Block 42":
        _fail("SIGNING_MAGIC", data[end - 16 : end].hex())
    if first_size != footer_size or first_size < 36 or first_size + 8 != length:
        _fail("SIGNING_SIZE", f"{first_size}/{footer_size}/{length}")
    cursor = start + 8
    pairs_end = end - 24
    ids: set[int] = set()
    pair_count = 0
    while cursor < pairs_end:
        if cursor + 8 > pairs_end:
            _fail("SIGNING_PAIR_HEADER", str(cursor))
        pair_length = _le64(data, cursor)
        cursor += 8
        if pair_length < 4 or pair_length > pairs_end - cursor:
            _fail("SIGNING_PAIR_LENGTH", str(pair_length))
        pair_id = _le32(data, cursor)
        if pair_id == 0 or pair_id in ids:
            _fail("SIGNING_PAIR_ID", str(pair_id))
        ids.add(pair_id)
        cursor += pair_length
        pair_count += 1
    if cursor != pairs_end or pair_count == 0:
        _fail("SIGNING_PAIR_CONSUMPTION", f"{cursor}/{pairs_end}/{pair_count}")


def verify_bundle(bundle: bytes) -> CanonicalManifest:
    return parse_manifest(bundle, "native-close-proof-bundle-v1")


def _validate_bundle_mapping(
    bundles: Mapping[bytes, bytes],
) -> dict[bytes, tuple[bytes, CanonicalManifest]]:
    """Validate a raw public-API inventory before registry reachability is examined."""

    try:
        items = tuple(bundles.items())
    except (AttributeError, RuntimeError, TypeError) as exc:
        _fail("BUNDLE_INVENTORY_TYPE", str(exc))
    validated: dict[bytes, tuple[bytes, CanonicalManifest]] = {}
    decoded_rows: set[tuple[bytes, bytes, bytes, bytes, bytes]] = set()
    total = 0
    for key, content in items:
        if not isinstance(key, bytes) or len(key) != 32 or not any(key):
            _fail("BUNDLE_INVENTORY_KEY", repr(key))
        if not isinstance(content, bytes):
            _fail("BUNDLE_INVENTORY_VALUE", key.hex())
        total += len(content)
        if total > 16_777_216:
            _fail("BUNDLE_INVENTORY_SIZE", str(total))
        if hashlib.sha256(content).digest() != key:
            _fail("BUNDLE_CONTENT_ADDRESS", key.hex())
        bundle = verify_bundle(content)
        _, evidence = _framed(bundle.values[3], "native-close-proof-evidence-v1")
        decoded_row = (
            evidence.values[2],
            evidence.values[3],
            evidence.values[4],
            evidence.values[5],
            evidence.values[6],
        )
        if decoded_row in decoded_rows:
            _fail("BUNDLE_DUPLICATE_DECODED_ROW", key.hex())
        decoded_rows.add(decoded_row)
        validated[key] = (content, bundle)
    return validated


def verify_registry(
    registry: bytes,
    bundles_by_sha256: Mapping[bytes, bytes],
) -> FenceVerification:
    inventory = _validate_bundle_mapping(bundles_by_sha256)
    registry_manifest = parse_manifest(registry, "native-close-fence-registry-v1")
    rows = _parse_list(
        registry_manifest.values[1],
        "native-close-fence-entry-v1",
        0,
        512,
        item_minimum=128,
        item_maximum=1024,
    )
    if not rows:
        return FenceVerification(False, ProofUnavailableReason.EMPTY_REGISTRY, 0)

    parsed: list[tuple[CanonicalManifest, CanonicalManifest, CanonicalManifest, CanonicalManifest]] = []
    used_bundles: set[bytes] = set()
    for _, row in rows:
        bundle_hash = row.values[5]
        inventory_item = inventory.get(bundle_hash)
        if inventory_item is None:
            return FenceVerification(False, ProofUnavailableReason.MISSING_BUNDLE, len(rows))
        bundle_bytes, bundle = inventory_item
        if bundle_hash in used_bundles:
            _fail("CROSS_ROW_BUNDLE_REUSE", bundle_hash.hex())
        used_bundles.add(bundle_hash)
        _, basis = _framed(bundle.values[1], "native-close-proof-basis-v1", 4096)
        _, evidence = _framed(bundle.values[3], "native-close-proof-evidence-v1")
        _, approval = _framed(bundle.values[5], "native-close-proof-approval-v1")
        if row.values[:5] != (evidence.values[2], evidence.values[3], evidence.values[4], evidence.values[5], evidence.values[6]):
            _fail("ROW_EVIDENCE_MISMATCH", bundle_hash.hex())
        parsed.append((row, basis, evidence, approval))

    variants: dict[bytes, bytes] = {}
    for row, basis, _, _ in parsed:
        variant = basis.values[3]
        basis_hash = row.values[0]
        prior = variants.setdefault(variant, basis_hash)
        if prior != basis_hash:
            _fail("DUPLICATE_BUILD_VARIANT", hashlib.sha256(variant).hexdigest())

    # A positive decision requires a trusted, independently derived verifier for Git
    # commit/tree policy bytes, artifact membership, and the final installed JNI set.
    # No caller-asserted replacement object is accepted as proof.
    return FenceVerification(
        False,
        ProofUnavailableReason.EXTERNAL_VERIFIER_UNIMPLEMENTED,
        len(rows),
    )


def _load_bundles(directory: Path) -> dict[bytes, bytes]:
    if not directory.is_dir() or directory.is_symlink():
        _fail("BUNDLE_ROOT", str(directory))
    result: dict[bytes, bytes] = {}
    total = 0
    for path in sorted(directory.iterdir(), key=lambda item: item.name.encode("utf-8")):
        if path.is_symlink() or not path.is_file() or not _LOWER64.fullmatch(path.stem) or path.suffix != ".bin":
            _fail("BUNDLE_DISCOVERY", path.name)
        content = path.read_bytes()
        total += len(content)
        digest = hashlib.sha256(content).digest()
        if path.name != digest.hex() + ".bin":
            _fail("BUNDLE_FILENAME", path.name)
        if digest in result:
            _fail("BUNDLE_DUPLICATE", digest.hex())
        # Discovery is an inventory boundary: even an unreferenced bundle must parse,
        # re-encode canonically, and satisfy all internal cross-hashes.
        verify_bundle(content)
        result[digest] = content
    if total > 16_777_216:
        _fail("BUNDLE_ROOT_SIZE", str(total))
    return result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--bundles", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = verify_registry(args.registry.read_bytes(), _load_bundles(args.bundles))
    except (OSError, NativeCloseFenceError) as exc:
        print(f"NATIVE_CLOSE_FENCE=INVALID {exc}", file=sys.stderr)
        return 2
    # CLI verification is intentionally unable to synthesize semantic proof.
    if not result.authorized:
        print(f"NATIVE_CLOSE_PROOF=UNAVAILABLE reason={result.reason.value} rows={result.entry_count}")
        return 1
    _fail("UNREACHABLE", "CLI has no external semantic proof inputs")


if __name__ == "__main__":
    raise SystemExit(main())
