package com.motionarcade.vision.pose

import java.util.Collections

internal fun validLivePoseSessionResult(
    poseCount: Int,
    taskTimestampMs: Long,
): LivePoseSessionResult =
    LivePoseSessionResult(
        taskTimestampMs,
        List(poseCount.coerceAtLeast(0)) { poseIndex ->
            List(LIVE_POSE_LANDMARK_COUNT) { landmarkIndex ->
                LivePoseSessionLandmark(
                    (landmarkIndex + 1) / 40.0f,
                    (poseIndex + 1) / 4.0f,
                    0.0f,
                    0.9f,
                    0.8f,
                )
            }
        },
    )

internal fun LivePoseSessionCallbacks.emitValidLivePoseResult(
    poseCount: Int,
    taskTimestampMs: Long,
) {
    onResult(validLivePoseSessionResult(poseCount, taskTimestampMs))
}

internal class CapturingObservationDispatcher(
    private val generation: Long,
) : LivePoseObservationDispatcher {
    val frames = Collections.synchronizedList(mutableListOf<LivePoseObservationFrame>())
    @Volatile private var accepting = true

    override fun offer(frame: LivePoseObservationFrame): Boolean {
        if (!accepting || frame.sessionGeneration != generation) return false
        frames += frame
        return true
    }

    override fun revoke(reason: LivePoseObservationTerminalReason) {
        accepting = false
    }

    override fun closeAndAwait(reason: LivePoseObservationTerminalReason) {
        accepting = false
    }

    override fun snapshot(): LivePoseObservationDispatcherSnapshot =
        LivePoseObservationDispatcherSnapshot(
            accepting = accepting,
            pendingCount = 0,
            inFlightCount = 0,
            maximumObservedPendingCount = if (frames.isEmpty()) 0 else 1,
            acceptedCount = frames.size.toLong(),
            droppedCount = 0L,
            appliedCount = frames.size.toLong(),
        )

    fun capturedPoseCounts(): List<Int> = frames.map { it.poses.size }

    fun capturedSourceTimestampsNs(): List<Long> = frames.map { it.sourceTimestampNs }

    fun capturedTaskTimestampsMs(): List<Long> = frames.map { it.taskTimestampMs }
}
