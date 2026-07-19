package com.motionarcade.games.monster

import com.motionarcade.core.contract.DeterministicEventId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterRaidGameSessionTest {
    @Test
    fun eachClassHasTwoDistinctExecutableSkillsWithCooldowns() {
        val vanguard = Harness(GameMode.DUAL)
        val enemyBefore = vanguard.game.snapshot.enemyHealth
        vanguard.submit(PlayerId.P1, MotionType.MONSTER_SKILL_ONE)
        vanguard.advance(1)
        assertTrue(vanguard.game.snapshot.enemyHealth < enemyBefore)
        assertEquals(1, vanguard.player(PlayerId.P1).skillOneUses)
        assertTrue(vanguard.player(PlayerId.P1).skillOneCooldownTicks > 0)

        vanguard.submit(PlayerId.P1, MotionType.MONSTER_SKILL_TWO)
        vanguard.advance(1)
        assertEquals(1, vanguard.player(PlayerId.P1).skillTwoUses)
        assertTrue(vanguard.player(PlayerId.P1).guardTicksRemaining > 0)
        assertTrue(vanguard.player(PlayerId.P2).guardTicksRemaining > 0)

        val ranger = Harness(GameMode.DUAL)
        ranger.submit(PlayerId.P2, MotionType.MONSTER_SKILL_ONE)
        ranger.advance(1)
        ranger.submit(PlayerId.P2, MotionType.MONSTER_SKILL_TWO)
        ranger.advance(1)
        assertEquals(1, ranger.player(PlayerId.P2).skillOneUses)
        assertEquals(1, ranger.player(PlayerId.P2).skillTwoUses)
        assertTrue(ranger.game.snapshot.teamCharge > 0)

        assertEquals(MonsterRaidSkill.RUNE_SLAM, MonsterRaidGameSession.skillFor(MonsterRaidClass.VANGUARD, true))
        assertEquals(MonsterRaidSkill.AEGIS_PULSE, MonsterRaidGameSession.skillFor(MonsterRaidClass.VANGUARD, false))
        assertEquals(MonsterRaidSkill.STAR_VOLLEY, MonsterRaidGameSession.skillFor(MonsterRaidClass.RANGER, true))
        assertEquals(MonsterRaidSkill.WEAKPOINT_MARK, MonsterRaidGameSession.skillFor(MonsterRaidClass.RANGER, false))
    }

    @Test
    fun dodgeChangesLaneAndMagicChargeBuildsTeamMeter() {
        val raid = Harness(GameMode.SOLO)

        raid.submit(PlayerId.P1, MotionType.MONSTER_MAGIC_CHARGE)
        raid.advance(1)
        assertEquals(25, raid.game.snapshot.teamCharge)
        raid.submit(PlayerId.P1, MotionType.DODGE_LEFT)
        raid.advance(1)

        assertEquals(MonsterRaidLane.LEFT, raid.player(PlayerId.P1).lane)
        assertTrue(raid.player(PlayerId.P1).guardTicksRemaining > 0)
    }

    @Test
    fun raidProgressesThreeWavesEliteAndAllThreeBossPhases() {
        val raid = Harness(GameMode.DUAL)
        val seenEnemies = linkedSetOf(raid.game.snapshot.enemy)

        while (raid.game.snapshot.stage != MonsterRaidStage.BOSS) {
            raid.attackAndRearm(PlayerId.P2)
            seenEnemies += raid.game.snapshot.enemy
        }
        assertEquals(
            setOf(
                MonsterRaidEnemy.MOSS_CRAWLER,
                MonsterRaidEnemy.SKY_WISP,
                MonsterRaidEnemy.STONE_TUSK,
                MonsterRaidEnemy.IRON_WARDEN,
                MonsterRaidEnemy.TEMPEST_TITAN,
            ),
            seenEnemies,
        )
        assertEquals(MonsterRaidBossPhase.PHASE_1, raid.game.snapshot.bossPhase)

        raid.attackUntilBossHealthAtMost(MonsterRaidGameSession.BOSS_PHASE_2_THRESHOLD)
        assertEquals(MonsterRaidBossPhase.PHASE_2, raid.game.snapshot.bossPhase)
        raid.attackUntilBossHealthAtMost(MonsterRaidGameSession.BOSS_PHASE_3_THRESHOLD)
        assertEquals(MonsterRaidBossPhase.PHASE_3, raid.game.snapshot.bossPhase)
        assertEquals(MonsterRaidClass.VANGUARD, raid.player(PlayerId.P1).playerClass)
        assertEquals(MonsterRaidClass.RANGER, raid.player(PlayerId.P2).playerClass)
    }

    @Test
    fun phaseTwoVanguardGuardProtectsRangerWhileRangerAttacks() {
        val raid = Harness(GameMode.DUAL)
        raid.attackUntilBossHealthAtMost(MonsterRaidGameSession.BOSS_PHASE_2_THRESHOLD)
        val phaseTwo = raid.game.snapshot.copy(
            bossAttack = MonsterBossAttack.CLAW,
            bossTelegraphTicksRemaining = 2,
            players = raid.game.snapshot.players.mapValues { (_, player) ->
                player.copy(guardTicksRemaining = 0, lastDamageTaken = 0)
            },
        )
        val guarded = MonsterRaidGameSession.restore(phaseTwo)
        val unguarded = MonsterRaidGameSession.restore(phaseTwo)
        assertTrue(guarded.resume())
        assertTrue(unguarded.resume())
        val p1 = guarded.snapshot.players.getValue(PlayerId.P1)
        val guardEvent = raidEvent(
            game = guarded,
            playerId = PlayerId.P1,
            sequence = p1.acceptedSequenceWatermark + 1,
            type = MotionType.MONSTER_BLOCK,
            calibrationRevision = guarded.snapshot.calibrationRevision,
            timestampNs = p1.acceptedEventTimestampWatermarkNs + 1_000L,
        )
        assertTrue(guarded.accept(guardEvent) is MonsterRaidInputResult.Queued)

        guarded.advanceTicks(2)
        unguarded.advanceTicks(2)

        val guardedRanger = guarded.snapshot.players.getValue(PlayerId.P2)
        val unguardedRanger = unguarded.snapshot.players.getValue(PlayerId.P2)
        assertEquals(3, guardedRanger.lastDamageTaken)
        assertEquals(14, unguardedRanger.lastDamageTaken)
        assertTrue(guardedRanger.health > unguardedRanger.health)
        assertTrue(raid.player(PlayerId.P2).score > 0)
    }

    @Test
    fun soloAiCompanionAssistsButNeverClearsForIdlePlayer() {
        val raid = Harness(GameMode.SOLO)

        raid.advance(80)
        assertEquals(MonsterRaidGameSession.WAVE_HEALTH, raid.game.snapshot.enemyHealth)
        assertEquals(0, raid.player(PlayerId.AI).score)
        assertEquals(MonsterRaidLane.RIGHT, raid.player(PlayerId.AI).lane)

        while (raid.game.snapshot.stage != MonsterRaidStage.RESULT) {
            if (raid.game.snapshot.bossAttack != null) {
                raid.submit(PlayerId.P1, MotionType.MONSTER_BLOCK)
                raid.advance(1)
            }
            raid.attackAndRearm(PlayerId.P1)
        }

        assertEquals(MonsterRaidOutcome.VICTORY, raid.game.snapshot.outcome)
        assertTrue(raid.player(PlayerId.P1).score > 0)
        assertTrue(raid.player(PlayerId.AI).score > 0)
        assertFalse(raid.player(PlayerId.P1).downed)
        assertTrue(raid.player(PlayerId.AI).dangerEvades > 0)
    }

    @Test
    fun soloAiCannotSupplySecondHumanHalfOfTeamUltimate() {
        val game = MonsterRaidGameSession.start("raid-solo-no-ai-ultimate", 17L, 2, GameMode.SOLO)
        val before = game.snapshot

        val result = game.accept(
            raidEvent(
                game = game,
                playerId = PlayerId.P1,
                sequence = 0,
                type = MotionType.TEAM_ULTIMATE,
                calibrationRevision = 2,
                timestampNs = 1_000L,
            ),
        )

        assertEquals(MonsterRaidInputRejection.UNSUPPORTED_ACTION, (result as MonsterRaidInputResult.Rejected).reason)
        assertEquals(before, game.snapshot)
    }

    @Test
    fun dualUltimateRequiresBothPlayersWithinSixHundredMillisecondsInPhaseThree() {
        val raid = Harness(GameMode.DUAL)
        raid.attackUntilBossHealthAtMost(MonsterRaidGameSession.BOSS_PHASE_3_THRESHOLD)
        while (raid.game.snapshot.teamCharge < MonsterRaidGameSession.MAX_TEAM_CHARGE) raid.advance(1)
        val before = raid.game.snapshot.enemyHealth
        val timestamp = raid.nextTimestamp(PlayerId.P1)

        raid.submit(PlayerId.P1, MotionType.TEAM_ULTIMATE, timestamp)
        raid.submit(PlayerId.P2, MotionType.TEAM_ULTIMATE, timestamp + 600_000_000L)
        raid.advance(1)

        assertTrue(raid.game.snapshot.enemyHealth < before)
        assertEquals(0, raid.game.snapshot.teamCharge)
        assertEquals(null, raid.game.snapshot.pendingUltimatePlayer)
        assertTrue(raid.player(PlayerId.P1).score > 0)
        assertTrue(raid.player(PlayerId.P2).score > 0)
    }

    @Test
    fun dualUltimateOutsideWindowDoesNoDamageAndCanRetry() {
        val raid = Harness(GameMode.DUAL)
        raid.attackUntilBossHealthAtMost(MonsterRaidGameSession.BOSS_PHASE_3_THRESHOLD)
        while (raid.game.snapshot.teamCharge < MonsterRaidGameSession.MAX_TEAM_CHARGE) raid.advance(1)
        val before = raid.game.snapshot.enemyHealth
        val timestamp = raid.nextTimestamp(PlayerId.P1)

        raid.submit(PlayerId.P1, MotionType.TEAM_ULTIMATE, timestamp)
        raid.submit(PlayerId.P2, MotionType.TEAM_ULTIMATE, timestamp + 600_000_001L)
        raid.advance(1)
        assertEquals(before, raid.game.snapshot.enemyHealth)
        assertEquals(MonsterRaidGameSession.MAX_TEAM_CHARGE, raid.game.snapshot.teamCharge)

        raid.submit(PlayerId.P1, MotionType.TEAM_ULTIMATE, timestamp + 700_000_000L)
        raid.submit(PlayerId.P2, MotionType.TEAM_ULTIMATE, timestamp + 700_000_001L)
        raid.advance(1)
        assertTrue(raid.game.snapshot.enemyHealth < before)
    }

    @Test
    fun dualRaidCanReachVictoryWithBothRolesContributing() {
        val raid = Harness(GameMode.DUAL)
        while (raid.game.snapshot.stage != MonsterRaidStage.RESULT) {
            listOf(PlayerId.P1, PlayerId.P2).forEach { playerId ->
                if (raid.game.snapshot.stage == MonsterRaidStage.RESULT) return@forEach
                val teammate = if (playerId == PlayerId.P1) PlayerId.P2 else PlayerId.P1
                if (raid.player(playerId).downed) return@forEach
                if (raid.player(teammate).downed) {
                    raid.submit(playerId, MotionType.MONSTER_REVIVE)
                    raid.advance(1)
                } else {
                    raid.attackAndRearm(playerId)
                }
            }
        }

        assertEquals(MonsterRaidOutcome.VICTORY, raid.game.snapshot.outcome)
        assertTrue(raid.player(PlayerId.P1).score > 0)
        assertTrue(raid.player(PlayerId.P2).score > 0)
    }

    @Test
    fun vanguardCanReviveDownedRangerWithoutCorruptingEncounter() {
        val raid = Harness(GameMode.DUAL)
        while (raid.game.snapshot.stage != MonsterRaidStage.BOSS) raid.attackAndRearm(PlayerId.P2)

        while (!raid.player(PlayerId.P2).downed) {
            if (raid.game.snapshot.bossAttack != null) {
                raid.submit(PlayerId.P1, MotionType.MONSTER_BLOCK)
            }
            raid.advance(1)
        }
        val stageBefore = raid.game.snapshot.stage
        val enemyHealthBefore = raid.game.snapshot.enemyHealth
        raid.submit(PlayerId.P1, MotionType.MONSTER_BLOCK)
        raid.advance(1)
        assertTrue(raid.player(PlayerId.P2).downed)
        raid.submit(PlayerId.P1, MotionType.MONSTER_REVIVE)
        raid.advance(1)

        assertFalse(raid.player(PlayerId.P2).downed)
        assertTrue(raid.player(PlayerId.P2).health > 0)
        assertEquals(1, raid.player(PlayerId.P1).revivesPerformed)
        assertEquals(1, raid.player(PlayerId.P2).receivedRevives)
        assertEquals(stageBefore, raid.game.snapshot.stage)
        assertEquals(enemyHealthBefore, raid.game.snapshot.enemyHealth)
    }

    @Test
    fun simultaneousInputsAreIndependentAndLeavePausePreservesExactlyOnceState() {
        val raid = Harness(GameMode.DUAL)
        val sameTimestamp = 10_000L
        raid.submit(PlayerId.P2, MotionType.PUNCH_JAB, sameTimestamp)
        raid.submit(PlayerId.P1, MotionType.PUNCH_JAB, sameTimestamp)

        val paused = raid.game.pause(PauseReason.POSE_LOST)
        assertEquals(SessionStatus.PAUSED, paused.status)
        assertTrue(raid.player(PlayerId.P1).score > 0)
        assertTrue(raid.player(PlayerId.P2).score > 0)
        val exact = raid.game.snapshot
        raid.advance(5)
        assertEquals(exact, raid.game.snapshot)
        assertTrue(raid.game.resume())
        assertFalse(raid.game.snapshot.paused)
    }

    @Test
    fun cameraRecalibrationPreservesEncounterDropsOldQueueAndFencesOldEpoch() {
        val game = MonsterRaidGameSession.start("raid-recalibration", 17L, 2, GameMode.DUAL)
        assertTrue(
            game.accept(raidEvent(game, PlayerId.P1, 0, MotionType.PUNCH_JAB, 2, 1_000L))
                is MonsterRaidInputResult.Queued,
        )
        val before = game.snapshot

        val migrated = game.recalibrate(3)

        assertEquals(3, migrated.calibrationRevision)
        assertEquals(SessionStatus.PAUSED, migrated.status)
        assertEquals(PauseReason.CAMERA_SWITCH, migrated.pauseReason)
        assertEquals(before.simulationTick, migrated.simulationTick)
        assertEquals(before.stage, migrated.stage)
        assertEquals(before.waveIndex, migrated.waveIndex)
        assertEquals(before.enemyHealth, migrated.enemyHealth)
        assertEquals(before.teamScore, migrated.teamScore)
        assertEquals(before.players, migrated.players)
        assertEquals(
            before.copy(
                calibrationRevision = 3,
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.CAMERA_SWITCH,
                pendingUltimatePlayer = null,
                pendingUltimateTimestampNs = null,
            ),
            migrated,
        )
        assertTrue(game.resume())
        game.advanceTicks(1)
        assertEquals(before.enemyHealth, game.snapshot.enemyHealth)

        assertEquals(
            MonsterRaidInputRejection.CONTRACT_REJECTED,
            (
                game.accept(raidEvent(game, PlayerId.P1, 1, MotionType.PUNCH_JAB, 2, 2_000L))
                    as MonsterRaidInputResult.Rejected
            ).reason,
        )
        assertEquals(
            MonsterRaidInputRejection.CONTRACT_REJECTED,
            (
                game.accept(raidEvent(game, PlayerId.P1, 0, MotionType.PUNCH_JAB, 3, 2_000L))
                    as MonsterRaidInputResult.Rejected
            ).reason,
        )
        assertTrue(
            game.accept(raidEvent(game, PlayerId.P1, 1, MotionType.PUNCH_JAB, 3, 2_000L))
                is MonsterRaidInputResult.Queued,
        )
        assertTrue(runCatching { game.recalibrate(3) }.isFailure)
        assertTrue(runCatching { game.recalibrate(-1) }.isFailure)
    }

    private fun raidEvent(
        game: MonsterRaidGameSession,
        playerId: PlayerId,
        sequence: Long,
        type: MotionType,
        calibrationRevision: Int,
        timestampNs: Long,
    ): MotionEventEnvelope {
        val eventId = (DeterministicEventId.create(game.snapshot.sessionId, playerId, sequence) as
            com.motionarcade.core.contract.ContractResult.Valid).value
        return MotionEventEnvelope(
            eventId = eventId,
            sessionId = game.snapshot.sessionId,
            playerId = playerId,
            sequenceNumber = sequence,
            type = type,
            quality = 1f,
            confidence = 1f,
            eventTimestampNs = timestampNs,
            calibrationRevision = calibrationRevision,
            source = InputSource.FIXTURE,
            metadata = emptyMap(),
        )
    }

    private class Harness(mode: GameMode) {
        val game = MonsterRaidGameSession.start("raid-${mode.name.lowercase()}", 17L, 2, mode)
        private val sequence = mutableMapOf(PlayerId.P1 to 0L, PlayerId.P2 to 0L)
        private val timestamp = mutableMapOf(PlayerId.P1 to 1_000L, PlayerId.P2 to 1_000L)

        fun player(id: PlayerId): MonsterRaidPlayerState = game.snapshot.players.getValue(id)

        fun nextTimestamp(playerId: PlayerId): Long = timestamp.getValue(playerId) + 1_000L

        fun submit(playerId: PlayerId, type: MotionType, explicitTimestamp: Long? = null) {
            val nextSequence = sequence.getValue(playerId)
            val nextTimestamp = explicitTimestamp ?: nextTimestamp(playerId)
            timestamp[playerId] = nextTimestamp
            sequence[playerId] = nextSequence + 1L
            val eventId = (DeterministicEventId.create(game.snapshot.sessionId, playerId, nextSequence) as
                com.motionarcade.core.contract.ContractResult.Valid).value
            assertTrue(
                game.accept(
                    MotionEventEnvelope(
                        eventId = eventId,
                        sessionId = game.snapshot.sessionId,
                        playerId = playerId,
                        sequenceNumber = nextSequence,
                        type = type,
                        quality = 1f,
                        confidence = 1f,
                        eventTimestampNs = nextTimestamp,
                        calibrationRevision = 2,
                        source = InputSource.FIXTURE,
                        metadata = emptyMap(),
                    ),
                ) is MonsterRaidInputResult.Queued,
            )
        }

        fun advance(ticks: Int) {
            repeat(ticks) { game.advanceTicks(1) }
        }

        fun attackAndRearm(playerId: PlayerId) {
            if (player(playerId).downed) return
            submit(playerId, MotionType.PUNCH_HOOK)
            advance(1)
            if (game.snapshot.stage == MonsterRaidStage.RESULT) return
            submit(playerId, MotionType.MONSTER_BLOCK)
            advance(2)
        }

        fun attackUntilBossHealthAtMost(target: Int) {
            while (game.snapshot.stage != MonsterRaidStage.BOSS) attackAndRearm(PlayerId.P2)
            while (
                game.snapshot.stage == MonsterRaidStage.BOSS &&
                game.snapshot.enemyHealth > target
            ) {
                if (player(PlayerId.P2).downed) {
                    submit(PlayerId.P1, MotionType.MONSTER_REVIVE)
                    advance(1)
                }
                attackAndRearm(PlayerId.P2)
                if (game.snapshot.bossAttack != null && !player(PlayerId.P1).downed) {
                    submit(PlayerId.P1, MotionType.MONSTER_BLOCK)
                    advance(1)
                }
            }
        }
    }
}
