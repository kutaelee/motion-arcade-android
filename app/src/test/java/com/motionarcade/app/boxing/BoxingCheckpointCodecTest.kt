package com.motionarcade.app.boxing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.app.checkpoint.typed.DecodedGameSessionSnapshot
import com.motionarcade.app.checkpoint.typed.GameSessionSnapshotProtoAdapter
import com.motionarcade.core.contract.ContractResult
import com.motionarcade.core.contract.DeterministicEventId
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.boxing.BoxingGameSession
import com.motionarcade.games.boxing.BoxingInputResult
import com.motionarcade.games.monster.MonsterRaidGameSession
import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxingCheckpointCodecTest {
    @Test
    fun legacyRawAndEnvelopedV3FixturesMigrateToCanonicalProtoForBothModes() {
        val solo = BoxingGameSession.start("boxing-legacy-solo", 61L, 2, GameMode.SOLO)
            .checkpointForAppBackground()
        val dual = BoxingGameSession.start("boxing-legacy-dual", 67L, 2, GameMode.DUAL)
            .checkpointForAppBackground()
        listOf(
            solo to BoxingLegacyCheckpointFixtures.soloPausedV3,
            dual to BoxingLegacyCheckpointFixtures.dualPausedV3,
        ).forEach { (expected, legacy) ->
            val encoded = BoxingCheckpointCodec.encode(expected)
            val envelope = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded))
            assertEquals(GameId.BOXING, envelope.gameId)
            assertEquals(expected.mode, envelope.mode)
            assertEquals(BoxingCheckpointCodec.PAYLOAD_CODEC_ID, envelope.payloadCodecId)
            assertEquals(BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION, envelope.payloadCodecVersion)
            val typedPayload = requireNotNull(GameSessionSnapshotProtoAdapter.decode(envelope.payload))
            assertTrue(
                when (expected.mode) {
                    GameMode.SOLO -> typedPayload is DecodedGameSessionSnapshot.BoxingSolo
                    GameMode.DUAL -> typedPayload is DecodedGameSessionSnapshot.BoxingDual
                },
            )
            val migrated = requireNotNull(BoxingCheckpointCodec.decode(legacy))
            assertEquals(expected, migrated)
            val rawWithLegacyV4Header = legacy.copyOf().also { bytes ->
                ByteBuffer.wrap(bytes).putInt(Int.SIZE_BYTES, BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_VERSION)
            }
            assertNull(BoxingCheckpointCodec.decode(rawWithLegacyV4Header))
            val rewritten = BoxingCheckpointCodec.encode(migrated)
            assertArrayEquals(encoded, rewritten)
            assertEquals(migrated, BoxingCheckpointCodec.decode(rewritten))
            assertTrue(
                !legacy.contentEquals(
                    requireNotNull(TypedCheckpointEnvelopeCodec.decode(rewritten)).payload,
                ),
            )
            val legacyEnvelope = TypedCheckpointEnvelopeCodec.encode(
                GameId.BOXING,
                expected.mode,
                BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_ID,
                BoxingCheckpointCodec.LEGACY_MIN_SUPPORTED_VERSION,
                legacy,
            )
            assertEquals(expected, BoxingCheckpointCodec.decode(legacyEnvelope))
            val latestLegacyPayload = when (expected.mode) {
                GameMode.SOLO -> BoxingLegacyCheckpointFixtures.soloPausedV4
                GameMode.DUAL -> BoxingLegacyCheckpointFixtures.dualPausedV4
            }
            val latestLegacyEnvelope = TypedCheckpointEnvelopeCodec.encode(
                GameId.BOXING,
                expected.mode,
                BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_ID,
                BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_VERSION,
                latestLegacyPayload,
            )
            val latestMigrated = requireNotNull(BoxingCheckpointCodec.decode(latestLegacyEnvelope))
            assertEquals(expected, latestMigrated)
            assertArrayEquals(encoded, BoxingCheckpointCodec.encode(latestMigrated))
            val mismatchedVersionEnvelope = TypedCheckpointEnvelopeCodec.encode(
                GameId.BOXING,
                expected.mode,
                BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_ID,
                BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION,
                legacy,
            )
            assertNull(BoxingCheckpointCodec.decode(mismatchedVersionEnvelope))
        }
    }

    @Test
    fun soloRoundTripIsDeterministicAndRestoresPaused() {
        val checkpoint = BoxingGameSession.start("boxing-codec-solo", 31L, 2, GameMode.SOLO)
            .apply { advanceTicks(5) }
            .checkpointForAppBackground()

        val encoded = BoxingCheckpointCodec.encode(checkpoint)
        val restored = requireNotNull(BoxingCheckpointCodec.decode(encoded))

        assertArrayEquals(encoded, BoxingCheckpointCodec.encode(checkpoint))
        assertEquals(SessionStatus.PAUSED, restored.status)
        assertEquals(checkpoint.simulationTick, restored.simulationTick)
        assertEquals(checkpoint.opponentHealth, restored.opponentHealth)
        assertTrue(restored.players.isEmpty())
    }

    @Test
    fun encodedCheckpointContinuesTheSameSoloAiPrngSequence() {
        val source = BoxingGameSession.start("boxing-codec-prng", 37L, 2, GameMode.SOLO)
        repeat(13) { source.advanceTicks(1) }
        val checkpoint = source.checkpointForAppBackground()
        val control = BoxingGameSession.restore(checkpoint)
        val restored = BoxingGameSession.restore(
            requireNotNull(BoxingCheckpointCodec.decode(BoxingCheckpointCodec.encode(checkpoint))),
        )
        assertTrue(control.resume())
        assertTrue(restored.resume())

        repeat(36) {
            assertEquals(control.advanceTicks(1), restored.advanceTicks(1))
        }

        assertTrue(control.snapshot.aiAttackOrdinal >= 4)
        assertEquals(control.snapshot.aiAttackOrdinal.toLong(), control.snapshot.prngState)
    }

    @Test
    fun dualRoundTripPreservesBothPlayersAndCompatibilityProjection() {
        val checkpoint = BoxingGameSession.start("boxing-codec-dual", 9L, 4, GameMode.DUAL)
            .checkpointForAppBackground()

        val restored = requireNotNull(BoxingCheckpointCodec.decode(BoxingCheckpointCodec.encode(checkpoint)))

        assertEquals(setOf(PlayerId.P1, PlayerId.P2), restored.players.keys)
        assertEquals(checkpoint.players, restored.players)
        assertEquals(restored.players.getValue(PlayerId.P1).health, restored.playerHealth)
    }

    @Test
    fun encodedDualCheckpointContinuesInputsAndClockWithZeroPrngState() {
        val checkpoint = BoxingGameSession.start("boxing-codec-dual-replay", 43L, 4, GameMode.DUAL)
            .checkpointForAppBackground()
        val control = BoxingGameSession.restore(checkpoint)
        val restored = BoxingGameSession.restore(
            requireNotNull(BoxingCheckpointCodec.decode(BoxingCheckpointCodec.encode(checkpoint))),
        )
        assertTrue(control.resume())
        assertTrue(restored.resume())

        listOf(PlayerId.P1, PlayerId.P2).forEachIndexed { index, playerId ->
            val eventId = (
                DeterministicEventId.create(checkpoint.sessionId, playerId, 0L) as ContractResult.Valid
            ).value
            val event = MotionEventEnvelope(
                eventId = eventId,
                sessionId = checkpoint.sessionId,
                playerId = playerId,
                sequenceNumber = 0L,
                type = MotionType.PUNCH_JAB,
                quality = 1f,
                confidence = 1f,
                eventTimestampNs = 1_000L + index,
                calibrationRevision = checkpoint.calibrationRevision,
                source = InputSource.FIXTURE,
                metadata = emptyMap(),
            )
            assertTrue(control.accept(event) is BoxingInputResult.Queued)
            assertTrue(restored.accept(event) is BoxingInputResult.Queued)
        }
        repeat(8) {
            assertEquals(control.advanceTicks(1), restored.advanceTicks(1))
        }

        assertEquals(0, control.snapshot.aiAttackOrdinal)
        assertEquals(0L, control.snapshot.prngState)
    }

    @Test
    fun malformedOversizedAndSemanticallyInvalidStateFailClosed() {
        val checkpoint = BoxingGameSession.start("boxing-codec-negative", 9L, 2, GameMode.SOLO)
            .checkpointForAppBackground()
        val encoded = BoxingCheckpointCodec.encode(checkpoint)

        assertNull(BoxingCheckpointCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(BoxingCheckpointCodec.decode(ByteArray(BoxingCheckpointCodec.MAX_ENCODED_BYTES + 1)))
        val payload = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded)).payload
        assertNull(BoxingCheckpointCodec.decode(payload))
        val wrongRoute = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            GameMode.DUAL,
            BoxingCheckpointCodec.PAYLOAD_CODEC_ID,
            BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongRoute))
        val wrongEnvelopeGame = TypedCheckpointEnvelopeCodec.encode(
            GameId.MONSTER,
            checkpoint.mode,
            BoxingCheckpointCodec.PAYLOAD_CODEC_ID,
            BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongEnvelopeGame))
        val wrongCodecId = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            mode = checkpoint.mode,
            payloadCodecId = "not-the-g1-checkpoint-codec",
            payloadCodecVersion = BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload = payload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongCodecId))
        val wrongCanonicalVersion = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            checkpoint.mode,
            BoxingCheckpointCodec.PAYLOAD_CODEC_ID,
            BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION + 1,
            payload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongCanonicalVersion))
        val wrongLegacyVersion = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            checkpoint.mode,
            BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_ID,
            BoxingCheckpointCodec.LEGACY_PAYLOAD_CODEC_VERSION + 1,
            payload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongLegacyVersion))
        val wrongGamePayload = requireNotNull(
            GameSessionSnapshotProtoAdapter.encode(
                MonsterRaidGameSession.start("wrong-game-payload", 11L, 2, checkpoint.mode)
                    .checkpointForAppBackground(),
            ),
        )
        val wrongGameType = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            checkpoint.mode,
            BoxingCheckpointCodec.PAYLOAD_CODEC_ID,
            BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            wrongGamePayload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongGameType))
        assertNull(BoxingCheckpointCodec.decode("""{"schemaVersion":1,"state":{}}""".toByteArray()))
        assertTrue(
            runCatching {
                BoxingCheckpointCodec.encode(checkpoint.copy(opponentHealth = BoxingGameSession.MAX_HEALTH + 1))
            }.isFailure,
        )
    }

    @Test
    fun compatibleDecodeRejectsCrossModeAndCalibrationRestore() {
        val checkpoint = BoxingGameSession.start("boxing-codec-compatible", 17L, 4, GameMode.SOLO)
            .checkpointForAppBackground()
        val encoded = BoxingCheckpointCodec.encode(checkpoint)

        assertEquals(
            GameMode.SOLO,
            requireNotNull(BoxingCheckpointCodec.decodeCompatible(encoded, 4, GameMode.SOLO)).mode,
        )
        assertNull(BoxingCheckpointCodec.decodeCompatible(encoded, 4, GameMode.DUAL))
        assertNull(BoxingCheckpointCodec.decodeCompatible(encoded, 5, GameMode.SOLO))
    }

    @Test
    fun legacyV1SoloCheckpointFailsClosedAfterPvpRulesRevision() {
        val legacy = Base64.getDecoder().decode(LEGACY_V1_SOLO_CHECKPOINT_BASE64)

        assertNull(BoxingCheckpointCodec.decode(legacy))
    }

    @Test
    fun corruptCanonicalEnvelopeFailsClosedWithoutLegacyFallback() {
        val checkpoint = BoxingGameSession.start("boxing-codec-old-rules", 9L, 4, GameMode.DUAL)
            .checkpointForAppBackground()
        val corruptedEnvelope = BoxingCheckpointCodec.encode(checkpoint).copyOf()
        corruptedEnvelope[corruptedEnvelope.lastIndex] =
            (corruptedEnvelope.last().toInt() xor 0x01).toByte()

        assertTrue(TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(corruptedEnvelope))
        assertNull(BoxingCheckpointCodec.decode(corruptedEnvelope))
    }

    private companion object {
        const val LEGACY_V1_SOLO_CHECKPOINT_BASE64 =
            "QlhORwAAAAEAAAACAA1sZWdhY3ktYm94aW5nAAZCT1hJTkcABFNPTE8AD2JveGluZy1ydWxlcy12MgAAAAAAAAABAAAAAAAAAAAAAAAAAAZQQVVTRUQBAA5BUFBfQkFDS0dST1VORAAFUk9VTkQAAABkAAAAZAAAAGQAAAAAAAADhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/////////////////////AAAAAAA="
    }
}
