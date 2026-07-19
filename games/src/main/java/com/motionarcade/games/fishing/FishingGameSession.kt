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
import java.util.LinkedHashSet
import kotlin.math.roundToInt

enum class FishingPhase {
    READY,
    BITE_WAIT,
    HOOK_WINDOW,
    REELING,
    TENSION,
    NETTING,
    RESULT,
}

enum class FishingFish {
    SUNFIN,
    MOON_CARP,
}

enum class FishingOutcome {
    CAUGHT,
    ESCAPED,
}

data class FishingTimelineEpoch(
    val epoch: Long,
    val baseNs: Long,
    val simulationBaseTick: Long,
)

data class FishingSnapshot(
    val schemaVersion: Int,
    val sessionId: String,
    val gameId: GameId,
    val mode: GameMode,
    val contentRevision: String,
    val seed: Long,
    val prngAlgorithmId: String,
    val prngAlgorithmVersion: Int,
    val prngState: Long,
    val eventTimelineEpoch: Long,
    val eventTimelineBaseNs: Long,
    val eventTimelineSimulationBaseTick: Long,
    val eventTimelineLedgerVersion: Int,
    val eventTimelineLedger: List<FishingTimelineEpoch>,
    val calibrationRevision: Int,
    val simulationTick: Long,
    val status: SessionStatus,
    val pauseReason: PauseReason?,
    val phase: FishingPhase,
    val fish: FishingFish?,
    val castEventSequence: Long?,
    val castEventEpoch: Long?,
    val castEventTick: Long?,
    val castEventTimestampNs: Long?,
    val castEventTimelineBaseNs: Long?,
    val castEventTimelineSimulationBaseTick: Long?,
    val castQuality: Float?,
    val hookEventSequence: Long?,
    val hookEventEpoch: Long?,
    val hookEventTick: Long?,
    val hookEventTimestampNs: Long?,
    val hookEventTimelineBaseNs: Long?,
    val hookEventTimelineSimulationBaseTick: Long?,
    val hookQuality: Float?,
    val biteAtTick: Long?,
    val hookDeadlineTick: Long?,
    val recoveryScheduledAtTick: Long?,
    val reelCycles: Int,
    val tension: Int,
    val reelScore: Int,
    val tensionScore: Int,
    val netQuality: Float?,
    val recoveryTokens: Int,
    val failureStreak: Int,
    val regionDifficulty: Int,
    val score: Int,
    val outcome: FishingOutcome?,
    val acceptedSequenceWatermark: Long,
    val acceptedEventTimestampWatermarkNs: Long,
    val lastAppliedSequence: Long,
    val lastAppliedEventEpoch: Long,
    val lastAppliedEventTick: Long?,
    val lastAppliedEventTimestampNs: Long,
    val lastAppliedEventTimelineBaseNs: Long?,
    val lastAppliedEventTimelineSimulationBaseTick: Long?,
    val lastStateChangingSequence: Long,
    val lastStateChangingEventEpoch: Long,
    val lastStateChangingEventTick: Long?,
    val lastStateChangingEventTimestampNs: Long,
    val lastStateChangingEventTimelineBaseNs: Long?,
    val lastStateChangingEventTimelineSimulationBaseTick: Long?,
    val pendingRewardId: String?,
    val committedRewardIds: Set<String>,
) {
    val paused: Boolean
        get() = status == SessionStatus.PAUSED
}

enum class FishingInputRejection {
    SESSION_MISMATCH,
    WRONG_PLAYER,
    TIMELINE_EPOCH_MISMATCH,
    SEQUENCE_REWIND,
    TIMESTAMP_REWIND,
    EVENT_BEFORE_TIMELINE,
    EVENT_TOO_OLD,
    EVENT_TOO_FAR_IN_FUTURE,
    QUEUE_OVERFLOW,
    CONTRACT_REJECTED,
}

enum class FishingIgnoreReason {
    SESSION_PAUSED,
    SESSION_ALREADY_FINISHED,
}

sealed interface FishingInputResult {
    data class Queued(
        val scheduledTick: Long,
        val snapshot: FishingSnapshot,
    ) : FishingInputResult

    data class Ignored(
        val reason: FishingIgnoreReason,
        val snapshot: FishingSnapshot,
    ) : FishingInputResult

    data class Rejected(
        val reason: FishingInputRejection,
        val snapshot: FishingSnapshot,
    ) : FishingInputResult
}

/**
 * Deterministic solo fishing runtime.
 *
 * [accept] validates and queues semantic input without changing game rules. [advanceTicks] is the
 * only rule transition point. The caller tags each event with the timeline epoch observed when it
 * was produced; pause/restore re-arm to a new epoch so stale callbacks cannot cross that boundary.
 * Event observation time is converted to a simulation tick once, and a bounded lateness window
 * keeps inference callback scheduling from changing hook outcomes.
 */
class FishingGameSession private constructor(
    initial: FishingSnapshot,
) {
    private data class QueuedInput(
        val event: MotionEventEnvelope,
        val eventEpoch: Long,
        val eventTick: Long,
    )

    private data class RandomStep(
        val state: Long,
        val value: Long,
    )

    private data class ProgressPoint(
        val phase: FishingPhase,
        val reelCycles: Int,
        val tension: Int,
        val tensionActions: Int,
    )

    private data class CastDerivation(
        val prngState: Long,
        val fish: FishingFish,
        val biteDelayTicks: Int,
    )

    private data class EventProvenance(
        val sequence: Long,
        val epoch: Long,
        val tick: Long,
        val timestampNs: Long,
        val timelineBaseNs: Long,
        val timelineSimulationBaseTick: Long,
    )

    private var state: FishingSnapshot = immutableSnapshot(initial)
    private var lastSafeCheckpoint: FishingSnapshot = immutableSnapshot(initial)
    private var eventGate = MotionEventGate(initial.calibrationRevision)
    private val inputQueue = ArrayDeque<QueuedInput>()
    private var lastAcceptedSequence = initial.acceptedSequenceWatermark
    private var lastAcceptedTimestampNs = initial.acceptedEventTimestampWatermarkNs
    private var pendingPauseReason: PauseReason? = null

    val snapshot: FishingSnapshot
        @Synchronized get() = immutableSnapshot(state)

    @Synchronized
    fun accept(
        event: MotionEventEnvelope,
        eventTimelineEpoch: Long,
    ): FishingInputResult {
        if (event.sessionId != state.sessionId) {
            return rejected(FishingInputRejection.SESSION_MISMATCH)
        }
        if (event.playerId != PlayerId.P1) {
            return rejected(FishingInputRejection.WRONG_PLAYER)
        }
        if (state.phase == FishingPhase.RESULT) {
            return ignored(FishingIgnoreReason.SESSION_ALREADY_FINISHED)
        }
        if (state.paused) {
            return ignored(FishingIgnoreReason.SESSION_PAUSED)
        }
        if (eventTimelineEpoch != state.eventTimelineEpoch) {
            return rejected(FishingInputRejection.TIMELINE_EPOCH_MISMATCH)
        }
        if (event.sequenceNumber <= lastAcceptedSequence) {
            return rejected(FishingInputRejection.SEQUENCE_REWIND)
        }
        if (event.eventTimestampNs < state.eventTimelineBaseNs) {
            return rejected(FishingInputRejection.EVENT_BEFORE_TIMELINE)
        }
        if (event.eventTimestampNs < lastAcceptedTimestampNs) {
            return rejected(FishingInputRejection.TIMESTAMP_REWIND)
        }
        val eventOffsetTick =
            (event.eventTimestampNs - state.eventTimelineBaseNs) / FIXED_STEP_NS
        if (eventOffsetTick > MAX_SIMULATION_TICK - state.eventTimelineSimulationBaseTick) {
            return rejected(FishingInputRejection.EVENT_TOO_FAR_IN_FUTURE)
        }
        val eventTick = state.eventTimelineSimulationBaseTick + eventOffsetTick
        if (state.simulationTick - eventTick > MAX_EVENT_LATENESS_TICKS) {
            return rejected(FishingInputRejection.EVENT_TOO_OLD)
        }
        if (eventTick > safeTickAdd(state.simulationTick, MAX_FUTURE_EVENT_TICKS)) {
            return rejected(FishingInputRejection.EVENT_TOO_FAR_IN_FUTURE)
        }
        if (inputQueue.size >= INPUT_QUEUE_CAPACITY) {
            pendingPauseReason = PauseReason.EVENT_QUEUE_OVERFLOW
            return rejected(FishingInputRejection.QUEUE_OVERFLOW)
        }
        if (eventGate.evaluateAndRecord(event) !is MotionEventGateResult.Accepted) {
            return rejected(FishingInputRejection.CONTRACT_REJECTED)
        }

        inputQueue.addLast(QueuedInput(event, eventTimelineEpoch, eventTick))
        lastAcceptedSequence = event.sequenceNumber
        lastAcceptedTimestampNs = event.eventTimestampNs
        state = state.copy(
            acceptedSequenceWatermark = event.sequenceNumber,
            acceptedEventTimestampWatermarkNs = event.eventTimestampNs,
        )
        updateSafeCheckpoint()
        return FishingInputResult.Queued(eventTick, immutableSnapshot(state))
    }

    @Synchronized
    fun advanceTicks(ticks: Int): FishingSnapshot {
        require(ticks >= 0) { "ticks must be non-negative" }
        require(ticks <= MAX_CATCH_UP_TICKS) {
            "one game-loop call may advance at most $MAX_CATCH_UP_TICKS ticks"
        }
        if (state.paused || state.phase == FishingPhase.RESULT) return immutableSnapshot(state)
        require(ticks.toLong() <= MAX_SIMULATION_TICK - state.simulationTick) {
            "ticks exceed the supported simulation clock range"
        }

        var remaining = ticks
        while (remaining > 0 && !state.paused && state.phase != FishingPhase.RESULT) {
            val requestedPause = pendingPauseReason
            if (requestedPause != null) {
                pendingPauseReason = null
                inputQueue.clear()
                state = state.copy(
                    status = SessionStatus.PAUSED,
                    pauseReason = requestedPause,
                )
                updateSafeCheckpoint()
                break
            }

            remaining -= 1
            state = state.copy(simulationTick = state.simulationTick + 1L)
            openHookWindowIfDue(state.simulationTick)

            while (
                inputQueue.isNotEmpty() &&
                inputQueue.first.eventTick <= state.simulationTick
            ) {
                applyQueued(inputQueue.removeFirst())
                openHookWindowIfDue(state.simulationTick)
            }
            expireHookWindowIfSettled(state.simulationTick)
            if (state.phase != FishingPhase.HOOK_WINDOW) updateSafeCheckpoint()
        }
        return immutableSnapshot(state)
    }

    @Synchronized
    fun pause(reason: PauseReason): FishingSnapshot {
        if (state.phase == FishingPhase.RESULT) return immutableSnapshot(state)
        inputQueue.clear()
        pendingPauseReason = null
        state = state.copy(
            status = SessionStatus.PAUSED,
            pauseReason = reason,
        )
        updateSafeCheckpoint()
        return immutableSnapshot(state)
    }

    @Synchronized
    fun recalibrate(newCalibrationRevision: Int): FishingSnapshot {
        require(newCalibrationRevision > state.calibrationRevision) {
            "calibration revision must increase"
        }
        inputQueue.clear()
        pendingPauseReason = null
        eventGate = MotionEventGate(newCalibrationRevision)
        state = if (state.phase == FishingPhase.RESULT) {
            state.copy(calibrationRevision = newCalibrationRevision)
        } else {
            state.copy(
                calibrationRevision = newCalibrationRevision,
                status = SessionStatus.PAUSED,
                pauseReason = PauseReason.CAMERA_SWITCH,
            )
        }
        updateSafeCheckpoint()
        return immutableSnapshot(state)
    }

    @Synchronized
    fun resume(nowNs: Long): Boolean {
        if (state.status != SessionStatus.PAUSED || state.phase == FishingPhase.RESULT) return false
        require(nowNs >= 0L) { "nowNs must use a non-negative monotonic clock" }
        if (nowNs < state.eventTimelineBaseNs || nowNs < lastAcceptedTimestampNs) return false
        if (
            state.eventTimelineEpoch == Long.MAX_VALUE ||
            state.eventTimelineLedger.size >= TIMELINE_LEDGER_CAPACITY
        ) return false
        val nextEpoch = state.eventTimelineEpoch + 1L
        val nextTimeline = FishingTimelineEpoch(
            epoch = nextEpoch,
            baseNs = nowNs,
            simulationBaseTick = state.simulationTick,
        )
        inputQueue.clear()
        pendingPauseReason = null
        state = state.copy(
            status = SessionStatus.RUNNING,
            pauseReason = null,
            eventTimelineEpoch = nextEpoch,
            eventTimelineBaseNs = nowNs,
            eventTimelineSimulationBaseTick = state.simulationTick,
            eventTimelineLedger = immutableTimelineLedger(
                state.eventTimelineLedger + nextTimeline,
            ),
            acceptedEventTimestampWatermarkNs = nowNs,
        )
        lastAcceptedTimestampNs = nowNs
        updateSafeCheckpoint()
        return true
    }

    @Synchronized
    fun checkpoint(): FishingSnapshot = immutableSnapshot(lastSafeCheckpoint)

    @Synchronized
    fun commitReward(rewardId: String): Boolean {
        if (rewardId != state.pendingRewardId || rewardId in state.committedRewardIds) return false
        state = state.copy(
            pendingRewardId = null,
            committedRewardIds = immutableRewards(state.committedRewardIds + rewardId),
        )
        updateSafeCheckpoint()
        return true
    }

    private fun applyQueued(input: QueuedInput) {
        val event = input.event
        state = state.copy(
            lastAppliedSequence = event.sequenceNumber,
            lastAppliedEventEpoch = input.eventEpoch,
            lastAppliedEventTick = input.eventTick,
            lastAppliedEventTimestampNs = event.eventTimestampNs,
            lastAppliedEventTimelineBaseNs = state.eventTimelineBaseNs,
            lastAppliedEventTimelineSimulationBaseTick =
                state.eventTimelineSimulationBaseTick,
        )
        val changed = when (state.phase) {
            FishingPhase.READY -> applyReady(event, input.eventTick)
            FishingPhase.BITE_WAIT -> false
            FishingPhase.HOOK_WINDOW -> applyHook(event, input.eventTick)
            FishingPhase.REELING -> applyReel(event)
            FishingPhase.TENSION -> applyTension(event)
            FishingPhase.NETTING -> applyNet(event)
            FishingPhase.RESULT -> false
        }
        if (changed) {
            state = state.copy(
                lastStateChangingSequence = event.sequenceNumber,
                lastStateChangingEventEpoch = input.eventEpoch,
                lastStateChangingEventTick = input.eventTick,
                lastStateChangingEventTimestampNs = event.eventTimestampNs,
                lastStateChangingEventTimelineBaseNs = state.eventTimelineBaseNs,
                lastStateChangingEventTimelineSimulationBaseTick =
                    state.eventTimelineSimulationBaseTick,
            )
        }
    }

    private fun applyReady(event: MotionEventEnvelope, eventTick: Long): Boolean {
        if (event.type != MotionType.FISH_CAST) return false
        val cast = deriveCast(
            state.prngState,
            event.quality,
            state.regionDifficulty,
            state.failureStreak,
        )
        state = state.copy(
            prngState = cast.prngState,
            phase = FishingPhase.BITE_WAIT,
            fish = cast.fish,
            castEventSequence = event.sequenceNumber,
            castEventEpoch = state.eventTimelineEpoch,
            castEventTick = eventTick,
            castEventTimestampNs = event.eventTimestampNs,
            castEventTimelineBaseNs = state.eventTimelineBaseNs,
            castEventTimelineSimulationBaseTick = state.eventTimelineSimulationBaseTick,
            castQuality = event.quality,
            biteAtTick = safeTickAdd(eventTick, cast.biteDelayTicks.toLong()),
            score = scorePlus(state.score, qualityPoints(event, CAST_POINTS)),
        )
        return true
    }

    private fun applyHook(event: MotionEventEnvelope, eventTick: Long): Boolean {
        if (event.type != MotionType.FISH_HOOK) return false
        val openedAt = requireNotNull(state.biteAtTick)
        val deadline = requireNotNull(state.hookDeadlineTick)
        if (eventTick !in openedAt..deadline) return false
        val remaining = deadline - eventTick
        val timingBonus = ((remaining * HOOK_TIMING_POINTS) / HOOK_WINDOW_TICKS).toInt()
        state = state.copy(
            phase = FishingPhase.REELING,
            hookEventSequence = event.sequenceNumber,
            hookEventEpoch = state.eventTimelineEpoch,
            hookEventTick = eventTick,
            hookEventTimestampNs = event.eventTimestampNs,
            hookEventTimelineBaseNs = state.eventTimelineBaseNs,
            hookEventTimelineSimulationBaseTick = state.eventTimelineSimulationBaseTick,
            hookQuality = event.quality,
            biteAtTick = null,
            hookDeadlineTick = null,
            score = scorePlus(
                state.score,
                qualityPoints(event, HOOK_POINTS) + timingBonus,
            ),
        )
        return true
    }

    private fun applyReel(event: MotionEventEnvelope): Boolean {
        if (event.type != MotionType.FISH_REEL_CYCLE) return false
        val nextCycles = state.reelCycles + 1
        val nextTension = (state.tension + tensionPerCycle()).coerceAtMost(MAX_TENSION)
        val nextPhase = when {
            nextCycles >= requiredReelCycles() -> FishingPhase.NETTING
            nextTension >= TENSION_GATE -> FishingPhase.TENSION
            else -> FishingPhase.REELING
        }
        state = state.copy(
            phase = nextPhase,
            reelCycles = nextCycles,
            tension = nextTension,
            reelScore = scorePlus(state.reelScore, qualityPoints(event, REEL_POINTS)),
            score = scorePlus(state.score, qualityPoints(event, REEL_POINTS)),
        )
        return true
    }

    private fun applyTension(event: MotionEventEnvelope): Boolean {
        if (
            event.type != MotionType.FISH_TENSION_LEFT &&
            event.type != MotionType.FISH_TENSION_RIGHT
        ) {
            return false
        }
        val nextTension = (state.tension - TENSION_RELIEF).coerceAtLeast(0)
        state = state.copy(
            phase = if (nextTension <= TENSION_SAFE) FishingPhase.REELING else FishingPhase.TENSION,
            tension = nextTension,
            tensionScore = scorePlus(
                state.tensionScore,
                qualityPoints(event, TENSION_POINTS),
            ),
            score = scorePlus(state.score, qualityPoints(event, TENSION_POINTS)),
        )
        return true
    }

    private fun applyNet(event: MotionEventEnvelope): Boolean {
        if (event.type != MotionType.FISH_NET) return false
        state = state.copy(
            netQuality = event.quality,
            score = scorePlus(state.score, qualityPoints(event, NET_POINTS)),
        )
        state = finish(FishingOutcome.CAUGHT)
        return true
    }

    private fun openHookWindowIfDue(upToTick: Long) {
        if (state.phase != FishingPhase.BITE_WAIT) return
        val biteTick = requireNotNull(state.biteAtTick)
        if (biteTick > upToTick) return
        state = state.copy(
            phase = FishingPhase.HOOK_WINDOW,
            hookDeadlineTick = safeTickAdd(biteTick, HOOK_WINDOW_TICKS),
        )
    }

    private fun expireHookWindowIfSettled(upToTick: Long) {
        if (state.phase != FishingPhase.HOOK_WINDOW) return
        val deadline = requireNotNull(state.hookDeadlineTick)
        if (upToTick <= safeTickAdd(deadline, MAX_EVENT_LATENESS_TICKS)) return
        state = if (state.recoveryTokens > 0) {
            state.copy(
                phase = FishingPhase.BITE_WAIT,
                biteAtTick = safeTickAdd(upToTick, RECOVERY_BITE_DELAY_TICKS),
                hookDeadlineTick = null,
                recoveryScheduledAtTick = upToTick,
                recoveryTokens = state.recoveryTokens - 1,
            )
        } else {
            finish(FishingOutcome.ESCAPED)
        }
    }

    private fun finish(outcome: FishingOutcome): FishingSnapshot {
        val rarityBonus = if (outcome == FishingOutcome.CAUGHT && state.fish == FishingFish.MOON_CARP) {
            RARE_BONUS
        } else {
            0
        }
        val rewardId = if (outcome == FishingOutcome.CAUGHT) expectedRewardId(state.sessionId) else null
        return state.copy(
            status = SessionStatus.COMPLETED,
            pauseReason = null,
            phase = FishingPhase.RESULT,
            outcome = outcome,
            score = scorePlus(state.score, rarityBonus),
            biteAtTick = null,
            hookDeadlineTick = null,
            pendingRewardId = rewardId,
        )
    }

    private fun updateSafeCheckpoint() {
        if (state.phase != FishingPhase.HOOK_WINDOW) {
            lastSafeCheckpoint = immutableSnapshot(state)
        }
    }

    private fun requiredReelCycles(): Int = requiredReelCycles(requireNotNull(state.fish))

    private fun tensionPerCycle(): Int = when (state.fish) {
        FishingFish.SUNFIN -> COMMON_TENSION_PER_CYCLE
        FishingFish.MOON_CARP -> RARE_TENSION_PER_CYCLE
        null -> error("Tension requires a selected fish")
    }

    private fun rejected(reason: FishingInputRejection): FishingInputResult.Rejected =
        FishingInputResult.Rejected(reason, immutableSnapshot(state))

    private fun ignored(reason: FishingIgnoreReason): FishingInputResult.Ignored =
        FishingInputResult.Ignored(reason, immutableSnapshot(state))

    companion object {
        const val FIXED_STEP_NS: Long = 16_666_667L
        const val CONTENT_REVISION: String = "fishing-rules-v1"
        const val PRNG_ALGORITHM_ID: String = "splitmix64"
        const val PRNG_ALGORITHM_VERSION: Int = 1
        const val MAX_SCORE: Int = 1_000_000
        const val INPUT_QUEUE_CAPACITY: Int = 64
        const val MAX_EVENT_LATENESS_TICKS: Long = 60L
        const val MAX_FUTURE_EVENT_TICKS: Long = 5L
        const val MAX_CATCH_UP_TICKS: Int = 5
        const val TIMELINE_LEDGER_CAPACITY: Int = 256

        fun start(
            sessionId: String,
            seed: Long,
            calibrationRevision: Int,
            eventTimelineOriginNs: Long,
            failureStreak: Int = 0,
            regionDifficulty: Int = 1,
        ): FishingGameSession {
            require(sessionId.length in 1..120)
            require(calibrationRevision >= 0)
            require(eventTimelineOriginNs >= 0L)
            require(failureStreak in 0..MAX_FAILURE_STREAK)
            require(regionDifficulty in MIN_REGION_DIFFICULTY..MAX_REGION_DIFFICULTY)
            return FishingGameSession(
                FishingSnapshot(
                    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                    sessionId = sessionId,
                    gameId = GameId.FISHING,
                    mode = GameMode.SOLO,
                    contentRevision = CONTENT_REVISION,
                    seed = seed,
                    prngAlgorithmId = PRNG_ALGORITHM_ID,
                    prngAlgorithmVersion = PRNG_ALGORITHM_VERSION,
                    prngState = seed,
                    eventTimelineEpoch = 0L,
                    eventTimelineBaseNs = eventTimelineOriginNs,
                    eventTimelineSimulationBaseTick = 0L,
                    eventTimelineLedgerVersion = TIMELINE_LEDGER_VERSION,
                    eventTimelineLedger = immutableTimelineLedger(
                        listOf(
                            FishingTimelineEpoch(
                                epoch = 0L,
                                baseNs = eventTimelineOriginNs,
                                simulationBaseTick = 0L,
                            ),
                        ),
                    ),
                    calibrationRevision = calibrationRevision,
                    simulationTick = 0L,
                    status = SessionStatus.RUNNING,
                    pauseReason = null,
                    phase = FishingPhase.READY,
                    fish = null,
                    castEventSequence = null,
                    castEventEpoch = null,
                    castEventTick = null,
                    castEventTimestampNs = null,
                    castEventTimelineBaseNs = null,
                    castEventTimelineSimulationBaseTick = null,
                    castQuality = null,
                    hookEventSequence = null,
                    hookEventEpoch = null,
                    hookEventTick = null,
                    hookEventTimestampNs = null,
                    hookEventTimelineBaseNs = null,
                    hookEventTimelineSimulationBaseTick = null,
                    hookQuality = null,
                    biteAtTick = null,
                    hookDeadlineTick = null,
                    recoveryScheduledAtTick = null,
                    reelCycles = 0,
                    tension = 0,
                    reelScore = 0,
                    tensionScore = 0,
                    netQuality = null,
                    recoveryTokens = 1,
                    failureStreak = failureStreak,
                    regionDifficulty = regionDifficulty,
                    score = 0,
                    outcome = null,
                    acceptedSequenceWatermark = -1L,
                    acceptedEventTimestampWatermarkNs = eventTimelineOriginNs,
                    lastAppliedSequence = -1L,
                    lastAppliedEventEpoch = 0L,
                    lastAppliedEventTick = null,
                    lastAppliedEventTimestampNs = 0L,
                    lastAppliedEventTimelineBaseNs = null,
                    lastAppliedEventTimelineSimulationBaseTick = null,
                    lastStateChangingSequence = -1L,
                    lastStateChangingEventEpoch = 0L,
                    lastStateChangingEventTick = null,
                    lastStateChangingEventTimestampNs = 0L,
                    lastStateChangingEventTimelineBaseNs = null,
                    lastStateChangingEventTimelineSimulationBaseTick = null,
                    pendingRewardId = null,
                    committedRewardIds = immutableRewards(emptySet()),
                ),
            )
        }

        fun restore(checkpoint: FishingSnapshot): FishingGameSession {
            val normalized = checkpoint.copy(
                committedRewardIds = immutableRewards(checkpoint.committedRewardIds),
                eventTimelineLedger = immutableTimelineLedger(checkpoint.eventTimelineLedger),
            )
            validateCheckpoint(normalized)
            val restored = if (normalized.phase == FishingPhase.RESULT) {
                normalized
            } else {
                normalized.copy(
                    status = SessionStatus.PAUSED,
                    pauseReason = PauseReason.APP_BACKGROUND,
                )
            }
            return FishingGameSession(restored)
        }

        private fun validateCheckpoint(checkpoint: FishingSnapshot) {
            require(checkpoint.schemaVersion == SNAPSHOT_SCHEMA_VERSION)
            require(checkpoint.sessionId.length in 1..120)
            require(checkpoint.gameId == GameId.FISHING)
            require(checkpoint.mode == GameMode.SOLO)
            require(checkpoint.contentRevision == CONTENT_REVISION)
            require(checkpoint.prngAlgorithmId == PRNG_ALGORITHM_ID)
            require(checkpoint.prngAlgorithmVersion == PRNG_ALGORITHM_VERSION)
            require(checkpoint.eventTimelineEpoch >= 0L)
            require(checkpoint.eventTimelineBaseNs >= 0L)
            require(
                checkpoint.eventTimelineSimulationBaseTick in
                    0L..checkpoint.simulationTick,
            )
            validateTimelineLedger(checkpoint)
            require(checkpoint.calibrationRevision >= 0)
            require(checkpoint.simulationTick in 0L..MAX_SIMULATION_TICK)
            require(checkpoint.phase != FishingPhase.HOOK_WINDOW) {
                "Hook windows restore from the preceding safe checkpoint"
            }
            validateInputProvenance(checkpoint)
            require(checkpoint.score in 0..MAX_SCORE)
            require(checkpoint.reelCycles >= 0)
            require(checkpoint.tension in 0..MAX_TENSION)
            require(checkpoint.reelScore >= 0)
            require(checkpoint.tensionScore >= 0)
            require(checkpoint.recoveryTokens in 0..1)
            require(checkpoint.failureStreak in 0..MAX_FAILURE_STREAK)
            require(checkpoint.regionDifficulty in MIN_REGION_DIFFICULTY..MAX_REGION_DIFFICULTY)
            require(
                checkpoint.recoveryScheduledAtTick == null ||
                    checkpoint.recoveryScheduledAtTick in 0L..MAX_SIMULATION_TICK,
            )
            require(
                checkpoint.castEventTick == null ||
                    checkpoint.castEventTick in 0L..MAX_SIMULATION_TICK,
            )
            require(
                checkpoint.castQuality == null ||
                    (checkpoint.castQuality.isFinite() && checkpoint.castQuality in 0f..1f),
            )
            require(
                checkpoint.hookQuality == null ||
                    (checkpoint.hookQuality.isFinite() && checkpoint.hookQuality in 0f..1f),
            )
            require(
                checkpoint.netQuality == null ||
                    (checkpoint.netQuality.isFinite() && checkpoint.netQuality in 0f..1f),
            )
            require(checkpoint.biteAtTick == null || checkpoint.biteAtTick in 0L..MAX_SIMULATION_TICK)
            require(checkpoint.hookDeadlineTick == null)
            val expectedReward = expectedRewardId(checkpoint.sessionId)
            require(checkpoint.committedRewardIds.all { it == expectedReward })
            if (checkpoint.pendingRewardId != null) {
                require(checkpoint.pendingRewardId !in checkpoint.committedRewardIds)
            }
            require(
                when (checkpoint.status) {
                    SessionStatus.RUNNING -> checkpoint.pauseReason == null &&
                        checkpoint.phase != FishingPhase.RESULT
                    SessionStatus.PAUSED -> checkpoint.pauseReason != null &&
                        checkpoint.phase != FishingPhase.RESULT
                    SessionStatus.COMPLETED -> checkpoint.pauseReason == null &&
                        checkpoint.phase == FishingPhase.RESULT
                    SessionStatus.COUNTDOWN,
                    SessionStatus.ABORTED,
                    -> false
                },
            )

            if (checkpoint.phase == FishingPhase.READY) {
                require(
                    checkpoint.fish == null &&
                        checkpoint.castEventSequence == null &&
                        checkpoint.castEventEpoch == null &&
                        checkpoint.castEventTick == null &&
                        checkpoint.castEventTimestampNs == null &&
                        checkpoint.castEventTimelineBaseNs == null &&
                        checkpoint.castEventTimelineSimulationBaseTick == null &&
                        checkpoint.castQuality == null &&
                        checkpoint.hookEventSequence == null &&
                        checkpoint.hookEventEpoch == null &&
                        checkpoint.hookEventTick == null &&
                        checkpoint.hookEventTimestampNs == null &&
                        checkpoint.hookEventTimelineBaseNs == null &&
                        checkpoint.hookEventTimelineSimulationBaseTick == null &&
                        checkpoint.hookQuality == null &&
                        checkpoint.biteAtTick == null &&
                        checkpoint.recoveryScheduledAtTick == null &&
                        checkpoint.reelCycles == 0 &&
                        checkpoint.tension == 0 &&
                        checkpoint.reelScore == 0 &&
                        checkpoint.tensionScore == 0 &&
                        checkpoint.netQuality == null &&
                        checkpoint.recoveryTokens == 1 &&
                        checkpoint.score == 0 &&
                        checkpoint.prngState == checkpoint.seed &&
                        checkpoint.outcome == null &&
                        checkpoint.lastStateChangingSequence == -1L &&
                        checkpoint.pendingRewardId == null &&
                        checkpoint.committedRewardIds.isEmpty(),
                )
                return
            }

            val fish = requireNotNull(checkpoint.fish)
            val castSequence = requireNotNull(checkpoint.castEventSequence)
            val castEpoch = requireNotNull(checkpoint.castEventEpoch)
            val castTick = requireNotNull(checkpoint.castEventTick)
            val castTimestampNs = requireNotNull(checkpoint.castEventTimestampNs)
            val castTimelineBaseNs = requireNotNull(checkpoint.castEventTimelineBaseNs)
            val castTimelineSimulationBaseTick =
                requireNotNull(checkpoint.castEventTimelineSimulationBaseTick)
            val castQuality = requireNotNull(checkpoint.castQuality)
            require(castTick <= checkpoint.simulationTick)
            require(castSequence >= 0L)
            require(castEpoch in 0L..checkpoint.eventTimelineEpoch)
            val cast = deriveCast(
                checkpoint.seed,
                castQuality,
                checkpoint.regionDifficulty,
                checkpoint.failureStreak,
            )
            require(checkpoint.prngState == cast.prngState)
            require(checkpoint.fish == cast.fish)
            val castScore = qualityPoints(castQuality, CAST_POINTS)
            val initialBiteTick = safeTickAdd(castTick, cast.biteDelayTicks.toLong())
            val expectedRecoverySchedule = safeTickAdd(
                initialBiteTick,
                HOOK_WINDOW_TICKS + MAX_EVENT_LATENESS_TICKS + 1L,
            )
            validateCastProvenance(
                checkpoint,
                castSequence,
                castEpoch,
                castTick,
                castTimestampNs,
                castTimelineBaseNs,
                castTimelineSimulationBaseTick,
            )

            when (checkpoint.phase) {
                FishingPhase.BITE_WAIT -> {
                    val biteTick = requireNotNull(checkpoint.biteAtTick)
                    val expectedBiteTick = if (checkpoint.recoveryTokens == 1) {
                        require(checkpoint.recoveryScheduledAtTick == null)
                        initialBiteTick
                    } else {
                        val recoveryTick = requireNotNull(checkpoint.recoveryScheduledAtTick)
                        require(recoveryTick == expectedRecoverySchedule)
                        require(recoveryTick <= checkpoint.simulationTick)
                        safeTickAdd(recoveryTick, RECOVERY_BITE_DELAY_TICKS)
                    }
                    require(
                        biteTick == expectedBiteTick &&
                            biteTick > checkpoint.simulationTick &&
                            checkpoint.hookEventSequence == null &&
                            checkpoint.hookEventEpoch == null &&
                            checkpoint.hookEventTick == null &&
                            checkpoint.hookEventTimestampNs == null &&
                            checkpoint.hookEventTimelineBaseNs == null &&
                            checkpoint.hookEventTimelineSimulationBaseTick == null &&
                            checkpoint.hookQuality == null &&
                            checkpoint.reelCycles == 0 &&
                            checkpoint.tension == 0 &&
                            checkpoint.reelScore == 0 &&
                            checkpoint.tensionScore == 0 &&
                            checkpoint.netQuality == null &&
                            checkpoint.score == castScore &&
                            checkpoint.outcome == null &&
                            checkpoint.pendingRewardId == null &&
                            checkpoint.committedRewardIds.isEmpty(),
                    )
                }
                FishingPhase.REELING,
                FishingPhase.TENSION,
                FishingPhase.NETTING,
                -> {
                    val progress = requireNotNull(
                        progressPoint(
                            fish,
                            checkpoint.phase,
                            checkpoint.reelCycles,
                            checkpoint.tension,
                        ),
                    )
                    val hookScore = validateHookProvenance(
                        checkpoint,
                        castSequence,
                        castEpoch,
                        castTick,
                        initialBiteTick,
                        expectedRecoverySchedule,
                    )
                    val progressActionCount =
                        progress.reelCycles.toLong() + progress.tensionActions.toLong()
                    validateProgressProvenance(
                        checkpoint,
                        progressActionCount,
                        resultIncludesNet = false,
                    )
                    require(
                        checkpoint.biteAtTick == null &&
                            checkpoint.reelScore in 0..progress.reelCycles * REEL_POINTS &&
                            checkpoint.tensionScore in
                            0..progress.tensionActions * TENSION_POINTS &&
                            checkpoint.netQuality == null &&
                            checkpoint.score == castScore + hookScore +
                            checkpoint.reelScore + checkpoint.tensionScore &&
                            checkpoint.outcome == null &&
                            checkpoint.pendingRewardId == null &&
                            checkpoint.committedRewardIds.isEmpty(),
                    )
                }
                FishingPhase.RESULT -> {
                    require(checkpoint.biteAtTick == null && checkpoint.outcome != null)
                    when (checkpoint.outcome) {
                        FishingOutcome.CAUGHT -> {
                            val progress = requireNotNull(
                                progressPoint(
                                    fish,
                                    FishingPhase.NETTING,
                                    checkpoint.reelCycles,
                                    checkpoint.tension,
                                ),
                            )
                            val hookScore = validateHookProvenance(
                                checkpoint,
                                castSequence,
                                castEpoch,
                                castTick,
                                initialBiteTick,
                                expectedRecoverySchedule,
                            )
                            val progressActionCount =
                                progress.reelCycles.toLong() + progress.tensionActions.toLong()
                            validateProgressProvenance(
                                checkpoint,
                                progressActionCount,
                                resultIncludesNet = true,
                            )
                            val netScore = qualityPoints(
                                requireNotNull(checkpoint.netQuality),
                                NET_POINTS,
                            )
                            val rarityBonus = if (fish == FishingFish.MOON_CARP) RARE_BONUS else 0
                            require(
                                checkpoint.reelScore in
                                    0..progress.reelCycles * REEL_POINTS &&
                                    checkpoint.tensionScore in
                                    0..progress.tensionActions * TENSION_POINTS &&
                                    checkpoint.score == castScore + hookScore +
                                    checkpoint.reelScore + checkpoint.tensionScore +
                                    netScore + rarityBonus &&
                                    (
                                        checkpoint.pendingRewardId == expectedReward ||
                                            (
                                                checkpoint.pendingRewardId == null &&
                                                    expectedReward in checkpoint.committedRewardIds
                                                )
                                ),
                            )
                        }
                        FishingOutcome.ESCAPED -> {
                            val recoveryTick = requireNotNull(checkpoint.recoveryScheduledAtTick)
                            require(
                                recoveryTick == expectedRecoverySchedule &&
                                    checkpoint.simulationTick == safeTickAdd(
                                        safeTickAdd(recoveryTick, RECOVERY_BITE_DELAY_TICKS),
                                        HOOK_WINDOW_TICKS + MAX_EVENT_LATENESS_TICKS + 1L,
                                    ) &&
                                    checkpoint.recoveryTokens == 0 &&
                                    checkpoint.hookEventSequence == null &&
                                    checkpoint.hookEventEpoch == null &&
                                    checkpoint.hookEventTick == null &&
                                    checkpoint.hookEventTimestampNs == null &&
                                    checkpoint.hookEventTimelineBaseNs == null &&
                                    checkpoint.hookEventTimelineSimulationBaseTick == null &&
                                    checkpoint.hookQuality == null &&
                                    checkpoint.reelCycles == 0 &&
                                    checkpoint.tension == 0 &&
                                    checkpoint.reelScore == 0 &&
                                    checkpoint.tensionScore == 0 &&
                                    checkpoint.netQuality == null &&
                                    checkpoint.score == castScore &&
                                    checkpoint.lastStateChangingSequence == castSequence &&
                                    checkpoint.lastStateChangingEventEpoch == castEpoch &&
                                    checkpoint.lastStateChangingEventTick == castTick &&
                                    checkpoint.lastStateChangingEventTimestampNs ==
                                    castTimestampNs &&
                                    checkpoint.pendingRewardId == null &&
                                    checkpoint.committedRewardIds.isEmpty(),
                            )
                        }
                    }
                }
                FishingPhase.READY,
                FishingPhase.HOOK_WINDOW,
                -> error("Phase was handled or rejected above")
            }
        }

        private fun validateInputProvenance(checkpoint: FishingSnapshot) {
            require(checkpoint.acceptedSequenceWatermark >= -1L)
            validateAcceptedTimestampWatermark(checkpoint)
            require(checkpoint.lastAppliedSequence >= -1L)
            require(checkpoint.acceptedSequenceWatermark >= checkpoint.lastAppliedSequence)
            require(checkpoint.lastAppliedEventEpoch in 0L..checkpoint.eventTimelineEpoch)
            require(checkpoint.lastAppliedEventTimestampNs >= 0L)
            val lastApplied = if (checkpoint.lastAppliedSequence == -1L) {
                require(
                    checkpoint.lastAppliedEventTick == null &&
                        checkpoint.lastAppliedEventTimestampNs == 0L &&
                        checkpoint.lastAppliedEventTimelineBaseNs == null &&
                        checkpoint.lastAppliedEventTimelineSimulationBaseTick == null,
                )
                null
            } else {
                eventProvenance(
                    sequence = checkpoint.lastAppliedSequence,
                    epoch = checkpoint.lastAppliedEventEpoch,
                    tick = requireNotNull(checkpoint.lastAppliedEventTick),
                    timestampNs = checkpoint.lastAppliedEventTimestampNs,
                    timelineBaseNs = requireNotNull(
                        checkpoint.lastAppliedEventTimelineBaseNs,
                    ),
                    timelineSimulationBaseTick = requireNotNull(
                        checkpoint.lastAppliedEventTimelineSimulationBaseTick,
                    ),
                ).also { applied ->
                    require(applied.tick in 0L..checkpoint.simulationTick)
                    validateEventEpochMapping(checkpoint, applied)
                    require(
                        checkpoint.acceptedEventTimestampWatermarkNs >=
                            applied.timestampNs,
                    )
                }
            }

            require(checkpoint.lastStateChangingSequence >= -1L)
            require(checkpoint.lastStateChangingEventEpoch in 0L..checkpoint.eventTimelineEpoch)
            require(checkpoint.lastStateChangingEventTimestampNs >= 0L)
            if (checkpoint.lastStateChangingSequence == -1L) {
                require(
                    checkpoint.lastStateChangingEventTick == null &&
                        checkpoint.lastStateChangingEventTimestampNs == 0L &&
                        checkpoint.lastStateChangingEventTimelineBaseNs == null &&
                        checkpoint.lastStateChangingEventTimelineSimulationBaseTick == null,
                )
                return
            }

            val lastStateChanging = eventProvenance(
                sequence = checkpoint.lastStateChangingSequence,
                epoch = checkpoint.lastStateChangingEventEpoch,
                tick = requireNotNull(checkpoint.lastStateChangingEventTick),
                timestampNs = checkpoint.lastStateChangingEventTimestampNs,
                timelineBaseNs = requireNotNull(
                    checkpoint.lastStateChangingEventTimelineBaseNs,
                ),
                timelineSimulationBaseTick = requireNotNull(
                    checkpoint.lastStateChangingEventTimelineSimulationBaseTick,
                ),
            )
            require(lastStateChanging.tick in 0L..checkpoint.simulationTick)
            validateEventEpochMapping(checkpoint, lastStateChanging)
            requireNotNull(lastApplied)
            validateProvenanceOrder(lastStateChanging, lastApplied)
        }

        private fun validateTimelineLedger(checkpoint: FishingSnapshot) {
            require(checkpoint.eventTimelineLedgerVersion == TIMELINE_LEDGER_VERSION)
            val ledger = checkpoint.eventTimelineLedger
            require(ledger.size in 1..TIMELINE_LEDGER_CAPACITY)
            require(checkpoint.eventTimelineEpoch == ledger.lastIndex.toLong())
            ledger.forEachIndexed { index, row ->
                require(row.epoch == index.toLong())
                require(row.baseNs >= 0L)
                require(row.simulationBaseTick in 0L..checkpoint.simulationTick)
                if (index > 0) {
                    val previous = ledger[index - 1]
                    require(row.baseNs >= previous.baseNs)
                    require(row.simulationBaseTick >= previous.simulationBaseTick)
                }
            }
            val current = ledger.last()
            require(current.epoch == checkpoint.eventTimelineEpoch)
            require(current.baseNs == checkpoint.eventTimelineBaseNs)
            require(
                current.simulationBaseTick == checkpoint.eventTimelineSimulationBaseTick,
            )
        }

        private fun validateAcceptedTimestampWatermark(checkpoint: FishingSnapshot) {
            val watermark = checkpoint.acceptedEventTimestampWatermarkNs
            val currentTimeline = checkpoint.eventTimelineLedger.last()
            require(watermark >= currentTimeline.baseNs)
            val offset = (watermark - currentTimeline.baseNs) / FIXED_STEP_NS
            require(offset <= MAX_SIMULATION_TICK - currentTimeline.simulationBaseTick)
            val mappedTick = currentTimeline.simulationBaseTick + offset
            val maximumAcceptedTick = safeTickAdd(
                checkpoint.simulationTick,
                MAX_FUTURE_EVENT_TICKS,
            )
            require(mappedTick <= maximumAcceptedTick)

            listOfNotNull(
                checkpoint.castEventTimestampNs,
                checkpoint.hookEventTimestampNs,
                checkpoint.lastAppliedEventTimestampNs.takeIf {
                    checkpoint.lastAppliedSequence >= 0L
                },
                checkpoint.lastStateChangingEventTimestampNs.takeIf {
                    checkpoint.lastStateChangingSequence >= 0L
                },
            ).forEach { receiptTimestamp ->
                require(watermark >= receiptTimestamp)
            }
        }

        private fun validateCastProvenance(
            checkpoint: FishingSnapshot,
            sequence: Long,
            epoch: Long,
            tick: Long,
            timestampNs: Long,
            timelineBaseNs: Long,
            timelineSimulationBaseTick: Long,
        ) {
            val cast = eventProvenance(
                sequence,
                epoch,
                tick,
                timestampNs,
                timelineBaseNs,
                timelineSimulationBaseTick,
            )
            validateEventEpochMapping(checkpoint, cast)
            validateProvenanceOrder(cast, lastStateChangingProvenance(checkpoint))
        }

        private fun validateHookProvenance(
            checkpoint: FishingSnapshot,
            castSequence: Long,
            castEpoch: Long,
            castTick: Long,
            initialBiteTick: Long,
            expectedRecoverySchedule: Long,
        ): Int {
            val hookSequence = requireNotNull(checkpoint.hookEventSequence)
            val hookEpoch = requireNotNull(checkpoint.hookEventEpoch)
            val hookTick = requireNotNull(checkpoint.hookEventTick)
            val hookTimestampNs = requireNotNull(checkpoint.hookEventTimestampNs)
            val hookTimelineBaseNs = requireNotNull(checkpoint.hookEventTimelineBaseNs)
            val hookTimelineSimulationBaseTick =
                requireNotNull(checkpoint.hookEventTimelineSimulationBaseTick)
            val hookQuality = requireNotNull(checkpoint.hookQuality)
            val cast = eventProvenance(
                castSequence,
                castEpoch,
                castTick,
                requireNotNull(checkpoint.castEventTimestampNs),
                requireNotNull(checkpoint.castEventTimelineBaseNs),
                requireNotNull(checkpoint.castEventTimelineSimulationBaseTick),
            )
            val hook = eventProvenance(
                hookSequence,
                hookEpoch,
                hookTick,
                hookTimestampNs,
                hookTimelineBaseNs,
                hookTimelineSimulationBaseTick,
            )
            require(hookSequence > castSequence)
            validateEventEpochMapping(checkpoint, hook)
            validateProvenanceOrder(cast, hook)
            validateProvenanceOrder(hook, lastStateChangingProvenance(checkpoint))
            val openedAt = if (checkpoint.recoveryTokens == 1) {
                require(checkpoint.recoveryScheduledAtTick == null)
                initialBiteTick
            } else {
                val recoveryTick = requireNotNull(checkpoint.recoveryScheduledAtTick)
                require(recoveryTick == expectedRecoverySchedule)
                safeTickAdd(recoveryTick, RECOVERY_BITE_DELAY_TICKS)
            }
            val deadline = safeTickAdd(openedAt, HOOK_WINDOW_TICKS)
            require(hookTick in openedAt..deadline)
            val timingBonus = ((deadline - hookTick) * HOOK_TIMING_POINTS / HOOK_WINDOW_TICKS).toInt()
            return qualityPoints(hookQuality, HOOK_POINTS) + timingBonus
        }

        private fun validateProgressProvenance(
            checkpoint: FishingSnapshot,
            progressActionCount: Long,
            resultIncludesNet: Boolean,
        ) {
            val hookSequence = requireNotNull(checkpoint.hookEventSequence)
            val hookEpoch = requireNotNull(checkpoint.hookEventEpoch)
            val hookTick = requireNotNull(checkpoint.hookEventTick)
            val hookTimestampNs = requireNotNull(checkpoint.hookEventTimestampNs)
            val netActionCount = if (resultIncludesNet) 1L else 0L
            require(progressActionCount in 0L..Long.MAX_VALUE - netActionCount)
            val expectedActions = progressActionCount + netActionCount
            if (expectedActions == 0L) {
                require(
                    checkpoint.lastStateChangingSequence == hookSequence &&
                        checkpoint.lastStateChangingEventEpoch == hookEpoch &&
                        checkpoint.lastStateChangingEventTick == hookTick &&
                        checkpoint.lastStateChangingEventTimestampNs == hookTimestampNs,
                )
            } else {
                require(
                    checkpoint.lastStateChangingSequence >= hookSequence &&
                        checkpoint.lastStateChangingSequence - hookSequence >= expectedActions &&
                        requireNotNull(checkpoint.lastStateChangingEventTick) >= hookTick,
                )
            }
            if (resultIncludesNet) {
                require(checkpoint.lastAppliedSequence == checkpoint.lastStateChangingSequence)
            }
        }

        private fun eventProvenance(
            sequence: Long,
            epoch: Long,
            tick: Long,
            timestampNs: Long,
            timelineBaseNs: Long,
            timelineSimulationBaseTick: Long,
        ): EventProvenance = EventProvenance(
            sequence = sequence,
            epoch = epoch,
            tick = tick,
            timestampNs = timestampNs,
            timelineBaseNs = timelineBaseNs,
            timelineSimulationBaseTick = timelineSimulationBaseTick,
        )

        private fun lastStateChangingProvenance(checkpoint: FishingSnapshot): EventProvenance =
            eventProvenance(
                sequence = checkpoint.lastStateChangingSequence,
                epoch = checkpoint.lastStateChangingEventEpoch,
                tick = requireNotNull(checkpoint.lastStateChangingEventTick),
                timestampNs = checkpoint.lastStateChangingEventTimestampNs,
                timelineBaseNs = requireNotNull(
                    checkpoint.lastStateChangingEventTimelineBaseNs,
                ),
                timelineSimulationBaseTick = requireNotNull(
                    checkpoint.lastStateChangingEventTimelineSimulationBaseTick,
                ),
            )

        private fun validateEventEpochMapping(
            checkpoint: FishingSnapshot,
            event: EventProvenance,
        ) {
            require(event.sequence >= 0L)
            require(event.epoch in 0L..checkpoint.eventTimelineEpoch)
            require(event.timestampNs >= 0L)
            require(event.timelineBaseNs >= 0L)
            require(event.timelineSimulationBaseTick in 0L..event.tick)
            require(event.timestampNs >= event.timelineBaseNs)
            val offset = (event.timestampNs - event.timelineBaseNs) / FIXED_STEP_NS
            require(offset <= MAX_SIMULATION_TICK - event.timelineSimulationBaseTick)
            require(event.tick == event.timelineSimulationBaseTick + offset)
            val exactTimeline = checkpoint.eventTimelineLedger[event.epoch.toInt()]
            require(event.timelineBaseNs == exactTimeline.baseNs)
            require(event.timelineSimulationBaseTick == exactTimeline.simulationBaseTick)
            checkpoint.eventTimelineLedger
                .drop(event.epoch.toInt() + 1)
                .forEach { laterTimeline ->
                    require(laterTimeline.baseNs >= event.timestampNs)
                    require(laterTimeline.simulationBaseTick >= event.tick)
                }
        }

        private fun validateProvenanceOrder(
            previous: EventProvenance,
            current: EventProvenance,
        ) {
            require(current.sequence >= previous.sequence)
            if (current.sequence == previous.sequence) {
                require(current == previous)
                return
            }
            require(current.epoch >= previous.epoch)
            require(current.timestampNs >= previous.timestampNs)
            require(current.tick >= previous.tick)
            if (current.epoch == previous.epoch) {
                require(current.timelineBaseNs == previous.timelineBaseNs)
                require(
                    current.timelineSimulationBaseTick ==
                        previous.timelineSimulationBaseTick,
                )
            } else {
                require(current.timelineBaseNs >= previous.timestampNs)
                require(current.timelineSimulationBaseTick >= previous.tick)
            }
        }

        private fun nextRandom(currentState: Long): RandomStep {
            val nextState = currentState + SPLITMIX_GAMMA
            var value = nextState
            value = (value xor (value ushr 30)) * SPLITMIX_MIX_1
            value = (value xor (value ushr 27)) * SPLITMIX_MIX_2
            return RandomStep(nextState, value xor (value ushr 31))
        }

        private fun deriveCast(
            initialPrngState: Long,
            quality: Float,
            regionDifficulty: Int,
            failureStreak: Int,
        ): CastDerivation {
            val fishDraw = nextRandom(initialPrngState)
            val delayDraw = nextRandom(fishDraw.state)
            val fish = if (
                bounded(fishDraw.value, 100) <
                rareChance(quality, regionDifficulty, failureStreak)
            ) {
                FishingFish.MOON_CARP
            } else {
                FishingFish.SUNFIN
            }
            return CastDerivation(
                prngState = delayDraw.state,
                fish = fish,
                biteDelayTicks = MIN_BITE_DELAY_TICKS + bounded(
                    delayDraw.value,
                    BITE_DELAY_RANGE_TICKS,
                ),
            )
        }

        private fun rareChance(
            quality: Float,
            regionDifficulty: Int,
            failureStreak: Int,
        ): Int = (
            BASE_RARE_PERCENT +
                (quality * TECHNIQUE_RARE_PERCENT).roundToInt() +
                regionDifficulty * DIFFICULTY_RARE_PERCENT +
                failureStreak.coerceAtMost(MAX_FAILURE_STREAK_BONUS) *
                FAILURE_STREAK_RARE_PERCENT
            ).coerceIn(MIN_RARE_PERCENT, MAX_RARE_PERCENT)

        private fun progressPoint(
            fish: FishingFish,
            phase: FishingPhase,
            reelCycles: Int,
            tension: Int,
        ): ProgressPoint? = progressPath(fish).firstOrNull {
            it.phase == phase && it.reelCycles == reelCycles && it.tension == tension
        }

        private fun progressPath(fish: FishingFish): List<ProgressPoint> {
            val result = mutableListOf(
                ProgressPoint(
                    phase = FishingPhase.REELING,
                    reelCycles = 0,
                    tension = 0,
                    tensionActions = 0,
                ),
            )
            while (result.last().phase != FishingPhase.NETTING) {
                val current = result.last()
                val next = when (current.phase) {
                    FishingPhase.REELING -> {
                        val cycles = current.reelCycles + 1
                        val tensionPerCycle = when (fish) {
                            FishingFish.SUNFIN -> COMMON_TENSION_PER_CYCLE
                            FishingFish.MOON_CARP -> RARE_TENSION_PER_CYCLE
                        }
                        val nextTension =
                            (current.tension + tensionPerCycle).coerceAtMost(MAX_TENSION)
                        val nextPhase = when {
                            cycles >= requiredReelCycles(fish) -> FishingPhase.NETTING
                            nextTension >= TENSION_GATE -> FishingPhase.TENSION
                            else -> FishingPhase.REELING
                        }
                        current.copy(
                            phase = nextPhase,
                            reelCycles = cycles,
                            tension = nextTension,
                        )
                    }
                    FishingPhase.TENSION -> {
                        val nextTension = (current.tension - TENSION_RELIEF).coerceAtLeast(0)
                        current.copy(
                            phase = if (nextTension <= TENSION_SAFE) {
                                FishingPhase.REELING
                            } else {
                                FishingPhase.TENSION
                            },
                            tension = nextTension,
                            tensionActions = current.tensionActions + 1,
                        )
                    }
                    else -> error("Unexpected deterministic fishing progress phase")
                }
                result += next
            }
            return result
        }

        private fun bounded(value: Long, bound: Int): Int = ((value ushr 1) % bound).toInt()

        private fun qualityPoints(event: MotionEventEnvelope, maximum: Int): Int =
            qualityPoints(event.quality, maximum)

        private fun qualityPoints(quality: Float, maximum: Int): Int =
            (quality * maximum).roundToInt().coerceIn(0, maximum)

        private fun scorePlus(current: Int, delta: Int): Int =
            (current.toLong() + delta.toLong()).coerceAtMost(MAX_SCORE.toLong()).toInt()

        private fun safeTickAdd(base: Long, delta: Long): Long {
            require(base in 0L..MAX_SIMULATION_TICK)
            require(delta >= 0L)
            return if (delta > MAX_SIMULATION_TICK - base) {
                MAX_SIMULATION_TICK
            } else {
                base + delta
            }
        }

        private fun expectedRewardId(sessionId: String): String =
            "$sessionId/fishing/catch-0"

        private fun requiredReelCycles(fish: FishingFish): Int = when (fish) {
            FishingFish.SUNFIN -> COMMON_REEL_CYCLES
            FishingFish.MOON_CARP -> RARE_REEL_CYCLES
        }

        private fun immutableSnapshot(value: FishingSnapshot): FishingSnapshot =
            value.copy(
                committedRewardIds = immutableRewards(value.committedRewardIds),
                eventTimelineLedger = immutableTimelineLedger(value.eventTimelineLedger),
            )

        private fun immutableRewards(values: Collection<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(values))

        private fun immutableTimelineLedger(
            values: Collection<FishingTimelineEpoch>,
        ): List<FishingTimelineEpoch> = Collections.unmodifiableList(
            values.map { requireNotNull(it) },
        )

        private const val SNAPSHOT_SCHEMA_VERSION = 3
        private const val TIMELINE_LEDGER_VERSION = 1
        private const val MAX_SIMULATION_TICK = Long.MAX_VALUE - 10_000L
        private const val MAX_FAILURE_STREAK = 1_000
        private const val MIN_REGION_DIFFICULTY = 0
        private const val MAX_REGION_DIFFICULTY = 10
        private const val BASE_RARE_PERCENT = 10
        private const val TECHNIQUE_RARE_PERCENT = 10
        private const val DIFFICULTY_RARE_PERCENT = 2
        private const val FAILURE_STREAK_RARE_PERCENT = 4
        private const val MAX_FAILURE_STREAK_BONUS = 8
        private const val MIN_RARE_PERCENT = 5
        private const val MAX_RARE_PERCENT = 60
        private const val MIN_BITE_DELAY_TICKS = 45
        private const val BITE_DELAY_RANGE_TICKS = 46
        private const val HOOK_WINDOW_TICKS = 30L
        private const val RECOVERY_BITE_DELAY_TICKS = 30L
        private const val COMMON_REEL_CYCLES = 5
        private const val RARE_REEL_CYCLES = 7
        private const val COMMON_TENSION_PER_CYCLE = 22
        private const val RARE_TENSION_PER_CYCLE = 28
        private const val MAX_TENSION = 100
        private const val TENSION_GATE = 60
        private const val TENSION_SAFE = 35
        private const val TENSION_RELIEF = 35
        private const val CAST_POINTS = 200
        private const val HOOK_POINTS = 300
        private const val HOOK_TIMING_POINTS = 100L
        private const val REEL_POINTS = 60
        private const val TENSION_POINTS = 40
        private const val NET_POINTS = 300
        private const val RARE_BONUS = 500
        private const val SPLITMIX_GAMMA = -7046029254386353131L
        private const val SPLITMIX_MIX_1 = -4658895280553007687L
        private const val SPLITMIX_MIX_2 = -7723592293110705685L
    }
}
