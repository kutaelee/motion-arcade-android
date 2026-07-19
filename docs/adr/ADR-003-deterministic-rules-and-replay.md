# ADR-003: Deterministic rules, time, and replay

- Status: Accepted
- Date: 2026-07-14
- Risk tier: 1

## Context

Touch, prerecorded fixtures, and live motion must produce the same game outcome for
the same semantic event sequence. Lifecycle recovery must not invent or duplicate
progress.

## Decision

- Advance simulation in fixed 60 Hz integer ticks.
- Use monotonic nanoseconds at input boundaries and convert to ticks once.
- Assign deterministic event IDs from session, player, and sequence.
- Reject duplicate confirmed events before state mutation.
- Use a project-owned versioned PRNG whose state is included in safe checkpoints.
- Persist only schema-versioned safe checkpoints; pause reasons freeze timers and
  ticks.

## Alternatives

- Wall-clock delta simulation: rejected because device scheduling changes outcomes.
- Platform random generator without state capture: rejected because replay cannot be
  reproduced.
- Persist every detector update: rejected for privacy, size, and nondeterminism.

## Verification

Golden replay tests must compare final state and state hash across repeated runs,
touch/fixture sources, duplicate inputs, rotation rebind, and corrupt/migrated save
cases.

## Rollback

Restore the previous snapshot schema reader and reject newer snapshots safely; never
partially deserialize into live game state.
