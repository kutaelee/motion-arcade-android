package com.motionarcade.app

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class DualPlayerCombatInputControllerTest {
    @Test
    fun monsterClassSkillsRequireFourHundredMillisecondHold() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        listOf(MotionType.MONSTER_SKILL_ONE, MotionType.MONSTER_SKILL_TWO).forEachIndexed { index, type ->
            val accepted = mutableListOf<MotionEventEnvelope>()
            val controller = DualPlayerCombatInputController("monster-skill-$index", config, onEvent = accepted::add)
            val start = 1_000_000_000L
            controller.onSafeMotionFrame(frame(config, start, type))
            controller.onSafeMotionFrame(frame(config, start + 399_000_000L, type))
            assertEquals(emptyList<MotionEventEnvelope>(), accepted)
            controller.onSafeMotionFrame(frame(config, start + 400_000_000L, type))
            assertEquals(listOf(type, type), accepted.map(MotionEventEnvelope::type))
        }
    }

    @Test
    fun monsterReviveRequiresFullOnePointFiveSecondHold() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController("monster-revive-hold", config, onEvent = accepted::add)

        controller.onSafeMotionFrame(frame(config, 1_000_000_000L, MotionType.MONSTER_REVIVE))
        controller.onSafeMotionFrame(frame(config, 2_499_000_000L, MotionType.MONSTER_REVIVE))
        assertEquals(emptyList<MotionEventEnvelope>(), accepted)

        controller.onSafeMotionFrame(frame(config, 2_500_000_000L, MotionType.MONSTER_REVIVE))
        assertEquals(
            listOf(PlayerId.P1 to MotionType.MONSTER_REVIVE, PlayerId.P2 to MotionType.MONSTER_REVIVE),
            accepted.map { it.playerId to it.type },
        )
    }

    @Test
    fun monsterChargeAndUltimateCollisionCanOnlyEmitMagicCharge() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            "monster-team-power-collision",
            config,
            onEvent = accepted::add,
        )
        val colliding = setOf(MotionType.MONSTER_MAGIC_CHARGE, MotionType.TEAM_ULTIMATE)

        controller.onSafeMotionFrame(frame(config, 1_000_000_000L, colliding))
        controller.onSafeMotionFrame(frame(config, 1_800_000_000L, colliding))

        assertEquals(
            listOf(
                PlayerId.P1 to MotionType.MONSTER_MAGIC_CHARGE,
                PlayerId.P2 to MotionType.MONSTER_MAGIC_CHARGE,
            ),
            accepted.map { it.playerId to it.type },
        )
    }

    @Test
    fun roleBoundCameraFrameEmitsOneSemanticEventForEachPlayer() {
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            "boxing-dual-test",
            config,
            onEvent = accepted::add,
        )

        controller.onSafeMotionFrame(frame(config, timestampNs = 100L, activeType = MotionType.PUNCH_JAB))

        assertEquals(listOf(PlayerId.P1, PlayerId.P2), accepted.map(MotionEventEnvelope::playerId))
        assertEquals(listOf(0L, 0L), accepted.map(MotionEventEnvelope::sequenceNumber))
        assertEquals(setOf(MotionType.PUNCH_JAB), accepted.map(MotionEventEnvelope::type).toSet())
    }

    @Test
    fun continuityFenceRequiresNewNeutralIntervalBeforeAnotherHeldGesture() {
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            "boxing-dual-fence",
            config,
            onEvent = accepted::add,
        )

        controller.onSafeMotionFrame(frame(config, timestampNs = 1_000_000_000L, activeType = MotionType.PUNCH_JAB))
        controller.onMotionContinuityFence(timestampNs = 1_100_000_000L)
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_200_000_000L, activeType = MotionType.PUNCH_JAB))
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_300_000_000L, activeType = null))
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_490_000_000L, activeType = null))
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_500_000_000L, activeType = MotionType.PUNCH_JAB))

        assertEquals(
            listOf(
                PlayerId.P1 to 0L,
                PlayerId.P2 to 0L,
                PlayerId.P1 to 1L,
                PlayerId.P2 to 1L,
            ),
            accepted.map { it.playerId to it.sequenceNumber },
        )
    }

    @Test
    fun lateSafetyFenceAfterTouchUsesTheAlreadyConsumedTimestamp() {
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            "boxing-dual-touch-fence",
            config,
            onEvent = accepted::add,
        )

        controller.submitTouch(PlayerId.P1, MotionType.PUNCH_JAB, timestampNs = 1_000_000_000L)
        controller.onMotionContinuityFence(timestampNs = 100L)
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_100_000_000L, activeType = null))
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_290_000_000L, activeType = null))
        controller.onSafeMotionFrame(frame(config, timestampNs = 1_300_000_000L, activeType = MotionType.PUNCH_JAB))

        assertEquals(
            listOf(0L, 1L),
            accepted.filter { it.playerId == PlayerId.P1 }.map(MotionEventEnvelope::sequenceNumber),
        )
    }

    @Test
    fun dualFishingTouchFallbackEmitsIndependentSemanticEventsForBothPlayers() {
        val fishing = Files.newInputStream(fishingConfigPath()).use(FishingMotionConfigLoader::load)
        val config = DualPlayerCombatMotionConfigs.fishing(fishing)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            "fishing-dual-touch",
            config,
            onEvent = accepted::add,
        )

        controller.submitTouch(PlayerId.P1, MotionType.FISH_CAST, timestampNs = 1_000_000_000L)
        controller.submitTouch(PlayerId.P2, MotionType.FISH_CAST, timestampNs = 1_000_000_000L)

        assertEquals(
            listOf(PlayerId.P1 to MotionType.FISH_CAST, PlayerId.P2 to MotionType.FISH_CAST),
            accepted.map { it.playerId to it.type },
        )
        assertEquals(listOf(0L, 0L), accepted.map { it.sequenceNumber })
    }

    @Test
    fun restoredWatermarksSeedBothPlayerSequencesAndTimestampFences() {
        val fishing = FishingMotionConfigLoader.load(Files.newInputStream(fishingConfigPath()))
        val config = DualPlayerCombatMotionConfigs.fishing(fishing)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            sessionId = "fishing-dual-restored",
            config = config,
            acceptedSequenceWatermarks = mapOf(PlayerId.P1 to 4L, PlayerId.P2 to 9L),
            acceptedTimestampWatermarksNs = mapOf(PlayerId.P1 to 100L, PlayerId.P2 to 200L),
            onEvent = accepted::add,
        )

        controller.submitTouch(PlayerId.P1, MotionType.FISH_CAST, 101L)
        controller.submitTouch(PlayerId.P2, MotionType.FISH_CAST, 201L)

        assertEquals(listOf(5L, 10L), accepted.map { event -> event.sequenceNumber })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), accepted.map { event -> event.playerId })
    }

    @Test
    fun dualFishingTouchCoversEveryConfiguredGestureForBothPlayers() {
        val fishing = FishingMotionConfigLoader.load(Files.newInputStream(fishingConfigPath()))
        val config = DualPlayerCombatMotionConfigs.fishing(fishing)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = DualPlayerCombatInputController(
            sessionId = "fishing-dual-all-actions",
            config = config,
            onEvent = accepted::add,
        )
        val orderedTypes = config.motionTypes.sortedBy(MotionType::name)

        orderedTypes.forEachIndexed { index, type ->
            val timestamp = (index + 1L) * 10_000_000_000L
            controller.submitTouch(PlayerId.P1, type, timestamp)
            controller.submitTouch(PlayerId.P2, type, timestamp)
        }

        assertEquals(
            orderedTypes.flatMap { type -> listOf(type, type) },
            accepted.map { event -> event.type },
        )
        assertEquals(
            orderedTypes.flatMap { listOf(PlayerId.P1, PlayerId.P2) },
            accepted.map { event -> event.playerId },
        )
    }

    private fun frame(
        config: DualPlayerCombatMotionConfig,
        timestampNs: Long,
        activeType: MotionType?,
    ): DualPlayerCombatMotionFrame = frame(
        config = config,
        timestampNs = timestampNs,
        activeTypes = setOfNotNull(activeType),
    )

    private fun frame(
        config: DualPlayerCombatMotionConfig,
        timestampNs: Long,
        activeTypes: Set<MotionType>,
    ): DualPlayerCombatMotionFrame =
        DualPlayerCombatMotionFrame(
            sessionGeneration = 1L,
            revision = timestampNs,
            sourceTimestampNs = timestampNs,
            poseCount = 2,
            usableForDual = true,
            continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
            configId = config.configId,
            calibrationRevision = config.calibrationRevision,
            profile = config.profile,
            samples = listOf(PlayerId.P1, PlayerId.P2).flatMap { playerId ->
                config.motionTypes.map { type ->
                    MotionSignalSample(
                        playerId = playerId,
                        type = type,
                        timestampNs = timestampNs,
                        activation = if (type in activeTypes) 1f else 0f,
                        quality = if (type in activeTypes) 1f else 0f,
                        confidence = 1f,
                        calibrationRevision = config.calibrationRevision,
                        source = InputSource.MOTION,
                    )
                }
            },
        )

    private fun fishingConfigPath(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(
            workingDirectory.resolve("vision/src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
            workingDirectory.resolve("../vision/src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Bundled fishing config source was not found from $workingDirectory")
    }
}
