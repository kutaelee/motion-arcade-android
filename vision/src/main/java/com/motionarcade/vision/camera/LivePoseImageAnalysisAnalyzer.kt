package com.motionarcade.vision.camera

import android.graphics.PixelFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInputFrame
import com.motionarcade.vision.pose.LivePoseObservationDispatcher
import com.motionarcade.vision.pose.LivePosePipeline
import com.motionarcade.vision.pose.LivePoseSessionFactory
import com.motionarcade.vision.pose.LivePoseSubmissionResult
import com.motionarcade.vision.pose.LivePoseTimeoutScheduler
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Transient one-frame scratch. Every copied byte is overwritten before the proxy is closed. */
internal class RgbaFrameScratch {
    private var owned: ByteBuffer? = null

    fun <T> withCopiedFrame(
        image: ImageProxy,
        block: (ByteBuffer) -> T,
    ): T? {
        val dimensions = exactRgbaCapacity(image.width, image.height) ?: return null
        if (image.format != PixelFormat.RGBA_8888 || image.planes.size != 1) return null
        val plane = image.planes.single()
        val copied = copyPlane(
            width = image.width,
            height = image.height,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride,
            source = plane.buffer,
            requiredCapacity = dimensions,
        ) ?: return null
        return try {
            block(copied)
        } finally {
            zero(copied)
        }
    }

    fun close() {
        owned?.let(::zero)
        owned = null
    }

    internal fun copyPlane(
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        source: ByteBuffer,
    ): ByteBuffer? {
        val capacity = exactRgbaCapacity(width, height) ?: return null
        return copyPlane(width, height, pixelStride, rowStride, source, capacity)
    }

    private fun copyPlane(
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        source: ByteBuffer,
        requiredCapacity: Int,
    ): ByteBuffer? = try {
        val activeRowBytes = Math.multiplyExact(width, RGBA_BYTES_PER_PIXEL)
        if (pixelStride != RGBA_BYTES_PER_PIXEL || rowStride < activeRowBytes) return null
        val requiredSourceBytes =
            Math.addExact(
                Math.multiplyExact(rowStride, height - 1),
                activeRowBytes,
            )
        val sourceView = source.duplicate()
        if (sourceView.remaining() < requiredSourceBytes) return null
        val sourceStart = sourceView.position()
        val destination =
            owned?.takeIf { it.capacity() == requiredCapacity }
                ?: ByteBuffer.allocateDirect(requiredCapacity).also { replacement ->
                    owned?.let(::zero)
                    owned = replacement
                }
        destination.clear()
        repeat(height) { row ->
            val rowStart = Math.addExact(sourceStart, Math.multiplyExact(row, rowStride))
            val rowEnd = Math.addExact(rowStart, activeRowBytes)
            if (rowEnd > sourceView.limit()) return null
            val rowView = sourceView.duplicate()
            rowView.position(rowStart)
            rowView.limit(rowEnd)
            destination.put(rowView)
        }
        if (destination.position() != requiredCapacity) {
            zero(destination)
            return null
        }
        destination.flip()
        destination
    } catch (_: ArithmeticException) {
        owned?.let(::zero)
        null
    } catch (_: IllegalArgumentException) {
        owned?.let(::zero)
        null
    }

    private fun zero(buffer: ByteBuffer) {
        val writer = buffer.duplicate()
        writer.clear()
        while (writer.hasRemaining()) writer.put(0)
        buffer.clear()
        buffer.limit(buffer.capacity())
    }

    private fun exactRgbaCapacity(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        return try {
            Math.multiplyExact(Math.multiplyExact(width, height), RGBA_BYTES_PER_PIXEL)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}

private fun Throwable.addSuppressedUnlessSame(secondary: Throwable) {
    if (this !== secondary) addSuppressed(secondary)
}

/**
 * CameraX pixel owner for the live product path. The proxy never escapes this callback and closes
 * exactly once even when scope validation, copying, MediaPipe submission, or close itself fails.
 */
internal class LivePoseImageAnalysisAnalyzer(
    private val sessionGate: CameraSessionRequestGate,
    private val sessionToken: CameraSessionRequestGate.Token,
    metadataSink: CameraFrameMetadataSink,
    private val counters: CameraFrameCounters,
    sessionFactory: LivePoseSessionFactory,
    inferenceSink: LivePoseInferenceSink,
    observationDispatcher: LivePoseObservationDispatcher,
    private val ownerExecutor: Executor,
    private val scopeViolationSink: CameraFrameScopeViolationSink = CameraFrameScopeViolationSink.NONE,
    timeoutScheduler: LivePoseTimeoutScheduler = LivePoseTimeoutScheduler.SYSTEM,
) : ImageAnalysis.Analyzer {
    private val occupied = AtomicBoolean(false)
    private val accepting = AtomicBoolean(true)
    private val abortSignaled = AtomicBoolean(false)
    private val closeStarted = AtomicBoolean(false)
    private val closeAfterAnalyze = AtomicBoolean(false)
    private val metadataSink = metadataSink
    private val scratch = RgbaFrameScratch()
    private val pipeline =
        LivePosePipeline(
            sessionGeneration = sessionToken.generation,
            sessionFactory = sessionFactory,
            inferenceSink = inferenceSink,
            observationDispatcher = observationDispatcher,
            timeoutScheduler = timeoutScheduler,
        )

    override fun analyze(image: ImageProxy) {
        var primaryFailure: Throwable? = null
        var admitted = false
        counters.callbackReceived()
        try {
            admitted = occupied.compareAndSet(false, true)
            if (!admitted) {
                counters.capacityRejected()
                return
            }
            when (val delivery = sessionGate.prepare(sessionToken, observedFrame(image))) {
                is CameraFrameDelivery.Ready -> {
                    metadataSink.onFrameMetadata(delivery.metadata)
                    counters.metadataDelivered()
                    if (accepting.get() && pipeline.isAccepting()) {
                        val submission =
                            scratch.withCopiedFrame(image) { rgba ->
                                pipeline.submit(
                                    LivePoseInputFrame(
                                        rgba = rgba,
                                        width = delivery.metadata.analysisWidth,
                                        height = delivery.metadata.analysisHeight,
                                        clockwiseRotationDegrees =
                                            delivery.metadata.clockwiseRotationDegrees,
                                        sourceTimestampNs = delivery.metadata.sourceTimestampNanos,
                                    ),
                                )
                            }
                        if (submission == null || submission == LivePoseSubmissionResult.TERMINAL) {
                            if (pipeline.isAccepting()) pipeline.fail()
                            counters.processingFailure()
                        }
                    }
                }
                CameraFrameDelivery.StaleGeneration -> counters.staleGenerationRejected()
                CameraFrameDelivery.MetadataMismatch -> {
                    counters.metadataRejected()
                    invalidateAndSignalAbort()?.let { throw it }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            counters.processingFailure()
            pipeline.fail()
            invalidateAndSignalAbort()?.let { failure.addSuppressedUnlessSame(it) }
            throw failure
        } finally {
            try {
                counters.closeAttempt()
                try {
                    image.close()
                    counters.closeSucceeded()
                } catch (closeFailure: Throwable) {
                    counters.closeFailure()
                    pipeline.fail()
                    val abortFailure = invalidateAndSignalAbort()
                    val originalFailure = primaryFailure
                    if (originalFailure == null) {
                        abortFailure?.let { closeFailure.addSuppressedUnlessSame(it) }
                        throw closeFailure
                    }
                    originalFailure.addSuppressedUnlessSame(closeFailure)
                    abortFailure?.let { originalFailure.addSuppressedUnlessSame(it) }
                }
            } finally {
                if (admitted) {
                    occupied.set(false)
                    closeAfterAnalyzeIfRequested()
                }
            }
        }
    }

    /** Revokes admission immediately, then closes native state behind queued analyzer work. */
    fun release() {
        if (!accepting.compareAndSet(true, false)) return
        pipeline.revokeForClose()
        val closeTask = Runnable(::closeOwnedResourcesExactlyOnce)
        try {
            ownerExecutor.execute(closeTask)
        } catch (_: RuntimeException) {
            closeAfterCurrentAnalyzeOrSynchronously(closeTask)
        }
    }

    private fun closeOwnedResourcesExactlyOnce() {
        if (!closeStarted.compareAndSet(false, true)) return
        try {
            pipeline.close()
        } finally {
            scratch.close()
        }
    }

    private fun closeAfterCurrentAnalyzeOrSynchronously(closeTask: Runnable) {
        closeAfterAnalyze.set(true)
        if (!occupied.get() && closeAfterAnalyze.compareAndSet(true, false)) {
            // Rejection is an exceptional owner failure. Closing on this caller bounds retained
            // graphs to the one failed generation; spawning or queueing fallbacks would not.
            closeTask.run()
        }
    }

    private fun closeAfterAnalyzeIfRequested() {
        if (closeAfterAnalyze.compareAndSet(true, false)) {
            // The current analyzer callback is the serial owner. Close only after its proxy has
            // closed and its scratch scope has been wiped, never concurrently on a fallback.
            closeOwnedResourcesExactlyOnce()
        }
    }

    private fun invalidateAndSignalAbort(): Throwable? {
        sessionGate.invalidate(sessionToken)
        if (!abortSignaled.compareAndSet(false, true)) return null
        return try {
            scopeViolationSink.onScopeViolation()
            null
        } catch (failure: Throwable) {
            failure
        }
    }
}
