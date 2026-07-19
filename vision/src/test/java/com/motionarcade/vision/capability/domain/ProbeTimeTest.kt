package com.motionarcade.vision.capability.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTimeTest {
    @Test
    fun warmupRequirementIsTheSharedDomainContract() {
        assertEquals(5, ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS)
    }

    @Test
    fun negativeClockInputsFailClosed() {
        assertTrue(MonotonicTimeNs.from(-1L) is CapabilityDomainResult.Invalid)
        assertTrue(SourceTimestampNs.from(-1L) is CapabilityDomainResult.Invalid)
    }

    @Test
    fun deadlineAcceptsEqualityAndExpiresOnlyAtPlusOne() {
        val start = monotonic(10L)
        val deadline = valid(ProbeDeadlinePolicy.fixed(ProbeDeadlineKind.PSS_SAMPLE, start))
        val exact = monotonic(500_000_010L)

        assertTrue(ProbeDeadlinePolicy.completedOnTime(exact, deadline))
        assertFalse(ProbeDeadlinePolicy.expired(exact, deadline))
        assertFalse(ProbeDeadlinePolicy.completedOnTime(monotonic(exact.value + 1L), deadline))
        assertTrue(ProbeDeadlinePolicy.expired(monotonic(exact.value + 1L), deadline))
    }

    @Test
    fun deadlineOverflowFailsClosedAndResultDeadlineUsesEarlierBarrier() {
        assertTrue(
            ProbeDeadlinePolicy.fixed(
                ProbeDeadlineKind.RUNTIME_CREATE_RETURN,
                monotonic(Long.MAX_VALUE),
            ) is CapabilityDomainResult.Invalid,
        )

        val resultDeadline = valid(
            ProbeDeadlinePolicy.resultCallback(
                submissionReturnedAtNs = monotonic(5_000L),
                phaseOrDrainDeadlineNs = monotonic(9_000L),
            ),
        )
        assertEquals(9_000L, resultDeadline.deadlineNs.value)
    }

    @Test
    fun deadlineRegistryMatchesEveryNormativeDuration() {
        assertEquals(
            listOf(
                ProbeDeadlineKind.CAMERA_BIND_RETURN to 5_000_000_000L,
                ProbeDeadlineKind.FIRST_FRAME_AFTER_BIND to 5_000_000_000L,
                ProbeDeadlineKind.IDENTITY_READ to 5_000_000_000L,
                ProbeDeadlineKind.CAMERA_IDLE_GAP to 1_000_000_000L,
                ProbeDeadlineKind.WARMUP_PHASE to 10_000_000_000L,
                ProbeDeadlineKind.RUNTIME_CREATE_RETURN to 10_000_000_000L,
                ProbeDeadlineKind.SUBMISSION_RETURN to 1_000_000_000L,
                ProbeDeadlineKind.CALLBACK_OUTPUT_DISPOSAL to 1_000_000_000L,
                ProbeDeadlineKind.MEASUREMENT_DRAIN to 1_000_000_000L,
                ProbeDeadlineKind.RUNTIME_TEARDOWN_RETURN to 5_000_000_000L,
                ProbeDeadlineKind.RECOVERY_JOURNAL_READ to 1_000_000_000L,
                ProbeDeadlineKind.RECOVERY_JOURNAL_MUTATION to 1_000_000_000L,
                ProbeDeadlineKind.CAPABILITY_MODE_STORE_READ to 5_000_000_000L,
                ProbeDeadlineKind.CAPABILITY_MODE_STORE_MUTATION to 5_000_000_000L,
                ProbeDeadlineKind.DATASTORE_OWNER_HANDOFF to 5_000_000_000L,
                ProbeDeadlineKind.PSS_SAMPLE to 500_000_000L,
                ProbeDeadlineKind.RENDER_DRAIN to 1_000_000_000L,
                ProbeDeadlineKind.THERMAL_MEASUREMENT_CUTOFF to 1_000_000_000L,
            ),
            ProbeDeadlineKind.entries.map { it to it.durationNs },
        )
    }

    @Test
    fun firstTaskTimestampIsZeroAndEpochIsMonotonicAcrossPhaseBoundaries() {
        val epoch = TaskTimestampEpoch()
        val first = reserved(epoch.reserve(source(8_000_000_000L)))
        val subMillisecond = reserved(epoch.reserve(source(8_000_000_100L)))
        val laterPhase = reserved(epoch.reserve(source(8_002_500_000L)))

        assertEquals(0L, first.taskTimestampMs.value)
        assertEquals(0L, first.packetTimestampUs)
        assertEquals(1L, subMillisecond.taskTimestampMs.value)
        assertEquals(2L, laterPhase.taskTimestampMs.value)
        assertEquals(2_000L, laterPhase.packetTimestampUs)
    }

    @Test
    fun rejectedSourceDoesNotMutateTimestampEpoch() {
        val epoch = TaskTimestampEpoch()
        reserved(epoch.reserve(source(1_000_000L)))
        val rejected = epoch.reserve(source(1_000_000L))

        assertEquals(
            TaskTimestampFailure.TASK_TIMESTAMP_INVALID,
            (rejected as TaskTimestampDecision.Rejected).reason,
        )
        val next = reserved(epoch.reserve(source(2_000_000L)))
        assertEquals(1L, next.taskTimestampMs.value)
    }

    @Test
    fun exactMaximumPriorTimestampExhaustsWithoutMutation() {
        val epoch = TaskTimestampEpoch(
            sourceAnchorNs = 0L,
            previousTaskTimestampMs = ProbeTimeContract.MAX_TASK_TIMESTAMP_MS,
            lastSourceTimestampNs = 0L,
        )

        val rejected = epoch.reserve(source(1L))
        assertEquals(
            TaskTimestampFailure.TASK_TIMESTAMP_EXHAUSTED,
            (rejected as TaskTimestampDecision.Rejected).reason,
        )
        assertEquals(ProbeTimeContract.MAX_TASK_TIMESTAMP_MS, epoch.lastReservedTaskTimestampMs()?.value)
    }

    @Test
    fun exactMaximumTimestampIsReservedOnceThenNextSourceExhausts() {
        val epoch = TaskTimestampEpoch(
            sourceAnchorNs = 0L,
            previousTaskTimestampMs = ProbeTimeContract.MAX_TASK_TIMESTAMP_MS - 1L,
            lastSourceTimestampNs = 0L,
        )

        val maximum = reserved(epoch.reserve(source(1L)))
        assertEquals(ProbeTimeContract.MAX_TASK_TIMESTAMP_MS, maximum.taskTimestampMs.value)
        assertEquals(9_223_372_036_854_775_000L, maximum.packetTimestampUs)

        val exhausted = epoch.reserve(source(2L))
        assertEquals(
            TaskTimestampFailure.TASK_TIMESTAMP_EXHAUSTED,
            (exhausted as TaskTimestampDecision.Rejected).reason,
        )
        assertEquals(ProbeTimeContract.MAX_TASK_TIMESTAMP_MS, epoch.lastReservedTaskTimestampMs()?.value)
    }

    @Test
    fun freshEpochHasNoCommittedTimestamp() {
        assertNull(TaskTimestampEpoch().lastReservedTaskTimestampMs())
    }

    private fun monotonic(value: Long): MonotonicTimeNs = valid(MonotonicTimeNs.from(value))

    private fun source(value: Long): SourceTimestampNs = valid(SourceTimestampNs.from(value))

    private fun reserved(decision: TaskTimestampDecision): TaskTimestampDecision.Reserved =
        decision as TaskTimestampDecision.Reserved

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }
}
