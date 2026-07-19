package com.motionarcade.vision.capability.recovery

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.IntSupplier
import java.util.function.LongSupplier

/** Opaque process-lifetime monitor backed by an API29+ application PowerManager listener. */
sealed interface ProcessThermalSafetyMonitor

/** One exact start/end/cutoff window owned by a process monitor. */
sealed interface RecoveryThermalMeasurement

/** Opaque cutoff handle. Its presence is not proof until the closure boundary consumes it. */
sealed interface RecoveryThermalCutoff

private enum class ThermalMonitorState {
    OPEN,
    FAILED,
    SHUTTING_DOWN,
}

private enum class ThermalEndpoint {
    START,
    END,
}

private interface ThermalPlatformPort {
    fun register(): Boolean

    fun removeListenerAndDrainBounded(): Boolean
}

/**
 * Shared registration linearization helper. Platform APIs are allowed to install a listener and
 * then throw; both the exact inverse and bounded worker termination must therefore run on every
 * exceptional return. The helper intentionally returns false after any registration exception,
 * even when cleanup appears successful.
 */
internal object SideEffectRegistrationRollbackBoundary {
    fun register(
        registerMayHaveSideEffect: () -> Unit,
        rollbackExactSideEffect: () -> Boolean,
        terminateAndJoinBounded: () -> Boolean,
    ): Boolean {
        return try {
            registerMayHaveSideEffect()
            true
        } catch (_: Throwable) {
            val rolledBack = try {
                rollbackExactSideEffect()
            } catch (_: Throwable) {
                false
            }
            val terminated = try {
                terminateAndJoinBounded()
            } catch (_: Throwable) {
                false
            }
            if (!rolledBack || !terminated) return false
            false
        }
    }
}

/*
 * Intentionally private: only the Android PowerManager adapter in this file is a production issuer.
 * Unit fixtures reflectively instantiate this seam to exercise downstream transitions; those
 * instances carry no platform port and are not Android-adapter evidence.
 */
object ProcessThermalSafetyBoundary {
private sealed interface ThermalCommand {
    data class Callback(val status: Int) : ThermalCommand

    data class Endpoint(
        val measurement: IssuedRecoveryThermalMeasurement,
        val endpoint: ThermalEndpoint,
        val status: Int,
    ) : ThermalCommand

    data class Cutoff(
        val cutoff: IssuedRecoveryThermalCutoff,
    ) : ThermalCommand
}

private class IssuedProcessThermalSafetyMonitor private constructor(
    val issuerIdentity: Any,
    val processGeneration: Long,
    val currentStatusReader: IntSupplier,
    val drainExecutor: Executor,
) : ProcessThermalSafetyMonitor {
    val queue = ArrayDeque<ThermalCommand>()
    var state = ThermalMonitorState.OPEN
    var safetyFailureLatched = false
    var callbackCommandsInSystem = 0
    var drainScheduled = false
    var nextMeasurementOrdinal = 1L
    var activeMeasurement: IssuedRecoveryThermalMeasurement? = null
    var platformPort: ThermalPlatformPort? = null
    var registrationConfirmed = false
    var nanoTimeReader: LongSupplier = LongSupplier { System.nanoTime() }

    companion object {
        fun issue(
            issuerIdentity: Any,
            processGeneration: Long,
            currentStatusReader: IntSupplier,
            drainExecutor: Executor,
        ): IssuedProcessThermalSafetyMonitor =
            IssuedProcessThermalSafetyMonitor(
                issuerIdentity = issuerIdentity,
                processGeneration = processGeneration,
                currentStatusReader = currentStatusReader,
                drainExecutor = drainExecutor,
            )
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private class AndroidThermalPlatformPort(
    private val powerManager: PowerManager,
    private val listener: PowerManager.OnThermalStatusChangedListener,
    private val callbackExecutor: Executor,
    private val drainExecutor: ExecutorService,
) : ThermalPlatformPort {
    private val closed = AtomicBoolean(false)
    private val registrationAttempted = AtomicBoolean(false)

    override fun register(): Boolean {
        if (!registrationAttempted.compareAndSet(false, true)) return false
        return SideEffectRegistrationRollbackBoundary.register(
            registerMayHaveSideEffect = {
                powerManager.addThermalStatusListener(callbackExecutor, listener)
            },
            rollbackExactSideEffect = {
                closed.set(true)
                removeListenerExact()
            },
            terminateAndJoinBounded = ::drainAndTerminateBounded,
        )
    }

    override fun removeListenerAndDrainBounded(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        // Do not short-circuit: worker drain/join is mandatory even if removal throws.
        val removed = removeListenerExact()
        val terminated = drainAndTerminateBounded()
        return removed && terminated && registrationAttempted.get()
    }

    private fun removeListenerExact(): Boolean =
        try {
            powerManager.removeThermalStatusListener(listener)
            true
        } catch (_: Throwable) {
            false
        }

    private fun drainAndTerminateBounded(): Boolean {
        val barrier = CountDownLatch(1)
        val barrierSubmitted = try {
            drainExecutor.execute { barrier.countDown() }
            true
        } catch (_: Throwable) {
            false
        }
        val drained = try {
            barrierSubmitted &&
                barrier.await(PLATFORM_DRAIN_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        drainExecutor.shutdown()
        val terminated = try {
            drainExecutor.awaitTermination(
                PLATFORM_DRAIN_DEADLINE_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!terminated) drainExecutor.shutdownNow()
        return drained && terminated
    }

    private companion object {
        const val PLATFORM_DRAIN_DEADLINE_MS = 1_000L
    }
}

private class IssuedRecoveryThermalMeasurement(
    val issuerIdentity: Any,
    val monitor: IssuedProcessThermalSafetyMonitor,
    val processGeneration: Long,
    val resourceGeneration: Long,
    val ordinal: Long,
) : RecoveryThermalMeasurement {
    val histogram = IntArray(ProcessThermalSafetyBoundary.STATUS_BIN_COUNT)
    var endpointMask = 0
    var observationCount = 0
    var maximumStatus = -1
    var cutoffQueued = false
    var frozen = false
    var failed = false
}

private class IssuedRecoveryThermalCutoff(
    val issuerIdentity: Any,
    val measurement: IssuedRecoveryThermalMeasurement,
    val processGeneration: Long,
    val resourceGeneration: Long,
    val snapshotReturnedAtNs: Long,
    val deadlineNs: Long,
) : RecoveryThermalCutoff {
    val consumed = AtomicBoolean(false)
    var frozenAtNs = -1L
}

private class IssuedUnavailableApiThermalMonitor(
    val issuerIdentity: Any,
    val processGeneration: Long,
    val apiLevel: Int,
) : ProcessThermalSafetyMonitor {
    var activeMeasurement: IssuedUnavailableApiThermalMeasurement? = null
    var shuttingDown = false
    var failed = false
}

private class IssuedUnavailableApiThermalMeasurement(
    val issuerIdentity: Any,
    val monitor: IssuedUnavailableApiThermalMonitor,
    val processGeneration: Long,
    val resourceGeneration: Long,
) : RecoveryThermalMeasurement {
    var cutoffIssued = false
}

private class IssuedUnavailableApiThermalCutoff(
    val issuerIdentity: Any,
    val measurement: IssuedUnavailableApiThermalMeasurement,
    val processGeneration: Long,
    val resourceGeneration: Long,
) : RecoveryThermalCutoff {
    val consumed = AtomicBoolean(false)
}

/**
 * Process-lifetime thermal FIFO specified by ADR-011.
 *
 * API29+ uses the application PowerManager listener and synchronous current-status reader bound by
 * [ownApplicationProcess]. API26-28 uses the distinct exact-unavailable path below.
 */
    internal const val CALLBACK_CAPACITY: Int = 64
    internal const val STATUS_BIN_COUNT: Int = 7
    internal const val MAXIMUM_NONCRITICAL_STATUS: Int = 3
    internal const val FIRST_CRITICAL_STATUS: Int = 4

    private val issuerIdentity = Any()
    private val directExecutor = Executor { command -> command.run() }
    private val nextProcessGeneration = AtomicLong(1L)
    private val nextUnavailableProcessGeneration = AtomicLong(1L)
    /**
     * Process-global and absorbing. A listener attach can take effect before throwing, and a failed
     * inverse cannot prove absence. No later monitor or native-create gate may treat that process
     * as clean, even if a second platform call appears to succeed.
     */
    private val applicationRegistrationSafetyFailure = AtomicBoolean(false)
    private var applicationProcessMonitor: IssuedProcessThermalSafetyMonitor? = null
    private var unavailableProcessMonitor: IssuedUnavailableApiThermalMonitor? = null
    private var applicationRegistrationInFlight = false

    /**
     * Installs the one process-lifetime API29+ listener. Synchronous endpoint reads, asynchronous
     * callbacks, and the FIFO barrier are all bound to this same PowerManager owner.
     */
    fun ownApplicationProcess(context: Context): ProcessThermalSafetyMonitor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        synchronized(this) {
            if (applicationRegistrationSafetyFailure.get()) return null
            applicationProcessMonitor?.let { existing ->
                if (existing.state == ThermalMonitorState.OPEN &&
                    !existing.safetyFailureLatched &&
                    existing.platformPort != null
                ) {
                    return existing
                }
                return null
            }
            if (applicationRegistrationInFlight) return null
            applicationRegistrationInFlight = true
        }
        try {
            val applicationContext = context.applicationContext ?: return null
            val powerManager =
                applicationContext.getSystemService(PowerManager::class.java)
                    ?: (applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?: return null
            val generation = allocateProcessGeneration() ?: return null
            val drainExecutor =
                Executors.newSingleThreadExecutor { command ->
                    Thread(command, "motion-thermal-fifo").apply { isDaemon = true }
                }
            val monitor =
                IssuedProcessThermalSafetyMonitor.issue(
                    issuerIdentity = issuerIdentity,
                    processGeneration = generation,
                    currentStatusReader = IntSupplier { powerManager.currentThermalStatus },
                    drainExecutor = drainExecutor,
                )
            val listener =
                PowerManager.OnThermalStatusChangedListener { status ->
                    try {
                        executeStatusCallback(monitor, status)
                    } catch (_: RejectedExecutionException) {
                        // Invalid, critical, and overflow callbacks already latch failure.
                    }
                }
            val platformPort =
                AndroidThermalPlatformPort(
                    powerManager = powerManager,
                    listener = listener,
                    callbackExecutor = directExecutor,
                    drainExecutor = drainExecutor,
                )
            monitor.platformPort = platformPort
            if (!platformPort.register()) {
                latchApplicationRegistrationFailure(monitor)
                return null
            }
            val registrationSafe = synchronized(monitor) {
                if (monitor.state == ThermalMonitorState.OPEN && !monitor.safetyFailureLatched) {
                    monitor.registrationConfirmed = true
                    true
                } else {
                    false
                }
            }
            if (!registrationSafe) {
                platformPort.removeListenerAndDrainBounded()
                latchApplicationRegistrationFailure(monitor)
                return null
            }
            val published = synchronized(this) {
                if (applicationProcessMonitor == null) {
                    applicationProcessMonitor = monitor
                    true
                } else {
                    false
                }
            }
            if (!published) {
                synchronized(monitor) {
                    monitor.registrationConfirmed = false
                    failLocked(monitor)
                }
                platformPort.removeListenerAndDrainBounded()
                latchApplicationRegistrationFailure(monitor)
                return null
            }
            return monitor
        } finally {
            synchronized(this) { applicationRegistrationInFlight = false }
        }
    }

    /**
     * Genuine API26-28 exception path. It records no fabricated status or histogram. The actual
     * process SDK is checked both here and again when the cutoff is consumed, so this authority can
     * never substitute for the API29+ listener/current-status FIFO.
     */
    fun unavailableForCurrentApi(): ProcessThermalSafetyMonitor? {
        val apiLevel = Build.VERSION.SDK_INT
        if (apiLevel !in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API) return null
        synchronized(this) {
            if (applicationRegistrationSafetyFailure.get()) return null
            unavailableProcessMonitor?.takeIf { it.apiLevel == apiLevel }?.let { return it }
            val generation = nextUnavailableProcessGeneration.getAndIncrement()
            if (generation <= 0L) return null
            return IssuedUnavailableApiThermalMonitor(
                issuerIdentity = issuerIdentity,
                processGeneration = generation,
                apiLevel = apiLevel,
            ).also { unavailableProcessMonitor = it }
        }
    }

    /**
     * Supplies the one synchronous gate invoked immediately before native create. The external
     * reader runs without an app-owned monitor lock and the final FIFO/latch validation follows it.
     */
    internal fun isCurrentSafeForNativeCreate(
        monitor: ProcessThermalSafetyMonitor,
    ): Boolean {
        if (applicationRegistrationSafetyFailure.get()) return false
        genuineUnavailableMonitor(monitor)?.let { unavailable ->
            return synchronized(unavailable) {
                !unavailable.failed && !unavailable.shuttingDown &&
                    unavailable.activeMeasurement == null &&
                    Build.VERSION.SDK_INT == unavailable.apiLevel &&
                    Build.VERSION.SDK_INT in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API
            }
        }
        val issued = genuineMonitor(monitor) ?: return false
        if (readCurrentStatusOrFail(issued) == null) return false
        return synchronized(issued) {
            issued.registrationConfirmed && issued.state == ThermalMonitorState.OPEN &&
                !issued.safetyFailureLatched && issued.activeMeasurement == null &&
                issued.queue.isEmpty() && issued.callbackCommandsInSystem == 0 &&
                !issued.drainScheduled
        }
    }

    fun beginMeasurement(
        monitor: ProcessThermalSafetyMonitor,
        resourceGeneration: Long,
    ): RecoveryThermalMeasurement? {
        if (applicationRegistrationSafetyFailure.get()) return null
        val unavailable = genuineUnavailableMonitor(monitor)
        if (unavailable != null) {
            if (resourceGeneration <= 0L ||
                Build.VERSION.SDK_INT != unavailable.apiLevel ||
                Build.VERSION.SDK_INT !in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API
            ) {
                return null
            }
            synchronized(unavailable) {
                if (unavailable.failed || unavailable.shuttingDown ||
                    unavailable.activeMeasurement != null
                ) {
                    return null
                }
                return IssuedUnavailableApiThermalMeasurement(
                    issuerIdentity = issuerIdentity,
                    monitor = unavailable,
                    processGeneration = unavailable.processGeneration,
                    resourceGeneration = resourceGeneration,
                ).also { unavailable.activeMeasurement = it }
            }
        }
        val issued = genuineMonitor(monitor) ?: return null
        if (resourceGeneration <= 0L) return null
        val startStatus = readCurrentStatusOrFail(issued) ?: return null
        val measurement: IssuedRecoveryThermalMeasurement
        val scheduleDrain: Boolean
        synchronized(issued) {
            if (issued.state != ThermalMonitorState.OPEN ||
                issued.safetyFailureLatched ||
                !issued.registrationConfirmed ||
                issued.activeMeasurement != null ||
                issued.nextMeasurementOrdinal <= 0L ||
                issued.nextMeasurementOrdinal == Long.MAX_VALUE
            ) {
                return null
            }
            measurement =
                IssuedRecoveryThermalMeasurement(
                    issuerIdentity = issuerIdentity,
                    monitor = issued,
                    processGeneration = issued.processGeneration,
                    resourceGeneration = resourceGeneration,
                    ordinal = issued.nextMeasurementOrdinal++,
                )
            issued.activeMeasurement = measurement
            issued.queue.addLast(
                ThermalCommand.Endpoint(measurement, ThermalEndpoint.START, startStatus),
            )
            scheduleDrain = reserveDrainRunnerLocked(issued)
        }
        if (scheduleDrain && !submitDrainRunner(issued)) return null
        return measurement
    }

    /** Exact rollback for a coordinator begin that failed before its runtime claim committed. */
    internal fun abortMeasurement(
        monitor: ProcessThermalSafetyMonitor,
        measurement: RecoveryThermalMeasurement,
    ): Boolean {
        val unavailable = genuineUnavailableMonitor(monitor)
        val unavailableMeasurement = measurement as? IssuedUnavailableApiThermalMeasurement
        if (unavailable != null && unavailableMeasurement != null) {
            synchronized(unavailable) {
                if (unavailable.activeMeasurement !== unavailableMeasurement ||
                    unavailableMeasurement.cutoffIssued
                ) {
                    return false
                }
                unavailable.activeMeasurement = null
                return true
            }
        }
        val issued = genuineMonitor(monitor) ?: return false
        val window = genuineMeasurement(measurement, issued) ?: return false
        synchronized(issued) {
            if (issued.activeMeasurement !== window || window.cutoffQueued || window.frozen) {
                return false
            }
            window.failed = true
            issued.activeMeasurement = null
            return true
        }
    }

    /** Entry point for the future PowerManager listener executor. The 65th command fails closed. */
    @Throws(RejectedExecutionException::class)
    fun executeStatusCallback(
        monitor: ProcessThermalSafetyMonitor,
        status: Int,
    ) {
        genuineUnavailableMonitor(monitor)?.let { unavailable ->
            synchronized(unavailable) {
                if (unavailable.shuttingDown) return
                unavailable.failed = true
            }
            throw RejectedExecutionException("thermal callback is invalid on API26-28")
        }
        val issued = genuineMonitor(monitor)
            ?: throw RejectedExecutionException("unverified thermal monitor")
        val scheduleDrain: Boolean
        synchronized(issued) {
            if (issued.state == ThermalMonitorState.FAILED) return
            if (issued.state == ThermalMonitorState.SHUTTING_DOWN) return
            if (status !in 0 until STATUS_BIN_COUNT || status >= FIRST_CRITICAL_STATUS) {
                failLocked(issued)
                throw RejectedExecutionException("critical or invalid thermal callback")
            }
            if (issued.callbackCommandsInSystem >= CALLBACK_CAPACITY) {
                failLocked(issued)
                throw RejectedExecutionException("thermal callback FIFO capacity exhausted")
            }
            issued.callbackCommandsInSystem += 1
            issued.queue.addLast(ThermalCommand.Callback(status))
            scheduleDrain = reserveDrainRunnerLocked(issued)
        }
        if (scheduleDrain && !submitDrainRunner(issued)) {
            throw RejectedExecutionException("thermal drain runner rejected")
        }
    }

    fun endMeasurement(
        monitor: ProcessThermalSafetyMonitor,
        measurement: RecoveryThermalMeasurement,
    ): RecoveryThermalCutoff? {
        val unavailable = genuineUnavailableMonitor(monitor)
        val unavailableMeasurement = measurement as? IssuedUnavailableApiThermalMeasurement
        if (unavailable != null && unavailableMeasurement != null) {
            synchronized(unavailable) {
                if (unavailableMeasurement.javaClass !=
                        IssuedUnavailableApiThermalMeasurement::class.java ||
                    unavailableMeasurement.issuerIdentity !== issuerIdentity ||
                    unavailableMeasurement.monitor !== unavailable ||
                    unavailable.activeMeasurement !== unavailableMeasurement ||
                    unavailableMeasurement.processGeneration != unavailable.processGeneration ||
                    unavailableMeasurement.resourceGeneration <= 0L ||
                    unavailableMeasurement.cutoffIssued ||
                    unavailable.failed ||
                    unavailable.shuttingDown ||
                    Build.VERSION.SDK_INT != unavailable.apiLevel ||
                    Build.VERSION.SDK_INT !in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API
                ) {
                    return null
                }
                unavailableMeasurement.cutoffIssued = true
                return IssuedUnavailableApiThermalCutoff(
                    issuerIdentity = issuerIdentity,
                    measurement = unavailableMeasurement,
                    processGeneration = unavailable.processGeneration,
                    resourceGeneration = unavailableMeasurement.resourceGeneration,
                )
            }
        }
        val issued = genuineMonitor(monitor) ?: return null
        val window = genuineMeasurement(measurement, issued) ?: return null
        val endStatus = readCurrentStatusOrFail(issued) ?: return null
        val snapshotReturnedAtNs = readNanoTimeOrFail(issued) ?: return null
        val cutoffDeadlineNs = try {
            Math.addExact(snapshotReturnedAtNs, THERMAL_CUTOFF_DEADLINE_NS)
        } catch (_: ArithmeticException) {
            latchFailure(issued)
            return null
        }
        val cutoff: IssuedRecoveryThermalCutoff
        val scheduleDrain: Boolean
        synchronized(issued) {
            if (issued.state != ThermalMonitorState.OPEN ||
                issued.safetyFailureLatched ||
                !issued.registrationConfirmed ||
                issued.activeMeasurement !== window ||
                window.failed ||
                window.cutoffQueued ||
                window.frozen
            ) {
                return null
            }
            cutoff =
                IssuedRecoveryThermalCutoff(
                    issuerIdentity = issuerIdentity,
                    measurement = window,
                    processGeneration = issued.processGeneration,
                    resourceGeneration = window.resourceGeneration,
                    snapshotReturnedAtNs = snapshotReturnedAtNs,
                    deadlineNs = cutoffDeadlineNs,
                )
            window.cutoffQueued = true
            // Both commands are appended in one gate transition behind every admitted callback.
            issued.queue.addLast(
                ThermalCommand.Endpoint(window, ThermalEndpoint.END, endStatus),
            )
            issued.queue.addLast(ThermalCommand.Cutoff(cutoff))
            scheduleDrain = reserveDrainRunnerLocked(issued)
        }
        if (scheduleDrain && !submitDrainRunner(issued)) return null
        return cutoff
    }

    /** Best-effort process shutdown never authorizes journal or store mutation. */
    fun beginProcessShutdown(monitor: ProcessThermalSafetyMonitor): Boolean {
        genuineUnavailableMonitor(monitor)?.let { unavailable ->
            synchronized(unavailable) {
                if (unavailable.failed) return false
                unavailable.shuttingDown = true
                return true
            }
        }
        val issued = genuineMonitor(monitor) ?: return false
        val platformPort: ThermalPlatformPort?
        synchronized(issued) {
            if (issued.state == ThermalMonitorState.FAILED) return false
            if (issued.state == ThermalMonitorState.SHUTTING_DOWN) return false
            if (!issued.registrationConfirmed) return false
            issued.state = ThermalMonitorState.SHUTTING_DOWN
            platformPort = issued.platformPort
        }
        if (platformPort != null && !platformPort.removeListenerAndDrainBounded()) {
            latchApplicationRegistrationFailure(issued)
            latchFailure(issued)
            return false
        }
        return true
    }

    internal fun consumeCurrentSafeCutoff(
        monitor: ProcessThermalSafetyMonitor,
        measurement: RecoveryThermalMeasurement,
        cutoff: RecoveryThermalCutoff,
        resourceGeneration: Long,
    ): Boolean {
        val unavailable = genuineUnavailableMonitor(monitor)
        val unavailableMeasurement = measurement as? IssuedUnavailableApiThermalMeasurement
        val unavailableCutoff = cutoff as? IssuedUnavailableApiThermalCutoff
        if (unavailable != null &&
            unavailableMeasurement != null &&
            unavailableCutoff != null
        ) {
            synchronized(unavailable) {
                if (unavailableMeasurement.javaClass !=
                        IssuedUnavailableApiThermalMeasurement::class.java ||
                    unavailableCutoff.javaClass != IssuedUnavailableApiThermalCutoff::class.java ||
                    unavailableMeasurement.issuerIdentity !== issuerIdentity ||
                    unavailableCutoff.issuerIdentity !== issuerIdentity ||
                    unavailableMeasurement.monitor !== unavailable ||
                    unavailable.activeMeasurement !== unavailableMeasurement ||
                    unavailableCutoff.measurement !== unavailableMeasurement ||
                    unavailableMeasurement.processGeneration != unavailable.processGeneration ||
                    unavailableCutoff.processGeneration != unavailable.processGeneration ||
                    unavailableMeasurement.resourceGeneration != resourceGeneration ||
                    unavailableCutoff.resourceGeneration != resourceGeneration ||
                    !unavailableMeasurement.cutoffIssued ||
                    unavailable.failed ||
                    unavailable.shuttingDown ||
                    Build.VERSION.SDK_INT != unavailable.apiLevel ||
                    Build.VERSION.SDK_INT !in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API
                ) {
                    return false
                }
                if (!unavailableCutoff.consumed.compareAndSet(false, true)) return false
                unavailable.activeMeasurement = null
                return true
            }
        }
        val issued = genuineMonitor(monitor) ?: return false
        val window = genuineMeasurement(measurement, issued) ?: return false
        val exactCutoff = cutoff as? IssuedRecoveryThermalCutoff ?: return false
        if (exactCutoff.javaClass != IssuedRecoveryThermalCutoff::class.java ||
            exactCutoff.issuerIdentity !== issuerIdentity ||
            exactCutoff.measurement !== window ||
            exactCutoff.processGeneration != issued.processGeneration ||
            exactCutoff.resourceGeneration != resourceGeneration ||
            window.resourceGeneration != resourceGeneration
        ) {
            return false
        }
        // PowerManager/current-status readers are external and may synchronously re-enter the
        // callback executor. The read therefore occurs before acquiring the app-owned gate.
        if (readCurrentStatusOrFail(issued) == null) return false
        synchronized(issued) {
            if (issued.state != ThermalMonitorState.OPEN ||
                issued.safetyFailureLatched ||
                !issued.registrationConfirmed ||
                issued.queue.isNotEmpty() ||
                issued.callbackCommandsInSystem != 0 ||
                issued.drainScheduled ||
                issued.activeMeasurement != null ||
                !window.frozen ||
                exactCutoff.frozenAtNs < exactCutoff.snapshotReturnedAtNs ||
                exactCutoff.frozenAtNs > exactCutoff.deadlineNs ||
                window.failed ||
                window.endpointMask != REQUIRED_ENDPOINT_MASK ||
                window.observationCount < 2 ||
                window.maximumStatus !in 0..MAXIMUM_NONCRITICAL_STATUS
            ) {
                return false
            }
            return exactCutoff.consumed.compareAndSet(false, true)
        }
    }

    internal enum class LiveAuthorityConsumeResult {
        CONSUMED,
        UNSAFE,
        ALREADY_CONSUMED,
    }

    /**
     * Reads current status outside the app gate, then revalidates the FIFO/latch and consumes at
     * the gate. A reader that synchronously re-enters callback admission is observed by that final
     * validation instead of deadlocking under an app-owned monitor.
     */
    internal fun consumeAuthorityIfCurrentSafe(
        monitor: ProcessThermalSafetyMonitor,
        authorityConsumed: AtomicBoolean,
    ): LiveAuthorityConsumeResult {
        genuineUnavailableMonitor(monitor)?.let { unavailable ->
            synchronized(unavailable) {
                if (unavailable.failed ||
                    unavailable.shuttingDown ||
                    unavailable.activeMeasurement != null ||
                    Build.VERSION.SDK_INT != unavailable.apiLevel ||
                    Build.VERSION.SDK_INT !in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API
                ) {
                    return LiveAuthorityConsumeResult.UNSAFE
                }
                return if (authorityConsumed.compareAndSet(false, true)) {
                    LiveAuthorityConsumeResult.CONSUMED
                } else {
                    LiveAuthorityConsumeResult.ALREADY_CONSUMED
                }
            }
        }
        val issued = genuineMonitor(monitor) ?: return LiveAuthorityConsumeResult.UNSAFE
        if (readCurrentStatusOrFail(issued) == null) {
            return LiveAuthorityConsumeResult.UNSAFE
        }
        synchronized(issued) {
            if (issued.state != ThermalMonitorState.OPEN ||
                issued.safetyFailureLatched ||
                !issued.registrationConfirmed ||
                issued.queue.isNotEmpty() ||
                issued.callbackCommandsInSystem != 0 ||
                issued.drainScheduled ||
                issued.activeMeasurement != null
            ) {
                return LiveAuthorityConsumeResult.UNSAFE
            }
            return if (authorityConsumed.compareAndSet(false, true)) {
                LiveAuthorityConsumeResult.CONSUMED
            } else {
                LiveAuthorityConsumeResult.ALREADY_CONSUMED
            }
        }
    }

    private fun genuineMonitor(
        candidate: ProcessThermalSafetyMonitor,
    ): IssuedProcessThermalSafetyMonitor? {
        val issued = candidate as? IssuedProcessThermalSafetyMonitor ?: return null
        return if (issued.javaClass == IssuedProcessThermalSafetyMonitor::class.java &&
            issued.issuerIdentity === issuerIdentity &&
            issued.processGeneration > 0L
        ) {
            issued
        } else {
            null
        }
    }

    private fun genuineUnavailableMonitor(
        candidate: ProcessThermalSafetyMonitor,
    ): IssuedUnavailableApiThermalMonitor? {
        val issued = candidate as? IssuedUnavailableApiThermalMonitor ?: return null
        return if (issued.javaClass == IssuedUnavailableApiThermalMonitor::class.java &&
            issued.issuerIdentity === issuerIdentity &&
            issued.processGeneration > 0L &&
            issued.apiLevel in MINIMUM_UNAVAILABLE_API..MAXIMUM_UNAVAILABLE_API
        ) {
            issued
        } else {
            null
        }
    }

    private fun genuineMeasurement(
        candidate: RecoveryThermalMeasurement,
        monitor: IssuedProcessThermalSafetyMonitor,
    ): IssuedRecoveryThermalMeasurement? {
        val issued = candidate as? IssuedRecoveryThermalMeasurement ?: return null
        return if (issued.javaClass == IssuedRecoveryThermalMeasurement::class.java &&
            issued.issuerIdentity === issuerIdentity &&
            issued.monitor === monitor &&
            issued.processGeneration == monitor.processGeneration &&
            issued.resourceGeneration > 0L &&
            issued.ordinal > 0L
        ) {
            issued
        } else {
            null
        }
    }

    private fun readCurrentStatusOrFail(
        monitor: IssuedProcessThermalSafetyMonitor,
    ): Int? {
        val status = try {
            monitor.currentStatusReader.asInt
        } catch (_: Throwable) {
            latchFailure(monitor)
            return null
        }
        if (status !in 0 until STATUS_BIN_COUNT || status >= FIRST_CRITICAL_STATUS) {
            latchFailure(monitor)
            return null
        }
        return status
    }

    private fun readNanoTimeOrFail(monitor: IssuedProcessThermalSafetyMonitor): Long? {
        val captured = try {
            monitor.nanoTimeReader.asLong
        } catch (_: Throwable) {
            latchFailure(monitor)
            return null
        }
        if (captured < 0L) {
            latchFailure(monitor)
            return null
        }
        return captured
    }

    private fun reserveDrainRunnerLocked(monitor: IssuedProcessThermalSafetyMonitor): Boolean {
        if (monitor.drainScheduled) return false
        monitor.drainScheduled = true
        return true
    }

    private fun submitDrainRunner(monitor: IssuedProcessThermalSafetyMonitor): Boolean =
        try {
            monitor.drainExecutor.execute { drain(monitor) }
            true
        } catch (_: Throwable) {
            latchFailure(monitor)
            false
        }

    private fun drain(monitor: IssuedProcessThermalSafetyMonitor) {
        while (true) {
            val command = synchronized(monitor) {
                if (monitor.queue.isEmpty()) {
                    monitor.drainScheduled = false
                    return
                }
                monitor.queue.removeFirst()
            }
            try {
                process(monitor, command)
            } catch (_: Throwable) {
                latchFailure(monitor)
            } finally {
                if (command is ThermalCommand.Callback) {
                    synchronized(monitor) {
                        if (monitor.callbackCommandsInSystem <= 0) {
                            failLocked(monitor)
                        } else {
                            monitor.callbackCommandsInSystem -= 1
                        }
                    }
                }
            }
        }
    }

    private fun process(
        monitor: IssuedProcessThermalSafetyMonitor,
        command: ThermalCommand,
    ) {
        when (command) {
            is ThermalCommand.Callback -> {
                if (command.status !in 0 until STATUS_BIN_COUNT ||
                    command.status >= FIRST_CRITICAL_STATUS
                ) {
                    latchFailure(monitor)
                    return
                }
                synchronized(monitor) {
                    val active = monitor.activeMeasurement
                    if (monitor.state == ThermalMonitorState.OPEN &&
                        !monitor.safetyFailureLatched &&
                        active != null &&
                        !active.frozen
                    ) {
                        observeLocked(active, command.status)
                    }
                }
            }

            is ThermalCommand.Endpoint -> synchronized(monitor) {
                val measurement = command.measurement
                if (monitor.state != ThermalMonitorState.OPEN ||
                    monitor.safetyFailureLatched ||
                    monitor.activeMeasurement !== measurement ||
                    measurement.failed ||
                    measurement.frozen
                ) {
                    return@synchronized
                }
                val bit = when (command.endpoint) {
                    ThermalEndpoint.START -> START_ENDPOINT_BIT
                    ThermalEndpoint.END -> END_ENDPOINT_BIT
                }
                if ((measurement.endpointMask and bit) != 0) {
                    failLocked(monitor)
                    return@synchronized
                }
                measurement.endpointMask = measurement.endpointMask or bit
                observeLocked(measurement, command.status)
            }

            is ThermalCommand.Cutoff -> {
                val reachedAtNs = readNanoTimeOrFail(monitor) ?: return
                synchronized(monitor) {
                    val cutoff = command.cutoff
                    val measurement = cutoff.measurement
                    if (monitor.state != ThermalMonitorState.OPEN ||
                        monitor.safetyFailureLatched ||
                        monitor.activeMeasurement !== measurement ||
                        measurement.failed ||
                        measurement.frozen ||
                        cutoff.snapshotReturnedAtNs < 0L ||
                        cutoff.deadlineNs < cutoff.snapshotReturnedAtNs ||
                        reachedAtNs < cutoff.snapshotReturnedAtNs ||
                        reachedAtNs > cutoff.deadlineNs ||
                        measurement.endpointMask != REQUIRED_ENDPOINT_MASK ||
                        measurement.observationCount < 2 ||
                        measurement.maximumStatus !in 0..MAXIMUM_NONCRITICAL_STATUS
                    ) {
                        failLocked(monitor)
                        return@synchronized
                    }
                    cutoff.frozenAtNs = reachedAtNs
                    measurement.frozen = true
                    monitor.activeMeasurement = null
                }
            }
        }
    }

    private fun observeLocked(
        measurement: IssuedRecoveryThermalMeasurement,
        status: Int,
    ) {
        if (status !in 0 until STATUS_BIN_COUNT || status >= FIRST_CRITICAL_STATUS ||
            measurement.observationCount == Int.MAX_VALUE ||
            measurement.histogram[status] == Int.MAX_VALUE
        ) {
            failLocked(measurement.monitor)
            return
        }
        measurement.histogram[status] += 1
        measurement.observationCount += 1
        measurement.maximumStatus = maxOf(measurement.maximumStatus, status)
    }

    private fun latchFailure(monitor: IssuedProcessThermalSafetyMonitor) {
        synchronized(monitor) { failLocked(monitor) }
    }

    private fun failLocked(monitor: IssuedProcessThermalSafetyMonitor) {
        monitor.safetyFailureLatched = true
        monitor.state = ThermalMonitorState.FAILED
        monitor.activeMeasurement?.failed = true
    }

    private fun allocateProcessGeneration(): Long? {
        while (true) {
            val current = nextProcessGeneration.get()
            if (current <= 0L || current == Long.MAX_VALUE) return null
            if (nextProcessGeneration.compareAndSet(current, current + 1L)) return current
        }
    }

    private fun latchApplicationRegistrationFailure(
        affected: IssuedProcessThermalSafetyMonitor? = null,
    ) {
        applicationRegistrationSafetyFailure.set(true)
        val published = synchronized(this) { applicationProcessMonitor }
        listOfNotNull(affected, published).distinctBy { System.identityHashCode(it) }.forEach { monitor ->
            synchronized(monitor) {
                monitor.registrationConfirmed = false
                failLocked(monitor)
            }
        }
        synchronized(this) {
            unavailableProcessMonitor?.failed = true
        }
    }

    private const val START_ENDPOINT_BIT = 0x01
    private const val END_ENDPOINT_BIT = 0x02
    private const val REQUIRED_ENDPOINT_MASK = START_ENDPOINT_BIT or END_ENDPOINT_BIT
    private const val MINIMUM_UNAVAILABLE_API = 26
    private const val MAXIMUM_UNAVAILABLE_API = 28
    internal const val THERMAL_CUTOFF_DEADLINE_NS = 1_000_000_000L
}

/** Opaque public-FrameMetrics owner backed by one Window and dedicated HandlerThread. */
sealed interface RecoveryRenderOwner

/** Exact non-authoritative closed receipt; it never claims a native render-thread fence. */
sealed interface RecoveryRenderClosure

/** Platform seam for the dedicated Window/Handler adapter. */
internal interface RecoveryRenderPlatformPort {
    /** Must return only after Window listener removal returned. */
    fun removeListener()

    /** Must drain/discard the dedicated Handler through its bounded deadline or throw. */
    fun drainDiscardThroughDeadline()
}

/** Testable operations behind the exact HandlerThread used by the production FrameMetrics port. */
internal interface RecoveryRenderDrainOperations {
    fun isCurrentLooper(): Boolean

    fun postBarrier(signal: () -> Unit): Boolean

    fun quitSafely(): Boolean

    @Throws(InterruptedException::class)
    fun joinBounded(timeoutMillis: Long)

    fun isAlive(): Boolean

    fun interruptTarget()

    fun nanoTime(): Long
}

/**
 * One fail-closed drain algorithm shared by every production exit. Even a primary failure still
 * runs termination and bounded join; a live or unconfirmable worker is never accepted.
 */
internal class RecoveryRenderDrainController(
    private val operations: RecoveryRenderDrainOperations,
    private val deadlineMillis: Long,
) {
    fun drainDiscardThroughDeadline() {
        val startedAt = operations.nanoTime()
        var primaryFailure: Throwable? = null
        var restoreInterrupt = false
        try {
            if (operations.isCurrentLooper()) {
                throw IllegalStateException("render queue cannot drain itself")
            }
            val barrier = CountDownLatch(1)
            if (!operations.postBarrier { barrier.countDown() }) {
                throw IllegalStateException("render queue barrier post was rejected")
            }
            if (!barrier.await(deadlineMillis, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("render queue barrier deadline expired")
            }
            if (!operations.quitSafely()) {
                throw IllegalStateException("render handler quit was rejected")
            }
            operations.joinBounded(remainingMillis(startedAt))
            if (operations.isAlive()) {
                throw IllegalStateException("render handler termination deadline expired")
            }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                restoreInterrupt = true
                Thread.interrupted()
            }
            primaryFailure = failure
        } finally {
            val terminated = terminateAndJoinBounded()
            if (!terminated && primaryFailure == null) {
                primaryFailure =
                    IllegalStateException("render handler final termination was unconfirmed")
            }
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
        primaryFailure?.let { failure ->
            throw IllegalStateException("render queue drain failed closed", failure)
        }
    }

    fun terminateAndJoinBounded(): Boolean {
        var restoreInterrupt = Thread.interrupted()
        try {
            try {
                operations.quitSafely()
            } catch (_: Throwable) {
                // Join still determines whether termination is certain.
            }
            if (operations.isCurrentLooper()) return false
            try {
                operations.joinBounded(deadlineMillis)
            } catch (_: InterruptedException) {
                restoreInterrupt = true
                Thread.interrupted()
            } catch (_: Throwable) {
                // A target interrupt plus one final bounded join is still required below.
            }
            if (operations.isAlive()) {
                try {
                    operations.interruptTarget()
                } catch (_: Throwable) {
                    return false
                }
                try {
                    operations.joinBounded(deadlineMillis)
                } catch (_: InterruptedException) {
                    restoreInterrupt = true
                    Thread.interrupted()
                } catch (_: Throwable) {
                    return false
                }
            }
            return try {
                !operations.isAlive()
            } catch (_: Throwable) {
                false
            }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    private fun remainingMillis(startedAtNs: Long): Long {
        val elapsed =
            try {
                TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, Math.subtractExact(operations.nanoTime(), startedAtNs)),
                )
            } catch (_: Throwable) {
                deadlineMillis
            }
        return (deadlineMillis - elapsed).coerceAtLeast(1L)
    }
}

sealed interface RecoveryRenderCallbackAdmission {
    data object DISCARD : RecoveryRenderCallbackAdmission

    sealed interface MUTATION : RecoveryRenderCallbackAdmission
}

object RecoveryRenderOwnerBoundary {
private enum class RenderOwnerState {
    OPEN,
    CLOSING,
    CLOSED,
    FAILED,
}

/* Private so only the Window/Handler adapter (or the explicit test seam) can issue an owner. */
private class IssuedRecoveryRenderOwner private constructor(
    val issuerIdentity: Any,
    val ownerGeneration: Long,
    val platformPort: RecoveryRenderPlatformPort,
) : RecoveryRenderOwner {
    var state = RenderOwnerState.OPEN
    var claimedResourceGeneration = 0L
    var metricMutationEnabled = true
    var inFlightCallbacks = 0
    var copiedMetricCallbackCount = 0L
    var invalidMetricCallbackCount = 0L
    var reportedDropCount = 0L

    companion object {
        fun issue(
            issuerIdentity: Any,
            ownerGeneration: Long,
            platformPort: RecoveryRenderPlatformPort,
        ): IssuedRecoveryRenderOwner =
            IssuedRecoveryRenderOwner(
                issuerIdentity = issuerIdentity,
                ownerGeneration = ownerGeneration,
                platformPort = platformPort,
            )
    }
}

private class AndroidWindowRenderPlatformPort(
    private val window: Window,
    private val handlerThread: HandlerThread,
    private val handler: Handler,
    private val drainDeadlineMillis: Long,
) : RecoveryRenderPlatformPort {
    private val listenerRemoved = AtomicBoolean(false)
    private val drained = AtomicBoolean(false)
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val drainController =
        RecoveryRenderDrainController(
            object : RecoveryRenderDrainOperations {
                override fun isCurrentLooper(): Boolean =
                    Looper.myLooper() === handler.looper

                override fun postBarrier(signal: () -> Unit): Boolean = handler.post { signal() }

                override fun quitSafely(): Boolean = handlerThread.quitSafely()

                override fun joinBounded(timeoutMillis: Long) {
                    handlerThread.join(timeoutMillis)
                }

                override fun isAlive(): Boolean = handlerThread.isAlive

                override fun interruptTarget() = handlerThread.interrupt()

                override fun nanoTime(): Long = System.nanoTime()
            },
            drainDeadlineMillis,
        )

    fun bind(owner: RecoveryRenderOwner): Boolean {
        if (listener != null || listenerRemoved.get()) return false
        val exactListener =
            Window.OnFrameMetricsAvailableListener { _, metrics: FrameMetrics, dropCount ->
                // FrameMetrics is mutable/reused by the platform. Copy only bounded scalars before
                // entering any app-owned state and never retain the platform object.
                val totalDuration = try {
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                } catch (_: Throwable) {
                    INVALID_FRAME_METRIC
                }
                val intendedVsync = try {
                    metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                } catch (_: Throwable) {
                    INVALID_FRAME_METRIC
                }
                val admission =
                    RecoveryRenderOwnerBoundary.admitMetricCallback(
                        owner,
                        totalDuration,
                        intendedVsync,
                        dropCount,
                    )
                if (admission is RecoveryRenderCallbackAdmission.MUTATION) {
                    RecoveryRenderOwnerBoundary.completeMetricCallback(owner, admission)
                }
            }
        listener = exactListener
        return SideEffectRegistrationRollbackBoundary.register(
            registerMayHaveSideEffect = {
                window.addOnFrameMetricsAvailableListener(exactListener, handler)
            },
            rollbackExactSideEffect = {
                listenerRemoved.set(true)
                try {
                    window.removeOnFrameMetricsAvailableListener(exactListener)
                    true
                } catch (_: Throwable) {
                    false
                }
            },
            terminateAndJoinBounded = drainController::terminateAndJoinBounded,
        )
    }

    fun abortRegistration(): Boolean = drainController.terminateAndJoinBounded()

    override fun removeListener() {
        val exactListener = listener ?: throw IllegalStateException("render listener unavailable")
        if (!listenerRemoved.compareAndSet(false, true)) {
            throw IllegalStateException("render listener already removed")
        }
        try {
            window.removeOnFrameMetricsAvailableListener(exactListener)
        } catch (failure: Throwable) {
            drainController.terminateAndJoinBounded()
            throw failure
        }
    }

    override fun drainDiscardThroughDeadline() {
        if (!listenerRemoved.get() || !drained.compareAndSet(false, true)) {
            throw IllegalStateException("render queue drain order violated")
        }
        drainController.drainDiscardThroughDeadline()
    }

    private companion object {
        const val INVALID_FRAME_METRIC = Long.MIN_VALUE
    }
}

private class IssuedRenderMutation(
    val issuerIdentity: Any,
    val owner: IssuedRecoveryRenderOwner,
    val ownerGeneration: Long,
    val totalDurationNs: Long,
    val intendedVsyncTimestampNs: Long,
    val dropCountSinceLastInvocation: Int,
) : RecoveryRenderCallbackAdmission.MUTATION {
    val completed = AtomicBoolean(false)
}

private class IssuedRecoveryRenderClosure(
    val issuerIdentity: Any,
    val owner: IssuedRecoveryRenderOwner,
    val ownerGeneration: Long,
    val resourceGeneration: Long,
) : RecoveryRenderClosure {
    val consumed = AtomicBoolean(false)
}

/**
 * Public FrameMetrics ownership boundary. CLOSED means removal returned, metric mutation was
 * atomically disabled, and the dedicated handler adapter returned from its bounded drain. It does
 * not and cannot claim native render quiescence under capability-v15.
 */
    private val issuerIdentity = Any()
    private val nextOwnerGeneration = AtomicLong(1L)

    /** Registers the real public FrameMetrics listener on one dedicated HandlerThread. */
    fun ownWindow(
        window: Window,
        drainDeadlineMillis: Long = DEFAULT_DRAIN_DEADLINE_MILLIS,
    ): RecoveryRenderOwner? {
        if (drainDeadlineMillis !in 1L..MAXIMUM_DRAIN_DEADLINE_MILLIS) return null
        val generation = allocateOwnerGeneration() ?: return null
        val handlerThread = HandlerThread("motion-frame-metrics-$generation")
        return try {
            handlerThread.start()
            val port =
                AndroidWindowRenderPlatformPort(
                    window = window,
                    handlerThread = handlerThread,
                    handler = Handler(handlerThread.looper),
                    drainDeadlineMillis = drainDeadlineMillis,
                )
            val owner =
                IssuedRecoveryRenderOwner.issue(
                    issuerIdentity = issuerIdentity,
                    ownerGeneration = generation,
                    platformPort = port,
                )
            if (!port.bind(owner)) {
                port.abortRegistration()
                null
            } else {
                owner
            }
        } catch (_: Throwable) {
            try {
                handlerThread.quitSafely()
                handlerThread.join(drainDeadlineMillis)
            } catch (_: Throwable) {
                handlerThread.interrupt()
            }
            null
        }
    }

    fun admitMetricCallback(
        owner: RecoveryRenderOwner,
        totalDurationNs: Long = INVALID_FRAME_METRIC,
        intendedVsyncTimestampNs: Long = INVALID_FRAME_METRIC,
        dropCountSinceLastInvocation: Int = -1,
    ): RecoveryRenderCallbackAdmission {
        val issued = genuineOwner(owner) ?: return RecoveryRenderCallbackAdmission.DISCARD
        synchronized(issued) {
            if (issued.state != RenderOwnerState.OPEN || !issued.metricMutationEnabled) {
                return RecoveryRenderCallbackAdmission.DISCARD
            }
            if (issued.inFlightCallbacks == Int.MAX_VALUE) {
                issued.state = RenderOwnerState.FAILED
                issued.metricMutationEnabled = false
                return RecoveryRenderCallbackAdmission.DISCARD
            }
            issued.inFlightCallbacks += 1
            return IssuedRenderMutation(
                issuerIdentity = issuerIdentity,
                owner = issued,
                ownerGeneration = issued.ownerGeneration,
                totalDurationNs = totalDurationNs,
                intendedVsyncTimestampNs = intendedVsyncTimestampNs,
                dropCountSinceLastInvocation = dropCountSinceLastInvocation,
            )
        }
    }

    fun completeMetricCallback(
        owner: RecoveryRenderOwner,
        admission: RecoveryRenderCallbackAdmission,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val mutation = admission as? IssuedRenderMutation ?: return false
        if (mutation.javaClass != IssuedRenderMutation::class.java ||
            mutation.issuerIdentity !== issuerIdentity ||
            mutation.owner !== issued ||
            mutation.ownerGeneration != issued.ownerGeneration ||
            !mutation.completed.compareAndSet(false, true)
        ) {
            return false
        }
        synchronized(issued) {
            if (issued.inFlightCallbacks <= 0) {
                issued.state = RenderOwnerState.FAILED
                issued.metricMutationEnabled = false
                return false
            }
            issued.inFlightCallbacks -= 1
            // A callback admitted before removal may complete while close is draining. Once the
            // mutation gate is disabled, completion is cleanup-only: it releases its in-flight
            // reservation but must not change any reported/copy/invalid scalar.
            if (!issued.metricMutationEnabled) return true
            if (issued.copiedMetricCallbackCount == Long.MAX_VALUE) {
                failRenderOwner(issued)
                return false
            }
            issued.copiedMetricCallbackCount += 1L
            val validScalars =
                mutation.totalDurationNs in 0L..MAXIMUM_FRAME_METRIC_NS &&
                    mutation.intendedVsyncTimestampNs >= 0L &&
                    mutation.dropCountSinceLastInvocation >= 0
            if (!validScalars) {
                if (issued.invalidMetricCallbackCount == Long.MAX_VALUE) {
                    failRenderOwner(issued)
                    return false
                }
                issued.invalidMetricCallbackCount += 1L
            } else {
                issued.reportedDropCount = try {
                    Math.addExact(
                        issued.reportedDropCount,
                        mutation.dropCountSinceLastInvocation.toLong(),
                    )
                } catch (_: ArithmeticException) {
                    failRenderOwner(issued)
                    return false
                }
            }
            return true
        }
    }

    internal fun claimForResourceGeneration(
        owner: RecoveryRenderOwner,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        if (resourceGeneration <= 0L) return false
        synchronized(issued) {
            if (issued.state != RenderOwnerState.OPEN ||
                issued.claimedResourceGeneration != 0L
            ) {
                return false
            }
            issued.claimedResourceGeneration = resourceGeneration
            return true
        }
    }

    internal fun releaseUncommittedClaim(
        owner: RecoveryRenderOwner,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        synchronized(issued) {
            if (issued.state != RenderOwnerState.OPEN ||
                issued.claimedResourceGeneration != resourceGeneration
            ) {
                return false
            }
            issued.claimedResourceGeneration = 0L
            return true
        }
    }

    internal fun closeForResourceGeneration(
        owner: RecoveryRenderOwner,
        resourceGeneration: Long,
    ): RecoveryRenderClosure? {
        val issued = genuineOwner(owner) ?: return null
        synchronized(issued) {
            if (issued.state != RenderOwnerState.OPEN ||
                issued.claimedResourceGeneration != resourceGeneration ||
                resourceGeneration <= 0L
            ) {
                return null
            }
            issued.state = RenderOwnerState.CLOSING
        }
        try {
            issued.platformPort.removeListener()
        } catch (_: Throwable) {
            failRenderOwner(issued)
            return null
        }
        // Removal return is followed by the one authoritative metric-mutation close transition.
        synchronized(issued) {
            if (issued.state != RenderOwnerState.CLOSING) return null
            issued.metricMutationEnabled = false
        }
        try {
            issued.platformPort.drainDiscardThroughDeadline()
        } catch (_: Throwable) {
            failRenderOwner(issued)
            return null
        }
        synchronized(issued) {
            if (issued.state != RenderOwnerState.CLOSING || issued.inFlightCallbacks != 0) {
                issued.state = RenderOwnerState.FAILED
                return null
            }
            issued.state = RenderOwnerState.CLOSED
            return IssuedRecoveryRenderClosure(
                issuerIdentity = issuerIdentity,
                owner = issued,
                ownerGeneration = issued.ownerGeneration,
                resourceGeneration = resourceGeneration,
            )
        }
    }

    internal fun consumeClosed(
        owner: RecoveryRenderOwner,
        closure: RecoveryRenderClosure,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val receipt = closure as? IssuedRecoveryRenderClosure ?: return false
        synchronized(issued) {
            if (receipt.javaClass != IssuedRecoveryRenderClosure::class.java ||
                receipt.issuerIdentity !== issuerIdentity ||
                receipt.owner !== issued ||
                receipt.ownerGeneration != issued.ownerGeneration ||
                receipt.resourceGeneration != resourceGeneration ||
                issued.claimedResourceGeneration != resourceGeneration ||
                issued.state != RenderOwnerState.CLOSED ||
                issued.metricMutationEnabled ||
                issued.inFlightCallbacks != 0
            ) {
                return false
            }
            return receipt.consumed.compareAndSet(false, true)
        }
    }

    private fun genuineOwner(candidate: RecoveryRenderOwner): IssuedRecoveryRenderOwner? {
        val issued = candidate as? IssuedRecoveryRenderOwner ?: return null
        return if (issued.javaClass == IssuedRecoveryRenderOwner::class.java &&
            issued.issuerIdentity === issuerIdentity &&
            issued.ownerGeneration > 0L
        ) {
            issued
        } else {
            null
        }
    }

    private fun failRenderOwner(owner: IssuedRecoveryRenderOwner) {
        synchronized(owner) {
            owner.metricMutationEnabled = false
            owner.state = RenderOwnerState.FAILED
        }
    }

    private fun allocateOwnerGeneration(): Long? {
        while (true) {
            val current = nextOwnerGeneration.get()
            if (current <= 0L || current == Long.MAX_VALUE) return null
            if (nextOwnerGeneration.compareAndSet(current, current + 1L)) return current
        }
    }

    private const val DEFAULT_DRAIN_DEADLINE_MILLIS = 1_000L
    private const val MAXIMUM_DRAIN_DEADLINE_MILLIS = 5_000L
    private const val MAXIMUM_FRAME_METRIC_NS = 1_000_000_000L
    private const val INVALID_FRAME_METRIC = Long.MIN_VALUE
}
