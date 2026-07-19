# Module boundaries

Status: accepted for implementation under the design SSOT.

## Dependency direction

```text
app ───────> vision ───────> game-core
 │                            ▲
 ├─────────> games ───────────┘
 └──────────────────────────> game-core
```

`game-core` owns semantic input and deterministic simulation contracts. `vision`
translates ephemeral camera observations into those contracts. `games` consumes
only confirmed semantic events. `app` is the composition root.

## Invariants

- `games` must not depend on CameraX, MediaPipe, Compose, or raw pose landmarks.
- `game-core` must not depend on CameraX, MediaPipe, or Compose.
- A camera frame is processed only within its callback lifetime and is closed in a
  `finally` path; raw frames are neither copied nor persisted.
- Touch, fixture, and motion inputs enter rules engines as the same `MotionEvent`
  shape.
- Simulation uses fixed 60 Hz ticks and monotonic nanosecond event time.
- Confirmed events are ordered, bounded, idempotent, and never silently discarded.
- Ambiguous identity or low confidence pauses progression instead of changing score,
  difficulty, or player ownership.
- Snapshots contain safe-checkpoint game state and deterministic PRNG state, never
  raw frames, landmarks, candidates, or biometric identifiers.
- The release manifest has no `INTERNET`, microphone, storage, or background-camera
  permission.

## State ownership

| State | Owner | Persistence |
| --- | --- | --- |
| Camera frame and detector result | `vision` | none |
| Coordinate/calibration revision | `vision` / `game-core` contract | approved profile only |
| Player track and ambiguity state | `vision` | session memory only |
| Confirmed semantic event sequence | `game-core` | replay/evidence only when explicitly enabled in debug |
| Game rules, timer, score, PRNG | `games` | safe checkpoint through `app` |
| UI/navigation/settings | `app` | approved settings/session store |

## Failure direction

Detector, delegate, camera, identity, and proximity failures produce explicit
capability or pause states. They do not mutate game outcome. Recovery requires a
new calibration revision or re-arm boundary before gameplay resumes.
