package com.motionarcade.app

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloCombatInputControllerTest {
    @Test
    fun boxingConfigAcceptsP1BoxingFrame() {
        val config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 3)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = SoloCombatInputController("solo-boxing", config, onEvent = accepted::add)

        controller.onSafeMotionFrame(frame(config, 100L, MotionType.PUNCH_JAB))

        assertEquals(listOf(PlayerId.P1 to MotionType.PUNCH_JAB), accepted.map { it.playerId to it.type })
    }

    @Test
    fun fishingConfigIsRejected() {
        val fishing = Files.newInputStream(fishingConfigPath()).use(FishingMotionConfigLoader::load)

        assertThrows(IllegalArgumentException::class.java) {
            SoloCombatInputController(
                sessionId = "solo-fishing-invalid",
                config = DualPlayerCombatMotionConfigs.fishing(fishing),
                onEvent = { },
            )
        }
    }

    @Test
    fun touchAcceptsOnlyP1AndRejectsP2AndAi() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = SoloCombatInputController("solo-touch", config, onEvent = accepted::add)

        controller.submitTouch(PlayerId.P2, MotionType.PUNCH_JAB, 1L)
        controller.submitTouch(PlayerId.AI, MotionType.PUNCH_JAB, 2L)
        controller.submitTouch(PlayerId.P1, MotionType.PUNCH_JAB, 3L)

        assertEquals(listOf(PlayerId.P1 to MotionType.PUNCH_JAB), accepted.map { it.playerId to it.type })
    }

    @Test
    fun safeCameraFrameEmitsOnlyP1SemanticEvent() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = SoloCombatInputController("solo-frame", config, onEvent = accepted::add)

        controller.onSafeMotionFrame(frame(config, 100L, MotionType.PUNCH_JAB))

        assertEquals(listOf(PlayerId.P1), accepted.map(MotionEventEnvelope::playerId))
        assertEquals(listOf(MotionType.PUNCH_JAB), accepted.map(MotionEventEnvelope::type))
    }

    @Test
    fun magicChargeRequiresEightHundredMillisecondHold() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = SoloCombatInputController("solo-charge", config, onEvent = accepted::add)
        val start = 1_000_000_000L

        controller.onSafeMotionFrame(frame(config, start, MotionType.MONSTER_MAGIC_CHARGE))
        controller.onSafeMotionFrame(
            frame(config, start + 799_000_000L, MotionType.MONSTER_MAGIC_CHARGE),
        )
        assertEquals(emptyList<MotionEventEnvelope>(), accepted)
        controller.onSafeMotionFrame(
            frame(config, start + 800_000_000L, MotionType.MONSTER_MAGIC_CHARGE),
        )

        assertEquals(listOf(MotionType.MONSTER_MAGIC_CHARGE), accepted.map { it.type })
    }

    @Test
    fun continuityFenceDiscardsHeldCandidateUntilFreshNeutralRearm() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = SoloCombatInputController("solo-fence", config, onEvent = accepted::add)
        val start = 1_000_000_000L

        controller.onSafeMotionFrame(frame(config, start, MotionType.MONSTER_SKILL_ONE))
        controller.onSafeMotionFrame(frame(config, start + 399_000_000L, MotionType.MONSTER_SKILL_ONE))
        controller.onMotionContinuityFence(start + 400_000_000L)
        controller.onSafeMotionFrame(frame(config, start + 500_000_000L, MotionType.MONSTER_SKILL_ONE))
        controller.onSafeMotionFrame(frame(config, start + 600_000_000L, null))
        controller.onSafeMotionFrame(frame(config, start + 780_000_000L, null))
        controller.onSafeMotionFrame(frame(config, start + 800_000_000L, MotionType.MONSTER_SKILL_ONE))
        controller.onSafeMotionFrame(frame(config, start + 1_200_000_000L, MotionType.MONSTER_SKILL_ONE))

        assertEquals(listOf(MotionType.MONSTER_SKILL_ONE), accepted.map(MotionEventEnvelope::type))
    }

    @Test
    fun restoredP1WatermarksSeedSequenceAndTimestampFence() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val controller = SoloCombatInputController(
            sessionId = "solo-restored",
            config = config,
            acceptedSequenceWatermarks = mapOf(PlayerId.P1 to 4L),
            acceptedTimestampWatermarksNs = mapOf(PlayerId.P1 to 100L),
            onEvent = accepted::add,
        )

        controller.submitTouch(PlayerId.P1, MotionType.PUNCH_JAB, 100L)
        controller.submitTouch(PlayerId.P1, MotionType.PUNCH_JAB, 101L)

        assertEquals(listOf(5L), accepted.map(MotionEventEnvelope::sequenceNumber))
    }

    @Test
    fun requiredInitialNeutralRearmReportsReadyOnlyAfterContinuousNeutralHold() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        val accepted = mutableListOf<MotionEventEnvelope>()
        val rearmStates = mutableListOf<Boolean>()
        val controller = SoloCombatInputController(
            sessionId = "solo-required-rearm",
            config = config,
            requireNeutralRearm = true,
            onRearmStateChanged = rearmStates::add,
            onEvent = accepted::add,
        )
        val neutralStartedNs = 1_000_000_000L
        val requiredHoldNs = config.gestureDefinitions.maxOf { it.neutralRearmNs }

        controller.onSafeMotionFrame(frame(config, neutralStartedNs - 1L, MotionType.PUNCH_JAB))
        assertTrue(accepted.isEmpty())
        assertEquals(false, rearmStates.last())
        controller.onSafeMotionFrame(frame(config, neutralStartedNs, null))
        controller.onSafeMotionFrame(frame(config, neutralStartedNs + requiredHoldNs, null))

        assertEquals(true, rearmStates.last())
        controller.onSafeMotionFrame(
            frame(config, neutralStartedNs + requiredHoldNs + 1L, MotionType.PUNCH_JAB),
        )
        assertEquals(false, rearmStates.last())
    }

    @Test
    fun nonP1CheckpointWatermarksAreRejected() {
        val config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 0)
        listOf(PlayerId.P2, PlayerId.AI).forEach { playerId ->
            assertThrows(IllegalArgumentException::class.java) {
                SoloCombatInputController(
                    sessionId = "solo-invalid-$playerId",
                    config = config,
                    acceptedSequenceWatermarks = mapOf(playerId to 0L),
                    onEvent = { },
                )
            }
        }
    }

    private fun frame(
        config: DualPlayerCombatMotionConfig,
        timestampNs: Long,
        activeType: MotionType?,
    ): SoloCombatMotionFrame = SoloCombatMotionFrame(
        sessionGeneration = 1L,
        revision = timestampNs.coerceAtLeast(1L),
        sourceTimestampNs = timestampNs,
        poseCount = 1,
        usableForSolo = true,
        continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
        configId = config.configId,
        calibrationRevision = config.calibrationRevision,
        profile = config.profile,
        samples = config.motionTypes.map { type ->
            MotionSignalSample(
                playerId = PlayerId.P1,
                type = type,
                timestampNs = timestampNs,
                activation = if (type == activeType) 1f else 0f,
                quality = if (type == activeType) 1f else 0f,
                confidence = 1f,
                calibrationRevision = config.calibrationRevision,
                source = InputSource.MOTION,
            )
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
