package com.motionarcade.games.monster

import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionEventGate
import com.motionarcade.core.contract.MotionEventGateResult
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class MonsterRaidStage { WAVE, ELITE, BOSS, RESULT }
enum class MonsterRaidBossPhase { PHASE_1, PHASE_2, PHASE_3 }
enum class MonsterRaidClass { VANGUARD, RANGER }
enum class MonsterRaidSkill { RUNE_SLAM, AEGIS_PULSE, STAR_VOLLEY, WEAKPOINT_MARK }
enum class MonsterRaidLane { LEFT, CENTER, RIGHT }
enum class MonsterRaidEnemy { MOSS_CRAWLER, SKY_WISP, STONE_TUSK, IRON_WARDEN, TEMPEST_TITAN }
enum class MonsterRaidOutcome { VICTORY, DEFEAT, TIMEOUT }

data class MonsterRaidPlayerState(
    val playerId: PlayerId,
    val playerClass: MonsterRaidClass,
    val health: Int,
    val stamina: Int,
    val score: Int,
    val guardTicksRemaining: Int,
    val attackCooldownTicks: Int,
    val skillOneCooldownTicks: Int,
    val skillTwoCooldownTicks: Int,
    val skillOneUses: Int,
    val skillTwoUses: Int,
    val lane: MonsterRaidLane,
    val dangerEvades: Int,
    val requiresRearm: Boolean,
    val downed: Boolean,
    val revivesPerformed: Int,
    val receivedRevives: Int,
    val lastDamageDealt: Int,
    val lastDamageTaken: Int,
    val acceptedSequenceWatermark: Long,
    val acceptedEventTimestampWatermarkNs: Long,
    val lastAppliedSequence: Long,
)

data class MonsterRaidSnapshot(
    val schemaVersion: Int,
    val sessionId: String,
    val gameId: GameId,
    val mode: GameMode,
    val contentRevision: String,
    val seed: Long,
    val prngAlgorithmId: String,
    val prngAlgorithmVersion: Int,
    val prngState: Long,
    val calibrationRevision: Int,
    val simulationTick: Long,
    val status: SessionStatus,
    val pauseReason: PauseReason?,
    val stage: MonsterRaidStage,
    val waveIndex: Int,
    val enemy: MonsterRaidEnemy,
    val enemyHealth: Int,
    val bossPhase: MonsterRaidBossPhase?,
    val bossAttack: MonsterBossAttack?,
    val bossTelegraphTicksRemaining: Int,
    val bossAttackOrdinal: Int,
    val teamCharge: Int,
    val teamScore: Int,
    val roundTicksRemaining: Int,
    val players: Map<PlayerId, MonsterRaidPlayerState>,
    val pendingUltimatePlayer: PlayerId?,
    val pendingUltimateTimestampNs: Long?,
    val lastHumanCoreActionTick: Long?,
    val outcome: MonsterRaidOutcome?,
) {
    val paused: Boolean get() = status == SessionStatus.PAUSED
}

enum class MonsterRaidInputRejection {
    SESSION_MISMATCH,
    WRONG_PLAYER,
    UNSUPPORTED_ACTION,
    CONTRACT_REJECTED,
    QUEUE_OVERFLOW,
}

enum class MonsterRaidInputIgnoreReason { SESSION_PAUSED, SESSION_FINISHED, PLAYER_DOWNED }

sealed interface MonsterRaidInputResult {
    data class Queued(val scheduledTick: Long, val snapshot: MonsterRaidSnapshot) : MonsterRaidInputResult
    data class Ignored(
        val reason: MonsterRaidInputIgnoreReason,
        val snapshot: MonsterRaidSnapshot,
    ) : MonsterRaidInputResult
    data class Rejected(
        val reason: MonsterRaidInputRejection,
        val snapshot: MonsterRaidSnapshot,
    ) : MonsterRaidInputResult
}

/** Complete deterministic Slice 6 raid. Camera and pose implementations remain outside :games. */
class MonsterRaidGameSession private constructor(initial: MonsterRaidSnapshot) {
    private data class QueuedInput(val event: MotionEventEnvelope, val scheduledTick: Long)

    private var state = immutableSnapshot(initial)
    private var gate = MotionEventGate(initial.calibrationRevision)
    private val inputQueue = ArrayDeque<QueuedInput>()
    private val ultimateHistory = linkedMapOf(
        PlayerId.P1 to ArrayDeque<Long>(),
        PlayerId.P2 to ArrayDeque<Long>(),
    )

    val snapshot: MonsterRaidSnapshot
        @Synchronized get() = immutableSnapshot(state)

    @Synchronized
    fun accept(event: MotionEventEnvelope): MonsterRaidInputResult {
        if (event.sessionId != state.sessionId) return rejected(MonsterRaidInputRejection.SESSION_MISMATCH)
        if (event.playerId !in humanPlayers(state.mode)) return rejected(MonsterRaidInputRejection.WRONG_PLAYER)
        if (event.type !in ACTIONS) return rejected(MonsterRaidInputRejection.UNSUPPORTED_ACTION)
        // The companion AI may assist with movement and basic attacks, but it must never
        // stand in for the second human half of the cooperative ultimate contract.
        if (state.mode == GameMode.SOLO && event.type == MotionType.TEAM_ULTIMATE) {
            return rejected(MonsterRaidInputRejection.UNSUPPORTED_ACTION)
        }
        if (state.stage == MonsterRaidStage.RESULT) return ignored(MonsterRaidInputIgnoreReason.SESSION_FINISHED)
        if (state.paused) return ignored(MonsterRaidInputIgnoreReason.SESSION_PAUSED)
        if (state.players.getValue(event.playerId).downed) {
            return ignored(MonsterRaidInputIgnoreReason.PLAYER_DOWNED)
        }
        val player = state.players.getValue(event.playerId)
        if (
            event.sequenceNumber <= player.acceptedSequenceWatermark ||
            event.eventTimestampNs < player.acceptedEventTimestampWatermarkNs
        ) {
            return rejected(MonsterRaidInputRejection.CONTRACT_REJECTED)
        }
        if (inputQueue.size >= INPUT_QUEUE_CAPACITY) {
            applyConfirmedInputsBeforeStop()
            if (state.stage != MonsterRaidStage.RESULT) {
                state = state.copy(status = SessionStatus.PAUSED, pauseReason = PauseReason.EVENT_QUEUE_OVERFLOW)
            }
            return rejected(MonsterRaidInputRejection.QUEUE_OVERFLOW)
        }
        if (gate.evaluateAndRecord(event) !is MotionEventGateResult.Accepted) {
            return rejected(MonsterRaidInputRejection.CONTRACT_REJECTED)
        }
        val scheduledTick = state.simulationTick + 1L
        inputQueue.addLast(QueuedInput(event, scheduledTick))
        updatePlayer(event.playerId) {
            it.copy(
                acceptedSequenceWatermark = event.sequenceNumber,
                acceptedEventTimestampWatermarkNs = event.eventTimestampNs,
            )
        }
        return MonsterRaidInputResult.Queued(scheduledTick, snapshot)
    }

    @Synchronized
    fun advanceTicks(ticks: Int): MonsterRaidSnapshot {
        require(ticks in 0..MAX_CATCH_UP_TICKS)
        if (state.paused || state.stage == MonsterRaidStage.RESULT) return snapshot
        require(ticks.toLong() <= Long.MAX_VALUE - state.simulationTick)
        repeat(ticks) {
            if (state.paused || state.stage == MonsterRaidStage.RESULT) return@repeat
            state = state.copy(
                simulationTick = state.simulationTick + 1L,
                roundTicksRemaining = state.roundTicksRemaining - 1,
                teamCharge = min(MAX_TEAM_CHARGE, state.teamCharge + PASSIVE_CHARGE_PER_TICK),
                players = immutablePlayers(
                    state.players.mapValues { (_, player) ->
                        player.copy(
                            stamina = min(MAX_STAMINA, player.stamina + STAMINA_REGEN_PER_TICK),
                            guardTicksRemaining = (player.guardTicksRemaining - 1).coerceAtLeast(0),
                            attackCooldownTicks = (player.attackCooldownTicks - 1).coerceAtLeast(0),
                            skillOneCooldownTicks = (player.skillOneCooldownTicks - 1).coerceAtLeast(0),
                            skillTwoCooldownTicks = (player.skillTwoCooldownTicks - 1).coerceAtLeast(0),
                            lastDamageDealt = 0,
                            lastDamageTaken = 0,
                        )
                    },
                ),
            )
            takeDueInputs().forEach { apply(it.event) }
            if (state.stage == MonsterRaidStage.RESULT) return@repeat
            runAiCompanion()
            if (state.stage == MonsterRaidStage.RESULT) return@repeat
            progressBossAttack()
            if (state.roundTicksRemaining <= 0 && state.stage != MonsterRaidStage.RESULT) {
                finish(MonsterRaidOutcome.TIMEOUT)
            }
        }
        return snapshot
    }

    @Synchronized
    fun pause(reason: PauseReason): MonsterRaidSnapshot {
        if (state.stage == MonsterRaidStage.RESULT) return snapshot
        applyConfirmedInputsBeforeStop()
        if (state.stage == MonsterRaidStage.RESULT) return snapshot
        clearUltimateHistory()
        state = state.copy(
            status = SessionStatus.PAUSED,
            pauseReason = reason,
            pendingUltimatePlayer = null,
            pendingUltimateTimestampNs = null,
        )
        return snapshot
    }

    /** Moves an active raid to a new camera calibration epoch while preserving encounter state. */
    @Synchronized
    fun recalibrate(newCalibrationRevision: Int): MonsterRaidSnapshot {
        require(newCalibrationRevision >= 0) { "newCalibrationRevision must be non-negative" }
        require(newCalibrationRevision > state.calibrationRevision) {
            "newCalibrationRevision must be strictly greater than the active revision"
        }
        inputQueue.clear()
        clearUltimateHistory()
        gate = MotionEventGate(newCalibrationRevision)
        state = if (state.stage == MonsterRaidStage.RESULT) {
            state.copy(calibrationRevision = newCalibrationRevision)
        } else {
            state.copy(
                calibrationRevision = newCalibrationRevision,
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.CAMERA_SWITCH,
                pendingUltimatePlayer = null,
                pendingUltimateTimestampNs = null,
            )
        }
        return snapshot
    }

    @Synchronized
    fun resume(): Boolean {
        if (!state.paused || state.stage == MonsterRaidStage.RESULT) return false
        inputQueue.clear()
        clearUltimateHistory()
        state = state.copy(
            status = SessionStatus.RUNNING,
            pauseReason = null,
            pendingUltimatePlayer = null,
            pendingUltimateTimestampNs = null,
        )
        return true
    }

    @Synchronized
    fun checkpointForAppBackground(): MonsterRaidSnapshot = pause(PauseReason.APP_BACKGROUND)

    private fun takeDueInputs(): List<QueuedInput> {
        val due = mutableListOf<QueuedInput>()
        while (inputQueue.isNotEmpty() && inputQueue.first.scheduledTick <= state.simulationTick) {
            due += inputQueue.removeFirst()
        }
        return due.sortedWith(
            compareBy<QueuedInput> { it.event.eventTimestampNs }
                .thenBy { it.event.playerId.ordinal }
                .thenBy { it.event.sequenceNumber },
        )
    }

    private fun apply(event: MotionEventEnvelope) {
        updatePlayer(event.playerId) { it.copy(lastAppliedSequence = event.sequenceNumber) }
        when (event.type) {
            MotionType.MONSTER_BLOCK -> block(event.playerId)
            MotionType.MONSTER_REVIVE -> revive(event.playerId)
            MotionType.PUNCH_JAB -> strike(event, strong = false)
            MotionType.PUNCH_HOOK -> strike(event, strong = true)
            MotionType.MONSTER_SKILL_ONE -> classSkill(event.playerId, first = true)
            MotionType.MONSTER_SKILL_TWO -> classSkill(event.playerId, first = false)
            MotionType.MONSTER_MAGIC_CHARGE -> magicCharge(event.playerId)
            MotionType.DODGE_LEFT -> dodge(event.playerId, left = true)
            MotionType.DODGE_RIGHT -> dodge(event.playerId, left = false)
            MotionType.TEAM_ULTIMATE -> teamUltimate(event)
            else -> error("non-raid action reached queue")
        }
    }

    private fun block(playerId: PlayerId) {
        val actor = player(playerId)
        updatePlayer(playerId) { actor.copy(guardTicksRemaining = GUARD_TICKS, requiresRearm = false) }
    }

    private fun revive(playerId: PlayerId) {
        val actor = player(playerId)
        val downedTeammate = state.players.values
            .filter { it.playerId != playerId && it.downed }
            .minByOrNull { it.playerId.ordinal }
            ?: return
        updatePlayer(downedTeammate.playerId) {
            it.copy(
                health = REVIVE_HEALTH,
                downed = false,
                receivedRevives = it.receivedRevives + 1,
                guardTicksRemaining = REVIVE_GUARD_TICKS,
            )
        }
        updatePlayer(playerId) {
            actor.copy(
                score = actor.score + REVIVE_SCORE,
                revivesPerformed = actor.revivesPerformed + 1,
                guardTicksRemaining = GUARD_TICKS,
            )
        }
        state = state.copy(teamScore = state.teamScore + REVIVE_SCORE)
    }

    private fun strike(event: MotionEventEnvelope, strong: Boolean) {
        val actor = player(event.playerId)
        val staminaCost = if (strong) STRONG_STAMINA_COST else BASIC_STAMINA_COST
        if (actor.requiresRearm || actor.attackCooldownTicks > 0 || actor.stamina < staminaCost) return
        val classBase = when (actor.playerClass) {
            MonsterRaidClass.VANGUARD -> if (strong) VANGUARD_STRONG_DAMAGE else VANGUARD_BASIC_DAMAGE
            MonsterRaidClass.RANGER -> if (strong) RANGER_STRONG_DAMAGE else RANGER_BASIC_DAMAGE
        }
        val quality = event.quality.coerceIn(0f, 1f)
        val damage = (classBase * (0.75f + quality * 0.25f)).roundToInt().coerceAtLeast(1)
        dealDamage(damage, event.playerId)
        if (state.mode == GameMode.SOLO && event.playerId == PlayerId.P1) {
            state = state.copy(lastHumanCoreActionTick = state.simulationTick)
        }
        updatePlayer(event.playerId) {
            player(event.playerId).copy(
                stamina = actor.stamina - staminaCost,
                attackCooldownTicks = ATTACK_COOLDOWN_TICKS,
                requiresRearm = true,
            )
        }
    }

    private fun classSkill(playerId: PlayerId, first: Boolean) {
        val actor = player(playerId)
        val cooldown = if (first) actor.skillOneCooldownTicks else actor.skillTwoCooldownTicks
        val staminaCost = if (first) SKILL_ONE_STAMINA_COST else SKILL_TWO_STAMINA_COST
        if (cooldown > 0 || actor.stamina < staminaCost) return
        val skill = skillFor(actor.playerClass, first)
        when (skill) {
            MonsterRaidSkill.RUNE_SLAM -> dealDamage(VANGUARD_RUNE_SLAM_DAMAGE, playerId)
            MonsterRaidSkill.AEGIS_PULSE -> {
                state.players.keys.forEach { target ->
                    updatePlayer(target) {
                        if (it.downed) it else it.copy(guardTicksRemaining = AEGIS_GUARD_TICKS)
                    }
                }
                state = state.copy(teamCharge = min(MAX_TEAM_CHARGE, state.teamCharge + AEGIS_TEAM_CHARGE))
            }
            MonsterRaidSkill.STAR_VOLLEY -> dealDamage(RANGER_STAR_VOLLEY_DAMAGE, playerId)
            MonsterRaidSkill.WEAKPOINT_MARK -> {
                dealDamage(RANGER_WEAKPOINT_DAMAGE, playerId)
                state = state.copy(teamCharge = min(MAX_TEAM_CHARGE, state.teamCharge + WEAKPOINT_TEAM_CHARGE))
            }
        }
        updatePlayer(playerId) {
            val current = player(playerId)
            current.copy(
                stamina = (current.stamina - staminaCost).coerceAtLeast(0),
                skillOneCooldownTicks = if (first) CLASS_SKILL_COOLDOWN_TICKS else current.skillOneCooldownTicks,
                skillTwoCooldownTicks = if (first) current.skillTwoCooldownTicks else CLASS_SKILL_COOLDOWN_TICKS,
                skillOneUses = current.skillOneUses + if (first) 1 else 0,
                skillTwoUses = current.skillTwoUses + if (first) 0 else 1,
            )
        }
        if (state.mode == GameMode.SOLO && playerId == PlayerId.P1) {
            state = state.copy(lastHumanCoreActionTick = state.simulationTick)
        }
    }

    private fun dodge(playerId: PlayerId, left: Boolean) {
        val actor = player(playerId)
        if (actor.stamina < DODGE_STAMINA_COST) return
        updatePlayer(playerId) {
            actor.copy(
                stamina = actor.stamina - DODGE_STAMINA_COST,
                guardTicksRemaining = DODGE_GUARD_TICKS,
                lane = if (left) MonsterRaidLane.LEFT else MonsterRaidLane.RIGHT,
                requiresRearm = false,
            )
        }
        if (state.mode == GameMode.SOLO && playerId == PlayerId.P1) {
            state = state.copy(lastHumanCoreActionTick = state.simulationTick)
        }
    }

    private fun magicCharge(playerId: PlayerId) {
        val actor = player(playerId)
        if (actor.stamina < MAGIC_CHARGE_STAMINA_COST || actor.attackCooldownTicks > 0) return
        updatePlayer(playerId) {
            actor.copy(
                stamina = actor.stamina - MAGIC_CHARGE_STAMINA_COST,
                attackCooldownTicks = MAGIC_CHARGE_COOLDOWN_TICKS,
            )
        }
        state = state.copy(teamCharge = min(MAX_TEAM_CHARGE, state.teamCharge + MAGIC_CHARGE_GAIN))
        if (state.mode == GameMode.SOLO && playerId == PlayerId.P1) {
            state = state.copy(lastHumanCoreActionTick = state.simulationTick)
        }
    }

    private fun teamUltimate(event: MotionEventEnvelope) {
        if (
            state.stage != MonsterRaidStage.BOSS ||
            state.bossPhase != MonsterRaidBossPhase.PHASE_3 ||
            state.teamCharge < MAX_TEAM_CHARGE
        ) return
        check(state.mode == GameMode.DUAL) { "team ultimate requires two human players" }
        val counterpart = if (event.playerId == PlayerId.P1) PlayerId.P2 else PlayerId.P1
        val counterpartTimestamp = ultimateHistory.getValue(counterpart).minByOrNull { candidate ->
            abs(candidate - event.eventTimestampNs)
        }
        if (
            counterpartTimestamp != null &&
            abs(counterpartTimestamp - event.eventTimestampNs) <= TEAM_ULTIMATE_SYNC_WINDOW_NS
        ) {
            resolveTeamUltimate(event.playerId)
            return
        }
        ultimateHistory.getValue(event.playerId).addLast(event.eventTimestampNs)
        while (ultimateHistory.getValue(event.playerId).size > ULTIMATE_HISTORY_CAPACITY) {
            ultimateHistory.getValue(event.playerId).removeFirst()
        }
        state = state.copy(
            pendingUltimatePlayer = event.playerId,
            pendingUltimateTimestampNs = event.eventTimestampNs,
        )
    }

    private fun resolveTeamUltimate(trigger: PlayerId) {
        clearUltimateHistory()
        val damage = min(TEAM_ULTIMATE_DAMAGE, state.enemyHealth)
        state = state.copy(
            teamCharge = 0,
            pendingUltimatePlayer = null,
            pendingUltimateTimestampNs = null,
        )
        dealDamage(damage, trigger, splitTeamScore = true)
    }

    private fun dealDamage(damage: Int, source: PlayerId, splitTeamScore: Boolean = false) {
        val dealt = min(damage, state.enemyHealth)
        state = state.copy(
            enemyHealth = (state.enemyHealth - dealt).coerceAtLeast(0),
            teamScore = state.teamScore + dealt,
        )
        if (splitTeamScore && state.mode == GameMode.DUAL) {
            val p1 = (dealt + 1) / 2
            val p2 = dealt / 2
            updatePlayer(PlayerId.P1) { it.copy(score = it.score + p1, lastDamageDealt = p1) }
            updatePlayer(PlayerId.P2) { it.copy(score = it.score + p2, lastDamageDealt = p2) }
        } else {
            updatePlayer(source) { it.copy(score = it.score + dealt, lastDamageDealt = dealt) }
        }
        if (state.enemyHealth == 0) advanceEncounter()
        if (state.stage == MonsterRaidStage.BOSS) updateBossPhase()
    }

    private fun advanceEncounter() {
        when (state.stage) {
            MonsterRaidStage.WAVE -> {
                val nextWave = state.waveIndex + 1
                if (nextWave < WAVE_ENEMIES.size) {
                    state = state.copy(
                        waveIndex = nextWave,
                        enemy = WAVE_ENEMIES[nextWave],
                        enemyHealth = WAVE_HEALTH,
                    )
                } else {
                    state = state.copy(
                        stage = MonsterRaidStage.ELITE,
                        enemy = MonsterRaidEnemy.IRON_WARDEN,
                        enemyHealth = ELITE_HEALTH,
                    )
                }
            }
            MonsterRaidStage.ELITE -> state = state.copy(
                stage = MonsterRaidStage.BOSS,
                enemy = MonsterRaidEnemy.TEMPEST_TITAN,
                enemyHealth = BOSS_HEALTH,
                bossPhase = MonsterRaidBossPhase.PHASE_1,
                bossAttack = null,
                bossTelegraphTicksRemaining = 0,
            )
            MonsterRaidStage.BOSS -> finish(MonsterRaidOutcome.VICTORY)
            MonsterRaidStage.RESULT -> Unit
        }
    }

    private fun updateBossPhase() {
        if (state.stage != MonsterRaidStage.BOSS) return
        val phase = when {
            state.enemyHealth > BOSS_PHASE_2_THRESHOLD -> MonsterRaidBossPhase.PHASE_1
            state.enemyHealth > BOSS_PHASE_3_THRESHOLD -> MonsterRaidBossPhase.PHASE_2
            else -> MonsterRaidBossPhase.PHASE_3
        }
        if (phase != state.bossPhase) {
            state = state.copy(
                bossPhase = phase,
                bossAttack = null,
                bossTelegraphTicksRemaining = 0,
            )
        }
    }

    private fun runAiCompanion() {
        if (state.mode != GameMode.SOLO || state.stage == MonsterRaidStage.RESULT) return
        val ai = player(PlayerId.AI)
        if (!ai.downed && state.simulationTick % AI_MOVE_CADENCE_TICKS == 0L) {
            updatePlayer(PlayerId.AI) { current -> current.copy(lane = nextLane(current.lane)) }
        }
        val lastHumanAction = state.lastHumanCoreActionTick ?: return
        if (state.simulationTick - lastHumanAction > AI_ASSIST_WINDOW_TICKS) return
        if (ai.downed || state.simulationTick % AI_ATTACK_CADENCE_TICKS != 0L) return
        val damage = if (state.stage == MonsterRaidStage.BOSS) AI_BOSS_DAMAGE else AI_BASIC_DAMAGE
        dealDamage(damage, PlayerId.AI)
    }

    private fun progressBossAttack() {
        if (state.stage != MonsterRaidStage.BOSS) return
        val attack = state.bossAttack
        if (attack == null) {
            if (state.simulationTick % bossCadence() == 0L) {
                val next = BOSS_ATTACKS[
                    (((state.seed xor state.prngState) and Long.MAX_VALUE) % BOSS_ATTACKS.size).toInt()
                ]
                state = state.copy(
                    bossAttack = next,
                    bossTelegraphTicksRemaining = BOSS_TELEGRAPH_TICKS,
                    bossAttackOrdinal = state.bossAttackOrdinal + 1,
                    prngState = state.prngState + 1L,
                )
            }
            return
        }
        if (state.bossTelegraphTicksRemaining > 1) {
            state = state.copy(bossTelegraphTicksRemaining = state.bossTelegraphTicksRemaining - 1)
            return
        }
        resolveBossAttack(attack)
    }

    private fun resolveBossAttack(attack: MonsterBossAttack) {
        val targets = when (state.mode) {
            GameMode.SOLO -> listOf(
                if (state.bossAttackOrdinal % 2 == 0) PlayerId.AI else PlayerId.P1,
            )
            GameMode.DUAL -> listOf(PlayerId.P1, PlayerId.P2)
        }
        val vanguardGuarding = state.players.values.any {
            it.playerClass == MonsterRaidClass.VANGUARD && it.guardTicksRemaining > 0 && !it.downed
        }
        targets.forEach { playerId ->
            val current = player(playerId)
            if (current.downed) return@forEach
            if (playerId == PlayerId.AI) {
                updatePlayer(PlayerId.AI) {
                    current.copy(
                        lane = nextLane(current.lane),
                        dangerEvades = current.dangerEvades + 1,
                        guardTicksRemaining = DODGE_GUARD_TICKS,
                        lastDamageTaken = 0,
                    )
                }
                return@forEach
            }
            val selfGuard = current.guardTicksRemaining > 0
            val roleProtected =
                state.bossPhase == MonsterRaidBossPhase.PHASE_2 &&
                    current.playerClass == MonsterRaidClass.RANGER &&
                    vanguardGuarding
            val base = when (state.bossPhase) {
                MonsterRaidBossPhase.PHASE_1 -> PHASE_1_DAMAGE
                MonsterRaidBossPhase.PHASE_2 -> PHASE_2_DAMAGE
                MonsterRaidBossPhase.PHASE_3 -> PHASE_3_DAMAGE
                null -> PHASE_1_DAMAGE
            } + if (attack == MonsterBossAttack.BARRIER) BARRIER_DAMAGE_BONUS else 0
            val damage = if (selfGuard || roleProtected) GUARDED_DAMAGE else base
            val health = (current.health - damage).coerceAtLeast(0)
            updatePlayer(playerId) {
                current.copy(
                    health = health,
                    downed = health == 0,
                    lastDamageTaken = damage,
                )
            }
        }
        state = state.copy(bossAttack = null, bossTelegraphTicksRemaining = 0)
        if (humanPlayers(state.mode).all { player(it).downed }) finish(MonsterRaidOutcome.DEFEAT)
    }

    private fun bossCadence(): Long = when (state.bossPhase) {
        MonsterRaidBossPhase.PHASE_1 -> PHASE_1_CADENCE_TICKS
        MonsterRaidBossPhase.PHASE_2 -> PHASE_2_CADENCE_TICKS
        MonsterRaidBossPhase.PHASE_3 -> PHASE_3_CADENCE_TICKS
        null -> PHASE_1_CADENCE_TICKS
    }

    private fun nextLane(lane: MonsterRaidLane): MonsterRaidLane = when (lane) {
        MonsterRaidLane.LEFT -> MonsterRaidLane.CENTER
        MonsterRaidLane.CENTER -> MonsterRaidLane.RIGHT
        MonsterRaidLane.RIGHT -> MonsterRaidLane.LEFT
    }

    private fun applyConfirmedInputsBeforeStop() {
        if (inputQueue.isNotEmpty() && !state.paused) advanceTicks(1)
        check(inputQueue.isEmpty())
    }

    private fun finish(outcome: MonsterRaidOutcome) {
        inputQueue.clear()
        clearUltimateHistory()
        state = state.copy(
            status = SessionStatus.COMPLETED,
            pauseReason = null,
            stage = MonsterRaidStage.RESULT,
            bossAttack = null,
            bossTelegraphTicksRemaining = 0,
            pendingUltimatePlayer = null,
            pendingUltimateTimestampNs = null,
            outcome = outcome,
        )
    }

    private fun player(id: PlayerId): MonsterRaidPlayerState = state.players.getValue(id)

    private fun updatePlayer(id: PlayerId, transform: (MonsterRaidPlayerState) -> MonsterRaidPlayerState) {
        val next = LinkedHashMap(state.players)
        next[id] = transform(next.getValue(id))
        state = state.copy(players = immutablePlayers(next))
    }

    private fun clearUltimateHistory() {
        ultimateHistory.values.forEach(ArrayDeque<Long>::clear)
    }

    private fun rejected(reason: MonsterRaidInputRejection) = MonsterRaidInputResult.Rejected(reason, snapshot)
    private fun ignored(reason: MonsterRaidInputIgnoreReason) = MonsterRaidInputResult.Ignored(reason, snapshot)

    companion object {
        const val FIXED_STEP_NS = 100_000_000L
        const val MAX_CATCH_UP_TICKS = 5
        const val INPUT_QUEUE_CAPACITY = 64
        const val MAX_HEALTH = 100
        const val MAX_STAMINA = 100
        const val MAX_TEAM_CHARGE = 100
        const val ROUND_TICKS = 1_200
        const val WAVE_HEALTH = 30
        const val ELITE_HEALTH = 60
        const val BOSS_HEALTH = 180
        const val BOSS_PHASE_2_THRESHOLD = 120
        const val BOSS_PHASE_3_THRESHOLD = 60
        const val TEAM_ULTIMATE_SYNC_WINDOW_NS = 600_000_000L
        const val CONTENT_REVISION = "monster-raid-rules-v1"
        const val PRNG_ALGORITHM_ID = "xor-index-v1"
        const val PRNG_ALGORITHM_VERSION = 1
        const val SNAPSHOT_SCHEMA_VERSION = 2
        private const val STAMINA_REGEN_PER_TICK = 2
        private const val PASSIVE_CHARGE_PER_TICK = 1
        private const val BASIC_STAMINA_COST = 6
        private const val STRONG_STAMINA_COST = 12
        private const val VANGUARD_BASIC_DAMAGE = 7
        private const val VANGUARD_STRONG_DAMAGE = 11
        private const val RANGER_BASIC_DAMAGE = 9
        private const val RANGER_STRONG_DAMAGE = 14
        private const val ATTACK_COOLDOWN_TICKS = 2
        private const val CLASS_SKILL_COOLDOWN_TICKS = 40
        private const val SKILL_ONE_STAMINA_COST = 20
        private const val SKILL_TWO_STAMINA_COST = 24
        private const val VANGUARD_RUNE_SLAM_DAMAGE = 18
        private const val AEGIS_GUARD_TICKS = 10
        private const val AEGIS_TEAM_CHARGE = 10
        private const val RANGER_STAR_VOLLEY_DAMAGE = 22
        private const val RANGER_WEAKPOINT_DAMAGE = 12
        private const val WEAKPOINT_TEAM_CHARGE = 18
        private const val GUARD_TICKS = 5
        private const val DODGE_GUARD_TICKS = 4
        private const val DODGE_STAMINA_COST = 10
        private const val MAGIC_CHARGE_STAMINA_COST = 12
        private const val MAGIC_CHARGE_COOLDOWN_TICKS = 8
        private const val MAGIC_CHARGE_GAIN = 24
        private const val REVIVE_GUARD_TICKS = 8
        private const val REVIVE_HEALTH = 35
        private const val REVIVE_SCORE = 20
        private const val AI_ATTACK_CADENCE_TICKS = 8L
        private const val AI_MOVE_CADENCE_TICKS = 5L
        private const val AI_ASSIST_WINDOW_TICKS = 24L
        private const val AI_BASIC_DAMAGE = 5
        private const val AI_BOSS_DAMAGE = 4
        private const val TEAM_ULTIMATE_DAMAGE = 42
        private const val ULTIMATE_HISTORY_CAPACITY = 8
        private const val BOSS_TELEGRAPH_TICKS = 4
        private const val PHASE_1_CADENCE_TICKS = 16L
        private const val PHASE_2_CADENCE_TICKS = 13L
        private const val PHASE_3_CADENCE_TICKS = 10L
        private const val PHASE_1_DAMAGE = 10
        private const val PHASE_2_DAMAGE = 14
        private const val PHASE_3_DAMAGE = 18
        private const val BARRIER_DAMAGE_BONUS = 4
        private const val GUARDED_DAMAGE = 3
        private val ACTIONS = setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.MONSTER_BLOCK,
            MotionType.MONSTER_REVIVE,
            MotionType.MONSTER_SKILL_ONE,
            MotionType.MONSTER_SKILL_TWO,
            MotionType.MONSTER_MAGIC_CHARGE,
            MotionType.DODGE_LEFT,
            MotionType.DODGE_RIGHT,
            MotionType.TEAM_ULTIMATE,
        )
        private val WAVE_ENEMIES = listOf(
            MonsterRaidEnemy.MOSS_CRAWLER,
            MonsterRaidEnemy.SKY_WISP,
            MonsterRaidEnemy.STONE_TUSK,
        )
        private val BOSS_ATTACKS = MonsterBossAttack.entries

        fun start(sessionId: String, seed: Long, calibrationRevision: Int, mode: GameMode): MonsterRaidGameSession {
            require(sessionId.length in 1..120)
            require(calibrationRevision >= 0)
            val players = if (mode == GameMode.DUAL) {
                listOf(
                    initialPlayer(PlayerId.P1, MonsterRaidClass.VANGUARD),
                    initialPlayer(PlayerId.P2, MonsterRaidClass.RANGER),
                )
            } else {
                listOf(
                    initialPlayer(PlayerId.P1, MonsterRaidClass.VANGUARD),
                    initialPlayer(PlayerId.AI, MonsterRaidClass.RANGER),
                )
            }
            return MonsterRaidGameSession(
                MonsterRaidSnapshot(
                    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                    sessionId = sessionId,
                    gameId = GameId.MONSTER,
                    mode = mode,
                    contentRevision = CONTENT_REVISION,
                    seed = seed,
                    prngAlgorithmId = PRNG_ALGORITHM_ID,
                    prngAlgorithmVersion = PRNG_ALGORITHM_VERSION,
                    prngState = 0L,
                    calibrationRevision = calibrationRevision,
                    simulationTick = 0,
                    status = SessionStatus.RUNNING,
                    pauseReason = null,
                    stage = MonsterRaidStage.WAVE,
                    waveIndex = 0,
                    enemy = WAVE_ENEMIES.first(),
                    enemyHealth = WAVE_HEALTH,
                    bossPhase = null,
                    bossAttack = null,
                    bossTelegraphTicksRemaining = 0,
                    bossAttackOrdinal = 0,
                    teamCharge = 0,
                    teamScore = 0,
                    roundTicksRemaining = ROUND_TICKS,
                    players = immutablePlayers(players.associateBy { it.playerId }),
                    pendingUltimatePlayer = null,
                    pendingUltimateTimestampNs = null,
                    lastHumanCoreActionTick = null,
                    outcome = null,
                ),
            )
        }

        fun restore(checkpoint: MonsterRaidSnapshot): MonsterRaidGameSession {
            validate(checkpoint)
            val restored = if (checkpoint.stage == MonsterRaidStage.RESULT) {
                checkpoint
            } else {
                checkpoint.copy(
                    status = SessionStatus.PAUSED,
                    pauseReason = PauseReason.APP_BACKGROUND,
                    pendingUltimatePlayer = null,
                    pendingUltimateTimestampNs = null,
                )
            }
            return MonsterRaidGameSession(immutableSnapshot(restored))
        }

        private fun validate(snapshot: MonsterRaidSnapshot) {
            require(snapshot.schemaVersion == SNAPSHOT_SCHEMA_VERSION)
            require(snapshot.sessionId.length in 1..120)
            require(snapshot.gameId == GameId.MONSTER)
            require(snapshot.contentRevision == CONTENT_REVISION)
            require(snapshot.prngAlgorithmId == PRNG_ALGORITHM_ID)
            require(snapshot.prngAlgorithmVersion == PRNG_ALGORITHM_VERSION)
            require(snapshot.prngState == snapshot.bossAttackOrdinal.toLong())
            require(snapshot.prngState in 0L..snapshot.simulationTick)
            require(snapshot.calibrationRevision >= 0 && snapshot.simulationTick >= 0)
            require(snapshot.waveIndex in WAVE_ENEMIES.indices)
            require(snapshot.teamCharge in 0..MAX_TEAM_CHARGE)
            require(snapshot.teamScore >= 0 && snapshot.roundTicksRemaining >= 0)
            require(snapshot.simulationTick + snapshot.roundTicksRemaining.toLong() == ROUND_TICKS.toLong())
            require(snapshot.lastHumanCoreActionTick == null || snapshot.lastHumanCoreActionTick in 0..snapshot.simulationTick)
            val expectedPlayers = if (snapshot.mode == GameMode.DUAL) {
                setOf(PlayerId.P1, PlayerId.P2)
            } else {
                setOf(PlayerId.P1, PlayerId.AI)
            }
            require(snapshot.players.keys == expectedPlayers)
            snapshot.players.forEach { (id, player) ->
                require(player.playerId == id)
                require(player.health in 0..MAX_HEALTH)
                require(player.stamina in 0..MAX_STAMINA)
                require(player.score >= 0)
                require(player.guardTicksRemaining >= 0 && player.attackCooldownTicks >= 0)
                require(player.skillOneCooldownTicks >= 0 && player.skillTwoCooldownTicks >= 0)
                require(player.skillOneUses >= 0 && player.skillTwoUses >= 0)
                require(player.dangerEvades >= 0)
                require(player.revivesPerformed >= 0 && player.receivedRevives >= 0)
                require(player.downed == (player.health == 0))
                require(player.acceptedSequenceWatermark >= -1)
                require(player.acceptedEventTimestampWatermarkNs >= -1)
                require(player.lastAppliedSequence in -1..player.acceptedSequenceWatermark)
                require(
                    (player.acceptedSequenceWatermark == -1L) ==
                        (player.acceptedEventTimestampWatermarkNs == -1L),
                )
            }
            require((snapshot.stage == MonsterRaidStage.RESULT) == (snapshot.outcome != null))
            require((snapshot.stage == MonsterRaidStage.BOSS) == (snapshot.bossPhase != null))
            require(
                when (snapshot.stage) {
                    MonsterRaidStage.WAVE ->
                        snapshot.enemy == WAVE_ENEMIES[snapshot.waveIndex] &&
                            snapshot.enemyHealth in 1..WAVE_HEALTH
                    MonsterRaidStage.ELITE ->
                        snapshot.enemy == MonsterRaidEnemy.IRON_WARDEN &&
                            snapshot.enemyHealth in 1..ELITE_HEALTH
                    MonsterRaidStage.BOSS ->
                        snapshot.enemy == MonsterRaidEnemy.TEMPEST_TITAN &&
                            snapshot.enemyHealth in 1..BOSS_HEALTH
                    MonsterRaidStage.RESULT -> snapshot.status == SessionStatus.COMPLETED
                },
            )
            require(
                when (snapshot.status) {
                    SessionStatus.RUNNING -> snapshot.pauseReason == null && snapshot.stage != MonsterRaidStage.RESULT
                    SessionStatus.PAUSED -> snapshot.pauseReason != null && snapshot.stage != MonsterRaidStage.RESULT
                    SessionStatus.COMPLETED -> snapshot.pauseReason == null && snapshot.stage == MonsterRaidStage.RESULT
                    else -> false
                },
            )
            require(
                snapshot.pendingUltimatePlayer == null ==
                    (snapshot.pendingUltimateTimestampNs == null),
            )
        }

        private fun initialPlayer(id: PlayerId, playerClass: MonsterRaidClass) = MonsterRaidPlayerState(
            playerId = id,
            playerClass = playerClass,
            health = MAX_HEALTH,
            stamina = MAX_STAMINA,
            score = 0,
            guardTicksRemaining = 0,
            attackCooldownTicks = 0,
            skillOneCooldownTicks = 0,
            skillTwoCooldownTicks = 0,
            skillOneUses = 0,
            skillTwoUses = 0,
            lane = MonsterRaidLane.CENTER,
            dangerEvades = 0,
            requiresRearm = false,
            downed = false,
            revivesPerformed = 0,
            receivedRevives = 0,
            lastDamageDealt = 0,
            lastDamageTaken = 0,
            acceptedSequenceWatermark = -1,
            acceptedEventTimestampWatermarkNs = -1,
            lastAppliedSequence = -1,
        )

        private fun humanPlayers(mode: GameMode): List<PlayerId> =
            if (mode == GameMode.DUAL) listOf(PlayerId.P1, PlayerId.P2) else listOf(PlayerId.P1)

        fun skillFor(playerClass: MonsterRaidClass, first: Boolean): MonsterRaidSkill = when (playerClass) {
            MonsterRaidClass.VANGUARD -> if (first) MonsterRaidSkill.RUNE_SLAM else MonsterRaidSkill.AEGIS_PULSE
            MonsterRaidClass.RANGER -> if (first) MonsterRaidSkill.STAR_VOLLEY else MonsterRaidSkill.WEAKPOINT_MARK
        }

        private fun immutableSnapshot(snapshot: MonsterRaidSnapshot): MonsterRaidSnapshot =
            snapshot.copy(players = immutablePlayers(snapshot.players))

        private fun immutablePlayers(players: Map<PlayerId, MonsterRaidPlayerState>) =
            Collections.unmodifiableMap(LinkedHashMap(players))
    }
}
