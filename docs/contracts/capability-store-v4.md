# CapabilityModeStoreV4 normative schema

## Authority and boundary

This is the persistence schema referenced by ADR-011 `capability-v15`. It freezes the
bytes and invariants before implementation. Rejected CapabilityStoreV2/V3 are never
read or migrated because they were never activated or released. This document is not a generated
`.proto`, does not activate DataStore/protobuf dependencies, and stores no incomplete
attempt.

All canonical manifests use ADR-011's domain, field-count, field-name/value length,
UTF-8, integer, boolean, and raw-hash rules. All arithmetic is checked. Generic required
union bytes are `0` for ABSENT or `1 || payload` for PRESENT; the explicitly sealed
three-tag Unsupported variant is the sole exception. No map, unknown field, or
implementation-defined optional semantic field is permitted.

## Physical mode isolation and Proto envelope

There are exactly two independent installation-bounded Proto DataStore files under the
canonical private directory `noBackupFilesDir/capability_store_v4/`: `solo.pb` and
`dual.pb`. Each has a distinct serializer,
IO owner, operation generation, corruption state, checksum, and 1,048,576-byte cap.
Reading/resetting/updating one mode never opens or changes the other.

The state gate captures the existing mode-store read or mutation start timestamp before
enqueue; `ModeStoreDirectoryBootstrapV1` is the owner's first step inside that same
deadline and receives no separate or reset clock. Before any file/owner operation it
lstat/opens/fstat-validates the fixed `noBackupFilesDir` root as a directory. If its
sole fixed child `capability_store_v4` is absent with exact `ENOENT`, it calls public
`Os.mkdir(path, 0700)` once; `EEXIST` is accepted only after the same lstat validation.
It then opens/fstat-validates the child with `O_RDONLY|O_NOFOLLOW` (API27+ also
`O_CLOEXEC`), fsyncs/closes the child, and fsyncs/closes the already validated root.
An existing child is still lstat/open/fstat-validated before use. Symlink/non-directory,
unexpected errno, mkdir/open/stat/fsync/close failure, deadline expiry, or process death
without a later complete revalidation blocks the mode. App code may not use
`File.mkdirs`, unchecked boolean results, chmod/rename fallback, or rely on a DataStore
producer to create or validate the directory. This bounded path-based protocol assumes
the OS-owned private root, the declared single process, and no compromised filesystem.

Bootstrap returns `ModeStoreDirectoryIdentityV1`, the exact raw `st_dev` and `st_ino`
values from the opened child descriptor. The owner revalidates and freezes the same
identity immediately before every DataStore construction/update; after cancel-and-join
and before the direct verifier, it opens/fstats the child again and requires exact
identity equality. The pinned DataStore 1.2.1 bytecode unavoidably calls private
`createParentDirectories(file)` at writeScope offsets 101..106, whose offsets 18..26
call `File.mkdirs()`, discard its boolean, then require `File.isDirectory()`. That exact
post-bootstrap call is the sole exception to the app-level ban: it is not creation or
validation authority, its parent must already have the frozen identity, and any pre/
post identity mismatch blocks completion. A changed dependency hash or call sequence
invalidates this exception and the adapter.

DataStore 1.2.1 owns the exact fixed temporary sibling `<mode-file>.tmp`. Before any
DataStore instance is constructed, clean-process recovery lstat-inspects only that
mode's base and `.tmp`. A remaining regular `.tmp` is never promoted; it may be removed
with checked public `Os.remove` only when base is absent or a strict valid envelope,
after which the parent directory is fsynced/closed and both paths are re-inspected. A
symlink/non-regular path, malformed base,
failed removal, or residual `.tmp` blocks the mode. After a returned update and owner
cancel-and-join, checked parent-directory fsync precedes every direct verifier; it
requires the base to be one strict canonical envelope with its exact frozen whole-file
length/hash and `.tmp` absent. The initial direct verifier establishes file identity
before the sole DataStore owner is constructed and may return exact ABSENT on first run.
Solo recovery never opens dual base or
`.tmp` and vice versa.

Every named parent-directory fsync lstat-requires the fixed private parent to be a
directory, opens it with public `Os.open(O_RDONLY|O_NOFOLLOW, 0)` (API27+ also
`O_CLOEXEC`), fstat-requires `OsConstants.S_ISDIR(st_mode)`, then checks `Os.fsync` and
`Os.close`. Public `OsConstants.O_DIRECTORY` is not available and must not be used.
Any path/type/open/fsync/close failure blocks the mode.

`ModeStoreDiskVerifierV1` first validates the parent and requires `.tmp` lstat to return
exact ENOENT. It returns one exact tagged in-memory `ModeStoreDiskResultV1`:

- `0=ABSENT` only when base lstat also returns exact ENOENT; or
- `1=PRESENT` with uint64 `complete_envelope_length`, raw
  `complete_envelope_sha256`, exact `complete_envelope_bytes`, and the strict parsed
  canonical payload.

For PRESENT it lstat/opens the base with `O_RDONLY|O_NOFOLLOW` (API27+ also
`O_CLOEXEC`), fstat-requires regular type and frozen length `1..1,048,576` before
allocation, then repeatedly requests only the remaining positive byte count. Every
read before the frozen length must return positive progress. After exactly that many
bytes, one separate one-byte read must return exactly zero as normal EOF; a positive
return is an extra byte. It then requires an unchanged final fstat length, checks close,
and applies the exact protobuf wire-tag/canonical re-encode/payload-hash grammar.
Zero before the frozen length, nonzero at the EOF probe, changing length, or any path/
open/read/stat/close/parse failure blocks the mode. ABSENT is legal only for first-run or
an expected-old-absent pending comparison; save/reset success and cache service require
the consumer's exact PRESENT tuple. The verifier performs no write, cleanup, migration,
DataStore construction, or handler call, and the API26 branch never references
`O_CLOEXEC`.

Each file has exactly these protobuf fields and no others:

```proto
message CapabilityModeStoreEnvelopeV4 {
  uint32 schema_version = 1;       // exactly 4
  bytes canonical_payload = 2;    // definition below
  bytes payload_sha256 = 3;        // exactly 32 raw bytes
}
```

The serializer rejects an input larger than 1,048,576 bytes before allocation. It
pre-scans wire tags and requires fields 1/2/3 exactly once with their declared wire
types, rejects trailing/unknown/duplicate tags, parses, checks schema/hash, serializes
in ascending field order, and requires byte-for-byte equality with the input. A write
uses only this canonical serializer.

## Mode-store payload

`canonical_payload` is a `capability-mode-store-payload-v4` manifest with
`fieldCount=4`:

1. `schema_revision`: exact UTF-8 `capability-mode-store-payload-v4`;
2. `owner_runtime_build_id`: raw 32-byte RuntimeBuildId;
3. `mode`: one byte `0=SOLO,1=DUAL`;
4. `records`: uint32 count, followed by each record as
   `uint32_be(recordLength) || canonicalRecordBytes`.

Record count is 0..64. Records sort strictly by unsigned raw ProbeBaseScopeId and may
not duplicate a key or CapabilityResultId. Every embedded scope must match the payload
owner and mode, and payload mode must match the fixed filename. A strict valid prior
owner is not a cache miss: after current-artifact journal recovery it is atomically
replaced by the canonical empty current-owner/current-mode payload under the ADR
deadline, then strictly reread. Mixed embedded owners or mode/filename mismatch is
corruption and cannot use owner rotation.

## Capability record

Each record is a `capability-record-v4` manifest with `fieldCount=13`:

1. `probe_base_scope_bytes`: exact canonical ProbeBaseScopeV2 bytes;
2. `probe_base_scope_id`: raw 32-byte hash of field 1;
3. `capability_result_id`: raw 32-byte ADR-011 CapabilityResultId;
4. `selected_delegate`: one byte `0=NONE,1=CPU,2=GPU`;
5. `mode_outcome`: one byte using the table below;
6. `effect_capability`: one byte, exactly `0=CONSERVATIVE_UNVERIFIED` in Slice 1B;
7. `npu_status`: one byte, exactly `0=UNAVAILABLE_PINNED_API_0_10_35`;
8. `window_metrics`: required union containing WindowMetricsV4;
9. `system_metrics`: canonical SystemMetricsV3;
10. `outcome_reason`: exact UTF-8 allowlist value below;
11. `fallback_trace`: canonical FallbackTraceV1;
12. `terminal_evidence`: uint32 count 0..2 followed by
    `uint32_be(proofLength) || canonical TerminalProofV1 bytes` in trace order;
13. `unsupported_proof`: required union containing an exact UnsupportedProofV3 variant.

| mode_outcome | Value | Required scope mode |
| --- | ---: | --- |
| SOLO_SUPPORT | 0 | SOLO |
| SOLO_BELOW_FLOOR | 1 | SOLO |
| DUAL_FULL | 2 | DUAL |
| DUAL_CONDITIONAL | 3 | DUAL |
| DUAL_BELOW_FLOOR | 4 | DUAL |
| SCOPED_UNSUPPORTED | 5 | SOLO or DUAL |

Allowed outcome reasons are `SOLO_SUPPORT`, `SOLO_MEASURED_BELOW_FLOOR`, `DUAL_FULL`,
`DUAL_CONDITIONAL`, `DUAL_MEASURED_BELOW_FLOOR`,
`STATIC_RGBA_LAYOUT_UNSUPPORTED`, and `ALL_SAFE_DELEGATES_TERMINAL`. Outcome/reason
must be the corresponding pair. SCOPED_UNSUPPORTED uses NONE, ABSENT window metrics,
NOT_MEASURED system metrics, and a non-ABSENT unsupported proof variant. Every measured
outcome uses CPU/GPU, PRESENT window metrics, and ABSENT unsupported proof. No
incomplete, unverified, quarantine, or requested-dual-incomplete state is a record.

## Finite fallback grammar and terminal proof

FallbackTraceV1 is `uint32 count || count event bytes`, with count 1..6. It is a
canonical decision-slot proof, not a wall-clock event log. A resumed attempt may rerun
lost process-local candidate measurement after an already durable terminal slot; the
event is encoded in its fixed grammar slot, while no pre-crash candidate metric is
reused. Event bytes are:

| Event | Value |
| --- | ---: |
| CPU_CANDIDATE_MEASURED | 0 |
| CPU_CANDIDATE_SAFE_TERMINAL | 1 |
| CPU_CANDIDATE_SKIPPED_QUARANTINED | 2 |
| GPU_CANDIDATE_MEASURED | 3 |
| GPU_CANDIDATE_SAFE_TERMINAL | 4 |
| GPU_CANDIDATE_SKIPPED_QUARANTINED | 5 |
| CPU_SELECTED_MEASURED | 6 |
| GPU_SELECTED_MEASURED | 7 |
| CPU_SELECTED_SAFE_TERMINAL | 8 |
| GPU_SELECTED_SAFE_TERMINAL | 9 |
| CPU_FALLBACK_CANDIDATE_MEASURED | 10 |
| CPU_FALLBACK_CANDIDATE_SAFE_TERMINAL | 11 |
| CPU_FALLBACK_SELECTED_MEASURED | 12 |
| CPU_FALLBACK_SELECTED_SAFE_TERMINAL | 13 |
| ALL_SAFE_DELEGATES_TERMINAL | 14 |
| STATIC_RGBA_LAYOUT_UNSUPPORTED | 15 |

Let `C` be exactly one event 0..2 and `G` exactly one event 3..5. The complete legal
language is the following finite set of sequence forms; bracket order is exact:

```text
[STATIC_RGBA_LAYOUT_UNSUPPORTED]
[C, G, CPU_SELECTED_MEASURED]                 iff C is CPU_CANDIDATE_MEASURED
[C, G, GPU_SELECTED_MEASURED]                 iff G is GPU_CANDIDATE_MEASURED
[CPU_CANDIDATE_MEASURED, GPU_CANDIDATE_MEASURED,
 GPU_SELECTED_SAFE_TERMINAL, CPU_FALLBACK_CANDIDATE_MEASURED,
 CPU_FALLBACK_SELECTED_MEASURED]
[CPU_CANDIDATE_SAFE_TERMINAL, GPU_CANDIDATE_SAFE_TERMINAL,
 ALL_SAFE_DELEGATES_TERMINAL]
[CPU_CANDIDATE_SAFE_TERMINAL, GPU_CANDIDATE_MEASURED,
 GPU_SELECTED_SAFE_TERMINAL, ALL_SAFE_DELEGATES_TERMINAL]
[CPU_CANDIDATE_MEASURED, GPU_CANDIDATE_SAFE_TERMINAL,
 CPU_SELECTED_SAFE_TERMINAL, ALL_SAFE_DELEGATES_TERMINAL]
[CPU_CANDIDATE_MEASURED, GPU_CANDIDATE_MEASURED,
 GPU_SELECTED_SAFE_TERMINAL, CPU_FALLBACK_CANDIDATE_SAFE_TERMINAL,
 ALL_SAFE_DELEGATES_TERMINAL]
[CPU_CANDIDATE_MEASURED, GPU_CANDIDATE_MEASURED,
 GPU_SELECTED_SAFE_TERMINAL, CPU_FALLBACK_CANDIDATE_MEASURED,
 CPU_FALLBACK_SELECTED_SAFE_TERMINAL, ALL_SAFE_DELEGATES_TERMINAL]
```

No other sequence is legal. A SKIPPED_QUARANTINED event requires an exact external
mode-journal QUARANTINED or RETRY_CONSUMED entry and can occur only in a measured-direct
form selecting the other delegate; it can never reach ALL_SAFE_DELEGATES_TERMINAL. The one same-process fallback
always begins with two measured candidates, a clean selected-GPU terminal, and a fresh
CPU fallback candidate. Any uncertainty, including ErrorListener, produces no trace or
record.

A measured trace ending CPU_SELECTED_MEASURED or CPU_FALLBACK_SELECTED_MEASURED
requires selected CPU; one ending GPU_SELECTED_MEASURED requires selected GPU. It also
requires PRESENT window metrics and the measured outcome/reason recomputed for the
scope. An ALL-terminal or static trace requires NONE, SCOPED_UNSUPPORTED, ABSENT window,
and its matching proof variant. No trace ending in a safe-terminal token without either
a measured resolution or ALL token is persistable.

Each safe-terminal event except the final ALL token has exactly one proof with its
zero-based trace ordinal. `terminal-proof-v1` has `fieldCount=6`:

1. uint32 `trace_ordinal`;
2. one-byte `delegate` (`1=CPU,2=GPU`);
3. one-byte `role` (`0=CANDIDATE,1=SELECTED,2=FALLBACK_CANDIDATE,3=FALLBACK_SELECTED`);
4. one-byte `reason` (`0=OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY`,
   `1=DETECT_EXCEPTION_RETURNED`, `2=RESULT_CALLBACK_DEADLINE_CLEAN`);
5. one-byte `closure` (`0=PRE_CREATE_NO_NATIVE_ENTRY`,
   `1=POST_CREATE_ALL_OWNERS_RETURNED_CLEAN`);
6. one-byte `resolved_owner_mask`, exactly `0xff`.

Bits 0..7 prove runtime, submitted MPImage, callback MPImage, buffer, ImageProxy,
analyzer/listeners, journal active, and thermal/render collectors respectively clean or
not-created. Reason 0 requires closure 0; reasons 1/2 require closure 1. Proofs sort by
trace ordinal and must match the event's delegate/role. ErrorListener has no legal
reason. The record is rejected if any terminal event lacks one proof, any other event
has a proof, or a proof is duplicated/unresolved.

## SCOPED_UNSUPPORTED proof

Unsupported proof union tags are `0=ABSENT`, `1=STATIC_LAYOUT`, and
`2=ALL_SAFE_TERMINAL`; no generic PRESENT payload exists.

STATIC_LAYOUT contains `static-rgba-layout-proof-v1` with `fieldCount=7`:

1. raw `probe_base_scope_id`, equal to the record key;
2. one-byte `initial_first_callback_violation`;
3. one-byte `controlled_same_scope_rebind_count`, exactly one;
4. one-byte `rebound_first_callback_violation`;
5. uint32 `proxy_only_observation_count`, exactly two;
6. uint32 `clean_proxy_close_count`, exactly two;
7. uint32 `native_create_entry_count`, exactly zero.

Violation values are `0=PLANE_COUNT_NE_1`, `1=PIXEL_STRIDE_NE_4`,
`2=ROW_STRIDE_LT_CHECKED_WIDTH_X4`, and
`3=BUFFER_REMAINING_LT_CHECKED_REQUIRED_BYTES`. Initial and rebound values must match.
This variant requires the one-event static trace, zero terminal proofs, NONE delegate,
static reason, ABSENT window, and exact NOT_MEASURED collectors. Dimension/crop/
rotation/arithmetic/later-frame/different-code failures remain incomplete.

ALL_SAFE_TERMINAL contains `all-safe-delegates-terminal-proof-v1` with
`fieldCount=4`:

1. raw SHA-256 of exactly
   `uint32_be(terminalEvidenceCount) || Σ(uint32_be(proofLength) || canonicalProofBytes)`
   over terminal-evidence trace order;
2. one-byte `terminal_delegate_mask`, exactly `0x03` for CPU|GPU;
3. one-byte `unknown_or_quarantined_delegate_mask`, exactly zero;
4. one-byte `journal_clear_required_mask`, exactly `0x03`.

It requires an ALL-terminal trace, exactly one final clean terminal proof for each CPU
and GPU, NONE delegate, all-terminal reason, ABSENT window, and NOT_MEASURED collectors.
The count is exactly two for this variant; raw proof concatenation without the count or
length prefixes is never the hash input. The reader recomputes the proof hash and masks and rejects the record unless the exact
mode journal has no active/recovery entry for either delegate. Thus an empty assertion
or impossible fallback sequence cannot permanently block games.

## Distribution certificates and validation boundary

A rank certificate is exact 24 bytes:
`uint64_be(value) || uint64_be(strictlyLessCount) || uint64_be(equalCount)`.
A distribution value is a required union. PRESENT contains a `distribution-v3`
manifest with `fieldCount=6`: uint64 `count`, then certificates for P50, P90, P95,
P99, and maximum. Count and every equalCount are positive.

For percentile `q`, the exact one-based nearest rank is computed without floating point:

```text
whole = count / 100
remainder = count % 100
rank(q) = checkedAdd(checkedMultiply(whole,q), ceil(remainder*q/100))
```

The certificate must satisfy `strictlyLessCount < rank <= strictlyLessCount +
equalCount <= count`. Maximum additionally requires `strictlyLessCount + equalCount ==
count`. Certificate values are monotonic. Equal values require identical less/equal
counts. For increasing values, prior `less+equal` is at most next `less`; if values are
adjacent it must equal next `less`. Value zero requires `strictlyLessCount=0`. These
rules describe at least one nonnegative-integer multiset. ABSENT is allowed exactly when the
source sample count is zero. Count one uses `(value,0,1)` for all certificates.

Immediately before serialization, the writer owns the bounded ephemeral sample vector,
sorts exact integers, calculates the certificates, verifies every count, and then
discards the vector. Raw samples are never persisted. A reader validates certificate
self-consistency and conservation; it is not sensor attestation. It recomputes the tier
solely from stored duration/counts/certified P95/certified maximum/growth. Payload
checksum and private storage protect the benign-corruption boundary, not a compromised
device.

## WindowMetricsV4

PRESENT window metrics is a `window-metrics-v4` manifest with `fieldCount=31`:

1. uint64 `duration_ns`;
2. uint64 `camera_callback_count`;
3. uint64 `source_accepted_count`;
4. uint64 `source_rejected_negative_count`;
5. uint64 `source_rejected_duplicate_count`;
6. uint64 `source_rejected_out_of_order_count`;
7. uint64 `source_rejected_id_exhausted_count`;
8. uint64 `completed_count`;
9. uint64 `input_conversion_error_count`;
10. uint64 `inference_error_count`;
11. uint64 `completion_timeout_count`;
12. uint64 `callback_resource_error_count`;
13. uint64 `attempt_aborted_count`;
14. uint64 `outside_window_callback_count`;
15. one-byte `upstream_overwrite_status`, exactly `0=UNAVAILABLE`;
16. uint64 `occupancy_valid_completed_count`;
17. `representative_quartile_counts`, exactly four uint64 values Q1..Q4;
18. representative `inference_duration_distribution`;
19. representative `pipeline_age_distribution`;
20. representative `frame_age_at_receive_distribution`;
21. representative `frame_age_at_completion_distribution`;
22. all-completion `all_inference_duration_distribution`;
23. all-completion `all_pipeline_age_distribution`;
24. all-completion `all_frame_age_at_receive_distribution`;
25. all-completion `all_frame_age_at_completion_distribution`;
26. `camera_interarrival_distribution`;
27. `source_interarrival_distribution`;
28. exact rank certificate `q1_frame_age_at_completion_median`;
29. exact rank certificate `q4_frame_age_at_completion_median`;
30. signed int64 two's-complement `frame_age_growth_ns`;
31. canonical `representative_frame_age_joint_witness` defined below.

Only a fresh selected ten-second steady window is persisted, so duration is exactly
10,000,000,000 ns. A persisted measured window has zero source rejections and zero
processing error/timeout/resource/abort counts. Therefore camera callbacks = source
accepted = completed. Occupancy-valid completed equals the checked sum of four
quartile counts, is at most completed, and each quartile is positive. Representative
distribution counts equal occupancy-valid completed; all-completion distribution
counts equal completed. Camera/source inter-arrival distribution count is
`max(cameraCallbackCount-1,0)` and follows the required-union rule.

The two median certificates use the respective Q1/Q4 quartile count and exact P50 rank.
The reader validates them and the joint witness, then recomputes growth as checked Q4
value minus Q1 value. It
recomputes all three functional rational rates and the mode outcome from stored
aggregates. Support requires
at least 30 representative completions and at least five in each quartile. A measured
below-floor record may have 4..29 representative completions with every quartile
positive, or 30+ that fails another inclusive support threshold. Counts 0..3 cannot
form a persisted structurally complete runtime window.

### Representative frame-age joint witness

`representative-frame-age-joint-witness-v1` has exact `fieldCount=3`:

1. uint32 `checkpoint_count` in 1..7;
2. exactly that many strictly increasing uint64 `checkpoint_values`;
3. four quartile rows Q1..Q4, each containing exactly `2*checkpoint_count+1`
   uint64 bucket counts.

Checkpoint values are exactly the sorted unique union of the representative
frame-age-at-completion distribution's P50/P90/P95/P99/maximum certificate values and
the Q1/Q4 median certificate values. No missing or additional checkpoint is valid. For
checkpoints `v0..v(m-1)`, every row's buckets are in exact order:

```text
[0,v0), ==v0, (v0,v1), ==v1, ... , (v(m-2),v(m-1)), ==v(m-1), (v(m-1),+infinity)
```

All values are nonnegative integers. The first bucket is zero when `v0==0`; an open
between bucket is zero when checked `prior+1 >= next`; and every checkpoint must be at
most the overall certified maximum, making the final checkpoint exactly that maximum
and the final greater bucket zero. Each row sum equals its corresponding stored
quartile count. Aggregating all four rows must reproduce the overall representative
count and, at each of its five certificate values, the exact stored strictly-less and
equal counts. The Q1 row at the Q1 median checkpoint must reproduce that median's exact
less/equal counts; the Q4 row must do the same for Q4. Every sum and index calculation
uses checked arithmetic.

The writer derives these at-most seven checkpoints and sixty bucket counts from its
ephemeral partition before discarding samples. The reader rejects any conservation,
reachability, global-certificate, quartile-median, maximum, or growth contradiction.
This is a bounded aggregate partition witness, not frame order, a timestamp series, or
sensor attestation.

## SystemMetricsV3

`system-metrics-v3` has `fieldCount=4`:

1. canonical `pss-metrics-v3`;
2. canonical `render-metrics-v3`;
3. canonical `thermal-metrics-v3`;
4. one-byte `upstream_drop_evidence`, exactly `0=PARTIAL_UNAVAILABLE`.

`pss-metrics-v3` has `fieldCount=12`:

1. one-byte status `0=COMPLETE,1=PARTIAL,2=UNAVAILABLE,3=NOT_MEASURED`;
2. one-byte endpoint-present mask, bits 0..2 for before-warmup/window-start/after-drain;
3. uint64 `scheduled_sample_count`;
4. uint64 `valid_sample_count`;
5. uint64 `start_timeout_count`;
6. uint64 `call_timeout_count`;
7. uint64 `call_failure_count`;
8. uint64 `owner_unavailable_count`;
9. uint64 `before_warmup_kib`;
10. uint64 `window_start_kib`;
11. uint64 `after_drain_kib`;
12. required union containing the 24-byte maximum rank certificate over valid PSS values.

For a measured record, scheduled count is exactly 13 and equals the checked sum of
valid plus the four defect counts; call-timeout count is at most one. COMPLETE is
exactly valid 13, all defects zero,
mask `0x07`, and PRESENT maximum. PARTIAL is exactly valid 1..12, positive conserved
defects, endpoint bits matching successful endpoint tokens, and PRESENT maximum. For
each endpoint, an absent mask bit requires its stored value to be exactly zero; a
present bit requires one successful matching token and a value at most the certified
maximum. The maximum certificate's `equalCount` must be at least the number of present
endpoint fields whose stored value equals that certified maximum; endpoint presence
and `valid_sample_count >= popcount(mask)` remain separately required.
UNAVAILABLE is exactly valid zero, defect sum 13, mask/value zero, and ABSENT maximum.
NOT_MEASURED has every count/mask/value zero and ABSENT maximum and is allowed only for
SCOPED_UNSUPPORTED. Endpoint presence implies valid >= mask popcount. The maximum
certificate uses total count=valid. A
timeout's late return never changes its conserved defect count.

`render-metrics-v3` has `fieldCount=13`:

1. one-byte status `0=COMPLETE,1=PARTIAL,2=UNAVAILABLE,3=NOT_MEASURED`;
2. one-byte reason (`0=NONE`, `1=LISTENER_UNAVAILABLE`,
   `2=SCOPED_UNSUPPORTED_NOT_MEASURED`, `3=REMOVAL_FENCE_UNPROVEN`);
3. uint64 `duration_ns`;
4. uint64 `active_vsync_count`;
5. uint64 `callback_count`;
6. uint64 `valid_unique_count`;
7. uint64 `invalid_count`;
8. uint64 `duplicate_count`;
9. uint64 `unmatched_count`;
10. uint64 `late_count`;
11. uint64 `report_drop_count`;
12. uint64 `missing_active_count`;
13. TOTAL_DURATION distribution union.

For a measured record, duration is exactly 10,000,000,000 ns. Callback dispositions
are exclusive in order late, invalid, unmatched, duplicate, valid, and their checked
sum equals callback count. Valid is at most active; missing is exactly active-valid.
Distribution is PRESENT with count=valid iff valid is positive. COMPLETE requires
reason NONE, positive active, `active=callback=valid`, and every defect/drop/missing
zero. PARTIAL requires reason NONE and either active zero or at least one defect/drop/
missing positive. UNAVAILABLE requires LISTENER_UNAVAILABLE or
REMOVAL_FENCE_UNPROVEN, measured duration, every count zero, and ABSENT distribution.
The public Window adapter must use REMOVAL_FENCE_UNPROVEN because Handler drain cannot
fence an asynchronously posted native observer removal. COMPLETE/PARTIAL are reserved
and reader-invalid under capability-v15; enabling them requires a later policy/schema
revision with a proved native happens-before contract. NOT_MEASURED requires scoped-unsupported reason,
zero duration/counts, and ABSENT distribution and is allowed only for
SCOPED_UNSUPPORTED. Render FPS is valid/duration and never changes ML/effect in Slice 1B.

`thermal-metrics-v3` has `fieldCount=10`:

The name is retained only because no v9 store bytes were ever implemented, activated,
or released; capability-v15 is the first legal writer/reader of this sealed layout.

1. one-byte status `0=COMPLETE,1=PARTIAL,2=UNAVAILABLE_API,3=NOT_MEASURED`;
2. one-byte reason (`0=NONE`, `1=DELIVERY_FENCE_UNPROVEN`, `2=API_LT_29`,
   `3=SCOPED_UNSUPPORTED_NOT_MEASURED`);
3. one-byte `continuous_listener_coverage` bit;
4. one-byte endpoint mask, bits start/end;
5. uint64 `observation_count`;
6. uint32 public Android `start_status`;
7. uint32 public Android `end_status`;
8. exactly seven uint64 histogram bins for public states 0..6;
9. uint32 `max_status`;
10. one-byte `critical_observed` bit.

Histogram sum equals observation count; max is the highest nonzero bin and critical is
one iff bins 4..6 sum positive. Both endpoint snapshots and every listener callback
whose wrapper `execute()` linearized before the app-owned measurement cutoff contribute
one histogram observation. The process-lifetime queue holds at most 64 queued-or-running
callback commands; endpoint/cutoff controls are separately fixed at one each. A 65th
callback makes the attempt incomplete and revokes same-process service. Public thermal
unregister has no Binder-delivery fence, so COMPLETE/NONE and one-endpoint PARTIAL are
reserved and reader-invalid under capability-v15.

The sole API29+ measured form is PARTIAL/DELIVERY_FENCE_UNPROVEN: continuous coverage
zero, mask `0x03`, count at least two, both valid endpoint fields, max <=3 (SEVERE), no
critical, and the endpoint multiplicity rule below. A callback delivered after cutoff
is a same-process safety event and never changes these frozen bytes.
For each public status value, its histogram bin must be at least the number of present
start/end endpoint fields equal to that value; two equal endpoints therefore require a
bin count of at least two. This applies before the exact partial tuple is accepted.
UNAVAILABLE_API is API26-28 with its reason and every coverage/mask/count/value/bin zero.
NOT_MEASURED is allowed only for SCOPED_UNSUPPORTED with its reason and the same exact
zero form. API29+ attach/cutoff/FIFO failure, missing or invalid endpoint, zero
observation, or any known critical observation is incomplete and cannot persist.

Every capability-v15 measured record requires render UNAVAILABLE with
LISTENER_UNAVAILABLE or REMOVAL_FENCE_UNPROVEN. PSS PARTIAL/UNAVAILABLE and thermal
PARTIAL/DELIVERY_FENCE_UNPROVEN or UNAVAILABLE_API also retain conservative effect state and release-partial
evidence. Every measured record forbids collector NOT_MEASURED. SCOPED_UNSUPPORTED
requires all three collectors NOT_MEASURED in their exact zero forms.

## Cross-mode, journal, and repository invariants

- Scope bytes are parsed and validated before their hash/result ID is accepted.
- A CPU/GPU selected record is invalid while the exact mode journal has a recovery,
  QUARANTINED, or RETRY_CONSUMED entry for that selected delegate. Other-delegate
  quarantine/tombstone may coexist; a SKIPPED_QUARANTINED trace event requires that
  exact external entry on every strict read. Conversely, every other-delegate retained
  entry requires the matching skipped slot and forbids any measured/terminal event for
  that delegate in the same record. Final clear and recheck never delete such retained
  evidence. RETRY_CONSUMED is absorbing for its exact artifact/mode/base/delegate entry.
- A pending RecoveryJournalV5 commit is accepted only when the exact complete protobuf
  envelope length/hash equals `intended_new_file_length/hash` and its embedded canonical
  record hash equals `intended_new_record_sha256`; CapabilityResultId, delegate, or one
  record hash alone cannot hide an unrelated-record mutation. Retry context ID and
  same-epoch terminal proof bytes must also equal the journal's exact ordered evidence.
- POST_SEAL_MODE_POISON with the exact RecoveryJournalV5 runtime-fence
  reason forbids every record in that artifact/mode. Reset request and pending old/new
  reconciliation follow RecoveryJournalV5 before lookup.
- Solo and dual records pair only when every scope field except mode is byte-identical.
  A dual result is never exposed without a strict paired solo result. Combined
  A/B/C/incomplete UI is derived and never stored as a second authority.
- A dual read/update/reset/corruption handler cannot open or mutate solo bytes. A strict
  solo result remains serviceable while dual is unreadable and derives
  SOLO_VALID_DUAL_INCOMPLETE.
- Empty reset writes owner=current RuntimeBuildId, the fixed file mode, and zero records,
  then verifies it through the exact clean-process singleton sequence in
  RecoveryJournalV5 before journal clear or native work.
- Mode-store operations obey ADR-011 deadlines. A timed-out generation never serves a
  late snapshot or update; next-launch pending-store reconciliation is authoritative.
- A valid record is still not service authority until the current process has an exact
  NativeCloseFenceProofV1 registry match and, on API29+, a registered healthy thermal
  monitor, current status below CRITICAL, and clear absorbing safety latch. These live
  gates are never serialized into the historical record.

## Privacy allowlist

The only persisted bytes are the fields above. Forbidden even inside future envelope
fields: pixels, frames, buffers, Bitmaps, landmarks, per-frame pose counts, raw sample
vectors, raw source/task timestamps, frame/task/generation/correlation IDs, raw camera/
OS identifiers, installed or host paths, serial/manufacturer/model/vendor/user
identifiers, and generic maps. Revision requires a new ADR, schema domain, migration/
rollback proof, and tests.
