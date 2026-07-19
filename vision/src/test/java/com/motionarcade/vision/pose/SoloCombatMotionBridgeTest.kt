package com.motionarcade.vision.pose

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.motion.SoloCombatMotionFrameSink
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SoloCombatMotionBridgeTest {
    @Test
    fun onlyOneContiguousValidPoseProducesP1Signals() {
        val frames = mutableListOf<SoloCombatMotionFrame>()
        val bridge = bridge(frames)

        bridge.onPoseObservation(frame(1L, 100L, listOf(pose())), decision(LivePoseContinuityBoundary.RESET_GENERATION))
        bridge.onPoseObservation(frame(2L, 200L, listOf(pose())), decision(LivePoseContinuityBoundary.CONTIGUOUS))

        assertFalse(frames.first().usableForSolo)
        val usable = frames.last()
        assertTrue(usable.usableForSolo)
        assertTrue(usable.samples.isNotEmpty())
        assertEquals(setOf(PlayerId.P1), usable.samples.map { it.playerId }.toSet())
        assertTrue(
            SoloCombatMotionFrame::class.java.declaredFields.none { field ->
                field.name.contains("landmark", ignoreCase = true) ||
                    field.name.contains("track", ignoreCase = true) ||
                    field.name.contains("bounds", ignoreCase = true)
            },
        )
    }

    @Test
    fun boxingProfileProducesOnlyP1BoxingSignals() {
        val frames = mutableListOf<SoloCombatMotionFrame>()
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 2)
        val bridge = SoloCombatMotionBridge(config, SoloCombatMotionFrameSink(frames::add))

        bridge.onPoseObservation(
            frame(1L, 100L, listOf(pose())),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val emitted = frames.single()
        assertTrue(emitted.usableForSolo)
        assertEquals(DualPlayerCombatProfile.BOXING, emitted.profile)
        assertEquals(config.motionTypes, emitted.samples.map { it.type }.toSet())
        assertEquals(setOf(PlayerId.P1), emitted.samples.map { it.playerId }.toSet())
    }

    @Test
    fun zeroOrTwoPosesAndContinuityResetEmitZeroFences() {
        val frames = mutableListOf<SoloCombatMotionFrame>()
        val bridge = bridge(frames)

        bridge.onPoseObservation(frame(1L, 100L, emptyList()), decision(LivePoseContinuityBoundary.RESET_GENERATION))
        bridge.onPoseObservation(frame(2L, 200L, listOf(pose(), pose(0.7f))), decision(LivePoseContinuityBoundary.CONTIGUOUS))
        bridge.onPoseObservation(frame(4L, 300L, listOf(pose())), decision(LivePoseContinuityBoundary.RESET_REVISION_GAP))

        assertEquals(listOf(0, 2, 1), frames.map { it.poseCount })
        assertTrue(frames.none(SoloCombatMotionFrame::usableForSolo))
        assertTrue(frames.all { frame -> frame.samples.all { it.activation == 0f && it.confidence == 0f } })
    }

    @Test
    fun nonMonotonicSourceTimestampResetsAndEmitsZeroFence() {
        val frames = mutableListOf<SoloCombatMotionFrame>()
        val bridge = bridge(frames)
        bridge.onPoseObservation(frame(1L, 100L, listOf(pose())), decision(LivePoseContinuityBoundary.RESET_GENERATION))
        bridge.onPoseObservation(frame(2L, 200L, listOf(pose())), decision(LivePoseContinuityBoundary.CONTIGUOUS))
        bridge.onPoseObservation(frame(3L, 200L, listOf(pose())), decision(LivePoseContinuityBoundary.CONTIGUOUS))

        val fence = frames.last()
        assertFalse(fence.usableForSolo)
        assertEquals(LivePoseContinuityBoundary.RESET_REVISION_GAP, fence.continuityBoundary)
        assertTrue(fence.samples.all { it.activation == 0f && it.quality == 0f && it.confidence == 0f })
    }

    @Test
    fun fishingProfileCannotBeBoundAsSoloCombat() {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val path = listOf(
            workingDirectory.resolve("src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
            workingDirectory.resolve("vision/src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
        ).firstOrNull(Files::isRegularFile) ?: error("Fishing config fixture not found")
        val fishing = Files.newInputStream(path).use(FishingMotionConfigLoader::load)

        assertThrows(IllegalArgumentException::class.java) {
            SoloCombatMotionBridge(
                DualPlayerCombatMotionConfigs.fishing(fishing),
                SoloCombatMotionFrameSink.NONE,
            )
        }
    }

    private fun bridge(frames: MutableList<SoloCombatMotionFrame>) = SoloCombatMotionBridge(
        config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 1),
        sink = SoloCombatMotionFrameSink(frames::add),
    )

    private fun decision(boundary: LivePoseContinuityBoundary) =
        LivePoseContinuityDecision(boundary, temporalEpoch = 1L)

    private fun frame(revision: Long, timestampNs: Long, poses: List<LivePoseObservation>) =
        LivePoseObservationFrame(1L, revision, timestampNs, revision, poses)

    private fun pose(centerX: Float = 0.5f): LivePoseObservation {
        val points = MutableList(LIVE_POSE_LANDMARK_COUNT) {
            LivePoseLandmark(centerX, 0.5f, 0f, 0.95f, 0.95f)
        }
        fun put(index: Int, x: Float, y: Float) {
            points[index] = LivePoseLandmark(x, y, 0f, 0.95f, 0.95f)
        }
        put(11, centerX - 0.07f, 0.30f)
        put(12, centerX + 0.07f, 0.30f)
        put(13, centerX - 0.09f, 0.41f)
        put(14, centerX + 0.09f, 0.41f)
        put(15, centerX - 0.07f, 0.52f)
        put(16, centerX + 0.07f, 0.52f)
        put(23, centerX - 0.06f, 0.62f)
        put(24, centerX + 0.06f, 0.62f)
        return LivePoseObservation(points)
    }
}
