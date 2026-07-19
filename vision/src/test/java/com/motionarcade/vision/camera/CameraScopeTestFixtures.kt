package com.motionarcade.vision.camera

import android.view.Surface
import android.view.View
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.IdentityHashMap

/**
 * Real LifecycleRegistry facade that lets a synchronous observer callback finish dispatch before
 * rethrowing its first failure. This deterministically models addObserver storing its observer and
 * then failing after that side effect. Optional remove failure leaves the observer live.
 */
internal class AfterSideEffectResumedLifecycleOwner(
    private val removalFailure: RuntimeException? = null,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val observerWrappers = IdentityHashMap<LifecycleObserver, LifecycleObserver>()
    private val exposedLifecycle =
        object : Lifecycle() {
            override fun addObserver(observer: LifecycleObserver) {
                val eventObserver = observer as? LifecycleEventObserver
                    ?: error("Fixture supports LifecycleEventObserver only")
                var callbackFailure: RuntimeException? = null
                val wrapper =
                    LifecycleEventObserver { source, event ->
                        try {
                            eventObserver.onStateChanged(source, event)
                        } catch (failure: RuntimeException) {
                            if (callbackFailure == null) callbackFailure = failure
                        }
                    }
                observerWrappers[observer] = wrapper
                registry.addObserver(wrapper)
                callbackFailure?.let { throw it }
            }

            override fun removeObserver(observer: LifecycleObserver) {
                removeAttempts += 1
                removalFailure?.let { throw it }
                val wrapper = observerWrappers.remove(observer) ?: observer
                registry.removeObserver(wrapper)
            }

            override fun getCurrentState(): State = registry.currentState
        }

    var removeAttempts: Int = 0
        private set

    val trackedObserverCount: Int
        get() = observerWrappers.size

    init {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun handle(event: Lifecycle.Event) {
        registry.handleLifecycleEvent(event)
    }

    override fun getLifecycle(): Lifecycle = exposedLifecycle
}

internal class MutableCameraHandleFixture(
    cameraIdentity: Any = Any(),
    publicCamera2Id: String? = "front-camera-0",
) {
    var cameraIdentity: Any = cameraIdentity
    var publicCamera2Id: String? = publicCamera2Id
    val handle = BoundCameraHandle({ this.cameraIdentity }, { this.publicCamera2Id })
}

internal class MutablePreviewTransformProvider(
    var snapshot: PreviewSensorToViewTransformSnapshot? = testPreviewSensorToViewTransform(),
) : CameraPreviewTransformProvider {
    override fun current(previewView: PreviewView): PreviewSensorToViewTransformSnapshot? = snapshot
}

internal fun testBoundCameraScopeSnapshot(
    cameraHandle: BoundCameraHandle,
    lens: CameraFrameLens = CameraFrameLens.FRONT,
    analysisWidth: Int = 640,
    analysisHeight: Int = 480,
    analysisCrop: CameraFrameCrop = CameraFrameCrop(0, 0, analysisWidth, analysisHeight),
    analysisRotationDegrees: Int = 0,
    previewWidth: Int = 640,
    previewHeight: Int = 480,
    previewCrop: CameraFrameCrop = CameraFrameCrop(0, 0, previewWidth, previewHeight),
    previewRotationDegrees: Int = analysisRotationDegrees,
    previewSurfaceWidth: Int = 640,
    previewSurfaceHeight: Int = 480,
    previewSensorToViewTransform: PreviewSensorToViewTransformSnapshot =
        testPreviewSensorToViewTransform(),
    targetRotation: Int = Surface.ROTATION_0,
    viewPortAspectNumerator: Int = 4,
    viewPortAspectDenominator: Int = 3,
    viewPortScaleType: Int = ViewPort.FILL_CENTER,
    viewPortLayoutDirection: Int = View.LAYOUT_DIRECTION_LTR,
    previewViewImplementationMode: PreviewView.ImplementationMode =
        PreviewView.ImplementationMode.COMPATIBLE,
    previewViewScaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
): BoundCameraScopeSnapshot =
    BoundCameraScopeSnapshot(
        camera = requireNotNull(cameraHandle.observe()),
        lens = lens,
        analysisWidth = analysisWidth,
        analysisHeight = analysisHeight,
        analysisCrop = analysisCrop,
        analysisRotationDegrees = analysisRotationDegrees,
        analysisTargetRotation = targetRotation,
        previewWidth = previewWidth,
        previewHeight = previewHeight,
        previewCrop = previewCrop,
        previewRotationDegrees = previewRotationDegrees,
        previewTargetRotation = targetRotation,
        previewSurfaceWidth = previewSurfaceWidth,
        previewSurfaceHeight = previewSurfaceHeight,
        previewSensorToViewTransform = previewSensorToViewTransform,
        viewPortAspectNumerator = viewPortAspectNumerator,
        viewPortAspectDenominator = viewPortAspectDenominator,
        viewPortRotation = targetRotation,
        viewPortScaleType = viewPortScaleType,
        viewPortLayoutDirection = viewPortLayoutDirection,
        previewRevision = CAMERA_PREVIEW_REVISION,
        previewViewImplementationMode = previewViewImplementationMode,
        previewViewScaleType = previewViewScaleType,
    )

internal fun testPreviewSensorToViewTransform(
    values: FloatArray =
        floatArrayOf(
            1f,
            0f,
            0f,
            0f,
            1f,
            0f,
            0f,
            0f,
            1f,
        ),
): PreviewSensorToViewTransformSnapshot =
    PreviewSensorToViewTransformSnapshot(
        matrix = ExactMatrix3x3.fromValues(values),
    )
