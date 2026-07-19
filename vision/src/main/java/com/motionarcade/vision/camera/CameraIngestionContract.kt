package com.motionarcade.vision.camera

import android.graphics.Rect
import android.util.Size
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.math.BigInteger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Immutable crop bounds. No mutable Android geometry object crosses the handoff boundary. */
data class CameraFrameCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "Crop origin must be non-negative" }
        require(right > left && bottom > top) { "Crop must have positive area" }
    }
}

enum class CameraFrameLens {
    FRONT,
    BACK,
}

/**
 * Privacy-safe handoff for the pre-native slice.
 *
 * It intentionally contains no pixel, plane, bitmap, ImageProxy, landmark, or native handle.
 */
data class CameraFrameMetadata(
    val sessionGeneration: Long,
    val frameOrdinal: Long,
    val sourceTimestampNanos: Long,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val crop: CameraFrameCrop,
    val clockwiseRotationDegrees: Int,
    val targetSurfaceRotation: Int,
    val lens: CameraFrameLens,
    val analysisMirrored: Boolean,
    val previewMirrored: Boolean,
) {
    init {
        require(sessionGeneration > 0L) { "Session generation must be positive" }
        require(frameOrdinal >= 0L) { "Frame ordinal must be non-negative" }
        require(sourceTimestampNanos >= 0L) { "Source timestamp must be non-negative" }
        require(analysisWidth > 0 && analysisHeight > 0) { "Analysis size must be positive" }
        require(crop.right <= analysisWidth && crop.bottom <= analysisHeight) {
            "Crop must be contained by the analysis buffer"
        }
        require(isQuarterTurn(clockwiseRotationDegrees)) { "Unsupported frame rotation" }
        require(isSurfaceRotation(targetSurfaceRotation)) { "Unsupported target rotation" }
        require(!analysisMirrored) { "Analysis input must never be lens-mirrored" }
        require(previewMirrored == (lens == CameraFrameLens.FRONT)) {
            "Only the front-camera presentation is mirrored"
        }
    }
}

fun interface CameraFrameMetadataSink {
    fun onFrameMetadata(metadata: CameraFrameMetadata)

    companion object {
        val NONE = CameraFrameMetadataSink { }
    }
}

internal fun interface CameraFrameScopeViolationSink {
    fun onScopeViolation()

    companion object {
        val NONE = CameraFrameScopeViolationSink { }
    }
}

/** Saturating, process-local counters. They make no claim that pose inference occurred. */
data class CameraFrameCounterSnapshot(
    val callbacksReceived: Long,
    val metadataDelivered: Long,
    val staleGenerationRejected: Long,
    val capacityRejected: Long,
    val metadataRejected: Long,
    val processingFailures: Long,
    val closeAttempts: Long,
    val closeSucceeded: Long,
    val closeFailures: Long,
)

class CameraFrameCounters internal constructor() {
    private val callbacksReceived = AtomicLong()
    private val metadataDelivered = AtomicLong()
    private val staleGenerationRejected = AtomicLong()
    private val capacityRejected = AtomicLong()
    private val metadataRejected = AtomicLong()
    private val processingFailures = AtomicLong()
    private val closeAttempts = AtomicLong()
    private val closeSucceeded = AtomicLong()
    private val closeFailures = AtomicLong()

    fun snapshot(): CameraFrameCounterSnapshot =
        CameraFrameCounterSnapshot(
            callbacksReceived = callbacksReceived.get(),
            metadataDelivered = metadataDelivered.get(),
            staleGenerationRejected = staleGenerationRejected.get(),
            capacityRejected = capacityRejected.get(),
            metadataRejected = metadataRejected.get(),
            processingFailures = processingFailures.get(),
            closeAttempts = closeAttempts.get(),
            closeSucceeded = closeSucceeded.get(),
            closeFailures = closeFailures.get(),
        )

    internal fun callbackReceived() = callbacksReceived.incrementSaturated()

    internal fun metadataDelivered() = metadataDelivered.incrementSaturated()

    internal fun staleGenerationRejected() = staleGenerationRejected.incrementSaturated()

    internal fun capacityRejected() = capacityRejected.incrementSaturated()

    internal fun metadataRejected() = metadataRejected.incrementSaturated()

    internal fun processingFailure() = processingFailures.incrementSaturated()

    internal fun closeAttempt() = closeAttempts.incrementSaturated()

    internal fun closeSucceeded() = closeSucceeded.incrementSaturated()

    internal fun closeFailure() = closeFailures.incrementSaturated()
}

private fun AtomicLong.incrementSaturated() {
    while (true) {
        val observed = get()
        if (observed == Long.MAX_VALUE) return
        if (compareAndSet(observed, observed + 1L)) return
    }
}

internal data class CameraFrameScope(
    val width: Int,
    val height: Int,
    val crop: CameraFrameCrop,
    val clockwiseRotationDegrees: Int,
    val targetSurfaceRotation: Int,
    val lens: CameraFrameLens = CameraFrameLens.FRONT,
) {
    init {
        require(width > 0 && height > 0) { "Scope size must be positive" }
        require(crop.right <= width && crop.bottom <= height) { "Scope crop is out of bounds" }
        require(isQuarterTurn(clockwiseRotationDegrees)) { "Unsupported scope rotation" }
        require(isSurfaceRotation(targetSurfaceRotation)) { "Unsupported scope target rotation" }
    }
}

internal data class ObservedCameraFrame(
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val crop: CameraFrameCrop,
    val clockwiseRotationDegrees: Int,
)

internal sealed interface CameraFrameDelivery {
    data class Ready(val metadata: CameraFrameMetadata) : CameraFrameDelivery

    data object StaleGeneration : CameraFrameDelivery

    data object MetadataMismatch : CameraFrameDelivery
}

internal fun observedFrame(image: ImageProxy): ObservedCameraFrame {
    val mutableCrop: Rect = image.cropRect
    return ObservedCameraFrame(
        timestampNanos = image.imageInfo.timestamp,
        width = image.width,
        height = image.height,
        crop = CameraFrameCrop(
            left = mutableCrop.left,
            top = mutableCrop.top,
            right = mutableCrop.right,
            bottom = mutableCrop.bottom,
        ),
        clockwiseRotationDegrees = image.imageInfo.rotationDegrees,
    )
}

/**
 * Capacity-one metadata analyzer. The ImageProxy is never exposed to the downstream sink and is
 * closed exactly once by this owner on every callback path.
 */
internal class MetadataOnlyImageAnalysisAnalyzer(
    private val sessionGate: CameraSessionRequestGate,
    private val sessionToken: CameraSessionRequestGate.Token,
    private val metadataSink: CameraFrameMetadataSink,
    private val counters: CameraFrameCounters,
    private val scopeViolationSink: CameraFrameScopeViolationSink = CameraFrameScopeViolationSink.NONE,
) : ImageAnalysis.Analyzer {
    private val occupied = AtomicBoolean(false)
    private val abortSignaled = AtomicBoolean(false)

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
                // The capacity lease includes proxy disposal. A blocked or failed close must not
                // admit another frame to downstream work before this callback has resolved.
                if (admitted) occupied.set(false)
            }
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

private fun Throwable.addSuppressedUnlessSame(secondary: Throwable) {
    if (this !== secondary) addSuppressed(secondary)
}

/** Exact deterministic ordering required by the Slice 1B capability scope. */
internal object CameraResolutionPolicy : ResolutionFilter {
    val target = Size(640, 480)

    override fun filter(supportedSizes: List<Size>, rotationDegrees: Int): List<Size> {
        require(isQuarterTurn(rotationDegrees)) { "Unsupported resolution-filter rotation" }
        return supportedSizes
            .filter { it.width > 0 && it.height > 0 }
            .sortedWith(::compareSizes)
    }

    internal fun order(supportedSizes: List<Size>): List<Size> =
        supportedSizes
            .filter { it.width > 0 && it.height > 0 }
            .sortedWith(::compareSizes)

    private fun compareSizes(left: Size, right: Size): Int {
        val leftLong = maxOf(left.width, left.height).toLong()
        val leftShort = minOf(left.width, left.height).toLong()
        val rightLong = maxOf(right.width, right.height).toLong()
        val rightShort = minOf(right.width, right.height).toLong()

        val exactComparison =
            compareValues(
                leftLong == target.width.toLong() && leftShort == target.height.toLong(),
                rightLong == target.width.toLong() && rightShort == target.height.toLong(),
            )
        if (exactComparison != 0) return -exactComparison

        val leftError = kotlin.math.abs(3L * leftLong - 4L * leftShort)
        val rightError = kotlin.math.abs(3L * rightLong - 4L * rightShort)
        val ratioComparison = compareFractions(leftError, leftShort, rightError, rightShort)
        if (ratioComparison != 0) return ratioComparison

        val targetArea = target.width.toLong() * target.height.toLong()
        val leftAreaError = kotlin.math.abs(leftLong * leftShort - targetArea)
        val rightAreaError = kotlin.math.abs(rightLong * rightShort - targetArea)
        val areaComparison = leftAreaError.compareTo(rightAreaError)
        if (areaComparison != 0) return areaComparison
        val longEdgeComparison = leftLong.compareTo(rightLong)
        if (longEdgeComparison != 0) return longEdgeComparison
        val shortEdgeComparison = leftShort.compareTo(rightShort)
        if (shortEdgeComparison != 0) return shortEdgeComparison
        val widthComparison = left.width.compareTo(right.width)
        return if (widthComparison != 0) widthComparison else left.height.compareTo(right.height)
    }

    private fun compareFractions(
        leftNumerator: Long,
        leftDenominator: Long,
        rightNumerator: Long,
        rightDenominator: Long,
    ): Int {
        val leftCross = BigInteger.valueOf(leftNumerator).multiply(BigInteger.valueOf(rightDenominator))
        val rightCross = BigInteger.valueOf(rightNumerator).multiply(BigInteger.valueOf(leftDenominator))
        return leftCross.compareTo(rightCross)
    }
}

internal data class CameraUseCaseBundle(
    val preview: Preview,
    val analysis: ImageAnalysis,
    val group: UseCaseGroup,
)

internal object CameraUseCaseFactory {
    fun create(
        viewPort: ViewPort,
        analyzerExecutor: Executor,
        analyzer: ImageAnalysis.Analyzer,
    ): CameraUseCaseBundle {
        val targetRotation = viewPort.rotation
        require(isSurfaceRotation(targetRotation)) { "ViewPort target rotation is unsupported" }
        val resolutionSelector =
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        CameraResolutionPolicy.target,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .setResolutionFilter(CameraResolutionPolicy)
                .build()
        val preview = Preview.Builder().setTargetRotation(targetRotation).build()
        val analysis =
            ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(false)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, analyzer) }
        val group =
            UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(analysis)
                .build()
        return CameraUseCaseBundle(preview = preview, analysis = analysis, group = group)
    }
}

internal fun isQuarterTurn(rotationDegrees: Int): Boolean =
    rotationDegrees == 0 ||
        rotationDegrees == 90 ||
        rotationDegrees == 180 ||
        rotationDegrees == 270

internal fun isSurfaceRotation(rotation: Int): Boolean =
    rotation == Surface.ROTATION_0 ||
        rotation == Surface.ROTATION_90 ||
        rotation == Surface.ROTATION_180 ||
        rotation == Surface.ROTATION_270
