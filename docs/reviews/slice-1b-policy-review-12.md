# Slice 1B capability policy review 12

## Verdict and reviewed snapshot

**REJECT / REWORK.** Three fresh read-only passes reviewed the same uncommitted
`capability-v12` candidate before any commit. The path audit returned P0/P1/P2=0/1/2,
the prose audit 0/0/5, and the whole-candidate audit 0/0/1. The whole-candidate P2
reproduced the prose audit's formatting/paraphrase classes, so the deduplicated verdict
is P0=0, P1=1, P2=7. Reviewers changed no repository, index, branch, commit, or remote
state, and the reviewed hashes stayed fixed from start to end.

The repository HEAD remained rejected v10 commit
`04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
`9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`). v12 existed only as these worktree
authority bytes and was never committed:

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `C0965243988F8FD9C52EC20DA3057D1FF8CC6D3A38835F4380809CD03B787944` |
| `docs/contracts/capability-store-v4.md` | `83367CD10DBC66CBE49E895B5BC4A14089EE19F47CE041F5A17E2D9DA47EBF8B` |
| `docs/contracts/native-close-fence-proof-v1.md` | `7A8D886D46FAB82E192912F87E70292722ED0DAA967923AAF2F7B995AE74F20A` |
| `docs/contracts/recovery-journal-v5.md` | `FD6E34118FB6B0666A8EBA077CE111F558C678EF7C229E65570F9CCB552553C7` |
| `docs/execution/slice-1b-contract.md` | `A36738205708BF475068E63F3EAA018C102EFA2E7F1C1B078591E1FFBAB62007` |

The v12 authority manifest was canonical at SHA-256
`458001CCC978E1C591090C2824538071248B3F890467A7EF87820E4A0FCC9BC6`,
with five authorities, rejected reviews 01-11,
`status=CANDIDATE_REREVIEW_REQUIRED`, and `implementation_authorized=false`.
The pre-audit ledger hash reviewed by all three passes was
`9CB6048B951A453743B72FDE008ACD5CAEF729C7AD569AE04D383E69A7380B27`;
the post-audit rejection ledger is
`8F103489B2CF0AD654B05E577BB00C46BA6FE566C5F7ABE93291E15CE8AAB068`.

## Findings and required v13 resolution

| Priority | v12 defect and reproduced counterexample | Required `capability-v13` resolution |
| --- | --- | --- |
| P1 | **Cached preflight paths are not an immutable repository snapshot.** After all governed paths were validated and cached, an actual Windows junction swap replaced `docs/contracts` with an outside directory holding a marker store and a rehashed outside manifest. Later reads accepted those outside bytes and returned `OK=True, errors=0`. Swapping `docs/reviews` after directory validation similarly made later enumeration read the outside directory and still pass. | Capture each governed input as immutable bytes/metadata from anchored non-following handles. On Windows, hold non-delete-shared directory handles, reject reparse targets at open time, verify final handle paths stay below canonical root, and parse/hash/lint only the captured bytes. Freeze review enumeration in that same anchored snapshot. Add real junction-swap tests at both boundaries. |
| P2 | **The forbidden v4 sentinel and the test oracle remain outside a complete I/O boundary.** A broken junction named `recovery-journal-v4.md` exists lexically but `Path.exists()` follows it and returns false, so the full validator passes. Existing helper-coverage tests do not detect that raw call or later cached-path dereferences. | Treat only anchored `FileNotFoundError` as absence; every broken link/reparse/inspection error fails closed. Maintain an independent exact governed-I/O inventory and statically/runtime-prohibit filesystem reads/enumeration outside the snapshot abstraction. |
| P2 | **CommonMark formatting and physical wrapping bypass logical claims.** Real validator runs passed `Implementation is **authorized**.`, link-wrapped `authorized`, bold `true`, `<br>`, table-split text, soft-wrapped list/paragraph text, and bold `governs`. | Normalize rendered logical blocks before matching: join soft wraps/list continuations, flatten emphasis/link labels, normalize table cells and `<br>`, while preserving excluded code/quotation contexts. |
| P2 | **Authorization and stale-governance semantic families remain incomplete.** Actor/permission/commence claims and binding/source-of-truth/follows/supersedes/applicable claims passed, as did spaced legacy identifiers. Examples include `We are authorized to implement Slice 1B.` and `Capability-v9 remains applicable to this implementation.` | Add bounded subject/permission/commence and binding/source-of-truth/follows/supersedes/applicable families; normalize identifier spacing and Unicode hyphens. Explicitly label the prose lint defense-in-depth rather than semantic proof. |
| P2 | **A non-normative prefix suppresses an unrelated positive clause on the same line.** `History is closed; Implementation is authorized.`, `The claim is rejected, but implementation is authorized.`, and `Rejected proposal notwithstanding, the team may implement Slice 1B.` passed. | Split logical text into clauses and scope history/rejection only to the exact labelled or quoted object; continue scanning later semicolon, sentence, and adversative clauses. |
| P2 | **Truthful conditions and meta-denials are false positives.** `Implementation may start only after exact-commit review passes.`, `Before implementation is authorized...`, `Unless implementation is authorized...`, `Never write: ...`, and `Do not assert capability-v11 is current.` were rejected. | Classify local `before`/`until`/`unless`/`if`/`whether`, `only after/if/once`, and reporting/directive negation without letting it mask another positive clause. |
| P2 | **Fenced-code state is not CommonMark-correct.** A three-backtick line incorrectly closed a four-backtick fence; an info-bearing would-be close incorrectly closed; and a valid tilde-fence info string containing a backtick was not recognized. | Retain fence marker and opening length; require a closing run of the same marker with length at least the opening and whitespace-only trailing content; apply marker-specific info-string rules. |

## Clean technical evidence retained as supporting evidence

- Capability focused log: 37 tests, 34 passed, 3 privileged Windows symlink-creation
  tests skipped on `WinError 1314`; SHA-256 `FE3C0AA4...FB36A`.
- Supply focused: 53/53; full Python: 165 tests, 162 passed and the same 3 skipped.
- Strict offline forced Gradle: `BUILD SUCCESSFUL`, 224/224 tasks executed, raw-log
  SHA-256 `A0C1A1A258BEDD796FAEE5E4AF8DA874ADD043532F527EDBC3B35E1148DE376D`.
- JUnit XML: 8 files, 52 tests, 0 failures/errors/skips.
- Debug APK: 68,384,279 bytes, SHA-256
  `4E89A7ED05E56A0B93BB84FCF0E6F27463AEF3B2CBCC1BFCD8AE4BCE7306336D`;
  APK policy passed.
- Independent whole-container parse: 545 entries, eight DEX, sixteen JNI, exact
  zero-comment terminal EOCD, complete central/local ranges, and one valid 4,096-byte
  signing block with two pairs.
- Native ownership: 16 entries/3 owners. Runtime technical license audit: 159
  coordinates; generated/checked inventory hash
  `A248299BCBF597D07F3CAE07EA3E9B60E81B662033B162302D110B6443E1BF1B`.
- The v11 whole-APK/ProofBasis/multi-variant and six-file A -> B -> exact A
  recovery/rollback remediations remained coherent after v12 revision propagation.
- Android source/build working-tree delta: zero. `adb devices -l` had no physical target;
  G2/G6/G8/G9 and human QA remain unobserved.

These clean supporting results do not override the policy-gate rejection.

Reviewed validator hashes:

| File | SHA-256 |
| --- | --- |
| `scripts/validate_capability_policy.py` | `C6998D052F2723E63A1964DE091463A80C657477032375565DA9EDFF10C9D0A0` |
| `scripts/tests/test_validate_capability_policy.py` | `EF9A108F789FF2A4E49DC51E8E4B933CD909242BC313D795394C0A48A401E8CD` |
| `scripts/validate_supply_chain.py` | `5E96664AE67FF42EE32ACF5A08B714FEBA983D0D2AE97C3997B6899549DF5B78` |
| `scripts/tests/test_validate_supply_chain.py` | `B2144D9F475A9D539FB53CEC8E2D589B8D18016474C7201A55327C340EF2502B` |

## Required v13 rereview fixtures

- actual authority-directory and review-directory junction swaps after the old preflight
  boundary, plus immutable captured-byte proof and final handle-path containment;
- broken final/ancestor symlink or junction at each forbidden/required class and an
  independent exact inventory forbidding raw filesystem dereferences;
- emphasis, link labels, `<br>`, tables, soft-wrapped paragraphs/list items, inline code,
  blockquotes, HTML comments, and exact CommonMark backtick/tilde fence openings/closings;
- actor/permission/commence and binding/source-of-truth/follows/supersedes/applicable
  positive families, spaced/Unicode-hyphen legacy identifiers, and negative mirrors;
- mixed history plus positive clauses, future conditions, meta-denials, and positive
  clauses that must remain visible beside a locally negative clause;
- all v11/v12 native, recovery, range, manifest, and structured-false fixtures retained.

`capability-v12` is permanently rejected evidence. A new `capability-v13` candidate must
close all findings, remain `implementation_authorized=false`, pass full validation and
three fresh current-byte audits, then receive fresh exact-commit independent review
before any separate authorization transition or Slice 1B code.
