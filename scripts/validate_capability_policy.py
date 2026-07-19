#!/usr/bin/env python3
"""Fail-closed consistency checks for the current Slice 1B policy authorities.

All governed repository I/O is delegated to ``capability_policy_snapshot``.  Once
that immutable snapshot returns, this module parses, hashes, and lints only captured
bytes and the captured review-directory names.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
import unicodedata

try:
    from scripts.capability_policy_markdown import (
        find_candidate_boundary_findings,
        markdown_without_html_comments,
        normalized_logical_blocks,
    )
    from scripts.capability_policy_snapshot import (
        SnapshotError,
        capture_git_commit_snapshot,
        capture_repository_snapshot,
    )
except ModuleNotFoundError:  # Direct ``python scripts/validate_...py`` execution.
    from capability_policy_markdown import (  # type: ignore[no-redef]
        find_candidate_boundary_findings,
        markdown_without_html_comments,
        normalized_logical_blocks,
    )
    from capability_policy_snapshot import (  # type: ignore[no-redef]
        SnapshotError,
        capture_git_commit_snapshot,
        capture_repository_snapshot,
    )


CURRENT_POLICY = "capability-v15"
CURRENT_JOURNAL = "RecoveryJournalV5"
CURRENT_JOURNAL_FILE = "docs/contracts/recovery-journal-v5.md"
AUTHORITY_MANIFEST_FILE = "docs/contracts/capability-policy-authority-v15.json"
AUTHORITY_SCHEMA = "capability-policy-authority-v1"
AUTHORITY_STATUS = "CANDIDATE_REREVIEW_REQUIRED"

HISTORICAL_V10_MANIFEST_FILE = "docs/contracts/capability-policy-authority-v10.json"
HISTORICAL_V10_MANIFEST_SHA256 = (
    "5009b65b1f9f6d541fffd7f3ac94d828de53ceb6d6e78de562734c40a1b6a3bd"
)
HISTORICAL_V11_MANIFEST_FILE = "docs/contracts/capability-policy-authority-v11.json"
HISTORICAL_V11_MANIFEST_SHA256 = (
    "7cfa899e4251991effa2b092118c588b0d22d3f784bf08d45c2f676d2b5363bd"
)
HISTORICAL_V12_MANIFEST_FILE = "docs/contracts/capability-policy-authority-v12.json"
HISTORICAL_V12_MANIFEST_SHA256 = (
    "458001ccc978e1c591090c2824538071248b3f890467a7ef87820e4a0fcc9bc6"
)
HISTORICAL_V13_MANIFEST_FILE = "docs/contracts/capability-policy-authority-v13.json"
HISTORICAL_V13_MANIFEST_SHA256 = (
    "d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff"
)
HISTORICAL_V14_MANIFEST_FILE = "docs/contracts/capability-policy-authority-v14.json"
HISTORICAL_V14_MANIFEST_SHA256 = (
    "a8afa6050138b16789e246f86c989a53ad12b4eb8a0fc9036c688c35f0417d51"
)
HISTORICAL_MANIFESTS = (
    (HISTORICAL_V10_MANIFEST_FILE, HISTORICAL_V10_MANIFEST_SHA256),
    (HISTORICAL_V11_MANIFEST_FILE, HISTORICAL_V11_MANIFEST_SHA256),
    (HISTORICAL_V12_MANIFEST_FILE, HISTORICAL_V12_MANIFEST_SHA256),
    (HISTORICAL_V13_MANIFEST_FILE, HISTORICAL_V13_MANIFEST_SHA256),
    (HISTORICAL_V14_MANIFEST_FILE, HISTORICAL_V14_MANIFEST_SHA256),
)

CURRENT_EVIDENCE_FILE = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v15-precommit-validation.md"
)
HISTORICAL_V10_EVIDENCE_FILE = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v10-precommit-validation.md"
)
HISTORICAL_V11_EVIDENCE_FILE = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v11-precommit-validation.md"
)
HISTORICAL_V12_EVIDENCE_FILE = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v12-precommit-validation.md"
)
HISTORICAL_V13_EVIDENCE_FILE = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v13-precommit-validation.md"
)
HISTORICAL_V14_EVIDENCE_FILE = (
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v14-precommit-validation.md"
)
FROZEN_HISTORICAL_EVIDENCE = (
    (
        HISTORICAL_V10_EVIDENCE_FILE,
        "4794ba28c909cec98cf8118c5cb4810b852fd15045484f51887b78f09a1a65c1",
    ),
    (
        HISTORICAL_V11_EVIDENCE_FILE,
        "c874a1395f5e82d995ce8fb22c697b2ed3898900d8a3f6f6874f0234293ec157",
    ),
    (
        HISTORICAL_V12_EVIDENCE_FILE,
        "8f103489b2cf0ad654b05e577bb00c46ba6fe566c5f7abe93291e15ce8aab068",
    ),
    (
        HISTORICAL_V13_EVIDENCE_FILE,
        "71db385c245f31ae00809160981cfd25fdf4f0b65f5bd07bf4b92ad79a2a76c3",
    ),
    (
        HISTORICAL_V14_EVIDENCE_FILE,
        "b99eb19acb807a0be9d1d125f631d07d316a55afb847032f67351a1b9c02b016",
    ),
)
HISTORICAL_EVIDENCE_FILES = tuple(path for path, _sha256 in FROZEN_HISTORICAL_EVIDENCE)
FROZEN_HISTORICAL_AUTHORIZATION = (
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

CURRENT_AUTHORITY_FILES = (
    "docs/adr/ADR-011-measured-capability-probe-policy.md",
    "docs/contracts/capability-store-v4.md",
    "docs/contracts/native-close-fence-proof-v1.md",
    "docs/contracts/recovery-journal-v5.md",
    "docs/execution/slice-1b-contract.md",
)
EXPECTED_REVIEW_FILES = tuple(
    f"docs/reviews/slice-1b-policy-review-{number:02d}.md" for number in range(1, 15)
)
FROZEN_REJECTED_REVIEWS = (
    (EXPECTED_REVIEW_FILES[0], "a82d40a84976f86a542d4fb16e785ea9ba798bc2cf07649200dd006aa9a01de3"),
    (EXPECTED_REVIEW_FILES[1], "ee2b845afe1e2f5a839814c5430b9de5f690554864a5a030292e506616928b21"),
    (EXPECTED_REVIEW_FILES[2], "1612d29e457b6b6fd446bb90a036140ace886c378fa853ee558b7a1a3e5e4e27"),
    (EXPECTED_REVIEW_FILES[3], "05d48ec0a2331a0a35c3b0259640778392750d8fb4b99e354df257df8db84312"),
    (EXPECTED_REVIEW_FILES[4], "d3da0283f7a6659d8ee0d810a4ffacbb2393129e74dcd6bb835179181228e67b"),
    (EXPECTED_REVIEW_FILES[5], "af162e7757b0195a67bb045054579f507682a9ce319e114281d03300d098cbc8"),
    (EXPECTED_REVIEW_FILES[6], "04e24b2f42e1d485dd40e58e6f4863993dbc6dcb912513313778d250aeaca3d2"),
    (EXPECTED_REVIEW_FILES[7], "55e29089dc5cebc595b455442baca241de9f8dc3995a0a6ada44d0b87bacdd5a"),
    (EXPECTED_REVIEW_FILES[8], "6b98f41d145921ae1d684e81c342dc1250a476f5bf613163338b89ad1af016b2"),
    (EXPECTED_REVIEW_FILES[9], "da50f6a04e7ce96f8488f62743ec8d6f217a4e75ec0c06468009407b7adf4383"),
    (EXPECTED_REVIEW_FILES[10], "d6476b1207c853c183a67e728d7c2e3e499e7741defbc8ebc50dc9afd2bfda37"),
    (EXPECTED_REVIEW_FILES[11], "0dd13653649093074f1e8e0757fd9aee755d7f75b105dac3292475ad0e8bf726"),
    (EXPECTED_REVIEW_FILES[12], "4de103affeb31156ce235efaca96d1076ef171dc9c73c4a6368d1d4d45138f30"),
    (EXPECTED_REVIEW_FILES[13], "75e4910a1b37700e78e5013c6e61d0184f7394c036ea30b9c2211777f3b92e8d"),
)

# This is deliberately explicit and independent of token maps and manifest content.
# Any new filesystem input must first become a reviewed change to this exact boundary.
GOVERNED_INPUTS = (
    "docs/adr/ADR-011-measured-capability-probe-policy.md",
    "docs/contracts/capability-policy-authority-v10.json",
    "docs/contracts/capability-policy-authority-v11.json",
    "docs/contracts/capability-policy-authority-v12.json",
    "docs/contracts/capability-policy-authority-v13.json",
    "docs/contracts/capability-policy-authority-v14.json",
    "docs/contracts/capability-policy-authority-v15.json",
    "docs/contracts/capability-policy-authorization-v14.json",
    "docs/contracts/capability-store-v3.md",
    "docs/contracts/capability-store-v4.md",
    "docs/contracts/native-close-fence-proof-v1.md",
    "docs/contracts/recovery-journal-v5.md",
    "docs/evidence/EVIDENCE_INDEX.md",
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v10-precommit-validation.md",
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v11-precommit-validation.md",
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v12-precommit-validation.md",
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v13-precommit-validation.md",
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v14-precommit-validation.md",
    "docs/evidence/runs/2026-07-15-slice-1b-policy/v15-precommit-validation.md",
    "docs/execution/slice-1b-contract.md",
    "docs/execution/slice-status.md",
    "docs/reviews/slice-1b-policy-review-01.md",
    "docs/reviews/slice-1b-policy-review-02.md",
    "docs/reviews/slice-1b-policy-review-03.md",
    "docs/reviews/slice-1b-policy-review-04.md",
    "docs/reviews/slice-1b-policy-review-05.md",
    "docs/reviews/slice-1b-policy-review-06.md",
    "docs/reviews/slice-1b-policy-review-07.md",
    "docs/reviews/slice-1b-policy-review-08.md",
    "docs/reviews/slice-1b-policy-review-09.md",
    "docs/reviews/slice-1b-policy-review-10.md",
    "docs/reviews/slice-1b-policy-review-11.md",
    "docs/reviews/slice-1b-policy-review-12.md",
    "docs/reviews/slice-1b-policy-review-13.md",
    "docs/reviews/slice-1b-policy-review-14.md",
    "docs/reviews/slice-1b-policy-v14-exact-review-01.json",
    "docs/reviews/slice-1b-policy-v14-exact-review-02.json",
    "docs/reviews/slice-1b-policy-v14-exact-review-03.json",
)
REVIEW_DIRECTORY = "docs/reviews"
FORBIDDEN_GOVERNED_PATHS = (
    "docs/contracts/capability-policy-authorization-v15.json",
    "docs/contracts/recovery-journal-v4.md",
    "docs/reviews/slice-1b-policy-v15-exact-review-01.json",
    "docs/reviews/slice-1b-policy-v15-exact-review-02.json",
    "docs/reviews/slice-1b-policy-v15-exact-review-03.json",
)

REQUIRED_FIRST_LINES: dict[str, str] = {
    "docs/adr/ADR-011-measured-capability-probe-policy.md": (
        "# ADR-011: Measured capability probe and quality-tier policy"
    ),
    "docs/contracts/capability-store-v3.md": "# CapabilityModeStoreV3 rejected contract",
    "docs/contracts/capability-store-v4.md": "# CapabilityModeStoreV4 normative schema",
    "docs/contracts/recovery-journal-v5.md": "# RecoveryJournalV5 normative contract",
    "docs/contracts/native-close-fence-proof-v1.md": (
        "# NativeCloseFenceProofV1 normative contract"
    ),
    "docs/execution/slice-1b-contract.md": "# Slice 1B execution contract",
    "docs/reviews/slice-1b-policy-review-14.md": (
        "# Slice 1B capability policy review 14"
    ),
}

REQUIRED_EXACT_LINES: dict[str, tuple[str, ...]] = {
    "docs/adr/ADR-011-measured-capability-probe-policy.md": (
        "- Policy revision: `capability-v15`",
    ),
}

REQUIRED_LINE_POSITIONS: dict[str, dict[int, str]] = {
    "docs/adr/ADR-011-measured-capability-probe-policy.md": {
        6: "- Policy revision: `capability-v15`",
    },
}

REQUIRED_TOKENS: dict[str, tuple[str, ...]] = {
    "docs/adr/ADR-011-measured-capability-probe-policy.md": (
        "- Policy revision: `capability-v15`",
        "`WorkloadBuildManifestV3`",
        "`workload-build-manifest-v3`",
        "`probe-workload-v3`",
        "`serial-probe-v5`",
        "`runtime-journal-v5`",
        "`capability-mode-store-payload-v4`",
        "`RecoveryJournalV5`",
        "`docs/contracts/recovery-journal-v5.md`",
        "`docs/contracts/native-close-fence-proof-v1.md`",
        "`capability_recovery_v5/",
        "`PARTIAL/DELIVERY_FENCE_UNPROVEN`",
        "approval BundleV1 carrying",
        "exact-EOF",
        "other-variant rows stay inert",
        "`docs/contracts/capability-policy-authorization-v15.json`",
        "`implementation_authorized=false`",
        "APPROVE_NO_ACTIONABLE_FINDINGS",
        "authorization commit",
        "formal same-byte gate",
        "immutable Git objects",
        "replacement objects disabled",
        "eight gate implementation/test paths",
        "`scripts/validate_capability_policy_authorization.py`",
        "including rejected review records",
        "Windows trailing-space/dot alias",
        "function only of explicit immutable `C`, `H`",
        "does not read `HEAD`, the index, the worktree",
    ),
    "docs/contracts/capability-store-v4.md": (
        "ADR-011 `capability-v15`",
        "RecoveryJournalV5",
        "PARTIAL/DELIVERY_FENCE_UNPROVEN",
        "`<mode-file>.tmp`",
        "ModeStoreDirectoryBootstrapV1",
        "ModeStoreDirectoryIdentityV1",
        "createParentDirectories(file)",
        "ModeStoreDiskResultV1",
        "one separate one-byte read must return exactly zero",
    ),
    "docs/contracts/recovery-journal-v5.md": (
        "# RecoveryJournalV5 normative contract",
        "ADR-011 `capability-v15`",
        "`recovery-journal-payload-v5`",
        "`fieldCount=9`",
        "## ActiveV5",
        "## ManualRetryContextV1",
        "## PendingStoreCommitV5",
        "`pending-store-commit-v5`",
        "capability_recovery_v5/",
        "CheckedAtomicReplaceV1",
        "ModeStoreDiskVerifierV1",
        "JournalDirectoryBootstrapV1",
        "JournalDiskResultV1",
        "Files.newDirectoryStream(selectedModePath)",
    ),
    "docs/contracts/native-close-fence-proof-v1.md": (
        "ADR-011 `capability-v15`",
        "native-close-fence-registry-v1",
        "NativeCloseProofBasisV1",
        "NativeClosePolicySetV1",
        "NativeCloseCodeImageV1",
        "NativeCloseProofEvidenceV1",
        "NativeCloseProofApprovalV1",
        "NativeCloseAuthorizationSnapshotV1",
        "docs/evidence/native-close-fence-v1/bundles/",
        "`fieldCount=9`",
        "`producer_id`",
        "`package_output_name`",
        "`jni_entry_name`",
        "BUILD_DAG_AND_PHASE_A_PROVENANCE",
        "UTF-8 bit 11 may be zero or one",
        "exactly `0x0000` or `0x0800`",
        "bit 3 data descriptors",
        "authorization_rows",
        "Only after steps 1-4 complete",
        "zero represented sets is unproved",
        "entry fields 1/2/3/4/5 equal EvidenceV1",
        "`runtime_generation`",
        "`NATIVE_CLOSED`",
        "can never return to IDENTITY_VERIFIED",
        "installed_jni_set_sha256",
        "RUNTIME_CALLBACK_FENCE_UNPROVEN",
        "`eocdOffset = fileLength - 22`",
        "`APK Sig Block 42`",
        "`fieldCount=7`",
        "`proof_basis`: `uint32_be(length) ||` exact canonical NativeCloseProofBasisV1",
        "build variants remain inert registry data",
    ),
    "docs/contracts/capability-store-v3.md": (
        "`capability-v15`",
        "`docs/contracts/native-close-fence-proof-v1.md`",
        "`docs/contracts/recovery-journal-v5.md`",
        "`docs/execution/slice-1b-contract.md`",
    ),
    "docs/execution/slice-1b-contract.md": (
        "ADR-011 `capability-v15`",
        "Rejected revisions v1-v14",
        "`docs/contracts/recovery-journal-v5.md`",
        "`docs/contracts/native-close-fence-proof-v1.md`",
        "records 01-14",
        "v1-v14 findings have total v15 rules",
        "fourteen rejected records",
        "complete v15 fixtures",
        "`docs/contracts/capability-policy-authority-v15.json`",
        "`implementation_authorized=false`",
        "BundleV1-carried ProofBasis bytes/hash",
        "exact-EOF/comment-zero/single-disk EOCD",
        "six physical persistence files",
        "## Authorization transition contract",
        "schema=capability-policy-authorization-v1",
        "`ALL_THREE_APPROVE_ZERO_FINDINGS_DISTINCT_REVIEWER_SESSION_AND_LANE`",
        "authorization-only commit",
        "worktree validation is a diagnostic only",
        "immutable Git commit/tree objects",
        "eight entries in `C`, `A`, and the validated `H` tree",
        "--stage authorization --candidate-commit $C",
        "manifest-bound rejected reviews 01-14",
        "Windows trailing-space/dot alias",
        "--head-commit $H",
        "It never reads symbolic `HEAD`, the index,",
        "separate operational check",
        "throw 'dirty checkout'",
        "throw 'rollback tag mismatch'",
    ),
    "docs/execution/slice-status.md": (
        "`capability-v15`",
        "1B GATE REPAIR",
        "RecoveryJournalV5",
        "Review 14 records P0 0/P1 1/P2 0",
        "v14 candidate/authorization objects remain immutable",
        "formal validation rejected the first implementation chain",
        "runtime-neutral v15 gate repair",
        "fresh exact reviews",
        "authorization are required before implementation resumes",
    ),
    "docs/evidence/EVIDENCE_INDEX.md": (
        "`capability-v15`",
        "Review 14 records P0 0/P1 1/P2 0",
        "v14 candidate",
        "authorization",
        "fails formal descendant authorization",
        "runtime-neutral `capability-v15` repair",
        "freeze dual-stage test behavior before `C`",
        "bind v14 authorization/reviews as historical bytes",
    ),
    CURRENT_EVIDENCE_FILE: (
        "runtime-neutral `capability-v15` gate repair",
        "It is not an acceptance record,",
        "`status=CANDIDATE_REREVIEW_REQUIRED`",
        "`implementation_authorized=false`",
        "reviews 01-14",
        "## Pre-C validation ledger",
        "## Post-C immutable gates",
        "structurally PENDING",
    ),
    HISTORICAL_V10_EVIDENCE_FILE: (
        "`capability-v10` candidate",
        "It is not an acceptance record,",
        "`status=CANDIDATE_REREVIEW_REQUIRED`",
        "`implementation_authorized=false`",
        "exact-commit review remains required",
    ),
    HISTORICAL_V11_EVIDENCE_FILE: (
        "records the uncommitted `capability-v11` remediation",
        "current-byte audits rejected v11 before commit",
        "It is not an acceptance record,",
        "`implementation_authorized=false`",
        "v11 was never committed",
    ),
    HISTORICAL_V12_EVIDENCE_FILE: (
        "records the uncommitted `capability-v12` remediation",
        "Three fresh current-byte audits rejected v12",
        "It is not an acceptance record,",
        "`implementation_authorized=false`",
        "never committed and must not receive",
    ),
    HISTORICAL_V13_EVIDENCE_FILE: (
        "**REJECT / REWORK.**",
        "P0=0, P1=5, P2=8",
        "v13 was never committed",
        "`implementation_authorized=false`",
        "`capability-v13` is permanently rejected evidence",
    ),
    "docs/reviews/slice-1b-policy-review-14.md": (
        "**REJECT / REWORK.**",
        "`capability-v14` policy bytes and their authorization record",
        "P0: 0",
        "P1: 1",
        "P2: 0",
        "candidate and authorization stages require incompatible frozen tests",
        "implementation authorization is blocked",
    ),
}

FORBIDDEN_TOKENS: dict[str, tuple[str, ...]] = {
    "docs/adr/ADR-011-measured-capability-probe-policy.md": (
        *(f"- Policy revision: `capability-v{number}`" for number in range(1, 15)),
        "`runtime-journal-v4`",
        "`docs/contracts/recovery-journal-v4.md`",
        "`capability_recovery_v4/",
    ),
    "docs/contracts/capability-store-v4.md": (
        "ADR-011 `capability-v14`",
        "pending RecoveryJournalV4",
    ),
    "docs/contracts/capability-store-v3.md": (
        "`capability-v14`",
        "`docs/contracts/recovery-journal-v4.md` are the only current",
    ),
    "docs/execution/slice-1b-contract.md": (
        "ADR-011 `capability-v14`",
        "records 01-06",
        "records 01-09",
        "records 01-10",
        "records 01-11",
        "records 01-12",
        "records 01-13",
        "v1-v7 findings have total v8 rules",
        "v1-v9 findings have total v10 rules",
        "v1-v10 findings have total v11 rules",
        "v1-v11 findings have total v12 rules",
        "v1-v12 findings have total v13 rules",
        "v1-v13 findings have total v14 rules",
        "six rejected records",
        "nine rejected records",
        "ten rejected records",
        "eleven rejected records",
        "twelve rejected records",
        "thirteen rejected records",
        "complete v8 fixtures",
        "complete v10 fixtures",
        "complete v11 fixtures",
        "complete v12 fixtures",
        "complete v13 fixtures",
        "complete v14 fixtures",
        "capability-policy-authority-v14.json",
        "recovery-journal-v4.md",
        "RecoveryJournalV4",
        "ActiveV4",
    ),
}

CANDIDATE_BOUNDARY_FILES = frozenset(
    (
        *CURRENT_AUTHORITY_FILES,
        "docs/contracts/capability-store-v3.md",
        "docs/execution/slice-status.md",
        "docs/evidence/EVIDENCE_INDEX.md",
        CURRENT_EVIDENCE_FILE,
    )
)

UNSAFE_POST_CLOSE_PATTERN = re.compile(
    r"(?i)\bpost[- ](?:close|seal)\b.{0,120}\b"
    r"(?:ignore|ignored|serve(?:s|d|ing)?\s+(?:the\s+)?cache|cache\s+(?:is\s+)?served)\b"
)


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _validate_historical_manifests(errors: list[str], read_file) -> None:
    for relative, expected_hash in HISTORICAL_MANIFESTS:
        actual_hash = _sha256_bytes(read_file(relative))
        if actual_hash != expected_hash:
            errors.append(
                f"historical authority manifest hash mismatch for {relative}: "
                f"expected={expected_hash} actual={actual_hash}"
            )


def _validate_frozen_rejected_evidence(errors: list[str], read_file) -> None:
    for relative, expected_hash in FROZEN_HISTORICAL_EVIDENCE:
        actual_hash = _sha256_bytes(read_file(relative))
        if actual_hash != expected_hash:
            errors.append(
                f"historical rejection ledger hash mismatch for {relative}: "
                f"expected={expected_hash} actual={actual_hash}"
            )
    for relative, expected_hash in FROZEN_REJECTED_REVIEWS:
        actual_hash = _sha256_bytes(read_file(relative))
        if actual_hash != expected_hash:
            errors.append(
                f"frozen rejected review hash mismatch for {relative}: "
                f"expected={expected_hash} actual={actual_hash}"
            )


def _validate_frozen_historical_authorization(errors: list[str], read_file) -> None:
    for relative, expected_hash in FROZEN_HISTORICAL_AUTHORIZATION:
        actual_hash = _sha256_bytes(read_file(relative))
        if actual_hash != expected_hash:
            errors.append(
                f"historical authorization evidence hash mismatch for {relative}: "
                f"expected={expected_hash} actual={actual_hash}"
            )


def _validate_current_evidence_linkage(errors: list[str], read_file) -> None:
    relative = CURRENT_EVIDENCE_FILE
    try:
        text = read_file(relative).decode("utf-8")
    except UnicodeError as exc:
        errors.append(f"cannot decode current evidence linkage table: {exc}")
        return

    blocks = [block for _start, _end, block in normalized_logical_blocks(text)]
    if blocks.count("Current authority SHA-256") != 1:
        errors.append("current evidence must contain one visible authority-hash heading")

    for governed_path in (*CURRENT_AUTHORITY_FILES, AUTHORITY_MANIFEST_FILE):
        expected = f"{governed_path} {_sha256_bytes(read_file(governed_path))}"
        path_rows = [block for block in blocks if block.startswith(f"{governed_path} ")]
        if path_rows != [expected]:
            errors.append(
                f"current evidence authority linkage mismatch for {governed_path}: "
                f"expected={[expected]!r} actual={path_rows!r}"
            )


def _validate_authority_manifest(errors: list[str], read_file) -> None:
    raw = read_file(AUTHORITY_MANIFEST_FILE)
    try:
        if len(raw) > 131_072:
            raise ValueError(f"authority manifest too large: {len(raw)}")
        text = raw.decode("utf-8")
        payload = json.loads(text, object_pairs_hook=_reject_duplicate_json_keys)
    except (RecursionError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        errors.append(f"cannot parse authority manifest: {exc}")
        return
    if not isinstance(payload, dict):
        errors.append("authority manifest root must be an object")
        return

    try:
        canonical = json.dumps(
            payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8") + b"\n"
    except (RecursionError, TypeError, UnicodeError, ValueError) as exc:
        errors.append(f"cannot canonicalize authority manifest: {exc}")
        return
    if raw != canonical:
        errors.append("authority manifest bytes are not canonical sorted compact JSON")

    expected_keys = {
        "authorities",
        "candidate_revision",
        "implementation_authorized",
        "rejected_revisions",
        "schema",
        "status",
    }
    if set(payload) != expected_keys:
        errors.append(
            "authority manifest top-level fields mismatch: "
            f"expected={sorted(expected_keys)} actual={sorted(payload)}"
        )
    if payload.get("schema") != AUTHORITY_SCHEMA:
        errors.append("authority manifest schema mismatch")
    if payload.get("candidate_revision") != CURRENT_POLICY:
        errors.append("authority manifest candidate revision mismatch")
    if payload.get("status") != AUTHORITY_STATUS:
        errors.append("authority manifest must remain candidate rereview required")
    if payload.get("implementation_authorized") is not False:
        errors.append("authority manifest must not authorize implementation before rereview")

    authorities = payload.get("authorities")
    if not isinstance(authorities, list) or len(authorities) != len(CURRENT_AUTHORITY_FILES):
        errors.append("authority manifest current-authority list shape mismatch")
    else:
        for expected_path, row in zip(CURRENT_AUTHORITY_FILES, authorities, strict=True):
            authority_raw = read_file(expected_path)
            if not isinstance(row, dict) or set(row) != {"path", "revision", "sha256"}:
                errors.append(f"authority manifest row malformed for {expected_path}")
                continue
            if row.get("path") != expected_path or row.get("revision") != CURRENT_POLICY:
                errors.append(f"authority manifest row identity mismatch for {expected_path}")
            expected_hash = row.get("sha256")
            if not isinstance(expected_hash, str) or re.fullmatch(r"[a-f0-9]{64}", expected_hash) is None:
                errors.append(f"authority manifest hash malformed for {expected_path}")
            elif _sha256_bytes(authority_raw) != expected_hash:
                errors.append(
                    f"authority file hash mismatch for {expected_path}: "
                    f"expected={expected_hash} actual={_sha256_bytes(authority_raw)}"
                )

    rejected = payload.get("rejected_revisions")
    if not isinstance(rejected, list) or len(rejected) != len(EXPECTED_REVIEW_FILES):
        errors.append("authority manifest rejected-revision list shape mismatch")
    else:
        for number, ((expected_path, frozen_hash), row) in enumerate(
            zip(FROZEN_REJECTED_REVIEWS, rejected, strict=True), start=1
        ):
            review_raw = read_file(expected_path)
            expected_revision = f"capability-v{number}"
            if not isinstance(row, dict) or set(row) != {"review", "review_sha256", "revision"}:
                errors.append(f"rejected-revision row malformed for {expected_revision}")
                continue
            if row.get("review") != expected_path or row.get("revision") != expected_revision:
                errors.append(f"rejected-revision row identity mismatch for {expected_revision}")
            expected_hash = row.get("review_sha256")
            if not isinstance(expected_hash, str) or re.fullmatch(r"[a-f0-9]{64}", expected_hash) is None:
                errors.append(f"review hash malformed for {expected_revision}")
            elif expected_hash != frozen_hash:
                errors.append(f"rejected review manifest hash is not frozen: {expected_path}")
            elif _sha256_bytes(review_raw) != frozen_hash:
                errors.append(f"rejected review hash mismatch: {expected_path}")


@dataclass(frozen=True)
class CapabilityPolicyResult:
    errors: tuple[str, ...]
    source: str = "worktree"
    commit: str | None = None
    tree: str | None = None

    @property
    def ok(self) -> bool:
        return not self.errors


def validate_capability_policy(
    root: Path, *, commit: str | None = None
) -> CapabilityPolicyResult:
    """Validate either a diagnostic worktree snapshot or an immutable commit.

    The default worktree mode is useful before candidate creation, but it is not an
    authorization gate because a mutable namespace can change after any observation.
    Passing ``commit`` selects the formal, Git-object-backed exact-candidate gate.
    """

    errors: list[str] = []
    requested_source = "git-commit" if commit is not None else "worktree"
    try:
        if commit is None:
            snapshot = capture_repository_snapshot(
                root.absolute(),
                GOVERNED_INPUTS,
                REVIEW_DIRECTORY,
                FORBIDDEN_GOVERNED_PATHS,
            )
        else:
            snapshot = capture_git_commit_snapshot(
                root.absolute(),
                commit,
                GOVERNED_INPUTS,
                REVIEW_DIRECTORY,
                FORBIDDEN_GOVERNED_PATHS,
            )
    except SnapshotError as exc:
        return CapabilityPolicyResult(
            (f"cannot capture governed repository snapshot: {exc}",),
            source=requested_source,
            commit=commit,
        )

    try:
        local_files = dict(snapshot.files)
        file_keys = tuple(local_files)
        governed_inputs = tuple(snapshot.governed_inputs)
        review_names = tuple(snapshot.review_names)
        snapshot_source = snapshot.source
        snapshot_commit = snapshot.commit
        snapshot_tree = snapshot.tree
        malformed_values = [key for key, value in local_files.items() if type(value) is not bytes]
    except Exception as exc:
        return CapabilityPolicyResult(
            (f"malformed governed repository snapshot: {exc}",),
            source=requested_source,
            commit=commit,
        )

    expected_inputs = set(GOVERNED_INPUTS)
    if any(type(key) is not str for key in file_keys):
        errors.append("snapshot file keys must all be strings")
    elif len(file_keys) != len(set(file_keys)) or set(file_keys) != expected_inputs:
        errors.append(
            "snapshot file-key inventory mismatch: "
            f"expected={sorted(expected_inputs)} actual={sorted(map(repr, file_keys))}"
        )
    if malformed_values:
        errors.append(f"snapshot file values are not bytes: {sorted(map(repr, malformed_values))}")
    if any(type(name) is not str for name in governed_inputs):
        errors.append("snapshot governed inputs must all be strings")
    elif governed_inputs != tuple(sorted(GOVERNED_INPUTS)):
        errors.append("snapshot governed-input inventory mismatch")
    if any(type(name) is not str for name in review_names):
        errors.append("snapshot review names must all be strings")
    elif review_names != tuple(sorted(review_names)) or len(review_names) != len(
        {unicodedata.normalize("NFKC", name).casefold() for name in review_names}
    ):
        errors.append("snapshot review names are not sorted and NFKC-casefold-unique")
    if commit is None:
        if snapshot_source != "worktree" or snapshot_commit is not None or snapshot_tree is not None:
            errors.append("worktree snapshot source metadata mismatch")
    elif (
        snapshot_source != "git-commit"
        or snapshot_commit != commit
        or not isinstance(snapshot_tree, str)
        or re.fullmatch(r"[a-f0-9]{40}", snapshot_tree) is None
    ):
        errors.append("Git commit snapshot source metadata mismatch")
    if errors:
        return CapabilityPolicyResult(
            tuple(errors),
            source=requested_source,
            commit=snapshot_commit if isinstance(snapshot_commit, str) else commit,
            tree=snapshot_tree if isinstance(snapshot_tree, str) else None,
        )

    immutable_files = MappingProxyType(local_files)

    consumed: set[str] = set()

    def read_file(relative: str) -> bytes:
        consumed.add(relative)
        return immutable_files[relative]

    _validate_historical_manifests(errors, read_file)
    _validate_frozen_rejected_evidence(errors, read_file)
    _validate_frozen_historical_authorization(errors, read_file)
    _validate_authority_manifest(errors, read_file)
    _validate_current_evidence_linkage(errors, read_file)

    for relative, tokens in REQUIRED_TOKENS.items():
        raw = read_file(relative)
        try:
            text = raw.decode("utf-8")
        except UnicodeError as exc:
            errors.append(f"cannot decode capability-policy file {relative}: {exc}")
            continue
        source_without_comments = markdown_without_html_comments(text)
        for token in tokens:
            if token not in source_without_comments:
                errors.append(f"current capability-policy token missing in {relative}: {token}")

        if relative in CANDIDATE_BOUNDARY_FILES:
            for finding in find_candidate_boundary_findings(
                text, current_policy=CURRENT_POLICY
            ):
                if finding.category.endswith("stale-governance"):
                    label = "legacy authority declared current"
                else:
                    label = "candidate document contradicts implementation stop gate"
                errors.append(
                    f"{label} (defense in depth) in {relative}:"
                    f"{finding.line_start}-{finding.line_end}: {finding.excerpt!r}"
                )

        if relative in CURRENT_AUTHORITY_FILES:
            for line_start, line_end, block in normalized_logical_blocks(text):
                match = UNSAFE_POST_CLOSE_PATTERN.search(block)
                if match is not None:
                    errors.append(
                        f"unsafe post-close/cache directive in {relative}:"
                        f"{line_start}-{line_end}: {match.group(0)!r}"
                    )

        lines = text.splitlines()
        first_line = REQUIRED_FIRST_LINES.get(relative)
        if first_line is not None:
            actual_first = lines[0] if lines else ""
            if actual_first != first_line:
                errors.append(
                    f"capability-policy authority header mismatch in {relative}: "
                    f"expected {first_line!r}, got {actual_first!r}"
                )
        for exact_line in REQUIRED_EXACT_LINES.get(relative, ()):
            count = lines.count(exact_line)
            if count != 1:
                errors.append(
                    f"capability-policy exact line count mismatch in {relative}: "
                    f"{exact_line!r} count={count}"
                )
        for line_number, exact_line in REQUIRED_LINE_POSITIONS.get(relative, {}).items():
            actual = lines[line_number - 1] if len(lines) >= line_number else ""
            if actual != exact_line:
                errors.append(
                    f"capability-policy exact line mismatch in {relative}:{line_number}: "
                    f"expected {exact_line!r}, got {actual!r}"
                )
        for token in FORBIDDEN_TOKENS.get(relative, ()):
            if token in text:
                errors.append(f"stale capability-policy token in {relative}: {token}")

    expected_review_names = {relative.rsplit("/", 1)[-1] for relative in EXPECTED_REVIEW_FILES}
    present_review_names = {
        name
        for name in review_names
        if unicodedata.normalize("NFKC", name).casefold().startswith(
            "slice-1b-policy-review-"
        )
        and unicodedata.normalize("NFKC", name).casefold().endswith(".md")
    }
    for name in sorted(expected_review_names - present_review_names):
        errors.append(f"required rejected-review record missing: docs/reviews/{name}")
    for name in sorted(present_review_names - expected_review_names):
        errors.append(f"unexpected unbound capability-policy review: docs/reviews/{name}")

    unconsumed = set(GOVERNED_INPUTS) - consumed
    unexpected_consumed = consumed - set(GOVERNED_INPUTS)
    if unconsumed or unexpected_consumed:
        errors.append(
            "governed captured-byte consumption mismatch: "
            f"unconsumed={sorted(unconsumed)} unexpected={sorted(unexpected_consumed)}"
        )
    return CapabilityPolicyResult(
        tuple(errors),
        source=snapshot_source,
        commit=snapshot_commit,
        tree=snapshot_tree,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--commit",
        help="validate the exact immutable lowercase 40-hex candidate commit",
    )
    args = parser.parse_args(argv)
    result = validate_capability_policy(args.root, commit=args.commit)
    if result.ok:
        if args.commit is None:
            print(
                f"CAPABILITY_POLICY_WORKTREE_DIAGNOSTIC=PASS candidate={CURRENT_POLICY} "
                f"status={AUTHORITY_STATUS} journal={CURRENT_JOURNAL} "
                f"authorities=5 reviews={len(EXPECTED_REVIEW_FILES)} "
                "implementationAuthorized=false "
                "formalGate=false"
            )
        else:
            print(
                f"CAPABILITY_POLICY_EXACT_COMMIT_GATE=PASS candidate={CURRENT_POLICY} "
                f"commit={result.commit} tree={result.tree} status={AUTHORITY_STATUS} "
                f"journal={CURRENT_JOURNAL} authorities=5 "
                f"reviews={len(EXPECTED_REVIEW_FILES)} "
                "implementationAuthorized=false formalGate=true"
            )
        return 0
    for error in result.errors:
        print(f"ERROR {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
