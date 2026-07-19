package com.motionarcade.vision.tracking

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PlayerTrackerTest {
    @Test
    fun explicitInitializationBecomesActiveAndPreservesBothRoleEvents() {
        val tracker = fixture.tracker()
        val initialFrame = fixture.frame(
            0,
            fixture.observation(10, 0.25),
            fixture.observation(20, 0.75),
        )
        val initialized = tracker.initialize(
            initialFrame,
            fixture.binding(initialFrame, p1ObservationId = 10, p2ObservationId = 20),
        ).accepted()

        assertEquals(fixture.config.configId, initialized.configId)
        assertEquals(fixture.config.schemaVersion, initialized.configSchemaVersion)
        assertEquals(PlayerTrackerConfigJson.SCHEMA_ID, initialized.configSchemaId)
        assertEquals(sha256(fixture.configSourceText), initialized.configSourceSha256Hex)
        assertEquals(PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED, initialized.configReviewStatus)
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), initialized.assignments.map { it.roleId })
        assertEquals(listOf(TrackState.TENTATIVE, TrackState.TENTATIVE), initialized.assignments.map { it.state })
        assertEquals(2, initialized.transitions.size)
        assertNotEquals(initialized.p1().trackId, initialized.p2().trackId)

        val active = tracker.processFrame(
            fixture.frame(
                400,
                fixture.observation(7, 0.75),
                fixture.observation(8, 0.25),
            ),
        ).accepted()

        assertFalse(active.pauseRequired)
        assertEquals(TrackState.ACTIVE, active.p1().state)
        assertEquals(TrackState.ACTIVE, active.p2().state)
        assertEquals(8, active.p1().observationId)
        assertEquals(7, active.p2().observationId)
        assertEquals(initialized.p1().trackId, active.p1().trackId)
        assertEquals(initialized.p2().trackId, active.p2().trackId)
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), active.transitions.map { it.roleId })
    }

    @Test
    fun insufficientCostGapDoesNotGuessAndRequiresExplicitNeutralRearm() {
        val tracker = fixture.initializedActiveTracker(
            config = fixture.config.copy(proximityBodyScalePauseThreshold = 0.0001),
        )
        val oldP1 = tracker.currentOutput()!!.p1().trackId
        val oldP2 = tracker.currentOutput()!!.p2().trackId

        val ambiguousFrame = fixture.frame(
            500,
            fixture.observation(101, 0.4999, bodyScale = 0.15, halfWidth = 0.04),
            fixture.observation(102, 0.5001, bodyScale = 0.25, halfWidth = 0.08),
        )
        val ambiguous = tracker.processFrame(ambiguousFrame).accepted()

        assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, ambiguous.pauseReason)
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), ambiguous.assignments.map { it.state })
        assertTrue(ambiguous.assignments.all { it.observationId == null })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), ambiguous.transitions.map { it.roleId })

        val rearm = tracker.requestRearm(fixture.binding(ambiguousFrame, 101, 102)).accepted()
        assertEquals(listOf(TrackState.REARM, TrackState.REARM), rearm.assignments.map { it.state })
        assertTrue(rearm.assignments.all { it.trackId == null })

        val half = tracker.processFrame(
            fixture.frame(
                900,
                fixture.observation(201, 0.54, bodyScale = 0.15),
                fixture.observation(202, 0.46, bodyScale = 0.25),
            ),
        ).accepted()
        assertEquals(IdentityPauseReason.REARM_STABILITY, half.pauseReason)

        tracker.processFrame(
            fixture.frame(
                1_300,
                fixture.observation(251, 0.58, bodyScale = 0.15),
                fixture.observation(252, 0.42, bodyScale = 0.25),
            ),
        ).accepted()

        val complete = tracker.processFrame(
            fixture.frame(
                1_500,
                fixture.observation(301, 0.60, bodyScale = 0.15),
                fixture.observation(302, 0.40, bodyScale = 0.25),
            ),
        ).accepted()
        assertFalse("unexpected pause: ${complete.pauseReason}", complete.pauseRequired)
        assertEquals(listOf(TrackState.ACTIVE, TrackState.ACTIVE), complete.assignments.map { it.state })
        assertNotEquals(oldP1, complete.p1().trackId)
        assertNotEquals(oldP2, complete.p2().trackId)
    }

    @Test
    fun confidentCrossingUsesHysteresisAndKeepsTrackIdsStable() {
        val p1 = fixture.observation(1, 0.20)
        val p2 = fixture.observation(2, 0.80)
        val tracker = fixture.tracker()
        val initialFrame = fixture.frame(0, p1, p2)
        tracker.initialize(
            initialFrame,
            fixture.binding(initialFrame, 1, 2),
        ).accepted()
        val active = tracker.processFrame(
            fixture.frame(
                400,
                fixture.observation(41, 0.40),
                fixture.observation(42, 0.60),
            ),
        ).accepted()

        val pending = tracker.processFrame(
            fixture.frame(
                700,
                fixture.observation(70, 0.45),
                fixture.observation(71, 0.55),
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.CROSSING_HYSTERESIS, pending.pauseReason)
        assertEquals(listOf(TrackState.ACTIVE, TrackState.ACTIVE), pending.assignments.map { it.state })
        assertTrue(pending.assignments.all { it.observationId == null })

        val crossed = tracker.processFrame(
            fixture.frame(
                900,
                fixture.observation(90, 0.35),
                fixture.observation(91, 0.65),
            ),
        ).accepted()

        assertFalse(crossed.pauseRequired)
        assertEquals(91, crossed.p1().observationId)
        assertEquals(90, crossed.p2().observationId)
        assertEquals(active.p1().trackId, crossed.p1().trackId)
        assertEquals(active.p2().trackId, crossed.p2().trackId)
    }

    @Test
    fun completeOcclusionTransitionsAt300_800And1500Milliseconds() {
        val tracker = fixture.initializedActiveTracker()

        val at300 = tracker.processFrame(fixture.frame(700)).accepted()
        assertEquals(listOf(TrackState.OCCLUDED, TrackState.OCCLUDED), at300.assignments.map { it.state })
        assertEquals(IdentityPauseReason.INSUFFICIENT_OBSERVATIONS, at300.pauseReason)

        val at800 = tracker.processFrame(fixture.frame(1_200)).accepted()
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), at800.assignments.map { it.state })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), at800.transitions.map { it.roleId })

        val at1500 = tracker.processFrame(fixture.frame(1_900)).accepted()
        assertEquals(listOf(TrackState.LOST, TrackState.LOST), at1500.assignments.map { it.state })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), at1500.transitions.map { it.roleId })
    }

    @Test
    fun briefOcclusionRecoversTheSameTrackWithoutRoleStealing() {
        val tracker = fixture.initializedActiveTracker()
        val original = tracker.currentOutput()!!
        tracker.processFrame(
            fixture.frame(700, fixture.observation(71, 0.25)),
        ).accepted()

        val recovered = tracker.processFrame(
            fixture.frame(
                750,
                fixture.observation(75, 0.75),
                fixture.observation(76, 0.25),
            ),
        ).accepted()

        assertFalse(recovered.pauseRequired)
        assertEquals(76, recovered.p1().observationId)
        assertEquals(75, recovered.p2().observationId)
        assertEquals(original.p1().trackId, recovered.p1().trackId)
        assertEquals(original.p2().trackId, recovered.p2().trackId)
    }

    @Test
    fun singlePoseStickyAssociationCannotBeStolenByTheOccludedRole() {
        val tracker = fixture.initializedActiveTracker(
            config = fixture.config.copy(
                velocityMismatchWeight = 0.0,
                maximumPredictionHorizonNanos = 0L,
            ),
        )
        tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.40),
                fixture.observation(52, 0.75),
            ),
        ).accepted()

        val firstSingle = tracker.processFrame(
            fixture.frame(600, fixture.observation(61, 0.55)),
        ).accepted()
        val movedTowardOtherHome = tracker.processFrame(
            fixture.frame(700, fixture.observation(71, 0.70)),
        ).accepted()

        assertEquals(61, firstSingle.p1().observationId)
        assertNull(firstSingle.p2().observationId)
        assertEquals(TrackState.OCCLUDED, firstSingle.p2().state)
        assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, movedTowardOtherHome.pauseReason)
        assertEquals(
            listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS),
            movedTowardOtherHome.assignments.map { it.state },
        )
        assertTrue(movedTowardOtherHome.assignments.all { it.observationId == null })
    }

    @Test
    fun reviewerOcclusionTrajectoryCannotCommitSwapForEitherInputPermutation() {
        listOf(false, true).forEach { reverseInputOrder ->
            val tracker = fixture.initializedActiveTracker()
            val original = tracker.currentOutput()!!
            tracker.processFrame(
                fixture.frame(
                    500,
                    fixture.observation(51, 0.40),
                    fixture.observation(52, 0.75),
                ),
            ).accepted()
            tracker.processFrame(
                fixture.frame(600, fixture.observation(61, 0.55)),
            ).accepted()
            val stillSticky = tracker.processFrame(
                fixture.frame(700, fixture.observation(71, 0.70)),
            ).accepted()
            assertEquals(71, stillSticky.p1().observationId)
            assertEquals(TrackState.OCCLUDED, stillSticky.p2().state)

            val actualP1 = fixture.observation(81, 0.70)
            val actualP2 = fixture.observation(82, 0.85)
            val firstReturn = if (reverseInputOrder) {
                fixture.frame(800, actualP2, actualP1)
            } else {
                fixture.frame(800, actualP1, actualP2)
            }
            val failedClosed = tracker.processFrame(firstReturn).accepted()

            assertEquals(IdentityPauseReason.REARM_REQUIRED, failedClosed.pauseReason)
            assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), failedClosed.assignments.map { it.state })
            assertTrue(failedClosed.assignments.all { it.observationId == null })
            assertEquals(original.p1().trackId, failedClosed.p1().trackId)
            assertEquals(original.p2().trackId, failedClosed.p2().trackId)

            val nextActualP1 = fixture.observation(91, 0.70)
            val nextActualP2 = fixture.observation(92, 0.85)
            val secondReturn = if (reverseInputOrder) {
                fixture.frame(900, nextActualP2, nextActualP1)
            } else {
                fixture.frame(900, nextActualP1, nextActualP2)
            }
            val remainsClosed = tracker.processFrame(secondReturn).accepted()
            assertEquals(IdentityPauseReason.REARM_REQUIRED, remainsClosed.pauseReason)
            assertTrue(remainsClosed.assignments.all { it.observationId == null })
        }
    }

    @Test
    fun stickyAssociationGateFailureFailsClosedInsteadOfSwitchingRoles() {
        val tracker = fixture.initializedActiveTracker(
            config = fixture.config.copy(absoluteAssignmentGate = 0.10),
        )
        val latched = tracker.processFrame(
            fixture.frame(500, fixture.observation(51, 0.25)),
        ).accepted()
        assertEquals(51, latched.p1().observationId)
        assertNull(latched.p2().observationId)

        val failedGate = tracker.processFrame(
            fixture.frame(600, fixture.observation(61, 0.60)),
        ).accepted()

        assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, failedGate.pauseReason)
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), failedGate.assignments.map { it.state })
        assertTrue(failedGate.assignments.all { it.observationId == null })
    }

    @Test
    fun stickyAssociationTieAndExactMarginBoundaryBothRequireRearm() {
        val config = fixture.config.copy(
            pelvisDistanceWeight = 1.0,
            shoulderDistanceWeight = 0.0,
            velocityMismatchWeight = 0.0,
            scaleMismatchWeight = 0.0,
            directionDiscontinuityWeight = 0.0,
            lanePenaltyWeight = 0.0,
            absoluteAssignmentGate = 10.0,
            assignmentMargin = 0.5,
            maximumPredictionHorizonNanos = 0L,
        )
        fun latchedTracker(): PlayerTracker {
            val tracker = fixture.initializedActiveTracker(
                config = config,
                p1 = fixture.observation(1, 0.25, bodyScale = 0.25),
                p2 = fixture.observation(2, 0.75, bodyScale = 0.25),
            )
            val latched = tracker.processFrame(
                fixture.frame(500, fixture.observation(51, 0.375, bodyScale = 0.25)),
            ).accepted()
            assertEquals(51, latched.p1().observationId)
            return tracker
        }

        val tie = latchedTracker().processFrame(
            fixture.frame(600, fixture.observation(61, 0.5625, bodyScale = 0.25)),
        ).accepted()
        val exactMargin = latchedTracker().processFrame(
            fixture.frame(600, fixture.observation(61, 0.50, bodyScale = 0.25)),
        ).accepted()

        listOf(tie, exactMargin).forEach { output ->
            assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, output.pauseReason)
            assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), output.assignments.map { it.state })
            assertTrue(output.assignments.all { it.observationId == null })
        }
    }

    @Test
    fun eightHundredMillisecondOcclusionCannotAutoRecoverAssignments() {
        val tracker = fixture.initializedActiveTracker()
        tracker.processFrame(fixture.frame(1_200)).accepted()

        val visibleAgain = tracker.processFrame(
            fixture.frame(
                1_300,
                fixture.observation(131, 0.25),
                fixture.observation(132, 0.75),
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.REARM_REQUIRED, visibleAgain.pauseReason)
        assertTrue(visibleAgain.assignments.all { it.observationId == null })
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), visibleAgain.assignments.map { it.state })
    }

    @Test
    fun onePlayerLeaveNeverAssignsBothRolesAndReentryNeedsRearm() {
        val tracker = fixture.initializedActiveTracker()
        val original = tracker.currentOutput()!!

        val shortLoss = tracker.processFrame(
            fixture.frame(700, fixture.observation(70, 0.25)),
        ).accepted()
        assertEquals(TrackState.ACTIVE, shortLoss.p1().state)
        assertEquals(TrackState.OCCLUDED, shortLoss.p2().state)
        assertEquals(70, shortLoss.p1().observationId)
        assertNull(shortLoss.p2().observationId)

        tracker.processFrame(
            fixture.frame(1_000, fixture.observation(100, 0.25)),
        ).accepted()
        tracker.processFrame(
            fixture.frame(1_300, fixture.observation(130, 0.25)),
        ).accepted()
        val left = tracker.processFrame(
            fixture.frame(1_600, fixture.observation(160, 0.25)),
        ).accepted()
        assertEquals(TrackState.ACTIVE, left.p1().state)
        assertEquals(TrackState.LOST, left.p2().state)
        assertEquals(160, left.p1().observationId)
        assertNull(left.p2().observationId)

        val returnedFrame = fixture.frame(
            1_800,
            fixture.observation(181, 0.25),
            fixture.observation(182, 0.75),
        )
        val returned = tracker.processFrame(returnedFrame).accepted()
        assertEquals(IdentityPauseReason.REARM_REQUIRED, returned.pauseReason)
        assertTrue(returned.assignments.all { it.observationId == null })

        tracker.requestRearm(fixture.binding(returnedFrame, 181, 182)).accepted()
        val nonNeutral = tracker.processFrame(
            fixture.frame(
                2_300,
                fixture.observation(231, 0.25, neutral = false),
                fixture.observation(232, 0.75, neutral = false),
            ),
        ).accepted()
        assertEquals(IdentityPauseReason.REARM_STABILITY, nonNeutral.pauseReason)

        tracker.processFrame(
            fixture.frame(
                2_800,
                fixture.observation(281, 0.25),
                fixture.observation(282, 0.75),
            ),
        ).accepted()
        val halfNeutral = tracker.processFrame(
            fixture.frame(
                3_200,
                fixture.observation(331, 0.25),
                fixture.observation(332, 0.75),
            ),
        ).accepted()
        assertEquals(IdentityPauseReason.REARM_STABILITY, halfNeutral.pauseReason)
        tracker.processFrame(
            fixture.frame(
                3_600,
                fixture.observation(361, 0.25),
                fixture.observation(362, 0.75),
            ),
        ).accepted()
        val rearmed = tracker.processFrame(
            fixture.frame(
                3_800,
                fixture.observation(381, 0.25),
                fixture.observation(382, 0.75),
            ),
        ).accepted()

        assertFalse(rearmed.pauseRequired)
        assertNotEquals(original.p1().trackId, rearmed.p1().trackId)
        assertNotEquals(original.p2().trackId, rearmed.p2().trackId)
    }

    @Test
    fun definitiveViewportExitMarksOnlyTheMatchedRoleLostImmediately() {
        val tracker = fixture.initializedActiveTracker()

        val output = tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(
                    51,
                    0.06,
                    definitiveFrameExit = true,
                ),
                fixture.observation(52, 0.75),
            ),
        ).accepted()

        assertEquals(TrackState.LOST, output.p1().state)
        assertEquals(TrackState.ACTIVE, output.p2().state)
        assertNull(output.p1().observationId)
        assertEquals(52, output.p2().observationId)
        assertEquals(IdentityPauseReason.REARM_REQUIRED, output.pauseReason)
        assertEquals(listOf(PlayerId.P1), output.transitions.map { it.roleId })
    }

    @Test
    fun iouOverlapPausesBothRolesAndPreservesBothTransitions() {
        val config = fixture.config.copy(
            overlapIouPauseThreshold = 0.20,
            proximityBodyScalePauseThreshold = 0.01,
        )
        val tracker = fixture.initializedActiveTracker(config)

        val output = tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.48, halfWidth = 0.12),
                fixture.observation(52, 0.52, halfWidth = 0.12),
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.PLAYER_OVERLAP, output.pauseReason)
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), output.assignments.map { it.state })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), output.transitions.map { it.roleId })
        assertEquals(listOf(500_000_000L, 500_000_000L), output.transitions.map { it.timestampNanos })
        assertTrue(output.assignments.all { it.observationId == null })
    }

    @Test
    fun proximityAlonePausesWhenBoundsDoNotOverlap() {
        val config = fixture.config.copy(
            overlapIouPauseThreshold = 1.0,
            proximityBodyScalePauseThreshold = 0.10,
        )
        val tracker = fixture.initializedActiveTracker(config)

        val output = tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.49, bodyScale = 0.40, halfWidth = 0.005),
                fixture.observation(52, 0.51, bodyScale = 0.40, halfWidth = 0.005),
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.PLAYER_OVERLAP, output.pauseReason)
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), output.assignments.map { it.state })
        assertTrue(output.assignments.all { it.observationId == null })
    }

    @Test
    fun unsafeIouOrProximityAtInitializationIsRejectedBeforeTracksExist() {
        val cases = listOf(
            Triple(
                fixture.config.copy(
                    overlapIouPauseThreshold = 0.20,
                    proximityBodyScalePauseThreshold = 0.01,
                ),
                fixture.observation(1, 0.45, halfWidth = 0.20),
                fixture.observation(2, 0.55, halfWidth = 0.20),
            ),
            Triple(
                fixture.config.copy(
                    overlapIouPauseThreshold = 1.0,
                    proximityBodyScalePauseThreshold = 0.10,
                ),
                fixture.observation(1, 0.49, bodyScale = 0.40, halfWidth = 0.005),
                fixture.observation(2, 0.51, bodyScale = 0.40, halfWidth = 0.005),
            ),
        )

        cases.forEach { (config, first, second) ->
            val tracker = fixture.tracker(config)
            val unsafeFrame = fixture.frame(0, first, second)
            val rejected = tracker.initialize(
                unsafeFrame,
                fixture.binding(unsafeFrame, first.observationId, second.observationId),
            ).rejected()
            assertEquals(PlayerTrackerViolation.INVALID_ROLE_BINDING, rejected.violation)
            assertNull(rejected.lastAcceptedOutput)
            assertNull(tracker.currentOutput())

            val safeFrame = fixture.frame(
                0,
                fixture.observation(11, 0.25),
                fixture.observation(12, 0.75),
            )
            assertTrue(
                tracker.initialize(safeFrame, fixture.binding(safeFrame, 11, 12)) is
                    PlayerTrackerResult.Accepted,
            )
        }
    }

    @Test
    fun exactTieAndExactMarginBoundaryBothFailClosed() {
        val marginConfig = fixture.config.copy(
            pelvisDistanceWeight = 1.0,
            shoulderDistanceWeight = 0.0,
            velocityMismatchWeight = 0.0,
            scaleMismatchWeight = 0.0,
            directionDiscontinuityWeight = 0.0,
            lanePenaltyWeight = 0.0,
            absoluteAssignmentGate = 10.0,
            assignmentMargin = 0.125,
        )
        val left = fixture.observation(1, 0.25, bodyScale = 0.25)
        val right = fixture.observation(2, 0.75, bodyScale = 0.25)
        val tieTracker = fixture.initializedActiveTracker(marginConfig, left, right)
        val marginTracker = fixture.initializedActiveTracker(marginConfig, left, right)
        val aboveMarginTracker = fixture.initializedActiveTracker(marginConfig, left, right)

        val tie = tieTracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.10, bodyScale = 0.25, halfWidth = 0.04),
                fixture.observation(52, 0.20, bodyScale = 0.25, halfWidth = 0.08),
            ),
        ).accepted()
        val exactMargin = marginTracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.4921875, bodyScale = 0.25),
                fixture.observation(52, 0.5078125, bodyScale = 0.25),
            ),
        ).accepted()
        val aboveMargin = aboveMarginTracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.484375, bodyScale = 0.25),
                fixture.observation(52, 0.515625, bodyScale = 0.25),
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, tie.pauseReason)
        assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, exactMargin.pauseReason)
        assertEquals(IdentityPauseReason.NONE, aboveMargin.pauseReason)
    }

    @Test
    fun lowConfidenceSecondPoseIsMissingRatherThanBorrowingTheOtherRole() {
        val tracker = fixture.initializedActiveTracker()

        val output = tracker.processFrame(
            fixture.frame(
                700,
                fixture.observation(71, 0.25),
                fixture.observation(72, 0.75, confidence = 0.20),
            ),
        ).accepted()

        assertEquals(TrackState.ACTIVE, output.p1().state)
        assertEquals(TrackState.OCCLUDED, output.p2().state)
        assertEquals(71, output.p1().observationId)
        assertNull(output.p2().observationId)
        assertEquals(IdentityPauseReason.INSUFFICIENT_OBSERVATIONS, output.pauseReason)
    }

    @Test
    fun detectorPipelineGapCannotBeMistakenForContinuousIdentity() {
        val tracker = fixture.initializedActiveTracker()

        val output = tracker.processFrame(
            fixture.frame(
                1_000,
                fixture.observation(101, 0.25),
                fixture.observation(102, 0.75),
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.REARM_REQUIRED, output.pauseReason)
        assertEquals(listOf(TrackState.AMBIGUOUS, TrackState.AMBIGUOUS), output.assignments.map { it.state })
        assertTrue(output.assignments.all { it.observationId == null })
    }

    @Test
    fun sameDirectionMovementWithDifferentBodyScalesPreservesRoles() {
        val p1 = fixture.observation(1, 0.20, bodyScale = 0.12)
        val p2 = fixture.observation(2, 0.70, bodyScale = 0.30)
        val tracker = fixture.tracker()
        val initialFrame = fixture.frame(0, p1, p2)
        val initialized = tracker.initialize(
            initialFrame,
            fixture.binding(initialFrame, 1, 2),
        ).accepted()

        val moved = tracker.processFrame(
            fixture.frame(
                400,
                fixture.observation(41, 0.75, bodyScale = 0.30),
                fixture.observation(42, 0.25, bodyScale = 0.12),
            ),
        ).accepted()

        assertFalse(moved.pauseRequired)
        assertEquals(42, moved.p1().observationId)
        assertEquals(41, moved.p2().observationId)
        assertEquals(initialized.p1().trackId, moved.p1().trackId)
        assertEquals(initialized.p2().trackId, moved.p2().trackId)
    }

    @Test
    fun fastDisplacementAndUpstreamListReorderingCannotInjectARoleSwap() {
        val tracker = fixture.initializedActiveTracker()

        val output = tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(52, 0.75),
                fixture.observation(51, 0.40),
            ),
        ).accepted()

        assertEquals(51, output.p1().observationId)
        assertEquals(52, output.p2().observationId)
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), output.assignments.map { it.roleId })
    }

    @Test
    fun configuredAbsoluteGateChangesDecisionWithoutAlgorithmConstants() {
        val strict = fixture.initializedActiveTracker(
            config = fixture.config.copy(absoluteAssignmentGate = 0.10),
        )
        val permissive = fixture.initializedActiveTracker(
            config = fixture.config.copy(absoluteAssignmentGate = 5.0),
        )
        val jump = fixture.frame(
            500,
            fixture.observation(51, 0.35),
            fixture.observation(52, 0.85),
        )

        val strictOutput = strict.processFrame(jump).accepted()
        val permissiveOutput = permissive.processFrame(jump).accepted()

        assertEquals(IdentityPauseReason.ASSIGNMENT_AMBIGUOUS, strictOutput.pauseReason)
        assertEquals(IdentityPauseReason.NONE, permissiveOutput.pauseReason)
    }

    @Test
    fun staleRoleBindingIsRejectedEvenWhenLatestFrameReusesObservationIds() {
        val tracker = fixture.initializedActiveTracker()
        val sourceFrame = fixture.frame(
            500,
            fixture.observation(101, 0.497, bodyScale = 0.15, halfWidth = 0.04),
            fixture.observation(102, 0.503, bodyScale = 0.25, halfWidth = 0.08),
        )
        tracker.processFrame(sourceFrame).accepted()
        val staleBinding = fixture.binding(sourceFrame, 101, 102)

        val latestFrame = fixture.frame(
            600,
            fixture.observation(101, 0.25),
            fixture.observation(102, 0.75),
        )
        val latest = tracker.processFrame(latestFrame).accepted()
        val rejected = tracker.requestRearm(staleBinding).rejected()

        assertEquals(PlayerTrackerViolation.INVALID_ROLE_BINDING, rejected.violation)
        assertSame(latest, rejected.lastAcceptedOutput)
        assertSame(latest, tracker.currentOutput())

        val accepted = tracker.requestRearm(fixture.binding(latestFrame, 101, 102)).accepted()
        assertEquals(IdentityPauseReason.REARM_STABILITY, accepted.pauseReason)
    }

    @Test
    fun bindingRequiresExactOpaqueFrameTokenAndCannotBeReplayed() {
        val sourceFrame = fixture.frame(
            0,
            fixture.observation(1, 0.25),
            fixture.observation(2, 0.75),
            frameToken = PlayerObservationFrameToken("binding-session", 1L),
        )
        val sameMetadataDifferentFrame = fixture.frame(
            0,
            fixture.observation(1, 0.25),
            fixture.observation(2, 0.75),
            frameToken = PlayerObservationFrameToken("binding-session", 2L),
        )
        val tracker = fixture.tracker()
        val wrongToken = tracker.initialize(
            sameMetadataDifferentFrame,
            fixture.binding(sourceFrame, 1, 2),
        ).rejected()
        assertEquals(PlayerTrackerViolation.INVALID_ROLE_BINDING, wrongToken.violation)
        assertNull(wrongToken.lastAcceptedOutput)
        tracker.initialize(
            sameMetadataDifferentFrame,
            fixture.binding(sameMetadataDifferentFrame, 1, 2),
        ).accepted()

        val rearmTracker = fixture.initializedActiveTracker()
        rearmTracker.processFrame(fixture.frame(1_000)).accepted()
        val rearmFrame = fixture.frame(
            1_100,
            fixture.observation(101, 0.25),
            fixture.observation(102, 0.75),
        )
        rearmTracker.processFrame(rearmFrame).accepted()
        val binding = fixture.binding(rearmFrame, 101, 102)
        val first = rearmTracker.requestRearm(binding).accepted()
        val replay = rearmTracker.requestRearm(binding).rejected()

        assertEquals(PlayerTrackerViolation.INVALID_ROLE_BINDING, replay.violation)
        assertSame(first, replay.lastAcceptedOutput)
        assertSame(first, rearmTracker.currentOutput())
    }

    @Test
    fun strictConfigLoaderRejectsUnknownDuplicateMissingNonfiniteAndOutOfBoundsValues() {
        val canonical = String(PlayerTrackerConfigJson.encodeCandidate(fixture.config), StandardCharsets.UTF_8)
        val unknown = canonical.dropLast(1) + ",\"unexpected\":1}"
        val duplicate = canonical.replaceFirst(
            "\"configId\":\"fixture-two-player-v1\"",
            "\"configId\":\"fixture-two-player-v1\",\"configId\":\"duplicate\"",
        )
        val missing = canonical.replace("\"lanePenaltyWeight\":0.0,", "")
        val nonfinite = canonical.replace("\"absoluteAssignmentGate\":5.0", "\"absoluteAssignmentGate\":1e9999")
        val zeroIouThreshold = canonical.replace(
            "\"overlapIouPauseThreshold\":1.0",
            "\"overlapIouPauseThreshold\":0.0",
        )
        val zeroProximityThreshold = canonical.replace(
            "\"proximityBodyScalePauseThreshold\":0.01",
            "\"proximityBodyScalePauseThreshold\":0.0",
        )
        val nonIntegralSchema = canonical.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")
        val promotedWithoutExternalGate = canonical.replace(
            "\"reviewStatus\":\"CANDIDATE_UNVERIFIED\"",
            "\"reviewStatus\":\"RELEASE_APPROVED\"",
        )

        assertEquals(PlayerTrackerConfigViolation.UNKNOWN_FIELD, configFailure(unknown).violation)
        assertEquals(PlayerTrackerConfigViolation.DUPLICATE_FIELD, configFailure(duplicate).violation)
        assertEquals(PlayerTrackerConfigViolation.MISSING_FIELD, configFailure(missing).violation)
        assertEquals(PlayerTrackerConfigViolation.NON_FINITE_NUMBER, configFailure(nonfinite).violation)
        assertEquals(
            PlayerTrackerConfigViolation.INVALID_CONFIG_VALUE,
            configFailure(zeroIouThreshold).violation,
        )
        assertEquals(
            PlayerTrackerConfigViolation.INVALID_CONFIG_VALUE,
            configFailure(zeroProximityThreshold).violation,
        )
        assertEquals(PlayerTrackerConfigViolation.WRONG_TYPE, configFailure(nonIntegralSchema).violation)
        assertEquals(
            PlayerTrackerConfigViolation.REVIEW_STATUS_NOT_ALLOWED,
            configFailure(promotedWithoutExternalGate).violation,
        )
    }

    @Test
    fun oversizedConfigIsRejectedBeforeSnapshotDecodeOrHashWork() {
        val oversizedInvalidUtf8 = ByteArray(32 * 1024 + 1) { 0xff.toByte() }

        val failure = PlayerTrackerConfigJson.load(oversizedInvalidUtf8) as
            PlayerTrackerConfigLoadResult.Failure

        assertEquals(PlayerTrackerConfigViolation.DOCUMENT_TOO_LARGE, failure.violation)
    }

    @Test
    fun concurrentCallerMutationCannotSplitDecodedConfigFromHashedSourceSnapshot() {
        val firstBytes = PlayerTrackerConfigJson.encodeCandidate(
            fixture.config.copy(configId = "race-a-${"a".repeat(24_000)}"),
        )
        val secondBytes = PlayerTrackerConfigJson.encodeCandidate(
            fixture.config.copy(configId = "race-b-${"b".repeat(24_000)}"),
        )
        assertEquals(firstBytes.size, secondBytes.size)
        val shared = firstBytes.copyOf()
        val running = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val mutator = thread(start = true, isDaemon = true, name = "tracker-config-mutator") {
            started.countDown()
            while (running.get()) {
                System.arraycopy(firstBytes, 0, shared, 0, shared.size)
                System.arraycopy(secondBytes, 0, shared, 0, shared.size)
            }
        }

        started.await()
        try {
            repeat(64) {
                val loaded = (PlayerTrackerConfigJson.load(shared) as PlayerTrackerConfigLoadResult.Success).loaded
                val decodedCanonicalBytes = PlayerTrackerConfigJson.encodeCandidate(loaded.config)
                assertEquals(sha256(decodedCanonicalBytes), loaded.sourceSha256Hex)
            }
        } finally {
            running.set(false)
            mutator.join(5_000)
        }
        assertFalse(mutator.isAlive)
    }

    @Test
    fun timestampAndCalibrationRollbackAreRejectedWithoutStateMutation() {
        val tracker = fixture.tracker()
        val initialFrame = fixture.frame(
            100,
            fixture.observation(1, 0.25),
            fixture.observation(2, 0.75),
            calibrationRevision = 2,
        )
        val initialized = tracker.initialize(
            initialFrame,
            fixture.binding(initialFrame, 1, 2),
        ).accepted()

        val sameTime = tracker.processFrame(
            fixture.frame(
                100,
                fixture.observation(3, 0.25),
                fixture.observation(4, 0.75),
                calibrationRevision = 2,
            ),
        ).rejected()
        assertEquals(PlayerTrackerViolation.TIMESTAMP_NOT_STRICTLY_INCREASING, sameTime.violation)
        assertSame(initialized, sameTime.lastAcceptedOutput)

        val revisionRollback = tracker.processFrame(
            fixture.frame(
                200,
                fixture.observation(3, 0.25),
                fixture.observation(4, 0.75),
                calibrationRevision = 1,
            ),
        ).rejected()
        assertEquals(PlayerTrackerViolation.CALIBRATION_REVISION_ROLLBACK, revisionRollback.violation)
        assertSame(initialized, revisionRollback.lastAcceptedOutput)
    }

    @Test
    fun frameSequenceReplayRewindAndSessionChangeAreRejectedWithoutStateMutation() {
        val tracker = fixture.tracker()
        val initialFrame = fixture.frame(
            0,
            fixture.observation(1, 0.25),
            fixture.observation(2, 0.75),
            frameToken = PlayerObservationFrameToken("sequence-session", 1L),
        )
        tracker.initialize(initialFrame, fixture.binding(initialFrame, 1, 2)).accepted()
        tracker.processFrame(
            fixture.frame(
                400,
                fixture.observation(41, 0.25),
                fixture.observation(42, 0.75),
                frameToken = PlayerObservationFrameToken("sequence-session", 2L),
            ),
        ).accepted()
        val accepted = tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.25),
                fixture.observation(52, 0.75),
                frameToken = PlayerObservationFrameToken("sequence-session", 3L),
            ),
        ).accepted()

        val sameSequenceDifferentFrame = tracker.processFrame(
            fixture.frame(
                600,
                fixture.observation(61, 0.25),
                fixture.observation(62, 0.75),
                frameToken = PlayerObservationFrameToken("sequence-session", 3L),
            ),
        ).rejected()
        val rewind = tracker.processFrame(
            fixture.frame(
                700,
                fixture.observation(71, 0.25),
                fixture.observation(72, 0.75),
                frameToken = PlayerObservationFrameToken("sequence-session", 2L),
            ),
        ).rejected()
        val changedSession = tracker.processFrame(
            fixture.frame(
                800,
                fixture.observation(81, 0.25),
                fixture.observation(82, 0.75),
                frameToken = PlayerObservationFrameToken("other-session", 4L),
            ),
        ).rejected()

        listOf(sameSequenceDifferentFrame, rewind, changedSession).forEach { rejected ->
            assertEquals(PlayerTrackerViolation.FRAME_TOKEN_REPLAY, rejected.violation)
            assertSame(accepted, rejected.lastAcceptedOutput)
        }
        assertSame(accepted, tracker.currentOutput())
    }

    @Test
    fun calibrationAdvanceDetachesTracksUntilExplicitRearm() {
        val tracker = fixture.initializedActiveTracker()

        val changed = tracker.processFrame(
            fixture.frame(
                500,
                fixture.observation(51, 0.25),
                fixture.observation(52, 0.75),
                calibrationRevision = 2,
            ),
        ).accepted()

        assertEquals(IdentityPauseReason.CALIBRATION_CHANGED, changed.pauseReason)
        assertEquals(listOf(TrackState.REARM, TrackState.REARM), changed.assignments.map { it.state })
        assertTrue(changed.assignments.all { it.trackId == null })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), changed.transitions.map { it.roleId })

        val rollback = tracker.processFrame(
            fixture.frame(
                600,
                fixture.observation(61, 0.25),
                fixture.observation(62, 0.75),
                calibrationRevision = 1,
            ),
        ).rejected()
        assertEquals(PlayerTrackerViolation.CALIBRATION_REVISION_ROLLBACK, rollback.violation)
        assertSame(changed, rollback.lastAcceptedOutput)
    }

    @Test
    fun frameInputIsBoundedAndInvalidInputDoesNotAdvanceSequences() {
        val tracker = fixture.tracker()
        val invalidFrame = fixture.frame(
            0,
            fixture.observation(1, 0.20),
            fixture.observation(2, 0.50),
            fixture.observation(3, 0.80),
        )
        val invalid = tracker.initialize(
            invalidFrame,
            fixture.binding(invalidFrame, 1, 2),
        ).rejected()
        assertEquals(PlayerTrackerViolation.INVALID_FRAME, invalid.violation)
        assertNull(invalid.lastAcceptedOutput)

        val validFrame = fixture.frame(0, fixture.observation(1, 0.25), fixture.observation(2, 0.75))
        val valid = tracker.initialize(validFrame, fixture.binding(validFrame, 1, 2)).accepted()
        assertEquals(listOf(1L, 1L), valid.assignments.map { it.roleSequenceNumber })
    }

    @Test
    fun untrustedObservationListIsIteratedOnceBeforePrivateSnapshotValidation() {
        val firstPass = listOf(
            fixture.observation(1, 0.25),
            fixture.observation(2, 0.75),
        )
        val laterPass = listOf(
            fixture.observation(7, 0.25).copy(pelvis = NormalizedPoint(Double.NaN, 0.58)),
        )
        val iteratorCalls = AtomicInteger(0)
        val adversarial = object : AbstractList<PlayerTrackObservation>() {
            override val size: Int
                get() = error("caller size must not be read before snapshot")

            override fun get(index: Int): PlayerTrackObservation = error("indexed access is forbidden")

            override fun iterator(): Iterator<PlayerTrackObservation> =
                if (iteratorCalls.incrementAndGet() == 1) firstPass.iterator() else laterPass.iterator()
        }
        val template = fixture.frame(0)
        val frame = template.copy(observations = adversarial)
        val output = fixture.tracker().initialize(frame, fixture.binding(frame, 1, 2)).accepted()

        assertEquals(1, iteratorCalls.get())
        assertEquals(listOf(1, 2), output.assignments.map { it.observationId })
    }

    @Test
    fun misreportedListSizeCannotBypassThreeReadBound() {
        val values = listOf(
            fixture.observation(1, 0.20),
            fixture.observation(2, 0.50),
            fixture.observation(3, 0.80),
        )
        val iteratorCalls = AtomicInteger(0)
        val nextCalls = AtomicInteger(0)
        val adversarial = object : AbstractList<PlayerTrackObservation>() {
            override val size: Int = 2

            override fun get(index: Int): PlayerTrackObservation = error("indexed access is forbidden")

            override fun iterator(): Iterator<PlayerTrackObservation> {
                iteratorCalls.incrementAndGet()
                val delegate = values.iterator()
                return object : Iterator<PlayerTrackObservation> {
                    override fun hasNext(): Boolean = delegate.hasNext()

                    override fun next(): PlayerTrackObservation {
                        nextCalls.incrementAndGet()
                        return delegate.next()
                    }
                }
            }
        }
        val template = fixture.frame(0)
        val frame = template.copy(observations = adversarial)
        val rejected = fixture.tracker().initialize(frame, fixture.binding(frame, 1, 2)).rejected()

        assertEquals(PlayerTrackerViolation.INVALID_FRAME, rejected.violation)
        assertNull(rejected.lastAcceptedOutput)
        assertEquals(1, iteratorCalls.get())
        assertEquals(3, nextCalls.get())
    }

    @Test
    fun concurrentObservationMutationDuringSnapshotFailsClosedWithoutTracks() {
        val backing = mutableListOf(
            fixture.observation(1, 0.25),
            fixture.observation(2, 0.75),
        )
        val firstCaptured = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val iteratorCalls = AtomicInteger(0)
        val adversarial = object : AbstractList<PlayerTrackObservation>() {
            override val size: Int
                get() = backing.size

            override fun get(index: Int): PlayerTrackObservation = backing[index]

            override fun iterator(): Iterator<PlayerTrackObservation> {
                iteratorCalls.incrementAndGet()
                return object : Iterator<PlayerTrackObservation> {
                    private var index = 0

                    override fun hasNext(): Boolean = index < backing.size

                    override fun next(): PlayerTrackObservation {
                        val value = backing[index++]
                        if (index == 1) {
                            firstCaptured.countDown()
                            if (!releaseSnapshot.await(5, TimeUnit.SECONDS)) {
                                throw IllegalStateException("test mutation was not released")
                            }
                        }
                        return value
                    }
                }
            }
        }
        val template = fixture.frame(0)
        val frame = template.copy(observations = adversarial)
        val tracker = fixture.tracker()
        val result = AtomicReference<PlayerTrackerResult>()
        val worker = thread(start = true, name = "observation-snapshot-test") {
            result.set(tracker.initialize(frame, fixture.binding(frame, 1, 2)))
        }

        assertTrue(firstCaptured.await(5, TimeUnit.SECONDS))
        backing[1] = backing[1].copy(pelvis = NormalizedPoint(Double.NaN, 0.58))
        releaseSnapshot.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        val rejected = result.get() as PlayerTrackerResult.Rejected
        assertEquals(PlayerTrackerViolation.INVALID_FRAME, rejected.violation)
        assertNull(rejected.lastAcceptedOutput)
        assertNull(tracker.currentOutput())
        assertEquals(1, iteratorCalls.get())
    }

    @Test
    fun zeroOneAndTwoObservationsAreAcceptedButThreeAreRejected() {
        val tracker = fixture.tracker()
        val initialFrame = fixture.frame(0, fixture.observation(1, 0.25), fixture.observation(2, 0.75))
        tracker.initialize(
            initialFrame,
            fixture.binding(initialFrame, 1, 2),
        ).accepted()

        assertTrue(tracker.processFrame(fixture.frame(100)) is PlayerTrackerResult.Accepted)
        assertTrue(
            tracker.processFrame(
                fixture.frame(200, fixture.observation(21, 0.25)),
            ) is PlayerTrackerResult.Accepted,
        )
        assertTrue(
            tracker.processFrame(
                fixture.frame(
                    300,
                    fixture.observation(31, 0.25),
                    fixture.observation(32, 0.75),
                ),
            ) is PlayerTrackerResult.Accepted,
        )
        val three = tracker.processFrame(
            fixture.frame(
                400,
                fixture.observation(41, 0.20),
                fixture.observation(42, 0.50),
                fixture.observation(43, 0.80),
            ),
        ).rejected()
        assertEquals(PlayerTrackerViolation.INVALID_FRAME, three.violation)
    }

    @Test
    fun nanInfinityAndDuplicateObservationIdsAreRejectedWithoutInitialization() {
        val tracker = fixture.tracker()
        val nan = fixture.observation(1, 0.25).copy(
            pelvis = NormalizedPoint(Double.NaN, 0.58),
        )
        val infinity = fixture.observation(1, 0.25).copy(
            bodyScale = Double.POSITIVE_INFINITY,
        )
        val nanFrame = fixture.frame(0, nan, fixture.observation(2, 0.75))
        val infinityFrame = fixture.frame(0, infinity, fixture.observation(2, 0.75))
        val duplicateFrame = fixture.frame(
            0,
            fixture.observation(7, 0.25),
            fixture.observation(7, 0.75),
        )

        assertEquals(
            PlayerTrackerViolation.INVALID_FRAME,
            tracker.initialize(
                nanFrame,
                fixture.binding(nanFrame, 1, 2),
            ).rejected().violation,
        )
        assertEquals(
            PlayerTrackerViolation.INVALID_FRAME,
            tracker.initialize(
                infinityFrame,
                fixture.binding(infinityFrame, 1, 2),
            ).rejected().violation,
        )
        assertEquals(
            PlayerTrackerViolation.INVALID_FRAME,
            tracker.initialize(
                duplicateFrame,
                fixture.binding(duplicateFrame, 7, 7),
            ).rejected().violation,
        )
        assertNull(tracker.currentOutput())
    }

    @Test
    fun publicObservationContractContainsKinematicsNotAppearanceIdentity() {
        val fieldNames = PlayerTrackObservation::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(
            setOf(
                "observationId",
                "pelvis",
                "shoulderCenter",
                "bodyScale",
                "facingDirection",
                "bounds",
                "confidence",
                "neutral",
                "definitiveFrameExit",
            ),
            fieldNames,
        )
        assertTrue(fieldNames.none { it.contains("face", ignoreCase = true) })
        assertTrue(fieldNames.none { it.contains("appearance", ignoreCase = true) })
    }

    @Test
    fun assignmentTimelineGoldenIsDeterministicWithoutClaimingMotionEventIntegration() {
        fun replay(): List<PlayerTrackerOutput> {
            val tracker = fixture.tracker()
            val initialFrame = fixture.frame(
                0,
                fixture.observation(1, 0.20),
                fixture.observation(2, 0.80),
            )
            return listOf(
                tracker.initialize(
                    initialFrame,
                    fixture.binding(initialFrame, 1, 2),
                ).accepted(),
                tracker.processFrame(
                    fixture.frame(
                        400,
                        fixture.observation(41, 0.40),
                        fixture.observation(42, 0.60),
                    ),
                ).accepted(),
                tracker.processFrame(
                    fixture.frame(
                        700,
                        fixture.observation(71, 0.55),
                        fixture.observation(72, 0.45),
                    ),
                ).accepted(),
                tracker.processFrame(
                    fixture.frame(
                        900,
                        fixture.observation(91, 0.65),
                        fixture.observation(92, 0.35),
                    ),
                ).accepted(),
                tracker.processFrame(
                    fixture.frame(1_000, fixture.observation(101, 0.65)),
                ).accepted(),
            )
        }

        val first = replay()
        assertEquals(first, replay())
        assertEquals(
            listOf(
                "0:P1=1:TENTATIVE,P2=2:TENTATIVE:TENTATIVE_TRACKS",
                "400:P1=41:ACTIVE,P2=42:ACTIVE:NONE",
                "700:P1=-:ACTIVE,P2=-:ACTIVE:CROSSING_HYSTERESIS",
                "900:P1=91:ACTIVE,P2=92:ACTIVE:NONE",
                "1000:P1=101:ACTIVE,P2=-:OCCLUDED:INSUFFICIENT_OBSERVATIONS",
            ),
            first.map { output ->
                val assignments = output.assignments.joinToString(separator = ",") { assignment ->
                    "${assignment.roleId}=${assignment.observationId ?: "-"}:${assignment.state}"
                }
                "${output.timestampNanos / 1_000_000}:$assignments:${output.pauseReason}"
            },
        )
    }

    private fun PlayerTrackerResult.accepted(): PlayerTrackerOutput =
        (this as PlayerTrackerResult.Accepted).output

    private fun PlayerTrackerResult.rejected(): PlayerTrackerResult.Rejected =
        this as PlayerTrackerResult.Rejected

    private fun RearmRequestResult.accepted(): PlayerTrackerOutput =
        (this as RearmRequestResult.Accepted).output

    private fun RearmRequestResult.rejected(): RearmRequestResult.Rejected =
        this as RearmRequestResult.Rejected

    private fun configFailure(source: String): PlayerTrackerConfigLoadResult.Failure =
        PlayerTrackerConfigJson.load(source.toByteArray(StandardCharsets.UTF_8)) as
            PlayerTrackerConfigLoadResult.Failure

    private fun PlayerTrackerOutput.p1(): RoleTrackAssignment = assignments[0]

    private fun PlayerTrackerOutput.p2(): RoleTrackAssignment = assignments[1]

    private companion object {
        val fixture = PlayerTrackerTestFixtures

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
