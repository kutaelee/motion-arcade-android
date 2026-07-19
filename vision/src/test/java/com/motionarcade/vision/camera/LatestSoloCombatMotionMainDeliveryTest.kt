package com.motionarcade.vision.camera

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.motion.SoloCombatMotionFrameSink
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSoloCombatMotionMainDeliveryTest {
    @Test
    fun stalledMainReceivesFenceBeforeNewestSoloFrame() {
        val executor = QueuedExecutor()
        val config = DualPlayerCombatMotionConfigs.monster(0)
        val delivered = mutableListOf<SoloCombatMotionFrame>()
        var resets = 0
        val handoff = LatestSoloCombatMotionMainDelivery(
            executor,
            LivePoseObservationDeliveryGate.UNCONDITIONAL,
            SoloCombatMotionFrameSink(delivered::add),
            onFrameReplaced = { resets += 1 },
        )

        handoff.onSoloCombatMotionFrame(frame(config, 1L, 100L))
        handoff.onSoloCombatMotionFrame(frame(config, 2L, 200L))
        handoff.onSoloCombatMotionFrame(frame(config, 3L, 300L))
        assertEquals(1, executor.size)
        assertEquals(2, resets)
        executor.runNext()

        assertEquals(listOf(1L, 3L), delivered.map { it.revision })
        assertFalse(delivered.first().usableForSolo)
        assertTrue(delivered.first().samples.all { it.activation == 0f })
        assertTrue(delivered.last().usableForSolo)
    }

    @Test
    fun productSinkFailureReportsHardDeliveryFailure() {
        val executor = QueuedExecutor()
        val config = DualPlayerCombatMotionConfigs.monster(0)
        var failures = 0
        val handoff = LatestSoloCombatMotionMainDelivery(
            executor,
            LivePoseObservationDeliveryGate.UNCONDITIONAL,
            SoloCombatMotionFrameSink { error("broken product callback") },
            onFrameReplaced = {},
            onDeliveryFailure = { failures += 1 },
        )

        handoff.onSoloCombatMotionFrame(frame(config, 1L, 100L))
        executor.runNext()

        assertEquals(1, failures)
    }

    private fun frame(config: DualPlayerCombatMotionConfig, revision: Long, timestampNs: Long) =
        SoloCombatMotionFrame(
            sessionGeneration = 1L,
            revision = revision,
            sourceTimestampNs = timestampNs,
            poseCount = 1,
            usableForSolo = true,
            continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
            configId = config.configId,
            calibrationRevision = config.calibrationRevision,
            profile = config.profile,
            samples = config.motionTypes.map { type ->
                MotionSignalSample(PlayerId.P1, type, timestampNs, 0.75f, 0.75f, 0.9f, config.calibrationRevision, InputSource.MOTION)
            },
        )

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }
}
