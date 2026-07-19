# Pose model provenance

- Status: approved for development integration; physical two-person and legal release
  review pending
- Recorded: 2026-07-14

| Field | Value |
| --- | --- |
| Model | MediaPipe Pose Landmarker Lite, float16 |
| Bundled file name | `pose_landmarker_lite.task` |
| Repository path | `vision/src/main/assets/pose_landmarker_lite.task` |
| APK path | `assets/pose_landmarker_lite.task` |
| Upstream path label | `1` |
| Source URL | `https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task` |
| Upstream GCS generation observed | `1682624736756847` |
| Expected bytes | `5,777,746` |
| SHA-256 | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Supported task binding | MediaPipe Tasks Vision `PoseLandmarker` 0.10.35; IMAGE, VIDEO, and LIVE_STREAM APIs exist, while this app permits only ephemeral live camera and non-frame test fixtures |
| Product option policy | CPU baseline; measured delegate fallback; `numPoses=1` solo and provisional `numPoses=2` dual pending physical evidence |
| Runtime download | prohibited |
| License stated by official model card | Apache License 2.0 |
| Model card | `https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf` |
| Model-card revision identity | no semantic revision declared; PDF SHA-256 `e982460cedece5ad9a46aa8f13523328e9ab95d7d5b2b8c99b33f13f6a79ca86`, reviewed 2026-07-14 |
| Semantic release version | unknown; upstream publishes the path label but no semantic model release |
| Baseline integration approval | 2026-07-14, project architecture review; release approval withheld |
| Replacement record | N/A — initial baseline, not a replacement |

The exact URL, size, and hash identify the bundled artifact. The mutable `latest`
path is prohibited.

## Release limitations

The official model card treats multiple people as out of scope and says only one
person may be tracked when multiple people are visible. The Tasks API exposes a pose
count option, but that API surface is not evidence that the product's two-person
requirements are met. Dual mode remains blocked until physical-device fixtures prove
simultaneous detection, identity stability, latency, and frame-rate gates.

The model card is evidence for the published artifact license, not a complete audit of
the training-data rights chain. G9 legal review remains manual.

## Verification procedure

1. Download only the fixed URL to a temporary file.
2. Require exact size and SHA-256 before moving it into `vision/src/main/assets`.
3. Recompute the hash after the move and from the packaged APK.
4. Fail the build if the file is missing or has any other hash.
