# Slice 1B execution contract

## Objective

Implement an offline first-run capability probe that executes the actual CameraX and
packaged Pose Landmarker workload, measures representative one-/two-pose CPU/GPU
support without a device allowlist, and emits deterministic scoped evidence under
ADR-011 `capability-v15`.

## Context and downstream actor

Original Slice 1 needs an exact camera/rotation/crop/resolution/mode result before
motion input. The probe must distinguish supported, incompatible, unsupported,
incomplete, and unverified scopes without extrapolating empty-scene, emulator, or
single-configuration evidence. Rejected revisions v1-v14 remain evidence; no code
implementation starts from a v15 candidate pass. It starts only after the exact
candidate, three-review, authorization-only transition below is mechanically validated.

## Scope

- pure typed clocks, deadlines, windows, statistics, occupancy, failure, accounting,
  outcomes, recovery, and cache contracts;
- exact installed-artifact/workload/build identity and public Camera2 change token;
- independent solo/dual CPU/GPU candidates, valid slow representative selection, and
  mandatory maximum-one safe CPU fallback;
- explicit pinned NPU-unavailable result;
- bounded crash-safe recovery set and cache commit protocol;
- separate submitted/callback MPImage ownership and late-submission cleanup;
- serial LIVE_STREAM owner/state gate/watchdogs and non-blocking teardown;
- CameraX Preview + RGBA ImageAnalysis using accepted Slice 1A rules;
- aggregate-only profile/journal, first-run occupancy/restart UI;
- manifest-driven human-QA portal with hash-labelled APK download, exact observation
  tasks, and GitHub issue evidence submission;
- unit, instrumentation, physical, privacy, performance, supply-chain, and evidence
  validation.

## Non-goals

- skeleton overlay, gestures, player identity, game rules, or scoring;
- mixing scope/configuration changes inside one measurement;
- treating `numPoses=2` or empty-scene compute as physical dual proof;
- treating ML success or a progress animation as full-effects/G8 proof;
- runtime model download, analytics, network, raw capture/export, or new permission;
- raster assets or ImageGen calls. The visual-asset approval gate remains closed.

## Inputs and dependencies

- ZIP SHA-256:
  `C998B9EB5F102F231BB626F50864C881E7D0E83E9ADEE5868E363D4FF8094330`;
- accepted `slice-1a-accepted` and rollback `slice-1b-start` tags;
- ADR-011 v15, `docs/contracts/capability-store-v4.md`,
  `docs/contracts/recovery-journal-v5.md`,
  `docs/contracts/native-close-fence-proof-v1.md`, ADR-007/010, and rejected-review
  records 01-14;
- candidate review-control manifest
  `docs/contracts/capability-policy-authority-v15.json`, which hashes the exact five
  current authority files plus rejected-review records 01-14 and permanently remains
  `implementation_authorized=false`;
- pinned CameraX 1.6.1, MediaPipe Tasks Vision 0.10.35, and model SHA-256
  `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`;
- DataStore/protobuf activation only after lock/verification/license/removal review;
  journal state uses only app-owned CheckedAtomicReplaceV1 and never platform
  `android.util.AtomicFile`.

## Authorization transition contract

### Candidate stage

The clean candidate commit `C` and its exact tree `T` include the v15 manifest, five
ordered authorities, final v15 precommit ledger, authorization validator/tests, and no
implementation delta. Before `C` exists, worktree validation is a diagnostic only and
cannot be a same-byte or authorization gate. After `C` exists, the formal candidate
validator reads the immutable Git commit/tree objects with replacement objects disabled
and does not dereference governed worktree paths. It rejects the four reserved
authorization/review paths named in ADR-011, every whole-path alias, and every ancestor
component whose NFKC-plus-casefold form aliases a governed namespace while using a
different spelling. Required inputs must be exact mode-100644 blobs. The manifest retains
`status=CANDIDATE_REREVIEW_REQUIRED` and `implementation_authorized=false` forever.
Candidate validation or review alone grants no implementation permission.

The exact v14 authorization record and three v14 exact-review files are immutable
historical inputs in `C`. The v15 validator rejects their omission, mutation, mode
change, or portable filename alias. Only the four v15 paths are reserved and absent at
candidate stage. The candidate already contains authorization-stage-aware test
expectations; the same protected test blobs must pass full discovery before `C`, at
`A`, and at each implementation `H`.

The common canonical `target` object has exactly these keys and meanings:

- `authorities`: the five ordered objects with exact `path` and raw-file `sha256`;
- `authority_manifest`: exact `path` and raw-file `sha256` for the v15 manifest;
- `candidate_commit`: lowercase 40-hex commit `C`;
- `candidate_revision`: exactly `capability-v15`;
- `candidate_tree`: lowercase 40-hex tree `T`;
- `git_object_format`: exactly `sha1`;
- `precommit_ledger`: exact `path` and raw-file `sha256` for
  `docs/evidence/runs/2026-07-15-slice-1b-policy/v15-precommit-validation.md`.

`target_sha256` is SHA-256 of sorted compact canonical target JSON plus one LF.

### Exact reviews

Each of the three fixed review paths is strict UTF-8 canonical sorted compact JSON plus
one LF, at most 65,536 bytes, with no BOM, duplicate/unknown key, symlink, reparse point,
submodule, or non-regular mode. Its top-level keys are exactly `finding_counts`,
`read_only`, `repository_mutated`, `review_id`, `review_lane`, `reviewer_id`, `schema`,
`session_id`, `target`, `target_sha256`, and `verdict`.

- `schema` is `capability-policy-exact-review-v1`;
- ordered `review_id` values are `01`, `02`, and `03`;
- lane set is exactly `authority-and-contract`, `validator-adversarial`, and
  `evidence-and-governance`;
- reviewer IDs each match `^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$` and are distinct;
- session IDs are 32 lowercase hexadecimal characters, each distinct;
- `read_only=true`, `repository_mutated=false`;
- `finding_counts` has exactly integer `p0=0`, `p1=0`, and `p2=0`;
- `verdict=APPROVE_NO_ACTIONABLE_FINDINGS`;
- all three target objects and target hashes are identical and bind `C` and `T`.

The reviews hash neither themselves, one another, nor the authorization record. A
conditional verdict, missing review, nonzero finding, duplicate identity/session/lane,
or different target rejects v15 and forbids creation of an authorization record.

### Authorization-only commit

Only after all three reviews satisfy the exact schema may a single-parent commit `A`
whose parent is exactly `C` add the three reviews and
`docs/contracts/capability-policy-authorization-v15.json`. The full `C..A` delta is
exactly those four new mode-100644 regular files; it contains no modification, deletion,
rename, symlink, submodule, Android source, validator, authority, manifest, or ledger
change.

The authorization record is strict UTF-8 canonical sorted compact JSON plus one LF,
at most 65,536 bytes, with exactly `authorization_scope`, `excluded_claims`,
`implementation_authorized`, `quorum`, `rollback_baseline`, `schema`, `status`,
`target`, and `target_sha256`.

- If and only if the three-review condition is satisfied, the record carries
  `schema=capability-policy-authorization-v1`, `status=AUTHORIZED`, and
  `implementation_authorized=true`;
- `authorization_scope=SLICE_1B_IMPLEMENTATION_ONLY`;
- `excluded_claims` is the ordered list `SLICE_1B_ACCEPTED`, `G2`, `G6`, `G8`,
  `PHYSICAL_DEVICE_VALIDATED`, `RELEASE_READY`;
- target and target hash are byte-equivalent to all three reviews;
- `quorum` has exactly `required`, `reviews`, and `rule`; `required=3`; `rule` is
  `ALL_THREE_APPROVE_ZERO_FINDINGS_DISTINCT_REVIEWER_SESSION_AND_LANE`; and the three
  ordered `reviews` rows each have exactly `path` and `sha256` for fixed paths 01/02/03;
- `rollback_baseline` has exactly `tag=slice-1b-start`,
  `tag_object=776d3717bc0bf5dc6e93f593256530214a334101`,
  `commit=97e0c21345945cd55451406ef947537d465fc902`, and
  `tree=83c7a194aa9f6fc839b5aff55fb823a94eaf7400`; the formal validator reads the tag
  object payload, peeled commit object, and tree by those exact immutable IDs. Live tag
  ref equality is a separate operational rollback/release check.

The record omits `A`, so it has no self-hash cycle. Authorization validation requires an
explicit lowercase 40-hex `H`: `H=A` initially and the exact implementation descendant
later. From literal Git commit objects it derives `A` as the first child of `C` on `H`'s
single-parent chain, then checks one parent exactly `C`, exact object bytes, the exact
four-file diff, and every bound byte. It never reads symbolic `HEAD`, the index,
worktree, or a mutable tag ref. Later implementation commits use that linear chain and
retain the manifest, five authorities, ledger,
record, three exact reviews, manifest-bound rejected reviews 01-14, and the four
historical v14 gate files byte-for-byte.
They also retain the mode/type/blob identity
from `C` for `scripts/capability_policy_snapshot.py` and its test,
`scripts/capability_policy_markdown.py` and its test,
`scripts/validate_capability_policy.py` and its test, and
`scripts/validate_capability_policy_authorization.py` and its test. The authorization
validator compares those eight entries in `C`, `A`, and the validated `H` tree. Any
additional NFKC-plus-casefold or Windows trailing-space/dot alias of a protected
candidate/authorization path is forbidden in `A` and every later tree. Any ambiguity,
mutation, extra diff, failed review, or unverified ancestry stops work and
requires a new revision. This transition permits only Slice 1B implementation; it does
not accept Slice 1B or pass a physical-device, human-QA, asset, performance, security,
license, or release gate. The four gate files are evidence, not a sixth policy authority
or NativeClosePolicySetV1 member.

Before a human or agent starts edits, a separate operational check must observe a clean
index/worktree, current `HEAD=H`, and live `refs/tags/slice-1b-start` equal to the fixed
tag object. Failure stops local work or release but does not alter the immutable-object
authorization verdict for explicit `C` and `H`.

## Impacted components

| Component | Impact |
| --- | --- |
| `:vision` | Pure domain, RGBA adapter, runtime owner, state gate, recovery, metrics |
| `:app` | Artifact hashing, permission/occupancy/restart UI, FrameMetrics, persistence |
| `:game-core` | Stable ML/effect availability and reason types only |
| `:games` | No change |
| Supply chain | Activated bytes locked, verified, and license-audited |
| Privacy | Volatile image resources; only aggregate/categorical data persists |

## Invariants

- CameraSessionController alone binds camera; Slice 1A analyzer/sequencer stay live.
- The candidate authority manifest is a machine-checked review stop gate, not a sixth
  runtime policy file. It is excluded from NativeClosePolicySetV1 so later review-status
  evidence cannot create a policy/proof hash cycle. Any byte change in one of the five
  authorities invalidates the manifest and requires a new exact review target.
- Analysis is RGBA_8888 + latest-only and has one inference outstanding.
- Exact installed base/split APK bytes produce RuntimeArtifactId before native create;
  PackageManager pair/count/name/path ambiguity or inability to hash forbids native work.
- Generated WorkloadBuildManifest v3 has exact 15 fields and all pinned variant
  runtime artifacts/build files/lockfiles; ProbeBaseScope v2 has exact 33 fields.
- CapabilityModeStore v4 physically shards solo/dual integrity and IO owners, is strict,
  unique/sorted/current-build bounded, and keyed by raw ProbeBaseScopeId; corruption
  blocks only the requested mode rather than becoming a cache miss.
- Pinned candidates are CPU/GPU. NPU is `UNAVAILABLE_PINNED_API`, never benchmarked or
  labelled from empty acceleration.
- Exact one/two pose occupancy, not `numPoses`, validates representative workload.
- One exact-capacity direct buffer has position 0/limit capacity. Returned submission
  cleanup closes input, zeros full capacity, and releases once.
- Late submission return performs generation-guarded cleanup but can never revive
  tier/cache; analyzer proxy closes at deadline.
- Callback Bitmap MPImage is distinct and closes on every result path before barrier.
- Every pinned ErrorListener is callback-output-undelivered/resource-uncertain,
  quarantines its captured route, and permits no same-process fallback or cache.
- Result/ErrorListener entry shares one generation admission gate and in-flight counter.
  NativeCloseFenceProofV1 freezes the exact five-file policy set plus final pre-asset
  DEX/JNI for every registered variant. Each content-addressed BundleV1 carries its
  exact canonical ProofBasis preimage/hash plus evidence and independent zero-finding
  approval; distinct reconstructed build variants may share one registry, but runtime
  uses only its Workload-embedded exact basis. Each APK first passes exact-EOF EOCD,
  complete central range/count, contiguous unique local ranges, and optional structurally
  framed APK Signing Block whole-file grammar. ZIP flags/method/CRC/sizes are local/
  central-equal, bit 3/ZIP64 are rejected, and only empty/zero local alignment padding is
  accepted. One exact-basis/current-API row for every ABI JNI set in a required nonempty
  list is validated before journal persistence; only a timely route reservation
  may then construct a fresh one-use snapshot holding tuple-preserving canonical rows,
  whose sole CAS precedes each native create;
  mismatch means zero native calls. Close return, zero in-flight, and seal precede
  persistence/service. An observed
  post-seal entry revokes same-process use and attempts mode-wide poison without claiming
  retroactive ordering before a completed final clear.
- Runtime create/detect/normal close share one owner. No app-owned lock spans native/
  dependency calls, waits, image/buffer cleanup, or teardown.
- Uncertain resource state requires clean-process recovery; no same-process fallback.
- Deadline starts/ends and strict expiry (`now > deadline`) follow ADR-011's table.
- Artifact/workload/model identity expires generation-authority after five seconds;
  a late read cannot authorize native work.
- Every fresh runtime starts timestamp epoch UNSET/-1, first accepted source maps to
  task timestamp zero, and checked monotonic/range/`*1000` rules precede dependency use.
- Journal/store reads and mutations have exact owners/deadlines; timeout poisons only
  the affected mode persistence generation and late old/new bytes wait for clean-launch
  strict recovery. The one deadline timestamp is captured before enqueue and never
  restarts on owner entry.
- Directory bootstrap, selected-journal NIO enumeration, DataStore parent-identity
  freeze/recheck, cleanup, and final verification all run inside that same captured
  read/mutation token. The pinned dependency's exact post-bootstrap
  `createParentDirectories` call is non-authoritative and a parent identity change
  blocks completion.
- Persistence is single-process: source, merged manifest, and packaged manifest forbid
  every `android:process`, `isolatedProcess`, and legacy multiprocess provider. A second
  app process invalidates the owner/atomicity proof and blocks native/cache service.
- New source admission closes exactly at window end; post-end callbacks are diagnostic.
- Every admitted frame has exactly one source and processing disposition.
- Candidate/steady/mode/runtime/delegate/fallback statistics never mix.
- Valid support has zero rejection/error/abort and representative FPS/tails.
- Safe selected GPU failure triggers exactly one CPU route; valid low does not.
- Recovery set preserves prior CPU/GPU state while active changes.
- RecoveryJournalV5 binds every route to a nonzero attempt epoch and persists exact
  TerminalProofV1 bytes plus ActiveV5 retry/context provenance. Fresh/resume use retry zero;
  every active/terminal route in a manual epoch uses retry one and cross-validates its
  origin-bound ManualRetryContextV1 hash even after the target marker becomes terminal.
  Fresh, resume, abandon, and between-runtime cancellation never
  mix proofs or retry state across epochs.
- Solo/dual journal, mode-store, serializer, owner, corruption, reset, and timeout
  domains are physical shards; solo service never opens dual persistence.
- First-run persistence directories use exact component-by-component public `Os.mkdir`
  on ENOENT plus lstat/open/fstat/fsync/close revalidation; no unchecked mkdirs or other-
  mode construction is allowed.
- Every same-artifact/mode active is reconciled for its stored scope before current-
  scope cache lookup; active is never overwritten by a newly selected scope.
- Journal recovery precedes cache; result save follows clean close + active clearance.
- Pending save binds expected-old complete-file absence/length/hash, exact intended-new
  complete-file length/hash, embedded canonical-record hash, and retry context ID; same
  result ID/delegate cannot prove commit. After update, owner cancel-and-join precedes
  ModeStoreDiskVerifierV1 fresh tagged ABSENT/PRESENT read-only evidence and final-clear
  dual verification. PRESENT requires positive reads to frozen length then exact zero
  from a separate EOF probe;
  the verifier never constructs another DataStore owner.
- Solo and dual cache independently; valid solo survives every dual outcome.
- Quarantine/resource uncertainty outranks Unsupported; only all proven-clean terminal
  routes or a permanent scoped incompatibility may persist Unsupported/NONE.
- Persisted fallback traces have a finite grammar; rank certificates, terminal proofs,
  Unsupported proofs, a quartile/global joint witness, and total system-metric tuples
  are reader-validated.
- ML capability and effect capability are separate. Slice 1B effects stay conservative.
- Device names are neither tier inputs nor profile/journal fields.
- No INTERNET, dynamic model, analytics, new permission, or visual asset.

## Execution slices

1. Freeze and independently approve policy/schema/eval/evidence contracts.
   - Expected: v1-v14 findings have total v15 rules and counterexample fixtures.
   - Validation: immutable SSOT plus pinned AAR/JNI/API review.
   - Evidence: fourteen rejected records (01-14), clean exact candidate commit/tree,
     three zero-finding exact reviews, and the authorization-only commit/record.
   - Rollback: `slice-1b-start`.
   - Stop: any actionable P0/P1/P2 or hidden implementation decision.
2. Implement pure capability/recovery domain.
   - Expected: strong clocks/deadlines/artifact/workload/scope/result manifests,
     counts, occupancy, window/accounting/failure/fallback, journal/cache state,
     outcomes.
   - Validation: boundary, malformed, metamorphic, property, privacy tests.
   - Evidence: forced JUnit XML/log and inventory.
   - Rollback: remove pure package.
   - Stop: contradictory event trace can tier/cache or erase recovery state.
3. Implement runtime ownership and artifact/recovery adapters.
   - Expected: APK-set hashing, mode-sharded RecoveryJournalV5, owner, two-image lifecycle,
     result correlation, restart-only ErrorListener, state gate, watchdog, late cleanup,
     safe CPU route.
   - Validation: fake clocks/executors/files/runtimes plus pinned instrumentation.
   - Evidence: categorical timelines only; no pixels/landmarks/paths.
   - Rollback: remove adapters without altering pure policy.
   - Stop: false NPU, retained reusable buffer, unclosed output, UI join, repeated
     crash, resource-uncertain fallback, dynamic model.
4. Implement camera/measurement/render path.
   - Expected: permission bind, exact requested/actual guarded scope, REALTIME age,
     occupancy UI, source-close/drain/freeze, PSS/thermal/render collectors.
   - Validation: controller/analyzer/stride/crop/scope/deadline/FrameMetrics tests and
     emulator mechanisms.
   - Evidence: redacted aggregate traces and sensitive scan.
   - Rollback: remove composition.
   - Stop: empty-scene tier, unknown timebase, background camera, scope mixing, 1A
     bypass, or post-end submission.
5. Implement first-run UI and repositories.
   - Expected: permission/progress/reposition/cancel/error/restart, dual opt-in,
     evidence reason, independent solo save, recheck, corruption recovery.
   - Validation: state/repository/UI unit + instrumentation tests.
   - Evidence: screenshots and aggregate fixtures.
   - Rollback: code/tag transition to `slice-1b-start`; remove UI/repositories while
     leaving every existing solo/dual journal and mode-store byte unopened and unchanged.
   - Stop: silent mode/effect exposure, duplicate cache, unbounded retry, generic map,
     unreviewed dependency.
6. Validate, review, and bind evidence.
   - Expected: forced tests/lint/policy/CI and independent code review clean; physical
     status explicit.
   - Evidence: exact commit/tree, immutable logs/XML, manifests, distributions,
     screenshots/configuration, exact-head CI.
   - Stop: physical/effect extrapolation, hidden failure, partial claimed complete,
     missing rollback/evidence.

## Acceptance criteria

### Artifact, workload, delegate, and scope

- RuntimeArtifactId hashes exact index-paired installed base/split bytes using canonical
  v1 fields; one-byte mutation changes it. Count/name/path/read ambiguity prevents native create.
- WorkloadBuildManifest v3 (15 fields), RuntimeBuildId, ProbeBaseScope v2 (33 fields),
  and CapabilityResultId implement exact domain/order/length/encoding and bind all
  named inputs without host paths.
- Generated workload bytes/hash are independently recomputed; missing/malformed/hash-
  mismatched resource and five-second identity timeout prevent native create.
- NativeCloseFenceProofV1 freezes final pre-asset DEX/JNI outputs, uses canonical
  five-file PolicySet bytes and per-variant content-addressed BundleV1 containing exact
  ProofBasis bytes/hash, evidence, and an independent zero-finding approval. Generation
  reconstructs every registered variant, admits at most one basis per canonical variant,
  and produces registry/workload without rerunning code transforms. Every final packaged
  APK must match its code snapshot and the whole-file EOCD/central/local/signing-block
  grammar. Runtime recomputes installed DEX/JNI images, validates the Workload-embedded
  acyclic ProofBasis, ignores other-variant rows, and has one exact-basis/current-API row
  for every member of a nonempty installed ABI JNI list with CPU/GPU mask 0x03. Registry
  fields must equal the carried basis, parsed EvidenceV1 tuple/claim, and current
  PolicySet. Every route uses a newly incremented one-CAS
  snapshot that is destroyed after clean close; any lifecycle or match failure invokes
  zero native calls.
- CapabilityModeStore v4 has independent solo/dual files, serializers, owners,
  checksums, generations, reset permits, and max 64 current-build records per mode.
  A corrupt/hung/reset dual path never opens or changes a strict solo file/journal.
- Each mode validates canonical scope/result hashes, strict enums, sorted unique keys,
  envelope checksum/re-encoding/size, finite fallback grammar, terminal/Unsupported
  proofs, rank certificates, and total collector tuples. Corruption is incomplete.
- Exact normative fields/enums/recomputation/privacy match
  `docs/contracts/capability-store-v4.md`; v2/v3 are never read and no implementation-
  defined persisted field exists.
- Explicit reset uses journal REQUESTED/RUNNING plus a one-shot handler owner, bounded
  cancel-and-join, then a non-overlapping handler-free strict verifier. It replaces only
  the confirmed mode store, changes only that selected journal's `last_epoch` and
  `mode_control`, preserves its entries, and never opens/changes the other mode's
  journal/store, calibration, or game state; corrupt
  journal and post-seal poison have no same-artifact reset.
- Same options factory creates candidate, selected, fallback, and shipped runtime.
- CPU/GPU proto artifact tests prove exact acceleration. NPU enum produces no runtime,
  result key, ranking, or fallback and is reported pinned-API unavailable.
- Resolution/crop selector and `preview-v2` actual transformation scope are exact.
  Any camera/token/lens/size/rotation/crop/preview change aborts/rebinds.
- Proxy-only RGBA preflight occurs before native work; only the same static layout
  violation on the first callback after one exact controlled rebind is Unsupported.
  A different/later conversion failure is incomplete.
- Public opted-in Camera2 ID is only a hashed/re-read change token; restricted API or
  suppression is absent. REALTIME source is mandatory.

### Pixels, owner, callback, and cleanup

- Plane/layout/capacity/rotation/crop are checked before full RGBA copy to an exact
  capacity buffer with position 0/limit capacity.
- Normal detect return/throw closes submitted input, absolute-zeros full capacity,
  releases once, then result wait proceeds. JNI-copy artifact test binds this ordering.
- Submission expiry transfers lease cleanup to generation-bound owner, returns analyzer
  so proxy closes, and marks restart-only. A later owner return exact-once closes/
  zeros/releases without state/cache revival; permanent hang never reuses the buffer.
- Every callback-output Bitmap MPImage closes in listener finally before barrier on
  match/mismatch/stale/duplicate/invalid/late. App creates no Bitmap input.
- Every ErrorListener origin/currentness/outstanding combination is restart-only
  callback-output-undelivered. Current task receives one callback-resource disposition;
  stale/no-task changes no frozen counter but still revokes result/cache and quarantines.
- Result must echo exact timestamp/generation. Each fresh runtime maps its first valid
  source to task timestamp zero, never resets across phases, checks the pinned
  microsecond multiplication ceiling, and discards its epoch on teardown. Prior equal
  to the ceiling is exactly TASK_TIMESTAMP_EXHAUSTED; invalid input/conversion alone is
  TASK_TIMESTAMP_INVALID.
- Result/error before `SUBMISSION_RETURNED` is callback-order-invalid incomplete and
  cannot be reinterpreted after return; callback output still closes and analyzer
  release still waits for normal input cleanup or submission-timeout ownership transfer.
- Runtime owner is distinct from camera/callback/state/lifecycle/journal/watchdog.
  Dependency-internal synchronization is allowed; no app-owned lock crosses external
  work. Main/analyzer/lifecycle never join teardown.
- ErrorListener, callback disposal, input cleanup, or close uncertainty requires
  restart/quarantine and cannot start CPU in the same process. A clean-terminal proof
  requires close return and listener seal with zero ErrorListener events.

### Deadlines, windows, occupancy, and accounting

- Every ADR deadline fixture names exact start/completion/deadline, admits equality,
  expires only when `now > deadline`, and tests exact/+1 ns ordering. Persistence has
  one pre-enqueue timestamp including queue delay; owner entry cannot reset it.
- Journal/store mutation distinguishes pre-commit expiry from post-authorization
  unknown old/new; timed-out FINALIZING terminates restart-required rather than hanging.
- PSS has one process owner/call maximum, 500 ms tokens, no queue/replacement/join, and
  state-inert late return. Candidate timing is exactly eight named tokens; selected
  timing is 13. Its bounded defect result may be partial/unavailable.
- Finalizer at equality is a no-op; a reserved callback owns cleanup until its disposal
  deadline, while `+1 ns` finalizer assigns the one terminal disposition and freezes.
- Five warm-ups are five sequential successful correlated callbacks within both task
  and whole-phase deadlines; they are excluded from measurement.
- Source admission is `[start,end)`. Post-end/drain callbacks are never submitted and
  increment only outside-window diagnostic. Already-admitted tasks alone may drain.
- Idle anchor is exact max(last callback, last task terminal, phase entered). Pure
  0..3 statistic branches are distinct from runtime evidence; four positive quartiles
  make 4..29 the only reachable persisted below-30 range.
- Solo window observes poseCount 1 and dual poseCount 2 at least once in every quartile;
  otherwise mode is workload-not-present incomplete with no fallback/cache/tier.
- Pose occupancy is aggregate-only. Blank-wall solo and one-person dual cannot support.
- Window conservation equations hold for normal/error/timeout/abort/finalizer races;
  outside-window and upstream-unavailable diagnostics are excluded explicitly.
- Upstream CameraX overwrite is never coerced to zero; delivered-frame ML may classify,
  but drop evidence remains release-partial and cannot pass G8 alone.
- State gate linearizes source/submission/callback/error/lifecycle/watchdog/finalizer;
  callback at exact deadline and finalizer exact/+1 ns have one result.

### Recovery, cache, and fallback

- RecoveryJournalV5 has separate parent-checked current-artifact solo/dual files and IO
  owners. Each checksum-bound file has fixed mode, optional active, sorted max-eight
  per-base/delegate entries, optional pending store commit, monotonic local epoch, and
  fixed-capacity reset/post-seal control.
- The exhaustive state/reason/retry/cache/active matrix rejects every unlisted tuple;
  safe terminal and resource-uncertain reasons cannot cross, FALLBACK is CPU-only, and
  generic ErrorListener is absent. ActiveV5 carries its own retry bit/context ID; a
  retry-1 active and every same-epoch terminal/manual entry require the exact matching
  ManualRetryContextV1 hash, while retry zero requires ABSENT. Consumed tombstones retain
  the hash after the payload context clears.
- Any same-artifact/mode active is reconciled before that mode lookup. ACTIVE/
  TEARDOWN_PENDING becomes quarantine with exact deletion pending; delete commits before
  journal bit. No durable intentional-abort state exists; active remains through cleanup.
- CPU failure → GPU active/crash and GPU crash → CPU active/crash preserve both states
  across launches and cannot alternate forever.
- Manual retry is one authorization per exact retry-0 quarantine entry. Only
  cancellation before authorization consumes nothing; retry-1 terminal/manual state
  converts to an absorbing RETRY_CONSUMED tombstone on abandon, interruption, or
  uncommitted save before its context clears. Fresh start requires no live retry-1
  context/state and retains existing tombstones. That exact artifact/mode/base/delegate tombstone
  survives recheck and strict final success for the artifact shard; a successful manual
  retry instead resolves its original quarantine through the selected record. Every
  delegate executed in that manual epoch carries retry one, and recovery consumes the
  whole retry-1 epoch deterministically rather than inferring provenance from order.
- Fresh, proof-prefix resume, quarantine-route retry, abandon, and between-runtime
  cancellation have exact attempt epochs. Quarantine retry always allocates a new epoch,
  never reuses quarantine-era proof/metrics, and preserves other tombstones/quarantines.
  Safe terminal entries contain full canonical proof bytes; cross-epoch use is corrupt.
- Pre-create journal failure invokes no native. Post-create journal failure, active
  uncertainty, or recovery overflow creates no tier/cache/new runtime.
- Every close durably writes TEARDOWN_PENDING immediately before native close. A
  controlled abort retains active while releasing every owner/barrier; only a proven
  clean final mutation clears/abandons it. Manual cancellation retains retry 1; no
  close crash can hide.
- Cache save starts only after clean native/render resource closure, thermal cutoff and
  current-status safety check, and durable active absence.
  Journal pending-store prepare retains same-epoch proof/retry entries and binds exact
  expected-old whole-file absence/length/hash, intended-new whole-file length/hash,
  intended record hash, and retry context ID. One mode-store update writes old/new.
  After update, the sole owner is cancelled/joined and a read-only direct backing-file
  verifier proves intended new; then a retention-
  preserving final journal clear and fresh direct reread of both journal and store
  authorize serving. Final clear never deletes
  QUARANTINED/RETRY_CONSUMED; every skipped trace still requires its exact retained
  entry.
  Selected-delegate recovery/pending state wins over cache; other-delegate quarantine
  remains scoped.
- Every persistence read/reset/delete/save has its exact deadline and commit token.
  Clean-process mode-store and selected journal-directory bootstrap uses public
  `Os.mkdir(path,0700)` only for exact ENOENT, then validates and fsyncs each child/
  parent; `File.mkdirs`, unchecked booleans, and other-mode construction are forbidden.
  Journal writes use CheckedAtomicReplaceV1 on API26-37: checked same-directory `.next`
  create with O_NOFOLLOW, checked partial-write loop/fsync/close/reread, authorization
  immediately before the sole public `Os.rename(next, base)`, checked parent-directory
  fsync through O_RDONLY|O_NOFOLLOW plus fstat S_ISDIR (never unavailable O_DIRECTORY),
  and a fresh exact intended-base reread with `.next` absent. API26 omits
  O_CLOEXEC; API27+ requires it.
  Platform `android.util.AtomicFile`, `.bak`, copy, delete-base, and fallback rename are
  forbidden. Pre-authorization cleanup preserves prior only after checked public
  `Os.remove` plus
  direct exact-old/absence verification. Timeout ends UI as outcome-unknown/restart-
  required. Normal save cancels and joins the sole DataStore owner after its update,
  checks the mode-store parent-directory fsync, then ModeStoreDiskVerifierV1 reads with
  no live owner. Its tagged ABSENT is legal only in named first-run/expected-old cases;
  PRESENT reads positive progress to frozen length, requires zero on a separate one-byte
  EOF probe, and requires DataStore's fixed `<base>.tmp` absent plus exact intended
  whole-file length/hash. It is read-only and not a
  DataStore instance. A handler-free successor may open only after final dual verify and
  must first read the exact verified payload. Reset likewise uses bounded cancel-and-
  join before a sequential non-overlapping DataStore verifier owner. Next launch
  accepts only exact intended-new full-file identity and embedded record, recognizes
  exact expected-old full-file identity/absence as not committed, and treats any third
  or unrelated-record mutation as
  corruption while retaining retry 1 where consumed.
- RESET_REQUESTED becomes RESET_RUNNING before a one-shot handler owner. Successful
  reset-owner cancel-and-join precedes parent fsync, owner-free direct verification,
  a handler-free strict owner, its join, and final direct verification; interrupted
  RUNNING never automatically rearms. Control epoch equals monotonic last epoch through
  REQUESTED/RUNNING and every explicit rearm checked-increments both. Duplicate live
  ownership is forbidden.
- NativeCloseFenceProofV1 binds the frozen DEX/JNI image to content-addressed evidence
  and the exact five-file PolicySet to a basis-carrying content-addressed BundleV1 and an
  independent zero-finding approval, then independently reconstructs every registered
  variant and verifies its whole-file APK plus final packaged DEX/JNI equality. Exact
  Workload-embedded-basis and current-API matching for every installed ABI JNI set in a
  nonempty list produce one immutable route-generation snapshot; other-variant rows are
  inert and its sole CAS
  precedes one native create and clean close destroys it before a later route rebuild.
  Close return, callback admission closure, and zero in-flight seal then precede
  save/service. A post-seal
  callback immediately revokes same-process use and attempts mode-wide poison outside
  entry capacity. Only a timely committed poison permanently blocks the artifact/mode;
  the policy makes no kill-before-poison or retroactive-final-clear claim.
- Selected GPU clean create/detect/error/callback-timeout failure must run fresh CPU
  candidate then fresh CPU steady unless CPU already terminal/quarantined. Prior valid
  low CPU remains eligible; CPU has no second retry. Resource uncertainty defers to a
  new process. Valid low/class degradation never falls back.
- Valid solo commits independently before dual. Dual cancel/failure/corruption/timeout/
  reset never opens or mutates solo persistence, and solo remains serviceable.
- Rollback from exact artifact A to the `slice-1b-start` artifact B is a code/tag
  transition only. B has no authority to open, trust, migrate, normalize, or delete A's
  RuntimeArtifactId-sharded solo/dual journals or the solo/dual mode-store files that
  contain A's persisted state. Their exact bytes remain inert for a future reinstall of
  exact A, which must recompute A's identity and perform the normal strict recovery
  before native work or cache service.
- Artifact/profile/journal/scope/occupancy/resource uncertainty is incomplete before
  permanent/no-delegate Unsupported; a quarantined or RETRY_CONSUMED required route
  cannot be Unsupported.

### Rates, outcomes, render, and privacy

- The only functional FPS inputs are cameraCallback, completion, representative
  completion. Accepted and source FPS are diagnostics. Exact rational boundaries pass.
- Representative pose samples provide 30 completions, five/quartile, P95/growth/max
  age limits, capacity one, and zero rejection/error/abort. All-completion metrics are
  separately reported.
- Growth is exact certified Q4-minus-Q1 median representative completion-frame age;
  named representative/all distributions expose self-consistent P50/P90/P95/P99/max
  rank certificates without persisting raw samples. A bounded per-quartile/global
  bucket witness proves that those certificates describe one jointly feasible multiset.
- Candidate ordering is class, representative FPS, representative P95, CPU/GPU.
  Selected fresh steady alone decides the mode.
- ML A/B/C/Unsupported and SOLO_VALID_DUAL_INCOMPLETE follow exact precedence.
  Slice1B EffectCapability is always CONSERVATIVE_UNVERIFIED; no full-effects claim.
- SCOPED_UNSUPPORTED persists only with matching-static-proxy proof or exact CPU+GPU
  clean-terminal proofs, finite trace/hash/masks, and no journal unknown/quarantine/
  RETRY_CONSUMED state.
- FrameMetrics copies scalars immediately, but the public Window adapter has no native
  removal fence: Android removal is asynchronously posted to RenderThread and the
  observer can post to its Handler later. The v15 default therefore closes metric
  mutation after removal, drains/discards through the deadline, and persists exact
  UNAVAILABLE/REMOVAL_FENCE_UNPROVEN zero evidence. ML may persist conservatively; G8
  cannot pass. COMPLETE/PARTIAL render bytes are reader-invalid in capability-v15 and
  require a later policy/schema revision with a checked native happens-before proof and
  tracking-Handler reservation/dispatch/sentinel seal; a finite quiet-period test alone
  is insufficient.
- PSS 13-token conservation maps exactly to complete/partial/unavailable and never waits
  for a timed-out OS call. An absent endpoint bit requires exact zero stored value;
  present requires its successful token, and maximum equalCount covers present endpoint
  multiplicity at that maximum. API29+ thermal uses a process-lifetime wrapper-owned
  FIFO with exactly 64 queued/running callback slots and an app-owned asynchronous
  single drain runner. End snapshot plus an atomic app cutoff freezes only callbacks
  whose `execute()` admission linearized before that cutoff; the listener is not removed
  for measurement and later callbacks remain safety events. Because oneway Binder
  delivery has no unregister fence, every persisted API29+ tuple is exact PARTIAL/
  DELIVERY_FENCE_UNPROVEN with coverage zero, both endpoints, histogram multiplicity,
  max <=SEVERE(3), and no known critical; COMPLETE/one-endpoint partial are reader-
  invalid. The 65th callback, runner/cutoff/status failure, or known critical creates no
  record and sets an absorbing same-process latch. Current-status checks gate every
  native create, pending, serve, start, and resume; late critical pauses rather than
  mutating historical bytes, and a later lower status cannot clear the latch. API26-28 is
  unavailable and G8 remains blocked.
- Profile/journal serializers are whitelist-only and reject pixels, Bitmaps, landmarks,
  per-frame pose counts, timestamps/correlation, raw IDs/paths, generic maps, and user/
  device-identifying fields.
- Full test/lint/strict verification/supply-chain/APK permission/model/native/license
  and zero-deployed-asset policy pass.
- Physical actual-scope/occupancy/distribution/release evidence and independent review
  exist before `slice-1b-accepted`.

### Non-blocking human QA

- Every physical/perceptual question is published as a versioned portal task with APK
  filename/SHA-256, build/slice status, device/OS/lens/mode preconditions, observable
  pass/fail choices, required screenshot/video/log evidence, and privacy warning.
- The portal never labels the Slice 0A technical baseline as a completed game and never
  turns an unreviewed form/issue response into a passed gate. Submitted evidence is
  reviewed and hash-bound before it changes G2/G5/G8/G9 status.
- Pending, failed, or absent human results block only the gate whose evidence they are
  required to prove. Policy, implementation, unit/instrumentation, supply-chain,
  security, license, packaging, and other unrelated slices continue independently.
- Visual imagery for the portal is CSS/type only while G6 is closed. It does not create
  an alternate visual-asset source or weaken the style-profile/prompt-registry rule.

## Deterministic evaluation set

- canonical manifest domains/field counts/order/lengths, split-array pairing, missing/
  duplicate/base-name/path split, exact APK/file one-byte mutation, dirty APK identity;
- exact under/at/over workload/journal/profile byte-size caps before allocation;
- workload options/float bits/model/AAR/lock/verification/build mutations, generated-
  resource recomputation and late identity-read generation invalidation;
- NativeCloseFence code-image/registry/bundle canonical bytes, exact content-addressed
  five-file PolicySet and every nested field name/type/order, evidence categories and
  independent approval, exact BundleV1-carried ProofBasis bytes/hash and registry-to-
  Evidence tuple/claim equality, debug/release distinct-basis reconstruction, duplicate-
  variant/random/stale/missing-preimage rejection, acyclic Workload-embedded ProofBasis,
  DEX/JNI Phase-A-to-final-package equality, exact-EOF/comment-zero/single-disk EOCD,
  central count/range exact consumption, adjacent unique local ranges, structurally exact
  optional APK Signing Block, shadow/trailing/alias/overlap rejection, uncompressed APK
  JNI enumeration, exact framed
  API/ABI key ordering, nonempty installed ABI sets/every row, multi-route generation
  validate-before-journal ordering, tuple-preserving authorization rows,
  local/central flag/method/CRC/size equality, data-descriptor/ZIP64 rejection,
  CAS/consume/rebuild, and missing/duplicate/circular/quiet-period-only proof rejection
  with zero native calls before exact match;
- scope/result hash mutation, duplicate/unsorted mode-store keys, unknown enum/schema,
  record/trace overflow, physically isolated mode files/owners, REQUESTED/RUNNING
  one-shot reset, zero/stale/future/equal-last control epochs, explicit rearm increment,
  sequential reset/verifier ownership, pending exact whole-file old/new identities,
  corrupt dual read/reset/timeout with unchanged solo, forbidden poison/journal reset,
  and no v2/v3 read/migration;
- pinned DataStore 1.2.1 duplicate live owner, cancel-and-join sequential reopen,
  corruption-triggering same-read descriptor-synced replacement observed on disk (not
  crash durability), unresolved reinvocation,
  one-shot write claim, handler-free strict verifier, `<base>.tmp` absent/partial/full/
  symlink/nonregular/removal failure, read-before-length zero/separate EOF zero/extra byte,
  parent-directory fsync, and RESET_RUNNING crash recovery;
- CPU/GPU acceleration proto and NPU enum empty-acceleration exclusion;
- resolution rational ties, zero-origin/subcrop, actual negotiated/preview transform,
  per-frame camera/token/rotation/crop/scope drift;
- pre-native RGBA preflight valid/first violation/same rebound/different rebound and
  pre-native proxy-close/analyzer-detach uncertainty;
- RGBA padding/stride/capacity/position/limit/full-capacity zero and JNI copy fixture;
- two MPImages on all callback paths, close throw/hang, dependency failure before
  output handle, no app Bitmap input;
- submission exact/+1 deadline, late return cleanup, permanent hang, proxy close,
  generation reuse/stale callback; every current/stale/no-task/pre-return/post-freeze/
  close ErrorListener is restart/quarantine with zero same-process fallback;
- callback admission/in-flight interleavings at close/seal, close-return zero barrier,
  each proved ABI/API no-future-callback artifact, post-seal callbacks before pending/
  after store/after final clear/after first serve, latch-to-poison crash cuts, poison
  with a full eight-entry list, same-process revoke, and permanent block only after a
  timely durable poison;
- timestamp epoch first zero, phase continuity, fresh-runtime reset, rejected-source
  non-mutation, checked subtraction/+1/range/`*1000`, exact ceiling exhaustion reason,
  invalid conversion reason, echo, and privacy;
- dependency-internal lock with no app-lock deadlock; create/detect/close watchdogs;
- every normative deadline exact/+1, source close at end, post-end callback, admitted
  drain completion/disposal, warm-up reservation crossing phase end, fatal/abort/
  freeze adversarial gate order;
- pure 0/1/3 statistic reducers, concrete four-quartile 4/29/30 runtime traces, and
  exact idle anchor;
- blank wall/one pose/two pose, occupancy in missing/all quartiles, representative
  FPS/P95 subsets and aggregate-only serialization;
- 19/18/17 CPU/GPU candidates, all-safe-failed, exact 20/15/10 and inclusive tails;
- high callback/low representative FPS; source-FPS diagnostic cannot change tier;
- all dispositions, abort before/after submission, conservation, zero denominator,
  upstream/outside-window diagnostics;
- recovery active/teardown/pending-store/reset/poison for same/other scope, cross-delegate
  crashes, attempt allocation/resume/fresh-abandon/cancel-between-runtimes, full
  terminal-proof recovery, first-route quarantine-only and dual-quarantine manual-new-
  epoch recovery, cross-epoch rejection, capacity, duplicate/checksum/RuntimeArtifactId/
  mode/path/symlink errors;
- deterministic exact-artifact rollback traces A -> `slice-1b-start` artifact B -> exact
  A with before/after whole-file hashes for A's two RuntimeArtifactId journal files, a
  pre-existing distinct artifact C's two journal files, and the installation's two
  current A-owned solo/dual mode-store files, plus an access ledger proving B opens and
  mutates zero of those six physical persistence files:
  RETRY_CONSUMED remains absorbing with zero native calls for the exact tombstoned
  artifact/mode/base/delegate route while every other legal route follows normal policy;
  POST_SEAL_MODE_POISON remains blocking and resumes only its specified A-owned
  reconciliation; pending intended-new/expected-old/third identities retain their exact
  committed/not-committed/corrupt outcomes; strict solo/dual stores retain their bytes;
  unrelated base scopes, the other mode, and C's two RuntimeArtifactId-sharded journals
  remain byte-identical and unopened; no C-specific mode-store file exists;
- exhaustive legal journal tuples plus one-field mutations, FALLBACK+GPU, generic
  ErrorListener rejection, ActiveV5/context-ID/manual/pending coexistence rules, target
  marker becoming terminal before another retry-1 route, and context mutations;
- every journal/store read/reset/delete/save exact/+1 deadline, pre/post authorization,
  pre-enqueue queue delay without deadline reset, late read, old/new write, poisoned
  owner, next-launch normalization, deletion-before-bit crash, pending intended-new/
  expected-old/third full-file hash including same-result-ID and unrelated-record
  mutations, and bounded FINALIZING;
- CheckedAtomicReplaceV1 API26/API27+ flag selection, no API26 O_CLOEXEC linkage,
  component-by-component mkdir ENOENT/EEXIST/type/fsync/death cuts,
  bounded `Files.newDirectoryStream` name enumeration and iterator/close failures,
  pinned DataStore `mkdirs()+isDirectory()` with parent-identity equality,
  partial/zero/interrupted write, file/directory fsync/close/rename failure, old/absent/
  truncated/third base, residual/symlink/nonregular `.next`, public Os.remove failure,
  exact clean commit token, deadline around authorization/reread, solo/dual mode-directory
  isolation, static no-AtomicFile and no-multiprocess scan, and every process-death cut;
- manual retry cancel before/after authorization, clean intermediate/terminal/cancel,
  terminal-then-fresh/abandon, uncertain close, quarantine-only, orphan, save failure/
  success, RETRY_CONSUMED conversion, one authorization per exact quarantine, absorbing
  consumed entry with retained context ID, origin hash, target-terminal-then-next-route,
  and save -> owner join -> direct verify -> retention-preserving clear -> dual direct reread with a
  skipped CPU/GPU trace; recheck/capacity never overwrite or erase its tombstone;
- recovery-before-cache, selected-delegate deny, delegate NONE proof, recheck ordering,
  other-delegate quarantine, and solo/dual byte isolation;
- mandatory safe GPU fallback, prior CPU valid/terminal/quarantine, unsafe resource
  restart-only, fresh runtime identities, no second retry;
- solo save then dual cancel/incomplete/corrupt/hung/reset/Unsupported and derived state;
- ML/effect split: ML A plus failed/missing render remains conservative effects;
- rank-certificate count1/rank/impossible/equal/increasing/max tuples, Q1/Q4 growth
  negative/equal/+1/overflow, joint-witness global/quartile conservation/reachability/
  adjacency and the v6 impossible-Q1 counterexample, outcome recomputation, and no raw samples;
- every finite fallback trace, reorder/duplicate/missing terminal proof, static proof
  mismatch/native entry, all-terminal masks/hash, skipped quarantine, ErrorListener exclusion;
- PSS valid/zero/negative/throw/start-timeout/call-timeout/permanent hang/late return/
  poisoned repeated recheck, exact eight-candidate/13-selected schedules, absent endpoint
  value zero, maximum/equal endpoint multiplicity, and every complete/partial/
  unavailable/not-measured tuple;
- FrameMetrics callback conservation/reuse/drop/invalid/duplicate/late/membership and
  every status tuple; public-adapter REMOVAL_FENCE_UNPROVEN zero form, queued-before-
  remove Handler drain, native-post-behind-sentinel counterexample, enqueue false,
  callback throw, and proof-manifest rejection without a native happens-before fence;
- thermal execute-before-cutoff/cutoff-before-execute gate orders, exact 64/65 callback
  capacity, per-generation queued/running conservation, both endpoint commands, cutoff
  after app-admitted callbacks, Binder execute delayed past store/final-clear/first-serve,
  late CRITICAL same-process pause with frozen bytes unchanged, runner/listener/status
  failure, exact PARTIAL/DELIVERY_FENCE_UNPROVEN and API26-28 zero tuples, endpoint
  histogram multiplicity, shutdown unregister non-authority, and no external call while
  the wrapper gate is held;
- sensitive profile/journal rejection, API33/API37 16 KB mechanisms, physical blocker.

## Validation commands

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict :vision:testDebugUnitTest :vision:lintDebug --rerun-tasks
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict test lint :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
python -B -m unittest discover -s scripts\tests -v
# Pre-C mutable-worktree diagnostic only; never an authorization gate.
python -B scripts\validate_capability_policy.py --root .
# After candidate commit C exists, this Git-object validation is the formal gate.
$C = git rev-parse HEAD
python -B scripts\validate_capability_policy.py --root . --commit $C
python -B scripts\validate_capability_policy_authorization.py --root . --stage candidate --candidate-commit $C
# At A, and again for every later implementation commit, pass the exact object H.
$H = git rev-parse HEAD
python -B scripts\validate_capability_policy_authorization.py --root . --stage authorization --candidate-commit $C --head-commit $H
# Separate operational stop before edits/build/release; not an authorization input.
if (git status --porcelain=v1 --untracked-files=all) { throw 'dirty checkout' }
if ((git rev-parse refs/tags/slice-1b-start) -ne '776d3717bc0bf5dc6e93f593256530214a334101') { throw 'rollback tag mismatch' }
python -B scripts\validate_supply_chain.py --root .
python -B scripts\validate_project_assets.py --root .
```

Physical/managed-device commands are discovered from connected targets and Gradle
inventory. Empty `adb devices` is blocker evidence, never a physical pass.

## Required evidence

- diff/inventory/exact commit/tree/rollback tag;
- rollback evidence binding exact A, B, and pre-existing distinct C
  APK/RuntimeArtifactId identities; pre-B and post-B SHA-256/length for A's two journal
  files, C's two journal files, and the installation's two current A-owned solo/dual
  mode-store files; a B persistence-access ledger with zero opens or mutations of those
  six files; and exact-A reinstall recovery traces for RETRY_CONSUMED, poison, pending
  old/new/third, unrelated scope, and solo/dual isolation;
- focused/full forced logs, persisted raw Gradle logs with SHA-256, and JUnit XML;
- complete v15 fixtures, including explicitly diagnostic handle-anchored worktree
  capture, formal immutable Git-object candidate capture that is unaffected by
  post-validation namespace swaps, full-path/ancestor NFKC aliases, final/ancestor
  symlinks and Windows junctions, independent governed-I/O inventory, and normalized
  CommonMark logical-block/clause non-authorization defense-in-depth fixtures; plus
  historical manifest/ledger/review exact-byte freezing, reserved authorization-path
  alias rejection, three-review quorum/identity/target fixtures, and authorization-only
  commit ancestry/diff/cycle fixtures; plus
  canonical artifact/workload/PolicySet/proof-registry/mode-
  store/proof-evidence/approval/journal examples, final-package DEX/JNI equality report,
  plus automated revision-reference consistency;
- two-image/input/late-cleanup/owner/state/watchdog categorical timelines;
- delegate warm-up/candidate/occupancy/select/fallback/crash/recovery timelines;
- actual camera token/size/crop/preview/rotation, timebase, windows, rates/tails,
  dispositions, PSS/thermal/render metrics;
- permission/lifecycle and API33/API37 16 KB instrumentation;
- physical device/SoC/RAM/OS/lens/scope/mode/delegate occupancy distributions or
  explicit adb blocker;
- published QA portal URL, manifest revision, APK download response/hash, issue template,
  and each reviewed human submission or explicit pending/blocker state;
- manifest/network/storage/export/model/APK/native/license/supply-chain checks;
- zero-deployed-asset result, independent review, exact-head Push/PR CI;
- hash-bound evidence manifest.

## Risk, stop, and rollback

Tier 2: result controls mode availability and camera/ML. Stop on artifact ambiguity,
false NPU, empty-scene tier, privacy leak, permission/network change, time/scope mixing,
retained reusable resource, hidden Bitmap input, unclosed callback output, app-lock
deadlock, UI/lifecycle native join, resource-uncertain fallback, recovery-state loss,
invalid cache, device-name decision, effect overclaim, or Slice 1A bypass.

Rollback is only a code/tag transition to `slice-1b-start`. It never deletes, rewrites,
normalizes, migrates, or opens an existing capability profile, mode store, or any
RuntimeArtifactId solo/dual journal shard. Artifact B leaves all artifact-A persistence
inert and byte-preserved; a later reinstall of exact A must observe those exact bytes
and complete the ordinary strict journal/store recovery before native work, cache
service, or success UI. An out-of-band app-data clear is a destructive test reset only:
it is never product rollback, recovery evidence, or a passed acceptance/release gate.
Reverify the Slice 1A tree and accepted Slice 0A APK hash, and attach the deterministic
A -> B -> exact A preservation/recovery evidence named above.

## Final return contract

Report `completed`, `implementation complete; physical-device verification pending`,
`blocked`, or `failed safely`, with actual tests, measurements, evidence, unverified
scopes, and ML/effect reasoning. Code/emulator success cannot create
`slice-1b-accepted`, G2, or G8.
