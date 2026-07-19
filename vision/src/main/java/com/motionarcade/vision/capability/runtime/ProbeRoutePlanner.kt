package com.motionarcade.vision.capability.runtime

import com.motionarcade.vision.capability.domain.ProbeDelegate
import java.util.Collections
import java.util.IdentityHashMap

enum class RoutePlannerQuarantineReason {
    CALLBACK_RESOURCE_UNCERTAIN,
    NATIVE_CREATE_UNCERTAIN,
    NATIVE_CLOSE_UNCERTAIN,
    RUNTIME_IDENTITY_REUSED,
    ARITHMETIC_OVERFLOW,
}

enum class IgnoredRouteEventReason {
    INVALID_TRANSITION,
    UNTRUSTED_OPEN_EXECUTION,
    STALE_ATTEMPT_OR_ROUTE,
    RUNTIME_IDENTITY_MISMATCH,
    OPEN_EXECUTION_ALREADY_CONSUMED,
}

enum class OrphanOpenedRuntimeReason {
    NO_PENDING_OPEN,
    ROUTE_KEY_MISMATCH,
    RUNTIME_IDENTITY_REUSED,
}

sealed interface RouteTransition {
    data class OpenRequested(val command: OpenRuntimeCommand) : RouteTransition

    class RuntimeOpened internal constructor(
        val route: RuntimeRoute,
        val runtimeIdentity: RuntimeIdentity,
        /** Ownership is transferred to the external runtime owner. */
        val execution: RuntimeOpenExecution,
    ) : RouteTransition

    class OrphanRuntimeCloseRequested internal constructor(
        /** Ownership is transferred to the external cleanup owner. */
        val cleanup: OrphanRuntimeCleanup,
    ) : RouteTransition

    data class OrphanRuntimeCleaned(
        val reason: OrphanOpenedRuntimeReason,
    ) : RouteTransition

    class CloseRequested internal constructor(
        val claim: ProbeCloseClaim,
        /** Ownership is transferred to the external cleanup owner. */
        val execution: RuntimeOpenExecution,
    ) : RouteTransition {
        val command: CloseRuntimeCommand
            get() = claim.command
    }

    data class NativeCreateDenied(
        val route: RuntimeRoute,
        val reason: NativeCreateDenialReason,
    ) : RouteTransition

    data class AwaitingSelectedDelegate(
        val cpuCandidate: RouteAttemptOutcome,
        val gpuCandidate: RouteAttemptOutcome,
    ) : RouteTransition

    data class Completed(val outcome: RouteAttemptOutcome) : RouteTransition

    data class Incomplete(val reason: RouteAttemptOutcome = RouteAttemptOutcome.INCOMPLETE) :
        RouteTransition

    data class Quarantined(val reason: RoutePlannerQuarantineReason) : RouteTransition

    data class Ignored(val reason: IgnoredRouteEventReason) : RouteTransition
}

private enum class PlannerStage {
    READY,
    OPEN_PENDING,
    ACTIVE,
    CLOSE_PENDING,
    AWAITING_SELECTION,
    TERMINAL,
    QUARANTINED,
}

private data class ActiveRuntime(
    val request: RuntimeCreateRequest,
    val identity: RuntimeIdentity,
    val execution: RuntimeOpenExecution,
    val machine: ProbeStateMachine,
)

private data class PendingClose(
    val active: ActiveRuntime,
    val claim: ProbeCloseClaim,
    val outcome: RouteAttemptOutcome,
    val quarantineAfterClose: RoutePlannerQuarantineReason? = null,
)

class OrphanRuntimeCleanupToken internal constructor()

class OrphanRuntimeCleanup internal constructor(
    val token: OrphanRuntimeCleanupToken,
    val command: CloseRuntimeCommand,
    val execution: RuntimeOpenExecution,
    val reason: OrphanOpenedRuntimeReason,
)

private data class PendingOrphanCleanup(
    val cleanup: OrphanRuntimeCleanup,
    val quarantineAfterClose: RoutePlannerQuarantineReason?,
    var closeAttemptInFlight: Boolean = false,
)

/**
 * Serialized pure route coordinator.
 *
 * It never calls authorization, create, or close. Those external operations are represented
 * by commands and run through [RuntimeOwnerBoundary] without this state gate held.
 */
class ProbeRoutePlanner(
    private val attempt: ProbeAttemptContext,
) {
    private var stage = PlannerStage.READY
    private var nextOrdinal = 1
    private var nextRuntimeGeneration = 1L
    private var pendingOpen: RuntimeCreateRequest? = null
    private var active: ActiveRuntime? = null
    private var pendingClose: PendingClose? = null
    private val pendingOrphanCleanups = mutableMapOf<OrphanRuntimeCleanupToken, PendingOrphanCleanup>()
    private val admittedOpenExecutions = Collections.newSetFromMap(
        IdentityHashMap<RuntimeOpenExecution, Boolean>(),
    )
    private val usedRuntimeIdentities = mutableSetOf<RuntimeIdentity>()
    private var cpuCandidateOutcome: RouteAttemptOutcome? = null
    private var gpuCandidateOutcome: RouteAttemptOutcome? = null
    private var fallbackConsumed = false

    @Synchronized
    fun start(): RouteTransition {
        if (stage != PlannerStage.READY) {
            return ignored(IgnoredRouteEventReason.INVALID_TRANSITION)
        }
        return requestOpen(RuntimeRouteKind.CPU_CANDIDATE)
    }

    @Synchronized
    fun acceptOpenExecution(execution: RuntimeOpenExecution): RouteTransition {
        if (!RuntimeOwnerBoundary.isGenuineOpenExecution(execution)) {
            return ignored(IgnoredRouteEventReason.UNTRUSTED_OPEN_EXECUTION)
        }
        if (!admittedOpenExecutions.add(execution)) {
            return ignored(IgnoredRouteEventReason.OPEN_EXECUTION_ALREADY_CONSUMED)
        }
        val result = execution.result
        if (result is RuntimeOpenResult.Failed) {
            return acceptFailedOpen(result)
        }

        result as RuntimeOpenResult.Opened
        if (!execution.ownsRuntime) {
            return ignored(IgnoredRouteEventReason.OPEN_EXECUTION_ALREADY_CONSUMED)
        }
        val identityReused = !usedRuntimeIdentities.add(result.runtimeIdentity)
        val expected = pendingOpen
        if (expected == null || stage != PlannerStage.OPEN_PENDING) {
            if (active?.execution === execution) {
                return ignored(IgnoredRouteEventReason.OPEN_EXECUTION_ALREADY_CONSUMED)
            }
            return scheduleOrphanCleanup(
                result,
                execution,
                if (identityReused) {
                    OrphanOpenedRuntimeReason.RUNTIME_IDENTITY_REUSED
                } else {
                    OrphanOpenedRuntimeReason.NO_PENDING_OPEN
                },
                quarantineAfterClose = if (identityReused) {
                    RoutePlannerQuarantineReason.RUNTIME_IDENTITY_REUSED
                } else {
                    null
                },
            )
        }
        if (result.routeKey != expected.route.key) {
            return scheduleOrphanCleanup(
                result,
                execution,
                if (identityReused) {
                    OrphanOpenedRuntimeReason.RUNTIME_IDENTITY_REUSED
                } else {
                    OrphanOpenedRuntimeReason.ROUTE_KEY_MISMATCH
                },
                quarantineAfterClose = if (identityReused) {
                    RoutePlannerQuarantineReason.RUNTIME_IDENTITY_REUSED
                } else {
                    null
                },
            )
        }
        pendingOpen = null

        if (identityReused) {
            stage = PlannerStage.QUARANTINED
            return scheduleOrphanCleanup(
                result,
                execution,
                OrphanOpenedRuntimeReason.RUNTIME_IDENTITY_REUSED,
                quarantineAfterClose = RoutePlannerQuarantineReason.RUNTIME_IDENTITY_REUSED,
            )
        }
        val machine = RuntimeOwnerBoundary.stateMachine(execution)
            ?: return ignored(IgnoredRouteEventReason.UNTRUSTED_OPEN_EXECUTION)
        active = ActiveRuntime(expected, result.runtimeIdentity, execution, machine)
        stage = PlannerStage.ACTIVE
        return RouteTransition.RuntimeOpened(expected.route, result.runtimeIdentity, execution)
    }

    @Synchronized
    fun acceptOrphanCloseExecution(
        token: OrphanRuntimeCleanupToken,
        execution: OrphanRuntimeCloseExecution,
    ): RouteTransition {
        val pending = pendingOrphanCleanups[token]
            ?: return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        if (!RuntimeOwnerBoundary.isGenuineOrphanCloseExecution(
                execution,
                this,
                pending.cleanup,
            )
        ) {
            return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        }
        if (!pending.closeAttemptInFlight) {
            return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        }
        pending.closeAttemptInFlight = false
        val result = execution.result
        if (result.routeKey != pending.cleanup.command.routeKey) {
            return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        }
        if (result.runtimeIdentity != pending.cleanup.command.runtimeIdentity) {
            return ignored(IgnoredRouteEventReason.RUNTIME_IDENTITY_MISMATCH)
        }
        if (result is RuntimeCloseResult.Deferred) {
            return RouteTransition.OrphanRuntimeCloseRequested(pending.cleanup)
        }
        pendingOrphanCleanups.remove(token)
        if (result is RuntimeCloseResult.Failed) {
            return quarantineAfterClosingActive(RoutePlannerQuarantineReason.NATIVE_CLOSE_UNCERTAIN)
        }
        pending.quarantineAfterClose?.let { reason ->
            return quarantineAfterClosingActive(reason)
        }
        return RouteTransition.OrphanRuntimeCleaned(pending.cleanup.reason)
    }

    @Synchronized
    fun pendingOrphanCleanupCount(): Int = pendingOrphanCleanups.size

    @Synchronized
    fun authorizeOrphanClose(cleanup: OrphanRuntimeCleanup): OrphanRuntimeCleanup? {
        val pending = pendingOrphanCleanups[cleanup.token] ?: return null
        if (pending.cleanup !== cleanup || pending.closeAttemptInFlight) return null
        pending.closeAttemptInFlight = true
        return cleanup
    }

    private fun acceptFailedOpen(result: RuntimeOpenResult.Failed): RouteTransition {
        val expected = pendingOpen
            ?: return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        if (stage != PlannerStage.OPEN_PENDING || result.routeKey != expected.route.key) {
            return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        }
        pendingOpen = null
        return when (val reason = result.reason) {
            is RuntimeOpenFailure.AuthorizationDenied -> {
                stage = PlannerStage.TERMINAL
                RouteTransition.NativeCreateDenied(expected.route, reason.reason)
            }
            RuntimeOpenFailure.AUTHORIZER_THREW,
            RuntimeOpenFailure.DUPLICATE_RUNTIME_CREATE_REQUEST,
            RuntimeOpenFailure.CREATE_THREW,
            RuntimeOpenFailure.OWNERSHIP_GENERATION_EXHAUSTED,
            RuntimeOpenFailure.PRE_CREATE_SAFETY_REJECTED,
            is RuntimeOpenFailure.CreateThrewAfterBinding,
            is RuntimeOpenFailure.CreationBindingInvalid,
            is RuntimeOpenFailure.IdentityReadThrew,
            -> quarantine(RoutePlannerQuarantineReason.NATIVE_CREATE_UNCERTAIN)
        }
    }

    @Synchronized
    fun completeActive(claim: ProbeCloseClaim): RouteTransition {
        val current = active
            ?: return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        if (stage != PlannerStage.ACTIVE || !current.machine.isGenuineCloseClaim(claim) ||
            claim.command.routeKey != current.request.route.key
        ) {
            return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        }
        if (claim.command.runtimeIdentity != current.identity) {
            return ignored(IgnoredRouteEventReason.RUNTIME_IDENTITY_MISMATCH)
        }

        active = null
        pendingClose = PendingClose(current, claim, claim.outcome)
        stage = PlannerStage.CLOSE_PENDING
        return RouteTransition.CloseRequested(
            claim,
            current.execution,
        )
    }

    @Synchronized
    fun acceptCloseCompletion(completion: ProbeCloseCompletion): RouteTransition {
        val closing = pendingClose
            ?: return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        if (stage != PlannerStage.CLOSE_PENDING) {
            return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        }
        val record = closing.active.machine.consumeCloseCompletion(completion, closing.claim)
            ?: return ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE)
        if (record.kind == ProbeCloseCompletionKind.RETRY_REQUIRED) {
            return RouteTransition.CloseRequested(
                closing.claim,
                closing.active.execution,
            )
        }
        pendingClose = null
        if (record.kind == ProbeCloseCompletionKind.RECOVERY_REQUIRED) {
            return quarantine(
                if (record.failureReason == ProbeFailureReason.RUNTIME_CLOSE_UNCERTAIN) {
                    RoutePlannerQuarantineReason.NATIVE_CLOSE_UNCERTAIN
                } else {
                    RoutePlannerQuarantineReason.CALLBACK_RESOURCE_UNCERTAIN
                },
            )
        }
        closing.quarantineAfterClose?.let { return quarantine(it) }
        if (record.outcome == RouteAttemptOutcome.RESOURCE_UNCERTAIN) {
            return quarantine(RoutePlannerQuarantineReason.CALLBACK_RESOURCE_UNCERTAIN)
        }
        return advanceAfterCleanClose(closing.active.request.route.kind, record.outcome)
    }

    @Synchronized
    fun select(delegate: ProbeDelegate): RouteTransition {
        if (stage != PlannerStage.AWAITING_SELECTION) {
            return ignored(IgnoredRouteEventReason.INVALID_TRANSITION)
        }
        val candidateOutcome = when (delegate) {
            ProbeDelegate.CPU -> cpuCandidateOutcome
            ProbeDelegate.GPU -> gpuCandidateOutcome
            ProbeDelegate.NONE -> null
        }
        if (candidateOutcome != RouteAttemptOutcome.MEASURED) {
            return ignored(IgnoredRouteEventReason.INVALID_TRANSITION)
        }
        return requestOpen(
            when (delegate) {
                ProbeDelegate.CPU -> RuntimeRouteKind.SELECTED_CPU
                ProbeDelegate.GPU -> RuntimeRouteKind.SELECTED_GPU
                ProbeDelegate.NONE -> error("NONE was rejected above")
            },
        )
    }

    private fun advanceAfterCleanClose(
        kind: RuntimeRouteKind,
        outcome: RouteAttemptOutcome,
    ): RouteTransition = when (kind) {
        RuntimeRouteKind.CPU_CANDIDATE -> completeCpuCandidate(outcome)
        RuntimeRouteKind.GPU_CANDIDATE -> completeGpuCandidate(outcome)
        RuntimeRouteKind.SELECTED_CPU -> completeSelectedCpu(outcome)
        RuntimeRouteKind.SELECTED_GPU -> completeSelectedGpu(outcome)
        RuntimeRouteKind.FALLBACK_CPU_CANDIDATE -> completeFallbackCandidate(outcome)
        RuntimeRouteKind.FALLBACK_CPU_SELECTED -> completeFallbackSelected(outcome)
    }

    private fun completeCpuCandidate(outcome: RouteAttemptOutcome): RouteTransition {
        cpuCandidateOutcome = outcome
        if (outcome == RouteAttemptOutcome.INCOMPLETE) return terminalIncomplete()
        return requestOpen(RuntimeRouteKind.GPU_CANDIDATE)
    }

    private fun completeGpuCandidate(outcome: RouteAttemptOutcome): RouteTransition {
        gpuCandidateOutcome = outcome
        if (outcome == RouteAttemptOutcome.INCOMPLETE) return terminalIncomplete()
        val cpu = requireNotNull(cpuCandidateOutcome)
        if (cpu == RouteAttemptOutcome.CLEAN_TERMINAL &&
            outcome == RouteAttemptOutcome.CLEAN_TERMINAL
        ) {
            return terminal(RouteAttemptOutcome.CLEAN_TERMINAL)
        }
        stage = PlannerStage.AWAITING_SELECTION
        return RouteTransition.AwaitingSelectedDelegate(cpu, outcome)
    }

    private fun completeSelectedCpu(outcome: RouteAttemptOutcome): RouteTransition = when (outcome) {
        RouteAttemptOutcome.MEASURED -> terminal(RouteAttemptOutcome.MEASURED)
        RouteAttemptOutcome.INCOMPLETE -> terminalIncomplete()
        RouteAttemptOutcome.RESOURCE_UNCERTAIN ->
            quarantine(RoutePlannerQuarantineReason.CALLBACK_RESOURCE_UNCERTAIN)
        RouteAttemptOutcome.CLEAN_TERMINAL -> {
            if (gpuCandidateOutcome == RouteAttemptOutcome.CLEAN_TERMINAL) {
                terminal(RouteAttemptOutcome.CLEAN_TERMINAL)
            } else {
                // A measured/nonterminal GPU candidate may not be silently discarded.
                terminalIncomplete()
            }
        }
    }

    private fun completeSelectedGpu(outcome: RouteAttemptOutcome): RouteTransition = when (outcome) {
        RouteAttemptOutcome.MEASURED -> terminal(RouteAttemptOutcome.MEASURED)
        RouteAttemptOutcome.INCOMPLETE -> terminalIncomplete()
        RouteAttemptOutcome.RESOURCE_UNCERTAIN ->
            quarantine(RoutePlannerQuarantineReason.CALLBACK_RESOURCE_UNCERTAIN)
        RouteAttemptOutcome.CLEAN_TERMINAL -> {
            when {
                cpuCandidateOutcome == RouteAttemptOutcome.MEASURED && !fallbackConsumed -> {
                    fallbackConsumed = true
                    requestOpen(RuntimeRouteKind.FALLBACK_CPU_CANDIDATE)
                }
                cpuCandidateOutcome == RouteAttemptOutcome.CLEAN_TERMINAL ->
                    terminal(RouteAttemptOutcome.CLEAN_TERMINAL)
                else -> terminalIncomplete()
            }
        }
    }

    private fun completeFallbackCandidate(outcome: RouteAttemptOutcome): RouteTransition = when (outcome) {
        RouteAttemptOutcome.MEASURED -> requestOpen(RuntimeRouteKind.FALLBACK_CPU_SELECTED)
        RouteAttemptOutcome.CLEAN_TERMINAL -> terminal(RouteAttemptOutcome.CLEAN_TERMINAL)
        RouteAttemptOutcome.INCOMPLETE -> terminalIncomplete()
        RouteAttemptOutcome.RESOURCE_UNCERTAIN ->
            quarantine(RoutePlannerQuarantineReason.CALLBACK_RESOURCE_UNCERTAIN)
    }

    private fun completeFallbackSelected(outcome: RouteAttemptOutcome): RouteTransition = when (outcome) {
        RouteAttemptOutcome.MEASURED -> terminal(RouteAttemptOutcome.MEASURED)
        RouteAttemptOutcome.CLEAN_TERMINAL -> terminal(RouteAttemptOutcome.CLEAN_TERMINAL)
        RouteAttemptOutcome.INCOMPLETE -> terminalIncomplete()
        RouteAttemptOutcome.RESOURCE_UNCERTAIN ->
            quarantine(RoutePlannerQuarantineReason.CALLBACK_RESOURCE_UNCERTAIN)
    }

    private fun requestOpen(kind: RuntimeRouteKind): RouteTransition {
        val key = try {
            RuntimeRouteKey(
                attemptEpoch = attempt.attemptEpoch,
                routeOrdinal = nextOrdinal,
                runtimeGeneration = RuntimeGeneration(nextRuntimeGeneration),
            )
        } catch (_: IllegalArgumentException) {
            return quarantine(RoutePlannerQuarantineReason.ARITHMETIC_OVERFLOW)
        }
        nextOrdinal = try {
            Math.addExact(nextOrdinal, 1)
        } catch (_: ArithmeticException) {
            return quarantine(RoutePlannerQuarantineReason.ARITHMETIC_OVERFLOW)
        }
        nextRuntimeGeneration = try {
            Math.addExact(nextRuntimeGeneration, 1L)
        } catch (_: ArithmeticException) {
            return quarantine(RoutePlannerQuarantineReason.ARITHMETIC_OVERFLOW)
        }
        val request = RuntimeCreateRequest(attempt, RuntimeRoute(key, kind))
        pendingOpen = request
        stage = PlannerStage.OPEN_PENDING
        return RouteTransition.OpenRequested(OpenRuntimeCommand(request))
    }

    private fun terminal(outcome: RouteAttemptOutcome): RouteTransition {
        stage = PlannerStage.TERMINAL
        return RouteTransition.Completed(outcome)
    }

    private fun terminalIncomplete(): RouteTransition {
        stage = PlannerStage.TERMINAL
        return RouteTransition.Incomplete()
    }

    private fun quarantine(reason: RoutePlannerQuarantineReason): RouteTransition.Quarantined {
        stage = PlannerStage.QUARANTINED
        pendingOpen = null
        active = null
        pendingClose = null
        return RouteTransition.Quarantined(reason)
    }

    private fun scheduleOrphanCleanup(
        result: RuntimeOpenResult.Opened,
        execution: RuntimeOpenExecution,
        reason: OrphanOpenedRuntimeReason,
        quarantineAfterClose: RoutePlannerQuarantineReason?,
    ): RouteTransition.OrphanRuntimeCloseRequested {
        val token = OrphanRuntimeCleanupToken()
        val cleanup = OrphanRuntimeCleanup(
            token = token,
            command = CloseRuntimeCommand(result.routeKey, result.runtimeIdentity),
            execution = execution,
            reason = reason,
        )
        check(pendingOrphanCleanups.put(
            token,
            PendingOrphanCleanup(cleanup, quarantineAfterClose),
        ) == null)
        return RouteTransition.OrphanRuntimeCloseRequested(cleanup)
    }

    private fun quarantineAfterClosingActive(
        reason: RoutePlannerQuarantineReason,
    ): RouteTransition {
        val current = active ?: return quarantine(reason)
        current.machine.abortIncomplete()
        val claim = current.machine.beginClose() ?: return quarantine(reason)
        active = null
        pendingClose = PendingClose(
            active = current,
            claim = claim,
            outcome = RouteAttemptOutcome.RESOURCE_UNCERTAIN,
            quarantineAfterClose = reason,
        )
        stage = PlannerStage.CLOSE_PENDING
        return RouteTransition.CloseRequested(
            claim,
            current.execution,
        )
    }

    private fun ignored(reason: IgnoredRouteEventReason): RouteTransition.Ignored =
        RouteTransition.Ignored(reason)

}
