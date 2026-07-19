package com.motionarcade.vision.camera

import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import java.util.concurrent.Executor

/** Observable proof that the worker-to-main handoff remains latest-only and bounded. */
internal data class LatestLivePoseInferenceDeliveryCounters(
    val pendingSnapshotCount: Int,
    val maximumPendingSnapshotCount: Int,
    val replacedSnapshotCount: Long,
    val droppedSnapshotCount: Long,
    val deliveredSnapshotCount: Long,
    val executorRejectionCount: Long,
    val sinkFailureCount: Long,
)

/**
 * Latest-only worker-to-main handoff for aggregate pose inference state.
 *
 * One immutable snapshot and one runnable may wait while the main thread is stalled. A terminal
 * snapshot wins even when its revision is lower than a pending nonterminal snapshot, and then
 * locks its generation against later nonterminal callbacks. The injected gate rechecks camera
 * ownership at execution time.
 */
internal class LatestLivePoseInferenceMainDelivery(
    private val sessionGeneration: Long,
    private val mainExecutor: Executor,
    private val deliveryGate: LivePoseObservationDeliveryGate,
    private val productSink: LivePoseInferenceSink,
) : LivePoseInferenceSink {
    init {
        require(sessionGeneration > 0L) { "A live delivery requires a positive camera generation" }
    }

    private val lock = Any()
    private var pendingSnapshot: LivePoseInferenceSnapshot? = null
    private var runnableScheduled = false
    private var terminalLocked = false
    private var maximumPendingSnapshotCount = 0
    private var replacedSnapshotCount = 0L
    private var droppedSnapshotCount = 0L
    private var deliveredSnapshotCount = 0L
    private var executorRejectionCount = 0L
    private var sinkFailureCount = 0L

    override fun onInference(snapshot: LivePoseInferenceSnapshot) {
        val shouldSchedule = synchronized(lock) {
            if (snapshot.sessionGeneration != sessionGeneration) {
                droppedSnapshotCount = droppedSnapshotCount.incrementSaturated()
                return@synchronized false
            }

            val terminal = snapshot.phase.isTerminal()
            if (terminalLocked) {
                droppedSnapshotCount = droppedSnapshotCount.incrementSaturated()
                return@synchronized false
            }

            val previous = pendingSnapshot
            if (previous == null) {
                pendingSnapshot = snapshot
                maximumPendingSnapshotCount = maxOf(maximumPendingSnapshotCount, 1)
                if (terminal) terminalLocked = true
            } else if (terminal) {
                pendingSnapshot = snapshot
                terminalLocked = true
                replacedSnapshotCount = replacedSnapshotCount.incrementSaturated()
            } else if (snapshot.revision > previous.revision) {
                pendingSnapshot = snapshot
                replacedSnapshotCount = replacedSnapshotCount.incrementSaturated()
            } else {
                droppedSnapshotCount = droppedSnapshotCount.incrementSaturated()
                return@synchronized false
            }

            if (runnableScheduled) {
                false
            } else {
                runnableScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        scheduleDrain()
    }

    fun counters(): LatestLivePoseInferenceDeliveryCounters = synchronized(lock) {
        LatestLivePoseInferenceDeliveryCounters(
            pendingSnapshotCount = if (pendingSnapshot == null) 0 else 1,
            maximumPendingSnapshotCount = maximumPendingSnapshotCount,
            replacedSnapshotCount = replacedSnapshotCount,
            droppedSnapshotCount = droppedSnapshotCount,
            deliveredSnapshotCount = deliveredSnapshotCount,
            executorRejectionCount = executorRejectionCount,
            sinkFailureCount = sinkFailureCount,
        )
    }

    private fun scheduleDrain() {
        try {
            mainExecutor.execute(::drainLatest)
        } catch (_: RuntimeException) {
            synchronized(lock) {
                if (pendingSnapshot != null) {
                    droppedSnapshotCount = droppedSnapshotCount.incrementSaturated()
                }
                pendingSnapshot = null
                runnableScheduled = false
                terminalLocked = false
                executorRejectionCount = executorRejectionCount.incrementSaturated()
            }
        }
    }

    private fun drainLatest() {
        val snapshot = synchronized(lock) {
            val latest = pendingSnapshot
            pendingSnapshot = null
            runnableScheduled = false
            latest
        } ?: return

        var deliveryRan = false
        val ran = try {
            deliveryGate.deliverIfCurrent {
                deliveryRan = true
                try {
                    productSink.onInference(snapshot)
                    synchronized(lock) {
                        deliveredSnapshotCount = deliveredSnapshotCount.incrementSaturated()
                    }
                } catch (_: Exception) {
                    synchronized(lock) {
                        sinkFailureCount = sinkFailureCount.incrementSaturated()
                        droppedSnapshotCount = droppedSnapshotCount.incrementSaturated()
                    }
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!ran && !deliveryRan) {
            synchronized(lock) {
                droppedSnapshotCount = droppedSnapshotCount.incrementSaturated()
            }
        }
    }

    private fun LivePoseInferencePhase.isTerminal(): Boolean =
        this == LivePoseInferencePhase.FAILED || this == LivePoseInferencePhase.RELEASED

    private fun Long.incrementSaturated(): Long = if (this == Long.MAX_VALUE) this else this + 1L
}
