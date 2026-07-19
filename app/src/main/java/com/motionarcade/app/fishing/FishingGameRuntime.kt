package com.motionarcade.app.fishing

import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.motion.FishingMotionConfig
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The single-owner boundary for fishing simulation callbacks.
 *
 * Implementations must not hand work back to the caller thread. The runtime invokes every method
 * from its scheduler worker and preserves the global order of commands that survive coalescing.
 */
internal interface FishingGameRuntimeTarget {
    fun onTick(nowNs: Long)

    fun onPrimaryAction(nowNs: Long)

    fun onMotionFrame(frame: FishingMotionFrame, observedAtNs: Long)

    fun onPauseToggle(nowNs: Long)

    fun onBackground()

    fun onForeground(nowNs: Long)

    fun onCameraState(active: Boolean, poseCount: Int?, observedAtNs: Long)

    fun onMotionBindingStarted(
        sessionId: String,
        eventTimelineEpoch: Long,
        sessionGeneration: Long,
    )

    fun onMotionBindingClosed()

    fun onCameraRecalibrated(config: FishingMotionConfig)

    /** Fail-safe signal for one or more discrete commands rejected by a full mailbox. */
    fun onRuntimeQueueOverflow()

    /** Fail-safe signal for a non-fatal exception raised while dispatching the worker cycle. */
    fun onRuntimeWorkerFailure()
}

internal fun interface FishingGameRuntimeMonotonicClock {
    fun nowNs(): Long
}

/** Scheduler seam used by deterministic JVM tests. */
internal interface FishingGameRuntimeScheduler {
    fun scheduleWithFixedDelay(
        initialDelayNs: Long,
        delayNs: Long,
        task: () -> Unit,
    )

    /** Queues one task on the same worker ahead of scheduler shutdown. */
    fun enqueueFinal(task: () -> Unit): Boolean = false

    /** Stops admission/execution and waits no longer than [timeoutMs]. */
    fun closeAndAwait(timeoutMs: Long)
}

internal enum class FishingGameRuntimeCloseResult {
    CLOSED_NO_PENDING_BACKGROUND,
    CLOSED_AFTER_BACKGROUND_DELIVERY,
    BACKGROUND_DELIVERY_SCHEDULE_REJECTED,
    BACKGROUND_DELIVERY_INCOMPLETE,
    CLOSE_IN_PROGRESS,
}

internal data class FishingGameRuntimePendingCounts(
    val discrete: Int,
    val motion: Int,
    val camera: Int,
    val overflow: Int,
)

/**
 * A bounded mailbox in front of the deterministic 60 Hz fishing controller.
 *
 * Discrete commands retain FIFO order. Lifecycle background, motion, and camera observations are
 * bounded latest-only lanes because they can arrive faster than simulation can consume them. All
 * lanes share a sequence so the surviving commands are dispatched in their original global order.
 */
internal class FishingGameRuntime(
    private val target: FishingGameRuntimeTarget,
    private val clock: FishingGameRuntimeMonotonicClock =
        FishingGameRuntimeMonotonicClock(System::nanoTime),
    private val scheduler: FishingGameRuntimeScheduler =
        SingleThreadFishingGameRuntimeScheduler(),
    private val closeAwaitMs: Long = DEFAULT_CLOSE_AWAIT_MS,
) : AutoCloseable {
    private val mailboxLock = Any()
    private val discreteCommands = ArrayDeque<RuntimeCommand>(DISCRETE_CAPACITY)
    private var pendingBackground: BackgroundCommand? = null
    private var pendingRecalibration: CameraRecalibratedCommand? = null
    private var degradedMotionEdge: MotionCommand? = null
    private var latestMotion: MotionCommand? = null
    private var degradedCameraEdge: CameraCommand? = null
    private var latestCamera: CameraCommand? = null
    private var pendingOverflow: QueueOverflowCommand? = null
    private var nextSequence = 1L
    private var admissionOpen = true
    private var workerFailed = false
    private var latestAdmittedBackgroundSequence: Long? = null
    private var latestDeliveredBackgroundSequence: Long? = null
    private var closeStarted = false
    private var completedCloseResult: FishingGameRuntimeCloseResult? = null

    init {
        require(closeAwaitMs in 1L..MAX_CLOSE_AWAIT_MS)
        scheduler.scheduleWithFixedDelay(
            initialDelayNs = 0L,
            delayNs = FRAME_PERIOD_NS,
            task = ::runWorkerCycle,
        )
    }

    fun onPrimaryAction() {
        enqueueDiscrete { sequence, nowNs -> PrimaryActionCommand(sequence, nowNs) }
    }

    fun onMotionFrame(frame: FishingMotionFrame) {
        synchronized(mailboxLock) {
            if (!acceptsInputsLocked()) return
            val command = MotionCommand(nextSequenceLocked(), clock.nowNs().validated(), frame)
            if (!frame.usableForSolo && degradedMotionEdge == null) {
                degradedMotionEdge = command
            }
            latestMotion = command
        }
    }

    fun onPauseToggle() {
        enqueueDiscrete { sequence, nowNs -> PauseToggleCommand(sequence, nowNs) }
    }

    fun onBackground() {
        synchronized(mailboxLock) {
            if (!acceptsInputsLocked()) return
            val command = BackgroundCommand(nextSequenceLocked())
            // Lifecycle safety is independent of the gameplay FIFO. Multiple background edges
            // before a worker cycle are equivalent, so retain only the latest admitted intent.
            pendingBackground = command
            latestAdmittedBackgroundSequence = command.sequence
        }
    }

    fun onForeground() {
        enqueueDiscrete { sequence, nowNs -> ForegroundCommand(sequence, nowNs) }
    }

    fun onCameraState(active: Boolean, poseCount: Int?) {
        synchronized(mailboxLock) {
            if (!acceptsInputsLocked()) return
            val command = CameraCommand(
                sequence = nextSequenceLocked(),
                observedAtNs = clock.nowNs().validated(),
                active = active,
                poseCount = poseCount,
            )
            if ((!active || poseCount != 1) && degradedCameraEdge == null) {
                degradedCameraEdge = command
            }
            latestCamera = command
        }
    }

    fun onMotionBindingStarted(
        sessionId: String,
        eventTimelineEpoch: Long,
        sessionGeneration: Long,
    ) {
        enqueueDiscrete { sequence, _ ->
            MotionBindingStartedCommand(
                sequence = sequence,
                sessionId = sessionId,
                eventTimelineEpoch = eventTimelineEpoch,
                sessionGeneration = sessionGeneration,
            )
        }
    }

    fun onMotionBindingClosed() {
        enqueueDiscrete { sequence, _ -> MotionBindingClosedCommand(sequence) }
    }

    fun onCameraRecalibrated(config: FishingMotionConfig) {
        synchronized(mailboxLock) {
            if (!acceptsInputsLocked()) return
            // Camera epoch migration is a safety barrier, not optional gameplay input. Retain the
            // latest requested revision even when the bounded gameplay FIFO is saturated.
            if (
                pendingRecalibration?.config?.calibrationRevision
                    ?.let { it >= config.calibrationRevision } == true
            ) {
                return
            }
            pendingRecalibration = CameraRecalibratedCommand(nextSequenceLocked(), config)
        }
    }

    internal fun pendingCounts(): FishingGameRuntimePendingCounts = synchronized(mailboxLock) {
        FishingGameRuntimePendingCounts(
            discrete = discreteCommands.size +
                (if (pendingBackground == null) 0 else 1) +
                (if (pendingRecalibration == null) 0 else 1),
            motion = distinctPendingCount(degradedMotionEdge, latestMotion),
            camera = distinctPendingCount(degradedCameraEdge, latestCamera),
            overflow = if (pendingOverflow == null) 0 else 1,
        )
    }

    override fun close() {
        closeWithResult()
    }

    internal fun closeWithResult(): FishingGameRuntimeCloseResult {
        val needsBackgroundDelivery = synchronized(mailboxLock) {
            if (closeStarted) {
                return completedCloseResult ?: FishingGameRuntimeCloseResult.CLOSE_IN_PROGRESS
            }
            closeStarted = true
            admissionOpen = false
            workerFailed = true
            discreteCommands.clear()
            pendingBackground = null
            pendingRecalibration = null
            degradedMotionEdge = null
            latestMotion = null
            degradedCameraEdge = null
            latestCamera = null
            pendingOverflow = null
            hasUndeliveredBackgroundLocked()
        }

        if (!needsBackgroundDelivery) {
            scheduler.closeAndAwait(closeAwaitMs)
            return completeClose(FishingGameRuntimeCloseResult.CLOSED_NO_PENDING_BACKGROUND)
        }

        val finalizerScheduled = try {
            scheduler.enqueueFinal(::deliverFinalBackgroundOnWorker)
        } catch (failure: Throwable) {
            if (failure.isFatalRuntimeFailure()) throw failure
            false
        }
        if (!finalizerScheduled) {
            scheduler.closeAndAwait(closeAwaitMs)
            val delivered = synchronized(mailboxLock) { !hasUndeliveredBackgroundLocked() }
            return completeClose(
                if (delivered) {
                    FishingGameRuntimeCloseResult.CLOSED_AFTER_BACKGROUND_DELIVERY
                } else {
                    FishingGameRuntimeCloseResult.BACKGROUND_DELIVERY_SCHEDULE_REJECTED
                },
            )
        }

        scheduler.closeAndAwait(closeAwaitMs)
        val delivered = synchronized(mailboxLock) { !hasUndeliveredBackgroundLocked() }
        return completeClose(
            if (delivered) {
                FishingGameRuntimeCloseResult.CLOSED_AFTER_BACKGROUND_DELIVERY
            } else {
                FishingGameRuntimeCloseResult.BACKGROUND_DELIVERY_INCOMPLETE
            },
        )
    }

    private fun enqueueDiscrete(factory: (Long, Long) -> RuntimeCommand) {
        synchronized(mailboxLock) {
            if (!acceptsInputsLocked()) return
            val sequence = nextSequenceLocked()
            if (discreteCommands.size >= DISCRETE_CAPACITY) {
                if (pendingOverflow == null) {
                    pendingOverflow = QueueOverflowCommand(sequence)
                }
                return
            }
            discreteCommands.addLast(factory(sequence, clock.nowNs().validated()))
        }
    }

    private fun nextSequenceLocked(): Long {
        check(nextSequence > 0L) { "runtime command sequence exhausted" }
        val allocated = nextSequence
        nextSequence = if (allocated == Long.MAX_VALUE) 0L else allocated + 1L
        return allocated
    }

    private fun runWorkerCycle() {
        try {
            runWorkerCycleUnchecked()
        } catch (failure: Throwable) {
            if (failure.isFatalRuntimeFailure()) throw failure
            failWorker()
        }
    }

    private fun runWorkerCycleUnchecked() {
        val cycle = drainCycle() ?: return
        for (command in cycle.commands) {
            if (!isExecutionOpen()) return
            dispatchCommand(command)
        }
        if (isExecutionOpen()) {
            target.onTick(cycle.tickTimestampNs)
        }
    }

    private fun drainCycle(): WorkerCycle? = synchronized(mailboxLock) {
        if (!acceptsInputsLocked()) return@synchronized null
        val drained = ArrayList<RuntimeCommand>(discreteCommands.size + COALESCED_LANE_COUNT)
        while (discreteCommands.isNotEmpty()) {
            drained += discreteCommands.removeFirst()
        }
        pendingBackground?.let(drained::add)
        pendingBackground = null
        pendingRecalibration?.let(drained::add)
        pendingRecalibration = null
        degradedMotionEdge?.let(drained::add)
        degradedMotionEdge = null
        latestMotion?.let(drained::add)
        latestMotion = null
        degradedCameraEdge?.let(drained::add)
        degradedCameraEdge = null
        latestCamera?.let(drained::add)
        latestCamera = null
        pendingOverflow?.let(drained::add)
        pendingOverflow = null
        drained.distinctBy(RuntimeCommand::sequence).sortedBy(RuntimeCommand::sequence).let {
            drained.clear()
            drained.addAll(it)
        }
        // Capture the tick while admission is locked. An input admitted after this point therefore
        // cannot carry a timestamp older than a tick that overtakes it on the worker.
        WorkerCycle(drained, clock.nowNs().validated())
    }

    private fun isExecutionOpen(): Boolean = synchronized(mailboxLock) { acceptsInputsLocked() }

    private fun acceptsInputsLocked(): Boolean = admissionOpen && !workerFailed

    private fun failWorker() {
        val shouldSignal = synchronized(mailboxLock) {
            if (!acceptsInputsLocked()) {
                false
            } else {
                workerFailed = true
                discreteCommands.clear()
                pendingBackground = null
                pendingRecalibration = null
                degradedMotionEdge = null
                latestMotion = null
                degradedCameraEdge = null
                latestCamera = null
                pendingOverflow = null
                true
            }
        }
        if (!shouldSignal) return
        try {
            target.onRuntimeWorkerFailure()
        } catch (failure: Throwable) {
            if (failure.isFatalRuntimeFailure()) throw failure
            // The worker is already fenced. A second non-fatal target failure must not cancel the
            // ScheduledExecutor task or create an unbounded retry loop.
        }
    }

    private fun Throwable.isFatalRuntimeFailure(): Boolean =
        this is VirtualMachineError || this is ThreadDeath || this is LinkageError

    private fun dispatchCommand(command: RuntimeCommand) {
        command.dispatch(target)
        if (command is BackgroundCommand) {
            synchronized(mailboxLock) {
                latestDeliveredBackgroundSequence = command.sequence
            }
        }
    }

    private fun deliverFinalBackgroundOnWorker() {
        val sequence = synchronized(mailboxLock) {
            latestAdmittedBackgroundSequence?.takeIf {
                it != latestDeliveredBackgroundSequence
            }
        } ?: return
        try {
            target.onBackground()
        } catch (failure: Throwable) {
            if (failure.isFatalRuntimeFailure()) throw failure
            return
        }
        synchronized(mailboxLock) {
            latestDeliveredBackgroundSequence = sequence
        }
    }

    private fun hasUndeliveredBackgroundLocked(): Boolean =
        latestAdmittedBackgroundSequence != null &&
            latestAdmittedBackgroundSequence != latestDeliveredBackgroundSequence

    private fun completeClose(
        result: FishingGameRuntimeCloseResult,
    ): FishingGameRuntimeCloseResult = synchronized(mailboxLock) {
        completedCloseResult = result
        result
    }

    private fun distinctPendingCount(first: RuntimeCommand?, second: RuntimeCommand?): Int = when {
        first == null && second == null -> 0
        first == null || second == null -> 1
        first.sequence == second.sequence -> 1
        else -> 2
    }

    private fun Long.validated(): Long {
        require(this >= 0L) { "runtime clock must be non-negative" }
        return this
    }

    private sealed interface RuntimeCommand {
        val sequence: Long

        fun dispatch(target: FishingGameRuntimeTarget)
    }

    private data class WorkerCycle(
        val commands: List<RuntimeCommand>,
        val tickTimestampNs: Long,
    )

    private data class PrimaryActionCommand(
        override val sequence: Long,
        val nowNs: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onPrimaryAction(nowNs)
        }
    }

    private data class MotionCommand(
        override val sequence: Long,
        val observedAtNs: Long,
        val frame: FishingMotionFrame,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onMotionFrame(frame, observedAtNs)
        }
    }

    private data class PauseToggleCommand(
        override val sequence: Long,
        val nowNs: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onPauseToggle(nowNs)
        }
    }

    private data class BackgroundCommand(
        override val sequence: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onBackground()
        }
    }

    private data class ForegroundCommand(
        override val sequence: Long,
        val nowNs: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onForeground(nowNs)
        }
    }

    private data class CameraCommand(
        override val sequence: Long,
        val observedAtNs: Long,
        val active: Boolean,
        val poseCount: Int?,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onCameraState(active, poseCount, observedAtNs)
        }
    }

    private data class MotionBindingStartedCommand(
        override val sequence: Long,
        val sessionId: String,
        val eventTimelineEpoch: Long,
        val sessionGeneration: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onMotionBindingStarted(sessionId, eventTimelineEpoch, sessionGeneration)
        }
    }

    private data class MotionBindingClosedCommand(
        override val sequence: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onMotionBindingClosed()
        }
    }

    private data class CameraRecalibratedCommand(
        override val sequence: Long,
        val config: FishingMotionConfig,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onCameraRecalibrated(config)
        }
    }

    private data class QueueOverflowCommand(
        override val sequence: Long,
    ) : RuntimeCommand {
        override fun dispatch(target: FishingGameRuntimeTarget) {
            target.onRuntimeQueueOverflow()
        }
    }

    internal companion object {
        const val DISCRETE_CAPACITY: Int = 32
        const val FRAME_PERIOD_NS: Long = FishingGameSession.FIXED_STEP_NS
        private const val COALESCED_LANE_COUNT = 7
        private const val DEFAULT_CLOSE_AWAIT_MS = 500L
        private const val MAX_CLOSE_AWAIT_MS = 2_000L
    }
}

private class SingleThreadFishingGameRuntimeScheduler : FishingGameRuntimeScheduler {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
        },
    )

    override fun scheduleWithFixedDelay(
        initialDelayNs: Long,
        delayNs: Long,
        task: () -> Unit,
    ) {
        require(initialDelayNs >= 0L)
        require(delayNs > 0L)
        executor.scheduleWithFixedDelay(
            task,
            initialDelayNs,
            delayNs,
            TimeUnit.NANOSECONDS,
        )
    }

    override fun enqueueFinal(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun closeAndAwait(timeoutMs: Long) {
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val startedNs = System.nanoTime()
        executor.shutdown()
        if (!executor.awaitTermination(timeoutNs, TimeUnit.NANOSECONDS)) {
            executor.shutdownNow()
            val elapsedNs = (System.nanoTime() - startedNs).coerceAtLeast(0L)
            val remainingNs = (timeoutNs - elapsedNs).coerceAtLeast(0L)
            if (remainingNs > 0L) {
                executor.awaitTermination(remainingNs, TimeUnit.NANOSECONDS)
            }
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "fishing-game-runtime"
    }
}
