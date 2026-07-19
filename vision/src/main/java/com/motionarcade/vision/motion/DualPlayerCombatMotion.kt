package com.motionarcade.vision.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.GestureDefinition
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.vision.pose.LIVE_POSE_MAX_POSES
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import com.motionarcade.vision.pose.FishingPoseSignalConfig
import java.util.Collections
import java.util.LinkedHashMap

/** Game-rule profiles that consume the tracker-authorized two-player pose recognizer. */
enum class DualPlayerCombatProfile {
    FISHING,
    BOXING,
    MONSTER,
}

/**
 * Versioned, Kotlin-owned candidate thresholds for a two-player combat profile.
 *
 * These are candidate values, not a claim of physical-device gesture approval. Raw landmarks stay
 * in :vision; callers receive only bounded scalar signals for the profile's declared event set.
 */
class DualPlayerCombatMotionConfig internal constructor(
    val configId: String,
    val schemaVersion: Int,
    val calibrationRevision: Int,
    val profile: DualPlayerCombatProfile,
    gestureDefinitions: Collection<GestureDefinition>,
    internal val poseConfig: DualPlayerCombatPoseSignalConfig?,
    internal val fishingPoseConfig: FishingPoseSignalConfig? = null,
) {
    val gestureDefinitions: List<GestureDefinition> = Collections.unmodifiableList(
        gestureDefinitions.map { definition -> definition.copy() },
    )

    val motionTypes: Set<MotionType> = Collections.unmodifiableSet(
        LinkedHashSet(gestureDefinitions.map(GestureDefinition::type)),
    )

    init {
        require(configId.matches(CONFIG_ID_PATTERN))
        require(schemaVersion == 1)
        require(calibrationRevision >= 0)
        require(motionTypes == profile.motionTypes)
        require(gestureDefinitions.size == motionTypes.size)
        if (profile == DualPlayerCombatProfile.FISHING) {
            require(poseConfig == null)
            requireNotNull(fishingPoseConfig)
            require(fishingPoseConfig.configId == configId)
            require(fishingPoseConfig.schemaVersion == schemaVersion)
        } else {
            require(fishingPoseConfig == null)
            requireNotNull(poseConfig)
            require(poseConfig.configId == configId)
            require(poseConfig.schemaVersion == schemaVersion)
        }
    }

    private companion object {
        val CONFIG_ID_PATTERN = Regex("^[a-z][a-z0-9-]{2,63}$")
    }
}

/**
 * Aggregate-only dual-player signal frame. It deliberately exposes neither landmarks, bounds,
 * observation IDs, track IDs, nor raw image timestamps beyond the monotonic event clock.
 */
class DualPlayerCombatMotionFrame(
    val sessionGeneration: Long,
    val revision: Long,
    val sourceTimestampNs: Long,
    val poseCount: Int,
    val usableForDual: Boolean,
    val continuityBoundary: LivePoseContinuityBoundary,
    val configId: String,
    val calibrationRevision: Int,
    val profile: DualPlayerCombatProfile,
    samples: Collection<MotionSignalSample>,
) {
    val samples: List<MotionSignalSample> = Collections.unmodifiableList(
        samples.map { sample ->
            sample.copy(metadata = Collections.unmodifiableMap(LinkedHashMap(sample.metadata)))
        },
    )

    init {
        require(sessionGeneration > 0L)
        require(revision > 0L)
        require(sourceTimestampNs >= 0L)
        require(poseCount in 0..LIVE_POSE_MAX_POSES)
        require(configId.matches(CONFIG_ID_PATTERN))
        require(calibrationRevision >= 0)
        val expected = profile.motionTypes
        require(this.samples.size == expected.size * 2)
        require(this.samples.groupBy(MotionSignalSample::playerId).keys == setOf(PlayerId.P1, PlayerId.P2))
        PlayerId.entries.filter { it == PlayerId.P1 || it == PlayerId.P2 }.forEach { playerId ->
            val playerSamples = this.samples.filter { sample -> sample.playerId == playerId }
            require(playerSamples.map(MotionSignalSample::type).toSet() == expected)
            require(playerSamples.size == expected.size)
        }
        require(this.samples.all { sample ->
            sample.timestampNs == sourceTimestampNs &&
                sample.calibrationRevision == calibrationRevision &&
                sample.source == InputSource.MOTION &&
                sample.activation.isFinite() && sample.activation in 0f..1f &&
                sample.quality.isFinite() && sample.quality in 0f..1f &&
                sample.confidence.isFinite() && sample.confidence in 0f..1f &&
                sample.metadata.isEmpty()
        })
        require(!usableForDual || poseCount == 2)
        require(!usableForDual || continuityBoundary == LivePoseContinuityBoundary.CONTIGUOUS)
    }

    override fun toString(): String =
        "DualPlayerCombatMotionFrame(generation=$sessionGeneration, revision=$revision, " +
            "poseCount=$poseCount, usableForDual=$usableForDual, profile=$profile, " +
            "configId=$configId, signalCount=${samples.size})"

    /** A coalescing gap must reset candidate state before a later live signal can be consumed. */
    internal fun asContinuityFence(): DualPlayerCombatMotionFrame = DualPlayerCombatMotionFrame(
        sessionGeneration = sessionGeneration,
        revision = revision,
        sourceTimestampNs = sourceTimestampNs,
        poseCount = poseCount,
        usableForDual = false,
        continuityBoundary = LivePoseContinuityBoundary.RESET_REVISION_GAP,
        configId = configId,
        calibrationRevision = calibrationRevision,
        profile = profile,
        samples = samples.map { sample ->
            sample.copy(activation = 0f, quality = 0f, confidence = 0f, metadata = emptyMap())
        },
    )

    private companion object {
        val CONFIG_ID_PATTERN = Regex("^[a-z][a-z0-9-]{2,63}$")
    }
}

fun interface DualPlayerCombatMotionFrameSink {
    fun onDualPlayerCombatMotionFrame(frame: DualPlayerCombatMotionFrame)

    companion object {
        val NONE = DualPlayerCombatMotionFrameSink { }
    }
}

/** Candidate definitions are explicit data, never gesture thresholds hidden in a game screen. */
object DualPlayerCombatMotionConfigs {
    fun fishing(config: FishingMotionConfig): DualPlayerCombatMotionConfig =
        DualPlayerCombatMotionConfig(
            configId = config.configId,
            schemaVersion = config.schemaVersion,
            calibrationRevision = config.calibrationRevision,
            profile = DualPlayerCombatProfile.FISHING,
            gestureDefinitions = config.gestureDefinitions,
            poseConfig = null,
            fishingPoseConfig = config.poseConfig,
        )

    fun boxing(calibrationRevision: Int): DualPlayerCombatMotionConfig =
        config(
            configId = "boxing-dual-pose-v1-candidate",
            calibrationRevision = calibrationRevision,
            profile = DualPlayerCombatProfile.BOXING,
            definitions = listOf(
                definition(MotionType.BOXING_GUARD, holdMs = 150, cooldownMs = 180, group = "BOXING_DEFENSE", priority = 3),
                definition(MotionType.DODGE_LEFT, holdMs = 80, cooldownMs = 350, group = "BOXING_DEFENSE", priority = 2),
                definition(MotionType.DODGE_RIGHT, holdMs = 80, cooldownMs = 350, group = "BOXING_DEFENSE", priority = 2),
                definition(MotionType.PUNCH_JAB, holdMs = 0, cooldownMs = 350, group = "BOXING_STRIKE", priority = 2),
                definition(MotionType.PUNCH_HOOK, holdMs = 0, cooldownMs = 450, group = "BOXING_STRIKE", priority = 1),
            ),
        )

    fun monster(calibrationRevision: Int): DualPlayerCombatMotionConfig =
        config(
            configId = "monster-dual-pose-v1-candidate",
            calibrationRevision = calibrationRevision,
            profile = DualPlayerCombatProfile.MONSTER,
            definitions = listOf(
                definition(MotionType.MONSTER_BLOCK, holdMs = 150, cooldownMs = 220, group = "MONSTER_DEFENSE", priority = 2),
                definition(MotionType.MONSTER_REVIVE, holdMs = 1_500, cooldownMs = 500, group = "MONSTER_SUPPORT", priority = 3),
                definition(MotionType.MONSTER_SKILL_ONE, holdMs = 400, cooldownMs = 1_200, group = "MONSTER_CLASS_SKILL", priority = 2),
                definition(MotionType.MONSTER_SKILL_TWO, holdMs = 400, cooldownMs = 1_200, group = "MONSTER_CLASS_SKILL", priority = 2),
                definition(MotionType.MONSTER_MAGIC_CHARGE, holdMs = 800, cooldownMs = 800, group = "MONSTER_SUPPORT", priority = 2),
                definition(MotionType.TEAM_ULTIMATE, holdMs = 800, cooldownMs = 900, group = "MONSTER_SKILL", priority = 1),
                definition(MotionType.DODGE_LEFT, holdMs = 80, cooldownMs = 350, group = "MONSTER_DEFENSE", priority = 2),
                definition(MotionType.DODGE_RIGHT, holdMs = 80, cooldownMs = 350, group = "MONSTER_DEFENSE", priority = 2),
                definition(MotionType.PUNCH_JAB, holdMs = 0, cooldownMs = 350, group = "MONSTER_STRIKE", priority = 2),
                definition(MotionType.PUNCH_HOOK, holdMs = 0, cooldownMs = 450, group = "MONSTER_STRIKE", priority = 1),
            ),
        )

    private fun config(
        configId: String,
        calibrationRevision: Int,
        profile: DualPlayerCombatProfile,
        definitions: List<GestureDefinition>,
    ): DualPlayerCombatMotionConfig =
        DualPlayerCombatMotionConfig(
            configId = configId,
            schemaVersion = 1,
            calibrationRevision = calibrationRevision,
            profile = profile,
            gestureDefinitions = definitions,
            poseConfig = DualPlayerCombatPoseSignalConfig(configId = configId, schemaVersion = 1),
        )

    private fun definition(
        type: MotionType,
        holdMs: Long,
        cooldownMs: Long,
        group: String,
        priority: Int,
    ): GestureDefinition = GestureDefinition(
        type = type,
        entryThreshold = 0.82f,
        exitThreshold = 0.30f,
        minimumConfidence = 0.55f,
        minimumHoldNs = holdMs * NANOS_PER_MILLISECOND,
        maximumCandidateNs = maxOf(holdMs + 650L, 850L) * NANOS_PER_MILLISECOND,
        cooldownNs = cooldownMs * NANOS_PER_MILLISECOND,
        neutralRearmNs = 180L * NANOS_PER_MILLISECOND,
        exclusivityGroup = group,
        priority = priority,
    )

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

internal data class DualPlayerCombatPoseSignalConfig(
    val configId: String,
    val schemaVersion: Int,
    val minimumLandmarkConfidence: Float = 0.55f,
    val minimumShoulderWidth: Float = 0.05f,
    val minimumTorsoLength: Float = 0.05f,
    val punchMinimumTravelShoulderWidths: Float = 0.35f,
    val punchMinimumElbowExtensionRadians: Float = 0.35f,
    val punchWindowNs: Long = 450_000_000L,
    val hookMinimumElbowAngleRadians: Float = 1.22f,
    val hookMaximumElbowAngleRadians: Float = 2.27f,
    val hookMinimumCrossBodyShoulderWidths: Float = 0.35f,
    val dodgeMinimumLeanTorsoLengths: Float = 0.20f,
    val guardMaximumWristDistanceShoulderWidths: Float = 0.95f,
    val guardMinimumWristHeightTorsoLengths: Float = 0.15f,
    val ultimateMinimumRaiseTorsoLengths: Float = 0.25f,
    val skillMinimumRaiseTorsoLengths: Float = 0.20f,
    val skillMinimumRestBelowChestTorsoLengths: Float = 0.05f,
    val chargeMinimumRaiseTorsoLengths: Float = 0.25f,
    val chargeMinimumWristSeparationShoulderWidths: Float = 1.20f,
    val maximumHistorySamples: Int = 48,
) {
    init {
        require(configId.isNotBlank())
        require(schemaVersion == 1)
        require(minimumLandmarkConfidence in 0f..1f)
        require(minimumShoulderWidth > 0f && minimumTorsoLength > 0f)
        require(punchMinimumTravelShoulderWidths > 0f)
        require(punchMinimumElbowExtensionRadians > 0f)
        require(punchWindowNs in 1L..700_000_000L)
        require(hookMinimumElbowAngleRadians in 0f..hookMaximumElbowAngleRadians)
        require(hookMinimumCrossBodyShoulderWidths > 0f)
        require(dodgeMinimumLeanTorsoLengths > 0f)
        require(guardMaximumWristDistanceShoulderWidths > 0f)
        require(guardMinimumWristHeightTorsoLengths > 0f)
        require(ultimateMinimumRaiseTorsoLengths > 0f)
        require(skillMinimumRaiseTorsoLengths > 0f)
        require(skillMinimumRestBelowChestTorsoLengths > 0f)
        require(chargeMinimumRaiseTorsoLengths > 0f)
        require(chargeMinimumWristSeparationShoulderWidths > 0f)
        require(maximumHistorySamples in 8..256)
    }
}

private val DualPlayerCombatProfile.motionTypes: Set<MotionType>
    get() = when (this) {
        DualPlayerCombatProfile.FISHING -> setOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )
        DualPlayerCombatProfile.BOXING -> setOf(
            MotionType.BOXING_GUARD,
            MotionType.DODGE_LEFT,
            MotionType.DODGE_RIGHT,
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
        )
        DualPlayerCombatProfile.MONSTER -> setOf(
            MotionType.MONSTER_BLOCK,
            MotionType.MONSTER_REVIVE,
            MotionType.MONSTER_SKILL_ONE,
            MotionType.MONSTER_SKILL_TWO,
            MotionType.MONSTER_MAGIC_CHARGE,
            MotionType.TEAM_ULTIMATE,
            MotionType.DODGE_LEFT,
            MotionType.DODGE_RIGHT,
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
        )
    }

internal val DualPlayerCombatProfile.requiredMotionTypes: Set<MotionType>
    get() = motionTypes
