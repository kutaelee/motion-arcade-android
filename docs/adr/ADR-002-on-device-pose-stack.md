# ADR-002: On-device camera and pose stack

- Status: Accepted with conditions
- Date: 2026-07-14
- Risk tier: 2

## Context

The application requires one- and two-person pose interpretation while remaining
offline and never storing frames.

## Decision

Use CameraX image analysis with keep-only-latest backpressure and MediaPipe Pose
Landmarker Tasks on-device. The exact pinned dependency and model artifact are not
approved until G0 records official source, version, SHA-256, license, and Gradle
verification metadata.

The vision adapter must close every `ImageProxy` in a `finally` path, emit monotonic
timestamps, and expose only normalized pose observations or semantic events outside
the module.

## Rejected alternatives

- Remote inference: violates the offline and no-upload invariants.
- Face-based identity: violates the no-biometric-identification invariant.
- Unversioned bundled model: fails supply-chain reproducibility.

## Conditions

- Physical-device overlay and performance evidence is mandatory before release.
- Delegate fallback is measured at runtime, never inferred from a device allowlist.
- If dual mode is below the SSOT threshold, the mode is hidden or labeled
  incompatible; quality is not silently reduced below a safe level.

## Rollback

Disable the vision composition and retain touch/fixture test paths. This is a
development rollback only and is not evidence that the motion product is complete.
