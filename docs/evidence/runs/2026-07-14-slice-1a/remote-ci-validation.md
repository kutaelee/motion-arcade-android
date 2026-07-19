# Slice 1A remote CI validation

Status: `PASS` for reviewed candidate commit
`8b53d41c6c50ae0da134f21d541229e538b6d35b` (tree
`d82ac415139753f0198b9cb9db001c7d25aff012`). Push run `29382457489` and PR run
`29382458906` both completed `ssot` and `android` successfully at that exact SHA.
This is deterministic Slice 1A and emulator evidence; it is not physical-camera,
physical-overlay, G2, or G9 approval.

The current evidence update is necessarily a later commit than the run it records.
That final evidence commit must itself pass remote CI and be named by the annotated
acceptance tag; no acceptance is claimed yet.

## Exact post-review executions

- Push run `29382457489`: `ssot` and `android` succeeded.
- Pull-request run `29382458906`: `ssot` and `android` succeeded.

Both evaluated `8b53d41c6c50ae0da134f21d541229e538b6d35b`. Machine-readable
job timing, conclusions, URLs, and representative log markers are in
`exact-head-ci.json`.

## Retained pre-review executions

Two GitHub-hosted Ubuntu 24.04 executions evaluated the same commit:

- Push run `29380386520`: `ssot` passed in 13 seconds and `android` passed in
  13 minutes 9 seconds.
- Pull-request run `29380388410`: both `ssot` and `android` passed.

The machine-readable run summaries are
`github-actions-push-29380386520.json` and
`github-actions-pr-29380388410.json`. The complete representative Push log is
retained as UTF-8/LF text with terminal ANSI sequences removed in
`github-actions-push-29380386520.log`.

## Directly observed checks

The exact post-review representative Push run recorded all of the following with
exit code zero:

- ZIP SHA-256 check, ZIP integrity check, package extraction comparison, six
  schema validations, 65 internal checksums, and `PACKAGE VALIDATION PASSED`.
- 75 Python policy tests, zero deployed visual assets, and
  `SUPPLY_CHAIN_STATIC_POLICY=PASS`.
- Gradle strict dependency verification, `test`, `lint`, `assembleDebug`, and
  AndroidTest APK assembly: `BUILD SUCCESSFUL in 6m 54s`.
- APK policy: only camera and the package-scoped dynamic-receiver permission;
  bundled pose-model SHA-256 matched.
- Native provenance: `16` APK native entries, `3` exact verified AAR owners,
  zero unmatched entries.
- Runtime technical license audit: `159` release-runtime coordinates and zero
  errors. This remains technical evidence, not legal redistribution approval.
- `/dev/kvm` was required and available.
- Android 17 / API 37 Google APIs 16 KB x86_64 system image revision 6 was
  installed; `2` tests started and `2` finished on `pixel6Api37`; the managed
  device task ended `BUILD SUCCESSFUL in 6m 27s`.

## Retained failure evidence and changed hypotheses

The successful run followed four materially different CI diagnoses:

1. The first Linux checkout could not execute `gradlew`, and three CRLF SSOT
   files were normalized by Git. The fix changed only Git mode/attributes and
   restored byte-exact 66/66 checkout equality.
2. Clean Linux dependency resolution requested Gradle/JUnit/Guava/AAPT2
   metadata not previously encountered by the Windows cache. Every added
   SHA-256 was computed from the official repository artifact and cross-checked
   against a published digest or already-locked sibling before it was committed.
3. After dependency resolution passed, Linux AGP stripped four MediaPipe native
   libraries, which correctly failed exact AAR ownership. The debug variant now
   retains native bytes through the official `keepDebugSymbols` API; release
   stripping remains unchanged.
4. The final Push and PR executions then passed the complete workflow, including
   native ownership and the API 37 16 KB managed device.

No failed run is reclassified as a pass. Their run IDs and conclusions remain in
GitHub Actions history and in the integration commit sequence.

## Remaining boundary

No physical Android device, live camera frame, physical overlay sample, or
two-person pose session was observed in these runs. Slice 1B and Original Slice 1
must supply those capabilities and evidence before G2 can be approved.
