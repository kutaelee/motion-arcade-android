package com.motionarcade.vision.camera

import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrameSink
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.concurrent.Executor

/**
 * Bounded worker-to-main handoff for coordinate-free two-player combat signals.
 *
 * Replacing a pending frame first emits a zero-signal continuity fence and resets the raw-side
 * recognizer. A later frame therefore cannot complete a gesture candidate across a UI stall.
 */
internal class LatestDualPlayerCombatMotionMainDelivery(
    private val mainExecutor: Executor,
    private val deliveryGate: LivePoseObservationDeliveryGate,
    private val productSink: DualPlayerCombatMotionFrameSink,
    private val onFrameReplaced: () -> Unit,
) : DualPlayerCombatMotionFrameSink {
    private val lock = Any()
    private var continuityEdge: DualPlayerCombatMotionFrame? = null
    private var latest: DualPlayerCombatMotionFrame? = null
    private var runnableScheduled = false
    private var fenceDeliveryAcknowledgementRequired = false

    override fun onDualPlayerCombatMotionFrame(frame: DualPlayerCombatMotionFrame) {
        val shouldSchedule = synchronized(lock) {
            if (runnableScheduled) {
                try {
                    onFrameReplaced()
                } catch (_: Exception) {
                    // A reset hook failure cannot retain a recognizer candidate across the gap.
                }
                if (continuityEdge == null) continuityEdge = checkNotNull(latest).asContinuityFence()
                latest = frame
                false
            } else {
                latest = frame
                runnableScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        try {
            mainExecutor.execute(::drainLatest)
        } catch (_: RuntimeException) {
            synchronized(lock) {
                continuityEdge = (continuityEdge ?: latest)?.asContinuityFence()
                latest = null
                runnableScheduled = false
            }
            try {
                onFrameReplaced()
            } catch (_: Exception) {
                // The preserved fence remains fail-closed even if reset reporting fails.
            }
        }
    }

    private fun drainLatest() {
        val frames = synchronized(lock) {
            val edge = continuityEdge
            continuityEdge = null
            val frame = latest
            latest = null
            runnableScheduled = false
            listOfNotNull(edge, frame).distinctBy(DualPlayerCombatMotionFrame::revision)
        }
        if (frames.isEmpty()) return
        try {
            deliveryGate.deliverIfCurrent {
                for (frame in frames) {
                    if (isFenceDeliveryAcknowledgementRequired() && !isSafetyFence(frame)) break
                    try {
                        productSink.onDualPlayerCombatMotionFrame(frame)
                    } catch (_: Exception) {
                        // A failed fence is not proof that the consumer reset its gesture state.
                        // Preserve it and never pass a later usable frame until it is observed.
                        if (isSafetyFence(frame)) {
                            retainFailedFence(frame)
                            break
                        }
                    }
                    if (isSafetyFence(frame)) acknowledgeFenceDelivery()
                }
            }
        } catch (_: RuntimeException) {
            // A delivery-gate failure is not proof that a fence reached the product. Keep the
            // first fence (generated coalescing fence or source unsafe frame) for a later current
            // generation delivery, and intentionally drop any queued usable frame behind it.
            frames.firstOrNull { frame -> isSafetyFence(frame) }?.let(::retainFailedFence)
        }
    }

    private fun isFenceDeliveryAcknowledgementRequired(): Boolean =
        synchronized(lock) { fenceDeliveryAcknowledgementRequired }

    private fun retainFailedFence(frame: DualPlayerCombatMotionFrame) {
        synchronized(lock) {
            continuityEdge = frame
            fenceDeliveryAcknowledgementRequired = true
        }
    }

    private fun acknowledgeFenceDelivery() {
        synchronized(lock) { fenceDeliveryAcknowledgementRequired = false }
    }

    private fun isSafetyFence(frame: DualPlayerCombatMotionFrame): Boolean =
        !frame.usableForDual ||
            frame.continuityBoundary != com.motionarcade.vision.pose.LivePoseContinuityBoundary.CONTIGUOUS
}
