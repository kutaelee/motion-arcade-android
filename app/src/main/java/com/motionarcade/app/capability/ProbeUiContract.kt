package com.motionarcade.app.capability

import com.motionarcade.core.capability.EffectCapability
import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityDerivation
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityModeOutcome
import com.motionarcade.vision.capability.domain.CapabilityOutcomePolicy
import com.motionarcade.vision.capability.domain.ModeCapabilityEvidence
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.ProbeTimeContract

enum class ProbeUiStage {
    PREPARING,
    WARMUP,
    CANDIDATE_MEASUREMENT,
    SELECTED_MEASUREMENT,
    FINALIZING,
    REPOSITION_REQUIRED,
    VERIFIED,
    CANCELLED,
    INCOMPLETE,
    RESTART_REQUIRED,
}

enum class ProbeUiReasonCategory {
    CLEAN_INCOMPLETE,
    REPOSITION,
    CANCELLED,
    RESTART_REQUIRED,
    LOCAL_ONLY,
}

/** Finite presentation vocabulary; no raw engine message or generic metadata is retained. */
enum class ProbeUiReason(val category: ProbeUiReasonCategory) {
    ARTIFACT_OR_BUILD_INVALID(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    PROFILE_INVALID(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    CAMERA_PERMISSION_REVOKED(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    LIFECYCLE_INTERRUPTED(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    CAMERA_BIND_FAILED(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    CAMERA_STALLED(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    SCOPE_CHANGED(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    TIMEBASE_INVALID(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    SOURCE_SEQUENCE_INVALID(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    CLOCK_INVALID(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    CALLBACK_CORRELATION_INVALID(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    MEASUREMENT_INCOMPLETE(ProbeUiReasonCategory.CLEAN_INCOMPLETE),
    WORKLOAD_NOT_PRESENT_OR_UNSTABLE(ProbeUiReasonCategory.REPOSITION),
    USER_CANCELLED(ProbeUiReasonCategory.CANCELLED),
    ERROR_LISTENER(ProbeUiReasonCategory.RESTART_REQUIRED),
    RESOURCE_UNCERTAIN(ProbeUiReasonCategory.RESTART_REQUIRED),
    PERSISTENCE_NOT_COMMITTED(ProbeUiReasonCategory.RESTART_REQUIRED),
    PERSISTENCE_OUTCOME_UNKNOWN(ProbeUiReasonCategory.RESTART_REQUIRED),
    MALFORMED_ENGINE_TRANSITION(ProbeUiReasonCategory.LOCAL_ONLY),
    START_REQUEST_ID_EXHAUSTED(ProbeUiReasonCategory.LOCAL_ONLY),
}

enum class ProbeIncompleteRecovery {
    REQUEST_PERMISSION,
    RETRY,
}

data class ProbeAttemptIdentity(
    val sessionId: Long,
    val attemptId: Long,
) {
    init {
        require(sessionId > 0L)
        require(attemptId > 0L)
    }
}

/** Opaque UI-issued correlation token. It is never an engine route or work authorization. */
data class ProbeStartRequestId(val value: Long) {
    init {
        require(value > 0L)
    }
}

data class ProbeAttemptCursor(
    val startRequestId: ProbeStartRequestId,
    val identity: ProbeAttemptIdentity,
    val mode: GameMode,
    val revision: Long,
) {
    init {
        require(revision > 0L)
    }
}

/** Aggregate-only display projection of an engine-validated mode record. */
data class ModeMeasurementSummary(
    val mode: GameMode,
    val outcome: CapabilityModeOutcome,
    val selectedDelegate: ProbeDelegate,
    val representativeCompletionCount: Long,
    val durationNs: Long,
    val representativeInferenceP95Ns: Long?,
    val maximumRepresentativeFrameAgeNs: Long?,
) {
    init {
        require(representativeCompletionCount >= 0L)
        require(durationNs >= 0L)
        require(representativeInferenceP95Ns == null || representativeInferenceP95Ns >= 0L)
        require(maximumRepresentativeFrameAgeNs == null || maximumRepresentativeFrameAgeNs >= 0L)

        val outcomeMatchesMode = when (mode) {
            GameMode.SOLO -> outcome == CapabilityModeOutcome.SOLO_SUPPORT ||
                outcome == CapabilityModeOutcome.SOLO_BELOW_FLOOR ||
                outcome == CapabilityModeOutcome.SCOPED_UNSUPPORTED
            GameMode.DUAL -> outcome == CapabilityModeOutcome.DUAL_FULL ||
                outcome == CapabilityModeOutcome.DUAL_CONDITIONAL ||
                outcome == CapabilityModeOutcome.DUAL_BELOW_FLOOR ||
                outcome == CapabilityModeOutcome.SCOPED_UNSUPPORTED
        }
        require(outcomeMatchesMode)

        if (outcome == CapabilityModeOutcome.SCOPED_UNSUPPORTED) {
            require(selectedDelegate == ProbeDelegate.NONE)
            require(representativeCompletionCount == 0L)
            require(durationNs == 0L)
            require(representativeInferenceP95Ns == null)
            require(maximumRepresentativeFrameAgeNs == null)
        } else {
            require(selectedDelegate == ProbeDelegate.CPU || selectedDelegate == ProbeDelegate.GPU)
            require(durationNs == ProbeTimeContract.SELECTED_STEADY_DURATION_NS)
            require(representativeInferenceP95Ns != null)
            require(maximumRepresentativeFrameAgeNs != null)
        }
    }
}

sealed interface ModeCapabilityUi {
    val mode: GameMode

    data class Unverified(override val mode: GameMode) : ModeCapabilityUi

    data class Verified(val measurement: ModeMeasurementSummary) : ModeCapabilityUi {
        override val mode: GameMode = measurement.mode
    }

    data class Incomplete(
        override val mode: GameMode,
        val reason: ProbeUiReason,
    ) : ModeCapabilityUi
}

data class CapabilitySummaryUi(
    val solo: ModeCapabilityUi = ModeCapabilityUi.Unverified(GameMode.SOLO),
    val dual: ModeCapabilityUi = ModeCapabilityUi.Unverified(GameMode.DUAL),
) {
    init {
        require(solo.mode == GameMode.SOLO)
        require(dual.mode == GameMode.DUAL)
    }

    val effectCapability: EffectCapability
        get() = EffectCapability.CONSERVATIVE_UNVERIFIED

    val derivation: CapabilityDerivation
        get() {
            val result = CapabilityOutcomePolicy.derive(
                solo = solo.toEvidence(isSolo = true),
                dual = dual.toEvidence(isSolo = false),
            )
            check(result is CapabilityDomainResult.Valid)
            return result.value
        }

    fun beginning(mode: GameMode): CapabilitySummaryUi = when (mode) {
        GameMode.SOLO -> CapabilitySummaryUi()
        GameMode.DUAL -> copy(dual = ModeCapabilityUi.Unverified(GameMode.DUAL))
    }

    fun withIncomplete(mode: GameMode, reason: ProbeUiReason): CapabilitySummaryUi = when (mode) {
        GameMode.SOLO -> copy(
            solo = ModeCapabilityUi.Incomplete(GameMode.SOLO, reason),
            dual = ModeCapabilityUi.Unverified(GameMode.DUAL),
        )
        GameMode.DUAL -> copy(dual = ModeCapabilityUi.Incomplete(GameMode.DUAL, reason))
    }

    fun withVerified(measurement: ModeMeasurementSummary): CapabilitySummaryUi =
        when (measurement.mode) {
            GameMode.SOLO -> copy(
                solo = ModeCapabilityUi.Verified(measurement),
                dual = ModeCapabilityUi.Unverified(GameMode.DUAL),
            )
            GameMode.DUAL -> copy(dual = ModeCapabilityUi.Verified(measurement))
        }

    private fun ModeCapabilityUi.toEvidence(isSolo: Boolean): ModeCapabilityEvidence = when (this) {
        is ModeCapabilityUi.Verified -> ModeCapabilityEvidence.Persisted(measurement.outcome)
        is ModeCapabilityUi.Incomplete -> ModeCapabilityEvidence.Incomplete
        is ModeCapabilityUi.Unverified -> if (isSolo) {
            ModeCapabilityEvidence.Incomplete
        } else {
            ModeCapabilityEvidence.Unrequested
        }
    }
}

/**
 * Immutable aggregate snapshot emitted by the authoritative probe coordinator.
 *
 * The UI never infers a route, fallback, close result, journal transition, or save.
 * A revision is accepted only for the exact UI start request and active identity, and in
 * strictly increasing order. Stage-specific payload validation is performed by [ProbeUiReducer].
 */
data class ProbeEngineSnapshot(
    val startRequestId: ProbeStartRequestId,
    val identity: ProbeAttemptIdentity,
    val revision: Long,
    val mode: GameMode,
    val stage: ProbeUiStage,
    val delegate: ProbeDelegate = ProbeDelegate.NONE,
    val successfulWarmups: Int = 0,
    val elapsedNs: Long = 0L,
    val durationNs: Long = 0L,
    val measurement: ModeMeasurementSummary? = null,
    val reason: ProbeUiReason? = null,
) {
    init {
        require(revision > 0L)
    }

    val cursor: ProbeAttemptCursor
        get() = ProbeAttemptCursor(startRequestId, identity, mode, revision)
}

sealed interface ProbeUiState {
    data object PermissionRequired : ProbeUiState

    data object PermissionRequesting : ProbeUiState

    data object PermissionDenied : ProbeUiState

    data class Ready(
        val summary: CapabilitySummaryUi = CapabilitySummaryUi(),
        val lastAttempt: ProbeAttemptCursor? = null,
    ) : ProbeUiState

    data class AwaitingEngineStart(
        val startRequestId: ProbeStartRequestId,
        val mode: GameMode,
        val summary: CapabilitySummaryUi,
        val previousAttempt: ProbeAttemptCursor?,
        val cancelWhenIdentified: Boolean = false,
    ) : ProbeUiState {
        init {
            val previousValue = previousAttempt?.startRequestId?.value
            if (previousValue == null) {
                require(startRequestId.value == 1L)
            } else {
                require(previousValue < Long.MAX_VALUE)
                require(startRequestId.value == previousValue + 1L)
            }
        }
    }

    data class Projecting(
        val snapshot: ProbeEngineSnapshot,
        val summary: CapabilitySummaryUi,
    ) : ProbeUiState

    data class Cancelling(
        val cursor: ProbeAttemptCursor,
        val summary: CapabilitySummaryUi,
    ) : ProbeUiState

    data class Reposition(
        val cursor: ProbeAttemptCursor,
        val summary: CapabilitySummaryUi,
        val reason: ProbeUiReason,
    ) : ProbeUiState {
        init {
            require(
                reason.category == ProbeUiReasonCategory.REPOSITION ||
                    reason.category == ProbeUiReasonCategory.CANCELLED,
            )
        }
    }

    data class Incomplete(
        val cursor: ProbeAttemptCursor,
        val summary: CapabilitySummaryUi,
        val reason: ProbeUiReason,
        val recovery: ProbeIncompleteRecovery,
        val permissionRequestPending: Boolean = false,
    ) : ProbeUiState {
        init {
            require(reason.category == ProbeUiReasonCategory.CLEAN_INCOMPLETE)
            if (reason != ProbeUiReason.CAMERA_PERMISSION_REVOKED) {
                require(recovery == ProbeIncompleteRecovery.RETRY)
            }
            if (permissionRequestPending) {
                require(recovery == ProbeIncompleteRecovery.REQUEST_PERMISSION)
            }
        }
    }

    data class Result(
        val summary: CapabilitySummaryUi,
        val lastAttempt: ProbeAttemptCursor,
        val dualOptInAvailable: Boolean,
        val dualSkipped: Boolean = false,
    ) : ProbeUiState

    data class RestartRequired(
        val cursor: ProbeAttemptCursor,
        val summary: CapabilitySummaryUi,
        val reason: ProbeUiReason,
        val cleanupRequested: Boolean,
        val terminationRequested: Boolean = false,
    ) : ProbeUiState {
        init {
            require(
                reason.category == ProbeUiReasonCategory.RESTART_REQUIRED ||
                    reason == ProbeUiReason.MALFORMED_ENGINE_TRANSITION,
            )
        }
    }

    data class StartRequestIdExhausted(
        val mode: GameMode,
        val summary: CapabilitySummaryUi,
        val lastAttempt: ProbeAttemptCursor,
    ) : ProbeUiState {
        val reason: ProbeUiReason = ProbeUiReason.START_REQUEST_ID_EXHAUSTED
    }
}

sealed interface ProbeUiAction {
    data object RequestPermission : ProbeUiAction

    data class PermissionResult(val granted: Boolean) : ProbeUiAction

    data object StartSolo : ProbeUiAction

    data object OptInDual : ProbeUiAction

    data object SkipDual : ProbeUiAction

    data object Cancel : ProbeUiAction

    data object RetryAfterReposition : ProbeUiAction

    data object RetryAfterIncomplete : ProbeUiAction

    data object TerminateForCleanRestart : ProbeUiAction

    data class EngineSnapshotReceived(val snapshot: ProbeEngineSnapshot) : ProbeUiAction
}

/** Commands are user intent or safety cleanup only; they never authorize engine work. */
sealed interface ProbeUiCommand {
    data object RequestCameraPermission : ProbeUiCommand

    data class StartProbe(
        val mode: GameMode,
        val startRequestId: ProbeStartRequestId,
    ) : ProbeUiCommand

    data class CancelCurrentAttempt(val identity: ProbeAttemptIdentity) : ProbeUiCommand

    data class RequestCleanProcessTermination(val identity: ProbeAttemptIdentity) : ProbeUiCommand
}

data class ProbeTransition(
    val state: ProbeUiState,
    val commands: List<ProbeUiCommand> = emptyList(),
)
