package com.motionarcade.vision.pose

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The only coordinate space exposed by the raw observation bridge. */
enum class LivePoseCoordinateSpace {
    ROTATED_ANALYSIS_NORMALIZED_V1,
}

/** A mandatory temporal reset boundary for downstream stateful recognition. */
enum class LivePoseContinuityBoundary {
    RESET_GENERATION,
    RESET_REVISION_GAP,
    CONTIGUOUS,
}

/**
 * Privacy-safe product output. It is continuity and aggregate state, never a gesture approval or
 * a container for landmarks, pixels, source timestamps, or task timestamps.
 */
class LivePoseSemanticSummary internal constructor(
    val sessionGeneration: Long,
    val revision: Long,
    val poseCount: Int,
    val temporalEpoch: Long,
    val continuityBoundary: LivePoseContinuityBoundary,
    val coordinateSpace: LivePoseCoordinateSpace,
) {
    val directGestureApprovalAllowed: Boolean = false

    init {
        require(sessionGeneration > 0L)
        require(revision > 0L)
        require(poseCount in 0..LIVE_POSE_MAX_POSES)
        require(temporalEpoch > 0L)
        require(coordinateSpace == LivePoseCoordinateSpace.ROTATED_ANALYSIS_NORMALIZED_V1)
    }

    override fun toString(): String =
        "LivePoseSemanticSummary(generation=$sessionGeneration, revision=$revision, " +
            "poseCount=$poseCount, epoch=$temporalEpoch, boundary=$continuityBoundary, " +
            "space=$coordinateSpace, directGestureApprovalAllowed=false)"
}

fun interface LivePoseSemanticSink {
    fun onPoseSemanticSummary(summary: LivePoseSemanticSummary)
}

/** Actual product-path consumer retained by the camera controller. */
internal class LivePoseSemanticStateStore : LivePoseSemanticSink {
    private val latest = AtomicReference<LivePoseSemanticSummary?>(null)

    override fun onPoseSemanticSummary(summary: LivePoseSemanticSummary) {
        latest.set(summary)
    }

    fun snapshot(): LivePoseSemanticSummary? = latest.get()

    fun clearGeneration(sessionGeneration: Long) {
        latest.updateAndGet { current ->
            if (current?.sessionGeneration == sessionGeneration) null else current
        }
    }
}

internal data class LivePoseContinuityDecision(
    val boundary: LivePoseContinuityBoundary,
    val temporalEpoch: Long,
)

internal fun interface LivePoseObservationSink {
    fun onPoseObservation(
        frame: LivePoseObservationFrame,
        continuity: LivePoseContinuityDecision,
    )

    companion object {
        val NONE = LivePoseObservationSink { _, _ -> }
    }
}

internal fun interface LivePoseObservationDeliveryGate {
    /** Returns true only when [delivery] ran while its camera generation remained current. */
    fun deliverIfCurrent(delivery: () -> Unit): Boolean

    companion object {
        val UNCONDITIONAL = LivePoseObservationDeliveryGate { delivery ->
            delivery()
            true
        }
    }
}

/** Shared across camera generations so a generation change is an executable reset, not a note. */
internal class LivePoseTemporalContinuityGate {
    private var generation: Long? = null
    private var lastRevision: Long? = null
    private var temporalEpoch = 0L

    @Synchronized
    fun admit(frame: LivePoseObservationFrame): LivePoseContinuityDecision? {
        check(frame.coordinateSpace == LivePoseCoordinateSpace.ROTATED_ANALYSIS_NORMALIZED_V1)
        val previousGeneration = generation
        val previousRevision = lastRevision
        if (
            previousGeneration != null &&
                (frame.sessionGeneration < previousGeneration ||
                    (frame.sessionGeneration == previousGeneration &&
                        previousRevision != null &&
                        frame.revision <= previousRevision))
        ) {
            return null
        }
        val boundary =
            when {
                previousGeneration != frame.sessionGeneration ->
                    LivePoseContinuityBoundary.RESET_GENERATION
                previousRevision == null ||
                    previousRevision == Long.MAX_VALUE ||
                    frame.revision != previousRevision + 1L ->
                    LivePoseContinuityBoundary.RESET_REVISION_GAP
                else -> LivePoseContinuityBoundary.CONTIGUOUS
            }
        if (boundary != LivePoseContinuityBoundary.CONTIGUOUS) {
            temporalEpoch = Math.incrementExact(temporalEpoch)
        }
        check(temporalEpoch > 0L) { "The first observation must establish a temporal epoch" }
        generation = frame.sessionGeneration
        lastRevision = frame.revision
        return LivePoseContinuityDecision(boundary, temporalEpoch)
    }

    @Synchronized
    fun terminate(terminatedGeneration: Long) {
        if (generation == terminatedGeneration) lastRevision = null
    }
}

/**
 * Sole raw-observation consumer. Its lock serializes semantic delivery with terminal reset, so a
 * completed terminal fence implies that no old-generation semantic application is still running.
 */
internal class LivePoseObservationCoordinator(
    private val sessionGeneration: Long,
    private val continuityGate: LivePoseTemporalContinuityGate,
    private val semanticSink: LivePoseSemanticSink,
    private val deliveryGate: LivePoseObservationDeliveryGate =
        LivePoseObservationDeliveryGate.UNCONDITIONAL,
    private val onTerminal: () -> Unit = {},
    private val onObservationError: (Long) -> Unit = {},
    private val observationSink: LivePoseObservationSink = LivePoseObservationSink.NONE,
) {
    private val lock = Any()
    private var active = true

    fun apply(frame: LivePoseObservationFrame): Boolean =
        synchronized(lock) {
            if (!active || frame.sessionGeneration != sessionGeneration) {
                return@synchronized false
            }
            var delivered = false
            val generationWasCurrent =
                deliveryGate.deliverIfCurrent {
                    val decision = continuityGate.admit(frame) ?: return@deliverIfCurrent
                    val summary =
                        LivePoseSemanticSummary(
                            sessionGeneration = frame.sessionGeneration,
                            revision = frame.revision,
                            poseCount = frame.poses.size,
                            temporalEpoch = decision.temporalEpoch,
                            continuityBoundary = decision.boundary,
                            coordinateSpace = frame.coordinateSpace,
                        )
                    try {
                        observationSink.onPoseObservation(frame, decision)
                    } catch (_: Exception) {
                        try {
                            onObservationError(frame.sessionGeneration)
                        } catch (_: Exception) {
                            // The coordinator still withholds this result from product admission.
                        }
                    }
                    try {
                        semanticSink.onPoseSemanticSummary(summary)
                    } catch (_: Exception) {
                        // A product observer cannot reopen or corrupt the continuity gate.
                    }
                    delivered = true
                }
            // A reentrant close may have terminated this coordinator from inside the sink.
            generationWasCurrent && delivered && active
        }

    fun terminate() {
        synchronized(lock) {
            if (!active) return
            active = false
            continuityGate.terminate(sessionGeneration)
            try {
                onTerminal()
            } catch (_: Exception) {
                // Terminal fencing remains authoritative even if a presentation observer fails.
            }
        }
    }
}

internal enum class LivePoseObservationTerminalReason {
    FAILED,
    RELEASED,
    REBOUND,
}

internal data class LivePoseObservationDispatcherSnapshot(
    val accepting: Boolean,
    val pendingCount: Int,
    val inFlightCount: Int,
    val maximumObservedPendingCount: Int,
    val acceptedCount: Long,
    val droppedCount: Long,
    val appliedCount: Long,
)

internal interface LivePoseObservationDispatcher {
    /**
     * Never applies the frame to the coordinator inline. Terminal fencing may run inline when
     * scheduling fails. Returns true when the frame passed admission checks; a later worker
     * failure cannot retroactively change that admission result.
     */
    fun offer(frame: LivePoseObservationFrame): Boolean

    /** Revokes future delivery and drops queued raw observations without waiting for a consumer. */
    fun revoke(reason: LivePoseObservationTerminalReason)

    /** Returns only after queued and executing raw observation work has reached zero. */
    fun closeAndAwait(reason: LivePoseObservationTerminalReason)

    fun snapshot(): LivePoseObservationDispatcherSnapshot
}

internal fun interface LivePoseObservationDispatcherFactory {
    fun create(
        sessionGeneration: Long,
        coordinator: LivePoseObservationCoordinator,
    ): LivePoseObservationDispatcher

    companion object {
        val SYSTEM =
            LivePoseObservationDispatcherFactory { generation, coordinator ->
                BoundedLivePoseObservationDispatcher(
                    sessionGeneration = generation,
                    coordinator = coordinator,
                    workerExecutor =
                        Executors.newSingleThreadExecutor { runnable ->
                            Thread(
                                runnable,
                                "MotionArcade-PoseObservation-$generation",
                            ).apply { isDaemon = true }
                        },
                    submissionBridge = LivePoseExecutorSubmissionBridge.SYSTEM,
                )
            }
    }
}

internal fun interface LivePoseExecutorSubmissionTicket {
    fun cancel()
}

internal data class LivePoseExecutorSubmissionBridgeSnapshot(
    val pendingCount: Int,
    val executingSubmissionCount: Int,
    val maximumObservedPendingCount: Int,
    val workerThreadCount: Int,
)

/**
 * One process-wide indirection owns calls to potentially hostile ExecutorService.execute(). Its
 * queue is latest-only, so one permanently blocked execute call retains at most that request plus
 * one replaceable pending request instead of one daemon and object graph per camera generation.
 */
internal interface LivePoseExecutorSubmissionBridge {
    fun submit(
        executor: ExecutorService,
        task: Runnable,
        onFailure: () -> Unit,
    ): LivePoseExecutorSubmissionTicket?

    fun snapshot(): LivePoseExecutorSubmissionBridgeSnapshot

    companion object {
        val SYSTEM: LivePoseExecutorSubmissionBridge =
            BoundedLivePoseExecutorSubmissionBridge("MotionArcade-PoseExecutorBridge")
    }
}

internal class BoundedLivePoseExecutorSubmissionBridge(
    threadName: String,
) : LivePoseExecutorSubmissionBridge {
    private class Submission(
        val executor: ExecutorService,
        val task: Runnable,
        val onFailure: () -> Unit,
    ) {
        val cancelled = AtomicBoolean(false)
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pending: Submission? = null
    private var executing: Submission? = null
    private var stopped = false
    private var maximumObservedPendingCount = 0
    private val worker = Thread(::runLoop, threadName).apply { isDaemon = true }

    init {
        worker.start()
    }

    override fun submit(
        executor: ExecutorService,
        task: Runnable,
        onFailure: () -> Unit,
    ): LivePoseExecutorSubmissionTicket? {
        val submission = Submission(executor, task, onFailure)
        val displaced =
            lock.withLock {
                if (stopped) return null
                pending.also {
                    pending = submission
                    maximumObservedPendingCount = maxOf(maximumObservedPendingCount, 1)
                    changed.signalAll()
                }
            }
        if (displaced != null && displaced.cancelled.compareAndSet(false, true)) {
            signalFailure(displaced)
        }
        return LivePoseExecutorSubmissionTicket { cancel(submission) }
    }

    override fun snapshot(): LivePoseExecutorSubmissionBridgeSnapshot =
        lock.withLock {
            LivePoseExecutorSubmissionBridgeSnapshot(
                pendingCount = if (pending == null) 0 else 1,
                executingSubmissionCount = if (executing == null) 0 else 1,
                maximumObservedPendingCount = maximumObservedPendingCount,
                workerThreadCount = if (worker.isAlive) 1 else 0,
            )
        }

    /** Test-only lifecycle; production intentionally owns one bridge for the process lifetime. */
    fun closeForTest() {
        val abandoned =
            lock.withLock {
                if (stopped) return
                stopped = true
                pending.also { pending = null }
            }
        abandoned?.cancelled?.set(true)
        worker.interrupt()
    }

    private fun cancel(submission: Submission) {
        submission.cancelled.set(true)
        lock.withLock {
            if (pending === submission) {
                pending = null
                changed.signalAll()
            }
        }
    }

    private fun runLoop() {
        while (true) {
            val submission =
                lock.withLock {
                    while (pending == null && !stopped) {
                        try {
                            changed.await()
                        } catch (_: InterruptedException) {
                            if (stopped) return
                        }
                    }
                    if (stopped) return
                    requireNotNull(pending).also {
                        pending = null
                        executing = it
                    }
                }
            try {
                if (!submission.cancelled.get()) {
                    submission.executor.execute {
                        if (!submission.cancelled.get()) submission.task.run()
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (submission.cancelled.compareAndSet(false, true)) signalFailure(submission)
            } catch (_: RuntimeException) {
                if (submission.cancelled.compareAndSet(false, true)) signalFailure(submission)
            } finally {
                lock.withLock {
                    if (executing === submission) executing = null
                    changed.signalAll()
                }
            }
        }
    }

    private fun signalFailure(submission: Submission) {
        try {
            submission.onFailure()
        } catch (_: Exception) {
            // Submission failure is already fail-closed; observers cannot reopen it.
        }
    }
}

/** One executing frame plus one latest pending frame; older pending frames are dropped. */
internal class BoundedLivePoseObservationDispatcher(
    private val sessionGeneration: Long,
    private val coordinator: LivePoseObservationCoordinator,
    private val workerExecutor: ExecutorService,
    private val submissionBridge: LivePoseExecutorSubmissionBridge =
        LivePoseExecutorSubmissionBridge.SYSTEM,
    private val beforeDeliveryCheck: (LivePoseObservationFrame) -> Unit = {},
) : LivePoseObservationDispatcher {
    init {
        require(sessionGeneration > 0L)
    }

    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()
    private var accepting = true
    private var pending: LivePoseObservationFrame? = null
    private var workerScheduled = false
    private var workerRunning = false
    private var inFlightCount = 0
    private var inFlightThread: Thread? = null
    private var maximumObservedPendingCount = 0
    private var acceptedCount = 0L
    private var droppedCount = 0L
    private var appliedCount = 0L
    private var terminalReason: LivePoseObservationTerminalReason? = null
    private var submissionTicket: LivePoseExecutorSubmissionTicket? = null
    private var shutdownWhenWorkerExits = false
    private var lastOfferedRevision: Long? = null

    override fun offer(frame: LivePoseObservationFrame): Boolean {
        var requestWorker = false
        stateLock.withLock {
            if (!accepting || frame.sessionGeneration != sessionGeneration) {
                return false
            }
            val previousRevision = lastOfferedRevision
            if (previousRevision != null && frame.revision <= previousRevision) {
                droppedCount = droppedCount.incrementSaturated()
                return false
            }
            if (pending != null) droppedCount = droppedCount.incrementSaturated()
            pending = frame
            lastOfferedRevision = frame.revision
            acceptedCount = acceptedCount.incrementSaturated()
            maximumObservedPendingCount = maxOf(maximumObservedPendingCount, 1)
            if (!workerScheduled) {
                workerScheduled = true
                requestWorker = true
            }
            stateChanged.signalAll()
        }
        if (requestWorker) requestWorkerSubmission()
        return true
    }

    override fun revoke(reason: LivePoseObservationTerminalReason) {
        var ticket: LivePoseExecutorSubmissionTicket? = null
        var terminateNow = false
        stateLock.withLock {
            if (terminalReason == null) terminalReason = reason
            accepting = false
            if (pending != null) {
                pending = null
                droppedCount = droppedCount.incrementSaturated()
            }
            ticket = submissionTicket
            submissionTicket = null
            if (!workerRunning) {
                workerScheduled = false
            }
            terminateNow = inFlightCount == 0
            stateChanged.signalAll()
        }
        ticket?.cancel()
        if (terminateNow) coordinator.terminate()
    }

    override fun closeAndAwait(reason: LivePoseObservationTerminalReason) {
        revoke(reason)
        val reentrantConsumer =
            stateLock.withLock { inFlightThread === Thread.currentThread() }
        if (reentrantConsumer) {
            // A consumer cannot wait for itself. Revoke and terminate reentrantly; the worker's
            // finally block performs the remaining executor cleanup after the sink unwinds.
            coordinator.terminate()
            stateLock.withLock {
                shutdownWhenWorkerExits = true
                stateChanged.signalAll()
            }
            return
        }
        var interrupted = false
        stateLock.withLock {
            while (inFlightCount != 0) {
                try {
                    stateChanged.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        coordinator.terminate()
        var ticket: LivePoseExecutorSubmissionTicket? = null
        stateLock.withLock {
            ticket = submissionTicket
            submissionTicket = null
            workerScheduled = false
            stateChanged.signalAll()
        }
        ticket?.cancel()
        shutdownWorkerExecutor()
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun snapshot(): LivePoseObservationDispatcherSnapshot =
        stateLock.withLock {
            LivePoseObservationDispatcherSnapshot(
                accepting = accepting,
                pendingCount = if (pending == null) 0 else 1,
                inFlightCount = inFlightCount,
                maximumObservedPendingCount = maximumObservedPendingCount,
                acceptedCount = acceptedCount,
                droppedCount = droppedCount,
                appliedCount = appliedCount,
            )
        }

    private fun drain() {
        stateLock.withLock {
            workerRunning = true
            submissionTicket = null
        }
        try {
            while (true) {
                val frame =
                    stateLock.withLock {
                        if (!accepting) {
                            pending = null
                            return@withLock null
                        }
                        pending?.also {
                            pending = null
                            inFlightCount = 1
                            inFlightThread = Thread.currentThread()
                        }
                    }
                if (frame == null) {
                    val retry =
                        stateLock.withLock {
                            if (accepting && pending != null) {
                                true
                            } else {
                                workerScheduled = false
                                workerRunning = false
                                stateChanged.signalAll()
                                false
                            }
                        }
                    if (!accepting) coordinator.terminate()
                    if (retry) continue
                    shutdownAfterReentrantCloseIfRequired()
                    return
                }
                try {
                    beforeDeliveryCheck(frame)
                    val deliveryStillAccepted = stateLock.withLock { accepting }
                    if (deliveryStillAccepted && coordinator.apply(frame)) {
                        stateLock.withLock {
                            appliedCount = appliedCount.incrementSaturated()
                        }
                    }
                } catch (_: Exception) {
                    // Coordinator exceptions cannot strand the worker or revive a generation.
                } finally {
                    stateLock.withLock {
                        inFlightCount = 0
                        inFlightThread = null
                        stateChanged.signalAll()
                    }
                }
            }
        } catch (failure: Throwable) {
            coordinator.terminate()
            var ticket: LivePoseExecutorSubmissionTicket? = null
            stateLock.withLock {
                accepting = false
                pending = null
                inFlightCount = 0
                inFlightThread = null
                workerScheduled = false
                workerRunning = false
                ticket = submissionTicket
                submissionTicket = null
                stateChanged.signalAll()
            }
            ticket?.cancel()
            shutdownAfterReentrantCloseIfRequired()
            throw failure
        }
    }

    private fun requestWorkerSubmission() {
        val ticket =
            try {
                submissionBridge.submit(workerExecutor, Runnable(::drain), ::rejectScheduledWorker)
            } catch (_: RuntimeException) {
                null
            }
        if (ticket == null) {
            rejectScheduledWorker()
            return
        }
        val retain =
            stateLock.withLock {
                if (workerScheduled) {
                    submissionTicket = ticket
                    true
                } else {
                    false
                }
            }
        if (!retain) ticket.cancel()
    }

    private fun rejectScheduledWorker() {
        var ticket: LivePoseExecutorSubmissionTicket? = null
        stateLock.withLock {
            accepting = false
            if (terminalReason == null) terminalReason = LivePoseObservationTerminalReason.FAILED
            if (pending != null) {
                pending = null
                droppedCount = droppedCount.incrementSaturated()
            }
            workerScheduled = false
            workerRunning = false
            ticket = submissionTicket
            submissionTicket = null
            stateChanged.signalAll()
        }
        ticket?.cancel()
        coordinator.terminate()
    }

    private fun shutdownAfterReentrantCloseIfRequired() {
        val shouldShutdown =
            stateLock.withLock {
                shutdownWhenWorkerExits.also { shutdownWhenWorkerExits = false }
            }
        if (shouldShutdown) shutdownWorkerExecutor()
    }

    private fun shutdownWorkerExecutor() {
        try {
            workerExecutor.shutdownNow()
        } catch (_: RuntimeException) {
            // The terminal coordinator fence is independent from executor shutdown behavior.
        }
    }

    private fun Long.incrementSaturated(): Long =
        if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L
}
