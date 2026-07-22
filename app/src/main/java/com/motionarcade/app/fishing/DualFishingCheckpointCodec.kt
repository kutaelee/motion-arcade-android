package com.motionarcade.app.fishing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.app.checkpoint.typed.DecodedGameSessionSnapshot
import com.motionarcade.app.checkpoint.typed.GameSessionSnapshotProtoAdapter
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.DualFishingGameSession
import com.motionarcade.games.fishing.DualFishingPlayerState
import com.motionarcade.games.fishing.DualFishingSnapshot
import com.motionarcade.games.fishing.FishingFish
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded process-state encoding. Camera, pose, and renderer state are intentionally excluded. */
internal object DualFishingCheckpointCodec {
    const val MAX_ENCODED_BYTES = TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES

    fun encode(snapshot: DualFishingSnapshot): ByteArray {
        val canonicalSnapshot = DualFishingGameSession.restore(snapshot).snapshot
        val payload = requireNotNull(GameSessionSnapshotProtoAdapter.encode(canonicalSnapshot)) {
            "Dual fishing checkpoint cannot be represented by the typed snapshot contract"
        }
        return TypedCheckpointEnvelopeCodec.encode(
            gameId = GameId.FISHING,
            mode = GameMode.DUAL,
            payloadCodecId = PAYLOAD_CODEC_ID,
            payloadCodecVersion = PAYLOAD_CODEC_VERSION,
            payload = payload,
        )
    }

    private fun encodeLegacyPayload(snapshot: DualFishingSnapshot): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(LEGACY_PAYLOAD_CODEC_VERSION)
            output.writeInt(snapshot.schemaVersion)
            output.writeBoundedUtf(snapshot.sessionId)
            output.writeBoundedUtf(snapshot.gameId.name)
            output.writeBoundedUtf(snapshot.mode.name)
            output.writeBoundedUtf(snapshot.contentRevision)
            output.writeLong(snapshot.seed)
            output.writeBoundedUtf(snapshot.prngAlgorithmId)
            output.writeInt(snapshot.prngAlgorithmVersion)
            output.writeLong(snapshot.prngState)
            output.writeInt(snapshot.calibrationRevision)
            output.writeLong(snapshot.simulationTick)
            output.writeBoundedUtf(snapshot.status.name)
            output.writeNullableEnum(snapshot.pauseReason)
            output.writeBoundedUtf(snapshot.rodPlayerId.name)
            output.writeInt(snapshot.catches)
            output.writeInt(snapshot.combo)
            PLAYERS.forEach { playerId -> output.writePlayer(snapshot.players.getValue(playerId)) }
        }
        return buffer.toByteArray().also { encoded ->
            require(encoded.size in 1..MAX_PAYLOAD_BYTES)
        }
    }

    fun decode(encoded: ByteArray): DualFishingSnapshot? {
        if (encoded.size !in 1..MAX_ENCODED_BYTES) return null
        val privateBytes = encoded.copyOf()
        if (TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(privateBytes)) {
            val envelope = TypedCheckpointEnvelopeCodec.decode(privateBytes) ?: return null
            if (envelope.gameId != GameId.FISHING || envelope.mode != GameMode.DUAL) return null
            return when {
                envelope.payloadCodecId == PAYLOAD_CODEC_ID &&
                    envelope.payloadCodecVersion == PAYLOAD_CODEC_VERSION ->
                    (GameSessionSnapshotProtoAdapter.decode(envelope.payload) as?
                        DecodedGameSessionSnapshot.FishingDual)?.snapshot

                envelope.payloadCodecId == LEGACY_PAYLOAD_CODEC_ID &&
                    envelope.payloadCodecVersion == LEGACY_PAYLOAD_CODEC_VERSION ->
                    decodeLegacyPayload(envelope.payload)

                else -> null
            }
        }
        return decodeLegacyPayload(privateBytes)
    }

    private fun decodeLegacyPayload(encoded: ByteArray): DualFishingSnapshot? {
        if (encoded.size !in 1..MAX_PAYLOAD_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(encoded.copyOf())).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == LEGACY_PAYLOAD_CODEC_VERSION)
                val schemaVersion = input.readInt()
                val sessionId = input.readBoundedUtf()
                val gameId = input.readEnum<GameId>()
                val mode = input.readEnum<GameMode>()
                val contentRevision = input.readBoundedUtf()
                val seed = input.readLong()
                val prngAlgorithmId = input.readBoundedUtf()
                val prngAlgorithmVersion = input.readInt()
                val prngState = input.readLong()
                val calibrationRevision = input.readInt()
                val simulationTick = input.readLong()
                val status = input.readEnum<SessionStatus>()
                val pauseReason = input.readNullableEnum<PauseReason>()
                val rodPlayerId = input.readEnum<PlayerId>()
                val catches = input.readInt()
                val combo = input.readInt()
                val players = PLAYERS.associateWith { expected -> input.readPlayer(expected) }
                require(input.available() == 0)
                DualFishingGameSession.restore(
                    DualFishingSnapshot(
                        schemaVersion = schemaVersion,
                        sessionId = sessionId,
                        gameId = gameId,
                        mode = mode,
                        contentRevision = contentRevision,
                        seed = seed,
                        prngAlgorithmId = prngAlgorithmId,
                        prngAlgorithmVersion = prngAlgorithmVersion,
                        prngState = prngState,
                        calibrationRevision = calibrationRevision,
                        simulationTick = simulationTick,
                        status = status,
                        pauseReason = pauseReason,
                        rodPlayerId = rodPlayerId,
                        catches = catches,
                        combo = combo,
                        players = players,
                    ),
                ).snapshot
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writePlayer(player: DualFishingPlayerState) {
        writeBoundedUtf(player.playerId.name)
        writeBoundedUtf(player.phase.name)
        writeNullableEnum(player.fish)
        writeNullableLong(player.biteAtTick)
        writeNullableLong(player.hookDeadlineTick)
        writeNullableLong(player.assistDeadlineTick)
        writeInt(player.reelCycles)
        writeInt(player.tension)
        writeInt(player.recoveryTokens)
        writeInt(player.score)
        writeNullableEnum(player.outcome)
        writeLong(player.acceptedSequenceWatermark)
        writeLong(player.acceptedEventTimestampWatermarkNs)
        writeLong(player.lastAppliedSequence)
    }

    private fun DataInputStream.readPlayer(expected: PlayerId): DualFishingPlayerState {
        val playerId = readEnum<PlayerId>()
        require(playerId == expected)
        return DualFishingPlayerState(
            playerId = playerId,
            phase = readEnum<FishingPhase>(),
            fish = readNullableEnum<FishingFish>(),
            biteAtTick = readNullableLong(),
            hookDeadlineTick = readNullableLong(),
            assistDeadlineTick = readNullableLong(),
            reelCycles = readInt(),
            tension = readInt(),
            recoveryTokens = readInt(),
            score = readInt(),
            outcome = readNullableEnum<FishingOutcome>(),
            acceptedSequenceWatermark = readLong(),
            acceptedEventTimestampWatermarkNs = readLong(),
            lastAppliedSequence = readLong(),
        )
    }

    private fun DataOutputStream.writeBoundedUtf(value: String) {
        require(value.length <= MAX_STRING_CHARS)
        writeUTF(value)
    }

    private fun DataInputStream.readBoundedUtf(): String = readUTF().also { value ->
        require(value.length <= MAX_STRING_CHARS)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedUtf(value.name)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T =
        enumValueOf(readBoundedUtf())

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum() else null

    private const val MAGIC = 0x44465348
    const val PAYLOAD_CODEC_ID = "game-session-snapshot-proto"
    const val PAYLOAD_CODEC_VERSION = 1
    private const val LEGACY_PAYLOAD_CODEC_ID = "fishing-dual-checkpoint"
    private const val LEGACY_PAYLOAD_CODEC_VERSION = 2
    private const val MAX_PAYLOAD_BYTES = 4 * 1024
    private const val MAX_STRING_CHARS = 160
    private val PLAYERS = listOf(PlayerId.P1, PlayerId.P2)
}
