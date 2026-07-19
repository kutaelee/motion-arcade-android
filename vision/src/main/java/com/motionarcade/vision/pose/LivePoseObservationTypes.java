package com.motionarcade.vision.pose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM package-private raw pose types. Keeping every type in this file non-public is deliberate:
 * product callers may consume {@link LivePoseSemanticSummary}, but cannot name, construct, or
 * retain raw MediaPipe-derived landmarks from another package.
 */
final class LivePoseLandmark {
    private final float x;
    private final float y;
    private final float z;
    private final Float visibility;
    private final Float presence;

    LivePoseLandmark(float x, float y, float z, Float visibility, Float presence) {
        if (!Float.isFinite(x)
                || x < LivePoseObservationKt.LIVE_POSE_IMAGE_COORDINATE_MIN
                || x > LivePoseObservationKt.LIVE_POSE_IMAGE_COORDINATE_MAX) {
            throw new IllegalArgumentException("invalid x coordinate");
        }
        if (!Float.isFinite(y)
                || y < LivePoseObservationKt.LIVE_POSE_IMAGE_COORDINATE_MIN
                || y > LivePoseObservationKt.LIVE_POSE_IMAGE_COORDINATE_MAX) {
            throw new IllegalArgumentException("invalid y coordinate");
        }
        if (!Float.isFinite(z)
                || z < LivePoseObservationKt.LIVE_POSE_DEPTH_MIN
                || z > LivePoseObservationKt.LIVE_POSE_DEPTH_MAX) {
            throw new IllegalArgumentException("invalid depth coordinate");
        }
        requireOptionalScore(visibility, "visibility");
        requireOptionalScore(presence, "presence");
        this.x = x;
        this.y = y;
        this.z = z;
        this.visibility = visibility;
        this.presence = presence;
    }

    float getX() {
        return x;
    }

    float getY() {
        return y;
    }

    float getZ() {
        return z;
    }

    Float getVisibility() {
        return visibility;
    }

    Float getPresence() {
        return presence;
    }

    @Override
    public String toString() {
        return "LivePoseLandmark(redacted)";
    }

    private static void requireOptionalScore(Float score, String field) {
        if (score != null && (!Float.isFinite(score) || score < 0.0f || score > 1.0f)) {
            throw new IllegalArgumentException("invalid " + field + " score");
        }
    }
}

final class LivePoseObservation {
    private final List<LivePoseLandmark> landmarks;

    LivePoseObservation(List<LivePoseLandmark> landmarks) {
        if (landmarks.size() != LivePoseObservationKt.LIVE_POSE_LANDMARK_COUNT) {
            throw new IllegalArgumentException("unexpected landmark count");
        }
        ArrayList<LivePoseLandmark> copy = new ArrayList<>(landmarks.size());
        for (LivePoseLandmark landmark : landmarks) {
            copy.add(
                    new LivePoseLandmark(
                            landmark.getX(),
                            landmark.getY(),
                            landmark.getZ(),
                            landmark.getVisibility(),
                            landmark.getPresence()));
        }
        this.landmarks = Collections.unmodifiableList(copy);
    }

    List<LivePoseLandmark> getLandmarks() {
        return landmarks;
    }

    @Override
    public String toString() {
        return "LivePoseObservation(landmarkCount="
                + LivePoseObservationKt.LIVE_POSE_LANDMARK_COUNT
                + ")";
    }
}

final class LivePoseObservationFrame {
    private final long sessionGeneration;
    private final long revision;
    private final long sourceTimestampNs;
    private final long taskTimestampMs;
    private final LivePoseCoordinateSpace coordinateSpace;
    private final List<LivePoseObservation> poses;

    LivePoseObservationFrame(
            long sessionGeneration,
            long revision,
            long sourceTimestampNs,
            long taskTimestampMs,
            List<LivePoseObservation> poses) {
        if (sessionGeneration <= 0L) {
            throw new IllegalArgumentException("session generation must be positive");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (sourceTimestampNs < 0L || taskTimestampMs < 0L) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        if (poses.size() > LivePosePipelineKt.LIVE_POSE_MAX_POSES) {
            throw new IllegalArgumentException("unexpected pose count");
        }
        ArrayList<LivePoseObservation> copy = new ArrayList<>(poses.size());
        for (LivePoseObservation pose : poses) {
            copy.add(new LivePoseObservation(pose.getLandmarks()));
        }
        this.sessionGeneration = sessionGeneration;
        this.revision = revision;
        this.sourceTimestampNs = sourceTimestampNs;
        this.taskTimestampMs = taskTimestampMs;
        this.coordinateSpace = LivePoseCoordinateSpace.ROTATED_ANALYSIS_NORMALIZED_V1;
        this.poses = Collections.unmodifiableList(copy);
    }

    long getSessionGeneration() {
        return sessionGeneration;
    }

    long getRevision() {
        return revision;
    }

    long getSourceTimestampNs() {
        return sourceTimestampNs;
    }

    long getTaskTimestampMs() {
        return taskTimestampMs;
    }

    LivePoseCoordinateSpace getCoordinateSpace() {
        return coordinateSpace;
    }

    List<LivePoseObservation> getPoses() {
        return poses;
    }

    @Override
    public String toString() {
        return "LivePoseObservationFrame(generation="
                + sessionGeneration
                + ", revision="
                + revision
                + ", poseCount="
                + poses.size()
                + ")";
    }
}

/** Callback DTO copied synchronously while the MediaPipe callback still owns its result. */
final class LivePoseSessionLandmark {
    private final float x;
    private final float y;
    private final float z;
    private final Float visibility;
    private final Float presence;

    LivePoseSessionLandmark(float x, float y, float z, Float visibility, Float presence) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.visibility = visibility;
        this.presence = presence;
    }

    float getX() {
        return x;
    }

    float getY() {
        return y;
    }

    float getZ() {
        return z;
    }

    Float getVisibility() {
        return visibility;
    }

    Float getPresence() {
        return presence;
    }

    @Override
    public String toString() {
        return "LivePoseSessionLandmark(redacted)";
    }
}

/** Callback result with no MediaPipe or MPImage type in its JVM surface. */
final class LivePoseSessionResult {
    private final long taskTimestampMs;
    private final List<List<LivePoseSessionLandmark>> poses;

    LivePoseSessionResult(long taskTimestampMs, List<List<LivePoseSessionLandmark>> poses) {
        ArrayList<List<LivePoseSessionLandmark>> poseCopy = new ArrayList<>(poses.size());
        for (List<LivePoseSessionLandmark> pose : poses) {
            ArrayList<LivePoseSessionLandmark> landmarkCopy = new ArrayList<>(pose.size());
            for (LivePoseSessionLandmark landmark : pose) {
                landmarkCopy.add(
                        new LivePoseSessionLandmark(
                                landmark.getX(),
                                landmark.getY(),
                                landmark.getZ(),
                                landmark.getVisibility(),
                                landmark.getPresence()));
            }
            poseCopy.add(Collections.unmodifiableList(landmarkCopy));
        }
        this.taskTimestampMs = taskTimestampMs;
        this.poses = Collections.unmodifiableList(poseCopy);
    }

    long getTaskTimestampMs() {
        return taskTimestampMs;
    }

    List<List<LivePoseSessionLandmark>> getPoses() {
        return poses;
    }

    @Override
    public String toString() {
        return "LivePoseSessionResult(poseCount=" + poses.size() + ")";
    }
}
