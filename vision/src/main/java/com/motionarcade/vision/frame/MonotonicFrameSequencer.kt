package com.motionarcade.vision.frame

data class AcceptedFrameTimestamp(
    val frameId: Long,
    val sourceTimestampNs: Long,
)

enum class TimestampRejectionReason {
    NEGATIVE_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    OUT_OF_ORDER_TIMESTAMP,
    FRAME_ID_EXHAUSTED,
}

sealed interface FrameTimestampDecision {
    data class Accepted(val frame: AcceptedFrameTimestamp) : FrameTimestampDecision

    data class Rejected(
        val sourceTimestampNs: Long,
        val reason: TimestampRejectionReason,
    ) : FrameTimestampDecision
}

/** Assigns frame IDs only to strictly monotonic CameraX source timestamps. */
class MonotonicFrameSequencer(
    initialFrameId: Long = 0L,
) {
    private var nextFrameId = initialFrameId
    private var frameIdsExhausted = false
    private var lastAcceptedTimestampNs: Long? = null

    init {
        require(initialFrameId >= 0L) { "initialFrameId must be non-negative" }
    }

    @Synchronized
    fun accept(sourceTimestampNs: Long): FrameTimestampDecision {
        if (sourceTimestampNs < 0L) {
            return rejected(sourceTimestampNs, TimestampRejectionReason.NEGATIVE_TIMESTAMP)
        }
        val previous = lastAcceptedTimestampNs
        if (previous != null && sourceTimestampNs == previous) {
            return rejected(sourceTimestampNs, TimestampRejectionReason.DUPLICATE_TIMESTAMP)
        }
        if (previous != null && sourceTimestampNs < previous) {
            return rejected(sourceTimestampNs, TimestampRejectionReason.OUT_OF_ORDER_TIMESTAMP)
        }
        if (frameIdsExhausted) {
            return rejected(sourceTimestampNs, TimestampRejectionReason.FRAME_ID_EXHAUSTED)
        }

        val accepted = AcceptedFrameTimestamp(nextFrameId, sourceTimestampNs)
        lastAcceptedTimestampNs = sourceTimestampNs
        if (nextFrameId == Long.MAX_VALUE) {
            frameIdsExhausted = true
        } else {
            nextFrameId += 1L
        }
        return FrameTimestampDecision.Accepted(accepted)
    }

    @Synchronized
    fun lastAcceptedTimestampNs(): Long? = lastAcceptedTimestampNs

    private fun rejected(
        sourceTimestampNs: Long,
        reason: TimestampRejectionReason,
    ): FrameTimestampDecision.Rejected = FrameTimestampDecision.Rejected(sourceTimestampNs, reason)
}
