package com.motionarcade.app.fishing

import com.motionarcade.core.contract.PauseReason
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingHudProjectionTest {
    private val ready = FishingGameSession.start(
        sessionId = "hud-projection",
        seed = 7L,
        calibrationRevision = 1,
        eventTimelineOriginNs = 0L,
    ).snapshot

    @Test
    fun readyAndResultExposePlayablePrimaryActions() {
        val readyProjection = state(ready).toHudProjection()
        val resultProjection = state(
            ready.copy(
                phase = FishingPhase.RESULT,
                outcome = FishingOutcome.CAUGHT,
                score = 700,
            ),
        ).toHudProjection()

        assertEquals(FishingHudAction.CAST, readyProjection.action)
        assertTrue(readyProjection.actionEnabled)
        assertEquals(0f, readyProjection.progress, 0f)
        assertEquals(FishingHudAction.RESTART, resultProjection.action)
        assertTrue(resultProjection.actionEnabled)
        assertFalse(resultProjection.pauseEnabled)
        assertEquals(1f, resultProjection.progress, 0f)
    }

    @Test
    fun biteWaitAndPauseDisablePrimaryAction() {
        val waiting = state(ready.copy(phase = FishingPhase.BITE_WAIT)).toHudProjection()
        val paused = state(
            FishingGameSession.start(
                sessionId = "paused-hud",
                seed = 8L,
                calibrationRevision = 1,
                eventTimelineOriginNs = 0L,
            ).apply { pause(PauseReason.USER) }.snapshot,
        ).toHudProjection()

        assertEquals(FishingHudAction.WAIT, waiting.action)
        assertFalse(waiting.actionEnabled)
        assertEquals(FishingHudAction.NONE, paused.action)
        assertFalse(paused.actionEnabled)
        assertTrue(paused.userPaused)
    }

    @Test
    fun cameraAndPoseCountsProduceExplicitTrackingStates() {
        assertEquals(
            FishingTrackingState.CAMERA_CONNECTING,
            state(ready, cameraActive = false, poseCount = null).toHudProjection().trackingState,
        )
        assertEquals(
            FishingTrackingState.POSE_WAITING,
            state(ready, cameraActive = true, poseCount = null).toHudProjection().trackingState,
        )
        assertEquals(
            FishingTrackingState.NO_PLAYER,
            state(ready, cameraActive = true, poseCount = 0).toHudProjection().trackingState,
        )
        assertEquals(
            FishingTrackingState.SOLO_READY,
            state(ready, cameraActive = true, poseCount = 1).toHudProjection().trackingState,
        )
        assertEquals(
            FishingTrackingState.TOO_MANY_PLAYERS,
            state(ready, cameraActive = true, poseCount = 2).toHudProjection().trackingState,
        )
    }

    private fun state(
        snapshot: com.motionarcade.games.fishing.FishingSnapshot,
        cameraActive: Boolean = false,
        poseCount: Int? = null,
    ): FishingGameUiState = FishingGameUiState(
        snapshot = snapshot,
        cameraActive = cameraActive,
        detectedPoseCount = poseCount,
        lastInputDetail = null,
    )
}
