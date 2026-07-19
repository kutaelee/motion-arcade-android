package com.motionarcade.vision.camera

import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.MainThread
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityIdentity
import com.motionarcade.vision.capability.domain.CapabilityIdentityContract
import com.motionarcade.vision.capability.domain.RequiredDigestProvenance
import com.motionarcade.vision.capability.domain.RequiredDigestToken

internal const val CAMERA_PREVIEW_REVISION = CapabilityIdentityContract.PREVIEW_REVISION

/** Equality is reference identity, even when the wrapped camera overrides equals. */
internal class CameraObjectIdentity(private val reference: Any) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CameraObjectIdentity && reference === other.reference)

    override fun hashCode(): Int = System.identityHashCode(reference)

    override fun toString(): String = "CameraObjectIdentity"
}

internal data class BoundCameraObservation(
    val objectIdentity: CameraObjectIdentity,
    val camera2IdToken: RequiredDigestToken,
) {
    init {
        require(camera2IdToken.provenance == RequiredDigestProvenance.CAMERA2_ID) {
            "Camera observation requires a Camera2 ID token"
        }
    }
}

/**
 * Re-readable handle for one CameraX bind result.
 *
 * The public Camera2 ID is converted immediately to the canonical digest token and is never
 * retained by this object. An exception or non-canonical token source is an unresolved scope.
 */
internal class BoundCameraHandle(
    private val identityReader: () -> Any,
    private val publicCamera2IdReader: () -> String?,
) {
    fun observe(): BoundCameraObservation? =
        try {
            val identity = CameraObjectIdentity(identityReader())
            when (val token = CapabilityIdentity.camera2IdToken(publicCamera2IdReader())) {
                is CapabilityDomainResult.Valid -> BoundCameraObservation(identity, token.value)
                is CapabilityDomainResult.Invalid -> null
            }
        } catch (_: RuntimeException) {
            null
        }
}

/** Immutable exact IEEE-754 representation of an Android 3x3 transform matrix. */
internal class ExactMatrix3x3 private constructor(private val rawBits: IntArray) {
    init {
        require(rawBits.size == MATRIX_VALUE_COUNT) { "A 3x3 matrix requires nine values" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExactMatrix3x3 && rawBits.contentEquals(other.rawBits))

    override fun hashCode(): Int = rawBits.contentHashCode()

    override fun toString(): String = "ExactMatrix3x3"

    companion object {
        private const val MATRIX_VALUE_COUNT = 9

        fun from(matrix: Matrix): ExactMatrix3x3 {
            val values = FloatArray(MATRIX_VALUE_COUNT)
            matrix.getValues(values)
            return fromValues(values)
        }

        internal fun fromValues(values: FloatArray): ExactMatrix3x3 {
            require(values.size == MATRIX_VALUE_COUNT)
            require(values.all(Float::isFinite)) { "Sensor-to-view matrix must be finite" }
            require(isNumericallyInvertible(values)) {
                "Sensor-to-view matrix must be numerically invertible"
            }
            return ExactMatrix3x3(
                IntArray(MATRIX_VALUE_COUNT) { index ->
                    java.lang.Float.floatToRawIntBits(values[index])
                },
            )
        }

        private fun isNumericallyInvertible(values: FloatArray): Boolean {
            val source = Matrix().apply { setValues(values.copyOf()) }
            val inverse = Matrix()
            if (!source.invert(inverse)) return false

            val inverseValues = FloatArray(MATRIX_VALUE_COUNT)
            inverse.getValues(inverseValues)
            if (!inverseValues.all(Float::isFinite)) return false

            val conditionNumber = infinityNorm(values) * infinityNorm(inverseValues)
            return conditionNumber.isFinite() && conditionNumber <= MAX_CONDITION_NUMBER
        }

        private fun infinityNorm(values: FloatArray): Double =
            (0 until MATRIX_VALUE_COUNT step MATRIX_DIMENSION)
                .maxOf { row ->
                    (0 until MATRIX_DIMENSION).sumOf { column ->
                        kotlin.math.abs(values[row + column].toDouble())
                    }
                }

        private const val MATRIX_DIMENSION = 3
        private const val MAX_CONDITION_NUMBER = 1.0e12
    }
}

internal data class PreviewSensorToViewTransformSnapshot(val matrix: ExactMatrix3x3)

internal fun interface CameraPreviewTransformProvider {
    fun current(previewView: PreviewView): PreviewSensorToViewTransformSnapshot?
}

internal object PreviewViewSensorToViewTransformProvider : CameraPreviewTransformProvider {
    override fun current(previewView: PreviewView): PreviewSensorToViewTransformSnapshot? {
        val sensorToViewTransform = previewView.sensorToViewTransform ?: return null
        return PreviewSensorToViewTransformSnapshot(
            matrix = ExactMatrix3x3.from(sensorToViewTransform),
        )
    }
}

/** Full immutable camera/analysis/preview scope used for exact drift comparison. */
internal data class BoundCameraScopeSnapshot(
    val camera: BoundCameraObservation,
    val lens: CameraFrameLens,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val analysisCrop: CameraFrameCrop,
    val analysisRotationDegrees: Int,
    val analysisTargetRotation: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewCrop: CameraFrameCrop,
    val previewRotationDegrees: Int,
    val previewTargetRotation: Int,
    val previewSurfaceWidth: Int,
    val previewSurfaceHeight: Int,
    val previewSensorToViewTransform: PreviewSensorToViewTransformSnapshot,
    val viewPortAspectNumerator: Int,
    val viewPortAspectDenominator: Int,
    val viewPortRotation: Int,
    val viewPortScaleType: Int,
    val viewPortLayoutDirection: Int,
    val previewRevision: String,
    val previewViewImplementationMode: PreviewView.ImplementationMode,
    val previewViewScaleType: PreviewView.ScaleType,
) {
    init {
        require(analysisWidth > 0 && analysisHeight > 0) { "Analysis size must be positive" }
        require(previewWidth > 0 && previewHeight > 0) { "Preview size must be positive" }
        require(previewSurfaceWidth > 0 && previewSurfaceHeight > 0) {
            "Preview surface size must be positive"
        }
        require(analysisCrop.right <= analysisWidth && analysisCrop.bottom <= analysisHeight) {
            "Analysis crop must be contained by its resolution"
        }
        require(previewCrop.right <= previewWidth && previewCrop.bottom <= previewHeight) {
            "Preview crop must be contained by its resolution"
        }
        require(isQuarterTurn(analysisRotationDegrees) && isQuarterTurn(previewRotationDegrees)) {
            "Use-case rotations must be quarter turns"
        }
        require(
            isSurfaceRotation(analysisTargetRotation) &&
                isSurfaceRotation(previewTargetRotation) &&
                isSurfaceRotation(viewPortRotation),
        ) { "Target rotations must be public Surface rotations" }
        require(
            analysisTargetRotation == viewPortRotation && previewTargetRotation == viewPortRotation,
        ) { "Preview and analysis must share the ViewPort target rotation" }
        require(viewPortAspectNumerator > 0 && viewPortAspectDenominator > 0) {
            "ViewPort aspect ratio must be finite and positive"
        }
        require(viewPortScaleType == ViewPort.FILL_CENTER) { "ViewPort must use FILL_CENTER" }
        require(
            viewPortLayoutDirection == View.LAYOUT_DIRECTION_LTR ||
                viewPortLayoutDirection == View.LAYOUT_DIRECTION_RTL,
        ) { "ViewPort layout direction is unsupported" }
        require(previewRevision == CAMERA_PREVIEW_REVISION) { "Preview revision mismatch" }
        require(previewViewImplementationMode == PreviewView.ImplementationMode.COMPATIBLE) {
            "PreviewView implementation mode mismatch"
        }
        require(previewViewScaleType == PreviewView.ScaleType.FILL_CENTER) {
            "PreviewView scale type mismatch"
        }
    }

    fun analysisFrameScope(): CameraFrameScope =
        CameraFrameScope(
            width = analysisWidth,
            height = analysisHeight,
            crop = analysisCrop,
            clockwiseRotationDegrees = analysisRotationDegrees,
            targetSurfaceRotation = analysisTargetRotation,
            lens = lens,
        )
}

internal fun interface BoundCameraScopeResolver {
    fun resolve(input: BoundCameraScopeInput): BoundCameraScopeSnapshot?
}

internal data class BoundCameraScopeInput(
    val cameraHandle: BoundCameraHandle,
    val lens: CameraFrameLens = CameraFrameLens.FRONT,
    val viewPort: ViewPort,
    val useCases: CameraUseCaseBundle,
    val previewSurfaceWidth: Int,
    val previewSurfaceHeight: Int,
    val previewSensorToViewTransform: PreviewSensorToViewTransformSnapshot,
    val previewViewImplementationMode: PreviewView.ImplementationMode,
    val previewViewScaleType: PreviewView.ScaleType,
)

internal object ResolutionInfoScopeResolver : BoundCameraScopeResolver {
    override fun resolve(input: BoundCameraScopeInput): BoundCameraScopeSnapshot? {
        val camera = input.cameraHandle.observe() ?: return null
        val analysisInfo = input.useCases.analysis.resolutionInfo ?: return null
        val previewInfo = input.useCases.preview.resolutionInfo ?: return null
        val analysisSize = analysisInfo.resolution
        val analysisCrop = analysisInfo.cropRect
        val previewSize = previewInfo.resolution
        val previewCrop = previewInfo.cropRect
        val viewPort = input.viewPort
        val aspectRatio = viewPort.aspectRatio
        val targetRotation = viewPort.rotation
        val groupViewPort = input.useCases.group.viewPort ?: return null
        if (
            !sameViewPortConfiguration(groupViewPort, viewPort) ||
            input.useCases.group.useCases.size != 2 ||
            input.useCases.group.useCases[0] !== input.useCases.preview ||
            input.useCases.group.useCases[1] !== input.useCases.analysis ||
            input.useCases.analysis.targetRotation != targetRotation ||
            input.useCases.preview.targetRotation != targetRotation ||
            input.useCases.analysis.backpressureStrategy !=
                androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST ||
            input.useCases.analysis.imageQueueDepth != 1 ||
            input.useCases.analysis.outputImageFormat !=
                androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 ||
            input.useCases.analysis.isOutputImageRotationEnabled ||
            !isQuarterTurn(analysisInfo.rotationDegrees) ||
            analysisInfo.rotationDegrees != previewInfo.rotationDegrees ||
            analysisSize.width <= 0 ||
            analysisSize.height <= 0 ||
            previewSize.width <= 0 ||
            previewSize.height <= 0 ||
            input.previewSurfaceWidth <= 0 ||
            input.previewSurfaceHeight <= 0 ||
            !validCrop(analysisCrop, analysisSize.width, analysisSize.height) ||
            !validCrop(previewCrop, previewSize.width, previewSize.height) ||
            !aspectRatio.isFinite ||
            aspectRatio.numerator <= 0 ||
            aspectRatio.denominator <= 0 ||
            !isSurfaceRotation(targetRotation)
        ) {
            return null
        }
        return try {
            BoundCameraScopeSnapshot(
                camera = camera,
                lens = input.lens,
                analysisWidth = analysisSize.width,
                analysisHeight = analysisSize.height,
                analysisCrop = analysisCrop.toImmutableCrop(),
                analysisRotationDegrees = analysisInfo.rotationDegrees,
                analysisTargetRotation = input.useCases.analysis.targetRotation,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
                previewCrop = previewCrop.toImmutableCrop(),
                previewRotationDegrees = previewInfo.rotationDegrees,
                previewTargetRotation = input.useCases.preview.targetRotation,
                previewSurfaceWidth = input.previewSurfaceWidth,
                previewSurfaceHeight = input.previewSurfaceHeight,
                previewSensorToViewTransform = input.previewSensorToViewTransform,
                viewPortAspectNumerator = aspectRatio.numerator,
                viewPortAspectDenominator = aspectRatio.denominator,
                viewPortRotation = targetRotation,
                viewPortScaleType = viewPort.scaleType,
                viewPortLayoutDirection = viewPort.layoutDirection,
                previewRevision = CAMERA_PREVIEW_REVISION,
                previewViewImplementationMode = input.previewViewImplementationMode,
                previewViewScaleType = input.previewViewScaleType,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun sameViewPortConfiguration(left: ViewPort, right: ViewPort): Boolean =
        left.aspectRatio == right.aspectRatio &&
            left.rotation == right.rotation &&
            left.scaleType == right.scaleType &&
            left.layoutDirection == right.layoutDirection

    private fun validCrop(
        crop: android.graphics.Rect,
        width: Int,
        height: Int,
    ): Boolean =
        crop.left >= 0 &&
            crop.top >= 0 &&
            crop.right > crop.left &&
            crop.bottom > crop.top &&
            crop.right <= width &&
            crop.bottom <= height

    private fun android.graphics.Rect.toImmutableCrop(): CameraFrameCrop =
        CameraFrameCrop(left, top, right, bottom)
}

internal enum class CameraScopeWatchEvent {
    VALIDATE,
    INVALIDATE,
}

internal interface CameraScopeWatcher {
    @MainThread
    fun start(lifecycleOwner: LifecycleOwner)

    /** Returns false if any listener/observer removal was uncertain. */
    @MainThread
    fun stop(): Boolean
}

internal fun interface CameraScopeWatcherFactory {
    fun create(
        previewView: PreviewView,
        onEvent: (CameraScopeWatchEvent) -> Unit,
    ): CameraScopeWatcher
}

/** Once listener removal is uncertain, no later empty/idempotent stop may report clean. */
internal class StickyCameraScopeCleanup {
    private var poisoned = false

    fun beginStop(): Boolean = !poisoned

    fun finishStop(clean: Boolean): Boolean {
        if (!clean) poisoned = true
        return !poisoned
    }
}

internal class PlatformCameraScopeWatcherFactory(private val context: Context) :
    CameraScopeWatcherFactory {
    override fun create(
        previewView: PreviewView,
        onEvent: (CameraScopeWatchEvent) -> Unit,
    ): CameraScopeWatcher =
        PlatformCameraScopeWatcher(
            previewView = previewView,
            displayManager = requireNotNull(context.getSystemService(DisplayManager::class.java)),
            onEvent = onEvent,
        )
}

/**
 * CameraX exposes no reliable PreviewView transform-change listener. The controller binds the
 * public sensor-to-PreviewView matrix at STREAMING and re-reads it on persistent layout, attach,
 * display, lifecycle, and stream-state events. In pinned CameraX 1.6.1
 * OutputTransform.getMatrix() is LIBRARY_GROUP restricted and its ViewPort size is package-private,
 * so neither is accessed or suppressed. Public PreviewView surface size, exact ViewPort fields,
 * ResolutionInfo, and sensorToViewTransform are bound instead. This is not claimed to be the
 * inaccessible OutputTransform matrix. A transform mutation that produces none of these observable
 * events remains unobservable and cannot be physical-overlay completion evidence.
 */
private class PlatformCameraScopeWatcher(
    private val previewView: PreviewView,
    private val displayManager: DisplayManager,
    private val onEvent: (CameraScopeWatchEvent) -> Unit,
) : CameraScopeWatcher {
    private var lifecycleOwner: LifecycleOwner? = null
    private var layoutRemovalRequired = false
    private var attachRemovalRequired = false
    private var displayRemovalRequired = false
    private var lifecycleRemovalRequired = false
    private var started = false
    private val cleanup = StickyCameraScopeCleanup()

    private val layoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            onEvent(CameraScopeWatchEvent.VALIDATE)
        }
    private val attachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                onEvent(CameraScopeWatchEvent.VALIDATE)
            }

            override fun onViewDetachedFromWindow(view: View) {
                onEvent(CameraScopeWatchEvent.INVALIDATE)
            }
        }
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                onEvent(CameraScopeWatchEvent.VALIDATE)
            }

            override fun onDisplayRemoved(displayId: Int) {
                onEvent(CameraScopeWatchEvent.VALIDATE)
            }

            override fun onDisplayChanged(displayId: Int) {
                onEvent(CameraScopeWatchEvent.VALIDATE)
            }
        }
    private val lifecycleObserver =
        LifecycleEventObserver { _, event ->
            onEvent(
                when (event) {
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP,
                    Lifecycle.Event.ON_DESTROY,
                    -> CameraScopeWatchEvent.INVALIDATE
                    else -> CameraScopeWatchEvent.VALIDATE
                },
            )
        }

    @MainThread
    override fun start(lifecycleOwner: LifecycleOwner) {
        check(!started) { "Scope watcher is one-use" }
        started = true
        this.lifecycleOwner = lifecycleOwner
        try {
            // Mark removal as required before each side-effecting registration. Registration can
            // store the callback and then throw while synchronously dispatching it; recording only
            // after return would let that partial install escape cleanup as a false clean result.
            layoutRemovalRequired = true
            previewView.addOnLayoutChangeListener(layoutListener)
            attachRemovalRequired = true
            previewView.addOnAttachStateChangeListener(attachListener)
            displayRemovalRequired = true
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            lifecycleRemovalRequired = true
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        } catch (failure: RuntimeException) {
            stop()
            throw failure
        }
    }

    @MainThread
    override fun stop(): Boolean {
        var clean = cleanup.beginStop()
        if (layoutRemovalRequired) {
            clean = removeSafely { previewView.removeOnLayoutChangeListener(layoutListener) } && clean
            layoutRemovalRequired = false
        }
        if (attachRemovalRequired) {
            clean = removeSafely { previewView.removeOnAttachStateChangeListener(attachListener) } && clean
            attachRemovalRequired = false
        }
        if (displayRemovalRequired) {
            clean = removeSafely { displayManager.unregisterDisplayListener(displayListener) } && clean
            displayRemovalRequired = false
        }
        if (lifecycleRemovalRequired) {
            clean =
                removeSafely { lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver) } && clean
            lifecycleRemovalRequired = false
        }
        lifecycleOwner = null
        return cleanup.finishStop(clean)
    }

    private inline fun removeSafely(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: RuntimeException) {
            false
        }
}
