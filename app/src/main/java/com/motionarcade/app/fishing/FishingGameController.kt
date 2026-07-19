package com.motionarcade.app.fishing

import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.GestureDefinition
import com.motionarcade.core.motion.MotionGestureEngine
import com.motionarcade.core.motion.MotionSampleResult
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingInputResult
import com.motionarcade.games.fishing.FishingPhase
import com.motionarcade.games.fishing.FishingSnapshot
import kotlin.math.min

internal sealed interface FishingControllerInputResult {
    val snapshot: FishingSnapshot

    data class Accepted(
        val event: MotionEventEnvelope,
        val scheduledTick: Long,
        override val snapshot: FishingSnapshot,
    ) : FishingControllerInputResult

    data class Ignored(
        val detail: String,
        override val snapshot: FishingSnapshot,
    ) : FishingControllerInputResult
}

/**
 * App-level owner that joins touch/motion confirmation to the deterministic fishing runtime.
 * It owns no camera or Compose objects and uses only the monotonic timestamps supplied by callers.
 */
internal class FishingGameController private constructor(
    private val session: FishingGameSession,
    private var motionEngine: MotionGestureEngine,
    initialClockNs: Long,
) {
    private var consumedClockNs = initialClockNs
    private var motionPhase = session.snapshot.phase
    private var emittedSequenceWatermark = session.snapshot.acceptedSequenceWatermark
    private var motionReadyArmed = false

    val snapshot: FishingSnapshot
        @Synchronized get() = session.snapshot

    @Synchronized
    fun advanceTo(nowNs: Long): FishingSnapshot {
        require(nowNs >= 0L) { "nowNs must be non-negative" }
        require(nowNs >= consumedClockNs) { "controller clock must not rewind" }
        val current = session.snapshot
        if (current.paused || current.phase == FishingPhase.RESULT) return current
        val elapsed = nowNs - consumedClockNs
        val availableTicks = elapsed / FishingGameSession.FIXED_STEP_NS
        val ticks = min(availableTicks, FishingGameSession.MAX_CATCH_UP_TICKS.toLong()).toInt()
        if (ticks == 0) return current
        val next = session.advanceTicks(ticks)
        consumedClockNs = Math.addExact(
            consumedClockNs,
            Math.multiplyExact(ticks.toLong(), FishingGameSession.FIXED_STEP_NS),
        )
        return next
    }

    @Synchronized
    fun submitTouch(
        type: MotionType,
        nowNs: Long,
    ): FishingControllerInputResult {
        if (session.snapshot.paused || session.snapshot.phase == FishingPhase.RESULT) {
            return ignored("touch_session_inactive")
        }
        advanceTo(nowNs)
        return when (val result = motionEngine.processTouch(PlayerId.P1, type, nowNs)) {
            is MotionSampleResult.Emitted -> acceptEvent(result.event)
            is MotionSampleResult.Advanced -> ignored("touch_advanced_${result.phase.name}")
            is MotionSampleResult.Rejected -> ignored("touch_rejected_${result.reason.name}")
        }
    }

    @Synchronized
    fun submitMotionFrame(
        samples: List<MotionSignalSample>,
    ): List<FishingControllerInputResult> {
        if (session.snapshot.paused || session.snapshot.phase == FishingPhase.RESULT) {
            return listOf(ignored("motion_session_inactive"))
        }
        val timestampNs = samples.map(MotionSignalSample::timestampNs).distinct().singleOrNull()
            ?: return listOf(ignored("motion_frame_timestamp_mismatch"))
        // Camera analysis is asynchronous: a valid capture timestamp may arrive behind the render
        // clock. The game session owns the bounded-lateness decision, so never rewind this clock.
        if (timestampNs >= consumedClockNs) advanceTo(timestampNs)
        val currentPhase = session.snapshot.phase
        if (currentPhase != motionPhase) {
            check(
                motionEngine.rearmAfterCheckpoint(
                    playerId = PlayerId.P1,
                    acceptedSequenceWatermark = emittedSequenceWatermark,
                    timestampFenceNs = timestampNs,
                ),
            )
            motionPhase = currentPhase
            motionReadyArmed = false
            // A pose feature can remain high across a game-phase boundary. Discard the boundary
            // frame and require a complete neutral interval before admitting the next gesture so
            // history from the previous phase cannot satisfy the new phase's action.
            return listOf(ignored("motion_phase_boundary_fenced"))
        }
        val allowedTypes = allowedMotionTypes(currentPhase)
        val phaseFilteredSamples = samples.map { sample ->
            if (sample.type in allowedTypes) {
                sample
            } else {
                // Preserve confidence so a valid neutral sample can release an old cooldown, but
                // prevent a geometrically overlapping gesture from owning the shared arm group.
                sample.copy(activation = 0f, quality = 0f)
            }
        }
        return motionEngine.processFrame(phaseFilteredSamples).mapNotNull { result ->
            when (result) {
                is MotionSampleResult.Emitted -> {
                    if (
                        result.event.type == MotionType.FISH_READY &&
                        currentPhase == FishingPhase.READY &&
                        !motionReadyArmed
                    ) {
                        emittedSequenceWatermark = maxOf(
                            emittedSequenceWatermark,
                            result.event.sequenceNumber,
                        )
                        motionReadyArmed = true
                        check(motionEngine.resetTransientState(PlayerId.P1))
                        ignored("motion_ready_armed")
                    } else {
                        acceptEvent(result.event)
                    }
                }
                is MotionSampleResult.Advanced -> null
                is MotionSampleResult.Rejected ->
                    ignored("motion_rejected_${result.reason.name}")
            }
        }
    }

    /** Drops transient gesture candidates at a camera continuity boundary without mutating game state. */
    @Synchronized
    fun fenceMotionContinuity(timestampFenceNs: Long): Boolean {
        val fenced = motionEngine.rearmAfterCheckpoint(
            playerId = PlayerId.P1,
            acceptedSequenceWatermark = emittedSequenceWatermark,
            timestampFenceNs = timestampFenceNs,
        )
        if (fenced) motionReadyArmed = false
        return fenced
    }

    @Synchronized
    fun pause(reason: PauseReason): FishingSnapshot = session.pause(reason)

    @Synchronized
    fun checkpoint(): FishingSnapshot = session.checkpoint()

    @Synchronized
    fun recalibrate(config: com.motionarcade.vision.motion.FishingMotionConfig): FishingSnapshot {
        require(config.calibrationRevision > session.snapshot.calibrationRevision)
        val next = session.recalibrate(config.calibrationRevision)
        motionEngine = createMotionEngine(
            sessionId = next.sessionId,
            calibrationRevision = next.calibrationRevision,
            definitions = validatedDefinitions(config.gestureDefinitions),
        )
        check(
            motionEngine.rearmAfterCheckpoint(
                playerId = PlayerId.P1,
                acceptedSequenceWatermark = next.acceptedSequenceWatermark,
                timestampFenceNs = next.acceptedEventTimestampWatermarkNs,
            ),
        )
        emittedSequenceWatermark = next.acceptedSequenceWatermark
        motionReadyArmed = false
        return next
    }

    @Synchronized
    fun resume(nowNs: Long): Boolean {
        require(nowNs >= 0L) { "nowNs must be non-negative" }
        val paused = session.snapshot
        if (!paused.paused || paused.phase == FishingPhase.RESULT) return false
        if (
            !motionEngine.rearmAfterCheckpoint(
                playerId = PlayerId.P1,
                acceptedSequenceWatermark = emittedSequenceWatermark,
                timestampFenceNs = nowNs,
            )
        ) {
            return false
        }
        val resumed = session.resume(nowNs)
        if (resumed) {
            consumedClockNs = nowNs
            motionReadyArmed = false
        }
        return resumed
    }

    private fun acceptEvent(event: MotionEventEnvelope): FishingControllerInputResult {
        emittedSequenceWatermark = maxOf(emittedSequenceWatermark, event.sequenceNumber)
        return when (val accepted = session.accept(event, session.snapshot.eventTimelineEpoch)) {
            is FishingInputResult.Queued -> FishingControllerInputResult.Accepted(
                event = event,
                scheduledTick = accepted.scheduledTick,
                snapshot = accepted.snapshot,
            )
            is FishingInputResult.Ignored -> ignored("game_ignored_${accepted.reason.name}")
            is FishingInputResult.Rejected -> ignored("game_rejected_${accepted.reason.name}")
        }
    }

    private fun ignored(detail: String): FishingControllerInputResult.Ignored =
        FishingControllerInputResult.Ignored(detail, session.snapshot)

    private fun allowedMotionTypes(phase: FishingPhase): Set<MotionType> =
        if (phase == FishingPhase.READY) {
            setOf(if (motionReadyArmed) MotionType.FISH_CAST else MotionType.FISH_READY)
        } else {
            allowedMotionTypesForPhase(phase)
        }

    companion object {
        private val FISHING_MOTION_TYPES = setOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )

        private fun allowedMotionTypesForPhase(phase: FishingPhase): Set<MotionType> = when (phase) {
            FishingPhase.READY -> emptySet()
            FishingPhase.BITE_WAIT -> emptySet()
            FishingPhase.HOOK_WINDOW -> setOf(MotionType.FISH_HOOK)
            FishingPhase.REELING -> setOf(MotionType.FISH_REEL_CYCLE)
            FishingPhase.TENSION -> setOf(
                MotionType.FISH_TENSION_LEFT,
                MotionType.FISH_TENSION_RIGHT,
            )
            FishingPhase.NETTING -> setOf(MotionType.FISH_NET)
            FishingPhase.RESULT -> emptySet()
        }

        fun start(
            sessionId: String,
            seed: Long,
            calibrationRevision: Int,
            originNs: Long,
            gestureDefinitions: Collection<GestureDefinition>,
        ): FishingGameController {
            val definitions = validatedDefinitions(gestureDefinitions)
            return FishingGameController(
                session = FishingGameSession.start(
                    sessionId = sessionId,
                    seed = seed,
                    calibrationRevision = calibrationRevision,
                    eventTimelineOriginNs = originNs,
                ),
                motionEngine = createMotionEngine(
                    sessionId = sessionId,
                    calibrationRevision = calibrationRevision,
                    definitions = definitions,
                ),
                initialClockNs = originNs,
            )
        }

        fun restore(
            checkpoint: FishingSnapshot,
            currentClockNs: Long,
            gestureDefinitions: Collection<GestureDefinition>,
        ): FishingGameController {
            require(currentClockNs >= 0L)
            val restoredSession = FishingGameSession.restore(checkpoint)
            val restoredSnapshot = restoredSession.snapshot
            val definitions = validatedDefinitions(gestureDefinitions)
            val motionEngine = createMotionEngine(
                sessionId = restoredSnapshot.sessionId,
                calibrationRevision = restoredSnapshot.calibrationRevision,
                definitions = definitions,
            )
            check(
                motionEngine.rearmAfterCheckpoint(
                    playerId = PlayerId.P1,
                    acceptedSequenceWatermark = restoredSnapshot.acceptedSequenceWatermark,
                    timestampFenceNs = restoredSnapshot.acceptedEventTimestampWatermarkNs,
                ),
            )
            return FishingGameController(
                session = restoredSession,
                motionEngine = motionEngine,
                initialClockNs = currentClockNs,
            )
        }

        private fun validatedDefinitions(
            definitions: Collection<GestureDefinition>,
        ): List<GestureDefinition> = definitions.map { it.copy() }.also { snapshot ->
            require(snapshot.size == FISHING_MOTION_TYPES.size)
            require(snapshot.map { it.type }.toSet() == FISHING_MOTION_TYPES)
        }

        private fun createMotionEngine(
            sessionId: String,
            calibrationRevision: Int,
            definitions: Collection<GestureDefinition>,
        ): MotionGestureEngine = MotionGestureEngine(
            sessionId = sessionId,
            definitions = definitions,
            activeCalibrationRevision = calibrationRevision,
        )
    }
}

internal fun FishingSnapshot.suggestedTouchType(): MotionType? = when (phase) {
    FishingPhase.READY -> MotionType.FISH_CAST
    FishingPhase.BITE_WAIT -> null
    FishingPhase.HOOK_WINDOW -> MotionType.FISH_HOOK
    FishingPhase.REELING -> MotionType.FISH_REEL_CYCLE
    FishingPhase.TENSION -> MotionType.FISH_TENSION_LEFT
    FishingPhase.NETTING -> MotionType.FISH_NET
    FishingPhase.RESULT -> null
}
