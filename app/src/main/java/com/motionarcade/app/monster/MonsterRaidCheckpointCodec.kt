package com.motionarcade.app.monster

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
    const val MAX_ENCODED_BYTES = 8 * 1024

    fun encode(snapshot: MonsterRaidSnapshot): ByteArray {
        MonsterRaidGameSession.restore(snapshot)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(snapshot.schemaVersion)
            output.writeText(snapshot.sessionId)
            output.writeText(snapshot.gameId.name)
            output.writeText(snapshot.mode.name)
            output.writeText(snapshot.contentRevision)
            output.writeLong(snapshot.seed)
            output.writeText(snapshot.prngAlgorithmId)
            output.writeInt(snapshot.prngAlgorithmVersion)
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
        return buffer.toByteArray().also { require(it.size in 1..MAX_ENCODED_BYTES) }
    }

    fun decode(encoded: ByteArray): MonsterRaidSnapshot? {
        if (encoded.size !in 1..MAX_ENCODED_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(encoded.copyOf())).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == VERSION)
                val schemaVersion = input.readInt()
                val sessionId = input.readText()
                val gameId = input.readEnum<GameId>()
                val mode = input.readEnum<GameMode>()
                val contentRevision = input.readText()
                val seed = input.readLong()
                val prngAlgorithmId = input.readText()
                val prngAlgorithmVersion = input.readInt()
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
                        schemaVersion = schemaVersion,
                        sessionId = sessionId,
                        gameId = gameId,
                        mode = mode,
                        contentRevision = contentRevision,
                        seed = seed,
                        prngAlgorithmId = prngAlgorithmId,
                        prngAlgorithmVersion = prngAlgorithmVersion,
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
    private const val VERSION = 2
    private const val MAX_TEXT_CHARS = 160
}
