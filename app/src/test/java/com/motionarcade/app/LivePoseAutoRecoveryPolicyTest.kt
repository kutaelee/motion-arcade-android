package com.motionarcade.app

import com.motionarcade.vision.pose.LivePoseFailureReason
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePoseAutoRecoveryPolicyTest {
    @Test
    fun everyTerminalLocalFailureReasonUsesTheSameBoundedRecoveryContract() {
        LivePoseFailureReason.entries.forEachIndexed { index, reason ->
            val failed = failed(
                generation = index.toLong() + 1L,
                revision = 1L,
                reason = reason,
            )

            val first = reduceLivePoseAutoRecovery(LivePoseAutoRecoveryState(), failed)
            val second = reduceLivePoseAutoRecovery(
                first.state,
                failed(
                    generation = failed.sessionGeneration + 100L,
                    revision = 1L,
                    reason = reason,
                ),
            )

            assertTrue("$reason must receive one automatic recovery", first.requestRebind)
            assertFalse("$reason must never start a recovery loop", second.requestRebind)
        }
    }

    @Test
    fun firstTerminalFailureRequestsExactlyOneFreshBinding() {
        val failed = failed(generation = 7L, revision = 3L)

        val first = reduceLivePoseAutoRecovery(LivePoseAutoRecoveryState(), failed)
        val duplicate = reduceLivePoseAutoRecovery(first.state, failed.copy(revision = 4L))
        val failedRecovery = reduceLivePoseAutoRecovery(
            duplicate.state,
            failed(generation = 8L, revision = 2L),
        )

        assertTrue(first.requestRebind)
        assertTrue(first.state.isRecovering(failed))
        assertFalse(duplicate.requestRebind)
        assertFalse(failedRecovery.requestRebind)
    }

    @Test
    fun nonFailedAndGenerationZeroSnapshotsNeverRequestRecovery() {
        val idle = LivePoseInferenceSnapshot.idle()
        val released = LivePoseInferenceSnapshot(
            sessionGeneration = 4L,
            revision = 2L,
            phase = LivePoseInferencePhase.RELEASED,
            poseCount = null,
            callbackCount = 1L,
            resultTimestampMs = null,
        )

        assertFalse(reduceLivePoseAutoRecovery(LivePoseAutoRecoveryState(), idle).requestRebind)
        assertFalse(reduceLivePoseAutoRecovery(LivePoseAutoRecoveryState(), released).requestRebind)
    }

    @Test
    fun aNewPolicyEpochCanRecoverAfterLifecycleOrLensReset() {
        val failed = failed(generation = 11L, revision = 2L)
        val prior = reduceLivePoseAutoRecovery(LivePoseAutoRecoveryState(), failed)

        assertFalse(reduceLivePoseAutoRecovery(prior.state, failed).requestRebind)
        assertTrue(
            reduceLivePoseAutoRecovery(LivePoseAutoRecoveryState(), failed).requestRebind,
        )
    }

    @Test
    fun pausedLateFailureDoesNotConsumeTheNextResumedEpochBudget() {
        val failed = failed(generation = 13L, revision = 2L)
        val paused = decideLivePoseAutoRecovery(
            LivePoseAutoRecoveryState(),
            failed,
            lifecycleResumed = false,
        )
        val resumed = decideLivePoseAutoRecovery(
            paused.state,
            failed(generation = 14L, revision = 1L),
            lifecycleResumed = true,
        )

        assertFalse(paused.requestRebind)
        assertTrue(resumed.requestRebind)
    }

    @Test
    fun duplicateFailedRevisionDoesNotInvalidateAnAlreadyScheduledRecovery() {
        val original = failed(generation = 15L, revision = 2L)
        val duplicate = original.copy(revision = 3L)

        assertTrue(
            canCompleteLivePoseAutoRecovery(
                lifecycleResumed = true,
                inference = duplicate,
                failedGeneration = original.sessionGeneration,
            ),
        )
        assertFalse(
            canCompleteLivePoseAutoRecovery(
                lifecycleResumed = false,
                inference = duplicate,
                failedGeneration = original.sessionGeneration,
            ),
        )
        assertFalse(
            canCompleteLivePoseAutoRecovery(
                lifecycleResumed = true,
                inference = failed(generation = 16L, revision = 1L),
                failedGeneration = original.sessionGeneration,
            ),
        )
    }

    private fun failed(
        generation: Long,
        revision: Long,
        reason: LivePoseFailureReason = LivePoseFailureReason.RESULT_TIMEOUT,
    ) = LivePoseInferenceSnapshot(
        sessionGeneration = generation,
        revision = revision,
        phase = LivePoseInferencePhase.FAILED,
        poseCount = null,
        callbackCount = 0L,
        resultTimestampMs = null,
        failureReason = reason,
    )
}
