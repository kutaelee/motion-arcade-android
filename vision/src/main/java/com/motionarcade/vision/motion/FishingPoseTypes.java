package com.motionarcade.vision.pose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FishingPosePoint {
    private final float x;
    private final float y;
    private final float z;
    private final float confidence;

    FishingPosePoint(float x, float y, float z, float confidence) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.confidence = confidence;
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

    float getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return "FishingPosePoint(redacted)";
    }
}

final class FishingPoseSample {
    private final long timestampNs;
    private final int calibrationRevision;
    private final List<FishingPosePoint> landmarks;

    FishingPoseSample(
            long timestampNs,
            int calibrationRevision,
            List<FishingPosePoint> landmarks) {
        this.timestampNs = timestampNs;
        this.calibrationRevision = calibrationRevision;
        this.landmarks = immutableLandmarks(landmarks);
    }

    long getTimestampNs() {
        return timestampNs;
    }

    int getCalibrationRevision() {
        return calibrationRevision;
    }

    List<FishingPosePoint> getLandmarks() {
        return landmarks;
    }

    @Override
    public String toString() {
        return "FishingPoseSample(timestampNs=" + timestampNs
                + ", calibrationRevision=" + calibrationRevision
                + ", landmarks=redacted[count=" + landmarks.size() + "])";
    }

    private static List<FishingPosePoint> immutableLandmarks(List<FishingPosePoint> source) {
        if (source == null) {
            throw new NullPointerException("landmarks");
        }
        if (source.size() != LivePoseObservationKt.LIVE_POSE_LANDMARK_COUNT) {
            throw new IllegalArgumentException("unexpected landmark count");
        }
        ArrayList<FishingPosePoint> copy =
                new ArrayList<>(LivePoseObservationKt.LIVE_POSE_LANDMARK_COUNT);
        for (FishingPosePoint point : source) {
            if (point == null) {
                throw new NullPointerException("landmark");
            }
            copy.add(new FishingPosePoint(
                    point.getX(),
                    point.getY(),
                    point.getZ(),
                    point.getConfidence()));
        }
        return Collections.unmodifiableList(copy);
    }
}
