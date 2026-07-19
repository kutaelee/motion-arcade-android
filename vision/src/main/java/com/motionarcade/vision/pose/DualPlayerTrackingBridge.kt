package com.motionarcade.vision.pose

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.TrackState
import com.motionarcade.vision.tracking.IdentityPauseReason
import com.motionarcade.vision.tracking.LoadedPlayerTrackerConfig
import com.motionarcade.vision.tracking.PlayerTrackerConfigReviewStatus
import com.motionarcade.vision.tracking.NormalizedBounds
import com.motionarcade.vision.tracking.NormalizedPoint
import com.motionarcade.vision.tracking.PlayerObservationFrame
import com.motionarcade.vision.tracking.PlayerObservationFrameToken
import com.motionarcade.vision.tracking.PlayerTracker
import com.motionarcade.vision.tracking.PlayerTrackerOutput
import com.motionarcade.vision.tracking.PlayerTrackerResult
import com.motionarcade.vision.tracking.PlayerTrackObservation
import com.motionarcade.vision.tracking.RearmRequestResult
import com.motionarcade.vision.tracking.RoleObservationBinding
import java.util.Collections
import kotlin.math.hypot
import kotlin.math.min

/**
 * Explicit setup policy for the camera-analysis coordinate system. This is a role choice made by
 * the product flow, not an appearance, face, or body-identity inference.
 */
enum class DualPlayerRoleSetupPolicy {
    P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
}

/** Aggregate-only role state for one player. It intentionally contains no detector observation ID. */
data class DualPlayerRoleTrackingState(
    val playerId: PlayerId,
    val state: TrackState,
) {
    init {
        require(playerId == PlayerId.P1 || playerId == PlayerId.P2)
    }
}

/**
 * Privacy-safe dual-player result delivered outside the raw-pose boundary.
 *
 * It intentionally omits landmark coordinates, bounds, source/task timestamps, frame tokens, and
 * detector observation IDs. The UI can render setup/re-arm guidance and pause a game from this
 * state, but cannot retain a pose trace or derive a biometric identifier.
 */
class DualPlayerTrackingSummary internal constructor(
    val sessionGeneration: Long,
    val revision: Long,
    val detectedPoseCount: Int,
    val roleSetupPolicy: DualPlayerRoleSetupPolicy,
    roleStates: List<DualPlayerRoleTrackingState>,
    val pauseRequired: Boolean,
    val pauseReason: IdentityPauseReason,
    val rearmChallengeId: Long,
    val configId: String,
    val configSchemaVersion: Int,
    val configSourceSha256Hex: String,
    val configReviewStatus: PlayerTrackerConfigReviewStatus,
) {
    val roleStates: List<DualPlayerRoleTrackingState> =
        Collections.unmodifiableList(roleStates.map { it.copy() })

    init {
        require(sessionGeneration > 0L)
        require(revision > 0L)
        require(detectedPoseCount in 0..LIVE_POSE_MAX_POSES)
        require(roleStates.map(DualPlayerRoleTrackingState::playerId) == listOf(PlayerId.P1, PlayerId.P2))
        require(pauseRequired == (pauseReason != IdentityPauseReason.NONE))
        require(rearmChallengeId > 0L)
        require(configId.isNotBlank())
        require(configSchemaVersion == 1)
        require(configSourceSha256Hex.matches(Regex("[0-9a-f]{64}")))
        require(configReviewStatus == PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED)
    }

    override fun toString(): String =
        "DualPlayerTrackingSummary(generation=$sessionGeneration, revision=$revision, " +
            "poseCount=$detectedPoseCount, states=${roleStates.map(DualPlayerRoleTrackingState::state)}, " +
            "pauseRequired=$pauseRequired, pauseReason=$pauseReason, configId=$configId)"
}

fun interface DualPlayerTrackingSink {
    fun onDualPlayerTrackingSummary(summary: DualPlayerTrackingSummary)

    companion object {
        val NONE = DualPlayerTrackingSink { }
    }
}

/**
 * Converts package-private live pose data into kinematic tracker observations and an
 * aggregate-only result. It never performs automatic role swapping: an unsafe or uncertain
 * association reaches the caller as a pause, and re-association remains an explicit request.
 */
internal class DualPlayerTrackingBridge(
    private val loadedConfig: LoadedPlayerTrackerConfig,
    private val roleSetupPolicy: DualPlayerRoleSetupPolicy,
    private val calibrationRevision: Long,
    private val sink: DualPlayerTrackingSink,
    private val initialSetupRequiresExplicitRearm: Boolean = true,
    private val combatMotionBridge: DualPlayerCombatMotionBridge? = null,
) : LivePoseObservationSink {
    private val config = loadedConfig.config

    private var tracker: PlayerTracker? = null
    private var frameSequence = 0L
    private var tokenGeneration: Long? = null
    private var latestTrackFrame: PlayerObservationFrame? = null
    private var latestRawRevision: Long? = null
    private var rolesHaveBeenInitialized = false
    private var explicitRearmRequired = initialSetupRequiresExplicitRearm
    private var explicitRearmRequested = false
    private var explicitRearmNeutralSinceNs: Long? = null
    private var latestExplicitRearmObservationNs: Long? = null
    private var rearmChallengeId = 1L
    private var trackerManagedRearmInProgress = false

    init {
        require(calibrationRevision >= 0L)
    }

    @Synchronized
    override fun onPoseObservation(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
    ) {
        if (
            tokenGeneration != frame.sessionGeneration ||
                continuity.boundary != LivePoseContinuityBoundary.CONTIGUOUS
        ) {
            if (explicitRearmRequired || rolesHaveBeenInitialized) beginExplicitRearmChallenge()
            resetForContinuity(frame.sessionGeneration)
            emitAwaiting(frame, continuity, IdentityPauseReason.REARM_REQUIRED)
            return
        }

        val observations = frame.poses.mapIndexedNotNull(::toTrackObservation)
        val observationFrame =
            PlayerObservationFrame(
                frameToken = nextFrameToken(frame.sessionGeneration),
                timestampNanos = frame.sourceTimestampNs,
                calibrationRevision = calibrationRevision,
                observations = observations,
        )
        latestTrackFrame = observationFrame
        latestRawRevision = frame.revision

        val existing = tracker
        if (existing == null) {
            if (explicitRearmRequired) {
                if (initialBinding(observationFrame) == null) resetExplicitRearmDwell()
                emitAwaiting(
                    frame,
                    continuity,
                    if (explicitRearmRequested) {
                        IdentityPauseReason.REARM_STABILITY
                    } else {
                        IdentityPauseReason.REARM_REQUIRED
                    },
                )
                return
            }
            val binding = initialBinding(observationFrame)
            if (binding == null) {
                resetExplicitRearmDwell()
                emitAwaiting(frame, continuity, IdentityPauseReason.INSUFFICIENT_OBSERVATIONS)
                return
            }
            val newTracker = PlayerTracker(loadedConfig)
            when (val result = newTracker.initialize(observationFrame, binding)) {
                is PlayerTrackerResult.Accepted -> {
                    tracker = newTracker
                    rolesHaveBeenInitialized = true
                    emit(frame, continuity, result.output)
                }

                is PlayerTrackerResult.Rejected -> {
                    beginExplicitRearmChallenge()
                    emitAwaiting(frame, continuity, IdentityPauseReason.REARM_REQUIRED)
                }
            }
            return
        }

        when (val result = existing.processFrame(observationFrame)) {
            is PlayerTrackerResult.Accepted -> {
                if (explicitRearmRequired) {
                    processExplicitRearmFrame(frame, continuity, observationFrame, result.output)
                } else if (trackerManagedRearmInProgress) {
                    when {
                        result.output.pauseReason == IdentityPauseReason.REARM_STABILITY ->
                            emit(frame, continuity, result.output)

                        !result.output.pauseRequired -> {
                            trackerManagedRearmInProgress = false
                            emit(frame, continuity, result.output)
                        }

                        else -> {
                            beginExplicitRearmChallenge()
                            emit(frame, continuity, result.output)
                        }
                    }
                } else {
                    if (
                        result.output.pauseRequired &&
                            result.output.pauseReason != IdentityPauseReason.TENTATIVE_TRACKS
                    ) {
                        beginExplicitRearmChallenge()
                    }
                    emit(frame, continuity, result.output)
                }
            }
            is PlayerTrackerResult.Rejected -> {
                tracker = null
                beginExplicitRearmChallenge()
                emitAwaiting(frame, continuity, IdentityPauseReason.REARM_REQUIRED)
            }
        }
    }

    /**
     * Starts an explicit re-arm from the latest kinematic-only frame. This never activates a role
     * by itself: after a continuity reset this bridge requires a new neutral interval, while an
     * existing [PlayerTracker] requires its configured neutral re-arm interval before ACTIVE.
     */
    @Synchronized
    fun requestRearm(expectedGeneration: Long, expectedRearmChallengeId: Long): Boolean {
        if (
            expectedGeneration != tokenGeneration ||
                expectedRearmChallengeId != rearmChallengeId ||
                !explicitRearmRequired
        ) {
            return false
        }
        val frame = latestTrackFrame ?: return false
        val activeTracker = tracker
        if (activeTracker == null) {
            val binding = initialBinding(frame) ?: return false
            val newTracker = PlayerTracker(loadedConfig)
            when (newTracker.initialize(frame, binding)) {
                is PlayerTrackerResult.Accepted -> {
                    tracker = newTracker
                    rolesHaveBeenInitialized = true
                    explicitRearmRequested = true
                    resetExplicitRearmDwell()
                    if (boundObservations(frame, binding)?.all(::isRearmReadyObservation) == true) {
                        explicitRearmNeutralSinceNs = frame.timestampNanos
                        latestExplicitRearmObservationNs = frame.timestampNanos
                    }
                    return true
                }

                is PlayerTrackerResult.Rejected -> {
                    beginExplicitRearmChallenge()
                    return false
                }
            }
        }
        val binding = initialBinding(frame) ?: return false
        return when (val result = activeTracker.requestRearm(binding)) {
            is RearmRequestResult.Accepted -> {
                explicitRearmRequired = false
                explicitRearmRequested = false
                resetExplicitRearmDwell()
                trackerManagedRearmInProgress = true
                emitFromOutput(frame, result.output)
                true
            }

            is RearmRequestResult.Rejected -> false
        }
    }

    private fun resetForContinuity(generation: Long) {
        tracker = null
        latestTrackFrame = null
        latestRawRevision = null
        explicitRearmRequested = false
        trackerManagedRearmInProgress = false
        resetExplicitRearmDwell()
        if (tokenGeneration != generation) {
            tokenGeneration = generation
            frameSequence = 0L
        }
    }

    private fun nextFrameToken(generation: Long): PlayerObservationFrameToken {
        check(tokenGeneration == generation) { "tracking generation must be established before a frame" }
        check(frameSequence < Long.MAX_VALUE) { "tracking frame sequence exhausted" }
        frameSequence += 1L
        return PlayerObservationFrameToken(
            sessionNonce = "live-pose-generation-$generation",
            frameSequence = frameSequence,
        )
    }

    private fun resetExplicitRearmDwell() {
        explicitRearmNeutralSinceNs = null
        latestExplicitRearmObservationNs = null
    }

    private fun beginExplicitRearmChallenge() {
        check(rearmChallengeId < Long.MAX_VALUE) { "dual-player re-arm challenge sequence exhausted" }
        rearmChallengeId += 1L
        explicitRearmRequired = true
        explicitRearmRequested = false
        trackerManagedRearmInProgress = false
        resetExplicitRearmDwell()
    }

    private fun processExplicitRearmFrame(
        liveFrame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
        observationFrame: PlayerObservationFrame,
        output: PlayerTrackerOutput,
    ) {
        if (
            output.pauseRequired &&
                output.pauseReason != IdentityPauseReason.TENTATIVE_TRACKS
        ) {
            tracker = null
            beginExplicitRearmChallenge()
            emitAwaiting(liveFrame, continuity, IdentityPauseReason.REARM_REQUIRED)
            return
        }
        val assigned = output.assignments.mapNotNull { assignment ->
            assignment.observationId?.let { observationId ->
                observationFrame.observations.firstOrNull { it.observationId == observationId }
            }
        }
        val previousRearmObservation = latestExplicitRearmObservationNs
        val stableGap =
            previousRearmObservation == null ||
                observationFrame.timestampNanos - previousRearmObservation <=
                config.maximumStableObservationGapNanos
        if (assigned.size != 2 || !stableGap || !assigned.all(::isRearmReadyObservation)) {
            resetExplicitRearmDwell()
            emitAwaiting(liveFrame, continuity, IdentityPauseReason.REARM_STABILITY)
            return
        }
        latestExplicitRearmObservationNs = observationFrame.timestampNanos
        val neutralSince = explicitRearmNeutralSinceNs
        if (neutralSince == null) {
            explicitRearmNeutralSinceNs = observationFrame.timestampNanos
            emitAwaiting(liveFrame, continuity, IdentityPauseReason.REARM_STABILITY)
            return
        }
        if (
            observationFrame.timestampNanos - neutralSince < config.rearmNeutralDurationNanos ||
                output.pauseRequired
        ) {
            emitAwaiting(liveFrame, continuity, IdentityPauseReason.REARM_STABILITY)
            return
        }
        explicitRearmRequired = false
        explicitRearmRequested = false
        resetExplicitRearmDwell()
        emit(liveFrame, continuity, output)
    }

    private fun boundObservations(
        frame: PlayerObservationFrame,
        binding: RoleObservationBinding,
    ): List<PlayerTrackObservation>? =
        listOf(binding.p1ObservationId, binding.p2ObservationId).map { observationId ->
            frame.observations.firstOrNull { it.observationId == observationId }
        }.takeIf { observations -> observations.all { it != null } }
            ?.map(::requireNotNull)

    private fun isRearmReadyObservation(observation: PlayerTrackObservation): Boolean =
        observation.neutral && observation.confidence >= config.rearmMinimumConfidence

    private fun initialBinding(frame: PlayerObservationFrame): RoleObservationBinding? {
        if (frame.observations.size != 2) return null
        val sorted = frame.observations.sortedBy { it.pelvis.x }
        val left = sorted.first()
        val right = sorted.last()
        if (right.pelvis.x - left.pelvis.x < MINIMUM_INITIAL_ROLE_SEPARATION) return null
        return when (roleSetupPolicy) {
            DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT ->
                RoleObservationBinding(
                    sourceFrameToken = frame.frameToken,
                    sourceTimestampNanos = frame.timestampNanos,
                    calibrationRevision = frame.calibrationRevision,
                    p1ObservationId = left.observationId,
                    p2ObservationId = right.observationId,
                )
        }
    }

    private fun toTrackObservation(index: Int, pose: LivePoseObservation): PlayerTrackObservation? {
        val landmarks = pose.landmarks
        val required = listOf(
            landmarks[LEFT_SHOULDER],
            landmarks[RIGHT_SHOULDER],
            landmarks[LEFT_WRIST],
            landmarks[RIGHT_WRIST],
            landmarks[LEFT_HIP],
            landmarks[RIGHT_HIP],
        )
        if (required.any(::isInvalidLandmark)) return null
        val confidence = required.minOf(::landmarkConfidence)
        if (confidence < config.minimumObservationConfidence) return null

        val leftShoulder = required[0]
        val rightShoulder = required[1]
        val leftWrist = required[2]
        val rightWrist = required[3]
        val leftHip = required[4]
        val rightHip = required[5]
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val pelvis = midpoint(leftHip, rightHip)
        val bodyScale = hypot(shoulderCenter.x - pelvis.x, shoulderCenter.y - pelvis.y)
        if (!bodyScale.isFinite() || bodyScale < config.minimumBodyScale) return null

        val left = required.minOf { it.x.toDouble() }
        val top = required.minOf { it.y.toDouble() }
        val right = required.maxOf { it.x.toDouble() }
        val bottom = required.maxOf { it.y.toDouble() }
        if (right <= left || bottom <= top) return null
        val facingDirection = (rightShoulder.x - leftShoulder.x).toDouble() / bodyScale
        if (!facingDirection.isFinite()) return null
        val wristsAverageY = (leftWrist.y + rightWrist.y).toDouble() / 2.0
        val neutral = wristsAverageY >= shoulderCenter.y + bodyScale * NEUTRAL_WRIST_DROP_BODY_SCALE

        return PlayerTrackObservation(
            observationId = index,
            pelvis = pelvis,
            shoulderCenter = shoulderCenter,
            bodyScale = bodyScale,
            facingDirection = facingDirection.coerceIn(-1.0, 1.0),
            bounds = NormalizedBounds(left, top, right, bottom),
            confidence = confidence,
            neutral = neutral,
            definitiveFrameExit = false,
        )
    }

    private fun isInvalidLandmark(landmark: LivePoseLandmark): Boolean =
        !landmark.x.isFinite() ||
            !landmark.y.isFinite() ||
            landmark.x !in 0f..1f ||
            landmark.y !in 0f..1f

    private fun landmarkConfidence(landmark: LivePoseLandmark): Double =
        min(landmark.visibility ?: 0f, landmark.presence ?: 0f).toDouble()

    private fun midpoint(first: LivePoseLandmark, second: LivePoseLandmark): NormalizedPoint =
        NormalizedPoint(
            x = (first.x + second.x).toDouble() / 2.0,
            y = (first.y + second.y).toDouble() / 2.0,
        )

    private fun emit(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
        output: PlayerTrackerOutput,
    ) {
        sink.onDualPlayerTrackingSummary(summary(frame.sessionGeneration, frame.revision, frame.poses.size, output))
        combatMotionBridge?.onTrackedFrame(frame, continuity, output)
    }

    private fun emitFromOutput(frame: PlayerObservationFrame, output: PlayerTrackerOutput) {
        val generation = requireNotNull(tokenGeneration)
        val revision = requireNotNull(latestRawRevision)
        sink.onDualPlayerTrackingSummary(summary(generation, revision, frame.observations.size, output))
        // Request-rearm output has no current raw pose frame. Never let a pre-rearm candidate
        // survive until the next tracker-authorized dual frame.
        combatMotionBridge?.reset()
    }

    private fun emitAwaiting(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
        reason: IdentityPauseReason,
    ) {
        sink.onDualPlayerTrackingSummary(
            DualPlayerTrackingSummary(
                sessionGeneration = frame.sessionGeneration,
                revision = frame.revision,
                detectedPoseCount = frame.poses.size,
                roleSetupPolicy = roleSetupPolicy,
                roleStates = awaitingRoleStates(),
                pauseRequired = true,
                pauseReason = reason,
                rearmChallengeId = rearmChallengeId,
                configId = config.configId,
                configSchemaVersion = config.schemaVersion,
                configSourceSha256Hex = loadedConfig.sourceSha256Hex,
                configReviewStatus = loadedConfig.reviewStatus,
            ),
        )
        combatMotionBridge?.onUnsafeFrame(frame, continuity)
    }

    private fun summary(
        generation: Long,
        revision: Long,
        poseCount: Int,
        output: PlayerTrackerOutput,
    ): DualPlayerTrackingSummary =
        DualPlayerTrackingSummary(
            sessionGeneration = generation,
            revision = revision,
            detectedPoseCount = poseCount,
            roleSetupPolicy = roleSetupPolicy,
            roleStates = output.assignments.map { assignment ->
                DualPlayerRoleTrackingState(
                    playerId = assignment.roleId,
                    state = assignment.state,
                )
            },
            pauseRequired = output.pauseRequired,
            pauseReason = output.pauseReason,
            rearmChallengeId = rearmChallengeId,
            configId = output.configId,
            configSchemaVersion = output.configSchemaVersion,
            configSourceSha256Hex = output.configSourceSha256Hex,
            configReviewStatus = output.configReviewStatus,
        )

    private fun awaitingRoleStates(): List<DualPlayerRoleTrackingState> =
        listOf(
            DualPlayerRoleTrackingState(PlayerId.P1, TrackState.REARM),
            DualPlayerRoleTrackingState(PlayerId.P2, TrackState.REARM),
        )

    private companion object {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val MINIMUM_INITIAL_ROLE_SEPARATION = 0.02
        const val NEUTRAL_WRIST_DROP_BODY_SCALE = 0.20
    }
}
