package com.motionarcade.vision.capability.domain

import kotlin.math.max

@JvmInline
value class MonotonicTimeNs private constructor(val value: Long) {
    companion object {
        fun from(value: Long): CapabilityDomainResult<MonotonicTimeNs> =
            if (value >= 0L) {
                CapabilityDomainResult.Valid(MonotonicTimeNs(value))
            } else {
                invalid("$/monotonicTimeNs", ">= 0", value.toString())
            }

        internal fun trusted(value: Long): MonotonicTimeNs {
            check(value >= 0L)
            return MonotonicTimeNs(value)
        }
    }
}

@JvmInline
value class SourceTimestampNs private constructor(val value: Long) {
    companion object {
        fun from(value: Long): CapabilityDomainResult<SourceTimestampNs> =
            if (value >= 0L) {
                CapabilityDomainResult.Valid(SourceTimestampNs(value))
            } else {
                invalid("$/sourceTimestampNs", ">= 0", value.toString())
            }

        internal fun trusted(value: Long): SourceTimestampNs {
            check(value >= 0L)
            return SourceTimestampNs(value)
        }
    }
}

@JvmInline
value class TaskTimestampMs private constructor(val value: Long) {
    companion object {
        internal fun trusted(value: Long): TaskTimestampMs {
            check(value in 0L..ProbeTimeContract.MAX_TASK_TIMESTAMP_MS)
            return TaskTimestampMs(value)
        }
    }
}

object ProbeTimeContract {
    const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    const val MICROS_PER_MILLISECOND: Long = 1_000L
    const val MAX_TASK_TIMESTAMP_MS: Long = 9_223_372_036_854_775L
    const val REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS: Int = 5

    const val CANDIDATE_MEASUREMENT_DURATION_NS: Long = 5_000_000_000L
    const val SELECTED_STEADY_DURATION_NS: Long = 10_000_000_000L
}

enum class ProbeDeadlineKind(val durationNs: Long) {
    CAMERA_BIND_RETURN(5_000_000_000L),
    FIRST_FRAME_AFTER_BIND(5_000_000_000L),
    IDENTITY_READ(5_000_000_000L),
    CAMERA_IDLE_GAP(1_000_000_000L),
    WARMUP_PHASE(10_000_000_000L),
    RUNTIME_CREATE_RETURN(10_000_000_000L),
    SUBMISSION_RETURN(1_000_000_000L),
    CALLBACK_OUTPUT_DISPOSAL(1_000_000_000L),
    MEASUREMENT_DRAIN(1_000_000_000L),
    RUNTIME_TEARDOWN_RETURN(5_000_000_000L),
    RECOVERY_JOURNAL_READ(1_000_000_000L),
    RECOVERY_JOURNAL_MUTATION(1_000_000_000L),
    CAPABILITY_MODE_STORE_READ(5_000_000_000L),
    CAPABILITY_MODE_STORE_MUTATION(5_000_000_000L),
    DATASTORE_OWNER_HANDOFF(5_000_000_000L),
    PSS_SAMPLE(500_000_000L),
    RENDER_DRAIN(1_000_000_000L),
    THERMAL_MEASUREMENT_CUTOFF(1_000_000_000L),
}

class ProbeDeadline internal constructor(
    val startedAtNs: MonotonicTimeNs,
    val deadlineNs: MonotonicTimeNs,
)

object ProbeDeadlinePolicy {
    fun fixed(
        kind: ProbeDeadlineKind,
        startedAtNs: MonotonicTimeNs,
    ): CapabilityDomainResult<ProbeDeadline> = deadline(startedAtNs, kind.durationNs)

    fun resultCallback(
        submissionReturnedAtNs: MonotonicTimeNs,
        phaseOrDrainDeadlineNs: MonotonicTimeNs,
    ): CapabilityDomainResult<ProbeDeadline> =
        deadline(submissionReturnedAtNs, 1_000_000_000L).map { nominal ->
            ProbeDeadline(
                startedAtNs = submissionReturnedAtNs,
                deadlineNs = MonotonicTimeNs.trusted(
                    minOf(nominal.deadlineNs.value, phaseOrDrainDeadlineNs.value),
                ),
            )
        }

    fun completedOnTime(
        completedAtNs: MonotonicTimeNs,
        deadline: ProbeDeadline,
    ): Boolean = completedAtNs.value <= deadline.deadlineNs.value

    fun expired(
        nowNs: MonotonicTimeNs,
        deadline: ProbeDeadline,
    ): Boolean = nowNs.value > deadline.deadlineNs.value

    private fun deadline(
        startedAtNs: MonotonicTimeNs,
        durationNs: Long,
    ): CapabilityDomainResult<ProbeDeadline> {
        val deadlineValue = try {
            Math.addExact(startedAtNs.value, durationNs)
        } catch (_: ArithmeticException) {
            return overflow("$/deadlineNs", "startedAtNs + durationNs")
        }
        if (deadlineValue < 0L) return overflow("$/deadlineNs", "startedAtNs + durationNs")
        return CapabilityDomainResult.Valid(
            ProbeDeadline(startedAtNs, MonotonicTimeNs.trusted(deadlineValue)),
        )
    }
}

enum class TaskTimestampFailure {
    TASK_TIMESTAMP_INVALID,
    TASK_TIMESTAMP_EXHAUSTED,
}

sealed interface TaskTimestampDecision {
    data class Reserved(
        val taskTimestampMs: TaskTimestampMs,
        val packetTimestampUs: Long,
    ) : TaskTimestampDecision

    data class Rejected(val reason: TaskTimestampFailure) : TaskTimestampDecision
}

/**
 * Process-local timestamp epoch for exactly one freshly created runtime.
 * State is committed only after every checked conversion succeeds.
 */
class TaskTimestampEpoch internal constructor(
    sourceAnchorNs: Long? = null,
    previousTaskTimestampMs: Long = -1L,
    lastSourceTimestampNs: Long? = null,
) {
    private var sourceAnchorNs: Long? = sourceAnchorNs
    private var previousTaskTimestampMs: Long = previousTaskTimestampMs
    private var lastSourceTimestampNs: Long? = lastSourceTimestampNs

    init {
        if (sourceAnchorNs == null) {
            require(previousTaskTimestampMs == -1L && lastSourceTimestampNs == null)
        } else {
            require(sourceAnchorNs >= 0L)
            require(previousTaskTimestampMs in 0L..ProbeTimeContract.MAX_TASK_TIMESTAMP_MS)
            require(lastSourceTimestampNs != null && lastSourceTimestampNs >= sourceAnchorNs)
        }
    }

    constructor() : this(null, -1L, null)

    @Synchronized
    fun reserve(sourceTimestampNs: SourceTimestampNs): TaskTimestampDecision {
        val source = sourceTimestampNs.value
        val previousSource = lastSourceTimestampNs
        if (previousSource != null && source <= previousSource) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_INVALID)
        }

        val anchor = sourceAnchorNs ?: source
        val deltaNs = try {
            Math.subtractExact(source, anchor)
        } catch (_: ArithmeticException) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_INVALID)
        }
        if (deltaNs < 0L) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_INVALID)
        }
        if (previousTaskTimestampMs == ProbeTimeContract.MAX_TASK_TIMESTAMP_MS) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_EXHAUSTED)
        }
        if (previousTaskTimestampMs !in -1L until ProbeTimeContract.MAX_TASK_TIMESTAMP_MS) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_INVALID)
        }

        val relativeMs = deltaNs / ProbeTimeContract.NANOS_PER_MILLISECOND
        val nextMs = try {
            Math.addExact(previousTaskTimestampMs, 1L)
        } catch (_: ArithmeticException) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_EXHAUSTED)
        }
        val taskTimestampMs = max(relativeMs, nextMs)
        if (taskTimestampMs !in 0L..ProbeTimeContract.MAX_TASK_TIMESTAMP_MS) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_INVALID)
        }
        val packetTimestampUs = try {
            Math.multiplyExact(taskTimestampMs, ProbeTimeContract.MICROS_PER_MILLISECOND)
        } catch (_: ArithmeticException) {
            return TaskTimestampDecision.Rejected(TaskTimestampFailure.TASK_TIMESTAMP_INVALID)
        }

        sourceAnchorNs = anchor
        previousTaskTimestampMs = taskTimestampMs
        lastSourceTimestampNs = source
        return TaskTimestampDecision.Reserved(
            taskTimestampMs = TaskTimestampMs.trusted(taskTimestampMs),
            packetTimestampUs = packetTimestampUs,
        )
    }

    @Synchronized
    fun lastReservedTaskTimestampMs(): TaskTimestampMs? =
        previousTaskTimestampMs.takeIf { it >= 0L }?.let(TaskTimestampMs::trusted)
}
