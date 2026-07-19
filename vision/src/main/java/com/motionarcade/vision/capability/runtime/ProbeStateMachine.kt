package com.motionarcade.vision.capability.runtime

import com.motionarcade.vision.capability.domain.ProbeTimeContract
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import com.motionarcade.vision.capability.domain.TaskTimestampDecision
import com.motionarcade.vision.capability.domain.TaskTimestampEpoch
import com.motionarcade.vision.capability.domain.TaskTimestampFailure
import java.util.Collections
import java.util.IdentityHashMap

enum class ProbeMachineState {
    IDLE,
    WARMUP,
    AWAITING_MEASUREMENT,
    MEASURING,
    DRAINING,
    MEASUREMENT_COMPLETE_PENDING_CLOSE,
    CLEAN_TERMINAL_PENDING_CLOSE,
    INCOMPLETE_PENDING_CLOSE,
    QUARANTINED,
}

enum class ProbeFailureReason {
    INVALID_TRANSITION,
    CLOCK_INVALID_OR_OVERFLOW,
    CAPACITY_ONE_VIOLATION,
    TASK_TIMESTAMP_INVALID,
    TASK_TIMESTAMP_EXHAUSTED,
    SUBMISSION_DEADLINE_EXPIRED,
    SUBMISSION_FAILED,
    RESULT_DEADLINE_EXPIRED,
    RESULT_BEFORE_SUBMISSION_RETURN,
    STALE_OR_DUPLICATE_RESULT,
    CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
    CLEANUP_TOKEN_EXHAUSTED,
    ERROR_LISTENER_RESOURCE_UNCERTAIN,
    CALLBACK_GATE_CONTRACT_VIOLATION,
    RUNTIME_CLOSE_UNCERTAIN,
    WARMUP_DEADLINE_EXPIRED,
    ATTEMPT_ABORTED,
}

enum class ProbePhase {
    WARMUP,
    MEASUREMENT,
}

data class FrameReservation(
    val token: Long,
    val phase: ProbePhase,
    val submission: RuntimeSubmission,
)

sealed interface FrameAdmission {
    data class Reserved(val reservation: FrameReservation) : FrameAdmission

    data object OutsideMeasurementWindow : FrameAdmission

    data class Rejected(val reason: ProbeFailureReason) : FrameAdmission
}

@JvmInline
value class CallbackCleanupToken(val value: Long) {
    init {
        require(value > 0L)
    }
}

data class CallbackCleanupReservation(
    val token: CallbackCleanupToken,
)

sealed interface ResultAdmission {
    val cleanup: CallbackCleanupReservation?

    data class Reserved(
        override val cleanup: CallbackCleanupReservation,
    ) : ResultAdmission

    data class Rejected(
        val reason: ProbeFailureReason,
        override val cleanup: CallbackCleanupReservation,
    ) : ResultAdmission

    data object CleanupUnavailable : ResultAdmission {
        val reason: ProbeFailureReason = ProbeFailureReason.CLEANUP_TOKEN_EXHAUSTED
        override val cleanup: CallbackCleanupReservation? = null
    }
}

sealed interface CallbackResolution {
    data class Completed(
        val phase: ProbePhase,
        val successfulWarmupCallbacks: Int,
        val measurementCompletions: Int,
    ) : CallbackResolution

    data class StateInert(val reason: ProbeFailureReason) : CallbackResolution

    data class Rejected(val reason: ProbeFailureReason) : CallbackResolution
}

/** Opaque one-shot correlation between terminal state admission and native close evidence. */
sealed interface ProbeCloseClaim {
    val command: CloseRuntimeCommand
    val outcome: RouteAttemptOutcome
}

private class IssuedProbeCloseClaim(
    override val command: CloseRuntimeCommand,
    override val outcome: RouteAttemptOutcome,
) : ProbeCloseClaim

enum class ProbeCloseCompletionKind {
    SEALED,
    RETRY_REQUIRED,
    RECOVERY_REQUIRED,
}

sealed interface ProbeCloseCompletion

private class IssuedProbeCloseCompletion(
    val claim: IssuedProbeCloseClaim,
    val execution: RuntimeCloseExecution,
    val kind: ProbeCloseCompletionKind,
    val outcome: RouteAttemptOutcome,
    val retryReason: RuntimeCloseDeferredReason?,
    val failureReason: ProbeFailureReason?,
) : ProbeCloseCompletion

data class ProbeCloseCompletionRecord(
    val kind: ProbeCloseCompletionKind,
    val outcome: RouteAttemptOutcome,
    val retryReason: RuntimeCloseDeferredReason? = null,
    val failureReason: ProbeFailureReason? = null,
)

private class IssuedRuntimeSubmitAuthorization(
    override val command: SubmitRuntimeCommand,
) : RuntimeSubmitAuthorization

private data class CallbackGateEntry(
    val contractViolation: Boolean,
    val counted: Boolean,
)

private enum class OutstandingStage {
    RESERVED,
    SUBMISSION_STARTED,
    SUBMISSION_RETURNED,
    CALLBACK_RESERVED,
}

private data class OutstandingTask(
    val reservation: FrameReservation,
    var stage: OutstandingStage,
    var submissionStartedAtNs: Long? = null,
    var submissionReturnedAtNs: Long? = null,
    var submitAuthorization: IssuedRuntimeSubmitAuthorization? = null,
    var submitCommand: SubmitRuntimeCommand? = null,
    var submitAuthorizationConsumed: Boolean = false,
    var submissionTimeoutTransferred: Boolean = false,
    var callbackCleanupToken: CallbackCleanupToken? = null,
)

private enum class CleanupKind {
    MATCHED_RESULT,
    STATE_INERT,
}

private data class PendingCleanup(
    val admittedAtNs: Long,
    val kind: CleanupKind,
    val taskReservationToken: Long?,
    val inertReason: ProbeFailureReason,
)

/**
 * Serialized state gate for one fresh runtime generation.
 *
 * It owns timestamp derivation and callback reservations. Dependency work and output cleanup
 * occur outside the gate; only categorical completion evidence re-enters it.
 */
class ProbeStateMachine internal constructor(
    private val machineBinding: RuntimeMachineBinding,
    private val routeKey: RuntimeRouteKey,
    private val runtimeIdentity: RuntimeIdentity,
    private val role: RuntimeRole,
    private val clock: ProbeClock,
    initialCleanupToken: Long,
) {
    init {
        require(initialCleanupToken > 0L) { "cleanup token seed must be positive" }
        require(RuntimeOwnerBoundary.isGenuineMachineBinding(machineBinding)) {
            "runtime machine binding must be owner-issued"
        }
    }

    var state: ProbeMachineState = ProbeMachineState.IDLE
        private set

    var failureReason: ProbeFailureReason? = null
        private set

    var successfulWarmupCallbacks: Int = 0
        private set

    var measurementCompletions: Int = 0
        private set

    var measurementStartNs: Long? = null
        private set

    var measurementEndNs: Long? = null
        private set

    private var warmupDeadlineNs: Long? = null
    private var nextReservationToken = 1L
    private var nextCleanupToken = initialCleanupToken
    private var cleanupTokenExhausted = false
    private var outstanding: OutstandingTask? = null
    private val pendingCleanups = linkedMapOf<CallbackCleanupToken, PendingCleanup>()
    private val admittedSubmitExecutions = Collections.newSetFromMap(
        IdentityHashMap<RuntimeSubmitExecution, Boolean>(),
    )
    private var lastResolvedTaskTimestampMs = -1L
    private var lastTimedOutTaskTimestampMs: Long? = null
    private val timestampEpoch = TaskTimestampEpoch()
    private var nextClockOperationToken = 1L
    private val pendingClockOperations = linkedMapOf<Long, Thread>()
    private val clockCaptureActive = ThreadLocal<Boolean>()
    private var callbackAdmissionOpen = true
    private var callbackGateSealed = false
    private var inFlightCallbackCount = 0
    private var callbackGateError = false
    private var activeCloseClaim: IssuedProbeCloseClaim? = null
    private var closeResultAdmitted = false
    private var closeAttemptInFlight = false
    private var pendingCloseCompletion: IssuedProbeCloseCompletion? = null

    fun startWarmup(): Boolean {
        val token = reserveClockOperation() ?: return false
        val captured = captureClockOutsideGate()
        return synchronized(this) {
            val now = commitClockOperation(token, captured) ?: return@synchronized false
            if (state != ProbeMachineState.IDLE) return@synchronized false
            warmupDeadlineNs = checkedAdd(now, WARMUP_DEADLINE_NS)
                ?: return@synchronized false
            state = ProbeMachineState.WARMUP
            true
        }
    }

    fun reserveFrame(
        frameId: Long,
        sourceTimestampNs: SourceTimestampNs,
    ): FrameAdmission {
        val token = reserveClockOperation()
            ?: return FrameAdmission.Rejected(ProbeFailureReason.INVALID_TRANSITION)
        val captured = captureClockOutsideGate()
        return synchronized(this) {
        val now = commitClockOperation(token, captured)
            ?: return@synchronized FrameAdmission.Rejected(requireNotNull(failureReason))
        if (state == ProbeMachineState.WARMUP && isPast(requireNotNull(warmupDeadlineNs), now)) {
            failIncomplete(ProbeFailureReason.WARMUP_DEADLINE_EXPIRED)
            return@synchronized FrameAdmission.Rejected(ProbeFailureReason.WARMUP_DEADLINE_EXPIRED)
        }
        if (state == ProbeMachineState.AWAITING_MEASUREMENT) {
            val duration = when (role) {
                RuntimeRole.CANDIDATE -> CANDIDATE_DURATION_NS
                RuntimeRole.SELECTED_STEADY -> SELECTED_STEADY_DURATION_NS
            }
            measurementStartNs = now
            measurementEndNs = checkedAdd(now, duration)
                ?: return@synchronized FrameAdmission.Rejected(requireNotNull(failureReason))
            state = ProbeMachineState.MEASURING
        }
        if (state == ProbeMachineState.MEASURING && now >= requireNotNull(measurementEndNs)) {
            state = ProbeMachineState.DRAINING
            return@synchronized FrameAdmission.OutsideMeasurementWindow
        }
        if (state == ProbeMachineState.DRAINING ||
            state == ProbeMachineState.MEASUREMENT_COMPLETE_PENDING_CLOSE
        ) {
            return@synchronized FrameAdmission.OutsideMeasurementWindow
        }
        if (state != ProbeMachineState.WARMUP && state != ProbeMachineState.MEASURING) {
            return@synchronized FrameAdmission.Rejected(ProbeFailureReason.INVALID_TRANSITION)
        }
        if (outstanding != null) {
            failClosed(ProbeFailureReason.CAPACITY_ONE_VIOLATION)
            return@synchronized FrameAdmission.Rejected(ProbeFailureReason.CAPACITY_ONE_VIOLATION)
        }
        if (frameId < 0L) {
            failIncomplete(ProbeFailureReason.TASK_TIMESTAMP_INVALID)
            return@synchronized FrameAdmission.Rejected(ProbeFailureReason.TASK_TIMESTAMP_INVALID)
        }

        val reservationToken = allocateReservationToken()
            ?: return@synchronized FrameAdmission.Rejected(requireNotNull(failureReason))
        val timestamp = when (val decision = timestampEpoch.reserve(sourceTimestampNs)) {
            is TaskTimestampDecision.Reserved -> decision
            is TaskTimestampDecision.Rejected -> {
                val reason = when (decision.reason) {
                    TaskTimestampFailure.TASK_TIMESTAMP_INVALID ->
                        ProbeFailureReason.TASK_TIMESTAMP_INVALID
                    TaskTimestampFailure.TASK_TIMESTAMP_EXHAUSTED ->
                        ProbeFailureReason.TASK_TIMESTAMP_EXHAUSTED
                }
                failIncomplete(reason)
                return@synchronized FrameAdmission.Rejected(reason)
            }
        }
        val phase = if (state == ProbeMachineState.WARMUP) ProbePhase.WARMUP else ProbePhase.MEASUREMENT
        val reservation = FrameReservation(
            token = reservationToken,
            phase = phase,
            submission = RuntimeSubmission(
                runtimeIdentity = runtimeIdentity,
                frameId = frameId,
                sourceTimestampNs = sourceTimestampNs,
                taskTimestampMs = timestamp.taskTimestampMs,
                packetTimestampUs = timestamp.packetTimestampUs,
            ),
        )
        outstanding = OutstandingTask(reservation, OutstandingStage.RESERVED)
        FrameAdmission.Reserved(reservation)
        }
    }

    fun startSubmission(reservationToken: Long): RuntimeSubmitAuthorization? {
        val token = reserveClockOperation() ?: return null
        val captured = captureClockOutsideGate()
        return synchronized(this) {
        val startedAtNs = commitClockOperation(token, captured) ?: run {
            outstanding = null
            return@synchronized null
        }
        if (terminalCloseOutcome() != null) {
            if (outstanding?.stage == OutstandingStage.RESERVED &&
                outstanding?.reservation?.token == reservationToken
            ) {
                outstanding = null
            }
            return@synchronized null
        }
        val task = matchingOutstanding(reservationToken) ?: return@synchronized null
        if (task.stage != OutstandingStage.RESERVED) {
            failClosed(ProbeFailureReason.INVALID_TRANSITION)
            return@synchronized null
        }
        val key = RuntimeSubmitKey(
            routeKey = routeKey,
            reservationToken = reservationToken,
            runtimeIdentity = runtimeIdentity,
            taskTimestampMs = task.reservation.submission.taskTimestampMs,
        )
        val command = SubmitRuntimeCommand(key, task.reservation.submission)
        val authorization = IssuedRuntimeSubmitAuthorization(command)
        task.submissionStartedAtNs = startedAtNs
        task.submitAuthorization = authorization
        task.submitCommand = command
        task.submitAuthorizationConsumed = false
        task.stage = OutstandingStage.SUBMISSION_STARTED
        authorization
        }
    }

    /** Consumed by RuntimeOwnerBoundary before dependency entry; identity and full bytes are fixed. */
    @Synchronized
    fun consumeSubmitAuthorization(
        authorization: RuntimeSubmitAuthorization,
    ): SubmitRuntimeCommand? {
        val task = outstanding ?: return null
        val issued = task.submitAuthorization ?: return null
        if (authorization !== issued || task.submitAuthorizationConsumed ||
            task.submitCommand != issued.command || task.stage != OutstandingStage.SUBMISSION_STARTED
        ) {
            return null
        }
        task.submitAuthorizationConsumed = true
        return issued.command
    }

    fun admitSubmitExecution(execution: RuntimeSubmitExecution): RuntimeSubmitAdmission {
        if (!RuntimeOwnerBoundary.isGenuineSubmitExecutionForMachine(execution, this)) {
            return RuntimeSubmitAdmission.Stale(inputCleanupFor(execution.result))
        }
        val token = reserveClockOperation()
            ?: return RuntimeSubmitAdmission.Stale(inputCleanupFor(execution.result))
        val captured = captureClockOutsideGate()
        return synchronized(this) {
        val now = commitClockOperation(token, captured) ?: run {
            outstanding = null
            return@synchronized RuntimeSubmitAdmission.Failed(
                SubmissionFailure.STATE_CLOCK_INVALID_OR_OVERFLOW,
                RuntimeInputCleanup(execution.command.key, RuntimeInputCleanupOwner.SUBMIT_CALLER),
            )
        }
        if (!admittedSubmitExecutions.add(execution)) {
            return@synchronized RuntimeSubmitAdmission.Stale(inputCleanupFor(execution.result))
        }
        val task = outstanding
        if (task == null ||
            task.stage != OutstandingStage.SUBMISSION_STARTED ||
            task.submitCommand != execution.command || !task.submitAuthorizationConsumed
        ) {
            return@synchronized RuntimeSubmitAdmission.Stale(inputCleanupFor(execution.result))
        }

        if (task.submissionTimeoutTransferred || execution.result is RuntimeSubmitResult.DeadlineExpired) {
            outstanding = null
            failClosed(ProbeFailureReason.SUBMISSION_DEADLINE_EXPIRED)
            return@synchronized RuntimeSubmitAdmission.DeadlineExpired(
                RuntimeInputCleanup(execution.command.key, RuntimeInputCleanupOwner.SUBMISSION_TIMEOUT),
            )
        }
        val deadline = submissionDeadline(task) ?: run {
            outstanding = null
            return@synchronized RuntimeSubmitAdmission.Failed(
                SubmissionFailure.STATE_CLOCK_INVALID_OR_OVERFLOW,
                RuntimeInputCleanup(execution.command.key, RuntimeInputCleanupOwner.SUBMIT_CALLER),
            )
        }
        if (isPast(deadline, now)) {
            outstanding = null
            failClosed(ProbeFailureReason.SUBMISSION_DEADLINE_EXPIRED)
            return@synchronized RuntimeSubmitAdmission.DeadlineExpired(
                RuntimeInputCleanup(execution.command.key, RuntimeInputCleanupOwner.SUBMISSION_TIMEOUT),
            )
        }

        val cleanup = RuntimeInputCleanup(
            execution.command.key,
            RuntimeInputCleanupOwner.SUBMIT_CALLER,
        )
        when (val result = execution.result) {
            is RuntimeSubmitResult.Returned -> {
                task.submissionReturnedAtNs = now
                if (state == ProbeMachineState.QUARANTINED ||
                    state == ProbeMachineState.INCOMPLETE_PENDING_CLOSE
                ) {
                    outstanding = null
                } else {
                    task.stage = OutstandingStage.SUBMISSION_RETURNED
                }
                RuntimeSubmitAdmission.Returned(cleanup)
            }
            is RuntimeSubmitResult.Failed -> {
                outstanding = null
                if (state != ProbeMachineState.QUARANTINED &&
                    state != ProbeMachineState.INCOMPLETE_PENDING_CLOSE
                ) {
                    if (result.reason == SubmissionFailure.DEPENDENCY_FAILED) {
                        failIncomplete(ProbeFailureReason.SUBMISSION_FAILED)
                    } else {
                        failClosed(ProbeFailureReason.SUBMISSION_FAILED)
                    }
                }
                RuntimeSubmitAdmission.Failed(result.reason, cleanup)
            }
            is RuntimeSubmitResult.DeadlineExpired -> error("handled before clock admission")
        }
        }
    }

    fun admitResult(callback: RuntimeResultCallback): ResultAdmission {
        val entry = beginCallbackGateEntry()
        try {
            val token = reserveClockOperation(allowClosedAdmission = true)
                ?: return synchronized(this) {
                    rejectedCleanup(
                        if (entry.contractViolation) {
                            ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION
                        } else {
                            ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW
                        },
                        admittedAtNs = 0L,
                    )
                }
            val captured = captureClockOutsideGate()
            return synchronized(this) {
            val now = commitClockOperation(token, captured)
            if (now == null) {
                return@synchronized rejectedCleanup(
                    if (entry.contractViolation) {
                        ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION
                    } else {
                        ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW
                    },
                    admittedAtNs = 0L,
                )
            }
            if (entry.contractViolation) {
                return@synchronized rejectedCleanup(
                    ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION,
                    now,
                )
            }
            val task = outstanding
            if (task == null) {
                val reason = if (callback.runtimeIdentity == runtimeIdentity &&
                    callback.taskTimestampMs == lastTimedOutTaskTimestampMs
                ) {
                    ProbeFailureReason.RESULT_DEADLINE_EXPIRED
                } else {
                    ProbeFailureReason.STALE_OR_DUPLICATE_RESULT
                }
                return@synchronized rejectedCleanup(reason, now)
            }
            if (callback.runtimeIdentity != runtimeIdentity ||
                callback.taskTimestampMs <= lastResolvedTaskTimestampMs ||
                callback.taskTimestampMs != task.reservation.submission.taskTimestampMs.value ||
                state == ProbeMachineState.MEASUREMENT_COMPLETE_PENDING_CLOSE ||
                state == ProbeMachineState.CLEAN_TERMINAL_PENDING_CLOSE
            ) {
                return@synchronized rejectedCleanup(ProbeFailureReason.STALE_OR_DUPLICATE_RESULT, now)
            }
            if (task.stage != OutstandingStage.SUBMISSION_RETURNED) {
                if (task.stage == OutstandingStage.CALLBACK_RESERVED) {
                    return@synchronized rejectedCleanup(
                        ProbeFailureReason.STALE_OR_DUPLICATE_RESULT,
                        now,
                    )
                }
                if (task.stage != OutstandingStage.SUBMISSION_STARTED) {
                    outstanding = null
                }
                failIncomplete(ProbeFailureReason.RESULT_BEFORE_SUBMISSION_RETURN)
                return@synchronized rejectedCleanup(
                    ProbeFailureReason.RESULT_BEFORE_SUBMISSION_RETURN,
                    now,
                )
            }

            val deadline = resultDeadline(task)
                ?: return@synchronized rejectedCleanup(requireNotNull(failureReason), now)
            if (isPast(deadline, now)) {
                outstanding = null
                transitionResultTimeout(task, now)
                return@synchronized rejectedCleanup(ProbeFailureReason.RESULT_DEADLINE_EXPIRED, now)
            }

            val cleanup = reserveCleanup(
                admittedAtNs = now,
                kind = CleanupKind.MATCHED_RESULT,
                taskReservationToken = task.reservation.token,
                inertReason = ProbeFailureReason.STALE_OR_DUPLICATE_RESULT,
            ) ?: run {
                outstanding = null
                return@synchronized ResultAdmission.CleanupUnavailable
            }
            task.callbackCleanupToken = cleanup.token
            task.stage = OutstandingStage.CALLBACK_RESERVED
            ResultAdmission.Reserved(cleanup)
            }
        } finally {
            endCallbackGateEntry(entry)
        }
    }

    fun completeCallbackOutput(
        cleanupToken: CallbackCleanupToken,
        cleanupEvidence: CallbackOutputCleanupEvidence,
    ): CallbackResolution {
        val token = reserveClockOperation(allowClosedAdmission = true)
            ?: return CallbackResolution.Rejected(ProbeFailureReason.INVALID_TRANSITION)
        val captured = captureClockOutsideGate()
        return synchronized(this) {
        val pending = pendingCleanups[cleanupToken]
            ?: run {
                failClosed(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN)
                releaseClockOperationWithoutAdmission(token)
                return@synchronized CallbackResolution.Rejected(
                    ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
                )
            }
        val disposalDeadline = try {
            Math.addExact(pending.admittedAtNs, CALLBACK_DISPOSAL_DEADLINE_NS)
        } catch (_: ArithmeticException) {
            null
        }
        val now = commitClockOperation(token, captured)
        if (disposalDeadline == null) {
            check(pendingCleanups.remove(cleanupToken) === pending)
            clearMatchedOutstanding(pending)
            failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
            return@synchronized CallbackResolution.Rejected(
                ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW,
            )
        }
        if (now == null) {
            check(pendingCleanups.remove(cleanupToken) === pending)
            clearMatchedOutstanding(pending)
            if (cleanupEvidence != CallbackOutputCleanupEvidence.CLOSED) {
                failClosed(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN)
                return@synchronized CallbackResolution.Rejected(
                    ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
                )
            }
            if (captured == null) {
                failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
                return@synchronized CallbackResolution.Rejected(
                    ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW,
                )
            }
            if (isPast(disposalDeadline, captured)) {
                failClosed(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN)
                return@synchronized CallbackResolution.Rejected(
                    ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
                )
            }
            if (state != ProbeMachineState.INCOMPLETE_PENDING_CLOSE &&
                state != ProbeMachineState.QUARANTINED
            ) {
                failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
                return@synchronized CallbackResolution.Rejected(
                    ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW,
                )
            }
            return@synchronized CallbackResolution.StateInert(requireNotNull(failureReason))
        }
        check(pendingCleanups.remove(cleanupToken) === pending)
        if (cleanupEvidence != CallbackOutputCleanupEvidence.CLOSED || isPast(disposalDeadline, now)) {
            clearMatchedOutstanding(pending)
            failClosed(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN)
            return@synchronized CallbackResolution.Rejected(
                ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
            )
        }
        if (pending.kind == CleanupKind.STATE_INERT) {
            finalizeDrainIfEligible(now)
            return@synchronized CallbackResolution.StateInert(pending.inertReason)
        }

        val task = outstanding
        if (task == null ||
            task.stage != OutstandingStage.CALLBACK_RESERVED ||
            task.callbackCleanupToken != cleanupToken ||
            task.reservation.token != pending.taskReservationToken
        ) {
            failClosed(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN)
            return@synchronized CallbackResolution.Rejected(
                ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN,
            )
        }
        lastResolvedTaskTimestampMs = task.reservation.submission.taskTimestampMs.value
        outstanding = null

        if (state == ProbeMachineState.QUARANTINED ||
            state == ProbeMachineState.INCOMPLETE_PENDING_CLOSE
        ) {
            finalizeDrainIfEligible(now)
            return@synchronized CallbackResolution.StateInert(
                failureReason ?: ProbeFailureReason.ATTEMPT_ABORTED,
            )
        }
        if (task.reservation.phase == ProbePhase.WARMUP &&
            isPast(requireNotNull(warmupDeadlineNs), now)
        ) {
            failIncomplete(ProbeFailureReason.WARMUP_DEADLINE_EXPIRED)
            return@synchronized CallbackResolution.StateInert(
                ProbeFailureReason.WARMUP_DEADLINE_EXPIRED,
            )
        }

        when (task.reservation.phase) {
            ProbePhase.WARMUP -> {
                successfulWarmupCallbacks = checkedIncrement(successfulWarmupCallbacks)
                    ?: return@synchronized CallbackResolution.Rejected(requireNotNull(failureReason))
                if (
                    successfulWarmupCallbacks ==
                    ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS
                ) {
                    state = ProbeMachineState.AWAITING_MEASUREMENT
                }
            }
            ProbePhase.MEASUREMENT -> {
                measurementCompletions = checkedIncrement(measurementCompletions)
                    ?: return@synchronized CallbackResolution.Rejected(requireNotNull(failureReason))
                finalizeDrainIfEligible(now)
            }
        }
        CallbackResolution.Completed(
            phase = task.reservation.phase,
            successfulWarmupCallbacks = successfulWarmupCallbacks,
            measurementCompletions = measurementCompletions,
        )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onErrorListener(callback: RuntimeErrorCallback) {
        val entry = beginCallbackGateEntry()
        try {
            synchronized(this) {
                callbackGateError = true
                if (pendingClockOperations.isNotEmpty()) {
                    invalidatePendingClockOperations()
                }
                val task = outstanding
                if (task?.stage != OutstandingStage.CALLBACK_RESERVED &&
                    task?.stage != OutstandingStage.SUBMISSION_STARTED
                ) {
                    outstanding = null
                }
                failClosed(
                    if (entry.contractViolation) {
                        ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION
                    } else {
                        ProbeFailureReason.ERROR_LISTENER_RESOURCE_UNCERTAIN
                    },
                )
            }
        } finally {
            endCallbackGateEntry(entry)
        }
    }

    /** Evaluates deadlines using equality-success / strictly-greater-than-expiry. */
    fun runWatchdog() {
        val token = reserveClockOperation() ?: return
        val captured = captureClockOutsideGate()
        synchronized(this) {
        val now = commitClockOperation(token, captured) ?: return@synchronized
        pendingCleanups.values.forEach { cleanup ->
            val deadline = checkedAdd(cleanup.admittedAtNs, CALLBACK_DISPOSAL_DEADLINE_NS)
                ?: return@synchronized
            if (isPast(deadline, now)) {
                failClosed(ProbeFailureReason.CALLBACK_OUTPUT_CLEANUP_UNCERTAIN)
                return@synchronized
            }
        }

        val task = outstanding
        if (task != null) {
            when (task.stage) {
                OutstandingStage.SUBMISSION_STARTED -> {
                    val deadline = submissionDeadline(task) ?: return@synchronized
                    if (isPast(deadline, now)) {
                        task.submissionTimeoutTransferred = true
                        failClosed(ProbeFailureReason.SUBMISSION_DEADLINE_EXPIRED)
                        return@synchronized
                    }
                }
                OutstandingStage.SUBMISSION_RETURNED -> {
                    val deadline = resultDeadline(task) ?: return@synchronized
                    if (isPast(deadline, now)) {
                        outstanding = null
                        transitionResultTimeout(task, now)
                        return@synchronized
                    }
                }
                OutstandingStage.CALLBACK_RESERVED,
                OutstandingStage.RESERVED,
                -> Unit
            }
        }

        if (state == ProbeMachineState.WARMUP && isPast(requireNotNull(warmupDeadlineNs), now)) {
            if (outstanding?.stage != OutstandingStage.CALLBACK_RESERVED &&
                outstanding?.stage != OutstandingStage.SUBMISSION_STARTED
            ) {
                outstanding = null
            }
            failIncomplete(ProbeFailureReason.WARMUP_DEADLINE_EXPIRED)
            return@synchronized
        }
        if (state == ProbeMachineState.MEASURING && now >= requireNotNull(measurementEndNs)) {
            state = ProbeMachineState.DRAINING
        }
        finalizeDrainIfEligible(now)
        }
    }

    fun abortIncomplete() {
        synchronized(this) {
            if (terminalCloseOutcome() != null) return@synchronized
            if (pendingClockOperations.isNotEmpty()) {
                invalidatePendingClockOperations()
            }
            if (activeCloseClaim != null || closeResultAdmitted || !callbackAdmissionOpen) {
                return@synchronized
            }
            if (outstanding?.stage != OutstandingStage.CALLBACK_RESERVED &&
                outstanding?.stage != OutstandingStage.SUBMISSION_STARTED
            ) {
                outstanding = null
            }
            failIncomplete(ProbeFailureReason.ATTEMPT_ABORTED)
        }
    }

    /** Diagnostic only. Native close authority is issued solely by [beginClose]. */
    @Synchronized
    fun pendingCloseOutcome(): RouteAttemptOutcome? {
        if (pendingClockOperations.isNotEmpty() || inFlightCallbackCount != 0) return null
        if (activeCloseClaim != null || closeResultAdmitted || callbackGateSealed) return null
        if (outstanding?.stage == OutstandingStage.SUBMISSION_STARTED) return null
        if (outstanding != null || pendingCleanups.isNotEmpty()) return null
        return terminalCloseOutcome()
    }

    /**
     * Claims one terminal close command without closing callback admission. Callback entry remains
     * counted while native close runs, as required by ADR-011's callback gate.
     */
    @Synchronized
    fun beginClose(): ProbeCloseClaim? {
        if (!callbackAdmissionOpen || callbackGateSealed || closeResultAdmitted) return null
        if (activeCloseClaim != null) return null
        if (pendingClockOperations.isNotEmpty() || inFlightCallbackCount != 0) return null
        if (outstanding != null || pendingCleanups.isNotEmpty()) return null
        val outcome = terminalCloseOutcome() ?: return null
        return IssuedProbeCloseClaim(
            command = CloseRuntimeCommand(routeKey, runtimeIdentity),
            outcome = outcome,
        ).also { activeCloseClaim = it }
    }

    @Synchronized
    fun isGenuineCloseClaim(claim: ProbeCloseClaim): Boolean =
        claim === activeCloseClaim && !closeResultAdmitted

    /** Boundary-only handshake. It reserves one external close attempt without holding this monitor. */
    @Synchronized
    fun authorizeCloseAttempt(claim: ProbeCloseClaim): CloseRuntimeCommand? {
        val issued = activeCloseClaim ?: return null
        if (claim !== issued || closeResultAdmitted || pendingCloseCompletion != null
        ) {
            return null
        }
        if (!closeAttemptInFlight) {
            closeAttemptInFlight = true
        }
        return issued.command
    }

    /** Admits one owner/machine-bound close execution and emits an opaque planner completion. */
    @Synchronized
    fun completeClose(
        claim: ProbeCloseClaim,
        execution: RuntimeCloseExecution,
    ): ProbeCloseCompletion? {
        val issued = activeCloseClaim ?: return null
        if (claim !== issued || closeResultAdmitted || !closeAttemptInFlight ||
            pendingCloseCompletion != null ||
            !RuntimeOwnerBoundary.isGenuineCloseExecutionForMachine(execution, this, claim) ||
            execution.command != issued.command ||
            execution.result.routeKey != issued.command.routeKey ||
            execution.result.runtimeIdentity != issued.command.runtimeIdentity
        ) {
            return null
        }
        val result = execution.result
        if (result is RuntimeCloseResult.Deferred) {
            return issueCloseCompletion(
                issued,
                execution,
                ProbeCloseCompletionKind.RETRY_REQUIRED,
                retryReason = result.reason,
            )
        }

        closeAttemptInFlight = false
        closeResultAdmitted = true
        activeCloseClaim = null
        if (result is RuntimeCloseResult.Failed) {
            callbackGateError = true
            failClosed(ProbeFailureReason.RUNTIME_CLOSE_UNCERTAIN)
            return issueCloseCompletion(
                issued,
                execution,
                ProbeCloseCompletionKind.RECOVERY_REQUIRED,
                failureReason = ProbeFailureReason.RUNTIME_CLOSE_UNCERTAIN,
            )
        }

        val cleanSeal = pendingClockOperations.isEmpty() &&
            inFlightCallbackCount == 0 &&
            outstanding == null &&
            pendingCleanups.isEmpty() &&
            !callbackGateError
        if (!cleanSeal) {
            callbackGateError = true
            failClosed(ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION)
            return issueCloseCompletion(
                issued,
                execution,
                ProbeCloseCompletionKind.RECOVERY_REQUIRED,
                failureReason = ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION,
            )
        }
        callbackAdmissionOpen = false
        callbackGateSealed = true
        return issueCloseCompletion(
            issued,
            execution,
            ProbeCloseCompletionKind.SEALED,
            failureReason = this.failureReason,
        )
    }

    @Synchronized
    fun consumeCloseCompletion(
        completion: ProbeCloseCompletion,
        claim: ProbeCloseClaim,
    ): ProbeCloseCompletionRecord? {
        val issued = pendingCloseCompletion ?: return null
        if (completion !== issued || claim !== issued.claim) return null
        pendingCloseCompletion = null
        return ProbeCloseCompletionRecord(
            kind = issued.kind,
            outcome = issued.outcome,
            retryReason = issued.retryReason,
            failureReason = issued.failureReason,
        )
    }

    @Synchronized
    fun pendingCallbackCleanupCount(): Int = pendingCleanups.size

    @Synchronized
    fun submissionExternalCallInFlight(): Boolean =
        outstanding?.stage == OutstandingStage.SUBMISSION_STARTED

    @Synchronized
    fun callbackEntryInFlightCount(): Int = inFlightCallbackCount

    @Synchronized
    fun callbackAdmissionIsOpen(): Boolean = callbackAdmissionOpen

    @Synchronized
    fun callbackGateIsSealed(): Boolean = callbackGateSealed

    private fun issueCloseCompletion(
        claim: IssuedProbeCloseClaim,
        execution: RuntimeCloseExecution,
        kind: ProbeCloseCompletionKind,
        retryReason: RuntimeCloseDeferredReason? = null,
        failureReason: ProbeFailureReason? = null,
    ): ProbeCloseCompletion {
        check(pendingCloseCompletion == null)
        return IssuedProbeCloseCompletion(
            claim = claim,
            execution = execution,
            kind = kind,
            outcome = claim.outcome,
            retryReason = retryReason,
            failureReason = failureReason,
        ).also { pendingCloseCompletion = it }
    }

    private fun beginCallbackGateEntry(): CallbackGateEntry = synchronized(this) {
        val contractViolation = !callbackAdmissionOpen ||
            callbackGateSealed ||
            activeCloseClaim != null ||
            closeResultAdmitted
        val nextCount = try {
            Math.addExact(inFlightCallbackCount, 1)
        } catch (_: ArithmeticException) {
            callbackGateError = true
            callbackGateSealed = false
            failClosed(ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION)
            return@synchronized CallbackGateEntry(contractViolation = true, counted = false)
        }
        inFlightCallbackCount = nextCount
        if (contractViolation) {
            callbackGateError = true
            callbackGateSealed = false
            failClosed(ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION)
        }
        CallbackGateEntry(contractViolation = contractViolation, counted = true)
    }

    private fun endCallbackGateEntry(entry: CallbackGateEntry) {
        if (!entry.counted) return
        synchronized(this) {
            if (inFlightCallbackCount <= 0) {
                callbackGateError = true
                callbackGateSealed = false
                failClosed(ProbeFailureReason.CALLBACK_GATE_CONTRACT_VIOLATION)
                return@synchronized
            }
            inFlightCallbackCount -= 1
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as java.lang.Object).notifyAll()
        }
    }

    private fun terminalCloseOutcome(): RouteAttemptOutcome? = when (state) {
        ProbeMachineState.MEASUREMENT_COMPLETE_PENDING_CLOSE -> RouteAttemptOutcome.MEASURED
        ProbeMachineState.CLEAN_TERMINAL_PENDING_CLOSE -> RouteAttemptOutcome.CLEAN_TERMINAL
        ProbeMachineState.INCOMPLETE_PENDING_CLOSE -> RouteAttemptOutcome.INCOMPLETE
        ProbeMachineState.QUARANTINED -> RouteAttemptOutcome.RESOURCE_UNCERTAIN
        else -> null
    }

    private fun rejectedCleanup(
        reason: ProbeFailureReason,
        admittedAtNs: Long,
    ): ResultAdmission {
        val cleanup = reserveCleanup(
            admittedAtNs = admittedAtNs,
            kind = CleanupKind.STATE_INERT,
            taskReservationToken = null,
            inertReason = reason,
        ) ?: return ResultAdmission.CleanupUnavailable
        return ResultAdmission.Rejected(reason = reason, cleanup = cleanup)
    }

    private fun reserveCleanup(
        admittedAtNs: Long,
        kind: CleanupKind,
        taskReservationToken: Long?,
        inertReason: ProbeFailureReason,
    ): CallbackCleanupReservation? {
        if (cleanupTokenExhausted) {
            failClosed(ProbeFailureReason.CLEANUP_TOKEN_EXHAUSTED)
            return null
        }
        val token = CallbackCleanupToken(nextCleanupToken)
        if (nextCleanupToken == Long.MAX_VALUE) {
            cleanupTokenExhausted = true
        } else {
            nextCleanupToken += 1L
        }
        check(pendingCleanups.put(token, PendingCleanup(
            admittedAtNs,
            kind,
            taskReservationToken,
            inertReason,
        )) == null)
        return CallbackCleanupReservation(token)
    }

    private fun clearMatchedOutstanding(pending: PendingCleanup) {
        if (pending.kind == CleanupKind.MATCHED_RESULT &&
            outstanding?.reservation?.token == pending.taskReservationToken
        ) {
            outstanding = null
        }
    }

    private fun matchingOutstanding(reservationToken: Long): OutstandingTask? {
        val task = outstanding
        if (task == null || task.reservation.token != reservationToken) {
            if (state == ProbeMachineState.WARMUP ||
                state == ProbeMachineState.AWAITING_MEASUREMENT ||
                state == ProbeMachineState.MEASURING ||
                state == ProbeMachineState.DRAINING
            ) {
                failClosed(ProbeFailureReason.INVALID_TRANSITION)
            }
            return null
        }
        return task
    }

    private fun transitionResultTimeout(task: OutstandingTask, now: Long) {
        if (terminalCloseOutcome() != null) return
        lastTimedOutTaskTimestampMs = task.reservation.submission.taskTimestampMs.value
        failureReason = ProbeFailureReason.RESULT_DEADLINE_EXPIRED
        state = if (task.reservation.phase == ProbePhase.WARMUP &&
            isPast(requireNotNull(warmupDeadlineNs), now)
        ) {
            failureReason = ProbeFailureReason.WARMUP_DEADLINE_EXPIRED
            ProbeMachineState.INCOMPLETE_PENDING_CLOSE
        } else {
            ProbeMachineState.CLEAN_TERMINAL_PENDING_CLOSE
        }
    }

    private fun finalizeDrainIfEligible(now: Long) {
        if (state != ProbeMachineState.DRAINING) return
        val deadline = measurementDrainDeadline() ?: return
        if (isPast(deadline, now) && outstanding == null && pendingCleanups.isEmpty()) {
            state = ProbeMachineState.MEASUREMENT_COMPLETE_PENDING_CLOSE
        }
    }

    private fun measurementDrainDeadline(): Long? {
        val end = measurementEndNs ?: run {
            failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
            return null
        }
        return checkedAdd(end, MEASUREMENT_DRAIN_NS)
    }

    private fun resultDeadline(task: OutstandingTask): Long? {
        val returned = checkedAdd(
            requireNotNull(task.submissionReturnedAtNs),
            RESULT_CALLBACK_DEADLINE_NS,
        ) ?: return null
        val phase = when (task.reservation.phase) {
            ProbePhase.WARMUP -> requireNotNull(warmupDeadlineNs)
            ProbePhase.MEASUREMENT -> measurementDrainDeadline() ?: return null
        }
        return minOf(returned, phase)
    }

    private fun submissionDeadline(task: OutstandingTask): Long? = checkedAdd(
        requireNotNull(task.submissionStartedAtNs),
        SUBMISSION_RETURN_DEADLINE_NS,
    )

    private fun inputCleanupFor(result: RuntimeSubmitResult): RuntimeInputCleanup =
        RuntimeInputCleanup(
            submitKey = result.key,
            owner = if (result is RuntimeSubmitResult.DeadlineExpired) {
                RuntimeInputCleanupOwner.SUBMISSION_TIMEOUT
            } else {
                RuntimeInputCleanupOwner.SUBMIT_CALLER
            },
        )

    private fun allocateReservationToken(): Long? {
        val allocated = nextReservationToken
        nextReservationToken = try {
            Math.addExact(nextReservationToken, 1L)
        } catch (_: ArithmeticException) {
            failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
            return null
        }
        return allocated
    }

    /**
     * Reserves one clock admission while the state gate is held, then releases the gate.
     *
     * Different threads may capture the clock concurrently and serialize only their state commit.
     * This avoids treating ordinary callback/watchdog contention as a clock failure and avoids a
     * dependency re-entry deadlock. The same thread cannot recursively enter its own clock capture.
     */
    private fun reserveClockOperation(allowClosedAdmission: Boolean = false): Long? = synchronized(this) {
        if (!allowClosedAdmission &&
            (!callbackAdmissionOpen || callbackGateSealed ||
                activeCloseClaim != null || closeResultAdmitted)
        ) {
            return@synchronized null
        }
        val captureThread = Thread.currentThread()
        if (clockCaptureActive.get() == true) return@synchronized null
        if (pendingClockOperations.values.any { it === captureThread }) return@synchronized null
        val token = nextClockOperationToken
        nextClockOperationToken = try {
            Math.addExact(nextClockOperationToken, 1L)
        } catch (_: ArithmeticException) {
            failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
            return@synchronized null
        }
        check(pendingClockOperations.put(token, captureThread) == null)
        token
    }

    /** This is the only ProbeClock call site and is never entered while holding this monitor. */
    private fun captureClockOutsideGate(): Long? {
        check(clockCaptureActive.get() != true)
        clockCaptureActive.set(true)
        return try {
            val now = clock.nowNs()
            if (now >= 0L) now else null
        } catch (_: Throwable) {
            null
        } finally {
            clockCaptureActive.remove()
        }
    }

    /** Commits only a still-live token owned by this thread. State commits remain serial. */
    private fun commitClockOperation(token: Long, captured: Long?): Long? {
        if (pendingClockOperations[token] !== Thread.currentThread()) {
            return null
        }
        pendingClockOperations.remove(token)
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
        if (captured == null) {
            failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
        }
        return captured
    }

    private fun releaseClockOperationWithoutAdmission(token: Long) {
        if (pendingClockOperations[token] === Thread.currentThread()) {
            pendingClockOperations.remove(token)
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as java.lang.Object).notifyAll()
        }
    }

    /** Invalidates every external clock capture without waiting; later token commits are stale. */
    private fun invalidatePendingClockOperations() {
        pendingClockOperations.clear()
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
    }

    private fun checkedAdd(left: Long, right: Long): Long? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
        null
    }

    private fun checkedIncrement(value: Int): Int? = try {
        Math.addExact(value, 1)
    } catch (_: ArithmeticException) {
        failClosed(ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW)
        null
    }

    private fun failClosed(reason: ProbeFailureReason) {
        failureReason = reason
        state = ProbeMachineState.QUARANTINED
        if (reason == ProbeFailureReason.CLOCK_INVALID_OR_OVERFLOW) {
            invalidatePendingClockOperations()
        }
    }

    private fun failIncomplete(reason: ProbeFailureReason) {
        if (terminalCloseOutcome() != null) return
        failureReason = reason
        state = ProbeMachineState.INCOMPLETE_PENDING_CLOSE
    }

    private fun isPast(deadlineNs: Long, nowNs: Long): Boolean = nowNs > deadlineNs

    companion object {
        const val CANDIDATE_DURATION_NS: Long = ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS
        const val SELECTED_STEADY_DURATION_NS: Long = ProbeTimeContract.SELECTED_STEADY_DURATION_NS
        const val WARMUP_DEADLINE_NS: Long = 10_000_000_000L
        const val SUBMISSION_RETURN_DEADLINE_NS: Long = 1_000_000_000L
        const val RESULT_CALLBACK_DEADLINE_NS: Long = 1_000_000_000L
        const val CALLBACK_DISPOSAL_DEADLINE_NS: Long = 1_000_000_000L
        const val MEASUREMENT_DRAIN_NS: Long = 1_000_000_000L
    }
}
