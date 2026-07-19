package com.motionarcade.vision.capability.domain

import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.LensFacing
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityManifestsTest {
    @Test
    fun runtimeArtifactCanonicalizesBaseThenUnsignedUtf8Splits() {
        val base = RuntimeArtifactEntryV1("base", 10uL, digest(1))
        val alpha = RuntimeArtifactEntryV1("alpha", 11uL, digest(2))
        val zeta = RuntimeArtifactEntryV1("zeta", 12uL, digest(3))

        val first = valid(CapabilityIdentity.runtimeArtifactId(listOf(zeta, base, alpha)))
        val reordered = valid(CapabilityIdentity.runtimeArtifactId(listOf(alpha, zeta, base)))

        assertEquals(first, reordered)
        assertEquals(RUNTIME_ARTIFACT_SHA256, first.digest.toLowerHex())
    }

    @Test
    fun runtimeArtifactRejectsMissingBaseDuplicateAndBaseNamedSplit() {
        assertInvalid(CapabilityIdentity.runtimeArtifactId(emptyList()))
        assertInvalid(
            CapabilityIdentity.runtimeArtifactId(
                listOf(
                    RuntimeArtifactEntryV1("base", 1uL, digest(1)),
                    RuntimeArtifactEntryV1("base", 2uL, digest(2)),
                ),
            ),
        )
        assertInvalid(
            CapabilityIdentity.runtimeArtifactId(
                listOf(
                    RuntimeArtifactEntryV1("base", 1uL, digest(1)),
                    RuntimeArtifactEntryV1("same", 2uL, digest(2)),
                    RuntimeArtifactEntryV1("same", 3uL, digest(3)),
                ),
            ),
        )
    }

    @Test
    fun oneInstalledByteDigestMutationChangesRuntimeArtifactId() {
        val first = valid(
            CapabilityIdentity.runtimeArtifactId(
                listOf(RuntimeArtifactEntryV1("base", 10uL, digest(1))),
            ),
        )
        val mutated = valid(
            CapabilityIdentity.runtimeArtifactId(
                listOf(RuntimeArtifactEntryV1("base", 10uL, digest(2))),
            ),
        )
        assertNotEquals(first, mutated)
    }

    @Test
    fun runtimeBuildAndCapabilityResultGoldenHashesUseIndependentLiteralInputs() {
        val artifact = valid(
            CapabilityIdentity.runtimeArtifactId(
                listOf(
                    RuntimeArtifactEntryV1("zeta", 12uL, digest(3)),
                    RuntimeArtifactEntryV1("base", 10uL, digest(1)),
                    RuntimeArtifactEntryV1("alpha", 11uL, digest(2)),
                ),
            ),
        )
        val workloadHash = valid(CapabilityIdentity.workloadManifestSha256(validWorkload()))

        assertEquals(RUNTIME_ARTIFACT_SHA256, artifact.digest.toLowerHex())
        assertEquals(WORKLOAD_SHA256, workloadHash.digest.toLowerHex())
        val fixedArtifactId = RuntimeArtifactId(digestFromHex(RUNTIME_ARTIFACT_SHA256))
        val fixedWorkloadHash = WorkloadBuildManifestSha256(digestFromHex(WORKLOAD_SHA256))
        assertEquals(
            RUNTIME_BUILD_SHA256,
            CapabilityIdentity.runtimeBuildId(fixedArtifactId, fixedWorkloadHash).digest.toLowerHex(),
        )

        val fixedScopeId = ProbeBaseScopeId(digestFromHex(RESULT_GOLDEN_SCOPE_ID))
        listOf(
            ProbeDelegate.NONE to CAPABILITY_RESULT_NONE_SHA256,
            ProbeDelegate.CPU to CAPABILITY_RESULT_CPU_SHA256,
            ProbeDelegate.GPU to CAPABILITY_RESULT_GPU_SHA256,
        ).forEach { (delegate, expected) ->
            assertEquals(
                expected,
                CapabilityIdentity.capabilityResultId(fixedScopeId, delegate).digest.toLowerHex(),
            )
        }
    }

    @Test
    fun workloadManifestHasExactFifteenFieldsAndCanonicalizesNamedLists() {
        val workload = validWorkload()
        val bytes = valid(CapabilityIdentity.workloadManifestBytes(workload))
        val reordered = valid(
            CapabilityIdentity.workloadManifestBytes(
                workload.copy(
                    dependencyArtifacts = workload.dependencyArtifacts.reversed(),
                    buildFiles = workload.buildFiles.reversed(),
                    lockFiles = workload.lockFiles.reversed(),
                ),
            ),
        )

        assertEquals(
            listOf(
                "policy_revision",
                "workload_revision",
                "input_adapter_revision",
                "state_machine_revision",
                "journal_revision",
                "preview_revision",
                "app_version_code",
                "build_type",
                "minified",
                "pose_options",
                "model_file",
                "dependency_artifacts",
                "build_files",
                "lockfiles",
                "native_close_proof_basis",
            ),
            fields(bytes, "workload-build-manifest-v3").map { it.first },
        )
        assertArrayEquals(bytes, reordered)
    }

    @Test
    fun workloadRejectsRevisionOptionModelAndDuplicateMutations() {
        val workload = validWorkload()
        assertInvalid(
            CapabilityIdentity.workloadManifestBytes(
                workload.copy(policyRevision = "capability-v14"),
            ),
        )
        listOf(
            workload.copy(workloadRevision = "probe-workload-v2"),
            workload.copy(inputAdapterRevision = "rgba-bytebuffer-v2"),
            workload.copy(stateMachineRevision = "serial-probe-v4"),
            workload.copy(journalRevision = "runtime-journal-v4"),
            workload.copy(previewRevision = "preview-v1"),
        ).forEach { assertInvalid(CapabilityIdentity.workloadManifestBytes(it)) }
        assertInvalid(
            CapabilityIdentity.workloadManifestBytes(
                workload.copy(
                    poseOptions = workload.poseOptions.copy(
                        minTrackingConfidenceBits = 0x3f000001u,
                    ),
                ),
            ),
        )
        assertInvalid(
            CapabilityIdentity.workloadManifestBytes(
                workload.copy(modelFile = workload.modelFile.copy(fileSha256 = digest(9))),
            ),
        )
        assertInvalid(
            CapabilityIdentity.workloadManifestBytes(
                workload.copy(buildFiles = workload.buildFiles + workload.buildFiles.first()),
            ),
        )
    }

    @Test
    fun workloadManifestAcceptsExactOneMiBAndRejectsOneAdditionalCanonicalByte() {
        val base = validWorkload()
        val baseSize = valid(CapabilityIdentity.workloadManifestBytes(base)).size
        val exactPadding = CapabilityIdentityContract.WORKLOAD_MANIFEST_MAX_BYTES - baseSize
        assertTrue(exactPadding > 0)

        val atLimit = base.withFirstDependencyClassifier("x".repeat(exactPadding))
        val atLimitBytes = valid(CapabilityIdentity.workloadManifestBytes(atLimit))
        assertEquals(CapabilityIdentityContract.WORKLOAD_MANIFEST_MAX_BYTES, atLimitBytes.size)

        val overLimit = base.withFirstDependencyClassifier("x".repeat(exactPadding + 1))
        assertInvalid(CapabilityIdentity.workloadManifestBytes(overLimit))
    }

    @Test
    fun validBuildFileMutationChangesWorkloadAndRuntimeBuildIdentity() {
        val first = validWorkload()
        val mutableName = "app/build.gradle.kts"
        val changedFiles = first.buildFiles.map { file ->
            if (file.logicalName == mutableName) file.copy(fileSha256 = digest(88)) else file
        }
        val second = first.copy(buildFiles = changedFiles)

        val firstHash = valid(CapabilityIdentity.workloadManifestSha256(first))
        val secondHash = valid(CapabilityIdentity.workloadManifestSha256(second))
        assertNotEquals(firstHash, secondHash)

        val artifact = RuntimeArtifactId(digest(55))
        assertNotEquals(
            CapabilityIdentity.runtimeBuildId(artifact, firstHash),
            CapabilityIdentity.runtimeBuildId(artifact, secondHash),
        )
    }

    @Test
    fun requiredDigestBuildersUseExactCanonicalPreimagesAndAbsenceRules() {
        val osPreimage = hex(OS_BUILD_PREIMAGE_HEX)
        val cameraPreimage = hex(CAMERA2_ID_PREIMAGE_HEX)
        assertEquals(98, osPreimage.size)
        assertEquals(36, cameraPreimage.size)
        assertEquals(OS_BUILD_SHA256, independentSha256Hex(osPreimage))
        assertEquals(CAMERA2_ID_SHA256, independentSha256Hex(cameraPreimage))

        val osToken = present(valid(CapabilityIdentity.osBuildToken(OS_BUILD_VALUE)))
        val cameraToken = present(valid(CapabilityIdentity.camera2IdToken(CAMERA2_ID_VALUE)))
        assertEquals(RequiredDigestProvenance.OS_BUILD, osToken.provenance)
        assertEquals(RequiredDigestProvenance.CAMERA2_ID, cameraToken.provenance)
        assertEquals(OS_BUILD_SHA256, requireNotNull(osToken.digestOrNull).toLowerHex())
        assertEquals(CAMERA2_ID_SHA256, requireNotNull(cameraToken.digestOrNull).toLowerHex())
        assertFalse(osToken.toString().contains(OS_BUILD_VALUE))
        assertFalse(cameraToken.toString().contains(CAMERA2_ID_VALUE))
        assertTrue(
            osToken::class.java.declaredFields.none {
                it.type == String::class.java
            },
        )
        assertTrue(
            valid(CapabilityIdentity.osBuildToken(null))::class.java.declaredFields.none {
                it.type == String::class.java
            },
        )

        val scopeFields = fields(
            valid(
                CapabilityIdentity.probeBaseScopeBytes(
                    validScope().copy(
                        osBuildToken = osToken,
                        camera2IdToken = cameraToken,
                    ),
                ),
            ),
            "probe-base-scope-v2",
        ).toMap()
        assertEquals(OS_BUILD_TOKEN_HEX, scopeFields.getValue("os_build_token").toHex())
        assertEquals(CAMERA2_ID_TOKEN_HEX, scopeFields.getValue("camera2_id_token").toHex())

        listOf<String?>(null, "", "unknown").forEach { value ->
            assertAbsent(CapabilityIdentity.osBuildToken(value), RequiredDigestProvenance.OS_BUILD)
        }
        listOf<String?>(null, "").forEach { value ->
            assertAbsent(CapabilityIdentity.camera2IdToken(value), RequiredDigestProvenance.CAMERA2_ID)
        }
        assertTrue(valid(CapabilityIdentity.camera2IdToken("unknown")).digestOrNull != null)
        assertTrue(valid(CapabilityIdentity.osBuildToken(" ")).digestOrNull != null)
        assertTrue(valid(CapabilityIdentity.camera2IdToken(" ")).digestOrNull != null)

        val composed = present(valid(CapabilityIdentity.osBuildToken("\u00e9")))
        val decomposed = present(valid(CapabilityIdentity.osBuildToken("e\u0301")))
        assertNotEquals(composed.digestOrNull, decomposed.digestOrNull)
        assertInvalid(CapabilityIdentity.osBuildToken("\uD800"))
        assertInvalid(CapabilityIdentity.camera2IdToken("\uD800"))
    }

    @Test
    fun requiredDigestImplementationsHavePrivateBytecodeConstructionOnly() {
        val tokenClass = RequiredDigestToken::class.java
        val expectedImplementationNames =
            setOf(
                "${tokenClass.name}\$AbsentRequiredDigestToken",
                "${tokenClass.name}\$PresentRequiredDigestToken",
            )
        val implementations = checkNotNull(tokenClass.permittedSubclasses).toList()

        assertEquals(expectedImplementationNames, implementations.map { it.name }.toSet())
        implementations.forEach { implementation ->
            assertTrue(
                "Implementation class must be private: ${implementation.name}",
                Modifier.isPrivate(implementation.modifiers),
            )
            assertTrue(implementation.declaredConstructors.isNotEmpty())
            assertTrue(
                "A real private constructor must remain present: ${implementation.name}",
                implementation.declaredConstructors.any { Modifier.isPrivate(it.modifiers) },
            )
            implementation.declaredConstructors.forEach { constructor ->
                val arguments =
                    Array<Any?>(constructor.parameterCount) { index ->
                        val parameterType = constructor.parameterTypes[index]
                        when (parameterType) {
                            RequiredDigestProvenance::class.java -> RequiredDigestProvenance.OS_BUILD
                            String::class.java -> OS_BUILD_VALUE
                            else -> null
                        }
                    }
                assertFalse(
                    "No implementation constructor may accept a precomputed digest: $constructor",
                    constructor.parameterTypes.contains(Sha256Digest::class.java),
                )
                if (Modifier.isPrivate(constructor.modifiers)) {
                    assertThrows(IllegalAccessException::class.java) {
                        constructor.newInstance(*arguments)
                    }
                } else {
                    assertTrue(
                        "Only Kotlin's guarded synthetic bridge may be non-private: $constructor",
                        constructor.isSynthetic,
                    )
                    assertTrue(
                        "Synthetic bridge must require the canonical raw source: $constructor",
                        constructor.parameterTypes.contains(String::class.java),
                    )
                }
            }
        }

        val absentImplementation = implementations.single { "AbsentRequiredDigestToken" in it.name }
        val presentImplementation = implementations.single { "PresentRequiredDigestToken" in it.name }
        listOf<String?>(null, "", "unknown").forEach { value ->
            assertEquals(
                osToken(value),
                invokeSyntheticToken(absentImplementation, RequiredDigestProvenance.OS_BUILD, value),
            )
        }
        listOf<String?>(null, "").forEach { value ->
            assertEquals(
                cameraToken(value),
                invokeSyntheticToken(absentImplementation, RequiredDigestProvenance.CAMERA2_ID, value),
            )
        }
        assertSyntheticTokenRejected(
            absentImplementation,
            RequiredDigestProvenance.OS_BUILD,
            OS_BUILD_VALUE,
        )
        assertSyntheticTokenRejected(
            absentImplementation,
            RequiredDigestProvenance.CAMERA2_ID,
            "unknown",
        )

        val reflectedOs =
            invokeSyntheticToken(
                presentImplementation,
                RequiredDigestProvenance.OS_BUILD,
                OS_BUILD_VALUE,
            )
        val reflectedCamera =
            invokeSyntheticToken(
                presentImplementation,
                RequiredDigestProvenance.CAMERA2_ID,
                CAMERA2_ID_VALUE,
            )
        assertEquals(osToken(OS_BUILD_VALUE), reflectedOs)
        assertEquals(cameraToken(CAMERA2_ID_VALUE), reflectedCamera)
        assertNotEquals(
            cameraToken(CAMERA2_ID_VALUE),
            invokeSyntheticToken(
                presentImplementation,
                RequiredDigestProvenance.OS_BUILD,
                CAMERA2_ID_VALUE,
            ),
        )
        listOf<String?>(null, "", "unknown").forEach { value ->
            assertSyntheticTokenRejected(
                presentImplementation,
                RequiredDigestProvenance.OS_BUILD,
                value,
            )
        }
        listOf<String?>(null, "").forEach { value ->
            assertSyntheticTokenRejected(
                presentImplementation,
                RequiredDigestProvenance.CAMERA2_ID,
                value,
            )
        }
        listOf(RequiredDigestProvenance.OS_BUILD, RequiredDigestProvenance.CAMERA2_ID).forEach {
            provenance ->
            assertSyntheticTokenRejected(presentImplementation, provenance, "\uD800")
        }

        listOf(
            "${RequiredDigestToken::class.java.packageName}.AbsentRequiredDigestToken",
            "${RequiredDigestToken::class.java.packageName}.PresentRequiredDigestToken",
        ).forEach { legacyTopLevelName ->
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName(legacyTopLevelName)
            }
        }

        val exposedArbitraryInputMethods =
            (
                implementations +
                    implementations.map { Class.forName("${it.name}\$Companion") } +
                    tokenClass +
                    RequiredDigestToken.Companion::class.java +
                    CapabilityIdentity::class.java +
                    Class.forName("${tokenClass.packageName}.CapabilityPrimitivesKt")
            )
                .flatMap { it.declaredMethods.toList() }
                .filterNot { Modifier.isPrivate(it.modifiers) }
                .filter { method ->
                    val acceptsArbitraryTokenMaterial =
                        method.parameterTypes.any { parameterType ->
                            parameterType == RequiredDigestProvenance::class.java ||
                                parameterType == Sha256Digest::class.java
                        }
                    val returnsToken =
                        tokenClass.isAssignableFrom(method.returnType) ||
                            method.genericReturnType.typeName.contains(tokenClass.name)
                    acceptsArbitraryTokenMaterial && returnsToken
                }
        assertTrue(
            "Exposed digest/provenance construction surface: $exposedArbitraryInputMethods",
            exposedArbitraryInputMethods.isEmpty(),
        )
    }

    @Test
    fun samePackageJavaCannotCompileDirectRequiredDigestForgery() {
        val benign =
            compileJava(
                simpleClassName = "RequiredDigestClasspathProbe",
                source =
                    """
                    package com.motionarcade.vision.capability.domain;
                    final class RequiredDigestClasspathProbe {
                        static Class<?> tokenType() { return RequiredDigestToken.class; }
                    }
                    """.trimIndent(),
            )
        assertTrue("Java compiler cannot see the production token class: ${benign.diagnostics}", benign.success)

        val maliciousSources =
            listOf(
                "LegacyAbsentForge" to
                    """
                    package com.motionarcade.vision.capability.domain;
                    final class LegacyAbsentForge {
                        static RequiredDigestToken forge() {
                            return new AbsentRequiredDigestToken(RequiredDigestProvenance.OS_BUILD);
                        }
                    }
                    """.trimIndent(),
                "LegacyPresentForge" to
                    """
                    package com.motionarcade.vision.capability.domain;
                    final class LegacyPresentForge {
                        static RequiredDigestToken forge(Sha256Digest digest) {
                            return new PresentRequiredDigestToken(
                                RequiredDigestProvenance.CAMERA2_ID,
                                digest
                            );
                        }
                    }
                    """.trimIndent(),
                "NestedAbsentForge" to
                    """
                    package com.motionarcade.vision.capability.domain;
                    final class NestedAbsentForge {
                        static RequiredDigestToken forge() {
                            return new RequiredDigestToken.AbsentRequiredDigestToken(
                                RequiredDigestProvenance.OS_BUILD
                            );
                        }
                    }
                    """.trimIndent(),
                "NestedPresentForge" to
                    """
                    package com.motionarcade.vision.capability.domain;
                    final class NestedPresentForge {
                        static RequiredDigestToken forge(Sha256Digest digest) {
                            return new RequiredDigestToken.PresentRequiredDigestToken(
                                RequiredDigestProvenance.CAMERA2_ID,
                                digest
                            );
                        }
                    }
                    """.trimIndent(),
            )

        maliciousSources.forEach { (className, source) ->
            val compilation = compileJava(className, source)
            assertFalse(
                "same-package Java forged a token with $className: ${compilation.diagnostics}",
                compilation.success,
            )
        }
    }

    @Test
    fun probeScopeHasExactThirtyThreeFieldsAndRequiredTokenTags() {
        val scope = validScope()
        val bytes = valid(CapabilityIdentity.probeBaseScopeBytes(scope))
        val firstId = valid(CapabilityIdentity.probeBaseScopeId(scope))
        val tokenChanged = valid(
            CapabilityIdentity.probeBaseScopeId(
                scope.copy(camera2IdToken = cameraToken("camera-33")),
            ),
        )

        val fields = fields(bytes, "probe-base-scope-v2")
        assertEquals(
            listOf(
                "runtime_build_id",
                "app_version_code",
                "build_type",
                "minified",
                "policy_revision",
                "workload_revision",
                "input_adapter_revision",
                "state_machine_revision",
                "journal_revision",
                "profile_schema_revision",
                "preview_revision",
                "model_sha256",
                "os_api",
                "os_build_token",
                "camera2_id_token",
                "lens",
                "analysis_width",
                "analysis_height",
                "rotation_degrees",
                "crop_left",
                "crop_top",
                "crop_right",
                "crop_bottom",
                "preview_surface_width",
                "preview_surface_height",
                "transform_crop_left",
                "transform_crop_top",
                "transform_crop_right",
                "transform_crop_bottom",
                "transform_rotation_degrees",
                "target_rotation",
                "mode",
                "running_mode",
            ),
            fields.map { it.first },
        )
        val values = fields.toMap()
        assertEquals(33, values.getValue("os_build_token").size)
        assertEquals(1, values.getValue("os_build_token").first().toInt())
        assertArrayEquals(byteArrayOf(0), values.getValue("camera2_id_token"))
        assertNotEquals(firstId, tokenChanged)
    }

    @Test
    fun probeScopeRejectsWrongRevisionRotationCropTargetAndModel() {
        val scope = validScope()
        assertInvalid(CapabilityIdentity.probeBaseScopeBytes(scope.copy(profileSchemaRevision = "v3")))
        assertInvalid(CapabilityIdentity.probeBaseScopeBytes(scope.copy(rotationDegrees = 45u)))
        assertInvalid(CapabilityIdentity.probeBaseScopeBytes(scope.copy(cropRight = 641u)))
        assertInvalid(CapabilityIdentity.probeBaseScopeBytes(scope.copy(targetRotation = 4u)))
        assertInvalid(CapabilityIdentity.probeBaseScopeBytes(scope.copy(modelSha256 = digest(1))))
        assertInvalid(
            CapabilityIdentity.probeBaseScopeBytes(
                scope.copy(osBuildToken = cameraToken(CAMERA2_ID_VALUE)),
            ),
        )
        assertInvalid(
            CapabilityIdentity.probeBaseScopeBytes(
                scope.copy(camera2IdToken = osToken(OS_BUILD_VALUE)),
            ),
        )
    }

    @Test
    fun scopeAndResultIdentityMutateWithModeLensAndExplicitDelegateWire() {
        val scope = validScope()
        val baseId = valid(CapabilityIdentity.probeBaseScopeId(scope))
        val dualId = valid(CapabilityIdentity.probeBaseScopeId(scope.copy(mode = GameMode.DUAL)))
        val backId = valid(CapabilityIdentity.probeBaseScopeId(scope.copy(lensFacing = LensFacing.BACK)))

        assertNotEquals(baseId, dualId)
        assertNotEquals(baseId, backId)
        assertNotEquals(
            CapabilityIdentity.capabilityResultId(baseId, ProbeDelegate.NONE),
            CapabilityIdentity.capabilityResultId(baseId, ProbeDelegate.CPU),
        )
        assertNotEquals(
            CapabilityIdentity.capabilityResultId(baseId, ProbeDelegate.CPU),
            CapabilityIdentity.capabilityResultId(baseId, ProbeDelegate.GPU),
        )
    }

    @Test
    fun artifactDomainAcceptsNoRawPathField() {
        assertEquals(
            setOf("logicalName", "apkLength", "apkSha256"),
            RuntimeArtifactEntryV1::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun manifestListInputsAreDefensivelyCopiedAtConstruction() {
        val base = validWorkload()
        val delegates = mutableListOf(ProbeDelegate.CPU, ProbeDelegate.GPU)
        val dependencies = base.dependencyArtifacts.toMutableList()
        val buildFiles = base.buildFiles.toMutableList()
        val lockFiles = base.lockFiles.toMutableList()
        val immutable = base.copy(
            poseOptions = base.poseOptions.copy(candidateDelegates = delegates),
            dependencyArtifacts = dependencies,
            buildFiles = buildFiles,
            lockFiles = lockFiles,
        )
        val before = valid(CapabilityIdentity.workloadManifestBytes(immutable))

        delegates.clear()
        dependencies.clear()
        buildFiles.clear()
        lockFiles.clear()

        assertEquals(listOf(ProbeDelegate.CPU, ProbeDelegate.GPU), immutable.poseOptions.candidateDelegates)
        assertArrayEquals(before, valid(CapabilityIdentity.workloadManifestBytes(immutable)))
    }

    @Test
    fun authorityNameRegistriesRejectEveryMutationAndKeepIdentityBytesStable() {
        val workload = validWorkload()
        val policyBefore = valid(CapabilityIdentity.nativeClosePolicySetSha256(workload.buildFiles))
        val workloadBefore = valid(CapabilityIdentity.workloadManifestBytes(workload))

        listOf(
            CapabilityIdentityContract.requiredBuildFileNames,
            CapabilityIdentityContract.requiredLockFileNames,
            CapabilityIdentityContract.nativeClosePolicyFileNames,
        ).forEach(::assertUnmodifiable)

        assertEquals(
            policyBefore,
            valid(CapabilityIdentity.nativeClosePolicySetSha256(workload.buildFiles)),
        )
        assertArrayEquals(workloadBefore, valid(CapabilityIdentity.workloadManifestBytes(workload)))
    }

    @Test
    fun independentGoldenHashesFixFullIdentityCanonicalBytes() {
        val workload = validWorkload()
        val workloadBytes = valid(CapabilityIdentity.workloadManifestBytes(workload))
        val proofBasisBytes = fields(workloadBytes, "workload-build-manifest-v3")
            .toMap()
            .getValue("native_close_proof_basis")
        val scopeBytes = valid(CapabilityIdentity.probeBaseScopeBytes(validScope()))

        assertEquals(3_396, workloadBytes.size)
        assertEquals(
            WORKLOAD_SHA256,
            CanonicalManifestCodec.sha256(workloadBytes).toLowerHex(),
        )
        assertEquals(418, proofBasisBytes.size)
        assertEquals(
            "cbdffa179458072e1f6589049a494d9b208720f8e5d65cbaced4574421f1d5f2",
            CanonicalManifestCodec.sha256(proofBasisBytes).toLowerHex(),
        )
        assertEquals(
            "f452728463f7ddea617bc5ef3a6176986bec9e5b208f99043cb1fdbd5c2bf562",
            valid(CapabilityIdentity.nativeClosePolicySetSha256(workload.buildFiles)).toLowerHex(),
        )
        assertEquals(1_217, scopeBytes.size)
        assertEquals(
            CANONICAL_SCOPE_SHA256,
            CanonicalManifestCodec.sha256(scopeBytes).toLowerHex(),
        )
    }

    private fun validWorkload(): WorkloadBuildManifestV3 {
        val buildFiles = CapabilityIdentityContract.requiredBuildFileNames.mapIndexed { index, name ->
            NamedFileDigest(name, (index + 1).toULong(), digest(index + 1))
        }
        val lockFiles = CapabilityIdentityContract.requiredLockFileNames.mapIndexed { index, name ->
            NamedFileDigest(name, (index + 100).toULong(), digest(index + 100))
        }
        val dependencies =
            listOf(
                NamedFileDigest("maven:z.group:z-name:1.0::jar", 40uL, digest(40)),
                NamedFileDigest("maven:a.group:a-name:2.0::aar", 41uL, digest(41)),
            )
        val policyHash = valid(CapabilityIdentity.nativeClosePolicySetSha256(buildFiles))
        val dependencyHash = valid(CapabilityIdentity.dependencyArtifactsValueSha256(dependencies))
        return WorkloadBuildManifestV3(
            policyRevision = CapabilityIdentityContract.POLICY_REVISION,
            workloadRevision = CapabilityIdentityContract.WORKLOAD_REVISION,
            inputAdapterRevision = CapabilityIdentityContract.INPUT_ADAPTER_REVISION,
            stateMachineRevision = CapabilityIdentityContract.STATE_MACHINE_REVISION,
            journalRevision = CapabilityIdentityContract.JOURNAL_REVISION,
            previewRevision = CapabilityIdentityContract.PREVIEW_REVISION,
            appVersionCode = 1uL,
            buildType = "debug",
            minified = false,
            poseOptions = PoseOptionsV2.exact(),
            modelFile =
                NamedFileDigest(
                    CapabilityIdentityContract.MODEL_LOGICAL_PATH,
                    5_777_746uL,
                    valid(Sha256Digest.fromLowerHex(CapabilityIdentityContract.MODEL_SHA256_HEX)),
                ),
            dependencyArtifacts = dependencies,
            buildFiles = buildFiles,
            lockFiles = lockFiles,
            nativeCloseProofBasis =
                NativeCloseProofBasisV1(
                    schemaRevision = "native-close-proof-basis-v1",
                    nativeClosePolicySetSha256 = policyHash,
                    dependencyArtifactsValueSha256 = dependencyHash,
                    buildVariant = NativeCloseBuildVariantV1("debug", false),
                    nativeCloseCodeImageSha256 = digest(77),
                ),
        )
    }

    private fun validScope(): ProbeBaseScopeV2 =
        ProbeBaseScopeV2(
            runtimeBuildId = RuntimeBuildId(digest(1)),
            appVersionCode = 1uL,
            buildType = "debug",
            minified = false,
            policyRevision = CapabilityIdentityContract.POLICY_REVISION,
            workloadRevision = CapabilityIdentityContract.WORKLOAD_REVISION,
            inputAdapterRevision = CapabilityIdentityContract.INPUT_ADAPTER_REVISION,
            stateMachineRevision = CapabilityIdentityContract.STATE_MACHINE_REVISION,
            journalRevision = CapabilityIdentityContract.JOURNAL_REVISION,
            profileSchemaRevision = CapabilityIdentityContract.PROFILE_SCHEMA_REVISION,
            previewRevision = CapabilityIdentityContract.PREVIEW_REVISION,
            modelSha256 = valid(Sha256Digest.fromLowerHex(CapabilityIdentityContract.MODEL_SHA256_HEX)),
            osApi = 37u,
            osBuildToken = osToken(OS_BUILD_VALUE),
            camera2IdToken = cameraToken(null),
            lensFacing = LensFacing.FRONT,
            analysisWidth = 640u,
            analysisHeight = 480u,
            rotationDegrees = 90u,
            cropLeft = 0u,
            cropTop = 0u,
            cropRight = 640u,
            cropBottom = 480u,
            previewSurfaceWidth = 1080u,
            previewSurfaceHeight = 1920u,
            transformCropLeft = 0u,
            transformCropTop = 0u,
            transformCropRight = 640u,
            transformCropBottom = 480u,
            transformRotationDegrees = 270u,
            targetRotation = 1u,
            mode = GameMode.SOLO,
            runningMode = ProbeRunningMode.LIVE_STREAM,
        )

    private fun WorkloadBuildManifestV3.withFirstDependencyClassifier(
        classifier: String,
    ): WorkloadBuildManifestV3 {
        val first = dependencyArtifacts.first()
        check(first.logicalName.endsWith("::jar"))
        val changed = first.copy(
            logicalName = first.logicalName.removeSuffix("::jar") + ":$classifier:jar",
        )
        val dependencies = listOf(changed) + dependencyArtifacts.drop(1)
        val dependencyHash = valid(CapabilityIdentity.dependencyArtifactsValueSha256(dependencies))
        return copy(
            dependencyArtifacts = dependencies,
            nativeCloseProofBasis = nativeCloseProofBasis.copy(
                dependencyArtifactsValueSha256 = dependencyHash,
            ),
        )
    }

    private fun assertUnmodifiable(values: List<String>) {
        @Suppress("UNCHECKED_CAST")
        val mutable = values as MutableList<String>
        assertUnsupportedOperation { mutable[0] = "mutated" }
        assertUnsupportedOperation { mutable.add("mutated") }
        assertUnsupportedOperation { mutable.removeAt(0) }
        assertUnsupportedOperation { mutable.clear() }
    }

    private fun assertUnsupportedOperation(operation: () -> Unit) {
        var rejected = false
        try {
            operation()
        } catch (_: UnsupportedOperationException) {
            rejected = true
        }
        assertTrue("Expected UnsupportedOperationException", rejected)
    }

    private fun assertAbsent(
        result: CapabilityDomainResult<RequiredDigestToken>,
        expectedProvenance: RequiredDigestProvenance,
    ) {
        val token = valid(result)
        assertTrue(token.digestOrNull == null)
        assertEquals(expectedProvenance, token.provenance)
    }

    private fun osToken(value: String?): RequiredDigestToken =
        valid(CapabilityIdentity.osBuildToken(value))

    private fun cameraToken(value: String?): RequiredDigestToken =
        valid(CapabilityIdentity.camera2IdToken(value))

    private fun present(token: RequiredDigestToken): RequiredDigestToken {
        checkNotNull(token.digestOrNull) { "Expected PRESENT token, got $token" }
        return token
    }

    private fun digestFromHex(value: String): Sha256Digest =
        valid(Sha256Digest.fromLowerHex(value))

    private fun independentSha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private fun invokeSyntheticToken(
        implementation: Class<*>,
        provenance: RequiredDigestProvenance,
        value: String?,
    ): RequiredDigestToken {
        val constructor = implementation.declaredConstructors.single { !Modifier.isPrivate(it.modifiers) }
        assertTrue(constructor.isSynthetic)
        val arguments =
            Array<Any?>(constructor.parameterCount) { index ->
                val parameterType = constructor.parameterTypes[index]
                when (parameterType) {
                    RequiredDigestProvenance::class.java -> provenance
                    String::class.java -> value
                    else -> null
                }
            }
        return constructor.newInstance(*arguments) as RequiredDigestToken
    }

    private fun assertSyntheticTokenRejected(
        implementation: Class<*>,
        provenance: RequiredDigestProvenance,
        value: String?,
    ) {
        val rejected = assertThrows(InvocationTargetException::class.java) {
            invokeSyntheticToken(implementation, provenance, value)
        }
        assertTrue(
            "Noncanonical bridge input was not rejected: ${rejected.cause}",
            rejected.cause is NullPointerException || rejected.cause is IllegalArgumentException,
        )
    }

    private fun compileJava(simpleClassName: String, source: String): JavaCompilation {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Identity construction-surface tests require a JDK compiler"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val outputDirectory = Files.createTempDirectory("required-digest-javac-")
        return try {
            val sourceObject =
                object : SimpleJavaFileObject(
                    URI.create("string:///$simpleClassName.java"),
                    JavaFileObject.Kind.SOURCE,
                ) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
                }
            val success =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { fileManager ->
                    compiler.getTask(
                        null,
                        fileManager,
                        diagnostics,
                        listOf(
                            "-classpath",
                            System.getProperty("java.class.path"),
                            "-d",
                            outputDirectory.toString(),
                            "-proc:none",
                        ),
                        null,
                        listOf(sourceObject),
                    ).call()
                }
            JavaCompilation(
                success = success,
                diagnostics = diagnostics.diagnostics.joinToString(separator = "\n") { it.toString() },
            )
        } finally {
            outputDirectory.toFile().deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
        }

    private fun fields(bytes: ByteArray, domain: String): List<Pair<String, ByteArray>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(domain.toByteArray(StandardCharsets.UTF_8).size + 1)
        val count = buffer.int
        return List(count) {
            val nameBytes = ByteArray(buffer.int)
            buffer.get(nameBytes)
            val valueLength = buffer.long
            require(valueLength in 0L..Int.MAX_VALUE.toLong())
            val value = ByteArray(valueLength.toInt())
            buffer.get(value)
            String(nameBytes, StandardCharsets.UTF_8) to value
        }.also { check(!buffer.hasRemaining()) }
    }

    private fun digest(seed: Int): Sha256Digest =
        valid(Sha256Digest.fromBytes(ByteArray(32) { index -> (seed + index).toByte() }))

    private fun assertInvalid(result: CapabilityDomainResult<*>) {
        assertTrue("Expected invalid result, got $result", result is CapabilityDomainResult.Invalid)
    }

    private data class JavaCompilation(
        val success: Boolean,
        val diagnostics: String,
    )

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }

    private companion object {
        const val OS_BUILD_VALUE =
            "vendor/device/product:14/UP1A.231005.007/123456:user/release-keys"
        const val OS_BUILD_PREIMAGE_HEX =
            "6f732d6275696c642d763100000000010000000576616c75650000000000000041" +
                "76656e646f722f6465766963652f70726f647563743a31342f555031412e323331" +
                "3030352e3030372f3132333435363a757365722f72656c656173652d6b657973"
        const val OS_BUILD_SHA256 =
            "fa779ecc83a5e26a27744d6393a6ebae700ee3b87ef986c96d75fadfb9e0a20c"
        const val OS_BUILD_TOKEN_HEX =
            "01fa779ecc83a5e26a27744d6393a6ebae700ee3b87ef986c96d75fadfb9e0a20c"

        const val CAMERA2_ID_VALUE = "0"
        const val CAMERA2_ID_PREIMAGE_HEX =
            "63616d657261322d69642d763100000000010000000576616c7565000000000000000130"
        const val CAMERA2_ID_SHA256 =
            "45839db3dba82d2288fc7e82d5deefe06b0866a09a716e921743cf412c96af2c"
        const val CAMERA2_ID_TOKEN_HEX =
            "0145839db3dba82d2288fc7e82d5deefe06b0866a09a716e921743cf412c96af2c"

        const val RUNTIME_ARTIFACT_SHA256 =
            "b1688eecd61d6483f2cae6b1f9e034d0216f9f3818371cc04fcbd2da72f6fc39"
        const val WORKLOAD_SHA256 =
            "b1c0306d9e38982ced116cbd2ef581dfb13330d8ebab85e4266cfe4df3bd4919"
        const val RUNTIME_BUILD_SHA256 =
            "80ab073f677478872eb7f2eff76204402e969e4800ef9c4eaf9f62d72f26a698"
        const val RESULT_GOLDEN_SCOPE_ID =
            "159d4f9967edc2974910ccc909fd6314413d570634c12898866560d572f47785"
        const val CAPABILITY_RESULT_NONE_SHA256 =
            "2ba94846acc5207f9b2b05a45541bf3f8b1937d1ff8c1fd35ab3accb5b2adebe"
        const val CAPABILITY_RESULT_CPU_SHA256 =
            "81045e87aa3e3ceadc6e5f606a88f28a719e386ec1b327d852d5b75117a6e94a"
        const val CAPABILITY_RESULT_GPU_SHA256 =
            "20a6eb4f5bfa3eac97e516633f11aee04ce3bcf2a8cd64a5d0f483e672be2512"
        const val CANONICAL_SCOPE_SHA256 =
            "8ab54b55f691889ff5da8cd6197d51882d9953a336e970fa8e9614c184f77e7b"
    }
}
