package com.motionarcade.app.boxing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.boxing.BoxingGameSession
import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxingCheckpointCodecTest {
    @Test
    fun typedEnvelopeMigratesPreEnvelopeV3FixturesForBothModes() {
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
            val migrated = requireNotNull(BoxingCheckpointCodec.decode(legacy))
            assertEquals(expected, migrated)
            val rewritten = BoxingCheckpointCodec.encode(migrated)
            assertArrayEquals(
                legacy,
                requireNotNull(TypedCheckpointEnvelopeCodec.decode(rewritten)).payload,
            )
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
    fun dualRoundTripPreservesBothPlayersAndCompatibilityProjection() {
        val checkpoint = BoxingGameSession.start("boxing-codec-dual", 9L, 4, GameMode.DUAL)
            .checkpointForAppBackground()

        val restored = requireNotNull(BoxingCheckpointCodec.decode(BoxingCheckpointCodec.encode(checkpoint)))

        assertEquals(setOf(PlayerId.P1, PlayerId.P2), restored.players.keys)
        assertEquals(checkpoint.players, restored.players)
        assertEquals(restored.players.getValue(PlayerId.P1).health, restored.playerHealth)
    }

    @Test
    fun malformedOversizedAndSemanticallyInvalidStateFailClosed() {
        val checkpoint = BoxingGameSession.start("boxing-codec-negative", 9L, 2, GameMode.SOLO)
            .checkpointForAppBackground()
        val encoded = BoxingCheckpointCodec.encode(checkpoint)

        assertNull(BoxingCheckpointCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(BoxingCheckpointCodec.decode(ByteArray(BoxingCheckpointCodec.MAX_ENCODED_BYTES + 1)))
        val payload = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded)).payload
        val wrongRoute = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            GameMode.DUAL,
            BoxingCheckpointCodec.PAYLOAD_CODEC_ID,
            BoxingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(BoxingCheckpointCodec.decode(wrongRoute))
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
    fun previousCodecVersionFailsClosedInsteadOfLoadingOldDualRules() {
        val checkpoint = BoxingGameSession.start("boxing-codec-old-rules", 9L, 4, GameMode.DUAL)
            .checkpointForAppBackground()
        val downgradedVersion = requireNotNull(
            TypedCheckpointEnvelopeCodec.decode(BoxingCheckpointCodec.encode(checkpoint)),
        ).payload
        ByteBuffer.wrap(downgradedVersion).putInt(Int.SIZE_BYTES, 2)

        assertNull(BoxingCheckpointCodec.decode(downgradedVersion))
    }

    private companion object {
        const val LEGACY_V1_SOLO_CHECKPOINT_BASE64 =
            "QlhORwAAAAEAAAACAA1sZWdhY3ktYm94aW5nAAZCT1hJTkcABFNPTE8AD2JveGluZy1ydWxlcy12MgAAAAAAAAABAAAAAAAAAAAAAAAAAAZQQVVTRUQBAA5BUFBfQkFDS0dST1VORAAFUk9VTkQAAABkAAAAZAAAAGQAAAAAAAADhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/////////////////////AAAAAAA="
    }
}
