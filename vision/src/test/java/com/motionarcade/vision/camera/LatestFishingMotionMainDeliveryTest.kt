package com.motionarcade.vision.camera

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.motion.FishingMotionFrameSink
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestFishingMotionMainDeliveryTest {
    @Test
    fun stalledMainKeepsOneRunnableEarliestFenceAndLatestFrame() {
        val executor = QueuedExecutor()
        val requestGate = CameraSessionRequestGate()
        val token = requestGate.begin()
        val delivered = mutableListOf<FishingMotionFrame>()
        var replacements = 0
        val handoff = LatestFishingMotionMainDelivery(
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate { action ->
                requestGate.deliverIfCurrent(token, action)
            },
            productSink = FishingMotionFrameSink(delivered::add),
            onFrameReplaced = { replacements += 1 },
        )

        handoff.onFishingMotionFrame(frame(revision = 1L, timestampNs = 10L))
        handoff.onFishingMotionFrame(frame(revision = 2L, timestampNs = 20L))
        handoff.onFishingMotionFrame(frame(revision = 3L, timestampNs = 30L))

        assertEquals(1, executor.size)
        assertEquals(2, replacements)
        executor.runNext()

        assertEquals(2, delivered.size)
        val boundary = delivered.first()
        assertEquals(1L, boundary.revision)
        assertEquals(10L, boundary.sourceTimestampNs)
        assertFalse(boundary.usableForSolo)
        assertTrue(boundary.samples.all { it.activation == 0f && it.confidence == 0f })
        val latest = delivered.last()
        assertEquals(3L, latest.revision)
        assertEquals(30L, latest.sourceTimestampNs)
        assertTrue(latest.usableForSolo)
        assertEquals(
            LatestFishingMotionDeliveryCounters(
                pendingFrameCount = 0,
                maximumPendingFrameCount = 2,
                replacedFrameCount = 2L,
                droppedFrameCount = 0L,
                deliveredFrameCount = 2L,
                executorRejectionCount = 0L,
                sinkFailureCount = 0L,
            ),
            handoff.counters(),
        )
    }

    @Test
    fun queuedOldGenerationIsDroppedWhenRebindCompletesBeforeMainRuns() {
        val executor = QueuedExecutor()
        val requestGate = CameraSessionRequestGate()
        val oldToken = requestGate.begin()
        val delivered = mutableListOf<FishingMotionFrame>()
        val handoff = LatestFishingMotionMainDelivery(
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate { action ->
                requestGate.deliverIfCurrent(oldToken, action)
            },
            productSink = FishingMotionFrameSink(delivered::add),
        )

        handoff.onFishingMotionFrame(frame(1L, 10L))
        requestGate.begin()
        executor.runNext()

        assertTrue(delivered.isEmpty())
        assertEquals(1L, handoff.counters().droppedFrameCount)
    }

    @Test
    fun executorRejectionResetsHistoryAndForcesNextFrameThroughFence() {
        val executor = RejectFirstExecutor()
        val delivered = mutableListOf<FishingMotionFrame>()
        var resets = 0
        val handoff = LatestFishingMotionMainDelivery(
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate.UNCONDITIONAL,
            productSink = FishingMotionFrameSink(delivered::add),
            onFrameReplaced = { resets += 1 },
        )

        handoff.onFishingMotionFrame(frame(1L, 10L))
        handoff.onFishingMotionFrame(frame(2L, 20L))
        executor.runNext()

        assertEquals(1, resets)
        assertEquals(2, delivered.size)
        assertFalse(delivered.first().usableForSolo)
        assertTrue(delivered.first().samples.all { it.activation == 0f })
        assertTrue(delivered.last().usableForSolo)
        assertEquals(1L, handoff.counters().executorRejectionCount)
    }

    private fun frame(revision: Long, timestampNs: Long): FishingMotionFrame =
        FishingMotionFrame(
            sessionGeneration = 1L,
            revision = revision,
            sourceTimestampNs = timestampNs,
            poseCount = 1,
            usableForSolo = true,
            continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
            configId = "fishing-qa-candidate-v1",
            calibrationRevision = 1,
            samples = TYPES.map { type ->
                MotionSignalSample(
                    playerId = PlayerId.P1,
                    type = type,
                    timestampNs = timestampNs,
                    activation = 0.8f,
                    quality = 0.8f,
                    confidence = 0.9f,
                    calibrationRevision = 1,
                    source = InputSource.MOTION,
                )
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

    private class RejectFirstExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        private var rejected = false

        override fun execute(command: Runnable) {
            if (!rejected) {
                rejected = true
                throw IllegalStateException("rejected once")
            }
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
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
