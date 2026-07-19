package com.motionarcade.app

import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionGestureEngine
import com.motionarcade.core.motion.MotionSampleResult
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame

/**
 * App-level bridge from profile-scoped, coordinate-free camera signals to game semantic events.
 *
 * The controller deliberately owns no camera and never receives landmarks or identities. Both
 * touch fallback and camera frames use the same [MotionGestureEngine], so the game sees one event
 * contract regardless of input source.
 */
internal interface DualPlayerCombatMotionConsumer {
    fun onSafeMotionFrame(frame: DualPlayerCombatMotionFrame)

    fun onMotionContinuityFence(timestampNs: Long)
}

internal class DualPlayerCombatInputController(
    private val sessionId: String,
    private val config: DualPlayerCombatMotionConfig,
    acceptedSequenceWatermarks: Map<PlayerId, Long> = emptyMap(),
    acceptedTimestampWatermarksNs: Map<PlayerId, Long> = emptyMap(),
    requireNeutralRearm: Boolean = false,
    private val onRearmStateChanged: (Boolean) -> Unit = {},
    private val onEvent: (MotionEventEnvelope) -> Unit,
) : DualPlayerCombatMotionConsumer {
    private val motionEngine = MotionGestureEngine(
        sessionId = sessionId,
        definitions = config.gestureDefinitions,
        activeCalibrationRevision = config.calibrationRevision,
    )
    private val definitionsByType = config.gestureDefinitions.associateBy { it.type }
    private val lastSequenceByPlayer = mutableMapOf<PlayerId, Long>()
    private val lastTimestampByPlayer = mutableMapOf<PlayerId, Long>()

    init {
        require(acceptedSequenceWatermarks.keys.all { playerId -> playerId in PLAYERS })
        require(acceptedTimestampWatermarksNs.keys.all { playerId -> playerId in PLAYERS })
        PLAYERS.forEach { playerId ->
            val sequence = acceptedSequenceWatermarks[playerId] ?: -1L
            val timestamp = acceptedTimestampWatermarksNs[playerId] ?: -1L
            require(sequence >= -1L && timestamp >= -1L)
            if (requireNeutralRearm || sequence >= 0L || timestamp >= 0L) {
                check(
                    motionEngine.rearmAfterCheckpoint(
                        playerId = playerId,
                        acceptedSequenceWatermark = sequence,
                        timestampFenceNs = timestamp.coerceAtLeast(0L),
                    ),
                )
                if (sequence >= 0L) lastSequenceByPlayer[playerId] = sequence
                if (timestamp >= 0L) lastTimestampByPlayer[playerId] = timestamp
            }
        }
        if (requireNeutralRearm) onRearmStateChanged(false)
    }

    @Synchronized
    fun submitTouch(
        playerId: PlayerId,
        type: MotionType,
        timestampNs: Long,
    ) {
        if (playerId !in PLAYERS || type !in config.motionTypes || timestampNs < 0L) return
        lastTimestampByPlayer[playerId] = maxOf(lastTimestampByPlayer[playerId] ?: -1L, timestampNs)
        when (val result = motionEngine.processTouch(playerId, type, timestampNs)) {
            is MotionSampleResult.Emitted -> accept(result.event)
            is MotionSampleResult.Advanced,
            is MotionSampleResult.Rejected,
            -> Unit
        }
    }

    @Synchronized
    override fun onSafeMotionFrame(frame: DualPlayerCombatMotionFrame) {
        if (
            !frame.usableForDual ||
                frame.profile != config.profile ||
                frame.configId != config.configId ||
                frame.calibrationRevision != config.calibrationRevision
        ) {
            onMotionContinuityFence(frame.sourceTimestampNs)
            return
        }
        PLAYERS.forEach { playerId ->
            lastTimestampByPlayer[playerId] = maxOf(
                lastTimestampByPlayer[playerId] ?: -1L,
                frame.sourceTimestampNs,
            )
        }
        motionEngine.processFrame(frame.samples).forEach { result ->
            if (result is MotionSampleResult.Emitted) accept(result.event)
        }
        val currentFrameNeutral = frame.samples.all { sample ->
            val definition = definitionsByType.getValue(sample.type)
            sample.confidence >= definition.minimumConfidence &&
                sample.activation <= definition.exitThreshold
        }
        onRearmStateChanged(
            currentFrameNeutral && PLAYERS.all(motionEngine::isNeutralRearmComplete),
        )
    }

    @Synchronized
    override fun onMotionContinuityFence(timestampNs: Long) {
        if (timestampNs < 0L) return
        PLAYERS.forEach { playerId ->
            motionEngine.rearmAfterCheckpoint(
                playerId = playerId,
                acceptedSequenceWatermark = lastSequenceByPlayer[playerId] ?: -1L,
                // Touch and camera callbacks share the engine's monotonic timeline but can arrive
                // in either order. A late safety edge must never lower that engine timestamp.
                timestampFenceNs = maxOf(timestampNs, lastTimestampByPlayer[playerId] ?: 0L),
            )
        }
        onRearmStateChanged(false)
    }

    private fun accept(event: MotionEventEnvelope) {
        lastSequenceByPlayer[event.playerId] = event.sequenceNumber
        onEvent(event)
    }

    private companion object {
        val PLAYERS = listOf(PlayerId.P1, PlayerId.P2)
    }
}
