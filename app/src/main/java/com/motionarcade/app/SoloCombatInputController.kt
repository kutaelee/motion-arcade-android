package com.motionarcade.app

import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionGestureEngine
import com.motionarcade.core.motion.MotionSampleResult
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.motion.supportsSoloCombat

/** Consumes only P1's coordinate-free one-person combat frame. */
internal interface SoloCombatMotionConsumer {
    fun onSafeMotionFrame(frame: SoloCombatMotionFrame)

    fun onMotionContinuityFence(timestampNs: Long)
}

internal class SoloCombatInputController(
    private val sessionId: String,
    private val config: DualPlayerCombatMotionConfig,
    acceptedSequenceWatermarks: Map<PlayerId, Long> = emptyMap(),
    acceptedTimestampWatermarksNs: Map<PlayerId, Long> = emptyMap(),
    requireNeutralRearm: Boolean = false,
    private val onRearmStateChanged: (Boolean) -> Unit = {},
    private val onEvent: (MotionEventEnvelope) -> Unit,
) : SoloCombatMotionConsumer {
    private val motionEngine = MotionGestureEngine(
        sessionId = sessionId,
        definitions = config.gestureDefinitions,
        activeCalibrationRevision = config.calibrationRevision,
    )
    private val definitionsByType = config.gestureDefinitions.associateBy { it.type }
    private var lastSequence = -1L
    private var lastTimestampNs = -1L

    init {
        require(config.profile.supportsSoloCombat)
        require(acceptedSequenceWatermarks.keys.all { it == SOLO_PLAYER })
        require(acceptedTimestampWatermarksNs.keys.all { it == SOLO_PLAYER })
        val sequence = acceptedSequenceWatermarks[SOLO_PLAYER] ?: -1L
        val timestamp = acceptedTimestampWatermarksNs[SOLO_PLAYER] ?: -1L
        require(sequence >= -1L && timestamp >= -1L)
        if (requireNeutralRearm || sequence >= 0L || timestamp >= 0L) {
            check(
                motionEngine.rearmAfterCheckpoint(
                    playerId = SOLO_PLAYER,
                    acceptedSequenceWatermark = sequence,
                    timestampFenceNs = timestamp.coerceAtLeast(0L),
                ),
            )
            lastSequence = sequence
            lastTimestampNs = timestamp
        }
        if (requireNeutralRearm) onRearmStateChanged(false)
    }

    @Synchronized
    fun submitTouch(
        playerId: PlayerId,
        type: MotionType,
        timestampNs: Long,
    ) {
        if (playerId != SOLO_PLAYER || type !in config.motionTypes || timestampNs < 0L) return
        lastTimestampNs = maxOf(lastTimestampNs, timestampNs)
        when (val result = motionEngine.processTouch(playerId, type, timestampNs)) {
            is MotionSampleResult.Emitted -> accept(result.event)
            is MotionSampleResult.Advanced,
            is MotionSampleResult.Rejected,
            -> Unit
        }
    }

    @Synchronized
    override fun onSafeMotionFrame(frame: SoloCombatMotionFrame) {
        if (
            !frame.usableForSolo ||
                frame.profile != config.profile ||
                frame.configId != config.configId ||
                frame.calibrationRevision != config.calibrationRevision ||
                frame.samples.any { it.playerId != SOLO_PLAYER }
        ) {
            onMotionContinuityFence(frame.sourceTimestampNs)
            return
        }
        lastTimestampNs = maxOf(lastTimestampNs, frame.sourceTimestampNs)
        motionEngine.processFrame(frame.samples).forEach { result ->
            if (result is MotionSampleResult.Emitted && result.event.playerId == SOLO_PLAYER) {
                accept(result.event)
            }
        }
        val currentFrameNeutral = frame.samples.all { sample ->
            val definition = definitionsByType.getValue(sample.type)
            sample.confidence >= definition.minimumConfidence &&
                sample.activation <= definition.exitThreshold
        }
        onRearmStateChanged(
            currentFrameNeutral && motionEngine.isNeutralRearmComplete(SOLO_PLAYER),
        )
    }

    @Synchronized
    override fun onMotionContinuityFence(timestampNs: Long) {
        if (timestampNs < 0L) return
        motionEngine.rearmAfterCheckpoint(
            playerId = SOLO_PLAYER,
            acceptedSequenceWatermark = lastSequence,
            timestampFenceNs = maxOf(timestampNs, lastTimestampNs.coerceAtLeast(0L)),
        )
        onRearmStateChanged(false)
    }

    private fun accept(event: MotionEventEnvelope) {
        if (event.playerId != SOLO_PLAYER) return
        lastSequence = event.sequenceNumber
        onEvent(event)
    }

    private companion object {
        val SOLO_PLAYER = PlayerId.P1
    }
}
