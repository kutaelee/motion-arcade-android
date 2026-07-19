package com.motionarcade.games.fishing

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

class DualFishingGameSessionTest {
    @Test
    fun sharedCatchEnforcesRodAndSupportRolesEndToEnd() {
        val game = game("coop-end-to-end", seed = 31L)
        assertEquals(PlayerId.P1, game.snapshot.rodPlayerId)
        assertWrongRole(game, PlayerId.P2, 0, MotionType.FISH_CAST)

        castAndOpenHook(game, sequence = 0)
        assertMirrored(game, FishingPhase.HOOK_WINDOW)
        assertWrongRole(game, PlayerId.P2, 1, MotionType.FISH_HOOK)
        submit(game, PlayerId.P1, 1, MotionType.FISH_HOOK)

        var p1Sequence = 2L
        var p2Sequence = 0L
        while (game.snapshot.players.getValue(PlayerId.P1).phase != FishingPhase.NETTING) {
            when (game.snapshot.players.getValue(PlayerId.P1).phase) {
                FishingPhase.REELING -> submit(game, PlayerId.P1, p1Sequence++, MotionType.FISH_REEL_CYCLE)
                FishingPhase.TENSION -> submit(game, PlayerId.P2, p2Sequence++, MotionType.FISH_TENSION_LEFT)
                else -> error("unexpected cooperative phase")
            }
        }
        assertWrongRole(game, PlayerId.P1, p1Sequence, MotionType.FISH_NET)
        submit(game, PlayerId.P2, p2Sequence, MotionType.FISH_NET)

        assertEquals(SessionStatus.COMPLETED, game.snapshot.status)
        assertMirrored(game, FishingPhase.RESULT)
        assertEquals(FishingOutcome.CAUGHT, game.snapshot.players.getValue(PlayerId.P1).outcome)
        assertEquals(1, game.snapshot.catches)
        assertEquals(1, game.snapshot.combo)
    }

    @Test
    fun missedSupportWindowRecoversOnceThenEscapesWithoutResettingSessionState() {
        val game = game("coop-recovery", seed = 17L)
        castAndOpenHook(game, 0)
        submit(game, PlayerId.P1, 1, MotionType.FISH_HOOK)
        submit(game, PlayerId.P1, 2, MotionType.FISH_REEL_CYCLE)
        submit(game, PlayerId.P1, 3, MotionType.FISH_REEL_CYCLE)
        assertMirrored(game, FishingPhase.TENSION)

        advancePast(game, requireNotNull(shared(game).assistDeadlineTick))
        assertMirrored(game, FishingPhase.REELING)
        assertEquals(0, shared(game).recoveryTokens)
        assertEquals(24, shared(game).tension)

        submit(game, PlayerId.P1, 4, MotionType.FISH_REEL_CYCLE)
        assertMirrored(game, FishingPhase.TENSION)
        advancePast(game, requireNotNull(shared(game).assistDeadlineTick))
        assertEquals(SessionStatus.COMPLETED, game.snapshot.status)
        assertEquals(FishingOutcome.ESCAPED, shared(game).outcome)
        assertEquals(0, game.snapshot.combo)
    }

    @Test
    fun simultaneousRodReelAndSupportReliefAreBothAppliedIndependentOfCallbackOrder() {
        val first = game("coop-simultaneous", seed = 17L)
        val second = game("coop-simultaneous", seed = 17L)
        listOf(first, second).forEach { game ->
            castAndOpenHook(game, 0)
            submit(game, PlayerId.P1, 1, MotionType.FISH_HOOK)
            submit(game, PlayerId.P1, 2, MotionType.FISH_REEL_CYCLE)
        }
        val rodTimestamp = 999L * DualFishingGameSession.FIXED_STEP_NS
        val supportTimestamp = rodTimestamp - 1L
        val reelFirst = event(first, PlayerId.P1, 3, MotionType.FISH_REEL_CYCLE, rodTimestamp)
        val reliefFirst = event(first, PlayerId.P2, 0, MotionType.FISH_TENSION_LEFT, supportTimestamp)
        assertTrue(first.accept(reliefFirst) is DualFishingInputResult.Queued)
        assertTrue(first.accept(reelFirst) is DualFishingInputResult.Queued)
        assertTrue(second.accept(event(second, PlayerId.P1, 3, MotionType.FISH_REEL_CYCLE, rodTimestamp)) is DualFishingInputResult.Queued)
        assertTrue(second.accept(event(second, PlayerId.P2, 0, MotionType.FISH_TENSION_LEFT, supportTimestamp)) is DualFishingInputResult.Queued)

        assertEquals(first.advanceTicks(1), second.advanceTicks(1))
        assertEquals(FishingPhase.REELING, shared(first).phase)
        assertTrue(shared(first).tension < 48)
    }

    @Test
    fun roleSwitchIsExplicitAndOnlyAvailableAtResultBoundary() {
        val game = completedGame("coop-role-switch")
        val score = shared(game).score
        val watermarks = game.snapshot.players.mapValues { it.value.acceptedSequenceWatermark }

        val next = game.startNextCatch(switchRoles = true)

        assertEquals(PlayerId.P2, next.rodPlayerId)
        assertEquals(SessionStatus.RUNNING, next.status)
        assertEquals(FishingPhase.READY, shared(game).phase)
        assertEquals(score, shared(game).score)
        assertEquals(watermarks, next.players.mapValues { it.value.acceptedSequenceWatermark })
        assertWrongRole(game, PlayerId.P1, 100, MotionType.FISH_CAST)
        assertTrue(runCatching { game.startNextCatch(false) }.isFailure)
    }

    @Test
    fun trackingPauseCommitsConfirmedInputAndRequiresExplicitResume() {
        val game = game("coop-pause")
        assertTrue(game.accept(event(game, PlayerId.P1, 0, MotionType.FISH_CAST)) is DualFishingInputResult.Queued)

        val paused = game.pause(PauseReason.PLAYER_LANE_CROSS)
        assertEquals(SessionStatus.PAUSED, paused.status)
        assertMirrored(game, FishingPhase.BITE_WAIT)
        assertTrue(game.accept(event(game, PlayerId.P2, 0, MotionType.FISH_TENSION_LEFT)) is DualFishingInputResult.Ignored)
        assertTrue(game.resume())
        assertFalse(game.snapshot.paused)
    }

    @Test
    fun poseLossPausesTimersAndDecaysTensionWithoutChangingScoreOrOutcome() {
        val game = game("coop-pose-loss")
        castAndOpenHook(game, 0)
        submit(game, PlayerId.P1, 1, MotionType.FISH_HOOK)
        submit(game, PlayerId.P1, 2, MotionType.FISH_REEL_CYCLE)
        submit(game, PlayerId.P1, 3, MotionType.FISH_REEL_CYCLE)
        val before = shared(game)
        val tick = game.snapshot.simulationTick

        game.pause(PauseReason.POSE_LOST)
        val once = shared(game).tension
        game.pause(PauseReason.POSE_LOST)
        game.advanceTicks(DualFishingGameSession.MAX_CATCH_UP_TICKS)

        assertEquals(tick, game.snapshot.simulationTick)
        assertEquals(before.tension - 12, shared(game).tension)
        assertEquals(once, shared(game).tension)
        assertEquals(before.score, shared(game).score)
        assertEquals(before.outcome, shared(game).outcome)
        assertEquals(PauseReason.POSE_LOST, game.snapshot.pauseReason)
    }

    @Test
    fun recalibrationPreservesSharedCatchAndFencesOldEpoch() {
        val game = game("coop-recalibration")
        submit(game, PlayerId.P1, 0, MotionType.FISH_CAST)
        val before = game.snapshot

        val migrated = game.recalibrate(1)

        assertEquals(before.players, migrated.players)
        assertEquals(before.rodPlayerId, migrated.rodPlayerId)
        assertEquals(SessionStatus.PAUSED, migrated.status)
        assertTrue(game.resume())
        val old = event(game, PlayerId.P1, 1, MotionType.FISH_CAST).copy(calibrationRevision = 0)
        assertEquals(
            DualFishingInputRejection.CONTRACT_REJECTED,
            (game.accept(old) as DualFishingInputResult.Rejected).reason,
        )
    }

    @Test
    fun checkpointRestorePreservesRolesSharedStateAndWatermarks() {
        val original = game("coop-restore")
        submit(original, PlayerId.P1, 0, MotionType.FISH_CAST)

        val restored = DualFishingGameSession.restore(original.snapshot)

        assertEquals(SessionStatus.PAUSED, restored.snapshot.status)
        assertEquals(PauseReason.APP_BACKGROUND, restored.snapshot.pauseReason)
        assertEquals(original.snapshot.players, restored.snapshot.players)
        assertEquals(original.snapshot.rodPlayerId, restored.snapshot.rodPlayerId)
        assertTrue(restored.resume())
        assertEquals(
            DualFishingInputRejection.CONTRACT_REJECTED,
            (restored.accept(event(restored, PlayerId.P1, 0, MotionType.FISH_CAST)) as DualFishingInputResult.Rejected).reason,
        )
    }

    @Test
    fun foreignUnsupportedDuplicateAndOverflowInputsFailClosed() {
        val game = game("coop-reject")
        val valid = event(game, PlayerId.P1, 0, MotionType.FISH_CAST)
        assertEquals(
            DualFishingInputRejection.WRONG_PLAYER,
            (game.accept(valid.copy(playerId = PlayerId.AI)) as DualFishingInputResult.Rejected).reason,
        )
        assertEquals(
            DualFishingInputRejection.UNSUPPORTED_ACTION,
            (game.accept(event(game, PlayerId.P1, 0, MotionType.PUNCH_JAB)) as DualFishingInputResult.Rejected).reason,
        )
        assertTrue(game.accept(valid) is DualFishingInputResult.Queued)
        assertEquals(
            DualFishingInputRejection.CONTRACT_REJECTED,
            (game.accept(valid) as DualFishingInputResult.Rejected).reason,
        )

        val overflow = game("coop-overflow")
        repeat(DualFishingGameSession.INPUT_QUEUE_CAPACITY) { index ->
            assertTrue(
                overflow.accept(event(overflow, PlayerId.P1, index.toLong(), MotionType.FISH_CAST))
                    is DualFishingInputResult.Queued,
            )
        }
        val result = overflow.accept(
            event(overflow, PlayerId.P1, DualFishingGameSession.INPUT_QUEUE_CAPACITY.toLong(), MotionType.FISH_CAST),
        )
        assertEquals(DualFishingInputRejection.QUEUE_OVERFLOW, (result as DualFishingInputResult.Rejected).reason)
        assertEquals(PauseReason.EVENT_QUEUE_OVERFLOW, overflow.snapshot.pauseReason)
    }

    @Test
    fun restoreRejectsNonMirroredCooperativeState() {
        val valid = game("coop-invalid").snapshot
        val players = valid.players.toMutableMap().apply {
            this[PlayerId.P2] = getValue(PlayerId.P2).copy(score = 99)
        }
        assertTrue(runCatching { DualFishingGameSession.restore(valid.copy(players = players)) }.isFailure)
    }

    @Test
    fun actionOutsideItsPhaseIsRejectedWithoutConsumingWatermark() {
        val game = game("coop-wrong-phase")
        val before = game.snapshot
        val result = game.accept(event(game, PlayerId.P1, 3, MotionType.FISH_REEL_CYCLE))
        assertEquals(DualFishingInputRejection.WRONG_PHASE, (result as DualFishingInputResult.Rejected).reason)
        assertEquals(before, game.snapshot)
    }

    private fun completedGame(sessionId: String): DualFishingGameSession = game(sessionId, seed = 31L).also { game ->
        castAndOpenHook(game, 0)
        submit(game, PlayerId.P1, 1, MotionType.FISH_HOOK)
        var p1 = 2L
        var p2 = 0L
        while (shared(game).phase != FishingPhase.RESULT) {
            when (shared(game).phase) {
                FishingPhase.REELING -> submit(game, PlayerId.P1, p1++, MotionType.FISH_REEL_CYCLE)
                FishingPhase.TENSION -> submit(game, PlayerId.P2, p2++, MotionType.FISH_TENSION_LEFT)
                FishingPhase.NETTING -> submit(game, PlayerId.P2, p2++, MotionType.FISH_NET)
                else -> error("unexpected phase ${shared(game).phase}")
            }
        }
    }

    private fun castAndOpenHook(game: DualFishingGameSession, sequence: Long) {
        submit(game, game.snapshot.rodPlayerId, sequence, MotionType.FISH_CAST)
        advanceTo(game, requireNotNull(shared(game).biteAtTick))
        assertMirrored(game, FishingPhase.HOOK_WINDOW)
    }

    private fun submit(game: DualFishingGameSession, player: PlayerId, sequence: Long, type: MotionType) {
        assertTrue(game.accept(event(game, player, sequence, type)) is DualFishingInputResult.Queued)
        game.advanceTicks(1)
    }

    private fun assertWrongRole(game: DualFishingGameSession, player: PlayerId, sequence: Long, type: MotionType) {
        assertEquals(
            DualFishingInputRejection.WRONG_ROLE,
            (game.accept(event(game, player, sequence, type)) as DualFishingInputResult.Rejected).reason,
        )
    }

    private fun assertMirrored(game: DualFishingGameSession, phase: FishingPhase) {
        assertTrue(game.snapshot.players.values.all { it.phase == phase })
    }

    private fun shared(game: DualFishingGameSession) = game.snapshot.players.getValue(game.snapshot.rodPlayerId)

    private fun advancePast(game: DualFishingGameSession, tick: Long) = advanceTo(game, tick + 1L)

    private fun advanceTo(game: DualFishingGameSession, targetTick: Long) {
        while (game.snapshot.simulationTick < targetTick) {
            game.advanceTicks(minOf(targetTick - game.snapshot.simulationTick, DualFishingGameSession.MAX_CATCH_UP_TICKS.toLong()).toInt())
        }
    }

    private fun event(
        game: DualFishingGameSession,
        player: PlayerId,
        sequence: Long,
        type: MotionType,
        timestampNs: Long = (sequence + 1L) * DualFishingGameSession.FIXED_STEP_NS,
    ) = MotionEventEnvelope(
        eventId = "${game.snapshot.sessionId}/${player.name}/$sequence",
        sessionId = game.snapshot.sessionId,
        playerId = player,
        sequenceNumber = sequence,
        type = type,
        quality = 1f,
        confidence = 1f,
        eventTimestampNs = timestampNs,
        calibrationRevision = game.snapshot.calibrationRevision,
        source = InputSource.FIXTURE,
        metadata = emptyMap(),
    )

    private fun game(sessionId: String, seed: Long = 17L) =
        DualFishingGameSession.start(sessionId, seed, calibrationRevision = 0)
}
