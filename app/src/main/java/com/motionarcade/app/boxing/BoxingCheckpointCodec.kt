package com.motionarcade.app.boxing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.app.checkpoint.typed.DecodedGameSessionSnapshot
import com.motionarcade.app.checkpoint.typed.GameSessionSnapshotProtoAdapter
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.boxing.BoxingAiAttack
import com.motionarcade.games.boxing.BoxingDodgeDirection
import com.motionarcade.games.boxing.BoxingGameSession
import com.motionarcade.games.boxing.BoxingOutcome
import com.motionarcade.games.boxing.BoxingPhase
import com.motionarcade.games.boxing.BoxingPlayerState
import com.motionarcade.games.boxing.BoxingSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded process checkpoint containing deterministic boxing state and no camera or pose data. */
internal object BoxingCheckpointCodec {
    const val MAX_ENCODED_BYTES = TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES

    fun encode(snapshot: BoxingSnapshot): ByteArray {
        val canonicalSnapshot = BoxingGameSession.restore(snapshot).snapshot
        val payload = requireNotNull(GameSessionSnapshotProtoAdapter.encode(canonicalSnapshot)) {
            "Boxing snapshot cannot be encoded as the canonical G1 contract"
        }
        return TypedCheckpointEnvelopeCodec.encode(
            gameId = GameId.BOXING,
            mode = canonicalSnapshot.mode,
            payloadCodecId = PAYLOAD_CODEC_ID,
            payloadCodecVersion = PAYLOAD_CODEC_VERSION,
            payload = payload,
        )
    }

    private fun encodeLegacyPayload(snapshot: BoxingSnapshot): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(LEGACY_PAYLOAD_CODEC_VERSION)
            output.writeInt(snapshot.schemaVersion)
            output.writeText(snapshot.sessionId)
            output.writeText(snapshot.gameId.name)
            output.writeText(snapshot.mode.name)
            output.writeText(snapshot.contentRevision)
            output.writeLong(snapshot.seed)
            output.writeText(snapshot.prngAlgorithmId)
            output.writeInt(snapshot.prngAlgorithmVersion)
            output.writeLong(snapshot.prngState)
            output.writeInt(snapshot.calibrationRevision)
            output.writeLong(snapshot.simulationTick)
            output.writeText(snapshot.status.name)
            output.writeNullableEnum(snapshot.pauseReason)
            output.writeText(snapshot.phase.name)
            output.writeInt(snapshot.playerHealth)
            output.writeInt(snapshot.opponentHealth)
            output.writeInt(snapshot.playerStamina)
            output.writeInt(snapshot.score)
            output.writeInt(snapshot.roundTicksRemaining)
            output.writeNullableEnum(snapshot.aiTelegraph)
            output.writeInt(snapshot.aiTelegraphTicksRemaining)
            output.writeInt(snapshot.aiAttackOrdinal)
            output.writeInt(snapshot.guardTicksRemaining)
            output.writeNullableEnum(snapshot.dodgeDirection)
            output.writeInt(snapshot.dodgeTicksRemaining)
            output.writeInt(snapshot.attackCooldownTicks)
            output.writeBoolean(snapshot.requiresReturnToGuard)
            output.writeInt(snapshot.lastPlayerDamage)
            output.writeInt(snapshot.lastReceivedDamage)
            output.writeInt(snapshot.blockedAttackCount)
            output.writeInt(snapshot.dodgedAttackCount)
            output.writeInt(snapshot.ignoredStrikeCount)
            output.writeLong(snapshot.acceptedSequenceWatermark)
            output.writeLong(snapshot.acceptedEventTimestampWatermarkNs)
            output.writeLong(snapshot.lastAppliedSequence)
            output.writeNullableEnum(snapshot.outcome)
            val players = snapshot.players.values.sortedBy { it.playerId.ordinal }
            output.writeInt(players.size)
            players.forEach { player -> output.writePlayer(player) }
        }
        return buffer.toByteArray().also { require(it.size in 1..MAX_PAYLOAD_BYTES) }
    }

    fun decode(encoded: ByteArray): BoxingSnapshot? {
        if (encoded.size !in 1..MAX_ENCODED_BYTES) return null
        val privateBytes = encoded.copyOf()
        if (TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(privateBytes)) {
            val envelope = TypedCheckpointEnvelopeCodec.decode(privateBytes) ?: return null
            if (envelope.gameId != GameId.BOXING) return null
            return when {
                envelope.payloadCodecId == PAYLOAD_CODEC_ID &&
                    envelope.payloadCodecVersion == PAYLOAD_CODEC_VERSION ->
                    decodeCanonicalPayload(envelope.payload, envelope.mode)

                envelope.payloadCodecId == LEGACY_PAYLOAD_CODEC_ID &&
                    envelope.payloadCodecVersion in LEGACY_MIN_SUPPORTED_VERSION..LEGACY_PAYLOAD_CODEC_VERSION ->
                    decodeLegacyPayload(envelope.payload, envelope.payloadCodecVersion)
                        ?.takeIf { it.mode == envelope.mode }

                else -> null
            }
        }
        return decodeLegacyPayload(privateBytes, RAW_LEGACY_PAYLOAD_VERSION)
    }

    private fun decodeCanonicalPayload(payload: ByteArray, envelopeMode: GameMode): BoxingSnapshot? =
        when (val decoded = GameSessionSnapshotProtoAdapter.decode(payload)) {
            is DecodedGameSessionSnapshot.BoxingSolo ->
                decoded.snapshot.takeIf { envelopeMode == GameMode.SOLO && it.mode == GameMode.SOLO }

            is DecodedGameSessionSnapshot.BoxingDual ->
                decoded.snapshot.takeIf { envelopeMode == GameMode.DUAL && it.mode == GameMode.DUAL }

            else -> null
        }

    private fun decodeLegacyPayload(
        encoded: ByteArray,
        expectedPayloadVersion: Int?,
    ): BoxingSnapshot? {
        if (encoded.size !in 1..MAX_PAYLOAD_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(encoded.copyOf())).use { input ->
                require(input.readInt() == MAGIC)
                val encodedVersion = input.readInt()
                require(encodedVersion in LEGACY_MIN_SUPPORTED_VERSION..LEGACY_PAYLOAD_CODEC_VERSION)
                require(expectedPayloadVersion == null || encodedVersion == expectedPayloadVersion)
                val encodedSchemaVersion = input.readInt()
                require(
                    encodedSchemaVersion == if (encodedVersion >= LEGACY_PAYLOAD_CODEC_VERSION) {
                        BoxingGameSession.SNAPSHOT_SCHEMA_VERSION
                    } else {
                        LEGACY_SNAPSHOT_SCHEMA_VERSION
                    },
                )
                val sessionId = input.readText()
                val gameId = input.readEnum<GameId>()
                val mode = input.readEnum<GameMode>()
                val contentRevision = input.readText()
                val seed = input.readLong()
                val prngAlgorithmId = if (encodedVersion >= LEGACY_PAYLOAD_CODEC_VERSION) {
                    input.readText()
                } else {
                    BoxingGameSession.PRNG_ALGORITHM_ID
                }
                val prngAlgorithmVersion = if (encodedVersion >= LEGACY_PAYLOAD_CODEC_VERSION) {
                    input.readInt()
                } else {
                    BoxingGameSession.PRNG_ALGORITHM_VERSION
                }
                val encodedPrngState = if (encodedVersion >= LEGACY_PAYLOAD_CODEC_VERSION) input.readLong() else null
                val calibrationRevision = input.readInt()
                val simulationTick = input.readLong()
                val status = input.readEnum<SessionStatus>()
                val pauseReason = input.readNullableEnum<PauseReason>()
                val phase = input.readEnum<BoxingPhase>()
                val playerHealth = input.readInt()
                val opponentHealth = input.readInt()
                val playerStamina = input.readInt()
                val score = input.readInt()
                val roundTicksRemaining = input.readInt()
                val aiTelegraph = input.readNullableEnum<BoxingAiAttack>()
                val aiTelegraphTicksRemaining = input.readInt()
                val aiAttackOrdinal = input.readInt()
                val guardTicksRemaining = input.readInt()
                val dodgeDirection = input.readNullableEnum<BoxingDodgeDirection>()
                val dodgeTicksRemaining = input.readInt()
                val attackCooldownTicks = input.readInt()
                val requiresReturnToGuard = input.readBoolean()
                val lastPlayerDamage = input.readInt()
                val lastReceivedDamage = input.readInt()
                val blockedAttackCount = input.readInt()
                val dodgedAttackCount = input.readInt()
                val ignoredStrikeCount = input.readInt()
                val acceptedSequenceWatermark = input.readLong()
                val acceptedEventTimestampWatermarkNs = if (encodedVersion >= 2) {
                    input.readLong()
                } else {
                    migratedTimestampFence(acceptedSequenceWatermark)
                }
                val lastAppliedSequence = input.readLong()
                val outcome = input.readNullableEnum<BoxingOutcome>()
                val playerCount = input.readInt()
                require(playerCount == if (mode == GameMode.DUAL) 2 else 0)
                val players = List(playerCount) {
                    input.readPlayer(encodedVersion, acceptedSequenceWatermark)
                }.associateBy { it.playerId }
                require(players.size == playerCount)
                require(input.available() == 0)
                BoxingGameSession.restore(
                    BoxingSnapshot(
                        schemaVersion = if (encodedVersion < LEGACY_PAYLOAD_CODEC_VERSION) {
                            BoxingGameSession.SNAPSHOT_SCHEMA_VERSION
                        } else {
                            encodedSchemaVersion
                        },
                        sessionId = sessionId,
                        gameId = gameId,
                        mode = mode,
                        contentRevision = contentRevision,
                        seed = seed,
                        prngAlgorithmId = prngAlgorithmId,
                        prngAlgorithmVersion = prngAlgorithmVersion,
                        prngState = encodedPrngState ?: aiAttackOrdinal.toLong(),
                        calibrationRevision = calibrationRevision,
                        simulationTick = simulationTick,
                        status = status,
                        pauseReason = pauseReason,
                        phase = phase,
                        playerHealth = playerHealth,
                        opponentHealth = opponentHealth,
                        playerStamina = playerStamina,
                        score = score,
                        roundTicksRemaining = roundTicksRemaining,
                        aiTelegraph = aiTelegraph,
                        aiTelegraphTicksRemaining = aiTelegraphTicksRemaining,
                        aiAttackOrdinal = aiAttackOrdinal,
                        guardTicksRemaining = guardTicksRemaining,
                        dodgeDirection = dodgeDirection,
                        dodgeTicksRemaining = dodgeTicksRemaining,
                        attackCooldownTicks = attackCooldownTicks,
                        requiresReturnToGuard = requiresReturnToGuard,
                        lastPlayerDamage = lastPlayerDamage,
                        lastReceivedDamage = lastReceivedDamage,
                        blockedAttackCount = blockedAttackCount,
                        dodgedAttackCount = dodgedAttackCount,
                        ignoredStrikeCount = ignoredStrikeCount,
                        acceptedSequenceWatermark = acceptedSequenceWatermark,
                        acceptedEventTimestampWatermarkNs = acceptedEventTimestampWatermarkNs,
                        lastAppliedSequence = lastAppliedSequence,
                        outcome = outcome,
                        players = players,
                    ),
                ).snapshot
            }
        }.getOrNull()
    }

    fun decodeCompatible(
        encoded: ByteArray,
        calibrationRevision: Int,
        mode: GameMode,
    ): BoxingSnapshot? = decode(encoded)?.takeIf { snapshot ->
        snapshot.calibrationRevision == calibrationRevision && snapshot.mode == mode
    }

    private fun DataOutputStream.writePlayer(player: BoxingPlayerState) {
        writeText(player.playerId.name)
        writeInt(player.health)
        writeInt(player.stamina)
        writeInt(player.score)
        writeInt(player.guardTicksRemaining)
        writeNullableEnum(player.dodgeDirection)
        writeInt(player.dodgeTicksRemaining)
        writeInt(player.attackCooldownTicks)
        writeBoolean(player.requiresReturnToGuard)
        writeInt(player.lastPlayerDamage)
        writeInt(player.lastReceivedDamage)
        writeInt(player.blockedAttackCount)
        writeInt(player.dodgedAttackCount)
        writeInt(player.ignoredStrikeCount)
        writeLong(player.acceptedSequenceWatermark)
        writeLong(player.acceptedEventTimestampWatermarkNs)
        writeLong(player.lastAppliedSequence)
    }

    private fun DataInputStream.readPlayer(
        encodedVersion: Int,
        legacyGlobalSequenceWatermark: Long,
    ): BoxingPlayerState {
        val playerId = readEnum<PlayerId>()
        val health = readInt()
        val stamina = readInt()
        val score = readInt()
        val guardTicksRemaining = readInt()
        val dodgeDirection = readNullableEnum<BoxingDodgeDirection>()
        val dodgeTicksRemaining = readInt()
        val attackCooldownTicks = readInt()
        val requiresReturnToGuard = readBoolean()
        val lastPlayerDamage = readInt()
        val lastReceivedDamage = readInt()
        val blockedAttackCount = readInt()
        val dodgedAttackCount = readInt()
        val ignoredStrikeCount = readInt()
        val acceptedSequenceWatermark = if (encodedVersion >= 2) {
            readLong()
        } else {
            legacyGlobalSequenceWatermark
        }
        val acceptedEventTimestampWatermarkNs = if (encodedVersion >= 2) {
            readLong()
        } else {
            migratedTimestampFence(acceptedSequenceWatermark)
        }
        val lastAppliedSequence = readLong()
        return BoxingPlayerState(
            playerId = playerId,
            health = health,
            stamina = stamina,
            score = score,
            guardTicksRemaining = guardTicksRemaining,
            dodgeDirection = dodgeDirection,
            dodgeTicksRemaining = dodgeTicksRemaining,
            attackCooldownTicks = attackCooldownTicks,
            requiresReturnToGuard = requiresReturnToGuard,
            lastPlayerDamage = lastPlayerDamage,
            lastReceivedDamage = lastReceivedDamage,
            blockedAttackCount = blockedAttackCount,
            dodgedAttackCount = dodgedAttackCount,
            ignoredStrikeCount = ignoredStrikeCount,
            acceptedSequenceWatermark = acceptedSequenceWatermark,
            acceptedEventTimestampWatermarkNs = acceptedEventTimestampWatermarkNs,
            lastAppliedSequence = lastAppliedSequence,
        )
    }

    private fun migratedTimestampFence(sequenceWatermark: Long): Long =
        if (sequenceWatermark == -1L) -1L else 0L

    private fun DataOutputStream.writeText(value: String) {
        require(value.length <= MAX_TEXT_CHARS)
        writeUTF(value)
    }

    private fun DataInputStream.readText(): String = readUTF().also { require(it.length <= MAX_TEXT_CHARS) }
    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T = enumValueOf(readText())
    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
        writeBoolean(value != null)
        if (value != null) writeText(value.name)
    }
    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum() else null

    private const val MAGIC = 0x42584e47
    const val PAYLOAD_CODEC_ID = "game-session-snapshot-proto"
    const val PAYLOAD_CODEC_VERSION = 1
    const val LEGACY_PAYLOAD_CODEC_ID = "boxing-checkpoint"
    const val LEGACY_PAYLOAD_CODEC_VERSION = 4
    const val LEGACY_MIN_SUPPORTED_VERSION = 3
    private const val RAW_LEGACY_PAYLOAD_VERSION = 3
    private const val LEGACY_SNAPSHOT_SCHEMA_VERSION = 4
    private const val MAX_PAYLOAD_BYTES = 8 * 1024
    private const val MAX_TEXT_CHARS = 160
}
