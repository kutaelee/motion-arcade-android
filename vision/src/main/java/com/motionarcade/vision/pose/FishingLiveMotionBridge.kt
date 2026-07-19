package com.motionarcade.vision.pose

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.FishingMotionConfig
import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.motion.FishingMotionFrameSink
import kotlin.math.min

/** Converts private raw observations into one immutable, coordinate-free fishing signal frame. */
internal class FishingLiveMotionBridge(
    private val config: FishingMotionConfig,
    private val sink: FishingMotionFrameSink,
) : LivePoseObservationSink {
    private val extractor = FishingPoseSignalExtractor(config.poseConfig)

    fun resetAfterDeliveryGap() {
        extractor.reset()
    }

    override fun onPoseObservation(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
    ) {
        if (
            continuity.boundary != LivePoseContinuityBoundary.CONTIGUOUS ||
            frame.poses.size != 1
        ) {
            extractor.reset()
            emit(frame, continuity, usable = false, signals = zeroSignals())
            return
        }

        val pose = frame.poses.single()
        val sample = FishingPoseSample(
            frame.sourceTimestampNs,
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
        when (val result = extractor.process(sample)) {
            is FishingPoseSignalResult.Accepted -> {
                val usable = !result.temporalReset
                emit(
                    frame = frame,
                    continuity = continuity,
                    usable = usable,
                    signals = if (usable) result.signals else zeroSignals(),
                )
            }
            is FishingPoseSignalResult.Rejected -> {
                extractor.reset()
                emit(frame, continuity, usable = false, signals = zeroSignals())
            }
        }
    }

    private fun emit(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
        usable: Boolean,
        signals: List<FishingGestureCandidateSignal>,
    ) {
        val byCandidate = signals.associateBy(FishingGestureCandidateSignal::candidate)
        check(byCandidate.size == CANDIDATE_TO_TYPE.size)
        sink.onFishingMotionFrame(
            FishingMotionFrame(
                sessionGeneration = frame.sessionGeneration,
                revision = frame.revision,
                sourceTimestampNs = frame.sourceTimestampNs,
                poseCount = frame.poses.size,
                usableForSolo = usable,
                continuityBoundary = continuity.boundary,
                configId = config.configId,
                calibrationRevision = config.calibrationRevision,
                samples = CANDIDATE_TO_TYPE.map { (candidate, type) ->
                    val signal = requireNotNull(byCandidate[candidate])
                    MotionSignalSample(
                        playerId = PlayerId.P1,
                        type = type,
                        timestampNs = frame.sourceTimestampNs,
                        activation = signal.activation,
                        quality = signal.activation,
                        confidence = signal.confidence,
                        calibrationRevision = config.calibrationRevision,
                        source = InputSource.MOTION,
                    )
                },
            ),
        )
    }

    private fun zeroSignals(): List<FishingGestureCandidateSignal> =
        FishingGestureCandidate.entries.map { candidate ->
            FishingGestureCandidateSignal(candidate, activation = 0f, confidence = 0f)
        }

    private companion object {
        val CANDIDATE_TO_TYPE = linkedMapOf(
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
