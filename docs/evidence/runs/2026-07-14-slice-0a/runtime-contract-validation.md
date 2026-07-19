# Slice 0A contract and packaged-runtime validation

Status: `LOCALLY VERIFIED` for the Slice 0A packaged-runtime acceptance scope. The
content-addressed debug APK, contracts, packaged model, privacy logger replacement,
and two emulator environments are directly observed. This evidence does not satisfy
physical-device, camera, two-person quality, traffic-capture, performance,
visual-asset, legal, or release-signing gates.

## Artifact identity

```text
path=artifacts/slice-0a/motion-arcade-slice-0a-58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f.apk
bytes=68351511
sha256=58D350E136C50C2B1A407FD887E66A4939CEB8AC6348545A828A69D6C76F665F
package=com.motionarcade.app.debug
certificate=CN=Android Debug, O=Android, C=US
APK Signature Scheme v2=true
sourceCommit=dadd77d15ee4595fd2ef8d7540e5ecad08cd64cd
sourceTree=0c8c299301d09262a6ac1844065ab92bcab2a88b
readOnly=true
```

The APK was produced once by the forced offline strict build at the source commit
above, copied byte-identically from mutable `app/build/` to the content-addressed
path, and marked read-only. Both subsequent device runs consumed it with
`:app:packageDebug UP-TO-DATE`; its SHA-256 was unchanged before and after API 37
and API 33 execution. `artifact-provenance.json` binds the source tree, argv,
environment, artifact, and five required validation artifacts. This is an
installable debug artifact, not a final release-signed APK. Raw signature output is
`apk-signature.txt`, SHA-256
`0642615979A0929BC5447143E9F24707776E4A04E4ECA3285D33D4FD7F0C1118`.

## Static validators and JVM contracts

Observed results:

```text
Python validator tests: 75 run, 75 passed
SUPPLY_CHAIN_STATIC_POLICY=PASS
RUNTIME_LICENSE_AUDIT=PASS configuration=releaseRuntimeClasspath coordinates=159 errors=0
debugRuntimeClasspath=159 releaseRuntimeClasspath=159 debugOnly=0 releaseOnly=0
licenseInventorySha256=A248299BCBF597D07F3CAE07EA3E9B60E81B662033B162302D110B6443E1BF1B
NATIVE_RUNTIME=PASS nativeEntries=16 unmatched=0 ambiguous=0 duplicate=0
nativeInventorySha256=C14D43AF8CCBCCF86C2AB4FD32F4692048C000515FC444DCFB3AB309F7348A86
APK_ZIP=PASS entries=545 duplicateEntries=0
DEBUG_TOOLING_SCAN=PASS dexFiles=8 ComposeViewAdapter=0 PreviewActivity=0
PROJECT_ASSET_STATUS=NO_DEPLOYED_VISUAL_ASSETS deployed=0 manifest=0
G6 is not approved: no deployed visual assets were evaluated.
APK_POLICY=PASS package=com.motionarcade.app.debug
permissions=android.permission.CAMERA,com.motionarcade.app.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
modelSha256=59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a
game-core tests=25 failures=0 errors=0 skipped=0
```

The packaged permission set therefore omits `INTERNET` and
`ACCESS_NETWORK_STATE`. This is a package observation, not a traffic-capture proof.
The model hash equals the recorded upstream artifact. No visual asset is deployed,
which preserves ImageGen lineage rules but leaves G6 explicitly unapproved.

## API 37 16 KB managed-device execution

The strict build had already forced all 224 build/test/lint tasks. The managed-device
command then reused the exact checkpoint APK while executing the device setup and
two instrumentation cases:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict :app:pixel6Api37DebugAndroidTest
```

Observed result: exit 0, `BUILD SUCCESSFUL in 1m 50s`, 4 executed and 128
up-to-date tasks. The console recorded `Starting 2 tests` and `Finished 2 tests`.
The APK SHA-256 was `58D350E1...F665F` before and after. Its normalized console log is
`androidtest-api37-16kb.log`, SHA-256
`E9A0087C6C272158767D1BF5C175283CA01F9AD536F856B84604E4FDB707FCC3`.

The persisted XML (`androidtest-api37-16kb-results.xml`, SHA-256
`9AB48D0A9B544C15D12C28869537A7FA32CA6C99C8847855F717B4F8DA162FA0`)
reports:

```text
tests=2 failures=0 errors=0 skipped=0 totalTime=3.021s
packagedPoseModelCreatesTwoPoseLandmarkerWithoutRemoteTransport=2.190s
packagedMediaPipeLoggerFactoryReturnsLocalNoOpLogger=0.002s
```

The managed AVD configuration directly records:

```text
device=Pixel 6
apiLevel=37
abi.type=x86_64
image.sysdir.1=system-images\android-37.0\google_apis_ps16k\x86_64\
tag.ids=google_apis,page_size_16kb,ai_glasses_compatible
deviceInfoName=dev37_google_apis_ps16k_x86_64_Pixel_6
deviceInfoHardware=sdk_gphone16k_x86_64
deviceInfoSha256=3246F5A4077CB6490CA73C18944F3513B1147BD92DD113F3502FA9E7379EC610
```

The result log shows `lib/x86_64/libmediapipe_tasks_jni.so` loaded successfully.
AGP still prints a `testedAbi` default warning even though the DSL pins
`testedAbi = "x86_64"`; this is retained as tool-warning debt, not hidden.

## API 33 connected execution

Device:

```text
serial=emulator-5554
model=sdk_gphone64_x86_64
AVD=Pixel_6_2
API=33
```

Command:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache '-Pkotlin.incremental=false' --offline --dependency-verification strict :app:connectedDebugAndroidTest
```

Observed result: exit 0, `BUILD SUCCESSFUL in 27s`; two tests started and finished.
The APK SHA-256 was `58D350E1...F665F` before and after the command. The normalized
console log
`androidtest-api33.log` has SHA-256
`D032D780459CABAA0633196DDE70E723E8CFC49EBC5D1E8C74C40BA14D6843F1`.

The persisted XML (`androidtest-api33-results.xml`, SHA-256
`05B47750C852ABBF8FFEBFB4D376A4A62872EB1ABAFA963B3FDC06DAA8EB4DF0`)
reports:

```text
tests=2 failures=0 errors=0 skipped=0 totalTime=1.678s
packagedPoseModelCreatesTwoPoseLandmarkerWithoutRemoteTransport=0.991s
packagedMediaPipeLoggerFactoryReturnsLocalNoOpLogger=0.004s
```

The first instrumentation case loads the packaged model, requests `numPoses=2`,
creates the native PoseLandmarker, and closes it. The second asserts that the
packaged MediaPipe logger factory returns `TasksStatsDummyLogger` after the scoped
bytecode transform. These checks prove initialization only; they do not prove
two-person detection, identity stability, or absence of all possible network I/O.

## App start and emulator visual observation

The content-addressed APK was explicitly reinstalled after connected tests and a
cold start returned:

```text
Status: ok
LaunchState: COLD
Activity: com.motionarcade.app.debug/com.motionarcade.app.MainActivity
TotalTime: 1448ms
processId=5866
fatalException=0 remoteLogger=0 CctBackendFactory=0 dataTransport=0 processCrash=0
```

The UI hierarchy contains `Motion Arcade` and
`Slice 0A contract and supply-chain baseline`. The captured portrait screenshot
`api33-launch.png` (SHA-256
`93DD7C50175BBD6C7DC7D4951F2268A02C2E40B9B2F317DCF3ED12FA80CCAAC0`)
was visually inspected: both strings are visible without clipping and the process
renders the expected white scaffold. It contains no game art and is emulator-only;
it cannot satisfy physical-device eye review or G6.

## Retained limitations and stop conditions

- No physical Android device is connected. Camera, 1/2-person detection, overlay,
  safety, thermal, rear-camera, and physical visual evidence remain unavailable.
- No traffic capture or diagnostic-export/storage test has run.
- The MediaPipe factory replacement is version-coupled. Dependency or bytecode
  drift must continue to fail CI/instrumentation before release.
- A change to the APK hash invalidates this artifact-bound native and runtime
  evidence and requires the validators and instrumentation to be rerun.
