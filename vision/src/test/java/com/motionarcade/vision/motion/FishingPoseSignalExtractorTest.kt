package com.motionarcade.vision.pose

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingPoseSignalExtractorTest {
    private val config = FishingPoseSignalConfig(
        configId = "fishing-pose-candidate-test-v1",
        schemaVersion = 1,
        minimumLandmarkConfidence = 0.6f,
        minimumShoulderWidth = 0.08f,
        minimumTorsoLength = 0.12f,
        readyMaximumWristDistanceShoulderWidths = 1.0f,
        castMinimumWristTravelShoulderWidths = 0.45f,
        castMinimumElbowExtensionDelta = 0.15f,
        castWindowNs = 700_000_000L,
        hookMinimumRiseTorsoLengths = 0.25f,
        hookWindowNs = 350_000_000L,
        reelMinimumRadiusShoulderWidths = 0.25f,
        reelMinimumAccumulatedRadians = (4.0 * PI / 3.0).toFloat(),
        reelMinimumElbowAngleRangeRadians = 0.2f,
        reelMaximumStepRadians = 1.3f,
        reelMaximumFrameGapNs = 150_000_000L,
        tensionMinimumLeanTorsoLengths = 0.15f,
        netMinimumRiseTorsoLengths = 0.25f,
        netWindowNs = 800_000_000L,
        maximumHistorySamples = 64,
    )

    @Test
    fun readyRequiresBothWristsNearChest() {
        val extractor = FishingPoseSignalExtractor(config)

        val centered = extractor.accepted(pose(0L))
        val oneFar = extractor.accepted(
            pose(100_000_000L, rightWrist = Pair(0.90f, 0.90f)),
        )

        assertTrue(centered.activation(FishingGestureCandidate.READY) >= 0.7f)
        assertEquals(0f, oneFar.activation(FishingGestureCandidate.READY), 0.0001f)
    }

    @Test
    fun castUsesBodyNormalizedTravelAndElbowExtensionWithinSevenHundredMilliseconds() {
        val extractor = FishingPoseSignalExtractor(config)
        extractor.accepted(
            pose(
                0L,
                rightElbow = Pair(0.64f, 0.47f),
                rightWrist = Pair(0.61f, 0.54f),
            ),
        )

        val within = extractor.accepted(
            pose(
                500_000_000L,
                rightElbow = Pair(0.70f, 0.40f),
                rightWrist = Pair(0.82f, 0.40f),
            ),
        )
        val outside = extractor.accepted(
            pose(
                1_300_000_000L,
                rightElbow = Pair(0.70f, 0.40f),
                rightWrist = Pair(0.82f, 0.40f),
            ),
        )

        assertEquals(1f, within.activation(FishingGestureCandidate.CAST), 0.0001f)
        assertEquals(0f, outside.activation(FishingGestureCandidate.CAST), 0.0001f)
    }

    @Test
    fun hookUsesWristRiseWithinThreeHundredFiftyMilliseconds() {
        val extractor = FishingPoseSignalExtractor(config)
        extractor.accepted(
            pose(0L, leftWrist = Pair(0.45f, 0.68f), rightWrist = Pair(0.55f, 0.68f)),
        )

        val within = extractor.accepted(
            pose(
                300_000_000L,
                leftWrist = Pair(0.45f, 0.56f),
                rightWrist = Pair(0.55f, 0.56f),
            ),
        )

        assertEquals(1f, within.activation(FishingGestureCandidate.HOOK), 0.0001f)

        val lateExtractor = FishingPoseSignalExtractor(config)
        lateExtractor.accepted(
            pose(0L, leftWrist = Pair(0.45f, 0.68f), rightWrist = Pair(0.55f, 0.68f)),
        )
        val late = lateExtractor.accepted(
            pose(
                351_000_000L,
                leftWrist = Pair(0.45f, 0.56f),
                rightWrist = Pair(0.55f, 0.56f),
            ),
        )
        assertEquals(0f, late.activation(FishingGestureCandidate.HOOK), 0.0001f)
    }

    @Test
    fun tensionUsesTorsoLeanAndIgnoresWholeBodyTranslation() {
        val extractor = FishingPoseSignalExtractor(config)
        val translated = extractor.accepted(
            pose(
                0L,
                leftShoulder = Pair(0.50f, 0.40f),
                rightShoulder = Pair(0.70f, 0.40f),
                leftHip = Pair(0.53f, 0.70f),
                rightHip = Pair(0.67f, 0.70f),
                leftElbow = Pair(0.50f, 0.50f),
                rightElbow = Pair(0.70f, 0.50f),
                leftWrist = Pair(0.55f, 0.55f),
                rightWrist = Pair(0.65f, 0.55f),
            ),
        )
        val leaning = extractor.accepted(
            pose(
                100_000_000L,
                leftShoulder = Pair(0.35f, 0.40f),
                rightShoulder = Pair(0.55f, 0.40f),
            ),
        )

        assertEquals(0f, translated.activation(FishingGestureCandidate.TENSION_LEFT), 0.0001f)
        assertEquals(0f, translated.activation(FishingGestureCandidate.TENSION_RIGHT), 0.0001f)
        assertTrue(leaning.activation(FishingGestureCandidate.TENSION_LEFT) >= 1f)
        assertEquals(0f, leaning.activation(FishingGestureCandidate.TENSION_RIGHT), 0.0001f)
    }

    @Test
    fun wholeBodyTranslationDoesNotBecomeCastHookOrNet() {
        val extractor = FishingPoseSignalExtractor(config)
        extractor.accepted(pose(0L))

        val translated = extractor.accepted(
            pose(
                timestampNs = 200_000_000L,
                leftShoulder = Pair(0.55f, 0.30f),
                rightShoulder = Pair(0.75f, 0.30f),
                leftElbow = Pair(0.55f, 0.40f),
                rightElbow = Pair(0.75f, 0.40f),
                leftWrist = Pair(0.60f, 0.45f),
                rightWrist = Pair(0.70f, 0.45f),
                leftHip = Pair(0.58f, 0.60f),
                rightHip = Pair(0.72f, 0.60f),
            ),
        )

        assertEquals(0f, translated.activation(FishingGestureCandidate.CAST), 0.0001f)
        assertEquals(0f, translated.activation(FishingGestureCandidate.HOOK), 0.0001f)
        assertEquals(0f, translated.activation(FishingGestureCandidate.NET), 0.0001f)
    }

    @Test
    fun uniformBodyScaleChangeDoesNotBecomeCastHookOrNet() {
        val extractor = FishingPoseSignalExtractor(config)
        extractor.accepted(pose(0L))

        val scaled = extractor.accepted(pose(200_000_000L).scaled(0.5f))

        assertEquals(0f, scaled.activation(FishingGestureCandidate.CAST), 0.0001f)
        assertEquals(0f, scaled.activation(FishingGestureCandidate.HOOK), 0.0001f)
        assertEquals(0f, scaled.activation(FishingGestureCandidate.NET), 0.0001f)
    }

    @Test
    fun trueTwoHandRiseIsInvariantToBodyScale() {
        fun activationAt(scale: Float): Pair<Float, Float> {
            val extractor = FishingPoseSignalExtractor(config)
            extractor.accepted(
                pose(
                    0L,
                    leftWrist = Pair(0.45f, 0.76f),
                    rightWrist = Pair(0.55f, 0.76f),
                ).scaled(scale),
            )
            val raised = extractor.accepted(
                pose(
                    200_000_000L,
                    leftWrist = Pair(0.45f, 0.56f),
                    rightWrist = Pair(0.55f, 0.56f),
                ).scaled(scale),
            )
            return Pair(
                raised.activation(FishingGestureCandidate.HOOK),
                raised.activation(FishingGestureCandidate.NET),
            )
        }

        val fullSize = activationAt(1f)
        val halfSize = activationAt(0.5f)

        assertEquals(fullSize.first, halfSize.first, 0.0001f)
        assertEquals(fullSize.second, halfSize.second, 0.0001f)
        assertEquals(1f, halfSize.first, 0.0001f)
        assertEquals(1f, halfSize.second, 0.0001f)
    }

    @Test
    fun netRequiresBothHandsToRise() {
        val extractor = FishingPoseSignalExtractor(config)
        extractor.accepted(
            pose(0L, leftWrist = Pair(0.45f, 0.76f), rightWrist = Pair(0.55f, 0.76f)),
        )
        val both = extractor.accepted(
            pose(
                400_000_000L,
                leftWrist = Pair(0.45f, 0.56f),
                rightWrist = Pair(0.55f, 0.56f),
            ),
        )
        assertEquals(1f, both.activation(FishingGestureCandidate.NET), 0.0001f)

        val oneHandExtractor = FishingPoseSignalExtractor(config)
        oneHandExtractor.accepted(
            pose(0L, leftWrist = Pair(0.45f, 0.76f), rightWrist = Pair(0.55f, 0.76f)),
        )
        val one = oneHandExtractor.accepted(
            pose(
                400_000_000L,
                leftWrist = Pair(0.45f, 0.56f),
                rightWrist = Pair(0.55f, 0.76f),
            ),
        )
        assertEquals(0f, one.activation(FishingGestureCandidate.NET), 0.0001f)
    }

    @Test
    fun reelNeedsConsistentCircularPhaseAndElbowRangeThenPulsesOnce() {
        val extractor = FishingPoseSignalExtractor(config)
        val activations = (0..6).map { step ->
            val angle = Math.toRadians(step * 45.0)
            val wrist = Pair(
                (0.50 + cos(angle) * 0.13).toFloat(),
                (0.55 + sin(angle) * 0.13).toFloat(),
            )
            val elbow = if (step % 2 == 0) Pair(0.64f, 0.48f) else Pair(0.72f, 0.52f)
            extractor.accepted(
                pose(
                    timestampNs = step * 50_000_000L,
                    rightElbow = elbow,
                    rightWrist = wrist,
                    leftWrist = Pair(0.49f, 0.55f),
                ),
            ).activation(FishingGestureCandidate.REEL_CYCLE)
        }
        val afterPulse = extractor.accepted(
            pose(
                timestampNs = 350_000_000L,
                rightElbow = Pair(0.72f, 0.52f),
                rightWrist = Pair(0.50f, 0.42f),
                leftWrist = Pair(0.49f, 0.55f),
            ),
        )

        assertTrue(activations.any { it >= 1f })
        assertEquals(0f, afterPulse.activation(FishingGestureCandidate.REEL_CYCLE), 0.0001f)

        val jitter = FishingPoseSignalExtractor(config)
        val jitterActivations = (0..20).map { step ->
            jitter.accepted(
                pose(
                    timestampNs = step * 40_000_000L,
                    rightWrist = Pair(0.62f + (step % 2) * 0.01f, 0.55f),
                ),
            ).activation(FishingGestureCandidate.REEL_CYCLE)
        }
        assertTrue(jitterActivations.all { it < 1f })
    }

    @Test
    fun lowConfidenceAndRevisionChangeBreakTemporalGestures() {
        val lowConfidenceExtractor = FishingPoseSignalExtractor(config)
        lowConfidenceExtractor.accepted(
            pose(0L, leftWrist = Pair(0.45f, 0.68f), rightWrist = Pair(0.55f, 0.68f)),
        )
        val low = lowConfidenceExtractor.accepted(pose(100_000_000L, confidence = 0.2f))
        val afterLow = lowConfidenceExtractor.accepted(
            pose(
                200_000_000L,
                leftWrist = Pair(0.45f, 0.55f),
                rightWrist = Pair(0.55f, 0.55f),
            ),
        )
        assertTrue(low.temporalReset)
        assertTrue(low.signals.all { it.activation == 0f && it.confidence == 0f })
        assertEquals(0f, afterLow.activation(FishingGestureCandidate.HOOK), 0.0001f)

        val revisionExtractor = FishingPoseSignalExtractor(config)
        revisionExtractor.accepted(
            pose(0L, revision = 1, leftWrist = Pair(0.45f, 0.68f)),
        )
        val changed = revisionExtractor.accepted(
            pose(100_000_000L, revision = 2, leftWrist = Pair(0.45f, 0.55f)),
        )
        assertTrue(changed.temporalReset)
        assertEquals(0f, changed.activation(FishingGestureCandidate.HOOK), 0.0001f)
    }

    @Test
    fun rejectedDuplicateTimestampDoesNotMutateHistory() {
        val extractor = FishingPoseSignalExtractor(config)
        extractor.accepted(
            pose(0L, leftWrist = Pair(0.45f, 0.68f), rightWrist = Pair(0.55f, 0.68f)),
        )
        val rejected = extractor.process(
            pose(0L, leftWrist = Pair(0.45f, 0.10f), rightWrist = Pair(0.55f, 0.10f)),
        )
        val valid = extractor.accepted(
            pose(
                200_000_000L,
                leftWrist = Pair(0.45f, 0.55f),
                rightWrist = Pair(0.55f, 0.55f),
            ),
        )

        assertEquals(
            FishingPoseSignalRejection.NON_MONOTONIC_TIMESTAMP,
            (rejected as FishingPoseSignalResult.Rejected).reason,
        )
        assertEquals(1f, valid.activation(FishingGestureCandidate.HOOK), 0.0001f)
    }

    @Test
    fun invalidCoordinatesAreRejectedWithoutChangingClock() {
        val extractor = FishingPoseSignalExtractor(config)
        val valid = pose(100L)
        val invalidLandmarks = valid.landmarks.toMutableList().apply {
            val wrist = this[FishingPoseLandmarkIndex.LEFT_WRIST]
            this[FishingPoseLandmarkIndex.LEFT_WRIST] = FishingPosePoint(
                Float.NaN,
                wrist.y,
                wrist.z,
                wrist.confidence,
            )
        }
        val invalid = FishingPoseSample(valid.timestampNs, valid.calibrationRevision, invalidLandmarks)

        assertEquals(
            FishingPoseSignalRejection.INVALID_FRAME,
            (extractor.process(invalid) as FishingPoseSignalResult.Rejected).reason,
        )
        extractor.accepted(pose(50L))
    }

    private fun FishingPoseSignalExtractor.accepted(
        sample: FishingPoseSample,
    ): FishingPoseSignalResult.Accepted = process(sample) as FishingPoseSignalResult.Accepted

    private fun FishingPoseSignalResult.Accepted.activation(
        candidate: FishingGestureCandidate,
    ): Float = signals.single { it.candidate == candidate }.activation

    @Suppress("LongParameterList")
    private fun pose(
        timestampNs: Long,
        revision: Int = 1,
        confidence: Float = 0.95f,
        leftShoulder: Pair<Float, Float> = Pair(0.40f, 0.40f),
        rightShoulder: Pair<Float, Float> = Pair(0.60f, 0.40f),
        leftElbow: Pair<Float, Float> = Pair(0.40f, 0.50f),
        rightElbow: Pair<Float, Float> = Pair(0.60f, 0.50f),
        leftWrist: Pair<Float, Float> = Pair(0.45f, 0.55f),
        rightWrist: Pair<Float, Float> = Pair(0.55f, 0.55f),
        leftHip: Pair<Float, Float> = Pair(0.43f, 0.70f),
        rightHip: Pair<Float, Float> = Pair(0.57f, 0.70f),
    ): FishingPoseSample {
        val points = MutableList(FishingPoseLandmarkIndex.LANDMARK_COUNT) {
            FishingPosePoint(0.5f, 0.5f, 0f, confidence)
        }
        fun put(index: Int, value: Pair<Float, Float>) {
            points[index] = FishingPosePoint(value.first, value.second, 0f, confidence)
        }
        put(FishingPoseLandmarkIndex.LEFT_SHOULDER, leftShoulder)
        put(FishingPoseLandmarkIndex.RIGHT_SHOULDER, rightShoulder)
        put(FishingPoseLandmarkIndex.LEFT_ELBOW, leftElbow)
        put(FishingPoseLandmarkIndex.RIGHT_ELBOW, rightElbow)
        put(FishingPoseLandmarkIndex.LEFT_WRIST, leftWrist)
        put(FishingPoseLandmarkIndex.RIGHT_WRIST, rightWrist)
        put(FishingPoseLandmarkIndex.LEFT_HIP, leftHip)
        put(FishingPoseLandmarkIndex.RIGHT_HIP, rightHip)
        return FishingPoseSample(timestampNs, revision, points)
    }

    private fun FishingPoseSample.scaled(
        scale: Float,
        centerX: Float = 0.5f,
        centerY: Float = 0.55f,
    ): FishingPoseSample = FishingPoseSample(
        timestampNs,
        calibrationRevision,
        landmarks.map { point ->
            FishingPosePoint(
                centerX + (point.x - centerX) * scale,
                centerY + (point.y - centerY) * scale,
                point.z,
                point.confidence,
            )
        },
    )
}
