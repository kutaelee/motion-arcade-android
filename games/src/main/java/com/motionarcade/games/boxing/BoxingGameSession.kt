package com.motionarcade.games.boxing

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
import kotlin.math.max
import kotlin.math.min

enum class BoxingPhase {
    ROUND,
    RESULT,
}

enum class BoxingAiAttack {
    STRAIGHT,
    HOOK,
    BODY,
}

enum class BoxingDodgeDirection {
    LEFT,
    RIGHT,
}

enum class BoxingOutcome {
    WIN,
    LOSS,
    DRAW,
}

/** Player-owned combat state. In dual mode no player's gesture may consume another player's rearm, cooldown, or score. */
data class BoxingPlayerState(
    val playerId: PlayerId,
    val health: Int,
    val stamina: Int,
    val score: Int,
    val guardTicksRemaining: Int,
    val dodgeDirection: BoxingDodgeDirection?,
    val dodgeTicksRemaining: Int,
    val attackCooldownTicks: Int,
    val requiresReturnToGuard: Boolean,
    val lastPlayerDamage: Int,
    val lastReceivedDamage: Int,
    val blockedAttackCount: Int,
    val dodgedAttackCount: Int,
    val ignoredStrikeCount: Int,
    val acceptedSequenceWatermark: Long,
    val acceptedEventTimestampWatermarkNs: Long,
    val lastAppliedSequence: Long,
)

data class BoxingSnapshot(
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
    val phase: BoxingPhase,
    val playerHealth: Int,
    val opponentHealth: Int,
    val playerStamina: Int,
    val score: Int,
    val roundTicksRemaining: Int,
    val aiTelegraph: BoxingAiAttack?,
    val aiTelegraphTicksRemaining: Int,
    val aiAttackOrdinal: Int,
    val guardTicksRemaining: Int,
    val dodgeDirection: BoxingDodgeDirection?,
    val dodgeTicksRemaining: Int,
    val attackCooldownTicks: Int,
    val requiresReturnToGuard: Boolean,
    val lastPlayerDamage: Int,
    val lastReceivedDamage: Int,
    val blockedAttackCount: Int,
    val dodgedAttackCount: Int,
    val ignoredStrikeCount: Int,
    val acceptedSequenceWatermark: Long,
    val acceptedEventTimestampWatermarkNs: Long,
    val lastAppliedSequence: Long,
    val outcome: BoxingOutcome?,
    /** Present only for a dual session; legacy scalar fields remain the solo compatibility projection. */
    val players: Map<PlayerId, BoxingPlayerState> = emptyMap(),
) {
    val paused: Boolean
        get() = status == SessionStatus.PAUSED
}

enum class BoxingInputRejection {
    SESSION_MISMATCH,
    WRONG_PLAYER,
    UNSUPPORTED_ACTION,
    QUEUE_OVERFLOW,
    CONTRACT_REJECTED,
}

enum class BoxingInputIgnoreReason {
    SESSION_PAUSED,
    SESSION_ALREADY_FINISHED,
}

sealed interface BoxingInputResult {
    data class Queued(
        val scheduledTick: Long,
        val snapshot: BoxingSnapshot,
    ) : BoxingInputResult

    data class Ignored(
        val reason: BoxingInputIgnoreReason,
        val snapshot: BoxingSnapshot,
    ) : BoxingInputResult

    data class Rejected(
        val reason: BoxingInputRejection,
        val snapshot: BoxingSnapshot,
    ) : BoxingInputResult
}

/**
 * Deterministic solo-AI or two-player boxing round. Camera and MediaPipe stay outside this module:
 * it accepts only validated semantic motion events, so touch, fixture, and pose input resolve
 * through one rule path. Every strike requires a return-to-guard action before the next strike can
 * score.
 */
class BoxingGameSession private constructor(
    initial: BoxingSnapshot,
) {
    private data class QueuedInput(
        val event: MotionEventEnvelope,
        val scheduledTick: Long,
    )

    private var state: BoxingSnapshot = initial
    private var eventGate = MotionEventGate(initial.calibrationRevision)
    private val inputQueue = ArrayDeque<QueuedInput>()
    private val acceptedSequenceFenceByPlayer = allowedPlayers(initial.mode).associateWithTo(mutableMapOf()) { playerId ->
        initial.players[playerId]?.acceptedSequenceWatermark ?: initial.acceptedSequenceWatermark
    }
    private val acceptedTimestampFenceByPlayer = allowedPlayers(initial.mode).associateWithTo(mutableMapOf()) { playerId ->
        initial.players[playerId]?.acceptedEventTimestampWatermarkNs
            ?: initial.acceptedEventTimestampWatermarkNs
    }

    val snapshot: BoxingSnapshot
        @Synchronized get() = state

    @Synchronized
    fun accept(event: MotionEventEnvelope): BoxingInputResult {
        if (event.sessionId != state.sessionId) return rejected(BoxingInputRejection.SESSION_MISMATCH)
        if (event.playerId !in allowedPlayers(state.mode)) return rejected(BoxingInputRejection.WRONG_PLAYER)
        if (state.phase == BoxingPhase.RESULT) return ignored(BoxingInputIgnoreReason.SESSION_ALREADY_FINISHED)
        if (state.paused) return ignored(BoxingInputIgnoreReason.SESSION_PAUSED)
        if (event.type !in PLAYER_ACTIONS) return rejected(BoxingInputRejection.UNSUPPORTED_ACTION)
        if (event.sequenceNumber <= acceptedSequenceFenceByPlayer.getValue(event.playerId)) {
            return rejected(BoxingInputRejection.CONTRACT_REJECTED)
        }
        if (event.eventTimestampNs < acceptedTimestampFenceByPlayer.getValue(event.playerId)) {
            return rejected(BoxingInputRejection.CONTRACT_REJECTED)
        }
        if (inputQueue.size >= INPUT_QUEUE_CAPACITY) {
            inputQueue.clear()
            state = state.copy(
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.EVENT_QUEUE_OVERFLOW,
            )
            return rejected(BoxingInputRejection.QUEUE_OVERFLOW)
        }
        if (eventGate.evaluateAndRecord(event) !is MotionEventGateResult.Accepted) {
            return rejected(BoxingInputRejection.CONTRACT_REJECTED)
        }
        val scheduledTick = state.simulationTick + 1L
        inputQueue.addLast(QueuedInput(event, scheduledTick))
        acceptedSequenceFenceByPlayer[event.playerId] = event.sequenceNumber
        acceptedTimestampFenceByPlayer[event.playerId] = event.eventTimestampNs
        state = state.copy(
            acceptedSequenceWatermark = max(state.acceptedSequenceWatermark, event.sequenceNumber),
            acceptedEventTimestampWatermarkNs = max(
                state.acceptedEventTimestampWatermarkNs,
                event.eventTimestampNs,
            ),
            players = if (state.mode == GameMode.DUAL) {
                val player = state.players.getValue(event.playerId)
                state.players + (
                    event.playerId to player.copy(
                        acceptedSequenceWatermark = event.sequenceNumber,
                        acceptedEventTimestampWatermarkNs = event.eventTimestampNs,
                    )
                )
            } else {
                state.players
            },
        )
        return BoxingInputResult.Queued(scheduledTick, state)
    }

    @Synchronized
    fun advanceTicks(ticks: Int): BoxingSnapshot {
        require(ticks >= 0) { "ticks must be non-negative" }
        require(ticks <= MAX_CATCH_UP_TICKS) {
            "one game-loop call may advance at most $MAX_CATCH_UP_TICKS ticks"
        }
        if (state.paused || state.phase == BoxingPhase.RESULT) return state
        require(ticks.toLong() <= Long.MAX_VALUE - state.simulationTick) {
            "ticks exceed the supported simulation clock range"
        }
        repeat(ticks) {
            if (state.paused || state.phase == BoxingPhase.RESULT) return@repeat
            state = if (state.mode == GameMode.DUAL) {
                state.copy(
                    simulationTick = state.simulationTick + 1L,
                    players = state.players.mapValues { (_, player) ->
                        player.copy(
                            stamina = min(MAX_STAMINA, player.stamina + STAMINA_REGEN_PER_TICK),
                            lastPlayerDamage = 0,
                            lastReceivedDamage = 0,
                        )
                    },
                ).withDualP1Projection()
            } else {
                state.copy(
                    simulationTick = state.simulationTick + 1L,
                    playerStamina = min(MAX_STAMINA, state.playerStamina + STAMINA_REGEN_PER_TICK),
                    lastPlayerDamage = 0,
                    lastReceivedDamage = 0,
                )
            }
            takeDueInputs().forEach { input ->
                applyPlayerAction(input.event)
            }
            if (state.mode == GameMode.DUAL) {
                finishDualKnockoutIfNeeded()
                if (state.phase == BoxingPhase.RESULT) return@repeat
                decrementWindows()
                val remaining = state.roundTicksRemaining - 1
                state = state.copy(roundTicksRemaining = remaining)
                if (remaining == 0) finishByDecision()
                return@repeat
            }
            if (state.phase == BoxingPhase.RESULT) return@repeat
            progressAiTelegraph()
            if (state.phase == BoxingPhase.RESULT) return@repeat
            decrementWindows()
            val remaining = state.roundTicksRemaining - 1
            state = state.copy(roundTicksRemaining = remaining)
            if (remaining == 0) finishByDecision()
        }
        return state
    }

    @Synchronized
    fun pause(reason: PauseReason): BoxingSnapshot {
        if (state.phase == BoxingPhase.RESULT) return state
        inputQueue.clear()
        state = state.copy(status = SessionStatus.PAUSED, pauseReason = reason)
        return state
    }

    /**
     * Migrates an active round to a newly calibrated camera epoch without resetting gameplay.
     * Pending semantic input belongs to the old camera epoch and is deliberately discarded.
     */
    @Synchronized
    fun recalibrate(newCalibrationRevision: Int): BoxingSnapshot {
        require(newCalibrationRevision >= 0) { "newCalibrationRevision must be non-negative" }
        require(newCalibrationRevision > state.calibrationRevision) {
            "newCalibrationRevision must be strictly greater than the active revision"
        }
        inputQueue.clear()
        eventGate = MotionEventGate(newCalibrationRevision)
        state = if (state.phase == BoxingPhase.RESULT) {
            state.copy(calibrationRevision = newCalibrationRevision)
        } else {
            state.copy(
                calibrationRevision = newCalibrationRevision,
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.CAMERA_SWITCH,
            )
        }
        return state
    }

    @Synchronized
    fun checkpointForAppBackground(): BoxingSnapshot = pause(PauseReason.APP_BACKGROUND)

    @Synchronized
    fun resume(): Boolean {
        if (state.status != SessionStatus.PAUSED || state.phase == BoxingPhase.RESULT) return false
        inputQueue.clear()
        state = state.copy(status = SessionStatus.RUNNING, pauseReason = null)
        return true
    }

    private fun applyPlayerAction(event: MotionEventEnvelope) {
        if (state.mode == GameMode.DUAL) {
            applyDualPlayerAction(event)
            return
        }
        state = state.copy(lastAppliedSequence = event.sequenceNumber)
        when (event.type) {
            MotionType.BOXING_GUARD -> applyGuard()
            MotionType.DODGE_LEFT -> applyDodge(BoxingDodgeDirection.LEFT)
            MotionType.DODGE_RIGHT -> applyDodge(BoxingDodgeDirection.RIGHT)
            MotionType.PUNCH_JAB -> applyStrike(event, JAB_STAMINA_COST, JAB_BASE_DAMAGE)
            MotionType.PUNCH_HOOK -> applyStrike(event, HOOK_STAMINA_COST, HOOK_BASE_DAMAGE)
            else -> error("queued non-boxing action")
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
            MotionType.BOXING_GUARD -> applyDualGuard(event.playerId)
            MotionType.DODGE_LEFT -> applyDualDodge(event.playerId, BoxingDodgeDirection.LEFT)
            MotionType.DODGE_RIGHT -> applyDualDodge(event.playerId, BoxingDodgeDirection.RIGHT)
            MotionType.PUNCH_JAB -> applyDualStrike(event, JAB_STAMINA_COST, JAB_BASE_DAMAGE)
            MotionType.PUNCH_HOOK -> applyDualStrike(event, HOOK_STAMINA_COST, HOOK_BASE_DAMAGE)
            else -> error("queued non-boxing action")
        }
    }

    private fun applyDualGuard(playerId: PlayerId) = updateDualPlayer(playerId) { player ->
        player.copy(
            guardTicksRemaining = GUARD_WINDOW_TICKS,
            dodgeDirection = null,
            dodgeTicksRemaining = 0,
            requiresReturnToGuard = false,
        )
    }

    private fun applyDualDodge(playerId: PlayerId, direction: BoxingDodgeDirection) {
        val player = dualPlayer(playerId)
        if (player.stamina < DODGE_STAMINA_COST) return
        updateDualPlayer(playerId) {
            player.copy(
                stamina = player.stamina - DODGE_STAMINA_COST,
                guardTicksRemaining = 0,
                dodgeDirection = direction,
                dodgeTicksRemaining = DODGE_WINDOW_TICKS,
            )
        }
    }

    private fun applyDualStrike(event: MotionEventEnvelope, staminaCost: Int, baseDamage: Int) {
        val player = dualPlayer(event.playerId)
        if (
            player.requiresReturnToGuard ||
            player.attackCooldownTicks > 0 ||
            player.stamina < staminaCost
        ) {
            val repetitionPenalty = if (player.requiresReturnToGuard) REPEAT_STRIKE_STAMINA_PENALTY else 0
            updateDualPlayer(event.playerId) {
                player.copy(
                    stamina = (player.stamina - repetitionPenalty).coerceAtLeast(0),
                    ignoredStrikeCount = player.ignoredStrikeCount + 1,
                    lastAppliedSequence = event.sequenceNumber,
                )
            }
            return
        }
        val postureQuality = event.quality
        val qualityBonus = when {
            postureQuality >= 0.85f -> MAX_QUALITY_DAMAGE_BONUS
            postureQuality >= 0.65f -> MID_QUALITY_DAMAGE_BONUS
            else -> 0
        }
        val targetId = opponentOf(event.playerId)
        val target = dualPlayer(targetId)
        val dodged = target.dodgeTicksRemaining > 0
        val guarded = !dodged && target.guardTicksRemaining > 0
        val requestedDamage = when {
            dodged -> 0
            guarded -> PVP_GUARDED_DAMAGE
            else -> baseDamage + qualityBonus
        }
        val dealtDamage = min(requestedDamage, target.health)
        updateDualPlayer(targetId) {
            target.copy(
                health = target.health - dealtDamage,
                lastReceivedDamage = dealtDamage,
                blockedAttackCount = target.blockedAttackCount + if (guarded) 1 else 0,
                dodgedAttackCount = target.dodgedAttackCount + if (dodged) 1 else 0,
            )
        }
        updateDualPlayer(event.playerId) {
            player.copy(
                stamina = player.stamina - staminaCost,
                attackCooldownTicks = STRIKE_COOLDOWN_TICKS,
                requiresReturnToGuard = true,
                lastPlayerDamage = dealtDamage,
                score = player.score + dealtDamage,
                lastAppliedSequence = event.sequenceNumber,
            )
        }
    }

    private fun dualPlayer(playerId: PlayerId): BoxingPlayerState =
        checkNotNull(state.players[playerId]) { "dual player $playerId is missing" }

    private fun updateDualPlayer(playerId: PlayerId, update: (BoxingPlayerState) -> BoxingPlayerState) {
        val current = dualPlayer(playerId)
        state = state.copy(players = state.players + (playerId to update(current))).withDualP1Projection()
    }

    private fun BoxingSnapshot.withDualP1Projection(): BoxingSnapshot {
        val p1 = checkNotNull(players[PlayerId.P1]) { "dual P1 is missing" }
        val p2 = checkNotNull(players[PlayerId.P2]) { "dual P2 is missing" }
        return copy(
            playerHealth = p1.health,
            opponentHealth = p2.health,
            playerStamina = p1.stamina,
            score = p1.score,
            guardTicksRemaining = p1.guardTicksRemaining,
            dodgeDirection = p1.dodgeDirection,
            dodgeTicksRemaining = p1.dodgeTicksRemaining,
            attackCooldownTicks = p1.attackCooldownTicks,
            requiresReturnToGuard = p1.requiresReturnToGuard,
            lastPlayerDamage = p1.lastPlayerDamage,
            lastReceivedDamage = p1.lastReceivedDamage,
            blockedAttackCount = p1.blockedAttackCount,
            dodgedAttackCount = p1.dodgedAttackCount,
            ignoredStrikeCount = p1.ignoredStrikeCount,
        )
    }

    private fun opponentOf(playerId: PlayerId): PlayerId = when (playerId) {
        PlayerId.P1 -> PlayerId.P2
        PlayerId.P2 -> PlayerId.P1
        PlayerId.AI -> error("AI is not a dual boxing player")
    }

    private fun finishDualKnockoutIfNeeded() {
        val p1Health = dualPlayer(PlayerId.P1).health
        val p2Health = dualPlayer(PlayerId.P2).health
        when {
            p1Health == 0 && p2Health == 0 -> finish(BoxingOutcome.DRAW)
            p2Health == 0 -> finish(BoxingOutcome.WIN)
            p1Health == 0 -> finish(BoxingOutcome.LOSS)
        }
    }

    private fun applyGuard() {
        state = state.copy(
            guardTicksRemaining = GUARD_WINDOW_TICKS,
            dodgeDirection = null,
            dodgeTicksRemaining = 0,
            requiresReturnToGuard = false,
        )
    }

    private fun applyDodge(direction: BoxingDodgeDirection) {
        if (state.playerStamina < DODGE_STAMINA_COST) return
        state = state.copy(
            playerStamina = state.playerStamina - DODGE_STAMINA_COST,
            guardTicksRemaining = 0,
            dodgeDirection = direction,
            dodgeTicksRemaining = DODGE_WINDOW_TICKS,
        )
    }

    private fun applyStrike(event: MotionEventEnvelope, staminaCost: Int, baseDamage: Int) {
        if (
            state.requiresReturnToGuard ||
            state.attackCooldownTicks > 0 ||
            state.playerStamina < staminaCost
        ) {
            val repetitionPenalty = if (state.requiresReturnToGuard) REPEAT_STRIKE_STAMINA_PENALTY else 0
            state = state.copy(
                playerStamina = (state.playerStamina - repetitionPenalty).coerceAtLeast(0),
                ignoredStrikeCount = state.ignoredStrikeCount + 1,
            )
            return
        }
        val postureQuality = event.quality
        val qualityBonus = when {
            postureQuality >= 0.85f -> MAX_QUALITY_DAMAGE_BONUS
            postureQuality >= 0.65f -> MID_QUALITY_DAMAGE_BONUS
            else -> 0
        }
        val counterBonus = if (
            state.aiTelegraph != null && state.aiTelegraphTicksRemaining in 1..COUNTER_WINDOW_TICKS
        ) {
            COUNTER_DAMAGE_BONUS
        } else {
            0
        }
        val damage = baseDamage + qualityBonus + counterBonus
        val opponentHealth = (state.opponentHealth - damage).coerceAtLeast(0)
        state = state.copy(
            opponentHealth = opponentHealth,
            playerStamina = state.playerStamina - staminaCost,
            attackCooldownTicks = STRIKE_COOLDOWN_TICKS,
            requiresReturnToGuard = true,
            lastPlayerDamage = damage,
            score = state.score + damage,
        )
        if (opponentHealth == 0) finish(BoxingOutcome.WIN)
    }

    private fun progressAiTelegraph() {
        val attack = state.aiTelegraph
        if (attack == null) {
            if (state.simulationTick % AI_ATTACK_CADENCE_TICKS == 0L) {
                state = state.copy(
                    aiTelegraph = nextAiAttack(state.seed, state.prngState),
                    aiTelegraphTicksRemaining = AI_TELEGRAPH_TICKS,
                    aiAttackOrdinal = state.aiAttackOrdinal + 1,
                    prngState = state.prngState + 1L,
                )
            }
            return
        }
        if (state.aiTelegraphTicksRemaining > 1) {
            state = state.copy(aiTelegraphTicksRemaining = state.aiTelegraphTicksRemaining - 1)
            return
        }
        resolveAiAttack(attack)
    }

    private fun resolveAiAttack(attack: BoxingAiAttack) {
        if (state.mode == GameMode.DUAL) {
            resolveDualAiAttack(attack)
            return
        }
        val dodged = state.dodgeTicksRemaining > 0 && state.dodgeDirection == requiredDodge(attack)
        val guarded = !dodged && state.guardTicksRemaining > 0
        val damage = when {
            dodged -> 0
            guarded -> GUARDED_DAMAGE
            else -> AI_DAMAGE
        }
        val playerHealth = (state.playerHealth - damage).coerceAtLeast(0)
        state = state.copy(
            playerHealth = playerHealth,
            aiTelegraph = null,
            aiTelegraphTicksRemaining = 0,
            lastReceivedDamage = damage,
            blockedAttackCount = state.blockedAttackCount + if (guarded) 1 else 0,
            dodgedAttackCount = state.dodgedAttackCount + if (dodged) 1 else 0,
        )
        if (playerHealth == 0) finish(BoxingOutcome.LOSS)
    }

    private fun resolveDualAiAttack(attack: BoxingAiAttack) {
        state.players.keys.sortedBy { it.ordinal }.forEach { playerId ->
            val player = dualPlayer(playerId)
            val dodged = player.dodgeTicksRemaining > 0 && player.dodgeDirection == requiredDodge(attack)
            val guarded = !dodged && player.guardTicksRemaining > 0
            val damage = when {
                dodged -> 0
                guarded -> GUARDED_DAMAGE
                else -> AI_DAMAGE
            }
            updateDualPlayer(playerId) {
                player.copy(
                    health = (player.health - damage).coerceAtLeast(0),
                    lastReceivedDamage = damage,
                    blockedAttackCount = player.blockedAttackCount + if (guarded) 1 else 0,
                    dodgedAttackCount = player.dodgedAttackCount + if (dodged) 1 else 0,
                )
            }
        }
        state = state.copy(aiTelegraph = null, aiTelegraphTicksRemaining = 0)
        if (state.players.values.all { it.health == 0 }) finish(BoxingOutcome.LOSS)
    }

    private fun decrementWindows() {
        if (state.mode == GameMode.DUAL) {
            state = state.copy(
                players = state.players.mapValues { (_, player) ->
                    player.copy(
                        guardTicksRemaining = (player.guardTicksRemaining - 1).coerceAtLeast(0),
                        dodgeTicksRemaining = (player.dodgeTicksRemaining - 1).coerceAtLeast(0),
                        dodgeDirection = if (player.dodgeTicksRemaining <= 1) null else player.dodgeDirection,
                        attackCooldownTicks = (player.attackCooldownTicks - 1).coerceAtLeast(0),
                    )
                },
            ).withDualP1Projection()
            return
        }
        state = state.copy(
            guardTicksRemaining = (state.guardTicksRemaining - 1).coerceAtLeast(0),
            dodgeTicksRemaining = (state.dodgeTicksRemaining - 1).coerceAtLeast(0),
            dodgeDirection = if (state.dodgeTicksRemaining <= 1) null else state.dodgeDirection,
            attackCooldownTicks = (state.attackCooldownTicks - 1).coerceAtLeast(0),
        )
    }

    private fun finishByDecision() {
        if (state.mode == GameMode.DUAL) {
            val p1 = dualPlayer(PlayerId.P1)
            val p2 = dualPlayer(PlayerId.P2)
            val outcome = when {
                p1.health > p2.health -> BoxingOutcome.WIN
                p1.health < p2.health -> BoxingOutcome.LOSS
                else -> BoxingOutcome.DRAW
            }
            finish(outcome)
            return
        }
        val outcome = when {
            state.playerHealth > state.opponentHealth -> BoxingOutcome.WIN
            state.playerHealth < state.opponentHealth -> BoxingOutcome.LOSS
            else -> BoxingOutcome.DRAW
        }
        finish(outcome)
    }

    private fun finish(outcome: BoxingOutcome) {
        inputQueue.clear()
        state = state.copy(
            status = SessionStatus.COMPLETED,
            pauseReason = null,
            phase = BoxingPhase.RESULT,
            aiTelegraph = null,
            aiTelegraphTicksRemaining = 0,
            outcome = outcome,
        )
    }

    private fun rejected(reason: BoxingInputRejection): BoxingInputResult.Rejected =
        BoxingInputResult.Rejected(reason, state)

    private fun ignored(reason: BoxingInputIgnoreReason): BoxingInputResult.Ignored =
        BoxingInputResult.Ignored(reason, state)

    companion object {
        val PLAYER_ACTIONS = setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.BOXING_GUARD,
            MotionType.DODGE_LEFT,
            MotionType.DODGE_RIGHT,
        )
        val AI_ATTACKS = BoxingAiAttack.entries

        const val SNAPSHOT_SCHEMA_VERSION = 5
        const val CONTENT_REVISION = "boxing-rules-v3-pvp"
        const val PRNG_ALGORITHM_ID = "boxing-ai-ordinal-v1"
        const val PRNG_ALGORITHM_VERSION = 1
        const val FIXED_STEP_NS = 100_000_000L
        const val ROUND_TICKS = 900
        const val MAX_CATCH_UP_TICKS = 5
        const val INPUT_QUEUE_CAPACITY = 32
        const val MAX_HEALTH = 100
        const val MAX_STAMINA = 100
        const val STAMINA_REGEN_PER_TICK = 1
        const val JAB_STAMINA_COST = 8
        const val HOOK_STAMINA_COST = 14
        const val DODGE_STAMINA_COST = 6
        const val REPEAT_STRIKE_STAMINA_PENALTY = 3
        const val JAB_BASE_DAMAGE = 6
        const val HOOK_BASE_DAMAGE = 9
        const val MID_QUALITY_DAMAGE_BONUS = 1
        const val MAX_QUALITY_DAMAGE_BONUS = 3
        const val COUNTER_DAMAGE_BONUS = 2
        const val STRIKE_COOLDOWN_TICKS = 4
        const val GUARD_WINDOW_TICKS = 5
        const val DODGE_WINDOW_TICKS = 3
        const val AI_ATTACK_CADENCE_TICKS = 12L
        const val AI_TELEGRAPH_TICKS = 5
        const val COUNTER_WINDOW_TICKS = 2
        const val AI_DAMAGE = 12
        const val GUARDED_DAMAGE = 3
        const val PVP_GUARDED_DAMAGE = 2

        fun start(
            sessionId: String,
            seed: Long,
            calibrationRevision: Int,
            mode: GameMode = GameMode.SOLO,
        ): BoxingGameSession {
            require(sessionId.length in 1..120) { "sessionId length must be 1..120" }
            require(calibrationRevision >= 0) { "calibrationRevision must be non-negative" }
            return BoxingGameSession(
                BoxingSnapshot(
                    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                    sessionId = sessionId,
                    gameId = GameId.BOXING,
                    mode = mode,
                    contentRevision = CONTENT_REVISION,
                    seed = seed,
                    prngAlgorithmId = PRNG_ALGORITHM_ID,
                    prngAlgorithmVersion = PRNG_ALGORITHM_VERSION,
                    prngState = 0L,
                    calibrationRevision = calibrationRevision,
                    simulationTick = 0L,
                    status = SessionStatus.RUNNING,
                    pauseReason = null,
                    phase = BoxingPhase.ROUND,
                    playerHealth = MAX_HEALTH,
                    opponentHealth = MAX_HEALTH,
                    playerStamina = MAX_STAMINA,
                    score = 0,
                    roundTicksRemaining = ROUND_TICKS,
                    aiTelegraph = null,
                    aiTelegraphTicksRemaining = 0,
                    aiAttackOrdinal = 0,
                    guardTicksRemaining = 0,
                    dodgeDirection = null,
                    dodgeTicksRemaining = 0,
                    attackCooldownTicks = 0,
                    requiresReturnToGuard = false,
                    lastPlayerDamage = 0,
                    lastReceivedDamage = 0,
                    blockedAttackCount = 0,
                    dodgedAttackCount = 0,
                    ignoredStrikeCount = 0,
                    acceptedSequenceWatermark = -1L,
                    acceptedEventTimestampWatermarkNs = -1L,
                    lastAppliedSequence = -1L,
                    outcome = null,
                    players = if (mode == GameMode.DUAL) dualPlayers() else emptyMap(),
                ),
            )
        }

        /** Restores deterministic gameplay state only; camera/input queues are deliberately not persisted. */
        fun restore(checkpoint: BoxingSnapshot): BoxingGameSession {
            validate(checkpoint)
            val restored = if (checkpoint.phase == BoxingPhase.RESULT) {
                checkpoint
            } else {
                checkpoint.copy(
                    status = SessionStatus.PAUSED,
                    pauseReason = PauseReason.APP_BACKGROUND,
                )
            }
            return BoxingGameSession(restored)
        }

        private fun validate(snapshot: BoxingSnapshot) {
            require(snapshot.schemaVersion == SNAPSHOT_SCHEMA_VERSION)
            require(snapshot.sessionId.length in 1..120)
            require(snapshot.gameId == GameId.BOXING)
            require(snapshot.contentRevision == CONTENT_REVISION)
            require(snapshot.prngAlgorithmId == PRNG_ALGORITHM_ID)
            require(snapshot.prngAlgorithmVersion == PRNG_ALGORITHM_VERSION)
            require(snapshot.prngState == snapshot.aiAttackOrdinal.toLong())
            require(snapshot.prngState in 0L..snapshot.simulationTick)
            require(snapshot.calibrationRevision >= 0)
            require(snapshot.simulationTick in 0L..ROUND_TICKS.toLong())
            require(snapshot.playerHealth in 0..MAX_HEALTH)
            require(snapshot.opponentHealth in 0..MAX_HEALTH)
            require(snapshot.playerStamina in 0..MAX_STAMINA)
            require(snapshot.score >= 0)
            require(snapshot.roundTicksRemaining in 0..ROUND_TICKS)
            require(snapshot.aiAttackOrdinal >= 0)
            require(snapshot.guardTicksRemaining in 0..GUARD_WINDOW_TICKS)
            require(snapshot.dodgeTicksRemaining in 0..DODGE_WINDOW_TICKS)
            require(snapshot.attackCooldownTicks in 0..STRIKE_COOLDOWN_TICKS)
            require(snapshot.lastPlayerDamage in 0..HOOK_BASE_DAMAGE + MAX_QUALITY_DAMAGE_BONUS + COUNTER_DAMAGE_BONUS)
            require(snapshot.lastReceivedDamage in 0..AI_DAMAGE)
            require(snapshot.blockedAttackCount >= 0)
            require(snapshot.dodgedAttackCount >= 0)
            require(snapshot.ignoredStrikeCount >= 0)
            require(snapshot.acceptedSequenceWatermark >= -1L)
            require(snapshot.acceptedEventTimestampWatermarkNs >= -1L)
            require(
                (snapshot.acceptedSequenceWatermark == -1L) ==
                    (snapshot.acceptedEventTimestampWatermarkNs == -1L),
            )
            require(snapshot.lastAppliedSequence in -1L..snapshot.acceptedSequenceWatermark)
            require((snapshot.aiTelegraph == null) == (snapshot.aiTelegraphTicksRemaining == 0))
            require(snapshot.aiTelegraphTicksRemaining in 0..AI_TELEGRAPH_TICKS)
            require((snapshot.dodgeDirection == null) == (snapshot.dodgeTicksRemaining == 0))
            require(snapshot.guardTicksRemaining == 0 || snapshot.dodgeTicksRemaining == 0)
            val timelineTotal = snapshot.simulationTick + snapshot.roundTicksRemaining.toLong()
            require(
                timelineTotal == ROUND_TICKS.toLong() ||
                    (snapshot.phase == BoxingPhase.RESULT && timelineTotal == ROUND_TICKS + 1L),
            )

            val expectedPlayers = if (snapshot.mode == GameMode.DUAL) {
                setOf(PlayerId.P1, PlayerId.P2)
            } else {
                emptySet()
            }
            require(snapshot.players.keys == expectedPlayers)
            snapshot.players.forEach { (id, player) ->
                require(player.playerId == id)
                validatePlayer(player)
            }
            if (snapshot.mode == GameMode.DUAL) {
                val p1 = snapshot.players.getValue(PlayerId.P1)
                val p2 = snapshot.players.getValue(PlayerId.P2)
                require(
                    snapshot.acceptedSequenceWatermark ==
                        snapshot.players.values.maxOf { it.acceptedSequenceWatermark },
                )
                require(
                    snapshot.acceptedEventTimestampWatermarkNs ==
                        snapshot.players.values.maxOf { it.acceptedEventTimestampWatermarkNs },
                )
                require(snapshot.playerHealth == p1.health)
                require(snapshot.opponentHealth == p2.health)
                require(snapshot.playerStamina == p1.stamina)
                require(snapshot.score == p1.score)
                require(snapshot.guardTicksRemaining == p1.guardTicksRemaining)
                require(snapshot.dodgeDirection == p1.dodgeDirection)
                require(snapshot.dodgeTicksRemaining == p1.dodgeTicksRemaining)
                require(snapshot.attackCooldownTicks == p1.attackCooldownTicks)
                require(snapshot.requiresReturnToGuard == p1.requiresReturnToGuard)
                require(snapshot.lastPlayerDamage == p1.lastPlayerDamage)
                require(snapshot.lastReceivedDamage == p1.lastReceivedDamage)
                require(snapshot.blockedAttackCount == p1.blockedAttackCount)
                require(snapshot.dodgedAttackCount == p1.dodgedAttackCount)
                require(snapshot.ignoredStrikeCount == p1.ignoredStrikeCount)
                require(snapshot.aiTelegraph == null && snapshot.aiTelegraphTicksRemaining == 0)
                require(snapshot.aiAttackOrdinal == 0 && snapshot.prngState == 0L)
            }

            require((snapshot.phase == BoxingPhase.RESULT) == (snapshot.outcome != null))
            require(
                when (snapshot.status) {
                    SessionStatus.RUNNING -> snapshot.pauseReason == null && snapshot.phase == BoxingPhase.ROUND
                    SessionStatus.PAUSED -> snapshot.pauseReason != null && snapshot.phase == BoxingPhase.ROUND
                    SessionStatus.COMPLETED -> snapshot.pauseReason == null && snapshot.phase == BoxingPhase.RESULT
                    else -> false
                },
            )
            if (snapshot.phase == BoxingPhase.ROUND) {
                require(snapshot.roundTicksRemaining > 0)
                require(snapshot.opponentHealth > 0)
                if (snapshot.mode == GameMode.DUAL) {
                    require(snapshot.players.values.all { it.health > 0 })
                } else {
                    require(snapshot.playerHealth > 0)
                }
            } else {
                require(snapshot.aiTelegraph == null && snapshot.aiTelegraphTicksRemaining == 0)
                require(snapshot.outcome == expectedOutcome(snapshot))
            }
        }

        private fun expectedOutcome(snapshot: BoxingSnapshot): BoxingOutcome {
            if (snapshot.mode == GameMode.DUAL) {
                val p1 = snapshot.players.getValue(PlayerId.P1)
                val p2 = snapshot.players.getValue(PlayerId.P2)
                return when {
                    p1.health == 0 && p2.health == 0 -> BoxingOutcome.DRAW
                    p2.health == 0 -> BoxingOutcome.WIN
                    p1.health == 0 -> BoxingOutcome.LOSS
                    p1.health > p2.health -> BoxingOutcome.WIN
                    p1.health < p2.health -> BoxingOutcome.LOSS
                    else -> BoxingOutcome.DRAW
                }
            }
            if (snapshot.opponentHealth == 0) return BoxingOutcome.WIN
            val playerHealth = if (snapshot.mode == GameMode.DUAL) {
                if (snapshot.players.values.all { it.health == 0 }) return BoxingOutcome.LOSS
                snapshot.players.values.sumOf { it.health } / snapshot.players.size
            } else {
                if (snapshot.playerHealth == 0) return BoxingOutcome.LOSS
                snapshot.playerHealth
            }
            require(snapshot.roundTicksRemaining == 0)
            return when {
                playerHealth > snapshot.opponentHealth -> BoxingOutcome.WIN
                playerHealth < snapshot.opponentHealth -> BoxingOutcome.LOSS
                else -> BoxingOutcome.DRAW
            }
        }

        private fun validatePlayer(player: BoxingPlayerState) {
            require(player.health in 0..MAX_HEALTH)
            require(player.stamina in 0..MAX_STAMINA)
            require(player.score >= 0)
            require(player.guardTicksRemaining in 0..GUARD_WINDOW_TICKS)
            require(player.dodgeTicksRemaining in 0..DODGE_WINDOW_TICKS)
            require(player.attackCooldownTicks in 0..STRIKE_COOLDOWN_TICKS)
            require(player.lastPlayerDamage in 0..HOOK_BASE_DAMAGE + MAX_QUALITY_DAMAGE_BONUS + COUNTER_DAMAGE_BONUS)
            require(player.lastReceivedDamage in 0..AI_DAMAGE)
            require(player.blockedAttackCount >= 0)
            require(player.dodgedAttackCount >= 0)
            require(player.ignoredStrikeCount >= 0)
            require(player.acceptedSequenceWatermark >= -1L)
            require(player.acceptedEventTimestampWatermarkNs >= -1L)
            require(
                (player.acceptedSequenceWatermark == -1L) ==
                    (player.acceptedEventTimestampWatermarkNs == -1L),
            )
            require(player.lastAppliedSequence in -1L..player.acceptedSequenceWatermark)
            require((player.dodgeDirection == null) == (player.dodgeTicksRemaining == 0))
            require(player.guardTicksRemaining == 0 || player.dodgeTicksRemaining == 0)
        }

        private fun dualPlayers(): Map<PlayerId, BoxingPlayerState> =
            listOf(PlayerId.P1, PlayerId.P2).associateWith { playerId ->
                BoxingPlayerState(
                    playerId = playerId,
                    health = MAX_HEALTH,
                    stamina = MAX_STAMINA,
                    score = 0,
                    guardTicksRemaining = 0,
                    dodgeDirection = null,
                    dodgeTicksRemaining = 0,
                    attackCooldownTicks = 0,
                    requiresReturnToGuard = false,
                    lastPlayerDamage = 0,
                    lastReceivedDamage = 0,
                    blockedAttackCount = 0,
                    dodgedAttackCount = 0,
                    ignoredStrikeCount = 0,
                    acceptedSequenceWatermark = -1L,
                    acceptedEventTimestampWatermarkNs = -1L,
                    lastAppliedSequence = -1L,
                )
            }

        private fun nextAiAttack(seed: Long, prngState: Long): BoxingAiAttack {
            val index = ((seed xor prngState) and Long.MAX_VALUE) % AI_ATTACKS.size
            return AI_ATTACKS[index.toInt()]
        }

        private fun requiredDodge(attack: BoxingAiAttack): BoxingDodgeDirection? = when (attack) {
            BoxingAiAttack.STRAIGHT -> BoxingDodgeDirection.LEFT
            BoxingAiAttack.HOOK -> BoxingDodgeDirection.RIGHT
            BoxingAiAttack.BODY -> null
        }

        private fun allowedPlayers(mode: GameMode): Set<PlayerId> =
            if (mode == GameMode.DUAL) setOf(PlayerId.P1, PlayerId.P2) else setOf(PlayerId.P1)
    }
}
