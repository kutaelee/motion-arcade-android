# Evidence index

Evidence status terms:

- `VERIFIED` — directly observed artifact or command output
- `PARTIAL` — relevant evidence exists but does not satisfy the full gate
- `PENDING` — not executed or not observed
- `BLOCKED` — a named prerequisite is absent

| Claim or gate | Status | Evidence |
| --- | --- | --- |
| Design ZIP integrity | VERIFIED | ZIP SHA-256, CRC, encryption scan, and 65/65 internal checksum comparison recorded in `docs/execution/slice-status.md` |
| Package semantic validation | VERIFIED | Linux validator passed six schemas, example, manifest, 44 registry assets, and 65 checksums; see `runs/2026-07-14-slice-0a/package-validation.md` |
| Clean immutable implementation baseline | VERIFIED | Git commit `58c98f915419363498ce08e6ae761a1660ceb34b`, tag `slice-0a-start` |
| Slice 0A artifact provenance | VERIFIED | source commit `dadd77d15ee4595fd2ef8d7540e5ecad08cd64cd`, source tree, forced offline build argv/environment, read-only content-addressed APK, and required validation evidence are bound by `artifact-provenance.json` |
| Android toolchain availability | PARTIAL | pinned Gradle 9.4.1/AGP 9.2.1/Kotlin 2.4.0/API 37 stack built locally with Oracle JDK 17.0.17 and remotely with Temurin 17.0.19; API 33 and API 37 16 KB emulator tests passed, while physical targets remain unobserved |
| G0 technical/local supply chain | VERIFIED | wrapper/model hashes, five locks, strict offline verification, module/action policy, debug=release 159-coordinate graph, schema-v2 runtime license audit, exact native ownership, APK policy, and local build passed |
| Remote CI execution | VERIFIED | final evidence commit `9ecc3ccc...` passed Push `29383341427`, PR `29383342660`, and acceptance-tag Push `29383930801`; merged `main` commit `97e0c213...` passed `29384524084`, with `ssot`, strict build/APK/native/license checks, KVM, and API 37 16 KB tests successful |
| Slice 1B rollback-tag CI | PARTIAL | run `29385104146` passed SSOT/build/test/KVM on both attempts; attempt 1 lost hosted-runner communication in API 37 and the single different-runner retry booted the same emulator/image but crashed its instrumentation process before running tests; exact-tree merged-main run `29384524084` had passed 2/2, so tag CI is not claimed passed and no identical third retry was made |
| Slice 1A time/coordinate sandbox | VERIFIED | corrected source `500ca21df791a034381c22fd1f6d8bb553d69dae`; 96 transform combinations, invalid-before-unresolved precedence, 27/27 vision tests, 25/25 contract regressions, clean independent rereview with no actionable P0/P1/P2, 28/28 evidence entries, annotated `slice-1a-accepted` tag, and merged-main CI |
| Slice 1B capability policy | BLOCKED | v1-v13 remain immutable rejected history. v14 candidate `e9216b81...` and authorization `7ef91b56...` are preserved, but actual implementation `H=7a8b69b...` fails formal descendant authorization because `d1d276f...` changed protected `scripts/tests/test_validate_capability_policy.py`; exact v14 authorization also exposes two incompatible candidate-stage tests. Review 14 records P0 0/P1 1/P2 0. A runtime-neutral `capability-v15` repair must freeze dual-stage test behavior before `C`, bind v14 authorization/reviews as historical bytes, pass full discovery at both `C` and `A`, receive three fresh exact reviews, and authorize the rebuilt implementation chain. Kernel automated tests are prototype evidence only until that actual descendant gate passes; no Slice 1B acceptance, G2/G6/G8, physical, or release claim is made. |
| DataStore 1.2.1 reset artifact | VERIFIED | official JVM jar/source hashes and a 5/5 JVM fixture directly observed same-process duplicate-owner rejection, cancel-and-join sequential reopen, descriptor-synced same-read replacement, reinvocation, one-shot CAS permit, and handler-free verification; bytecode confirms `.tmp`/sync but not `ATOMIC_MOVE` or parent-directory durability, and Android crash reproduction remains pending |
| Android public recovery API surface | PARTIAL | official API levels plus hashed API 33/36/37 stubs confirm `Os` mkdir/read/write/stat/sync/remove/rename, API27+ `O_CLOEXEC`, and `O_NOFOLLOW`/`S_ISDIR`; public `O_DIRECTORY` is absent, so v13 uses checked component mkdir plus open/fstat directory validation and distinguishes premature zero from the required EOF zero, while API26/27 linkage and device fsync/crash behavior remain pending |
| APK DEX/JNI ZIP-name flags | VERIFIED | raw EOCD/central/local-header inspection of Slice 0A APK SHA-256 `58D350E1...F665F` found 8 DEX + 16 JNI entries, all with local/central flags 0 and identical ASCII raw names; v13 permits UTF-8 bit 11 zero/one while requiring raw-name equality and adds a whole-container grammar, without claiming native fence proof |
| Human QA portal and APK handoff | PARTIAL | owner-only portal `https://motion-arcade-qa-desk.kutaelee0.chatgpt.site` is deployed from subtree `f0b914d...`, archive SHA-256 `8F00364B...E81E35`, and passed post-merge main CI `29401938885`; it truthfully shows no active playable QA and labels the unchanged Slice 0A APK/release as a non-playable archive, so physical/perceptual submissions remain pending |
| G1 contracts/determinism | PARTIAL | 25 contract/gate tests pass; typed game snapshots, Proto mapping/drift checks, save/restore, and replay determinism remain |
| G2 camera/pose | PARTIAL | Slice 1A synthetic transforms and analyzer ownership pass; camera binding, MediaPipe frames, physical overlay, rotation/lens switching, and one/two-person evidence remain |
| G3 gesture quality | PENDING | frozen subject/session holdout and metrics |
| G4 two-player identity | PENDING | crossing/occlusion/exit fixtures and physical evidence |
| G5 safety/rear UX | PENDING | human comprehension and visible rear-camera setup review |
| G6 generated assets | BLOCKED | anchor generation/review plus missing pivot/collision and registry coverage decision |
| G7 privacy/security | PARTIAL | content-addressed APK has no internet/network-state permission; no-op logger and packaged model initialization passed on API 33 and API 37 16 KB AVDs; cold-start logcat has no crash/remote-logger/DataTransport markers, while traffic/storage/export/physical evidence remain |
| G8 performance/thermal | PENDING | representative physical-device distributions |
| G9 release | PENDING | complete matrix, accepted risks, legal/safety sign-off, rollback proof |

Run-specific logs, reports, screenshots, and hashes belong under
`docs/evidence/runs/<timestamp-or-slice>/`. `_working/` is ignored and may not be
cited as final evidence.

The Slice 0A bundle is hash-bound by
`runs/2026-07-14-slice-0a/EVIDENCE_BUNDLE.sha256`. The repository validator passed
for all 25 entries and checks every listed byte plus the artifact filename/hash,
source commit/tree, build argv, and API 33/API 37/APK-policy/license/native
bindings. The APK and later large archives are local deliverables intentionally
excluded from Git.

The accepted Slice 1A bundle is hash-bound by
`runs/2026-07-14-slice-1a/EVIDENCE_BUNDLE.sha256`. Its 28 listed entries include
local regression, pre-review and exact-head remote CI, supply-chain hash review,
the first independent-review finding, and the clean second-review pass. Annotated
tag `slice-1a-accepted` points to final evidence commit `9ecc3ccc...` and records
manifest SHA-256 `72e20d02df127b6cd260a6dbbc213f59ca510fdf63355ada21b217bfb7162849`.
That tree was merged unchanged as `97e0c213...` and passed main CI. The bundle is
source/test evidence and does not contain or claim a new APK.
