package com.motionarcade.vision.pose

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.vision.motion.FishingMotionConfig
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.motion.FishingMotionFrameSink
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingLiveMotionBridgeTest {
    private val config: FishingMotionConfig by lazy {
        Files.newInputStream(bundledConfigPath()).use(FishingMotionConfigLoader::load)
    }

    @Test
    fun resetThenContiguousSoloPoseEmitsCoordinateFreeCompleteP1Frame() {
        val frames = mutableListOf<FishingMotionFrame>()
        val bridge = FishingLiveMotionBridge(config, FishingMotionFrameSink(frames::add))

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 100L, poses = listOf(validPose())),
            decision(LivePoseContinuityBoundary.RESET_GENERATION, epoch = 1L),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 101L, poses = listOf(validPose())),
            decision(LivePoseContinuityBoundary.CONTIGUOUS, epoch = 1L),
        )

        assertEquals(2, frames.size)
        assertFalse(frames[0].usableForSolo)
        assertTrue(frames[0].samples.all { it.activation == 0f && it.confidence == 0f })
        val active = frames[1]
        assertTrue(active.usableForSolo)
        assertEquals(1, active.poseCount)
        assertEquals(101L, active.sourceTimestampNs)
        assertEquals(EXPECTED_TYPES, active.samples.map { it.type }.toSet())
        assertEquals(7, active.samples.size)
        assertTrue(active.samples.all { sample ->
            sample.playerId == PlayerId.P1 &&
                sample.source == InputSource.MOTION &&
                sample.timestampNs == 101L &&
                sample.calibrationRevision == config.calibrationRevision &&
                sample.metadata.isEmpty()
        })
        assertFalse(active.toString().contains("landmark", ignoreCase = true))
        assertThrows(UnsupportedOperationException::class.java) {
            (active.samples as MutableList).clear()
        }
    }

    @Test
    fun zeroTwoAndLowConfidencePosesNeverSelectOrEmitPositiveSoloSignals() {
        val frames = mutableListOf<FishingMotionFrame>()
        val bridge = FishingLiveMotionBridge(config, FishingMotionFrameSink(frames::add))

        bridge.onPoseObservation(
            frame(1L, 10L, listOf(validPose())),
            decision(LivePoseContinuityBoundary.RESET_GENERATION, 1L),
        )
        bridge.onPoseObservation(
            frame(2L, 11L, emptyList()),
            decision(LivePoseContinuityBoundary.CONTIGUOUS, 1L),
        )
        bridge.onPoseObservation(
            frame(3L, 12L, listOf(validPose(), validPose())),
            decision(LivePoseContinuityBoundary.CONTIGUOUS, 1L),
        )
        bridge.onPoseObservation(
            frame(4L, 13L, listOf(validPose(confidence = 0.2f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS, 1L),
        )

        assertEquals(listOf(1, 0, 2, 1), frames.map { it.poseCount })
        assertTrue(frames.all { frame ->
            !frame.usableForSolo &&
                frame.samples.size == 7 &&
                frame.samples.all { it.activation == 0f && it.quality == 0f }
        })
    }

    @Test
    fun revisionBoundaryAndExplicitDeliveryGapResetTemporalHistory() {
        val frames = mutableListOf<FishingMotionFrame>()
        val bridge = FishingLiveMotionBridge(config, FishingMotionFrameSink(frames::add))

        bridge.onPoseObservation(
            frame(1L, 20L, listOf(validPose())),
            decision(LivePoseContinuityBoundary.RESET_GENERATION, 1L),
        )
        bridge.onPoseObservation(
            frame(3L, 30L, listOf(validPose())),
            decision(LivePoseContinuityBoundary.RESET_REVISION_GAP, 2L),
        )
        bridge.resetAfterDeliveryGap()
        bridge.onPoseObservation(
            frame(4L, 40L, listOf(validPose())),
            decision(LivePoseContinuityBoundary.CONTIGUOUS, 2L),
        )

        assertFalse(frames[0].usableForSolo)
        assertFalse(frames[1].usableForSolo)
        assertTrue(frames[2].usableForSolo)
        assertTrue(frames[2].samples.all { it.timestampNs == 40L })
    }

    private fun decision(
        boundary: LivePoseContinuityBoundary,
        epoch: Long,
    ): LivePoseContinuityDecision = LivePoseContinuityDecision(boundary, epoch)

    private fun frame(
        revision: Long,
        timestampNs: Long,
        poses: List<LivePoseObservation>,
    ): LivePoseObservationFrame = LivePoseObservationFrame(
        TEST_GENERATION,
        revision,
        timestampNs,
        revision,
        poses,
    )

    private fun validPose(confidence: Float = 0.95f): LivePoseObservation {
        val points = MutableList(LIVE_POSE_LANDMARK_COUNT) {
            LivePoseLandmark(0.5f, 0.5f, 0f, confidence, confidence)
        }
        fun put(index: Int, x: Float, y: Float) {
            points[index] = LivePoseLandmark(x, y, 0f, confidence, confidence)
        }
        put(FishingPoseLandmarkIndex.LEFT_SHOULDER, 0.40f, 0.40f)
        put(FishingPoseLandmarkIndex.RIGHT_SHOULDER, 0.60f, 0.40f)
        put(FishingPoseLandmarkIndex.LEFT_ELBOW, 0.40f, 0.50f)
        put(FishingPoseLandmarkIndex.RIGHT_ELBOW, 0.60f, 0.50f)
        put(FishingPoseLandmarkIndex.LEFT_WRIST, 0.45f, 0.55f)
        put(FishingPoseLandmarkIndex.RIGHT_WRIST, 0.55f, 0.55f)
        put(FishingPoseLandmarkIndex.LEFT_HIP, 0.43f, 0.70f)
        put(FishingPoseLandmarkIndex.RIGHT_HIP, 0.57f, 0.70f)
        return LivePoseObservation(points)
    }

    private fun bundledConfigPath(): Path {
        val root = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(
            root.resolve("src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
            root.resolve("vision/src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Bundled fishing config was not found from $root")
    }

    private companion object {
        const val TEST_GENERATION = 7L
        val EXPECTED_TYPES = setOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )
    }
}
