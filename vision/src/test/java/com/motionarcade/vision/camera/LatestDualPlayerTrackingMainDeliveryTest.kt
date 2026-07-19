package com.motionarcade.vision.camera

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.TrackState
import com.motionarcade.vision.pose.DualPlayerRoleSetupPolicy
import com.motionarcade.vision.pose.DualPlayerRoleTrackingState
import com.motionarcade.vision.pose.DualPlayerTrackingSink
import com.motionarcade.vision.pose.DualPlayerTrackingSummary
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import com.motionarcade.vision.tracking.IdentityPauseReason
import com.motionarcade.vision.tracking.PlayerTrackerConfigReviewStatus
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestDualPlayerTrackingMainDeliveryTest {
    @Test
    fun stalledMainReceivesSafetyPauseBeforeNewerActiveState() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<DualPlayerTrackingSummary>()
        val handoff =
            LatestDualPlayerTrackingMainDelivery(
                mainExecutor = executor,
                deliveryGate = LivePoseObservationDeliveryGate.UNCONDITIONAL,
                productSink = DualPlayerTrackingSink(delivered::add),
            )

        handoff.onDualPlayerTrackingSummary(summary(revision = 11L, pauseReason = IdentityPauseReason.CROSSING_HYSTERESIS))
        handoff.onDualPlayerTrackingSummary(summary(revision = 12L, pauseReason = IdentityPauseReason.NONE))

        assertEquals(1, executor.size)
        executor.runNext()

        assertEquals(
            listOf(IdentityPauseReason.CROSSING_HYSTERESIS, IdentityPauseReason.NONE),
            delivered.map { it.pauseReason },
        )
        assertTrue(delivered.first().pauseRequired)
        assertTrue(!delivered.last().pauseRequired)
    }

    @Test
    fun rebindBeforeMainRunsDropsBothPendingSafetyAndLatestStates() {
        val executor = QueuedExecutor()
        val gate = CameraSessionRequestGate()
        val token = gate.begin()
        val delivered = mutableListOf<DualPlayerTrackingSummary>()
        val handoff =
            LatestDualPlayerTrackingMainDelivery(
                mainExecutor = executor,
                deliveryGate = LivePoseObservationDeliveryGate { action ->
                    gate.deliverIfCurrent(token, action)
                },
                productSink = DualPlayerTrackingSink(delivered::add),
            )

        handoff.onDualPlayerTrackingSummary(summary(revision = 1L, pauseReason = IdentityPauseReason.ASSIGNMENT_AMBIGUOUS))
        handoff.onDualPlayerTrackingSummary(summary(revision = 2L, pauseReason = IdentityPauseReason.NONE))
        gate.begin()
        executor.runNext()

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun failedPauseCallbackSuppressesActiveUntilAPauseIsAcknowledged() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<DualPlayerTrackingSummary>()
        var failPause = true
        val handoff =
            LatestDualPlayerTrackingMainDelivery(
                mainExecutor = executor,
                deliveryGate = LivePoseObservationDeliveryGate.UNCONDITIONAL,
                productSink =
                    DualPlayerTrackingSink { summary ->
                        if (summary.pauseRequired && failPause) error("pause consumer unavailable")
                        delivered += summary
                    },
            )

        handoff.onDualPlayerTrackingSummary(summary(revision = 21L, pauseReason = IdentityPauseReason.PLAYER_OVERLAP))
        handoff.onDualPlayerTrackingSummary(summary(revision = 22L, pauseReason = IdentityPauseReason.NONE))
        executor.runNext()
        assertTrue(delivered.isEmpty())

        handoff.onDualPlayerTrackingSummary(summary(revision = 23L, pauseReason = IdentityPauseReason.NONE))
        executor.runNext()
        assertTrue(delivered.isEmpty())

        failPause = false
        handoff.onDualPlayerTrackingSummary(summary(revision = 24L, pauseReason = IdentityPauseReason.NONE))
        executor.runNext()
        assertEquals(
            "The exact failed safety pause must be retried before a later active state.",
            listOf(21L, 24L),
            delivered.map { it.revision },
        )
    }

    @Test
    fun failedDeliveryGateRetainsPauseBeforeANewerActiveState() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<DualPlayerTrackingSummary>()
        var gateAvailable = false
        val handoff =
            LatestDualPlayerTrackingMainDelivery(
                mainExecutor = executor,
                deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                    if (!gateAvailable) error("generation gate unavailable")
                    delivery()
                    true
                },
                productSink = DualPlayerTrackingSink(delivered::add),
            )

        handoff.onDualPlayerTrackingSummary(summary(revision = 31L, pauseReason = IdentityPauseReason.ASSIGNMENT_AMBIGUOUS))
        handoff.onDualPlayerTrackingSummary(summary(revision = 32L, pauseReason = IdentityPauseReason.NONE))
        executor.runNext()
        assertTrue(delivered.isEmpty())

        gateAvailable = true
        handoff.onDualPlayerTrackingSummary(summary(revision = 33L, pauseReason = IdentityPauseReason.NONE))
        executor.runNext()

        assertEquals(listOf(31L, 33L), delivered.map { it.revision })
    }

    private fun summary(revision: Long, pauseReason: IdentityPauseReason): DualPlayerTrackingSummary =
        DualPlayerTrackingSummary(
            sessionGeneration = 7L,
            revision = revision,
            detectedPoseCount = 2,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            roleStates =
                listOf(
                    DualPlayerRoleTrackingState(PlayerId.P1, TrackState.ACTIVE),
                    DualPlayerRoleTrackingState(PlayerId.P2, TrackState.ACTIVE),
                ),
            pauseRequired = pauseReason != IdentityPauseReason.NONE,
            pauseReason = pauseReason,
            rearmChallengeId = 1L,
            configId = "dual-delivery-fixture-v1",
            configSchemaVersion = 1,
            configSourceSha256Hex = "0".repeat(64),
            configReviewStatus = PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED,
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
}
