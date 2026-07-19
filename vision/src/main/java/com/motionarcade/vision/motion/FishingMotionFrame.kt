package com.motionarcade.vision.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.pose.LIVE_POSE_MAX_POSES
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Coordinate-free fishing recognition output. Raw MediaPipe landmarks remain package-private in
 * :vision; product callers receive only the complete scalar P1 signal frame and continuity facts.
 */
class FishingMotionFrame(
    val sessionGeneration: Long,
    val revision: Long,
    val sourceTimestampNs: Long,
    val poseCount: Int,
    val usableForSolo: Boolean,
    val continuityBoundary: LivePoseContinuityBoundary,
    val configId: String,
    val calibrationRevision: Int,
    samples: Collection<MotionSignalSample>,
) {
    val samples: List<MotionSignalSample> = Collections.unmodifiableList(
        samples.map { sample ->
            sample.copy(
                metadata = Collections.unmodifiableMap(LinkedHashMap(sample.metadata)),
            )
        },
    )

    init {
        require(sessionGeneration > 0L)
        require(revision > 0L)
        require(sourceTimestampNs >= 0L)
        require(poseCount in 0..LIVE_POSE_MAX_POSES)
        require(configId.matches(CONFIG_ID_PATTERN))
        require(calibrationRevision > 0)
        require(this.samples.size == FISHING_MOTION_TYPES.size)
        require(this.samples.map { it.type }.toSet() == FISHING_MOTION_TYPES)
        require(this.samples.all { sample ->
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

    override fun toString(): String =
        "FishingMotionFrame(generation=$sessionGeneration, revision=$revision, " +
            "poseCount=$poseCount, usableForSolo=$usableForSolo, " +
            "boundary=$continuityBoundary, configId=$configId, signalCount=${samples.size})"

    internal fun asContinuityFence(): FishingMotionFrame = FishingMotionFrame(
        sessionGeneration = sessionGeneration,
        revision = revision,
        sourceTimestampNs = sourceTimestampNs,
        poseCount = poseCount,
        usableForSolo = false,
        continuityBoundary = continuityBoundary,
        configId = configId,
        calibrationRevision = calibrationRevision,
        samples = samples.map { sample ->
            sample.copy(activation = 0f, quality = 0f, confidence = 0f, metadata = emptyMap())
        },
    )

    private companion object {
        val CONFIG_ID_PATTERN = Regex("^[a-z][a-z0-9-]{2,63}$")
    }
}

fun interface FishingMotionFrameSink {
    fun onFishingMotionFrame(frame: FishingMotionFrame)

    companion object {
        val NONE = FishingMotionFrameSink { }
    }
}
