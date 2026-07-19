package com.motionarcade.vision.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FishingMotionFrameTest {
    @Test
    fun frameDeepCopiesCompleteCoordinateFreeSignalCollection() {
        val mutable = samples().toMutableList()
        val frame = frame(mutable)
        mutable.clear()

        assertEquals(7, frame.samples.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.samples as MutableList).clear()
        }
    }

    @Test
    fun metadataNonFiniteWrongPlayerAndIncompleteTypeSetFailClosed() {
        val baseline = samples()
        val invalid = listOf(
            baseline.toMutableList().apply {
                this[0] = this[0].copy(metadata = mapOf("coordinate_x" to 0.5f))
            },
            baseline.toMutableList().apply {
                this[0] = this[0].copy(activation = Float.NaN)
            },
            baseline.toMutableList().apply {
                this[0] = this[0].copy(playerId = PlayerId.P2)
            },
            baseline.dropLast(1),
        )

        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { frame(candidate) }
        }
    }

    private fun frame(samples: Collection<MotionSignalSample>): FishingMotionFrame =
        FishingMotionFrame(
            sessionGeneration = 1L,
            revision = 2L,
            sourceTimestampNs = 100L,
            poseCount = 1,
            usableForSolo = true,
            continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
            configId = "fishing-qa-candidate-v1",
            calibrationRevision = 1,
            samples = samples,
        )

    private fun samples(): List<MotionSignalSample> = TYPES.map { type ->
        MotionSignalSample(
            playerId = PlayerId.P1,
            type = type,
            timestampNs = 100L,
            activation = 0.5f,
            quality = 0.5f,
            confidence = 0.9f,
            calibrationRevision = 1,
            source = InputSource.MOTION,
        )
    }

    private companion object {
        val TYPES = listOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )
    }
}
