# Third-party notices

This development notice records direct dependencies selected for the reproducible
baseline. `scripts/audit_runtime_licenses.py` generates a deterministic local runtime
inventory for `releaseRuntimeClasspath`, including cached POM SHA-256 chains,
declared licenses, and technical policy verdicts. The initial public snapshot retains
sanitized historical inventories; newly generated inventories are ignored because they
can contain machine-specific paths. They are not legal advice, complete license-text
attribution, or redistribution approval;
those remain a human G9 release condition.

| Component | Version | License evidence |
| --- | --- | --- |
| Android Gradle Plugin | 9.2.1 | Apache-2.0 |
| Kotlin / Compose compiler plugin | 2.4.0 | Apache-2.0 |
| AndroidX Compose BOM | 2026.06.01 | Apache-2.0 |
| Activity Compose | 1.13.0 | Apache-2.0 |
| Navigation Compose | 2.9.8 | Apache-2.0 |
| Lifecycle Compose | 2.11.0 | Apache-2.0 |
| DataStore | 1.2.1 | Apache-2.0 |
| CameraX | 1.6.1 | Apache-2.0; camera-core POM also declares BSD-3-Clause |
| AndroidX Graphics Path native runtime | 1.0.1 | Apache-2.0 |
| MediaPipe Tasks Vision | 0.10.35 | Apache-2.0 |
| MediaPipe Tasks Core native runtime | 0.10.35 | Apache-2.0 |
| Pose Landmarker Lite artifact | fixed path `1` and recorded hash | Apache-2.0 stated by official model card; full training-data provenance pending legal review |
| Checker Framework compatibility annotations | 2.5.3 | exact technical exception records dual POM declaration and official tagged MIT evidence; legal approval not inferred |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 |
| protobuf Gradle plugin | 0.10.0 | BSD-3-Clause |
| protoc / protobuf-javalite | 4.26.1 | BSD-3-Clause |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Robolectric | 4.16.1 | MIT |
| AndroidX Test / Espresso | 1.7.0 / 1.3.0 / 3.7.0 | Apache-2.0 |
| Python | 3.14.6 CI target | PSF License Version 2 |
| jsonschema and locked validation dependencies | lockfile versions | MIT |

The exact-coordinate technical exception for
`org.checkerframework:checker-compat-qual:2.5.3` is recorded in
`config/runtime-license-policy.json`; it is bound to the observed POM declarations,
JAR/POM hashes, official tagged license evidence, reviewer scope, and date. It does
not constitute legal approval. Generate the complete packaged native mapping with
the repository validation scripts. Before release, reconcile complete NOTICE/license
texts, then record an authorized legal reviewer and date. Absence from the direct
table is not a redistribution approval.
