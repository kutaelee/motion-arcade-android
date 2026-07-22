package com.motionarcade.app.monster

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.app.checkpoint.typed.DecodedGameSessionSnapshot
import com.motionarcade.app.checkpoint.typed.GameSessionSnapshotProtoAdapter
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.monster.MonsterBossAttack
import com.motionarcade.games.monster.MonsterRaidBossPhase
import com.motionarcade.games.monster.MonsterRaidClass
import com.motionarcade.games.monster.MonsterRaidEnemy
import com.motionarcade.games.monster.MonsterRaidGameSession
import com.motionarcade.games.monster.MonsterRaidLane
import com.motionarcade.games.monster.MonsterRaidOutcome
import com.motionarcade.games.monster.MonsterRaidPlayerState
import com.motionarcade.games.monster.MonsterRaidSnapshot
import com.motionarcade.games.monster.MonsterRaidStage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded process checkpoint containing deterministic raid state and no camera or pose data. */
internal object MonsterRaidCheckpointCodec {
    const val MAX_ENCODED_BYTES = TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES

    fun encode(snapshot: MonsterRaidSnapshot): ByteArray {
        val canonicalSnapshot = MonsterRaidGameSession.restore(snapshot).snapshot
        val payload = requireNotNull(GameSessionSnapshotProtoAdapter.encode(canonicalSnapshot)) {
            "Monster Raid snapshot cannot be encoded as the canonical G1 contract"
        }
        return TypedCheckpointEnvelopeCodec.encode(
            gameId = GameId.MONSTER,
            mode = canonicalSnapshot.mode,
            payloadCodecId = PAYLOAD_CODEC_ID,
            payloadCodecVersion = PAYLOAD_CODEC_VERSION,
            payload = payload,
        )
    }

    private fun encodeLegacyPayload(snapshot: MonsterRaidSnapshot): ByteArray {
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
            output.writeText(snapshot.stage.name)
            output.writeInt(snapshot.waveIndex)
            output.writeText(snapshot.enemy.name)
            output.writeInt(snapshot.enemyHealth)
            output.writeNullableEnum(snapshot.bossPhase)
            output.writeNullableEnum(snapshot.bossAttack)
            output.writeInt(snapshot.bossTelegraphTicksRemaining)
            output.writeInt(snapshot.bossAttackOrdinal)
            output.writeInt(snapshot.teamCharge)
            output.writeInt(snapshot.teamScore)
            output.writeInt(snapshot.roundTicksRemaining)
            val players = snapshot.players.values.sortedBy { it.playerId.ordinal }
            output.writeInt(players.size)
            players.forEach { player -> output.writePlayer(player) }
            output.writeNullableEnum(snapshot.pendingUltimatePlayer)
            output.writeNullableLong(snapshot.pendingUltimateTimestampNs)
            output.writeNullableLong(snapshot.lastHumanCoreActionTick)
            output.writeNullableEnum(snapshot.outcome)
        }
        return buffer.toByteArray().also { require(it.size in 1..MAX_PAYLOAD_BYTES) }
    }

    fun decode(encoded: ByteArray): MonsterRaidSnapshot? {
        if (encoded.size !in 1..MAX_ENCODED_BYTES) return null
        val privateBytes = encoded.copyOf()
        if (TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(privateBytes)) {
            val envelope = TypedCheckpointEnvelopeCodec.decode(privateBytes) ?: return null
            if (envelope.gameId != GameId.MONSTER) return null
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

    private fun decodeCanonicalPayload(payload: ByteArray, envelopeMode: GameMode): MonsterRaidSnapshot? =
        when (val decoded = GameSessionSnapshotProtoAdapter.decode(payload)) {
            is DecodedGameSessionSnapshot.MonsterSolo ->
                decoded.snapshot.takeIf { envelopeMode == GameMode.SOLO && it.mode == GameMode.SOLO }

            is DecodedGameSessionSnapshot.MonsterDual ->
                decoded.snapshot.takeIf { envelopeMode == GameMode.DUAL && it.mode == GameMode.DUAL }

            else -> null
        }

    private fun decodeLegacyPayload(
        encoded: ByteArray,
        expectedPayloadVersion: Int?,
    ): MonsterRaidSnapshot? {
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
                        MonsterRaidGameSession.SNAPSHOT_SCHEMA_VERSION
                    } else {
                        LEGACY_SNAPSHOT_SCHEMA_VERSION
                    },
                )
                val sessionId = input.readText()
                val gameId = input.readEnum<GameId>()
                val mode = input.readEnum<GameMode>()
                val contentRevision = input.readText()
                val seed = input.readLong()
                val prngAlgorithmId = input.readText()
                val prngAlgorithmVersion = input.readInt()
                val encodedPrngState = if (encodedVersion >= LEGACY_PAYLOAD_CODEC_VERSION) input.readLong() else null
                val calibrationRevision = input.readInt()
                val simulationTick = input.readLong()
                val status = input.readEnum<SessionStatus>()
                val pauseReason = input.readNullableEnum<PauseReason>()
                val stage = input.readEnum<MonsterRaidStage>()
                val waveIndex = input.readInt()
                val enemy = input.readEnum<MonsterRaidEnemy>()
                val enemyHealth = input.readInt()
                val bossPhase = input.readNullableEnum<MonsterRaidBossPhase>()
                val bossAttack = input.readNullableEnum<MonsterBossAttack>()
                val bossTelegraphTicksRemaining = input.readInt()
                val bossAttackOrdinal = input.readInt()
                val teamCharge = input.readInt()
                val teamScore = input.readInt()
                val roundTicksRemaining = input.readInt()
                val count = input.readInt()
                require(count == 2)
                val players = List(count) { input.readPlayer() }.associateBy { it.playerId }
                val pendingUltimatePlayer = input.readNullableEnum<PlayerId>()
                val pendingUltimateTimestampNs = input.readNullableLong()
                val lastHumanCoreActionTick = input.readNullableLong()
                val outcome = input.readNullableEnum<MonsterRaidOutcome>()
                require(input.available() == 0)
                MonsterRaidGameSession.restore(
                    MonsterRaidSnapshot(
                        schemaVersion = if (encodedVersion >= LEGACY_PAYLOAD_CODEC_VERSION) {
                            encodedSchemaVersion
                        } else {
                            MonsterRaidGameSession.SNAPSHOT_SCHEMA_VERSION
                        },
                        sessionId = sessionId,
                        gameId = gameId,
                        mode = mode,
                        contentRevision = contentRevision,
                        seed = seed,
                        prngAlgorithmId = prngAlgorithmId,
                        prngAlgorithmVersion = prngAlgorithmVersion,
                        prngState = encodedPrngState ?: bossAttackOrdinal.toLong(),
                        calibrationRevision = calibrationRevision,
                        simulationTick = simulationTick,
                        status = status,
                        pauseReason = pauseReason,
                        stage = stage,
                        waveIndex = waveIndex,
                        enemy = enemy,
                        enemyHealth = enemyHealth,
                        bossPhase = bossPhase,
                        bossAttack = bossAttack,
                        bossTelegraphTicksRemaining = bossTelegraphTicksRemaining,
                        bossAttackOrdinal = bossAttackOrdinal,
                        teamCharge = teamCharge,
                        teamScore = teamScore,
                        roundTicksRemaining = roundTicksRemaining,
                        players = players,
                        pendingUltimatePlayer = pendingUltimatePlayer,
                        pendingUltimateTimestampNs = pendingUltimateTimestampNs,
                        lastHumanCoreActionTick = lastHumanCoreActionTick,
                        outcome = outcome,
                    ),
                ).snapshot
            }
        }.getOrNull()
    }

    fun decodeCompatible(
        encoded: ByteArray,
        calibrationRevision: Int,
        mode: GameMode,
    ): MonsterRaidSnapshot? = decode(encoded)?.takeIf { snapshot ->
        snapshot.calibrationRevision == calibrationRevision && snapshot.mode == mode
    }

    private fun DataOutputStream.writePlayer(player: MonsterRaidPlayerState) {
        writeText(player.playerId.name)
        writeText(player.playerClass.name)
        writeInt(player.health)
        writeInt(player.stamina)
        writeInt(player.score)
        writeInt(player.guardTicksRemaining)
        writeInt(player.attackCooldownTicks)
        writeInt(player.skillOneCooldownTicks)
        writeInt(player.skillTwoCooldownTicks)
        writeInt(player.skillOneUses)
        writeInt(player.skillTwoUses)
        writeText(player.lane.name)
        writeInt(player.dangerEvades)
        writeBoolean(player.requiresRearm)
        writeBoolean(player.downed)
        writeInt(player.revivesPerformed)
        writeInt(player.receivedRevives)
        writeInt(player.lastDamageDealt)
        writeInt(player.lastDamageTaken)
        writeLong(player.acceptedSequenceWatermark)
        writeLong(player.acceptedEventTimestampWatermarkNs)
        writeLong(player.lastAppliedSequence)
    }

    private fun DataInputStream.readPlayer() = MonsterRaidPlayerState(
        playerId = readEnum<PlayerId>(),
        playerClass = readEnum<MonsterRaidClass>(),
        health = readInt(),
        stamina = readInt(),
        score = readInt(),
        guardTicksRemaining = readInt(),
        attackCooldownTicks = readInt(),
        skillOneCooldownTicks = readInt(),
        skillTwoCooldownTicks = readInt(),
        skillOneUses = readInt(),
        skillTwoUses = readInt(),
        lane = readEnum<MonsterRaidLane>(),
        dangerEvades = readInt(),
        requiresRearm = readBoolean(),
        downed = readBoolean(),
        revivesPerformed = readInt(),
        receivedRevives = readInt(),
        lastDamageDealt = readInt(),
        lastDamageTaken = readInt(),
        acceptedSequenceWatermark = readLong(),
        acceptedEventTimestampWatermarkNs = readLong(),
        lastAppliedSequence = readLong(),
    )

    private fun DataOutputStream.writeText(value: String) {
        require(value.length <= MAX_TEXT_CHARS)
        writeUTF(value)
    }

    private fun DataInputStream.readText(): String = readUTF().also { require(it.length <= MAX_TEXT_CHARS) }
    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T = enumValueOf(readText())
    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }
    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null
    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
        writeBoolean(value != null)
        if (value != null) writeText(value.name)
    }
    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum() else null

    private const val MAGIC = 0x4d524149
    const val PAYLOAD_CODEC_ID = "game-session-snapshot-proto"
    const val PAYLOAD_CODEC_VERSION = 1
    const val LEGACY_PAYLOAD_CODEC_ID = "monster-raid-checkpoint"
    const val LEGACY_PAYLOAD_CODEC_VERSION = 3
    const val LEGACY_MIN_SUPPORTED_VERSION = 2
    private const val RAW_LEGACY_PAYLOAD_VERSION = 2
    private const val LEGACY_SNAPSHOT_SCHEMA_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 8 * 1024
    private const val MAX_TEXT_CHARS = 160
}
