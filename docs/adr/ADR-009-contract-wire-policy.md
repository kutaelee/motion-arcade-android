# ADR-009: Contract wire policy for schema gaps

- Status: Accepted for implementation; G1 approval remains conditional
- Date: 2026-07-14
- Risk tier: 2

## Context

The SSOT JSON schemas deliberately leave several wire areas open while requiring
Kotlin and Proto correspondence:

- `MotionEvent.type` is a pattern-constrained string, while contract notes call for
  a game-independent core enum without listing its members.
- JSON integers have no maximum but Kotlin and Proto integers do.
- snapshot `state` and `players` are untyped JSON objects.
- `pauseReason` is absent, null, or an arbitrary string in JSON while runtime logic
  needs a closed reason set.
- diagnostic session fields and arbitrary metric/event objects do not prevent
  sensitive coordinates by schema alone.

Leaving these implicit would make producer, replay, persistence, and privacy behavior
depend on accidental serializer choices.

## Decision

### Motion type

The wire type remains an open, validated string matching
`^[A-Z][A-Z0-9_]{1,63}$`. Runtime producers use a versioned closed registry of known
types. Game mappings consume only registry members; an unknown wire value is rejected
before state mutation. This preserves schema compatibility without treating arbitrary
future strings as executable actions.

### Integer range

The implementation range is the intersection of the schema and target types:

- timestamp, sequence, and tick: `0..Long.MAX_VALUE`
- revision and algorithm version: `0..Int.MAX_VALUE`, with stricter schema minima
- schema version: `1..Int.MAX_VALUE`

JSON values outside this range fail conversion. They are never clamped or rounded.

### Snapshot state

`state` and player entries use canonical UTF-8 JSON objects at the initial adapter
boundary; `google.protobuf.Struct` is prohibited because it loses integer precision.
Before G1 approval, each of the three game states and shared player state must receive
a versioned implementation schema and a typed Proto oneof. Field numbers may not be
reused and removed fields must be reserved.

This temporary adapter enables schema correspondence tests but is not a release
contract and may not be cited as G1 completion.

### Presence and pause reason

Required empty `metadata` and `committedRewardIds` use presence wrappers in Proto.
For pause reason, absent and explicit JSON null normalize to `None`; non-null strings
must map to the closed domain `PauseReason` registry. Unknown reasons fail domain
conversion. The canonical JSON writer emits null for `None`.

### Diagnostics

Diagnostics use typed enums plus explicit metric/event allowlists. Raw landmarks,
coordinates, frames, image bytes, free-form payloads, stable body signatures, and
identifiers outside random session IDs are rejected before export. Schema validation
is necessary but insufficient for a diagnostic bundle.

## Consequences

- JSON DTO, domain model, and Proto adapter stay separate.
- Standard Proto JSON is not the SSOT JSON representation because int64 values would
  be emitted as strings.
- Kotlin validation must reject non-finite numbers even where JSON Schema libraries
  accept host-language numeric extensions.
- Future contract growth is additive and versioned; unknown executable semantics fail
  closed.

## Acceptance conditions before G1 approval

1. Pointer-to-Kotlin-to-Proto mapping covers each required schema property exactly
   once.
2. Boundary, unknown-key, non-finite, duplicate, rewind, and stale-revision tests pass.
3. Fishing, boxing, monster, and player state schemas plus typed Proto oneof are
   checked in.
4. Snapshot v1, corrupt, and future-version fixtures pass; N-1/N-2 fixtures become
   mandatory after those versions exist.
5. Descriptor compatibility and unknown-field round-trip tests pass.
6. Diagnostic allowlist negative tests prove coordinates and frame data are rejected.

## Rollback

Before a release snapshot exists, revert the contract adapter and all dependent code
to `slice-0a-start`. After persistence ships, retain the old reader, reject unsupported
future versions, restore only to `PAUSED`, and verify the snapshot hash after rollback.
