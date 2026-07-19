# CapabilityStoreV2 normative schema

> Status: rejected with `capability-v5`; historical review evidence only. No reader,
> writer, migration, or implementation may use this draft. CapabilityModeStoreV4 is
> the sole current proposed contract and remains unapproved pending rereview.

## Authority and boundary

This is the persistence schema referenced by ADR-011 `capability-v5`. It freezes the
bytes and invariants before implementation. It is not a generated `.proto`, does not
activate DataStore/protobuf dependencies, and stores no incomplete attempt.

All canonical manifests use ADR-011's domain, field-count, field-name/value length,
UTF-8, integer, boolean, and raw-hash rules. All arithmetic is checked. Required union
bytes are `0` for ABSENT or `1 || payload` for PRESENT. No map or optional semantic
field is permitted.

## Proto DataStore envelope

The eventual generated envelope has exactly these protobuf fields and no others:

```proto
message CapabilityStoreEnvelopeV2 {
  uint32 schema_version = 1;       // exactly 2
  bytes canonical_payload = 2;    // definition below
  bytes payload_sha256 = 3;        // exactly 32 raw bytes
}
```

The serializer rejects an input larger than 1,048,576 bytes before allocation. It
pre-scans wire tags and requires fields 1/2/3 exactly once with their declared wire
types, rejects trailing/unknown/duplicate tags, parses, checks schema/hash, serializes
in ascending field order, and requires byte-for-byte equality with the input. A write
uses only this canonical serializer.

## Store payload

`canonical_payload` is a `capability-store-payload-v2` manifest with `fieldCount=3`:

1. `schema_revision`: exact UTF-8 `capability-store-payload-v2`;
2. `owner_runtime_build_id`: raw 32-byte RuntimeBuildId;
3. `records`: uint32 count, followed by each record as
   `uint32_be(recordLength) || canonicalRecordBytes`.

Record count is 0..64. Records sort strictly by unsigned raw ProbeBaseScopeId and may
not duplicate a key or CapabilityResultId. A payload belongs to one RuntimeBuildId;
every embedded scope must match it. After journal recovery, an owner mismatch is
atomically replaced by a verified empty payload for the current RuntimeBuildId before
lookup/native work. Mixed-build payloads are invalid.

## Capability record

Each record is a `capability-record-v2` manifest with `fieldCount=11`:

1. `probe_base_scope_bytes`: exact canonical ProbeBaseScopeV2 bytes;
2. `probe_base_scope_id`: raw 32-byte hash of field 1;
3. `capability_result_id`: raw 32-byte ADR-011 CapabilityResultId;
4. `selected_delegate`: one byte `0=NONE,1=CPU,2=GPU`;
5. `mode_outcome`: one byte using the table below;
6. `effect_capability`: one byte, exactly `0=CONSERVATIVE_UNVERIFIED` in Slice 1B;
7. `npu_status`: one byte, exactly `0=UNAVAILABLE_PINNED_API_0_10_35`;
8. `window_metrics`: required union containing WindowMetricsV2;
9. `system_metrics`: canonical SystemMetricsV2;
10. `outcome_reason`: exact UTF-8 allowlist value below;
11. `fallback_timeline`: uint32 count 0..16 followed by one byte per ordered event.

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
must be the corresponding pair. `SCOPED_UNSUPPORTED` uses NONE and ABSENT window
metrics. Every measured outcome uses CPU/GPU and PRESENT window metrics. No incomplete,
unverified, quarantine, or requested-dual-incomplete state is a record.

Fallback event bytes are:

| Event | Value |
| --- | ---: |
| CPU_CANDIDATE_TERMINAL | 0 |
| GPU_CANDIDATE_TERMINAL | 1 |
| GPU_SELECTED_TERMINAL | 2 |
| GPU_UNCERTAIN_RESTART | 3 |
| CPU_FALLBACK_STARTED | 4 |
| CPU_FALLBACK_SUPPORT | 5 |
| CPU_FALLBACK_LOW | 6 |
| CPU_FALLBACK_TERMINAL | 7 |
| GPU_QUARANTINED_CPU_SELECTED | 8 |
| MANUAL_RETRY_USED | 9 |

Unknown events fail closed. Timeline order is causal category order only and contains
no time, generation, correlation, or frame identifier.

## Distribution

A distribution value is a required union. PRESENT contains a `distribution-v2`
manifest with `fieldCount=6`: uint64 `count`, `p50_ns`, `p90_ns`, `p95_ns`, `p99_ns`,
and `max_ns`. Count is positive; values are nonnegative, monotonic, and reproduce
ADR-011 nearest-rank output. ABSENT is allowed only when the source sample count is
zero. A count of one repeats that sample for every percentile/max.

## WindowMetricsV2

PRESENT window metrics is a `window-metrics-v2` manifest with `fieldCount=30`:

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
28. uint64 `q1_frame_age_at_completion_median_ns`;
29. uint64 `q4_frame_age_at_completion_median_ns`;
30. signed int64 two's-complement `frame_age_growth_ns`.

Only a fresh selected ten-second steady window is persisted, so duration is exactly
10,000,000,000 ns. A persisted measured window has zero source rejections and zero
processing error/timeout/resource/abort counts. Therefore camera callbacks = source
accepted = completed. Occupancy-valid completed equals the checked sum of four
quartile counts, is at most completed, and each quartile is positive. Representative
distribution counts equal occupancy-valid completed; all-completion distribution
counts equal completed. Inter-arrival distribution count is `max(callbackCount-1,0)`
and follows the required-union rule. Growth equals checked Q4 minus Q1. Rates and the
mode outcome are recomputed from these fields under ADR-011; stored outcome is rejected
if recomputation differs.

## SystemMetricsV2

`system-metrics-v2` has `fieldCount=4`:

1. canonical `pss-metrics-v2`;
2. canonical `render-metrics-v2`;
3. canonical `thermal-metrics-v2`;
4. one-byte `upstream_drop_evidence`, exactly `0=PARTIAL_UNAVAILABLE`.

`pss-metrics-v2` has `fieldCount=7`: one-byte status
`0=COMPLETE,1=PARTIAL,2=UNAVAILABLE,3=NOT_MEASURED`; one-byte endpoint-present bitmask; uint64
`sample_count`, `before_warmup_kib`, `window_start_kib`, `peak_kib`, and
`after_drain_kib`. Bits 0..3 map to those four values; absent values are zero. Peak is
the maximum valid scheduled sample. COMPLETE requires mask `0x0f` and all 13 selected-
steady samples (before warm-up, window start, offsets 1..10 seconds, after drain).

`render-metrics-v2` has `fieldCount=10`: one-byte status
`0=COMPLETE,1=PARTIAL,2=UNAVAILABLE,3=NOT_MEASURED`; uint64 `active_vsync_count`,
`valid_matched_count`, `duration_ns`; TOTAL_DURATION distribution union; uint64
`invalid_count`, `duplicate_count`, `unmatched_count`, `late_count`, and
`report_drop_count`. Duration is 10,000,000,000 ns for a measured record. COMPLETE
requires positive active/valid counts, a PRESENT distribution whose count equals valid,
one valid unique match per active vsync, and all five defect counts zero. Render FPS is recomputed from
valid count/duration and never changes ML outcome/effect state in Slice 1B.

`thermal-metrics-v2` has `fieldCount=5`: one-byte status
`0=COMPLETE,1=UNAVAILABLE_API,2=NOT_MEASURED`; uint32 public Android `start_status`, `end_status`,
`max_status`; and one-byte `critical_observed`. COMPLETE is API 29+, all statuses are
exact public values 0..6, max is at least start/end, all observed values were below
CRITICAL value 4, and critical_observed is zero for
any persisted record. UNAVAILABLE_API is API 26..28 and uses zero status values.

PSS/render partial or unavailable is compatible only with conservative effect state
and release-partial evidence. Thermal listener failure on API 29+ and any critical
observation are incomplete and therefore cannot be persisted. SCOPED_UNSUPPORTED uses
NOT_MEASURED for all three system collectors, zero masks/counts/values/duration, and
ABSENT distributions.

## Cross-record and repository invariants

- Scope bytes are parsed and validated before their hash/result ID is accepted.
- A CPU/GPU selected record is invalid while the exact journal has a recovery entry for
  that selected delegate. Other-delegate quarantine may coexist.
- Solo and dual records pair only when every scope field except mode is byte-identical.
  Combined A/B/C/incomplete UI is derived and never stored as a second authority.
- A dual update may replace only its exact DUAL key and cannot delete/replace the paired
  SOLO key. Solo is committed first.
- Empty reset writes owner=current RuntimeBuildId and zero records, then rereads and
  validates it before journal/native work.

## Privacy allowlist

The only persisted bytes are the fields above. Forbidden even inside future envelope
fields: pixels, frames, buffers, Bitmaps, landmarks, per-frame pose counts, raw source/
task timestamps, frame/task/generation/correlation IDs, raw camera/OS identifiers,
installed or host paths, serial/manufacturer/model/vendor/user identifiers, and generic
maps. Revision requires a new ADR, schema domain, migration/rollback proof, and tests.
