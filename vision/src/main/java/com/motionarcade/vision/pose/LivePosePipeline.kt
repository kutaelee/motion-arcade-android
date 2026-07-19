package com.motionarcade.vision.pose

import android.content.Context
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

const val LIVE_POSE_MAX_POSES: Int = 2
internal const val LIVE_POSE_RESULT_TIMEOUT_MILLIS: Long = 1_000L
private val UPPER_BODY_REQUIRED_INDEXES = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24)

enum class LivePoseInferencePhase {
    IDLE,
    INITIALIZING,
    WAITING_FOR_RESULT,
    ACTIVE,
    FAILED,
    RELEASED,
}

/** Aggregate-only UI observation. It never contains pixels, landmarks, paths, or user identity. */
data class LivePoseInferenceSnapshot(
    val sessionGeneration: Long,
    val revision: Long,
    val phase: LivePoseInferencePhase,
    val poseCount: Int?,
    val callbackCount: Long,
    val resultTimestampMs: Long?,
    val upperBodyConfidenceFloor: Float? = null,
    val upperBodyOnScreen: Boolean? = null,
) {
    init {
        require(sessionGeneration >= 0L) { "Session generation must be non-negative" }
        require(revision >= 0L) { "Snapshot revision must be non-negative" }
        require(callbackCount >= 0L) { "Callback count must be non-negative" }
        if (phase == LivePoseInferencePhase.ACTIVE) {
            require(poseCount != null && poseCount in 0..LIVE_POSE_MAX_POSES) {
                "Active inference requires an aggregate pose count"
            }
            require(callbackCount > 0L) { "Active inference requires a callback" }
            require(resultTimestampMs != null && resultTimestampMs >= 0L) {
                "Active inference requires its correlated MediaPipe timestamp"
            }
            if (poseCount == 0) {
                require(upperBodyConfidenceFloor == null && upperBodyOnScreen == null)
            } else if (upperBodyConfidenceFloor != null || upperBodyOnScreen != null) {
                require(upperBodyConfidenceFloor != null && upperBodyConfidenceFloor.isFinite())
                require(upperBodyConfidenceFloor in 0f..1f)
                require(upperBodyOnScreen != null)
            }
        } else {
            require(poseCount == null) { "Only active inference exposes a pose count" }
            require(resultTimestampMs == null) { "Only active inference exposes a result timestamp" }
            require(upperBodyConfidenceFloor == null && upperBodyOnScreen == null)
        }
    }

    companion object {
        fun idle(sessionGeneration: Long = 0L): LivePoseInferenceSnapshot =
            LivePoseInferenceSnapshot(
                sessionGeneration = sessionGeneration,
                revision = 0L,
                phase = LivePoseInferencePhase.IDLE,
                poseCount = null,
                callbackCount = 0L,
                resultTimestampMs = null,
            )
    }
}

fun interface LivePoseInferenceSink {
    fun onInference(snapshot: LivePoseInferenceSnapshot)

    companion object {
        val NONE = LivePoseInferenceSink { }
    }
}

internal data class LivePoseInputFrame(
    val rgba: ByteBuffer,
    val width: Int,
    val height: Int,
    val clockwiseRotationDegrees: Int,
    val sourceTimestampNs: Long,
) {
    init {
        require(rgba.isDirect) { "RGBA input must be direct" }
        require(width > 0 && height > 0) { "Input dimensions must be positive" }
        require(clockwiseRotationDegrees in ROTATIONS) { "Input rotation must be a quarter turn" }
        require(sourceTimestampNs >= 0L) { "Source timestamp must be non-negative" }
        val expectedCapacity = Math.multiplyExact(Math.multiplyExact(width, height), RGBA_BYTES_PER_PIXEL)
        require(rgba.position() == 0 && rgba.limit() == expectedCapacity) {
            "RGBA input must expose exactly one tightly packed frame"
        }
    }
}

internal enum class LivePoseSubmissionResult {
    SUBMITTED,
    BUSY,
    TERMINAL,
}

internal interface LivePoseSession {
    fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long)

    fun close()
}

internal interface LivePoseSessionCallbacks {
    fun onResult(result: LivePoseSessionResult)

    fun onError()
}

internal fun interface LivePoseTimeoutHandle {
    fun cancel()
}

/** Watchdog seam. Implementations schedule asynchronously and never invoke inline. */
internal fun interface LivePoseTimeoutScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): LivePoseTimeoutHandle

    companion object {
        val SYSTEM: LivePoseTimeoutScheduler = SystemLivePoseTimeoutScheduler
    }
}

private object SystemLivePoseTimeoutScheduler : LivePoseTimeoutScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(
            1,
        ) { runnable ->
            Thread(runnable, "MotionArcade-PoseWatchdog").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }

    override fun schedule(delayMillis: Long, action: () -> Unit): LivePoseTimeoutHandle {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return LivePoseTimeoutHandle { future.cancel(false) }
    }
}

internal fun interface LivePoseSessionFactory {
    fun create(callbacks: LivePoseSessionCallbacks): LivePoseSession

    companion object {
        /** Test/default fail-closed seam. Production surfaces inject the MediaPipe factory. */
        val UNAVAILABLE = LivePoseSessionFactory {
            throw IllegalStateException("Live pose session factory was not installed")
        }
    }
}

/**
 * Maps CameraX monotonic nanoseconds to MediaPipe's strictly increasing millisecond timebase.
 * The first accepted frame is zero; sub-millisecond frames are advanced by one millisecond.
 */
internal class LivePoseTimestampEpoch(
    private var sourceAnchorNs: Long? = null,
    private var previousSourceTimestampNs: Long? = null,
    private var previousTaskTimestampMs: Long = -1L,
) {
    init {
        if (sourceAnchorNs == null) {
            require(previousSourceTimestampNs == null && previousTaskTimestampMs == -1L)
        } else {
            val anchor = requireNotNull(sourceAnchorNs)
            require(anchor >= 0L)
            require(previousSourceTimestampNs != null && previousSourceTimestampNs!! >= anchor)
            require(previousTaskTimestampMs in 0L..ProbeTimeContract.MAX_TASK_TIMESTAMP_MS)
        }
    }

    @Synchronized
    fun reserve(sourceTimestampNs: Long): Long? {
        if (sourceTimestampNs < 0L) return null
        val previousSource = previousSourceTimestampNs
        if (previousSource != null && sourceTimestampNs <= previousSource) return null
        val anchor = sourceAnchorNs ?: sourceTimestampNs
        val relativeNs = try {
            Math.subtractExact(sourceTimestampNs, anchor)
        } catch (_: ArithmeticException) {
            return null
        }
        if (relativeNs < 0L || previousTaskTimestampMs == ProbeTimeContract.MAX_TASK_TIMESTAMP_MS) {
            return null
        }
        val nextTimestamp = try {
            Math.addExact(previousTaskTimestampMs, 1L)
        } catch (_: ArithmeticException) {
            return null
        }
        val taskTimestampMs = max(
            relativeNs / ProbeTimeContract.NANOS_PER_MILLISECOND,
            nextTimestamp,
        )
        if (taskTimestampMs !in 0L..ProbeTimeContract.MAX_TASK_TIMESTAMP_MS) return null
        try {
            Math.multiplyExact(taskTimestampMs, ProbeTimeContract.MICROS_PER_MILLISECOND)
        } catch (_: ArithmeticException) {
            return null
        }
        sourceAnchorNs = anchor
        previousSourceTimestampNs = sourceTimestampNs
        previousTaskTimestampMs = taskTimestampMs
        return taskTimestampMs
    }
}

/** One serial-owner session with callbacks admitted from MediaPipe's callback thread. */
internal class LivePosePipeline(
    private val sessionGeneration: Long,
    private val sessionFactory: LivePoseSessionFactory,
    private val inferenceSink: LivePoseInferenceSink,
    private val observationDispatcher: LivePoseObservationDispatcher,
    private val timestampEpoch: LivePoseTimestampEpoch = LivePoseTimestampEpoch(),
    private val timeoutScheduler: LivePoseTimeoutScheduler = LivePoseTimeoutScheduler.SYSTEM,
    private val resultTimeoutMillis: Long = LIVE_POSE_RESULT_TIMEOUT_MILLIS,
) {
    init {
        require(sessionGeneration > 0L) { "A live pipeline requires a positive camera generation" }
        require(resultTimeoutMillis > 0L) { "Result timeout must be positive" }
    }

    private val lock = Any()
    private val accepting = AtomicBoolean(true)
    private var phase = LivePoseInferencePhase.INITIALIZING
    private var callbackCount = 0L
    private var snapshotRevision = 0L
    private var observationRevision = 0L
    private var lastApprovedTimestampMs: Long? = null
    private var session: LivePoseSession? = null
    private var pendingSubmission: PendingSubmission? = null
    private var watchdogHandle: LivePoseTimeoutHandle? = null
    private var watchdogRevision = 0L
    private var closeStarted = false
    private val pendingSnapshots = ArrayDeque<LivePoseInferenceSnapshot>()
    private var snapshotDispatchInProgress = false

    private val callbacks =
        object : LivePoseSessionCallbacks {
            override fun onResult(result: LivePoseSessionResult) {
                val resolution =
                    try {
                        synchronized(lock) {
                            if (!accepting.get() || phase in TERMINAL_PHASES) return
                            val pending = pendingSubmission
                            val previousApproved = lastApprovedTimestampMs
                            val taskTimestampMs = result.taskTimestampMs
                            if (previousApproved != null && taskTimestampMs <= previousApproved) return
                            if (taskTimestampMs < 0L || pending == null) {
                                return@synchronized failFromCallbackLocked()
                            }
                            if (taskTimestampMs < pending.taskTimestampMs) return
                            if (taskTimestampMs > pending.taskTimestampMs) {
                                return@synchronized failFromCallbackLocked()
                            }
                            if (pending.stage == SubmissionStage.SUBMITTING) {
                                if (pending.earlyResult != null) {
                                    return@synchronized failFromCallbackLocked()
                                }
                                pending.earlyResult = result
                                return
                            }
                            resolveResultLocked(result)
                        }
                    } catch (_: RuntimeException) {
                        fail()
                        return
                    }
                completeResolution(resolution)
            }

            override fun onError() {
                fail()
            }
        }

    init {
        val shouldDrain = synchronized(lock) { enqueueLocked(snapshotLocked(phase)) }
        if (shouldDrain) drainSnapshots()
    }

    /** Called only by the camera analysis executor. */
    fun submit(frame: LivePoseInputFrame): LivePoseSubmissionResult {
        if (!accepting.get()) return LivePoseSubmissionResult.TERMINAL
        synchronized(lock) {
            if (pendingSubmission != null) return LivePoseSubmissionResult.BUSY
        }
        val activeSession = session ?: createSession() ?: return LivePoseSubmissionResult.TERMINAL
        val taskTimestampMs = timestampEpoch.reserve(frame.sourceTimestampNs)
        if (taskTimestampMs == null) {
            fail()
            return LivePoseSubmissionResult.TERMINAL
        }
        val pending =
            PendingSubmission(
                sourceTimestampNs = frame.sourceTimestampNs,
                taskTimestampMs = taskTimestampMs,
                stage = SubmissionStage.SUBMITTING,
            )
        synchronized(lock) {
            if (!accepting.get() || phase in TERMINAL_PHASES) {
                return LivePoseSubmissionResult.TERMINAL
            }
            if (pendingSubmission != null) return LivePoseSubmissionResult.BUSY
            pendingSubmission = pending
        }
        try {
            activeSession.detectAsync(frame, taskTimestampMs)
        } catch (_: RuntimeException) {
            fail()
            return LivePoseSubmissionResult.TERMINAL
        } catch (_: LinkageError) {
            fail()
            return LivePoseSubmissionResult.TERMINAL
        }
        val completion = try {
            synchronized(lock) {
                if (!accepting.get() || phase in TERMINAL_PHASES || pendingSubmission !== pending) {
                    SubmissionCompletion(LivePoseSubmissionResult.TERMINAL, resolution = null)
                } else {
                    pending.stage = SubmissionStage.AWAITING_RESULT
                    val earlyResult = pending.earlyResult
                    if (earlyResult == null) {
                        armWatchdogLocked()
                        SubmissionCompletion(LivePoseSubmissionResult.SUBMITTED, resolution = null)
                    } else {
                        pending.earlyResult = null
                        val resolution = resolveResultLocked(earlyResult)
                        SubmissionCompletion(
                            result =
                                if (accepting.get()) {
                                    LivePoseSubmissionResult.SUBMITTED
                                } else {
                                    LivePoseSubmissionResult.TERMINAL
                                },
                            resolution = resolution,
                        )
                    }
                }
            }
        } catch (_: RuntimeException) {
            fail()
            return LivePoseSubmissionResult.TERMINAL
        }
        completion.resolution?.let(::completeResolution)
        return completion.result
    }

    fun isAccepting(): Boolean = accepting.get()

    fun fail() {
        val shouldDrain =
            synchronized(lock) {
                if (phase in TERMINAL_PHASES) {
                    false
                } else {
                    accepting.set(false)
                    cancelWatchdogLocked()
                    pendingSubmission = null
                    phase = LivePoseInferencePhase.FAILED
                    enqueueLocked(snapshotLocked(phase))
                }
            }
        observationDispatcher.revoke(LivePoseObservationTerminalReason.FAILED)
        if (shouldDrain) drainSnapshots()
    }

    /** Synchronous generation revocation used before controller rebind queues native close. */
    fun revokeForClose() {
        accepting.set(false)
        synchronized(lock) {
            cancelWatchdogLocked()
            pendingSubmission = null
        }
        observationDispatcher.revoke(LivePoseObservationTerminalReason.REBOUND)
    }

    /** Called on the same serial executor that performs [submit]. */
    fun close() {
        revokeForClose()
        val ownership =
            synchronized(lock) {
                if (closeStarted) {
                    CloseOwnership(performNativeClose = false, session = null)
                } else {
                    closeStarted = true
                    CloseOwnership(
                        performNativeClose = true,
                        session = session.also { session = null },
                    )
                }
            }
        val closed =
            if (!ownership.performNativeClose) {
                synchronized(lock) { phase != LivePoseInferencePhase.FAILED }
            } else {
                try {
                    ownership.session?.close()
                    true
                } catch (_: RuntimeException) {
                    false
                } catch (_: LinkageError) {
                    false
                }
            }
        val terminalTransition =
            synchronized(lock) {
                if (phase in TERMINAL_PHASES) {
                    TerminalTransition(shouldDrainSnapshots = false, phase = phase)
                } else {
                    phase =
                        if (closed) {
                            LivePoseInferencePhase.RELEASED
                        } else {
                            LivePoseInferencePhase.FAILED
                        }
                    TerminalTransition(
                        shouldDrainSnapshots = enqueueLocked(snapshotLocked(phase)),
                        phase = phase,
                    )
                }
            }
        val terminalReason =
            if (terminalTransition.phase == LivePoseInferencePhase.RELEASED) {
                LivePoseObservationTerminalReason.RELEASED
            } else {
                LivePoseObservationTerminalReason.FAILED
            }
        observationDispatcher.closeAndAwait(terminalReason)
        if (terminalTransition.shouldDrainSnapshots) drainSnapshots()
    }

    private fun createSession(): LivePoseSession? {
        val created =
            try {
                sessionFactory.create(callbacks)
            } catch (_: RuntimeException) {
                fail()
                return null
            } catch (_: LinkageError) {
                fail()
                return null
            }
        if (!accepting.get()) {
            try {
                created.close()
            } catch (_: RuntimeException) {
                // The externally visible state is already terminal.
            } catch (_: LinkageError) {
                // The externally visible state is already terminal.
            }
            return null
        }
        val shouldDrain =
            synchronized(lock) {
                session = created
                if (phase == LivePoseInferencePhase.INITIALIZING) {
                    phase = LivePoseInferencePhase.WAITING_FOR_RESULT
                    enqueueLocked(snapshotLocked(phase))
                } else {
                    false
                }
            }
        if (shouldDrain) drainSnapshots()
        return created
    }

    private fun snapshotLocked(
        snapshotPhase: LivePoseInferencePhase,
        poseCount: Int? = null,
        resultTimestampMs: Long? = null,
        upperBodyConfidenceFloor: Float? = null,
        upperBodyOnScreen: Boolean? = null,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = sessionGeneration,
            revision = Math.incrementExact(snapshotRevision).also { snapshotRevision = it },
            phase = snapshotPhase,
            poseCount = poseCount,
            callbackCount = callbackCount,
            resultTimestampMs = resultTimestampMs,
            upperBodyConfidenceFloor = upperBodyConfidenceFloor,
            upperBodyOnScreen = upperBodyOnScreen,
        )

    private fun enqueueLocked(snapshot: LivePoseInferenceSnapshot): Boolean {
        pendingSnapshots.addLast(snapshot)
        if (snapshotDispatchInProgress) return false
        snapshotDispatchInProgress = true
        return true
    }

    private fun failFromCallbackLocked(): CallbackResolution {
        accepting.set(false)
        cancelWatchdogLocked()
        pendingSubmission = null
        phase = LivePoseInferencePhase.FAILED
        return CallbackResolution(
            shouldDrainSnapshots = enqueueLocked(snapshotLocked(phase)),
            terminalFailure = true,
        )
    }

    private fun resolveResultLocked(result: LivePoseSessionResult): CallbackResolution {
        val pending = pendingSubmission ?: return failFromCallbackLocked()
        if (result.taskTimestampMs != pending.taskTimestampMs) return failFromCallbackLocked()
        val nextObservationRevision = Math.incrementExact(observationRevision)
        val frame =
            result.toObservationFrameOrNull(
                sessionGeneration = sessionGeneration,
                frameRevision = nextObservationRevision,
                sourceTimestampNs = pending.sourceTimestampNs,
            ) ?: return failFromCallbackLocked()

        pendingSubmission = null
        cancelWatchdogLocked()
        if (!observationDispatcher.offer(frame)) return failFromCallbackLocked()
        observationRevision = nextObservationRevision
        lastApprovedTimestampMs = result.taskTimestampMs
        callbackCount = callbackCount.incrementSaturated()
        phase = LivePoseInferencePhase.ACTIVE
        val shouldDrainSnapshots =
            enqueueLocked(
                snapshotLocked(
                    snapshotPhase = phase,
                    poseCount = frame.poses.size,
                    resultTimestampMs = result.taskTimestampMs,
                    upperBodyConfidenceFloor = frame.upperBodyConfidenceFloor(),
                    upperBodyOnScreen = frame.upperBodyOnScreen(),
                ),
            )
        return CallbackResolution(
            shouldDrainSnapshots = shouldDrainSnapshots,
            terminalFailure = false,
        )
    }

    private fun LivePoseObservationFrame.upperBodyConfidenceFloor(): Float? = poses
        .takeIf { it.isNotEmpty() }
        ?.flatMap { pose -> UPPER_BODY_REQUIRED_INDEXES.map { index -> pose.landmarks[index] } }
        ?.minOf { landmark -> minOf(landmark.visibility ?: 0f, landmark.presence ?: 0f) }

    private fun LivePoseObservationFrame.upperBodyOnScreen(): Boolean? = poses
        .takeIf { it.isNotEmpty() }
        ?.all { pose ->
            UPPER_BODY_REQUIRED_INDEXES.all { index ->
                val landmark = pose.landmarks[index]
                landmark.x in 0f..1f && landmark.y in 0f..1f
            }
        }

    private fun completeResolution(resolution: CallbackResolution) {
        if (resolution.terminalFailure) {
            observationDispatcher.revoke(LivePoseObservationTerminalReason.FAILED)
        }
        if (resolution.shouldDrainSnapshots) drainSnapshots()
    }

    private fun drainSnapshots() {
        while (true) {
            val snapshot =
                synchronized(lock) {
                    if (pendingSnapshots.isEmpty()) {
                        snapshotDispatchInProgress = false
                        return
                    }
                    pendingSnapshots.removeFirst()
                }
            try {
                inferenceSink.onInference(snapshot)
            } catch (_: Exception) {
                // Aggregate presentation cannot break state serialization or revive inference.
            }
        }
    }

    private fun armWatchdogLocked() {
        check(watchdogHandle == null)
        val expectedTimestampMs = requireNotNull(pendingSubmission).taskTimestampMs
        watchdogRevision = Math.incrementExact(watchdogRevision)
        val expectedRevision = watchdogRevision
        watchdogHandle =
            timeoutScheduler.schedule(resultTimeoutMillis) {
                onResultTimeout(expectedRevision, expectedTimestampMs)
            }
    }

    private fun cancelWatchdogLocked() {
        watchdogRevision = if (watchdogRevision == Long.MAX_VALUE) 0L else watchdogRevision + 1L
        watchdogHandle?.cancel()
        watchdogHandle = null
    }

    private fun onResultTimeout(
        expectedRevision: Long,
        expectedTimestampMs: Long,
    ) {
        val shouldDrain =
            synchronized(lock) {
                if (
                    !accepting.get() ||
                    phase in TERMINAL_PHASES ||
                    watchdogHandle == null ||
                    watchdogRevision != expectedRevision ||
                    pendingSubmission?.stage != SubmissionStage.AWAITING_RESULT ||
                    pendingSubmission?.taskTimestampMs != expectedTimestampMs
                ) {
                    return
                }
                watchdogHandle = null
                watchdogRevision =
                    if (watchdogRevision == Long.MAX_VALUE) 0L else watchdogRevision + 1L
                accepting.set(false)
                pendingSubmission = null
                phase = LivePoseInferencePhase.FAILED
                enqueueLocked(snapshotLocked(phase))
            }
        observationDispatcher.revoke(LivePoseObservationTerminalReason.FAILED)
        if (shouldDrain) drainSnapshots()
    }

    private fun Long.incrementSaturated(): Long =
        if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L

    private companion object {
        val TERMINAL_PHASES = setOf(LivePoseInferencePhase.FAILED, LivePoseInferencePhase.RELEASED)
    }

    private data class PendingSubmission(
        val sourceTimestampNs: Long,
        val taskTimestampMs: Long,
        var stage: SubmissionStage,
        var earlyResult: LivePoseSessionResult? = null,
    )

    private data class SubmissionCompletion(
        val result: LivePoseSubmissionResult,
        val resolution: CallbackResolution?,
    )

    private data class CallbackResolution(
        val shouldDrainSnapshots: Boolean,
        val terminalFailure: Boolean,
    )

    private data class CloseOwnership(
        val performNativeClose: Boolean,
        val session: LivePoseSession?,
    )

    private data class TerminalTransition(
        val shouldDrainSnapshots: Boolean,
        val phase: LivePoseInferencePhase,
    )

    private enum class SubmissionStage {
        SUBMITTING,
        AWAITING_RESULT,
    }

}

internal class MediaPipeLivePoseSessionFactory(context: Context) : LivePoseSessionFactory {
    private val applicationContext = context.applicationContext

    override fun create(callbacks: LivePoseSessionCallbacks): LivePoseSession {
        val options =
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(POSE_MODEL_ASSET)
                        .setDelegate(Delegate.CPU)
                        .build(),
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(LIVE_POSE_MAX_POSES)
                .setMinPoseDetectionConfidence(DEFAULT_CONFIDENCE)
                .setMinPosePresenceConfidence(DEFAULT_CONFIDENCE)
                .setMinTrackingConfidence(DEFAULT_CONFIDENCE)
                .setOutputSegmentationMasks(false)
                .setResultListener { result, callbackImage ->
                    var copiedResult: LivePoseSessionResult? = null
                    var clean = true
                    try {
                        copiedResult =
                            LivePoseSessionResult(
                                result.timestampMs(),
                                result.landmarks().map { pose ->
                                    pose.map { landmark ->
                                        LivePoseSessionLandmark(
                                            landmark.x(),
                                            landmark.y(),
                                            landmark.z(),
                                            landmark.visibility().let { score ->
                                                if (score.isPresent) score.get() else null
                                            },
                                            landmark.presence().let { score ->
                                                if (score.isPresent) score.get() else null
                                            },
                                        )
                                    }
                                },
                            )
                    } catch (_: RuntimeException) {
                        clean = false
                    } finally {
                        try {
                            callbackImage.close()
                        } catch (_: RuntimeException) {
                            clean = false
                        }
                    }
                    if (clean && copiedResult != null) {
                        callbacks.onResult(requireNotNull(copiedResult))
                    } else {
                        callbacks.onError()
                    }
                }
                .setErrorListener { callbacks.onError() }
                .build()
        return MediaPipeLivePoseSession(
            PoseLandmarker.createFromOptions(applicationContext, options),
        )
    }

    private class MediaPipeLivePoseSession(
        private val landmarker: PoseLandmarker,
    ) : LivePoseSession {
        private val closed = AtomicBoolean(false)
        private val processingOptions =
            ROTATIONS.associateWith { rotation ->
                ImageProcessingOptions.builder()
                    .setRotationDegrees(rotation)
                    .build()
            }

        override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
            check(!closed.get()) { "Pose Landmarker session is closed" }
            val input =
                ByteBufferImageBuilder(
                    frame.rgba.duplicate().apply {
                        position(0)
                        limit(capacity())
                    },
                    frame.width,
                    frame.height,
                    MPImage.IMAGE_FORMAT_RGBA,
                ).build()
            try {
                landmarker.detectAsync(
                    input,
                    requireNotNull(processingOptions[frame.clockwiseRotationDegrees]),
                    taskTimestampMs,
                )
            } finally {
                // AndroidPacketCreator synchronously creates the native input packet before
                // detectAsync returns. The callback receives a distinct image_out MPImage.
                input.close()
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) landmarker.close()
        }
    }

    private companion object {
        const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"
        const val DEFAULT_CONFIDENCE = 0.5f
    }
}

private val ROTATIONS = setOf(0, 90, 180, 270)
private const val RGBA_BYTES_PER_PIXEL = 4
