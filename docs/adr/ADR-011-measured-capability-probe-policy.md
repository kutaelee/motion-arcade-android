# ADR-011: Measured capability probe and quality-tier policy

- Status: Proposed for independent rereview; physical validation pending
- Date: 2026-07-15
- Risk tier: Tier 2 device compatibility and runtime behavior
- Policy revision: `capability-v15`

## Context

The SSOT requires a permission-gated first-run probe that binds the camera, verifies
the packaged model, warms candidate delegates, measures a 640 x 480 solo workload for
ten seconds and, when requested, a distinct dual workload for ten seconds. It fixes
targets of 20 solo FPS and 15 dual FPS, stops dual below 10 FPS, and requires frame
age, tail latency, drops, memory, render, and thermal evidence. Device names are not
tier inputs.

Independent reviews rejected revisions v1-v6. Revision v6 made ErrorListener,
mode-sharded persistence, bounded IO/PSS, retry, trace, and aggregate rules explicit,
but exact review still found ten P1 and four P2 defects. They covered attempt/proof
recovery, exact pending old/new identity, abort crash ordering, joint aggregate
feasibility, callback/thermal barriers, DataStore reset, and several deterministic
edge tuples. A pre-commit v7 rereview then rejected three P1 and three P2 gaps in
consumed-retry survival, quarantine-only recovery, queued thermal admission, recovery
identity, endpoint multiplicity, and reset epochs. Revision v8 closed those items, but
its stable pre-commit audit found four new P1 defects: final clear erased evidence
required by a live skipped trace, thermal close raced executor admission, AtomicFile
completion trusted a void/log-only API, and FrameMetrics removal did not drain Handler
work already posted. Revision v9 closed those four defects but its exact-commit review
still rejected five P1 and two P2 defects: API 26-29 `AtomicFile` used a different
`.bak` crash grammar, public thermal unregister did not fence oneway Binder admission,
post-seal native poison claimed an impossible ordering, active retry provenance and an
exact profile revision byte string were missing, normal-save disk evidence was
undefined, and the execution contract retained stale revision references. Revision
v10 removed the platform AtomicFile dependency, made thermal evidence explicitly
non-authoritative, bound active retry provenance and every revision value, named the
fresh-file verifier, and limited post-fence poison to defense in depth. Revision v11
then closed the alternate Gradle-range, rollback-evidence, whole-APK ZIP, and
ProofBasis-preimage findings, but its pre-commit review rejected one repository-path
containment defect and one authorization/governance prose-lint defect. Revision v12
kept the v11 runtime policy and added path/prose checks, but review 12 rejected a cached-
path junction-swap TOCTOU plus seven prose/test-boundary P2 classes. Revision v13 added
an anchored snapshot and logical Markdown checks, but review 13 rejected five P1 and
eight P2 boundaries: blocking/torn POSIX reads, replaceable historical evidence,
case-aliased reviews, an undefined authorization transition, and malformed/Unicode/
context/parser cases. Revision v14 kept the runtime policy, independently froze
rejected evidence, closes the remaining snapshot/parser boundaries, and defines the
separate exact-candidate authorization transition below. It remains a non-authorizing
candidate and cannot approve physical G2/G8 release gates or human perceptual acceptance.

The v14 candidate and authorization objects remain immutable historical evidence, but
the first implementation descendant changed a candidate-bound policy-test blob. The
formal descendant validator correctly rejected that chain. The exact v14 test blob also
contained two candidate-stage assertions that necessarily fail after the reserved
authorization files are added, so restoring the blob could not make both authorization
and full test discovery pass. Review 14 permanently rejects that implementation chain.
Revision v15 changes no runtime threshold, persistence schema, native-close rule, or
release claim. It moves the authorization-stage test expectations into the candidate
*before* `C`, freezes the four v14 gate files as historical evidence, and requires the
same protected test bytes to pass at both `C` and `A`.

## Decision

### Policy-review authorization boundary

The canonical `capability-policy-authority-v15.json` is permanently a candidate review
subject with `status=CANDIDATE_REREVIEW_REQUIRED` and
`implementation_authorized=false`; neither field is flipped after review. A candidate
validation pass, pre-commit ledger, commit, or single review never grants implementation
permission.

Pre-commit worktree validation is an explicitly non-authorizing diagnostic. A mutable
filesystem namespace can change after any observation, so that diagnostic is never the
formal same-byte gate. Let `C` be the clean v15 candidate commit and `T` be its exact
Git tree. The formal candidate validator reads `C` and `T` from immutable Git objects
with replacement objects disabled; it does not dereference governed worktree paths.
At candidate stage these four reserved paths and every whole-path or ancestor-component
NFKC-plus-casefold alias are absent:

- `docs/contracts/capability-policy-authorization-v15.json`;
- `docs/reviews/slice-1b-policy-v15-exact-review-01.json`;
- `docs/reviews/slice-1b-policy-v15-exact-review-02.json`;
- `docs/reviews/slice-1b-policy-v15-exact-review-03.json`.

The v14 authorization record and its three exact-review files are expected historical
mode-100644 blobs in `C`, `A`, and every later validated tree. Their exact blob identity
is frozen independently of the current v15 reserved paths.

Each exact-review file is canonical compact JSON plus LF with fields exactly
`schema`, `review_id`, `review_lane`, `reviewer_id`, `session_id`, `read_only`,
`repository_mutated`, `finding_counts`, `verdict`, `target`, and `target_sha256`.
`schema=capability-policy-exact-review-v1`; each `reviewer_id` matches
`^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$` and is therefore 1-64 lowercase ASCII
characters;
`session_id` is 32 lowercase hexadecimal characters; `read_only=true`;
`repository_mutated=false`; `finding_counts` has exactly integer `p0`, `p1`, and `p2`,
all zero; and `verdict=APPROVE_NO_ACTIONABLE_FINDINGS`. The three `review_id`,
`reviewer_id`, `session_id`, and `review_lane` values are each pairwise distinct, and
the lane set is exactly `authority-and-contract`, `validator-adversarial`, and
`evidence-and-governance`.

Every review has the same exact `target`. It contains
`candidate_revision=capability-v15`, `git_object_format=sha1`, lowercase 40-hex
`candidate_commit=C`, lowercase 40-hex `candidate_tree=T`, the manifest path/hash, the
ordered five authority path/hash rows, and the v15 precommit-ledger path/hash.
`target_sha256` is SHA-256 of that target's canonical compact JSON bytes plus one
trailing LF. The review does not hash itself, another review, or the authorization
record.

Only after all three exact reviews pass may one canonical compact JSON+LF record be
added at the authorization path. Its exact fields are `schema`, `status`,
`implementation_authorized`, `authorization_scope`, `excluded_claims`, `target`,
`target_sha256`, `quorum`, and `rollback_baseline`.
If and only if that three-review condition is satisfied, the record carries
`schema=capability-policy-authorization-v1`, `status=AUTHORIZED`,
`implementation_authorized=true`, and
`authorization_scope=SLICE_1B_IMPLEMENTATION_ONLY`. `excluded_claims` is exactly the
ordered list `SLICE_1B_ACCEPTED`, `G2`, `G6`, `G8`,
`PHYSICAL_DEVICE_VALIDATED`, and `RELEASE_READY`. The
target and target hash equal all three reviews. `quorum` has exactly `required`,
`reviews`, and `rule`; `required=3`; `rule` is exactly
`ALL_THREE_APPROVE_ZERO_FINDINGS_DISTINCT_REVIEWER_SESSION_AND_LANE`; and `reviews`
contains the three fixed review paths in 01/02/03 order, each row having exactly `path`
and raw-file `sha256`. `rollback_baseline` has exactly `tag`, `tag_object`, `commit`,
and `tree`: `tag=slice-1b-start`, tag object
`776d3717bc0bf5dc6e93f593256530214a334101`, peeled commit
`97e0c21345945cd55451406ef947537d465fc902`, and tree
`83c7a194aa9f6fc839b5aff55fb823a94eaf7400`. The formal validator reads the exact tag
object, payload, peeled commit object, and tree by those immutable object IDs. A live
`refs/tags/slice-1b-start` equality check remains an operational rollback/release stop,
not an input to object authorization.

The authorization commit `A` has one parent exactly `C`; the `C..A` tree delta adds
only those four regular non-symlink mode-100644 files and changes or deletes no other
path. The record does not contain `A`, so no self-hash cycle exists. The authorization
validator requires an explicit lowercase 40-hex commit `H`: initially `H=A`, and later
`H` is the exact implementation descendant under validation. It derives `A` from the
literal commit-object parent chain of `H`, checks parent/diff and all Git object bytes,
then revalidates the immutable manifest, five authorities, precommit ledger, reviews,
and record. Any reject verdict, nonzero finding, duplicate identity/session/lane, byte
change, extra diff, missing evidence, or validator ambiguity forbids authorization and
requires a new policy revision. Later implementation commits must use a linear
first-parent chain: `A` is the unique first child of `C` on `H`'s literal parent chain
and its complete delta from `C` is the exact four-file addition.
They must preserve every bound policy/evidence byte, including rejected review records
01-14 that the manifest hashes. They must also retain the exact
mode-100644 Git blob identity from `C` for these eight gate implementation/test paths:
`scripts/capability_policy_snapshot.py`,
`scripts/tests/test_capability_policy_snapshot.py`,
`scripts/capability_policy_markdown.py`,
`scripts/tests/test_capability_policy_markdown.py`,
`scripts/validate_capability_policy.py`,
`scripts/tests/test_validate_capability_policy.py`,
`scripts/validate_capability_policy_authorization.py`, and
`scripts/tests/test_validate_capability_policy_authorization.py`. The authorization
validator checks those entries in `C`, `A`, and every later validated `H` tree. The
candidate, authorization, and every later validated tree must contain no additional
NFKC-plus-casefold or Windows trailing-space/dot alias of any protected policy,
evidence, validator, review, or authorization path. The
four authorization/review files are
not a sixth authority, are excluded from NativeClosePolicySetV1, and cannot prove any
excluded release claim.

Formal authorization is therefore a function only of explicit immutable `C`, `H`, and
their referenced Git objects; it does not read `HEAD`, the index, the worktree, or a
mutable tag ref. A dirty checkout, a current `HEAD` different from the intended `H`, or
a moved/missing live rollback tag is still an operational stop before editing, build,
or release, but cannot change the authorization verdict for the same object IDs.

### Versioned constants

All arithmetic is checked integer arithmetic.

| Constant | Value |
| --- | ---: |
| Successful warm-up callbacks per runtime | 5 |
| Candidate measurement duration | 5,000,000,000 ns |
| Selected steady duration | 10,000,000,000 ns |
| Camera bind-call return deadline | 5,000,000,000 ns |
| First frame after successful bind deadline | 5,000,000,000 ns |
| Artifact/workload/model identity read deadline | 5,000,000,000 ns |
| Camera idle gap while no task is active | 1,000,000,000 ns |
| Complete warm-up phase deadline | 10,000,000,000 ns |
| Runtime creation return deadline | 10,000,000,000 ns |
| `detectAsync` submission return deadline | 1,000,000,000 ns |
| Result callback after returned submission deadline | 1,000,000,000 ns |
| Measurement drain allowance | 1,000,000,000 ns |
| Callback-output disposal deadline | 1,000,000,000 ns |
| Runtime teardown return deadline | 5,000,000,000 ns |
| Recovery-journal read deadline | 1,000,000,000 ns |
| Recovery-journal mutation deadline | 1,000,000,000 ns |
| Capability mode-store read/mutation deadline | 5,000,000,000 ns |
| PSS sample call deadline | 500,000,000 ns |
| Render callback drain allowance | 1,000,000,000 ns |
| Support-ready representative completions | 30 |
| Representative samples per quartile | 5 |
| Recovery entries per installed artifact | 8 |
| Generated workload manifest maximum | 1,048,576 bytes |
| Recovery journal file maximum | 16,384 bytes |
| Capability store envelope maximum | 1,048,576 bytes |
| Maximum task timestamp (before pinned `*1000`) | 9,223,372,036,854,775 ms |
| Solo FPS / inclusive tail and growth limit | 20 / 50,000,000 ns |
| Full dual FPS / inclusive tail and growth limit | 15 / 66,666,667 ns |
| Conditional dual FPS / inclusive tail and growth limit | 10 / 100,000,000 ns |
| Inclusive maximum representative frame age | 1,000,000,000 ns |

The 20 FPS solo target is the conservative support floor because the SSOT gives no
lower solo floor. Changing a value or interpretation requires a new policy revision.

### Normative deadline table

An operation succeeds at `completedAtNs <= deadlineNs`. Expiration is a state-gate
transition only when a watchdog reads `nowNs > deadlineNs`; a watchdog at exact
equality must reschedule. Start/end timestamps below are captured inside the named
owner or state-gate transition. No caller supplies an external/back-dated timestamp.

| Operation | Start transition | Completion transition | Deadline |
| --- | --- | --- | --- |
| Camera bind call | `BIND_REQUESTED` at state gate before controller call | `BIND_RETURNED` at gate after successful return | requested + 5 s |
| First frame | successful `BIND_RETURNED` | first source admission at gate | bind returned + 5 s |
| Artifact/workload/model identity | identity owner enters `IDENTITY_READ_STARTED` | complete verified identity admitted at gate | started + 5 s |
| Runtime create | owner enters `CREATE_STARTED` | owner records returned/throw at gate | create started + 10 s |
| Submission return | owner records `SUBMISSION_STARTED` immediately before call | owner records return/throw at gate | submission started + 1 s |
| Result callback | `SUBMISSION_RETURNED` | correlated result admission at gate | min(returned + 1 s, current warm-up deadline or measurement drain deadline) |
| Callback image disposal | result admission reservation | disposal resolution at gate | reservation + 1 s |
| Whole warm-up | `WARMUP_ENTERED` at gate | fifth resolved successful barrier | entered + 10 s |
| Idle camera | max(last admitted camera callback, last task terminal, phase entered) | next camera admission | anchor + 1 s while no task/submission is active |
| Measurement | first accepted post-barrier callback | fixed end | start + exact 5 s or 10 s |
| Measurement drain | fixed end | atomic freeze | end + 1 s; new source admission is already closed |
| Runtime teardown | owner enters close | close return/throw recorded at gate | close started + 5 s |
| Journal read/recovery | state gate captures `JOURNAL_READ_DISPATCHED` immediately before enqueue | same-token directory bootstrap/bounded enumeration plus whole checksum-valid payload admitted at gate | dispatched + 1 s |
| Journal mutation | state gate captures named mutation dispatch immediately before enqueue | same-token directory bootstrap/bounded enumeration, checked `.next` file sync/close, same-directory atomic rename, and independent exact intended-base reread admitted at gate | dispatched + 1 s |
| Capability mode-store read | state gate captures `MODE_STORE_READ_DISPATCHED` immediately before enqueue | same-token directory bootstrap plus whole strict mode payload admitted at gate | dispatched + 5 s |
| Capability mode-store delete/reset/save | state gate captures named update dispatch immediately before enqueue | same-token directory bootstrap/identity freeze, returned DataStore update, owner join, identity equality, and the named read-only backing-file verifier admitting the exact old-or-new payload | dispatched + 5 s |
| DataStore owner handoff (normal save or reset) | coordinator captures `OWNER_CANCEL_DISPATCHED` before scope cancel | `cancelAndJoin` returned and old owner proven dead | dispatched + 5 s |
| PSS sample | scheduled token is admitted at gate and offered to the process PSS owner | `Debug.getPss()` value admitted at gate | scheduled token + 500 ms |
| Render drain | measurement end | removal plus same-Handler sentinel; authoritative seal only with a proved native-removal fence, otherwise conservative UNAVAILABLE | end + 1 s |
| Thermal measurement cutoff | end snapshot return | endpoint and cutoff commands reach the process-lifetime safety FIFO with zero pre-cutoff queued/running work and no known failure | snapshot returned + 1 s |

An exception is not a timeout; it follows the failure-effect matrix. Window end is
exclusive for source admission. Drain deadline is inclusive for already-admitted
frames, and freeze may occur only after the deadline (`now > deadline`).
An identity, journal, mode-store, or PSS operation owns a generation-bound token.
Return after expiry is state-inert in the current generation and every opened stream
still closes in owner `finally`. Journal/mode-store read timeout forbids cache and
native work; mutation timeout ends `FINALIZING` as
`PERSISTENCE_OUTCOME_UNKNOWN_RESTART_REQUIRED`. Main, lifecycle, analyzer, and runtime
owners never join a persistence owner.

Every persistence operation captures exactly one start timestamp at the state gate
immediately before enqueue; it includes all owner queue time and is never reset at
owner entry. Directory bootstrap, selected-directory enumeration, DataStore parent-
identity checks, cleanup, and the final verifier are steps of that same operation; no
unbounded preflight runs before the captured timestamp. Each persistence mutation token has the exhaustive states `PRE_COMMIT`,
`COMMIT_AUTHORIZED`, `COMPLETED`, `NOT_COMMITTED`, `EXPIRED_PRE_COMMIT`,
`EXPIRED_AFTER_AUTHORIZATION`, and `FAILED_AFTER_AUTHORIZATION`. Journal files use only
`CheckedAtomicReplaceV1`, never `android.util.AtomicFile`. One single-process owner first
freezes the exact prior base/absence and requires the fixed same-directory `.next` path
to be absent. It creates `.next` with public `android.system.Os.open` and
`O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW` plus `O_CLOEXEC` only on API 27+, loops over
checked partial `Os.write` results until the whole bounded canonical bytes are written,
rejects zero progress/interruption, and checks
`Os.fsync` and `Os.close`, and strictly rereads `.next` through a new read-only
descriptor with positive progress to frozen length and a separate zero-return EOF probe
while PRE_COMMIT. It then CAS-authorizes immediately before the sole public
`Os.rename(next, base)` call. There is no copy, delete-base, or rename fallback.
`O_CLOEXEC` is unavailable on API 26; that branch performs no process exec, exposes the
descriptor to no external call, and closes it before authorization.
Source, merged, and packaged manifests must contain no app component with
`android:process`, `isolatedProcess`, or legacy multiprocess-provider behavior; a second
app process invalidates this owner proof and blocks capability work.

After rename it lstat-checks the selected mode parent as a directory, opens that fixed
path with public `O_RDONLY|O_NOFOLLOW` and mode zero (plus API27+ `O_CLOEXEC`), fstat-
requires `OsConstants.S_ISDIR(st_mode)`, and fsyncs/closes that descriptor. Public
`OsConstants.O_DIRECTORY` does not exist and is forbidden. The owner then directly
lstat/opens the base and `.next` within the
original deadline. Exact intended base bytes with `.next` absent admit COMPLETED. Exact
prior base bytes or prior absence may admit NOT_COMMITTED only after a remaining regular
`.next` is removed with checked public `Os.remove` and the exact prior/absence plus sibling absence are
reverified after the same checked parent-directory fsync. A symlink/non-regular path,
malformed or third base, cleanup or directory-sync failure,
residual sibling, or inspection failure admits FAILED_AFTER_AUTHORIZATION. Expiry CAS
from PRE_COMMIT forbids later authorization; it may claim prior preservation only after
the same checked cleanup and exact prior/absence verification. Expiry after
authorization, or any unclassified failure after authorization, leaves one whole old
or new base for next-clean-process recovery. Same-directory POSIX rename supplies the
process-death old/new boundary on every supported API; directory-storage and sudden-
power-loss attestation remain explicitly outside this claim.

DataStore authorization remains a CAS immediately before its deterministic transform
returns the exact new payload. A returned update is not fresh-disk evidence by itself.
The normal-save coordinator cancels and joins the sole live owner after update return,
then requires the pre-update and post-join `ModeStoreDirectoryIdentityV1` values to be
equal, checks the mode-store parent-directory fsync, and runs `ModeStoreDiskVerifierV1`
with no DataStore owner alive. Its exact result is ABSENT or PRESENT with whole-file
length/hash/bytes and parsed payload. PRESENT reads positive progress to the frozen
fstat length, then requires a separate one-byte read to return zero as normal EOF; save/
reset success requires the exact intended PRESENT tuple. It enforces envelope size/wire/
canonical/hash while requiring DataStore 1.2.1's fixed `<base>.tmp` sibling absent. Before any
DataStore construction, clean-process recovery may checked-`Os.remove` only a regular
`.tmp` beside an absent/strict-valid base and must re-inspect both; it never promotes the
temporary. Save completion includes owner handoff plus that post-update verifier within the
original five-second deadline. The verifier never repairs, renames, deletes, or invokes
a corruption handler. After final-clear dual verification, a handler-free successor
DataStore owner may be constructed only sequentially; its first read must equal the
already verified payload before it can remain as the sole live service owner.

Only timely COMPLETED can authorize a following native call, cache service, pending
acceptance, final clear, or success UI. NOT_COMMITTED is restart-required and cannot be
retried by another owner in that process; FAILED_AFTER_AUTHORIZATION and expiry remain
outcome-unknown until next-launch strict recovery. Before authorization, cleanup is
trusted only after a checked `.next` `Os.remove` and the same direct exact-prior/absence and
clean-sibling inspection. Otherwise it is restart-required/outcome-unknown. This
protocol establishes observable whole-old/whole-new
process-death recovery; it does not claim independent storage-hardware or sudden-power-
loss attestation.

An authorized late result is accepted only by strict read/recovery on the next clean
process and cannot authorize native work, cache service, or a success message in the
timed-out process. The affected mode persistence owner/generation is poisoned after any
timeout and is never replaced in that process. Failure on the next clean launch to
restore one strict old/new payload is restart-required/corrupt, never a cache miss.

### Canonical runtime artifact and workload identity

Native creation is forbidden until the installed artifact has a stable identity.
`RuntimeArtifactId` is computed at runtime from exact installed APK bytes, not Git
cleanliness or a source path:

1. read `ApplicationInfo.sourceDir`, `splitSourceDirs`, and `splitNames` once before
   native work; the split arrays must both be present or both absent, have equal
   length, and retain their PackageManager index pairing;
2. hash the exact base APK and every paired installed split APK as read-only byte
   streams; reject an empty/duplicate split name, a split named `base`, a duplicate
   path, a length change while reading, or any missing/unreadable entry;
3. name entries logically as `base` and the exact paired split names, with base first
   and splits sorted by unsigned UTF-8 name bytes;
4. encode exactly
   `UTF8("runtime-artifact-manifest-v1") || 0x00 || uint32_be(entryCount)`, then for
   each ordered entry
   `uint32_be(nameLength) || UTF8(name) || uint64_be(apkLength) || rawSha256`;
   paths are never encoded or persisted;
5. hash those exact bytes.

If any installed byte stream, split name, length, or digest is unavailable, record
`RUNTIME_ARTIFACT_ID_UNAVAILABLE` and do not invoke native code. This identity remains
stable across launches of the same dirty/debug/release APK and changes with any
actual installed byte.

Every canonical manifest begins:

```text
UTF8(domain) || 0x00 || uint32_be(fieldCount)
```

Fields are emitted in the fixed policy-listed order, never map iteration order:

```text
uint32_be(nameUtf8Length) || exact UTF8(name) ||
uint64_be(valueLength) || exact value bytes
```

Integers are unsigned big-endian fixed-width values; booleans are one byte `0/1`;
hashes are raw 32 bytes; strings are exact UTF-8 without normalization. There are no
optional fields: an absent required value invalidates the manifest. File inputs use
`uint64_be(fileLength) || rawSha256(fileBytes)`. Hash output is lowercase 64-character
hex only when displayed/persisted.

`ProbeWorkloadRevision=probe-workload-v3` fixes:

- ImageAnalysis RGBA_8888 + `KEEP_ONLY_LATEST`;
- MediaPipe `RunningMode.LIVE_STREAM`;
- packaged model path at build time and runtime model SHA-256
  `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`;
- `numPoses=1` solo or `numPoses=2` dual;
- detection/presence/tracking confidence `0.5f` encoded by exact IEEE-754 bits;
- `outputSegmentationMasks=false`;
- CPU or GPU delegate with no delegate-options override and pinned default threading;
- input adapter `rgba-bytebuffer-v3`, state machine `serial-probe-v5`, recovery
  journal `runtime-journal-v5`, and preview contract `preview-v2`.

Every revision field is an exact byte value, not an implementer label. The six
WorkloadBuildManifestV3 revision fields are respectively UTF-8 `capability-v15`,
`probe-workload-v3`, `rgba-bytebuffer-v3`, `serial-probe-v5`, `runtime-journal-v5`, and
`preview-v2` in their listed field order.

Pinned Tasks 0.10.35 exposes `Delegate.NPU` but its public `TaskOptions` conversion
serializes only CPU/TFLite and GPU. NPU falls through with empty acceleration. It is
therefore recorded as `NPU_UNAVAILABLE_PINNED_API_0_10_35`, never created, ranked,
selected, cached, or used for fallback. A real NNAPI/NPU path requires a new pinned
adapter/dependency ADR plus G0, license, and artifact tests.

`WorkloadBuildManifestV3` uses domain `workload-build-manifest-v3`, exact
`fieldCount=15`, and the fixed order below. Each backticked top-level name is one
field; numbering only groups related fields for readability:

1. `policy_revision`, `workload_revision`, `input_adapter_revision`,
   `state_machine_revision`, `journal_revision`, `preview_revision` as exact UTF-8;
2. `app_version_code` uint64, `build_type` UTF-8, `minified` one byte;
3. `pose_options` as a 10-field canonical `pose-options-v2` manifest in this order:
   `running_mode` one-byte LIVE_STREAM `0`; `min_pose_detection_confidence`,
   `min_pose_presence_confidence`, and `min_tracking_confidence` each uint32 IEEE-754
   bits `0x3f000000`; `output_segmentation_masks` one byte `0`; `solo_num_poses`
   uint32 `1`; `dual_num_poses` uint32 `2`; `candidate_delegates` exact bytes
   `0x02 0x01 0x02` for count/CPU/GPU; `delegate_options_override` one byte `0`; and
   `threading` exact UTF-8 `PINNED_LIBRARY_DEFAULT`. It describes both modes/delegates,
   so RuntimeBuildId is not candidate-specific;
4. `model_file` as nested exact UTF-8 logical packaged path
   `assets/pose_landmarker_lite.task`, encoded as uint32 path length/path bytes then
   file length/raw hash;
5. `dependency_artifacts`: every external `ModuleComponentIdentifier` AAR/JAR on the
   exact variant runtime classpath under strict verification. Each unique name is
   `maven:group:name:version:classifier-or-empty:extension`, sorted by unsigned UTF-8
   bytes. Project components are excluded here because installed APK bytes and the
   named module build files bind them; file/self-resolving external dependencies are
   forbidden;
6. `build_files`: exactly `settings.gradle.kts`, `build.gradle.kts`,
   `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`,
   `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`,
   `app/build.gradle.kts`, `app/proguard-rules.pro`, `vision/build.gradle.kts`,
   `vision/consumer-rules.pro`, `game-core/build.gradle.kts`,
   `game-core/consumer-rules.pro`, `games/build.gradle.kts`, and
   `games/consumer-rules.pro`,
   `docs/adr/ADR-011-measured-capability-probe-policy.md`,
   `docs/contracts/capability-store-v4.md`,
   `docs/contracts/native-close-fence-proof-v1.md`,
   `docs/contracts/recovery-journal-v5.md`, and
   `docs/execution/slice-1b-contract.md`, plus the
   generated nonvisual proof registry
   `vision/src/main/assets/native_close_fence_registry_v1.bin`;
7. `lockfiles`: exactly `settings-gradle.lockfile`, `app/gradle.lockfile`,
   `vision/gradle.lockfile`, `game-core/gradle.lockfile`, and
   `games/gradle.lockfile`;
8. `native_close_proof_basis`: exact canonical NativeCloseProofBasisV1 bytes from
   `docs/contracts/native-close-fence-proof-v1.md`. Its five-file PolicySet hash,
   dependency-artifacts hash, and build variant must equal fields above, and its final
   pre-asset DEX code-image hash is independently reproduced from final packaged DEX
   entries. It excludes the registry, this workload manifest, RuntimeBuildId,
   RuntimeArtifactId, and APK identities.

Each nested list is encoded as `uint32_be(entryCount)`, then each unsigned-UTF-8-name-
sorted entry as `uint32_be(nameLength) || UTF8(name) || uint64_be(fileLength) ||
rawSha256`. Duplicate names/coordinates, missing named inputs, non-AAR/JAR dependency
artifacts, unresolved versions, or verification/lock disagreement fail the build.
`dependency_artifacts` uses the canonical coordinate as name; the other lists use the
exact repository-relative logical name above.

No host absolute path or line-ending rewrite is allowed; exact checked-in bytes are
hashed. A build task emits the canonical manifest bytes and their raw hash as a
generated read-only nonvisual app resource. CI independently recomputes and compares
them. Runtime rejects a missing, oversized, malformed, field-mismatched, or hash-mismatched
resource before native create and never logs or persists its logical file names.
Candidate and shipped runtimes use the same options factory.
`RuntimeBuildId` is the hash of a canonical `runtime-build-v2` manifest with exact
`fieldCount=2` and two fixed
fields: `runtime_artifact_id` raw RuntimeArtifactId, then
`workload_build_manifest_sha256` raw WorkloadBuildManifestV3 hash. The crash journal
uses exact RuntimeArtifactId as RecoveryBuildId; profile cache uses RuntimeBuildId.

### Base scope, result identity, and cache

`ProbeBaseScopeV2` is a canonical manifest with domain `probe-base-scope-v2`, exact
`fieldCount=33`, and the following fixed field order/types. Each backticked top-level
name is one field:

1. raw 32-byte `runtime_build_id`;
2. uint64 `app_version_code`, UTF-8 `build_type`, one-byte `minified`;
3. UTF-8 `policy_revision`, `workload_revision`, `input_adapter_revision`,
   `state_machine_revision`, `journal_revision`, `profile_schema_revision`, and
   `preview_revision`;
4. raw 32-byte `model_sha256`, uint32 `os_api`, required tagged-union
   `os_build_token`, and required tagged-union `camera2_id_token`;
5. one-byte `lens` (`0=FRONT`, `1=BACK`), uint32 `analysis_width`,
   `analysis_height`, `rotation_degrees`, then uint32 `crop_left`, `crop_top`,
   `crop_right`, `crop_bottom`;
6. uint32 `preview_surface_width`, `preview_surface_height`,
   `transform_crop_left`, `transform_crop_top`, `transform_crop_right`,
   `transform_crop_bottom`, `transform_rotation_degrees`, and `target_rotation`;
7. one-byte `mode` (`0=SOLO`, `1=DUAL`) and one-byte `running_mode`
   (`0=LIVE_STREAM`).

The seven ProbeBaseScopeV2 revision fields are respectively exact UTF-8
`capability-v15`, `probe-workload-v3`, `rgba-bytebuffer-v3`, `serial-probe-v5`,
`runtime-journal-v5`, `capability-mode-store-payload-v4`, and `preview-v2`. Any missing,
alternate, normalized, or one-byte-mutated value is invalid before hashing; in
particular, `profile_schema_revision` is exactly
`capability-mode-store-payload-v4`.

Rotation degrees must be exactly 0/90/180/270; target rotation must be an exact public
Surface rotation enum; dimensions/crops must pass the preceding bounds. A token union
is exact bytes `tag`, where `0=ABSENT` has no payload and `1=PRESENT` is followed by a
raw 32-byte digest. The union field itself is never optional. Hashing the canonical
scope bytes yields `ProbeBaseScopeId`.

OS/camera digest bytes use exact `fieldCount=1` canonical manifests named `value` with domains
`os-build-v1` and `camera2-id-v1`. OS value is exact non-empty `Build.FINGERPRINT`, excluding
`Build.UNKNOWN`. Camera value comes only from opted-in public
`Camera2CameraInfo.from(cameraInfo).cameraId`. Restricted
`CameraIdentifier.internalId`, suppression, parsing, and device-name composition are
forbidden. Missing OS/camera value uses the required `ABSENT` union, not a fake digest,
and makes profile results process-local unless Camera2 failure also blocks timestamp-
source proof, which makes the attempt incomplete. A process-local result is never
written to Proto DataStore; the journal may persist only its opaque ProbeBaseScopeId
for conservative crash recovery.
Camera ID is only a change token: re-read it before recovery, cache lookup, every
scope-guard check, and save. A change aborts as `SCOPE_CHANGED`; false misses are safe.

Each mode-separated Proto DataStore's unique primary key is raw `ProbeBaseScopeId`; it
can therefore hold at most one current result per base. The record contains one-byte
`selectedDelegateOrNone` (`0=NONE`, `1=CPU`, `2=GPU`). `CapabilityResultId` is the hash
of a canonical `capability-result-v2` manifest with exact `fieldCount=2` and fields `probe_base_scope_id` raw
ProbeBaseScopeId then `selected_delegate` that byte. `NONE` is
required for permanent/no-delegate Unsupported evidence and is not checked for
delegate availability; a measured-low Unsupported retains its measured CPU/GPU.
Duplicate base keys, duplicate result IDs, schema mismatch, unavailable selected
CPU/GPU, changed field, or invalid digest fails closed. `Recheck` deletes the exact
base record before journal/native work. Incomplete attempts are never cached.
Unmeasured scopes remain `UNVERIFIED`.

Solo and dual are independent base scopes, independent commits, and independent
integrity domains. A clean valid solo result **must** be saved before an optional dual
prompt/run. Dual cancellation, incompleteness, failure, transaction interruption, or
mode-file corruption never opens, rewrites, resets, deletes, or makes unavailable the
strict solo mode file. Combined UI state is derived; there is no combined transaction
or shared checksum.

`CapabilityModeStoreV4` uses exactly two private Proto DataStore files for the current
installation: logical `capability_store_v4/solo.pb` and `dual.pb`. The fixed directory
and filenames are canonicalized under `noBackupFilesDir`. Clean-process bootstrap uses
only checked public `Os.mkdir(path,0700)` for an exact ENOENT fixed child, then lstat/
open/fstat directory checks plus child/root fsync/close; `File.mkdirs`, unchecked
booleans, apply-time creation, and fallback are forbidden. A symlink,
non-regular existing file, or path escape is corrupt. Each file has its own exact
schema/mode/owner payload and SHA-256, a 1,048,576-byte whole-envelope cap, canonical
wire re-encoding, at most 64 records, and strict unsigned ProbeBaseScopeId ordering.
Every embedded scope must match both its owner build and file mode.

Each record carries canonical scope/result identity, selected delegate/NONE, outcome,
constant effect state, bounded certified aggregate summaries, a finite fallback trace,
at most two categorical clean-terminal proofs, and reason-specific Unsupported proof.
On write, ephemeral samples are reduced to rank certificates and discarded. On read,
canonical bytes, hashes, enum/tuple bounds, conservation, rank-certificate feasibility,
fallback grammar, terminal/Unsupported proof, and the tier derived from certified
aggregate values are recomputed. This proves record self-consistency, not sensor
attestation or writer honesty.

Unknown schema/enum, duplicate/unsorted key, hash mismatch, overflow, impossible route,
or invalid proof makes only that mode store unreadable and blocks native work for that
requested mode; it is never treated as a cache miss. The other mode store is not
opened as part of failure handling. After current-artifact journal recovery, a strict
valid prior RuntimeBuildId owner is atomically rotated to the canonical empty current-
owner payload for that mode before lookup/native work; timeout/throw follows the
persistence unknown-old/new rule. Current-build records are never evicted implicitly;
overflow is incomplete.

`docs/contracts/capability-store-v4.md` is normative for exact envelope tags, canonical
record/metrics fields, enums, byte caps, writer-versus-reader validation boundary,
route/proof grammar, cross-record invariants, and privacy. Changing it requires a new
policy/schema revision.

Unreadable mode-store UI may offer an explicit local `Reset solo results` or `Reset
dual results` action for only that unreadable mode. It never reconstructs DataStore in
the requesting process. Confirmation checked-increments the journal's shared
`last_epoch`, writes the same value as RESET_REQUESTED `control_epoch`, and requires a
clean process. A zero/stale/future control epoch is corruption. That process durably
changes the same epoch to RESET_RUNNING, constructs
one reset owner with a construction-time one-shot corruption-handler permit, and
replaces or updates only that mode with the canonical empty v4 payload. It then
cancel-and-joins that owner before constructing a non-overlapping handler-free verifier
owner for a strict disk reread. Only exact empty plus both bounded owner joins clears
the request. Handler reentry, timeout, or throw starts no overlapping instance and
leaves RESET_RUNNING restart-required; automatic recovery may only verify an already
empty file, while re-arming requires new explicit confirmation and a clean process.
The normal handler without REQUESTED always throws. The pinned DataStore 1.2.1 artifact
fixture passed duplicate-owner, sequential reopen, same-read descriptor-synced
replacement observed on disk, reinvocation, one-shot permit, and handler-free verifier
cases; it did not prove crash durability. Android integration must
reproduce them. A combined user-selected reset is two independent confirmed clean-
process operations. Each changes only its selected RecoveryJournalV5 `last_epoch` and
`mode_control` plus that mode store while preserving the selected journal's existing
entries; neither opens or changes the other mode's journal/store, calibration, or game
state.
Corrupt/overflow journal has no in-app reset under the same RuntimeArtifactId because
erasing unknown crash state could recreate an unbounded native crash loop.

### Resolution, crop, and scope guard

Request orientation-normalized 640 x 480. Positive CameraX sizes are ordered by:

1. exact normalized `640 x 480`;
2. exact rational error `abs(3*longEdge - 4*shortEdge) / shortEdge`, compared by
   checked cross multiplication;
3. absolute area difference from 307,200;
4. normalized long/short then original width/height.

Actual bound size, not request, becomes scope; a difference records
`NEGOTIATED_SIZE_CHANGED`. Crop is an integer rectangle:

```text
0 <= left < right <= imageWidth
0 <= top < bottom <= imageHeight
```

The model receives the complete image; crop is Slice 1A coordinate/preview metadata.
Every camera callback guards camera object, Camera2 token, lens, size, rotation, and
crop. Every preview transformation/lifecycle event guards surface size, transform
crop/rotation/target rotation, and `preview-v2` configuration. Any change aborts and
requires rebind; no statistics mix. Orientation need not be locked.

### Submitted input, callback output, and buffer ownership

Pinned Pose Landmarker creates distinct resources:

1. app-created direct-ByteBuffer-backed `submittedInputMpImage`;
2. dependency-created Bitmap-backed `callbackOutputMpImage` from `IMAGE:image_out`.

The only app input adapter:

1. validates one RGBA plane, positive dimensions, `pixelStride=4`, row stride,
   required remaining bytes, exact rotation, and crop bounds;
2. leases the sole session direct buffer whose capacity is exactly
   `width*height*4`, sets position 0 and limit=capacity, and copies full active rows;
3. transfers the lease to a generation-bound submission object and, on the runtime
   owner, builds RGBA MPImage and invokes `detectAsync` with rotation options;
4. after `detectAsync` returns or throws, closes the submitted MPImage exactly once,
   overwrites the **full capacity** by absolute writes, releases the lease, and signals
   input cleanup; only then may that buffer be reused;
5. on normal returned submission, the analyzer waits for result/error/timeout and then
   returns so `ClosingImageProxyAnalyzer` closes ImageProxy once.

Pinned JNI copies direct-buffer pixels synchronously before a normal `detectAsync`
return. If submission exceeds its deadline, state/correlation is permanently
invalidated, the lease remains owned by the generation-bound submission object, and
the analyzer returns immediately so ImageProxy closes. The buffer is neither zeroed
nor reused while native access is unresolved. If the owner later returns, its
generation-guarded `finally` closes input, zeros full capacity, releases the lease,
and signals diagnostics, but can never revive tier/cache/journal state. A permanent
hang retains only that isolated process buffer until process death and requires a
clean launch.

The app never creates a Bitmap input. The pinned output Bitmap is an explicit volatile
dependency allocation included in inference and PSS evidence. Every matched,
mismatched, stale, duplicate, invalidated, or late result callback attempts exact-once
callback-output close in `finally` before its barrier signal. It never reads/exports/
logs/persists output pixels. ErrorListener carries no output image. Late callbacks
are state-inert, never resource-inert.

No frame, buffer, Bitmap, MPImage, proxy, or landmark is persisted or placed in an
unbounded queue. No app-owned monitor, mutex, or StateGate is held across a dependency
call, wait, MPImage close, buffer cleanup, or landmarker close. Dependency-internal
`synchronized` behavior is expected and is not an app lock invariant.

### Runtime owner, callback correlation, and teardown

Each runtime has a dedicated single-thread `RuntimeOwner`; create, detect, and normal
close execute there. Camera, owner, callback, state gate, lifecycle, journal IO, and
watchdog are distinct roles. Main/analyzer/lifecycle never join native teardown.

At most one inference is outstanding. Camera2 timestamp source must be REALTIME.
Each fresh runtime initializes `sourceAnchorNs=UNSET` and
`previousTaskTimestampMs=-1` immediately after `CREATE_RETURNED`, before any warm-up
reservation. The first source frame that passes scope/source checks and is atomically
reserved for that runtime sets `sourceAnchorNs` to its exact nonnegative source
timestamp. The anchor then remains fixed across warm-up and measurement and resets only
for another freshly created runtime. For every reservation:

```text
deltaNs = checkedSubtract(sourceTimestampNs, sourceAnchorNs) // must be >= 0
relativeMs = floor(deltaNs / 1,000,000)
nextMs = checkedAdd(previousTaskTimestampMs, 1)
taskTimestampMs = max(relativeMs, nextMs)
packetTimestampUs = checkedMultiply(taskTimestampMs, 1,000)
```

Thus the first task timestamp is exactly zero. The pinned BaseVisionTaskApi multiplies
milliseconds by 1,000, so `taskTimestampMs` must be in
`0..9,223,372,036,854,775` before the dependency call. Source timestamps must be nonnegative
and strictly increase under the source sequencer. Before addition, an exact
`previousTaskTimestampMs == 9,223,372,036,854,775` deterministically ends as
`PROBE_INCOMPLETE(TASK_TIMESTAMP_EXHAUSTED)`. Any greater prior value is invalid state.
A negative source/delta, subtraction failure, invalid source conversion, out-of-range
relative value, or multiplication failure ends as `TASK_TIMESTAMP_INVALID`. No such
path admits a dependency call; clean teardown is still required. `previousTaskTimestampMs`
is committed exactly when the capacity-one task entry is reserved and is never rolled
back after a later submission/result failure. Neither anchor nor task timestamp is
persisted or logged.

A capacity-one entry contains runtime generation, task
timestamp, frame ID, phase, source timestamp, and typed ProbeClock values. A result
must echo the exact timestamp and generation. The pinned ErrorListener has neither
timestamp nor stage and can represent failure while the dependency is creating the
undelivered callback-output MPImage. Therefore **every** invocation, including current,
stale, duplicate, and no-outstanding cases, is
`CALLBACK_OUTPUT_UNDELIVERED/RESOURCE_UNCERTAIN`: it invalidates the process capability
epoch, forbids cache/save/new runtime, and requires quarantine plus a clean launch.
When exactly one current task awaits callback it receives one
`CALLBACK_RESOURCE_ERROR`; otherwise unfinished current-attempt frames receive
`ATTEMPT_ABORTED` and the listener event remains counter-diagnostic. A captured old
generation can identify only its base/delegate journal key; it can never become a clean
terminal delegate failure or affect a newer task's normal result. No same-process CPU
fallback follows an ErrorListener.

Unknown/duplicate/stale result correlation is incomplete, but its callback output still
closes. A result admitted before
`SUBMISSION_RETURNED` atomically marks `CALLBACK_ORDER_INVALID`. It can never become a
completion when submission later returns. Result output still closes; the analyzer
barrier may release only after that cleanup and normal returned-submission input
cleanup, or after the independent submission-timeout path transfers lease ownership.
After recording the one incomplete outcome, the early callback is state-inert.

Every generation has one app callback gate shared by result and ErrorListener entry:
`admissionOpen=true`, checked `inFlightCallbackCount=0`, and `sealed=false`. Entry and
finally increment/decrement are atomic at the state gate. Entry after admission closes
never becomes a result; it is a contract-violation event. Teardown closes camera/source
admission, detaches analyzer ownership, resolves every submission/input lease, calls
runtime close, and waits only through the runtime close deadline. After close returns,
the state gate may set `admissionOpen=false` and `sealed=true` only when in-flight is
exactly zero. A nonzero count, counter error, close throw/timeout, or admitted
ErrorListener keeps journal active/recovery state and forbids persistence/service.

The journal active marker remains durable until that seal and all output disposal have
completed. `docs/contracts/native-close-fence-proof-v1.md` is the sole registry byte,
acyclic generation, proof-basis, installed-JNI-set, API/ABI/delegate match, and bundle-
acceptance authority. Its generated nonvisual registry and exact ProofBasis bytes are
hashed by WorkloadBuildManifestV3.
Build/CI first freezes the exact five-file PolicySet and each registered variant's final
DEX/JNI code outputs, then validates a content-addressed canonical evidence/zero-finding
approval BundleV1 carrying that variant's exact parsed/recomputed ProofBasis bytes and
hash. One registry may contain multiple bases only for distinct fully reconstructed
current-policy build variants; the current workload embeds one byte-identical basis and
other-variant rows stay inert. Registry and workload generation may not rerun code
transforms, and every registered final APK DEX/JNI image must reproduce its snapshot.
Before entry enumeration, each APK must satisfy the single whole-file view: exact-EOF
zero-comment EOCD, single-disk exact central count/range consumption, contiguous unique
local records, and either no gap or one structurally framed APK Signing Block as the only
opaque gap. DEX/JNI local and central flags/method/CRC/sizes must agree; only flags zero
or UTF-8 bit 11, no data descriptor/ZIP64, and empty/all-zero local alignment padding are
legal. Runtime parses the verified embedded basis, recomputes installed DEX and every
installed ABI JNI set, and requires one exact-basis/current-API row with delegate mask
`0x03` for each before journal mutation. After a timely active-journal reservation, a
newly incremented immutable route-
generation snapshot holds the tuple-preserving canonical rows and is CAS-authorized
once immediately before its native create; clean close/seal destroys it before another
route can rebuild a fresh snapshot. Missing, mismatched, duplicate, stale, circular,
unapproved, final-package-different, or merely quiet-period evidence ends
`RUNTIME_CALLBACK_FENCE_UNPROVEN` with zero native creates, persistence, cache, or
service. Device names are never proof keys.

If a callback is nevertheless observed after a matched proof and seal, the entry first
latches an in-memory fence violation and immediately revokes all capability/game use in
that process. RecoveryJournalV5 then attempts its fixed-capacity
POST_SEAL_MODE_POISON mutation independently of the eight-entry list. A timely committed
poison permanently blocks that artifact/mode on the next launch. A timeout, failure, or
process death before that commit makes no durable-poison claim; correctness relies on
the exact native fence proof, not on ordering an arbitrary future violation before an
already completed pending/final-clear transition. This defense-in-depth path never
creates a clean terminal proof or same-process fallback.

A warm-up is one successful correlated callback, not a submission; exactly five are
sequential and excluded from measurement. Pose count may be zero during warm-up. Each
deadline follows the table and no task crosses a phase barrier.

Normal or failure teardown executes on RuntimeOwner and is watched without joining.
Only a proven returned/clean teardown permits another runtime in the same process.
Close throw/hang, submitted-input close uncertainty, buffer cleanup failure, or
callback-output disposal throw/hang requires restart; no same-process CPU fallback.

### Representative pose occupancy and probe sequence

The UI instructs exactly one visible participant for solo and exactly two for dual.
`numPoses` is only a maximum, so each completed result records aggregate `poseCount`.
An `occupancy-valid` completion has `poseCount==1` for solo or `poseCount==2` for dual.
No landmarks or per-frame pose count persist; only aggregate counts/distributions.

A structurally valid candidate/steady window requires at least one occupancy-valid
completion in **each** exact quartile. Otherwise the entire mode attempt is
`PROBE_INCOMPLETE(WORKLOAD_NOT_PRESENT_OR_UNSTABLE)`, not delegate failure, measured
low performance, Unsupported, or a cache entry. This prevents blank-wall solo and
one-person dual support. A reachable exact-duration window with fewer than 30 total
or representative completions may be measured below floor when occupancy is present
in every quartile and every deadline/conservation invariant holds.

Sequence:

1. Verify artifact identity, permission, foreground, bind, actual scope, REALTIME
   timebase, packaged model bytes, and one proxy-only RGBA layout preflight before
   native creation. A first static plane/pixel-stride/row-stride/capacity violation
   permits one controlled rebind; the same categorical violation on the first callback
   of the exact rebound scope is permanent scoped layout incompatibility. Any later or
   different conversion violation is incomplete, never inferred permanent.
2. For CPU then GPU, unless exact recovery state forbids it, create an isolated solo
   candidate, warm five callbacks, measure fresh five seconds, and cleanly close.
   A safe terminal delegate failure excludes it; a valid low performer is rankable.
3. Rank solo by exact class `SUPPORT`, then `BELOW_FLOOR`, representative completion
   FPS descending,
   representative inference P95 ascending (unavailable last), then CPU/GPU. Select a
   valid representative even when both are below 20 FPS.
4. Create a fresh selected runtime, warm, and measure fresh ten-second solo steady.
   Only steady decides solo. Cleanly close and independently commit solo.
5. After explicit dual opt-in, repeat with `numPoses=2`; candidate class order is
   `FULL`, `CONDITIONAL`, `BELOW_FLOOR`, then the same tie breakers. Fresh selected
   dual steady alone decides dual.

NPU status is reported separately as unavailable and never participates.

### Durable mode-sharded recovery journal and cache protocol

`RecoveryJournalV5` uses two independent private no-backup
`CheckedAtomicReplaceV1` bases and two independent single-thread IO owners for each
RuntimeArtifactId:
`capability_recovery_v5/<lowercase RuntimeArtifactId>/solo/journal.bin` and
`dual/journal.bin`. The
artifact directory and fixed filename are canonicalized under `noBackupFilesDir`.
Clean-process bootstrap validates each ancestor and uses checked public
`Os.mkdir(path,0700)` only for exact ENOENT components, with child/parent fsync and no
construction or enumeration of the other mode. A
symlink, non-regular existing file, path escape, or size above 16,384 bytes is corrupt
before allocation. A solo lookup/recovery never opens, waits for, resets, or mutates
the dual journal/store, and vice versa. An installed-byte change selects a different
absent directory without trusting or deleting older bytes. Derived paths are never
logged.

Each file is `canonicalPayload || rawSha256(canonicalPayload)`. The exact v5 payload
adds a monotonic local attempt/control epoch, ManualRetryContextV1 plus context IDs,
full TerminalProofV1 evidence on safe terminal entries, exact pending whole-file old/new
hashes, and fixed-capacity
reset/post-seal mode control. File/payload/request mode must agree, and checked atomic-
replace recovery yields one whole old or new payload without merge. The byte grammar, bounds,
privacy allowlist, and transition matrix are normative only in
`docs/contracts/recovery-journal-v5.md`; no implementation-defined journal field is
permitted.

Every payload RecoveryBuildId is the raw RuntimeArtifactId equal to its directory
shard. Every attempt is exact `(ProbeBaseScopeId, nonzero attempt_epoch)`. Fresh
start/recheck requires no live retry context/state, atomically allocates a new epoch,
deletes retry-0 terminal prefixes, and retains existing RETRY_CONSUMED tombstones.
Retry-1 active/terminal/manual state without its exact context is corrupt; its legal
consumption transitions create the nonresumable tombstone before clearing context.
Proof-prefix resume alone retains one unique terminal epoch. A manual retry of one
retry-0 quarantine always allocates a new epoch, discards old retry-0 proofs, retains
existing consumed tombstones, and reruns every needed candidate; quarantine-era proof/
metrics never mix.
Cancellation while active retains durable active through cleanup and then performs the
same retry-0 delete/retry-1 tombstone transition; between-runtime abandon is atomic.
Old/new checked-replace recovery can yield a whole resumable or whole abandoned state,
never a mixture. A terminal entry carries exact canonical TerminalProofV1, so clean
proof survives only within its epoch.

No durable intentional-abort marker exists. ActiveV5 stores an exact `retry_used` bit
and retry-context-ID union. Normal/fresh/resumed work uses zero/ABSENT; every active
route in a manual-retry epoch uses one and the exact ManualRetryContextV1 hash. The
origin-bound context remains even after its target marker becomes terminal; consumed
tombstones retain its hash after the payload context clears. Retry-0
ACTIVE/TEARDOWN_PENDING recovered after death becomes quarantine with the same epoch;
retry-1 active plus every retry-1 terminal/manual entry in that epoch becomes
RETRY_CONSUMED/MANUAL_RETRY_INTERRUPTED. Before every native entry,
one timely journal mutation reserves capacity and sets active; failure invokes no
native. Immediately before close, active becomes TEARDOWN_PENDING. It clears only after
the executable callback/render barriers are clean and the process-lifetime thermal
monitor reaches its partial-window cutoff/current-status gate. Manual retry is
authorized at most once. Retry-1 terminal/manual state becomes a fixed-capacity
RETRY_CONSUMED tombstone on abandon, interruption, or save-not-committed. It survives
fresh start, cancellation, crash, recovery, recheck, and strict final success for the
entire RuntimeArtifactId shard. Final clear never deletes QUARANTINED or RETRY_CONSUMED.
This retained external evidence is required to validate every live
`*_SKIPPED_QUARANTINED` trace and continues to forbid a second authorization. Capacity
exhaustion blocks further native work for that artifact/mode; installed-byte change
selects a new shard, while ordinary in-app recheck does not erase retry history.

Pending-store prepare records exact expected-old complete protobuf file absence or
length/hash, exact intended-new complete file length/hash, the embedded canonical
CapabilityRecordV4 hash, and retry context ID—not only CapabilityResultId. On the next
clean launch, exact intended whole-file identity proves committed, exact expected old/
absence proves not committed, and any third or unrelated-record mutation is corruption.
Pending and same-epoch terminal/manual evidence clear only after a timely DataStore
update, cancel-and-join of its sole owner, direct ModeStoreDiskVerifierV1 reread, final
journal mutation, and direct dual reread. Retained
quarantine/tombstone evidence is then
cross-validated against the new record before serve. Timeout after authorization
remains outcome-unknown in the current process.

A fixed-capacity POST_SEAL_MODE_POISON sits outside the eight-entry list and has the one
exact reason `RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL`. A timely committed poison
invalidates the exact mode store and permanently blocks that artifact/mode even at
entry capacity; it cannot be reset in-app. RESET_REQUESTED is a separate clean-process
control state and cannot override poison. The in-memory violation latch always revokes
same-process service. Its best-effort durable mutation may occur before or after
pending/final clear and is never used as the native-fence proof itself.
These rules make `FINALIZING` bounded,
preserve a valid solo result through every dual outcome, and prevent attempt mixing,
GPU/CPU alternation, or late persistence from reviving a consumed retry.

### Serial state, windows, and total accounting

One state machine owns source admission, correlation, callback reservations,
dispositions, deadlines, and freeze. Typed ProbeClock values are captured at its gate
or named owner transitions. No app lock crosses external work.

Clock meanings are fixed: `receivedAtNs` is analyzer admission at the gate;
`submittedAtNs` and `submissionReturnedAtNs` are the owner transitions around
`detectAsync`; `resultAdmittedAtNs` is result/error admission at the gate; and
`completedAtNs` is the gate transition after mandatory callback-output disposal.
Frame age and inference latency use `completedAtNs`, so dependency output handling is
inside the measurement. ErrorListener never produces a normal inference-duration
sample; its gate admission time resolves the one resource-error/abort disposition
before process-epoch invalidation.

Measurement source admission is exact `[startNs,endNs)`. At `receivedAtNs >= endNs`,
the analyzer does not validate/submit ML; it increments
`outsideWindowCallbackCount`, returns, and is excluded from window equations. This
diagnostic is neither a source nor processing disposition. Only already-admitted
window frames may complete through inclusive drain. The idle-camera watchdog is
disabled after source admission closes. Freeze occurs after drain expiry.

Result callbacks reserve a matching transition under the gate, close the callback
output outside it, then resolve as COMPLETED or resource failure before barrier. A
pre-deadline reservation prevents freeze only through its disposal deadline. Later
completion is diagnostic only. Cancellation/background/scope/fatal interruption gives
every accepted unfinished window frame `ATTEMPT_ABORTED` before atomic freeze. If a
callback was reserved by drain deadline, the effective freeze wait is its disposal
deadline; lifecycle/fatal admission still closes at the original drain deadline.

For window callbacks only:

```text
cameraCallbackCount = sourceAcceptedCount + sourceRejectedCount
sourceAcceptedCount = completed + inputConversionError + inferenceError +
  completionTimeout + callbackResourceError + attemptAborted
```

Source dispositions are ACCEPTED or exact ADR-010 NEGATIVE/DUPLICATE/OUT_OF_ORDER/
ID_EXHAUSTED rejections. Processing dispositions are COMPLETED,
INPUT_CONVERSION_ERROR, INFERENCE_ERROR, COMPLETION_TIMEOUT,
CALLBACK_RESOURCE_ERROR, or ATTEMPT_ABORTED. No frame appears twice. Pre-callback
CameraX overwrite remains `UPSTREAM_CAMERA_OVERWRITE_COUNT_UNAVAILABLE`.
It is never reported as zero; ML classification may use delivered-frame evidence, but
drop evidence remains release-PARTIAL and cannot by itself pass G8.

### Finalization linearization

Callback, error, lifecycle, thermal, watchdog, and finalizer transitions read the
clock and acquire the same short state gate. Finalizers never back-date time:

1. a finalizer read at `now <= deadline` is a no-op and reschedules;
2. a correlated result callback admitted at or before its deadline reserves that exact
   task; later finalizers cannot turn the reservation into a normal result-callback
   timeout. The whole-warm-up watchdog still marks the phase incomplete when its fifth
   resolved success is absent at `now > warmupDeadline`; reserved output cleanup then
   continues without creating a measurement disposition;
3. at `now > measurementDrainDeadline`, an unreserved outstanding task receives
   exactly one COMPLETION_TIMEOUT; if no callback disposal is reserved, all remaining
   admitted frames are disposed and the window freezes atomically;
4. a reserved callback resolves COMPLETED only when disposal succeeds at or before
   its disposal deadline. Throw or a finalizer first observing
   `now > disposalDeadline` resolves CALLBACK_RESOURCE_ERROR, marks resource state
   uncertain, and freezes restart-required; a later callback is cleanup/diagnostic
   only;
5. lifecycle, permission, camera, scope, critical thermal, or other named abort
   admitted at or before the original drain deadline assigns ATTEMPT_ABORTED to every
   accepted unfinished frame and freezes the attempt incomplete. An abort after that
   original deadline is diagnostic while a reserved disposal finishes;
6. window freeze makes counters/statistics immutable, but the functional result,
   terminal/fallback authorization, and cache eligibility remain tentative until
   timely clean teardown and the generation listener barrier are sealed with zero
   ErrorListener events. A result callback after freeze still disposes output without
   changing counters. Any ErrorListener before that seal overrides the tentative result
   with restart-required resource uncertainty; a defensive post-seal event revokes it
   under the pinned-contract-breach rule.

Therefore a callback and watchdog both scheduled at exact equality have one legal
result: the watchdog cannot expire, and the callback may reserve. If a finalizer first
acquires the gate with a strictly later clock reading, timeout/freeze wins. Pure
fixtures exercise both acquisition orders at equality and `+1 ns`.

### Failure-effect matrix and CPU fallback

No arbitrary `fatal` input exists.

| Event | Effect | Same-process CPU fallback |
| --- | --- | --- |
| Artifact/build-manifest/model-hash ambiguity or pre-create journal failure | Incomplete/release-blocking; no native call | Never |
| Permission/lifecycle/bind exception or timeout/camera stall/scope/timebase/source/clock/correlation failure | Incomplete | Never |
| Workload occupancy absent/unstable | Incomplete; reposition/retry | Never |
| First/transient/different input conversion or layout violation | Incomplete; one controlled pre-native rebind only | Never |
| Same static RGBA layout violation on first callback after that exact controlled rebind | Scoped Unsupported | Never |
| Delegate option/proto failure proven before entering `PoseLandmarker.createFromOptions`, or detect exception with input cleanup and teardown proven clean | Terminal delegate failure | Required for selected GPU |
| `PoseLandmarker.createFromOptions` throw/hang after entry | Restart-required incomplete; quarantine | Next clean launch only |
| Result callback timeout with teardown proven clean | Terminal delegate failure | Required for selected GPU |
| Any pinned ErrorListener, regardless of generation/outstanding task | Callback-output-undelivered/resource-uncertain incomplete; quarantine | Next clean launch only |
| Pre-native proxy close or analyzer detach/unbind uncertainty | Restart-required incomplete; no delegate blame | Never in that process |
| Create/submission hang, native/process abort, post-create ImageProxy/input close failure, buffer zero/release failure, analyzer detach/unbind failure | Restart-required incomplete; quarantine | Next clean launch only |
| Callback-output disposal throw/hang or dependency failure before an output handle is delivered | Restart-required incomplete; quarantine | Next clean launch only |
| Landmarker close throw/hang | Restart-required incomplete; quarantine | Next clean launch only |
| Journal mutation failure after native create | Restart-required incomplete; no cache/new native | Never in that process |
| Journal/mode-store read exception, corruption, or timeout | Incomplete persistence; no native/cache overwrite | Fresh clean process may retry strict recovery |
| Journal/mode-store mutation exception before commit authorization with exact prior/absence and clean siblings verified | Incomplete persistence; strict prior payload remains | Fresh clean process may retry |
| Pre-authorization cleanup cannot verify exact prior/absence plus clean siblings | Persistence outcome unknown; no native/cache/success | Next clean launch strict recovery only |
| Post-authorization direct verification returns exact prior/absence and clean siblings | NOT_COMMITTED; restart-required, no native/cache/success | Next clean process may retry |
| Journal/mode-store throw, third state, residual sibling, or timeout after commit authorization | Persistence outcome unknown; affected mode generation poisoned, no native/cache/success | Next clean launch strict old/new recovery only |
| API29+ thermal-listener attach/current-status failure, invalid status, FIFO/runner/cutoff failure, or any observed state at least CRITICAL | Incomplete safety evidence; absorbing same-process safety latch, no native/cache/game service | Never in that process |
| Noncritical thermal callback admitted after measurement cutoff, or any callback after best-effort shutdown unregister returns | Frozen historical bytes remain unchanged; callback stays live safety input before shutdown or state-inert cleanup during shutdown; no journal poison | Not applicable |
| Public FrameMetrics removal returns but no native fence exists | Persist exact REMOVAL_FENCE_UNPROVEN zero render evidence; ML may classify, G8 remains blocked | Not applicable |
| FrameMetrics removal throw/timeout, or proved authoritative barrier failure | Restart-required incomplete; no cache/new measurement | Never in that process |
| PSS or cleanly unavailable FrameMetrics collection | ML may classify; release/effect evidence PARTIAL | Not applicable |
| Valid exact-duration result below floor | Measured low performance | Never |

A safe candidate delegate failure excludes it. A selected GPU safe terminal failure
must run CPU exactly once unless CPU is QUARANTINED or TERMINAL_THIS_ATTEMPT. Prior
valid low CPU remains eligible. The fallback creates a fresh CPU candidate with five
warm-ups/five seconds, closes it, then a second fresh CPU selected runtime with five
warm-ups/ten seconds. CPU receives no second retry. Valid GPU low/class degradation
never triggers fallback. Restart-required failure resumes only after journal recovery
in a new process; no orphaned/uncertain resource may coexist with measurement.
If a selected CPU terminates cleanly while a nonterminal GPU candidate exists, the
attempt is incomplete rather than silently switching to GPU or claiming Unsupported;
an explicit fresh probe may rerank. Selected CPU failure is Unsupported only when all
other available routes are already proven-clean terminal failures.

### Functional outcome precedence

Classify each requested mode exactly once after finalization and clean teardown:

1. artifact/build/profile/journal ambiguity; lifecycle/camera/scope/timebase/clock/
   correlation failure; occupancy failure; unfinished phase/drain; resource uncertainty;
   or any recovery state that leaves the selected or mandatory-CPU route quarantined,
   RETRY_CONSUMED, or unmeasured before a fresh steady result exists is
   `PROBE_INCOMPLETE` and is never cached;
2. a permanent scoped incompatibility, or every available CPU/GPU route ending in a
   proven-clean terminal failure with no quarantined/unknown route, is scoped
   Unsupported. Persist it with delegate NONE only after clean journal reconciliation;
3. otherwise classify the fresh selected steady window. A structurally complete
   exact-duration low result with fewer than 30 representative completions is measured
   low rather than incomplete only when all four quartiles are positive; the runtime-
   reachable range is therefore 4-29, while pure statistic functions still test 0-3;
4. selected-GPU terminal failure is not final until the mandatory CPU route succeeds,
   measures low, terminates cleanly, or is proven unavailable. A quarantined/uncertain
   CPU makes the result incomplete, not Unsupported;
5. profile save failure removes the attempted classification from observable state.
   Only an unchanged independently valid prior record may still be served.

For solo, support yields a saved solo result; measured low/permanent incompatibility
yields Unsupported. For requested dual, full/conditional/valid-below-floor map to
A/B/C as below; permanent dual incompatibility maps to `C+INCOMPATIBLE` while retaining
solo; an incomplete dual maps only to `SOLO_VALID_DUAL_INCOMPLETE`. No lower-priority
condition can overwrite a higher-priority incomplete/resource state.

CapabilityModeStoreV4 persists no free-form timeline. Its finite fallback language
requires exact CPU-then-GPU candidate tokens followed by one legal direct-measured,
selected-GPU-to-fresh-CPU-fallback, all-clean-terminal, or static-layout resolution.
Every clean-terminal token has one ordered proof naming delegate/role/reason, pre-native
or all-owner-clean closure, and the exact fully resolved owner mask. ErrorListener and
every resource-uncertain category have no legal terminal token or proof.

SCOPED_UNSUPPORTED is reader-derivable only from one of two PRESENT proof variants:
two matching first-callback proxy-only static violations around exactly one same-scope
controlled rebind with two clean proxy closes and zero native entries; or exact final
CPU+GPU clean-terminal proofs with no unknown/quarantined/RETRY_CONSUMED route and fully reconciled
required journal-clear mask plus actual no-entry state before serve. Empty/missing proof,
skipped quarantine, impossible grammar, unresolved
owner, or proof/hash mismatch makes the mode file unreadable rather than Unsupported.
The byte grammar and cross-validation are normative in CapabilityModeStoreV4.

### Rates, representative distributions, and ML tiers

With REALTIME source timestamps:

```text
frameAgeAtReceive = receivedAt - sourceTimestamp
frameAgeAtCompletion = completedAt - sourceTimestamp
pipelineAge = completedAt - receivedAt
inferenceDuration = completedAt - submittedAt
```

All are non-negative. Completion age includes CameraX residence and dependency output
conversion. Percentiles use nearest rank over ascending integers:

```text
rank = ceil(percentile * count)
value = sorted[clamp(rank,1,count)-1]
```

The writer reduces each bounded ephemeral sample vector to exact rank certificates
`(value, strictlyLessCount, equalCount)` for P50/P90/P95/P99/max. Q1/Q4 medians use the
same certificate against their quartile count. Before discarding representative
frame-age samples, it also emits CapabilityModeStoreV4's fixed-shape joint witness:
the sorted unique union of those seven certified values and per-quartile counts in
each less/equal/between/greater bucket. The reader checks bucket reachability for
nonnegative integer values, per-quartile/global conservation, every global certificate,
both quartile medians, certified maximum, and growth together. Independently feasible
summaries that cannot describe one partitioned multiset are corrupt. The witness has
at most seven checkpoints and sixty aggregate counts; it contains no frame order or
per-frame timestamp and never claims to replay discarded samples.

Window rate numerators use exact rational comparison against exact duration. The
three and only three functional FPS inputs are:

1. `cameraCallbackFps = cameraCallbackCount / duration`;
2. `completionFps = COMPLETED / duration`;
3. `representativeCompletionFps = occupancyValidCompleted / duration`.

`acceptedInputFps` is recorded but equals camera callback FPS in a support window
because rejection must be zero. `sourceFps=(n-1)*1e9/(lastSource-firstSource)` is a
diagnostic only. Camera inter-arrival is the difference between consecutive
`receivedAtNs`; source inter-arrival is recorded separately. Render FPS is release
evidence, not an ML-tier input.

Split `[start,end)` into four exact quartiles using checked integer boundaries and
`receivedAtNs`. P50/P90/P95/P99 for inferenceDuration, pipelineAge,
frameAgeAtReceive, and frameAgeAtCompletion are reported both for occupancy-valid
COMPLETED samples and for all COMPLETED samples. Camera/source inter-arrival reports
the same percentiles separately. Support uses only the occupancy-valid distributions,
requires at least 30 representative completions and five representative samples in
every quartile, and defines `growth = Q4 median frameAgeAtCompletion - Q1 median
frameAgeAtCompletion` with checked signed subtraction. Negative growth is valid and
passes an upper bound; overflow is incomplete.

| ML class | Three FPS inputs | Representative inference P95 | Frame-age growth | Max representative age | Rejection/error/abort |
| --- | ---: | ---: | ---: | ---: | ---: |
| Solo | all `>=20` | `<=50,000,000 ns` | `<=50,000,000 ns` | `<=1,000,000,000 ns` | exactly 0 |
| Dual full | all `>=15` | `<=66,666,667 ns` | `<=66,666,667 ns` | `<=1,000,000,000 ns` | exactly 0 |
| Dual conditional | all `>=10` | `<=100,000,000 ns` | `<=100,000,000 ns` | `<=1,000,000,000 ns` | exactly 0 |

All inequalities are inclusive. Capacity one and no fatal event are also required.
Candidate and steady use the same rule.

`MlCapability` is distinct from visual effect quality:

| ML outcome | Condition | Mode availability |
| --- | --- | --- |
| A | solo passes; requested dual passes full | solo + full-rate dual |
| B | solo passes; dual misses full but passes conditional | solo + conditional dual |
| C | solo passes; dual unrequested or valid dual misses conditional | solo only |
| Unsupported | selected solo misses support or is scoped Unsupported | games blocked |

A precedes B; exact 15 that misses full tail but passes conditional is B. Unrequested
dual is C+UNVERIFIED. Valid dual below 10 is C+INCOMPATIBLE. Requested dual incomplete
produces `SOLO_VALID_DUAL_INCOMPLETE`; it is not C, but the independently saved solo
mode remains available. Permanent dual-only Unsupported leaves solo available.

Slice 1B always sets `EffectCapability=CONSERVATIVE_UNVERIFIED`. It never maps ML A to
“full effects.” Until representative game rendering and thermal G8 evidence pass,
games use the conservative low-effects profile. Later evidence may promote effect
quality without changing the measured ML mode result.

### Memory, render, and thermal evidence

Selected-steady PSS KiB is scheduled exactly 13 times: before warm-up, at window start,
at offsets 1 through 10 seconds from that start, and after measurement drain. The
dependency Bitmap allocation is therefore in the sampled process. A process-wide
single-thread `PssEvidenceOwner` with a capacity-one mailbox is the only caller of
`Debug.getPss()`. It is a daemon evidence owner, never main/analyzer/runtime/state/
persistence, holds only primitive token/value data, and no caller joins or waits on its
thread. Every five-second candidate schedules exactly eight tokens: before warm-up, at
window start, at offsets 1, 2, 3, 4, and 5 seconds from window start, and after drain.
Only the selected-steady 13 enter a stored result, but candidate timing and its
poisoning behavior are bound by `probe-workload-v3`. Only process
restart may create a new owner. Warm-up begins only after its before-warmup token is
valid or has reached one terminal defect disposition; it never waits beyond that
token's watchdog.

Each schedule creates one generation-bound token with the 500 ms deadline in the
normative table. READY alone may reserve and submit one runnable. If the owner is busy
or poisoned, the token resolves `owner_unavailable` without queueing. Watchdog expiry
before the runnable enters the call is `start_timeout`; expiry while inside the call is
`call_timeout` and permanently poisons the owner. A nonnegative value that returns and
is admitted at or before the deadline is valid. Throw or negative value is
`call_failure` and returns the owner to READY. A late return closes no app resource, is
token- and state-inert, never unpoisons the owner, and cannot change a frozen metric,
tier, cache, or later attempt.

The PSS owner holds no frame, image, landmarker, input buffer, listener, or persisted
payload. After every token is resolved valid/missing, even a timed-out daemon call is
detached evidence rather than a probe-owned runtime resource; it does not keep UI in
FINALIZING or block a clean capability save. One or more but fewer than 13 valid
samples is PSS `PARTIAL`; zero is `UNAVAILABLE`; all 13 with all three scheduled
endpoints and a certified peak is `COMPLETE`. The four dispositions conserve all 13
tokens. Peak uses a maximum rank
certificate over valid values; endpoint bits distinguish a missing endpoint from an
observed zero, and the certificate equal count covers the multiplicity of every present
endpoint equal to the certified maximum. Missing is never serialized as a zero sample, and all non-COMPLETE
statuses keep effect/release evidence partial under the normative schema.

FrameMetrics listener attaches before the window through one dedicated HandlerThread
and Handler used by no other producer. Each callback immediately copies
TOTAL_DURATION, INTENDED_VSYNC_TIMESTAMP, and `dropCountSinceLastInvocation`. Active
Choreographer frame times define the intended-vsync set. Each authoritative callback
receives exactly one disposition in order `LATE, INVALID, UNMATCHED, DUPLICATE, VALID`;
those counts conserve callback count. A valid sample is unique/nonnegative and its
intended timestamp belongs to the active `[start,end)` set. Missing-active is checked
active minus valid. Duplicate, `-1`, unmatched, missing, late, or any positive
report-drop makes authoritative render evidence PARTIAL.

Android 36 `HardwareRendererObserver.notifyDataAvailable()` posts asynchronously to the
supplied Handler, while `Window.removeOnFrameMetricsAvailableListener()` only reaches an
asynchronously posted native render-thread removal and exposes no public fence. A
same-Handler sentinel drains messages already enqueued before it but cannot prove that
an already-started native notification will not post behind it. Therefore the public
Window adapter in this revision is deliberately non-authoritative: after removal it
atomically closes metric mutation, drains/discards whatever reaches its Handler through
the deadline, and persists exact `UNAVAILABLE/REMOVAL_FENCE_UNPROVEN` zero evidence.
Those late messages own no app image/native handle and are state-inert. They do not
block an ML record, but keep effects/release evidence PARTIAL and can never satisfy G8.

COMPLETE or PARTIAL render bytes are reserved and reader-invalid under capability-v15.
A later policy/schema revision may enable them only when a checked-in proof manifest
binds the shipped API/ABI implementation to an actual native-removal happens-before
fence, plus a dedicated tracking Handler. That Handler would have to reserve every observer Runnable at one state gate before calling
`super.sendMessageAtTime` outside the gate, account false/rejected posts, dispatch and
decrement in `finally`, close after main-thread removal, and enqueue one distinct
non-Runnable sentinel. The sentinel never waits: it seals only with zero reserved/
queued/running observer messages and no failure. A pre-close reservation overtaken by
the sentinel is incomplete; an observer enqueue after close or callback after that
proved seal would require a newly specified exact failure transition before pending/
final clear. Source inspection or a finite device quiet period without a native fence
is not such a proof and cannot enable the reserved states.

```text
renderFps = uniqueValidMatchedFrameMetricCount / exactMeasurementDuration
```

Render P90 `<=20 ms` and P99 `<=33 ms` are later G8 criteria. A raster-asset-free
progress animation is mechanism evidence only, not representative game-effect proof.

On API 29+, register one process-lifetime thermal safety listener through one app-owned
FIFO before warm-up. Measurement never unregisters it: the public listener is an
oneway Binder callback and `removeThermalStatusListener` exposes no happens-before edge
to a future client `Executor.execute` call. The wrapper owns `OPEN/FAILED/SHUTTING_DOWN`,
an exact capacity of 64 queued-or-running callback commands, at most one start endpoint,
one end endpoint, and one cutoff control command per measurement, a checked count, and
at most one drain runner on an app-owned asynchronous single thread. `execute()`
linearizes at the wrapper gate, appends and counts before returning, and makes the 65th
callback FAILED with `RejectedExecutionException`; it never overwrites or silently
drops an earlier command. No app lock is held while submitting the runner or executing
callback/platform work. Runner rejection, callback throw, counter error, or capacity
failure revokes capability/game use for the process and creates no new measurement.

After registration, take the start `getCurrentThermalStatus()` snapshot outside the
gate and append its endpoint command. At measurement end, take the end snapshot outside
the gate, then one gate transition appends the end endpoint and a non-callback
`MEASUREMENT_CUTOFF` behind every callback already admitted by `execute()`. The runner
freezes the measurement histogram when that cutoff reaches the head with zero earlier
queued/running callback work and no known failure. The wrapper remains OPEN afterward;
later callback commands are same-process safety events and never mutate the frozen
record. A callback generated before cutoff but delivered to `execute()` afterward is
indistinguishable from a later event. The persisted reason therefore states that
delivery coverage is unproven rather than pretending the cutoff fenced the platform.
Best-effort listener removal occurs only during process shutdown and authorizes no
journal, store, cache, or release claim. SHUTTING_DOWN keeps accepting any delayed
executor call into state-inert safety cleanup until process death; it neither throws a
post-close poison nor mutates a frozen histogram.

Persist a seven-bin histogram over the two endpoint snapshots plus callbacks admitted
before the cutoff. Its sum is observation count, its highest nonempty bin is max, and
equal endpoint values require two observations in that bin. API29+ measured evidence is
always exact `PARTIAL/DELIVERY_FENCE_UNPROVEN`, continuous-coverage bit zero, endpoint
mask `0x03`, count at least two, valid endpoints, max at most SEVERE (`3`), and no observed
critical state. COMPLETE and one-endpoint PARTIAL are reserved and reader-invalid in
capability-v15. Attach failure, missing/invalid endpoint, zero observation, known
critical, or FIFO failure is incomplete and creates no record. API26-28 remains exact
`THERMAL_UNAVAILABLE_API`: ML classification may proceed, but release evidence stays
PARTIAL and G8 cannot pass.

Every observed state `>= PowerManager.THERMAL_STATUS_CRITICAL`, including one delivered
after cutoff or first serve, immediately sets an absorbing process safety latch, aborts
a live probe, or safety-pauses the game. Invalid status and FIFO/runner failure set the
same latch; a later lower callback cannot clear it, and only a clean process may
re-evaluate. A synchronous current-status check plus a clean in-memory safety latch is
required before every native create, pending prepare, cache service, game start, and
game resume. Those checks protect
the current process; they do not upgrade the stored historical evidence. Missing PSS/
render is likewise explicit partial/unavailable, never zero, and every non-complete
collector retains conservative effects. Pending-store prepare occurs only after the
thermal cutoff has frozen, the current status is noncritical, and the public
FrameMetrics owner has returned from removal, disabled all metric mutation, and entered
its exact non-authoritative closed state; capability-v15 makes no native render-
quiescence claim.

### Stored data and privacy

Profiles persist only scoped keys, aggregate rank certificates/counts, categorical
clean-terminal/Unsupported proof, evidence completeness, thermal state, delegate/effect
state, and a finite fallback trace. Journal persists only artifact/base/result hashes
and bounded categorical recovery/commit state. Forbidden: frames, buffers, Bitmaps,
landmarks, per-frame pose counts, raw sample vectors,
source/task timestamps, frame/task/generation/correlation IDs, raw camera/OS IDs, paths, serials,
manufacturer/model/vendor, generic maps, and user identifiers. Corrupt/unknown data
fails closed.

## Boundary

This policy is an implementable probe contract, not a physical tier proof. Physical
`slice-1b-accepted` requires actual configuration/distributions, complete release
evidence, and clean independent review. Original Slice 1 owns switching, physical
one/two-person overlay, and overlay gates. G8 owns representative game render, event
latency, effect promotion, and thermal soak.

## Alternatives considered

- Device/model allowlist: rejected; SSOT requires measurement.
- Measure pinned `Delegate.NPU`: rejected; its acceleration proto is empty.
- Treat `numPoses` as scene occupancy: rejected; it is only a maximum.
- Let high callback volume hide pose absence or processing errors: rejected.
- Use source/Git identity for crash recovery: rejected; exact installed APK bytes are
  stable for dirty builds and directly observable.
- One crash sentinel that replaces prior delegate state: rejected; bounded per-scope
  entries preserve CPU/GPU failure history.
- Save before runtime close/journal reconciliation: rejected; unclean native state
  invalidates cache eligibility.
- Close a timed-out TaskRunner on analyzer/lifecycle: rejected; native wait is
  unbounded.
- Interpret “no lock” as banning dependency internals: rejected; only app-owned locks
  are controllable.
- Claim full effects from ML/progress-animation success: rejected; later game/G8
  evidence owns effect promotion.

## Consequences

The probe is intentionally conservative and may ask participants to reposition or
restart after native uncertainty. CPU/GPU are measured honestly; NPU is explicitly
unavailable under the pin. Empty or under-occupied scenes cannot expose modes.
Resource uncertainty cannot coexist with fallback or cache. Exact installed bytes
bound cross-launch recovery even for dirty APKs. ML availability can be useful while
visual effects remain conservatively unverified until representative physical gates.
