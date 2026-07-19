package com.motionarcade.vision.camera

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrameSink
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestDualPlayerCombatMotionMainDeliveryTest {
    @Test
    fun stalledMainReceivesZeroSignalFenceBeforeNewestDualFrame() {
        val executor = QueuedExecutor()
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 0)
        val delivered = mutableListOf<DualPlayerCombatMotionFrame>()
        var resets = 0
        val handoff = LatestDualPlayerCombatMotionMainDelivery(
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate.UNCONDITIONAL,
            productSink = DualPlayerCombatMotionFrameSink(delivered::add),
            onFrameReplaced = { resets += 1 },
        )

        handoff.onDualPlayerCombatMotionFrame(frame(config, revision = 1L, timestampNs = 100L))
        handoff.onDualPlayerCombatMotionFrame(frame(config, revision = 2L, timestampNs = 200L))
        handoff.onDualPlayerCombatMotionFrame(frame(config, revision = 3L, timestampNs = 300L))

        assertEquals(1, executor.size)
        assertEquals(2, resets)
        executor.runNext()

        assertEquals(listOf(1L, 3L), delivered.map(DualPlayerCombatMotionFrame::revision))
        assertFalse(delivered.first().usableForDual)
        assertEquals(LivePoseContinuityBoundary.RESET_REVISION_GAP, delivered.first().continuityBoundary)
        assertTrue(delivered.first().samples.all { it.activation == 0f && it.confidence == 0f })
        assertTrue(delivered.last().usableForDual)
    }

    @Test
    fun failedFenceCallbackMustBeRetriedBeforeANewerUsableFrame() {
        val executor = QueuedExecutor()
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 0)
        val delivered = mutableListOf<DualPlayerCombatMotionFrame>()
        var failFence = true
        val handoff = LatestDualPlayerCombatMotionMainDelivery(
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate.UNCONDITIONAL,
            productSink = DualPlayerCombatMotionFrameSink { frame ->
                if (frame.isFence() && failFence) error("fence consumer unavailable")
                delivered += frame
            },
            onFrameReplaced = {},
        )

        handoff.onDualPlayerCombatMotionFrame(frame(config, revision = 1L, timestampNs = 100L))
        handoff.onDualPlayerCombatMotionFrame(frame(config, revision = 2L, timestampNs = 200L))
        executor.runNext()
        assertTrue(delivered.isEmpty())

        failFence = false
        handoff.onDualPlayerCombatMotionFrame(frame(config, revision = 3L, timestampNs = 300L))
        executor.runNext()

        assertEquals(listOf(1L, 3L), delivered.map(DualPlayerCombatMotionFrame::revision))
        assertFalse(delivered.first().usableForDual)
        assertTrue(delivered.last().usableForDual)
    }

    private fun frame(
        config: DualPlayerCombatMotionConfig,
        revision: Long,
        timestampNs: Long,
    ): DualPlayerCombatMotionFrame =
        DualPlayerCombatMotionFrame(
            sessionGeneration = 1L,
            revision = revision,
            sourceTimestampNs = timestampNs,
            poseCount = 2,
            usableForDual = true,
            continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
            configId = config.configId,
            calibrationRevision = config.calibrationRevision,
            profile = config.profile,
            samples = listOf(PlayerId.P1, PlayerId.P2).flatMap { playerId ->
                config.motionTypes.map { type ->
                    MotionSignalSample(
                        playerId = playerId,
                        type = type,
                        timestampNs = timestampNs,
                        activation = 0.75f,
                        quality = 0.75f,
                        confidence = 0.9f,
                        calibrationRevision = config.calibrationRevision,
                        source = InputSource.MOTION,
                    )
                }
            },
        )

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private fun DualPlayerCombatMotionFrame.isFence(): Boolean =
        !usableForDual || continuityBoundary != LivePoseContinuityBoundary.CONTIGUOUS
}
