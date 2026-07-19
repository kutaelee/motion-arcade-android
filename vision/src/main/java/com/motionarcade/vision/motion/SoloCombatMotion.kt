package com.motionarcade.vision.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.pose.LIVE_POSE_MAX_POSES
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import java.util.Collections
import java.util.LinkedHashMap

/** Coordinate-free P1-only combat signals for a one-person camera session. */
class SoloCombatMotionFrame(
    val sessionGeneration: Long,
    val revision: Long,
    val sourceTimestampNs: Long,
    val poseCount: Int,
    val usableForSolo: Boolean,
    val continuityBoundary: LivePoseContinuityBoundary,
    val configId: String,
    val calibrationRevision: Int,
    val profile: DualPlayerCombatProfile,
    samples: Collection<MotionSignalSample>,
) {
    val samples: List<MotionSignalSample> = Collections.unmodifiableList(
        samples.map { sample ->
            sample.copy(metadata = Collections.unmodifiableMap(LinkedHashMap(sample.metadata)))
        },
    )

    init {
        require(sessionGeneration > 0L)
        require(revision > 0L)
        require(sourceTimestampNs >= 0L)
        require(poseCount in 0..LIVE_POSE_MAX_POSES)
        require(configId.matches(CONFIG_ID_PATTERN))
        require(calibrationRevision >= 0)
        require(profile.supportsSoloCombat)
        require(samples.size == profile.requiredMotionTypes.size)
        require(samples.map(MotionSignalSample::type).toSet() == profile.requiredMotionTypes)
        require(samples.all { sample ->
            sample.playerId == PlayerId.P1 &&
                sample.timestampNs == sourceTimestampNs &&
                sample.calibrationRevision == calibrationRevision &&
                sample.source == InputSource.MOTION &&
                sample.activation.isFinite() && sample.activation in 0f..1f &&
                sample.quality.isFinite() && sample.quality in 0f..1f &&
                sample.confidence.isFinite() && sample.confidence in 0f..1f &&
                sample.metadata.isEmpty()
        })
        require(!usableForSolo || poseCount == 1)
        require(!usableForSolo || continuityBoundary == LivePoseContinuityBoundary.CONTIGUOUS)
    }

    internal fun asContinuityFence(): SoloCombatMotionFrame = SoloCombatMotionFrame(
        sessionGeneration = sessionGeneration,
        revision = revision,
        sourceTimestampNs = sourceTimestampNs,
        poseCount = poseCount,
        usableForSolo = false,
        continuityBoundary = LivePoseContinuityBoundary.RESET_REVISION_GAP,
        configId = configId,
        calibrationRevision = calibrationRevision,
        profile = profile,
        samples = samples.map { sample ->
            sample.copy(activation = 0f, quality = 0f, confidence = 0f, metadata = emptyMap())
        },
    )

    private companion object {
        val CONFIG_ID_PATTERN = Regex("^[a-z][a-z0-9-]{2,63}$")
    }
}

/** Profiles supported by the coordinate-free, exactly-one-person combat pipeline. */
val DualPlayerCombatProfile.supportsSoloCombat: Boolean
    get() = this == DualPlayerCombatProfile.BOXING || this == DualPlayerCombatProfile.MONSTER

fun interface SoloCombatMotionFrameSink {
    fun onSoloCombatMotionFrame(frame: SoloCombatMotionFrame)

    companion object {
        val NONE = SoloCombatMotionFrameSink { }
    }
}
