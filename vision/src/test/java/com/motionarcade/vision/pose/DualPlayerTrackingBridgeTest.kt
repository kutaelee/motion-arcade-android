package com.motionarcade.vision.pose

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.TrackState
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrameSink
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.tracking.IdentityPauseReason
import com.motionarcade.vision.tracking.LoadedPlayerTrackerConfig
import com.motionarcade.vision.tracking.PlayerTrackerConfigJson
import com.motionarcade.vision.tracking.PlayerTrackerConfigLoadResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPlayerTrackingBridgeTest {
    private val config: LoadedPlayerTrackerConfig by lazy {
        when (val result = PlayerTrackerConfigJson.load(CONFIG_TEXT.toByteArray(StandardCharsets.UTF_8))) {
            is PlayerTrackerConfigLoadResult.Success -> result.loaded
            is PlayerTrackerConfigLoadResult.Failure -> error("fixture config rejected: ${result.violation}")
        }
    }

    @Test
    fun resetIsFailClosedThenStableTwoPoseFramesActivateBothExplicitRoles() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge = bridge(summaries)

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 3L, timestampNs = 800_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        assertEquals(3, summaries.size)
        assertTrue(summaries.first().pauseRequired)
        assertEquals(IdentityPauseReason.REARM_REQUIRED, summaries.first().pauseReason)
        val active = summaries.last()
        assertFalse(active.pauseRequired)
        assertEquals(listOf(TrackState.ACTIVE, TrackState.ACTIVE), active.roleStates.map { it.state })
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), active.roleStates.map { it.playerId })
        assertEquals(DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT, active.roleSetupPolicy)
    }

    @Test
    fun motionSignalsAreRoleBoundOnlyAfterSafeTrackingAndFenceOnUnsafeFrame() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val motionFrames = mutableListOf<DualPlayerCombatMotionFrame>()
        val bridge =
            DualPlayerTrackingBridge(
                loadedConfig = config,
                roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
                calibrationRevision = 1L,
                sink = DualPlayerTrackingSink(summaries::add),
                initialSetupRequiresExplicitRearm = false,
                combatMotionBridge =
                    DualPlayerCombatMotionBridge(
                        config = DualPlayerCombatMotionConfigs.boxing(calibrationRevision = 1),
                        sink = DualPlayerCombatMotionFrameSink(motionFrames::add),
                    ),
            )

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 3L, timestampNs = 800_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val active = motionFrames.last()
        assertTrue(active.usableForDual)
        assertEquals(setOf(PlayerId.P1, PlayerId.P2), active.samples.map { it.playerId }.toSet())
        assertEquals(5, active.samples.count { it.playerId == PlayerId.P1 })
        assertEquals(5, active.samples.count { it.playerId == PlayerId.P2 })
        assertTrue(
            DualPlayerCombatMotionFrame::class.java.declaredFields.none { field ->
                field.name.contains("landmark", ignoreCase = true) ||
                    field.name.contains("track", ignoreCase = true) ||
                    field.name.contains("bounds", ignoreCase = true)
            },
        )

        bridge.onPoseObservation(
            frame(revision = 4L, timestampNs = 1_200_000_000L, poses = listOf(pose(0.25f), pose(0.75f, outOfBounds = true))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        val fenced = motionFrames.last()
        assertFalse(fenced.usableForDual)
        assertTrue(fenced.samples.all { it.activation == 0f && it.confidence == 0f })
    }

    @Test
    fun monsterReviveActivatesOnlyWhenBothRolesReachTowardTheirTeammate() {
        val motionFrames = mutableListOf<DualPlayerCombatMotionFrame>()
        val bridge = DualPlayerTrackingBridge(
            loadedConfig = config,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            calibrationRevision = 1L,
            sink = DualPlayerTrackingSink.NONE,
            initialSetupRequiresExplicitRearm = false,
            combatMotionBridge = DualPlayerCombatMotionBridge(
                config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 1),
                sink = DualPlayerCombatMotionFrameSink(motionFrames::add),
            ),
        )

        bridge.onPoseObservation(
            frame(1L, 0L, listOf(revivePose(0.25f, towardTeammate = true), revivePose(0.75f, towardTeammate = true))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(2L, 400_000_000L, listOf(revivePose(0.25f, towardTeammate = true), revivePose(0.75f, towardTeammate = true))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(3L, 800_000_000L, listOf(revivePose(0.25f, towardTeammate = true), revivePose(0.75f, towardTeammate = true))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val toward = motionFrames.last().samples.filter { it.type == MotionType.MONSTER_REVIVE }
        assertTrue(toward.all { it.activation >= 0.82f })

        bridge.onPoseObservation(
            frame(4L, 1_200_000_000L, listOf(revivePose(0.25f, towardTeammate = false), revivePose(0.75f, towardTeammate = false))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        val away = motionFrames.last().samples.filter { it.type == MotionType.MONSTER_REVIVE }
        assertTrue(away.all { it.activation == 0f })
    }

    @Test
    fun monsterClassSkillSignalsDistinguishLeftAndRightArmPoses() {
        val motionFrames = mutableListOf<DualPlayerCombatMotionFrame>()
        val bridge = DualPlayerTrackingBridge(
            loadedConfig = config,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            calibrationRevision = 1L,
            sink = DualPlayerTrackingSink.NONE,
            initialSetupRequiresExplicitRearm = false,
            combatMotionBridge = DualPlayerCombatMotionBridge(
                config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 1),
                sink = DualPlayerCombatMotionFrameSink(motionFrames::add),
            ),
        )
        bridge.onPoseObservation(
            frame(1L, 0L, listOf(skillPose(0.25f, leftArm = true), skillPose(0.75f, leftArm = true))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(2L, 400_000_000L, listOf(skillPose(0.25f, leftArm = true), skillPose(0.75f, leftArm = true))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(3L, 800_000_000L, listOf(skillPose(0.25f, leftArm = true), skillPose(0.75f, leftArm = true))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        val left = motionFrames.last().samples.associateBy { it.playerId to it.type }
        listOf(PlayerId.P1, PlayerId.P2).forEach { playerId ->
            assertTrue(requireNotNull(left[playerId to MotionType.MONSTER_SKILL_ONE]).activation >= 0.82f)
            assertEquals(0f, requireNotNull(left[playerId to MotionType.MONSTER_SKILL_TWO]).activation)
        }

        bridge.onPoseObservation(
            frame(4L, 1_200_000_000L, listOf(skillPose(0.25f, leftArm = false), skillPose(0.75f, leftArm = false))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        val right = motionFrames.last().samples.associateBy { it.playerId to it.type }
        listOf(PlayerId.P1, PlayerId.P2).forEach { playerId ->
            assertEquals(0f, requireNotNull(right[playerId to MotionType.MONSTER_SKILL_ONE]).activation)
            assertTrue(requireNotNull(right[playerId to MotionType.MONSTER_SKILL_TWO]).activation >= 0.82f)
        }
    }

    @Test
    fun monsterMagicChargeUsesRaisedVHandsForBothRoles() {
        val motionFrames = mutableListOf<DualPlayerCombatMotionFrame>()
        val bridge = DualPlayerTrackingBridge(
            loadedConfig = config,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            calibrationRevision = 1L,
            sink = DualPlayerTrackingSink.NONE,
            initialSetupRequiresExplicitRearm = false,
            combatMotionBridge = DualPlayerCombatMotionBridge(
                config = DualPlayerCombatMotionConfigs.monster(calibrationRevision = 1),
                sink = DualPlayerCombatMotionFrameSink(motionFrames::add),
            ),
        )
        listOf(0L, 400_000_000L, 800_000_000L).forEachIndexed { index, timestamp ->
            bridge.onPoseObservation(
                frame(
                    revision = index + 1L,
                    timestampNs = timestamp,
                    poses = listOf(chargePose(0.25f), chargePose(0.75f)),
                ),
                decision(
                    if (index == 0) LivePoseContinuityBoundary.RESET_GENERATION
                    else LivePoseContinuityBoundary.CONTIGUOUS,
                ),
            )
        }

        val charge = motionFrames.last().samples.filter {
            it.type == MotionType.MONSTER_MAGIC_CHARGE
        }
        assertEquals(setOf(PlayerId.P1, PlayerId.P2), charge.map { it.playerId }.toSet())
        assertTrue(charge.all { it.activation >= 0.82f })
    }

    @Test
    fun fishingSignalsReuseTheReviewedSoloThresholdsForBothTrackedRoles() {
        val motionFrames = mutableListOf<DualPlayerCombatMotionFrame>()
        val fishingConfig = Files.newInputStream(fishingConfigPath()).use(FishingMotionConfigLoader::load)
        val bridge = DualPlayerTrackingBridge(
            loadedConfig = config,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            calibrationRevision = 1L,
            sink = DualPlayerTrackingSink.NONE,
            initialSetupRequiresExplicitRearm = false,
            combatMotionBridge = DualPlayerCombatMotionBridge(
                config = DualPlayerCombatMotionConfigs.fishing(fishingConfig),
                sink = DualPlayerCombatMotionFrameSink(motionFrames::add),
            ),
        )

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 3L, timestampNs = 800_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val active = motionFrames.last()
        assertTrue(active.usableForDual)
        assertEquals(DualPlayerCombatProfile.FISHING, active.profile)
        assertEquals(7, active.samples.count { it.playerId == PlayerId.P1 })
        assertEquals(7, active.samples.count { it.playerId == PlayerId.P2 })
        assertEquals(
            setOf(
                MotionType.FISH_READY,
                MotionType.FISH_CAST,
                MotionType.FISH_HOOK,
                MotionType.FISH_REEL_CYCLE,
                MotionType.FISH_TENSION_LEFT,
                MotionType.FISH_TENSION_RIGHT,
                MotionType.FISH_NET,
            ),
            active.samples.map { it.type }.toSet(),
        )
    }

    @Test
    fun productionDefaultRequiresExplicitSetupOnEveryNewCameraBridge() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge =
            DualPlayerTrackingBridge(
                loadedConfig = config,
                roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
                calibrationRevision = 1L,
                sink = DualPlayerTrackingSink(summaries::add),
            )

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val setupRequired = summaries.last()
        assertTrue(setupRequired.pauseRequired)
        assertEquals(IdentityPauseReason.REARM_REQUIRED, setupRequired.pauseReason)
        assertTrue(bridge.requestRearm(setupRequired.sessionGeneration, setupRequired.rearmChallengeId))
    }

    @Test
    fun missingOrOutOfBoundsPoseCannotSelectRoleAndSummaryDoesNotExposeRawPoseFields() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge = bridge(summaries)

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f, outOfBounds = true))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val awaiting = summaries.last()
        assertTrue(awaiting.pauseRequired)
        assertEquals(IdentityPauseReason.INSUFFICIENT_OBSERVATIONS, awaiting.pauseReason)
        val declaredNames = DualPlayerTrackingSummary::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(declaredNames.none { name ->
            "landmark" in name || "coordinate" in name || "bound" in name || "timestamp" in name || "token" in name
        })
        assertTrue(
            DualPlayerTrackingSummary::class.java.declaredFields.none { field ->
                field.type.name.contains("LivePoseObservation") || field.type.name.contains("LivePoseLandmark")
            },
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (awaiting.roleStates as MutableList<DualPlayerRoleTrackingState>).clear()
        }
        assertFalse(awaiting.toString().contains("wrist", ignoreCase = true))
    }

    @Test
    fun continuityGapRequiresExplicitNeutralRearmBeforeAnyNewRoleAssociation() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge = bridge(summaries)

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 5L, timestampNs = 1_200_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.RESET_REVISION_GAP),
        )
        bridge.onPoseObservation(
            frame(revision = 6L, timestampNs = 1_600_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val afterGap = summaries.last()
        assertTrue(afterGap.pauseRequired)
        assertEquals(IdentityPauseReason.REARM_REQUIRED, afterGap.pauseReason)
        assertEquals(listOf(TrackState.REARM, TrackState.REARM), afterGap.roleStates.map { it.state })

        assertFalse(bridge.requestRearm(afterGap.sessionGeneration, afterGap.rearmChallengeId - 1L))
        assertTrue(bridge.requestRearm(afterGap.sessionGeneration, afterGap.rearmChallengeId))
        bridge.onPoseObservation(
            frame(revision = 7L, timestampNs = 2_000_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 8L, timestampNs = 2_400_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 9L, timestampNs = 2_800_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 10L, timestampNs = 3_200_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 11L, timestampNs = 3_600_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        assertFalse(summaries.last().pauseRequired)
        assertEquals(listOf(TrackState.ACTIVE, TrackState.ACTIVE), summaries.last().roleStates.map { it.state })
    }

    @Test
    fun missingFrameResetsExplicitRearmNeutralDwell() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge = bridge(summaries)

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 3L, timestampNs = 800_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.RESET_REVISION_GAP),
        )
        bridge.onPoseObservation(
            frame(revision = 4L, timestampNs = 1_200_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        val rearm = summaries.last()
        assertTrue(bridge.requestRearm(rearm.sessionGeneration, rearm.rearmChallengeId))
        bridge.onPoseObservation(
            frame(revision = 5L, timestampNs = 1_600_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 6L, timestampNs = 2_000_000_000L, poses = listOf(pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 7L, timestampNs = 2_400_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 8L, timestampNs = 2_800_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 9L, timestampNs = 3_200_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        assertTrue(summaries.last().pauseRequired)
        assertEquals(IdentityPauseReason.REARM_REQUIRED, summaries.last().pauseReason)
    }

    @Test
    fun crossingDuringExplicitRearmNeverActivatesTheNewLeftRightOrder() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge = bridge(summaries)

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 3L, timestampNs = 800_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 4L, timestampNs = 1_200_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_REVISION_GAP),
        )
        bridge.onPoseObservation(
            frame(revision = 5L, timestampNs = 1_600_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        val rearm = summaries.last()
        assertTrue(bridge.requestRearm(rearm.sessionGeneration, rearm.rearmChallengeId))
        bridge.onPoseObservation(
            frame(revision = 6L, timestampNs = 2_000_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 7L, timestampNs = 2_400_000_000L, poses = listOf(pose(0.55f), pose(0.45f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 8L, timestampNs = 2_800_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 9L, timestampNs = 3_200_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        assertTrue(summaries.last().pauseRequired)
        assertTrue(summaries.last().pauseReason != IdentityPauseReason.NONE)
    }

    @Test
    fun trackerManagedRearmCompletesItsOwnNeutralDwellWithoutASecondChallenge() {
        val summaries = mutableListOf<DualPlayerTrackingSummary>()
        val bridge = bridge(summaries)

        bridge.onPoseObservation(
            frame(revision = 1L, timestampNs = 0L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.RESET_GENERATION),
        )
        bridge.onPoseObservation(
            frame(revision = 2L, timestampNs = 400_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 3L, timestampNs = 800_000_000L, poses = listOf(pose(0.25f), pose(0.75f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 4L, timestampNs = 1_200_000_000L, poses = listOf(pose(0.55f), pose(0.45f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 5L, timestampNs = 1_600_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        val challenge = summaries.last()
        assertTrue(challenge.pauseRequired)
        assertTrue(bridge.requestRearm(challenge.sessionGeneration, challenge.rearmChallengeId))
        bridge.onPoseObservation(
            frame(revision = 6L, timestampNs = 2_000_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 7L, timestampNs = 2_400_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )
        bridge.onPoseObservation(
            frame(revision = 8L, timestampNs = 2_800_000_000L, poses = listOf(pose(0.75f), pose(0.25f))),
            decision(LivePoseContinuityBoundary.CONTIGUOUS),
        )

        assertFalse(summaries.last().pauseRequired)
        assertEquals(listOf(TrackState.ACTIVE, TrackState.ACTIVE), summaries.last().roleStates.map { it.state })
    }

    private fun bridge(summaries: MutableList<DualPlayerTrackingSummary>): DualPlayerTrackingBridge =
        DualPlayerTrackingBridge(
            loadedConfig = config,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            calibrationRevision = 1L,
            sink = DualPlayerTrackingSink(summaries::add),
            initialSetupRequiresExplicitRearm = false,
        )

    private fun decision(boundary: LivePoseContinuityBoundary): LivePoseContinuityDecision =
        LivePoseContinuityDecision(boundary, temporalEpoch = 1L)

    private fun frame(
        revision: Long,
        timestampNs: Long,
        poses: List<LivePoseObservation>,
    ): LivePoseObservationFrame =
        LivePoseObservationFrame(TEST_GENERATION, revision, timestampNs, revision, poses)

    private fun pose(centerX: Float, outOfBounds: Boolean = false): LivePoseObservation {
        val points = MutableList(LIVE_POSE_LANDMARK_COUNT) {
            LivePoseLandmark(centerX, 0.50f, 0f, 0.95f, 0.95f)
        }
        fun put(index: Int, x: Float, y: Float) {
            points[index] = LivePoseLandmark(x, y, 0f, 0.95f, 0.95f)
        }
        put(11, centerX - 0.07f, 0.30f)
        put(12, centerX + 0.07f, 0.30f)
        put(15, centerX - 0.07f, 0.52f)
        put(16, centerX + 0.07f, 0.52f)
        put(23, centerX - 0.06f, 0.62f)
        put(24, centerX + 0.06f, 0.62f)
        if (outOfBounds) put(15, 1.10f, 0.52f)
        return LivePoseObservation(points)
    }

    private fun revivePose(centerX: Float, towardTeammate: Boolean): LivePoseObservation {
        val base = pose(centerX).landmarks.toMutableList()
        val direction = when {
            centerX < 0.5f && towardTeammate -> 1f
            centerX < 0.5f -> -1f
            towardTeammate -> -1f
            else -> 1f
        }
        base[15] = LivePoseLandmark(centerX + direction * 0.14f, 0.40f, 0f, 0.95f, 0.95f)
        base[16] = LivePoseLandmark(centerX + direction * 0.16f, 0.40f, 0f, 0.95f, 0.95f)
        return LivePoseObservation(base)
    }

    private fun skillPose(centerX: Float, leftArm: Boolean): LivePoseObservation {
        val base = pose(centerX).landmarks.toMutableList()
        val raisedIndex = if (leftArm) 15 else 16
        val restingIndex = if (leftArm) 16 else 15
        base[raisedIndex] = LivePoseLandmark(centerX, 0.18f, 0f, 0.95f, 0.95f)
        base[restingIndex] = LivePoseLandmark(centerX, 0.54f, 0f, 0.95f, 0.95f)
        return LivePoseObservation(base)
    }

    private fun chargePose(centerX: Float): LivePoseObservation {
        val base = pose(centerX).landmarks.toMutableList()
        base[15] = LivePoseLandmark(centerX - 0.14f, 0.16f, 0f, 0.95f, 0.95f)
        base[16] = LivePoseLandmark(centerX + 0.14f, 0.16f, 0f, 0.95f, 0.95f)
        return LivePoseObservation(base)
    }

    private fun fishingConfigPath(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(
            workingDirectory.resolve("src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
            workingDirectory.resolve("vision/src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Bundled fishing config source was not found from $workingDirectory")
    }

    private companion object {
        const val TEST_GENERATION = 23L
        val CONFIG_TEXT = """
            {"schemaId":"motion-arcade.player-tracker-config.v1","reviewStatus":"CANDIDATE_UNVERIFIED","configId":"dual-bridge-fixture-v1","schemaVersion":1,"pelvisDistanceWeight":1.0,"shoulderDistanceWeight":0.5,"velocityMismatchWeight":0.8,"scaleMismatchWeight":0.25,"directionDiscontinuityWeight":0.25,"lanePenaltyWeight":0.0,"absoluteAssignmentGate":5.0,"assignmentMargin":0.20,"laneToleranceNormalized":0.10,"minimumBodyScale":0.05,"maximumNormalizedSpeedPerSecond":2.0,"minimumObservationConfidence":0.60,"rearmMinimumConfidence":0.80,"tentativeDurationNanos":400000000,"occlusionGraceNanos":400000000,"lostAfterNanos":1200000000,"rearmNeutralDurationNanos":1000000000,"crossingHysteresisFrames":2,"crossingHysteresisNanos":0,"overlapIouPauseThreshold":1.0,"proximityBodyScalePauseThreshold":0.01,"maximumPredictionHorizonNanos":400000000,"maximumStableObservationGapNanos":400000000}
        """.trimIndent()
    }
}
