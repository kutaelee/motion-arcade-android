# Motion Arcade

> **License:** Source-available for noncommercial use only under the
> [PolyForm Noncommercial License 1.0.0](LICENSE.md). Commercial use is not
> permitted without a separate written license from the copyright holder.
> Third-party components remain under their own licenses.

Offline Android motion-game collection with three deterministic two-player games:

- Harbor Pop Fishing
- Neon Gym Boxing
- Runestone Monster Expedition

## Source of truth

`android-motion-games-design-augmented-v1.0.zip` is the immutable design source of
truth. The sibling extracted directory is retained only for auditable, read-only
inspection. Implementation lives at the repository root so package checksum
validation remains meaningful.

Normative precedence is:

1. `DESIGN_SSOT.md` invariants and approval gates in the package
2. approved ADRs
3. schemas and runtime contracts
4. `source/ORIGINAL_SPEC.md`
5. guides, runbooks, and templates

The repository does not claim release readiness until every applicable G0-G9 gate
has direct evidence. Physical-device, two-person safety, performance, visual, and
legal reviews cannot be substituted by unit tests or emulator results.

## Module boundary

- `:app` — Android entry point, UI, navigation, persistence wiring
- `:vision` — CameraX, MediaPipe, coordinate transforms, tracking, gesture detection
- `:game-core` — deterministic time, events, replay, snapshots, shared game contracts
- `:games` — the three rules engines; no CameraX or MediaPipe dependency

See [module boundaries](docs/architecture/module-boundaries.md) and the
[slice execution contract](docs/execution/slice-status.md).

## Reproducible verification

Pinned project environment:

- Gradle 9.4.1 wrapper with distribution and wrapper-JAR SHA-256 checks
- Android Gradle Plugin 9.2.1
- Kotlin and Compose compiler plugin 2.4.0
- compileSdk/targetSdk 37; Android SDK Platform 37.0 revision 2
- Android SDK Build Tools 36.0.0, the AGP 9.2 default
- JDK 17 (local evidence: Oracle 17.0.17+8; CI target: Temurin 17.0.19+10)

The first build may download an approved missing Android SDK package because
`android.builder.sdkDownload=true`; Android SDK licenses must already be accepted.
Runtime model download is prohibited.

From the repository root on PowerShell, run the deterministic local checks with:

```powershell
python -B -m unittest discover -s scripts\tests -p 'test_*.py' -v
python -B scripts\validate_supply_chain.py --root .
python -B scripts\validate_project_assets.py --root .
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --dependency-verification strict test lint assembleDebug :app:assembleDebugAndroidTest
python -B scripts\audit_runtime_licenses.py --output docs\evidence\runs\2026-07-14-slice-0a\runtime-license-inventory.json
$abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
foreach ($abi in $abis) {
    $apk = "app\build\outputs\apk\debug\app-$abi-debug.apk"
    python -B scripts\validate_apk_policy.py --apk $apk --aapt2 "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt2.exe"
    python -B scripts\validate_native_runtime.py --apk $apk --expected-abi $abi --output "build\native-runtime-$abi.json"
}
```

With the existing API 33 AVD connected:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --dependency-verification strict :app:connectedDebugAndroidTest
```

The pinned Android 17 16 KB-page managed-device check is:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --dependency-verification strict :app:pixel6Api37DebugAndroidTest
```

`app/build/outputs/apk/debug/app-<abi>-debug.apk` files are mutable build outputs and must not be
cited as release artifacts. Evidence under `docs/evidence/runs/` is generated locally
and may contain machine-specific paths. This initial public snapshot includes only the
sanitized historical baseline; new run output is ignored. Validate a locally generated
evidence bundle with:

```powershell
python -B scripts\validate_evidence_bundle.py --root . --manifest docs\evidence\runs\2026-07-14-slice-0a\EVIDENCE_BUNDLE.sha256 --provenance docs\evidence\runs\2026-07-14-slice-0a\artifact-provenance.json
```

APKs and evidence archives are intentionally excluded from Git. A clean clone must
rebuild the APK and generate its own evidence before the handoff check can pass.
GitHub Actions is intentionally disabled for this repository; verification is run
locally to avoid consuming hosted Actions minutes.

## Current status

This repository is an unfinished development snapshot, not a release-ready product.
Authorized legal review, physical-device validation, and applicable G1-G9 release
evidence remain pending. See [the evidence index](docs/evidence/EVIDENCE_INDEX.md)
for the required evidence boundaries; newly generated run artifacts are not published.

The ImageGen prompt registry, style profiles, provenance hashes, and review status
are published, but generated image bytes whose `redistributionReviewStatus` is still
`PENDING` are withheld. Therefore this public snapshot is not expected to assemble
the asset-complete APK until a human redistribution review approves those bytes.

## License

The project-authored source code and original assets are licensed under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md). This is a source-available,
noncommercial license and is not an OSI-approved open-source license. See
[NOTICE](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md) for scope and
dependency-license boundaries.
