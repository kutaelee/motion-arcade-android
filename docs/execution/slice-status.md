# Slice execution and release status

This file records the conservative merged order of the original and augmented SSOT.
A slice advances only when its acceptance evidence is present. A later slice cannot
retroactively waive an earlier stop condition.

| Stage | Required slice order | Status | Completion evidence |
| --- | --- | --- | --- |
| 0 | Original 0 + 0A | TECHNICAL BASELINE ACCEPTED / RELEASE GATES OPEN | reproducible build, pinned supply chain, contracts, content-addressed APK/evidence, and exact-tree remote CI verified; physical, product, and authorized legal release approvals remain open |
| 1 | 1A → 1B → Original 1 | 1A ACCEPTED / 1B GATE REPAIR | `slice-1a-accepted` and exact-tree CI passed; v14 candidate/authorization objects remain immutable, but formal validation rejected the first implementation chain after a protected policy-test mutation exposed incompatible candidate/authorization-stage assertions; runtime-neutral v15 gate repair, fresh exact reviews, and authorization are required before implementation resumes |
| 2 | 2A → 2B → Original 2 | NOT STARTED | event fixtures/eval, deterministic engine, ambiguity-safe tracking |
| 3 | Original 3 + augmented common AC | NOT STARTED | fishing solo slice, replay and lifecycle evidence |
| 4 | Original 4 + augmented common AC | NOT STARTED | boxing solo slice, negative-path evidence |
| 5 | Original 5 + augmented common AC | NOT STARTED | two-player fishing/boxing, crossing and simultaneous-event evidence |
| 6 | Original 6 + augmented common AC | NOT STARTED | monster solo/dual, boss/revive/ultimate evidence |
| 7 | Original 7 + 7A → 7B | NOT STARTED | approved generated assets, APK, G0-G9 evidence bundle |

## Current verified baseline

- ZIP SHA-256: `C998B9EB5F102F231BB626F50864C881E7D0E83E9ADEE5868E363D4FF8094330`
- Internal checksum entries matched: 65/65.
- ZIP CRC test passed and no encrypted entries were observed.
- Baseline repository tag: `slice-0a-start` at commit
  `58c98f915419363498ce08e6ae761a1660ceb34b`.
- Accepted APK source commit:
  `dadd77d15ee4595fd2ef8d7540e5ecad08cd64cd` (tree
  `0c8c299301d09262a6ac1844065ab92bcab2a88b`).
- JDK 17 and Android SDK APIs 33/37 are installed locally; compileSdk/targetSdk are
  pinned to API 37.
- Strict local `test lint assembleDebug :app:assembleDebugAndroidTest` passed with
  25/25 JVM tests. API 33 and API 37 16 KB AVD instrumentation each passed 2/2
  packaged MediaPipe initialization/privacy tests.
- Content-addressed debug APK:
  `artifacts/slice-0a/motion-arcade-slice-0a-58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f.apk`,
  SHA-256 `58D350E136C50C2B1A407FD887E66A4939CEB8AC6348545A828A69D6C76F665F`
  (68,351,511 bytes). API 33 and API 37 16 KB tests consumed the same hash.
- Python policy/evidence tests passed 75/75. Debug and release runtime lock sets
  both contain the same 159 coordinates. The schema-v2 technical license audit
  passed 159/159; authorized legal redistribution review remains a G9 gate.
- Native runtime validation mapped all 16 APK native entries to one exact verified
  AAR artifact each, with 0 unmatched, ambiguous, or duplicate entries.
- No physical Android device is currently connected; all physical-only gates remain
  unverified.
- Slice 1A corrected source commit `500ca21df791a034381c22fd1f6d8bb553d69dae`
  passed 27/27 vision tests, including the 96-combination coordinate matrix and
  invalid-metadata precedence regression, plus 25/25 existing contract tests and
  full lint. A clean independent rereview found no actionable P0/P1/P2 issue.
  Final evidence commit `9ecc3ccc807a6460ee5fe480eee8a2909b0c45fc` passed
  Push `29383341427`, PR `29383342660`, and tag Push `29383930801` before
  annotated tag `slice-1a-accepted` was accepted. Merge commit
  `97e0c21345945cd55451406ef947537d465fc902` has the same tree and passed main
  Push `29384524084`.
- Slice 1B rollback tag `slice-1b-start` points to merged main commit `97e0c213...`
  (tree `83c7a194aa9f6fc839b5aff55fb823a94eaf7400`).
- Rollback-tag CI run `29385104146` did not pass. Attempt 1 passed SSOT, strict
  build/test, and KVM, then GitHub reported lost hosted-runner communication during
  API 37. The single different-runner retry again passed those prerequisites, but the
  managed device reported `Starting 0 tests` and `Instrumentation run failed due to
  Process crashed`. The same exact commit/tree, emulator 36.6.11, and API 37 ps16k
  image revision 6 had passed two tests on merged-main run `29384524084`. This is
  recorded as unresolved hosted managed-device variability, not as a tag-CI pass or
  a reproduced application assertion failure; no identical third retry was made.
- Slice 1B policy commit `3202df0095fd6e75ee675b070084674664be487f`
  was independently reviewed as `REJECT / REWORK`: five P1 and five P2 findings
  covered tier monotonicity, outcome precedence, timestamp domains, delegate
  throughput/selection, fallback reset, quartiles, cache invalidation, thermal API
  coverage, resolution scope, and required distributions. Commit
  `6e2b8b74191062482031c4ac0f76f8bf5893c1a5` closed those findings but its clean
  rereview found four new P1 and six P2 issues in low-performance selection, true
  frame age, LIVE_STREAM correlation, RGBA ownership, steady samples, cache,
  resolution, fallback symmetry, drops, and finalization. Commit
  `919132158839d309d0180e1af42b0f0406383298` then received three clean read-only
  reviews. Their deduplicated highest-priority register rejected `capability-v3` with
  seven P1 and six P2 findings covering pinned two-MPImage ownership, bounded native
  teardown, public camera identity, failure/fallback effects, crash-loop prevention,
  finalization, stall, workload/scope identity, exact thresholds, FrameMetrics, and
  crop semantics. Commit `89bb54eecc1115df49d384518b4ff25199818c6e`
  (`capability-v4`) then received three fresh clean reviews and was rejected with a
  deduplicated highest-priority ten P1 and seven P2 findings: pinned NPU fall-through,
  missing representative pose occupancy, ambiguous tier rates, deadline/drain gaps,
  recovery/cache/resource transitions, installed-artifact identity, late cleanup,
  solo/dual persistence, ML/effect separation, and exact render/thermal evidence.
  Commit `111aa4d2f55d1fb221c32db3405208515423316d` (`capability-v5`, tree
  `4e7b8e5e3b85fc3a631c512305e2098115746ddd`) then received three fresh exact-target
  reviews and was rejected with deduplicated P1 six/P2 six: timestamp-less
  ErrorListener resource ambiguity, solo/dual corruption coupling, Unsupported proof,
  retry reset, persistence/PSS hangs, journal tuples, timestamp origin, rank-summary
  verification, collector tuples, trace grammar, and 1-29 reachability. Review 05 fixes
  that evidence. Exact v6 commit `b29f0c9bf3bc85b5fb8db806d2840f3e9b2ad7e0`
  (tree `6439e89cf836d0f612f890b321c49d69f3dcbbd7`) then received three fresh
  reviews and was rejected with deduplicated P1 ten/P2 four. Review 06 records missing
  attempt/terminal proof boundaries, exact pending old/new identity, abort crash order,
  joint aggregate feasibility, callback/thermal barriers, DataStore reset lifecycle,
  timestamp/PSS/hash determinism, and the proposed v7 corrections. A stable pre-commit
  v7 snapshot was then rejected P1 three/P2 three; review 07 records consumed-retry,
  quarantine-only epoch, queued thermal, recovery identity, endpoint multiplicity, and
  reset-epoch gaps. Stable pre-commit v8 was then rejected P1 four/P2 zero; review 08
  records self-invalidating tombstone clear, thermal close/admission, AtomicFile
  void/log-only completion, and the missing native FrameMetrics removal fence. Exact v9
  commit `9fbd391553b9260128c7dee52e98e40f33b0ad64` (tree
  `8430416f87d9627c018fef2a66e5d834695a33b2`) was then rejected with deduplicated P1
  five/P2 two in review 09: API26-29 AtomicFile grammar, thermal Binder admission,
  post-seal poison ordering, retry provenance, profile revision bytes, normal-save disk
  evidence, and stale revision references. Exact v10 commit
  `04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
  `9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`) was then rejected with deduplicated
  P1 four/P2 one in review 10: alternate Gradle range delimiters, rollback deletion of
  permanent recovery evidence, missing whole-APK ZIP grammar, unavailable ProofBasis
  preimages, and fail-open authorization/stale-authority prose lint. Uncommitted v11
  closed those five items, but review 11 rejected it P1 one/P2 one because governed paths
  could escape through an ancestor junction/symlink and the prose grammar still missed
  positive authorization/governing-authority variants while rejecting comments/history.
  Uncommitted v12 closed the direct ancestor escape and several sentence variants, but
  review 12 rejected it P1 one/P2 seven: cached-path junction-swap TOCTOU, a broken-
  junction sentinel and incomplete I/O oracle, CommonMark logical formatting, remaining
  paraphrases, overbroad history suppression, conditional/meta-denial false positives,
  and incorrect fence state. Uncommitted v13 then closed those boundaries in stages,
  but review 13 rejected it with edit-level deduplicated P1 five/P2 eight: blocking and
  torn POSIX capture, replaceable historical evidence, a case-aliased review, an
  undefined authorization transition, and malformed/Unicode/Markdown/context cases.
  Its final rejection ledger SHA-256 is
  `71db385c245f31ae00809160981cfd25fdf4f0b65f5bd07bf4b92ad79a2a76c3`
  and review 13 SHA-256 is
  `4de103affeb31156ce235efaca96d1076ef171dc9c73c4a6368d1d4d45138f30`.
  Immutable v14 candidate `e9216b81b527c5825981fb429076ac7ba245644e` and
  authorization `7ef91b566257371e98529f4db13d2ecce10c2371` passed only for the
  exact authorization tree. Formal validation of implementation head
  `7a8b69bbc34ef6d10c3eb17b1aa8f90e56917d5b` returned
  `CAPABILITY_POLICY_AUTHORIZATION=NOT_AUTHORIZED`: first descendant `d1d276f...`
  changed candidate-bound `scripts/tests/test_validate_capability_policy.py`, and every
  later descendant inherited the violation. Restoring that blob cannot make the v14
  chain releasable because exact authorization `A` runs 42 focused tests with two
  candidate-stage assertion failures. Review 14 records P0 0/P1 1/P2 0 and permanently
  rejects the v14 implementation chain without rejecting its runtime thresholds.
  ADR-011 `capability-v15`, CapabilityModeStoreV4, NativeCloseFenceProofV1,
  RecoveryJournalV5, and the Slice 1B execution contract are therefore a single
  runtime-neutral gate repair: stage-aware test expectations must be frozen before
  candidate `C`, the four v14 gate files remain immutable historical evidence, full
  discovery must pass with identical protected blobs at `C` and authorization `A`, and
  fresh exact reviews plus authorization are required before kernel reapplication.
  Historical v10-v14 rejection and authorization evidence remains preserved.
- A read-only official DataStore 1.2.1 JVM artifact probe passed 5/5 cases: same-process
  duplicate live-owner rejection, cancel-and-join sequential reopen, descriptor-synced
  same-read corruption replacement observed on disk, unresolved reinvocation, one-shot
  CAS permit, and handler-free strict verification. Published bytecode confirms the
  fixed `.tmp` path and descriptor sync but requests `REPLACE_EXISTING`, not
  `ATOMIC_MOVE`; it did not activate the product dependency and does not replace future
  Android integration/crash evidence.
- Official Android API references and hashed API 33/36/37 stubs confirmed the public
  `Os` open/mkdir/read/write/stat/fsync/remove/rename surface, API27+ `O_CLOEXEC`, and API21+
  `O_NOFOLLOW`/`S_ISDIR`. They also proved public `OsConstants.O_DIRECTORY` is absent;
  v13 uses checked component mkdir, read-only no-follow directory open followed by
  fstat `S_ISDIR`, and a separate zero-return EOF probe after frozen-length reads. API26/27
  linkage plus device directory-fsync/process-death behavior remain unverified.
- The owner-only QA portal is deployed at
  `https://motion-arcade-qa-desk.kutaelee0.chatgpt.site`. Main merge
  `0d49efa625d828a5fdfb0e72e1163728d656c015` passed post-merge run `29401938885`.
  Deployed source subtree `f0b914dfa2372547716d269e879a84f53c722bde` and archive
  SHA-256 `8F00364BD79E3EA4C5DBEC125E826E099360D94E0CEEA7B598943450CDE81E35`
  expose no active playable QA: Slice 0A is explicitly an archived non-playable
  technical baseline. Missing human responses leave physical/release gates open while
  unrelated policy, implementation, and automated checks continue.

## Active stop conditions

- Do not change the bundled pose model unless source, version, SHA-256, license,
  redistribution status, lock/verification metadata, and packaged-model tests are
  updated together.
- Do not generate non-anchor art before all four anchor boards receive human approval
  and a locked revision.
- Do not invent missing asset pivot/collision metadata or registry-external raster
  prompts; those require an approved SSOT revision.
- Do not claim G3-G5, G8, or G9 from emulator or synthetic evidence alone.
- Do not create `slice-1b-accepted` without a physical 640 x 480 solo probe, or the
  deterministic nearest supported aspect-equivalent fallback with actual sizes
  recorded, and, when dual is requested, a separate physical dual probe. Emulator
  evidence is mechanism evidence only.

## Rollback direction

Each implementation stage receives a start tag and an acceptance tag. Rollback changes
only code/tag state and never deletes any RuntimeArtifactId mode-store or journal shard.
A rollback target without a compatible reader leaves those bytes inert and unopened; a
future exact artifact reinstall must recover them before service. Out-of-band app-data
clear is destructive test reset only and is not rollback or recovery evidence.
