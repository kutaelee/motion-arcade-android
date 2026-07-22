package com.motionarcade.app.checkpoint

import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Versioned integrity envelope for game-owned typed checkpoint payloads.
 *
 * SHA-256 detects accidental persistence corruption; it is not an authenticity signature. Camera,
 * pose, tracking, and renderer data have no field in this format.
 */
internal object TypedCheckpointEnvelopeCodec {
    const val MAGIC: Int = 0x4D_41_53_45
    const val SCHEMA_VERSION: Int = 2
    const val MAX_ENCODED_BYTES: Int = 32 * 1024
    private const val DIGEST_BYTES = 32
    private const val MAX_CODEC_ID_CHARS = 96

    data class Decoded(
        val gameId: GameId,
        val mode: GameMode,
        val payloadCodecId: String,
        val payloadCodecVersion: Int,
        val payload: ByteArray,
    )

    fun encode(
        gameId: GameId,
        mode: GameMode,
        payloadCodecId: String,
        payloadCodecVersion: Int,
        payload: ByteArray,
    ): ByteArray {
        require(payloadCodecId.length in 1..MAX_CODEC_ID_CHARS)
        require(payloadCodecId.all { it.isLetterOrDigit() || it in "._-" })
        require(payloadCodecVersion >= 1)
        require(payload.size in 1..MAX_PAYLOAD_PREFLIGHT_BYTES)
        val unsigned = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(SCHEMA_VERSION)
                output.writeUTF(gameId.name)
                output.writeUTF(mode.name)
                output.writeUTF(payloadCodecId)
                output.writeInt(payloadCodecVersion)
                output.writeInt(payload.size)
                output.write(payload)
            }
        }.toByteArray()
        require(unsigned.size + DIGEST_BYTES <= MAX_ENCODED_BYTES)
        return unsigned + sha256(unsigned)
    }

    fun decode(encoded: ByteArray): Decoded? {
        if (encoded.size !in MIN_ENCODED_BYTES..MAX_ENCODED_BYTES) return null
        val snapshot = encoded.copyOf()
        val unsignedSize = snapshot.size - DIGEST_BYTES
        val unsigned = snapshot.copyOfRange(0, unsignedSize)
        val suppliedDigest = snapshot.copyOfRange(unsignedSize, snapshot.size)
        if (!MessageDigest.isEqual(sha256(unsigned), suppliedDigest)) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(unsigned)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == SCHEMA_VERSION)
                val gameId = enumValueOf<GameId>(input.readUTF())
                val mode = enumValueOf<GameMode>(input.readUTF())
                val codecId = input.readUTF().also { value ->
                    require(value.length in 1..MAX_CODEC_ID_CHARS)
                    require(value.all { it.isLetterOrDigit() || it in "._-" })
                }
                val codecVersion = input.readInt().also { require(it >= 1) }
                val payloadSize = input.readInt()
                require(payloadSize in 1..input.available())
                val payload = ByteArray(payloadSize)
                input.readFully(payload)
                require(input.available() == 0)
                Decoded(gameId, mode, codecId, codecVersion, payload)
            }
        }.getOrNull()
    }

    fun hasEnvelopeMagic(encoded: ByteArray): Boolean =
        encoded.size >= Int.SIZE_BYTES &&
            ((encoded[0].toInt() and 0xff) shl 24 or
                ((encoded[1].toInt() and 0xff) shl 16) or
                ((encoded[2].toInt() and 0xff) shl 8) or
                (encoded[3].toInt() and 0xff)) == MAGIC

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val MIN_ENCODED_BYTES = 4 + 4 + 2 + 1 + 2 + 1 + 2 + 1 + 4 + 4 + 1 + DIGEST_BYTES
    private const val MAX_PAYLOAD_PREFLIGHT_BYTES = MAX_ENCODED_BYTES - DIGEST_BYTES
}
