package com.motionarcade.vision.pose

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.motion.SoloCombatMotionFrameSink
import com.motionarcade.vision.motion.requiredMotionTypes
import com.motionarcade.vision.motion.supportsSoloCombat

/** Keeps raw one-person pose coordinates inside :vision and emits only P1 scalar signals. */
internal class SoloCombatMotionBridge(
    private val config: DualPlayerCombatMotionConfig,
    private val sink: SoloCombatMotionFrameSink,
) : LivePoseObservationSink {
    private val extractor: DualPlayerCombatMotionBridge.CombatPoseSignalExtractor

    init {
        require(config.profile.supportsSoloCombat)
        extractor = DualPlayerCombatMotionBridge.CombatPoseSignalExtractor(config, PlayerId.P1)
    }

    fun reset() {
        extractor.reset()
    }

    override fun onPoseObservation(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
    ) {
        if (continuity.boundary != LivePoseContinuityBoundary.CONTIGUOUS || frame.poses.size != 1) {
            reset()
            emit(frame, continuity.boundary, usable = false, zeroSignals(frame.sourceTimestampNs))
            return
        }
        val signals = extractor.process(frame.sourceTimestampNs, frame.poses.single())
        if (signals == null) {
            reset()
            emit(
                frame,
                LivePoseContinuityBoundary.RESET_REVISION_GAP,
                usable = false,
                zeroSignals(frame.sourceTimestampNs),
            )
            return
        }
        emit(
            frame,
            continuity.boundary,
            usable = true,
            config.profile.requiredMotionTypes.map { type ->
                val signal = requireNotNull(signals[type])
                MotionSignalSample(
                    playerId = PlayerId.P1,
                    type = type,
                    timestampNs = signal.timestampNs,
                    activation = signal.activation,
                    quality = signal.activation,
                    confidence = signal.confidence,
                    calibrationRevision = config.calibrationRevision,
                    source = InputSource.MOTION,
                    metadata = emptyMap(),
                )
            },
        )
    }

    private fun emit(
        frame: LivePoseObservationFrame,
        boundary: LivePoseContinuityBoundary,
        usable: Boolean,
        samples: List<MotionSignalSample>,
    ) {
        sink.onSoloCombatMotionFrame(
            SoloCombatMotionFrame(
                sessionGeneration = frame.sessionGeneration,
                revision = frame.revision,
                sourceTimestampNs = frame.sourceTimestampNs,
                poseCount = frame.poses.size,
                usableForSolo = usable,
                continuityBoundary = boundary,
                configId = config.configId,
                calibrationRevision = config.calibrationRevision,
                profile = config.profile,
                samples = samples,
            ),
        )
    }

    private fun zeroSignals(timestampNs: Long): List<MotionSignalSample> =
        config.profile.requiredMotionTypes.map { type ->
            MotionSignalSample(
                playerId = PlayerId.P1,
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
