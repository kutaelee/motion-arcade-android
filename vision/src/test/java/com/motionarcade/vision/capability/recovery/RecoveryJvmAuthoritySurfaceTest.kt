package com.motionarcade.vision.capability.recovery

import java.lang.reflect.Modifier
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJvmAuthoritySurfaceTest {
    @Test
    fun everyAuthorityImplementationIsAnExactPrivateNestedBoundaryClass() {
        expectedPrivateNestedClasses().forEach { (boundary, simpleNames) ->
            val nested = boundary.declaredClasses.associateBy(Class<*>::getSimpleName)
            simpleNames.forEach { simpleName ->
                val implementation = requireNotNull(nested[simpleName])
                assertEquals(boundary, implementation.enclosingClass)
                assertTrue(
                    "$boundary.$simpleName must be JVM-private",
                    Modifier.isPrivate(implementation.modifiers),
                )
                assertTrue(Modifier.isStatic(implementation.modifiers))
                assertTrue(Modifier.isFinal(implementation.modifiers))
            }
        }
    }

    @Test
    fun historicalTopLevelAuthorityBinaryNamesNoLongerExist() {
        expectedPrivateNestedClasses().values.flatten().forEach { simpleName ->
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName("com.motionarcade.vision.capability.recovery.$simpleName")
            }
        }
    }

    @Test
    fun samePackageJavaCannotStealIssuerInvokeSyntheticConstructorOrMutateAuthorityState() {
        val source =
            """
            package com.motionarcade.vision.capability.recovery;

            final class RecoveryAuthorityAttack {
              void camera(RecoveryCameraPipelineBoundary.IssuedRecoveryCameraPipelineOwner owner) {
                owner.setStickyPoison(false);
                owner.setAnalyzerEntriesInFlight(0);
              }
              void cameraBinary(RecoveryCameraPipelineBoundary${'$'}IssuedRecoveryCameraFrameLease frame) {
                frame.setBufferZeroed(true);
              }
              void thermal(ProcessThermalSafetyBoundary.IssuedProcessThermalSafetyMonitor monitor) {
                monitor.setSafetyFailureLatched(false);
              }
              void render(RecoveryRenderOwnerBoundary.IssuedRecoveryRenderOwner owner) {
                owner.setInFlightCallbacks(0);
              }
              void session(RecoveryCleanClosureBoundary.IssuedRecoveryClosureSession session) {
                session.setFailed(false);
              }
              void receipt(RecoveryDurableJournalReceiptBoundary.IssuedRecoveryDurableActiveJournalReceipt receipt) {
                receipt.getConsumed().set(false);
              }
              void denial(RecoveryPreNativeCreateDenialBoundary.IssuedRecoveryPreNativeCreateDenialAuthority denial) {
                denial.getConsumed().set(false);
              }
              void poison(RecoveryPostSealPoisonPersistenceBoundary.IssuedRecoveryDurablePostSealPoisonSink sink) {
                sink.getCommitted().set(false);
              }
            }
            """.trimIndent()

        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val directory = Files.createTempDirectory("recovery-jvm-attack")
        val sourceFile = directory.resolve("RecoveryAuthorityAttack.java")
        Files.writeString(sourceFile, source)
        val errors = StringBuilder()
        val result =
            compiler.run(
                null,
                null,
                object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        errors.append(value.toChar())
                    }
                },
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                sourceFile.toString(),
            )

        assertFalse("same-package Java attack unexpectedly compiled: $errors", result == 0)
        assertFalse(Files.exists(directory.resolve("RecoveryAuthorityAttack.class")))
    }

    @Test
    fun mutableAuthorityBackingFieldsRemainJvmPrivate() {
        val owner = RecoveryClosureTestFixture.cameraPipelineOwner()
        listOf(
            "stickyPoison",
            "analyzerEntriesInFlight",
            "claimedResourceGeneration",
            "totalFrameCount",
        ).forEach { name ->
            val field = owner.javaClass.getDeclaredField(name)
            assertTrue("$name backing field must be private", Modifier.isPrivate(field.modifiers))
        }
    }

    @Test
    fun persistenceBoundaryExportsNoFenceIssuerBridge() {
        assertTrue(
            RecoveryPostSealPoisonPersistenceBoundary::class.java.declaredMethods.none { method ->
                method.name.contains("issueCleanFence") ||
                    method.name.contains("mintFence") ||
                    method.name.startsWith("access\$") && method.name.contains("Fence")
            },
        )
    }

    @Test
    fun authorityBoundariesExportNoKotlinPrivateAccessBridge() {
        val boundaries =
            listOf(
                RecoveryCameraPipelineBoundary::class.java,
                ProcessThermalSafetyBoundary::class.java,
                RecoveryRenderOwnerBoundary::class.java,
                RecoveryDurableJournalReceiptBoundary::class.java,
                RecoveryCleanClosureBoundary::class.java,
                RecoveryPreNativeCreateDenialBoundary::class.java,
                RecoveryPostSealPoisonPersistenceBoundary::class.java,
            )

        boundaries.forEach { boundary ->
            val bridges =
                boundary.declaredMethods
                    .filter { method -> method.name.startsWith("access\$") }
                    .map { method -> method.toGenericString() }
            assertTrue("$boundary exports Kotlin private-access bridges: $bridges", bridges.isEmpty())
        }
    }

    private fun expectedPrivateNestedClasses(): Map<Class<*>, Set<String>> =
        linkedMapOf(
            RecoveryCameraPipelineBoundary::class.java to
                setOf(
                    "IssuedRecoveryCameraPipelineOwner",
                    "IssuedRecoveryCameraAnalyzerSource",
                    "IssuedRecoveryCameraFrameLease",
                    "IssuedRecoveryCameraPipelineClosure",
                ),
            ProcessThermalSafetyBoundary::class.java to
                setOf(
                    "IssuedProcessThermalSafetyMonitor",
                    "IssuedRecoveryThermalMeasurement",
                    "IssuedRecoveryThermalCutoff",
                    "IssuedUnavailableApiThermalMonitor",
                    "IssuedUnavailableApiThermalMeasurement",
                    "IssuedUnavailableApiThermalCutoff",
                ),
            RecoveryRenderOwnerBoundary::class.java to
                setOf(
                    "IssuedRecoveryRenderOwner",
                    "IssuedRenderMutation",
                    "IssuedRecoveryRenderClosure",
                ),
            RecoveryDurableJournalReceiptBoundary::class.java to
                setOf(
                    "IssuedRecoveryDurableActiveJournalReceipt",
                    "IssuedRecoveryDurableTeardownJournalReceipt",
                ),
            RecoveryCleanClosureBoundary::class.java to
                setOf(
                    "IssuedRecoveryClosureSession",
                    "IssuedRecoveryThermallyGuardedRuntimeOpen",
                    "IssuedRecoveryCleanClosureAuthority",
                    "IssuedRecoveryCleanJournalPersistenceFence",
                ),
            RecoveryPreNativeCreateDenialBoundary::class.java to
                setOf("IssuedRecoveryPreNativeCreateDenialAuthority"),
            RecoveryPostSealPoisonPersistenceBoundary::class.java to
                setOf("IssuedRecoveryDurablePostSealPoisonSink"),
        )
}
