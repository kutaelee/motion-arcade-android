package com.motionarcade.app.capability

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityModeOutcome
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.ProbeTimeContract

object ProbeUiReducer {
    fun reduce(state: ProbeUiState, action: ProbeUiAction): ProbeTransition = when (action) {
        ProbeUiAction.RequestPermission -> requestPermission(state)
        is ProbeUiAction.PermissionResult -> permissionResult(state, action)
        ProbeUiAction.StartSolo -> startSolo(state)
        ProbeUiAction.OptInDual -> optInDual(state)
        ProbeUiAction.SkipDual -> skipDual(state)
        ProbeUiAction.Cancel -> cancel(state)
        ProbeUiAction.RetryAfterReposition -> retryAfterReposition(state)
        ProbeUiAction.RetryAfterIncomplete -> retryAfterIncomplete(state)
        ProbeUiAction.TerminateForCleanRestart -> terminateForCleanRestart(state)
        is ProbeUiAction.EngineSnapshotReceived -> receiveSnapshot(state, action.snapshot)
    }

    private fun requestPermission(state: ProbeUiState): ProbeTransition = when (state) {
        ProbeUiState.PermissionRequired,
        ProbeUiState.PermissionDenied,
        -> ProbeTransition(
            ProbeUiState.PermissionRequesting,
            listOf(ProbeUiCommand.RequestCameraPermission),
        )
        ProbeUiState.PermissionRequesting -> unchanged(state)
        is ProbeUiState.Incomplete -> if (
            state.reason == ProbeUiReason.CAMERA_PERMISSION_REVOKED &&
            state.recovery == ProbeIncompleteRecovery.REQUEST_PERMISSION &&
            !state.permissionRequestPending
        ) {
            ProbeTransition(
                state.copy(permissionRequestPending = true),
                listOf(ProbeUiCommand.RequestCameraPermission),
            )
        } else {
            unchanged(state)
        }
        else -> unchanged(state)
    }

    private fun permissionResult(
        state: ProbeUiState,
        action: ProbeUiAction.PermissionResult,
    ): ProbeTransition = when (state) {
        ProbeUiState.PermissionRequesting -> ProbeTransition(
            if (action.granted) ProbeUiState.Ready() else ProbeUiState.PermissionDenied,
        )
        is ProbeUiState.Incomplete -> if (
            state.permissionRequestPending &&
            state.recovery == ProbeIncompleteRecovery.REQUEST_PERMISSION
        ) {
            ProbeTransition(
                state.copy(
                    recovery = if (action.granted) {
                        ProbeIncompleteRecovery.RETRY
                    } else {
                        ProbeIncompleteRecovery.REQUEST_PERMISSION
                    },
                    permissionRequestPending = false,
                ),
            )
        } else {
            unchanged(state)
        }
        else -> unchanged(state)
    }

    private fun startSolo(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.Ready -> {
            val startRequestId = checkedNextStartRequestId(state.lastAttempt)
            if (startRequestId == null) {
                startRequestIdExhausted(
                    mode = GameMode.SOLO,
                    summary = state.summary,
                    lastAttempt = requireNotNull(state.lastAttempt),
                )
            } else {
                ProbeTransition(
                    ProbeUiState.AwaitingEngineStart(
                        startRequestId = startRequestId,
                        mode = GameMode.SOLO,
                        summary = state.summary.beginning(GameMode.SOLO),
                        previousAttempt = state.lastAttempt,
                    ),
                    listOf(ProbeUiCommand.StartProbe(GameMode.SOLO, startRequestId)),
                )
            }
        }
        is ProbeUiState.AwaitingEngineStart -> unchanged(state)
        else -> unchanged(state)
    }

    private fun optInDual(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.Result -> {
            val solo = (state.summary.solo as? ModeCapabilityUi.Verified)?.measurement
            if (
                state.dualOptInAvailable &&
                solo?.outcome == CapabilityModeOutcome.SOLO_SUPPORT
            ) {
                val startRequestId = checkedNextStartRequestId(state.lastAttempt)
                if (startRequestId == null) {
                    startRequestIdExhausted(
                        mode = GameMode.DUAL,
                        summary = state.summary,
                        lastAttempt = state.lastAttempt,
                    )
                } else {
                    ProbeTransition(
                        ProbeUiState.AwaitingEngineStart(
                            startRequestId = startRequestId,
                            mode = GameMode.DUAL,
                            summary = state.summary.beginning(GameMode.DUAL),
                            previousAttempt = state.lastAttempt,
                        ),
                        listOf(ProbeUiCommand.StartProbe(GameMode.DUAL, startRequestId)),
                    )
                }
            } else {
                unchanged(state)
            }
        }
        is ProbeUiState.AwaitingEngineStart -> unchanged(state)
        else -> unchanged(state)
    }

    private fun skipDual(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.Result -> when {
            state.dualSkipped -> unchanged(state)
            state.dualOptInAvailable && state.summary.dual is ModeCapabilityUi.Unverified ->
                ProbeTransition(
                    state.copy(
                        dualOptInAvailable = false,
                        dualSkipped = true,
                    ),
                )
            else -> unchanged(state)
        }
        else -> unchanged(state)
    }

    private fun cancel(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.AwaitingEngineStart -> if (state.cancelWhenIdentified) {
            unchanged(state)
        } else {
            ProbeTransition(state.copy(cancelWhenIdentified = true))
        }
        is ProbeUiState.Projecting -> ProbeTransition(
            ProbeUiState.Cancelling(state.snapshot.cursor, state.summary),
            listOf(ProbeUiCommand.CancelCurrentAttempt(state.snapshot.identity)),
        )
        is ProbeUiState.Cancelling -> unchanged(state)
        else -> unchanged(state)
    }

    private fun retryAfterReposition(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.Reposition -> {
            val startRequestId = checkedNextStartRequestId(state.cursor)
            if (startRequestId == null) {
                startRequestIdExhausted(
                    mode = state.cursor.mode,
                    summary = state.summary,
                    lastAttempt = state.cursor,
                )
            } else {
                ProbeTransition(
                    ProbeUiState.AwaitingEngineStart(
                        startRequestId = startRequestId,
                        mode = state.cursor.mode,
                        summary = state.summary.beginning(state.cursor.mode),
                        previousAttempt = state.cursor,
                    ),
                    listOf(ProbeUiCommand.StartProbe(state.cursor.mode, startRequestId)),
                )
            }
        }
        is ProbeUiState.AwaitingEngineStart -> unchanged(state)
        else -> unchanged(state)
    }

    private fun retryAfterIncomplete(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.Incomplete -> if (
            state.recovery == ProbeIncompleteRecovery.RETRY &&
            !state.permissionRequestPending
        ) {
            val startRequestId = checkedNextStartRequestId(state.cursor)
            if (startRequestId == null) {
                startRequestIdExhausted(
                    mode = state.cursor.mode,
                    summary = state.summary,
                    lastAttempt = state.cursor,
                )
            } else {
                ProbeTransition(
                    ProbeUiState.AwaitingEngineStart(
                        startRequestId = startRequestId,
                        mode = state.cursor.mode,
                        summary = state.summary.beginning(state.cursor.mode),
                        previousAttempt = state.cursor,
                    ),
                    listOf(ProbeUiCommand.StartProbe(state.cursor.mode, startRequestId)),
                )
            }
        } else {
            unchanged(state)
        }
        else -> unchanged(state)
    }

    private fun terminateForCleanRestart(state: ProbeUiState): ProbeTransition = when (state) {
        is ProbeUiState.RestartRequired -> if (state.terminationRequested) {
            unchanged(state)
        } else {
            ProbeTransition(
                state.copy(terminationRequested = true),
                listOf(
                    ProbeUiCommand.RequestCleanProcessTermination(state.cursor.identity),
                ),
            )
        }
        else -> unchanged(state)
    }

    private fun receiveSnapshot(
        state: ProbeUiState,
        snapshot: ProbeEngineSnapshot,
    ): ProbeTransition = when (state) {
        is ProbeUiState.AwaitingEngineStart -> receiveAwaitedSnapshot(state, snapshot)
        is ProbeUiState.Projecting -> receiveActiveSnapshot(state, snapshot)
        is ProbeUiState.Cancelling -> receiveCancellationSnapshot(state, snapshot)
        ProbeUiState.PermissionRequired,
        ProbeUiState.PermissionRequesting,
        ProbeUiState.PermissionDenied,
        is ProbeUiState.Ready,
        is ProbeUiState.Reposition,
        is ProbeUiState.Incomplete,
        is ProbeUiState.Result,
        is ProbeUiState.RestartRequired,
        is ProbeUiState.StartRequestIdExhausted,
        -> unchanged(state)
    }

    private fun receiveAwaitedSnapshot(
        state: ProbeUiState.AwaitingEngineStart,
        snapshot: ProbeEngineSnapshot,
    ): ProbeTransition {
        if (snapshot.startRequestId != state.startRequestId) {
            return unchanged(state)
        }
        if (snapshot.identity == state.previousAttempt?.identity) {
            return unchanged(state)
        }
        if (snapshot.mode != state.mode || !snapshot.isWellFormed()) {
            return malformed(snapshot.cursor, state.summary, cleanupAlreadyRequested = false)
        }
        if (state.cancelWhenIdentified) {
            if (snapshot.stage.isAuthoritativeTerminal()) {
                return applyAuthoritativeSnapshot(snapshot, state.summary)
            }
            return ProbeTransition(
                ProbeUiState.Cancelling(snapshot.cursor, state.summary),
                listOf(ProbeUiCommand.CancelCurrentAttempt(snapshot.identity)),
            )
        }
        return applyAuthoritativeSnapshot(snapshot, state.summary)
    }

    private fun receiveActiveSnapshot(
        state: ProbeUiState.Projecting,
        snapshot: ProbeEngineSnapshot,
    ): ProbeTransition {
        val current = state.snapshot
        if (
            snapshot.startRequestId != current.startRequestId ||
            snapshot.identity != current.identity
        ) {
            return unchanged(state)
        }
        if (snapshot.revision <= current.revision) {
            return unchanged(state)
        }
        if (snapshot.mode != current.mode || !snapshot.isWellFormed()) {
            return malformed(current.cursor, state.summary, cleanupAlreadyRequested = false)
        }
        if (!legalProjectionTransition(current, snapshot)) {
            return malformed(current.cursor, state.summary, cleanupAlreadyRequested = false)
        }
        return applyAuthoritativeSnapshot(snapshot, state.summary)
    }

    private fun receiveCancellationSnapshot(
        state: ProbeUiState.Cancelling,
        snapshot: ProbeEngineSnapshot,
    ): ProbeTransition {
        if (
            snapshot.startRequestId != state.cursor.startRequestId ||
            snapshot.identity != state.cursor.identity ||
            snapshot.revision <= state.cursor.revision
        ) {
            return unchanged(state)
        }
        if (snapshot.mode != state.cursor.mode || !snapshot.isWellFormed()) {
            return malformed(state.cursor, state.summary, cleanupAlreadyRequested = true)
        }
        return when (snapshot.stage) {
            ProbeUiStage.CANCELLED,
            ProbeUiStage.INCOMPLETE,
            ProbeUiStage.RESTART_REQUIRED,
            ProbeUiStage.REPOSITION_REQUIRED,
            ProbeUiStage.VERIFIED,
            -> applyAuthoritativeSnapshot(snapshot, state.summary)
            ProbeUiStage.PREPARING,
            ProbeUiStage.WARMUP,
            ProbeUiStage.CANDIDATE_MEASUREMENT,
            ProbeUiStage.SELECTED_MEASUREMENT,
            ProbeUiStage.FINALIZING,
            -> ProbeTransition(state.copy(cursor = snapshot.cursor))
        }
    }

    private fun applyAuthoritativeSnapshot(
        snapshot: ProbeEngineSnapshot,
        summary: CapabilitySummaryUi,
    ): ProbeTransition = when (snapshot.stage) {
        ProbeUiStage.PREPARING,
        ProbeUiStage.WARMUP,
        ProbeUiStage.CANDIDATE_MEASUREMENT,
        ProbeUiStage.SELECTED_MEASUREMENT,
        ProbeUiStage.FINALIZING,
        -> ProbeTransition(ProbeUiState.Projecting(snapshot, summary))

        ProbeUiStage.REPOSITION_REQUIRED -> {
            val reason = requireNotNull(snapshot.reason)
            ProbeTransition(
                ProbeUiState.Reposition(
                    cursor = snapshot.cursor,
                    summary = summary.withIncomplete(snapshot.mode, reason),
                    reason = reason,
                ),
            )
        }

        ProbeUiStage.VERIFIED -> {
            val measurement = requireNotNull(snapshot.measurement)
            val verifiedSummary = summary.withVerified(measurement)
            val dualAvailable = snapshot.mode == GameMode.SOLO &&
                measurement.outcome == CapabilityModeOutcome.SOLO_SUPPORT
            ProbeTransition(
                ProbeUiState.Result(
                    summary = verifiedSummary,
                    lastAttempt = snapshot.cursor,
                    dualOptInAvailable = dualAvailable,
                ),
            )
        }

        ProbeUiStage.CANCELLED -> {
            val reason = ProbeUiReason.USER_CANCELLED
            val cancelledSummary = summary.withIncomplete(snapshot.mode, reason)
            if (snapshot.mode == GameMode.SOLO) {
                ProbeTransition(
                    ProbeUiState.Reposition(snapshot.cursor, cancelledSummary, reason),
                )
            } else {
                ProbeTransition(
                    ProbeUiState.Result(
                        summary = cancelledSummary,
                        lastAttempt = snapshot.cursor,
                        dualOptInAvailable = true,
                    ),
                )
            }
        }

        ProbeUiStage.INCOMPLETE -> {
            val reason = requireNotNull(snapshot.reason)
            val recovery = if (reason == ProbeUiReason.CAMERA_PERMISSION_REVOKED) {
                ProbeIncompleteRecovery.REQUEST_PERMISSION
            } else {
                ProbeIncompleteRecovery.RETRY
            }
            ProbeTransition(
                ProbeUiState.Incomplete(
                    cursor = snapshot.cursor,
                    summary = summary.withIncomplete(snapshot.mode, reason),
                    reason = reason,
                    recovery = recovery,
                ),
            )
        }

        ProbeUiStage.RESTART_REQUIRED -> {
            val reason = requireNotNull(snapshot.reason)
            ProbeTransition(
                ProbeUiState.RestartRequired(
                    cursor = snapshot.cursor,
                    summary = summary.withIncomplete(snapshot.mode, reason),
                    reason = reason,
                    cleanupRequested = false,
                ),
            )
        }
    }

    private fun legalProjectionTransition(
        current: ProbeEngineSnapshot,
        next: ProbeEngineSnapshot,
    ): Boolean {
        if (current.stage == ProbeUiStage.FINALIZING) {
            if (
                next.stage != ProbeUiStage.FINALIZING &&
                next.stage != ProbeUiStage.VERIFIED &&
                next.stage != ProbeUiStage.INCOMPLETE &&
                next.stage != ProbeUiStage.RESTART_REQUIRED &&
                next.stage != ProbeUiStage.CANCELLED
            ) {
                return false
            }
            if (
                (next.stage == ProbeUiStage.FINALIZING || next.stage == ProbeUiStage.VERIFIED) &&
                current.measurement != next.measurement
            ) {
                return false
            }
        }
        if (next.stage == ProbeUiStage.VERIFIED && current.stage != ProbeUiStage.FINALIZING) {
            return false
        }
        return true
    }

    private fun ProbeEngineSnapshot.isWellFormed(): Boolean {
        val zeroProgress = successfulWarmups == 0 && elapsedNs == 0L && durationNs == 0L
        val noResolution = measurement == null && reason == null
        return when (stage) {
            ProbeUiStage.PREPARING ->
                delegate == ProbeDelegate.NONE && zeroProgress && noResolution
            ProbeUiStage.WARMUP ->
                delegate.isExecutable() &&
                    successfulWarmups in
                    0..ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS &&
                    elapsedNs == 0L && durationNs == 0L && noResolution
            ProbeUiStage.CANDIDATE_MEASUREMENT ->
                delegate.isExecutable() &&
                    successfulWarmups == ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS &&
                    durationNs == ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS &&
                    elapsedNs in 0L..durationNs && noResolution
            ProbeUiStage.SELECTED_MEASUREMENT ->
                delegate.isExecutable() &&
                    successfulWarmups == ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS &&
                    durationNs == ProbeTimeContract.SELECTED_STEADY_DURATION_NS &&
                    elapsedNs in 0L..durationNs && noResolution
            ProbeUiStage.FINALIZING,
            ProbeUiStage.VERIFIED,
            -> {
                val value = measurement
                value != null && value.mode == mode && value.selectedDelegate == delegate &&
                    zeroProgress && reason == null
            }
            ProbeUiStage.REPOSITION_REQUIRED ->
                delegate == ProbeDelegate.NONE && zeroProgress && measurement == null &&
                    reason?.category == ProbeUiReasonCategory.REPOSITION
            ProbeUiStage.CANCELLED ->
                delegate == ProbeDelegate.NONE && zeroProgress && measurement == null &&
                    reason?.category == ProbeUiReasonCategory.CANCELLED
            ProbeUiStage.INCOMPLETE ->
                delegate == ProbeDelegate.NONE && zeroProgress && measurement == null &&
                    reason?.category == ProbeUiReasonCategory.CLEAN_INCOMPLETE
            ProbeUiStage.RESTART_REQUIRED ->
                delegate == ProbeDelegate.NONE && zeroProgress && measurement == null &&
                    reason?.category == ProbeUiReasonCategory.RESTART_REQUIRED
        }
    }

    private fun ProbeDelegate.isExecutable(): Boolean =
        this == ProbeDelegate.CPU || this == ProbeDelegate.GPU

    private fun ProbeUiStage.isAuthoritativeTerminal(): Boolean = when (this) {
        ProbeUiStage.REPOSITION_REQUIRED,
        ProbeUiStage.VERIFIED,
        ProbeUiStage.CANCELLED,
        ProbeUiStage.INCOMPLETE,
        ProbeUiStage.RESTART_REQUIRED,
        -> true
        ProbeUiStage.PREPARING,
        ProbeUiStage.WARMUP,
        ProbeUiStage.CANDIDATE_MEASUREMENT,
        ProbeUiStage.SELECTED_MEASUREMENT,
        ProbeUiStage.FINALIZING,
        -> false
    }

    private fun malformed(
        cursor: ProbeAttemptCursor,
        summary: CapabilitySummaryUi,
        cleanupAlreadyRequested: Boolean,
    ): ProbeTransition {
        val reason = ProbeUiReason.MALFORMED_ENGINE_TRANSITION
        return ProbeTransition(
            state = ProbeUiState.RestartRequired(
                cursor = cursor,
                summary = summary.withIncomplete(cursor.mode, reason),
                reason = reason,
                cleanupRequested = true,
            ),
            commands = if (cleanupAlreadyRequested) {
                emptyList()
            } else {
                listOf(ProbeUiCommand.CancelCurrentAttempt(cursor.identity))
            },
        )
    }

    private fun startRequestIdExhausted(
        mode: GameMode,
        summary: CapabilitySummaryUi,
        lastAttempt: ProbeAttemptCursor,
    ): ProbeTransition = ProbeTransition(
        ProbeUiState.StartRequestIdExhausted(
            mode = mode,
            summary = summary.withIncomplete(mode, ProbeUiReason.START_REQUEST_ID_EXHAUSTED),
            lastAttempt = lastAttempt,
        ),
    )

    private fun checkedNextStartRequestId(
        previousAttempt: ProbeAttemptCursor?,
    ): ProbeStartRequestId? {
        val previousValue = previousAttempt?.startRequestId?.value ?: 0L
        if (previousValue == Long.MAX_VALUE) {
            return null
        }
        return ProbeStartRequestId(previousValue + 1L)
    }

    private fun unchanged(state: ProbeUiState): ProbeTransition = ProbeTransition(state)
}
