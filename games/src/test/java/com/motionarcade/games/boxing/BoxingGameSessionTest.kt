package com.motionarcade.games.boxing

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

class BoxingGameSessionTest {
    @Test
    fun queuedSemanticPunchChangesStateOnlyOnTheNextTick() {
        val game = game()
        val before = game.snapshot

        val queued = game.accept(event(game, 0, MotionType.PUNCH_JAB))

        assertTrue(queued is BoxingInputResult.Queued)
        assertEquals(before.opponentHealth, game.snapshot.opponentHealth)
        assertEquals(0L, game.snapshot.simulationTick)

        val after = game.advanceTicks(1)

        assertEquals(91, after.opponentHealth)
        assertEquals(9, after.lastPlayerDamage)
        assertTrue(after.requiresReturnToGuard)
        assertEquals(0L, after.lastAppliedSequence)
    }

    @Test
    fun repeatedStrikesWithoutReturnToGuardCannotCreateContinuousDamage() {
        val game = game()

        queueAndAdvance(game, 0, MotionType.PUNCH_JAB)
        val afterFirstStrike = game.snapshot
        queueAndAdvance(game, 1, MotionType.PUNCH_HOOK)
        val afterRepeatedStrike = game.snapshot
        queueAndAdvance(game, 2, MotionType.BOXING_GUARD)

        assertEquals(afterFirstStrike.opponentHealth, afterRepeatedStrike.opponentHealth)
        assertEquals(1, afterRepeatedStrike.ignoredStrikeCount)
        assertTrue(afterRepeatedStrike.playerStamina < afterFirstStrike.playerStamina)
        advance(game, 2)
        queueAndAdvance(game, 3, MotionType.PUNCH_HOOK)
        assertTrue(game.snapshot.opponentHealth < afterRepeatedStrike.opponentHealth)
        assertTrue(game.snapshot.requiresReturnToGuard)
    }

    @Test
    fun guardAndCorrectDodgeChangeTheAiAttackOutcome() {
        val guardGame = game(seed = 0L)
        advance(guardGame, 12)
        assertEquals(BoxingAiAttack.STRAIGHT, guardGame.snapshot.aiTelegraph)
        queueAndAdvance(guardGame, 0, MotionType.BOXING_GUARD)
        guardGame.advanceTicks(4)

        assertEquals(97, guardGame.snapshot.playerHealth)
        assertEquals(1, guardGame.snapshot.blockedAttackCount)

        val dodgeGame = game(seed = 0L)
        advance(dodgeGame, 12)
        dodgeGame.advanceTicks(3)
        queueAndAdvance(dodgeGame, 0, MotionType.DODGE_LEFT)
        dodgeGame.advanceTicks(1)

        assertEquals(100, dodgeGame.snapshot.playerHealth)
        assertEquals(1, dodgeGame.snapshot.dodgedAttackCount)
        assertEquals(0, dodgeGame.snapshot.lastReceivedDamage)
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
        assertEquals(94, game.snapshot.opponentHealth)
        queueAndAdvance(game, 1, MotionType.BOXING_GUARD)
        advance(game, 2)
        queueAndAdvance(game, 2, MotionType.PUNCH_JAB, quality = 1f, confidence = 1f)
        assertEquals(9, game.snapshot.lastPlayerDamage)
    }

    @Test
    fun pauseFreezesTimerClearsQueuedInputsAndRequiresAnExplicitResume() {
        val game = game()
        assertTrue(game.accept(event(game, 0, MotionType.PUNCH_JAB)) is BoxingInputResult.Queued)
        val paused = game.pause(PauseReason.USER)

        val afterAdvance = game.advanceTicks(5)
        val whilePaused = game.accept(event(game, 1, MotionType.PUNCH_JAB))

        assertEquals(SessionStatus.PAUSED, paused.status)
        assertEquals(paused.simulationTick, afterAdvance.simulationTick)
        assertEquals(100, afterAdvance.opponentHealth)
        assertTrue(whilePaused is BoxingInputResult.Ignored)
        assertTrue(game.resume())
        assertFalse(game.snapshot.paused)
    }

    @Test
    fun cameraRecalibrationPreservesRoundDropsOldQueueAndFencesOldEpoch() {
        val game = game()
        queueAndAdvance(game, 0, MotionType.PUNCH_JAB)
        assertTrue(game.accept(event(game, 1, MotionType.BOXING_GUARD)) is BoxingInputResult.Queued)
        val before = game.snapshot

        val migrated = game.recalibrate(1)

        assertEquals(1, migrated.calibrationRevision)
        assertEquals(SessionStatus.PAUSED, migrated.status)
        assertEquals(PauseReason.CAMERA_SWITCH, migrated.pauseReason)
        assertEquals(before.simulationTick, migrated.simulationTick)
        assertEquals(before.playerHealth, migrated.playerHealth)
        assertEquals(before.opponentHealth, migrated.opponentHealth)
        assertEquals(before.score, migrated.score)
        assertEquals(before.roundTicksRemaining, migrated.roundTicksRemaining)
        assertEquals(
            before.copy(
                calibrationRevision = 1,
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.CAMERA_SWITCH,
            ),
            migrated,
        )
        assertTrue(game.resume())
        game.advanceTicks(1)
        assertTrue(game.snapshot.requiresReturnToGuard)

        val oldRevision = event(game, 2, MotionType.BOXING_GUARD)
        val oldSequence = event(game, 1, MotionType.BOXING_GUARD).copy(calibrationRevision = 1)
        val fresh = event(game, 2, MotionType.BOXING_GUARD).copy(calibrationRevision = 1)
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (game.accept(oldRevision) as BoxingInputResult.Rejected).reason,
        )
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (game.accept(oldSequence) as BoxingInputResult.Rejected).reason,
        )
        assertTrue(game.accept(fresh) is BoxingInputResult.Queued)
        assertTrue(runCatching { game.recalibrate(1) }.isFailure)
        assertTrue(runCatching { game.recalibrate(-1) }.isFailure)
    }

    @Test
    fun dualCameraRecalibrationKeepsPlayerSequenceFencesIndependent() {
        val game = BoxingGameSession.start("boxing-dual-recalibration", 17L, 0, GameMode.DUAL)
        assertTrue(
            game.accept(event(game, 8, MotionType.BOXING_GUARD, playerId = PlayerId.P1))
                is BoxingInputResult.Queued,
        )
        assertTrue(
            game.accept(event(game, 2, MotionType.BOXING_GUARD, playerId = PlayerId.P2))
                is BoxingInputResult.Queued,
        )

        game.recalibrate(1)
        assertTrue(game.resume())

        assertTrue(
            game.accept(
                event(game, 3, MotionType.BOXING_GUARD, playerId = PlayerId.P2)
                    .copy(calibrationRevision = 1),
            ) is BoxingInputResult.Queued,
        )
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (
                game.accept(
                    event(game, 8, MotionType.BOXING_GUARD, playerId = PlayerId.P1)
                        .copy(calibrationRevision = 1),
                ) as BoxingInputResult.Rejected
            ).reason,
        )
        assertTrue(
            game.accept(
                event(game, 9, MotionType.BOXING_GUARD, playerId = PlayerId.P1)
                    .copy(calibrationRevision = 1),
            ) is BoxingInputResult.Queued,
        )
    }

    @Test
    fun cameraRecalibrationRejectsTimestampRewindEvenWhenSequenceIncreases() {
        val game = game()
        val acceptedTimestamp = 900L
        assertTrue(
            game.accept(
                event(
                    game,
                    sequence = 8,
                    type = MotionType.BOXING_GUARD,
                    eventTimestampNs = acceptedTimestamp,
                ),
            ) is BoxingInputResult.Queued,
        )

        val migrated = game.recalibrate(1)
        assertEquals(acceptedTimestamp, migrated.acceptedEventTimestampWatermarkNs)
        assertTrue(game.resume())

        val rewind = event(
            game,
            sequence = 9,
            type = MotionType.BOXING_GUARD,
            eventTimestampNs = acceptedTimestamp - 1,
        ).copy(calibrationRevision = 1)
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (game.accept(rewind) as BoxingInputResult.Rejected).reason,
        )

        val fresh = rewind.copy(
            eventId = "${game.snapshot.sessionId}/${PlayerId.P1}/10",
            sequenceNumber = 10,
            eventTimestampNs = acceptedTimestamp,
        )
        assertTrue(game.accept(fresh) is BoxingInputResult.Queued)
    }

    @Test
    fun completedResultMigratesCalibrationRevisionWithoutReopeningRound() {
        val game = game()
        advance(game, BoxingGameSession.ROUND_TICKS)
        val completed = game.snapshot
        assertEquals(BoxingPhase.RESULT, completed.phase)
        assertEquals(SessionStatus.COMPLETED, completed.status)

        val migrated = game.recalibrate(1)

        assertEquals(completed.copy(calibrationRevision = 1), migrated)
        assertEquals(SessionStatus.COMPLETED, migrated.status)
        assertFalse(game.resume())
        assertEquals(migrated, BoxingGameSession.restore(migrated).snapshot)
    }

    @Test
    fun dualCheckpointRestoreKeepsExactPlayerTimestampAndSequenceFences() {
        val game = BoxingGameSession.start("boxing-dual-fence-restore", 17L, 0, GameMode.DUAL)
        assertTrue(
            game.accept(
                event(
                    game,
                    sequence = 8,
                    type = MotionType.BOXING_GUARD,
                    playerId = PlayerId.P1,
                    eventTimestampNs = 800L,
                ),
            ) is BoxingInputResult.Queued,
        )
        assertTrue(
            game.accept(
                event(
                    game,
                    sequence = 2,
                    type = MotionType.BOXING_GUARD,
                    playerId = PlayerId.P2,
                    eventTimestampNs = 200L,
                ),
            ) is BoxingInputResult.Queued,
        )

        val restored = BoxingGameSession.restore(game.checkpointForAppBackground())
        assertTrue(restored.resume())
        assertTrue(
            restored.accept(
                event(
                    restored,
                    sequence = 3,
                    type = MotionType.BOXING_GUARD,
                    playerId = PlayerId.P2,
                    eventTimestampNs = 200L,
                ),
            ) is BoxingInputResult.Queued,
        )
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (
                restored.accept(
                    event(
                        restored,
                        sequence = 9,
                        type = MotionType.BOXING_GUARD,
                        playerId = PlayerId.P1,
                        eventTimestampNs = 799L,
                    ),
                ) as BoxingInputResult.Rejected
            ).reason,
        )
    }

    @Test
    fun checkpointRestoreReturnsPausedPreservesStateAndDropsQueuedInput() {
        val game = game()
        queueAndAdvance(game, 0, MotionType.PUNCH_JAB)
        assertTrue(game.accept(event(game, 1, MotionType.BOXING_GUARD)) is BoxingInputResult.Queued)

        val checkpoint = game.checkpointForAppBackground()
        val restored = BoxingGameSession.restore(checkpoint)

        assertEquals(SessionStatus.PAUSED, restored.snapshot.status)
        assertEquals(PauseReason.APP_BACKGROUND, restored.snapshot.pauseReason)
        assertEquals(checkpoint.opponentHealth, restored.snapshot.opponentHealth)
        assertEquals(checkpoint.simulationTick, restored.snapshot.simulationTick)
        assertTrue(restored.resume())
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (restored.accept(event(restored, 1, MotionType.BOXING_GUARD)) as BoxingInputResult.Rejected).reason,
        )
        restored.advanceTicks(1)
        assertTrue(restored.snapshot.requiresReturnToGuard)
    }

    @Test
    fun restoreForcesRunningCheckpointPausedAndRejectsSemanticCorruption() {
        val running = game().snapshot
        val finishedGame = game(seed = 73L)
        advance(finishedGame, BoxingGameSession.ROUND_TICKS)
        val finished = finishedGame.snapshot

        val restored = BoxingGameSession.restore(running).snapshot

        assertEquals(SessionStatus.PAUSED, restored.status)
        assertEquals(PauseReason.APP_BACKGROUND, restored.pauseReason)
        assertTrue(runCatching { BoxingGameSession.restore(running.copy(gameId = com.motionarcade.core.contract.GameId.FISHING)) }.isFailure)
        assertTrue(runCatching { BoxingGameSession.restore(running.copy(aiTelegraphTicksRemaining = 1)) }.isFailure)
        assertTrue(runCatching { BoxingGameSession.restore(running.copy(lastAppliedSequence = 0)) }.isFailure)
        assertTrue(runCatching { BoxingGameSession.restore(running.copy(roundTicksRemaining = running.roundTicksRemaining - 1)) }.isFailure)
        assertTrue(
            runCatching {
                BoxingGameSession.restore(
                    finished.copy(
                        outcome = if (finished.outcome == BoxingOutcome.WIN) BoxingOutcome.LOSS else BoxingOutcome.WIN,
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun dualRestoreRequiresBothPlayersAndExactP1CompatibilityProjection() {
        val checkpoint = BoxingGameSession.start("boxing-dual-restore", 31L, 2, GameMode.DUAL)
            .checkpointForAppBackground()

        val restored = BoxingGameSession.restore(checkpoint).snapshot

        assertEquals(setOf(PlayerId.P1, PlayerId.P2), restored.players.keys)
        assertEquals(restored.players.getValue(PlayerId.P1).health, restored.playerHealth)
        assertTrue(runCatching { BoxingGameSession.restore(checkpoint.copy(players = checkpoint.players - PlayerId.P2)) }.isFailure)
        assertTrue(runCatching { BoxingGameSession.restore(checkpoint.copy(score = checkpoint.score + 1)) }.isFailure)
    }

    @Test
    fun wrongPlayerUnsupportedActionAndDuplicateContractAreRejectedBeforeMutation() {
        val game = game()
        val wrongPlayer = event(game, 0, MotionType.PUNCH_JAB).copy(playerId = PlayerId.P2)
        val unsupported = event(game, 0, MotionType.FISH_CAST)
        val valid = event(game, 1, MotionType.PUNCH_JAB)

        assertEquals(
            BoxingInputRejection.WRONG_PLAYER,
            (game.accept(wrongPlayer) as BoxingInputResult.Rejected).reason,
        )
        assertEquals(
            BoxingInputRejection.UNSUPPORTED_ACTION,
            (game.accept(unsupported) as BoxingInputResult.Rejected).reason,
        )
        assertTrue(game.accept(valid) is BoxingInputResult.Queued)
        assertEquals(
            BoxingInputRejection.CONTRACT_REJECTED,
            (game.accept(valid) as BoxingInputResult.Rejected).reason,
        )
        assertEquals(100, game.snapshot.opponentHealth)
    }

    @Test
    fun dualModeAcceptsIndependentP1AndP2Sequences() {
        val game =
            BoxingGameSession.start(
                sessionId = "boxing-dual-input-test",
                seed = 17L,
                calibrationRevision = 0,
                mode = GameMode.DUAL,
            )
        val p1 = event(game, 0, MotionType.BOXING_GUARD)
        val p2 = p1.copy(eventId = "${game.snapshot.sessionId}/P2/0", playerId = PlayerId.P2)

        assertEquals(GameMode.DUAL, game.snapshot.mode)
        assertTrue(game.accept(p1) is BoxingInputResult.Queued)
        assertTrue(game.accept(p2) is BoxingInputResult.Queued)
    }

    @Test
    fun dualModePreservesSameTimestampActionsWithSeparateScoresAndDeterministicPlayerOrder() {
        val game =
            BoxingGameSession.start(
                sessionId = "boxing-dual-simultaneous-test",
                seed = 17L,
                calibrationRevision = 0,
                mode = GameMode.DUAL,
            )
        val sameTimestamp = BoxingGameSession.FIXED_STEP_NS
        val p1 = event(game, 8, MotionType.PUNCH_JAB, playerId = PlayerId.P1, eventTimestampNs = sameTimestamp)
        val p2 = event(game, 2, MotionType.PUNCH_JAB, playerId = PlayerId.P2, eventTimestampNs = sameTimestamp)

        // Deliver in the inverse order to prove the simulation uses timestamp then player ID,
        // rather than callback arrival order.
        assertTrue(game.accept(p2) is BoxingInputResult.Queued)
        assertTrue(game.accept(p1) is BoxingInputResult.Queued)
        val after = game.advanceTicks(1)

        assertEquals(91, after.players.getValue(PlayerId.P1).health)
        assertEquals(91, after.players.getValue(PlayerId.P2).health)
        assertEquals(91, after.opponentHealth)
        assertEquals(9, after.players.getValue(PlayerId.P1).score)
        assertEquals(9, after.players.getValue(PlayerId.P2).score)
        assertEquals(after.players.getValue(PlayerId.P1).score, after.score)
        assertEquals(8L, after.players.getValue(PlayerId.P1).lastAppliedSequence)
        assertEquals(2L, after.players.getValue(PlayerId.P2).lastAppliedSequence)
        assertEquals(2L, after.lastAppliedSequence)
    }

    @Test
    fun dualGuardAndDodgeReduceOrAvoidTheOtherPlayersStrike() {
        val guarded = BoxingGameSession.start("boxing-dual-guard", 17L, 0, GameMode.DUAL)
        queueAndAdvance(guarded, 0, MotionType.BOXING_GUARD, playerId = PlayerId.P2)
        queueAndAdvance(guarded, 0, MotionType.PUNCH_HOOK, playerId = PlayerId.P1)
        assertEquals(98, guarded.snapshot.players.getValue(PlayerId.P2).health)
        assertEquals(1, guarded.snapshot.players.getValue(PlayerId.P2).blockedAttackCount)
        assertEquals(2, guarded.snapshot.players.getValue(PlayerId.P1).score)

        listOf(MotionType.DODGE_LEFT, MotionType.DODGE_RIGHT).forEach { dodge ->
            val dodged = BoxingGameSession.start("boxing-dual-${dodge.name}", 17L, 0, GameMode.DUAL)
            queueAndAdvance(dodged, 0, dodge, playerId = PlayerId.P2)
            queueAndAdvance(dodged, 0, MotionType.PUNCH_HOOK, playerId = PlayerId.P1)
            assertEquals(100, dodged.snapshot.players.getValue(PlayerId.P2).health)
            assertEquals(1, dodged.snapshot.players.getValue(PlayerId.P2).dodgedAttackCount)
            assertEquals(0, dodged.snapshot.players.getValue(PlayerId.P1).score)
        }
    }

    @Test
    fun dualKnockoutDeclaresWinnerFromP1PerspectiveWithoutAiAttacks() {
        val game = BoxingGameSession.start("boxing-dual-ko", 17L, 0, GameMode.DUAL)
        var sequence = 0L
        while (game.snapshot.phase == BoxingPhase.ROUND) {
            queueAndAdvance(game, sequence++, MotionType.PUNCH_HOOK, playerId = PlayerId.P1)
            if (game.snapshot.phase == BoxingPhase.RESULT) break
            queueAndAdvance(game, sequence++, MotionType.BOXING_GUARD, playerId = PlayerId.P1)
            game.advanceTicks(2)
        }
        assertEquals(BoxingOutcome.WIN, game.snapshot.outcome)
        assertEquals(0, game.snapshot.players.getValue(PlayerId.P2).health)
        assertEquals(100, game.snapshot.players.getValue(PlayerId.P1).health)
        assertEquals(null, game.snapshot.aiTelegraph)
    }

    @Test
    fun dualSameTickKnockoutResolvesAsDrawAfterBothConfirmedInputs() {
        val fresh = BoxingGameSession.start("boxing-dual-double-ko", 17L, 0, GameMode.DUAL)
        val lowHealth = fresh.snapshot.copy(
            playerHealth = 9,
            opponentHealth = 9,
            players = fresh.snapshot.players.mapValues { (_, player) -> player.copy(health = 9) },
        )
        val game = BoxingGameSession.restore(lowHealth)
        assertTrue(game.resume())
        val sameTimestamp = BoxingGameSession.FIXED_STEP_NS

        game.accept(
            event(game, 1, MotionType.PUNCH_HOOK, playerId = PlayerId.P1, eventTimestampNs = sameTimestamp),
        )
        game.accept(
            event(game, 1, MotionType.PUNCH_HOOK, playerId = PlayerId.P2, eventTimestampNs = sameTimestamp),
        )
        val result = game.advanceTicks(1)

        assertEquals(0, result.players.getValue(PlayerId.P1).health)
        assertEquals(0, result.players.getValue(PlayerId.P2).health)
        assertEquals(BoxingOutcome.DRAW, result.outcome)
    }

    @Test
    fun dualRoundClockDecisionComparesP1AndP2HealthWithoutAiProgression() {
        val game = BoxingGameSession.start("boxing-dual-timeout", 17L, 0, GameMode.DUAL)

        advance(game, BoxingGameSession.ROUND_TICKS)
        val result = game.snapshot

        assertEquals(BoxingPhase.RESULT, result.phase)
        assertEquals(BoxingOutcome.DRAW, result.outcome)
        assertEquals(null, result.aiTelegraph)
        assertEquals(0, result.aiAttackOrdinal)
    }

    @Test
    fun dualRoundClockDecisionSelectsEitherPlayerFromRemainingHealth() {
        listOf(
            Triple(80, 60, BoxingOutcome.WIN),
            Triple(55, 75, BoxingOutcome.LOSS),
        ).forEach { (p1Health, p2Health, expected) ->
            val fresh = BoxingGameSession.start("boxing-dual-decision-$expected", 17L, 0, GameMode.DUAL)
            val checkpoint = fresh.snapshot.copy(
                playerHealth = p1Health,
                opponentHealth = p2Health,
                simulationTick = (BoxingGameSession.ROUND_TICKS - 1).toLong(),
                roundTicksRemaining = 1,
                players = fresh.snapshot.players.mapValues { (playerId, player) ->
                    player.copy(health = if (playerId == PlayerId.P1) p1Health else p2Health)
                },
            )
            val game = BoxingGameSession.restore(checkpoint)
            assertTrue(game.resume())

            val result = game.advanceTicks(1)

            assertEquals(BoxingPhase.RESULT, result.phase)
            assertEquals(expected, result.outcome)
        }
    }

    @Test
    fun dualSameTimestampStateDoesNotDependOnCallbackArrivalOrder() {
        val first = BoxingGameSession.start("boxing-dual-order-test", 17L, 0, GameMode.DUAL)
        val second = BoxingGameSession.start("boxing-dual-order-test", 17L, 0, GameMode.DUAL)
        val timestamp = BoxingGameSession.FIXED_STEP_NS
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
    fun queueOverflowPausesAndDropsPendingActionsInsteadOfContinuingSilently() {
        val game = game()
        repeat(BoxingGameSession.INPUT_QUEUE_CAPACITY) { sequence ->
            assertTrue(game.accept(event(game, sequence.toLong(), MotionType.PUNCH_JAB)) is BoxingInputResult.Queued)
        }

        val overflow = game.accept(event(game, BoxingGameSession.INPUT_QUEUE_CAPACITY.toLong(), MotionType.PUNCH_JAB))

        assertEquals(BoxingInputRejection.QUEUE_OVERFLOW, (overflow as BoxingInputResult.Rejected).reason)
        assertEquals(SessionStatus.PAUSED, game.snapshot.status)
        assertEquals(PauseReason.EVENT_QUEUE_OVERFLOW, game.snapshot.pauseReason)
        assertEquals(100, game.advanceTicks(1).opponentHealth)
    }

    @Test
    fun sameSeedAndSemanticEventSequenceProduceTheSameRoundState() {
        val first = game(seed = 37L)
        val second = game(seed = 37L)
        val actions = listOf(
            MotionType.PUNCH_JAB,
            MotionType.BOXING_GUARD,
            MotionType.DODGE_LEFT,
            MotionType.BOXING_GUARD,
            MotionType.PUNCH_HOOK,
        )

        actions.forEachIndexed { index, type ->
            queueAndAdvance(first, index.toLong(), type)
            queueAndAdvance(second, index.toLong(), type)
        }
        first.advanceTicks(5)
        second.advanceTicks(5)

        assertEquals(first.snapshot, second.snapshot)
    }

    private fun queueAndAdvance(
        game: BoxingGameSession,
        sequence: Long,
        type: MotionType,
        quality: Float = 1f,
        confidence: Float = 1f,
        metadata: Map<String, Float> = emptyMap(),
        playerId: PlayerId = PlayerId.P1,
    ) {
        assertTrue(
            game.accept(event(game, sequence, type, quality, confidence, metadata, playerId)) is BoxingInputResult.Queued,
        )
        game.advanceTicks(1)
    }

    private fun advance(game: BoxingGameSession, ticks: Int) {
        var remaining = ticks
        while (remaining > 0) {
            val step = minOf(remaining, BoxingGameSession.MAX_CATCH_UP_TICKS)
            game.advanceTicks(step)
            remaining -= step
        }
    }

    private fun event(
        game: BoxingGameSession,
        sequence: Long,
        type: MotionType,
        quality: Float = 1f,
        confidence: Float = 1f,
        metadata: Map<String, Float> = emptyMap(),
        playerId: PlayerId = PlayerId.P1,
        eventTimestampNs: Long = sequence * BoxingGameSession.FIXED_STEP_NS,
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

    private fun game(seed: Long = 17L): BoxingGameSession =
        BoxingGameSession.start(sessionId = "boxing-test-$seed", seed = seed, calibrationRevision = 0)
}
