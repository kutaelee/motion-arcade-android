package com.motionarcade.vision.pose

import java.util.ArrayList

internal const val LIVE_POSE_LANDMARK_COUNT: Int = 33

/*
 * MediaPipe normalized image coordinates can legitimately extend just outside the image. One
 * complete image width of overscan on either side preserves that signal without allowing corrupt
 * or adversarial magnitudes into the motion window. Values are rejected, never clamped.
 */
internal const val LIVE_POSE_IMAGE_COORDINATE_MIN: Float = -1.0f
internal const val LIVE_POSE_IMAGE_COORDINATE_MAX: Float = 2.0f

/* Z is expressed on the same approximate scale as X, but is not an image-plane coordinate. */
internal const val LIVE_POSE_DEPTH_MIN: Float = -10.0f
internal const val LIVE_POSE_DEPTH_MAX: Float = 10.0f

internal fun LivePoseSessionResult.toObservationFrameOrNull(
    sessionGeneration: Long,
    frameRevision: Long,
    sourceTimestampNs: Long,
): LivePoseObservationFrame? {
    if (taskTimestampMs < 0L || poses.size > LIVE_POSE_MAX_POSES) return null
    val copiedPoses = ArrayList<LivePoseObservation>(poses.size)
    for (pose in poses) {
        if (pose.size != LIVE_POSE_LANDMARK_COUNT) return null
        val copiedLandmarks = ArrayList<LivePoseLandmark>(LIVE_POSE_LANDMARK_COUNT)
        for (landmark in pose) {
            val copied =
                try {
                    LivePoseLandmark(
                        landmark.x,
                        landmark.y,
                        landmark.z,
                        landmark.visibility,
                        landmark.presence,
                    )
                } catch (_: IllegalArgumentException) {
                    return null
                }
            copiedLandmarks += copied
        }
        copiedPoses += LivePoseObservation(copiedLandmarks)
    }
    return try {
        LivePoseObservationFrame(
            sessionGeneration,
            frameRevision,
            sourceTimestampNs,
            taskTimestampMs,
            copiedPoses,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
