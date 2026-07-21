package com.motionarcade.app.fishing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.app.checkpoint.typed.DecodedGameSessionSnapshot
import com.motionarcade.app.checkpoint.typed.GameSessionSnapshotProtoAdapter
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.DualFishingGameSession
import com.motionarcade.games.fishing.FishingGameSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingCheckpointCodecTest {
    @Test
    fun writerUsesProtoEnvelopeAndRawOrEnvelopedLegacyPayloadMigratesDeterministically() {
        val checkpoint = FishingGameSession.start(
            sessionId = "fishing-envelope-migration",
            seed = 29L,
            calibrationRevision = 3,
            eventTimelineOriginNs = 0L,
        ).checkpoint()
        val expectedPersisted = FishingGameSession.restore(checkpoint).checkpoint()
        val preChangeBytes = FishingLegacyCheckpointFixtures.pausedV1

        val encoded = FishingCheckpointCodec.encode(checkpoint)
        val envelope = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded))
        assertEquals(GameId.FISHING, envelope.gameId)
        assertEquals(GameMode.SOLO, envelope.mode)
        assertEquals(FishingCheckpointCodec.PAYLOAD_CODEC_ID, envelope.payloadCodecId)
        assertEquals(FishingCheckpointCodec.PAYLOAD_CODEC_VERSION, envelope.payloadCodecVersion)
        val typed = GameSessionSnapshotProtoAdapter.decode(envelope.payload)
        assertTrue(typed is DecodedGameSessionSnapshot.FishingSolo)
        assertEquals(expectedPersisted, (typed as DecodedGameSessionSnapshot.FishingSolo).snapshot)

        val rawMigrated = requireNotNull(FishingCheckpointCodec.decode(preChangeBytes))
        val legacyEnvelope = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            "fishing-solo-checkpoint",
            1,
            preChangeBytes,
        )
        val envelopedMigrated = requireNotNull(FishingCheckpointCodec.decode(legacyEnvelope))
        assertEquals(expectedPersisted, rawMigrated)
        assertEquals(expectedPersisted, envelopedMigrated)
        assertEquals(SessionStatus.RUNNING, checkpoint.status)
        assertEquals(SessionStatus.PAUSED, rawMigrated.status)
        assertEquals(PauseReason.APP_BACKGROUND, rawMigrated.pauseReason)
        val rewritten = FishingCheckpointCodec.encode(rawMigrated)
        assertTrue(TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(rewritten))
        assertArrayEquals(encoded, rewritten)
        assertArrayEquals(rewritten, FishingCheckpointCodec.encode(envelopedMigrated))
    }

    @Test
    fun envelopeCorruptionAndWrongTypedRouteFailBeforeLegacyFallback() {
        val checkpoint = FishingGameSession.start("fishing-envelope-negative", 31L, 2, 0L)
            .checkpoint()
        val encoded = FishingCheckpointCodec.encode(checkpoint)
        val corrupted = encoded.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        assertNull(FishingCheckpointCodec.decode(corrupted))

        val payload = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded)).payload
        val wrongMode = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.DUAL,
            FishingCheckpointCodec.PAYLOAD_CODEC_ID,
            FishingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(FishingCheckpointCodec.decode(wrongMode))
        val wrongGame = TypedCheckpointEnvelopeCodec.encode(
            GameId.BOXING,
            GameMode.SOLO,
            FishingCheckpointCodec.PAYLOAD_CODEC_ID,
            FishingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(FishingCheckpointCodec.decode(wrongGame))
        val wrongCodec = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            "other-codec",
            1,
            payload,
        )
        assertNull(FishingCheckpointCodec.decode(wrongCodec))
        val wrongVersion = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            FishingCheckpointCodec.PAYLOAD_CODEC_ID,
            FishingCheckpointCodec.PAYLOAD_CODEC_VERSION + 1,
            payload,
        )
        assertNull(FishingCheckpointCodec.decode(wrongVersion))
        val dualPayload = requireNotNull(
            GameSessionSnapshotProtoAdapter.encode(
                DualFishingGameSession.start("wrong-solo-type", 31L, 2).checkpointForAppBackground(),
            ),
        )
        val wrongType = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            FishingCheckpointCodec.PAYLOAD_CODEC_ID,
            FishingCheckpointCodec.PAYLOAD_CODEC_VERSION,
            dualPayload,
        )
        assertNull(FishingCheckpointCodec.decode(wrongType))
        val wrongLegacyVersion = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            "fishing-solo-checkpoint",
            2,
            FishingLegacyCheckpointFixtures.pausedV1,
        )
        assertNull(FishingCheckpointCodec.decode(wrongLegacyVersion))
        assertNull(FishingCheckpointCodec.decode(payload))
        assertNull(
            FishingCheckpointCodec.decode(
                """{"schemaVersion":1,"gameId":"FISHING","mode":"SOLO","state":{}}"""
                    .toByteArray(),
            ),
        )
    }
    @Test
    fun roundTripUsesDomainValidationAndRestoresNonResultPaused() {
        val session = FishingGameSession.start(
            sessionId = "codec-round-trip",
            seed = 17L,
            calibrationRevision = 3,
            eventTimelineOriginNs = 100L,
        )
        val encoded = FishingCheckpointCodec.encode(session.checkpoint())
        val decoded = requireNotNull(FishingCheckpointCodec.decode(encoded))

        assertTrue(encoded.size in 1..FishingCheckpointCodec.MAX_ENCODED_BYTES)
        assertEquals("codec-round-trip", decoded.sessionId)
        assertEquals(17L, decoded.seed)
        assertEquals(3, decoded.calibrationRevision)
        assertEquals(SessionStatus.PAUSED, decoded.status)
        assertEquals(PauseReason.APP_BACKGROUND, decoded.pauseReason)
        assertEquals(session.snapshot.phase, decoded.phase)
        assertEquals(session.snapshot.simulationTick, decoded.simulationTick)
    }

    @Test
    fun everyTruncationWrongVersionTrailingDataAndOversizeFailClosed() {
        val encoded = FishingCheckpointCodec.encode(
            FishingGameSession.start(
                sessionId = "codec-negative",
                seed = 19L,
                calibrationRevision = 1,
                eventTimelineOriginNs = 0L,
            ).checkpoint(),
        )

        for (size in 0 until encoded.size) {
            assertNull("accepted truncated size=$size", FishingCheckpointCodec.decode(encoded.copyOf(size)))
        }
        val wrongVersion = encoded.copyOf().also { bytes -> bytes[7] = 3 }
        assertNull(FishingCheckpointCodec.decode(wrongVersion))
        assertNull(FishingCheckpointCodec.decode(encoded + 0.toByte()))
        assertNull(
            FishingCheckpointCodec.decode(
                ByteArray(FishingCheckpointCodec.MAX_ENCODED_BYTES + 1),
            ),
        )
    }

    @Test
    fun maximumTimelineLedgerRemainsWithinSavedStateBudget() {
        val session = FishingGameSession.start(
            sessionId = "codec-max-ledger",
            seed = 23L,
            calibrationRevision = 1,
            eventTimelineOriginNs = 0L,
        )
        repeat(FishingGameSession.TIMELINE_LEDGER_CAPACITY - 1) { index ->
            session.pause(PauseReason.APP_BACKGROUND)
            assertTrue(session.resume(index + 1L))
        }
        session.pause(PauseReason.APP_BACKGROUND)

        val encoded = FishingCheckpointCodec.encode(session.checkpoint())
        val decoded = FishingCheckpointCodec.decode(encoded)

        assertTrue(encoded.size <= FishingCheckpointCodec.MAX_ENCODED_BYTES)
        assertNotNull(decoded)
        assertEquals(
            FishingGameSession.TIMELINE_LEDGER_CAPACITY,
            requireNotNull(decoded).eventTimelineLedger.size,
        )
    }

    @Test
    fun decodingSnapshotsCallerOwnedBytesBeforeValidation() {
        val session = FishingGameSession.start(
            sessionId = "codec-alias",
            seed = 29L,
            calibrationRevision = 1,
            eventTimelineOriginNs = 0L,
        )
        session.pause(PauseReason.APP_BACKGROUND)
        val encoded = FishingCheckpointCodec.encode(session.checkpoint())
        val original = encoded.copyOf()
        val decoded = requireNotNull(FishingCheckpointCodec.decode(encoded))

        encoded.fill(0)

        assertArrayEquals(original, FishingCheckpointCodec.encode(decoded))
        assertEquals("codec-alias", decoded.sessionId)
    }
}
