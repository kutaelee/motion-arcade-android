# APK native runtime inventory

Status: `VERIFIED` for the recorded debug APK. This is byte ownership and publisher
metadata evidence, not legal approval.

```text
apk=artifacts/slice-0a/motion-arcade-slice-0a-58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f.apk
apkSha256=58D350E136C50C2B1A407FD887E66A4939CEB8AC6348545A828A69D6C76F665F
apkBytes=68351511
zipEntries=545
nativeEntries=16
nativeBytes=46134956
abis=arm64-v8a,armeabi-v7a,x86,x86_64
uniqueNativeFilenames=4
duplicatePaths=0
unmatchedOwners=0
ambiguousOwners=0
```

Every APK entry below was stream-hashed and matched byte-for-byte to exactly one
resolved Maven AAR artifact and coordinate. The deterministic machine inventory is
`native-runtime-inventory.json`, SHA-256
`C14D43AF8CCBCCF86C2AB4FD32F4692048C000515FC444DCFB3AB309F7348A86`.
Regeneration with `--expected-inventory` produced byte-identical JSON.

| ABI | Filename | Bytes | SHA-256 | Exact Maven owner |
| --- | --- | ---: | --- | --- |
| arm64-v8a | `libandroidx.graphics.path.so` | 10,096 | `41E9A793C43A0F4FDDB19E33F346BACE464F30F888BA7B9EAF96294EA115BFB6` | `androidx.graphics:graphics-path:1.0.1` |
| arm64-v8a | `libimage_processing_util_jni.so` | 32,528 | `0292B063FAFCCF734472CBB59588E756C2DBCA907C72021BA6FBF126385508A2` | `androidx.camera:camera-core:1.6.1` |
| arm64-v8a | `libmediapipe_tasks_jni.so` | 10,533,656 | `C0497AFFBE5C60DDBB61B969403D053AE61D209DC6C7063F38427640C62B8F90` | `com.google.mediapipe:tasks-core:0.10.35` |
| arm64-v8a | `libsurface_util_jni.so` | 4,896 | `A5C9D1928EA92EC7A94DFF8CF666E7739CD734198B9F0AE1D4E28EA3C86516BA` | `androidx.camera:camera-core:1.6.1` |
| armeabi-v7a | `libandroidx.graphics.path.so` | 7,252 | `41399EBA6FC2A60F6F14642375C1824F3CF25EB8FEC7397D753730A3CEDA3E2B` | `androidx.graphics:graphics-path:1.0.1` |
| armeabi-v7a | `libimage_processing_util_jni.so` | 24,448 | `05973C0CF8E8DD281C8EC505C41814B78B740461056925BB41120594D7C35DCD` | `androidx.camera:camera-core:1.6.1` |
| armeabi-v7a | `libmediapipe_tasks_jni.so` | 7,405,024 | `13156861846C2AB5924B7C6331BF541DBC9D0918C2C480EDE118ACA47BF75E2F` | `com.google.mediapipe:tasks-core:0.10.35` |
| armeabi-v7a | `libsurface_util_jni.so` | 3,460 | `78EB0B10E91B819BE97CB7DAA8C97FB757FA6D07CB80CCB7E92C113B45BDA9BE` | `androidx.camera:camera-core:1.6.1` |
| x86 | `libandroidx.graphics.path.so` | 9,284 | `EB0570B41FD3BFF25D8204A967C03BD7550719E768B791F680CC40CBE35F29AF` | `androidx.graphics:graphics-path:1.0.1` |
| x86 | `libimage_processing_util_jni.so` | 41,276 | `B52F93B57D408B2306071F9DE1A4B0B5684C1B86563E34A8711D80A1E6C03F94` | `androidx.camera:camera-core:1.6.1` |
| x86 | `libmediapipe_tasks_jni.so` | 14,961,392 | `DB2D3D3217E5E16A91014D337BBB922FCE7E0A66C0EB1B53B097FEE1EEC91E38` | `com.google.mediapipe:tasks-core:0.10.35` |
| x86 | `libsurface_util_jni.so` | 3,812 | `3048A535D701579C87C4AD91283CD1246D2C85399A164EE4720642037EA31727` | `androidx.camera:camera-core:1.6.1` |
| x86_64 | `libandroidx.graphics.path.so` | 10,760 | `4E56C996F13670E70082658DE7880C4020EABF4F25E43387F88ED78A713FC9F0` | `androidx.graphics:graphics-path:1.0.1` |
| x86_64 | `libimage_processing_util_jni.so` | 49,416 | `112703727717651E69E91F3EA2C3C2311737EE5809A909C9D342A811B6C7C069` | `androidx.camera:camera-core:1.6.1` |
| x86_64 | `libmediapipe_tasks_jni.so` | 13,032,744 | `67FE7C45CE672CC65D121996AD3FE2F87C82AB5419FDDFCB7210E4771D011AE0` | `com.google.mediapipe:tasks-core:0.10.35` |
| x86_64 | `libsurface_util_jni.so` | 4,912 | `E5311942B4FCE0F7F2505D41150CF9921E558A6368929CB73127450F74DD4FE4` | `androidx.camera:camera-core:1.6.1` |

## Owner and license evidence

| Owner | Entries / bytes | Resolved artifact evidence | Publisher license evidence |
| --- | ---: | --- | --- |
| `androidx.graphics:graphics-path:1.0.1` | 4 / 37,392 | AAR SHA-256 `8CA4032B6D79B351F0B59AD4B580EDDBB9423E1652F7C958830687F1EEE2EC03`; all four `jni/**` members match | POM declares AOSP/AndroidX and Apache-2.0 |
| `androidx.camera:camera-core:1.6.1` | 8 / 164,748 | AAR SHA-256 `A36F1C323851B51215BA476640E1EB4153882B0C368C0376E9FB905E689DF84A`; all eight `jni/**` members match | POM declares Apache-2.0 and BSD-3-Clause and says the module includes libyuv under BSD-3-Clause |
| `com.google.mediapipe:tasks-core:0.10.35` | 4 / 45,932,816 | AAR SHA-256 `4EDF2F33C840D682C751B3CA951B1AE0465373734776D7FB37DCEF936DE28AD0`; all four `jni/**` members match | POM declares the MediaPipe Authors and Apache-2.0 |

`tasks-vision:0.10.35` is the direct vision dependency but contains no native
entries; its POM depends on `tasks-core:0.10.35`, the exact owner above. Camera
Core's two native filenames retain the artifact-level Apache/BSD set. Available
publisher metadata does not justify assigning BSD-covered libyuv code to one
specific filename.

## Preservation and invalidation rule

The tested APK was copied from the mutable Gradle output into the content-addressed
path above, verified byte-identical, and marked read-only. It is bound to source
commit `dadd77d15ee4595fd2ef8d7540e5ecad08cd64cd` by
`artifact-provenance.json`. The validator inspected 159 locked debug runtime
coordinates and 88 verification-metadata-backed AAR artifacts. It rejected no entry:
16 matched, 0 unmatched, 0 ambiguous, and 0 duplicate.

Any APK SHA-256 change requires a new content-addressed filename and invalidates this
inventory, instrumentation, signature, policy, screenshot, and provenance binding.
The technical mapping is not legal advice; complete NOTICE/license-text review
remains a human G9 gate.
