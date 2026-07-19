package com.motionarcade.vision.capability.recovery

import android.view.FrameMetrics
import com.google.mediapipe.framework.image.MPImage
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import com.motionarcade.vision.capability.runtime.CallbackResolution
import com.motionarcade.vision.capability.runtime.FrameAdmission
import com.motionarcade.vision.capability.runtime.ResultAdmission
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary
import com.motionarcade.vision.capability.runtime.RuntimeResultCallback
import com.motionarcade.vision.capability.runtime.RuntimeSubmitAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.IntSupplier
import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryClosureRaceHardeningTest {
    @Test
    fun dummyProvenanceCannotMintClosure() {
        val exact =
            RecoveryClosureTestFixture.harness(
                routeKey(801),
                RouteAttemptOutcome.INCOMPLETE,
                autoDriveOutcome = false,
            )
        assertTrue(completeFirstCallback(exact, substituteDummy = false))
        exact.machine.abortIncomplete()
        assertNotNull(exact.completeCleanClosure())

        val forged =
            RecoveryClosureTestFixture.harness(
                routeKey(802),
                RouteAttemptOutcome.INCOMPLETE,
                autoDriveOutcome = false,
            )
        assertFalse(completeFirstCallback(forged, substituteDummy = true))
        forged.machine.abortIncomplete()
        assertNull(RecoveryCleanClosureBoundary.closeCameraPipeline(forged.session))
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(forged.session))
    }

    @Test
    fun detachAnalyzerEntryRaceClosesLateProxyAndStickyPoisons() {
        val entered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val detached = AtomicBoolean(false)
        val port =
            object : RecoveryCameraPipelinePlatformPort {
                override fun detachAnalyzerAndUnbindSource() {
                    detached.set(true)
                    releaseRead.countDown()
                }

                override fun isDetachedAndUnbound(): Boolean = detached.get()
            }
        val owner = RecoveryClosureTestFixture.cameraPipelineOwner(port, 8_101L)
        val generation = 8_102L
        assertTrue(RecoveryCameraPipelineBoundary.claimForResourceGeneration(owner, generation))
        val proxy =
            RecoveryClosureTestFixture.imageProxy(
                onWidthRead = {
                    entered.countDown()
                    assertTrue(releaseRead.await(2, TimeUnit.SECONDS))
                },
                onClose = { closeCalls.incrementAndGet() },
            )
        val result = AtomicReference<RecoveryCameraFrameLease?>()
        val source = requireNotNull(RecoveryClosureTestFixture.analyzerSource(owner, proxy))
        val analyzer =
            Thread {
                try {
                    result.set(
                        RecoveryCameraPipelineBoundary.ownFrame(owner, generation, source),
                    )
                } finally {
                    RecoveryClosureTestFixture.finishAnalyzerSource(source)
                }
            }
        analyzer.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertNull(
            RecoveryCameraPipelineBoundary.closeForResourceGeneration(
                owner,
                generation,
                RouteAttemptOutcome.INCOMPLETE,
                0,
            ),
        )
        analyzer.join(2_000L)
        assertFalse(analyzer.isAlive)
        assertNull(result.get())
        assertEquals(1, closeCalls.get())
        assertNull(
            RecoveryCameraPipelineBoundary.closeForResourceGeneration(
                owner,
                generation,
                RouteAttemptOutcome.INCOMPLETE,
                0,
            ),
        )
    }

    @Test
    fun detachFencesCallbackBegunBeforeEntryRegistration() {
        val owner = RecoveryClosureTestFixture.cameraPipelineOwner(generation = 8_151L)
        val generation = 8_152L
        assertTrue(RecoveryCameraPipelineBoundary.claimForResourceGeneration(owner, generation))
        val callbackBegan = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val result = AtomicReference<RecoveryCameraFrameLease?>()
        val callback =
            Thread {
                callbackBegan.countDown()
                val source =
                    RecoveryClosureTestFixture.analyzerSource(
                        owner,
                        RecoveryClosureTestFixture.imageProxy(
                            onClose = { closeCalls.incrementAndGet() },
                        ),
                    )
                result.set(
                    source?.let {
                        try {
                            RecoveryCameraPipelineBoundary.ownFrame(owner, generation, it)
                        } finally {
                            RecoveryClosureTestFixture.finishAnalyzerSource(it)
                        }
                    },
                )
            }
        val closure: RecoveryCameraPipelineClosure
        synchronized(owner) {
            callback.start()
            assertTrue(callbackBegan.await(2, TimeUnit.SECONDS))
            // The callback has begun but cannot yet register under the owner gate. Detach closes
            // first; once admitted, that exact late proxy must be disposed and revoke the receipt.
            closure =
                requireNotNull(
                    RecoveryCameraPipelineBoundary.closeForResourceGeneration(
                        owner,
                        generation,
                        RouteAttemptOutcome.INCOMPLETE,
                        0,
                    ),
                )
        }
        callback.join(2_000L)
        assertFalse(callback.isAlive)
        assertNull(result.get())
        assertEquals(1, closeCalls.get())
        assertFalse(RecoveryCameraPipelineBoundary.consumeClosed(owner, closure, generation))
    }

    @Test
    fun lateProxyIsClosedExactlyOnceAndRevokesPriorDetachReceipt() {
        val owner = RecoveryClosureTestFixture.cameraPipelineOwner(generation = 8_201L)
        val generation = 8_202L
        assertTrue(RecoveryCameraPipelineBoundary.claimForResourceGeneration(owner, generation))
        val closure =
            requireNotNull(
                RecoveryCameraPipelineBoundary.closeForResourceGeneration(
                    owner,
                    generation,
                    RouteAttemptOutcome.INCOMPLETE,
                    0,
                ),
            )
        val closeCalls = AtomicInteger()
        val late = RecoveryClosureTestFixture.imageProxy(onClose = { closeCalls.incrementAndGet() })
        assertNull(RecoveryClosureTestFixture.analyzerSource(owner, late))
        assertEquals(1, closeCalls.get())
        assertFalse(RecoveryCameraPipelineBoundary.consumeClosed(owner, closure, generation))
        assertNull(RecoveryClosureTestFixture.analyzerSource(owner, late))
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun sessionWrapperForwardsAnalyzerSourceWhenDetachWinsImmediatelyBeforeEntry() {
        val detachEntered = CountDownLatch(1)
        val detached = AtomicBoolean(false)
        val closeCalls = AtomicInteger()
        val port =
            object : RecoveryCameraPipelinePlatformPort {
                override fun detachAnalyzerAndUnbindSource() {
                    detached.set(true)
                    detachEntered.countDown()
                }

                override fun isDetachedAndUnbound(): Boolean = detached.get()
            }
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(8_251),
                RouteAttemptOutcome.INCOMPLETE,
                cameraPort = port,
                trackRepresentativeResources = false,
            )
        val source =
            requireNotNull(
                RecoveryClosureTestFixture.analyzerSource(
                    harness.camera,
                    RecoveryClosureTestFixture.imageProxy(
                        onClose = { closeCalls.incrementAndGet() },
                    ),
                ),
            )
        val closure = AtomicReference<RecoveryCameraPipelineClosure?>()
        val closer =
            Thread {
                closure.set(RecoveryCleanClosureBoundary.closeCameraPipeline(harness.session))
            }
        closer.start()
        assertTrue(detachEntered.await(2, TimeUnit.SECONDS))

        // The session is still genuine, so this must reach the camera owner despite close having
        // already won its state transition. That exact proxy is closed and prior clean evidence is
        // sticky-poisoned.
        assertNull(RecoveryCleanClosureBoundary.ownFrame(harness.session, source))
        RecoveryClosureTestFixture.finishAnalyzerSource(source)
        closer.join(2_000L)

        assertFalse(closer.isAlive)
        assertNull(closure.get())
        assertEquals(1, closeCalls.get())
        assertNull(RecoveryCleanClosureBoundary.mintCleanClosureAuthority(harness.session))
    }

    @Test
    fun authorityConsumeCommitRaceForcesDurablePostSealPoison() {
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(803),
                RouteAttemptOutcome.MEASURED,
            )
        val authority = requireNotNull(harness.completeCleanClosure())
        val reduction =
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                harness.committedTeardown,
                harness.key,
                authority,
            ) as RecoveryAttemptReductionResult.Applied
        assertNull(reduction.next.active)
        val fence = requireNotNull(reduction.cleanPersistenceFence)

        assertTrue(
            RecoveryCleanClosureBoundary.closeLateCallbackOutput(
                harness.session,
                RecoveryClosureTestFixture.mpImage(),
            ),
        )
        val decision =
            requireNotNull(
                RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(fence),
            )
        assertEquals(JournalActiveState.TEARDOWN_PENDING, decision.payload.active?.state)
        assertTrue(decision.payload.modeControl is ModeControlV5.PostSealModePoison)
        assertNull(RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(fence))
    }

    @Test
    fun preCreateCriticalThermalMakesZeroNativeCalls() {
        val monitor =
            RecoveryClosureTestFixture.thermalMonitor(
                currentStatusReader =
                    IntSupplier { ProcessThermalSafetyBoundary.FIRST_CRITICAL_STATUS },
                executor = Executor { command -> command.run() },
            )
        val attempt = RecoveryClosureTestFixture.attemptGuardedOpen(routeKey(804), monitor)
        assertNull(attempt.guardedOpen)
        assertEquals(0, attempt.nativeCreateCalls)
    }

    @Test
    fun thermalCutoffEqualityPassesAndPlusOneNanosecondFails() {
        assertTrue(thermalCutoffAt(ProcessThermalSafetyBoundary.THERMAL_CUTOFF_DEADLINE_NS))
        assertFalse(
            thermalCutoffAt(
                ProcessThermalSafetyBoundary.THERMAL_CUTOFF_DEADLINE_NS + 1L,
            ),
        )
    }

    @Test
    fun currentThermalReaderReentrancyDoesNotDeadlockAndFailsClosed() {
        val executor = ManualExecutor()
        lateinit var monitor: ProcessThermalSafetyMonitor
        monitor =
            RecoveryClosureTestFixture.thermalMonitor(
                currentStatusReader =
                    IntSupplier {
                        ProcessThermalSafetyBoundary.executeStatusCallback(monitor, 0)
                        0
                    },
                executor = executor,
            )
        val result = AtomicReference<Boolean?>()
        val thread =
            Thread {
                result.set(ProcessThermalSafetyBoundary.isCurrentSafeForNativeCreate(monitor))
            }
        thread.start()
        thread.join(2_000L)
        assertFalse(thread.isAlive)
        assertEquals(false, result.get())
        assertEquals(1, executor.pendingCount)
        executor.runAll()
    }

    @Test
    fun thermalRegistrationSideEffectThenThrowRollsBackAndJoins() {
        assertRegistrationSideEffectRollback()
    }

    @Test
    fun frameMetricsRegistrationSideEffectThenThrowRollsBackAndJoins() {
        assertRegistrationSideEffectRollback()
    }

    @Test
    fun frameMetricsCallbackCopiesOnlyBoundedScalarsAndNeverRetainsPlatformObject() {
        val owner = RecoveryClosureTestFixture.renderOwner()
        val admission = RecoveryRenderOwnerBoundary.admitMetricCallback(owner, 12L, 34L, 2)
        assertTrue(admission is RecoveryRenderCallbackAdmission.MUTATION)
        assertTrue(
            admission.javaClass.declaredFields.none { field ->
                FrameMetrics::class.java.isAssignableFrom(field.type)
            },
        )
        assertTrue(RecoveryRenderOwnerBoundary.completeMetricCallback(owner, admission))
        val invalid = RecoveryRenderOwnerBoundary.admitMetricCallback(owner, Long.MAX_VALUE, -1L, -1)
        assertTrue(invalid is RecoveryRenderCallbackAdmission.MUTATION)
        assertTrue(RecoveryRenderOwnerBoundary.completeMetricCallback(owner, invalid))
        assertTrue(
            Class.forName(
                "com.motionarcade.vision.capability.recovery.RecoveryRenderOwnerBoundary\$AndroidWindowRenderPlatformPort",
            ).declaredFields.none { field ->
                FrameMetrics::class.java.isAssignableFrom(field.type)
            },
        )
    }

    private fun completeFirstCallback(
        harness: RecoveryClosureTestFixture.Harness,
        substituteDummy: Boolean,
    ): Boolean {
        assertTrue(harness.machine.startWarmup())
        val source =
            requireNotNull(
                RecoveryClosureTestFixture.analyzerSource(
                    harness.camera,
                    RecoveryClosureTestFixture.imageProxy(),
                ),
            )
        val frame =
            try {
                requireNotNull(RecoveryCleanClosureBoundary.ownFrame(harness.session, source))
            } finally {
                RecoveryClosureTestFixture.finishAnalyzerSource(source)
            }
        assertNotNull(RecoveryCleanClosureBoundary.buildSubmittedInput(harness.session, frame))
        val reservation =
            (harness.machine.reserveFrame(0L, source(0L)) as FrameAdmission.Reserved).reservation
        val authorization = requireNotNull(harness.machine.startSubmission(reservation.token))
        val execution =
            requireNotNull(
                RecoveryCleanClosureBoundary.submitFrame(
                    harness.session,
                    frame,
                    authorization,
                ),
            )
        assertTrue(
            harness.machine.admitSubmitExecution(execution) is RuntimeSubmitAdmission.Returned,
        )
        assertTrue(RecoveryCleanClosureBoundary.completeReturnedSubmission(harness.session, frame))
        val callback =
            RuntimeResultCallback(
                runtimeIdentity = harness.runtimeIdentity,
                taskTimestampMs = reservation.submission.taskTimestampMs.value,
                poseCount = 1,
            )
        val realOutput = RecoveryClosureTestFixture.mpImage()
        harness.nativeCallbacks.onResultWithOutput(callback, realOutput)
        val result = harness.machine.admitResult(callback) as ResultAdmission.Reserved
        if (substituteDummy) {
            val copiedCallback =
                RuntimeResultCallback(
                    runtimeIdentity = harness.runtimeIdentity,
                    taskTimestampMs = reservation.submission.taskTimestampMs.value,
                    poseCount = 1,
                )
            assertNull(RuntimeOwnerBoundary.claimCallbackOutput(harness.open, copiedCallback, realOutput))
            val dummy = RecoveryClosureTestFixture.mpImage()
            assertNull(RuntimeOwnerBoundary.claimCallbackOutput(harness.open, callback, dummy))
            val rejected =
                RecoveryCleanClosureBoundary.completeCallbackOutput(
                    harness.session,
                    frame,
                    requireNotNull(result.cleanup).token,
                    callback,
                    dummy,
                )
            realOutput.close()
            assertNull(rejected)
            return false
        }
        val resolution =
            RecoveryCleanClosureBoundary.completeCallbackOutput(
                harness.session,
                frame,
                requireNotNull(result.cleanup).token,
                callback,
                realOutput,
            )
        assertTrue(resolution is CallbackResolution.Completed)
        assertTrue(RecoveryCleanClosureBoundary.closeCompletedFrame(harness.session, frame))
        return true
    }

    private fun thermalCutoffAt(reachedAtNs: Long): Boolean {
        val clock = AtomicLong(0L)
        val executor = ManualExecutor()
        val monitor =
            RecoveryClosureTestFixture.thermalMonitor(
                currentStatusReader = IntSupplier { 0 },
                executor = executor,
                nanoTimeReader = LongSupplier { clock.get() },
            )
        val generation = 8_301L + reachedAtNs.coerceAtMost(10L)
        val measurement =
            requireNotNull(ProcessThermalSafetyBoundary.beginMeasurement(monitor, generation))
        executor.runAll()
        val cutoff =
            requireNotNull(ProcessThermalSafetyBoundary.endMeasurement(monitor, measurement))
        clock.set(reachedAtNs)
        executor.runAll()
        return ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
            monitor,
            measurement,
            cutoff,
            generation,
        )
    }

    private fun assertRegistrationSideEffectRollback() {
        val installed = AtomicBoolean(false)
        val removed = AtomicBoolean(false)
        val joined = AtomicBoolean(false)
        assertFalse(
            SideEffectRegistrationRollbackBoundary.register(
                registerMayHaveSideEffect = {
                    installed.set(true)
                    throw IllegalStateException("side effect then throw")
                },
                rollbackExactSideEffect = {
                    removed.set(installed.get())
                    throw IllegalStateException("remove also uncertain")
                },
                terminateAndJoinBounded = {
                    joined.set(true)
                    true
                },
            ),
        )
        assertTrue(installed.get())
        assertTrue(removed.get())
        assertTrue(joined.get())
    }

    private fun source(value: Long): SourceTimestampNs =
        when (val result = SourceTimestampNs.from(value)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error(result.violations.toString())
        }

    private fun routeKey(seed: Int): RecoveryAttemptRouteKey =
        RecoveryAttemptRouteKey(
            probeBaseScopeId = RecoveryJournalTestFixtures.scope(seed),
            attemptEpoch = seed.toULong(),
            delegate = ProbeDelegate.CPU,
            role = JournalRouteRole.CANDIDATE,
        )

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
