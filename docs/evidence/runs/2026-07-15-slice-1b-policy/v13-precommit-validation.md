# Capability v13 pre-commit validation

Date: 2026-07-15 (Asia/Seoul)

## Verdict boundary

**REJECT / REWORK.** Independent adversarial passes found actionable defects before
`capability-v13` reached a frozen pre-commit candidate. The consolidated release-blocking
classification is P0=0, P1=5, P2=8. The P2 count groups related counterexamples by the
remediation boundary below rather than counting every payload. The audits did not approve
one identical byte snapshot: the worktree advanced as findings were repaired, so a clean
subset result cannot be combined with another pass to form a clean whole-candidate review.

v13 was never committed, never received exact-commit review, and never authorized
implementation. Repository HEAD remained rejected v10 commit
`04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
`9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`). The canonical v13 authority manifest is
immutable rejected evidence at SHA-256
`d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff` and contains
`status=CANDIDATE_REREVIEW_REQUIRED` plus `implementation_authorized=false`.

No physical Android device was connected. Camera framing, two-person recognition,
perceptual quality, physical performance/thermal behavior, and human safety comprehension
remain unobserved. The unchanged APK is Slice 0A only; no visual asset is deployed and G6
remains blocked. There is therefore no actionable human game QA task in this revision.

## Reviewed authority boundary

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `1ec2a6223b65bced62769258930d7b17fd053f97c01865ea42424cd041f6dae8` |
| `docs/contracts/capability-store-v4.md` | `ff0b004ac87aa26d37770f2c8be3282f219a7d216d0f6351ffbdf3f8eadb4d1f` |
| `docs/contracts/native-close-fence-proof-v1.md` | `b1cde3c68dc4938ac0c1b7e8d4765dd197d76df99ecdcfa7687cc72f1525591f` |
| `docs/contracts/recovery-journal-v5.md` | `0a2d390025c5680aea0eb157507fb89aae23a87071ce3dd602ec4c8a3059b41f` |
| `docs/execution/slice-1b-contract.md` | `da07e88306a192bee21fd0572779d03684e085a16c23a5342ad5d10765a9c4a9` |
| `docs/contracts/capability-policy-authority-v13.json` | `d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff` |

The manifest binds those five authority bytes and rejected reviews 01-12, but the v13
validator treated its review hashes as replaceable current-manifest data. It therefore
did not independently freeze the historical review or rejection-ledger evidence.

## Independent audit results

| Audit boundary | Actual result | Exact reviewed evidence |
| --- | --- | --- |
| Snapshot and Markdown modules | Initially found blocking POSIX snapshot and Markdown/Windows oracle defects. After local remediation, the module-only rereview returned P0/P1/P2=0/0/0; that subset approval did not cover the whole v13 candidate. | Final module hashes: snapshot `35038840defdaa1af676bc33f572a786d3f6c0e9c2022820caf68a81118b02ca`, snapshot tests `6f23ab4cbbabf25825c4e1dc2eeea9e7f993a3ba8a30872a44ae3c274e67ea1c`, Markdown `8152f50e167484f5f7ab348063b55ba7fca792bd2d08c40054c457a19affd2a7`, Markdown tests `eb462f692335677dacf3f2b4ac9bbe31397af68893a734e62a371c9c676a4276` |
| Integrated validator adversarial audit | **REJECT**, P0/P1/P2=0/2/4. Rehashing a changed historical review or ledger in the current manifest passed; a case-variant unexpected review passed; prose/comment/snapshot-shape/surrogate cases bypassed or crashed. | Validator `6359ee544ba5b03da14dbb2a32df43b36ef27988541cc913776f9eedd9585716`, tests `41bd4dc12bac580833a645799b4aea6bf74ef80bd9bc97581996a4ebedc14c92`, Markdown helper `b3247e90244c36e5146bae0aca9c41c3c335a934b5d46eaac996983373bed65e` |
| Authority/document consistency audit | **REJECT**, P0/P1/P2=0/1/0. The transition from a false candidate manifest to implementation authorization had no single normative, hash-bound, mechanically checked record or quorum. | Five authority hashes in the table above and canonical v13 manifest hash `d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff` |

The validator and Markdown hashes in the second row identify the bytes that reproduced
that audit's findings. Later worktree hashes differ and are not evidence that those
reviewed bytes passed.

## Consolidated findings

| Priority | v13 defect and discriminating counterexample | Required v14 resolution |
| --- | --- | --- |
| P1 | A POSIX FIFO could block a governed read instead of failing within a bounded operation. | Open non-blocking, require a regular file, and retain a bounded subprocess regression. |
| P1 | A writer could yield mixed bytes while inode, size, and restored mtime appeared unchanged. | Hold a Linux read lease, reject lease breaks/pre-existing writers, read twice, and compare exact bytes and metadata; fail closed when the filesystem cannot provide the required lease semantics. |
| P1 | Review 01/12 or a historical rejection ledger could be edited and the current manifest rehashed to pass. | Independently hard-code and verify exact historical review, manifest, and ledger hashes from the captured snapshot. |
| P1 | Case-variant `SLICE-1B-POLICY-REVIEW-13.MD` was outside the expected-name subtraction and passed. | Require a sorted casefold-unique enumeration and reject every case-insensitive unexpected review name. |
| P1 | Documents disagreed about one versus three clean reviews and did not define the artifact that authorizes implementation. | Define one versioned authorization record binding the exact candidate commit/tree, manifest, five authorities, precommit ledger, and three independent exact-review artifacts; keep candidate-stage authorization artifacts forbidden. |
| P2 | History conjunctions, false conditionals, inline HTML spans, actor/permission grammar, and local negation could hide or falsely flag authorization claims. | Scope visible conditions and negation per clause and cover bounded actor/permission/governance families. |
| P2 | A required authority token present only inside an HTML comment satisfied the raw substring check. | Strip comments before checking required visible tokens. |
| P2 | Extra/missing snapshot keys, non-string names, or non-byte values could false-pass or raise an uncaught exception. | Validate the complete snapshot object shape and exact inventory before any consumption. |
| P2 | A lone surrogate in the manifest caused canonicalization to raise outside the typed validation boundary. | Convert decode/parse/canonicalization failures to deterministic validation errors. |
| P2 | Unicode default-ignorable and compatibility forms split claims. | Normalize NFKC/default-ignorable characters and retain adversarial positive/negative fixtures. |
| P2 | CommonMark references and structural block boundaries were incompletely scoped. | Parse the supported logical Markdown boundary fail closed and preserve excluded code/quotation contexts explicitly. |
| P2 | Malformed comments and nested/mismatched fences corrupted the normative context state. | Preserve exact comment and fence marker/length boundaries and reject malformed visible context. |
| P2 | Windows review enumeration was not repeated after all review handles were captured. | Enumerate twice from the held directory anchor and reject any late name change. |

## Post-finding remediation evidence

The following results were observed only after one or more v13 audits had already
rejected the candidate. They support the v14 remediation; they do not rescue v13.

| Check | Actual result |
| --- | --- |
| Windows snapshot + Markdown + validator focused suite | 96 tests run, 83 passed, 13 skipped; skipped cases were nine POSIX-only cases and four unavailable Windows symlink-privilege cases. Raw log SHA-256 `1349c6a04b4098c681c07b9b97581e70071cb4216008c8c128f0219fab64f473` at `v13-remediation-focused.log`. |
| WSL POSIX snapshot suite | 9/9 passed from Linux `/tmp`; raw log SHA-256 `2a4c02d8a5acd26090ea8f493bc4786f142bf7293ff9fd58d38b49a5023bd2e0` at `v13-posix-snapshot.log`. The repository itself is on `/mnt/c`, where required leases are unsupported; production validation must fail closed on such a filesystem. |
| Current post-audit validator bytes | `scripts/validate_capability_policy.py` SHA-256 `8ddaea289de8fc575a56839cba4e0c4378e0bc54e071059341a2d135ff95e8a2`; tests SHA-256 `7b773767ec6815d4d15b52e041911cb52eab1764ffa7160215f0fb94442a9f7f`. These bytes were not the integrated-validator audit target above. |
| Full Python, Gradle, APK, native, and license rerun after final remediation | NOT EXECUTED for a frozen v13 candidate. Earlier v12 passes remain supporting evidence only. |
| Three independent audits of one frozen current-byte candidate | NOT ACHIEVED. |
| Exact-commit audits and authorization record | NOT EXECUTED; v13 was never committed. |

## Next gate

`capability-v13` is permanently rejected evidence. Create `capability-v14` with exact
historical evidence freezing, case-insensitive review closure, typed malformed-input
handling, and one normative authorization transition. Recompute the five authority
hashes and a canonical v14 manifest binding reviews 01-13, run the full validation
matrix, then obtain three independent reviews of the same frozen pre-commit bytes. Any
actionable P0/P1/P2 rejects v14. A clean candidate still keeps
`implementation_authorized=false`; implementation may begin only after the separately
validated, authorization-only follow-up transition defined by v14.
