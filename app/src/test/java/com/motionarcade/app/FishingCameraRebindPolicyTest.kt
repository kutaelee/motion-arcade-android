package com.motionarcade.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingCameraRebindPolicyTest {
    @Test
    fun initialAndRepeatedResumeDoNotDuplicateTheInitialComposeBind() {
        val initial = FishingCameraRebindPolicyState()

        val firstResume = reduceFishingCameraRebindPolicy(
            initial,
            FishingCameraRebindEvent.RESUME,
        )
        val repeatedResume = reduceFishingCameraRebindPolicy(
            firstResume.state,
            FishingCameraRebindEvent.RESUME,
        )

        assertFalse(firstResume.requestBind)
        assertFalse(repeatedResume.requestBind)
        assertEquals(initial, repeatedResume.state)
    }

    @Test
    fun repeatedPausesThenResumeRequestExactlyOneFreshCameraBind() {
        val firstPause = reduceFishingCameraRebindPolicy(
            FishingCameraRebindPolicyState(),
            FishingCameraRebindEvent.PAUSE,
        )
        val repeatedPause = reduceFishingCameraRebindPolicy(
            firstPause.state,
            FishingCameraRebindEvent.PAUSE,
        )
        val resume = reduceFishingCameraRebindPolicy(
            repeatedPause.state,
            FishingCameraRebindEvent.RESUME,
        )
        val repeatedResume = reduceFishingCameraRebindPolicy(
            resume.state,
            FishingCameraRebindEvent.RESUME,
        )

        assertFalse(firstPause.requestBind)
        assertFalse(repeatedPause.requestBind)
        assertTrue(resume.requestBind)
        assertFalse(repeatedResume.requestBind)
        assertFalse(repeatedResume.state.pausedSinceLastResume)
    }

    @Test
    fun pauseStopStartResumeSequenceStillRequestsOnlyTheResumeBind() {
        val paused = reduceFishingCameraRebindPolicy(
            FishingCameraRebindPolicyState(),
            FishingCameraRebindEvent.PAUSE,
        )

        // STOP and START are game-runtime events and intentionally leave camera policy unchanged.
        val afterStopStart = paused.state
        val resumed = reduceFishingCameraRebindPolicy(
            afterStopStart,
            FishingCameraRebindEvent.RESUME,
        )
        val duplicateResume = reduceFishingCameraRebindPolicy(
            resumed.state,
            FishingCameraRebindEvent.RESUME,
        )

        assertFalse(paused.requestBind)
        assertTrue(resumed.requestBind)
        assertFalse(duplicateResume.requestBind)
    }

    @Test
    fun manualRetryIsLimitedOncePerResumedEpochAndResetOnlyByPauseResume() {
        val firstRetry = reduceFishingCameraRebindPolicy(
            FishingCameraRebindPolicyState(),
            FishingCameraRebindEvent.MANUAL_RETRY,
        )
        val duplicateRetry = reduceFishingCameraRebindPolicy(
            firstRetry.state,
            FishingCameraRebindEvent.MANUAL_RETRY,
        )
        val paused = reduceFishingCameraRebindPolicy(
            duplicateRetry.state,
            FishingCameraRebindEvent.PAUSE,
        )
        val retryWhilePaused = reduceFishingCameraRebindPolicy(
            paused.state,
            FishingCameraRebindEvent.MANUAL_RETRY,
        )
        val resumed = reduceFishingCameraRebindPolicy(
            retryWhilePaused.state,
            FishingCameraRebindEvent.RESUME,
        )
        val nextEpochRetry = reduceFishingCameraRebindPolicy(
            resumed.state,
            FishingCameraRebindEvent.MANUAL_RETRY,
        )
        val nextEpochDuplicate = reduceFishingCameraRebindPolicy(
            nextEpochRetry.state,
            FishingCameraRebindEvent.MANUAL_RETRY,
        )

        assertTrue(firstRetry.requestBind)
        assertEquals(1, firstRetry.state.manualRetryAttempts)
        assertFalse(duplicateRetry.requestBind)
        assertFalse(retryWhilePaused.requestBind)
        assertTrue(resumed.requestBind)
        assertEquals(0, resumed.state.manualRetryAttempts)
        assertTrue(nextEpochRetry.requestBind)
        assertEquals(1, nextEpochRetry.state.manualRetryAttempts)
        assertFalse(nextEpochDuplicate.requestBind)
    }
}
