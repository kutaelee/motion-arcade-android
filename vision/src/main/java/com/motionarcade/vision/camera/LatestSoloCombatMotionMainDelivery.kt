package com.motionarcade.vision.camera

import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.motion.SoloCombatMotionFrameSink
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.concurrent.Executor

/** Bounded latest-only worker-to-main delivery with a mandatory reset fence on replacement. */
internal class LatestSoloCombatMotionMainDelivery(
    private val mainExecutor: Executor,
    private val deliveryGate: LivePoseObservationDeliveryGate,
    private val productSink: SoloCombatMotionFrameSink,
    private val onFrameReplaced: () -> Unit,
    private val onDeliveryFailure: () -> Unit = {},
) : SoloCombatMotionFrameSink {
    private val lock = Any()
    private var continuityEdge: SoloCombatMotionFrame? = null
    private var latest: SoloCombatMotionFrame? = null
    private var runnableScheduled = false
    private var fenceDeliveryAcknowledgementRequired = false

    override fun onSoloCombatMotionFrame(frame: SoloCombatMotionFrame) {
        val shouldSchedule = synchronized(lock) {
            if (runnableScheduled) {
                runCatching(onFrameReplaced)
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
            runCatching(onFrameReplaced)
            runCatching(onDeliveryFailure)
        }
    }

    private fun drainLatest() {
        val frames = synchronized(lock) {
            val edge = continuityEdge
            continuityEdge = null
            val frame = latest
            latest = null
            runnableScheduled = false
            listOfNotNull(edge, frame).distinctBy(SoloCombatMotionFrame::revision)
        }
        if (frames.isEmpty()) return
        try {
            deliveryGate.deliverIfCurrent {
                for (frame in frames) {
                    if (fenceRequired() && !frame.isSafetyFence()) break
                    try {
                        productSink.onSoloCombatMotionFrame(frame)
                    } catch (_: Exception) {
                        runCatching(onDeliveryFailure)
                        if (frame.isSafetyFence()) {
                            retainFailedFence(frame)
                            break
                        }
                    }
                    if (frame.isSafetyFence()) acknowledgeFence()
                }
            }
        } catch (_: RuntimeException) {
            runCatching(onDeliveryFailure)
            frames.firstOrNull { frame -> frame.isSafetyFence() }?.let(::retainFailedFence)
        }
    }

    private fun SoloCombatMotionFrame.isSafetyFence(): Boolean =
        !usableForSolo || continuityBoundary != LivePoseContinuityBoundary.CONTIGUOUS

    private fun fenceRequired(): Boolean = synchronized(lock) { fenceDeliveryAcknowledgementRequired }

    private fun retainFailedFence(frame: SoloCombatMotionFrame) {
        synchronized(lock) {
            continuityEdge = frame
            fenceDeliveryAcknowledgementRequired = true
        }
    }

    private fun acknowledgeFence() {
        synchronized(lock) { fenceDeliveryAcknowledgementRequired = false }
    }
}
