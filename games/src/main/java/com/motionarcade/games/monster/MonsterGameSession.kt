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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MonsterPhase {
    ENCOUNTER,
    RESULT,
}

enum class MonsterBossAttack {
    CLAW,
    ORB,
    BARRIER,
}

enum class MonsterOutcome {
    VICTORY,
    DEFEAT,
    TIMEOUT,
}

/** Player-owned combat state. Team resources remain explicit snapshot fields rather than hidden shared input state. */
data class MonsterPlayerState(
    val playerId: PlayerId,
    val health: Int,
    val stamina: Int,
    val score: Int,
    val blockTicksRemaining: Int,
    val attackCooldownTicks: Int,
    val requiresRearm: Boolean,
    val lastPlayerDamage: Int,
    val lastBossDamage: Int,
    val blockedAttackCount: Int,
    val ignoredActionCount: Int,
    val lastAppliedSequence: Long,
)

data class MonsterSnapshot(
    val schemaVersion: Int,
    val sessionId: String,
    val gameId: GameId,
    val mode: GameMode,
    val contentRevision: String,
    val seed: Long,
    val calibrationRevision: Int,
    val simulationTick: Long,
    val status: SessionStatus,
    val pauseReason: PauseReason?,
    val phase: MonsterPhase,
    val playerHealth: Int,
    val bossHealth: Int,
    val playerStamina: Int,
    val partnerCharge: Int,
    val score: Int,
    val roundTicksRemaining: Int,
    val bossTelegraph: MonsterBossAttack?,
    val bossTelegraphTicksRemaining: Int,
    val bossAttackOrdinal: Int,
    val blockTicksRemaining: Int,
    val attackCooldownTicks: Int,
    val requiresRearm: Boolean,
    val lastPlayerDamage: Int,
    val lastBossDamage: Int,
    val blockedAttackCount: Int,
    val counteredBarrierCount: Int,
    val ignoredActionCount: Int,
    val acceptedSequenceWatermark: Long,
    val lastAppliedSequence: Long,
    val outcome: MonsterOutcome?,
    /** Present only for a dual encounter; legacy scalar fields remain the P1 compatibility projection. */
    val players: Map<PlayerId, MonsterPlayerState> = emptyMap(),
    val pendingTeamUltimatePlayer: PlayerId? = null,
    val pendingTeamUltimateTimestampNs: Long? = null,
    val pendingTeamUltimateExpiresAtTick: Long? = null,
) {
    val paused: Boolean
        get() = status == SessionStatus.PAUSED
}

enum class MonsterInputRejection {
    SESSION_MISMATCH,
    WRONG_PLAYER,
    UNSUPPORTED_ACTION,
    QUEUE_OVERFLOW,
    CONTRACT_REJECTED,
}

enum class MonsterInputIgnoreReason {
    SESSION_PAUSED,
    SESSION_ALREADY_FINISHED,
}

sealed interface MonsterInputResult {
    data class Queued(
        val scheduledTick: Long,
        val snapshot: MonsterSnapshot,
    ) : MonsterInputResult

    data class Ignored(
        val reason: MonsterInputIgnoreReason,
        val snapshot: MonsterSnapshot,
    ) : MonsterInputResult

    data class Rejected(
        val reason: MonsterInputRejection,
        val snapshot: MonsterSnapshot,
    ) : MonsterInputResult
}

/**
 * Deterministic solo boss encounter. The companion's charge is a visible simulation resource,
 * never an inaccessible AI-only trigger. This core consumes only semantic events; live pose,
 * touch, and fixture adapters must all enter through the same [accept] path.
 *
 * This intentionally does not claim live pose mapping, dual-player role allocation, or asset
 * readiness. Those belong to later vertical-slice gates.
 */
class MonsterGameSession private constructor(
    initial: MonsterSnapshot,
) {
    private data class QueuedInput(
        val event: MotionEventEnvelope,
        val scheduledTick: Long,
    )

    private var state: MonsterSnapshot = initial
    private val eventGate = MotionEventGate(initial.calibrationRevision)
    private val inputQueue = ArrayDeque<QueuedInput>()
    // This is deliberately separate from the snapshot's pending fields. The latter drive a
    // short-lived HUD affordance, while SSOT requires the co-op pairing decision to use the
    // captured event timestamps even when camera/inference delivery crosses that affordance.
    private val recentTeamUltimateTimestampsByPlayer = PlayerId.entries.associateWith {
        ArrayDeque<Long>(TEAM_ULTIMATE_OBSERVATION_HISTORY_CAPACITY)
    }

    val snapshot: MonsterSnapshot
        @Synchronized get() = state

    @Synchronized
    fun accept(event: MotionEventEnvelope): MonsterInputResult {
        if (event.sessionId != state.sessionId) return rejected(MonsterInputRejection.SESSION_MISMATCH)
        if (event.playerId !in allowedPlayers(state.mode)) return rejected(MonsterInputRejection.WRONG_PLAYER)
        if (state.phase == MonsterPhase.RESULT) return ignored(MonsterInputIgnoreReason.SESSION_ALREADY_FINISHED)
        if (state.paused) return ignored(MonsterInputIgnoreReason.SESSION_PAUSED)
        if (event.type !in PLAYER_ACTIONS) return rejected(MonsterInputRejection.UNSUPPORTED_ACTION)
        if (inputQueue.size >= INPUT_QUEUE_CAPACITY) {
            inputQueue.clear()
            clearTeamUltimateObservations()
            state = state.copy(
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.EVENT_QUEUE_OVERFLOW,
            )
            return rejected(MonsterInputRejection.QUEUE_OVERFLOW)
        }
        if (eventGate.evaluateAndRecord(event) !is MotionEventGateResult.Accepted) {
            return rejected(MonsterInputRejection.CONTRACT_REJECTED)
        }
        val scheduledTick = state.simulationTick + 1L
        inputQueue.addLast(QueuedInput(event, scheduledTick))
        state = state.copy(acceptedSequenceWatermark = max(state.acceptedSequenceWatermark, event.sequenceNumber))
        return MonsterInputResult.Queued(scheduledTick, state)
    }

    @Synchronized
    fun advanceTicks(ticks: Int): MonsterSnapshot {
        require(ticks >= 0) { "ticks must be non-negative" }
        require(ticks <= MAX_CATCH_UP_TICKS) {
            "one game-loop call may advance at most $MAX_CATCH_UP_TICKS ticks"
        }
        if (state.paused || state.phase == MonsterPhase.RESULT) return state
        require(ticks.toLong() <= Long.MAX_VALUE - state.simulationTick) {
            "ticks exceed the supported simulation clock range"
        }
        repeat(ticks) {
            if (state.paused || state.phase == MonsterPhase.RESULT) return@repeat
            state = if (state.mode == GameMode.DUAL) {
                state.copy(
                    simulationTick = state.simulationTick + 1L,
                    partnerCharge = min(MAX_PARTNER_CHARGE, state.partnerCharge + PARTNER_CHARGE_PER_TICK),
                    players = state.players.mapValues { (_, player) ->
                        player.copy(
                            stamina = min(MAX_STAMINA, player.stamina + STAMINA_REGEN_PER_TICK),
                            lastPlayerDamage = 0,
                            lastBossDamage = 0,
                        )
                    },
                ).withDualP1Projection()
            } else {
                state.copy(
                    simulationTick = state.simulationTick + 1L,
                    playerStamina = min(MAX_STAMINA, state.playerStamina + STAMINA_REGEN_PER_TICK),
                    partnerCharge = min(MAX_PARTNER_CHARGE, state.partnerCharge + PARTNER_CHARGE_PER_TICK),
                    lastPlayerDamage = 0,
                    lastBossDamage = 0,
                )
            }
            takeDueInputs().forEach { input ->
                applyPlayerAction(input.event)
            }
            if (state.mode == GameMode.DUAL) expirePendingTeamUltimateIfDue()
            if (state.mode == GameMode.DUAL && state.bossHealth == 0) {
                finish(MonsterOutcome.VICTORY)
                return@repeat
            }
            if (state.phase == MonsterPhase.RESULT) return@repeat
            progressBossTelegraph()
            if (state.phase == MonsterPhase.RESULT) return@repeat
            decrementWindows()
            val remaining = state.roundTicksRemaining - 1
            state = state.copy(roundTicksRemaining = remaining)
            if (remaining == 0) finish(MonsterOutcome.TIMEOUT)
        }
        return state
    }

    @Synchronized
    fun pause(reason: PauseReason): MonsterSnapshot {
        if (state.phase == MonsterPhase.RESULT) return state
        inputQueue.clear()
        clearTeamUltimateObservations()
        state = state.copy(
            status = SessionStatus.PAUSED,
            pauseReason = reason,
            pendingTeamUltimatePlayer = null,
            pendingTeamUltimateTimestampNs = null,
            pendingTeamUltimateExpiresAtTick = null,
        )
        return state
    }

    @Synchronized
    fun resume(): Boolean {
        if (state.status != SessionStatus.PAUSED || state.phase == MonsterPhase.RESULT) return false
        inputQueue.clear()
        clearTeamUltimateObservations()
        state = state.copy(
            status = SessionStatus.RUNNING,
            pauseReason = null,
            pendingTeamUltimatePlayer = null,
            pendingTeamUltimateTimestampNs = null,
            pendingTeamUltimateExpiresAtTick = null,
        )
        return true
    }

    private fun applyPlayerAction(event: MotionEventEnvelope) {
        if (state.mode == GameMode.DUAL) {
            applyDualPlayerAction(event)
            return
        }
        state = state.copy(lastAppliedSequence = event.sequenceNumber)
        when (event.type) {
            MotionType.MONSTER_BLOCK -> applyBlock()
            MotionType.TEAM_ULTIMATE -> applyTeamUltimate()
            MotionType.PUNCH_JAB -> applyStrike(event, JAB_STAMINA_COST, JAB_BASE_DAMAGE)
            MotionType.PUNCH_HOOK -> applyStrike(event, HOOK_STAMINA_COST, HOOK_BASE_DAMAGE)
            else -> error("queued non-monster action")
        }
    }

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

    private fun applyDualPlayerAction(event: MotionEventEnvelope) {
        state = state.copy(lastAppliedSequence = event.sequenceNumber)
        when (event.type) {
            MotionType.MONSTER_BLOCK -> applyDualBlock(event.playerId)
            MotionType.TEAM_ULTIMATE -> applyDualTeamUltimate(event)
            MotionType.PUNCH_JAB -> applyDualStrike(event, JAB_STAMINA_COST, JAB_BASE_DAMAGE)
            MotionType.PUNCH_HOOK -> applyDualStrike(event, HOOK_STAMINA_COST, HOOK_BASE_DAMAGE)
            else -> error("queued non-monster action")
        }
    }

    private fun applyDualBlock(playerId: PlayerId) = updateDualPlayer(playerId) { player ->
        player.copy(blockTicksRemaining = BLOCK_WINDOW_TICKS, requiresRearm = false)
    }

    private fun applyDualTeamUltimate(event: MotionEventEnvelope) {
        val player = dualPlayer(event.playerId)
        if (state.partnerCharge < MAX_PARTNER_CHARGE) {
            recentTeamUltimateTimestampsByPlayer.getValue(event.playerId).clear()
            clearVisiblePendingTeamUltimateFor(event.playerId)
            updateDualPlayer(event.playerId) {
                player.copy(
                    ignoredActionCount = player.ignoredActionCount + 1,
                    lastAppliedSequence = event.sequenceNumber,
                )
            }
            return
        }
        val counterpart = if (event.playerId == PlayerId.P1) PlayerId.P2 else PlayerId.P1
        if (findMatchingTeamUltimateTimestamp(counterpart, event.eventTimestampNs) != null) {
            resolveDualTeamUltimate(event)
            return
        }

        // Preserve a bounded event-time history per player. A later same-player retry must not
        // overwrite an earlier valid observation before its delayed counterpart arrives.
        recordTeamUltimateObservation(event.playerId, event.eventTimestampNs)
        state = state.copy(
            pendingTeamUltimatePlayer = event.playerId,
            pendingTeamUltimateTimestampNs = event.eventTimestampNs,
            pendingTeamUltimateExpiresAtTick = state.simulationTick + TEAM_ULTIMATE_SYNC_WINDOW_TICKS,
        )
        updateDualPlayer(event.playerId) { player.copy(lastAppliedSequence = event.sequenceNumber) }
    }

    private fun resolveDualTeamUltimate(event: MotionEventEnvelope) {
        val counteredBarrier = state.bossTelegraph == MonsterBossAttack.BARRIER &&
            state.bossTelegraphTicksRemaining in 1..COUNTER_WINDOW_TICKS
        val requestedDamage = TEAM_ULTIMATE_DAMAGE + if (counteredBarrier) BARRIER_COUNTER_BONUS else 0
        val dealtDamage = min(requestedDamage, state.bossHealth)
        // The integer split is deterministic and preserves the full team score. P1 receives the
        // single remainder when a future balance change makes the damage odd.
        val p1Damage = (dealtDamage + 1) / 2
        val p2Damage = dealtDamage / 2
        clearTeamUltimateObservations()
        state = state.copy(
            bossHealth = (state.bossHealth - dealtDamage).coerceAtLeast(0),
            partnerCharge = 0,
            counteredBarrierCount = state.counteredBarrierCount + if (counteredBarrier) 1 else 0,
            bossTelegraph = if (counteredBarrier) null else state.bossTelegraph,
            bossTelegraphTicksRemaining = if (counteredBarrier) 0 else state.bossTelegraphTicksRemaining,
            pendingTeamUltimatePlayer = null,
            pendingTeamUltimateTimestampNs = null,
            pendingTeamUltimateExpiresAtTick = null,
        )
        updateDualPlayer(PlayerId.P1) { player ->
            player.copy(
                score = player.score + p1Damage,
                lastPlayerDamage = p1Damage,
                lastAppliedSequence = if (event.playerId == PlayerId.P1) event.sequenceNumber else player.lastAppliedSequence,
            )
        }
        updateDualPlayer(PlayerId.P2) { player ->
            player.copy(
                score = player.score + p2Damage,
                lastPlayerDamage = p2Damage,
                lastAppliedSequence = if (event.playerId == PlayerId.P2) event.sequenceNumber else player.lastAppliedSequence,
            )
        }
    }

    private fun applyDualStrike(event: MotionEventEnvelope, staminaCost: Int, baseDamage: Int) {
        val player = dualPlayer(event.playerId)
        if (player.requiresRearm || player.attackCooldownTicks > 0 || player.stamina < staminaCost) {
            updateDualPlayer(event.playerId) {
                player.copy(
                    ignoredActionCount = player.ignoredActionCount + 1,
                    lastAppliedSequence = event.sequenceNumber,
                )
            }
            return
        }
        val postureQuality = min(event.quality, event.confidence)
        val qualityBonus = when {
            postureQuality >= 0.85f -> MAX_QUALITY_DAMAGE_BONUS
            postureQuality >= 0.65f -> MID_QUALITY_DAMAGE_BONUS
            else -> 0
        }
        val dealtDamage = min(baseDamage + qualityBonus, state.bossHealth)
        state = state.copy(bossHealth = (state.bossHealth - dealtDamage).coerceAtLeast(0))
        updateDualPlayer(event.playerId) {
            player.copy(
                stamina = player.stamina - staminaCost,
                attackCooldownTicks = STRIKE_COOLDOWN_TICKS,
                requiresRearm = true,
                score = player.score + dealtDamage,
                lastPlayerDamage = dealtDamage,
                lastAppliedSequence = event.sequenceNumber,
            )
        }
    }

    private fun expirePendingTeamUltimateIfDue() {
        val expiresAtTick = state.pendingTeamUltimateExpiresAtTick ?: return
        if (state.simulationTick < expiresAtTick) return
        state = state.copy(
            pendingTeamUltimatePlayer = null,
            pendingTeamUltimateTimestampNs = null,
            pendingTeamUltimateExpiresAtTick = null,
        )
    }

    private fun clearVisiblePendingTeamUltimateFor(playerId: PlayerId) {
        if (state.pendingTeamUltimatePlayer != playerId) return
        state = state.copy(
            pendingTeamUltimatePlayer = null,
            pendingTeamUltimateTimestampNs = null,
            pendingTeamUltimateExpiresAtTick = null,
        )
    }

    private fun recordTeamUltimateObservation(playerId: PlayerId, timestampNs: Long) {
        val history = recentTeamUltimateTimestampsByPlayer.getValue(playerId)
        history.addLast(timestampNs)
        while (history.size > TEAM_ULTIMATE_OBSERVATION_HISTORY_CAPACITY) {
            history.removeFirst()
        }
    }

    private fun findMatchingTeamUltimateTimestamp(
        playerId: PlayerId,
        eventTimestampNs: Long,
    ): Long? {
        var nearest: Long? = null
        var nearestDistance = Long.MAX_VALUE
        recentTeamUltimateTimestampsByPlayer.getValue(playerId).forEach { candidate ->
            val distance = abs(eventTimestampNs - candidate)
            val currentNearest = nearest
            if (
                distance <= TEAM_ULTIMATE_SYNC_WINDOW_NS &&
                (
                    currentNearest == null ||
                        distance < nearestDistance ||
                        (distance == nearestDistance && candidate < currentNearest)
                    )
            ) {
                nearest = candidate
                nearestDistance = distance
            }
        }
        return nearest
    }

    private fun clearTeamUltimateObservations() {
        recentTeamUltimateTimestampsByPlayer.values.forEach { it.clear() }
    }

    private fun dualPlayer(playerId: PlayerId): MonsterPlayerState =
        checkNotNull(state.players[playerId]) { "dual player $playerId is missing" }

    private fun updateDualPlayer(playerId: PlayerId, update: (MonsterPlayerState) -> MonsterPlayerState) {
        val current = dualPlayer(playerId)
        state = state.copy(players = state.players + (playerId to update(current))).withDualP1Projection()
    }

    private fun MonsterSnapshot.withDualP1Projection(): MonsterSnapshot {
        val p1 = checkNotNull(players[PlayerId.P1]) { "dual P1 is missing" }
        return copy(
            playerHealth = p1.health,
            playerStamina = p1.stamina,
            score = p1.score,
            blockTicksRemaining = p1.blockTicksRemaining,
            attackCooldownTicks = p1.attackCooldownTicks,
            requiresRearm = p1.requiresRearm,
            lastPlayerDamage = p1.lastPlayerDamage,
            lastBossDamage = p1.lastBossDamage,
            blockedAttackCount = p1.blockedAttackCount,
            ignoredActionCount = p1.ignoredActionCount,
        )
    }

    private fun applyBlock() {
        state = state.copy(
            blockTicksRemaining = BLOCK_WINDOW_TICKS,
            requiresRearm = false,
        )
    }

    private fun applyTeamUltimate() {
        if (state.partnerCharge < MAX_PARTNER_CHARGE) {
            state = state.copy(ignoredActionCount = state.ignoredActionCount + 1)
            return
        }
        val counteredBarrier = state.bossTelegraph == MonsterBossAttack.BARRIER &&
            state.bossTelegraphTicksRemaining in 1..COUNTER_WINDOW_TICKS
        val damage = TEAM_ULTIMATE_DAMAGE + if (counteredBarrier) BARRIER_COUNTER_BONUS else 0
        val bossHealth = (state.bossHealth - damage).coerceAtLeast(0)
        state = state.copy(
            bossHealth = bossHealth,
            partnerCharge = 0,
            score = state.score + damage,
            lastPlayerDamage = damage,
            counteredBarrierCount = state.counteredBarrierCount + if (counteredBarrier) 1 else 0,
            bossTelegraph = if (counteredBarrier) null else state.bossTelegraph,
            bossTelegraphTicksRemaining = if (counteredBarrier) 0 else state.bossTelegraphTicksRemaining,
        )
        if (bossHealth == 0) finish(MonsterOutcome.VICTORY)
    }

    private fun applyStrike(event: MotionEventEnvelope, staminaCost: Int, baseDamage: Int) {
        if (state.requiresRearm || state.attackCooldownTicks > 0 || state.playerStamina < staminaCost) {
            state = state.copy(ignoredActionCount = state.ignoredActionCount + 1)
            return
        }
        val postureQuality = min(event.quality, event.confidence)
        val qualityBonus = when {
            postureQuality >= 0.85f -> MAX_QUALITY_DAMAGE_BONUS
            postureQuality >= 0.65f -> MID_QUALITY_DAMAGE_BONUS
            else -> 0
        }
        val damage = baseDamage + qualityBonus
        val bossHealth = (state.bossHealth - damage).coerceAtLeast(0)
        state = state.copy(
            bossHealth = bossHealth,
            playerStamina = state.playerStamina - staminaCost,
            attackCooldownTicks = STRIKE_COOLDOWN_TICKS,
            requiresRearm = true,
            score = state.score + damage,
            lastPlayerDamage = damage,
        )
        if (bossHealth == 0) finish(MonsterOutcome.VICTORY)
    }

    private fun progressBossTelegraph() {
        val attack = state.bossTelegraph
        if (attack == null) {
            if (state.simulationTick % BOSS_ATTACK_CADENCE_TICKS == 0L) {
                state = state.copy(
                    bossTelegraph = nextBossAttack(state.seed, state.bossAttackOrdinal),
                    bossTelegraphTicksRemaining = BOSS_TELEGRAPH_TICKS,
                    bossAttackOrdinal = state.bossAttackOrdinal + 1,
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
        if (state.mode == GameMode.DUAL) {
            resolveDualBossAttack(attack)
            return
        }
        val guarded = state.blockTicksRemaining > 0 && attack != MonsterBossAttack.BARRIER
        val damage = when {
            guarded -> GUARDED_DAMAGE
            attack == MonsterBossAttack.BARRIER -> BARRIER_DAMAGE
            else -> BOSS_DAMAGE
        }
        val playerHealth = (state.playerHealth - damage).coerceAtLeast(0)
        state = state.copy(
            playerHealth = playerHealth,
            bossTelegraph = null,
            bossTelegraphTicksRemaining = 0,
            lastBossDamage = damage,
            blockedAttackCount = state.blockedAttackCount + if (guarded) 1 else 0,
        )
        if (playerHealth == 0) finish(MonsterOutcome.DEFEAT)
    }

    private fun resolveDualBossAttack(attack: MonsterBossAttack) {
        state.players.keys.sortedBy { it.ordinal }.forEach { playerId ->
            val player = dualPlayer(playerId)
            val guarded = player.blockTicksRemaining > 0 && attack != MonsterBossAttack.BARRIER
            val damage = when {
                guarded -> GUARDED_DAMAGE
                attack == MonsterBossAttack.BARRIER -> BARRIER_DAMAGE
                else -> BOSS_DAMAGE
            }
            updateDualPlayer(playerId) {
                player.copy(
                    health = (player.health - damage).coerceAtLeast(0),
                    lastBossDamage = damage,
                    blockedAttackCount = player.blockedAttackCount + if (guarded) 1 else 0,
                )
            }
        }
        state = state.copy(bossTelegraph = null, bossTelegraphTicksRemaining = 0)
        if (state.players.values.all { it.health == 0 }) finish(MonsterOutcome.DEFEAT)
    }

    private fun decrementWindows() {
        if (state.mode == GameMode.DUAL) {
            state = state.copy(
                players = state.players.mapValues { (_, player) ->
                    player.copy(
                        blockTicksRemaining = (player.blockTicksRemaining - 1).coerceAtLeast(0),
                        attackCooldownTicks = (player.attackCooldownTicks - 1).coerceAtLeast(0),
                    )
                },
            ).withDualP1Projection()
            return
        }
        state = state.copy(
            blockTicksRemaining = (state.blockTicksRemaining - 1).coerceAtLeast(0),
            attackCooldownTicks = (state.attackCooldownTicks - 1).coerceAtLeast(0),
        )
    }

    private fun finish(outcome: MonsterOutcome) {
        inputQueue.clear()
        clearTeamUltimateObservations()
        state = state.copy(
            status = SessionStatus.COMPLETED,
            pauseReason = null,
            phase = MonsterPhase.RESULT,
            bossTelegraph = null,
            bossTelegraphTicksRemaining = 0,
            outcome = outcome,
            pendingTeamUltimatePlayer = null,
            pendingTeamUltimateTimestampNs = null,
            pendingTeamUltimateExpiresAtTick = null,
        )
    }

    private fun rejected(reason: MonsterInputRejection): MonsterInputResult.Rejected =
        MonsterInputResult.Rejected(reason, state)

    private fun ignored(reason: MonsterInputIgnoreReason): MonsterInputResult.Ignored =
        MonsterInputResult.Ignored(reason, state)

    companion object {
        val PLAYER_ACTIONS = setOf(
            MotionType.MONSTER_BLOCK,
            MotionType.TEAM_ULTIMATE,
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
        )
        val BOSS_ATTACKS = MonsterBossAttack.entries

        const val SNAPSHOT_SCHEMA_VERSION = 2
        const val CONTENT_REVISION = "monster-rules-v2"
        const val FIXED_STEP_NS = 100_000_000L
        const val ROUND_TICKS = 900
        const val MAX_CATCH_UP_TICKS = 5
        const val INPUT_QUEUE_CAPACITY = 32
        const val MAX_HEALTH = 100
        const val BOSS_MAX_HEALTH = 160
        const val MAX_STAMINA = 100
        const val MAX_PARTNER_CHARGE = 100
        const val STAMINA_REGEN_PER_TICK = 1
        const val PARTNER_CHARGE_PER_TICK = 2
        const val JAB_STAMINA_COST = 7
        const val HOOK_STAMINA_COST = 13
        const val JAB_BASE_DAMAGE = 6
        const val HOOK_BASE_DAMAGE = 10
        const val MID_QUALITY_DAMAGE_BONUS = 1
        const val MAX_QUALITY_DAMAGE_BONUS = 3
        const val TEAM_ULTIMATE_DAMAGE = 26
        const val BARRIER_COUNTER_BONUS = 10
        const val STRIKE_COOLDOWN_TICKS = 4
        const val BLOCK_WINDOW_TICKS = 5
        const val BOSS_ATTACK_CADENCE_TICKS = 12L
        const val BOSS_TELEGRAPH_TICKS = 5
        const val COUNTER_WINDOW_TICKS = 2
        const val TEAM_ULTIMATE_SYNC_WINDOW_NS = 600_000_000L
        const val TEAM_ULTIMATE_SYNC_WINDOW_TICKS =
            (TEAM_ULTIMATE_SYNC_WINDOW_NS / FIXED_STEP_NS).toInt()
        const val TEAM_ULTIMATE_OBSERVATION_HISTORY_CAPACITY = INPUT_QUEUE_CAPACITY
        const val BOSS_DAMAGE = 12
        const val BARRIER_DAMAGE = 18
        const val GUARDED_DAMAGE = 3

        fun start(
            sessionId: String,
            seed: Long,
            calibrationRevision: Int,
            mode: GameMode = GameMode.SOLO,
        ): MonsterGameSession {
            require(sessionId.length in 1..120) { "sessionId length must be 1..120" }
            require(calibrationRevision >= 0) { "calibrationRevision must be non-negative" }
            return MonsterGameSession(
                MonsterSnapshot(
                    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                    sessionId = sessionId,
                    gameId = GameId.MONSTER,
                    mode = mode,
                    contentRevision = CONTENT_REVISION,
                    seed = seed,
                    calibrationRevision = calibrationRevision,
                    simulationTick = 0L,
                    status = SessionStatus.RUNNING,
                    pauseReason = null,
                    phase = MonsterPhase.ENCOUNTER,
                    playerHealth = MAX_HEALTH,
                    bossHealth = BOSS_MAX_HEALTH,
                    playerStamina = MAX_STAMINA,
                    partnerCharge = 0,
                    score = 0,
                    roundTicksRemaining = ROUND_TICKS,
                    bossTelegraph = null,
                    bossTelegraphTicksRemaining = 0,
                    bossAttackOrdinal = 0,
                    blockTicksRemaining = 0,
                    attackCooldownTicks = 0,
                    requiresRearm = false,
                    lastPlayerDamage = 0,
                    lastBossDamage = 0,
                    blockedAttackCount = 0,
                    counteredBarrierCount = 0,
                    ignoredActionCount = 0,
                    acceptedSequenceWatermark = -1L,
                    lastAppliedSequence = -1L,
                    outcome = null,
                    players = if (mode == GameMode.DUAL) dualPlayers() else emptyMap(),
                ),
            )
        }

        private fun dualPlayers(): Map<PlayerId, MonsterPlayerState> =
            listOf(PlayerId.P1, PlayerId.P2).associateWith { playerId ->
                MonsterPlayerState(
                    playerId = playerId,
                    health = MAX_HEALTH,
                    stamina = MAX_STAMINA,
                    score = 0,
                    blockTicksRemaining = 0,
                    attackCooldownTicks = 0,
                    requiresRearm = false,
                    lastPlayerDamage = 0,
                    lastBossDamage = 0,
                    blockedAttackCount = 0,
                    ignoredActionCount = 0,
                    lastAppliedSequence = -1L,
                )
            }

        private fun nextBossAttack(seed: Long, ordinal: Int): MonsterBossAttack {
            val index = ((seed xor ordinal.toLong()) and Long.MAX_VALUE) % BOSS_ATTACKS.size
            return BOSS_ATTACKS[index.toInt()]
        }

        private fun allowedPlayers(mode: GameMode): Set<PlayerId> =
            if (mode == GameMode.DUAL) setOf(PlayerId.P1, PlayerId.P2) else setOf(PlayerId.P1)
    }
}
