from __future__ import annotations

import hashlib
import struct
import tempfile
import unittest
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from scripts import native_close_fence_generate as generate


def frame(payload: bytes) -> bytes:
    return len(payload).to_bytes(4, "big") + payload


def children(payloads: Iterable[bytes]) -> bytes:
    values = tuple(payloads)
    return len(values).to_bytes(4, "big") + b"".join(frame(value) for value in values)


def custom_manifest(domain: str, fields: Iterable[tuple[str, bytes]]) -> bytes:
    pairs = tuple(fields)
    output = bytearray(domain.encode("utf-8") + b"\0")
    output += len(pairs).to_bytes(4, "big")
    for name, value in pairs:
        raw_name = name.encode("utf-8")
        output += len(raw_name).to_bytes(4, "big") + raw_name
        output += len(value).to_bytes(8, "big") + value
    return bytes(output)


@dataclass(frozen=True)
class ProofFixture:
    registry: bytes
    bundles: dict[bytes, bytes]
    records: dict[str, tuple[bytes, ...]]
    rows: tuple[bytes, ...]
    bases: tuple[bytes, ...]


def build_proof_fixture(
    build_types: tuple[str, ...] = ("debug",),
    *,
    verdict: int = 0,
) -> ProofFixture:
    policy_content = {path: f"policy:{index}".encode() for index, path in enumerate(generate.POLICY_PATHS)}
    policy_files = tuple(generate.make_policy_file(path, policy_content[path]) for path in generate.POLICY_PATHS)
    policy_set = generate.make_policy_set(policy_content)
    code_image = generate.make_code_image(
        (
            ("base", "classes.dex", b"dex-one"),
            ("base", "classes2.dex", b"dex-two"),
        )
    )
    jni_set = generate.make_installed_jni_set(
        "arm64-v8a", (("base", "lib/arm64-v8a/libtasks_vision_jni.so", b"jni"),)
    )
    jni_hash = hashlib.sha256(jni_set).digest()
    artifact_contents = tuple(f"artifact:{category}".encode() for category in range(8))
    artifact_records = tuple(
        generate.encode_manifest(
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
    rows: list[bytes] = []
    bases: list[bytes] = []
    variants: list[bytes] = []
    evidences: list[bytes] = []
    approvals: list[bytes] = []
    for index, build_type in enumerate(build_types):
        variant = generate.make_build_variant(build_type, build_type == "release")
        basis = generate.make_proof_basis(policy_set, b"dependency-field", variant, code_image)
        basis_hash = hashlib.sha256(basis).digest()
        evidence = generate.encode_manifest(
            "native-close-proof-evidence-v1",
            (
                b"native-close-proof-evidence-v1",
                b"agent:/producer",
                basis_hash,
                (26 + index).to_bytes(4, "big"),
                b"arm64-v8a",
                jni_hash,
                b"\x03",
                b"\x00",
                children(artifact_records),
            ),
        )
        evidence_hash = hashlib.sha256(evidence).digest()
        commit = format(index + 1, "040x")
        tree = format(index + 101, "040x")
        approval = generate.encode_manifest(
            "native-close-proof-approval-v1",
            (
                b"native-close-proof-approval-v1",
                evidence_hash,
                b"agent:/reviewer",
                commit.encode("ascii"),
                tree.encode("ascii"),
                bytes((verdict,)),
                bytes(4),
                bytes(4),
                bytes(4),
            ),
        )
        bundle = generate.encode_manifest(
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
        row = generate.encode_manifest(
            "native-close-fence-entry-v1",
            (
                basis_hash,
                (26 + index).to_bytes(4, "big"),
                b"arm64-v8a",
                jni_hash,
                b"\x03",
                bundle_hash,
            ),
        )
        bundles[bundle_hash] = bundle
        rows.append(row)
        bases.append(basis)
        variants.append(variant)
        evidences.append(evidence)
        approvals.append(approval)

    rows.sort(key=lambda raw: generate._fence_key(generate.parse_manifest(raw, "native-close-fence-entry-v1")))
    registry = generate.encode_manifest(
        "native-close-fence-registry-v1",
        (b"native-close-fence-registry-v1", children(rows)),
    )
    return ProofFixture(
        registry=registry,
        bundles=bundles,
        records={
            "native-close-policy-file-v1": policy_files,
            "native-close-policy-set-v1": (policy_set,),
            "native-close-dex-entry-v1": tuple(
                raw for raw, _ in generate._parse_list(
                    generate.parse_manifest(code_image, "native-close-code-image-v1").values[1],
                    "native-close-dex-entry-v1",
                    1,
                    64,
                )
            ),
            "native-close-code-image-v1": (code_image,),
            "native-close-build-variant-v1": tuple(variants),
            "native-close-proof-basis-v1": tuple(bases),
            "native-close-jni-entry-v1": tuple(
                raw for raw, _ in generate._parse_list(
                    generate.parse_manifest(jni_set, "native-close-installed-jni-set-v1").values[1],
                    "native-close-jni-entry-v1",
                    1,
                    256,
                )
            ),
            "native-close-installed-jni-set-v1": (jni_set,),
            "native-close-evidence-entry-v1": artifact_records,
            "native-close-proof-evidence-v1": tuple(evidences),
            "native-close-proof-approval-v1": tuple(approvals),
            "native-close-proof-bundle-v1": tuple(bundles.values()),
            "native-close-fence-entry-v1": tuple(rows),
            "native-close-fence-registry-v1": (registry,),
        },
        rows=tuple(rows),
        bases=tuple(bases),
    )


def raw_deflate(payload: bytes) -> bytes:
    encoder = zlib.compressobj(level=6, wbits=-15)
    return encoder.compress(payload) + encoder.flush()


def signing_block(
    pairs: tuple[tuple[int, bytes], ...] = ((0x7109871A, b"signature"),),
    *,
    tail: bytes = b"",
) -> bytes:
    pair_bytes = b"".join(
        struct.pack("<Q", 4 + len(value)) + struct.pack("<I", pair_id) + value
        for pair_id, value in pairs
    ) + tail
    block_size = 24 + len(pair_bytes)
    return struct.pack("<Q", block_size) + pair_bytes + struct.pack("<Q", block_size) + b"APK Sig Block 42"


def alignment_padding(extra_size: int) -> bytes:
    if extra_size <= 0 or extra_size > 0xFFFF:
        raise ValueError("invalid alignment padding size")
    return struct.pack(
        "<IHHHHHIIIHH",
        0x04034B50,
        0,
        0,
        0,
        generate.ALIGNMENT_PADDING_TIME,
        generate.ALIGNMENT_PADDING_DATE,
        0,
        0,
        0,
        0,
        extra_size,
    ) + bytes(extra_size)


def make_apk(
    members: tuple[tuple[str, bytes, int], ...] = (("classes.dex", b"dex-content", 0),),
    *,
    local_extra: bytes = b"",
    block: bytes | None = None,
    gap_after_first: bytes = b"",
) -> bytes:
    locals_blob = bytearray()
    directory_parts: list[bytes] = []
    for name, plain, method in members:
        name_bytes = name.encode("ascii")
        packed = plain if method == 0 else raw_deflate(plain)
        checksum = zlib.crc32(plain) & 0xFFFFFFFF
        local_offset = len(locals_blob)
        locals_blob += struct.pack(
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
            len(name_bytes),
            len(local_extra),
        )
        locals_blob += name_bytes + local_extra + packed
        directory_parts.append(
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
                len(name_bytes),
                0,
                0,
                0,
                0,
                0,
                local_offset,
            )
            + name_bytes
        )
        if len(directory_parts) == 1:
            locals_blob += gap_after_first
    middle = b"" if block is None else block
    central_offset = len(locals_blob) + len(middle)
    directory = b"".join(directory_parts)
    eocd = struct.pack(
        "<IHHHHIIH",
        0x06054B50,
        0,
        0,
        len(members),
        len(members),
        len(directory),
        central_offset,
        0,
    )
    return bytes(locals_blob) + middle + directory + eocd


def mutate_u16(payload: bytes, offset: int, value: int) -> bytes:
    changed = bytearray(payload)
    changed[offset : offset + 2] = struct.pack("<H", value)
    return bytes(changed)


def mutate_u32(payload: bytes, offset: int, value: int) -> bytes:
    changed = bytearray(payload)
    changed[offset : offset + 4] = struct.pack("<I", value)
    return bytes(changed)


class NativeCloseFenceGeneratorTest(unittest.TestCase):
    GOLDEN_BUILD_VARIANT_HEX = (
        "6e61746976652d636c6f73652d6275696c642d76617269616e742d76310000000002"
        "0000000a6275696c645f7479706500000000000000056465627567000000086d696e"
        "6966696564000000000000000100"
    )

    def test_phase_a_constructors_and_every_schema_round_trip(self) -> None:
        fixture = build_proof_fixture()
        for domain, records in fixture.records.items():
            for record in records:
                with self.subTest(domain=domain):
                    parsed = generate.parse_manifest(record, domain)
                    self.assertEqual(record, generate.encode_manifest(domain, parsed.values))

    def test_build_variant_golden_bytes(self) -> None:
        encoded = generate.make_build_variant("debug", False)
        self.assertEqual(self.GOLDEN_BUILD_VARIANT_HEX, encoded.hex())

    def test_empty_registry_is_canonical_but_unauthorized(self) -> None:
        registry = generate.encode_manifest(
            "native-close-fence-registry-v1",
            (b"native-close-fence-registry-v1", bytes(4)),
        )
        self.assertEqual(registry, generate.encode_manifest(
            "native-close-fence-registry-v1",
            generate.parse_manifest(registry, "native-close-fence-registry-v1").values,
        ))
        result = generate.verify_registry(registry, {})
        self.assertFalse(result.authorized)
        self.assertEqual(generate.ProofUnavailableReason.EMPTY_REGISTRY, result.reason)

    def test_row_without_bundle_is_typed_fail_closed(self) -> None:
        fixture = build_proof_fixture()
        result = generate.verify_registry(fixture.registry, {})
        self.assertFalse(result.authorized)
        self.assertEqual(generate.ProofUnavailableReason.MISSING_BUNDLE, result.reason)

    def test_structural_bundle_is_closed_without_trusted_external_verifier(self) -> None:
        fixture = build_proof_fixture()
        result = generate.verify_registry(fixture.registry, fixture.bundles)
        self.assertFalse(result.authorized)
        self.assertEqual(
            generate.ProofUnavailableReason.EXTERNAL_VERIFIER_UNIMPLEMENTED,
            result.reason,
        )

    def test_caller_cannot_supply_replacement_objects_to_authorize(self) -> None:
        fixture = build_proof_fixture()
        with self.assertRaises(TypeError):
            generate.verify_registry(fixture.registry, fixture.bundles, object())
        result = generate.verify_registry(fixture.registry, fixture.bundles)
        self.assertFalse(result.authorized)
        self.assertEqual(1, result.entry_count)

    def test_debug_and_release_are_distinct_reconstructable_bases(self) -> None:
        fixture = build_proof_fixture(("debug", "release"))
        self.assertEqual(2, len({hashlib.sha256(basis).digest() for basis in fixture.bases}))
        variants = {
            generate.parse_manifest(basis, "native-close-proof-basis-v1").values[3]
            for basis in fixture.bases
        }
        self.assertEqual(2, len(variants))
        result = generate.verify_registry(fixture.registry, fixture.bundles)
        self.assertFalse(result.authorized)
        self.assertEqual(
            generate.ProofUnavailableReason.EXTERNAL_VERIFIER_UNIMPLEMENTED,
            result.reason,
        )

    def test_nonzero_approval_verdict_rejects_row(self) -> None:
        fixture = build_proof_fixture(verdict=1)
        with self.assertRaises(generate.NativeCloseFenceError) as raised:
            generate.verify_registry(fixture.registry, fixture.bundles)
        self.assertEqual("VERDICT", raised.exception.code)

    def test_installed_jni_set_rejects_cross_split_logical_collision(self) -> None:
        with self.assertRaises(generate.NativeCloseFenceError) as raised:
            generate.make_installed_jni_set(
                "arm64-v8a",
                (
                    ("base", "lib/arm64-v8a/libsame.so", b"base"),
                    ("feature", "lib/arm64-v8a/libsame.so", b"split"),
                ),
            )
        self.assertEqual("JNI_LOGICAL_PATH_COLLISION", raised.exception.code)

    def test_consistently_rehashed_jni_mutation_never_authorizes(self) -> None:
        fixture = build_proof_fixture()
        bundle_raw = next(iter(fixture.bundles.values()))
        bundle = generate.parse_manifest(bundle_raw, "native-close-proof-bundle-v1")
        _, evidence = generate._framed(bundle.values[3], "native-close-proof-evidence-v1")
        evidence_values = list(evidence.values)
        evidence_values[5] = b"\x42" * 32
        changed_evidence = generate.encode_manifest(evidence.domain, tuple(evidence_values))
        changed_evidence_hash = hashlib.sha256(changed_evidence).digest()

        _, approval = generate._framed(bundle.values[5], "native-close-proof-approval-v1")
        approval_values = list(approval.values)
        approval_values[1] = changed_evidence_hash
        changed_approval = generate.encode_manifest(approval.domain, tuple(approval_values))

        bundle_values = list(bundle.values)
        bundle_values[3] = frame(changed_evidence)
        bundle_values[4] = changed_evidence_hash
        bundle_values[5] = frame(changed_approval)
        bundle_values[6] = hashlib.sha256(changed_approval).digest()
        changed_bundle = generate.encode_manifest(bundle.domain, tuple(bundle_values))
        changed_bundle_hash = hashlib.sha256(changed_bundle).digest()

        row = generate.parse_manifest(fixture.rows[0], "native-close-fence-entry-v1")
        row_values = list(row.values)
        row_values[3] = b"\x42" * 32
        row_values[5] = changed_bundle_hash
        changed_row = generate.encode_manifest(row.domain, tuple(row_values))
        registry = generate.encode_manifest(
            "native-close-fence-registry-v1",
            (b"native-close-fence-registry-v1", children((changed_row,))),
        )
        result = generate.verify_registry(
            registry, {changed_bundle_hash: changed_bundle}
        )
        self.assertFalse(result.authorized)
        self.assertEqual(
            generate.ProofUnavailableReason.EXTERNAL_VERIFIER_UNIMPLEMENTED,
            result.reason,
        )

    def test_bundle_discovery_rejects_unreferenced_malformed_inventory(self) -> None:
        fixture = build_proof_fixture()
        valid_hash, valid_bundle = next(iter(fixture.bundles.items()))
        malformed = b"not-a-canonical-bundle"
        malformed_hash = hashlib.sha256(malformed).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / f"{valid_hash.hex()}.bin").write_bytes(valid_bundle)
            (root / f"{malformed_hash}.bin").write_bytes(malformed)
            with self.assertRaises(generate.NativeCloseFenceError):
                generate._load_bundles(root)

    def test_bundle_discovery_rejects_non_content_addressed_name(self) -> None:
        fixture = build_proof_fixture()
        bundle = next(iter(fixture.bundles.values()))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / f"{'0' * 64}.bin").write_bytes(bundle)
            with self.assertRaises(generate.NativeCloseFenceError) as raised:
                generate._load_bundles(root)
        self.assertEqual("BUNDLE_FILENAME", raised.exception.code)

    def test_public_registry_api_validates_complete_raw_bundle_mapping_first(self) -> None:
        fixture = build_proof_fixture()
        malformed = b"unreferenced-malformed-bundle"
        malformed_hash = hashlib.sha256(malformed).digest()
        with self.assertRaises(generate.NativeCloseFenceError) as malformed_error:
            generate.verify_registry(
                fixture.registry,
                {**fixture.bundles, malformed_hash: malformed},
            )
        self.assertEqual("DOMAIN", malformed_error.exception.code)

        with self.assertRaises(generate.NativeCloseFenceError) as key_error:
            generate.verify_registry(b"not-a-registry", {b"short": b"value"})
        self.assertEqual("BUNDLE_INVENTORY_KEY", key_error.exception.code)

        valid_bundle = next(iter(fixture.bundles.values()))
        with self.assertRaises(generate.NativeCloseFenceError) as address_error:
            generate.verify_registry(fixture.registry, {b"\x01" * 32: valid_bundle})
        self.assertEqual("BUNDLE_CONTENT_ADDRESS", address_error.exception.code)

    def test_manifest_mutations_are_rejected(self) -> None:
        variant = generate.make_build_variant("debug", False)
        bad_value = variant[:-1] + b"\x02"
        trailing = variant + b"\0"
        wrong_order = custom_manifest(
            "native-close-build-variant-v1",
            (("minified", b"\0"), ("build_type", b"debug")),
        )
        for label, candidate in (("value", bad_value), ("trailing", trailing), ("order", wrong_order)):
            with self.subTest(label=label), self.assertRaises(generate.NativeCloseFenceError):
                generate.parse_manifest(candidate, "native-close-build-variant-v1")

    def test_bundle_hash_and_reference_mutations_are_rejected(self) -> None:
        fixture = build_proof_fixture()
        bundle_bytes = next(iter(fixture.bundles.values()))
        bundle = generate.parse_manifest(bundle_bytes, "native-close-proof-bundle-v1")

        bad_hash_values = list(bundle.values)
        bad_hash_values[2] = bytes((bundle.values[2][0] ^ 1,)) + bundle.values[2][1:]
        with self.assertRaises(generate.NativeCloseFenceError):
            generate.verify_bundle(generate.encode_manifest(bundle.domain, tuple(bad_hash_values)))

        approval_raw, approval = generate._framed(
            bundle.values[5], "native-close-proof-approval-v1"
        )
        approval_values = list(approval.values)
        approval_values[1] = bytes((approval.values[1][0] ^ 1,)) + approval.values[1][1:]
        changed_approval = generate.encode_manifest(approval.domain, tuple(approval_values))
        reference_values = list(bundle.values)
        reference_values[5] = frame(changed_approval)
        reference_values[6] = hashlib.sha256(changed_approval).digest()
        with self.assertRaises(generate.NativeCloseFenceError) as raised:
            generate.verify_bundle(generate.encode_manifest(bundle.domain, tuple(reference_values)))
        self.assertEqual("APPROVAL_EVIDENCE_REFERENCE", raised.exception.code)
        self.assertNotEqual(approval_raw, changed_approval)

    def test_unsigned_stored_deflated_and_inert_signatures(self) -> None:
        archive = make_apk(
            (
                ("classes.dex", b"PK\x05\x06 embedded EOCD", 0),
                ("lib/arm64-v8a/libx.so", b"PK\x01\x02 central in deflate" * 3, 8),
            )
        )
        view = generate.parse_apk(archive)
        self.assertEqual(2, len(view.entries))
        self.assertFalse(view.signing_block_present)

    def test_invalid_raw_deflate_is_typed_fail_closed(self) -> None:
        archive = make_apk((("classes.dex", b"deflated-content" * 8, 8),))
        packed_size = struct.unpack_from("<I", archive, 18)[0]
        payload_start = 30 + len("classes.dex")
        malformed = (
            archive[:payload_start]
            + (b"\xff" * packed_size)
            + archive[payload_start + packed_size :]
        )
        with self.assertRaises(generate.NativeCloseFenceError) as raised:
            generate.parse_apk(malformed)
        self.assertEqual("ZIP_DEFLATE", raised.exception.code)

    def test_creator_aware_directory_attributes_and_slash_names(self) -> None:
        archive = make_apk()
        eocd = len(archive) - 22
        central = struct.unpack_from("<I", archive, eocd + 16)[0]
        dos_directory = mutate_u32(archive, central + 38, 0x10)
        with self.assertRaises(generate.NativeCloseFenceError) as dos_error:
            generate.parse_apk(dos_directory)
        self.assertEqual("ZIP_DIRECTORY_ATTRIBUTE", dos_error.exception.code)

        unix = mutate_u16(archive, central + 4, (3 << 8) | 20)
        unix_directory = mutate_u32(unix, central + 38, 0o040000 << 16)
        with self.assertRaises(generate.NativeCloseFenceError) as unix_error:
            generate.parse_apk(unix_directory)
        self.assertEqual("ZIP_DIRECTORY_ATTRIBUTE", unix_error.exception.code)

        unix_regular = mutate_u32(unix, central + 38, 0o100000 << 16)
        self.assertEqual(1, len(generate.parse_apk(unix_regular).entries))

        # A non-Unix creator's upper bits are host-specific opaque metadata, not Unix
        # mode.  The portable DOS directory bit remains the cross-creator signal.
        other_creator = mutate_u32(archive, central + 38, 0o040000 << 16)
        self.assertEqual(1, len(generate.parse_apk(other_creator).entries))

        with self.assertRaises(generate.NativeCloseFenceError) as slash_error:
            generate.parse_apk(make_apk((("directory/", b"x", 0),)))
        self.assertEqual("ZIP_NAME", slash_error.exception.code)

    def test_signed_archive_and_inert_signing_value(self) -> None:
        block = signing_block(((0x7109871A, b"PK\x05\x06PK\x01\x02 opaque"),))
        view = generate.parse_apk(make_apk(block=block))
        self.assertTrue(view.signing_block_present)

    def test_whole_file_zip_negatives(self) -> None:
        base = make_apk((("classes.dex", b"content", 0), ("classes2.dex", b"other", 8)))
        eocd = len(base) - 22
        central = struct.unpack_from("<I", base, eocd + 16)[0]
        second_central = central + 46 + len("classes.dex")
        first_payload = 30 + len("classes.dex")
        cases = {
            "comment": mutate_u16(base, eocd + 20, 1),
            "prefix": b"x" + base,
            "trailing": base + b"x",
            "truncated": base[:-1],
            "disk": mutate_u16(base, eocd + 4, 1),
            "count": mutate_u16(base, eocd + 8, 1),
            "sentinel": mutate_u32(base, eocd + 12, 0xFFFFFFFF),
            "central_extent": mutate_u32(base, eocd + 12, struct.unpack_from("<I", base, eocd + 12)[0] + 1),
            "central_signature": mutate_u32(base, central, 0x02014B51),
            "duplicate_local_offset": mutate_u32(base, second_central + 42, 0),
            "out_of_range_local": mutate_u32(base, central + 42, central),
            "central_extra": mutate_u16(base, central + 30, 1),
            "local_flags": mutate_u16(base, 6, 0x0800),
            "payload_crc": base[:first_payload] + bytes((base[first_payload] ^ 1,)) + base[first_payload + 1 :],
            "traversal": make_apk((("../x", b"x", 0),)),
            "nonzero_local_extra": make_apk(local_extra=b"\x01"),
        }
        changed_name = bytearray(base)
        changed_name[30] ^= 1
        cases["local_name"] = bytes(changed_name)
        for label, candidate in cases.items():
            with self.subTest(label=label), self.assertRaises(generate.NativeCloseFenceError):
                generate.parse_apk(candidate)

    def test_signing_block_negatives(self) -> None:
        valid_block = signing_block()
        valid = make_apk(block=valid_block)
        view = generate.parse_apk(valid)
        block_start = view.entries[-1].payload_end

        bad_magic = bytearray(valid)
        bad_magic[view.central_offset - 1] ^= 1
        unequal_size = bytearray(valid)
        unequal_size[block_start] ^= 1
        zero_id = bytearray(valid)
        zero_id[block_start + 16 : block_start + 20] = bytes(4)
        pair_underflow = bytearray(valid)
        pair_underflow[block_start + 8 : block_start + 16] = struct.pack("<Q", 3)
        pair_overflow = bytearray(valid)
        pair_overflow[block_start + 8 : block_start + 16] = struct.pack("<Q", 2**63)
        cases = {
            "short": make_apk(block=b"x"),
            "bad_magic": bytes(bad_magic),
            "unequal_size": bytes(unequal_size),
            "zero_id": bytes(zero_id),
            "duplicate_id": make_apk(block=signing_block(((7, b"a"), (7, b"b")))),
            "pair_underflow": bytes(pair_underflow),
            "pair_overflow": bytes(pair_overflow),
            "nonexact_pairs": make_apk(block=signing_block(tail=b"x")),
        }
        for label, candidate in cases.items():
            with self.subTest(label=label), self.assertRaises(generate.NativeCloseFenceError):
                generate.parse_apk(candidate)

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
                view = generate.parse_apk(apk.read_bytes())
                self.assertTrue(view.signing_block_present)
                self.assertGreater(len(view.entries), 1)

    def test_exact_zipflinger_alignment_padding_and_mutations(self) -> None:
        members = (("classes.dex", b"a", 0), ("classes2.dex", b"b", 0))
        padding = alignment_padding(257) + alignment_padding(11)
        view = generate.parse_apk(make_apk(members, gap_after_first=padding))
        self.assertEqual(2, len(view.entries))
        mutations = {
            "version": mutate_u16(padding, 4, 1),
            "time": mutate_u16(padding, 10, 0),
            "name": mutate_u16(padding, 26, 1),
            "payload": mutate_u32(padding, 18, 1),
            "nonzero_extra": padding[:30] + b"\x01" + padding[31:],
        }
        for label, mutated in mutations.items():
            with self.subTest(label=label), self.assertRaises(generate.NativeCloseFenceError):
                generate.parse_apk(make_apk(members, gap_after_first=mutated))


if __name__ == "__main__":
    unittest.main()
