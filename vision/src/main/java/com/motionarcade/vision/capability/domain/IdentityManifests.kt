package com.motionarcade.vision.capability.domain

import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.LensFacing
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

private fun <T> immutableListCopy(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

object CapabilityIdentityContract {
    const val POLICY_REVISION: String = "capability-v15"
    const val WORKLOAD_REVISION: String = "probe-workload-v3"
    const val INPUT_ADAPTER_REVISION: String = "rgba-bytebuffer-v3"
    const val STATE_MACHINE_REVISION: String = "serial-probe-v5"
    const val JOURNAL_REVISION: String = "runtime-journal-v5"
    const val PROFILE_SCHEMA_REVISION: String = "capability-mode-store-payload-v4"
    const val PREVIEW_REVISION: String = "preview-v2"
    const val MODEL_LOGICAL_PATH: String = "assets/pose_landmarker_lite.task"
    const val MODEL_SHA256_HEX: String =
        "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
    const val THREADING: String = "PINNED_LIBRARY_DEFAULT"
    const val WORKLOAD_MANIFEST_MAX_BYTES: Int = 1_048_576

    val requiredBuildFileNames: List<String> =
        immutableListCopy(
            listOf(
                "settings.gradle.kts",
                "build.gradle.kts",
                "gradle.properties",
                "gradle/wrapper/gradle-wrapper.properties",
                "gradle/libs.versions.toml",
                "gradle/verification-metadata.xml",
                "app/build.gradle.kts",
                "app/proguard-rules.pro",
                "vision/build.gradle.kts",
                "vision/consumer-rules.pro",
                "game-core/build.gradle.kts",
                "game-core/consumer-rules.pro",
                "games/build.gradle.kts",
                "games/consumer-rules.pro",
                "docs/adr/ADR-011-measured-capability-probe-policy.md",
                "docs/contracts/capability-store-v4.md",
                "docs/contracts/native-close-fence-proof-v1.md",
                "docs/contracts/recovery-journal-v5.md",
                "docs/execution/slice-1b-contract.md",
                "vision/src/main/assets/native_close_fence_registry_v1.bin",
            ),
        )

    val requiredLockFileNames: List<String> =
        immutableListCopy(
            listOf(
                "settings-gradle.lockfile",
                "app/gradle.lockfile",
                "vision/gradle.lockfile",
                "game-core/gradle.lockfile",
                "games/gradle.lockfile",
            ),
        )

    val nativeClosePolicyFileNames: List<String> =
        immutableListCopy(
            listOf(
                "docs/adr/ADR-011-measured-capability-probe-policy.md",
                "docs/contracts/capability-store-v4.md",
                "docs/contracts/native-close-fence-proof-v1.md",
                "docs/contracts/recovery-journal-v5.md",
                "docs/execution/slice-1b-contract.md",
            ),
        )
}

data class RuntimeArtifactEntryV1(
    val logicalName: String,
    val apkLength: ULong,
    val apkSha256: Sha256Digest,
)

data class NamedFileDigest(
    val logicalName: String,
    val fileLength: ULong,
    val fileSha256: Sha256Digest,
)

class PoseOptionsV2(
    val runningMode: ProbeRunningMode,
    val minPoseDetectionConfidenceBits: UInt,
    val minPosePresenceConfidenceBits: UInt,
    val minTrackingConfidenceBits: UInt,
    val outputSegmentationMasks: Boolean,
    val soloNumPoses: UInt,
    val dualNumPoses: UInt,
    candidateDelegates: List<ProbeDelegate>,
    val delegateOptionsOverride: Boolean,
    val threading: String,
) {
    val candidateDelegates: List<ProbeDelegate> = immutableListCopy(candidateDelegates)

    fun copy(
        runningMode: ProbeRunningMode = this.runningMode,
        minPoseDetectionConfidenceBits: UInt = this.minPoseDetectionConfidenceBits,
        minPosePresenceConfidenceBits: UInt = this.minPosePresenceConfidenceBits,
        minTrackingConfidenceBits: UInt = this.minTrackingConfidenceBits,
        outputSegmentationMasks: Boolean = this.outputSegmentationMasks,
        soloNumPoses: UInt = this.soloNumPoses,
        dualNumPoses: UInt = this.dualNumPoses,
        candidateDelegates: List<ProbeDelegate> = this.candidateDelegates,
        delegateOptionsOverride: Boolean = this.delegateOptionsOverride,
        threading: String = this.threading,
    ): PoseOptionsV2 = PoseOptionsV2(
        runningMode = runningMode,
        minPoseDetectionConfidenceBits = minPoseDetectionConfidenceBits,
        minPosePresenceConfidenceBits = minPosePresenceConfidenceBits,
        minTrackingConfidenceBits = minTrackingConfidenceBits,
        outputSegmentationMasks = outputSegmentationMasks,
        soloNumPoses = soloNumPoses,
        dualNumPoses = dualNumPoses,
        candidateDelegates = candidateDelegates,
        delegateOptionsOverride = delegateOptionsOverride,
        threading = threading,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PoseOptionsV2 &&
            runningMode == other.runningMode &&
            minPoseDetectionConfidenceBits == other.minPoseDetectionConfidenceBits &&
            minPosePresenceConfidenceBits == other.minPosePresenceConfidenceBits &&
            minTrackingConfidenceBits == other.minTrackingConfidenceBits &&
            outputSegmentationMasks == other.outputSegmentationMasks &&
            soloNumPoses == other.soloNumPoses &&
            dualNumPoses == other.dualNumPoses &&
            candidateDelegates == other.candidateDelegates &&
            delegateOptionsOverride == other.delegateOptionsOverride &&
            threading == other.threading

    override fun hashCode(): Int {
        var result = runningMode.hashCode()
        result = 31 * result + minPoseDetectionConfidenceBits.hashCode()
        result = 31 * result + minPosePresenceConfidenceBits.hashCode()
        result = 31 * result + minTrackingConfidenceBits.hashCode()
        result = 31 * result + outputSegmentationMasks.hashCode()
        result = 31 * result + soloNumPoses.hashCode()
        result = 31 * result + dualNumPoses.hashCode()
        result = 31 * result + candidateDelegates.hashCode()
        result = 31 * result + delegateOptionsOverride.hashCode()
        return 31 * result + threading.hashCode()
    }

    override fun toString(): String =
        "PoseOptionsV2(runningMode=$runningMode,candidateDelegates=$candidateDelegates)"

    companion object {
        fun exact(): PoseOptionsV2 =
            PoseOptionsV2(
                runningMode = ProbeRunningMode.LIVE_STREAM,
                minPoseDetectionConfidenceBits = 0x3f000000u,
                minPosePresenceConfidenceBits = 0x3f000000u,
                minTrackingConfidenceBits = 0x3f000000u,
                outputSegmentationMasks = false,
                soloNumPoses = 1u,
                dualNumPoses = 2u,
                candidateDelegates = listOf(ProbeDelegate.CPU, ProbeDelegate.GPU),
                delegateOptionsOverride = false,
                threading = CapabilityIdentityContract.THREADING,
            )
    }
}

data class NativeCloseBuildVariantV1(
    val buildType: String,
    val minified: Boolean,
)

data class NativeCloseProofBasisV1(
    val schemaRevision: String,
    val nativeClosePolicySetSha256: Sha256Digest,
    val dependencyArtifactsValueSha256: Sha256Digest,
    val buildVariant: NativeCloseBuildVariantV1,
    val nativeCloseCodeImageSha256: Sha256Digest,
)

class WorkloadBuildManifestV3(
    val policyRevision: String,
    val workloadRevision: String,
    val inputAdapterRevision: String,
    val stateMachineRevision: String,
    val journalRevision: String,
    val previewRevision: String,
    val appVersionCode: ULong,
    val buildType: String,
    val minified: Boolean,
    val poseOptions: PoseOptionsV2,
    val modelFile: NamedFileDigest,
    dependencyArtifacts: List<NamedFileDigest>,
    buildFiles: List<NamedFileDigest>,
    lockFiles: List<NamedFileDigest>,
    val nativeCloseProofBasis: NativeCloseProofBasisV1,
) {
    val dependencyArtifacts: List<NamedFileDigest> = immutableListCopy(dependencyArtifacts)
    val buildFiles: List<NamedFileDigest> = immutableListCopy(buildFiles)
    val lockFiles: List<NamedFileDigest> = immutableListCopy(lockFiles)

    fun copy(
        policyRevision: String = this.policyRevision,
        workloadRevision: String = this.workloadRevision,
        inputAdapterRevision: String = this.inputAdapterRevision,
        stateMachineRevision: String = this.stateMachineRevision,
        journalRevision: String = this.journalRevision,
        previewRevision: String = this.previewRevision,
        appVersionCode: ULong = this.appVersionCode,
        buildType: String = this.buildType,
        minified: Boolean = this.minified,
        poseOptions: PoseOptionsV2 = this.poseOptions,
        modelFile: NamedFileDigest = this.modelFile,
        dependencyArtifacts: List<NamedFileDigest> = this.dependencyArtifacts,
        buildFiles: List<NamedFileDigest> = this.buildFiles,
        lockFiles: List<NamedFileDigest> = this.lockFiles,
        nativeCloseProofBasis: NativeCloseProofBasisV1 = this.nativeCloseProofBasis,
    ): WorkloadBuildManifestV3 = WorkloadBuildManifestV3(
        policyRevision = policyRevision,
        workloadRevision = workloadRevision,
        inputAdapterRevision = inputAdapterRevision,
        stateMachineRevision = stateMachineRevision,
        journalRevision = journalRevision,
        previewRevision = previewRevision,
        appVersionCode = appVersionCode,
        buildType = buildType,
        minified = minified,
        poseOptions = poseOptions,
        modelFile = modelFile,
        dependencyArtifacts = dependencyArtifacts,
        buildFiles = buildFiles,
        lockFiles = lockFiles,
        nativeCloseProofBasis = nativeCloseProofBasis,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkloadBuildManifestV3 &&
            policyRevision == other.policyRevision &&
            workloadRevision == other.workloadRevision &&
            inputAdapterRevision == other.inputAdapterRevision &&
            stateMachineRevision == other.stateMachineRevision &&
            journalRevision == other.journalRevision &&
            previewRevision == other.previewRevision &&
            appVersionCode == other.appVersionCode &&
            buildType == other.buildType &&
            minified == other.minified &&
            poseOptions == other.poseOptions &&
            modelFile == other.modelFile &&
            dependencyArtifacts == other.dependencyArtifacts &&
            buildFiles == other.buildFiles &&
            lockFiles == other.lockFiles &&
            nativeCloseProofBasis == other.nativeCloseProofBasis

    override fun hashCode(): Int {
        var result = policyRevision.hashCode()
        result = 31 * result + workloadRevision.hashCode()
        result = 31 * result + inputAdapterRevision.hashCode()
        result = 31 * result + stateMachineRevision.hashCode()
        result = 31 * result + journalRevision.hashCode()
        result = 31 * result + previewRevision.hashCode()
        result = 31 * result + appVersionCode.hashCode()
        result = 31 * result + buildType.hashCode()
        result = 31 * result + minified.hashCode()
        result = 31 * result + poseOptions.hashCode()
        result = 31 * result + modelFile.hashCode()
        result = 31 * result + dependencyArtifacts.hashCode()
        result = 31 * result + buildFiles.hashCode()
        result = 31 * result + lockFiles.hashCode()
        return 31 * result + nativeCloseProofBasis.hashCode()
    }

    override fun toString(): String =
        "WorkloadBuildManifestV3(buildType=$buildType,minified=$minified,appVersionCode=$appVersionCode)"
}

data class ProbeBaseScopeV2(
    val runtimeBuildId: RuntimeBuildId,
    val appVersionCode: ULong,
    val buildType: String,
    val minified: Boolean,
    val policyRevision: String,
    val workloadRevision: String,
    val inputAdapterRevision: String,
    val stateMachineRevision: String,
    val journalRevision: String,
    val profileSchemaRevision: String,
    val previewRevision: String,
    val modelSha256: Sha256Digest,
    val osApi: UInt,
    val osBuildToken: RequiredDigestToken,
    val camera2IdToken: RequiredDigestToken,
    val lensFacing: LensFacing,
    val analysisWidth: UInt,
    val analysisHeight: UInt,
    val rotationDegrees: UInt,
    val cropLeft: UInt,
    val cropTop: UInt,
    val cropRight: UInt,
    val cropBottom: UInt,
    val previewSurfaceWidth: UInt,
    val previewSurfaceHeight: UInt,
    val transformCropLeft: UInt,
    val transformCropTop: UInt,
    val transformCropRight: UInt,
    val transformCropBottom: UInt,
    val transformRotationDegrees: UInt,
    val targetRotation: UInt,
    val mode: GameMode,
    val runningMode: ProbeRunningMode,
)

object CapabilityIdentity {
    /**
     * Hashes already-read logical base/split entries. PackageManager array pairing,
     * duplicate installed paths, stream stability, and read failures stay in the later
     * Android artifact adapter; raw paths cannot enter this pure preimage.
     */
    fun runtimeArtifactId(entries: List<RuntimeArtifactEntryV1>): CapabilityDomainResult<RuntimeArtifactId> =
        compute {
            if (entries.isEmpty()) reject("$/entries", "at least base", "empty")
            val encoded = entries.mapIndexed { index, entry ->
                EncodedRuntimeEntry(
                    value = entry,
                    nameBytes = strictUtf8(entry.logicalName, "$/entries/$index/logicalName"),
                )
            }
            duplicateName(encoded.map { it.value.logicalName }, "$/entries")
            val bases = encoded.filter { it.value.logicalName == BASE_NAME }
            if (bases.size != 1) reject("$/entries", "exactly one logical base entry", bases.size.toString())
            encoded.filter { it.value.logicalName != BASE_NAME }.forEach {
                if (it.nameBytes.isEmpty()) reject("$/entries/logicalName", "nonempty split name", "empty")
            }
            val splits = encoded
                .filter { it.value.logicalName != BASE_NAME }
                .sortedWith { left, right -> CanonicalManifestCodec.compareUnsigned(left.nameBytes, right.nameBytes) }
            val ordered = listOf(bases.single()) + splits
            val parts = ArrayList<ByteArray>(3 + ordered.size * 4)
            parts += strictUtf8(RUNTIME_ARTIFACT_DOMAIN, "$/domain")
            parts += byteArrayOf(0)
            parts += CanonicalManifestCodec.uint32(ordered.size.toUInt())
            ordered.forEach { entry ->
                parts += CanonicalManifestCodec.uint32(entry.nameBytes.size.toUInt())
                parts += entry.nameBytes
                parts += CanonicalManifestCodec.uint64(entry.value.apkLength)
                parts += entry.value.apkSha256.copyBytes()
            }
            RuntimeArtifactId(CanonicalManifestCodec.sha256(concatenate(parts, Int.MAX_VALUE, "$/manifest")))
        }

    /**
     * Hashes an exact nonempty Build.FINGERPRINT without retaining or returning it.
     * Null, empty, and the exact Build.UNKNOWN value are domain-specific ABSENT.
     */
    fun osBuildToken(fingerprint: String?): CapabilityDomainResult<RequiredDigestToken> =
        RequiredDigestToken.osBuild(fingerprint)

    /**
     * Hashes an exact nonempty public Camera2 ID without retaining or returning it.
     * Null/empty is ABSENT; timestamp-source proof remains a separate runtime gate.
     */
    fun camera2IdToken(publicCameraId: String?): CapabilityDomainResult<RequiredDigestToken> =
        RequiredDigestToken.camera2Id(publicCameraId)

    fun workloadManifestBytes(value: WorkloadBuildManifestV3): CapabilityDomainResult<ByteArray> =
        compute {
            exact(value.policyRevision, CapabilityIdentityContract.POLICY_REVISION, "$/policy_revision")
            exact(value.workloadRevision, CapabilityIdentityContract.WORKLOAD_REVISION, "$/workload_revision")
            exact(value.inputAdapterRevision, CapabilityIdentityContract.INPUT_ADAPTER_REVISION, "$/input_adapter_revision")
            exact(value.stateMachineRevision, CapabilityIdentityContract.STATE_MACHINE_REVISION, "$/state_machine_revision")
            exact(value.journalRevision, CapabilityIdentityContract.JOURNAL_REVISION, "$/journal_revision")
            exact(value.previewRevision, CapabilityIdentityContract.PREVIEW_REVISION, "$/preview_revision")
            validatePoseOptions(value.poseOptions)
            exact(value.modelFile.logicalName, CapabilityIdentityContract.MODEL_LOGICAL_PATH, "$/model_file/path")
            exact(
                value.modelFile.fileSha256.toLowerHex(),
                CapabilityIdentityContract.MODEL_SHA256_HEX,
                "$/model_file/sha256",
            )
            exactNameSet(value.buildFiles, CapabilityIdentityContract.requiredBuildFileNames, "$/build_files")
            exactNameSet(value.lockFiles, CapabilityIdentityContract.requiredLockFileNames, "$/lockfiles")
            validateDependencyNames(value.dependencyArtifacts)

            val dependencyValue = encodeNamedFileList(value.dependencyArtifacts, "$/dependency_artifacts")
            val buildFilesValue = encodeNamedFileList(value.buildFiles, "$/build_files")
            val lockFilesValue = encodeNamedFileList(value.lockFiles, "$/lockfiles")
            val expectedPolicyHash = nativeClosePolicySetSha256Internal(value.buildFiles)
            val expectedDependencyHash = CanonicalManifestCodec.sha256(dependencyValue)
            val basis = value.nativeCloseProofBasis
            exact(basis.schemaRevision, NATIVE_CLOSE_PROOF_BASIS_REVISION, "$/native_close_proof_basis/schema_revision")
            if (basis.nativeClosePolicySetSha256 != expectedPolicyHash) {
                reject(
                    "$/native_close_proof_basis/native_close_policy_set_sha256",
                    expectedPolicyHash.toLowerHex(),
                    basis.nativeClosePolicySetSha256.toLowerHex(),
                )
            }
            if (basis.dependencyArtifactsValueSha256 != expectedDependencyHash) {
                reject(
                    "$/native_close_proof_basis/dependency_artifacts_value_sha256",
                    expectedDependencyHash.toLowerHex(),
                    basis.dependencyArtifactsValueSha256.toLowerHex(),
                )
            }
            exact(basis.buildVariant.buildType, value.buildType, "$/native_close_proof_basis/build_variant/build_type")
            if (basis.buildVariant.minified != value.minified) {
                reject(
                    "$/native_close_proof_basis/build_variant/minified",
                    value.minified.toString(),
                    basis.buildVariant.minified.toString(),
                )
            }

            val fields =
                listOf(
                    utf8Field("policy_revision", value.policyRevision),
                    utf8Field("workload_revision", value.workloadRevision),
                    utf8Field("input_adapter_revision", value.inputAdapterRevision),
                    utf8Field("state_machine_revision", value.stateMachineRevision),
                    utf8Field("journal_revision", value.journalRevision),
                    utf8Field("preview_revision", value.previewRevision),
                    CanonicalField("app_version_code", CanonicalManifestCodec.uint64(value.appVersionCode)),
                    utf8Field("build_type", value.buildType),
                    CanonicalField("minified", CanonicalManifestCodec.boolean(value.minified)),
                    CanonicalField("pose_options", encodePoseOptions(value.poseOptions)),
                    CanonicalField("model_file", encodeNamedFile(value.modelFile, "$/model_file")),
                    CanonicalField("dependency_artifacts", dependencyValue),
                    CanonicalField("build_files", buildFilesValue),
                    CanonicalField("lockfiles", lockFilesValue),
                    CanonicalField("native_close_proof_basis", encodeNativeCloseProofBasis(basis)),
                )
            if (fields.size != 15) reject("$/fieldCount", "15", fields.size.toString())
            CanonicalManifestCodec.encode(
                domain = WORKLOAD_DOMAIN,
                fields = fields,
                maximumBytes = CapabilityIdentityContract.WORKLOAD_MANIFEST_MAX_BYTES,
            ).orAbort()
        }

    fun workloadManifestSha256(
        value: WorkloadBuildManifestV3,
    ): CapabilityDomainResult<WorkloadBuildManifestSha256> =
        workloadManifestBytes(value).map { WorkloadBuildManifestSha256(CanonicalManifestCodec.sha256(it)) }

    fun dependencyArtifactsValueSha256(
        entries: List<NamedFileDigest>,
    ): CapabilityDomainResult<Sha256Digest> = compute {
        validateDependencyNames(entries)
        CanonicalManifestCodec.sha256(encodeNamedFileList(entries, "$/dependency_artifacts"))
    }

    fun nativeClosePolicySetSha256(
        buildFiles: List<NamedFileDigest>,
    ): CapabilityDomainResult<Sha256Digest> = compute {
        exactNameSet(buildFiles, CapabilityIdentityContract.requiredBuildFileNames, "$/build_files")
        nativeClosePolicySetSha256Internal(buildFiles)
    }

    fun runtimeBuildId(
        runtimeArtifactId: RuntimeArtifactId,
        workloadManifestSha256: WorkloadBuildManifestSha256,
    ): RuntimeBuildId {
        val bytes = CanonicalManifestCodec.encode(
            domain = RUNTIME_BUILD_DOMAIN,
            fields =
                listOf(
                    CanonicalField("runtime_artifact_id", runtimeArtifactId.digest.copyBytes()),
                    CanonicalField(
                        "workload_build_manifest_sha256",
                        workloadManifestSha256.digest.copyBytes(),
                    ),
                ),
        )
        check(bytes is CapabilityDomainResult.Valid)
        return RuntimeBuildId(CanonicalManifestCodec.sha256(bytes.value))
    }

    fun probeBaseScopeBytes(value: ProbeBaseScopeV2): CapabilityDomainResult<ByteArray> =
        compute {
            validateScope(value)
            val fields =
                listOf(
                    CanonicalField("runtime_build_id", value.runtimeBuildId.digest.copyBytes()),
                    CanonicalField("app_version_code", CanonicalManifestCodec.uint64(value.appVersionCode)),
                    utf8Field("build_type", value.buildType),
                    CanonicalField("minified", CanonicalManifestCodec.boolean(value.minified)),
                    utf8Field("policy_revision", value.policyRevision),
                    utf8Field("workload_revision", value.workloadRevision),
                    utf8Field("input_adapter_revision", value.inputAdapterRevision),
                    utf8Field("state_machine_revision", value.stateMachineRevision),
                    utf8Field("journal_revision", value.journalRevision),
                    utf8Field("profile_schema_revision", value.profileSchemaRevision),
                    utf8Field("preview_revision", value.previewRevision),
                    CanonicalField("model_sha256", value.modelSha256.copyBytes()),
                    CanonicalField("os_api", CanonicalManifestCodec.uint32(value.osApi)),
                    CanonicalField("os_build_token", encodeToken(value.osBuildToken)),
                    CanonicalField("camera2_id_token", encodeToken(value.camera2IdToken)),
                    CanonicalField("lens", byteValue(lensWire(value.lensFacing), "$/lens")),
                    CanonicalField("analysis_width", CanonicalManifestCodec.uint32(value.analysisWidth)),
                    CanonicalField("analysis_height", CanonicalManifestCodec.uint32(value.analysisHeight)),
                    CanonicalField("rotation_degrees", CanonicalManifestCodec.uint32(value.rotationDegrees)),
                    CanonicalField("crop_left", CanonicalManifestCodec.uint32(value.cropLeft)),
                    CanonicalField("crop_top", CanonicalManifestCodec.uint32(value.cropTop)),
                    CanonicalField("crop_right", CanonicalManifestCodec.uint32(value.cropRight)),
                    CanonicalField("crop_bottom", CanonicalManifestCodec.uint32(value.cropBottom)),
                    CanonicalField("preview_surface_width", CanonicalManifestCodec.uint32(value.previewSurfaceWidth)),
                    CanonicalField("preview_surface_height", CanonicalManifestCodec.uint32(value.previewSurfaceHeight)),
                    CanonicalField("transform_crop_left", CanonicalManifestCodec.uint32(value.transformCropLeft)),
                    CanonicalField("transform_crop_top", CanonicalManifestCodec.uint32(value.transformCropTop)),
                    CanonicalField("transform_crop_right", CanonicalManifestCodec.uint32(value.transformCropRight)),
                    CanonicalField("transform_crop_bottom", CanonicalManifestCodec.uint32(value.transformCropBottom)),
                    CanonicalField(
                        "transform_rotation_degrees",
                        CanonicalManifestCodec.uint32(value.transformRotationDegrees),
                    ),
                    CanonicalField("target_rotation", CanonicalManifestCodec.uint32(value.targetRotation)),
                    CanonicalField("mode", byteValue(modeWire(value.mode), "$/mode")),
                    CanonicalField("running_mode", byteValue(value.runningMode.wireValue, "$/running_mode")),
                )
            if (fields.size != 33) reject("$/fieldCount", "33", fields.size.toString())
            CanonicalManifestCodec.encode(PROBE_BASE_SCOPE_DOMAIN, fields).orAbort()
        }

    fun probeBaseScopeId(value: ProbeBaseScopeV2): CapabilityDomainResult<ProbeBaseScopeId> =
        probeBaseScopeBytes(value).map { ProbeBaseScopeId(CanonicalManifestCodec.sha256(it)) }

    fun capabilityResultId(
        probeBaseScopeId: ProbeBaseScopeId,
        selectedDelegate: ProbeDelegate,
    ): CapabilityResultId {
        val encoded = CanonicalManifestCodec.encode(
            domain = CAPABILITY_RESULT_DOMAIN,
            fields =
                listOf(
                    CanonicalField("probe_base_scope_id", probeBaseScopeId.digest.copyBytes()),
                    CanonicalField(
                        "selected_delegate",
                        byteArrayOf(selectedDelegate.wireValue.toByte()),
                    ),
                ),
        )
        check(encoded is CapabilityDomainResult.Valid)
        return CapabilityResultId(CanonicalManifestCodec.sha256(encoded.value))
    }

    private fun validatePoseOptions(value: PoseOptionsV2) {
        if (value.runningMode != ProbeRunningMode.LIVE_STREAM) {
            reject("$/pose_options/running_mode", "LIVE_STREAM", value.runningMode.name)
        }
        exact(value.minPoseDetectionConfidenceBits, 0x3f000000u, "$/pose_options/min_pose_detection_confidence")
        exact(value.minPosePresenceConfidenceBits, 0x3f000000u, "$/pose_options/min_pose_presence_confidence")
        exact(value.minTrackingConfidenceBits, 0x3f000000u, "$/pose_options/min_tracking_confidence")
        if (value.outputSegmentationMasks) {
            reject("$/pose_options/output_segmentation_masks", "false", "true")
        }
        exact(value.soloNumPoses, 1u, "$/pose_options/solo_num_poses")
        exact(value.dualNumPoses, 2u, "$/pose_options/dual_num_poses")
        if (value.candidateDelegates != listOf(ProbeDelegate.CPU, ProbeDelegate.GPU)) {
            reject(
                "$/pose_options/candidate_delegates",
                "[CPU, GPU]",
                value.candidateDelegates.joinToString(prefix = "[", postfix = "]") { it.name },
            )
        }
        if (value.delegateOptionsOverride) {
            reject("$/pose_options/delegate_options_override", "false", "true")
        }
        exact(value.threading, CapabilityIdentityContract.THREADING, "$/pose_options/threading")
    }

    private fun encodePoseOptions(value: PoseOptionsV2): ByteArray =
        CanonicalManifestCodec.encode(
            domain = POSE_OPTIONS_DOMAIN,
            fields =
                listOf(
                    CanonicalField("running_mode", byteValue(value.runningMode.wireValue, "$/running_mode")),
                    CanonicalField(
                        "min_pose_detection_confidence",
                        CanonicalManifestCodec.uint32(value.minPoseDetectionConfidenceBits),
                    ),
                    CanonicalField(
                        "min_pose_presence_confidence",
                        CanonicalManifestCodec.uint32(value.minPosePresenceConfidenceBits),
                    ),
                    CanonicalField(
                        "min_tracking_confidence",
                        CanonicalManifestCodec.uint32(value.minTrackingConfidenceBits),
                    ),
                    CanonicalField(
                        "output_segmentation_masks",
                        CanonicalManifestCodec.boolean(value.outputSegmentationMasks),
                    ),
                    CanonicalField("solo_num_poses", CanonicalManifestCodec.uint32(value.soloNumPoses)),
                    CanonicalField("dual_num_poses", CanonicalManifestCodec.uint32(value.dualNumPoses)),
                    CanonicalField(
                        "candidate_delegates",
                        byteArrayOf(2, ProbeDelegate.CPU.wireValue.toByte(), ProbeDelegate.GPU.wireValue.toByte()),
                    ),
                    CanonicalField(
                        "delegate_options_override",
                        CanonicalManifestCodec.boolean(value.delegateOptionsOverride),
                    ),
                    utf8Field("threading", value.threading),
                ),
        ).orAbort()

    private fun encodeNativeCloseProofBasis(value: NativeCloseProofBasisV1): ByteArray {
        val buildVariant = CanonicalManifestCodec.encode(
            domain = NATIVE_CLOSE_BUILD_VARIANT_DOMAIN,
            fields =
                listOf(
                    utf8Field("build_type", value.buildVariant.buildType),
                    CanonicalField("minified", CanonicalManifestCodec.boolean(value.buildVariant.minified)),
                ),
        ).orAbort()
        return CanonicalManifestCodec.encode(
            domain = NATIVE_CLOSE_PROOF_BASIS_DOMAIN,
            fields =
                listOf(
                    utf8Field("schema_revision", value.schemaRevision),
                    CanonicalField(
                        "native_close_policy_set_sha256",
                        value.nativeClosePolicySetSha256.copyBytes(),
                    ),
                    CanonicalField(
                        "dependency_artifacts_value_sha256",
                        value.dependencyArtifactsValueSha256.copyBytes(),
                    ),
                    CanonicalField("build_variant", buildVariant),
                    CanonicalField(
                        "native_close_code_image_sha256",
                        value.nativeCloseCodeImageSha256.copyBytes(),
                    ),
                ),
        ).orAbort()
    }

    private fun nativeClosePolicySetSha256Internal(buildFiles: List<NamedFileDigest>): Sha256Digest {
        val entries = CapabilityIdentityContract.nativeClosePolicyFileNames.map { name ->
            val file = buildFiles.singleOrNull { it.logicalName == name }
                ?: reject("$/build_files", "required policy file $name", "missing or duplicate")
            if (file.fileLength == 0uL) reject("$/build_files/$name/fileLength", "> 0", "0")
            CanonicalManifestCodec.encode(
                domain = NATIVE_CLOSE_POLICY_FILE_DOMAIN,
                fields =
                    listOf(
                        utf8Field("logical_path", file.logicalName),
                        CanonicalField("file_length", CanonicalManifestCodec.uint64(file.fileLength)),
                        CanonicalField("file_sha256", file.fileSha256.copyBytes()),
                    ),
            ).orAbort()
        }
        val policyFilesValue = encodeLengthPrefixedList(entries, "$/policy_files")
        val policySet = CanonicalManifestCodec.encode(
            domain = NATIVE_CLOSE_POLICY_SET_DOMAIN,
            fields =
                listOf(
                    utf8Field("schema_revision", NATIVE_CLOSE_POLICY_SET_REVISION),
                    CanonicalField("policy_files", policyFilesValue),
                ),
        ).orAbort()
        return CanonicalManifestCodec.sha256(policySet)
    }

    private fun validateScope(value: ProbeBaseScopeV2) {
        exact(value.policyRevision, CapabilityIdentityContract.POLICY_REVISION, "$/policy_revision")
        exact(value.workloadRevision, CapabilityIdentityContract.WORKLOAD_REVISION, "$/workload_revision")
        exact(value.inputAdapterRevision, CapabilityIdentityContract.INPUT_ADAPTER_REVISION, "$/input_adapter_revision")
        exact(value.stateMachineRevision, CapabilityIdentityContract.STATE_MACHINE_REVISION, "$/state_machine_revision")
        exact(value.journalRevision, CapabilityIdentityContract.JOURNAL_REVISION, "$/journal_revision")
        exact(value.profileSchemaRevision, CapabilityIdentityContract.PROFILE_SCHEMA_REVISION, "$/profile_schema_revision")
        exact(value.previewRevision, CapabilityIdentityContract.PREVIEW_REVISION, "$/preview_revision")
        exact(value.modelSha256.toLowerHex(), CapabilityIdentityContract.MODEL_SHA256_HEX, "$/model_sha256")
        tokenProvenance(
            token = value.osBuildToken,
            expected = RequiredDigestProvenance.OS_BUILD,
            path = "$/os_build_token",
        )
        tokenProvenance(
            token = value.camera2IdToken,
            expected = RequiredDigestProvenance.CAMERA2_ID,
            path = "$/camera2_id_token",
        )
        if (value.osApi !in 26u..37u) reject("$/os_api", "26..37", value.osApi.toString())
        if (value.analysisWidth == 0u || value.analysisHeight == 0u) {
            reject("$/analysis_size", "positive dimensions", "${value.analysisWidth}x${value.analysisHeight}")
        }
        crop(
            left = value.cropLeft,
            top = value.cropTop,
            right = value.cropRight,
            bottom = value.cropBottom,
            width = value.analysisWidth,
            height = value.analysisHeight,
            path = "$/crop",
        )
        if (value.previewSurfaceWidth == 0u || value.previewSurfaceHeight == 0u) {
            reject(
                "$/preview_surface_size",
                "positive dimensions",
                "${value.previewSurfaceWidth}x${value.previewSurfaceHeight}",
            )
        }
        crop(
            left = value.transformCropLeft,
            top = value.transformCropTop,
            right = value.transformCropRight,
            bottom = value.transformCropBottom,
            width = value.analysisWidth,
            height = value.analysisHeight,
            path = "$/transform_crop",
        )
        rotation(value.rotationDegrees, "$/rotation_degrees")
        rotation(value.transformRotationDegrees, "$/transform_rotation_degrees")
        if (value.targetRotation !in 0u..3u) {
            reject("$/target_rotation", "public Surface rotation value 0..3", value.targetRotation.toString())
        }
    }

    private fun crop(
        left: UInt,
        top: UInt,
        right: UInt,
        bottom: UInt,
        width: UInt,
        height: UInt,
        path: String,
    ) {
        if (!(left < right && right <= width && top < bottom && bottom <= height)) {
            reject(path, "0 <= left < right <= width and 0 <= top < bottom <= height", "$left,$top,$right,$bottom/$width,$height")
        }
    }

    private fun rotation(value: UInt, path: String) {
        if (value !in setOf(0u, 90u, 180u, 270u)) {
            reject(path, "one of 0,90,180,270", value.toString())
        }
    }

    private fun encodeToken(value: RequiredDigestToken): ByteArray =
        value.digestOrNull?.let { digest -> byteArrayOf(1) + digest.copyBytes() }
            ?: byteArrayOf(0)

    private fun tokenProvenance(
        token: RequiredDigestToken,
        expected: RequiredDigestProvenance,
        path: String,
    ) {
        if (token.provenance != expected) {
            reject(path, expected.name, token.provenance.name)
        }
    }

    private fun lensWire(value: LensFacing): Int =
        when (value) {
            LensFacing.FRONT -> 0
            LensFacing.BACK -> 1
        }

    private fun modeWire(value: GameMode): Int =
        when (value) {
            GameMode.SOLO -> 0
            GameMode.DUAL -> 1
        }

    private fun validateDependencyNames(entries: List<NamedFileDigest>) {
        duplicateName(entries.map(NamedFileDigest::logicalName), "$/dependency_artifacts")
        entries.forEachIndexed { index, entry ->
            strictUtf8(entry.logicalName, "$/dependency_artifacts/$index/logicalName")
            if (!DEPENDENCY_COORDINATE.matches(entry.logicalName)) {
                reject(
                    "$/dependency_artifacts/$index/logicalName",
                    "maven:group:name:version:classifier-or-empty:aar-or-jar",
                    entry.logicalName,
                )
            }
        }
    }

    private fun exactNameSet(
        entries: List<NamedFileDigest>,
        required: List<String>,
        path: String,
    ) {
        duplicateName(entries.map(NamedFileDigest::logicalName), path)
        entries.forEachIndexed { index, entry -> strictUtf8(entry.logicalName, "$path/$index/logicalName") }
        val actual = entries.map(NamedFileDigest::logicalName).toSet()
        if (actual != required.toSet() || entries.size != required.size) {
            reject(path, "exact required logical-name set", actual.sorted().joinToString())
        }
    }

    private fun encodeNamedFile(value: NamedFileDigest, path: String): ByteArray {
        val name = strictUtf8(value.logicalName, "$path/logicalName")
        return concatenate(
            listOf(
                CanonicalManifestCodec.uint32(name.size.toUInt()),
                name,
                CanonicalManifestCodec.uint64(value.fileLength),
                value.fileSha256.copyBytes(),
            ),
            CapabilityIdentityContract.WORKLOAD_MANIFEST_MAX_BYTES,
            path,
        )
    }

    private fun encodeNamedFileList(entries: List<NamedFileDigest>, path: String): ByteArray {
        duplicateName(entries.map(NamedFileDigest::logicalName), path)
        val encoded = entries.mapIndexed { index, entry ->
            EncodedNamedFile(entry, strictUtf8(entry.logicalName, "$path/$index/logicalName"))
        }.sortedWith { left, right -> CanonicalManifestCodec.compareUnsigned(left.nameBytes, right.nameBytes) }
        val parts = ArrayList<ByteArray>(1 + encoded.size * 4)
        parts += CanonicalManifestCodec.uint32(encoded.size.toUInt())
        encoded.forEach { entry ->
            parts += CanonicalManifestCodec.uint32(entry.nameBytes.size.toUInt())
            parts += entry.nameBytes
            parts += CanonicalManifestCodec.uint64(entry.value.fileLength)
            parts += entry.value.fileSha256.copyBytes()
        }
        return concatenate(parts, CapabilityIdentityContract.WORKLOAD_MANIFEST_MAX_BYTES, path)
    }

    private fun encodeLengthPrefixedList(entries: List<ByteArray>, path: String): ByteArray {
        val parts = ArrayList<ByteArray>(1 + entries.size * 2)
        parts += CanonicalManifestCodec.uint32(entries.size.toUInt())
        entries.forEach { entry ->
            parts += CanonicalManifestCodec.uint32(entry.size.toUInt())
            parts += entry
        }
        return concatenate(parts, CapabilityIdentityContract.WORKLOAD_MANIFEST_MAX_BYTES, path)
    }

    private fun concatenate(parts: List<ByteArray>, maximumBytes: Int, path: String): ByteArray {
        var size = 0L
        try {
            parts.forEach { size = Math.addExact(size, it.size.toLong()) }
        } catch (_: ArithmeticException) {
            reject(path, "checked encoded length", "overflow")
        }
        if (size > maximumBytes.toLong() || size > Int.MAX_VALUE.toLong()) {
            reject(path, "encoded size <= $maximumBytes", size.toString())
        }
        val output = ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN)
        parts.forEach(output::put)
        check(!output.hasRemaining())
        return output.array()
    }

    private fun utf8Field(name: String, value: String): CanonicalField =
        CanonicalField(name, strictUtf8(value, "$/fields/$name"))

    private fun byteValue(value: Int, path: String): ByteArray =
        CanonicalManifestCodec.oneByte(value, path).orAbort()

    private fun strictUtf8(value: String, path: String): ByteArray =
        CanonicalManifestCodec.strictUtf8(value, path).orAbort()

    private fun duplicateName(values: List<String>, path: String) {
        values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            throw DomainAbort(listOf(CapabilityDomainViolation.DuplicateValue(path, it.key)))
        }
    }

    private fun exact(actual: String, expected: String, path: String) {
        if (actual != expected) reject(path, expected, actual)
    }

    private fun exact(actual: UInt, expected: UInt, path: String) {
        if (actual != expected) reject(path, expected.toString(), actual.toString())
    }

    private fun reject(path: String, expected: String, actual: String): Nothing =
        throw DomainAbort(listOf(CapabilityDomainViolation.InvalidValue(path, expected, actual)))

    private fun <T> CapabilityDomainResult<T>.orAbort(): T =
        when (this) {
            is CapabilityDomainResult.Valid -> value
            is CapabilityDomainResult.Invalid -> throw DomainAbort(violations)
        }

    private inline fun <T> compute(block: () -> T): CapabilityDomainResult<T> =
        try {
            CapabilityDomainResult.Valid(block())
        } catch (failure: DomainAbort) {
            CapabilityDomainResult.Invalid(failure.violations)
        }

    private data class EncodedRuntimeEntry(
        val value: RuntimeArtifactEntryV1,
        val nameBytes: ByteArray,
    )

    private data class EncodedNamedFile(
        val value: NamedFileDigest,
        val nameBytes: ByteArray,
    )

    private class DomainAbort(
        val violations: List<CapabilityDomainViolation>,
    ) : RuntimeException(null, null, false, false)

    private const val BASE_NAME = "base"
    private const val RUNTIME_ARTIFACT_DOMAIN = "runtime-artifact-manifest-v1"
    private const val WORKLOAD_DOMAIN = "workload-build-manifest-v3"
    private const val POSE_OPTIONS_DOMAIN = "pose-options-v2"
    private const val RUNTIME_BUILD_DOMAIN = "runtime-build-v2"
    private const val PROBE_BASE_SCOPE_DOMAIN = "probe-base-scope-v2"
    private const val CAPABILITY_RESULT_DOMAIN = "capability-result-v2"
    private const val NATIVE_CLOSE_POLICY_SET_DOMAIN = "native-close-policy-set-v1"
    private const val NATIVE_CLOSE_POLICY_SET_REVISION = "native-close-policy-set-v1"
    private const val NATIVE_CLOSE_POLICY_FILE_DOMAIN = "native-close-policy-file-v1"
    private const val NATIVE_CLOSE_PROOF_BASIS_DOMAIN = "native-close-proof-basis-v1"
    private const val NATIVE_CLOSE_PROOF_BASIS_REVISION = "native-close-proof-basis-v1"
    private const val NATIVE_CLOSE_BUILD_VARIANT_DOMAIN = "native-close-build-variant-v1"
    private val DEPENDENCY_COORDINATE = Regex("^maven:[^:]+:[^:]+:[^:]+:[^:]*(?::aar|:jar)$")
}
