# Slice 0A supply-chain and build evidence

Status: `LOCALLY VERIFIED`. The local technical criteria for Original Slice 0 and
Slice 0A are supported by the artifacts below. Remote CI execution, authorized
legal notice review, and physical-device gates are not observed and are not
inferred.

## Pinned toolchain

| Item | Pinned value |
| --- | --- |
| Gradle distribution | 9.4.1 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin / Compose plugin | 2.4.0 |
| compileSdk / targetSdk | 37 / 37 |
| Android Platform | 37.0 revision 2 |
| Build Tools | 36.0.0 (AGP 9.2 default) |
| Local JDK | Oracle 17.0.17+8 |
| CI JDK target | Temurin 17.0.19+10 |

The local JDK vendor/patch is recorded rather than treated as bit-identical to CI.
Android 17/API 37 was selected after checking the official stable platform and AGP
9.2 compatibility documentation. Kotlin 2.4.0 is the current stable line used here;
the 2.4.10 lint suggestion is a release candidate, not a stable upgrade input.

Wrapper identities:

```text
distributionUrl=https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
wrapperJarSha256=55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
```

## Strict build and deterministic checks

Command:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict test lint assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
```

Observed 2026-07-14 result from source commit
`dadd77d15ee4595fd2ef8d7540e5ecad08cd64cd`: exit 0,
`BUILD SUCCESSFUL in 1m 14s`, 224/224 actionable tasks executed. The UTF-8/LF and
trailing-whitespace-normalized console artifact is
`strict-build-api37.log`, SHA-256
`CAC80E27FC69B40761E529DCC90295F0D8E66C1A990B4F4205024AAD3CCAB908`.

The three `game-core` JUnit suites reported 25 tests, 0 failures, 0 errors, and
0 skips. App lint reported 0 errors and 4 warnings; the library modules reported
0 issues. Remaining app warnings are:

- Gradle 9.6.1 availability, not an AGP 9.2 compatibility approval;
- an invalid-looking MediaPipe `0.20230731` version suggestion;
- Kotlin/Compose 2.4.10 availability, currently a release candidate;
- the application icon, intentionally withheld until ImageGen anchor approval.

Manifest merge also reports that the defensive INTERNET and
ACCESS_NETWORK_STATE removal directives currently have nothing to remove. This is
expected and the packaged-permission validator below remains authoritative.

Lock and verification identities after the strict run:

```text
app/gradle.lockfile       4E42BECB846A8DCA89FE0DB61DF1F7460D13DB735F745B827C69D6485BFC079D
game-core/gradle.lockfile 7FEEF24E833A3FB036CBE7A66B87369976EA832738249AFDDC457CDFAE99D829
games/gradle.lockfile     205B89312B0F4E6035CA83C450D23BCC248E70585FBBB2F38ED2EA84C3710C19
vision/gradle.lockfile    EA134CEFA6372393EED3DFA097DFD6AFAEB1F1DD212C7D4C1020FA518E0879AE
gradle/verification-metadata.xml
                           82D83EE85E32AF416D7CF5108B5BD16DAC2B7DFE37869C8586D6506D69B7F199
```

The repository policy validator passed after checking exact dependency versions,
all five required lockfiles, positional and named four-module edges, hash-pinned CI
actions, wrapper identities, model checksum wiring, forbidden imports, and exact
debug/release runtime alignment. Both runtime lock sets contain 159 coordinates;
`debugOnly=[]` and `releaseOnly=[]`. The machine record is
`runtime-coordinate-alignment.json`.

## Runtime dependency license audit

Command:

```powershell
python -B scripts\audit_runtime_licenses.py --output docs\evidence\runs\2026-07-14-slice-0a\runtime-license-inventory.json
```

Observed result, repeated twice:

```text
RUNTIME_LICENSE_AUDIT=PASS configuration=releaseRuntimeClasspath coordinates=159 errors=0
inventorySha256=A248299BCBF597D07F3CAE07EA3E9B60E81B662033B162302D110B6443E1BF1B
```

The schema-v2 inventory was generated twice with byte-identical output.

The inventory retains each cached Maven POM SHA-256 chain and declaration. The one
multi-license coordinate, `org.checkerframework:checker-compat-qual:2.5.3`, has an
exact-coordinate technical exception bound to both observed POM declarations,
fixed JAR/POM hashes, 10 parsed classfiles, `ACC_ANNOTATION` plus `ACC_INTERFACE`,
and the retained official 2.5.3 tagged LICENSE (SHA-256
`37AC781FD633D592519CC231473B9A25EBD84C3BBE829ABB8F588D3355EDC63C`).
Joint policy/artifact/source drift, malformed classfiles, false legal-approval
wording, and hash/read TOCTOU are negative-tested. The policy and inventory
explicitly do not infer legal or redistribution approval.

The APK-native inventory is recorded in deterministic
`native-runtime-inventory.json` and summarized in `native-runtime-inventory.md`.
Its SHA-256 is
`C14D43AF8CCBCCF86C2AB4FD32F4692048C000515FC444DCFB3AB309F7348A86`.
It covers all 16 `lib/**` entries across four ABIs and maps each byte-identical
entry to one verification-metadata-backed AAR artifact and Maven owner. Camera Core's
Apache-2.0 plus BSD-3-Clause set is retained at artifact scope because the observed
publisher metadata does not allocate BSD-covered code to an individual `.so`.

CI now runs both native ownership validation and the runtime license audit after
Gradle resolution, and byte-compares the generated license JSON with the committed
inventory. The workflow definition was statically validated; no remote GitHub
Actions run has yet been observed.

## Pose model acquisition and packaging

The fixed upstream model was moved into the vision module only after the downloaded
file matched the recorded identity:

```text
bytes=5777746
sha256=59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a
apkPath=assets/pose_landmarker_lite.task
```

The build verifies this hash before compiling. The APK validator independently
re-hashes the packaged entry. Source, version path, model card, license statement,
limitations, and redistribution-review boundary are in
`docs/supply-chain/MODEL_PROVENANCE.md`.

## Retained failed hypotheses

- compileSdk 36 with Lifecycle 2.11.0 failed AAR metadata validation. The temporary
  downgrade hypothesis was superseded when Android 17/API 37 was verified as the
  current stable SDK and the complete stack was upgraded to API 37.
- A timed-out Gradle process overlapped a second invocation and produced Kotlin
  cache/test-result collisions. Daemons were stopped, generated outputs cleaned,
  and subsequent checks use one no-daemon process with incremental compilation off.
- A direct MediaPipe `androidTestImplementation` added seven unverified legacy Guava
  artifacts. That approach was reverted; instrumentation invokes the already
  packaged runtime by reflection.
- API 37 AOSP and Google 4 KB managed images were unavailable for the requested
  matrix. The distinct Google APIs 16 KB image was selected and executed instead;
  it is valid additional coverage, not evidence that a 4 KB image ran.

## Governance boundary

The technical G0 evidence is sufficient for continued local implementation. Final
release approval still requires a complete license-text/NOTICE reconciliation by an
authorized reviewer, observed remote CI, and the later physical-device/security
gates. This file therefore does not claim G0/G9 release approval.
