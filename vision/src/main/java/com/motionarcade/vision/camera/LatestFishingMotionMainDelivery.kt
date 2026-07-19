package com.motionarcade.vision.camera

import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.motion.FishingMotionFrameSink
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.concurrent.Executor

internal data class LatestFishingMotionDeliveryCounters(
    val pendingFrameCount: Int,
    val maximumPendingFrameCount: Int,
    val replacedFrameCount: Long,
    val droppedFrameCount: Long,
    val deliveredFrameCount: Long,
    val executorRejectionCount: Long,
    val sinkFailureCount: Long,
)

/**
 * Latest-only worker-to-main handoff. At most one runnable and one coordinate-free frame can wait
 * while the Android main thread is busy; every execution rechecks the camera generation gate.
 */
internal class LatestFishingMotionMainDelivery(
    private val mainExecutor: Executor,
    private val deliveryGate: LivePoseObservationDeliveryGate,
    private val productSink: FishingMotionFrameSink,
    private val onFrameReplaced: () -> Unit = {},
) : FishingMotionFrameSink {
    private val lock = Any()
    private var continuityEdge: FishingMotionFrame? = null
    private var latest: FishingMotionFrame? = null
    private var runnableScheduled = false
    private var maximumPendingFrameCount = 0
    private var replacedFrameCount = 0L
    private var droppedFrameCount = 0L
    private var deliveredFrameCount = 0L
    private var executorRejectionCount = 0L
    private var sinkFailureCount = 0L

    override fun onFishingMotionFrame(frame: FishingMotionFrame) {
        val shouldSchedule = synchronized(lock) {
            if (runnableScheduled) {
                try {
                    onFrameReplaced()
                } catch (_: Exception) {
                    // Replacement remains fail-closed even if a recognizer reset hook fails.
                }
                if (continuityEdge == null) {
                    continuityEdge = checkNotNull(latest).asContinuityFence()
                }
                latest = frame
                replacedFrameCount = replacedFrameCount.incrementSaturated()
                maximumPendingFrameCount = maxOf(maximumPendingFrameCount, pendingCountLocked())
                false
            } else {
                latest = frame
                runnableScheduled = true
                maximumPendingFrameCount = maxOf(maximumPendingFrameCount, pendingCountLocked())
                true
            }
        }
        if (!shouldSchedule) return
        try {
            mainExecutor.execute(::drainLatest)
        } catch (_: RuntimeException) {
            synchronized(lock) {
                val earliest = continuityEdge ?: latest
                continuityEdge = earliest?.asContinuityFence()
                latest = null
                runnableScheduled = false
                executorRejectionCount = executorRejectionCount.incrementSaturated()
            }
            try {
                onFrameReplaced()
            } catch (_: Exception) {
                // The next frame is still forced through a continuity fence.
            }
        }
    }

    fun counters(): LatestFishingMotionDeliveryCounters = synchronized(lock) {
        LatestFishingMotionDeliveryCounters(
            pendingFrameCount = pendingCountLocked(),
            maximumPendingFrameCount = maximumPendingFrameCount,
            replacedFrameCount = replacedFrameCount,
            droppedFrameCount = droppedFrameCount,
            deliveredFrameCount = deliveredFrameCount,
            executorRejectionCount = executorRejectionCount,
            sinkFailureCount = sinkFailureCount,
        )
    }

    private fun drainLatest() {
        val frames = synchronized(lock) {
            val edge = continuityEdge
            continuityEdge = null
            val snapshot = latest
            latest = null
            runnableScheduled = false
            listOfNotNull(edge, snapshot).distinctBy(FishingMotionFrame::revision)
        }
        if (frames.isEmpty()) return
        var deliveryRan = false
        val ran = try {
            deliveryGate.deliverIfCurrent {
                deliveryRan = true
                frames.forEach { frame ->
                    try {
                        productSink.onFishingMotionFrame(frame)
                        synchronized(lock) {
                            deliveredFrameCount = deliveredFrameCount.incrementSaturated()
                        }
                    } catch (_: Exception) {
                        synchronized(lock) {
                            sinkFailureCount = sinkFailureCount.incrementSaturated()
                            droppedFrameCount = droppedFrameCount.incrementSaturated()
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!ran && !deliveryRan) {
            synchronized(lock) {
                repeat(frames.size) {
                    droppedFrameCount = droppedFrameCount.incrementSaturated()
                }
            }
        }
    }

    private fun pendingCountLocked(): Int =
        (if (continuityEdge == null) 0 else 1) + (if (latest == null) 0 else 1)

    private fun Long.incrementSaturated(): Long = if (this == Long.MAX_VALUE) this else this + 1L
}
