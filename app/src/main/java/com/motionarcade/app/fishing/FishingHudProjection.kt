package com.motionarcade.app.fishing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.motionarcade.app.R
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.FishingFish
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase

internal enum class FishingHudAction {
    CAST,
    WAIT,
    HOOK,
    REEL,
    TENSION_LEFT,
    NET,
    RESTART,
    NONE,
}

internal enum class FishingTrackingState {
    CAMERA_CONNECTING,
    POSE_WAITING,
    SOLO_READY,
    NO_PLAYER,
    TOO_MANY_PLAYERS,
}

@Immutable
internal data class FishingHudProjection(
    val phase: FishingPhase,
    val outcome: FishingOutcome?,
    val score: Int,
    val progress: Float,
    val tension: Float,
    val trackingState: FishingTrackingState,
    val detectedPoseCount: Int?,
    val action: FishingHudAction,
    val actionEnabled: Boolean,
    val pauseEnabled: Boolean,
    val userPaused: Boolean,
    val recoveryStage: FishingRecoveryStage,
    val recoveryCountdownSeconds: Int?,
) {
    init {
        require(progress.isFinite() && progress in 0f..1f)
        require(tension.isFinite() && tension in 0f..1f)
        require(score >= 0)
    }
}

internal fun FishingGameUiState.toHudProjection(): FishingHudProjection {
    val requiredCycles = when (snapshot.fish) {
        FishingFish.MOON_CARP -> 7
        FishingFish.SUNFIN, null -> 5
    }
    val progress = when (snapshot.phase) {
        FishingPhase.READY -> 0f
        FishingPhase.BITE_WAIT -> 0.15f
        FishingPhase.HOOK_WINDOW -> 0.25f
        FishingPhase.REELING,
        FishingPhase.TENSION,
        -> 0.25f + 0.6f * (snapshot.reelCycles.toFloat() / requiredCycles)
        FishingPhase.NETTING -> 0.9f
        FishingPhase.RESULT -> 1f
    }.coerceIn(0f, 1f)
    val action = when {
        snapshot.status == SessionStatus.PAUSED -> FishingHudAction.NONE
        snapshot.phase == FishingPhase.READY -> FishingHudAction.CAST
        snapshot.phase == FishingPhase.BITE_WAIT -> FishingHudAction.WAIT
        snapshot.phase == FishingPhase.HOOK_WINDOW -> FishingHudAction.HOOK
        snapshot.phase == FishingPhase.REELING -> FishingHudAction.REEL
        snapshot.phase == FishingPhase.TENSION -> FishingHudAction.TENSION_LEFT
        snapshot.phase == FishingPhase.NETTING -> FishingHudAction.NET
        else -> FishingHudAction.RESTART
    }
    val trackingState = when {
        !cameraActive -> FishingTrackingState.CAMERA_CONNECTING
        detectedPoseCount == null -> FishingTrackingState.POSE_WAITING
        detectedPoseCount == 0 -> FishingTrackingState.NO_PLAYER
        detectedPoseCount == 1 -> FishingTrackingState.SOLO_READY
        else -> FishingTrackingState.TOO_MANY_PLAYERS
    }
    return FishingHudProjection(
        phase = snapshot.phase,
        outcome = snapshot.outcome,
        score = snapshot.score,
        progress = progress,
        tension = (snapshot.tension / 100f).coerceIn(0f, 1f),
        trackingState = trackingState,
        detectedPoseCount = detectedPoseCount,
        action = action,
        actionEnabled = action !in setOf(FishingHudAction.WAIT, FishingHudAction.NONE),
        pauseEnabled = snapshot.phase != FishingPhase.RESULT,
        userPaused = snapshot.pauseReason == PauseReason.USER,
        recoveryStage = recoveryStage,
        recoveryCountdownSeconds = recoveryCountdownSeconds,
    )
}

@Composable
internal fun FishingHudProjection.toHudModel(): FishingHudModel {
    val phaseLabel = stringResource(
        when (phase) {
            FishingPhase.READY -> R.string.fishing_phase_ready
            FishingPhase.BITE_WAIT -> R.string.fishing_phase_bite_wait
            FishingPhase.HOOK_WINDOW -> R.string.fishing_phase_hook
            FishingPhase.REELING -> R.string.fishing_phase_reeling
            FishingPhase.TENSION -> R.string.fishing_phase_tension
            FishingPhase.NETTING -> R.string.fishing_phase_netting
            FishingPhase.RESULT -> R.string.fishing_phase_result
        },
    )
    val instruction = when (recoveryStage) {
        FishingRecoveryStage.WAITING_FOR_CAMERA ->
            stringResource(R.string.fishing_recovery_waiting)
        FishingRecoveryStage.AWAITING_CONFIRMATION ->
            stringResource(R.string.fishing_recovery_confirm)
        FishingRecoveryStage.COUNTDOWN -> stringResource(
            R.string.fishing_recovery_countdown,
            requireNotNull(recoveryCountdownSeconds),
        )
        FishingRecoveryStage.NONE -> stringResource(
            when (phase) {
                FishingPhase.READY -> R.string.fishing_instruction_ready
                FishingPhase.BITE_WAIT -> R.string.fishing_instruction_bite_wait
                FishingPhase.HOOK_WINDOW -> R.string.fishing_instruction_hook
                FishingPhase.REELING -> R.string.fishing_instruction_reel
                FishingPhase.TENSION -> R.string.fishing_instruction_tension
                FishingPhase.NETTING -> R.string.fishing_instruction_net
                FishingPhase.RESULT -> when (outcome) {
                    FishingOutcome.CAUGHT -> R.string.fishing_instruction_caught
                    FishingOutcome.ESCAPED, null -> R.string.fishing_instruction_escaped
                }
            },
        )
    }
    val actionLabel = stringResource(
        when (action) {
            FishingHudAction.CAST -> R.string.fishing_action_cast
            FishingHudAction.WAIT -> R.string.fishing_action_wait
            FishingHudAction.HOOK -> R.string.fishing_action_hook
            FishingHudAction.REEL -> R.string.fishing_action_reel
            FishingHudAction.TENSION_LEFT -> R.string.fishing_action_tension_left
            FishingHudAction.NET -> R.string.fishing_action_net
            FishingHudAction.RESTART -> R.string.fishing_action_restart
            FishingHudAction.NONE -> R.string.fishing_action_paused
        },
    )
    val trackingStatus = when (recoveryStage) {
        FishingRecoveryStage.WAITING_FOR_CAMERA ->
            stringResource(R.string.fishing_recovery_waiting_short)
        FishingRecoveryStage.AWAITING_CONFIRMATION ->
            stringResource(R.string.fishing_recovery_confirm_short)
        FishingRecoveryStage.COUNTDOWN -> stringResource(
            R.string.fishing_recovery_countdown_short,
            requireNotNull(recoveryCountdownSeconds),
        )
        FishingRecoveryStage.NONE -> when (trackingState) {
            FishingTrackingState.CAMERA_CONNECTING ->
                stringResource(R.string.fishing_tracking_camera)
            FishingTrackingState.POSE_WAITING -> stringResource(R.string.fishing_tracking_waiting)
            FishingTrackingState.NO_PLAYER -> stringResource(R.string.fishing_tracking_none)
            FishingTrackingState.SOLO_READY -> stringResource(R.string.fishing_tracking_solo)
            FishingTrackingState.TOO_MANY_PLAYERS -> stringResource(
                R.string.fishing_tracking_many,
                requireNotNull(detectedPoseCount),
            )
        }
    }
    val progressPercent = (progress * 100f).toInt()
    val tensionPercent = (tension * 100f).toInt()
    return FishingHudModel(
        phaseLabel = phaseLabel,
        instruction = instruction,
        scoreLabel = stringResource(R.string.fishing_score, score),
        progressLabel = stringResource(R.string.fishing_progress, progressPercent),
        progressDescription = stringResource(
            R.string.fishing_progress_description,
            progressPercent,
        ),
        progress = progress,
        tensionLabel = stringResource(R.string.fishing_tension, tensionPercent),
        tensionDescription = stringResource(
            R.string.fishing_tension_description,
            tensionPercent,
        ),
        tension = tension,
        trackingStatusLabel = trackingStatus,
        actionLabel = actionLabel,
        actionEnabled = actionEnabled,
        pauseLabel = when (recoveryStage) {
            FishingRecoveryStage.WAITING_FOR_CAMERA ->
                stringResource(R.string.fishing_recovery_waiting_button)
            FishingRecoveryStage.AWAITING_CONFIRMATION ->
                stringResource(R.string.fishing_recovery_confirm_button)
            FishingRecoveryStage.COUNTDOWN -> stringResource(
                R.string.fishing_recovery_countdown_button,
                requireNotNull(recoveryCountdownSeconds),
            )
            FishingRecoveryStage.NONE -> stringResource(
                if (userPaused) R.string.fishing_resume else R.string.fishing_pause,
            )
        },
        pauseEnabled = when (recoveryStage) {
            FishingRecoveryStage.AWAITING_CONFIRMATION -> true
            FishingRecoveryStage.NONE -> pauseEnabled
            FishingRecoveryStage.WAITING_FOR_CAMERA,
            FishingRecoveryStage.COUNTDOWN,
            -> false
        },
        artFrame = when (phase) {
            FishingPhase.READY -> 0
            FishingPhase.BITE_WAIT, FishingPhase.HOOK_WINDOW -> 1
            FishingPhase.REELING, FishingPhase.TENSION -> 2
            FishingPhase.NETTING, FishingPhase.RESULT -> 3
        },
        showCatchMeasurement = phase == FishingPhase.NETTING || phase == FishingPhase.RESULT,
    )
}
