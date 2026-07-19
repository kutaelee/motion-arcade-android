@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.motionarcade.vision.capability.recovery

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import android.graphics.PixelFormat
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.motionarcade.vision.capability.runtime.ProbeStateMachine
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import com.motionarcade.vision.capability.runtime.RuntimeCallbackOutputExecution
import com.motionarcade.vision.capability.runtime.RuntimeOpenExecution
import com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary
import com.motionarcade.vision.capability.runtime.RuntimeSubmitAuthorization
import com.motionarcade.vision.capability.runtime.RuntimeSubmitExecution
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executor

/** Opaque ownership of the concrete CameraX source/analyzer pipeline for one runtime. */
sealed interface RecoveryCameraPipelineOwner

/**
 * One owner-issued frame graph. The source proxy, exact RGBA buffer, submitted input, and callback
 * output cannot be represented by independent optional leases.
 */
sealed interface RecoveryCameraFrameLease

/** One callback-origin token minted only by the analyzer instance installed by this boundary. */
sealed interface RecoveryCameraAnalyzerSource

fun interface RecoveryCameraAnalyzerSourceSink {
    fun onSource(source: RecoveryCameraAnalyzerSource)
}

/** Exact closed receipt; it has no authority until the clean-closure coordinator consumes it. */
sealed interface RecoveryCameraPipelineClosure

internal interface RecoveryCameraPipelinePlatformPort {
    /** Performs the actual analyzer detach and source unbind. Throws on any uncertainty. */
    fun detachAnalyzerAndUnbindSource()

    /** Returns true only when every owned use case is observably unbound at the time of this call. */
    fun isDetachedAndUnbound(): Boolean
}

private class CameraXPipelinePlatformPort(
    private val imageAnalysis: ImageAnalysis,
    private val cameraProvider: ProcessCameraProvider,
    useCases: List<UseCase>,
) : RecoveryCameraPipelinePlatformPort {
    private val ownedUseCases = ArrayList(useCases)

    override fun detachAnalyzerAndUnbindSource() {
        imageAnalysis.clearAnalyzer()
        cameraProvider.unbind(*ownedUseCases.toTypedArray())
    }

    override fun isDetachedAndUnbound(): Boolean =
        ownedUseCases.all { useCase -> !cameraProvider.isBound(useCase) }

    fun installAnalyzer(
        executor: Executor,
        analyzer: ImageAnalysis.Analyzer,
    ) {
        imageAnalysis.setAnalyzer(executor, analyzer)
    }
}

object RecoveryCameraPipelineBoundary {
private enum class CameraPipelineOwnerState {
    OPEN,
    CLOSING,
    CLOSED,
    VERIFYING_CLOSED,
    CONSUMED,
    FAILED,
}

private enum class CameraFrameState {
    OPEN,
    BUILDING_INPUT,
    INPUT_OWNED,
    SUBMITTING_INPUT,
    INPUT_SUBMITTED,
    CLEANING_INPUT,
    AWAITING_CALLBACK,
    CLOSING_CALLBACK,
    CALLBACK_CLOSED,
    CLOSING_SOURCE,
    CLOSED_WITH_CALLBACK,
    CLOSED_WITHOUT_CALLBACK,
    FAILED,
}

private class IssuedRecoveryCameraPipelineOwner private constructor(
    val issuerIdentity: Any,
    val ownerGeneration: Long,
    val platformPort: RecoveryCameraPipelinePlatformPort,
) : RecoveryCameraPipelineOwner {
    var state = CameraPipelineOwnerState.OPEN
    var claimedResourceGeneration = 0L
    val activeFrames: MutableSet<IssuedRecoveryCameraFrameLease> = identitySet()
    val activeAnalyzerSources: MutableSet<IssuedRecoveryCameraAnalyzerSource> = identitySet()
    val seenSourceProxies: MutableSet<ImageProxy> = identitySet()
    val sourceCloseAttempts: MutableSet<ImageProxy> = identitySet()
    val sourceClosed: MutableSet<ImageProxy> = identitySet()
    var analyzerEntriesInFlight = 0
    var analyzerInstalled = false
    var totalFrameCount = 0
    var callbackClosedFrameCount = 0
    var noCallbackFrameCount = 0
    var stickyPoison = false
    // Production factory flips this before publication; false exists only for the private test seam.
    var requiresBitmapCallbackOutput = false

    companion object {
        fun issue(
            issuerIdentity: Any,
            ownerGeneration: Long,
            platformPort: RecoveryCameraPipelinePlatformPort,
        ): IssuedRecoveryCameraPipelineOwner =
            IssuedRecoveryCameraPipelineOwner(
                issuerIdentity = issuerIdentity,
                ownerGeneration = ownerGeneration,
                platformPort = platformPort,
            )
    }
}

private class IssuedRecoveryCameraAnalyzerSource(
    val issuerIdentity: Any,
    val owner: IssuedRecoveryCameraPipelineOwner,
    val ownerGeneration: Long,
    val sourceProxy: ImageProxy,
) : RecoveryCameraAnalyzerSource {
    val consumed = AtomicBoolean(false)
    val finished = AtomicBoolean(false)
}

private class IssuedRecoveryCameraFrameLease(
    val issuerIdentity: Any,
    val owner: IssuedRecoveryCameraPipelineOwner,
    val ownerGeneration: Long,
    val resourceGeneration: Long,
    val sourceProxy: ImageProxy,
    val rgbaBuffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val exactByteCapacity: Int,
) : RecoveryCameraFrameLease {
    var state = CameraFrameState.OPEN
    var submittedInput: MPImage? = null
    var submitExecution: RuntimeSubmitExecution? = null
    var submittedInputCloseAttempted = false
    var submittedInputClosed = false
    var bufferCleanupAttempted = false
    var bufferZeroed = false
    var sourceCloseAttempted = false
    var sourceClosed = false
    var callbackOutputCloseAttempted = false
    var inputCleanupComplete = false
    var callbackOutputClosed = false
}

private class IssuedRecoveryCameraPipelineClosure(
    val issuerIdentity: Any,
    val owner: IssuedRecoveryCameraPipelineOwner,
    val ownerGeneration: Long,
    val resourceGeneration: Long,
) : RecoveryCameraPipelineClosure {
    val consumed = AtomicBoolean(false)
}

/**
 * The only production factory owns concrete CameraX objects and the complete per-frame resource
 * graph. Any foreign image, wrong-sized buffer, late callback, or live rebind poisons this owner and
 * permanently prevents clean-closure authority.
 */
    private val issuerIdentity = Any()
    private val nextOwnerGeneration = AtomicLong(1L)

    private class OwnedRecoveryCameraAnalyzer(
        private val owner: IssuedRecoveryCameraPipelineOwner,
        private val sink: RecoveryCameraAnalyzerSourceSink,
    ) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val source = beginAnalyzerSource(image) ?: return
            try {
                sink.onSource(source)
            } catch (_: Throwable) {
                poisonAnalyzerSource(source)
            } finally {
                finishAnalyzerSource(source)
            }
        }

        /*
         * Keep analyzer-origin minting inside this JVM-private implementation. Calling private
         * methods on the enclosing Kotlin object would make the compiler emit public static
         * access$... bridges whose signatures contain only public types, allowing same-package
         * Java to mint a source token without entering the installed analyzer.
         */
        private fun beginAnalyzerSource(
            sourceProxy: ImageProxy,
        ): IssuedRecoveryCameraAnalyzerSource? {
            var closeAsLate = false
            var source: IssuedRecoveryCameraAnalyzerSource? = null
            synchronized(owner) {
                if (!owner.seenSourceProxies.add(sourceProxy)) {
                    poisonLocked()
                    return null
                }
                if (!owner.analyzerInstalled || owner.state != CameraPipelineOwnerState.OPEN ||
                    owner.stickyPoison
                ) {
                    poisonLocked()
                    closeAsLate = true
                } else if (owner.analyzerEntriesInFlight == Int.MAX_VALUE) {
                    poisonLocked()
                    closeAsLate = true
                } else {
                    owner.analyzerEntriesInFlight += 1
                    source =
                        IssuedRecoveryCameraAnalyzerSource(
                            issuerIdentity = owner.issuerIdentity,
                            owner = owner,
                            ownerGeneration = owner.ownerGeneration,
                            sourceProxy = sourceProxy,
                        ).also(owner.activeAnalyzerSources::add)
                }
            }
            if (closeAsLate) closeOwnedSourceExactlyOnce(sourceProxy)
            return source
        }

        private fun poisonAnalyzerSource(source: IssuedRecoveryCameraAnalyzerSource) {
            if (source.owner !== owner || source.ownerGeneration != owner.ownerGeneration) {
                poisonLocked()
                return
            }
            synchronized(owner) { poisonLocked() }
        }

        private fun finishAnalyzerSource(source: IssuedRecoveryCameraAnalyzerSource) {
            if (source.owner !== owner || source.ownerGeneration != owner.ownerGeneration) {
                synchronized(owner) { poisonLocked() }
                return
            }
            if (!source.finished.compareAndSet(false, true)) {
                synchronized(owner) { poisonLocked() }
                return
            }
            var closeUnconsumed = false
            synchronized(owner) {
                if (owner.activeAnalyzerSources.remove(source) && !source.consumed.get()) {
                    poisonLocked()
                    closeUnconsumed = true
                }
                if (owner.analyzerEntriesInFlight <= 0) {
                    poisonLocked()
                } else {
                    owner.analyzerEntriesInFlight -= 1
                }
                (owner as java.lang.Object).notifyAll()
            }
            if (closeUnconsumed) closeOwnedSourceExactlyOnce(source.sourceProxy)
        }

        private fun closeOwnedSourceExactlyOnce(sourceProxy: ImageProxy): Boolean {
            synchronized(owner) {
                if (sourceProxy !in owner.seenSourceProxies ||
                    !owner.sourceCloseAttempts.add(sourceProxy)
                ) {
                    poisonLocked()
                    return false
                }
            }
            val closed = try {
                sourceProxy.close()
                true
            } catch (_: Throwable) {
                false
            }
            synchronized(owner) {
                if (closed) {
                    owner.sourceClosed.add(sourceProxy)
                } else {
                    poisonLocked()
                }
            }
            return closed
        }

        private fun poisonLocked() {
            owner.stickyPoison = true
            owner.state = CameraPipelineOwnerState.FAILED
        }
    }

    fun ownBoundCameraXPipeline(
        imageAnalysis: ImageAnalysis,
        cameraProvider: ProcessCameraProvider,
        useCases: List<UseCase>,
    ): RecoveryCameraPipelineOwner? {
        val exactUseCases = ArrayList(useCases)
        if (exactUseCases.isEmpty() || exactUseCases.none { it === imageAnalysis }) return null
        if (exactUseCases.indices.any { index ->
                (0 until index).any { prior -> exactUseCases[prior] === exactUseCases[index] }
            }
        ) {
            return null
        }
        val allBound = try {
            exactUseCases.all(cameraProvider::isBound)
        } catch (_: Throwable) {
            false
        }
        if (!allBound) return null
        val generation = allocateGeneration() ?: return null
        return IssuedRecoveryCameraPipelineOwner.issue(
            issuerIdentity = issuerIdentity,
            ownerGeneration = generation,
            platformPort =
                CameraXPipelinePlatformPort(
                    imageAnalysis = imageAnalysis,
                    cameraProvider = cameraProvider,
                    useCases = exactUseCases,
                ),
        ).also { owner -> owner.requiresBitmapCallbackOutput = true }
    }

    /** Installs the exact analyzer that is allowed to mint source-origin tokens for this owner. */
    fun installOwnedAnalyzer(
        owner: RecoveryCameraPipelineOwner,
        executor: Executor,
        sink: RecoveryCameraAnalyzerSourceSink,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val platform = issued.platformPort as? CameraXPipelinePlatformPort ?: return false
        synchronized(issued) {
            if (issued.state != CameraPipelineOwnerState.OPEN || issued.stickyPoison ||
                issued.analyzerInstalled || issued.claimedResourceGeneration != 0L
            ) {
                return false
            }
            // Publish before the platform call so a synchronous callback is origin-valid. Any
            // side-effect-then-throw path below is absorbing and clears the analyzer best-effort.
            issued.analyzerInstalled = true
        }
        return try {
            platform.installAnalyzer(executor, OwnedRecoveryCameraAnalyzer(issued, sink))
            true
        } catch (_: Throwable) {
            synchronized(issued) { poisonLocked(issued) }
            try {
                issued.platformPort.detachAnalyzerAndUnbindSource()
            } catch (_: Throwable) {
                // The owner is already absorbing-failed.
            }
            false
        }
    }

    internal fun claimForResourceGeneration(
        owner: RecoveryCameraPipelineOwner,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        if (resourceGeneration <= 0L) return false
        synchronized(issued) {
            if (issued.state != CameraPipelineOwnerState.OPEN ||
                issued.stickyPoison ||
                !issued.analyzerInstalled ||
                issued.claimedResourceGeneration != 0L
            ) {
                return false
            }
            issued.claimedResourceGeneration = resourceGeneration
            return true
        }
    }

    internal fun releaseUncommittedClaim(
        owner: RecoveryCameraPipelineOwner,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        synchronized(issued) {
            if (issued.state != CameraPipelineOwnerState.OPEN ||
                issued.stickyPoison ||
                issued.activeFrames.isNotEmpty() ||
                issued.claimedResourceGeneration != resourceGeneration
            ) {
                return false
            }
            issued.claimedResourceGeneration = 0L
            return true
        }
    }

    internal fun ownFrame(
        owner: RecoveryCameraPipelineOwner,
        resourceGeneration: Long,
        source: RecoveryCameraAnalyzerSource,
    ): RecoveryCameraFrameLease? {
        val issued = genuineOwner(owner) ?: return null
        val analyzerSource = genuineAnalyzerSource(source, issued) ?: return null
        if (!analyzerSource.consumed.compareAndSet(false, true)) {
            synchronized(issued) { poisonLocked(issued) }
            return null
        }
        synchronized(issued) {
            issued.activeAnalyzerSources.remove(analyzerSource)
            if (issued.claimedResourceGeneration != resourceGeneration || resourceGeneration <= 0L ||
                issued.state != CameraPipelineOwnerState.OPEN || issued.stickyPoison
            ) {
                poisonLocked(issued)
            }
        }
        val admitted = synchronized(issued) {
            issued.claimedResourceGeneration == resourceGeneration && resourceGeneration > 0L &&
                issued.state == CameraPipelineOwnerState.OPEN && !issued.stickyPoison
        }
        if (!admitted) {
            closeOwnedSourceExactlyOnce(issued, analyzerSource.sourceProxy)
            return null
        }

        var accepted: IssuedRecoveryCameraFrameLease? = null
        val copied = copyOwnedRgba(analyzerSource.sourceProxy)
        if (copied != null) {
            synchronized(issued) {
                if (issued.state != CameraPipelineOwnerState.OPEN || issued.stickyPoison ||
                    issued.totalFrameCount == Int.MAX_VALUE
                ) {
                    poisonLocked(issued)
                    return@synchronized
                }
                val lease =
                    IssuedRecoveryCameraFrameLease(
                        issuerIdentity = issuerIdentity,
                        owner = issued,
                        ownerGeneration = issued.ownerGeneration,
                        resourceGeneration = resourceGeneration,
                        sourceProxy = analyzerSource.sourceProxy,
                        rgbaBuffer = copied.buffer,
                        width = copied.width,
                        height = copied.height,
                        exactByteCapacity = copied.capacity,
                    )
                issued.activeFrames.add(lease)
                issued.totalFrameCount += 1
                accepted = lease
            }
        }
        if (accepted == null) {
            if (copied != null) zeroBuffer(copied.buffer)
            synchronized(issued) { poisonLocked(issued) }
            closeOwnedSourceExactlyOnce(issued, analyzerSource.sourceProxy)
        }
        return accepted
    }

    /** Builds and registers the exact submitted MPImage from the graph's owned RGBA buffer. */
    internal fun buildSubmittedInput(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        resourceGeneration: Long,
    ): MPImage? {
        val issued = genuineOwner(owner) ?: return null
        val lease = genuineFrame(frame, issued, resourceGeneration) ?: return null
        synchronized(issued) {
            if (!frameActiveLocked(issued, lease) || lease.state != CameraFrameState.OPEN ||
                lease.submittedInput != null ||
                !exactBufferShape(lease)
            ) {
                return null
            }
            lease.state = CameraFrameState.BUILDING_INPUT
        }
        val input = try {
            ByteBufferImageBuilder(
                lease.rgbaBuffer,
                lease.width,
                lease.height,
                MPImage.IMAGE_FORMAT_RGBA,
            ).build()
        } catch (_: Throwable) {
            null
        }
        val exact = input != null && isExactSubmittedImage(input, lease)
        var accepted = false
        synchronized(issued) {
            if (exact && frameActiveLocked(issued, lease) &&
                lease.state == CameraFrameState.BUILDING_INPUT
            ) {
                lease.submittedInput = input
                lease.state = CameraFrameState.INPUT_OWNED
                accepted = true
            } else {
                failFrameLocked(issued, lease)
                lease.bufferCleanupAttempted = true
                lease.sourceCloseAttempted = true
            }
        }
        if (accepted) return input
        if (input != null) closeQuietly(input)
        val zeroed = zeroAndVerify(lease)
        val sourceClosed = closeOwnedSourceExactlyOnce(issued, lease.sourceProxy)
        synchronized(issued) {
            lease.bufferZeroed = zeroed
            lease.sourceClosed = sourceClosed
        }
        return null
    }

    /**
     * Passes the graph-owned MPImage through RuntimeOwnerBoundary and binds the returned execution
     * to this exact frame. No caller-provided or independently built image can acquire this state.
     */
    internal fun submitFrame(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        resourceGeneration: Long,
        machine: ProbeStateMachine,
        authorization: RuntimeSubmitAuthorization,
        openExecution: RuntimeOpenExecution,
    ): RuntimeSubmitExecution? {
        val issued = genuineOwner(owner) ?: return null
        val lease = genuineFrame(frame, issued, resourceGeneration) ?: return null
        val input: MPImage
        synchronized(issued) {
            if (!frameActiveLocked(issued, lease) || lease.state != CameraFrameState.INPUT_OWNED ||
                lease.submitExecution != null
            ) {
                return null
            }
            input = lease.submittedInput ?: return null
            lease.state = CameraFrameState.SUBMITTING_INPUT
        }
        val execution =
            RuntimeOwnerBoundary.submitWithInput(
                machine,
                authorization,
                openExecution,
                input,
            )
        val exact =
            execution != null &&
                RuntimeOwnerBoundary.isGenuineSubmitExecutionForInput(
                    execution,
                    openExecution,
                    input,
                )
        synchronized(issued) {
            if (exact && frameActiveLocked(issued, lease) &&
                lease.state == CameraFrameState.SUBMITTING_INPUT
            ) {
                lease.submitExecution = execution
                lease.state = CameraFrameState.INPUT_SUBMITTED
                return execution
            }
            failFrameLocked(issued, lease)
        }
        closeQuietly(input)
        zeroAndVerify(lease)
        closeOwnedSourceExactlyOnce(issued, lease.sourceProxy)
        return null
    }

    /** Closes the concrete callback MPImage and binds that cleanup to the exact frame graph. */
    internal fun closeCallbackOutput(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        callbackExecution: RuntimeCallbackOutputExecution?,
        callbackOutput: MPImage,
        submittedOpenExecution: RuntimeOpenExecution,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val lease = genuineFrame(frame, issued, resourceGeneration) ?: return false
        val input: MPImage
        val submitExecution: RuntimeSubmitExecution
        synchronized(issued) {
            if (!frameActiveLocked(issued, lease) ||
                lease.state != CameraFrameState.AWAITING_CALLBACK ||
                !lease.inputCleanupComplete ||
                lease.submittedInput == null || lease.submitExecution == null ||
                lease.callbackOutputCloseAttempted || callbackOutput === lease.submittedInput
            ) {
                return false
            }
            input = lease.submittedInput ?: return false
            submitExecution = lease.submitExecution ?: return false
            lease.callbackOutputCloseAttempted = true
            lease.state = CameraFrameState.CLOSING_CALLBACK
        }
        val proven =
            callbackExecution != null &&
                RuntimeOwnerBoundary.consumeCallbackOutputForSubmission(
                    callbackExecution,
                    submitExecution,
                    submittedOpenExecution,
                    input,
                    callbackOutput,
                )
        val expectedOutput =
            !issued.requiresBitmapCallbackOutput || isBitmapCallbackOutput(callbackOutput)
        val closed = closeQuietly(callbackOutput)
        var accepted = false
        var closeSource = false
        synchronized(issued) {
            if (proven && closed && expectedOutput && frameActiveLocked(issued, lease) &&
                lease.state == CameraFrameState.CLOSING_CALLBACK
            ) {
                lease.callbackOutputClosed = true
                lease.state = CameraFrameState.CALLBACK_CLOSED
                accepted = true
            } else {
                failFrameLocked(issued, lease)
                if (!lease.sourceCloseAttempted) {
                    lease.sourceCloseAttempted = true
                    closeSource = true
                }
            }
        }
        if (closeSource) {
            val sourceClosed = closeOwnedSourceExactlyOnce(issued, lease.sourceProxy)
            synchronized(issued) { lease.sourceClosed = sourceClosed }
        }
        return accepted
    }

    /**
     * Returned submission cleanup happens before result wait: close submitted input and overwrite
     * the full direct-buffer capacity. The frame graph remains alive only for callback/proxy.
     */
    internal fun completeReturnedSubmission(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val lease = genuineFrame(frame, issued, resourceGeneration) ?: return false
        val input: MPImage
        synchronized(issued) {
            if (!frameActiveLocked(issued, lease) ||
                lease.state != CameraFrameState.INPUT_SUBMITTED ||
                lease.inputCleanupComplete || lease.submittedInputCloseAttempted ||
                lease.bufferCleanupAttempted || lease.submitExecution == null
            ) {
                return false
            }
            input = lease.submittedInput ?: return false
            lease.submittedInputCloseAttempted = true
            lease.bufferCleanupAttempted = true
            lease.state = CameraFrameState.CLEANING_INPUT
        }
        val inputClosed = closeQuietly(input)
        val bufferZeroed = zeroAndVerify(lease)
        var accepted = false
        var closeSource = false
        synchronized(issued) {
            lease.submittedInputClosed = inputClosed
            lease.bufferZeroed = bufferZeroed
            if (inputClosed && bufferZeroed && frameActiveLocked(issued, lease) &&
                lease.state == CameraFrameState.CLEANING_INPUT
            ) {
                lease.inputCleanupComplete = true
                lease.state = CameraFrameState.AWAITING_CALLBACK
                accepted = true
            } else {
                failFrameLocked(issued, lease)
                if (!lease.sourceCloseAttempted) {
                    lease.sourceCloseAttempted = true
                    closeSource = true
                }
            }
        }
        if (closeSource) {
            val sourceClosed = closeOwnedSourceExactlyOnce(issued, lease.sourceProxy)
            synchronized(issued) { lease.sourceClosed = sourceClosed }
        }
        return accepted
    }

    /**
     * Closes the source proxy after returned-submission cleanup and concrete callback-output cleanup
     * have completed; partial success is an absorbing owner failure.
     */
    internal fun closeCompletedFrame(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        resourceGeneration: Long,
    ): Boolean = closeFrame(owner, frame, resourceGeneration, callbackRequired = true)

    /** Closes a graph only after the exact machine has reached a no-callback terminal outcome. */
    internal fun closeFrameWithoutCallback(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        resourceGeneration: Long,
    ): Boolean = closeFrame(owner, frame, resourceGeneration, callbackRequired = false)

    /** A callback admitted after graph/pipeline closure poisons the owner even when close succeeds. */
    internal fun closeLateCallbackOutput(
        owner: RecoveryCameraPipelineOwner,
        callbackOutput: MPImage,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        synchronized(issued) {
            if (issued.claimedResourceGeneration != resourceGeneration || resourceGeneration <= 0L) {
                return false
            }
            // Admission is the revocation linearization point; disposal follows without an app lock.
            poisonLocked(issued)
        }
        return closeQuietly(callbackOutput)
    }

    internal fun closeForResourceGeneration(
        owner: RecoveryCameraPipelineOwner,
        resourceGeneration: Long,
        outcome: RouteAttemptOutcome,
        exactCompletedCallbackCount: Int,
    ): RecoveryCameraPipelineClosure? {
        val issued = genuineOwner(owner) ?: return null
        synchronized(issued) {
            if (issued.state != CameraPipelineOwnerState.OPEN ||
                issued.stickyPoison ||
                issued.claimedResourceGeneration != resourceGeneration ||
                resourceGeneration <= 0L ||
                issued.activeFrames.isNotEmpty() ||
                issued.sourceCloseAttempts.size > issued.seenSourceProxies.size ||
                issued.seenSourceProxies.size - issued.sourceCloseAttempts.size >
                    issued.analyzerEntriesInFlight ||
                !frameEvidenceMatchesOutcomeLocked(issued, outcome, exactCompletedCallbackCount)
            ) {
                return null
            }
            issued.state = CameraPipelineOwnerState.CLOSING
        }
        try {
            issued.platformPort.detachAnalyzerAndUnbindSource()
            if (!awaitAnalyzerEntryDrain(issued)) {
                failOwner(issued)
                return null
            }
            if (!issued.platformPort.isDetachedAndUnbound()) {
                failOwner(issued)
                return null
            }
        } catch (_: Throwable) {
            failOwner(issued)
            return null
        }
        synchronized(issued) {
            if (issued.state != CameraPipelineOwnerState.CLOSING || issued.stickyPoison ||
                issued.analyzerEntriesInFlight != 0 || issued.activeFrames.isNotEmpty() ||
                issued.activeAnalyzerSources.isNotEmpty() ||
                issued.sourceCloseAttempts.size != issued.seenSourceProxies.size ||
                issued.sourceClosed.size != issued.seenSourceProxies.size
            ) {
                return null
            }
            issued.state = CameraPipelineOwnerState.CLOSED
            return IssuedRecoveryCameraPipelineClosure(
                issuerIdentity = issuerIdentity,
                owner = issued,
                ownerGeneration = issued.ownerGeneration,
                resourceGeneration = resourceGeneration,
            )
        }
    }

    internal fun consumeClosed(
        owner: RecoveryCameraPipelineOwner,
        closure: RecoveryCameraPipelineClosure,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val receipt = closure as? IssuedRecoveryCameraPipelineClosure ?: return false
        synchronized(issued) {
            if (receipt.javaClass != IssuedRecoveryCameraPipelineClosure::class.java ||
                receipt.issuerIdentity !== issuerIdentity ||
                receipt.owner !== issued ||
                receipt.ownerGeneration != issued.ownerGeneration ||
                receipt.resourceGeneration != resourceGeneration ||
                issued.claimedResourceGeneration != resourceGeneration ||
                issued.state != CameraPipelineOwnerState.CLOSED ||
                issued.stickyPoison ||
                issued.activeFrames.isNotEmpty() || issued.activeAnalyzerSources.isNotEmpty() ||
                issued.analyzerEntriesInFlight != 0 ||
                issued.sourceClosed.size != issued.seenSourceProxies.size
            ) {
                return false
            }
            issued.state = CameraPipelineOwnerState.VERIFYING_CLOSED
        }
        // This is deliberately a fresh read. A detach followed by rebind is not clean evidence.
        val stillUnbound = try {
            issued.platformPort.isDetachedAndUnbound()
        } catch (_: Throwable) {
            false
        }
        synchronized(issued) {
            if (!stillUnbound || issued.state != CameraPipelineOwnerState.VERIFYING_CLOSED ||
                issued.stickyPoison
            ) {
                poisonLocked(issued)
                return false
            }
            if (!receipt.consumed.compareAndSet(false, true)) {
                poisonLocked(issued)
                return false
            }
            issued.state = CameraPipelineOwnerState.CONSUMED
            return true
        }
    }

    /** Revalidates post-mint camera state immediately before journal authorization. */
    internal fun isStillClean(
        owner: RecoveryCameraPipelineOwner,
        resourceGeneration: Long,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        synchronized(issued) {
            if (issued.claimedResourceGeneration != resourceGeneration ||
                issued.state != CameraPipelineOwnerState.CONSUMED || issued.stickyPoison ||
                issued.activeFrames.isNotEmpty() || issued.activeAnalyzerSources.isNotEmpty() ||
                issued.analyzerEntriesInFlight != 0 ||
                issued.sourceClosed.size != issued.seenSourceProxies.size
            ) {
                return false
            }
        }
        val stillUnbound = try {
            issued.platformPort.isDetachedAndUnbound()
        } catch (_: Throwable) {
            false
        }
        synchronized(issued) {
            if (!stillUnbound || issued.claimedResourceGeneration != resourceGeneration ||
                issued.state != CameraPipelineOwnerState.CONSUMED || issued.stickyPoison
            ) {
                poisonLocked(issued)
                return false
            }
            return true
        }
    }

    private fun closeFrame(
        owner: RecoveryCameraPipelineOwner,
        frame: RecoveryCameraFrameLease,
        resourceGeneration: Long,
        callbackRequired: Boolean,
    ): Boolean {
        val issued = genuineOwner(owner) ?: return false
        val lease = genuineFrame(frame, issued, resourceGeneration) ?: return false
        synchronized(issued) {
            val expectedState =
                if (callbackRequired) {
                    CameraFrameState.CALLBACK_CLOSED
                } else {
                    CameraFrameState.AWAITING_CALLBACK
                }
            if (!frameActiveLocked(issued, lease) || lease.state != expectedState ||
                lease.submittedInput == null || lease.submitExecution == null ||
                lease.callbackOutputClosed != callbackRequired ||
                !lease.inputCleanupComplete || lease.sourceCloseAttempted
            ) {
                return false
            }
            lease.sourceCloseAttempted = true
            lease.state = CameraFrameState.CLOSING_SOURCE
        }
        val proxyClosed = closeOwnedSourceExactlyOnce(issued, lease.sourceProxy)
        synchronized(issued) {
            lease.sourceClosed = proxyClosed
            if (!proxyClosed || !frameActiveLocked(issued, lease) ||
                lease.state != CameraFrameState.CLOSING_SOURCE
            ) {
                failFrameLocked(issued, lease)
                return false
            }
            lease.state =
                if (callbackRequired) {
                    CameraFrameState.CLOSED_WITH_CALLBACK
                } else {
                    CameraFrameState.CLOSED_WITHOUT_CALLBACK
                }
            issued.activeFrames.remove(lease)
            if (callbackRequired) {
                issued.callbackClosedFrameCount += 1
            } else {
                issued.noCallbackFrameCount += 1
            }
            return true
        }
    }

    private fun frameEvidenceMatchesOutcomeLocked(
        owner: IssuedRecoveryCameraPipelineOwner,
        outcome: RouteAttemptOutcome,
        exactCompletedCallbackCount: Int,
    ): Boolean {
        if (exactCompletedCallbackCount < 0 ||
            owner.callbackClosedFrameCount != exactCompletedCallbackCount ||
            owner.totalFrameCount != owner.callbackClosedFrameCount + owner.noCallbackFrameCount
        ) {
            return false
        }
        return when (outcome) {
            RouteAttemptOutcome.MEASURED ->
                exactCompletedCallbackCount > 0 && owner.noCallbackFrameCount == 0
            RouteAttemptOutcome.CLEAN_TERMINAL ->
                owner.noCallbackFrameCount > 0
            RouteAttemptOutcome.INCOMPLETE -> true
            RouteAttemptOutcome.RESOURCE_UNCERTAIN -> false
        }
    }

    private fun genuineOwner(
        candidate: RecoveryCameraPipelineOwner,
    ): IssuedRecoveryCameraPipelineOwner? {
        val issued = candidate as? IssuedRecoveryCameraPipelineOwner ?: return null
        return issued.takeIf {
            it.javaClass == IssuedRecoveryCameraPipelineOwner::class.java &&
                it.issuerIdentity === issuerIdentity &&
                it.ownerGeneration > 0L
        }
    }

    private fun genuineFrame(
        candidate: RecoveryCameraFrameLease,
        owner: IssuedRecoveryCameraPipelineOwner,
        resourceGeneration: Long,
    ): IssuedRecoveryCameraFrameLease? {
        val issued = candidate as? IssuedRecoveryCameraFrameLease ?: return null
        return issued.takeIf {
            it.javaClass == IssuedRecoveryCameraFrameLease::class.java &&
                it.issuerIdentity === issuerIdentity &&
                it.owner === owner &&
                it.ownerGeneration == owner.ownerGeneration &&
                it.resourceGeneration == resourceGeneration
        }
    }

    private fun genuineAnalyzerSource(
        candidate: RecoveryCameraAnalyzerSource,
        owner: IssuedRecoveryCameraPipelineOwner? = null,
    ): IssuedRecoveryCameraAnalyzerSource? {
        val issued = candidate as? IssuedRecoveryCameraAnalyzerSource ?: return null
        return issued.takeIf {
            it.javaClass == IssuedRecoveryCameraAnalyzerSource::class.java &&
                it.issuerIdentity === issuerIdentity &&
                it.ownerGeneration == it.owner.ownerGeneration &&
                (owner == null || it.owner === owner)
        }
    }

    private fun frameActiveLocked(
        owner: IssuedRecoveryCameraPipelineOwner,
        frame: IssuedRecoveryCameraFrameLease,
    ): Boolean =
        owner.state == CameraPipelineOwnerState.OPEN &&
            !owner.stickyPoison &&
            frame.state !in
            setOf(
                CameraFrameState.CLOSED_WITH_CALLBACK,
                CameraFrameState.CLOSED_WITHOUT_CALLBACK,
                CameraFrameState.FAILED,
            ) &&
            frame in owner.activeFrames

    private fun exactBufferShape(frame: IssuedRecoveryCameraFrameLease): Boolean =
        frame.rgbaBuffer.isDirect &&
            !frame.rgbaBuffer.isReadOnly &&
            frame.rgbaBuffer.capacity() == frame.exactByteCapacity &&
            frame.rgbaBuffer.position() == 0 &&
            frame.rgbaBuffer.limit() == frame.exactByteCapacity

    private fun isExactSubmittedImage(
        image: MPImage,
        frame: IssuedRecoveryCameraFrameLease,
    ): Boolean = try {
        image.width == frame.width &&
            image.height == frame.height &&
            image.containedImageProperties.any { properties ->
                properties.storageType == MPImage.STORAGE_TYPE_BYTEBUFFER &&
                    properties.imageFormat == MPImage.IMAGE_FORMAT_RGBA
            }
    } catch (_: Throwable) {
        false
    }

    private fun isBitmapCallbackOutput(image: MPImage): Boolean = try {
        image.containedImageProperties.any { properties ->
            properties.storageType == MPImage.STORAGE_TYPE_BITMAP
        }
    } catch (_: Throwable) {
        false
    }

    private data class CopiedRgba(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val capacity: Int,
    )

    /**
     * Accepts only CameraX RGBA_8888's exact single-plane, full-crop, unrotated layout. Row
     * padding is permitted, but only the active width of every row is copied into a newly allocated
     * owner buffer. This deliberately fails closed on crop/rotation layouts until the corresponding
     * transform owner is implemented.
     */
    private fun copyOwnedRgba(source: ImageProxy): CopiedRgba? = try {
        val width = source.width
        val height = source.height
        val capacity = exactRgbaCapacity(width, height) ?: return null
        val crop = source.cropRect
        if (source.format != PixelFormat.RGBA_8888 || source.planes.size != 1 ||
            crop.left != 0 || crop.top != 0 || crop.right != width || crop.bottom != height ||
            source.imageInfo.rotationDegrees != 0
        ) {
            return null
        }
        val plane = source.planes.single()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val activeRowBytes = Math.multiplyExact(width, RGBA_BYTES_PER_PIXEL)
        if (pixelStride != RGBA_BYTES_PER_PIXEL || rowStride < activeRowBytes) return null
        val requiredBytes =
            Math.addExact(
                Math.multiplyExact(rowStride, height - 1),
                activeRowBytes,
            )
        val maximumLayoutBytes = Math.multiplyExact(rowStride, height)
        val sourceBuffer = plane.buffer.duplicate()
        if (sourceBuffer.remaining() < requiredBytes ||
            sourceBuffer.remaining() > maximumLayoutBytes
        ) {
            return null
        }
        val sourceStart = sourceBuffer.position()
        val owned = ByteBuffer.allocateDirect(capacity)
        repeat(height) { row ->
            val rowStart = Math.addExact(sourceStart, Math.multiplyExact(row, rowStride))
            val rowEnd = Math.addExact(rowStart, activeRowBytes)
            if (rowEnd > sourceBuffer.limit()) return null
            val rowView = sourceBuffer.duplicate()
            rowView.position(rowStart)
            rowView.limit(rowEnd)
            owned.put(rowView)
        }
        if (owned.position() != capacity) return null
        owned.clear()
        CopiedRgba(owned, width, height, capacity)
    } catch (_: Throwable) {
        null
    }

    private fun zeroBuffer(buffer: ByteBuffer): Boolean = try {
        if (!buffer.isDirect || buffer.isReadOnly) return false
        val writer = buffer.duplicate()
        writer.clear()
        while (writer.hasRemaining()) writer.put(0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun zeroAndVerify(frame: IssuedRecoveryCameraFrameLease): Boolean = try {
        if (!exactBufferShape(frame)) return false
        val writer = frame.rgbaBuffer.duplicate()
        writer.clear()
        while (writer.hasRemaining()) writer.put(0)
        val verifier = frame.rgbaBuffer.duplicate()
        verifier.clear()
        while (verifier.hasRemaining()) {
            if (verifier.get().toInt() != 0) return false
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun closeQuietly(image: MPImage): Boolean = try {
        image.close()
        true
    } catch (_: Throwable) {
        false
    }

    private fun closeQuietly(image: ImageProxy): Boolean = try {
        image.close()
        true
    } catch (_: Throwable) {
        false
    }

    private fun closeOwnedSourceExactlyOnce(
        owner: IssuedRecoveryCameraPipelineOwner,
        sourceProxy: ImageProxy,
    ): Boolean {
        synchronized(owner) {
            if (sourceProxy !in owner.seenSourceProxies ||
                !owner.sourceCloseAttempts.add(sourceProxy)
            ) {
                poisonLocked(owner)
                return false
            }
        }
        val closed = closeQuietly(sourceProxy)
        synchronized(owner) {
            if (closed) {
                owner.sourceClosed.add(sourceProxy)
            } else {
                poisonLocked(owner)
            }
        }
        return closed
    }

    private fun awaitAnalyzerEntryDrain(owner: IssuedRecoveryCameraPipelineOwner): Boolean {
        val deadline = try {
            Math.addExact(System.nanoTime(), ANALYZER_ENTRY_DRAIN_DEADLINE_NS)
        } catch (_: ArithmeticException) {
            return false
        }
        synchronized(owner) {
            while (owner.analyzerEntriesInFlight != 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val millis = remaining / 1_000_000L
                val nanos = (remaining % 1_000_000L).toInt()
                try {
                    (owner as java.lang.Object).wait(millis, nanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return !owner.stickyPoison
        }
    }

    private fun exactRgbaCapacity(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        return try {
            Math.multiplyExact(Math.multiplyExact(width, height), RGBA_BYTES_PER_PIXEL)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun failFrameLocked(
        owner: IssuedRecoveryCameraPipelineOwner,
        frame: IssuedRecoveryCameraFrameLease,
    ) {
        frame.state = CameraFrameState.FAILED
        owner.activeFrames.remove(frame)
        poisonLocked(owner)
    }

    private fun poisonLocked(owner: IssuedRecoveryCameraPipelineOwner) {
        owner.stickyPoison = true
        owner.state = CameraPipelineOwnerState.FAILED
    }

    private fun failOwner(owner: IssuedRecoveryCameraPipelineOwner) {
        synchronized(owner) { poisonLocked(owner) }
    }

    private fun allocateGeneration(): Long? {
        while (true) {
            val current = nextOwnerGeneration.get()
            if (current <= 0L || current == Long.MAX_VALUE) return null
            if (nextOwnerGeneration.compareAndSet(current, current + 1L)) return current
        }
    }

    private const val RGBA_BYTES_PER_PIXEL = 4
    private const val ANALYZER_ENTRY_DRAIN_DEADLINE_NS = 1_000_000_000L
}

private fun <T> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
