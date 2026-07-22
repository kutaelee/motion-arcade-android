package com.motionarcade.app

import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot

/**
 * Bounds automatic camera recovery to one fresh generation per screen/lens epoch.
 *
 * A terminal snapshot can be delivered more than once through presentation recomposition. The
 * failed generation is therefore consumed exactly once, and a failed recovery generation never
 * starts an automatic loop. Leaving the route, changing the lens, or resuming after a real pause
 * creates a new policy epoch.
 */
internal data class LivePoseAutoRecoveryState(
    val retryAttempts: Int = 0,
    val consumedFailureGeneration: Long? = null,
) {
    init {
        require(retryAttempts in 0..MAX_LIVE_POSE_AUTO_RECOVERY_ATTEMPTS)
        require(consumedFailureGeneration == null || consumedFailureGeneration > 0L)
        require((retryAttempts == 0) == (consumedFailureGeneration == null))
    }
}

internal data class LivePoseAutoRecoveryDecision(
    val state: LivePoseAutoRecoveryState,
    val requestRebind: Boolean,
)

internal fun reduceLivePoseAutoRecovery(
    state: LivePoseAutoRecoveryState,
    inference: LivePoseInferenceSnapshot,
): LivePoseAutoRecoveryDecision {
    if (
        inference.phase != LivePoseInferencePhase.FAILED ||
        inference.sessionGeneration <= 0L ||
        state.retryAttempts >= MAX_LIVE_POSE_AUTO_RECOVERY_ATTEMPTS ||
        state.consumedFailureGeneration == inference.sessionGeneration
    ) {
        return LivePoseAutoRecoveryDecision(state, requestRebind = false)
    }
    return LivePoseAutoRecoveryDecision(
        state = LivePoseAutoRecoveryState(
            retryAttempts = state.retryAttempts + 1,
            consumedFailureGeneration = inference.sessionGeneration,
        ),
        requestRebind = true,
    )
}

internal fun decideLivePoseAutoRecovery(
    state: LivePoseAutoRecoveryState,
    inference: LivePoseInferenceSnapshot,
    lifecycleResumed: Boolean,
): LivePoseAutoRecoveryDecision =
    if (lifecycleResumed) {
        reduceLivePoseAutoRecovery(state, inference)
    } else {
        LivePoseAutoRecoveryDecision(state, requestRebind = false)
    }

internal fun canCompleteLivePoseAutoRecovery(
    lifecycleResumed: Boolean,
    inference: LivePoseInferenceSnapshot,
    failedGeneration: Long,
): Boolean =
    lifecycleResumed &&
        inference.phase == LivePoseInferencePhase.FAILED &&
        inference.sessionGeneration == failedGeneration

internal fun LivePoseAutoRecoveryState.isRecovering(
    inference: LivePoseInferenceSnapshot,
): Boolean =
    inference.phase == LivePoseInferencePhase.FAILED &&
        consumedFailureGeneration == inference.sessionGeneration

internal const val LIVE_POSE_AUTO_RECOVERY_DELAY_MILLIS: Long = 200L
internal const val MAX_LIVE_POSE_AUTO_RECOVERY_ATTEMPTS: Int = 1
