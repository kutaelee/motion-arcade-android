package com.motionarcade.vision.camera

import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePoseGenerationReducerTest {
    @Test
    fun cameraFailurePublishesOwnedTerminalRevisionBeforeDroppingLateCallbacks() {
        val gate = CameraSessionRequestGate()
        val token = gate.begin()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val reducer = LivePoseGenerationReducer()
        reducer.begin(token, LivePoseInferenceSink(snapshots::add))
        reducer.accept(token, active(token.generation, revision = 3L, timestampMs = 12L))

        reducer.terminate(token, LivePoseInferencePhase.FAILED)
        reducer.accept(token, active(token.generation, revision = 5L, timestampMs = 13L))

        assertEquals(
            listOf(
                LivePoseInferencePhase.IDLE,
                LivePoseInferencePhase.ACTIVE,
                LivePoseInferencePhase.FAILED,
            ),
            snapshots.map { it.phase },
        )
        val terminal = snapshots.last()
        assertEquals(token.generation, terminal.sessionGeneration)
        assertEquals(4L, terminal.revision)
        assertEquals(1L, terminal.callbackCount)
        assertEquals(null, terminal.poseCount)
        assertEquals(null, terminal.resultTimestampMs)
    }

    @Test
    fun newGenerationRejectsQueuedOldGenerationAndNonIncreasingRevision() {
        val gate = CameraSessionRequestGate()
        val first = gate.begin()
        val firstSnapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val secondSnapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val reducer = LivePoseGenerationReducer()
        reducer.begin(first, LivePoseInferenceSink(firstSnapshots::add))
        reducer.terminate(first, LivePoseInferencePhase.RELEASED)

        val second = gate.begin()
        reducer.begin(second, LivePoseInferenceSink(secondSnapshots::add))
        reducer.accept(first, active(first.generation, revision = 2L, timestampMs = 1L))
        reducer.accept(second, active(second.generation, revision = 2L, timestampMs = 2L))
        reducer.accept(second, active(second.generation, revision = 1L, timestampMs = 1L))

        assertEquals(2, secondSnapshots.size)
        assertEquals(second.generation, secondSnapshots.last().sessionGeneration)
        assertEquals(2L, secondSnapshots.last().revision)
        assertEquals(2L, secondSnapshots.last().resultTimestampMs)
    }

    @Test
    fun pipelineTerminalIsAbsorbingAndControllerReleaseDoesNotReplaceIt() {
        val gate = CameraSessionRequestGate()
        val token = gate.begin()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val reducer = LivePoseGenerationReducer()
        reducer.begin(token, LivePoseInferenceSink(snapshots::add))
        reducer.accept(
            token,
            LivePoseInferenceSnapshot(
                sessionGeneration = token.generation,
                revision = 3L,
                phase = LivePoseInferencePhase.FAILED,
                poseCount = null,
                callbackCount = 1L,
                resultTimestampMs = null,
            ),
        )

        reducer.accept(token, active(token.generation, revision = 4L, timestampMs = 2L))
        reducer.terminate(token, LivePoseInferencePhase.RELEASED)

        assertEquals(listOf(LivePoseInferencePhase.IDLE, LivePoseInferencePhase.FAILED), snapshots.map { it.phase })
    }

    private fun active(
        generation: Long,
        revision: Long,
        timestampMs: Long,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = generation,
            revision = revision,
            phase = LivePoseInferencePhase.ACTIVE,
            poseCount = 2,
            callbackCount = 1L,
            resultTimestampMs = timestampMs,
        )
}
