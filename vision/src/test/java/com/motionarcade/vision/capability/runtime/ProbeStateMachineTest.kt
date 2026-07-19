package com.motionarcade.vision.capability.runtime

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeStateMachineTest {
    private val identity = RuntimeIdentity(7L)
    private val routeKey = RuntimeRouteKey(
        ProbeAttemptEpoch(17uL),
        routeOrdinal = 1,
        runtimeGeneration = RuntimeGeneration(1L),
    )
    private val openedByMachine = IdentityHashMap<ProbeStateMachine, RuntimeOpenExecution>()

    @Test
    fun warmupRequiresFiveSuccessfulCorrelatedCallbacksNotSubmissions() {
        val clock = FakeClock()
        val machine = machine(clock)
        assertTrue(machine.startWarmup())

        val first = reserve(machine, frameId = 0L)
        val firstCommand = startSubmission(machine, first)
        assertTrue(admitReturned(machine, firstCommand) is RuntimeSubmitAdmission.Returned)
        assertEquals(0, machine.successfulWarmupCallbacks)

        val result = machine.admitResult(callback(first))
        assertTrue(result is ResultAdmission.Reserved)
        machine.completeCallbackOutput(cleanupToken(result), CallbackOutputCleanupEvidence.CLOSED)
        assertEquals(1, machine.successfulWarmupCallbacks)

        for (
            index in 1L until
                ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS.toLong()
        ) {
            completeFrame(machine, index)
        }
        assertEquals(
            ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS,
            machine.successfulWarmupCallbacks,
        )
        assertEquals(ProbeMachineState.AWAITING_MEASUREMENT, machine.state)
    }

    @Test
    fun stateGateDerivesFirstZeroAndMonotonicTaskTimestampsFromTypedSource() {
        val machine = machine(FakeClock())
        machine.startWarmup()

        val first = reserve(machine, frameId = 0L, sourceTimestampNs = 8_000_000_000L)
        assertEquals(0L, first.submission.taskTimestampMs.value)
        assertEquals(0L, first.submission.packetTimestampUs)
        completeReserved(machine, first)

        val second = reserve(machine, frameId = 1L, sourceTimestampNs = 8_000_000_100L)
        assertEquals(1L, second.submission.taskTimestampMs.value)
        assertEquals(1_000L, second.submission.packetTimestampUs)

        val reserveParameters = ProbeStateMachine::class.java.methods
            .single { it.name.startsWith("reserveFrame") && it.parameterCount == 2 }
            .parameterTypes
        assertEquals(listOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType), reserveParameters.toList())
        // SourceTimestampNs is an inline value at the JVM boundary; there is no caller task-timestamp parameter.
    }

    @Test
    fun candidateWindowIsExactEndExclusiveAndDrainExpiresAtPlusOne() {
        val clock = FakeClock()
        val machine = warmedMachine(clock, RuntimeRole.CANDIDATE)
        clock.nowNs = 2_000_000_000L
        completeFrame(machine, frameId = 5L)
        val start = requireNotNull(machine.measurementStartNs)
        val end = requireNotNull(machine.measurementEndNs)
        assertEquals(ProbeStateMachine.CANDIDATE_DURATION_NS, end - start)

        clock.nowNs = end
        assertTrue(machine.reserveFrame(6L, source(6L)) is FrameAdmission.OutsideMeasurementWindow)
        assertEquals(ProbeMachineState.DRAINING, machine.state)

        clock.nowNs = end + ProbeStateMachine.MEASUREMENT_DRAIN_NS
        machine.runWatchdog()
        assertNull(machine.pendingCloseOutcome())
        clock.nowNs += 1L
        machine.runWatchdog()
        assertEquals(RouteAttemptOutcome.MEASURED, machine.pendingCloseOutcome())
    }

    @Test
    fun submissionDeadlineAllowsEqualityAndExpiresAtPlusOne() {
        val equalityClock = FakeClock()
        val equality = machine(equalityClock)
        equality.startWarmup()
        val equalityFrame = reserve(equality, 0L)
        val equalityCommand = startSubmission(equality, equalityFrame)
        equalityClock.nowNs = ProbeStateMachine.SUBMISSION_RETURN_DEADLINE_NS
        val equalityAdmission = admitReturned(equality, equalityCommand)
        assertTrue(equalityAdmission is RuntimeSubmitAdmission.Returned)
        assertEquals(
            RuntimeInputCleanupOwner.SUBMIT_CALLER,
            equalityAdmission.inputCleanup.owner,
        )

        val expiryClock = FakeClock()
        val expiry = machine(expiryClock)
        expiry.startWarmup()
        val expiryFrame = reserve(expiry, 0L)
        val expiryCommand = startSubmission(expiry, expiryFrame)
        expiryClock.nowNs = ProbeStateMachine.SUBMISSION_RETURN_DEADLINE_NS + 1L
        val expiryAdmission = admitReturned(expiry, expiryCommand)
        assertTrue(expiryAdmission is RuntimeSubmitAdmission.DeadlineExpired)
        assertEquals(
            RuntimeInputCleanupOwner.SUBMISSION_TIMEOUT,
            expiryAdmission.inputCleanup.owner,
        )
        assertEquals(ProbeMachineState.QUARANTINED, expiry.state)
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, expiry.pendingCloseOutcome())
    }

    @Test
    fun lateCallbackAndWatchdogConvergeInBothOrdersAfterCleanup() {
        listOf(false, true).forEach { watchdogFirst ->
            val clock = FakeClock()
            val machine = machine(clock)
            machine.startWarmup()
            val frame = reserve(machine, 0L)
            admitReturned(machine, startSubmission(machine, frame))
            clock.nowNs = ProbeStateMachine.RESULT_CALLBACK_DEADLINE_NS + 1L

            if (watchdogFirst) machine.runWatchdog()
            val late = machine.admitResult(callback(frame))
            assertTrue(late is ResultAdmission.Rejected)
            assertEquals(
                ProbeFailureReason.RESULT_DEADLINE_EXPIRED,
                (late as ResultAdmission.Rejected).reason,
            )
            assertNull(machine.pendingCloseOutcome())
            val cleanup = machine.completeCallbackOutput(
                cleanupToken(late),
                CallbackOutputCleanupEvidence.CLOSED,
            )

            assertTrue(cleanup is CallbackResolution.StateInert)
            assertEquals(ProbeMachineState.CLEAN_TERMINAL_PENDING_CLOSE, machine.state)
            assertEquals(RouteAttemptOutcome.CLEAN_TERMINAL, machine.pendingCloseOutcome())
        }
    }

    @Test
    fun equalityCallbackAndWatchdogRaceStillAdmitsExactlyOnce() {
        val clock = BlockingClock()
        val machine = machine(clock)
        machine.startWarmup()
        val frame = reserve(machine, 0L)
        admitReturned(machine, startSubmission(machine, frame))
        clock.nowNs = ProbeStateMachine.RESULT_CALLBACK_DEADLINE_NS
        clock.blockNextCaptures(2)

        val go = CountDownLatch(1)
        val admission = AtomicReference<ResultAdmission>()
        val callbackThread = Thread {
            go.await()
            admission.set(machine.admitResult(callback(frame)))
        }.apply { isDaemon = true }
        val watchdogThread = Thread {
            go.await()
            machine.runWatchdog()
        }.apply { isDaemon = true }
        callbackThread.start()
        watchdogThread.start()
        go.countDown()
        try {
            assertTrue(clock.awaitBlocked())
        } finally {
            clock.releaseCapture()
        }
        joinWithin(callbackThread, 5_000L)
        joinWithin(watchdogThread, 5_000L)

        val reserved = admission.get()
        assertTrue(reserved is ResultAdmission.Reserved)
        machine.completeCallbackOutput(
            cleanupToken(requireNotNull(reserved)),
            CallbackOutputCleanupEvidence.CLOSED,
        )
        assertEquals(1, machine.successfulWarmupCallbacks)
    }

    @Test
    fun plusOneCallbackAndWatchdogRaceHonorsBothCommitOrders() {
        listOf(true, false).forEach { watchdogCommitsFirst ->
            val callbackEntered = CountDownLatch(1)
            val callbackRelease = CountDownLatch(1)
            val watchdogEntered = CountDownLatch(1)
            val watchdogRelease = CountDownLatch(1)
            val deadline = ProbeStateMachine.RESULT_CALLBACK_DEADLINE_NS
            val fallbackNow = AtomicReference(0L)
            val clock = ProbeClock {
                when (Thread.currentThread().name) {
                    "ordered-callback" -> {
                        callbackEntered.countDown()
                        callbackRelease.await()
                        deadline
                    }
                    "ordered-watchdog" -> {
                        watchdogEntered.countDown()
                        watchdogRelease.await()
                        deadline + 1L
                    }
                    else -> fallbackNow.get()
                }
            }
            val machine = machine(clock)
            machine.startWarmup()
            val frame = reserve(machine, 0L)
            admitReturned(machine, startSubmission(machine, frame))
            val admission = AtomicReference<ResultAdmission>()
            val callbackThread = Thread {
                admission.set(machine.admitResult(callback(frame)))
            }.apply {
                isDaemon = true
                name = "ordered-callback"
            }
            val watchdogThread = Thread { machine.runWatchdog() }.apply {
                isDaemon = true
                name = "ordered-watchdog"
            }

            fallbackNow.set(deadline + 1L)
            callbackThread.start()
            watchdogThread.start()
            assertTrue(callbackEntered.await(5L, TimeUnit.SECONDS))
            assertTrue(watchdogEntered.await(5L, TimeUnit.SECONDS))
            if (watchdogCommitsFirst) {
                watchdogRelease.countDown()
                joinWithin(watchdogThread, 5_000L)
                callbackRelease.countDown()
                joinWithin(callbackThread, 5_000L)
                val rejected = admission.get() as ResultAdmission.Rejected
                assertEquals(ProbeFailureReason.RESULT_DEADLINE_EXPIRED, rejected.reason)
                assertTrue(
                    machine.completeCallbackOutput(
                        cleanupToken(rejected),
                        CallbackOutputCleanupEvidence.CLOSED,
                    ) is CallbackResolution.StateInert,
                )
                assertEquals(0, machine.successfulWarmupCallbacks)
                assertEquals(RouteAttemptOutcome.CLEAN_TERMINAL, machine.pendingCloseOutcome())
            } else {
                callbackRelease.countDown()
                joinWithin(callbackThread, 5_000L)
                val reserved = admission.get()
                assertTrue(reserved is ResultAdmission.Reserved)
                watchdogRelease.countDown()
                joinWithin(watchdogThread, 5_000L)
                assertTrue(
                    machine.completeCallbackOutput(
                        cleanupToken(requireNotNull(reserved)),
                        CallbackOutputCleanupEvidence.CLOSED,
                    ) is CallbackResolution.Completed,
                )
                assertEquals(1, machine.successfulWarmupCallbacks)
                assertNull(machine.pendingCloseOutcome())
            }
        }
    }

    @Test
    fun concurrentDuplicateCallbacksSerializeToOneReservationAndTwoCleanups() {
        val clock = BlockingClock()
        val machine = machine(clock)
        machine.startWarmup()
        val frame = reserve(machine, 0L)
        admitReturned(machine, startSubmission(machine, frame))
        clock.blockNextCaptures(2)
        val first = AtomicReference<ResultAdmission>()
        val second = AtomicReference<ResultAdmission>()
        val firstThread = Thread {
            first.set(machine.admitResult(callback(frame)))
        }.apply { isDaemon = true }
        val secondThread = Thread {
            second.set(machine.admitResult(callback(frame)))
        }.apply { isDaemon = true }

        firstThread.start()
        secondThread.start()
        try {
            assertTrue(clock.awaitBlocked())
        } finally {
            clock.releaseCapture()
        }
        joinWithin(firstThread, 5_000L)
        joinWithin(secondThread, 5_000L)

        val results = listOf(requireNotNull(first.get()), requireNotNull(second.get()))
        val reserved = results.single { it is ResultAdmission.Reserved }
        val duplicate = results.single { it is ResultAdmission.Rejected } as ResultAdmission.Rejected
        assertEquals(ProbeFailureReason.STALE_OR_DUPLICATE_RESULT, duplicate.reason)
        assertEquals(2, machine.pendingCallbackCleanupCount())
        assertTrue(
            machine.completeCallbackOutput(
                cleanupToken(duplicate),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.StateInert,
        )
        assertTrue(
            machine.completeCallbackOutput(
                cleanupToken(reserved),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.Completed,
        )
        assertEquals(0, machine.pendingCallbackCleanupCount())
        assertEquals(1, machine.successfulWarmupCallbacks)
    }

    @Test
    fun staleDuplicateAndPreReturnOutputsAlwaysReceiveCleanupReservations() {
        val duplicateMachine = machine(FakeClock())
        duplicateMachine.startWarmup()
        val completed = completeFrame(duplicateMachine, 0L)
        val duplicate = duplicateMachine.admitResult(callback(completed))

        assertTrue(duplicate is ResultAdmission.Rejected)
        assertEquals(1, duplicateMachine.pendingCallbackCleanupCount())
        assertTrue(
            duplicateMachine.completeCallbackOutput(
                cleanupToken(duplicate),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.StateInert,
        )
        assertEquals(1, duplicateMachine.successfulWarmupCallbacks)
        assertEquals(ProbeMachineState.WARMUP, duplicateMachine.state)

        val earlyMachine = machine(FakeClock())
        earlyMachine.startWarmup()
        val earlyFrame = reserve(earlyMachine, 0L)
        val earlyCommand = startSubmission(earlyMachine, earlyFrame)
        val early = earlyMachine.admitResult(callback(earlyFrame))
        assertEquals(
            ProbeFailureReason.RESULT_BEFORE_SUBMISSION_RETURN,
            (early as ResultAdmission.Rejected).reason,
        )
        assertTrue(earlyMachine.submissionExternalCallInFlight())
        assertNull(earlyMachine.pendingCloseOutcome())
        earlyMachine.completeCallbackOutput(cleanupToken(early), CallbackOutputCleanupEvidence.CLOSED)
        assertNull(earlyMachine.pendingCloseOutcome())
        val returned = admitReturned(earlyMachine, earlyCommand)
        assertTrue(returned is RuntimeSubmitAdmission.Returned)
        assertEquals(RuntimeInputCleanupOwner.SUBMIT_CALLER, returned.inputCleanup.owner)
        assertEquals(RouteAttemptOutcome.INCOMPLETE, earlyMachine.pendingCloseOutcome())
    }

    @Test
    fun cleanupFailureQuarantinesMatchedAndStateInertCallbacks() {
        val matched = machine(FakeClock())
        matched.startWarmup()
        val matchedFrame = reserve(matched, 0L)
        admitReturned(matched, startSubmission(matched, matchedFrame))
        val admitted = matched.admitResult(callback(matchedFrame))
        matched.completeCallbackOutput(
            cleanupToken(admitted),
            CallbackOutputCleanupEvidence.CLOSE_THREW,
        )
        assertEquals(ProbeMachineState.QUARANTINED, matched.state)
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, matched.pendingCloseOutcome())

        val inert = machine(FakeClock())
        inert.startWarmup()
        val frame = completeFrame(inert, 0L)
        val duplicate = inert.admitResult(callback(frame))
        inert.completeCallbackOutput(
            cleanupToken(duplicate),
            CallbackOutputCleanupEvidence.CLOSE_TIMED_OUT,
        )
        assertEquals(ProbeMachineState.QUARANTINED, inert.state)
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, inert.pendingCloseOutcome())
    }

    @Test
    fun callbackDisposalAllowsEqualityAndPlusOneQuarantines() {
        val equalityClock = FakeClock()
        val equality = machine(equalityClock)
        equality.startWarmup()
        val equalityFrame = reserveSubmit(equality)
        val equalityResult = equality.admitResult(callback(equalityFrame))
        equalityClock.nowNs = ProbeStateMachine.CALLBACK_DISPOSAL_DEADLINE_NS
        assertTrue(
            equality.completeCallbackOutput(
                cleanupToken(equalityResult),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.Completed,
        )

        val expiryClock = FakeClock()
        val expiry = machine(expiryClock)
        expiry.startWarmup()
        val expiryFrame = reserveSubmit(expiry)
        val expiryResult = expiry.admitResult(callback(expiryFrame))
        expiryClock.nowNs = ProbeStateMachine.CALLBACK_DISPOSAL_DEADLINE_NS + 1L
        assertTrue(
            expiry.completeCallbackOutput(
                cleanupToken(expiryResult),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.Rejected,
        )
        assertEquals(ProbeMachineState.QUARANTINED, expiry.state)
    }

    @Test
    fun cleanupTokenMaxBoundaryExhaustsOnceAndNeverReuses() {
        val machine = machine(FakeClock())
        setPrivateLong(machine, "nextCleanupToken", Long.MAX_VALUE - 1L)
        assertTrue(machine.startWarmup())
        val unboundCallback = RuntimeResultCallback(identity, taskTimestampMs = 0L, poseCount = 1)

        val beforeMax = machine.admitResult(unboundCallback)
        assertTrue(beforeMax is ResultAdmission.Rejected)
        assertEquals(Long.MAX_VALUE - 1L, cleanupToken(beforeMax).value)
        assertTrue(
            machine.completeCallbackOutput(
                cleanupToken(beforeMax),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.StateInert,
        )

        val exactMax = machine.admitResult(unboundCallback)
        assertTrue(exactMax is ResultAdmission.Rejected)
        assertEquals(Long.MAX_VALUE, cleanupToken(exactMax).value)
        assertTrue(
            machine.completeCallbackOutput(
                cleanupToken(exactMax),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.StateInert,
        )

        val exhausted = machine.admitResult(unboundCallback)
        assertTrue(exhausted is ResultAdmission.CleanupUnavailable)
        assertNull(exhausted.cleanup)
        assertEquals(ProbeFailureReason.CLEANUP_TOKEN_EXHAUSTED, machine.failureReason)
        assertEquals(ProbeMachineState.QUARANTINED, machine.state)
        assertEquals(0, machine.pendingCallbackCleanupCount())
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())

        val repeated = machine.admitResult(unboundCallback)
        assertTrue(repeated is ResultAdmission.CleanupUnavailable)
        assertNull(repeated.cleanup)
        assertEquals(ProbeFailureReason.CLEANUP_TOKEN_EXHAUSTED, machine.failureReason)
        assertEquals(0, machine.pendingCallbackCleanupCount())
    }

    @Test
    fun abortDuringReservedCleanupPreservesCleanupAndSuppressesCounts() {
        val machine = machine(FakeClock())
        machine.startWarmup()
        val frame = reserveSubmit(machine)
        val admitted = machine.admitResult(callback(frame))

        machine.abortIncomplete()

        assertEquals(1, machine.pendingCallbackCleanupCount())
        assertNull(machine.pendingCloseOutcome())
        val cleanup = machine.completeCallbackOutput(
            cleanupToken(admitted),
            CallbackOutputCleanupEvidence.CLOSED,
        )
        assertTrue(cleanup is CallbackResolution.StateInert)
        assertEquals(0, machine.successfulWarmupCallbacks)
        assertEquals(RouteAttemptOutcome.INCOMPLETE, machine.pendingCloseOutcome())
    }

    @Test
    fun abortDuringCleanupClockCaptureConsumesClosedEvidenceAndAllowsClose() {
        val clock = BlockingClock()
        val machine = machine(clock)
        machine.startWarmup()
        val frame = reserveSubmit(machine)
        val admitted = machine.admitResult(callback(frame))
        val resolution = AtomicReference<CallbackResolution>()
        clock.blockNextCapture()
        val cleanupThread = Thread {
            resolution.set(machine.completeCallbackOutput(
                cleanupToken(admitted),
                CallbackOutputCleanupEvidence.CLOSED,
            ))
        }.apply { isDaemon = true }

        cleanupThread.start()
        assertTrue(clock.awaitBlocked())
        machine.abortIncomplete()
        clock.releaseCapture()
        joinWithin(cleanupThread, 5_000L)

        val inert = resolution.get() as CallbackResolution.StateInert
        assertEquals(ProbeFailureReason.ATTEMPT_ABORTED, inert.reason)
        assertEquals(0, machine.pendingCallbackCleanupCount())
        assertEquals(0, machine.successfulWarmupCallbacks)
        assertEquals(RouteAttemptOutcome.INCOMPLETE, machine.pendingCloseOutcome())
        val claim = requireNotNull(machine.beginClose())
        val execution = ownerClose(machine, claim)
        val completion = requireNotNull(machine.completeClose(claim, execution))
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(machine.consumeCloseCompletion(completion, claim)).kind,
        )
    }

    @Test
    fun errorDuringCleanupClockCaptureConsumesClosedEvidenceAndAllowsRecoveryClose() {
        val clock = BlockingClock()
        val machine = machine(clock)
        machine.startWarmup()
        val frame = reserveSubmit(machine)
        val admitted = machine.admitResult(callback(frame))
        val resolution = AtomicReference<CallbackResolution>()
        clock.blockNextCapture()
        val cleanupThread = Thread {
            resolution.set(machine.completeCallbackOutput(
                cleanupToken(admitted),
                CallbackOutputCleanupEvidence.CLOSED,
            ))
        }.apply { isDaemon = true }

        cleanupThread.start()
        assertTrue(clock.awaitBlocked())
        machine.onErrorListener(RuntimeErrorCallback(identity))
        clock.releaseCapture()
        joinWithin(cleanupThread, 5_000L)

        val inert = resolution.get() as CallbackResolution.StateInert
        assertEquals(ProbeFailureReason.ERROR_LISTENER_RESOURCE_UNCERTAIN, inert.reason)
        assertEquals(0, machine.pendingCallbackCleanupCount())
        assertEquals(0, machine.successfulWarmupCallbacks)
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())
        val claim = requireNotNull(machine.beginClose())
        val execution = ownerClose(machine, claim)
        val completion = requireNotNull(machine.completeClose(claim, execution))
        val record = requireNotNull(machine.consumeCloseCompletion(completion, claim))
        assertEquals(ProbeCloseCompletionKind.RECOVERY_REQUIRED, record.kind)
        assertEquals(ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION, record.failureReason)
    }

    @Test
    fun postFreezeCallbackIsStateInertButTemporarilyBlocksClose() {
        val clock = FakeClock()
        val machine = warmedMachine(clock, RuntimeRole.CANDIDATE)
        completeFrame(machine, 5L)
        val end = requireNotNull(machine.measurementEndNs)
        clock.nowNs = end
        machine.reserveFrame(6L, source(6L))
        clock.nowNs = end + ProbeStateMachine.MEASUREMENT_DRAIN_NS + 1L
        machine.runWatchdog()
        assertEquals(RouteAttemptOutcome.MEASURED, machine.pendingCloseOutcome())

        val late = machine.admitResult(
            RuntimeResultCallback(identity, taskTimestampMs = 5L, poseCount = 1),
        )
        assertTrue(late is ResultAdmission.Rejected)
        assertNull(machine.pendingCloseOutcome())
        machine.completeCallbackOutput(cleanupToken(late), CallbackOutputCleanupEvidence.CLOSED)
        assertEquals(ProbeMachineState.MEASUREMENT_COMPLETE_PENDING_CLOSE, machine.state)
        assertEquals(RouteAttemptOutcome.MEASURED, machine.pendingCloseOutcome())
        assertEquals(1, machine.measurementCompletions)
    }

    @Test
    fun postFreezeAbortIsDiagnosticAndDoesNotInvalidateCleanupOrMeasuredOutcome() {
        val clock = BlockingClock()
        val machine = terminalMeasuredMachine(clock)
        val late = machine.admitResult(
            RuntimeResultCallback(identity, taskTimestampMs = 99L, poseCount = 0),
        )
        val resolution = AtomicReference<CallbackResolution>()
        clock.blockNextCapture()
        val cleanupThread = Thread {
            resolution.set(machine.completeCallbackOutput(
                cleanupToken(late),
                CallbackOutputCleanupEvidence.CLOSED,
            ))
        }.apply { isDaemon = true }

        cleanupThread.start()
        assertTrue(clock.awaitBlocked())
        machine.abortIncomplete()
        clock.releaseCapture()
        joinWithin(cleanupThread, 5_000L)

        assertTrue(resolution.get() is CallbackResolution.StateInert)
        assertEquals(ProbeMachineState.MEASUREMENT_COMPLETE_PENDING_CLOSE, machine.state)
        assertNull(machine.failureReason)
        assertEquals(0, machine.pendingCallbackCleanupCount())
        assertEquals(RouteAttemptOutcome.MEASURED, machine.pendingCloseOutcome())
    }

    @Test
    fun warmupWholePhaseDeadlineWinsAfterReservedCleanup() {
        val clock = FakeClock()
        val machine = machine(clock)
        machine.startWarmup()
        clock.nowNs = 9_500_000_000L
        val frame = reserveSubmit(machine)
        clock.nowNs = ProbeStateMachine.WARMUP_DEADLINE_NS
        val admission = machine.admitResult(callback(frame))
        assertTrue(admission is ResultAdmission.Reserved)

        clock.nowNs += 1L
        val resolution = machine.completeCallbackOutput(
            cleanupToken(admission),
            CallbackOutputCleanupEvidence.CLOSED,
        )

        assertTrue(resolution is CallbackResolution.StateInert)
        assertEquals(0, machine.successfulWarmupCallbacks)
        assertEquals(RouteAttemptOutcome.INCOMPLETE, machine.pendingCloseOutcome())
    }

    @Test
    fun errorAndAbortBeforeOrAfterSubmitReturnRespectTheInFlightCloseFence() {
        data class EventCase(
            val apply: (ProbeStateMachine) -> Unit,
            val expectedOutcome: RouteAttemptOutcome,
        )

        val cases = listOf(
            EventCase(
                apply = { it.onErrorListener(RuntimeErrorCallback(identity)) },
                expectedOutcome = RouteAttemptOutcome.RESOURCE_UNCERTAIN,
            ),
            EventCase(
                apply = { it.abortIncomplete() },
                expectedOutcome = RouteAttemptOutcome.INCOMPLETE,
            ),
        )

        cases.forEach { case ->
            val beforeReturn = machine(FakeClock())
            beforeReturn.startWarmup()
            val beforeFrame = reserve(beforeReturn, 0L)
            val beforeCommand = startSubmission(beforeReturn, beforeFrame)

            case.apply(beforeReturn)

            assertTrue(beforeReturn.submissionExternalCallInFlight())
            assertNull(beforeReturn.pendingCloseOutcome())
            val beforeAdmission = admitReturned(beforeReturn, beforeCommand)
            assertTrue(beforeAdmission is RuntimeSubmitAdmission.Returned)
            assertEquals(
                RuntimeInputCleanupOwner.SUBMIT_CALLER,
                beforeAdmission.inputCleanup.owner,
            )
            assertEquals(case.expectedOutcome, beforeReturn.pendingCloseOutcome())

            val afterReturn = machine(FakeClock())
            afterReturn.startWarmup()
            val afterFrame = reserve(afterReturn, 0L)
            val afterCommand = startSubmission(afterReturn, afterFrame)
            assertTrue(admitReturned(afterReturn, afterCommand) is RuntimeSubmitAdmission.Returned)

            case.apply(afterReturn)

            assertTrue(!afterReturn.submissionExternalCallInFlight())
            assertEquals(case.expectedOutcome, afterReturn.pendingCloseOutcome())
        }
    }

    @Test
    fun watchdogBeforeSubmitReturnTransfersInputCleanupButNeverAllowsEarlyClose() {
        val clock = FakeClock()
        val machine = machine(clock)
        machine.startWarmup()
        val frame = reserve(machine, 0L)
        val command = startSubmission(machine, frame)
        clock.nowNs = ProbeStateMachine.SUBMISSION_RETURN_DEADLINE_NS + 1L

        machine.runWatchdog()

        assertTrue(machine.submissionExternalCallInFlight())
        assertNull(machine.pendingCloseOutcome())
        val admission = admitReturned(machine, command)
        assertTrue(admission is RuntimeSubmitAdmission.DeadlineExpired)
        assertEquals(
            RuntimeInputCleanupOwner.SUBMISSION_TIMEOUT,
            admission.inputCleanup.owner,
        )
        assertTrue(!machine.submissionExternalCallInFlight())
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())
    }

    @Test
    fun clockFailureInvalidatesAConcurrentStartSubmissionTicket() {
        val startEntered = CountDownLatch(1)
        val startRelease = CountDownLatch(1)
        val clock = ProbeClock {
            when (Thread.currentThread().name) {
                "pending-start-submission" -> {
                    startEntered.countDown()
                    startRelease.await()
                    0L
                }
                "failing-watchdog" -> throw IllegalStateException("clock failure")
                else -> 0L
            }
        }
        val machine = machine(clock)
        assertTrue(machine.startWarmup())
        val frame = reserve(machine, 0L)
        val authorization = AtomicReference<RuntimeSubmitAuthorization>()
        val startThread = Thread {
            authorization.set(machine.startSubmission(frame.token))
        }.apply {
            isDaemon = true
            name = "pending-start-submission"
        }
        val watchdogThread = Thread { machine.runWatchdog() }.apply {
            isDaemon = true
            name = "failing-watchdog"
        }

        startThread.start()
        assertTrue(startEntered.await(5L, TimeUnit.SECONDS))
        watchdogThread.start()
        joinWithin(watchdogThread, 5_000L)
        assertEquals(ProbeMachineState.QUARANTINED, machine.state)
        assertEquals(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW, machine.failureReason)
        startRelease.countDown()
        joinWithin(startThread, 5_000L)

        assertNull(authorization.get())
        assertFalse(machine.submissionExternalCallInFlight())
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())
    }

    @Test
    fun cleanupQuarantineSuppressesAConcurrentStartAuthorization() {
        val startEntered = CountDownLatch(1)
        val startRelease = CountDownLatch(1)
        val clock = ProbeClock {
            if (Thread.currentThread().name == "pending-start-after-cleanup") {
                startEntered.countDown()
                startRelease.await()
            }
            0L
        }
        val machine = machine(clock)
        machine.startWarmup()
        val completed = completeFrame(machine, 0L)
        val duplicate = machine.admitResult(callback(completed))
        val next = reserve(machine, 1L)
        val authorization = AtomicReference<RuntimeSubmitAuthorization>()
        val startThread = Thread {
            authorization.set(machine.startSubmission(next.token))
        }.apply {
            isDaemon = true
            name = "pending-start-after-cleanup"
        }

        startThread.start()
        assertTrue(startEntered.await(5L, TimeUnit.SECONDS))
        val cleanup = machine.completeCallbackOutput(
            cleanupToken(duplicate),
            CallbackOutputCleanupEvidence.CLOSE_THREW,
        )
        assertTrue(cleanup is CallbackResolution.Rejected)
        assertEquals(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN, machine.failureReason)
        startRelease.countDown()
        joinWithin(startThread, 5_000L)

        assertNull(authorization.get())
        assertEquals(ProbeMachineState.QUARANTINED, machine.state)
        assertEquals(
            ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
            machine.failureReason,
        )
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())
    }

    @Test
    fun cleanupQuarantineCannotBeDowngradedByAConcurrentResultTimeout() {
        val watchdogEntered = CountDownLatch(1)
        val watchdogRelease = CountDownLatch(1)
        val now = AtomicReference(0L)
        val clock = ProbeClock {
            if (Thread.currentThread().name == "pending-timeout-watchdog") {
                watchdogEntered.countDown()
                watchdogRelease.await()
            }
            now.get()
        }
        val machine = machine(clock)
        machine.startWarmup()
        val completed = completeFrame(machine, 0L)
        val duplicate = machine.admitResult(callback(completed))
        val next = reserve(machine, 1L)
        admitReturned(machine, startSubmission(machine, next))
        now.set(ProbeStateMachine.RESULT_CALLBACK_DEADLINE_NS + 1L)
        val watchdogThread = Thread { machine.runWatchdog() }.apply {
            isDaemon = true
            name = "pending-timeout-watchdog"
        }

        watchdogThread.start()
        assertTrue(watchdogEntered.await(5L, TimeUnit.SECONDS))
        val cleanup = machine.completeCallbackOutput(
            cleanupToken(duplicate),
            CallbackOutputCleanupEvidence.CLOSE_THREW,
        )
        assertTrue(cleanup is CallbackResolution.Rejected)
        watchdogRelease.countDown()
        joinWithin(watchdogThread, 5_000L)

        assertEquals(ProbeMachineState.QUARANTINED, machine.state)
        assertEquals(
            ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
            machine.failureReason,
        )
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())
    }

    @Test
    fun submitReturnAtDeadlineBeforeWatchdogKeepsCallerCleanupAndAcceptsResult() {
        val clock = FakeClock()
        val machine = machine(clock)
        machine.startWarmup()
        val frame = reserve(machine, 0L)
        val command = startSubmission(machine, frame)
        clock.nowNs = ProbeStateMachine.SUBMISSION_RETURN_DEADLINE_NS

        val submitAdmission = admitReturned(machine, command)
        clock.nowNs += 1L
        machine.runWatchdog()

        assertTrue(submitAdmission is RuntimeSubmitAdmission.Returned)
        assertEquals(
            RuntimeInputCleanupOwner.SUBMIT_CALLER,
            submitAdmission.inputCleanup.owner,
        )
        assertTrue(!machine.submissionExternalCallInFlight())
        assertNull(machine.pendingCloseOutcome())
        val result = machine.admitResult(callback(frame))
        assertTrue(result is ResultAdmission.Reserved)
        assertTrue(
            machine.completeCallbackOutput(
                cleanupToken(result),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.Completed,
        )
        assertEquals(1, machine.successfulWarmupCallbacks)
    }

    @Test
    fun failedThrownAndExplicitDeadlineResultsAdmitCategorically() {
        listOf(
            SubmissionFailure.DEPENDENCY_FAILED to RouteAttemptOutcome.INCOMPLETE,
            SubmissionFailure.DEPENDENCY_THROW to RouteAttemptOutcome.RESOURCE_UNCERTAIN,
        ).forEach { (failure, expectedOutcome) ->
            val submissionPort = PoseRuntimeSubmissionPort {
                if (failure == SubmissionFailure.DEPENDENCY_THROW) {
                    throw IllegalStateException("dependency throw")
                }
                PoseRuntimeSubmitEvidence.FAILED
            }
            val machine = machine(FakeClock(), submissionPort = submissionPort)
            machine.startWarmup()
            val authorization = startSubmission(machine, reserve(machine, 0L))
            val admission = admitOwnerExecution(machine, authorization)

            assertEquals(failure, (admission as RuntimeSubmitAdmission.Failed).reason)
            assertEquals(RuntimeInputCleanupOwner.SUBMIT_CALLER, admission.inputCleanup.owner)
            assertEquals(expectedOutcome, machine.pendingCloseOutcome())
        }

        val deadlineMachine = machine(
            FakeClock(),
            submissionPort = PoseRuntimeSubmissionPort {
                PoseRuntimeSubmitEvidence.DEADLINE_EXPIRED
            },
        )
        deadlineMachine.startWarmup()
        val deadlineAuthorization = startSubmission(deadlineMachine, reserve(deadlineMachine, 0L))
        val deadlineAdmission = admitOwnerExecution(deadlineMachine, deadlineAuthorization)
        assertTrue(deadlineAdmission is RuntimeSubmitAdmission.DeadlineExpired)
        assertEquals(
            RuntimeInputCleanupOwner.SUBMISSION_TIMEOUT,
            deadlineAdmission.inputCleanup.owner,
        )
        assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, deadlineMachine.pendingCloseOutcome())
    }

    @Test
    fun foreignOwnerSubmitExecutionsAreInertAndCannotConsumeActiveSubmission() {
        val machine = machine(FakeClock())
        machine.startWarmup()
        val activeAuthorization = startSubmission(machine, reserve(machine, 0L))
        val activeCommand = activeAuthorization.command
        val differentRoute = routeKey.copy(
            routeOrdinal = routeKey.routeOrdinal + 1,
            runtimeGeneration = RuntimeGeneration(
                routeKey.runtimeGeneration.value + 1L,
            ),
        )
        val routeForeign = ownerSubmitExecution(
            startedMachine(FakeClock(), route = differentRoute),
        )
        val identityForeign = ownerSubmitExecution(
            startedMachine(FakeClock(), runtimeIdentity = RuntimeIdentity(identity.value + 1L)),
        )
        val laterForeignMachine = machine(FakeClock())
        assertTrue(laterForeignMachine.startWarmup())
        completeFrame(laterForeignMachine, 40L)
        val laterForeign = ownerSubmitExecution(
            laterForeignMachine,
            frameId = 41L,
        )
        val foreignExecutions = listOf(routeForeign, identityForeign, laterForeign)

        assertTrue(foreignExecutions.any { it.command.key.routeKey != activeCommand.key.routeKey })
        assertTrue(foreignExecutions.any { it.command.key.runtimeIdentity != activeCommand.key.runtimeIdentity })
        assertTrue(foreignExecutions.any {
            it.command.key.reservationToken != activeCommand.key.reservationToken &&
                it.command.key.taskTimestampMs != activeCommand.key.taskTimestampMs
        })
        foreignExecutions.forEach { foreignExecution ->
            val stale = machine.admitSubmitExecution(foreignExecution)
            assertTrue(stale is RuntimeSubmitAdmission.Stale)
            assertEquals(RuntimeInputCleanupOwner.SUBMIT_CALLER, stale.inputCleanup.owner)
            assertTrue(machine.submissionExternalCallInFlight())
            assertNull(machine.pendingCloseOutcome())
        }

        assertTrue(admitReturned(machine, activeAuthorization) is RuntimeSubmitAdmission.Returned)
        assertTrue(!machine.submissionExternalCallInFlight())
    }

    @Test
    fun blockedExternalSubmitDoesNotHoldStateGateAndCloseWaitsForActualStackReturn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val execution = AtomicReference<RuntimeSubmitExecution>()
        val machine = machine(
            FakeClock(),
            submissionPort = PoseRuntimeSubmissionPort {
                entered.countDown()
                release.await()
                PoseRuntimeSubmitEvidence.RETURNED
            },
        )
        machine.startWarmup()
        val authorization = startSubmission(machine, reserve(machine, 0L))
        val submitThread = Thread {
            execution.set(RuntimeOwnerBoundary.submit(
                machine,
                authorization,
                opened(machine),
            ))
        }
        submitThread.start()
        assertTrue(entered.await(5L, TimeUnit.SECONDS))

        machine.abortIncomplete()

        assertTrue(machine.submissionExternalCallInFlight())
        assertNull(machine.pendingCloseOutcome())
        release.countDown()
        joinWithin(submitThread, 5_000L)
        assertTrue(machine.admitSubmitExecution(requireNotNull(execution.get())) is
            RuntimeSubmitAdmission.Returned)
        assertEquals(RouteAttemptOutcome.INCOMPLETE, machine.pendingCloseOutcome())
    }

    @Test
    fun everyClockedAdmissionCapturesOutsideTheStateMonitor() {
        lateinit var machine: ProbeStateMachine
        val held = mutableListOf<Boolean>()
        var now = 0L
        val clock = ProbeClock {
            held += Thread.holdsLock(machine)
            now
        }
        machine = machine(clock)

        assertTrue(machine.startWarmup())
        val frame = reserve(machine, 0L)
        val command = startSubmission(machine, frame)
        assertTrue(admitReturned(machine, command) is RuntimeSubmitAdmission.Returned)
        val result = machine.admitResult(callback(frame))
        assertTrue(result is ResultAdmission.Reserved)
        assertTrue(machine.completeCallbackOutput(
            cleanupToken(result),
            CallbackOutputCleanupEvidence.CLOSED,
        ) is CallbackResolution.Completed)
        machine.runWatchdog()

        assertEquals(7, held.size)
        assertTrue(held.none { it })
    }

    @Test
    fun deliveredClockThrowablesNeverEscapeAndFailClosedCategorically() {
        listOf(
            RuntimeException("clock runtime"),
            AssertionError("clock assertion"),
            LinkageError("clock linkage"),
        ).forEach { delivered ->
            val machine = machine(ProbeClock { throw delivered })

            assertFalse(machine.startWarmup())
            assertEquals(ProbeMachineState.QUARANTINED, machine.state)
            assertEquals(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW, machine.failureReason)
            assertEquals(RouteAttemptOutcome.RESOURCE_UNCERTAIN, machine.pendingCloseOutcome())
        }
    }

    @Test
    fun sameThreadClockReentryStaysBlockedAfterAbortInvalidatesLogicalTickets() {
        lateinit var machine: ProbeStateMachine
        var nestedStart = true
        var nestedAbortAttempted = false
        val clock = ProbeClock {
            assertFalse(Thread.holdsLock(machine))
            if (!nestedAbortAttempted) {
                nestedAbortAttempted = true
                machine.abortIncomplete()
                nestedStart = machine.startWarmup()
            }
            0L
        }
        machine = machine(clock)

        assertFalse(machine.startWarmup())
        assertFalse(nestedStart)
        assertEquals(ProbeMachineState.INCOMPLETE_PENDING_CLOSE, machine.state)
        assertEquals(ProbeFailureReason.ATTEMPT_ABORTED, machine.failureReason)
    }

    @Test
    fun crossThreadReentrantAbortHelperJoinCompletesWithoutDeadlock() {
        lateinit var machine: ProbeStateMachine
        val helperCompleted = AtomicBoolean(false)
        val helperJoinedBeforeDeadline = AtomicBoolean(false)
        val clock = ProbeClock {
            val helper = Thread {
                machine.abortIncomplete()
                helperCompleted.set(true)
            }.apply { isDaemon = true }
            helper.start()
            helper.join(5_000L)
            helperJoinedBeforeDeadline.set(!helper.isAlive)
            0L
        }
        machine = machine(clock)
        val outerResult = AtomicReference<Boolean>()
        val outer = Thread { outerResult.set(machine.startWarmup()) }.apply { isDaemon = true }

        outer.start()
        joinWithin(outer, 5_000L)

        assertTrue(helperCompleted.get())
        assertTrue(helperJoinedBeforeDeadline.get())
        assertFalse(requireNotNull(outerResult.get()))
        assertEquals(ProbeMachineState.INCOMPLETE_PENDING_CLOSE, machine.state)
        assertEquals(ProbeFailureReason.ATTEMPT_ABORTED, machine.failureReason)
    }

    @Test
    fun crossThreadReentrantClockedHelperJoinCompletesWithoutSerializationDeadlock() {
        lateinit var machine: ProbeStateMachine
        val firstCapture = AtomicBoolean(true)
        val helperCompleted = AtomicBoolean(false)
        val helperJoined = AtomicBoolean(false)
        val clock = ProbeClock {
            if (firstCapture.compareAndSet(true, false)) {
                val helper = Thread {
                    machine.runWatchdog()
                    helperCompleted.set(true)
                }.apply { isDaemon = true }
                helper.start()
                helper.join(5_000L)
                helperJoined.set(!helper.isAlive)
            }
            0L
        }
        machine = machine(clock)
        val outerResult = AtomicReference<Boolean>()
        val outer = Thread { outerResult.set(machine.startWarmup()) }.apply { isDaemon = true }

        outer.start()
        joinWithin(outer, 5_000L)

        assertTrue(helperCompleted.get())
        assertTrue(helperJoined.get())
        assertTrue(requireNotNull(outerResult.get()))
        assertEquals(ProbeMachineState.WARMUP, machine.state)
    }

    @Test
    fun abortWhileClockCaptureIsBlockedInvalidatesThePendingStart() {
        val clock = BlockingClock()
        val machine = machine(clock)
        val startResult = AtomicReference<Boolean>()
        clock.blockNextCapture()
        val startThread = Thread { startResult.set(machine.startWarmup()) }.apply {
            isDaemon = true
        }

        startThread.start()
        assertTrue(clock.awaitBlocked())
        machine.abortIncomplete()
        clock.releaseCapture()
        joinWithin(startThread, 5_000L)

        assertFalse(requireNotNull(startResult.get()))
        assertEquals(ProbeMachineState.INCOMPLETE_PENDING_CLOSE, machine.state)
        assertEquals(ProbeFailureReason.ATTEMPT_ABORTED, machine.failureReason)
    }

    @Test
    fun committedClockStartCanSucceedBeforeALaterAbortWinsTerminalState() {
        val clock = BlockingClock()
        val machine = machine(clock)
        val startResult = AtomicReference<Boolean>()
        clock.blockNextCapture()
        val startThread = Thread { startResult.set(machine.startWarmup()) }.apply {
            isDaemon = true
        }

        startThread.start()
        assertTrue(clock.awaitBlocked())
        clock.releaseCapture()
        joinWithin(startThread, 5_000L)
        assertTrue(requireNotNull(startResult.get()))

        machine.abortIncomplete()
        assertEquals(ProbeMachineState.INCOMPLETE_PENDING_CLOSE, machine.state)
        assertEquals(ProbeFailureReason.ATTEMPT_ABORTED, machine.failureReason)
    }

    @Test
    fun closeClaimFencesCallbackEntryBeforeItsClockCaptureAndSealsOnlyAfterCleanup() {
        val clock = BlockingClock()
        val machine = terminalMeasuredMachine(clock)
        val admission = AtomicReference<ResultAdmission>()
        clock.blockNextCapture()
        val callbackThread = Thread {
            admission.set(machine.admitResult(
                RuntimeResultCallback(identity, taskTimestampMs = 99L, poseCount = 0),
            ))
        }.apply { isDaemon = true }

        callbackThread.start()
        assertTrue(clock.awaitBlocked())
        assertEquals(1, machine.callbackEntryInFlightCount())
        assertNull(machine.pendingCloseOutcome())
        assertNull(machine.beginClose())

        clock.releaseCapture()
        joinWithin(callbackThread, 5_000L)
        val rejected = admission.get()
        assertTrue(rejected is ResultAdmission.Rejected)
        assertEquals(0, machine.callbackEntryInFlightCount())
        assertEquals(1, machine.pendingCallbackCleanupCount())
        assertNull(machine.beginClose())
        machine.completeCallbackOutput(
            cleanupToken(rejected),
            CallbackOutputCleanupEvidence.CLOSED,
        )

        val claim = requireNotNull(machine.beginClose())
        assertNull(machine.beginClose())
        val closeExecution = ownerClose(machine, claim)
        val completion = requireNotNull(machine.completeClose(claim, closeExecution))
        val sealed = requireNotNull(machine.consumeCloseCompletion(completion, claim))
        assertEquals(ProbeCloseCompletionKind.SEALED, sealed.kind)
        assertEquals(RouteAttemptOutcome.MEASURED, sealed.outcome)
        assertFalse(machine.callbackAdmissionIsOpen())
        assertTrue(machine.callbackGateIsSealed())
    }

    @Test
    fun callbackDuringCloseIsCountedCleanedAndPreventsCleanSeal() {
        val clock = BlockingClock()
        val machine = terminalMeasuredMachine(clock)
        val claim = requireNotNull(machine.beginClose())
        val admission = AtomicReference<ResultAdmission>()
        clock.blockNextCapture()
        val callbackThread = Thread {
            admission.set(machine.admitResult(
                RuntimeResultCallback(identity, taskTimestampMs = 100L, poseCount = 0),
            ))
        }.apply { isDaemon = true }

        callbackThread.start()
        assertTrue(clock.awaitBlocked())
        assertEquals(1, machine.callbackEntryInFlightCount())
        val closeExecution = ownerClose(machine, claim)
        val completion = requireNotNull(machine.completeClose(claim, closeExecution))
        val record = requireNotNull(machine.consumeCloseCompletion(completion, claim))

        assertEquals(ProbeCloseCompletionKind.RECOVERY_REQUIRED, record.kind)
        assertEquals(RouteAttemptOutcome.MEASURED, record.outcome)
        assertEquals(ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION, record.failureReason)
        assertFalse(machine.callbackGateIsSealed())
        assertTrue(machine.callbackAdmissionIsOpen())
        clock.releaseCapture()
        joinWithin(callbackThread, 5_000L)
        val rejected = admission.get()
        assertTrue(rejected is ResultAdmission.Rejected)
        assertEquals(
            ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION,
            (rejected as ResultAdmission.Rejected).reason,
        )
        assertEquals(1, machine.pendingCallbackCleanupCount())
        machine.completeCallbackOutput(
            cleanupToken(rejected),
            CallbackOutputCleanupEvidence.CLOSED,
        )
        assertEquals(0, machine.pendingCallbackCleanupCount())
        assertEquals(ProbeMachineState.QUARANTINED, machine.state)
    }

    @Test
    fun rawCleanForeignExecutionAndForeignCompletionCannotSeal() {
        val first = terminalMeasuredMachine(FakeClock())
        val other = terminalMeasuredMachine(FakeClock())
        val firstClaim = requireNotNull(first.beginClose())
        val otherClaim = requireNotNull(other.beginClose())
        val rawClean = boundaryCloseClean(firstClaim.command)
        val completeCloseMethod = ProbeStateMachine::class.java.methods.single {
            it.name == "completeClose" && it.parameterCount == 2
        }
        assertFalse(RuntimeCloseExecution::class.java.isInstance(rawClean))
        assertEquals(RuntimeCloseExecution::class.java, completeCloseMethod.parameterTypes[1])

        val firstExecution = ownerClose(first, firstClaim)
        val otherExecution = ownerClose(other, otherClaim)
        assertNull(first.completeClose(firstClaim, otherExecution))
        assertTrue(first.isGenuineCloseClaim(firstClaim))

        val otherCompletion = requireNotNull(other.completeClose(otherClaim, otherExecution))
        assertNull(first.consumeCloseCompletion(otherCompletion, firstClaim))
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(other.consumeCloseCompletion(otherCompletion, otherClaim)).kind,
        )

        val firstCompletion = requireNotNull(first.completeClose(firstClaim, firstExecution))
        assertNull(other.consumeCloseCompletion(firstCompletion, otherClaim))
        val firstRecord = requireNotNull(first.consumeCloseCompletion(firstCompletion, firstClaim))
        assertEquals(ProbeCloseCompletionKind.SEALED, firstRecord.kind)
        assertFalse(first.callbackAdmissionIsOpen())
        assertTrue(first.callbackGateIsSealed())
        assertNull(RuntimeOwnerBoundary.close(firstClaim, opened(first)))
    }

    @Test
    fun sameClaimConcurrentCloseReturnsOpaqueDeferredAndOriginalCleanSealsAfterRetry() {
        val runtime = BlockingCloseRuntime(identity)
        val machine = terminalMeasuredMachine(FakeClock(), runtime = runtime)
        val claim = requireNotNull(machine.beginClose())
        val firstExecution = AtomicReference<RuntimeCloseExecution>()
        val firstClose = Thread {
            firstExecution.set(ownerClose(machine, claim))
        }.apply { isDaemon = true }

        firstClose.start()
        assertTrue(runtime.awaitCloseEntered())
        val deferredExecution = ownerClose(machine, claim)
        assertEquals(
            RuntimeCloseDeferredReason.CLOSE_IN_FLIGHT,
            (deferredExecution.result as RuntimeCloseResult.Deferred).reason,
        )
        val retryCompletion = requireNotNull(machine.completeClose(claim, deferredExecution))
        val retry = requireNotNull(machine.consumeCloseCompletion(retryCompletion, claim))
        assertEquals(ProbeCloseCompletionKind.RETRY_REQUIRED, retry.kind)
        assertEquals(RuntimeCloseDeferredReason.CLOSE_IN_FLIGHT, retry.retryReason)
        assertTrue(machine.isGenuineCloseClaim(claim))
        assertTrue(machine.callbackAdmissionIsOpen())
        assertFalse(machine.callbackGateIsSealed())

        runtime.releaseClose()
        joinWithin(firstClose, 5_000L)
        val cleanCompletion = requireNotNull(
            machine.completeClose(claim, requireNotNull(firstExecution.get())),
        )
        val sealed = requireNotNull(machine.consumeCloseCompletion(cleanCompletion, claim))
        assertEquals(ProbeCloseCompletionKind.SEALED, sealed.kind)
        assertEquals(RouteAttemptOutcome.MEASURED, sealed.outcome)
        assertFalse(machine.callbackAdmissionIsOpen())
        assertTrue(machine.callbackGateIsSealed())
    }

    @Test
    fun callbackAfterSealIsContractViolationButStillOwnsOutputCleanup() {
        val machine = terminalMeasuredMachine(FakeClock())
        val claim = requireNotNull(machine.beginClose())
        val completion = requireNotNull(machine.completeClose(claim, ownerClose(machine, claim)))
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(machine.consumeCloseCompletion(completion, claim)).kind,
        )

        val late = machine.admitResult(
            RuntimeResultCallback(identity, taskTimestampMs = 101L, poseCount = 0),
        )

        assertTrue(late is ResultAdmission.Rejected)
        assertEquals(
            ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION,
            (late as ResultAdmission.Rejected).reason,
        )
        assertFalse(machine.callbackGateIsSealed())
        assertFalse(machine.callbackAdmissionIsOpen())
        assertEquals(ProbeMachineState.QUARANTINED, machine.state)
        assertEquals(1, machine.pendingCallbackCleanupCount())
        machine.completeCallbackOutput(
            cleanupToken(late),
            CallbackOutputCleanupEvidence.CLOSED,
        )
        assertEquals(0, machine.pendingCallbackCleanupCount())
    }

    private fun terminalMeasuredMachine(
        clock: MutableClock,
        runtime: PoseRuntime = TestRuntime(identity),
        submissionPort: PoseRuntimeSubmissionPort = PoseRuntimeSubmissionPort {
            PoseRuntimeSubmitEvidence.RETURNED
        },
    ): ProbeStateMachine {
        clock.nowNs = 0L
        val machine = machine(clock, runtime = runtime, submissionPort = submissionPort)
        assertTrue(machine.startWarmup())
        for (index in 0L until ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS.toLong()) {
            completeFrame(machine, index)
        }
        completeFrame(machine, 50L)
        val end = requireNotNull(machine.measurementEndNs)
        clock.nowNs = end
        assertTrue(machine.reserveFrame(51L, source(51L)) is FrameAdmission.OutsideMeasurementWindow)
        clock.nowNs = end + ProbeStateMachine.MEASUREMENT_DRAIN_NS + 1L
        machine.runWatchdog()
        assertEquals(RouteAttemptOutcome.MEASURED, machine.pendingCloseOutcome())
        return machine
    }

    private fun machine(
        clock: ProbeClock,
        role: RuntimeRole = RuntimeRole.CANDIDATE,
        runtimeIdentity: RuntimeIdentity = identity,
        route: RuntimeRouteKey = routeKey,
        runtime: PoseRuntime = TestRuntime(runtimeIdentity),
        submissionPort: PoseRuntimeSubmissionPort = PoseRuntimeSubmissionPort {
            PoseRuntimeSubmitEvidence.RETURNED
        },
    ): ProbeStateMachine {
        val kind = when (role) {
            RuntimeRole.CANDIDATE -> RuntimeRouteKind.CPU_CANDIDATE
            RuntimeRole.SELECTED_STEADY -> RuntimeRouteKind.SELECTED_CPU
        }
        val opened = RuntimeTestDeepFixture.openForRoute(
            route,
            runtime,
            submissionPort,
            kind = kind,
            clock = clock,
        )
        val machine = requireNotNull(RuntimeOwnerBoundary.stateMachine(opened))
        openedByMachine[machine] = opened
        return machine
    }

    private fun startedMachine(
        clock: ProbeClock,
        runtimeIdentity: RuntimeIdentity = identity,
        route: RuntimeRouteKey = routeKey,
    ): ProbeStateMachine = machine(
        clock = clock,
        runtimeIdentity = runtimeIdentity,
        route = route,
    ).also { assertTrue(it.startWarmup()) }

    private fun setPrivateLong(target: Any, name: String, value: Long) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(target, value)
    }

    private fun warmedMachine(
        clock: FakeClock,
        role: RuntimeRole,
    ): ProbeStateMachine = machine(clock, role).also { machine ->
        machine.startWarmup()
        for (
            index in 0L until
                ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS.toLong()
        ) {
            completeFrame(machine, index)
        }
        assertEquals(ProbeMachineState.AWAITING_MEASUREMENT, machine.state)
    }

    private fun completeFrame(
        machine: ProbeStateMachine,
        frameId: Long,
    ): FrameReservation {
        val reservation = reserve(machine, frameId)
        completeReserved(machine, reservation)
        return reservation
    }

    private fun completeReserved(
        machine: ProbeStateMachine,
        reservation: FrameReservation,
    ) {
        assertTrue(
            admitReturned(machine, startSubmission(machine, reservation)) is
                RuntimeSubmitAdmission.Returned,
        )
        val result = machine.admitResult(callback(reservation))
        assertTrue(result is ResultAdmission.Reserved)
        assertTrue(
            machine.completeCallbackOutput(
                cleanupToken(result),
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.Completed,
        )
    }

    private fun reserveSubmit(machine: ProbeStateMachine): FrameReservation {
        val reservation = reserve(machine, 0L)
        admitReturned(machine, startSubmission(machine, reservation))
        return reservation
    }

    private fun startSubmission(
        machine: ProbeStateMachine,
        reservation: FrameReservation,
    ): RuntimeSubmitAuthorization = requireNotNull(machine.startSubmission(reservation.token))

    private fun admitReturned(
        machine: ProbeStateMachine,
        authorization: RuntimeSubmitAuthorization,
    ): RuntimeSubmitAdmission = admitOwnerExecution(machine, authorization)

    private fun admitOwnerExecution(
        machine: ProbeStateMachine,
        authorization: RuntimeSubmitAuthorization,
    ): RuntimeSubmitAdmission = machine.admitSubmitExecution(
        ownerSubmitExecution(machine, authorization),
    )

    private fun ownerSubmitExecution(
        machine: ProbeStateMachine,
        frameId: Long = 0L,
    ): RuntimeSubmitExecution = ownerSubmitExecution(
        machine,
        startSubmission(machine, reserve(machine, frameId)),
    )

    private fun ownerSubmitExecution(
        machine: ProbeStateMachine,
        authorization: RuntimeSubmitAuthorization,
    ): RuntimeSubmitExecution = requireNotNull(
        RuntimeOwnerBoundary.submit(machine, authorization, opened(machine)),
    )

    private fun ownerClose(
        machine: ProbeStateMachine,
        claim: ProbeCloseClaim,
    ): RuntimeCloseExecution = requireNotNull(
        RuntimeOwnerBoundary.close(claim, opened(machine)),
    )

    private fun opened(machine: ProbeStateMachine): RuntimeOpenExecution =
        requireNotNull(openedByMachine[machine]) { "machine was not produced by this test's open helper" }

    private fun joinWithin(thread: Thread, timeoutMs: Long) {
        thread.join(timeoutMs)
        assertFalse("thread did not terminate within ${timeoutMs}ms", thread.isAlive)
    }

    private fun cleanupToken(admission: ResultAdmission): CallbackCleanupToken =
        requireNotNull(admission.cleanup).token

    private fun reserve(
        machine: ProbeStateMachine,
        frameId: Long,
        sourceTimestampNs: Long = frameId,
    ): FrameReservation {
        val admission = machine.reserveFrame(frameId, source(sourceTimestampNs))
        assertTrue(admission is FrameAdmission.Reserved)
        return (admission as FrameAdmission.Reserved).reservation
    }

    private fun callback(reservation: FrameReservation): RuntimeResultCallback = RuntimeResultCallback(
        runtimeIdentity = identity,
        taskTimestampMs = reservation.submission.taskTimestampMs.value,
        poseCount = 1,
    )

    private fun source(value: Long): SourceTimestampNs = when (val result = SourceTimestampNs.from(value)) {
        is CapabilityDomainResult.Valid -> result.value
        is CapabilityDomainResult.Invalid -> error(result.violations.toString())
    }

    private interface MutableClock : ProbeClock {
        var nowNs: Long
    }

    private class FakeClock(override var nowNs: Long = 0L) : MutableClock {
        override fun nowNs(): Long = nowNs
    }

    private class TestRuntime(override val identity: RuntimeIdentity) : PoseRuntime() {
        override fun close(): RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN
    }

    private class BlockingCloseRuntime(
        override val identity: RuntimeIdentity,
    ) : PoseRuntime() {
        private val closeEntered = CountDownLatch(1)
        private val closeRelease = CountDownLatch(1)

        fun awaitCloseEntered(): Boolean = closeEntered.await(5L, TimeUnit.SECONDS)

        fun releaseClose() = closeRelease.countDown()

        override fun close(): RuntimeCloseEvidence {
            closeEntered.countDown()
            closeRelease.await()
            return RuntimeCloseEvidence.CLEAN
        }
    }

    private class BlockingClock : MutableClock {
        @Volatile
        override var nowNs: Long = 0L
        private var capturesToBlock = 0
        private var entered = CountDownLatch(1)
        private var release = CountDownLatch(1)

        fun blockNextCapture() = blockNextCaptures(1)

        @Synchronized
        fun blockNextCaptures(count: Int) {
            require(count > 0)
            capturesToBlock = count
            entered = CountDownLatch(count)
            release = CountDownLatch(1)
        }

        fun awaitBlocked(): Boolean = entered.await(5L, TimeUnit.SECONDS)

        fun releaseCapture() = release.countDown()

        override fun nowNs(): Long {
            val shouldBlock = synchronized(this) {
                if (capturesToBlock == 0) {
                    false
                } else {
                    capturesToBlock -= 1
                    true
                }
            }
            if (shouldBlock) {
                entered.countDown()
                release.await()
            }
            return nowNs
        }
    }
}
