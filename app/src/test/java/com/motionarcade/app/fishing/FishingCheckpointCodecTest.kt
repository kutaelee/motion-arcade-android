package com.motionarcade.app.fishing

import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.FishingGameSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingCheckpointCodecTest {
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
        val wrongVersion = encoded.copyOf().also { bytes -> bytes[7] = 2 }
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
