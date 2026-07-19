# Slice 1B policy independent review 05

## Reviewed target

- Commit: `111aa4d2f55d1fb221c32db3405208515423316d`
- Tree: `4e7b8e5e3b85fc3a631c512305e2098115746ddd`
- Policy revision: `capability-v5`
- Review mode: three fresh independent read-only SSOT/domain/runtime reviews
- Physical devices observed: 0
- Verdict: `REJECT / REWORK`

All reviewers began and ended on the exact clean target and made no repository edits.
No P0 was found. Deduplicating overlap and taking the highest priority assigned by any
reviewer produced six P1 and six P2 findings. This record does not approve v6; fresh
reviewers must inspect its actual clean commit and tree.

## Direct pinned-runtime and validation evidence

- tasks-core 0.10.35 AAR SHA-256:
  `4EDF2F33C840D682C751B3CA951B1AE0465373734776D7FB37DCEF936DE28AD0`;
- tasks-vision 0.10.35 AAR SHA-256:
  `E2F0E0B63DEA220D0424802FBBD4050DFB3B19C616B969C48F12D6C49CF94CDE`;
- camera-core 1.6.1 AAR SHA-256:
  `A36F1C323851B51215BA476640E1EB4153882B0C368C0376E9FB905E689DF84A`;
- camera-camera2 1.6.1 AAR SHA-256:
  `10D8F0102468D10A311FDF73695FC29A6A69A738BB0445FBCFFD9B9C037CB36B`.

Pinned bytecode shows that `PoseLandmarker$1.convertToTaskInput()` obtains the
Bitmap-backed callback image before invoking the app listener, while
`OutputHandler.run()` routes a `MediaPipeException` from result conversion, output
conversion, or the app listener through the same timestamp-free `ErrorListener`.
The public API therefore exposes no stage discriminator that proves an error is a
clean inference-only failure.

On the reviewed target, `git show --check` and `git diff --check` passed, the repository
Python policy suite passed 75/75, supply-chain static policy passed, and asset policy
reported `NO_DEPLOYED_VISUAL_ASSETS`. The extracted-package validator was not counted
as a pass in one reviewer environment because its `jsonschema` module was unavailable.
No implementation or physical-device evidence was reviewed.

## Finding-to-resolution register

| Priority | v5 finding | Counterexample or risk | Proposed `capability-v6` resolution | State |
| --- | --- | --- | --- | --- |
| P1 | ErrorListener was classified as a clean inference error | Callback-output construction fails before the app receives its MPImage, but the same timestamp-free listener fires and v5 may fall back in-process | Every pinned ErrorListener is callback-output-undelivered/resource-uncertain, quarantines, and requires clean launch with no same-process fallback | Addressed; rereview pending |
| P1 | One envelope coupled valid solo to corrupt dual | A checksum-valid envelope can contain a semantically invalid dual record; strict load/reset makes the valid solo unavailable or deletes it | CapabilityModeStoreV3 and RecoveryJournalV3 physically shard files, checksums, owners, timeout, corruption, and reset by mode | Addressed; rereview pending |
| P1 | Persisted scoped Unsupported lacked categorical proof | NONE + `ALL_SAFE_DELEGATES_TERMINAL` + empty timeline passed the schema without proving CPU and GPU clean-terminal | Finite trace, ordered terminal proofs, matching static proof, all-route masks/hash, and journal pre-serve checks are normative | Addressed; rereview pending |
| P1 | A clean cancellation reset the one-shot retry budget | quarantine(0) -> retry active(1) -> clean cancel -> quarantine(0) can repeat forever | Authorization atomically commits retry 1; every later clean/unclean/cancel/save path preserves 1 until strict success and final journal clear | Addressed; rereview pending |
| P1 | Persistence I/O hangs had no total outcome | Journal read, DataStore read/delete/reset/update, or non-cancellable save can never return and leave UI/finalization unbounded | Every operation has owner/deadline/commit token, poisoned generation, bounded UI result, state-inert late bytes, and next-launch reconciliation | Addressed; rereview pending |
| P1 | Recovery journal accepted unsafe state/reason tuples | A checksum-valid `MANUAL_RETRY_ACTIVE,count=0` or terminal entry with resource-uncertain reason could be interpreted as safe | A complete state/reason/retry/cache/active/pending matrix rejects all unlisted tuples before cache/native work | Addressed; rereview pending |
| P2 | Task timestamp origin and overflow were undefined | Identical source frames can produce different first task timestamps or overflow `previous+1` differently | Fresh runtime UNSET/-1 epoch, exact first zero, non-mutation rules, checked arithmetic, pinned `*1000` ceiling, and reset boundary are fixed | Addressed; rereview pending |
| P2 | PSS calls could block finalization forever | A hung `Debug.getPss()` either blocks save or leaves an outstanding probe collector | One process evidence owner/call, 500 ms token, poison/no replacement, state-inert late return, and total 13-sample dispositions bound it | Addressed; rereview pending |
| P2 | Runtime-reachable `1-29` contradicted quartile occupancy | One to three completions cannot put one representative completion in each of four quartiles | Pure 0-3 reducer cases are separated; persisted/runtime below-30 evidence requires four positive quartiles and therefore 4-29 | Addressed; rereview pending |
| P2 | Stored summaries could not reproduce percentiles/medians | Nearest-rank values and Q1/Q4 medians cannot be recomputed from count plus five summary values | Rank certificates store value/less/equal counts, prove feasible nearest-rank summaries, and keep raw samples forbidden | Addressed; rereview pending |
| P2 | System-metric non-COMPLETE tuples were underconstrained | PARTIAL/UNAVAILABLE/NOT_MEASURED could carry contradictory masks, counts, values, or distributions | CapabilityModeStoreV3 freezes exact PSS/render/thermal status, reason, conservation, endpoint, histogram, and distribution tuples | Addressed; rereview pending |
| P2 | Fallback timeline had no executable grammar | A GPU-selected record could claim GPU terminal then CPU fallback support without selecting CPU | A finite maximum-six event language cross-validates selected delegate, terminal proof, outcome, Unsupported proof, and journal quarantine | Addressed; rereview pending |

## v6 remediation validation before exact-target rereview

- `git diff --check`: passed;
- `python -B -m unittest discover -s scripts\tests -v`: 75/75 passed;
- `python -B scripts\validate_supply_chain.py --root .`:
  `SUPPLY_CHAIN_STATIC_POLICY=PASS`;
- `python -B scripts\validate_project_assets.py --root .`:
  `NO_DEPLOYED_VISUAL_ASSETS`, so G6 remains unapproved;
- Gradle/AVD/physical tests: not rerun for this documentation-only remediation; the
  previously recorded exact baseline remains evidence, not evidence for v6 code.

## Required v6 rereview fixtures

- every pinned ErrorListener origin, including output conversion failure, is
  restart-only and cannot produce a clean terminal or same-process fallback;
- source timestamp first frame, reset, duplicate/out-of-order/negative relative value,
  millisecond conversion overflow, and `previous + 1` exhaustion;
- PSS on-time, exception, timeout, permanent block, late return before/after freeze, and
  save/finalization independence;
- valid solo plus corrupt/invalid dual, interrupted dual update, explicit reset, and
  current-build migration without loss of the independently valid solo result;
- manual retry cancellation before and after native authorization, clean terminal,
  uncertain resource exit, crash, and next-launch recovery;
- every persistence operation at exact deadline and deadline +1, late old-generation
  commit/read, process death, and next-launch reconciliation;
- exhaustive RecoveryJournal legal tuple acceptance and one-field mutations rejected;
- every scoped-Unsupported reason with complete CPU/GPU/static-layout proof and all
  missing/impossible variants rejected;
- exact finite fallback sequences and selected-delegate/outcome contradictions;
- writer percentile fixtures versus reader-verifiable summary invariants, including
  non-monotonic/impossible counts and quartile conservation;
- complete/partial/unavailable/not-measured PSS/render/thermal tuples;
- pure reducer counts 0/1/3, plus runtime representative counts 4/29/30 with four
  positive quartiles.

## Approval boundary

No Slice 1B implementation may begin until independent reviewers inspect a clean
`capability-v6` commit and report no actionable P0/P1/P2 defect. Emulator evidence
does not approve a physical tier, effect promotion, G2, or G8.
