package com.motionarcade.vision.capability.runtime

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import com.motionarcade.vision.capability.domain.TaskTimestampMs

enum class RuntimeRole {
    CANDIDATE,
    SELECTED_STEADY,
}

@JvmInline
value class ProbeAttemptEpoch(val value: ULong) {
    init {
        require(value != 0uL) { "attempt epoch must be nonzero" }
    }
}

@JvmInline
value class RuntimeGeneration(val value: Long) {
    init {
        require(value > 0L) { "runtime generation must be positive" }
    }
}

@JvmInline
value class RuntimeIdentity(val value: Long) {
    init {
        require(value > 0L) { "runtime identity must be positive" }
    }
}

data class RuntimeRouteKey(
    val attemptEpoch: ProbeAttemptEpoch,
    val routeOrdinal: Int,
    val runtimeGeneration: RuntimeGeneration,
) {
    init {
        require(routeOrdinal > 0) { "route ordinal must be positive" }
    }
}

enum class RuntimeRouteKind(
    val delegate: ProbeDelegate,
    val role: RuntimeRole,
    val measurementDurationNs: Long,
) {
    CPU_CANDIDATE(
        ProbeDelegate.CPU,
        RuntimeRole.CANDIDATE,
        ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS,
    ),
    GPU_CANDIDATE(
        ProbeDelegate.GPU,
        RuntimeRole.CANDIDATE,
        ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS,
    ),
    SELECTED_CPU(
        ProbeDelegate.CPU,
        RuntimeRole.SELECTED_STEADY,
        ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
    ),
    SELECTED_GPU(
        ProbeDelegate.GPU,
        RuntimeRole.SELECTED_STEADY,
        ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
    ),
    FALLBACK_CPU_CANDIDATE(
        ProbeDelegate.CPU,
        RuntimeRole.CANDIDATE,
        ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS,
    ),
    FALLBACK_CPU_SELECTED(
        ProbeDelegate.CPU,
        RuntimeRole.SELECTED_STEADY,
        ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
    ),
}

data class RuntimeRoute(
    val key: RuntimeRouteKey,
    val kind: RuntimeRouteKind,
) {
    val delegate: ProbeDelegate
        get() = kind.delegate

    val role: RuntimeRole
        get() = kind.role

    val measurementDurationNs: Long
        get() = kind.measurementDurationNs
}

data class ProbeAttemptContext(
    val mode: GameMode,
    val attemptEpoch: ProbeAttemptEpoch,
    val runtimeArtifactId: RuntimeArtifactId,
    val probeBaseScopeId: ProbeBaseScopeId,
)

data class RuntimeCreateRequest(
    val attempt: ProbeAttemptContext,
    val route: RuntimeRoute,
) {
    init {
        require(route.key.attemptEpoch == attempt.attemptEpoch) {
            "route and attempt epochs must match"
        }
    }
}

data class RuntimeSubmission(
    val runtimeIdentity: RuntimeIdentity,
    val frameId: Long,
    val sourceTimestampNs: SourceTimestampNs,
    val taskTimestampMs: TaskTimestampMs,
    val packetTimestampUs: Long,
) {
    init {
        require(frameId >= 0L) { "frame id must be non-negative" }
        require(packetTimestampUs >= 0L) { "packet timestamp must be non-negative" }
    }
}

data class RuntimeSubmitKey(
    val routeKey: RuntimeRouteKey,
    val reservationToken: Long,
    val runtimeIdentity: RuntimeIdentity,
    val taskTimestampMs: TaskTimestampMs,
) {
    init {
        require(reservationToken > 0L) { "reservation token must be positive" }
    }
}

/** Pure command emitted while the serialized state gate is held. */
data class SubmitRuntimeCommand(
    val key: RuntimeSubmitKey,
    val submission: RuntimeSubmission,
) {
    init {
        require(key.runtimeIdentity == submission.runtimeIdentity) {
            "submit command runtime identities must match"
        }
        require(key.taskTimestampMs == submission.taskTimestampMs) {
            "submit command task timestamps must match"
        }
    }
}

/** One-shot full-command authority issued and identity-registered by one bound state machine. */
sealed interface RuntimeSubmitAuthorization {
    val command: SubmitRuntimeCommand
}

/** Evidence returned by the dependency adapter. Only [RuntimeOwnerBoundary] may invoke it. */
enum class PoseRuntimeSubmitEvidence {
    RETURNED,
    FAILED,
    DEADLINE_EXPIRED,
}

enum class SubmissionFailure {
    RUNTIME_IDENTITY_MISMATCH,
    RUNTIME_IDENTITY_READ_THREW,
    RUNTIME_OWNERSHIP_INVALID,
    DEPENDENCY_THROW,
    DEPENDENCY_FAILED,
    COMMAND_REPLAY_IN_FLIGHT,
    COMMAND_KEY_COLLISION,
    COMMAND_OUT_OF_ORDER,
    SUBMISSION_IN_FLIGHT,
    STATE_CLOCK_INVALID_OR_OVERFLOW,
}

sealed interface RuntimeSubmitResult {
    val key: RuntimeSubmitKey

    data class Returned(
        override val key: RuntimeSubmitKey,
    ) : RuntimeSubmitResult

    data class Failed(
        override val key: RuntimeSubmitKey,
        val reason: SubmissionFailure,
    ) : RuntimeSubmitResult

    data class DeadlineExpired(
        override val key: RuntimeSubmitKey,
    ) : RuntimeSubmitResult
}

/** Opaque result of one owner-bound external call, admitted later by identity. */
sealed interface RuntimeSubmitExecution {
    val command: SubmitRuntimeCommand
    val result: RuntimeSubmitResult
}

/** Non-public Java-nest implementation bridge; callers still see a sealed outer contract. */
private interface BoundaryRuntimeSubmitExecution : RuntimeSubmitExecution

/** Opaque resource/machine binding created inside successful open before publication. */
sealed interface RuntimeMachineBinding

private interface BoundaryRuntimeMachineBinding : RuntimeMachineBinding

enum class RuntimeInputCleanupOwner {
    SUBMIT_CALLER,
    SUBMISSION_TIMEOUT,
}

data class RuntimeInputCleanup(
    val submitKey: RuntimeSubmitKey,
    val owner: RuntimeInputCleanupOwner,
)

/** Admission always names the sole owner responsible for the input lease cleanup. */
sealed interface RuntimeSubmitAdmission {
    val inputCleanup: RuntimeInputCleanup

    data class Returned(
        override val inputCleanup: RuntimeInputCleanup,
    ) : RuntimeSubmitAdmission

    data class Failed(
        val reason: SubmissionFailure,
        override val inputCleanup: RuntimeInputCleanup,
    ) : RuntimeSubmitAdmission

    data class DeadlineExpired(
        override val inputCleanup: RuntimeInputCleanup,
    ) : RuntimeSubmitAdmission

    data class Stale(
        override val inputCleanup: RuntimeInputCleanup,
    ) : RuntimeSubmitAdmission
}

enum class RuntimeCloseEvidence {
    CLEAN,
    THREW,
    TIMED_OUT,
    RESOURCE_UNCERTAIN,
}

enum class CallbackOutputCleanupEvidence {
    CLOSED,
    CLOSE_THREW,
    CLOSE_TIMED_OUT,
}

data class RuntimeResultCallback(
    val runtimeIdentity: RuntimeIdentity,
    val taskTimestampMs: Long,
    val poseCount: Int,
) {
    init {
        require(taskTimestampMs >= 0L) { "task timestamp must be non-negative" }
        require(poseCount >= 0) { "pose count must be non-negative" }
    }
}

data class RuntimeErrorCallback(
    val runtimeIdentity: RuntimeIdentity,
)

interface RuntimeCallbackPort {
    fun onResult(callback: RuntimeResultCallback)

    /**
     * Native live-stream adapters must use this overload when the callback owns an output image.
     * [RuntimeOwnerBoundary] wraps the adapter-facing port and records the exact object identity
     * before forwarding it. The legacy metadata-only overload deliberately carries no cleanup
     * provenance and therefore cannot authorize recovery clean closure.
     */
    fun onResultWithOutput(
        callback: RuntimeResultCallback,
        callbackOutput: Any,
    ) {
        onResult(callback)
    }

    fun onError(callback: RuntimeErrorCallback)
}

/**
 * One nonnegative monotonic timebase for a probe machine.
 *
 * The state gate never surrounds this call. Implementations must be non-blocking and thread-safe:
 * different machine roles may call the same instance concurrently, while each state transition
 * still commits serially. Re-entry must not mutate or replace the timebase.
 */
fun interface ProbeClock {
    fun nowNs(): Long
}

abstract class PoseRuntime {
    abstract val identity: RuntimeIdentity

    /** The concrete adapter owns native close and translates known failures to evidence. */
    abstract fun close(): RuntimeCloseEvidence
}

/** Dependency submit operation bound exactly once during native runtime creation. */
fun interface PoseRuntimeSubmissionPort {
    fun submit(submission: RuntimeSubmission): PoseRuntimeSubmitEvidence
}

/**
 * Input-aware dependency entry used by the recovery owner. The exact input object supplied to
 * [RuntimeOwnerBoundary] is passed through unchanged; a metadata-only port cannot silently accept
 * an image-backed recovery submission.
 */
fun interface PoseRuntimeInputSubmissionPort {
    fun submit(
        submission: RuntimeSubmission,
        input: Any,
    ): PoseRuntimeSubmitEvidence
}

/**
 * Sealed one-shot creation handoff. A factory receives this only from [RuntimeOwnerBoundary] and
 * must bind the fresh runtime and its dependency submit port before returning the runtime.
 */
sealed interface RuntimeCreationOwner {
    fun bind(runtime: PoseRuntime, submissionPort: PoseRuntimeSubmissionPort): Boolean

    fun bindInputAware(
        runtime: PoseRuntime,
        submissionPort: PoseRuntimeInputSubmissionPort,
    ): Boolean
}


/** Opaque proof that an exact callback output entered through the runtime-owned callback port. */
sealed interface RuntimeCallbackOutputExecution

private interface BoundaryRuntimeCallbackOutputExecution : RuntimeCallbackOutputExecution

private interface BoundaryRuntimeCreationOwner : RuntimeCreationOwner

fun interface FreshPoseRuntimeFactory {
    fun create(
        request: RuntimeCreateRequest,
        callbacks: RuntimeCallbackPort,
        owner: RuntimeCreationOwner,
    ): PoseRuntime
}

/** Synchronous fail-closed gate invoked by RuntimeOwnerBoundary immediately before native create. */
fun interface RuntimeNativeCreateGate {
    fun isCurrentSafe(): Boolean
}

enum class RouteAttemptOutcome {
    MEASURED,
    CLEAN_TERMINAL,
    INCOMPLETE,
    RESOURCE_UNCERTAIN,
}

data class RouteCompletion(
    val routeKey: RuntimeRouteKey,
    val runtimeIdentity: RuntimeIdentity,
    val outcome: RouteAttemptOutcome,
)

data class OpenRuntimeCommand(
    val request: RuntimeCreateRequest,
)

data class CloseRuntimeCommand(
    val routeKey: RuntimeRouteKey,
    val runtimeIdentity: RuntimeIdentity,
)

sealed interface RuntimeOpenFailure {
    data class AuthorizationDenied(val reason: NativeCreateDenialReason) : RuntimeOpenFailure

    data object AUTHORIZER_THREW : RuntimeOpenFailure

    data object DUPLICATE_RUNTIME_CREATE_REQUEST : RuntimeOpenFailure

    data object CREATE_THREW : RuntimeOpenFailure

    data object OWNERSHIP_GENERATION_EXHAUSTED : RuntimeOpenFailure

    data object PRE_CREATE_SAFETY_REJECTED : RuntimeOpenFailure

    data class CreateThrewAfterBinding(
        val cleanupOutcome: RuntimeCreateCleanupOutcome,
    ) : RuntimeOpenFailure

    data class CreationBindingInvalid(
        val cleanupOutcome: RuntimeCreateCleanupOutcome,
    ) : RuntimeOpenFailure

    data class IdentityReadThrew(
        val cleanupOutcome: RuntimeCreateCleanupOutcome,
    ) : RuntimeOpenFailure
}

/** Exact cleanup evidence for a resource whose create path could not publish ownership. */
enum class RuntimeCreateCleanupOutcome {
    CLEAN,
    REPORTED_FAILURE,
    CALL_THREW,
    TIMED_OUT,
    RESOURCE_UNCERTAIN,
}

sealed interface RuntimeOpenResult {
    val routeKey: RuntimeRouteKey

    data class Opened(
        override val routeKey: RuntimeRouteKey,
        val runtimeIdentity: RuntimeIdentity,
    ) : RuntimeOpenResult

    data class Failed(
        override val routeKey: RuntimeRouteKey,
        val reason: RuntimeOpenFailure,
    ) : RuntimeOpenResult
}

/** Opaque owner-bound result. No runtime or raw ownership claim is exposed. */
sealed interface RuntimeOpenExecution {
    val result: RuntimeOpenResult
    val ownsRuntime: Boolean
}

private interface BoundaryRuntimeOpenExecution : RuntimeOpenExecution

enum class RuntimeCloseFailure {
    RUNTIME_ROUTE_MISMATCH,
    RUNTIME_IDENTITY_MISMATCH,
    RUNTIME_OWNERSHIP_INVALID,
    CLOSE_THREW,
    CLOSE_TIMED_OUT,
    RESOURCE_UNCERTAIN,
}

/** A close attempt that performed no native close and may be retried with the same command. */
enum class RuntimeCloseDeferredReason {
    SUBMISSION_IN_FLIGHT,
    CLOSE_IN_FLIGHT,
}

sealed interface RuntimeCloseResult {
    val routeKey: RuntimeRouteKey
    val runtimeIdentity: RuntimeIdentity

    data class Clean(
        override val routeKey: RuntimeRouteKey,
        override val runtimeIdentity: RuntimeIdentity,
    ) : RuntimeCloseResult

    data class Failed(
        override val routeKey: RuntimeRouteKey,
        override val runtimeIdentity: RuntimeIdentity,
        val reason: RuntimeCloseFailure,
    ) : RuntimeCloseResult

    data class Deferred(
        override val routeKey: RuntimeRouteKey,
        override val runtimeIdentity: RuntimeIdentity,
        val reason: RuntimeCloseDeferredReason,
    ) : RuntimeCloseResult
}

/** Owner-bound native close evidence. Raw [RuntimeCloseResult] is never an admission authority. */
sealed interface RuntimeCloseExecution {
    val command: CloseRuntimeCommand
    val result: RuntimeCloseResult
}

private interface BoundaryRuntimeCloseExecution : RuntimeCloseExecution

/** Separate evidence type for planner-authorized orphan cleanup; never advances an active route. */
sealed interface OrphanRuntimeCloseExecution {
    val command: CloseRuntimeCommand
    val result: RuntimeCloseResult
}

private interface BoundaryOrphanRuntimeCloseExecution : OrphanRuntimeCloseExecution

/* Java-nest boundary helpers. These assemble non-authority values around inline JVM types. */
fun boundaryRuntimeIdentityValue(runtime: PoseRuntime): Long = runtime.identity.value

fun boundaryResultCallbackIdentityValue(callback: RuntimeResultCallback): Long =
    callback.runtimeIdentity.value

fun boundaryOpenedResult(
    routeKey: RuntimeRouteKey,
    runtimeIdentityValue: Long,
): RuntimeOpenResult.Opened = RuntimeOpenResult.Opened(
    routeKey,
    RuntimeIdentity(runtimeIdentityValue),
)

fun boundarySubmitIdentityValue(command: SubmitRuntimeCommand): Long =
    command.key.runtimeIdentity.value

fun boundarySubmitTaskTimestampValue(command: SubmitRuntimeCommand): Long =
    command.key.taskTimestampMs.value

fun boundarySubmitReservationToken(command: SubmitRuntimeCommand): Long =
    command.key.reservationToken

fun boundaryOpenedIdentityValue(result: RuntimeOpenResult.Opened): Long =
    result.runtimeIdentity.value

fun boundaryCloseIdentityValue(command: CloseRuntimeCommand): Long =
    command.runtimeIdentity.value

fun boundaryCloseResultIdentityValue(result: RuntimeCloseResult): Long =
    result.runtimeIdentity.value

fun boundaryCloseClean(command: CloseRuntimeCommand): RuntimeCloseResult.Clean =
    RuntimeCloseResult.Clean(command.routeKey, command.runtimeIdentity)

fun boundaryCloseFailed(
    command: CloseRuntimeCommand,
    reason: RuntimeCloseFailure,
): RuntimeCloseResult.Failed = RuntimeCloseResult.Failed(
    command.routeKey,
    command.runtimeIdentity,
    reason,
)

fun boundaryCloseDeferred(
    command: CloseRuntimeCommand,
    reason: RuntimeCloseDeferredReason,
): RuntimeCloseResult.Deferred = RuntimeCloseResult.Deferred(
    command.routeKey,
    command.runtimeIdentity,
    reason,
)

fun boundaryProbeStateMachine(
    binding: RuntimeMachineBinding,
    routeKey: RuntimeRouteKey,
    runtimeIdentityValue: Long,
    role: RuntimeRole,
    clock: ProbeClock,
): ProbeStateMachine = ProbeStateMachine(
    machineBinding = binding,
    routeKey = routeKey,
    runtimeIdentity = RuntimeIdentity(runtimeIdentityValue),
    role = role,
    clock = clock,
    initialCleanupToken = 1L,
)
