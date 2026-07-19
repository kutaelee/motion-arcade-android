package com.motionarcade.vision.pose

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * MediaPipe Pose landmark indexes used by the fishing recognizer. The recognizer receives plain
 * numbers so MediaPipe types remain contained at the :vision session boundary.
 */
internal object FishingPoseLandmarkIndex {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LANDMARK_COUNT = 33
}

internal enum class FishingGestureCandidate {
    READY,
    CAST,
    HOOK,
    REEL_CYCLE,
    TENSION_LEFT,
    TENSION_RIGHT,
    NET,
}

internal data class FishingGestureCandidateSignal(
    val candidate: FishingGestureCandidate,
    val activation: Float,
    val confidence: Float,
) {
    init {
        require(activation.isFinite() && activation in 0f..1f)
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

/**
 * Versioned candidate thresholds. Release values must be loaded from a reviewed config; there is
 * deliberately no production default in code.
 */
internal data class FishingPoseSignalConfig(
    val configId: String,
    val schemaVersion: Int,
    val minimumLandmarkConfidence: Float,
    val minimumShoulderWidth: Float,
    val minimumTorsoLength: Float,
    val readyMaximumWristDistanceShoulderWidths: Float,
    val castMinimumWristTravelShoulderWidths: Float,
    val castMinimumElbowExtensionDelta: Float,
    val castWindowNs: Long,
    val hookMinimumRiseTorsoLengths: Float,
    val hookWindowNs: Long,
    val reelMinimumRadiusShoulderWidths: Float,
    val reelMinimumAccumulatedRadians: Float,
    val reelMinimumElbowAngleRangeRadians: Float,
    val reelMaximumStepRadians: Float,
    val reelMaximumFrameGapNs: Long,
    val tensionMinimumLeanTorsoLengths: Float,
    val netMinimumRiseTorsoLengths: Float,
    val netWindowNs: Long,
    val maximumHistorySamples: Int,
) {
    init {
        require(configId.isNotBlank())
        require(schemaVersion == 1)
        require(
            minimumLandmarkConfidence.isFinite() &&
                minimumLandmarkConfidence in 0f..1f,
        )
        require(minimumShoulderWidth.isFinite() && minimumShoulderWidth > 0f)
        require(minimumTorsoLength.isFinite() && minimumTorsoLength > 0f)
        require(readyMaximumWristDistanceShoulderWidths.isFinite())
        require(readyMaximumWristDistanceShoulderWidths > 0f)
        require(castMinimumWristTravelShoulderWidths.isFinite())
        require(castMinimumWristTravelShoulderWidths > 0f)
        require(castMinimumElbowExtensionDelta.isFinite() && castMinimumElbowExtensionDelta > 0f)
        require(castWindowNs in 1L..700_000_000L)
        require(hookMinimumRiseTorsoLengths.isFinite() && hookMinimumRiseTorsoLengths > 0f)
        require(hookWindowNs in 1L..350_000_000L)
        require(reelMinimumRadiusShoulderWidths.isFinite() && reelMinimumRadiusShoulderWidths > 0f)
        require(reelMinimumAccumulatedRadians.isFinite())
        require(reelMinimumAccumulatedRadians in PI.toFloat()..(2f * PI.toFloat()))
        require(reelMinimumElbowAngleRangeRadians.isFinite())
        require(reelMinimumElbowAngleRangeRadians > 0f)
        require(reelMaximumStepRadians.isFinite())
        require(reelMaximumStepRadians in 0f..PI.toFloat())
        require(reelMaximumFrameGapNs > 0L)
        require(tensionMinimumLeanTorsoLengths.isFinite() && tensionMinimumLeanTorsoLengths > 0f)
        require(netMinimumRiseTorsoLengths.isFinite() && netMinimumRiseTorsoLengths > 0f)
        require(netWindowNs > 0L)
        require(maximumHistorySamples in 8..256)
    }
}

internal enum class FishingPoseSignalRejection {
    INVALID_FRAME,
    NON_MONOTONIC_TIMESTAMP,
}

internal sealed interface FishingPoseSignalResult {
    data class Accepted(
        val signals: List<FishingGestureCandidateSignal>,
        val temporalReset: Boolean,
    ) : FishingPoseSignalResult

    data class Rejected(val reason: FishingPoseSignalRejection) : FishingPoseSignalResult
}

/**
 * Stateful Pose-only fishing feature extractor.
 *
 * This class emits bounded candidate activations, never a game event. The frame-rate-independent
 * motion engine remains the sole authority that confirms a gesture and creates an event ID.
 */
internal class FishingPoseSignalExtractor(
    private val config: FishingPoseSignalConfig,
) {
    private data class Point(val x: Float, val y: Float)

    private data class Features(
        val timestampNs: Long,
        val leftShoulder: Point,
        val rightShoulder: Point,
        val leftElbow: Point,
        val rightElbow: Point,
        val leftWrist: Point,
        val rightWrist: Point,
        val shoulderCenter: Point,
        val pelvisCenter: Point,
        val chestCenter: Point,
        val shoulderWidth: Float,
        val torsoLength: Float,
        val leftElbowAngle: Float,
        val rightElbowAngle: Float,
        val confidence: Float,
    )

    private val history = ArrayDeque<Features>()
    private var calibrationRevision: Int? = null
    private var lastTimestampNs: Long? = null
    private var reelLastAngle: Float? = null
    private var reelLastTimestampNs: Long? = null
    private var reelDirection = 0
    private var reelAccumulatedRadians = 0f
    private var reelMinimumElbowAngle = Float.POSITIVE_INFINITY
    private var reelMaximumElbowAngle = Float.NEGATIVE_INFINITY

    @Synchronized
    fun process(sample: FishingPoseSample): FishingPoseSignalResult {
        val snapshot = sample.privateSnapshotOrNull()
            ?: return FishingPoseSignalResult.Rejected(FishingPoseSignalRejection.INVALID_FRAME)
        if (!snapshot.isValid()) {
            return FishingPoseSignalResult.Rejected(FishingPoseSignalRejection.INVALID_FRAME)
        }
        val previousTimestamp = lastTimestampNs
        if (previousTimestamp != null && snapshot.timestampNs <= previousTimestamp) {
            return FishingPoseSignalResult.Rejected(
                FishingPoseSignalRejection.NON_MONOTONIC_TIMESTAMP,
            )
        }

        val revisionChanged = calibrationRevision != null &&
            calibrationRevision != snapshot.calibrationRevision
        if (revisionChanged) resetTemporalState()
        calibrationRevision = snapshot.calibrationRevision
        lastTimestampNs = snapshot.timestampNs

        val features = snapshot.toFeatures()
        if (features == null || features.confidence < config.minimumLandmarkConfidence) {
            resetTemporalState(keepClock = true)
            return FishingPoseSignalResult.Accepted(zeroSignals(), temporalReset = true)
        }

        trimHistory(features.timestampNs)
        val signals = buildSignals(features)
        history.addLast(features)
        while (history.size > config.maximumHistorySamples) history.removeFirst()
        return FishingPoseSignalResult.Accepted(signals, temporalReset = revisionChanged)
    }

    @Synchronized
    fun reset() {
        calibrationRevision = null
        lastTimestampNs = null
        resetTemporalState()
    }

    private fun buildSignals(current: Features): List<FishingGestureCandidateSignal> {
        val ready = readyActivation(current)
        val cast = castActivation(current)
        val hook = hookActivation(current)
        val reel = reelActivation(current)
        val lean = (current.shoulderCenter.x - current.pelvisCenter.x) / current.torsoLength
        val leftTension = (-lean / config.tensionMinimumLeanTorsoLengths).coerceIn(0f, 1f)
        val rightTension = (lean / config.tensionMinimumLeanTorsoLengths).coerceIn(0f, 1f)
        val net = netActivation(current)
        return listOf(
            signal(FishingGestureCandidate.READY, ready, current.confidence),
            signal(FishingGestureCandidate.CAST, cast, current.confidence),
            signal(FishingGestureCandidate.HOOK, hook, current.confidence),
            signal(FishingGestureCandidate.REEL_CYCLE, reel, current.confidence),
            signal(FishingGestureCandidate.TENSION_LEFT, leftTension, current.confidence),
            signal(FishingGestureCandidate.TENSION_RIGHT, rightTension, current.confidence),
            signal(FishingGestureCandidate.NET, net, current.confidence),
        )
    }

    private fun readyActivation(current: Features): Float {
        val left = distance(current.leftWrist, current.chestCenter) / current.shoulderWidth
        val right = distance(current.rightWrist, current.chestCenter) / current.shoulderWidth
        val worstDistance = max(left, right)
        return (1f - worstDistance / config.readyMaximumWristDistanceShoulderWidths)
            .coerceIn(0f, 1f)
    }

    private fun castActivation(current: Features): Float {
        var best = 0f
        history.forEach { previous ->
            if (current.timestampNs - previous.timestampNs > config.castWindowNs) return@forEach
            val leftTravel = distance(
                normalizedRelative(
                    current.leftWrist,
                    current.shoulderCenter,
                    current.shoulderWidth,
                ),
                normalizedRelative(
                    previous.leftWrist,
                    previous.shoulderCenter,
                    previous.shoulderWidth,
                ),
            )
            val rightTravel = distance(
                normalizedRelative(
                    current.rightWrist,
                    current.shoulderCenter,
                    current.shoulderWidth,
                ),
                normalizedRelative(
                    previous.rightWrist,
                    previous.shoulderCenter,
                    previous.shoulderWidth,
                ),
            )
            val leftExtension = current.leftElbowAngle - previous.leftElbowAngle
            val rightExtension = current.rightElbowAngle - previous.rightElbowAngle
            val leftActivation = min(
                leftTravel / config.castMinimumWristTravelShoulderWidths,
                leftExtension / config.castMinimumElbowExtensionDelta,
            )
            val rightActivation = min(
                rightTravel / config.castMinimumWristTravelShoulderWidths,
                rightExtension / config.castMinimumElbowExtensionDelta,
            )
            best = max(best, max(leftActivation, rightActivation))
        }
        return best.coerceIn(0f, 1f)
    }

    private fun hookActivation(current: Features): Float {
        var bestRise = 0f
        history.forEach { previous ->
            if (current.timestampNs - previous.timestampNs > config.hookWindowNs) return@forEach
            val leftRise = normalizedRelative(
                previous.leftWrist,
                previous.shoulderCenter,
                previous.torsoLength,
            ).y - normalizedRelative(
                current.leftWrist,
                current.shoulderCenter,
                current.torsoLength,
            ).y
            val rightRise = normalizedRelative(
                previous.rightWrist,
                previous.shoulderCenter,
                previous.torsoLength,
            ).y - normalizedRelative(
                current.rightWrist,
                current.shoulderCenter,
                current.torsoLength,
            ).y
            bestRise = max(bestRise, max(leftRise, rightRise))
        }
        return (bestRise / config.hookMinimumRiseTorsoLengths).coerceIn(0f, 1f)
    }

    private fun reelActivation(current: Features): Float {
        val leftRadius = distance(current.leftWrist, current.chestCenter)
        val rightRadius = distance(current.rightWrist, current.chestCenter)
        val wrist = if (leftRadius >= rightRadius) current.leftWrist else current.rightWrist
        val radius = max(leftRadius, rightRadius) / current.shoulderWidth
        val elbowAngle = if (leftRadius >= rightRadius) {
            current.leftElbowAngle
        } else {
            current.rightElbowAngle
        }
        val previousReelTimestamp = reelLastTimestampNs
        if (
            radius < config.reelMinimumRadiusShoulderWidths ||
            previousReelTimestamp == null ||
            current.timestampNs - previousReelTimestamp > config.reelMaximumFrameGapNs
        ) {
            resetReel()
            reelLastAngle = atan2(wrist.y - current.chestCenter.y, wrist.x - current.chestCenter.x)
            reelLastTimestampNs = current.timestampNs
            reelMinimumElbowAngle = elbowAngle
            reelMaximumElbowAngle = elbowAngle
            return 0f
        }

        val angle = atan2(wrist.y - current.chestCenter.y, wrist.x - current.chestCenter.x)
        val delta = normalizedAngleDelta(requireNotNull(reelLastAngle), angle)
        reelLastAngle = angle
        reelLastTimestampNs = current.timestampNs
        reelMinimumElbowAngle = min(reelMinimumElbowAngle, elbowAngle)
        reelMaximumElbowAngle = max(reelMaximumElbowAngle, elbowAngle)
        if (abs(delta) > config.reelMaximumStepRadians) {
            resetReel(keepLatest = true)
            return 0f
        }
        if (abs(delta) >= MIN_REEL_DIRECTION_STEP_RADIANS) {
            val direction = if (delta > 0f) 1 else -1
            if (reelDirection != 0 && direction != reelDirection) {
                reelAccumulatedRadians = 0f
                reelMinimumElbowAngle = elbowAngle
                reelMaximumElbowAngle = elbowAngle
            }
            reelDirection = direction
            reelAccumulatedRadians += abs(delta)
        }
        val elbowRange = reelMaximumElbowAngle - reelMinimumElbowAngle
        val activation = min(
            reelAccumulatedRadians / config.reelMinimumAccumulatedRadians,
            elbowRange / config.reelMinimumElbowAngleRangeRadians,
        ).coerceIn(0f, 1f)
        if (activation >= 1f) resetReel(keepLatest = true)
        return activation
    }

    private fun netActivation(current: Features): Float {
        var best = 0f
        history.forEach { previous ->
            if (current.timestampNs - previous.timestampNs > config.netWindowNs) return@forEach
            val leftRise = normalizedRelative(
                previous.leftWrist,
                previous.shoulderCenter,
                previous.torsoLength,
            ).y - normalizedRelative(
                current.leftWrist,
                current.shoulderCenter,
                current.torsoLength,
            ).y
            val rightRise = normalizedRelative(
                previous.rightWrist,
                previous.shoulderCenter,
                previous.torsoLength,
            ).y - normalizedRelative(
                current.rightWrist,
                current.shoulderCenter,
                current.torsoLength,
            ).y
            val bothHandsRise = min(leftRise, rightRise)
            best = max(best, bothHandsRise / config.netMinimumRiseTorsoLengths)
        }
        return best.coerceIn(0f, 1f)
    }

    private fun FishingPoseSample.toFeatures(): Features? {
        fun point(index: Int): Point = landmarks[index].let { Point(it.x, it.y) }
        val leftShoulder = point(FishingPoseLandmarkIndex.LEFT_SHOULDER)
        val rightShoulder = point(FishingPoseLandmarkIndex.RIGHT_SHOULDER)
        val leftElbow = point(FishingPoseLandmarkIndex.LEFT_ELBOW)
        val rightElbow = point(FishingPoseLandmarkIndex.RIGHT_ELBOW)
        val leftWrist = point(FishingPoseLandmarkIndex.LEFT_WRIST)
        val rightWrist = point(FishingPoseLandmarkIndex.RIGHT_WRIST)
        val leftHip = point(FishingPoseLandmarkIndex.LEFT_HIP)
        val rightHip = point(FishingPoseLandmarkIndex.RIGHT_HIP)
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val pelvisCenter = midpoint(leftHip, rightHip)
        val chestCenter = midpoint(shoulderCenter, pelvisCenter)
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        val torsoLength = distance(shoulderCenter, pelvisCenter)
        if (shoulderWidth < config.minimumShoulderWidth || torsoLength < config.minimumTorsoLength) {
            return null
        }
        val required = intArrayOf(
            FishingPoseLandmarkIndex.LEFT_SHOULDER,
            FishingPoseLandmarkIndex.RIGHT_SHOULDER,
            FishingPoseLandmarkIndex.LEFT_ELBOW,
            FishingPoseLandmarkIndex.RIGHT_ELBOW,
            FishingPoseLandmarkIndex.LEFT_WRIST,
            FishingPoseLandmarkIndex.RIGHT_WRIST,
            FishingPoseLandmarkIndex.LEFT_HIP,
            FishingPoseLandmarkIndex.RIGHT_HIP,
        )
        val confidence = required.minOf { landmarks[it].confidence }
        return Features(
            timestampNs = timestampNs,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftElbow = leftElbow,
            rightElbow = rightElbow,
            leftWrist = leftWrist,
            rightWrist = rightWrist,
            shoulderCenter = shoulderCenter,
            pelvisCenter = pelvisCenter,
            chestCenter = chestCenter,
            shoulderWidth = shoulderWidth,
            torsoLength = torsoLength,
            leftElbowAngle = jointAngle(leftShoulder, leftElbow, leftWrist),
            rightElbowAngle = jointAngle(rightShoulder, rightElbow, rightWrist),
            confidence = confidence,
        )
    }

    private fun FishingPoseSample.isValid(): Boolean =
        timestampNs >= 0L &&
            calibrationRevision >= 0 &&
            landmarks.size == FishingPoseLandmarkIndex.LANDMARK_COUNT &&
            landmarks.all { point ->
                point.x.isFinite() && point.y.isFinite() && point.z.isFinite() &&
                    point.x in COORDINATE_MIN..COORDINATE_MAX &&
                    point.y in COORDINATE_MIN..COORDINATE_MAX &&
                    point.z in DEPTH_MIN..DEPTH_MAX &&
                    point.confidence.isUnit()
            }

    private fun FishingPoseSample.privateSnapshotOrNull(): FishingPoseSample? = try {
        val copied = ArrayList<FishingPosePoint>(FishingPoseLandmarkIndex.LANDMARK_COUNT)
        val iterator = landmarks.iterator()
        while (iterator.hasNext()) {
            if (copied.size >= FishingPoseLandmarkIndex.LANDMARK_COUNT) return null
            val point = iterator.next()
            copied += FishingPosePoint(point.x, point.y, point.z, point.confidence)
        }
        if (copied.size != FishingPoseLandmarkIndex.LANDMARK_COUNT) return null
        FishingPoseSample(timestampNs, calibrationRevision, copied)
    } catch (_: RuntimeException) {
        null
    }

    private fun trimHistory(nowNs: Long) {
        val maximumWindow = max(max(config.castWindowNs, config.hookWindowNs), config.netWindowNs)
        while (history.isNotEmpty() && nowNs - history.first.timestampNs > maximumWindow) {
            history.removeFirst()
        }
    }

    private fun resetTemporalState(keepClock: Boolean = false) {
        history.clear()
        resetReel()
        if (!keepClock) {
            calibrationRevision = null
            lastTimestampNs = null
        }
    }

    private fun resetReel(keepLatest: Boolean = false) {
        reelAccumulatedRadians = 0f
        reelDirection = 0
        reelMinimumElbowAngle = Float.POSITIVE_INFINITY
        reelMaximumElbowAngle = Float.NEGATIVE_INFINITY
        if (!keepLatest) {
            reelLastAngle = null
            reelLastTimestampNs = null
        }
    }

    private fun zeroSignals(): List<FishingGestureCandidateSignal> =
        FishingGestureCandidate.entries.map { signal(it, 0f, 0f) }

    private fun signal(
        candidate: FishingGestureCandidate,
        activation: Float,
        confidence: Float,
    ): FishingGestureCandidateSignal = FishingGestureCandidateSignal(
        candidate = candidate,
        activation = activation.coerceIn(0f, 1f),
        confidence = confidence.coerceIn(0f, 1f),
    )

    private fun midpoint(first: Point, second: Point): Point = Point(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f,
    )

    private fun relative(point: Point, origin: Point): Point = Point(
        x = point.x - origin.x,
        y = point.y - origin.y,
    )

    private fun normalizedRelative(point: Point, origin: Point, scale: Float): Point = Point(
        x = (point.x - origin.x) / scale,
        y = (point.y - origin.y) / scale,
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

    private fun normalizedAngleDelta(previous: Float, current: Float): Float {
        var delta = current - previous
        while (delta > PI) delta -= (2.0 * PI).toFloat()
        while (delta < -PI) delta += (2.0 * PI).toFloat()
        return delta
    }

    private fun Float.isUnit(): Boolean = isFinite() && this in 0f..1f

    private companion object {
        const val COORDINATE_MIN = -1f
        const val COORDINATE_MAX = 2f
        const val DEPTH_MIN = -10f
        const val DEPTH_MAX = 10f
        const val MIN_VECTOR_LENGTH = 1e-8
        const val MIN_REEL_DIRECTION_STEP_RADIANS = 0.035f
    }
}
