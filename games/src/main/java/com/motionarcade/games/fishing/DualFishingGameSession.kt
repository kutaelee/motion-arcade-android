package com.motionarcade.games.fishing

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
import kotlin.math.roundToInt

/** Per-player contract watermarks plus a mirrored view of the shared cooperative catch. */
data class DualFishingPlayerState(
    val playerId: PlayerId,
    val phase: FishingPhase,
    val fish: FishingFish?,
    val biteAtTick: Long?,
    val hookDeadlineTick: Long?,
    val assistDeadlineTick: Long?,
    val reelCycles: Int,
    val tension: Int,
    val recoveryTokens: Int,
    val score: Int,
    val outcome: FishingOutcome?,
    val acceptedSequenceWatermark: Long,
    val acceptedEventTimestampWatermarkNs: Long,
    val lastAppliedSequence: Long,
)

data class DualFishingSnapshot(
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
    val rodPlayerId: PlayerId,
    val catches: Int,
    val combo: Int,
    val players: Map<PlayerId, DualFishingPlayerState>,
) {
    val paused: Boolean
        get() = status == SessionStatus.PAUSED

    val teamScore: Int
        get() = players.values.sumOf(DualFishingPlayerState::score)
}

enum class DualFishingInputRejection {
    SESSION_MISMATCH,
    WRONG_PLAYER,
    UNSUPPORTED_ACTION,
    WRONG_ROLE,
    WRONG_PHASE,
    QUEUE_OVERFLOW,
    CONTRACT_REJECTED,
}

enum class DualFishingInputIgnoreReason {
    SESSION_PAUSED,
    SESSION_ALREADY_FINISHED,
    PLAYER_ALREADY_FINISHED,
}

sealed interface DualFishingInputResult {
    data class Queued(val scheduledTick: Long, val snapshot: DualFishingSnapshot) : DualFishingInputResult
    data class Ignored(
        val reason: DualFishingInputIgnoreReason,
        val snapshot: DualFishingSnapshot,
    ) : DualFishingInputResult
    data class Rejected(
        val reason: DualFishingInputRejection,
        val snapshot: DualFishingSnapshot,
    ) : DualFishingInputResult
}

/**
 * Deterministic cooperative fishing session.
 *
 * Camera, tracking, and pose libraries remain outside :games. Both motion and touch submit the
 * same semantic envelopes. P1 starts as the rod role; the partner owns tension relief and the
 * landing net. Both players operate one shared fish, tension meter, and recovery window while
 * their score fields record only their own accepted contributions.
 */
class DualFishingGameSession private constructor(initial: DualFishingSnapshot) {
    private data class QueuedInput(val event: MotionEventEnvelope, val scheduledTick: Long)
    private data class AppliedTransition(
        val player: DualFishingPlayerState,
        val scoreAward: Int,
    )

    private var state = immutableSnapshot(initial)
    private var eventGate = MotionEventGate(initial.calibrationRevision)
    private val inputQueue = ArrayDeque<QueuedInput>()

    val snapshot: DualFishingSnapshot
        @Synchronized get() = immutableSnapshot(state)

    @Synchronized
    fun accept(event: MotionEventEnvelope): DualFishingInputResult {
        if (event.sessionId != state.sessionId) return rejected(DualFishingInputRejection.SESSION_MISMATCH)
        if (event.playerId !in PLAYERS) return rejected(DualFishingInputRejection.WRONG_PLAYER)
        if (event.type !in ACTIONS) return rejected(DualFishingInputRejection.UNSUPPORTED_ACTION)
        if (event.playerId != requiredActor(event.type)) return rejected(DualFishingInputRejection.WRONG_ROLE)
        if (state.status == SessionStatus.COMPLETED) {
            return ignored(DualFishingInputIgnoreReason.SESSION_ALREADY_FINISHED)
        }
        if (state.paused) return ignored(DualFishingInputIgnoreReason.SESSION_PAUSED)
        if (shared.phase == FishingPhase.RESULT) {
            return ignored(DualFishingInputIgnoreReason.PLAYER_ALREADY_FINISHED)
        }
        val player = state.players.getValue(event.playerId)
        if (
            event.calibrationRevision != state.calibrationRevision ||
            event.sequenceNumber <= player.acceptedSequenceWatermark ||
            event.eventTimestampNs < player.acceptedEventTimestampWatermarkNs
        ) {
            return rejected(DualFishingInputRejection.CONTRACT_REJECTED)
        }
        if (!isActionAdmissible(shared.phase, event.type)) {
            return rejected(DualFishingInputRejection.WRONG_PHASE)
        }
        if (inputQueue.size >= INPUT_QUEUE_CAPACITY) {
            applyConfirmedInputsBeforeStop()
            if (state.status != SessionStatus.COMPLETED) {
                state = state.copy(
                    status = SessionStatus.PAUSED,
                    pauseReason = PauseReason.EVENT_QUEUE_OVERFLOW,
                )
            }
            return rejected(DualFishingInputRejection.QUEUE_OVERFLOW)
        }
        if (eventGate.evaluateAndRecord(event) !is MotionEventGateResult.Accepted) {
            return rejected(DualFishingInputRejection.CONTRACT_REJECTED)
        }
        val scheduledTick = state.simulationTick + 1L
        inputQueue.addLast(QueuedInput(event, scheduledTick))
        updatePlayer(event.playerId) { player ->
            player.copy(
                acceptedSequenceWatermark = event.sequenceNumber,
                acceptedEventTimestampWatermarkNs = event.eventTimestampNs,
            )
        }
        return DualFishingInputResult.Queued(scheduledTick, snapshot)
    }

    @Synchronized
    fun advanceTicks(ticks: Int): DualFishingSnapshot {
        require(ticks in 0..MAX_CATCH_UP_TICKS)
        if (state.paused || state.status == SessionStatus.COMPLETED) return snapshot
        require(ticks.toLong() <= Long.MAX_VALUE - state.simulationTick)
        repeat(ticks) {
            if (state.paused || state.status == SessionStatus.COMPLETED) return@repeat
            state = state.copy(simulationTick = state.simulationTick + 1L)
            openHookWindowIfDue()
            takeDueInputs().forEach { input -> apply(input.event) }
            expireWindowsIfDue()
            if (state.players.values.all { player -> player.phase == FishingPhase.RESULT }) {
                state = state.copy(status = SessionStatus.COMPLETED, pauseReason = null)
                inputQueue.clear()
            }
        }
        return snapshot
    }

    @Synchronized
    fun pause(reason: PauseReason): DualFishingSnapshot {
        if (state.status == SessionStatus.COMPLETED) return snapshot
        if (state.paused) return snapshot
        applyConfirmedInputsBeforeStop()
        if (state.status == SessionStatus.COMPLETED) return snapshot
        if (reason == PauseReason.POSE_LOST && shared.tension > 0) {
            setShared(shared.copy(tension = (shared.tension - POSE_LOSS_TENSION_DECAY).coerceAtLeast(0)))
        }
        state = state.copy(status = SessionStatus.PAUSED, pauseReason = reason)
        return snapshot
    }

    /** Moves an active race to a new camera calibration epoch without resetting either lane. */
    @Synchronized
    fun recalibrate(newCalibrationRevision: Int): DualFishingSnapshot {
        require(newCalibrationRevision >= 0) { "newCalibrationRevision must be non-negative" }
        require(newCalibrationRevision > state.calibrationRevision) {
            "newCalibrationRevision must be strictly greater than the active revision"
        }
        inputQueue.clear()
        eventGate = MotionEventGate(newCalibrationRevision)
        state = if (state.status == SessionStatus.COMPLETED) {
            state.copy(calibrationRevision = newCalibrationRevision)
        } else {
            state.copy(
                calibrationRevision = newCalibrationRevision,
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.CAMERA_SWITCH,
            )
        }
        return snapshot
    }

    @Synchronized
    fun resume(): Boolean {
        if (!state.paused) return false
        inputQueue.clear()
        state = state.copy(status = SessionStatus.RUNNING, pauseReason = null)
        return true
    }

    /** Produces a queue-free process checkpoint that must rearm camera safety before resuming. */
    @Synchronized
    fun checkpointForAppBackground(): DualFishingSnapshot {
        return pause(PauseReason.APP_BACKGROUND)
    }

    /** Starts another shared catch. Role switching is legal only at the result boundary. */
    @Synchronized
    fun startNextCatch(switchRoles: Boolean): DualFishingSnapshot {
        require(state.status == SessionStatus.COMPLETED) { "role switching is only allowed after a catch result" }
        val nextRod = if (switchRoles) supportPlayerId else state.rodPlayerId
        val reset = shared.copy(
            phase = FishingPhase.READY,
            fish = null,
            biteAtTick = null,
            hookDeadlineTick = null,
            assistDeadlineTick = null,
            reelCycles = 0,
            tension = 0,
            recoveryTokens = 1,
            outcome = null,
        )
        state = state.copy(
            status = SessionStatus.RUNNING,
            pauseReason = null,
            rodPlayerId = nextRod,
            players = mirrorShared(reset),
        )
        return snapshot
    }

    private fun applyConfirmedInputsBeforeStop() {
        // A Queued result is already confirmed. Commit the next deterministic tick before closing
        // admission so safety loss, overflow, or recreation never silently discards that event.
        if (inputQueue.isNotEmpty() && !state.paused) advanceTicks(1)
        check(inputQueue.isEmpty()) { "safe stop must not retain confirmed inputs" }
    }

    private fun takeDueInputs(): List<QueuedInput> {
        val due = mutableListOf<QueuedInput>()
        while (inputQueue.isNotEmpty() && inputQueue.first.scheduledTick <= state.simulationTick) {
            due += inputQueue.removeFirst()
        }
        return due.sortedWith(
            compareBy<QueuedInput> { input -> if (input.event.playerId == state.rodPlayerId) 0 else 1 }
                .thenBy { input -> input.event.eventTimestampNs }
                .thenBy { input -> input.event.sequenceNumber },
        )
    }

    private fun apply(event: MotionEventEnvelope) {
        val player = shared
        if (player.phase == FishingPhase.RESULT) return
        val applied = player.copy(lastAppliedSequence = event.sequenceNumber)
        val transition = if (
            event.type == MotionType.FISH_TENSION_LEFT || event.type == MotionType.FISH_TENSION_RIGHT
        ) {
            relieveTension(applied, event)
        } else when (applied.phase) {
            FishingPhase.READY -> cast(applied, event)
            FishingPhase.BITE_WAIT -> AppliedTransition(applied, 0)
            FishingPhase.HOOK_WINDOW -> hook(applied, event)
            FishingPhase.REELING -> reel(applied, event)
            FishingPhase.TENSION -> relieveTension(applied, event)
            FishingPhase.NETTING -> net(applied, event)
            FishingPhase.RESULT -> AppliedTransition(applied, 0)
        }
        setShared(transition.player, event.playerId, transition.scoreAward)
    }

    private fun cast(player: DualFishingPlayerState, event: MotionEventEnvelope): AppliedTransition {
        if (event.type != MotionType.FISH_CAST) return AppliedTransition(player, 0)
        val random = mixed(state.prngState xor player.playerId.seedSalt)
        val fish = if (random and 3L == 0L) FishingFish.MOON_CARP else FishingFish.SUNFIN
        val biteDelay = BITE_DELAY_MIN_TICKS + ((random ushr 3) and BITE_DELAY_MASK).toInt()
        return AppliedTransition(
            player = player.copy(
                phase = FishingPhase.BITE_WAIT,
                fish = fish,
                biteAtTick = state.simulationTick + biteDelay,
            ),
            scoreAward = scoreAward(event.quality, CAST_POINTS),
        )
    }

    private fun hook(player: DualFishingPlayerState, event: MotionEventEnvelope): AppliedTransition {
        if (event.type != MotionType.FISH_HOOK) return AppliedTransition(player, 0)
        return AppliedTransition(
            player = player.copy(
                phase = FishingPhase.REELING,
                biteAtTick = null,
                hookDeadlineTick = null,
            ),
            scoreAward = scoreAward(event.quality, HOOK_POINTS),
        )
    }

    private fun reel(player: DualFishingPlayerState, event: MotionEventEnvelope): AppliedTransition {
        if (event.type != MotionType.FISH_REEL_CYCLE) return AppliedTransition(player, 0)
        val cycles = player.reelCycles + 1
        val tension = (player.tension + tensionPerCycle(player)).coerceAtMost(MAX_TENSION)
        val phase = when {
            cycles >= requiredCycles(player) -> FishingPhase.NETTING
            tension >= TENSION_GATE -> FishingPhase.TENSION
            else -> FishingPhase.REELING
        }
        return AppliedTransition(
            player = player.copy(
                phase = phase,
                reelCycles = cycles,
                tension = tension,
                assistDeadlineTick = if (phase == FishingPhase.TENSION || phase == FishingPhase.NETTING) {
                    state.simulationTick + ASSIST_WINDOW_TICKS
                } else null,
            ),
            scoreAward = scoreAward(event.quality, REEL_POINTS),
        )
    }

    private fun relieveTension(
        player: DualFishingPlayerState,
        event: MotionEventEnvelope,
    ): AppliedTransition {
        if (event.type != MotionType.FISH_TENSION_LEFT && event.type != MotionType.FISH_TENSION_RIGHT) {
            return AppliedTransition(player, 0)
        }
        if (player.tension <= 0) return AppliedTransition(player, 0)
        val tension = (player.tension - TENSION_RELIEF).coerceAtLeast(0)
        return AppliedTransition(
            player = player.copy(
                phase = if (tension <= TENSION_SAFE) FishingPhase.REELING else FishingPhase.TENSION,
                tension = tension,
                assistDeadlineTick = if (tension <= TENSION_SAFE) null else state.simulationTick + ASSIST_WINDOW_TICKS,
            ),
            scoreAward = scoreAward(event.quality, TENSION_POINTS),
        )
    }

    private fun net(player: DualFishingPlayerState, event: MotionEventEnvelope): AppliedTransition {
        if (event.type != MotionType.FISH_NET) return AppliedTransition(player, 0)
        val rarity = if (player.fish == FishingFish.MOON_CARP) RARE_BONUS else 0
        return AppliedTransition(
            player = player.copy(
                phase = FishingPhase.RESULT,
                outcome = FishingOutcome.CAUGHT,
                assistDeadlineTick = null,
            ),
            scoreAward = scoreAward(event.quality, NET_POINTS) + rarity,
        )
    }

    private fun openHookWindowIfDue() {
        val player = shared
        val biteAt = player.biteAtTick ?: return
        if (player.phase == FishingPhase.BITE_WAIT && state.simulationTick >= biteAt) {
            setShared(
                player.copy(
                    phase = FishingPhase.HOOK_WINDOW,
                    hookDeadlineTick = biteAt + HOOK_WINDOW_TICKS,
                ),
            )
        }
    }

    private fun expireWindowsIfDue() {
        val player = shared
        val hookExpired = player.phase == FishingPhase.HOOK_WINDOW &&
            player.hookDeadlineTick?.let { state.simulationTick > it } == true
        val assistExpired = player.phase in setOf(FishingPhase.TENSION, FishingPhase.NETTING) &&
            player.assistDeadlineTick?.let { state.simulationTick > it } == true
        if (!hookExpired && !assistExpired) return
        setShared(
            if (player.recoveryTokens > 0) {
                if (hookExpired) {
                    player.copy(
                        phase = FishingPhase.BITE_WAIT,
                        biteAtTick = state.simulationTick + RECOVERY_BITE_DELAY_TICKS,
                        hookDeadlineTick = null,
                        recoveryTokens = player.recoveryTokens - 1,
                    )
                } else {
                    player.copy(
                        phase = FishingPhase.REELING,
                        tension = TENSION_SAFE,
                        assistDeadlineTick = null,
                        recoveryTokens = player.recoveryTokens - 1,
                    )
                }
            } else {
                player.copy(
                    phase = FishingPhase.RESULT,
                    biteAtTick = null,
                    hookDeadlineTick = null,
                    assistDeadlineTick = null,
                    outcome = FishingOutcome.ESCAPED,
                )
            },
        )
    }

    private fun requiredCycles(player: DualFishingPlayerState): Int =
        if (player.fish == FishingFish.MOON_CARP) RARE_REEL_CYCLES else COMMON_REEL_CYCLES

    private fun tensionPerCycle(player: DualFishingPlayerState): Int =
        if (player.fish == FishingFish.MOON_CARP) RARE_TENSION_PER_CYCLE else COMMON_TENSION_PER_CYCLE

    private fun scoreAward(quality: Float, points: Int): Int =
        (quality.coerceIn(0f, 1f) * points).roundToInt().coerceAtMost(MAX_SCORE)

    private val shared: DualFishingPlayerState
        get() = state.players.getValue(state.rodPlayerId)

    private val supportPlayerId: PlayerId
        get() = if (state.rodPlayerId == PlayerId.P1) PlayerId.P2 else PlayerId.P1

    private fun requiredActor(type: MotionType): PlayerId = when (type) {
        MotionType.FISH_TENSION_LEFT, MotionType.FISH_TENSION_RIGHT, MotionType.FISH_NET -> supportPlayerId
        else -> state.rodPlayerId
    }

    private fun isActionAdmissible(phase: FishingPhase, type: MotionType): Boolean = when (type) {
        MotionType.FISH_CAST -> phase == FishingPhase.READY
        MotionType.FISH_HOOK -> phase == FishingPhase.HOOK_WINDOW
        MotionType.FISH_REEL_CYCLE -> phase == FishingPhase.REELING
        MotionType.FISH_TENSION_LEFT, MotionType.FISH_TENSION_RIGHT ->
            phase == FishingPhase.REELING || phase == FishingPhase.TENSION
        MotionType.FISH_NET -> phase == FishingPhase.NETTING
        else -> false
    }

    private fun setShared(
        player: DualFishingPlayerState,
        appliedBy: PlayerId? = null,
        scoreAward: Int = 0,
    ) {
        val completedCatch = player.phase == FishingPhase.RESULT && shared.phase != FishingPhase.RESULT
        val caught = completedCatch && player.outcome == FishingOutcome.CAUGHT
        state = state.copy(
            prngState = if (completedCatch) mixed(state.prngState) else state.prngState,
            catches = if (caught) (state.catches + 1).coerceAtMost(MAX_CATCHES) else state.catches,
            combo = when {
                !completedCatch -> state.combo
                caught -> (state.combo + 1).coerceAtMost(MAX_COMBO)
                else -> 0
            },
            players = mirrorShared(player, appliedBy, scoreAward),
        )
    }

    private fun mirrorShared(
        source: DualFishingPlayerState,
        appliedBy: PlayerId? = null,
        scoreAward: Int = 0,
    ): Map<PlayerId, DualFishingPlayerState> = immutablePlayers(
        state.players.mapValues { (id, existing) ->
            source.copy(
                playerId = id,
                acceptedSequenceWatermark = existing.acceptedSequenceWatermark,
                acceptedEventTimestampWatermarkNs = existing.acceptedEventTimestampWatermarkNs,
                lastAppliedSequence = if (id == appliedBy) source.lastAppliedSequence else existing.lastAppliedSequence,
                score = if (id == appliedBy) {
                    (existing.score + scoreAward).coerceAtMost(MAX_SCORE)
                } else {
                    existing.score
                },
            )
        },
    )

    private fun updatePlayer(
        playerId: PlayerId,
        transform: (DualFishingPlayerState) -> DualFishingPlayerState,
    ) {
        val players = LinkedHashMap(state.players)
        players[playerId] = transform(players.getValue(playerId))
        state = state.copy(players = immutablePlayers(players))
    }

    private fun rejected(reason: DualFishingInputRejection) = DualFishingInputResult.Rejected(reason, snapshot)
    private fun ignored(reason: DualFishingInputIgnoreReason) = DualFishingInputResult.Ignored(reason, snapshot)

    companion object {
        const val FIXED_STEP_NS = 16_666_667L
        const val MAX_CATCH_UP_TICKS = 5
        const val INPUT_QUEUE_CAPACITY = 64
        const val MAX_SCORE = 1_000_000
        const val MAX_CATCHES = 1_000_000
        const val MAX_COMBO = 100_000
        const val MAX_TENSION = 100
        const val CONTENT_REVISION = "dual-fishing-coop-rules-v3"
        const val PRNG_ALGORITHM_ID = "splitmix64-player-salt"
        const val PRNG_ALGORITHM_VERSION = 1
        private const val SCHEMA_VERSION = 1
        private const val BITE_DELAY_MIN_TICKS = 6
        private const val BITE_DELAY_MASK = 3L
        private const val HOOK_WINDOW_TICKS = 24L
        private const val RECOVERY_BITE_DELAY_TICKS = 8L
        private const val ASSIST_WINDOW_TICKS = 90L
        private const val COMMON_REEL_CYCLES = 4
        private const val RARE_REEL_CYCLES = 5
        private const val COMMON_TENSION_PER_CYCLE = 24
        private const val RARE_TENSION_PER_CYCLE = 32
        private const val TENSION_GATE = 48
        private const val TENSION_RELIEF = 36
        private const val TENSION_SAFE = 24
        private const val POSE_LOSS_TENSION_DECAY = 12
        private const val CAST_POINTS = 100
        private const val HOOK_POINTS = 150
        private const val REEL_POINTS = 60
        private const val TENSION_POINTS = 40
        private const val NET_POINTS = 250
        private const val RARE_BONUS = 300
        private val PLAYERS = listOf(PlayerId.P1, PlayerId.P2)
        private val ACTIONS = setOf(
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )

        fun start(
            sessionId: String,
            seed: Long,
            calibrationRevision: Int,
        ): DualFishingGameSession {
            require(sessionId.length in 1..120)
            require(calibrationRevision >= 0)
            return DualFishingGameSession(
                DualFishingSnapshot(
                    schemaVersion = SCHEMA_VERSION,
                    sessionId = sessionId,
                    gameId = GameId.FISHING,
                    mode = GameMode.DUAL,
                    contentRevision = CONTENT_REVISION,
                    seed = seed,
                    prngAlgorithmId = PRNG_ALGORITHM_ID,
                    prngAlgorithmVersion = PRNG_ALGORITHM_VERSION,
                    prngState = seed,
                    calibrationRevision = calibrationRevision,
                    simulationTick = 0L,
                    status = SessionStatus.RUNNING,
                    pauseReason = null,
                    rodPlayerId = PlayerId.P1,
                    catches = 0,
                    combo = 0,
                    players = immutablePlayers(PLAYERS.associateWith(::initialPlayer)),
                ),
            )
        }

        fun restore(checkpoint: DualFishingSnapshot): DualFishingGameSession {
            validate(checkpoint)
            val restored = if (checkpoint.status == SessionStatus.COMPLETED) {
                checkpoint
            } else {
                checkpoint.copy(status = SessionStatus.PAUSED, pauseReason = PauseReason.APP_BACKGROUND)
            }
            return DualFishingGameSession(immutableSnapshot(restored))
        }

        private fun validate(snapshot: DualFishingSnapshot) {
            require(snapshot.schemaVersion == SCHEMA_VERSION)
            require(snapshot.sessionId.length in 1..120)
            require(snapshot.gameId == GameId.FISHING && snapshot.mode == GameMode.DUAL)
            require(snapshot.contentRevision == CONTENT_REVISION)
            require(snapshot.prngAlgorithmId == PRNG_ALGORITHM_ID)
            require(snapshot.prngAlgorithmVersion == PRNG_ALGORITHM_VERSION)
            require(snapshot.calibrationRevision >= 0)
            require(snapshot.simulationTick in 0L..(Long.MAX_VALUE - DEADLINE_HEADROOM_TICKS))
            require(snapshot.players.keys == PLAYERS.toSet())
            require(snapshot.rodPlayerId in PLAYERS)
            require(snapshot.catches in 0..MAX_CATCHES)
            require(snapshot.combo in 0..MAX_COMBO && snapshot.combo <= snapshot.catches)
            val rod = snapshot.players.getValue(snapshot.rodPlayerId)
            snapshot.players.forEach { (id, player) ->
                require(player.playerId == id)
                require(player.reelCycles >= 0)
                require(player.tension in 0..MAX_TENSION)
                require(player.recoveryTokens in 0..1)
                require(player.score in 0..MAX_SCORE)
                require(player.acceptedSequenceWatermark >= -1L)
                require(player.acceptedEventTimestampWatermarkNs >= -1L)
                require(player.lastAppliedSequence in -1L..player.acceptedSequenceWatermark)
                require(
                    (player.acceptedSequenceWatermark == -1L) ==
                        (player.acceptedEventTimestampWatermarkNs == -1L),
                )
                require((player.phase == FishingPhase.RESULT) == (player.outcome != null))
                require((player.phase == FishingPhase.READY) == (player.fish == null))
                require(
                    (player.phase == FishingPhase.BITE_WAIT || player.phase == FishingPhase.HOOK_WINDOW) ==
                        (player.biteAtTick != null),
                )
                require((player.phase == FishingPhase.HOOK_WINDOW) == (player.hookDeadlineTick != null))
                require((player.phase == FishingPhase.TENSION || player.phase == FishingPhase.NETTING) ==
                    (player.assistDeadlineTick != null))
                player.biteAtTick?.let { bite ->
                    require(bite >= 0L)
                    if (player.phase == FishingPhase.BITE_WAIT) require(bite >= snapshot.simulationTick)
                    if (player.phase == FishingPhase.HOOK_WINDOW) require(bite <= snapshot.simulationTick)
                }
                player.hookDeadlineTick?.let { require(it >= snapshot.simulationTick) }
                player.assistDeadlineTick?.let { require(it >= snapshot.simulationTick) }
                if (player.phase == FishingPhase.READY) {
                    require(player.reelCycles == 0 && player.tension == 0)
                }
            }
            snapshot.players.values.forEach { player ->
                require(player.phase == rod.phase)
                require(player.fish == rod.fish)
                require(player.biteAtTick == rod.biteAtTick)
                require(player.hookDeadlineTick == rod.hookDeadlineTick)
                require(player.assistDeadlineTick == rod.assistDeadlineTick)
                require(player.reelCycles == rod.reelCycles)
                require(player.tension == rod.tension)
                require(player.recoveryTokens == rod.recoveryTokens)
                require(player.outcome == rod.outcome)
            }
            require(
                when (snapshot.status) {
                    SessionStatus.RUNNING -> snapshot.pauseReason == null &&
                        snapshot.players.values.any { it.phase != FishingPhase.RESULT }
                    SessionStatus.PAUSED -> snapshot.pauseReason != null &&
                        snapshot.players.values.any { it.phase != FishingPhase.RESULT }
                    SessionStatus.COMPLETED -> snapshot.pauseReason == null &&
                        snapshot.players.values.all { it.phase == FishingPhase.RESULT }
                    else -> false
                },
            )
        }

        private fun initialPlayer(playerId: PlayerId) = DualFishingPlayerState(
            playerId = playerId,
            phase = FishingPhase.READY,
            fish = null,
            biteAtTick = null,
            hookDeadlineTick = null,
            assistDeadlineTick = null,
            reelCycles = 0,
            tension = 0,
            recoveryTokens = 1,
            score = 0,
            outcome = null,
            acceptedSequenceWatermark = -1L,
            acceptedEventTimestampWatermarkNs = -1L,
            lastAppliedSequence = -1L,
        )

        private fun immutableSnapshot(snapshot: DualFishingSnapshot): DualFishingSnapshot =
            snapshot.copy(players = immutablePlayers(snapshot.players))

        private fun immutablePlayers(
            players: Map<PlayerId, DualFishingPlayerState>,
        ): Map<PlayerId, DualFishingPlayerState> = Collections.unmodifiableMap(LinkedHashMap(players))

        private fun mixed(value: Long): Long {
            var z = value + -7046029254386353131L
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            return z xor (z ushr 31)
        }

        private const val DEADLINE_HEADROOM_TICKS = 128L

        private val PlayerId.seedSalt: Long
            get() = when (this) {
                PlayerId.P1 -> 0x13579BDFL
                PlayerId.P2 -> 0x2468ACE0L
                else -> error("dual fishing supports P1/P2 only")
            }
    }
}
