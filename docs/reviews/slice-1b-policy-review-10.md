# Slice 1B capability policy review 10

## Verdict and reviewed snapshot

**REJECT / REWORK.** Three independent read-only passes reviewed exact commit
`04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
`9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`) and rejected its `capability-v10`
candidate with no P0, four deduplicated P1, and one deduplicated P2 finding. The
branch and worktree were clean at the start and end of every pass. The reviewed
authority bytes were:

| Document | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `36C22DB812FF6124B745563DD71E7367B53C6E913DCBB7B53D6E6C4CBBC650B5` |
| `docs/contracts/capability-store-v4.md` | `4FE2ACE36DE6D76A58BC7F7E7F7911606D3A0D4AE74C873E5637AA377773577E` |
| `docs/contracts/native-close-fence-proof-v1.md` | `104BBDD92E251047BEC31BA40320CDAE7D9AF1B7FB2DB3937973C6B9548B37DC` |
| `docs/contracts/recovery-journal-v5.md` | `875B8CBE52CD7D63DA6AB1DEDD4612871513553DA81489ADD4798CFA33471175` |
| `docs/execution/slice-1b-contract.md` | `62F8AA609BFD85321BE2F6E6B95820563B9ED7F9F94FA2CB23DA1BF45450A557` |

The canonical candidate authority manifest remained SHA-256
`5009B65B1F9F6D541FFFD7F3AC94D828DE53CEB6D6E78DE562734C40A1B6A3BD`,
`status=CANDIDATE_REREVIEW_REQUIRED`, and `implementation_authorized=false`.
No Slice 1B implementation, physical-device behavior, G2/G8 result, human QA result,
or release approval was credited.

## Findings and required v11 resolution

| Priority | v10 defect and counterexample | Required `capability-v11` resolution |
| --- | --- | --- |
| P1 | **Gradle-supported alternate range delimiters bypass the exact-version gate.** `VERSION_RANGE_RE` recognizes conventional `[1.0,2.0)` and `(1.0,2.0]` but `_is_dynamic_token` returns false for Gradle-supported `[1.0, 2.0[` and `]1.0, 2.0]`. A temporary exact-tree mutation of `libs.versions.toml` to the first form passed the repository supply-chain validator. The catalog is decoded but intentionally not hash-pinned, so this is a source-policy bypass. | Reject every Gradle range delimiter form, including alternative exclusive lower `]` and exclusive upper `[`, conventional/open bounds, and compound comma-bearing ranges. Add direct classifier and decoded full-tree TOML fixtures for every form. Official syntax evidence: [Gradle rich versions](https://docs.gradle.org/current/userguide/rich_versions.html). |
| P1 | **Rollback deletes permanent recovery evidence.** The execution contract tells rollback to delete profile/journal bytes, while RecoveryJournalV5 says an old artifact shard is never deleted, `RETRY_CONSUMED` is absorbing for the artifact lifetime, and POST_SEAL poison has no same-artifact reset. Artifact A can record a tombstone/poison, rollback to B can delete A's shard, and reinstalling exact A can then observe ABSENT and authorize the unsafe route again. | Rollback changes code/tag only and leaves every mode store and journal shard inert and byte-preserved. Treat an out-of-band app-data wipe only as destructive test reset, never product recovery evidence. Add A -> B -> exact A fixtures for tombstone, poison, pending, and store preservation. |
| P1 | **The selected-entry ZIP rules do not define one global APK container view.** DEX/JNI local/central equality is specified only after selecting entries. EOCD selection, exact file-end/comment policy, disk/count equality, central-directory full consumption, local/data ranges and overlap, shadow central directories, and secondary valid EOCD rejection are not canonical. A comment signature or shadow directory can make proof tooling and the Android loader select different DEX/JNI views. | Define one whole-file APK ZIP grammar before entry enumeration: exactly one terminal EOCD, zero comment, single disk and count equality, no ZIP64, exact central range consumption, bounded unique local offsets/data ranges with no overlap into metadata, and rejection of secondary EOCD/shadow directories. Permit only the APK Signing Block as an opaque non-ZIP gap. Add comment-signature, shadow-EOCD/directory, truncated range, alias-offset, and overlap fixtures. |
| P1 | **A registry row's `proof_basis_sha256` has no guaranteed canonical preimage available to the generator.** Evidence carries only the basis hash and Bundle omits ProofBasis bytes, yet generation must inspect each row basis's PolicySet and compare it with the current five files. Only the one Workload-embedded basis has canonical bytes. A stale/random row basis therefore forces an undocumented trust or lookup rule. | Carry exact canonical ProofBasis bytes and their hash in each Bundle, require Registry/Evidence/Bundle equality, and independently reconstruct every registered current-policy build variant before registry generation. Runtime may authorize only rows equal to its recomputed Workload-embedded basis; other valid variant rows remain inert. Add missing/random/stale/one-byte/cross-row and duplicate-variant fixtures. |
| P2 | **Candidate authorization and stale-authority prose checks are enumerable and fail open.** With the corresponding authority hash refreshed, `Implementation is authorized.`, `Slice 1B may start.`, and `Capability-v9 continues as the governing authority.` each pass. The structured false authorization bit still blocks machine approval, but the advertised contradiction/reference lint is incomplete. | Expand the canonical contradiction and legacy-governance grammar, retain the structured false bit as the primary stop gate, and add each reproduced statement plus negative non-contradictory fixtures. |

## Validation observed on the rejected snapshot

- Full Python policy suite: 138/138 passed; all five findings were absent from it.
- Validator-focused passes: capability/APK 28/28 and validator group 76/76 passed.
- Capability validator reported candidate v10, five authorities, nine rejected reviews,
  and implementation false; supply-chain and actual APK policy gates passed.
- Current debug APK: 68,384,279 bytes, SHA-256
  `4E89A7ED05E56A0B93BB84FCF0E6F27463AEF3B2CBCC1BFCD8AE4BCE7306336D`.
- The native pass observed one terminal EOCD, zero comment, single disk, eight
  consecutive DEX entries, sixteen JNI entries across four ABIs, and matching selected
  local/central metadata in that APK. This proves only current-fixture feasibility, not
  the missing grammar or a runtime fence.
- A duplicate model-entry APK was rejected by the real `aapt2` before model validation
  and is not a finding.
- `git diff --check` passed; no reviewer changed the target tree.

## Required v11 rereview fixtures

- all conventional and alternative Gradle range delimiters in direct strings, catalog
  rich-version members, plugin/dependency strings, and dependency-verification versions;
- the three reproduced positive contradiction/governing-authority statements and
  non-contradictory denial/history controls;
- A -> B -> exact A rollback traces preserving solo/dual store, tombstone, poison,
  pending, and unrelated artifact shards without opening them under B;
- terminal/secondary/comment-signature EOCD, shadow central directory, disk/count/range
  mismatch, duplicate/aliased local offsets, data/metadata overlap, signing-block-only
  gap, and exact full central-directory consumption;
- Bundle-carried canonical ProofBasis bytes/hash, fully reconstructed debug/release
  current-policy variants, missing/random/stale/one-byte-mutated basis bytes/hashes,
  duplicate-variant and cross-row bundle reuse, inert other-variant runtime rows, and
  zero native calls before exact current-workload equality.

`capability-v10` is permanently rejected evidence. A new `capability-v11` candidate
must close all five findings, remain `implementation_authorized=false`, pass full
validation, and receive a fresh exact-commit independent review before Slice 1B code
may start.
