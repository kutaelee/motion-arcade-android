package com.motionarcade.vision.camera

import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestLivePoseInferenceMainDeliveryTest {
    @Test
    fun stalledMainKeepsOneRunnableAndOneLatestSnapshotAcrossOneHundredFrames() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<LivePoseInferenceSnapshot>()
        val handoff = handoff(executor, LivePoseInferenceSink(delivered::add))

        repeat(100) { index ->
            handoff.onInference(active(revision = index + 1L))
        }

        assertEquals(1, executor.size)
        assertEquals(
            LatestLivePoseInferenceDeliveryCounters(
                pendingSnapshotCount = 1,
                maximumPendingSnapshotCount = 1,
                replacedSnapshotCount = 99L,
                droppedSnapshotCount = 0L,
                deliveredSnapshotCount = 0L,
                executorRejectionCount = 0L,
                sinkFailureCount = 0L,
            ),
            handoff.counters(),
        )

        executor.runNext()

        assertEquals(100L, delivered.single().revision)
        assertEquals(0, handoff.counters().pendingSnapshotCount)
        assertEquals(1L, handoff.counters().deliveredSnapshotCount)
    }

    @Test
    fun queuedSnapshotIsDroppedWhenItsGenerationGateIsRevokedBeforeMainRuns() {
        val executor = QueuedExecutor()
        val requestGate = CameraSessionRequestGate()
        val token = requestGate.begin()
        val delivered = mutableListOf<LivePoseInferenceSnapshot>()
        val handoff = LatestLivePoseInferenceMainDelivery(
            sessionGeneration = token.generation,
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                requestGate.deliverIfCurrent(token, delivery)
            },
            productSink = LivePoseInferenceSink(delivered::add),
        )

        handoff.onInference(active(revision = 1L, generation = token.generation))
        requestGate.begin()
        executor.runNext()

        assertTrue(delivered.isEmpty())
        assertEquals(1L, handoff.counters().droppedSnapshotCount)
    }

    @Test
    fun lowerRevisionTerminalReplacesPendingNonterminalAndLocksGeneration() {
        listOf(LivePoseInferencePhase.FAILED, LivePoseInferencePhase.RELEASED).forEach { phase ->
            val executor = QueuedExecutor()
            val delivered = mutableListOf<LivePoseInferenceSnapshot>()
            val handoff = handoff(executor, LivePoseInferenceSink(delivered::add))

            handoff.onInference(active(revision = 100L))
            handoff.onInference(terminal(revision = 1L, phase = phase))
            handoff.onInference(active(revision = 101L))

            assertEquals(1, executor.size)
            executor.runNext()

            assertEquals(phase, delivered.single().phase)
            assertEquals(1L, delivered.single().revision)
            assertEquals(1L, handoff.counters().replacedSnapshotCount)
            assertEquals(1L, handoff.counters().droppedSnapshotCount)

            handoff.onInference(active(revision = 102L))
            assertEquals(0, executor.size)
            assertEquals(2L, handoff.counters().droppedSnapshotCount)
        }
    }

    @Test
    fun executorRejectionDropsPendingAndNextAcceptedDeliveryReestablishesBoundedState() {
        val executor = RejectFirstExecutor()
        val delivered = mutableListOf<LivePoseInferenceSnapshot>()
        val handoff = handoff(executor, LivePoseInferenceSink(delivered::add))

        handoff.onInference(active(revision = 1L))

        assertEquals(0, handoff.counters().pendingSnapshotCount)
        assertEquals(1L, handoff.counters().executorRejectionCount)
        assertEquals(1L, handoff.counters().droppedSnapshotCount)

        repeat(100) { index ->
            handoff.onInference(active(revision = index + 2L))
        }

        assertEquals(1, executor.size)
        assertEquals(1, handoff.counters().pendingSnapshotCount)
        executor.runNext()
        assertEquals(101L, delivered.single().revision)
        assertEquals(1, handoff.counters().maximumPendingSnapshotCount)
        assertEquals(99L, handoff.counters().replacedSnapshotCount)
    }

    @Test
    fun sinkExceptionIsContainedAndDoesNotPreventTheNextDelivery() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<LivePoseInferenceSnapshot>()
        var shouldThrow = true
        val handoff = handoff(
            executor,
            LivePoseInferenceSink { snapshot ->
                if (shouldThrow) {
                    shouldThrow = false
                    throw IllegalStateException("presentation failed once")
                }
                delivered += snapshot
            },
        )

        handoff.onInference(active(revision = 1L))
        executor.runNext()
        handoff.onInference(active(revision = 2L))
        executor.runNext()

        assertEquals(2L, delivered.single().revision)
        assertEquals(1L, handoff.counters().sinkFailureCount)
        assertEquals(1L, handoff.counters().droppedSnapshotCount)
        assertEquals(1L, handoff.counters().deliveredSnapshotCount)
    }

    private fun handoff(
        executor: Executor,
        sink: LivePoseInferenceSink,
    ): LatestLivePoseInferenceMainDelivery =
        LatestLivePoseInferenceMainDelivery(
            sessionGeneration = GENERATION,
            mainExecutor = executor,
            deliveryGate = LivePoseObservationDeliveryGate.UNCONDITIONAL,
            productSink = sink,
        )

    private fun active(
        revision: Long,
        generation: Long = GENERATION,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = generation,
            revision = revision,
            phase = LivePoseInferencePhase.ACTIVE,
            poseCount = 1,
            callbackCount = revision.coerceAtLeast(1L),
            resultTimestampMs = revision,
        )

    private fun terminal(
        revision: Long,
        phase: LivePoseInferencePhase,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = GENERATION,
            revision = revision,
            phase = phase,
            poseCount = null,
            callbackCount = revision,
            resultTimestampMs = null,
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
        val size: Int get() = tasks.size

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
        const val GENERATION = 1L
    }
}
