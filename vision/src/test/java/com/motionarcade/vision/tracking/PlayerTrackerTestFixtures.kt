package com.motionarcade.vision.tracking

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

internal object PlayerTrackerTestFixtures {
    val configSourceText = """
        {
          "schemaId": "motion-arcade.player-tracker-config.v1",
          "reviewStatus": "CANDIDATE_UNVERIFIED",
          "configId": "fixture-two-player-v1",
          "schemaVersion": 1,
          "pelvisDistanceWeight": 1.0,
          "shoulderDistanceWeight": 0.5,
          "velocityMismatchWeight": 0.8,
          "scaleMismatchWeight": 0.25,
          "directionDiscontinuityWeight": 0.25,
          "lanePenaltyWeight": 0.0,
          "absoluteAssignmentGate": 5.0,
          "assignmentMargin": 0.20,
          "laneToleranceNormalized": 0.10,
          "minimumBodyScale": 0.05,
          "maximumNormalizedSpeedPerSecond": 2.0,
          "minimumObservationConfidence": 0.60,
          "rearmMinimumConfidence": 0.80,
          "tentativeDurationNanos": 400000000,
          "occlusionGraceNanos": 400000000,
          "lostAfterNanos": 1200000000,
          "rearmNeutralDurationNanos": 1000000000,
          "crossingHysteresisFrames": 2,
          "crossingHysteresisNanos": 0,
          "overlapIouPauseThreshold": 1.0,
          "proximityBodyScalePauseThreshold": 0.01,
          "maximumPredictionHorizonNanos": 400000000,
          "maximumStableObservationGapNanos": 400000000
        }
    """.trimIndent()

    val loadedConfig = load(configSourceText.toByteArray(StandardCharsets.UTF_8))
    val config = loadedConfig.config

    fun loaded(config: PlayerTrackerConfig = this.config): LoadedPlayerTrackerConfig =
        if (config == this.config) {
            loadedConfig
        } else {
            load(PlayerTrackerConfigJson.encodeCandidate(config))
        }

    fun tracker(config: PlayerTrackerConfig = this.config): PlayerTracker = PlayerTracker(loaded(config))

    fun frame(
        milliseconds: Long,
        vararg observations: PlayerTrackObservation,
        calibrationRevision: Long = 1L,
        frameToken: PlayerObservationFrameToken = nextFrameToken(),
    ): PlayerObservationFrame = PlayerObservationFrame(
        frameToken = frameToken,
        timestampNanos = milliseconds * 1_000_000L,
        calibrationRevision = calibrationRevision,
        observations = observations.toList(),
    )

    fun binding(
        frame: PlayerObservationFrame,
        p1ObservationId: Int,
        p2ObservationId: Int,
    ): RoleObservationBinding = RoleObservationBinding(
        sourceFrameToken = frame.frameToken,
        sourceTimestampNanos = frame.timestampNanos,
        calibrationRevision = frame.calibrationRevision,
        p1ObservationId = p1ObservationId,
        p2ObservationId = p2ObservationId,
    )

    fun observation(
        id: Int,
        x: Double,
        bodyScale: Double = 0.20,
        confidence: Double = 0.99,
        neutral: Boolean = true,
        halfWidth: Double = 0.06,
        facingDirection: Double = 0.0,
        definitiveFrameExit: Boolean = false,
    ): PlayerTrackObservation = PlayerTrackObservation(
        observationId = id,
        pelvis = NormalizedPoint(x, 0.58),
        shoulderCenter = NormalizedPoint(x, 0.36),
        bodyScale = bodyScale,
        facingDirection = facingDirection,
        bounds = NormalizedBounds(
            left = x - halfWidth,
            top = 0.24,
            right = x + halfWidth,
            bottom = 0.72,
        ),
        confidence = confidence,
        neutral = neutral,
        definitiveFrameExit = definitiveFrameExit,
    )

    fun initializedActiveTracker(
        config: PlayerTrackerConfig = this.config,
        p1: PlayerTrackObservation = observation(1, 0.25),
        p2: PlayerTrackObservation = observation(2, 0.75),
    ): PlayerTracker {
        val tracker = tracker(config)
        val initialFrame = frame(0, p1, p2)
        check(
            tracker.initialize(
                initialFrame,
                binding(initialFrame, p1.observationId, p2.observationId),
            ) is PlayerTrackerResult.Accepted,
        )
        check(
            tracker.processFrame(
                frame(
                    400,
                    p1.copy(observationId = 11),
                    p2.copy(observationId = 12),
                ),
            ) is PlayerTrackerResult.Accepted,
        )
        return tracker
    }

    private fun load(sourceBytes: ByteArray): LoadedPlayerTrackerConfig =
        when (val result = PlayerTrackerConfigJson.load(sourceBytes)) {
            is PlayerTrackerConfigLoadResult.Success -> result.loaded
            is PlayerTrackerConfigLoadResult.Failure -> error(
                "fixture config rejected: ${result.violation}: ${result.detail}",
            )
        }

    private val tokenSequence = AtomicLong(0L)

    private fun nextFrameToken(): PlayerObservationFrameToken =
        PlayerObservationFrameToken(
            sessionNonce = "fixture-session-nonce-not-biometric",
            frameSequence = tokenSequence.incrementAndGet(),
        )
}
