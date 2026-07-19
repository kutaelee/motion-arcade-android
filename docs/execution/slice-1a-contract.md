# Slice 1A execution contract

## Objective

Provide a camera-independent, numerically testable time and coordinate sandbox so
later CameraX and MediaPipe work cannot silently change anatomical motion meaning
with rotation, crop, preview mode, or lens choice.

## Context and downstream actor

Original Slice 1 will bind physical cameras and pose inference. Its implementer
needs one mapping and frame-ownership contract before adding device behavior. The
observable Slice 1A outcome is deterministic synthetic evidence, not a working
camera preview.

## Scope

- `:vision` affine coordinate types and mapping
- analysis/display crop and quarter-turn rotation
- display-only front mirror and preview fit/fill
- overlay error measurement and thresholds
- monotonic source timestamp sequencing
- actual `ImageAnalysis.Analyzer` close-on-all-paths ownership boundary
- unit tests, ADR, execution status, and evidence bundle

## Non-goals

- Camera binding, permission UI, lens-switch UI, or preview rendering
- MediaPipe inference or pose/body-scale normalization
- capability probing, thermal measurement, or quality tiers
- game rules, two-player identity, persistence, or visual assets
- emulator evidence as a substitute for physical overlay accuracy

## Inputs and dependencies

- ZIP SSOT SHA-256
  `C998B9EB5F102F231BB626F50864C881E7D0E83E9ADEE5868E363D4FF8094330`
- `DESIGN_SSOT.md`, `docs/02-runtime-architecture-and-concurrency.md`,
  `docs/03-camera-coordinate-contract.md`, and ADR-005 inside the extracted SSOT
- locally accepted Slice 0A tag `slice-0a-accepted`
- existing pinned CameraX dependency; no new dependency is authorized

## Impacted components

| Component | Impact |
| --- | --- |
| `:vision` | Owns transforms, frame time, diagnostics, and ImageProxy lifetime |
| `:game-core` | No code change; its `LensFacing` and future normalized frame contract are consumed |
| `:app`, `:games` | No behavior change in this slice |
| Camera/MediaPipe | Not invoked; later adapters must consume this contract |
| Privacy | Diagnostics retain categories/counts only; no frame or landmark persistence |

## Invariants

- Front mirror exists only in the display transform.
- Analysis and display transforms return through the common buffer space when crop
  or rotation differs.
- Unsupported/missing rotation, crop, display, or lens metadata is never guessed.
- A singular/non-finite mapping cannot reach overlay or game input.
- Accepted source timestamps are strictly increasing and use no wall clock.
- The analyzer closes each owned `ImageProxy` on success, rejection, or exception.
- No image byte copy, frame persistence, visual asset, or new dependency is added.

## Execution slices

1. Implement pure affine primitives and explicit setup outcomes.
   - Expected: ready/drop/pause are distinguishable.
   - Validation: fixed golden and negative unit tests.
   - Evidence: source diff and JUnit XML.
   - Rollback: revert files to `slice-1a-start`.
   - Stop: any lens-dependent analysis coordinate.
2. Implement the 96-combination transform matrix and overlay metric.
   - Expected: exact numeric oracle and 3/5/10 percent verdict boundaries pass.
   - Validation: `CameraCoordinateMapperTest`, `OverlayErrorMetricTest`.
   - Evidence: test XML and normalized Gradle log.
   - Rollback: remove mapper/metric as one vertical unit.
   - Stop: unexplained mismatch or double mirror.
3. Implement monotonic sequencing and ImageProxy ownership.
   - Expected: duplicate/out-of-order time is rejected; close occurs on every path.
   - Validation: sequencer/analyzer negative tests.
   - Evidence: test XML and normalized Gradle log.
   - Rollback: remove frame adapter as one unit.
   - Stop: an exception path leaks or closes ambiguously.
4. Run full regression, lint, policy checks, and independent diff review.
   - Expected: no prior contract or supply-chain regression.
   - Evidence: exact commands, exits, logs, result summary, commit/tree IDs.
   - Stop: test weakening, dependency drift, or unrelated package change.

## Acceptance criteria

- A test asserts all 96 rotation/lens/aspect/scale/crop combinations and fixed
  numeric golden cases pass.
- Front and back lens mapping produce identical analysis points for the same buffer
  observation; front display X is the horizontal mirror of back display X.
- Explicit differing analysis/preview crops and display rotations map correctly.
- Missing crop/rotation metadata drops the frame; an explicitly unresolved or
  inconsistent ViewPort mapping pauses input; singular and non-finite paths
  increment category-only diagnostics.
- Overlay reports RMS/max error and exact target/release-block/development-stop
  verdicts.
- Accepted frames receive monotonic `sourceTimestampNs` and deterministic frame ID;
  negative, duplicate, and out-of-order timestamps do not advance state.
- `ImageProxy.close()` is observed exactly once on analyzer success and processor or
  timestamp failure; primary exceptions are preserved.
- Full repository tests, lint, supply-chain policy, and asset policy pass without a
  dependency or deployed-asset change.

## Validation commands

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict :vision:testDebugUnitTest :vision:lintDebug --rerun-tasks
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict test lint --rerun-tasks
python -B -m unittest discover -s scripts\tests -v
python -B scripts\validate_supply_chain.py --root .
python -B scripts\validate_project_assets.py --root .
```

## Required evidence

- unified diff and changed-file inventory
- focused and full normalized Gradle logs with exit codes
- immutable copies of all `:vision` JUnit XML files and aggregate counts
- policy validator output
- source commit/tree and rollback/start tags
- independent review findings, or an explicit tool-unavailability limitation

## Risk, stop, and rollback

Risk is Tier 1: local and reversible, but it establishes a behavior contract used by
later motion decisions. Stop on lens-dependent analysis meaning, unexplained numeric
oracle mismatch, leaked frame ownership, new unreviewed dependency, or SSOT conflict.
Rollback is `slice-1a-start`; no persistent data or compatibility migration exists.
After rollback, rerun the Slice 0A evidence validator and verify the accepted APK
hash remains unchanged.

## Final return contract

Report `completed`, `partially completed`, `blocked`, or `failed safely`, together
with actual tests run/passed/failed/skipped, evidence paths, remaining physical
unknowns, and whether Slice 1B may start. Code presence alone is not completion.
