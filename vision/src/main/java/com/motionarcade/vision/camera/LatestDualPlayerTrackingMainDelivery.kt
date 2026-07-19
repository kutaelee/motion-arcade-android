package com.motionarcade.vision.camera

import com.motionarcade.vision.pose.DualPlayerTrackingSink
import com.motionarcade.vision.pose.DualPlayerTrackingSummary
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.concurrent.Executor

/**
 * Latest-only handoff for aggregate dual-player tracking state. Unlike raw pose frames, the
 * pending value is already coordinate-free; delivery still remains generation-fenced and main
 * thread confined for Compose callers. A pause edge is retained ahead of the latest state so a
 * stalled main thread cannot skip the safety stop that preceded a later re-arm or ACTIVE state.
 */
internal class LatestDualPlayerTrackingMainDelivery(
    private val mainExecutor: Executor,
    private val deliveryGate: LivePoseObservationDeliveryGate,
    private val productSink: DualPlayerTrackingSink,
) : DualPlayerTrackingSink {
    private val lock = Any()
    private var pauseEdge: DualPlayerTrackingSummary? = null
    private var latest: DualPlayerTrackingSummary? = null
    private var runnableScheduled = false
    private var pauseDeliveryAcknowledgementRequired = false

    override fun onDualPlayerTrackingSummary(summary: DualPlayerTrackingSummary) {
        val schedule = synchronized(lock) {
            if (summary.pauseRequired && pauseEdge == null) pauseEdge = summary
            latest = summary
            if (runnableScheduled) {
                false
            } else {
                runnableScheduled = true
                true
            }
        }
        if (!schedule) return
        try {
            mainExecutor.execute(::drainLatest)
        } catch (_: RuntimeException) {
            synchronized(lock) {
                runnableScheduled = false
            }
        }
    }

    private fun drainLatest() {
        val summaries = synchronized(lock) {
            val edge = pauseEdge
            pauseEdge = null
            val last = latest.also {
                latest = null
                runnableScheduled = false
            }
            listOfNotNull(edge, last).distinctBy { summary ->
                summary.sessionGeneration to summary.revision to summary.pauseReason
            }
        }
        if (summaries.isEmpty()) return
        try {
            deliveryGate.deliverIfCurrent {
                for (summary in summaries) {
                    if (isPauseDeliveryAcknowledgementRequired() && !summary.pauseRequired) break
                    try {
                        productSink.onDualPlayerTrackingSummary(summary)
                    } catch (_: Exception) {
                        // Product presentation cannot reopen the camera delivery gate.
                        if (summary.pauseRequired) {
                            retainFailedPause(summary)
                            break
                        }
                    }
                    if (summary.pauseRequired) {
                        acknowledgePauseDelivery()
                    }
                }
            }
        } catch (_: RuntimeException) {
            // A gate failure is not proof that a previously extracted pause reached the product.
            // Preserve that stop for the next current-generation update; the latest active value
            // is intentionally discarded until the pause has been acknowledged.
            summaries.firstOrNull(DualPlayerTrackingSummary::pauseRequired)?.let(::retainFailedPause)
        }
    }

    private fun isPauseDeliveryAcknowledgementRequired(): Boolean =
        synchronized(lock) { pauseDeliveryAcknowledgementRequired }

    /**
     * A failed UI callback is not proof that the pause reached the product. Keep the exact
     * first failed edge so the next tracking update retries that same safety stop before any
     * active state can be observed.
     */
    private fun retainFailedPause(summary: DualPlayerTrackingSummary) {
        synchronized(lock) {
            pauseEdge = summary
            pauseDeliveryAcknowledgementRequired = true
        }
    }

    private fun acknowledgePauseDelivery() {
        synchronized(lock) {
            pauseDeliveryAcknowledgementRequired = false
        }
    }
}
