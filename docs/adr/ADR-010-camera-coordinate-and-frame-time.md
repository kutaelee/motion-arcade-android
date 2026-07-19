# ADR-010: Camera coordinate transforms and frame time

- Status: Accepted for Slice 1A implementation
- Date: 2026-07-14
- Risk tier: Tier 1 local, reversible behavior contract

## Context

CameraX buffer coordinates, rotated analysis coordinates, canonical anatomical
meaning, preview display coordinates, and game-world coordinates are different
spaces. A front-camera preview may be mirrored, while anatomical LEFT/RIGHT must
remain lens-independent. Camera callbacks can also arrive with duplicate or
out-of-order timestamps, and each `ImageProxy` has a single callback-bounded owner.

Ad-hoc point formulas spread across camera, overlay, and gesture code would make it
possible to rotate or mirror twice. Android UI matrices alone would couple the
numeric oracle to an Android runtime and would not make crop ownership or failure
disposition explicit. Guessing absent metadata would let a visually plausible
overlay drive incorrect game input.

## Decision

1. `:vision` owns a pure, immutable affine mapping layer. Crop rectangles are
   expressed in original buffer coordinates and are transformed independently for
   analysis and display rotations.
2. Buffer-to-analysis mapping is rotation correction followed by crop
   normalization. It never reads lens facing.
3. Analysis-to-display mapping inverts the analysis transform back to the common
   buffer, applies the explicit preview crop, display rotation, `FIT_CENTER` or
   `FILL_CENTER`, and finally applies front-camera horizontal mirroring.
4. Only 0/90/180/270 degree rotations are valid. Missing crop/rotation metadata,
   non-finite values, and singular transforms drop the frame and increment a
   category-only diagnostic. The preview mapping basis is explicitly either a
   shared ViewPort, explicit crop rectangles, or unresolved. Only the unresolved or
   internally inconsistent ViewPort/aspect relationship pauses game input; no
   default crop or aspect mapping is guessed.
5. Overlay accuracy records RMS and maximum pixel error normalized by the preview
   short edge. Maximum error `<=3%` is target, `>5%` blocks release, and `>=10%`
   stops development until the transform is corrected.
6. `ImageProxy.imageInfo.timestamp` is the source time. Accepted timestamps must be
   non-negative and strictly monotonic; duplicate and out-of-order frames are
   rejected without advancing frame ID state.
7. One `ImageAnalysis.Analyzer` owns each `ImageProxy` and closes it in a `finally`
   path. If processing and close both fail, the processing failure remains primary
   and the close failure is suppressed.

## Boundary

Slice 1A does not bind a physical camera, run MediaPipe inference, implement
body-centric scale normalization, cache transforms across lens changes, or claim
physical overlay accuracy. Those belong to Slice 1B, Original Slice 1, and Slice 2.
The canonical meaning established here is that display mirroring cannot alter the
analysis observation or anatomical landmark label; body translation and scale are
added later without changing that invariant.

## Alternatives considered

- Per-call formulas in camera and overlay code: rejected because composition order
  and double mirroring would not have one testable owner.
- `android.graphics.Matrix` as the contract type: rejected for the pure JVM
  sandbox because domain-space types and deterministic numeric tests would depend
  on Android runtime behavior.
- Mirror analysis for the front lens: rejected because it changes internal motion
  meaning with lens choice and conflicts with the design SSOT.

## Consequences

The full rotation/lens/aspect/scale/crop product can be exercised without camera
permission or image retention. Display metadata failures become explicit pause
states rather than guessed input. Original Slice 1 must adapt real CameraX metadata
to this contract and produce physical overlay captures before G2 can pass.

## Verification

- Fixed numeric golden points for quarter turns, crop offsets, and fit/fill.
- Exhaustive 96-combination synthetic matrix: four rotations, two lenses, three
  aspect families, two preview scale modes, and full/offset crops.
- Negative tests for absent metadata, singular/non-finite transforms, unresolved
  preview mapping, duplicate/out-of-order time, and processing/close failures.
- Physical short-edge error remains pending and cannot be inferred from these tests.
