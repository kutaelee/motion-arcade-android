package com.motionarcade.vision.capability.recovery

import androidx.camera.core.ImageProxy
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import com.motionarcade.vision.capability.runtime.ProbeCloseClaim
import com.motionarcade.vision.capability.runtime.ProbeCloseCompletion
import com.motionarcade.vision.capability.runtime.ProbeCloseCompletionKind
import com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCleanClosureAuthorityTest {
    @Test
    fun exactMeasuredClosureClearsTeardownOnceAndRawRouteKeyNeverAuthorizes() {
        val (teardown, key) = teardown(seed = 41)
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(teardown, key),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REQUIRED,
        )
        val authority =
            RecoveryClosureTestFixture.cleanAuthority(
                teardown,
                key,
                RouteAttemptOutcome.MEASURED,
            )
        val applied =
            requireApplied(
                RecoveryJournalAttemptReducer.completeCleanIntermediate(
                    teardown,
                    key,
                    authority,
                ),
            )
        assertNull(applied.next.active)
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                teardown,
                key,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_ALREADY_CONSUMED,
        )
    }

    @Test
    fun postNativeAndPreNativeTerminalAuthoritiesAreNotInterchangeable() {
        val (teardown, key) = teardown(seed = 42)
        val postNativeProof =
            RecoveryJournalTestFixtures.proof(
                delegate = key.delegate,
                role = key.role,
                reason = TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN,
            )
        val cleanAuthority =
            RecoveryClosureTestFixture.cleanAuthority(
                teardown,
                key,
                RouteAttemptOutcome.CLEAN_TERMINAL,
            )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                teardown,
                key,
                postNativeProof,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REQUIRED,
        )
        val terminal =
            requireApplied(
                RecoveryJournalAttemptReducer.recordCleanTerminal(
                    teardown,
                    key,
                    postNativeProof,
                    cleanAuthority,
                ),
            )
        assertEquals(TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN, terminal.next.entries.single().terminalEvidence?.reason)

        val (preTeardown, preKey) = teardown(seed = 43)
        val preProof =
            RecoveryJournalTestFixtures.proof(
                delegate = preKey.delegate,
                role = preKey.role,
                reason = TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY,
            )
        val wrongPostAuthority =
            RecoveryClosureTestFixture.cleanAuthority(
                preTeardown,
                preKey,
                RouteAttemptOutcome.CLEAN_TERMINAL,
            )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                preTeardown,
                preKey,
                preProof,
                wrongPostAuthority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(preTeardown, preKey, preProof),
            RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_REQUIRED,
        )
        val preAuthority =
            RecoveryClosureTestFixture.syntheticPreNativeAuthorityForReducerOnly(
                preTeardown,
                preKey,
            )
        requireApplied(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                preTeardown,
                preKey,
                preProof,
                preAuthority,
            ),
        )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                preTeardown,
                preKey,
                preProof,
                preAuthority,
            ),
            RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_ALREADY_CONSUMED,
        )
    }

    @Test
    fun cleanAuthorityIsAttemptAndRouteBound() {
        val (firstTeardown, firstKey) = teardown(seed = 44)
        val authority =
            RecoveryClosureTestFixture.cleanAuthority(
                firstTeardown,
                firstKey,
                RouteAttemptOutcome.MEASURED,
            )
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                firstTeardown.copy(recoveryBuildId = RecoveryJournalTestFixtures.runtime(144)),
                firstKey,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                firstTeardown.copy(mode = RecoveryJournalMode.DUAL),
                firstKey,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        val (otherTeardown, otherKey) = teardown(seed = 45)
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                otherTeardown,
                otherKey,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        val differentCommittedBytes = firstTeardown.copy(lastEpoch = firstTeardown.lastEpoch + 1uL)
        assertTrue(
            com.motionarcade.vision.capability.domain.CapabilityDomainResult.Valid::class.java
                .isInstance(RecoveryJournalV5Codec.encodePayload(differentCommittedBytes)),
        )
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                differentCommittedBytes,
                firstKey,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        // A mismatch does not burn the exact route's one-shot authority.
        requireApplied(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                firstTeardown,
                firstKey,
                authority,
            ),
        )
    }

    @Test
    fun sessionRejectsCrossMachineCrossClaimAndCrossRouteCloseEvidence() {
        val firstKey = routeKey(seed = 46)
        val secondKey = routeKey(seed = 47)
        val first =
            RecoveryClosureTestFixture.harness(firstKey, RouteAttemptOutcome.MEASURED)
        val second =
            RecoveryClosureTestFixture.harness(secondKey, RouteAttemptOutcome.MEASURED)
        assertTrue(first.closeExternalOwners())
        assertTrue(second.closeExternalOwners())
        assertTrue(
            RecoveryCleanClosureBoundary.bindCommittedTeardown(
                first.session,
                first.committedTeardownReceipt,
            ),
        )
        assertFalse(
            RecoveryCleanClosureBoundary.bindCommittedTeardown(
                first.session,
                first.committedTeardownReceipt,
            ),
        )
        assertTrue(
            RecoveryCleanClosureBoundary.bindCommittedTeardown(
                second.session,
                second.committedTeardownReceipt,
            ),
        )
        val firstClaim =
            requireNotNull(RecoveryCleanClosureBoundary.beginRuntimeClose(first.session))
        val secondClaim =
            requireNotNull(RecoveryCleanClosureBoundary.beginRuntimeClose(second.session))
        assertNull(RecoveryCleanClosureBoundary.executeRuntimeClose(first.session, secondClaim))
        val secondExecution =
            requireNotNull(
                RecoveryCleanClosureBoundary.executeRuntimeClose(second.session, secondClaim),
            )
        assertNull(
            RecoveryCleanClosureBoundary.admitRuntimeClose(
                first.session,
                firstClaim,
                secondExecution,
            ),
        )
        val firstExecution =
            requireNotNull(
                RecoveryCleanClosureBoundary.executeRuntimeClose(first.session, firstClaim),
            )
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            RecoveryCleanClosureBoundary.admitRuntimeClose(
                first.session,
                firstClaim,
                firstExecution,
            ),
        )

        val unbound =
            RecoveryClosureTestFixture.unboundRuntime(firstKey, RouteAttemptOutcome.MEASURED)
        val render = RecoveryClosureTestFixture.renderOwner()
        val camera = RecoveryClosureTestFixture.cameraPipelineOwner()
        assertNotNull(
            RecoveryCleanClosureBoundary.begin(
                unbound.guardedOpen,
                render,
                camera,
            ),
        )
        // The exact guarded open is itself a one-shot route binding.
        assertNull(
            RecoveryCleanClosureBoundary.begin(
                unbound.guardedOpen,
                RecoveryClosureTestFixture.renderOwner(),
                RecoveryClosureTestFixture.cameraPipelineOwner(),
            ),
        )
    }

    @Test
    fun openOwnersAndRenderMustActuallyCloseBeforeAuthorityExists() {
        val key = routeKey(seed = 48)
        val harness =
            RecoveryClosureTestFixture.harness(
                key,
                RouteAttemptOutcome.MEASURED,
            )
        assertNull(RecoveryCleanClosureBoundary.beginRuntimeClose(harness.session))
        assertNotNull(RecoveryCleanClosureBoundary.closeCameraPipeline(harness.session))
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            harness.closeNative(),
        )
        assertNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(harness.session))
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
        assertNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(harness.session))
        assertNotNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
    }

    @Test
    fun boundaryInvokesConcreteCloseZeroAndCameraDetachBeforeMint() {
        val sourceCloseCalls = AtomicInteger()
        val cameraDetachCalls = AtomicInteger()
        var cameraVerifiedClosed = false
        val cameraPort =
            object : RecoveryCameraPipelinePlatformPort {
                override fun detachAnalyzerAndUnbindSource() {
                    cameraDetachCalls.incrementAndGet()
                    cameraVerifiedClosed = true
                }

                override fun isDetachedAndUnbound(): Boolean = cameraVerifiedClosed
            }
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 148),
                RouteAttemptOutcome.MEASURED,
                cameraPort = cameraPort,
                frameSourceFactory = {
                    RecoveryClosureTestFixture.imageProxy(
                        sourceBuffer =
                            ByteBuffer.allocateDirect(4).apply {
                                while (hasRemaining()) put(0x55.toByte())
                                clear()
                            },
                        onClose = { sourceCloseCalls.incrementAndGet() },
                    )
                },
            )
        val callbackCount =
            Math.addExact(
                harness.machine.successfulWarmupCallbacks,
                harness.machine.measurementCompletions,
            )
        assertNull(RecoveryCleanClosureBoundary.beginRuntimeClose(harness.session))
        assertEquals(callbackCount, sourceCloseCalls.get())
        assertNotNull(RecoveryCleanClosureBoundary.closeCameraPipeline(harness.session))
        assertEquals(1, cameraDetachCalls.get())
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            harness.closeNative(),
        )
        assertNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(harness.session))
        assertNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(harness.session))
        assertNotNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
    }

    @Test
    fun unverifiableCameraDetachAndMissingExactTeardownCannotMint() {
        val unverifiableCamera =
            object : RecoveryCameraPipelinePlatformPort {
                override fun detachAnalyzerAndUnbindSource() = Unit

                override fun isDetachedAndUnbound(): Boolean = false
            }
        val failedCamera =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 149),
                RouteAttemptOutcome.MEASURED,
                cameraPort = unverifiableCamera,
                trackRepresentativeResources = false,
            )
        assertNull(RecoveryCleanClosureBoundary.closeCameraPipeline(failedCamera.session))
        assertNull(RecoveryCleanClosureBoundary.beginRuntimeClose(failedCamera.session))
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(failedCamera.session))

        val exactBinding =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 150),
                RouteAttemptOutcome.MEASURED,
                trackRepresentativeResources = false,
            )
        val altered =
            exactBinding.committedTeardown.copy(
                lastEpoch = exactBinding.committedTeardown.lastEpoch + 1uL,
            )
        val activePayload =
            exactBinding.committedTeardown.copy(
                active =
                    requireNotNull(exactBinding.committedTeardown.active).copy(
                        state = JournalActiveState.ACTIVE,
                    ),
            )
        assertFalse(
            RecoveryCleanClosureBoundary.bindCommittedTeardown(
                exactBinding.session,
                RecoveryClosureTestFixture.durableTeardownReceipt(activePayload, altered),
            ),
        )
        assertTrue(
            RecoveryCleanClosureBoundary.bindCommittedTeardown(
                exactBinding.session,
                exactBinding.committedTeardownReceipt,
            ),
        )
    }

    @Test
    fun guardedOpenIsConsumedOnceWithoutAReleaseOrRawOpenRetryPath() {
        val key = routeKey(seed = 151)
        val opened =
            RecoveryClosureTestFixture.unboundRuntime(key, RouteAttemptOutcome.MEASURED)
        val render = RecoveryClosureTestFixture.renderOwner()
        val camera = RecoveryClosureTestFixture.cameraPipelineOwner()
        assertNotNull(
            RecoveryCleanClosureBoundary.begin(
                opened.guardedOpen,
                render,
                camera,
            ),
        )
        assertNull(
            RecoveryCleanClosureBoundary.begin(
                opened.guardedOpen,
                RecoveryClosureTestFixture.renderOwner(),
                RecoveryClosureTestFixture.cameraPipelineOwner(),
            ),
        )
        assertTrue(
            RecoveryCleanClosureBoundary::class.java.methods
                .filter { it.name == "begin" }
                .none { method ->
                    method.parameterTypes.any { it.name.endsWith("RuntimeOpenExecution") }
                },
        )
    }

    @Test
    fun staleCrossGenerationThermalCutoffAndUnsafeCurrentStatusFailClosed() {
        val first =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 49),
                RouteAttemptOutcome.MEASURED,
            )
        assertTrue(first.closeExternalOwners())
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            first.closeNative(),
        )
        assertNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(first.session))
        assertNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(first.session))

        val foreign =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 50),
                RouteAttemptOutcome.MEASURED,
            )
        val foreignCutoff =
            requireNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(foreign.session))
        replacePrivateField(first.session, "thermalCutoff", foreignCutoff)
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(first.session))

        val status = AtomicLong(0L)
        val unsafe =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 51),
                RouteAttemptOutcome.MEASURED,
                currentThermalStatus = status,
            )
        assertTrue(unsafe.closeExternalOwners())
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            unsafe.closeNative(),
        )
        assertNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(unsafe.session))
        assertNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(unsafe.session))
        status.set(4L)
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(unsafe.session))
        status.set(0L)
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(unsafe.session))
    }

    @Test
    fun thermalCutoffWaitsForFifoAndCriticalAdmissionLatchesSynchronously() {
        val executor = ManualExecutor()
        val monitor = RecoveryClosureTestFixture.thermalMonitor(executor = executor)
        val measurement =
            requireNotNull(ProcessThermalSafetyBoundary.beginMeasurement(monitor, 900L))
        ProcessThermalSafetyBoundary.executeStatusCallback(monitor, 2)
        val cutoff = requireNotNull(ProcessThermalSafetyBoundary.endMeasurement(monitor, measurement))
        assertFalse(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                monitor,
                measurement,
                cutoff,
                900L,
            ),
        )
        assertEquals(1, executor.pendingCount)
        executor.runAll()
        assertTrue(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                monitor,
                measurement,
                cutoff,
                900L,
            ),
        )

        val criticalExecutor = ManualExecutor()
        val critical = RecoveryClosureTestFixture.thermalMonitor(executor = criticalExecutor)
        val criticalMeasurement =
            requireNotNull(ProcessThermalSafetyBoundary.beginMeasurement(critical, 901L))
        assertThrows(RejectedExecutionException::class.java) {
            ProcessThermalSafetyBoundary.executeStatusCallback(critical, 4)
        }
        assertNull(
            ProcessThermalSafetyBoundary.endMeasurement(critical, criticalMeasurement),
        )
        // The absorbing failure remains state-inert even after a later nominal status.
        ProcessThermalSafetyBoundary.executeStatusCallback(critical, 0)
        assertNull(ProcessThermalSafetyBoundary.beginMeasurement(critical, 902L))
    }

    @Test
    fun postMintCriticalStatusRevokesAuthorityUntilJournalMutation() {
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 152),
                RouteAttemptOutcome.MEASURED,
            )
        val authority = requireNotNull(harness.completeCleanClosure())
        assertThrows(RejectedExecutionException::class.java) {
            ProcessThermalSafetyBoundary.executeStatusCallback(harness.thermal, 4)
        }
        repeat(2) {
            assertRejected(
                RecoveryJournalAttemptReducer.completeCleanIntermediate(
                    harness.committedTeardown,
                    harness.key,
                    authority,
                ),
                RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED,
            )
            ProcessThermalSafetyBoundary.executeStatusCallback(harness.thermal, 0)
        }

        val liveStatus = AtomicLong(0L)
        val liveHarness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 153),
                RouteAttemptOutcome.MEASURED,
                currentThermalStatus = liveStatus,
            )
        val liveAuthority = requireNotNull(liveHarness.completeCleanClosure())
        liveStatus.set(4L)
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                liveHarness.committedTeardown,
                liveHarness.key,
                liveAuthority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED,
        )
        liveStatus.set(0L)
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                liveHarness.committedTeardown,
                liveHarness.key,
                liveAuthority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED,
        )
    }

    @Test
    fun rejectedCloseCompletionDoesNotConsumeRenderOrThermalReceipts() {
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 54),
                RouteAttemptOutcome.MEASURED,
            )
        assertTrue(harness.closeExternalOwners())
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            harness.closeNative(),
        )
        val cutoff =
            requireNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(harness.session))
        val renderClosure =
            requireNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(harness.session))
        val claim = privateField(harness.session, "closeClaim") as ProbeCloseClaim
        val completion =
            privateField(harness.session, "closeCompletion") as ProbeCloseCompletion
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(
                harness.machine.consumeCloseCompletion(completion, claim),
            ).kind,
        )
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
        val generation = RuntimeOwnerBoundary.ownershipGenerationForGenuineOpen(harness.open)
        assertTrue(
            RecoveryRenderOwnerBoundary.consumeClosed(
                harness.render,
                renderClosure,
                generation,
            ),
        )
        assertTrue(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                harness.thermal,
                privateField(harness.session, "thermalMeasurement") as RecoveryThermalMeasurement,
                cutoff,
                generation,
            ),
        )
    }

    @Test
    fun unavailableThermalReaderCannotOpenClosureSession() {
        val unavailable =
            RecoveryClosureTestFixture.thermalMonitor(
                currentStatusReader = IntSupplier { throw IllegalStateException("unavailable") },
                executor = Executor { command -> command.run() },
            )
        assertFalse(ProcessThermalSafetyBoundary.isCurrentSafeForNativeCreate(unavailable))
    }

    @Test
    fun thermalFifoHasOneRunnerExactCapacityAndShutdownAdmissionsAreStateInert() {
        val executor = ManualExecutor()
        val monitor = RecoveryClosureTestFixture.thermalMonitor(executor = executor)
        assertNotNull(ProcessThermalSafetyBoundary.beginMeasurement(monitor, 700L))
        repeat(ProcessThermalSafetyBoundary.CALLBACK_CAPACITY) { status ->
            ProcessThermalSafetyBoundary.executeStatusCallback(monitor, status % 4)
        }
        assertEquals(1, executor.pendingCount)
        assertThrows(RejectedExecutionException::class.java) {
            ProcessThermalSafetyBoundary.executeStatusCallback(monitor, 0)
        }

        val shutdownExecutor = ManualExecutor()
        val shutdown = RecoveryClosureTestFixture.thermalMonitor(executor = shutdownExecutor)
        val shutdownMeasurement =
            requireNotNull(ProcessThermalSafetyBoundary.beginMeasurement(shutdown, 701L))
        assertTrue(ProcessThermalSafetyBoundary.beginProcessShutdown(shutdown))
        ProcessThermalSafetyBoundary.executeStatusCallback(shutdown, 2)
        ProcessThermalSafetyBoundary.executeStatusCallback(shutdown, 4)
        ProcessThermalSafetyBoundary.executeStatusCallback(shutdown, Int.MAX_VALUE)
        // The START endpoint runner was already reserved; shutdown admissions add no work.
        assertEquals(1, shutdownExecutor.pendingCount)
        shutdownExecutor.runAll()
        assertNull(
            ProcessThermalSafetyBoundary.endMeasurement(
                shutdown,
                shutdownMeasurement,
            ),
        )
    }

    @Test
    fun successfulRecoveryClaimCannotBeReleasedOrReclaimed() {
        val opened =
            RecoveryClosureTestFixture.unboundRuntime(
                routeKey(seed = 170),
                RouteAttemptOutcome.MEASURED,
            )
        val firstCamera = RecoveryClosureTestFixture.cameraPipelineOwner()
        val first =
            RecoveryCleanClosureBoundary.begin(
                opened.guardedOpen,
                RecoveryClosureTestFixture.renderOwner(),
                firstCamera,
            )
        assertNotNull(first)
        assertTrue(
            RuntimeOwnerBoundary::class.java.methods.none {
                it.name == "releaseRecoveryClosureClaim" || it.name == "claimRecoveryClosure"
            },
        )
        assertNull(
            RecoveryCleanClosureBoundary.begin(
                opened.guardedOpen,
                RecoveryClosureTestFixture.renderOwner(),
                RecoveryClosureTestFixture.cameraPipelineOwner(),
            ),
        )
        val source =
            requireNotNull(
                RecoveryClosureTestFixture.analyzerSource(
                    firstCamera,
                    RecoveryClosureTestFixture.imageProxy(),
                ),
            )
        try {
            assertNotNull(RecoveryCleanClosureBoundary.ownFrame(requireNotNull(first), source))
        } finally {
            RecoveryClosureTestFixture.finishAnalyzerSource(source)
        }
    }

    @Test
    fun invalidAnalyzerLayoutAndCallerBufferOverloadCannotCloseMeasuredPipeline() {
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 171),
                RouteAttemptOutcome.MEASURED,
                trackRepresentativeResources = false,
            )
        val invalidProxy =
            RecoveryClosureTestFixture.imageProxy(
                width = 2,
                height = 2,
                pixelStride = 3,
            )
        val source =
            requireNotNull(RecoveryClosureTestFixture.analyzerSource(harness.camera, invalidProxy))
        try {
            assertNull(RecoveryCleanClosureBoundary.ownFrame(harness.session, source))
        } finally {
            RecoveryClosureTestFixture.finishAnalyzerSource(source)
        }
        assertTrue(
            RecoveryCleanClosureBoundary::class.java.methods.none { method ->
                method.name == "ownSubmittedInput" || method.name == "ownDirectBuffer" ||
                    method.name == "ownSourceProxy" ||
                    (method.name == "ownFrame" &&
                        method.parameterTypes.any { it == ByteBuffer::class.java })
            },
        )
        // The first invalid analyzer entry sticky-poisons the owner; a later valid shape cannot
        // recover the graph or mint cleanup evidence.
        val later =
            RecoveryClosureTestFixture.analyzerSource(
                harness.camera,
                RecoveryClosureTestFixture.imageProxy(width = 2, height = 2),
            )
        assertNull(later)
        RecoveryClosureTestFixture.mpImage(width = 2, height = 2).close()
        assertNull(RecoveryCleanClosureBoundary.closeCameraPipeline(harness.session))
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
    }

    @Test
    fun detachThenRebindIsCaughtByFreshReadAndPermanentlyPoisonsClosure() {
        var unbound = false
        val port =
            object : RecoveryCameraPipelinePlatformPort {
                override fun detachAnalyzerAndUnbindSource() {
                    unbound = true
                }

                override fun isDetachedAndUnbound(): Boolean = unbound
            }
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 172),
                RouteAttemptOutcome.MEASURED,
                cameraPort = port,
            )
        assertTrue(harness.closeExternalOwners())
        assertEquals(
            RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION,
            harness.closeNative(),
        )
        assertNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(harness.session))
        assertNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(harness.session))
        unbound = false
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
        unbound = true
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
    }

    @Test
    fun callbackQueuedAfterMintClosesConcreteOutputAndRevokesBeforeJournalUse() {
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 174),
                RouteAttemptOutcome.MEASURED,
            )
        val authority = requireNotNull(harness.completeCleanClosure())
        assertTrue(
            RecoveryCleanClosureBoundary.closeLateCallbackOutput(
                harness.session,
                RecoveryClosureTestFixture.mpImage(),
            ),
        )
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                harness.committedTeardown,
                harness.key,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED,
        )
    }

    @Test
    fun renderCloseDisablesQueuedMutationBeforeBoundedBarrierCompletesIt() {
        lateinit var owner: RecoveryRenderOwner
        lateinit var queued: RecoveryRenderCallbackAdmission
        var listenerRemoved = false
        var discardedDuringDrain = false
        var queuedCompletionWasScalarInert = false
        val port =
            object : RecoveryRenderPlatformPort {
                override fun removeListener() {
                    listenerRemoved = true
                }

                override fun drainDiscardThroughDeadline() {
                    check(listenerRemoved)
                    discardedDuringDrain =
                        RecoveryRenderOwnerBoundary.admitMetricCallback(owner) ===
                        RecoveryRenderCallbackAdmission.DISCARD
                    val implementation = owner as Any
                    val scalarNames =
                        listOf(
                            "copiedMetricCallbackCount",
                            "invalidMetricCallbackCount",
                            "reportedDropCount",
                        )
                    val before =
                        scalarNames.associateWith { name ->
                            implementation.javaClass.getDeclaredField(name).also {
                                it.isAccessible = true
                            }.getLong(implementation)
                        }
                    check(RecoveryRenderOwnerBoundary.completeMetricCallback(owner, queued))
                    val after =
                        scalarNames.associateWith { name ->
                            implementation.javaClass.getDeclaredField(name).also {
                                it.isAccessible = true
                            }.getLong(implementation)
                        }
                    queuedCompletionWasScalarInert = before == after
                }
            }
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed = 173),
                RouteAttemptOutcome.MEASURED,
                renderPort = port,
            )
        owner = harness.render
        queued = RecoveryRenderOwnerBoundary.admitMetricCallback(owner)
        assertTrue(queued is RecoveryRenderCallbackAdmission.MUTATION)
        assertNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(harness.session))
        assertTrue(discardedDuringDrain)
        assertTrue(queuedCompletionWasScalarInert)
        assertTrue(
            RecoveryRenderOwnerBoundary.admitMetricCallback(owner) ===
                RecoveryRenderCallbackAdmission.DISCARD,
        )
    }

    @Test
    fun authorityImplementationsExposeNoPublicConstructorAndRawProofIsInsufficient() {
        listOf(
            "com.motionarcade.vision.capability.recovery.RecoveryCleanClosureBoundary\$IssuedRecoveryCleanClosureAuthority",
            "com.motionarcade.vision.capability.recovery.RecoveryPreNativeCreateDenialBoundary\$IssuedRecoveryPreNativeCreateDenialAuthority",
            "com.motionarcade.vision.capability.recovery.ProcessThermalSafetyBoundary\$IssuedProcessThermalSafetyMonitor",
            "com.motionarcade.vision.capability.recovery.RecoveryRenderOwnerBoundary\$IssuedRecoveryRenderOwner",
            "com.motionarcade.vision.capability.recovery.RecoveryCameraPipelineBoundary\$IssuedRecoveryCameraPipelineOwner",
            "com.motionarcade.vision.capability.recovery.RecoveryDurableJournalReceiptBoundary\$IssuedRecoveryDurableActiveJournalReceipt",
            "com.motionarcade.vision.capability.recovery.RecoveryDurableJournalReceiptBoundary\$IssuedRecoveryDurableTeardownJournalReceipt",
            "com.motionarcade.vision.capability.recovery.RecoveryCleanClosureBoundary\$IssuedRecoveryThermallyGuardedRuntimeOpen",
            "com.motionarcade.vision.capability.recovery.RecoveryPostSealPoisonPersistenceBoundary\$IssuedRecoveryDurablePostSealPoisonSink",
            "com.motionarcade.vision.capability.recovery.RecoveryCleanClosureBoundary\$IssuedRecoveryCleanJournalPersistenceFence",
        ).forEach { name ->
            val type = Class.forName(name)
            assertFalse(Modifier.isPublic(type.modifiers))
            assertTrue(
                type.declaredConstructors.none {
                    Modifier.isPublic(it.modifiers) && !it.isSynthetic
                },
            )
        }
        val selfAssertedLifecycleMethods =
            setOf(
                "acquireSourceProxy",
                "acquireSubmittedInput",
                "acquireBuffer",
                "markBufferFullyZeroed",
                "releaseBuffer",
                "closeSourceAdmission",
                "detachAnalyzer",
            )
        assertTrue(
            RecoveryCleanClosureBoundary::class.java.methods.none {
                it.name in selfAssertedLifecycleMethods
            },
        )
        val closureMethods = RecoveryCleanClosureBoundary::class.java.methods.toList()
        assertTrue(
            closureMethods.filter { it.name == "begin" || it.name == "bindCommittedTeardown" }
                .none { method ->
                    method.parameterTypes.any { it == RecoveryJournalPayloadV5::class.java }
                },
        )
        assertTrue(
            closureMethods.single { it.name == "begin" }.parameterTypes
                .contains(RecoveryThermallyGuardedRuntimeOpen::class.java),
        )
        assertTrue(
            closureMethods.single { it.name == "bindCommittedTeardown" }.parameterTypes
                .contains(RecoveryDurableTeardownJournalReceipt::class.java),
        )
        assertTrue(
            RecoveryDurableJournalReceiptBoundary::class.java.methods.none { method ->
                method.name.contains("issue", ignoreCase = true) ||
                    method.name.contains("create", ignoreCase = true) ||
                    method.name.contains("verify", ignoreCase = true)
            },
        )

        val (teardown, key) = teardown(seed = 53)
        val rawProof = RecoveryJournalTestFixtures.proof(key.delegate, key.role)
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(teardown, key, rawProof),
            RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_REQUIRED,
        )
    }

    private fun teardown(seed: Int): Pair<RecoveryJournalPayloadV5, RecoveryAttemptRouteKey> {
        val empty =
            RecoveryJournalPayloadV5.empty(
                recoveryBuildId = RecoveryJournalTestFixtures.runtime(seed),
                mode = RecoveryJournalMode.SOLO,
            )
        val started =
            requireApplied(
                RecoveryJournalAttemptReducer.startInitialFresh(
                    empty,
                    RecoveryJournalTestFixtures.scope(seed),
                ),
            ).next
        val key = requireNotNull(started.active).key()
        val pending =
            requireApplied(
                RecoveryJournalAttemptReducer.markTeardownPending(started, key),
            ).next
        return pending to key
    }

    private fun routeKey(seed: Int): RecoveryAttemptRouteKey =
        RecoveryAttemptRouteKey(
            probeBaseScopeId = RecoveryJournalTestFixtures.scope(seed),
            attemptEpoch = seed.toULong(),
            delegate = ProbeDelegate.CPU,
            role = JournalRouteRole.CANDIDATE,
        )

    private fun ActiveV5.key(): RecoveryAttemptRouteKey =
        RecoveryAttemptRouteKey(
            probeBaseScopeId = probeBaseScopeId,
            attemptEpoch = attemptEpoch,
            delegate = delegate,
            role = role,
        )

    private fun countingImageProxy(closeCalls: AtomicInteger): ImageProxy =
        Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "close" -> {
                    closeCalls.incrementAndGet()
                    null
                }
                "getWidth", "getHeight" -> 1
                "toString" -> "CountingImageProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        } as ImageProxy

    private fun primitiveDefault(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> null
        }

    private fun replacePrivateField(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun privateField(target: Any, fieldName: String): Any {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return requireNotNull(field.get(target))
    }

    private fun requireApplied(
        result: RecoveryAttemptReductionResult,
    ): RecoveryAttemptReductionResult.Applied {
        assertTrue("expected Applied but was $result", result is RecoveryAttemptReductionResult.Applied)
        return result as RecoveryAttemptReductionResult.Applied
    }

    private fun assertRejected(
        result: RecoveryAttemptReductionResult,
        reason: RecoveryAttemptRejection,
    ) {
        assertEquals(RecoveryAttemptReductionResult.Rejected(reason), result)
    }

    private class ManualExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = commands.size

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }
}
