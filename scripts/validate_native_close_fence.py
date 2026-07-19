#!/usr/bin/env python3
"""Independent fail-closed validator for Native Close fence artifacts.

No symbol is imported from ``native_close_fence_generate``.  This file intentionally
reimplements the wire grammar and whole-file APK view so generation and validation do
not share a parser or acceptance decision.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import struct
import sys
import zlib
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Mapping, Sequence


class FenceValidationError(ValueError):
    def __init__(self, rule: str, message: str) -> None:
        super().__init__(f"{rule}: {message}")
        self.rule = rule
        self.message = message


def reject(rule: str, message: str) -> None:
    raise FenceValidationError(rule, message)


MAX_ALIGNMENT_PADDING_BYTES = 8 * 1024 * 1024
MAX_ALIGNMENT_PADDING_RECORDS = 256
ALIGNMENT_PADDING_TIME = 0x0821
ALIGNMENT_PADDING_DATE = 0x0221


@dataclass(frozen=True)
class Record:
    kind: str
    fields: tuple[tuple[str, bytes], ...]

    def value(self, name: str) -> bytes:
        for actual, value in self.fields:
            if actual == name:
                return value
        reject("MISSING_FIELD", f"{self.kind}.{name}")


@dataclass(frozen=True)
class PackageMember:
    name_bytes: bytes
    gp_flags: int
    compression: int
    crc: int
    packed_length: int
    plain_length: int
    header_start: int
    data_start: int
    data_end: int
    plain_sha256: bytes

    @property
    def name(self) -> str:
        return self.name_bytes.decode("ascii")


@dataclass(frozen=True)
class PackageView:
    members: tuple[PackageMember, ...]
    has_signing_block: bool
    directory_start: int
    directory_length: int


class FenceState(str, Enum):
    EMPTY = "EMPTY_REGISTRY"
    MISSING_BUNDLE = "MISSING_BUNDLE"
    EXTERNAL_VERIFIER_UNIMPLEMENTED = "EXTERNAL_VERIFIER_UNIMPLEMENTED"


@dataclass(frozen=True)
class FenceDecision:
    state: FenceState
    rows: int

    @property
    def authorized(self) -> bool:
        return False


LAYOUTS: dict[str, tuple[str, ...]] = {
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
    "native-close-evidence-entry-v1": ("category", "artifact_length", "artifact_sha256"),
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

EXPECTED_POLICIES = (
    "docs/adr/ADR-011-measured-capability-probe-policy.md",
    "docs/contracts/capability-store-v4.md",
    "docs/contracts/native-close-fence-proof-v1.md",
    "docs/contracts/recovery-journal-v5.md",
    "docs/execution/slice-1b-contract.md",
)
KNOWN_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
DEX_PATTERN = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?[.]dex\Z")
JNI_PATTERN = re.compile(r"lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/[A-Za-z0-9._+\-]+[.]so\Z")
AGENT_PATTERN = re.compile(r"agent:/[a-z0-9_]+(?:/[a-z0-9_]+)*\Z")
GITHUB_PATTERN = re.compile(r"github:[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?\Z")
HEX40_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
HEX64_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


class Reader:
    def __init__(self, payload: bytes, description: str) -> None:
        self.payload = payload
        self.index = 0
        self.description = description

    def bytes(self, length: int) -> bytes:
        remaining = len(self.payload) - self.index
        if length < 0 or length > remaining:
            reject("BOUNDS", f"{self.description}: need {length}, have {remaining}")
        start = self.index
        self.index += length
        return self.payload[start : self.index]

    def be32(self) -> int:
        return int.from_bytes(self.bytes(4), "big", signed=False)

    def be64(self) -> int:
        return int.from_bytes(self.bytes(8), "big", signed=False)

    def finish(self) -> None:
        if self.index != len(self.payload):
            reject("EXTRA_DATA", f"{self.description}: {len(self.payload) - self.index} bytes")


def canonical_bytes(record: Record) -> bytes:
    field_names = LAYOUTS.get(record.kind)
    if field_names is None:
        reject("DOMAIN", record.kind)
    actual_names = tuple(name for name, _ in record.fields)
    if actual_names != field_names:
        reject("FIELD_SEQUENCE", f"{record.kind}: {actual_names!r}")
    output = bytearray(record.kind.encode("utf-8"))
    output.append(0)
    output += len(record.fields).to_bytes(4, "big")
    for field_name, value in record.fields:
        encoded_name = field_name.encode("utf-8")
        output += len(encoded_name).to_bytes(4, "big")
        output += encoded_name
        output += len(value).to_bytes(8, "big")
        output += value
    return bytes(output)


def validate_manifest(payload: bytes, kind: str) -> Record:
    field_names = LAYOUTS.get(kind)
    if field_names is None:
        reject("DOMAIN", kind)
    marker = kind.encode("utf-8") + b"\0"
    if len(payload) < len(marker) + 4 or payload[: len(marker)] != marker:
        reject("DOMAIN_MARKER", kind)
    source = Reader(payload[len(marker) :], kind)
    declared_fields = source.be32()
    if declared_fields != len(field_names):
        reject("FIELD_COUNT", f"{kind}: {declared_fields}")
    fields: list[tuple[str, bytes]] = []
    for expected in field_names:
        name_size = source.be32()
        if name_size > 256:
            reject("FIELD_NAME_SIZE", f"{kind}: {name_size}")
        raw_name = source.bytes(name_size)
        if raw_name != expected.encode("utf-8"):
            reject("FIELD_SEQUENCE", f"{kind}: expected {expected}")
        value_size = source.be64()
        if value_size > len(source.payload) - source.index:
            reject("FIELD_SIZE", f"{kind}.{expected}: {value_size}")
        fields.append((expected, source.bytes(value_size)))
    source.finish()
    record = Record(kind, tuple(fields))
    _check_record(record)
    if canonical_bytes(record) != payload:
        reject("REENCODE", kind)
    return record


def _text(data: bytes, field: str) -> str:
    try:
        decoded = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        reject("UTF8", f"{field}: {exc}")
    if decoded.encode("utf-8") != data:
        reject("UTF8_ROUNDTRIP", field)
    return decoded


def _ascii_text(data: bytes, field: str) -> str:
    try:
        return data.decode("ascii", errors="strict")
    except UnicodeDecodeError as exc:
        reject("ASCII", f"{field}: {exc}")


def _fixed_integer(data: bytes, width: int, field: str) -> int:
    if len(data) != width:
        reject("INTEGER_WIDTH", f"{field}: {len(data)}")
    return int.from_bytes(data, "big", signed=False)


def _sha(data: bytes, field: str, zero_forbidden: bool = False) -> bytes:
    if len(data) != 32:
        reject("SHA256_WIDTH", f"{field}: {len(data)}")
    if zero_forbidden and data == bytes(32):
        reject("ZERO_SHA256", field)
    return data


def _parse_children(
    data: bytes,
    child_kind: str,
    low: int,
    high: int,
    *,
    low_size: int = 1,
    high_size: int | None = None,
) -> tuple[tuple[bytes, Record], ...]:
    source = Reader(data, f"children:{child_kind}")
    count = source.be32()
    if count < low or count > high:
        reject("CHILD_COUNT", f"{child_kind}: {count}")
    result: list[tuple[bytes, Record]] = []
    for position in range(count):
        size = source.be32()
        if size < low_size or (high_size is not None and size > high_size):
            reject("CHILD_SIZE", f"{child_kind}[{position}]: {size}")
        raw = source.bytes(size)
        result.append((raw, validate_manifest(raw, child_kind)))
    source.finish()
    return tuple(result)


def _embedded(data: bytes, child_kind: str, maximum: int | None = None) -> tuple[bytes, Record]:
    source = Reader(data, f"embedded:{child_kind}")
    size = source.be32()
    if size == 0 or (maximum is not None and size > maximum):
        reject("EMBEDDED_SIZE", f"{child_kind}: {size}")
    raw = source.bytes(size)
    source.finish()
    return raw, validate_manifest(raw, child_kind)


def _identity(data: bytes, field: str) -> str:
    value = _ascii_text(data, field)
    if len(data) < 7 or len(data) > 128:
        reject("IDENTITY_LENGTH", value)
    if AGENT_PATTERN.fullmatch(value) is None and GITHUB_PATTERN.fullmatch(value) is None:
        reject("IDENTITY_GRAMMAR", value)
    return value


def _package_name(data: bytes) -> str:
    value = _text(data, "package_output_name")
    if value in ("", ".", "..") or any(char in value for char in ("/", "\\", "\0")):
        reject("PACKAGE_OUTPUT", repr(value))
    return value


def _dex_index(name: str) -> int:
    return 1 if name == "classes.dex" else int(name[7:-4])


def _ordered_unique(keys: list[tuple[bytes, bytes]], rule: str) -> None:
    if keys != sorted(keys) or len(keys) != len(set(keys)):
        reject(rule, repr(keys))


def _check_record(record: Record) -> None:
    kind = record.kind
    get = record.value
    if kind == "native-close-policy-file-v1":
        logical = _text(get("logical_path"), "logical_path")
        if logical not in EXPECTED_POLICIES:
            reject("POLICY_PATH", logical)
        if _fixed_integer(get("file_length"), 8, "file_length") == 0:
            reject("POLICY_LENGTH", logical)
        _sha(get("file_sha256"), "file_sha256")
        return
    if kind == "native-close-policy-set-v1":
        _schema(record)
        children = _parse_children(get("policy_files"), "native-close-policy-file-v1", 5, 5)
        actual = tuple(child.value("logical_path").decode("utf-8") for _, child in children)
        if actual != EXPECTED_POLICIES:
            reject("POLICY_SEQUENCE", repr(actual))
        return
    if kind == "native-close-dex-entry-v1":
        _package_name(get("package_output_name"))
        dex_name = _ascii_text(get("dex_entry_name"), "dex_entry_name")
        if DEX_PATTERN.fullmatch(dex_name) is None:
            reject("DEX_NAME", dex_name)
        size = _fixed_integer(get("uncompressed_length"), 8, "uncompressed_length")
        if size < 1 or size > 268_435_456:
            reject("DEX_SIZE", str(size))
        _sha(get("content_sha256"), "content_sha256")
        return
    if kind == "native-close-code-image-v1":
        _schema(record)
        children = _parse_children(get("dex_entries"), "native-close-dex-entry-v1", 1, 64)
        decoded = [child for _, child in children]
        keys = [(child.value("package_output_name"), child.value("dex_entry_name")) for child in decoded]
        _ordered_unique(keys, "DEX_SEQUENCE")
        total = sum(_fixed_integer(child.value("uncompressed_length"), 8, "uncompressed_length") for child in decoded)
        if total > 536_870_912:
            reject("DEX_TOTAL", str(total))
        packages: dict[bytes, set[int]] = {}
        for child in decoded:
            packages.setdefault(child.value("package_output_name"), set()).add(
                _dex_index(child.value("dex_entry_name").decode("ascii"))
            )
        for package, indexes in packages.items():
            if indexes != set(range(1, max(indexes) + 1)):
                reject("DEX_GAP", package.decode("utf-8"))
        return
    if kind == "native-close-build-variant-v1":
        _text(get("build_type"), "build_type")
        if get("minified") not in (b"\0", b"\1"):
            reject("MINIFIED", get("minified").hex())
        return
    if kind == "native-close-proof-basis-v1":
        _schema(record)
        _sha(get("native_close_policy_set_sha256"), "native_close_policy_set_sha256")
        _sha(get("dependency_artifacts_value_sha256"), "dependency_artifacts_value_sha256")
        validate_manifest(get("build_variant"), "native-close-build-variant-v1")
        _sha(get("native_close_code_image_sha256"), "native_close_code_image_sha256")
        return
    if kind == "native-close-jni-entry-v1":
        _package_name(get("package_output_name"))
        jni_name = _ascii_text(get("jni_entry_name"), "jni_entry_name")
        if JNI_PATTERN.fullmatch(jni_name) is None:
            reject("JNI_NAME", jni_name)
        size = _fixed_integer(get("uncompressed_length"), 8, "uncompressed_length")
        if size < 1 or size > 268_435_456:
            reject("JNI_SIZE", str(size))
        _sha(get("content_sha256"), "content_sha256")
        return
    if kind == "native-close-installed-jni-set-v1":
        abi = _text(get("abi"), "abi")
        if abi not in KNOWN_ABIS:
            reject("ABI", abi)
        children = _parse_children(get("entries"), "native-close-jni-entry-v1", 1, 256)
        decoded = [child for _, child in children]
        keys = [(child.value("package_output_name"), child.value("jni_entry_name")) for child in decoded]
        _ordered_unique(keys, "JNI_SEQUENCE")
        total = sum(_fixed_integer(child.value("uncompressed_length"), 8, "uncompressed_length") for child in decoded)
        if total > 536_870_912:
            reject("JNI_TOTAL", str(total))
        logical_names = [child.value("jni_entry_name") for child in decoded]
        if len(logical_names) != len(set(logical_names)):
            reject("JNI_LOGICAL_PATH_COLLISION", abi)
        if any(child.value("jni_entry_name").decode("ascii").split("/")[1] != abi for child in decoded):
            reject("JNI_ABI", abi)
        return
    if kind == "native-close-evidence-entry-v1":
        category = get("category")
        if len(category) != 1 or category[0] > 7:
            reject("CATEGORY", category.hex())
        size = _fixed_integer(get("artifact_length"), 8, "artifact_length")
        if size < 1 or size > 4_194_304:
            reject("ARTIFACT_SIZE", str(size))
        _sha(get("artifact_sha256"), "artifact_sha256")
        return
    if kind == "native-close-proof-evidence-v1":
        _schema(record)
        _identity(get("producer_id"), "producer_id")
        _sha(get("proof_basis_sha256"), "proof_basis_sha256")
        api = _fixed_integer(get("os_api"), 4, "os_api")
        if api < 26 or api > 37:
            reject("OS_API", str(api))
        abi = _text(get("abi"), "abi")
        if abi not in KNOWN_ABIS:
            reject("ABI", abi)
        _sha(get("installed_jni_set_sha256"), "installed_jni_set_sha256")
        if get("delegate_mask") != b"\x03" or get("claim") != b"\0":
            reject("EVIDENCE_ENUM", f"{get('delegate_mask').hex()}/{get('claim').hex()}")
        children = _parse_children(get("evidence_entries"), "native-close-evidence-entry-v1", 8, 64)
        decoded = [child for _, child in children]
        keys = [(child.value("category")[0], child.value("artifact_sha256")) for child in decoded]
        if keys != sorted(keys) or len(keys) != len(set(keys)):
            reject("EVIDENCE_SEQUENCE", repr(keys))
        if {key[0] for key in keys} != set(range(8)):
            reject("EVIDENCE_COVERAGE", repr(keys))
        total = sum(_fixed_integer(child.value("artifact_length"), 8, "artifact_length") for child in decoded)
        if total > 67_108_864:
            reject("EVIDENCE_TOTAL", str(total))
        return
    if kind == "native-close-proof-approval-v1":
        _schema(record)
        _sha(get("proof_evidence_sha256"), "proof_evidence_sha256")
        _identity(get("reviewer_id"), "reviewer_id")
        commit = _ascii_text(get("reviewed_commit"), "reviewed_commit")
        tree = _ascii_text(get("reviewed_tree"), "reviewed_tree")
        if HEX40_PATTERN.fullmatch(commit) is None or HEX40_PATTERN.fullmatch(tree) is None:
            reject("GIT_ID", f"{commit}/{tree}")
        if get("verdict") != b"\0":
            reject("APPROVAL_VERDICT", get("verdict").hex())
        for count_name in ("p0_count", "p1_count", "p2_count"):
            if _fixed_integer(get(count_name), 4, count_name) != 0:
                reject("APPROVAL_FINDINGS", count_name)
        return
    if kind == "native-close-proof-bundle-v1":
        _check_bundle(record)
        return
    if kind == "native-close-fence-entry-v1":
        _sha(get("proof_basis_sha256"), "proof_basis_sha256", True)
        api = _fixed_integer(get("os_api"), 4, "os_api")
        if api < 26 or api > 37:
            reject("OS_API", str(api))
        abi = _text(get("abi"), "abi")
        if abi not in KNOWN_ABIS:
            reject("ABI", abi)
        _sha(get("installed_jni_set_sha256"), "installed_jni_set_sha256", True)
        if get("delegate_mask") != b"\x03":
            reject("ROW_DELEGATE", get("delegate_mask").hex())
        _sha(get("proof_bundle_sha256"), "proof_bundle_sha256", True)
        return
    if kind == "native-close-fence-registry-v1":
        if len(canonical_bytes(record)) > 131_072:
            reject("REGISTRY_LENGTH", str(len(canonical_bytes(record))))
        _schema(record)
        children = _parse_children(
            get("entries"), "native-close-fence-entry-v1", 0, 512, low_size=128, high_size=1024
        )
        decoded = [child for _, child in children]
        keys = [_row_key(child) for child in decoded]
        if keys != sorted(keys) or len(keys) != len(set(keys)):
            reject("ROW_SEQUENCE", repr([key.hex() for key in keys]))
        return
    reject("DOMAIN", kind)


def _schema(record: Record) -> None:
    if record.value("schema_revision") != record.kind.encode("utf-8"):
        reject("SCHEMA_REVISION", record.kind)


def _check_bundle(bundle: Record) -> None:
    _schema(bundle)
    basis_bytes, basis = _embedded(bundle.value("proof_basis"), "native-close-proof-basis-v1", 4096)
    basis_digest = hashlib.sha256(basis_bytes).digest()
    if _sha(bundle.value("proof_basis_sha256"), "proof_basis_sha256") != basis_digest:
        reject("BASIS_DIGEST", "bundle")
    evidence_bytes, evidence = _embedded(bundle.value("proof_evidence"), "native-close-proof-evidence-v1")
    evidence_digest = hashlib.sha256(evidence_bytes).digest()
    if _sha(bundle.value("proof_evidence_sha256"), "proof_evidence_sha256") != evidence_digest:
        reject("EVIDENCE_DIGEST", "bundle")
    approval_bytes, approval = _embedded(bundle.value("proof_approval"), "native-close-proof-approval-v1")
    if _sha(bundle.value("proof_approval_sha256"), "proof_approval_sha256") != hashlib.sha256(approval_bytes).digest():
        reject("APPROVAL_DIGEST", "bundle")
    if evidence.value("proof_basis_sha256") != basis_digest:
        reject("EVIDENCE_TO_BASIS", "mismatch")
    if approval.value("proof_evidence_sha256") != evidence_digest:
        reject("APPROVAL_TO_EVIDENCE", "mismatch")
    producer = evidence.value("producer_id").decode("ascii")
    reviewer = approval.value("reviewer_id").decode("ascii")
    if producer == reviewer or producer.partition(":")[0] != reviewer.partition(":")[0]:
        reject("INDEPENDENT_REVIEW", f"{producer}/{reviewer}")
    # Access ensures the recursively validated object is not an unused opaque digest.
    basis.value("build_variant")


def _row_key(row: Record) -> bytes:
    abi = row.value("abi")
    return (
        row.value("proof_basis_sha256")
        + row.value("os_api")
        + len(abi).to_bytes(4, "big")
        + abi
        + row.value("installed_jni_set_sha256")
    )


def _u16le(data: bytes, position: int) -> int:
    if position < 0 or position + 2 > len(data):
        reject("ZIP_BOUNDS", f"u16 at {position}")
    return int.from_bytes(data[position : position + 2], "little")


def _u32le(data: bytes, position: int) -> int:
    if position < 0 or position + 4 > len(data):
        reject("ZIP_BOUNDS", f"u32 at {position}")
    return int.from_bytes(data[position : position + 4], "little")


def _u64le(data: bytes, position: int) -> int:
    if position < 0 or position + 8 > len(data):
        reject("ZIP_BOUNDS", f"u64 at {position}")
    return int.from_bytes(data[position : position + 8], "little")


def _zip_name(name_bytes: bytes) -> None:
    name = _ascii_text(name_bytes, "archive_name")
    components = name.split("/")
    if (
        not name
        or "\0" in name
        or "\\" in name
        or name.startswith("/")
        or name.endswith("/")
        or any(component in ("", ".", "..") for component in components)
    ):
        reject("ARCHIVE_NAME", repr(name))


def _plain_fingerprint(
    archive: bytes, data_start: int, packed_size: int, method: int, declared_plain_size: int
) -> tuple[int, bytes]:
    checksum = 0
    hasher = hashlib.sha256()
    plain_count = 0

    def accept(output: bytes) -> None:
        nonlocal checksum, plain_count
        plain_count += len(output)
        if plain_count > declared_plain_size:
            reject("PLAIN_OVERFLOW", str(plain_count))
        checksum = zlib.crc32(output, checksum) & 0xFFFFFFFF
        hasher.update(output)

    packed_end = data_start + packed_size
    if method == 0:
        if packed_size != declared_plain_size:
            reject("STORED_LENGTH", f"{packed_size}/{declared_plain_size}")
        cursor = data_start
        while cursor < packed_end:
            next_cursor = min(cursor + 32_768, packed_end)
            accept(archive[cursor:next_cursor])
            cursor = next_cursor
    elif method == 8:
        try:
            decoder = zlib.decompressobj(wbits=-15)
            cursor = data_start
            while cursor < packed_end:
                next_cursor = min(cursor + 32_768, packed_end)
                pending = archive[cursor:next_cursor]
                cursor = next_cursor
                while pending:
                    previous = len(pending)
                    output = decoder.decompress(pending, 32_768)
                    accept(output)
                    pending = decoder.unconsumed_tail
                    if decoder.unused_data:
                        reject("DEFLATE_UNUSED", str(len(decoder.unused_data)))
                    if pending and len(pending) == previous and not output:
                        reject("DEFLATE_PROGRESS", str(cursor))
            if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
                reject("DEFLATE_EOF", "not exact")
            accept(decoder.flush())
        except zlib.error as exc:
            reject("DEFLATE_ERROR", str(exc))
    else:
        reject("COMPRESSION", str(method))
    if plain_count != declared_plain_size:
        reject("PLAIN_LENGTH", f"{plain_count}/{declared_plain_size}")
    return checksum, hasher.digest()


def validate_apk(archive: bytes) -> PackageView:
    """Validate the sole positional ZIP view selected by exact EOF."""

    archive_length = len(archive)
    if archive_length < 22:
        reject("EOCD_LENGTH", str(archive_length))
    eocd_start = archive_length - 22
    if _u32le(archive, eocd_start) != 0x06054B50:
        reject("EOCD_MAGIC", str(eocd_start))
    disk_number = _u16le(archive, eocd_start + 4)
    directory_disk = _u16le(archive, eocd_start + 6)
    count_on_disk = _u16le(archive, eocd_start + 8)
    count_total = _u16le(archive, eocd_start + 10)
    directory_length = _u32le(archive, eocd_start + 12)
    directory_start = _u32le(archive, eocd_start + 16)
    comment_length = _u16le(archive, eocd_start + 20)
    if disk_number != 0 or directory_disk != 0:
        reject("MULTI_DISK", f"{disk_number}/{directory_disk}")
    if count_on_disk != count_total or count_total < 1 or count_total > 65_534:
        reject("ENTRY_COUNT", f"{count_on_disk}/{count_total}")
    if directory_length == 0xFFFFFFFF or directory_start == 0xFFFFFFFF:
        reject("ZIP64_SENTINEL", "directory")
    if comment_length != 0:
        reject("ARCHIVE_COMMENT", str(comment_length))
    if directory_start + directory_length != eocd_start:
        reject("DIRECTORY_EXTENT", f"{directory_start}+{directory_length}!={eocd_start}")

    directory_cursor = directory_start
    directory_entries: list[tuple[bytes, int, int, int, int, int, int]] = []
    encountered_names: set[bytes] = set()
    for ordinal in range(count_total):
        if directory_cursor + 46 > eocd_start or _u32le(archive, directory_cursor) != 0x02014B50:
            reject("DIRECTORY_RECORD", f"{ordinal}@{directory_cursor}")
        made_by_version = _u16le(archive, directory_cursor + 4)
        creator_system = made_by_version >> 8
        flags = _u16le(archive, directory_cursor + 8)
        method = _u16le(archive, directory_cursor + 10)
        crc = _u32le(archive, directory_cursor + 16)
        packed = _u32le(archive, directory_cursor + 20)
        plain = _u32le(archive, directory_cursor + 24)
        name_size = _u16le(archive, directory_cursor + 28)
        extra_size = _u16le(archive, directory_cursor + 30)
        comment_size = _u16le(archive, directory_cursor + 32)
        start_disk = _u16le(archive, directory_cursor + 34)
        external_attributes = _u32le(archive, directory_cursor + 38)
        header_start = _u32le(archive, directory_cursor + 42)
        next_record = directory_cursor + 46 + name_size + extra_size + comment_size
        if next_record > eocd_start:
            reject("DIRECTORY_RECORD_EXTENT", str(ordinal))
        name_bytes = archive[directory_cursor + 46 : directory_cursor + 46 + name_size]
        if start_disk != 0 or packed == 0xFFFFFFFF or plain == 0xFFFFFFFF or header_start == 0xFFFFFFFF:
            reject("DIRECTORY_SENTINEL", str(ordinal))
        if extra_size != 0 or comment_size != 0:
            reject("DIRECTORY_SUFFIX", str(ordinal))
        unix_file_type = (external_attributes >> 16) & 0o170000
        if external_attributes & 0x10 or (creator_system == 3 and unix_file_type == 0o040000):
            reject("DIRECTORY_ATTRIBUTE", str(ordinal))
        # The upper half is Unix mode only when the creator declares Unix (3).
        # Reinterpreting another creator's opaque host attributes as Unix would create
        # an alternate archive view; the portable DOS directory bit is still universal.
        if flags not in (0, 0x0800) or method not in (0, 8):
            reject("DIRECTORY_MODE", f"{flags:#x}/{method}")
        _zip_name(name_bytes)
        if name_bytes in encountered_names:
            reject("DUPLICATE_ARCHIVE_NAME", name_bytes.decode("ascii"))
        encountered_names.add(name_bytes)
        directory_entries.append((name_bytes, flags, method, crc, packed, plain, header_start))
        directory_cursor = next_record
    if directory_cursor != eocd_start:
        reject("DIRECTORY_CONSUMPTION", f"{directory_cursor}/{eocd_start}")

    member_ranges: list[tuple[int, int, PackageMember]] = []
    for ordinal, (name_bytes, flags, method, crc, packed, plain, header_start) in enumerate(directory_entries):
        if header_start + 30 > directory_start or _u32le(archive, header_start) != 0x04034B50:
            reject("LOCAL_RECORD", f"{ordinal}@{header_start}")
        local_flags = _u16le(archive, header_start + 6)
        local_method = _u16le(archive, header_start + 8)
        local_crc = _u32le(archive, header_start + 14)
        local_packed = _u32le(archive, header_start + 18)
        local_plain = _u32le(archive, header_start + 22)
        local_name_size = _u16le(archive, header_start + 26)
        local_extra_size = _u16le(archive, header_start + 28)
        data_start = header_start + 30 + local_name_size + local_extra_size
        data_end = data_start + local_packed
        if data_start > directory_start or data_end > directory_start or data_end <= header_start:
            reject("LOCAL_EXTENT", str(ordinal))
        local_name = archive[header_start + 30 : header_start + 30 + local_name_size]
        local_extra = archive[header_start + 30 + local_name_size : data_start]
        if (
            local_name != name_bytes
            or local_flags != flags
            or local_method != method
            or local_crc != crc
            or local_packed != packed
            or local_plain != plain
        ):
            reject("HEADER_DISAGREEMENT", str(ordinal))
        if local_extra and local_extra != bytes(len(local_extra)):
            reject("ZIPALIGN_PADDING", str(ordinal))
        observed_crc, observed_sha = _plain_fingerprint(archive, data_start, packed, method, plain)
        if observed_crc != crc:
            reject("CRC32", f"{ordinal}: {observed_crc:#x}/{crc:#x}")
        member_ranges.append(
            (
                header_start,
                data_end,
                PackageMember(
                    name_bytes,
                    flags,
                    method,
                    crc,
                    packed,
                    plain,
                    header_start,
                    data_start,
                    data_end,
                    observed_sha,
                ),
            )
        )
    member_ranges.sort(key=lambda item: item[0])
    next_expected = 0
    offsets: set[int] = set()
    for begin, finish, _ in member_ranges:
        if begin in offsets or begin < next_expected:
            reject("LOCAL_PARTITION", f"{begin}/{next_expected}")
        if begin > next_expected:
            _alignment_padding(archive, next_expected, begin)
        offsets.add(begin)
        next_expected = finish

    signed = next_expected != directory_start
    if signed:
        _signing_block(archive, next_expected, directory_start)
    return PackageView(
        tuple(item[2] for item in member_ranges), signed, directory_start, directory_length
    )


def _alignment_padding(archive: bytes, begin: int, finish: int) -> None:
    """Accept only AGP zipflinger's content-free local padding records.

    They are omitted from the central directory and exist solely to align the next member.  Every
    semantic field is fixed and every extra byte must be zero, so the gap cannot carry a hidden
    name, payload, or alternate archive member.
    """
    length = finish - begin
    if length <= 0 or length > MAX_ALIGNMENT_PADDING_BYTES:
        reject("LOCAL_PADDING_LENGTH", str(length))
    cursor = begin
    records = 0
    while cursor < finish:
        if records >= MAX_ALIGNMENT_PADDING_RECORDS or finish - cursor < 30:
            reject("LOCAL_PADDING_RECORD", f"{records}@{cursor}")
        (
            signature,
            version,
            flags,
            method,
            modified_time,
            modified_date,
            crc,
            packed,
            plain,
            name_size,
            extra_size,
        ) = struct.unpack_from("<IHHHHHIIIHH", archive, cursor)
        record_end = cursor + 30 + name_size + extra_size + packed
        if (
            signature != 0x04034B50
            or version != 0
            or flags != 0
            or method != 0
            or modified_time != ALIGNMENT_PADDING_TIME
            or modified_date != ALIGNMENT_PADDING_DATE
            or crc != 0
            or packed != 0
            or plain != 0
            or name_size != 0
            or extra_size == 0
            or record_end > finish
            or any(archive[cursor + 30 : record_end])
        ):
            reject("LOCAL_PADDING_RECORD", f"{records}@{cursor}")
        cursor = record_end
        records += 1
    if cursor != finish or records == 0:
        reject("LOCAL_PADDING_CONSUMPTION", f"{cursor}/{finish}/{records}")


def _signing_block(archive: bytes, begin: int, finish: int) -> None:
    total = finish - begin
    if total < 44:
        reject("SIGNING_BLOCK_LENGTH", str(total))
    leading_length = _u64le(archive, begin)
    trailing_length = _u64le(archive, finish - 24)
    if archive[finish - 16 : finish] != b"APK Sig Block 42":
        reject("SIGNING_BLOCK_MAGIC", archive[finish - 16 : finish].hex())
    if leading_length != trailing_length or leading_length < 36 or leading_length + 8 != total:
        reject("SIGNING_BLOCK_SIZE", f"{leading_length}/{trailing_length}/{total}")
    pair_cursor = begin + 8
    pair_limit = finish - 24
    pair_ids: set[int] = set()
    pairs = 0
    while pair_cursor < pair_limit:
        if pair_cursor + 8 > pair_limit:
            reject("SIGNING_PAIR_PREFIX", str(pair_cursor))
        pair_length = _u64le(archive, pair_cursor)
        pair_cursor += 8
        if pair_length < 4 or pair_length > pair_limit - pair_cursor:
            reject("SIGNING_PAIR_EXTENT", str(pair_length))
        pair_id = _u32le(archive, pair_cursor)
        if pair_id == 0 or pair_id in pair_ids:
            reject("SIGNING_PAIR_ID", str(pair_id))
        pair_ids.add(pair_id)
        pair_cursor += pair_length
        pairs += 1
    if pair_cursor != pair_limit or pairs < 1:
        reject("SIGNING_PAIR_PARTITION", f"{pair_cursor}/{pair_limit}/{pairs}")


def validate_bundle(bundle_bytes: bytes) -> Record:
    return validate_manifest(bundle_bytes, "native-close-proof-bundle-v1")


def _validate_bundle_mapping(
    bundles: Mapping[bytes, bytes],
) -> dict[bytes, tuple[bytes, Record]]:
    """Validate every raw Mapping item before looking at registry references."""

    try:
        items = tuple(bundles.items())
    except (AttributeError, RuntimeError, TypeError) as exc:
        reject("BUNDLE_MAPPING_TYPE", str(exc))
    checked: dict[bytes, tuple[bytes, Record]] = {}
    decoded_rows: set[tuple[bytes, bytes, bytes, bytes, bytes]] = set()
    total = 0
    for key, payload in items:
        if not isinstance(key, bytes) or len(key) != 32 or key == bytes(32):
            reject("BUNDLE_MAPPING_KEY", repr(key))
        if not isinstance(payload, bytes):
            reject("BUNDLE_MAPPING_VALUE", key.hex())
        total += len(payload)
        if total > 16_777_216:
            reject("BUNDLE_MAPPING_SIZE", str(total))
        if hashlib.sha256(payload).digest() != key:
            reject("BUNDLE_MAPPING_ADDRESS", key.hex())
        bundle = validate_bundle(payload)
        _, evidence = _embedded(bundle.value("proof_evidence"), "native-close-proof-evidence-v1")
        decoded_row = (
            evidence.value("proof_basis_sha256"),
            evidence.value("os_api"),
            evidence.value("abi"),
            evidence.value("installed_jni_set_sha256"),
            evidence.value("delegate_mask"),
        )
        if decoded_row in decoded_rows:
            reject("BUNDLE_MAPPING_DUPLICATE_ROW", key.hex())
        decoded_rows.add(decoded_row)
        checked[key] = (payload, bundle)
    return checked


def validate_fence(
    registry_bytes: bytes,
    bundles: Mapping[bytes, bytes],
) -> FenceDecision:
    inventory = _validate_bundle_mapping(bundles)
    registry = validate_manifest(registry_bytes, "native-close-fence-registry-v1")
    children = _parse_children(
        registry.value("entries"),
        "native-close-fence-entry-v1",
        0,
        512,
        low_size=128,
        high_size=1024,
    )
    if not children:
        return FenceDecision(FenceState.EMPTY, 0)

    linked: list[tuple[Record, bytes, Record, Record, Record]] = []
    consumed_bundle_hashes: set[bytes] = set()
    for _, row in children:
        requested_hash = row.value("proof_bundle_sha256")
        inventory_item = inventory.get(requested_hash)
        if inventory_item is None:
            return FenceDecision(FenceState.MISSING_BUNDLE, len(children))
        bundle_bytes, bundle = inventory_item
        if requested_hash in consumed_bundle_hashes:
            reject("BUNDLE_ROW_REUSE", requested_hash.hex())
        consumed_bundle_hashes.add(requested_hash)
        basis_bytes, basis = _embedded(bundle.value("proof_basis"), "native-close-proof-basis-v1", 4096)
        _, evidence = _embedded(bundle.value("proof_evidence"), "native-close-proof-evidence-v1")
        _, approval = _embedded(bundle.value("proof_approval"), "native-close-proof-approval-v1")
        row_projection = (
            row.value("proof_basis_sha256"),
            row.value("os_api"),
            row.value("abi"),
            row.value("installed_jni_set_sha256"),
            row.value("delegate_mask"),
        )
        evidence_projection = (
            evidence.value("proof_basis_sha256"),
            evidence.value("os_api"),
            evidence.value("abi"),
            evidence.value("installed_jni_set_sha256"),
            evidence.value("delegate_mask"),
        )
        if row_projection != evidence_projection:
            reject("ROW_BUNDLE_LINK", requested_hash.hex())
        linked.append((row, basis_bytes, basis, evidence, approval))

    variants: dict[bytes, bytes] = {}
    for row, _, basis, _, _ in linked:
        variant = basis.value("build_variant")
        existing = variants.get(variant)
        if existing is not None and existing != row.value("proof_basis_sha256"):
            reject("VARIANT_COLLISION", hashlib.sha256(variant).hexdigest())
        variants[variant] = row.value("proof_basis_sha256")

    # Git object/tree verification and installed-JNI reconstruction are not implemented
    # here.  Caller-provided maps/sets are deliberately not an authorization surface.
    return FenceDecision(FenceState.EXTERNAL_VERIFIER_UNIMPLEMENTED, len(children))


def _discover_bundles(root: Path) -> dict[bytes, bytes]:
    if not root.is_dir() or root.is_symlink():
        reject("BUNDLE_DIRECTORY", str(root))
    found: dict[bytes, bytes] = {}
    aggregate = 0
    for candidate in sorted(root.iterdir(), key=lambda path: path.name.encode("utf-8")):
        if candidate.is_symlink() or not candidate.is_file():
            reject("BUNDLE_FILE_TYPE", candidate.name)
        if candidate.suffix != ".bin" or HEX64_PATTERN.fullmatch(candidate.stem) is None:
            reject("BUNDLE_FILE_NAME", candidate.name)
        payload = candidate.read_bytes()
        aggregate += len(payload)
        digest = hashlib.sha256(payload).digest()
        if candidate.name != f"{digest.hex()}.bin":
            reject("BUNDLE_FILE_ADDRESS", candidate.name)
        if digest in found:
            reject("DUPLICATE_BUNDLE", digest.hex())
        # The directory is a complete inventory boundary.  Malformed unreferenced
        # content is fatal instead of being ignored by registry reachability.
        validate_bundle(payload)
        found[digest] = payload
    if aggregate > 16_777_216:
        reject("BUNDLE_DIRECTORY_SIZE", str(aggregate))
    return found


def main(argv: Sequence[str] | None = None) -> int:
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--registry", required=True, type=Path)
    command.add_argument("--bundles", required=True, type=Path)
    options = command.parse_args(argv)
    try:
        decision = validate_fence(
            options.registry.read_bytes(), _discover_bundles(options.bundles)
        )
    except (OSError, FenceValidationError) as exc:
        print(f"NATIVE_CLOSE_FENCE=INVALID {exc}", file=sys.stderr)
        return 2
    print(f"NATIVE_CLOSE_FENCE={decision.state.value} rows={decision.rows}")
    return 0 if decision.authorized else 1


if __name__ == "__main__":
    raise SystemExit(main())
