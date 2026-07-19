package com.motionarcade.games.monster

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterGameSessionTest {
    @Test
    fun queuedSemanticStrikeChangesStateOnlyOnTheNextTick() {
        val game = game()
        val before = game.snapshot

        val queued = game.accept(event(game, 0, MotionType.PUNCH_JAB))

        assertTrue(queued is MonsterInputResult.Queued)
        assertEquals(before.bossHealth, game.snapshot.bossHealth)
        assertEquals(0L, game.snapshot.simulationTick)

        val after = game.advanceTicks(1)

        assertEquals(151, after.bossHealth)
        assertEquals(9, after.lastPlayerDamage)
        assertTrue(after.requiresRearm)
        assertEquals(0L, after.lastAppliedSequence)
    }

    @Test
    fun repeatedStrikeNeedsBlockRearmAndCannotDealContinuousDamage() {
        val game = game()

        queueAndAdvance(game, 0, MotionType.PUNCH_JAB)
        val afterFirstStrike = game.snapshot
        queueAndAdvance(game, 1, MotionType.PUNCH_HOOK)
        val afterRepeatedStrike = game.snapshot
        queueAndAdvance(game, 2, MotionType.MONSTER_BLOCK)

        assertEquals(afterFirstStrike.bossHealth, afterRepeatedStrike.bossHealth)
        assertEquals(1, afterRepeatedStrike.ignoredActionCount)
        assertFalse(game.snapshot.requiresRearm)
        advance(game, 2)
        queueAndAdvance(game, 3, MotionType.PUNCH_HOOK)
        assertTrue(game.snapshot.bossHealth < afterRepeatedStrike.bossHealth)
    }

    @Test
    fun blockReducesTelegraphedClawDamage() {
        val game = game(seed = 0L)
        advance(game, 12)
        assertEquals(MonsterBossAttack.CLAW, game.snapshot.bossTelegraph)

        queueAndAdvance(game, 0, MotionType.MONSTER_BLOCK)
        advance(game, 4)

        assertEquals(97, game.snapshot.playerHealth)
        assertEquals(1, game.snapshot.blockedAttackCount)
    }

    @Test
    fun chargedTeamUltimateCountersBarrierOnlyWhenTheWindowIsLive() {
        val game = game(seed = 1L)

        advance(game, 48)
        assertEquals(MonsterBossAttack.BARRIER, game.snapshot.bossTelegraph)
        advance(game, 3)
        assertEquals(100, game.snapshot.partnerCharge)
        assertEquals(2, game.snapshot.bossTelegraphTicksRemaining)

        queueAndAdvance(game, 0, MotionType.TEAM_ULTIMATE)

        assertEquals(1, game.snapshot.counteredBarrierCount)
        assertEquals(0, game.snapshot.lastBossDamage)
        assertEquals(0, game.snapshot.partnerCharge)
        assertTrue(game.snapshot.bossHealth <= MonsterGameSession.BOSS_MAX_HEALTH - 36)
    }

    @Test
    fun speedMetadataAloneCannotProduceMaximumDamage() {
        val game = game()

        queueAndAdvance(
            game,
            0,
            MotionType.PUNCH_JAB,
            quality = 0.5f,
            confidence = 0.5f,
            metadata = mapOf("speed" to 0.99f),
        )

        assertEquals(6, game.snapshot.lastPlayerDamage)
        assertEquals(154, game.snapshot.bossHealth)
        queueAndAdvance(game, 1, MotionType.MONSTER_BLOCK)
        advance(game, 2)
        queueAndAdvance(game, 2, MotionType.PUNCH_JAB, quality = 1f, confidence = 1f)
        assertEquals(9, game.snapshot.lastPlayerDamage)
    }

    @Test
    fun pauseFreezesTimerClearsQueuedInputsAndRequiresExplicitResume() {
        val game = game()
        assertTrue(game.accept(event(game, 0, MotionType.PUNCH_JAB)) is MonsterInputResult.Queued)
        val paused = game.pause(PauseReason.USER)

        val afterAdvance = game.advanceTicks(5)
        val whilePaused = game.accept(event(game, 1, MotionType.PUNCH_JAB))

        assertEquals(SessionStatus.PAUSED, paused.status)
        assertEquals(paused.simulationTick, afterAdvance.simulationTick)
        assertEquals(MonsterGameSession.BOSS_MAX_HEALTH, afterAdvance.bossHealth)
        assertTrue(whilePaused is MonsterInputResult.Ignored)
        assertTrue(game.resume())
        assertFalse(game.snapshot.paused)
    }

    @Test
    fun wrongPlayerUnsupportedActionAndDuplicateContractAreRejectedBeforeMutation() {
        val game = game()
        val wrongPlayer = event(game, 0, MotionType.PUNCH_JAB).copy(playerId = PlayerId.P2)
        val unsupported = event(game, 0, MotionType.FISH_CAST)
        val valid = event(game, 1, MotionType.PUNCH_JAB)

        assertEquals(
            MonsterInputRejection.WRONG_PLAYER,
            (game.accept(wrongPlayer) as MonsterInputResult.Rejected).reason,
        )
        assertEquals(
            MonsterInputRejection.UNSUPPORTED_ACTION,
            (game.accept(unsupported) as MonsterInputResult.Rejected).reason,
        )
        assertTrue(game.accept(valid) is MonsterInputResult.Queued)
        assertEquals(
            MonsterInputRejection.CONTRACT_REJECTED,
            (game.accept(valid) as MonsterInputResult.Rejected).reason,
        )
        assertEquals(MonsterGameSession.BOSS_MAX_HEALTH, game.snapshot.bossHealth)
    }

    @Test
    fun dualModeAcceptsIndependentP1AndP2Sequences() {
        val game =
            MonsterGameSession.start(
                sessionId = "monster-dual-input-test",
                seed = 17L,
                calibrationRevision = 0,
                mode = GameMode.DUAL,
            )
        val p1 = event(game, 0, MotionType.MONSTER_BLOCK)
        val p2 = p1.copy(eventId = "${game.snapshot.sessionId}/P2/0", playerId = PlayerId.P2)

        assertEquals(GameMode.DUAL, game.snapshot.mode)
        assertTrue(game.accept(p1) is MonsterInputResult.Queued)
        assertTrue(game.accept(p2) is MonsterInputResult.Queued)
    }

    @Test
    fun dualModePreservesSameTimestampStrikesWithSeparateScoresAndDeterministicPlayerOrder() {
        val game =
            MonsterGameSession.start(
                sessionId = "monster-dual-simultaneous-test",
                seed = 17L,
                calibrationRevision = 0,
                mode = GameMode.DUAL,
            )
        val sameTimestamp = MonsterGameSession.FIXED_STEP_NS
        val p1 = event(game, 8, MotionType.PUNCH_JAB, playerId = PlayerId.P1, eventTimestampNs = sameTimestamp)
        val p2 = event(game, 2, MotionType.PUNCH_JAB, playerId = PlayerId.P2, eventTimestampNs = sameTimestamp)

        assertTrue(game.accept(p2) is MonsterInputResult.Queued)
        assertTrue(game.accept(p1) is MonsterInputResult.Queued)
        val after = game.advanceTicks(1)

        assertEquals(142, after.bossHealth)
        assertEquals(9, after.players.getValue(PlayerId.P1).score)
        assertEquals(9, after.players.getValue(PlayerId.P2).score)
        assertEquals(after.players.getValue(PlayerId.P1).score, after.score)
        assertEquals(8L, after.players.getValue(PlayerId.P1).lastAppliedSequence)
        assertEquals(2L, after.players.getValue(PlayerId.P2).lastAppliedSequence)
        assertEquals(2L, after.lastAppliedSequence)
    }

    @Test
    fun dualSameTimestampStateDoesNotDependOnCallbackArrivalOrder() {
        val first = MonsterGameSession.start("monster-dual-order-test", 17L, 0, GameMode.DUAL)
        val second = MonsterGameSession.start("monster-dual-order-test", 17L, 0, GameMode.DUAL)
        val timestamp = MonsterGameSession.FIXED_STEP_NS
        val firstP1 = event(first, 8, MotionType.PUNCH_JAB, playerId = PlayerId.P1, eventTimestampNs = timestamp)
        val firstP2 = event(first, 2, MotionType.PUNCH_JAB, playerId = PlayerId.P2, eventTimestampNs = timestamp)
        val secondP1 = event(second, 8, MotionType.PUNCH_JAB, playerId = PlayerId.P1, eventTimestampNs = timestamp)
        val secondP2 = event(second, 2, MotionType.PUNCH_JAB, playerId = PlayerId.P2, eventTimestampNs = timestamp)

        first.accept(firstP2)
        first.accept(firstP1)
        second.accept(secondP1)
        second.accept(secondP2)

        assertEquals(first.advanceTicks(1), second.advanceTicks(1))
    }

    @Test
    fun dualTeamUltimateRequiresBothPlayersWithinThe600msSynchronizationWindow() {
        val game = dualChargedBarrierGame()
        val baseTimestamp = 10_000_000_000L
        val p1 = event(game, 7, MotionType.TEAM_ULTIMATE, playerId = PlayerId.P1, eventTimestampNs = baseTimestamp)
        val p2 = event(
            game,
            3,
            MotionType.TEAM_ULTIMATE,
            playerId = PlayerId.P2,
            eventTimestampNs = baseTimestamp + MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_NS,
        )

        assertTrue(game.accept(p2) is MonsterInputResult.Queued)
        assertTrue(game.accept(p1) is MonsterInputResult.Queued)
        val after = game.advanceTicks(1)

        assertEquals(124, after.bossHealth)
        assertEquals(0, after.partnerCharge)
        assertEquals(1, after.counteredBarrierCount)
        assertEquals(18, after.players.getValue(PlayerId.P1).score)
        assertEquals(18, after.players.getValue(PlayerId.P2).score)
        assertEquals(null, after.pendingTeamUltimatePlayer)
        assertEquals(null, after.pendingTeamUltimateTimestampNs)
    }

    @Test
    fun dualTeamUltimateOutsideThe600msWindowStaysPendingAndDoesNotSpendCharge() {
        val game = dualChargedBarrierGame()
        val baseTimestamp = 10_000_000_000L
        assertTrue(
            game.accept(
                event(
                    game,
                    7,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P1,
                    eventTimestampNs = baseTimestamp,
                ),
            )
                is MonsterInputResult.Queued,
        )
        assertTrue(
            game.accept(
                event(
                    game,
                    3,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P2,
                    eventTimestampNs = baseTimestamp + MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_NS + 1L,
                ),
            ) is MonsterInputResult.Queued,
        )
        val after = game.advanceTicks(1)

        assertEquals(MonsterGameSession.BOSS_MAX_HEALTH, after.bossHealth)
        assertEquals(MonsterGameSession.MAX_PARTNER_CHARGE, after.partnerCharge)
        assertEquals(0, after.counteredBarrierCount)
        assertEquals(PlayerId.P2, after.pendingTeamUltimatePlayer)
        assertEquals(baseTimestamp + MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_NS + 1L, after.pendingTeamUltimateTimestampNs)
        advance(game, MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_TICKS)
        assertEquals(null, game.snapshot.pendingTeamUltimatePlayer)
        assertEquals(null, game.snapshot.pendingTeamUltimateTimestampNs)
        assertEquals(null, game.snapshot.pendingTeamUltimateExpiresAtTick)
    }

    @Test
    fun dualTeamUltimateUsesEventTimeWhenCounterpartArrivesAfterVisibleArmExpiry() {
        val game = dualChargedBarrierGame()
        val baseTimestamp = 10_000_000_000L
        assertTrue(
            game.accept(
                event(
                    game,
                    7,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P1,
                    eventTimestampNs = baseTimestamp,
                ),
            ) is MonsterInputResult.Queued,
        )
        game.advanceTicks(1)
        assertEquals(PlayerId.P1, game.snapshot.pendingTeamUltimatePlayer)

        // The UI cue expires after six simulation ticks, but the semantic timestamp must remain
        // available because 600ms measures capture time, not callback completion time.
        advance(game, MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_TICKS)
        assertEquals(null, game.snapshot.pendingTeamUltimatePlayer)

        assertTrue(
            game.accept(
                event(
                    game,
                    3,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P2,
                    eventTimestampNs = baseTimestamp + MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_NS,
                ),
            ) is MonsterInputResult.Queued,
        )
        val after = game.advanceTicks(1)

        assertEquals(134, after.bossHealth)
        assertEquals(0, after.partnerCharge)
        assertEquals(13, after.players.getValue(PlayerId.P1).score)
        assertEquals(13, after.players.getValue(PlayerId.P2).score)
        assertEquals(null, after.pendingTeamUltimatePlayer)
    }

    @Test
    fun dualTeamUltimateMatchesAnEarlierObservationWhenTheSamePlayerRetriesLater() {
        val game = dualChargedBarrierGame()
        val baseTimestamp = 10_000_000_000L
        assertTrue(
            game.accept(
                event(game, 7, MotionType.TEAM_ULTIMATE, playerId = PlayerId.P1, eventTimestampNs = baseTimestamp),
            ) is MonsterInputResult.Queued,
        )
        game.advanceTicks(1)
        assertTrue(
            game.accept(
                event(
                    game,
                    8,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P1,
                    eventTimestampNs = baseTimestamp + MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_NS + 300_000_000L,
                ),
            ) is MonsterInputResult.Queued,
        )
        game.advanceTicks(1)

        assertTrue(
            game.accept(
                event(game, 3, MotionType.TEAM_ULTIMATE, playerId = PlayerId.P2, eventTimestampNs = baseTimestamp),
            ) is MonsterInputResult.Queued,
        )
        val after = game.advanceTicks(1)

        assertEquals(134, after.bossHealth)
        assertEquals(0, after.partnerCharge)
        assertEquals(13, after.players.getValue(PlayerId.P1).score)
        assertEquals(13, after.players.getValue(PlayerId.P2).score)
        assertEquals(8L, after.players.getValue(PlayerId.P1).lastAppliedSequence)
        assertEquals(3L, after.players.getValue(PlayerId.P2).lastAppliedSequence)
    }

    @Test
    fun dualTeamUltimateRejectsAnOlderSecondEventOutsideThe600msWindow() {
        val game = dualChargedBarrierGame()
        val baseTimestamp = 10_000_000_000L
        assertTrue(
            game.accept(
                event(
                    game,
                    3,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P2,
                    eventTimestampNs = baseTimestamp + MonsterGameSession.TEAM_ULTIMATE_SYNC_WINDOW_NS + 1L,
                ),
            ) is MonsterInputResult.Queued,
        )
        game.advanceTicks(1)
        assertEquals(PlayerId.P2, game.snapshot.pendingTeamUltimatePlayer)

        assertTrue(
            game.accept(
                event(
                    game,
                    7,
                    MotionType.TEAM_ULTIMATE,
                    playerId = PlayerId.P1,
                    eventTimestampNs = baseTimestamp,
                ),
            ) is MonsterInputResult.Queued,
        )
        val after = game.advanceTicks(1)

        assertEquals(MonsterGameSession.BOSS_MAX_HEALTH, after.bossHealth)
        assertEquals(MonsterGameSession.MAX_PARTNER_CHARGE, after.partnerCharge)
        assertEquals(PlayerId.P1, after.pendingTeamUltimatePlayer)
    }

    @Test
    fun queueOverflowPausesAndDropsPendingActionsInsteadOfContinuingSilently() {
        val game = game()
        repeat(MonsterGameSession.INPUT_QUEUE_CAPACITY) { sequence ->
            assertTrue(game.accept(event(game, sequence.toLong(), MotionType.PUNCH_JAB)) is MonsterInputResult.Queued)
        }

        val overflow = game.accept(event(game, MonsterGameSession.INPUT_QUEUE_CAPACITY.toLong(), MotionType.PUNCH_JAB))

        assertEquals(MonsterInputRejection.QUEUE_OVERFLOW, (overflow as MonsterInputResult.Rejected).reason)
        assertEquals(SessionStatus.PAUSED, game.snapshot.status)
        assertEquals(PauseReason.EVENT_QUEUE_OVERFLOW, game.snapshot.pauseReason)
        assertEquals(MonsterGameSession.BOSS_MAX_HEALTH, game.advanceTicks(1).bossHealth)
    }

    @Test
    fun sameSeedAndSemanticEventSequenceProduceTheSameEncounterState() {
        val first = game(seed = 37L)
        val second = game(seed = 37L)
        val actions = listOf(
            MotionType.PUNCH_JAB,
            MotionType.MONSTER_BLOCK,
            MotionType.PUNCH_HOOK,
            MotionType.TEAM_ULTIMATE,
            MotionType.MONSTER_BLOCK,
        )

        actions.forEachIndexed { index, type ->
            queueAndAdvance(first, index.toLong(), type)
            queueAndAdvance(second, index.toLong(), type)
        }
        advance(first, 5)
        advance(second, 5)

        assertEquals(first.snapshot, second.snapshot)
    }

    private fun queueAndAdvance(
        game: MonsterGameSession,
        sequence: Long,
        type: MotionType,
        quality: Float = 1f,
        confidence: Float = 1f,
        metadata: Map<String, Float> = emptyMap(),
    ) {
        assertTrue(game.accept(event(game, sequence, type, quality, confidence, metadata)) is MonsterInputResult.Queued)
        game.advanceTicks(1)
    }

    private fun advance(game: MonsterGameSession, ticks: Int) {
        var remaining = ticks
        while (remaining > 0) {
            val step = minOf(remaining, MonsterGameSession.MAX_CATCH_UP_TICKS)
            game.advanceTicks(step)
            remaining -= step
        }
    }

    private fun event(
        game: MonsterGameSession,
        sequence: Long,
        type: MotionType,
        quality: Float = 1f,
        confidence: Float = 1f,
        metadata: Map<String, Float> = emptyMap(),
        playerId: PlayerId = PlayerId.P1,
        eventTimestampNs: Long = sequence * MonsterGameSession.FIXED_STEP_NS,
    ): MotionEventEnvelope = MotionEventEnvelope(
        eventId = "${game.snapshot.sessionId}/${playerId.name}/$sequence",
        sessionId = game.snapshot.sessionId,
        playerId = playerId,
        sequenceNumber = sequence,
        type = type,
        quality = quality,
        confidence = confidence,
        eventTimestampNs = eventTimestampNs,
        calibrationRevision = 0,
        source = InputSource.FIXTURE,
        metadata = metadata,
    )

    private fun game(seed: Long = 17L): MonsterGameSession =
        MonsterGameSession.start(sessionId = "monster-test-$seed", seed = seed, calibrationRevision = 0)

    private fun dualChargedBarrierGame(): MonsterGameSession =
        MonsterGameSession.start(
            sessionId = "monster-dual-ultimate-test",
            seed = 1L,
            calibrationRevision = 0,
            mode = GameMode.DUAL,
        ).also { game ->
            advance(game, 48)
            assertEquals(MonsterBossAttack.BARRIER, game.snapshot.bossTelegraph)
            advance(game, 3)
            assertEquals(MonsterGameSession.MAX_PARTNER_CHARGE, game.snapshot.partnerCharge)
            assertEquals(2, game.snapshot.bossTelegraphTicksRemaining)
        }
}
