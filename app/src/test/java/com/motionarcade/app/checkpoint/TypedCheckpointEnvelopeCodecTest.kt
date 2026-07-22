package com.motionarcade.app.checkpoint

import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedCheckpointEnvelopeCodecTest {
    @Test
    fun roundTripBindsTypedRouteVersionAndPrivatePayloadSnapshot() {
        val callerPayload = "typed-fishing-state".toByteArray()
        val encoded = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            "fishing-solo-checkpoint",
            1,
            callerPayload,
        )
        callerPayload.fill(0)

        val decoded = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded))
        assertEquals(GameId.FISHING, decoded.gameId)
        assertEquals(GameMode.SOLO, decoded.mode)
        assertEquals("fishing-solo-checkpoint", decoded.payloadCodecId)
        assertEquals(1, decoded.payloadCodecVersion)
        assertArrayEquals("typed-fishing-state".toByteArray(), decoded.payload)
        assertTrue(TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(encoded))
        assertTrue(encoded.size <= TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES)
    }

    @Test
    fun everySingleByteMutationAndTrailingDataFailIntegrityBeforePayloadUse() {
        val encoded = TypedCheckpointEnvelopeCodec.encode(
            GameId.FISHING,
            GameMode.SOLO,
            "fishing-solo-checkpoint",
            1,
            ByteArray(96) { it.toByte() },
        )
        encoded.indices.forEach { index ->
            val mutated = encoded.copyOf()
            mutated[index] = (mutated[index].toInt() xor 1).toByte()
            assertNull("mutation at byte $index", TypedCheckpointEnvelopeCodec.decode(mutated))
        }
        assertNull(TypedCheckpointEnvelopeCodec.decode(encoded + 0))
        assertNull(TypedCheckpointEnvelopeCodec.decode(ByteArray(TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES + 1)))
        assertTrue(
            runCatching {
                TypedCheckpointEnvelopeCodec.encode(
                    GameId.FISHING,
                    GameMode.SOLO,
                    "fishing-solo-checkpoint",
                    1,
                    ByteArray(TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES + 1),
                )
            }.isFailure,
        )
    }

    @Test
    fun invalidCodecMetadataAndUnenvelopedPayloadFailClosed() {
        assertTrue(
            runCatching {
                TypedCheckpointEnvelopeCodec.encode(
                    GameId.FISHING,
                    GameMode.SOLO,
                    "unsafe/codec",
                    1,
                    byteArrayOf(1),
                )
            }.isFailure,
        )
        assertFalse(TypedCheckpointEnvelopeCodec.hasEnvelopeMagic("legacy".toByteArray()))
        assertNull(TypedCheckpointEnvelopeCodec.decode("legacy".toByteArray()))
    }
}
