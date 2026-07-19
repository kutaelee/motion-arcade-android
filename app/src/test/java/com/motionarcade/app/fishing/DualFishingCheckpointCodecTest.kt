package com.motionarcade.app.fishing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.core.contract.DeterministicEventId
import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.games.fishing.DualFishingGameSession
import com.motionarcade.games.fishing.DualFishingInputResult
import com.motionarcade.games.fishing.FishingFish
import com.motionarcade.games.fishing.FishingPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualFishingCheckpointCodecTest {
    @Test
    fun writerUsesTypedEnvelopeAndPreEnvelopeV2MigratesExactly() {
        val checkpoint = DualFishingGameSession.start(
            "dual-legacy-envelope",
            seed = 53L,
            calibrationRevision = 2,
        ).checkpointForAppBackground()
        val legacy = DualFishingLegacyCheckpointFixtures.pausedV2

        val encoded = DualFishingCheckpointCodec.encode(checkpoint)
        val envelope = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded))
        assertEquals(GameId.FISHING, envelope.gameId)
        assertEquals(GameMode.DUAL, envelope.mode)
        assertEquals(DualFishingCheckpointCodec.PAYLOAD_CODEC_ID, envelope.payloadCodecId)
        assertEquals(DualFishingCheckpointCodec.PAYLOAD_CODEC_VERSION, envelope.payloadCodecVersion)
        val migrated = requireNotNull(DualFishingCheckpointCodec.decode(legacy))
        assertEquals(checkpoint, migrated)
        val rewritten = DualFishingCheckpointCodec.encode(migrated)
        assertArrayEquals(encoded, rewritten)
        assertArrayEquals(
            legacy,
            requireNotNull(TypedCheckpointEnvelopeCodec.decode(rewritten)).payload,
        )
    }

    @Test
    fun roundTripPreservesBothPlayersAndRestoresPausedWithReplayFences() {
        val game = DualFishingGameSession.start("dual-checkpoint", seed = 19L, calibrationRevision = 2)
        assertTrue(game.accept(event(PlayerId.P1, 4L, 100L)) is DualFishingInputResult.Queued)
        game.advanceTicks(5)
        game.advanceTicks(5)
        assertTrue(
            game.accept(event(PlayerId.P1, 5L, 200L, MotionType.FISH_HOOK)) is DualFishingInputResult.Queued,
        )
        game.advanceTicks(1)
        assertTrue(
            game.accept(event(PlayerId.P2, 9L, 300L, MotionType.FISH_TENSION_LEFT)) is DualFishingInputResult.Queued,
        )
        game.advanceTicks(1)

        val encoded = DualFishingCheckpointCodec.encode(game.snapshot)
        val decoded = requireNotNull(DualFishingCheckpointCodec.decode(encoded))

        assertTrue(encoded.size <= DualFishingCheckpointCodec.MAX_ENCODED_BYTES)
        assertEquals(game.snapshot.players, decoded.players)
        assertEquals(game.snapshot.simulationTick, decoded.simulationTick)
        assertEquals(DualFishingGameSession.PRNG_ALGORITHM_ID, decoded.prngAlgorithmId)
        assertEquals(DualFishingGameSession.PRNG_ALGORITHM_VERSION, decoded.prngAlgorithmVersion)
        assertEquals(game.snapshot.prngState, decoded.prngState)
        assertTrue(decoded.paused)
        val restored = DualFishingGameSession.restore(decoded)
        assertTrue(restored.resume())
        assertTrue(restored.accept(event(PlayerId.P1, 5L, 200L, MotionType.FISH_HOOK)) is DualFishingInputResult.Rejected)
        assertTrue(
            restored.accept(event(PlayerId.P2, 9L, 300L, MotionType.FISH_TENSION_LEFT)) is DualFishingInputResult.Rejected,
        )
    }

    @Test
    fun encodingIsDeterministicAndCorruptOrOversizedStateFailsClosed() {
        val snapshot = DualFishingGameSession.start("dual-codec", 3L, 2).snapshot
        val encoded = DualFishingCheckpointCodec.encode(snapshot)

        assertArrayEquals(encoded, DualFishingCheckpointCodec.encode(snapshot))
        assertNull(DualFishingCheckpointCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(DualFishingCheckpointCodec.decode(ByteArray(DualFishingCheckpointCodec.MAX_ENCODED_BYTES + 1)))
        val payload = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded)).payload
        val wrongRoute = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            DualFishingCheckpointCodec.PAYLOAD_CODEC_ID,
            DualFishingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(DualFishingCheckpointCodec.decode(wrongRoute))
        assertNull(
            DualFishingCheckpointCodec.decode(
                """{"schemaVersion":1,"gameId":"FISHING","mode":"DUAL","state":{}}"""
                    .toByteArray(),
            ),
        )
    }

    @Test
    fun appBackgroundCheckpointAppliesConfirmedPendingInputExactlyOnce() {
        val game = DualFishingGameSession.start("dual-checkpoint", seed = 5L, calibrationRevision = 2)
        assertTrue(game.accept(event(PlayerId.P1, 12L, 300L)) is DualFishingInputResult.Queued)

        val decoded = requireNotNull(
            DualFishingCheckpointCodec.decode(
                DualFishingCheckpointCodec.encode(game.checkpointForAppBackground()),
            ),
        )
        val restored = DualFishingGameSession.restore(decoded)

        assertTrue(restored.snapshot.paused)
        assertEquals(100, restored.snapshot.players.getValue(PlayerId.P1).score)
        assertEquals(0, restored.snapshot.players.getValue(PlayerId.P2).score)
        assertTrue(restored.resume())
        assertTrue(restored.accept(event(PlayerId.P1, 12L, 300L)) is DualFishingInputResult.Rejected)
        restored.advanceTicks(1)
        assertEquals(100, restored.snapshot.players.getValue(PlayerId.P1).score)
    }

    @Test
    fun semanticInvalidCheckpointFailsClosedBeforeEncoding() {
        val snapshot = DualFishingGameSession.start("dual-invalid-codec", 7L, 2).snapshot
        val players = snapshot.players.toMutableMap().apply {
            this[PlayerId.P1] = getValue(PlayerId.P1).copy(
                phase = FishingPhase.BITE_WAIT,
                fish = FishingFish.SUNFIN,
                biteAtTick = null,
            )
        }

        assertTrue(
            runCatching { DualFishingCheckpointCodec.encode(snapshot.copy(players = players)) }.isFailure,
        )
    }

    @Test
    fun nextCatchWithPreservedScoreAndSwappedRolesRoundTrips() {
        val game = DualFishingGameSession.start("dual-checkpoint", seed = 19L, calibrationRevision = 2)
        assertTrue(game.accept(event(PlayerId.P1, 4L, 100L)) is DualFishingInputResult.Queued)
        game.pause(com.motionarcade.core.contract.PauseReason.USER)
        val completed = game.snapshot.copy(
            status = com.motionarcade.core.contract.SessionStatus.COMPLETED,
            pauseReason = null,
            catches = 1,
            combo = 1,
            players = game.snapshot.players.mapValues { (id, player) ->
                player.copy(
                    phase = FishingPhase.RESULT,
                    fish = FishingFish.SUNFIN,
                    biteAtTick = null,
                    hookDeadlineTick = null,
                    assistDeadlineTick = null,
                    score = if (id == PlayerId.P1) 100 else 40,
                    outcome = com.motionarcade.games.fishing.FishingOutcome.CAUGHT,
                )
            },
        )
        val resumed = DualFishingGameSession.restore(completed).startNextCatch(switchRoles = true)

        val decoded = requireNotNull(DualFishingCheckpointCodec.decode(DualFishingCheckpointCodec.encode(resumed)))
        assertEquals(PlayerId.P2, decoded.rodPlayerId)
        assertEquals(100, decoded.players.getValue(PlayerId.P1).score)
        assertEquals(40, decoded.players.getValue(PlayerId.P2).score)
        assertEquals(140, decoded.teamScore)
        assertTrue(decoded.paused)
    }

    private fun event(
        playerId: PlayerId,
        sequence: Long,
        timestampNs: Long,
        type: MotionType = MotionType.FISH_CAST,
    ): MotionEventEnvelope =
        MotionEventEnvelope(
            eventId = (DeterministicEventId.create("dual-checkpoint", playerId, sequence) as
                com.motionarcade.core.contract.ContractResult.Valid).value,
            sessionId = "dual-checkpoint",
            playerId = playerId,
            sequenceNumber = sequence,
            type = type,
            quality = 1f,
            confidence = 1f,
            eventTimestampNs = timestampNs,
            calibrationRevision = 2,
            source = InputSource.TOUCH,
            metadata = emptyMap(),
        )
}
