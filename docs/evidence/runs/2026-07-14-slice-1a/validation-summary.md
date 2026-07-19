# Slice 1A local and remote validation evidence

Status: `EXACT-HEAD CI PASS / FINAL EVIDENCE CI PENDING`. All deterministic
Slice 1A acceptance checks
passed at corrected source commit `500ca21df791a034381c22fd1f6d8bb553d69dae`
(tree `75cf093cab5ed34236967ee285ae3d2d3513b239`). Pre-review Push and PR CI passed
at `6c46b1b9be7e417bb08f52fe207b9f4f0daedfb9`; an independent reviewer then
found a P2 precedence defect, which is fixed and locally regressed here. A second
clean independent review at candidate `b9a22d995aa9daa232a0776309bd697c0f8f8c97`
found no actionable P0/P1/P2 issue. Push and PR CI then passed at reviewed
candidate `8b53d41c6c50ae0da134f21d541229e538b6d35b`. This evidence-record
commit must itself pass CI before the acceptance tag. This is not yet Slice 1A
acceptance, Original Slice 1, or G2 approval.

## Objective and implementation boundary

The slice establishes pure affine camera-coordinate mapping, display-only front
mirroring, preview fit/fill and crop handling, overlay error measurement, monotonic
source timestamps, and an actual CameraX `ImageAnalysis.Analyzer` ownership path
that closes `ImageProxy` under success and failure. It does not bind a camera, run
MediaPipe, normalize a body pose, or measure a physical overlay.

No dependency version, runtime lock set, model, permission, visual asset, app UI,
game rule, or package source changed. Linux CI required verified metadata for
platform-specific artifacts and debug-only preservation of native AAR bytes; release
native stripping remains unchanged. The previously accepted Slice 0A APK remains
read-only and its SHA-256 remained
`58D350E136C50C2B1A407FD887E66A4939CEB8AC6348545A828A69D6C76F665F`.

## Commands and observed results

Focused forced run:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict :vision:testDebugUnitTest :vision:lintDebug --rerun-tasks
```

Observed exit 0, `BUILD SUCCESSFUL in 45s`, 76/76 actionable tasks executed. The
UTF-8/LF normalized console evidence is `focused-gradle.log`.

Full forced regression:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict test lint --rerun-tasks
```

Observed exit 0, `BUILD SUCCESSFUL in 56s`, 170/170 actionable tasks executed. The
normalized console evidence is `full-regression-gradle.log`.

JUnit XML reports 27/27 `:vision` tests and 25/25 existing `:game-core` tests, with
zero failures, errors, or skips across eight suites. The coordinate test directly
asserts 96 combinations:

```text
4 rotations × 2 lenses × 3 aspect families × 2 preview scale modes × 2 crop variants
= 96 evaluated combinations
```

It also retains fixed numerical golden points for rotations, a rotated offset crop,
different analysis/preview crops, different analysis/display rotations, and
FIT/FILL offsets. Negative cases cover absent crop/rotation, non-finite and singular
transforms, unresolved/inconsistent ViewPort mapping, duplicate/out-of-order time,
frame ID exhaustion, timestamp/processor/diagnostic failures, and primary/suppressed
close failures.

Python policy tests passed 75/75. Static supply-chain policy passed. Asset policy
reported zero deployed visual assets and explicitly left G6 unapproved.
`candidate-evidence-audit.json` records successful JSON/XML parsing, aggregate
test-count reconciliation, bundle coverage, text normalization, and a bounded
credential-pattern scan with zero unmasked findings.

## Retained failed hypothesis and correction

The first test after introducing an explicit ViewPort mapping basis ran 26 tests
with one failure. A singular analysis crop was classified as shared-ViewPort
inconsistency before its own validity was checked, producing pause instead of the
SSOT-required frame drop. The validation order was changed so analysis and preview
metadata validity is established before comparing a shared ViewPort. The focused
and full forced runs above are after that correction and passed.

This was a changed hypothesis and method, not a repeated retry: the failure showed
classification precedence, so the code and negative test now distinguish missing or
singular per-frame metadata (`DROP_FRAME`) from a valid but unresolved or
inconsistent ViewPort relationship (`PAUSE_INPUT`).

The first independent review found that a later refactor still returned
`UNRESOLVED_PREVIEW_MAPPING / PAUSE_INPUT` before validating singular or non-finite
metadata. The combined regression test failed 1/1 before correction with a
zero-width analysis crop and unresolved preview basis. Commit
`500ca21df791a034381c22fd1f6d8bb553d69dae` now validates all required drop metadata
before any ViewPort pause branch. Seven combined fixtures cover singular analysis,
non-finite buffer, missing/invalid preview crop, missing display rotation,
non-finite display size, and missing mapping basis. The targeted test and both
forced suites above passed after correction.

Remote CI also retained successive, materially different failure hypotheses before
the pre-review green run: Linux executable/text attributes, platform-specific
verified dependency metadata, and Linux debug native stripping. The exact attempts
and successful Push/PR runs are recorded in `remote-ci-validation.md` and
`integration-provenance.json`.

## Evidence limitations

- Independent review 1 found and caused correction of a P2 defect, then stopped
  when concurrent evidence capture made the shared worktree dirty. Independent
  review 2 ran from clean candidate `b9a22d9...`, changed no file, and reported no
  actionable P0/P1/P2 finding.
- No physical Android device is connected. Physical 3% target, 5% release block,
  camera rotation/lens switching, and one/two-person pose behavior remain pending.
- The overlay metric thresholds are executable, but no physical samples have been
  measured.
- Remote GitHub Actions passed Push run `29382457489` and PR run `29382458906` for
  reviewed candidate `8b53d41c...`. Because this summary and `exact-head-ci.json`
  are a later evidence-only delta, that final commit's CI and annotated acceptance
  tag remain pending.

## Local verdict

`APPROVE WITH CONDITIONS` for publishing the final evidence commit. The
independent-review and reviewed-candidate CI conditions are satisfied. Conditions
before the `slice-1a-accepted` tag are: remote `ssot` and `android` jobs pass for
this final evidence commit and evidence hashes verify.
Original Slice 1/G2 still requires physical camera and overlay evidence regardless
of this verdict.
