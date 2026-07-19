# Slice 1B capability policy review 13

## Verdict and reviewed boundary

**REJECT / REWORK.** Independent adversarial passes rejected the uncommitted
`capability-v13` worktree before it became one frozen pre-commit candidate. The
consolidated, edit-level deduplication is P0=0, P1=5, P2=8. Related exploit payloads
are counted once per remediation boundary; the final v13 rejection ledger records the
full grouping at SHA-256
`71db385c245f31ae00809160981cfd25fdf4f0b65f5bd07bf4b92ad79a2a76c3`.

The reviews were deliberately not combined into an approval: findings were repaired
in the shared worktree between passes, so the clean module-only rereview and later
focused tests do not establish that three reviewers approved identical whole-candidate
bytes. Any actionable finding was a v13 stop condition.

Repository HEAD remained rejected v10 commit
`04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
`9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`). Neither the v13 manifest nor its ledger
appears in commit history. The canonical v13 manifest is immutable rejected evidence at
SHA-256 `d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff`,
with five authorities, rejected reviews 01-12,
`status=CANDIDATE_REREVIEW_REQUIRED`, and `implementation_authorized=false`.

## Exact audit targets

| Boundary | SHA-256 |
| --- | --- |
| v13 authority manifest | `d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff` |
| ADR-011 | `1ec2a6223b65bced62769258930d7b17fd053f97c01865ea42424cd041f6dae8` |
| CapabilityModeStoreV4 | `ff0b004ac87aa26d37770f2c8be3282f219a7d216d0f6351ffbdf3f8eadb4d1f` |
| NativeCloseFenceProofV1 | `b1cde3c68dc4938ac0c1b7e8d4765dd197d76df99ecdcfa7687cc72f1525591f` |
| RecoveryJournalV5 | `0a2d390025c5680aea0eb157507fb89aae23a87071ce3dd602ec4c8a3059b41f` |
| Slice 1B execution contract | `da07e88306a192bee21fd0572779d03684e085a16c23a5342ad5d10765a9c4a9` |
| integrated-validator audit target | validator `6359ee544ba5b03da14dbb2a32df43b36ef27988541cc913776f9eedd9585716`; tests `41bd4dc12bac580833a645799b4aea6bf74ef80bd9bc97581996a4ebedc14c92`; Markdown helper `b3247e90244c36e5146bae0aca9c41c3c335a934b5d46eaac996983373bed65e` |
| final module-only rereview target | snapshot `35038840defdaa1af676bc33f572a786d3f6c0e9c2022820caf68a81118b02ca`; snapshot tests `6f23ab4cbbabf25825c4e1dc2eeea9e7f993a3ba8a30872a44ae3c274e67ea1c`; Markdown `8152f50e167484f5f7ab348063b55ba7fca792bd2d08c40054c457a19affd2a7`; Markdown tests `eb462f692335677dacf3f2b4ac9bbe31397af68893a734e62a371c9c676a4276` |

The integrated-validator hashes and final module-only hashes describe different
worktree moments. Later remediated bytes are inputs to v14, not a retroactive v13 pass.

## Findings requiring v14

| Priority | Finding | Required resolution |
| --- | --- | --- |
| P1 | A governed POSIX FIFO could block indefinitely. | Use non-blocking open, require a regular file, and prove bounded failure. |
| P1 | Same inode/size and restored mtime could still yield torn mixed bytes. | Hold and verify Linux read leases, compare two exact reads/metadata, and fail closed where leases are unsupported. |
| P1 | Mutating review 01/12 or the v12 rejection ledger and rehashing the current manifest still passed. | Freeze every rejected review, historical manifest, and historical ledger by independent exact constants. |
| P1 | A case-variant unexpected review 13 filename passed enumeration checks. | Enforce sorted casefold-unique enumeration and case-insensitive exact expected names. |
| P1 | No normative artifact bound a clean candidate commit/tree, evidence, three reviewers, and a quorum into implementation permission. | Define and mechanically validate a versioned authorization-only follow-up record; the false candidate manifest never authorizes work. |
| P2 | Clause, conditional, history-conjunction, inline-HTML, and actor/permission variants bypassed the prose defense. | Normalize visible logical clauses and expand bounded positive/negative grammar without treating prose as the primary gate. |
| P2 | A required token hidden solely in an HTML comment passed. | Check required tokens only in visible comment-stripped source. |
| P2 | Extra/missing snapshot keys, non-byte values, and non-string review names could false-pass or crash. | Validate the complete snapshot shape and exact inventories before consumption. |
| P2 | A lone surrogate could escape manifest canonicalization as an exception. | Convert decode, parse, and canonicalization faults to typed rejection. |
| P2 | Unicode default-ignorable and compatibility characters split governed claims. | Apply NFKC/default-ignorable normalization and adversarial mirrors. |
| P2 | Reference definitions and structural Markdown constructs were incompletely scoped. | Handle supported reference/table/list/block boundaries explicitly and fail closed on ambiguity. |
| P2 | Malformed comments and nested/mismatched fences corrupted context state. | Preserve exact fence marker/length rules and reject malformed visible context. |
| P2 | Windows review names were enumerated only once before late mutation. | Re-enumerate from the held directory anchor after file capture and reject change. |

## Supporting post-rejection evidence

- Windows snapshot/Markdown/validator focused run: 96 tests, 83 passed, 13 skipped;
  log SHA-256
  `1349c6a04b4098c681c07b9b97581e70071cb4216008c8c128f0219fab64f473`.
- WSL POSIX snapshot run: 9/9 passed from Linux `/tmp`; log SHA-256
  `2a4c02d8a5acd26090ea8f493bc4786f142bf7293ff9fd58d38b49a5023bd2e0`.
- The module-only rereview of the four exact final module hashes above returned no
  actionable finding in its bounded scope.
- The current post-audit validator/test hashes were
  `8ddaea289de8fc575a56839cba4e0c4378e0bc54e071059341a2d135ff95e8a2` and
  `7b773767ec6815d4d15b52e041911cb52eab1764ffa7160215f0fb94442a9f7f`.

These are remediation evidence only. No full frozen-candidate Python/Gradle/APK/native/
license matrix, three same-byte whole-candidate reviews, exact-commit reviews, physical
device observation, or authorization record was completed for v13.

## Required v14 gate

`capability-v13` is permanently rejected evidence. v14 must bind this review and the
final v13 ledger; independently freeze all reviews 01-13 and ledgers v10-v13; reject
case aliases and malformed snapshots without crashing; define the exact authorization
record/quorum transition; and pass the full validation matrix plus three independent
same-byte pre-commit reviews. Any actionable P0/P1/P2 rejects v14. Even a clean v14
candidate remains `implementation_authorized=false` until its separate, mechanically
validated authorization-only follow-up exists.
