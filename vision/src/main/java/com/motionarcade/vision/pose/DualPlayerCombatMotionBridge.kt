package com.motionarcade.vision.pose

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrameSink
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import com.motionarcade.vision.motion.requiredMotionTypes
import com.motionarcade.vision.tracking.PlayerTrackerOutput
import java.util.ArrayDeque
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Maps tracker-authorized P1/P2 observations to profile-scoped scalar signals.
 *
 * This is intentionally called only inside [DualPlayerTrackingBridge]. Role-to-observation
 * association, raw landmarks, and all temporal feature state remain in :vision. Product callers
 * receive a complete two-player signal frame without identity or coordinate data.
 */
internal class DualPlayerCombatMotionBridge(
    private val config: DualPlayerCombatMotionConfig,
    private val sink: DualPlayerCombatMotionFrameSink,
) {
    private val combatExtractors = if (config.profile == DualPlayerCombatProfile.FISHING) {
        emptyMap()
    } else {
        PLAYERS.associateWith { playerId -> CombatPoseSignalExtractor(config, playerId) }
    }
    private val fishingExtractors = if (config.profile == DualPlayerCombatProfile.FISHING) {
        PLAYERS.associateWith { FishingPoseSignalExtractor(requireNotNull(config.fishingPoseConfig)) }
    } else {
        emptyMap()
    }

    fun reset() {
        combatExtractors.values.forEach(CombatPoseSignalExtractor::reset)
        fishingExtractors.values.forEach(FishingPoseSignalExtractor::reset)
    }

    fun onUnsafeFrame(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
    ) {
        reset()
        emit(frame, continuity, usable = false, emptySignals(frame.sourceTimestampNs))
    }

    fun onTrackedFrame(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
        tracking: PlayerTrackerOutput,
    ) {
        if (tracking.pauseRequired || continuity.boundary != LivePoseContinuityBoundary.CONTIGUOUS) {
            onUnsafeFrame(frame, continuity)
            return
        }
        val rolePoses = tracking.assignments.associate { assignment ->
            assignment.roleId to assignment.observationId?.let(frame.poses::getOrNull)
        }
        val p1 = rolePoses[PlayerId.P1]
        val p2 = rolePoses[PlayerId.P2]
        if (p1 == null || p2 == null || frame.poses.size != 2) {
            onUnsafeFrame(frame, continuity)
            return
        }
        if (config.profile == DualPlayerCombatProfile.FISHING) {
            val p1Signals = fishingSignals(PlayerId.P1, frame.sourceTimestampNs, p1)
            val p2Signals = fishingSignals(PlayerId.P2, frame.sourceTimestampNs, p2)
            val usable = p1Signals != null && p2Signals != null
            emit(
                frame = frame,
                continuity = continuity,
                usable = usable,
                signals = if (usable) requireNotNull(p1Signals) + requireNotNull(p2Signals) else {
                    reset()
                    emptySignals(frame.sourceTimestampNs)
                },
            )
            return
        }
        val p1Signals = requireNotNull(combatExtractors[PlayerId.P1]).process(frame.sourceTimestampNs, p1)
        val p2Signals = requireNotNull(combatExtractors[PlayerId.P2]).process(frame.sourceTimestampNs, p2)
        val usable = p1Signals != null && p2Signals != null
        emit(
            frame = frame,
            continuity = continuity,
            usable = usable,
            signals = if (usable) {
                signalsFor(PlayerId.P1, requireNotNull(p1Signals)) +
                    signalsFor(PlayerId.P2, requireNotNull(p2Signals))
            } else {
                reset()
                emptySignals(frame.sourceTimestampNs)
            },
        )
    }

    private fun emit(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
        usable: Boolean,
        signals: List<MotionSignalSample>,
    ) {
        sink.onDualPlayerCombatMotionFrame(
            DualPlayerCombatMotionFrame(
                sessionGeneration = frame.sessionGeneration,
                revision = frame.revision,
                sourceTimestampNs = frame.sourceTimestampNs,
                poseCount = frame.poses.size,
                usableForDual = usable,
                continuityBoundary = continuity.boundary,
                configId = config.configId,
                calibrationRevision = config.calibrationRevision,
                profile = config.profile,
                samples = signals,
            ),
        )
    }

    private fun signalsFor(
        playerId: PlayerId,
        signals: Map<MotionType, CombatPoseSignal>,
    ): List<MotionSignalSample> =
        config.profile.requiredMotionTypes.map { type ->
            val signal = requireNotNull(signals[type])
            MotionSignalSample(
                playerId = playerId,
                type = type,
                timestampNs = signal.timestampNs,
                activation = signal.activation,
                quality = signal.activation,
                confidence = signal.confidence,
                calibrationRevision = config.calibrationRevision,
                source = InputSource.MOTION,
                metadata = emptyMap(),
            )
        }

    private fun fishingSignals(
        playerId: PlayerId,
        timestampNs: Long,
        pose: LivePoseObservation,
    ): List<MotionSignalSample>? {
        val sample = FishingPoseSample(
            timestampNs,
            config.calibrationRevision,
            pose.landmarks.map { landmark ->
                FishingPosePoint(
                    landmark.x,
                    landmark.y,
                    landmark.z,
                    min(landmark.visibility ?: 0f, landmark.presence ?: 0f),
                )
            },
        )
        return when (val result = requireNotNull(fishingExtractors[playerId]).process(sample)) {
            is FishingPoseSignalResult.Accepted -> {
                if (result.temporalReset) return null
                val byCandidate = result.signals.associateBy(FishingGestureCandidateSignal::candidate)
                FISHING_CANDIDATE_TO_TYPE.map { (candidate, type) ->
                    val signal = requireNotNull(byCandidate[candidate])
                    MotionSignalSample(
                        playerId = playerId,
                        type = type,
                        timestampNs = timestampNs,
                        activation = signal.activation,
                        quality = signal.activation,
                        confidence = signal.confidence,
                        calibrationRevision = config.calibrationRevision,
                        source = InputSource.MOTION,
                        metadata = emptyMap(),
                    )
                }
            }
            is FishingPoseSignalResult.Rejected -> {
                requireNotNull(fishingExtractors[playerId]).reset()
                null
            }
        }
    }

    private fun emptySignals(timestampNs: Long): List<MotionSignalSample> =
        listOf(PlayerId.P1, PlayerId.P2).flatMap { playerId ->
            config.profile.requiredMotionTypes.map { type ->
                MotionSignalSample(
                    playerId = playerId,
                    type = type,
                    timestampNs = timestampNs,
                    activation = 0f,
                    quality = 0f,
                    confidence = 0f,
                    calibrationRevision = config.calibrationRevision,
                    source = InputSource.MOTION,
                    metadata = emptyMap(),
                )
            }
        }

    internal class CombatPoseSignalExtractor(
        private val config: DualPlayerCombatMotionConfig,
        private val playerId: PlayerId,
    ) {
        private val poseConfig = requireNotNull(config.poseConfig)
        private data class Point(val x: Float, val y: Float)

        private data class Features(
            val timestampNs: Long,
            val shoulderCenter: Point,
            val chestCenter: Point,
            val pelvisCenter: Point,
            val leftWrist: Point,
            val rightWrist: Point,
            val leftElbowAngle: Float,
            val rightElbowAngle: Float,
            val shoulderWidth: Float,
            val torsoLength: Float,
            val confidence: Float,
        )

        private val history = ArrayDeque<Features>()
        private var lastTimestampNs: Long? = null

        fun reset() {
            history.clear()
            lastTimestampNs = null
        }

        fun process(timestampNs: Long, pose: LivePoseObservation): Map<MotionType, CombatPoseSignal>? {
            val previousTimestamp = lastTimestampNs
            if (timestampNs < 0L || previousTimestamp != null && timestampNs <= previousTimestamp) return null
            lastTimestampNs = timestampNs
            val current = pose.toFeatures(timestampNs) ?: run {
                history.clear()
                return null
            }
            trimHistory(timestampNs)
            val result = config.profile.requiredMotionTypes.associateWith { type ->
                val activation = activation(type, current)
                CombatPoseSignal(timestampNs, activation, current.confidence)
            }
            history.addLast(current)
            while (history.size > poseConfig.maximumHistorySamples) history.removeFirst()
            return result
        }

        private fun activation(type: MotionType, current: Features): Float = when (type) {
            MotionType.BOXING_GUARD -> guardActivation(current)
            MotionType.DODGE_LEFT -> dodgeActivation(current, left = true)
            MotionType.DODGE_RIGHT -> dodgeActivation(current, left = false)
            MotionType.PUNCH_JAB -> punchActivation(current)
            MotionType.PUNCH_HOOK -> hookActivation(current)
            MotionType.MONSTER_BLOCK -> min(guardActivation(current), crossedWristActivation(current))
            MotionType.MONSTER_REVIVE -> reviveActivation(current)
            MotionType.MONSTER_SKILL_ONE -> classSkillActivation(current, leftArm = true)
            MotionType.MONSTER_SKILL_TWO -> classSkillActivation(current, leftArm = false)
            MotionType.MONSTER_MAGIC_CHARGE -> magicChargeActivation(current)
            MotionType.TEAM_ULTIMATE -> ultimateActivation(current)
            else -> error("Unsupported combat motion type: $type")
        }

        private fun guardActivation(current: Features): Float {
            val leftDistance = distance(current.leftWrist, current.chestCenter) / current.shoulderWidth
            val rightDistance = distance(current.rightWrist, current.chestCenter) / current.shoulderWidth
            val nearChest = 1f - max(leftDistance, rightDistance) /
                poseConfig.guardMaximumWristDistanceShoulderWidths
            val wristHeight = current.chestCenter.y - max(current.leftWrist.y, current.rightWrist.y)
            val raised = wristHeight / (current.torsoLength * poseConfig.guardMinimumWristHeightTorsoLengths)
            return min(nearChest, raised).coerceIn(0f, 1f)
        }

        private fun crossedWristActivation(current: Features): Float =
            (1f - distance(current.leftWrist, current.rightWrist) / current.shoulderWidth).coerceIn(0f, 1f)

        /** Both arms held together toward a teammate; the 1.5 s hold is enforced by the gesture engine. */
        private fun reviveActivation(current: Features): Float {
            val leftDelta = current.leftWrist.x - current.chestCenter.x
            val rightDelta = current.rightWrist.x - current.chestCenter.x
            val towardTeammate = when (playerId) {
                PlayerId.P1 -> min(leftDelta, rightDelta)
                PlayerId.P2 -> min(-leftDelta, -rightDelta)
                PlayerId.AI -> return 0f
            }
            return (towardTeammate / current.shoulderWidth).coerceIn(0f, 1f)
        }

        private fun classSkillActivation(current: Features, leftArm: Boolean): Float {
            val raisedWrist = if (leftArm) current.leftWrist else current.rightWrist
            val restingWrist = if (leftArm) current.rightWrist else current.leftWrist
            val raised = (current.shoulderCenter.y - raisedWrist.y) /
                (current.torsoLength * poseConfig.skillMinimumRaiseTorsoLengths)
            val resting = (restingWrist.y - current.chestCenter.y) /
                (current.torsoLength * poseConfig.skillMinimumRestBelowChestTorsoLengths)
            return min(raised, resting).coerceIn(0f, 1f)
        }

        private fun dodgeActivation(current: Features, left: Boolean): Float {
            val lean = (current.shoulderCenter.x - current.pelvisCenter.x) / current.torsoLength
            val directed = if (left) -lean else lean
            return (directed / poseConfig.dodgeMinimumLeanTorsoLengths).coerceIn(0f, 1f)
        }

        private fun magicChargeActivation(current: Features): Float {
            val raised = min(
                current.shoulderCenter.y - current.leftWrist.y,
                current.shoulderCenter.y - current.rightWrist.y,
            ) / (current.torsoLength * poseConfig.chargeMinimumRaiseTorsoLengths)
            val separated = distance(current.leftWrist, current.rightWrist) /
                (current.shoulderWidth * poseConfig.chargeMinimumWristSeparationShoulderWidths)
            return min(raised, separated).coerceIn(0f, 1f)
        }

        private fun punchActivation(current: Features): Float {
            var best = 0f
            history.forEach { previous ->
                if (current.timestampNs - previous.timestampNs > poseConfig.punchWindowNs) return@forEach
                val left = punchProgress(
                    current.leftWrist,
                    previous.leftWrist,
                    current.leftElbowAngle - previous.leftElbowAngle,
                    current.shoulderWidth,
                )
                val right = punchProgress(
                    current.rightWrist,
                    previous.rightWrist,
                    current.rightElbowAngle - previous.rightElbowAngle,
                    current.shoulderWidth,
                )
                best = max(best, max(left, right))
            }
            return best.coerceIn(0f, 1f)
        }

        private fun punchProgress(
            current: Point,
            previous: Point,
            elbowExtension: Float,
            shoulderWidth: Float,
        ): Float = min(
            distance(current, previous) / (shoulderWidth * poseConfig.punchMinimumTravelShoulderWidths),
            elbowExtension / poseConfig.punchMinimumElbowExtensionRadians,
        ).coerceIn(0f, 1f)

        private fun hookActivation(current: Features): Float {
            val left = hookProgress(current.leftWrist, current.leftElbowAngle, current)
            val right = hookProgress(current.rightWrist, current.rightElbowAngle, current)
            return max(left, right)
        }

        private fun hookProgress(wrist: Point, elbowAngle: Float, current: Features): Float {
            val angle = when {
                elbowAngle < poseConfig.hookMinimumElbowAngleRadians -> 0f
                elbowAngle > poseConfig.hookMaximumElbowAngleRadians -> 0f
                else -> 1f
            }
            val crossBody = abs(wrist.x - current.chestCenter.x) /
                (current.shoulderWidth * poseConfig.hookMinimumCrossBodyShoulderWidths)
            return min(angle, crossBody).coerceIn(0f, 1f)
        }

        private fun ultimateActivation(current: Features): Float {
            val leftRaise = (current.shoulderCenter.y - current.leftWrist.y) / current.torsoLength
            val rightRaise = (current.shoulderCenter.y - current.rightWrist.y) / current.torsoLength
            val raised = min(leftRaise, rightRaise) / poseConfig.ultimateMinimumRaiseTorsoLengths
            val wristsTogether = 1f - distance(current.leftWrist, current.rightWrist) /
                (current.shoulderWidth * poseConfig.ultimateMaximumWristDistanceShoulderWidths)
            return min(raised, wristsTogether).coerceIn(0f, 1f)
        }

        private fun LivePoseObservation.toFeatures(timestampNs: Long): Features? {
            val landmarks = landmarks
            if (landmarks.size != LIVE_POSE_LANDMARK_COUNT) return null
            val requiredIndexes = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24)
            if (requiredIndexes.any { index -> landmarks[index].invalid() }) return null
            val confidence = requiredIndexes.minOf { index ->
                min(landmarks[index].visibility ?: 0f, landmarks[index].presence ?: 0f)
            }
            if (confidence < poseConfig.minimumLandmarkConfidence) return null
            fun point(index: Int): Point = landmarks[index].let { landmark -> Point(landmark.x, landmark.y) }
            val leftShoulder = point(11)
            val rightShoulder = point(12)
            val leftElbow = point(13)
            val rightElbow = point(14)
            val leftWrist = point(15)
            val rightWrist = point(16)
            val leftHip = point(23)
            val rightHip = point(24)
            val shoulderCenter = midpoint(leftShoulder, rightShoulder)
            val pelvisCenter = midpoint(leftHip, rightHip)
            val shoulderWidth = distance(leftShoulder, rightShoulder)
            val torsoLength = distance(shoulderCenter, pelvisCenter)
            if (
                shoulderWidth < poseConfig.minimumShoulderWidth ||
                    torsoLength < poseConfig.minimumTorsoLength
            ) return null
            return Features(
                timestampNs = timestampNs,
                shoulderCenter = shoulderCenter,
                chestCenter = midpoint(shoulderCenter, pelvisCenter),
                pelvisCenter = pelvisCenter,
                leftWrist = leftWrist,
                rightWrist = rightWrist,
                leftElbowAngle = jointAngle(leftShoulder, leftElbow, leftWrist),
                rightElbowAngle = jointAngle(rightShoulder, rightElbow, rightWrist),
                shoulderWidth = shoulderWidth,
                torsoLength = torsoLength,
                confidence = confidence,
            )
        }

        private fun LivePoseLandmark.invalid(): Boolean =
            !x.isFinite() || !y.isFinite() || !z.isFinite() || x !in 0f..1f || y !in 0f..1f

        private fun trimHistory(nowNs: Long) {
            while (
                history.isNotEmpty() &&
                    nowNs - history.first().timestampNs > poseConfig.punchWindowNs
            ) history.removeFirst()
        }

        private fun midpoint(first: Point, second: Point): Point = Point(
            x = (first.x + second.x) / 2f,
            y = (first.y + second.y) / 2f,
        )

        private fun distance(first: Point, second: Point): Float =
            hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()

        private fun jointAngle(first: Point, vertex: Point, third: Point): Float {
            val ax = first.x - vertex.x
            val ay = first.y - vertex.y
            val bx = third.x - vertex.x
            val by = third.y - vertex.y
            val denominator = hypot(ax.toDouble(), ay.toDouble()) * hypot(bx.toDouble(), by.toDouble())
            if (denominator <= MIN_VECTOR_LENGTH) return 0f
            val cosine = ((ax * bx + ay * by) / denominator).coerceIn(-1.0, 1.0)
            return acos(cosine).toFloat()
        }

        private companion object {
            const val MIN_VECTOR_LENGTH = 1e-8
        }
    }

    internal data class CombatPoseSignal(
        val timestampNs: Long,
        val activation: Float,
        val confidence: Float,
    )

    private companion object {
        val PLAYERS = listOf(PlayerId.P1, PlayerId.P2)
        val FISHING_CANDIDATE_TO_TYPE = linkedMapOf(
            FishingGestureCandidate.READY to MotionType.FISH_READY,
            FishingGestureCandidate.CAST to MotionType.FISH_CAST,
            FishingGestureCandidate.HOOK to MotionType.FISH_HOOK,
            FishingGestureCandidate.REEL_CYCLE to MotionType.FISH_REEL_CYCLE,
            FishingGestureCandidate.TENSION_LEFT to MotionType.FISH_TENSION_LEFT,
            FishingGestureCandidate.TENSION_RIGHT to MotionType.FISH_TENSION_RIGHT,
            FishingGestureCandidate.NET to MotionType.FISH_NET,
        )
    }
}
