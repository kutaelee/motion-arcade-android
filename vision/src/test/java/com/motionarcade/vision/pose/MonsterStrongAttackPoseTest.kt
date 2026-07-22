package com.motionarcade.vision.pose

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionGestureEngine
import com.motionarcade.core.motion.MotionSampleResult
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterStrongAttackPoseTest {
    private val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 1)

    @Test
    fun strongAttackRequiresFullFiveHundredMillisecondOverheadHoldAndBothWristDrop() {
        val premature = extractor()
        premature.process(0L, overheadPose())
        assertEquals(0f, strong(premature.process(499_000_000L, droppedPose())))

        val complete = extractor()
        complete.process(0L, overheadPose())
        assertEquals(0f, strong(complete.process(500_000_000L, overheadPose())))
        assertTrue(strong(complete.process(700_000_000L, droppedPose())) >= 0.82f)

        val oneHand = extractor()
        oneHand.process(0L, overheadPose())
        oneHand.process(500_000_000L, overheadPose())
        assertEquals(0f, strong(oneHand.process(700_000_000L, oneWristDroppedPose())))
    }

    @Test
    fun eightHundredMillisecondTeamPowerHoldsCannotBecomeStrongAttackOnRelease() {
        listOf(chargePose(), ultimatePose()).forEach { heldPose ->
            val extractor = extractor()
            extractor.process(0L, heldPose)
            extractor.process(400_000_000L, heldPose)
            extractor.process(800_000_000L, heldPose)
            extractor.process(1_200_000_000L, heldPose)
            assertEquals(0f, strong(extractor.process(1_400_000_000L, droppedPose())))
            extractor.process(1_500_000_000L, overheadPose())
            extractor.process(2_000_000_000L, overheadPose())
            assertTrue(strong(extractor.process(2_200_000_000L, droppedPose())) >= 0.82f)
        }
    }

    @Test
    fun strongDescentMustBeginBeforeTeamPowerCutoff() {
        val beforeCutoff = extractor()
        beforeCutoff.process(0L, overheadPose())
        beforeCutoff.process(500_000_000L, overheadPose())
        assertTrue(strong(beforeCutoff.process(799_999_999L, droppedPose())) >= 0.82f)

        val atCutoff = extractor()
        atCutoff.process(0L, overheadPose())
        atCutoff.process(500_000_000L, overheadPose())
        assertEquals(0f, strong(atCutoff.process(800_000_000L, droppedPose())))

        val descentStartedBeforeCutoff = extractor()
        descentStartedBeforeCutoff.process(0L, overheadPose())
        descentStartedBeforeCutoff.process(500_000_000L, overheadPose())
        val partial = strong(descentStartedBeforeCutoff.process(750_000_000L, partialDropPose()))
        assertTrue(partial > 0f && partial < 1f)
        assertTrue(strong(descentStartedBeforeCutoff.process(900_000_000L, droppedPose())) >= 1f)
    }

    @Test
    fun continuityResetClearsArmedStrongAttack() {
        val extractor = extractor()
        extractor.process(0L, overheadPose())
        extractor.process(500_000_000L, overheadPose())
        extractor.reset()

        assertEquals(0f, strong(extractor.process(700_000_000L, droppedPose())))
    }

    @Test
    fun chargeUltimateBelowHeadAndBodyTranslationCannotArmStrongAttack() {
        listOf(chargePose(), ultimatePose(), belowHeadPose()).forEach { invalidReadyPose ->
            val extractor = extractor()
            extractor.process(0L, invalidReadyPose)
            extractor.process(500_000_000L, invalidReadyPose)
            assertEquals(0f, strong(extractor.process(700_000_000L, droppedPose())))
        }

        val translated = extractor()
        translated.process(0L, overheadPose())
        translated.process(500_000_000L, overheadPose())
        assertEquals(0f, strong(translated.process(700_000_000L, translatedOverheadPose())))
    }

    @Test
    fun strongDescentSuppressesJabAndBoxingStillUsesHookRecognizer() {
        val monster = extractor()
        monster.process(0L, overheadPose())
        monster.process(500_000_000L, overheadPose())
        val descent = requireNotNull(monster.process(700_000_000L, droppedPose()))
        assertEquals(0f, requireNotNull(descent[MotionType.PUNCH_JAB]).activation)
        assertTrue(requireNotNull(descent[MotionType.PUNCH_HOOK]).activation >= 1f)

        val boxing = DualPlayerCombatMotionBridge.CombatPoseSignalExtractor(
            config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 1),
            playerId = PlayerId.P1,
        )
        val hook = requireNotNull(boxing.process(0L, boxingHookPose()))
        assertEquals(DualPlayerCombatProfile.BOXING, DualPlayerCombatMotionConfigs.boxing(1).profile)
        assertTrue(requireNotNull(hook[MotionType.PUNCH_HOOK]).activation >= 0.82f)
    }

    @Test
    fun initialStrongRaiseSuppressesJabAndMissingHeadOnlyDisablesStrong() {
        val raising = extractor()
        raising.process(0L, droppedPose())
        val firstOverhead = requireNotNull(raising.process(100_000_000L, overheadPose()))
        assertEquals(0f, requireNotNull(firstOverhead[MotionType.PUNCH_JAB]).activation)
        assertEquals(0f, requireNotNull(firstOverhead[MotionType.PUNCH_HOOK]).activation)

        val headless = extractor()
        val charge = requireNotNull(headless.process(0L, withoutReliableHead(chargePose())))
        assertTrue(requireNotNull(charge[MotionType.MONSTER_MAGIC_CHARGE]).activation >= 0.82f)
        assertEquals(0f, requireNotNull(charge[MotionType.PUNCH_HOOK]).activation)
    }

    @Test
    fun poseSequenceEmitsOnlyOneStrongSemanticEventThroughGestureEngine() {
        val extractor = extractor()
        val engine = MotionGestureEngine(
            sessionId = "monster-strong-pose-engine",
            definitions = config.gestureDefinitions,
            activeCalibrationRevision = config.calibrationRevision,
        )
        val emitted = mutableListOf<MotionType>()

        listOf(
            0L to overheadPose(),
            500_000_000L to overheadPose(),
            700_000_000L to droppedPose(),
        ).forEach { (timestampNs, pose) ->
            val signals = requireNotNull(extractor.process(timestampNs, pose))
            val frame = config.gestureDefinitions.map { definition ->
                val signal = requireNotNull(signals[definition.type])
                MotionSignalSample(
                    playerId = PlayerId.P1,
                    type = definition.type,
                    timestampNs = timestampNs,
                    activation = signal.activation,
                    quality = signal.activation,
                    confidence = signal.confidence,
                    calibrationRevision = config.calibrationRevision,
                    source = InputSource.MOTION,
                )
            }
            engine.processFrame(frame).forEach { result ->
                if (result is MotionSampleResult.Emitted) emitted += result.event.type
            }
        }

        assertEquals(listOf(MotionType.PUNCH_HOOK), emitted)
    }

    private fun extractor() = DualPlayerCombatMotionBridge.CombatPoseSignalExtractor(
        config = config,
        playerId = PlayerId.P1,
    )

    private fun strong(signals: Map<MotionType, DualPlayerCombatMotionBridge.CombatPoseSignal>?): Float =
        requireNotNull(requireNotNull(signals)[MotionType.PUNCH_HOOK]).activation

    private fun overheadPose(): LivePoseObservation = pose(leftWristY = 0.15f, rightWristY = 0.15f)

    private fun droppedPose(): LivePoseObservation = pose(leftWristY = 0.34f, rightWristY = 0.34f)

    private fun oneWristDroppedPose(): LivePoseObservation = pose(leftWristY = 0.34f, rightWristY = 0.15f)

    private fun partialDropPose(): LivePoseObservation = pose(leftWristY = 0.28f, rightWristY = 0.28f)

    private fun belowHeadPose(): LivePoseObservation = pose(leftWristY = 0.20f, rightWristY = 0.20f)

    private fun translatedOverheadPose(): LivePoseObservation = pose(
        leftWristY = 0.35f,
        rightWristY = 0.35f,
        bodyOffsetY = 0.20f,
    )

    private fun boxingHookPose(): LivePoseObservation = pose(
        leftWristY = 0.40f,
        rightWristY = 0.52f,
        leftWristX = 0.55f,
        leftElbowY = 0.40f,
    )

    private fun withoutReliableHead(pose: LivePoseObservation): LivePoseObservation {
        val points = pose.landmarks.toMutableList()
        val head = points[0]
        points[0] = LivePoseLandmark(head.x, head.y, head.z, 0.10f, 0.10f)
        return LivePoseObservation(points)
    }

    private fun chargePose(): LivePoseObservation = pose(
        leftWristY = 0.15f,
        rightWristY = 0.15f,
        leftWristX = 0.36f,
        rightWristX = 0.64f,
    )

    private fun ultimatePose(): LivePoseObservation = pose(
        leftWristY = 0.15f,
        rightWristY = 0.15f,
        leftWristX = 0.48f,
        rightWristX = 0.52f,
    )

    private fun pose(
        leftWristY: Float,
        rightWristY: Float,
        leftWristX: Float = 0.45f,
        rightWristX: Float = 0.55f,
        bodyOffsetY: Float = 0f,
        leftElbowY: Float = 0.23f + bodyOffsetY,
    ): LivePoseObservation {
        val points = MutableList(LIVE_POSE_LANDMARK_COUNT) {
            LivePoseLandmark(0.50f, 0.50f, 0f, 0.95f, 0.95f)
        }
        fun put(index: Int, x: Float, y: Float) {
            points[index] = LivePoseLandmark(x, y, 0f, 0.95f, 0.95f)
        }
        put(0, 0.50f, 0.18f + bodyOffsetY)
        put(11, 0.43f, 0.30f + bodyOffsetY)
        put(12, 0.57f, 0.30f + bodyOffsetY)
        put(13, 0.43f, leftElbowY)
        put(14, 0.57f, 0.23f + bodyOffsetY)
        put(15, leftWristX, leftWristY)
        put(16, rightWristX, rightWristY)
        put(23, 0.44f, 0.62f + bodyOffsetY)
        put(24, 0.56f, 0.62f + bodyOffsetY)
        return LivePoseObservation(points)
    }
}
