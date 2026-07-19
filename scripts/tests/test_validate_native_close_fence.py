from __future__ import annotations

import hashlib
import struct
import tempfile
import unittest
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from scripts import validate_native_close_fence as validate


# Literal test-side schema: this fixture encoder does not call either production codec.
FIELD_NAMES: dict[str, tuple[str, ...]] = {
    "native-close-policy-file-v1": ("logical_path", "file_length", "file_sha256"),
    "native-close-policy-set-v1": ("schema_revision", "policy_files"),
    "native-close-dex-entry-v1": (
        "package_output_name", "dex_entry_name", "uncompressed_length", "content_sha256"
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
        "package_output_name", "jni_entry_name", "uncompressed_length", "content_sha256"
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

POLICY_PATHS = (
    "docs/adr/ADR-011-measured-capability-probe-policy.md",
    "docs/contracts/capability-store-v4.md",
    "docs/contracts/native-close-fence-proof-v1.md",
    "docs/contracts/recovery-journal-v5.md",
    "docs/execution/slice-1b-contract.md",
)


def literal_manifest(domain: str, values: Iterable[bytes]) -> bytes:
    names = FIELD_NAMES[domain]
    materialized = tuple(values)
    if len(materialized) != len(names):
        raise AssertionError(f"fixture field mismatch for {domain}")
    output = bytearray(domain.encode("utf-8") + b"\0")
    output += len(names).to_bytes(4, "big")
    for name, value in zip(names, materialized, strict=True):
        raw_name = name.encode("utf-8")
        output += len(raw_name).to_bytes(4, "big") + raw_name
        output += len(value).to_bytes(8, "big") + value
    return bytes(output)


def custom_manifest(domain: str, fields: Iterable[tuple[str, bytes]]) -> bytes:
    materialized = tuple(fields)
    output = bytearray(domain.encode("utf-8") + b"\0")
    output += len(materialized).to_bytes(4, "big")
    for name, value in materialized:
        raw_name = name.encode("utf-8")
        output += len(raw_name).to_bytes(4, "big") + raw_name
        output += len(value).to_bytes(8, "big") + value
    return bytes(output)


def frame(payload: bytes) -> bytes:
    return len(payload).to_bytes(4, "big") + payload


def children(payloads: Iterable[bytes]) -> bytes:
    materialized = tuple(payloads)
    return len(materialized).to_bytes(4, "big") + b"".join(frame(item) for item in materialized)


def unframe(payload: bytes) -> bytes:
    if len(payload) < 4:
        raise AssertionError("short fixture frame")
    size = int.from_bytes(payload[:4], "big")
    if size != len(payload) - 4:
        raise AssertionError("bad fixture frame")
    return payload[4:]


def record_values(record: validate.Record) -> tuple[bytes, ...]:
    return tuple(value for _, value in record.fields)


@dataclass(frozen=True)
class LocalProofFixture:
    registry: bytes
    bundles: dict[bytes, bytes]
    records: dict[str, tuple[bytes, ...]]
    rows: tuple[bytes, ...]
    bases: tuple[bytes, ...]


def local_proof_fixture(
    build_types: tuple[str, ...] = ("debug",), *, verdict: int = 0
) -> LocalProofFixture:
    policy_content = {path: f"policy:{index}".encode() for index, path in enumerate(POLICY_PATHS)}
    policy_files = tuple(
        literal_manifest(
            "native-close-policy-file-v1",
            (
                path.encode("utf-8"),
                len(policy_content[path]).to_bytes(8, "big"),
                hashlib.sha256(policy_content[path]).digest(),
            ),
        )
        for path in POLICY_PATHS
    )
    policy_set = literal_manifest(
        "native-close-policy-set-v1",
        (b"native-close-policy-set-v1", children(policy_files)),
    )
    dex_inputs = (("classes.dex", b"dex-one"), ("classes2.dex", b"dex-two"))
    dex_entries = tuple(
        literal_manifest(
            "native-close-dex-entry-v1",
            (
                b"base",
                name.encode("ascii"),
                len(content).to_bytes(8, "big"),
                hashlib.sha256(content).digest(),
            ),
        )
        for name, content in dex_inputs
    )
    code_image = literal_manifest(
        "native-close-code-image-v1",
        (b"native-close-code-image-v1", children(dex_entries)),
    )
    jni_content = b"jni"
    jni_entry = literal_manifest(
        "native-close-jni-entry-v1",
        (
            b"base",
            b"lib/arm64-v8a/libtasks_vision_jni.so",
            len(jni_content).to_bytes(8, "big"),
            hashlib.sha256(jni_content).digest(),
        ),
    )
    jni_set = literal_manifest(
        "native-close-installed-jni-set-v1",
        (b"arm64-v8a", children((jni_entry,))),
    )
    jni_hash = hashlib.sha256(jni_set).digest()
    artifact_contents = tuple(f"artifact:{category}".encode() for category in range(8))
    artifact_records = tuple(
        literal_manifest(
            "native-close-evidence-entry-v1",
            (
                bytes((category,)),
                len(content).to_bytes(8, "big"),
                hashlib.sha256(content).digest(),
            ),
        )
        for category, content in enumerate(artifact_contents)
    )

    bundles: dict[bytes, bytes] = {}
    rows_with_keys: list[tuple[bytes, bytes]] = []
    variants: list[bytes] = []
    bases: list[bytes] = []
    evidences: list[bytes] = []
    approvals: list[bytes] = []
    for index, build_type in enumerate(build_types):
        variant = literal_manifest(
            "native-close-build-variant-v1",
            (build_type.encode("utf-8"), bytes((int(build_type == "release"),))),
        )
        basis = literal_manifest(
            "native-close-proof-basis-v1",
            (
                b"native-close-proof-basis-v1",
                hashlib.sha256(policy_set).digest(),
                hashlib.sha256(b"dependency-field").digest(),
                variant,
                hashlib.sha256(code_image).digest(),
            ),
        )
        basis_hash = hashlib.sha256(basis).digest()
        api = (26 + index).to_bytes(4, "big")
        evidence = literal_manifest(
            "native-close-proof-evidence-v1",
            (
                b"native-close-proof-evidence-v1",
                b"agent:/producer",
                basis_hash,
                api,
                b"arm64-v8a",
                jni_hash,
                b"\x03",
                b"\x00",
                children(artifact_records),
            ),
        )
        evidence_hash = hashlib.sha256(evidence).digest()
        approval = literal_manifest(
            "native-close-proof-approval-v1",
            (
                b"native-close-proof-approval-v1",
                evidence_hash,
                b"agent:/reviewer",
                format(index + 1, "040x").encode("ascii"),
                format(index + 101, "040x").encode("ascii"),
                bytes((verdict,)),
                bytes(4),
                bytes(4),
                bytes(4),
            ),
        )
        bundle = literal_manifest(
            "native-close-proof-bundle-v1",
            (
                b"native-close-proof-bundle-v1",
                frame(basis),
                basis_hash,
                frame(evidence),
                evidence_hash,
                frame(approval),
                hashlib.sha256(approval).digest(),
            ),
        )
        bundle_hash = hashlib.sha256(bundle).digest()
        row = literal_manifest(
            "native-close-fence-entry-v1",
            (basis_hash, api, b"arm64-v8a", jni_hash, b"\x03", bundle_hash),
        )
        row_key = basis_hash + api + len(b"arm64-v8a").to_bytes(4, "big") + b"arm64-v8a" + jni_hash
        bundles[bundle_hash] = bundle
        rows_with_keys.append((row_key, row))
        variants.append(variant)
        bases.append(basis)
        evidences.append(evidence)
        approvals.append(approval)

    rows = tuple(row for _, row in sorted(rows_with_keys))
    registry = literal_manifest(
        "native-close-fence-registry-v1",
        (b"native-close-fence-registry-v1", children(rows)),
    )
    return LocalProofFixture(
        registry,
        bundles,
        {
            "native-close-policy-file-v1": policy_files,
            "native-close-policy-set-v1": (policy_set,),
            "native-close-dex-entry-v1": dex_entries,
            "native-close-code-image-v1": (code_image,),
            "native-close-build-variant-v1": tuple(variants),
            "native-close-proof-basis-v1": tuple(bases),
            "native-close-jni-entry-v1": (jni_entry,),
            "native-close-installed-jni-set-v1": (jni_set,),
            "native-close-evidence-entry-v1": artifact_records,
            "native-close-proof-evidence-v1": tuple(evidences),
            "native-close-proof-approval-v1": tuple(approvals),
            "native-close-proof-bundle-v1": tuple(bundles.values()),
            "native-close-fence-entry-v1": rows,
            "native-close-fence-registry-v1": (registry,),
        },
        rows,
        tuple(bases),
    )


def raw_deflate(payload: bytes) -> bytes:
    encoder = zlib.compressobj(level=6, wbits=-15)
    return encoder.compress(payload) + encoder.flush()


def signing_block(
    pairs: tuple[tuple[int, bytes], ...] = ((0x7109871A, b"signature"),), *, tail: bytes = b""
) -> bytes:
    encoded_pairs = b"".join(
        struct.pack("<Q", 4 + len(value)) + struct.pack("<I", pair_id) + value
        for pair_id, value in pairs
    ) + tail
    block_size = 24 + len(encoded_pairs)
    return struct.pack("<Q", block_size) + encoded_pairs + struct.pack("<Q", block_size) + b"APK Sig Block 42"


def make_apk(
    members: tuple[tuple[str, bytes, int], ...] = (("classes.dex", b"dex-content", 0),),
    *,
    local_extra: bytes = b"",
    block: bytes | None = None,
) -> bytes:
    local_records = bytearray()
    central_records: list[bytes] = []
    for name, plain, method in members:
        raw_name = name.encode("ascii")
        packed = plain if method == 0 else raw_deflate(plain)
        checksum = zlib.crc32(plain) & 0xFFFFFFFF
        local_offset = len(local_records)
        local_records += struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50,
            20,
            0,
            method,
            0,
            0,
            checksum,
            len(packed),
            len(plain),
            len(raw_name),
            len(local_extra),
        )
        local_records += raw_name + local_extra + packed
        central_records.append(
            struct.pack(
                "<IHHHHHHIIIHHHHHII",
                0x02014B50,
                20,
                20,
                0,
                method,
                0,
                0,
                checksum,
                len(packed),
                len(plain),
                len(raw_name),
                0,
                0,
                0,
                0,
                0,
                local_offset,
            )
            + raw_name
        )
    gap = b"" if block is None else block
    directory_start = len(local_records) + len(gap)
    directory = b"".join(central_records)
    eocd = struct.pack(
        "<IHHHHIIH",
        0x06054B50,
        0,
        0,
        len(members),
        len(members),
        len(directory),
        directory_start,
        0,
    )
    return bytes(local_records) + gap + directory + eocd


def mutate_u16(payload: bytes, offset: int, value: int) -> bytes:
    result = bytearray(payload)
    result[offset : offset + 2] = struct.pack("<H", value)
    return bytes(result)


def mutate_u32(payload: bytes, offset: int, value: int) -> bytes:
    result = bytearray(payload)
    result[offset : offset + 4] = struct.pack("<I", value)
    return bytes(result)


class NativeCloseFenceIndependentValidatorTest(unittest.TestCase):
    GOLDEN_BUILD_VARIANT_HEX = (
        "6e61746976652d636c6f73652d6275696c642d76617269616e742d76310000000002"
        "0000000a6275696c645f7479706500000000000000056465627567000000086d696e"
        "6966696564000000000000000100"
    )
    MAJOR_GOLDEN_SHA256 = {
        "native-close-policy-set-v1": "8fa415c6695074520cc812ea50aad35f6489d4c06be87a055d8a0f6b4bf82425",
        "native-close-code-image-v1": "4947ff59a67eec8301b8c91ed950d9ccc82eab108797ce138a65b2b61ee05b87",
        "native-close-installed-jni-set-v1": "6b02a4be687e1513470d48728ae1ea3af5d1d3a33dbd70dab73d7fc2719f3151",
        "native-close-proof-basis-v1": "e8419462d4f4e9b78c6ee562cc31eafccac84e75eb99117009729c22cbbcd0fe",
        "native-close-proof-evidence-v1": "8fb73e38aba68f17c18fd614e04c2a53564bf9e6d80e67b45f2c67454dc6bed0",
        "native-close-proof-approval-v1": "c5b4135303aae508a5d26a4a4cdc4fad1fc00b95d4e6bb0db1d729738b0ba9eb",
        "native-close-proof-bundle-v1": "08cf5ec42e33a98dc9ce38696d9db5a3e7da3442be5186892a001d252896553a",
        "native-close-fence-registry-v1": "41363b21b66ac2da0404cdb99b125107f46b9467a8f34bd0ec9f53997c049cb7",
    }

    def test_literal_golden_and_major_cross_hashes(self) -> None:
        fixture = local_proof_fixture()
        variant = fixture.records["native-close-build-variant-v1"][0]
        self.assertEqual(self.GOLDEN_BUILD_VARIANT_HEX, variant.hex())
        for domain, expected in self.MAJOR_GOLDEN_SHA256.items():
            with self.subTest(domain=domain):
                self.assertEqual(expected, hashlib.sha256(fixture.records[domain][0]).hexdigest())

    def test_independent_literal_fixture_round_trips_every_schema(self) -> None:
        fixture = local_proof_fixture(("debug", "release"))
        for domain, records in fixture.records.items():
            for payload in records:
                with self.subTest(domain=domain):
                    parsed = validate.validate_manifest(payload, domain)
                    self.assertEqual(payload, validate.canonical_bytes(parsed))

    def test_empty_missing_and_structural_rows_are_all_denied(self) -> None:
        empty = literal_manifest(
            "native-close-fence-registry-v1",
            (b"native-close-fence-registry-v1", bytes(4)),
        )
        self.assertEqual(validate.FenceState.EMPTY, validate.validate_fence(empty, {}).state)
        fixture = local_proof_fixture()
        self.assertEqual(
            validate.FenceState.MISSING_BUNDLE,
            validate.validate_fence(fixture.registry, {}).state,
        )
        decision = validate.validate_fence(fixture.registry, fixture.bundles)
        self.assertFalse(decision.authorized)
        self.assertEqual(validate.FenceState.EXTERNAL_VERIFIER_UNIMPLEMENTED, decision.state)

    def test_caller_claimed_context_is_not_an_api(self) -> None:
        fixture = local_proof_fixture()
        with self.assertRaises(TypeError):
            validate.validate_fence(fixture.registry, fixture.bundles, object())

    def test_debug_release_bases_are_distinct_but_not_authorized(self) -> None:
        fixture = local_proof_fixture(("debug", "release"))
        self.assertEqual(2, len({hashlib.sha256(item).digest() for item in fixture.bases}))
        decision = validate.validate_fence(fixture.registry, fixture.bundles)
        self.assertEqual(2, decision.rows)
        self.assertEqual(validate.FenceState.EXTERNAL_VERIFIER_UNIMPLEMENTED, decision.state)

    def test_nonzero_approval_rejects_before_external_gate(self) -> None:
        fixture = local_proof_fixture(verdict=1)
        with self.assertRaises(validate.FenceValidationError) as raised:
            validate.validate_fence(fixture.registry, fixture.bundles)
        self.assertEqual("APPROVAL_VERDICT", raised.exception.rule)

    def test_field_value_trailing_and_field_order_mutations_reject(self) -> None:
        variant = local_proof_fixture().records["native-close-build-variant-v1"][0]
        candidates = (
            variant[:-1] + b"\x02",
            variant + b"\x00",
            custom_manifest(
                "native-close-build-variant-v1",
                (("minified", b"\0"), ("build_type", b"debug")),
            ),
        )
        for candidate in candidates:
            with self.subTest(candidate=candidate.hex()), self.assertRaises(validate.FenceValidationError):
                validate.validate_manifest(candidate, "native-close-build-variant-v1")

    def test_bundle_digest_and_cross_reference_mutations_reject(self) -> None:
        fixture = local_proof_fixture()
        bundle_bytes = next(iter(fixture.bundles.values()))
        bundle = validate.validate_bundle(bundle_bytes)
        values = list(record_values(bundle))
        values[4] = values[4][:-1] + bytes((values[4][-1] ^ 1,))
        with self.assertRaises(validate.FenceValidationError) as digest_error:
            validate.validate_bundle(literal_manifest(bundle.kind, values))
        self.assertEqual("EVIDENCE_DIGEST", digest_error.exception.rule)

        approval = validate.validate_manifest(
            unframe(bundle.value("proof_approval")), "native-close-proof-approval-v1"
        )
        approval_values = list(record_values(approval))
        approval_values[1] = bytes(32)
        changed_approval = literal_manifest(approval.kind, approval_values)
        values = list(record_values(bundle))
        values[5] = frame(changed_approval)
        values[6] = hashlib.sha256(changed_approval).digest()
        with self.assertRaises(validate.FenceValidationError) as reference_error:
            validate.validate_bundle(literal_manifest(bundle.kind, values))
        self.assertEqual("APPROVAL_TO_EVIDENCE", reference_error.exception.rule)

    def test_installed_jni_set_rejects_cross_split_logical_collision(self) -> None:
        entries = tuple(
            literal_manifest(
                "native-close-jni-entry-v1",
                (
                    package,
                    b"lib/arm64-v8a/libsame.so",
                    (1).to_bytes(8, "big"),
                    hashlib.sha256(package).digest(),
                ),
            )
            for package in (b"base", b"feature")
        )
        jni_set = literal_manifest(
            "native-close-installed-jni-set-v1",
            (b"arm64-v8a", children(entries)),
        )
        with self.assertRaises(validate.FenceValidationError) as raised:
            validate.validate_manifest(jni_set, "native-close-installed-jni-set-v1")
        self.assertEqual("JNI_LOGICAL_PATH_COLLISION", raised.exception.rule)

    def test_consistently_rehashed_jni_mutation_never_authorizes(self) -> None:
        fixture = local_proof_fixture()
        bundle = validate.validate_bundle(next(iter(fixture.bundles.values())))
        evidence = validate.validate_manifest(
            unframe(bundle.value("proof_evidence")), "native-close-proof-evidence-v1"
        )
        evidence_values = list(record_values(evidence))
        evidence_values[5] = b"\x42" * 32
        changed_evidence = literal_manifest(evidence.kind, evidence_values)
        evidence_hash = hashlib.sha256(changed_evidence).digest()
        approval = validate.validate_manifest(
            unframe(bundle.value("proof_approval")), "native-close-proof-approval-v1"
        )
        approval_values = list(record_values(approval))
        approval_values[1] = evidence_hash
        changed_approval = literal_manifest(approval.kind, approval_values)
        bundle_values = list(record_values(bundle))
        bundle_values[3] = frame(changed_evidence)
        bundle_values[4] = evidence_hash
        bundle_values[5] = frame(changed_approval)
        bundle_values[6] = hashlib.sha256(changed_approval).digest()
        changed_bundle = literal_manifest(bundle.kind, bundle_values)
        bundle_hash = hashlib.sha256(changed_bundle).digest()
        row = validate.validate_manifest(fixture.rows[0], "native-close-fence-entry-v1")
        row_values = list(record_values(row))
        row_values[3] = b"\x42" * 32
        row_values[5] = bundle_hash
        changed_row = literal_manifest(row.kind, row_values)
        registry = literal_manifest(
            "native-close-fence-registry-v1",
            (b"native-close-fence-registry-v1", children((changed_row,))),
        )
        decision = validate.validate_fence(registry, {bundle_hash: changed_bundle})
        self.assertFalse(decision.authorized)
        self.assertEqual(validate.FenceState.EXTERNAL_VERIFIER_UNIMPLEMENTED, decision.state)

    def test_discovery_validates_unreferenced_bundle_and_filename_inventory(self) -> None:
        fixture = local_proof_fixture()
        valid_hash, valid_bundle = next(iter(fixture.bundles.items()))
        malformed = b"malformed-unreferenced-bundle"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / f"{valid_hash.hex()}.bin").write_bytes(valid_bundle)
            (root / f"{hashlib.sha256(malformed).hexdigest()}.bin").write_bytes(malformed)
            with self.assertRaises(validate.FenceValidationError):
                validate._discover_bundles(root)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / f"{'0' * 64}.bin").write_bytes(valid_bundle)
            with self.assertRaises(validate.FenceValidationError) as raised:
                validate._discover_bundles(root)
        self.assertEqual("BUNDLE_FILE_ADDRESS", raised.exception.rule)

    def test_public_fence_api_validates_complete_raw_bundle_mapping_first(self) -> None:
        fixture = local_proof_fixture()
        malformed = b"unreferenced-malformed-bundle"
        malformed_hash = hashlib.sha256(malformed).digest()
        with self.assertRaises(validate.FenceValidationError) as malformed_error:
            validate.validate_fence(
                fixture.registry,
                {**fixture.bundles, malformed_hash: malformed},
            )
        self.assertEqual("DOMAIN_MARKER", malformed_error.exception.rule)

        with self.assertRaises(validate.FenceValidationError) as key_error:
            validate.validate_fence(b"not-a-registry", {b"short": b"value"})
        self.assertEqual("BUNDLE_MAPPING_KEY", key_error.exception.rule)

        valid_bundle = next(iter(fixture.bundles.values()))
        with self.assertRaises(validate.FenceValidationError) as address_error:
            validate.validate_fence(fixture.registry, {b"\x01" * 32: valid_bundle})
        self.assertEqual("BUNDLE_MAPPING_ADDRESS", address_error.exception.rule)

    def test_unsigned_signed_deflate_and_inert_signature_controls(self) -> None:
        unsigned = make_apk(
            (
                ("classes.dex", b"PK\x05\x06 inert EOCD", 0),
                ("lib/x86/libx.so", b"PK\x01\x02 inert directory" * 4, 8),
            )
        )
        unsigned_view = validate.validate_apk(unsigned)
        self.assertFalse(unsigned_view.has_signing_block)
        self.assertEqual(2, len(unsigned_view.members))
        signed_view = validate.validate_apk(
            make_apk(block=signing_block(((0x12345678, b"PK\x03\x04 opaque local"),)))
        )
        self.assertTrue(signed_view.has_signing_block)

    def test_invalid_raw_deflate_is_typed_fail_closed(self) -> None:
        archive = make_apk((("classes.dex", b"deflated-content" * 8, 8),))
        packed_size = struct.unpack_from("<I", archive, 18)[0]
        payload_start = 30 + len("classes.dex")
        malformed = archive[:payload_start] + b"\xff" * packed_size + archive[payload_start + packed_size :]
        with self.assertRaises(validate.FenceValidationError) as raised:
            validate.validate_apk(malformed)
        self.assertEqual("DEFLATE_ERROR", raised.exception.rule)

    def test_creator_aware_directory_attributes_and_slash_names(self) -> None:
        archive = make_apk()
        eocd = len(archive) - 22
        central = struct.unpack_from("<I", archive, eocd + 16)[0]
        dos_directory = mutate_u32(archive, central + 38, 0x10)
        with self.assertRaises(validate.FenceValidationError) as dos_error:
            validate.validate_apk(dos_directory)
        self.assertEqual("DIRECTORY_ATTRIBUTE", dos_error.exception.rule)

        unix = mutate_u16(archive, central + 4, (3 << 8) | 20)
        unix_directory = mutate_u32(unix, central + 38, 0o040000 << 16)
        with self.assertRaises(validate.FenceValidationError) as unix_error:
            validate.validate_apk(unix_directory)
        self.assertEqual("DIRECTORY_ATTRIBUTE", unix_error.exception.rule)

        unix_regular = mutate_u32(unix, central + 38, 0o100000 << 16)
        self.assertEqual(1, len(validate.validate_apk(unix_regular).members))

        # Non-Unix upper attributes retain their creator-specific meaning.  Only the
        # portable DOS bit is interpreted for every creator.
        other_creator = mutate_u32(archive, central + 38, 0o040000 << 16)
        self.assertEqual(1, len(validate.validate_apk(other_creator).members))

        with self.assertRaises(validate.FenceValidationError) as slash_error:
            validate.validate_apk(make_apk((("directory/", b"x", 0),)))
        self.assertEqual("ARCHIVE_NAME", slash_error.exception.rule)

    def test_validator_rejects_whole_file_zip_mutations(self) -> None:
        base = make_apk((("classes.dex", b"content", 0), ("classes2.dex", b"other", 8)))
        eocd = len(base) - 22
        central = struct.unpack_from("<I", base, eocd + 16)[0]
        second_central = central + 46 + len("classes.dex")
        first_payload = 30 + len("classes.dex")
        changed_name = bytearray(base)
        changed_name[30] ^= 1
        cases = {
            "comment": mutate_u16(base, eocd + 20, 1),
            "prefix": b"x" + base,
            "trailing": base + b"x",
            "truncated": base[:-1],
            "disk": mutate_u16(base, eocd + 6, 1),
            "count_mismatch": mutate_u16(base, eocd + 8, 1),
            "count_sentinel": mutate_u16(base, eocd + 10, 0xFFFF),
            "offset_sentinel": mutate_u32(base, eocd + 16, 0xFFFFFFFF),
            "directory_extent": mutate_u32(base, eocd + 12, struct.unpack_from("<I", base, eocd + 12)[0] - 1),
            "directory_signature": mutate_u32(base, central, 0x02014B51),
            "duplicate_local": mutate_u32(base, second_central + 42, 0),
            "outside_local": mutate_u32(base, central + 42, central),
            "central_extra": mutate_u16(base, central + 30, 1),
            "local_metadata": mutate_u16(base, 6, 0x0800),
            "local_name": bytes(changed_name),
            "crc": base[:first_payload] + bytes((base[first_payload] ^ 1,)) + base[first_payload + 1 :],
            "traversal": make_apk((("a/../b", b"x", 0),)),
            "local_extra": make_apk(local_extra=b"\xff"),
        }
        for label, candidate in cases.items():
            with self.subTest(label=label), self.assertRaises(validate.FenceValidationError):
                validate.validate_apk(candidate)

    def test_validator_rejects_signing_block_mutations(self) -> None:
        valid = make_apk(block=signing_block())
        view = validate.validate_apk(valid)
        block_start = view.members[-1].data_end
        bad_magic = bytearray(valid)
        bad_magic[view.directory_start - 1] ^= 1
        unequal = bytearray(valid)
        unequal[block_start] ^= 1
        zero_id = bytearray(valid)
        zero_id[block_start + 16 : block_start + 20] = bytes(4)
        underflow = bytearray(valid)
        underflow[block_start + 8 : block_start + 16] = struct.pack("<Q", 3)
        overflow = bytearray(valid)
        overflow[block_start + 8 : block_start + 16] = struct.pack("<Q", 0xFFFFFFFFFFFFFFFF)
        cases = {
            "short": make_apk(block=b"tiny"),
            "magic": bytes(bad_magic),
            "sizes": bytes(unequal),
            "zero_id": bytes(zero_id),
            "duplicate_id": make_apk(block=signing_block(((9, b"a"), (9, b"b")))),
            "underflow": bytes(underflow),
            "overflow": bytes(overflow),
            "nonexact": make_apk(block=signing_block(tail=b"x")),
        }
        for label, candidate in cases.items():
            with self.subTest(label=label), self.assertRaises(validate.FenceValidationError):
                validate.validate_apk(candidate)

    def test_current_debug_abi_split_apks_when_present(self) -> None:
        apks = [
            Path(f"app/build/outputs/apk/debug/app-{abi}-debug.apk")
            for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        ]
        present = [apk for apk in apks if apk.is_file()]
        if not present:
            self.skipTest("debug ABI-split APK set not present")
        self.assertEqual(apks, present, "partial debug ABI-split APK set")
        for apk in apks:
            with self.subTest(apk=apk.name):
                view = validate.validate_apk(apk.read_bytes())
                self.assertTrue(view.has_signing_block)
                self.assertGreater(len(view.members), 1)


if __name__ == "__main__":
    unittest.main()
