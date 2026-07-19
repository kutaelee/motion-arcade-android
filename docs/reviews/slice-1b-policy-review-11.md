# Slice 1B capability policy review 11

## Verdict and reviewed snapshot

**REJECT / REWORK.** Three independent read-only passes reviewed the same uncommitted
`capability-v11` candidate before any commit. Native ZIP/bundle and recovery/rollback
passes were clean in their assigned scopes; the validator/evidence pass reproduced one
P1 and one P2. The deduplicated verdict is P0=0, P1=1, P2=1. Reviewers made no file,
index, branch, commit, or remote change, and start/end hashes were identical.

The reviewed repository HEAD remained rejected v10 commit
`04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
`9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`). v11 existed only as the following
worktree bytes and was never committed:

| Document | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `8AF9A387EC9215128659EC44D94D318C2BBFB4F47E96831BD672A97C3D664D0F` |
| `docs/contracts/capability-store-v4.md` | `E4E970E006ABEA3DC4D0605484F9B88CE9F5C68312EACFA1E81A83EE60212F24` |
| `docs/contracts/native-close-fence-proof-v1.md` | `988C8A8CE98A8549AC1AC5B0D49E43F2C5B82C63F408A5A323695A3CBE9A90D1` |
| `docs/contracts/recovery-journal-v5.md` | `B6739973E59A5528CE6B5331DCB52CB4A92A2C37E3EF2BEE7FB59E5A7E916A8A` |
| `docs/execution/slice-1b-contract.md` | `5B841DBDE6031205ED82C014CEBC6B2E07ABB24BB894919A31931C2D47791217` |

The v11 authority manifest SHA-256 was
`7CFA899E4251991EFFA2B092118C588B0D22D3F784BF08D45C2F676D2B5363BD`,
with `status=CANDIDATE_REREVIEW_REQUIRED` and
`implementation_authorized=false`. No Slice 1B implementation, physical-device
behavior, G2/G8 result, human QA result, or release approval was credited.

## Findings and required v12 resolution

| Priority | v11 defect and counterexample | Required `capability-v12` resolution |
| --- | --- | --- |
| P1 | **Governed file paths can escape the repository through an ancestor junction or symlink.** `validate_capability_policy.py` checks the final path with `is_symlink()` but does not prove that every ancestor remains below canonical `root`. A reviewer moved `docs/reviews` behind a Windows junction to a sibling outside directory, regenerated referenced hashes, and the real validator returned PASS while the resolved reviews directory was outside root. The same helper pattern governs authorities, status, index, and precommit evidence. | Use one canonical input-path validator for manifest, historical manifests, authorities, reviews, status, index, and evidence. Resolve strictly; require `resolved.relative_to(root)`; reject final and ancestor symlinks; reject Windows junction/reparse ancestors; require a regular final file. Add direct final-link, ancestor-link, ancestor-junction, and out-of-root fixtures for every governed input class. |
| P2 | **Authorization/stale-governance prose lint is enumerable and context-blind.** After refreshing the affected authority hash, the real validator accepted `Implementation authorization is granted.`, `Implementation is now authorized.`, `` `implementation_authorized` is `true`. ``, `Slice 1B is ready for implementation.`, `Capability-v9 governs this implementation.`, `Capability-v9 is still the governing authority.`, and `The governing authority remains Capability-v9.` It rejected the old sentence when it appeared only inside rejected-history prose or an HTML comment. The structured false manifest bit prevented a machine-authorization flip, so this is P2. | Define a line-aware canonical grammar for normative positive authorization and stale governing-authority claims instead of a sentence list. Exclude HTML comments, code, quotations, and explicitly rejected/history contexts. Add all reproduced positive variants plus denial, historical rejection, inline/fenced code, quotation, and comment controls. Keep the structured false bit as the primary stop gate. |

## Clean scoped results retained as supporting evidence

- Native ZIP/ProofBasis pass: P0=0, P1=0, P2=0. An independent exact-grammar parser
  reproduced the current 68,384,279-byte APK, terminal zero-comment EOCD, 545 exact
  central/local entries, eight DEX entries, sixteen JNI entries, exact compressed-stream
  boundaries/CRC, and one 4,096-byte signing block with two unique nonzero pairs. The
  BundleV1 seven-field carried-basis model, per-variant reconstruction, duplicate-variant
  failure, and inert non-current runtime rows were coherent in policy.
- Recovery/store/rollback pass: P0=0, P1=0, P2=0. The assigned review found the
  six-file A -> B -> exact A zero-open/zero-mutation model, exact-key absorbing
  `RETRY_CONSUMED`, persistent same-artifact poison, old/new/third-state recovery, and
  API26 versus API27+ linkage rules coherent in policy.

These scoped clean results do not override the validator rejection and do not prove a
future implementation.

## Validation observed on the rejected snapshot

- Capability focused suite: 20/20 passed.
- Supply-chain focused suite: 53/53 passed, including conventional/alternative/open/
  compound/GAV/`@extension`/`!!` Gradle ranges.
- Full Python suite: 148/148 passed; the two findings were absent from it.
- Capability validator reported v11, five authorities, ten rejected reviews, and
  implementation false; supply-chain, APK policy, native ownership, and asset-status
  gates returned their expected results.
- Forced strict offline Gradle test/lint/debug APK/androidTest APK run: 224/224 tasks,
  exit 0, 67.4 seconds. The active-session console result was recorded in the v11
  precommit ledger; no raw log file was persisted, so v12 must preserve and hash one.
- JUnit XML: 8 files, 52 tests, 0 failures, 0 errors, 0 skipped.
- Current debug APK: 68,384,279 bytes, SHA-256
  `4E89A7ED05E56A0B93BB84FCF0E6F27463AEF3B2CBCC1BFCD8AE4BCE7306336D`.
- APK policy passed; native ownership passed for 16 entries/3 owners; technical runtime
  license audit passed 159 coordinates with generated and checked inventory SHA-256
  `A248299BCBF597D07F3CAE07EA3E9B60E81B662033B162302D110B6443E1BF1B`.
- `git diff --check` passed.

Reviewed validator hashes:

| File | SHA-256 |
| --- | --- |
| `scripts/validate_capability_policy.py` | `98B1B17051B77C6D596BDABB4084ABA4C53054C072F3C760220F92A811B80C06` |
| `scripts/tests/test_validate_capability_policy.py` | `968C1ECEA92BA97520739FC71050CF7FE9D616954D08E280A2C0ED7342625EE2` |
| `scripts/validate_supply_chain.py` | `5E96664AE67FF42EE32ACF5A08B714FEBA983D0D2AE97C3997B6899549DF5B78` |
| `scripts/tests/test_validate_supply_chain.py` | `B2144D9F475A9D539FB53CEC8E2D589B8D18016474C7201A55327C340EF2502B` |

## Required v12 rereview fixtures

- final symlink, ancestor symlink, ancestor Windows junction/reparse point, and resolved
  out-of-root path for current manifest, rejected historical manifest, authority, review,
  status, evidence index, and precommit-evidence inputs;
- regular-file and canonical-root positive controls, missing component, directory final
  target, and root path reached through a redirected ancestor;
- every reproduced positive authorization/governing-authority sentence above;
- denial, rejected revision history, quoted counterexample, inline/fenced code, and HTML
  comment controls that must not become false positives;
- all v11 native and recovery fixtures retained without weakening.

`capability-v11` is permanently rejected evidence. A new `capability-v12` candidate must
close both findings, remain `implementation_authorized=false`, pass full validation and
three fresh current-byte audits, then receive fresh exact-commit independent review
before any separate authorization transition or Slice 1B code.
