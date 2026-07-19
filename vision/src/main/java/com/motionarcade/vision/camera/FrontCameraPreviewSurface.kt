package com.motionarcade.vision.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.motionarcade.vision.motion.FishingMotionConfig
import com.motionarcade.vision.motion.FishingMotionFrameSink
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrameSink
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import com.motionarcade.vision.motion.SoloCombatMotionFrameSink
import com.motionarcade.vision.motion.supportsSoloCombat
import com.motionarcade.vision.pose.DualPlayerCombatMotionBridge
import com.motionarcade.vision.pose.DualPlayerRoleSetupPolicy
import com.motionarcade.vision.pose.DualPlayerTrackingBridge
import com.motionarcade.vision.pose.DualPlayerTrackingSink
import com.motionarcade.vision.pose.FishingLiveMotionBridge
import com.motionarcade.vision.pose.SoloCombatMotionBridge
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseObservationCoordinator
import com.motionarcade.vision.pose.LivePoseObservationDeliveryGate
import com.motionarcade.vision.pose.LivePoseObservationDispatcherFactory
import com.motionarcade.vision.pose.LivePoseObservationSink
import com.motionarcade.vision.pose.LivePoseSemanticSink
import com.motionarcade.vision.pose.LivePoseSemanticStateStore
import com.motionarcade.vision.pose.LivePoseSemanticSummary
import com.motionarcade.vision.pose.LivePoseSessionFactory
import com.motionarcade.vision.pose.LivePoseTemporalContinuityGate
import com.motionarcade.vision.pose.MediaPipeLivePoseSessionFactory
import com.motionarcade.vision.tracking.LoadedPlayerTrackerConfig
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Finite, privacy-safe presentation state. Pose inference is reported through a separate sink. */
enum class FrontCameraPreviewStatus {
    IDLE,
    STARTING,
    ACTIVE,
    PERMISSION_MISSING,
    FRONT_CAMERA_UNAVAILABLE,
    BACK_CAMERA_UNAVAILABLE,
    BIND_FAILED,
    RELEASED,
}

/** Public camera choice without exposing CameraX selectors across the vision boundary. */
enum class CameraLensSelection {
    FRONT,
    BACK,
}

/** Process-local generation and scope gate shared by provider callbacks and the analyzer owner. */
internal class CameraSessionRequestGate(initialGeneration: Long = 0L) {
    class Token internal constructor(internal val generation: Long)

    private class ActiveSession(
        val token: Token,
        val cameraHandle: BoundCameraHandle,
        val scope: BoundCameraScopeSnapshot,
        var nextFrameOrdinal: Long = 0L,
        var lastTimestampNanos: Long? = null,
    )

    private var generation = initialGeneration
    private var current: Token? = null
    private var active: ActiveSession? = null

    @Synchronized
    fun begin(): Token {
        generation = Math.incrementExact(generation)
        return Token(generation).also {
            current = it
            active = null
        }
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean = current === token

    /**
     * Runs one semantic delivery under the same generation lock used by [begin], [cancel], and
     * [invalidate]. A completed rebind therefore proves that no old-generation callback can begin
     * after it, including the check-to-callback race that a standalone [isCurrent] check leaves.
     */
    @Synchronized
    fun deliverIfCurrent(token: Token, delivery: () -> Unit): Boolean {
        if (current !== token) return false
        delivery()
        return true
    }

    @Synchronized
    fun activate(
        token: Token,
        cameraHandle: BoundCameraHandle,
        scope: BoundCameraScopeSnapshot,
    ): Boolean {
        if (current !== token) return false
        active = ActiveSession(token = token, cameraHandle = cameraHandle, scope = scope)
        return true
    }

    /** Validates the re-read camera token outside the gate lock, then atomically numbers a frame. */
    fun prepare(
        token: Token,
        observed: ObservedCameraFrame,
    ): CameraFrameDelivery {
        val capturedSession =
            synchronized(this) {
                val session = active
                if (current !== token || session == null || session.token !== token) {
                    return CameraFrameDelivery.StaleGeneration
                }
                session
            }
        val cameraObservation = capturedSession.cameraHandle.observe()
        return synchronized(this) {
            val session = active
            if (
                current !== token ||
                session == null ||
                session !== capturedSession ||
                session.token !== token
            ) {
                return@synchronized CameraFrameDelivery.StaleGeneration
            }
            if (cameraObservation == null || cameraObservation != session.scope.camera) {
                return@synchronized CameraFrameDelivery.MetadataMismatch
            }
            prepareFrame(session, observed)
        }
    }

    private fun prepareFrame(
        session: ActiveSession,
        observed: ObservedCameraFrame,
    ): CameraFrameDelivery {
        val scope = session.scope.analysisFrameScope()
        val previousTimestamp = session.lastTimestampNanos
        if (
            observed.timestampNanos < 0L ||
            observed.width != scope.width ||
            observed.height != scope.height ||
            observed.crop != scope.crop ||
            observed.clockwiseRotationDegrees != scope.clockwiseRotationDegrees ||
            (previousTimestamp != null && observed.timestampNanos <= previousTimestamp) ||
            session.nextFrameOrdinal == Long.MAX_VALUE
        ) {
            return CameraFrameDelivery.MetadataMismatch
        }

        val ordinal = session.nextFrameOrdinal
        val metadata =
            CameraFrameMetadata(
                sessionGeneration = session.token.generation,
                frameOrdinal = ordinal,
                sourceTimestampNanos = observed.timestampNanos,
                analysisWidth = observed.width,
                analysisHeight = observed.height,
                crop = observed.crop,
                clockwiseRotationDegrees = observed.clockwiseRotationDegrees,
                targetSurfaceRotation = scope.targetSurfaceRotation,
                lens = scope.lens,
                analysisMirrored = false,
                previewMirrored = scope.lens == CameraFrameLens.FRONT,
            )
        session.lastTimestampNanos = observed.timestampNanos
        session.nextFrameOrdinal = ordinal + 1L
        return CameraFrameDelivery.Ready(metadata)
    }

    @Synchronized
    fun cancel() {
        current = null
        active = null
    }

    @Synchronized
    fun invalidate(token: Token) {
        if (current !== token) return
        current = null
        active = null
    }
}

internal fun streamStatus(state: PreviewView.StreamState): FrontCameraPreviewStatus =
    when (state) {
        PreviewView.StreamState.IDLE -> FrontCameraPreviewStatus.STARTING
        PreviewView.StreamState.STREAMING -> FrontCameraPreviewStatus.ACTIVE
    }

internal interface CameraStreamStateSource {
    fun observe(
        lifecycleOwner: LifecycleOwner,
        observer: Observer<PreviewView.StreamState>,
    )

    fun remove(observer: Observer<PreviewView.StreamState>)
}

private class PreviewViewCameraStreamStateSource(private val previewView: PreviewView) :
    CameraStreamStateSource {
    override fun observe(
        lifecycleOwner: LifecycleOwner,
        observer: Observer<PreviewView.StreamState>,
    ) {
        previewView.previewStreamState.observe(lifecycleOwner, observer)
    }

    override fun remove(observer: Observer<PreviewView.StreamState>) {
        previewView.previewStreamState.removeObserver(observer)
    }
}

/**
 * Front-camera presentation plus on-device, aggregate-only live pose inference.
 *
 * The existing trailing-lambda bind call remains source compatible. Pixels and landmarks never
 * leave :vision; the public inference sink receives only 0/1/2 pose and callback state.
 */
class FrontCameraPreviewSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val controller =
        CameraSessionController(
            context = context.applicationContext,
            previewView = previewView,
            livePoseSessionFactory = MediaPipeLivePoseSessionFactory(context.applicationContext),
        )

    init {
        addView(
            previewView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink = CameraFrameMetadataSink.NONE,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(lifecycleOwner, metadataSink, onStatus)
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(lifecycleOwner, metadataSink, inferenceSink, onStatus)
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(lifecycleOwner, metadataSink, inferenceSink, semanticSink, onStatus)
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        fishingMotionConfig: FishingMotionConfig,
        fishingMotionSink: FishingMotionFrameSink,
        onMotionBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            fishingMotionConfig,
            fishingMotionSink,
            onMotionBindingStarted,
            onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig,
        soloCombatMotionSink: SoloCombatMotionFrameSink,
        onSoloCombatBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
        onSoloCombatCallbackError: (Long) -> Unit = {},
    ) {
        controller.bind(
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            soloCombatMotionConfig = soloCombatMotionConfig,
            soloCombatMotionSink = soloCombatMotionSink,
            onSoloCombatBindingStarted = onSoloCombatBindingStarted,
            onStatus = onStatus,
            onSoloCombatCallbackError = onSoloCombatCallbackError,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig,
        roleSetupPolicy: DualPlayerRoleSetupPolicy,
        calibrationRevision: Long,
        dualPlayerTrackingSink: DualPlayerTrackingSink,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        onDualPlayerTrackingBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            dualPlayerTrackingConfig = dualPlayerTrackingConfig,
            roleSetupPolicy = roleSetupPolicy,
            calibrationRevision = calibrationRevision,
            dualPlayerTrackingSink = dualPlayerTrackingSink,
            dualPlayerCombatMotionConfig = dualPlayerCombatMotionConfig,
            dualPlayerCombatMotionSink = dualPlayerCombatMotionSink,
            onDualPlayerTrackingBindingStarted = onDualPlayerTrackingBindingStarted,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink = CameraFrameMetadataSink.NONE,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(lifecycleOwner, lensSelection, metadataSink, onStatus)
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(lifecycleOwner, lensSelection, metadataSink, inferenceSink, onStatus)
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(
            lifecycleOwner,
            lensSelection,
            metadataSink,
            inferenceSink,
            semanticSink,
            onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        fishingMotionConfig: FishingMotionConfig,
        fishingMotionSink: FishingMotionFrameSink,
        onMotionBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(
            lifecycleOwner,
            lensSelection,
            metadataSink,
            inferenceSink,
            fishingMotionConfig,
            fishingMotionSink,
            onMotionBindingStarted,
            onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig,
        soloCombatMotionSink: SoloCombatMotionFrameSink,
        onSoloCombatBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
        onSoloCombatCallbackError: (Long) -> Unit = {},
    ) {
        controller.bind(
            lifecycleOwner = lifecycleOwner,
            lensSelection = lensSelection,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            soloCombatMotionConfig = soloCombatMotionConfig,
            soloCombatMotionSink = soloCombatMotionSink,
            onSoloCombatBindingStarted = onSoloCombatBindingStarted,
            onStatus = onStatus,
            onSoloCombatCallbackError = onSoloCombatCallbackError,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig,
        roleSetupPolicy: DualPlayerRoleSetupPolicy,
        calibrationRevision: Long,
        dualPlayerTrackingSink: DualPlayerTrackingSink,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        onDualPlayerTrackingBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        controller.bind(
            lifecycleOwner = lifecycleOwner,
            lensSelection = lensSelection,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            dualPlayerTrackingConfig = dualPlayerTrackingConfig,
            roleSetupPolicy = roleSetupPolicy,
            calibrationRevision = calibrationRevision,
            dualPlayerTrackingSink = dualPlayerTrackingSink,
            dualPlayerCombatMotionConfig = dualPlayerCombatMotionConfig,
            dualPlayerCombatMotionSink = dualPlayerCombatMotionSink,
            onDualPlayerTrackingBindingStarted = onDualPlayerTrackingBindingStarted,
            onStatus = onStatus,
        )
    }

    fun latestPoseSemanticSummary(): LivePoseSemanticSummary? =
        controller.latestPoseSemanticSummary()

    fun frameCounters(): CameraFrameCounterSnapshot = controller.frameCounters()

    @MainThread
    fun requestDualPlayerRearm(
        expectedGeneration: Long,
        expectedRearmChallengeId: Long,
    ): Boolean = controller.requestDualPlayerRearm(expectedGeneration, expectedRearmChallengeId)

    @MainThread
    fun release() {
        controller.release()
    }
}

internal fun interface CameraPermissionChecker {
    fun isGranted(): Boolean
}

internal fun interface CameraViewPortProvider {
    fun current(previewView: PreviewView): ViewPort?
}

internal interface CameraViewPortLayoutListenerSource {
    fun add(listener: View.OnLayoutChangeListener)

    fun remove(listener: View.OnLayoutChangeListener)
}

private class PreviewViewPortLayoutListenerSource(private val previewView: PreviewView) :
    CameraViewPortLayoutListenerSource {
    override fun add(listener: View.OnLayoutChangeListener) {
        previewView.addOnLayoutChangeListener(listener)
    }

    override fun remove(listener: View.OnLayoutChangeListener) {
        previewView.removeOnLayoutChangeListener(listener)
    }
}

internal interface CameraProviderSession {
    fun hasFrontCamera(): Boolean

    fun bindFront(
        lifecycleOwner: LifecycleOwner,
        useCaseGroup: UseCaseGroup,
    ): BoundCameraHandle

    /** Defaults preserve source compatibility for existing front-only test and platform adapters. */
    fun hasCamera(lensSelection: CameraLensSelection): Boolean =
        lensSelection == CameraLensSelection.FRONT && hasFrontCamera()

    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        useCaseGroup: UseCaseGroup,
    ): BoundCameraHandle =
        when (lensSelection) {
            CameraLensSelection.FRONT -> bindFront(lifecycleOwner, useCaseGroup)
            CameraLensSelection.BACK -> error("Back camera binding is unsupported by this provider")
        }

    fun unbind(useCases: Array<out UseCase>)
}

internal fun interface CameraProviderGateway {
    fun request(
        callbackExecutor: Executor,
        callback: (Result<CameraProviderSession>) -> Unit,
    )
}

private class ProcessCameraProviderGateway(private val context: Context) : CameraProviderGateway {
    override fun request(
        callbackExecutor: Executor,
        callback: (Result<CameraProviderSession>) -> Unit,
    ) {
        val future =
            try {
                ProcessCameraProvider.getInstance(context)
            } catch (failure: RuntimeException) {
                callbackExecutor.execute { callback(Result.failure(failure)) }
                return
            }
        future.addListener(
            {
                val result =
                    try {
                        Result.success(ProcessCameraProviderSession(future.get()))
                    } catch (failure: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Result.failure(failure)
                    } catch (failure: ExecutionException) {
                        Result.failure(failure.cause ?: failure)
                    } catch (failure: RuntimeException) {
                        Result.failure(failure)
                    }
                callback(result)
            },
            callbackExecutor,
        )
    }
}

private class ProcessCameraProviderSession(
    private val provider: ProcessCameraProvider,
) : CameraProviderSession {
    override fun hasFrontCamera(): Boolean = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)

    override fun hasCamera(lensSelection: CameraLensSelection): Boolean =
        provider.hasCamera(lensSelection.cameraSelector())

    @ExperimentalCamera2Interop
    override fun bindFront(
        lifecycleOwner: LifecycleOwner,
        useCaseGroup: UseCaseGroup,
    ): BoundCameraHandle {
        val camera: Camera =
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                useCaseGroup,
            )
        return BoundCameraHandle(
            identityReader = { camera },
            publicCamera2IdReader = { Camera2CameraInfo.from(camera.cameraInfo).cameraId },
        )
    }

    @ExperimentalCamera2Interop
    override fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        useCaseGroup: UseCaseGroup,
    ): BoundCameraHandle {
        val camera: Camera =
            provider.bindToLifecycle(
                lifecycleOwner,
                lensSelection.cameraSelector(),
                useCaseGroup,
            )
        return BoundCameraHandle(
            identityReader = { camera },
            publicCamera2IdReader = { Camera2CameraInfo.from(camera.cameraInfo).cameraId },
        )
    }

    override fun unbind(useCases: Array<out UseCase>) {
        provider.unbind(*useCases)
    }
}

private fun CameraLensSelection.cameraSelector(): CameraSelector =
    when (this) {
        CameraLensSelection.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        CameraLensSelection.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
    }

private fun CameraLensSelection.frameLens(): CameraFrameLens =
    when (this) {
        CameraLensSelection.FRONT -> CameraFrameLens.FRONT
        CameraLensSelection.BACK -> CameraFrameLens.BACK
    }

internal interface CameraAnalysisExecutorOwner {
    val executor: Executor

    fun release()
}

private class SingleCameraAnalysisExecutorOwner : CameraAnalysisExecutorOwner {
    private val service: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MotionArcade-CameraAnalysis").apply { isDaemon = true }
        }

    override val executor: Executor = service

    override fun release() {
        service.shutdown()
    }
}

/** Sole owner of provider requests, preview, analysis, generation state, and the analysis executor. */
internal class CameraSessionController(
    context: Context,
    private val previewView: PreviewView,
    private val streamStateSource: CameraStreamStateSource =
        PreviewViewCameraStreamStateSource(previewView),
    private val permissionChecker: CameraPermissionChecker =
        CameraPermissionChecker {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        },
    private val providerGateway: CameraProviderGateway = ProcessCameraProviderGateway(context),
    private val viewPortProvider: CameraViewPortProvider = CameraViewPortProvider(PreviewView::getViewPort),
    private val viewPortLayoutListenerSource: CameraViewPortLayoutListenerSource =
        PreviewViewPortLayoutListenerSource(previewView),
    private val scopeResolver: BoundCameraScopeResolver = ResolutionInfoScopeResolver,
    private val previewTransformProvider: CameraPreviewTransformProvider =
        PreviewViewSensorToViewTransformProvider,
    private val scopeWatcherFactory: CameraScopeWatcherFactory =
        PlatformCameraScopeWatcherFactory(context),
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context),
    private val analysisExecutorOwner: CameraAnalysisExecutorOwner = SingleCameraAnalysisExecutorOwner(),
    private val requests: CameraSessionRequestGate = CameraSessionRequestGate(),
    private val livePoseSessionFactory: LivePoseSessionFactory = LivePoseSessionFactory.UNAVAILABLE,
    private val observationDispatcherFactory: LivePoseObservationDispatcherFactory =
        LivePoseObservationDispatcherFactory.SYSTEM,
    private val useCaseFactory:
        (ViewPort, Executor, androidx.camera.core.ImageAnalysis.Analyzer) -> CameraUseCaseBundle =
        CameraUseCaseFactory::create,
) {
    private val counters = CameraFrameCounters()
    private val poseReducer = LivePoseGenerationReducer()
    private val poseContinuityGate = LivePoseTemporalContinuityGate()
    private val poseSemanticStateStore = LivePoseSemanticStateStore()
    private var boundProvider: CameraProviderSession? = null
    private var boundUseCases: CameraUseCaseBundle? = null
    private var boundCameraHandle: BoundCameraHandle? = null
    private var boundLensSelection: CameraLensSelection? = null
    private var boundScopeSnapshot: BoundCameraScopeSnapshot? = null
    private var boundToken: CameraSessionRequestGate.Token? = null
    private var pendingUseCases: CameraUseCaseBundle? = null
    private var boundLivePoseAnalyzer: LivePoseImageAnalysisAnalyzer? = null
    private var pendingLivePoseAnalyzer: LivePoseImageAnalysisAnalyzer? = null
    private var dualPlayerTrackingBridge: DualPlayerTrackingBridge? = null
    private var streamObserver: Observer<PreviewView.StreamState>? = null
    private var scopeWatcher: CameraScopeWatcher? = null
    private var viewPortListener: View.OnLayoutChangeListener? = null
    private var viewPortListenerRemovalRequired = false
    private var viewPortListenerReady = false
    private var statusSink: ((FrontCameraPreviewStatus) -> Unit)? = null
    private var soloCombatCallbackErrorSink: ((Long) -> Unit)? = null
    private var cleanupPoisoned = false
    private var released = false

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner,
            metadataSink,
            LivePoseInferenceSink.NONE,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig,
        soloCombatMotionSink: SoloCombatMotionFrameSink,
        onSoloCombatBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
        onSoloCombatCallbackError: (Long) -> Unit = {},
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = soloCombatMotionConfig,
            soloCombatMotionSink = soloCombatMotionSink,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = onSoloCombatBindingStarted,
            onSoloCombatCallbackError = onSoloCombatCallbackError,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            semanticSink,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        fishingMotionConfig: FishingMotionConfig,
        fishingMotionSink: FishingMotionFrameSink,
        onMotionBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            semanticSink = null,
            fishingMotionConfig = fishingMotionConfig,
            fishingMotionSink = fishingMotionSink,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = onMotionBindingStarted,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig,
        roleSetupPolicy: DualPlayerRoleSetupPolicy,
        calibrationRevision: Long,
        dualPlayerTrackingSink: DualPlayerTrackingSink,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        onDualPlayerTrackingBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = onDualPlayerTrackingBindingStarted,
            dualPlayerTrackingConfig = dualPlayerTrackingConfig,
            roleSetupPolicy = roleSetupPolicy,
            calibrationRevision = calibrationRevision,
            dualPlayerTrackingSink = dualPlayerTrackingSink,
            dualPlayerCombatMotionConfig = dualPlayerCombatMotionConfig,
            dualPlayerCombatMotionSink = dualPlayerCombatMotionSink,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = LivePoseInferenceSink.NONE,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            lensSelection = lensSelection,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            lensSelection = lensSelection,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = semanticSink,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            lensSelection = lensSelection,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        fishingMotionConfig: FishingMotionConfig,
        fishingMotionSink: FishingMotionFrameSink,
        onMotionBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = null,
            fishingMotionConfig = fishingMotionConfig,
            fishingMotionSink = fishingMotionSink,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = onMotionBindingStarted,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            lensSelection = lensSelection,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig,
        soloCombatMotionSink: SoloCombatMotionFrameSink,
        onSoloCombatBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
        onSoloCombatCallbackError: (Long) -> Unit = {},
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = soloCombatMotionConfig,
            soloCombatMotionSink = soloCombatMotionSink,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = onSoloCombatBindingStarted,
            onSoloCombatCallbackError = onSoloCombatCallbackError,
            onDualPlayerTrackingBindingStarted = null,
            dualPlayerTrackingConfig = null,
            roleSetupPolicy = null,
            calibrationRevision = null,
            dualPlayerTrackingSink = null,
            dualPlayerCombatMotionConfig = null,
            dualPlayerCombatMotionSink = null,
            lensSelection = lensSelection,
            onStatus = onStatus,
        )
    }

    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig,
        roleSetupPolicy: DualPlayerRoleSetupPolicy,
        calibrationRevision: Long,
        dualPlayerTrackingSink: DualPlayerTrackingSink,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        onDualPlayerTrackingBindingStarted: (Long) -> Unit,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        bindInternal(
            lifecycleOwner = lifecycleOwner,
            metadataSink = metadataSink,
            inferenceSink = inferenceSink,
            semanticSink = null,
            fishingMotionConfig = null,
            fishingMotionSink = null,
            soloCombatMotionConfig = null,
            soloCombatMotionSink = null,
            onMotionBindingStarted = null,
            onSoloCombatBindingStarted = null,
            onDualPlayerTrackingBindingStarted = onDualPlayerTrackingBindingStarted,
            dualPlayerTrackingConfig = dualPlayerTrackingConfig,
            roleSetupPolicy = roleSetupPolicy,
            calibrationRevision = calibrationRevision,
            dualPlayerTrackingSink = dualPlayerTrackingSink,
            dualPlayerCombatMotionConfig = dualPlayerCombatMotionConfig,
            dualPlayerCombatMotionSink = dualPlayerCombatMotionSink,
            lensSelection = lensSelection,
            onStatus = onStatus,
        )
    }

    @MainThread
    private fun bindInternal(
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink?,
        fishingMotionConfig: FishingMotionConfig?,
        fishingMotionSink: FishingMotionFrameSink?,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig?,
        soloCombatMotionSink: SoloCombatMotionFrameSink?,
        onMotionBindingStarted: ((Long) -> Unit)?,
        onSoloCombatBindingStarted: ((Long) -> Unit)?,
        onSoloCombatCallbackError: ((Long) -> Unit)? = null,
        onDualPlayerTrackingBindingStarted: ((Long) -> Unit)?,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig?,
        roleSetupPolicy: DualPlayerRoleSetupPolicy?,
        calibrationRevision: Long?,
        dualPlayerTrackingSink: DualPlayerTrackingSink?,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        lensSelection: CameraLensSelection = CameraLensSelection.FRONT,
        onStatus: (FrontCameraPreviewStatus) -> Unit,
    ) {
        statusSink = onStatus
        soloCombatCallbackErrorSink = onSoloCombatCallbackError
        if (
            (fishingMotionConfig == null) != (fishingMotionSink == null) ||
                (soloCombatMotionConfig == null) != (soloCombatMotionSink == null) ||
                (soloCombatMotionConfig == null) != (onSoloCombatBindingStarted == null) ||
                (soloCombatMotionConfig == null) != (onSoloCombatCallbackError == null)
        ) {
            emit(FrontCameraPreviewStatus.BIND_FAILED)
            return
        }
        if (
            (dualPlayerTrackingConfig == null) != (roleSetupPolicy == null) ||
                (dualPlayerTrackingConfig == null) != (calibrationRevision == null) ||
                (dualPlayerTrackingConfig == null) != (dualPlayerTrackingSink == null) ||
                (dualPlayerTrackingConfig == null) != (onDualPlayerTrackingBindingStarted == null) ||
                (dualPlayerCombatMotionConfig == null) != (dualPlayerCombatMotionSink == null) ||
                (dualPlayerCombatMotionConfig != null && dualPlayerTrackingConfig == null) ||
                (dualPlayerCombatMotionConfig != null &&
                    dualPlayerCombatMotionConfig.calibrationRevision.toLong() != calibrationRevision) ||
                (soloCombatMotionConfig != null &&
                    !isSoloCombatBindingProfileSupported(soloCombatMotionConfig.profile)) ||
                listOf(
                    fishingMotionConfig != null,
                    soloCombatMotionConfig != null,
                    dualPlayerTrackingConfig != null,
                ).count { it } > 1
        ) {
            emit(FrontCameraPreviewStatus.BIND_FAILED)
            return
        }
        if (released) {
            emit(FrontCameraPreviewStatus.RELEASED)
            return
        }
        if (!releaseBinding(LivePoseInferencePhase.RELEASED)) {
            emit(FrontCameraPreviewStatus.BIND_FAILED)
            return
        }
        if (!permissionChecker.isGranted()) {
            emit(FrontCameraPreviewStatus.PERMISSION_MISSING)
            return
        }

        val token =
            try {
                requests.begin()
            } catch (_: ArithmeticException) {
                released = true
                analysisExecutorOwner.release()
                emit(FrontCameraPreviewStatus.BIND_FAILED)
                return
            }
        if (onMotionBindingStarted != null) {
            try {
                onMotionBindingStarted(token.generation)
            } catch (_: RuntimeException) {
                requests.invalidate(token)
                emit(FrontCameraPreviewStatus.BIND_FAILED)
                return
            }
        }
        if (onSoloCombatBindingStarted != null) {
            try {
                onSoloCombatBindingStarted(token.generation)
            } catch (_: RuntimeException) {
                requests.invalidate(token)
                emit(FrontCameraPreviewStatus.BIND_FAILED)
                return
            }
        }
        if (onDualPlayerTrackingBindingStarted != null) {
            try {
                onDualPlayerTrackingBindingStarted(token.generation)
            } catch (_: RuntimeException) {
                requests.invalidate(token)
                emit(FrontCameraPreviewStatus.BIND_FAILED)
                return
            }
        }
        poseReducer.begin(token, inferenceSink)
        emit(FrontCameraPreviewStatus.STARTING)
        continueWhenViewPortReady(
            token,
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            semanticSink,
            fishingMotionConfig,
            fishingMotionSink,
            soloCombatMotionConfig,
            soloCombatMotionSink,
            dualPlayerTrackingConfig,
            roleSetupPolicy,
            calibrationRevision,
            dualPlayerTrackingSink,
            dualPlayerCombatMotionConfig,
            dualPlayerCombatMotionSink,
            lensSelection,
        )
    }

    fun frameCounters(): CameraFrameCounterSnapshot = counters.snapshot()

    fun latestPoseSemanticSummary(): LivePoseSemanticSummary? = poseSemanticStateStore.snapshot()

    @MainThread
    fun requestDualPlayerRearm(
        expectedGeneration: Long,
        expectedRearmChallengeId: Long,
    ): Boolean =
        dualPlayerTrackingBridge?.requestRearm(expectedGeneration, expectedRearmChallengeId) ?: false

    @MainThread
    fun release() {
        if (released) return
        released = true
        val cleanRelease = releaseBinding(LivePoseInferencePhase.RELEASED)
        // releaseBinding revokes frame admission and queues Pose Landmarker close behind any
        // already-running analyzer callback. shutdown then drains that queue without new work.
        analysisExecutorOwner.release()
        emit(if (cleanRelease) FrontCameraPreviewStatus.RELEASED else FrontCameraPreviewStatus.BIND_FAILED)
        statusSink = null
        soloCombatCallbackErrorSink = null
    }

    @MainThread
    private fun continueWhenViewPortReady(
        token: CameraSessionRequestGate.Token,
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink?,
        fishingMotionConfig: FishingMotionConfig?,
        fishingMotionSink: FishingMotionFrameSink?,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig?,
        soloCombatMotionSink: SoloCombatMotionFrameSink?,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig?,
        roleSetupPolicy: DualPlayerRoleSetupPolicy?,
        calibrationRevision: Long?,
        dualPlayerTrackingSink: DualPlayerTrackingSink?,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        lensSelection: CameraLensSelection,
    ) {
        if (!requests.isCurrent(token)) return
        val viewPort = viewPortProvider.current(previewView)
        if (viewPort == null) {
            if (viewPortListener == null) {
                val listener =
                    View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        if (viewPortListenerReady) {
                            continueWhenViewPortReady(
                                token,
                                lifecycleOwner,
                                metadataSink,
                                inferenceSink,
                                semanticSink,
                                fishingMotionConfig,
                                fishingMotionSink,
                                soloCombatMotionConfig,
                                soloCombatMotionSink,
                                dualPlayerTrackingConfig,
                                roleSetupPolicy,
                                calibrationRevision,
                                dualPlayerTrackingSink,
                                dualPlayerCombatMotionConfig,
                                dualPlayerCombatMotionSink,
                                lensSelection,
                            )
                        }
                    }
                viewPortListener = listener
                viewPortListenerReady = false
                // Adding a listener may store and synchronously invoke it before throwing. Record
                // the cleanup duty first, and do not let that callback request a provider until
                // registration has returned successfully.
                viewPortListenerRemovalRequired = true
                try {
                    viewPortLayoutListenerSource.add(listener)
                } catch (_: RuntimeException) {
                    failIfCurrent(token, FrontCameraPreviewStatus.BIND_FAILED)
                    return
                }
                viewPortListenerReady = true
                // A synchronous callback could have made the ViewPort available while admission
                // was held. Re-read only after registration is known to have completed.
                continueWhenViewPortReady(
                    token,
                    lifecycleOwner,
                    metadataSink,
                    inferenceSink,
                    semanticSink,
                    fishingMotionConfig,
                    fishingMotionSink,
                    soloCombatMotionConfig,
                    soloCombatMotionSink,
                    dualPlayerTrackingConfig,
                    roleSetupPolicy,
                    calibrationRevision,
                    dualPlayerTrackingSink,
                    dualPlayerCombatMotionConfig,
                    dualPlayerCombatMotionSink,
                    lensSelection,
                )
            }
            return
        }
        if (!clearViewPortListener()) {
            failIfCurrent(token, FrontCameraPreviewStatus.BIND_FAILED)
            return
        }
        requestProvider(
            token,
            lifecycleOwner,
            metadataSink,
            inferenceSink,
            semanticSink,
            fishingMotionConfig,
            fishingMotionSink,
            soloCombatMotionConfig,
            soloCombatMotionSink,
            dualPlayerTrackingConfig,
            roleSetupPolicy,
            calibrationRevision,
            dualPlayerTrackingSink,
            dualPlayerCombatMotionConfig,
            dualPlayerCombatMotionSink,
            lensSelection,
            viewPort,
        )
    }

    @MainThread
    private fun requestProvider(
        token: CameraSessionRequestGate.Token,
        lifecycleOwner: LifecycleOwner,
        metadataSink: CameraFrameMetadataSink,
        inferenceSink: LivePoseInferenceSink,
        semanticSink: LivePoseSemanticSink?,
        fishingMotionConfig: FishingMotionConfig?,
        fishingMotionSink: FishingMotionFrameSink?,
        soloCombatMotionConfig: DualPlayerCombatMotionConfig?,
        soloCombatMotionSink: SoloCombatMotionFrameSink?,
        dualPlayerTrackingConfig: LoadedPlayerTrackerConfig?,
        roleSetupPolicy: DualPlayerRoleSetupPolicy?,
        calibrationRevision: Long?,
        dualPlayerTrackingSink: DualPlayerTrackingSink?,
        dualPlayerCombatMotionConfig: DualPlayerCombatMotionConfig?,
        dualPlayerCombatMotionSink: DualPlayerCombatMotionFrameSink?,
        lensSelection: CameraLensSelection,
        viewPort: ViewPort,
    ) {
        val observationSink =
            when {
                fishingMotionConfig != null -> {
                val productSink = requireNotNull(fishingMotionSink)
                lateinit var motionBridge: FishingLiveMotionBridge
                val mainDelivery = LatestFishingMotionMainDelivery(
                    mainExecutor = mainExecutor,
                    deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                        requests.deliverIfCurrent(token, delivery)
                    },
                    productSink = productSink,
                    onFrameReplaced = { motionBridge.resetAfterDeliveryGap() },
                )
                motionBridge = FishingLiveMotionBridge(
                    fishingMotionConfig,
                    mainDelivery,
                )
                motionBridge
                }

                soloCombatMotionConfig != null -> {
                    lateinit var bridge: SoloCombatMotionBridge
                    val delivery = LatestSoloCombatMotionMainDelivery(
                        mainExecutor = mainExecutor,
                        deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                            requests.deliverIfCurrent(token, delivery)
                        },
                        productSink = requireNotNull(soloCombatMotionSink),
                        onFrameReplaced = { bridge.reset() },
                        onDeliveryFailure = {
                            runCatching { soloCombatCallbackErrorSink?.invoke(token.generation) }
                            mainExecutor.execute { abortBindingIfOwned(token) }
                        },
                    )
                    SoloCombatMotionBridge(soloCombatMotionConfig, delivery).also { created ->
                        bridge = created
                    }
                }

                dualPlayerTrackingConfig != null -> {
                    val combatMotionBridge =
                        dualPlayerCombatMotionConfig?.let { motionConfig ->
                            lateinit var bridge: DualPlayerCombatMotionBridge
                            val delivery =
                                LatestDualPlayerCombatMotionMainDelivery(
                                    mainExecutor = mainExecutor,
                                    deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                                        requests.deliverIfCurrent(token, delivery)
                                    },
                                    productSink = requireNotNull(dualPlayerCombatMotionSink),
                                    onFrameReplaced = { bridge.reset() },
                                )
                            DualPlayerCombatMotionBridge(motionConfig, delivery).also { created ->
                                bridge = created
                            }
                        }
                    val trackingBridge =
                        DualPlayerTrackingBridge(
                            loadedConfig = dualPlayerTrackingConfig,
                            roleSetupPolicy = requireNotNull(roleSetupPolicy),
                            calibrationRevision = requireNotNull(calibrationRevision),
                            sink =
                                LatestDualPlayerTrackingMainDelivery(
                                    mainExecutor = mainExecutor,
                                    deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                                        requests.deliverIfCurrent(token, delivery)
                                    },
                                    productSink = requireNotNull(dualPlayerTrackingSink),
                                ),
                            combatMotionBridge = combatMotionBridge,
                        )
                    dualPlayerTrackingBridge = trackingBridge
                    trackingBridge
                }

                else -> LivePoseObservationSink.NONE
            }
        val coordinator =
            LivePoseObservationCoordinator(
                sessionGeneration = token.generation,
                continuityGate = poseContinuityGate,
                semanticSink =
                    LivePoseSemanticSink { summary ->
                        poseSemanticStateStore.onPoseSemanticSummary(summary)
                        semanticSink?.onPoseSemanticSummary(summary)
                    },
                observationSink = observationSink,
                deliveryGate =
                    LivePoseObservationDeliveryGate { delivery ->
                        requests.deliverIfCurrent(token, delivery)
                    },
                onTerminal = { poseSemanticStateStore.clearGeneration(token.generation) },
                onObservationError = { failedGeneration ->
                    if (failedGeneration == token.generation) {
                        runCatching { soloCombatCallbackErrorSink?.invoke(failedGeneration) }
                        mainExecutor.execute { abortBindingIfOwned(token) }
                    }
                },
            )
        val observationDispatcher =
            try {
                observationDispatcherFactory.create(token.generation, coordinator)
            } catch (_: RuntimeException) {
                coordinator.terminate()
                failIfCurrent(token, FrontCameraPreviewStatus.BIND_FAILED)
                return
            }
        val inferenceMainDelivery = LatestLivePoseInferenceMainDelivery(
            sessionGeneration = token.generation,
            mainExecutor = mainExecutor,
            deliveryGate = LivePoseObservationDeliveryGate { delivery ->
                requests.deliverIfCurrent(token, delivery)
            },
            productSink = LivePoseInferenceSink { snapshot ->
                poseReducer.accept(token, snapshot)
            },
        )
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = requests,
                sessionToken = token,
                metadataSink = metadataSink,
                counters = counters,
                sessionFactory = livePoseSessionFactory,
                inferenceSink = inferenceMainDelivery,
                observationDispatcher = observationDispatcher,
                ownerExecutor = analysisExecutorOwner.executor,
                scopeViolationSink =
                    CameraFrameScopeViolationSink {
                        mainExecutor.execute { abortBindingIfOwned(token) }
                    },
            )
        pendingLivePoseAnalyzer = analyzer
        val useCases =
            try {
                useCaseFactory(viewPort, analysisExecutorOwner.executor, analyzer).also {
                    it.preview.surfaceProvider = previewView.surfaceProvider
                }
            } catch (_: RuntimeException) {
                failIfCurrent(token, FrontCameraPreviewStatus.BIND_FAILED)
                return
            }
        pendingUseCases = useCases
        try {
            providerGateway.request(mainExecutor) { result ->
                completeProviderRequest(
                    token,
                    lifecycleOwner,
                    lensSelection,
                    useCases,
                    analyzer,
                    result,
                )
            }
        } catch (_: RuntimeException) {
            failIfCurrent(token, FrontCameraPreviewStatus.BIND_FAILED)
        }
    }

    @MainThread
    private fun completeProviderRequest(
        token: CameraSessionRequestGate.Token,
        lifecycleOwner: LifecycleOwner,
        lensSelection: CameraLensSelection,
        useCases: CameraUseCaseBundle,
        analyzer: LivePoseImageAnalysisAnalyzer,
        providerResult: Result<CameraProviderSession>,
    ) {
        if (!requests.isCurrent(token)) {
            analyzer.release()
            useCases.analysis.clearAnalyzer()
            return
        }
        val provider =
            providerResult.getOrElse { failure ->
                failIfCurrent(token, statusForFailure(failure, lensSelection))
                return
            }
        try {
            if (!provider.hasCamera(lensSelection)) {
                failIfCurrent(token, unavailableStatus(lensSelection))
                return
            }
            pendingUseCases = null
            pendingLivePoseAnalyzer = null
            boundProvider = provider
            boundUseCases = useCases
            boundLivePoseAnalyzer = analyzer
            boundToken = token
            val cameraHandle = provider.bind(lifecycleOwner, lensSelection, useCases.group)
            if (!requests.isCurrent(token)) {
                releaseBinding(LivePoseInferencePhase.FAILED)
                return
            }
            boundCameraHandle = cameraHandle
            boundLensSelection = lensSelection
            installScopeWatcher(lifecycleOwner, token)
            observeStream(lifecycleOwner, token)
        } catch (_: CameraInfoUnavailableException) {
            failIfCurrent(token, unavailableStatus(lensSelection))
        } catch (_: SecurityException) {
            failIfCurrent(token, FrontCameraPreviewStatus.PERMISSION_MISSING)
        } catch (_: RuntimeException) {
            failIfCurrent(token, FrontCameraPreviewStatus.BIND_FAILED)
        }
    }

    @MainThread
    private fun observeStream(
        lifecycleOwner: LifecycleOwner,
        token: CameraSessionRequestGate.Token,
    ) {
        check(clearStreamObserver()) { "Prior camera stream observer could not be removed" }
        val observer = Observer<PreviewView.StreamState> { state ->
            if (!requests.isCurrent(token)) return@Observer
            when (state) {
                PreviewView.StreamState.IDLE -> {
                    if (boundScopeSnapshot == null) {
                        emit(FrontCameraPreviewStatus.STARTING)
                    } else {
                        requests.invalidate(token)
                        abortBindingIfOwned(token)
                    }
                }
                PreviewView.StreamState.STREAMING -> {
                    if (establishOrValidateBoundScope(token)) {
                        emit(FrontCameraPreviewStatus.ACTIVE)
                    }
                }
            }
        }
        streamObserver = observer
        streamStateSource.observe(lifecycleOwner, observer)
    }

    @MainThread
    private fun failIfCurrent(
        token: CameraSessionRequestGate.Token,
        status: FrontCameraPreviewStatus,
    ) {
        if (!requests.isCurrent(token)) return
        val cleanRelease = releaseBinding(LivePoseInferencePhase.FAILED)
        emit(if (cleanRelease) status else FrontCameraPreviewStatus.BIND_FAILED)
    }

    @MainThread
    private fun abortBindingIfOwned(token: CameraSessionRequestGate.Token) {
        if (boundToken !== token) return
        releaseBinding(LivePoseInferencePhase.FAILED)
        emit(FrontCameraPreviewStatus.BIND_FAILED)
    }

    @MainThread
    private fun installScopeWatcher(
        lifecycleOwner: LifecycleOwner,
        token: CameraSessionRequestGate.Token,
    ) {
        val watcher =
            scopeWatcherFactory.create(previewView) { event ->
                if (event == CameraScopeWatchEvent.INVALIDATE) {
                    // Revocation is thread-safe and must precede queued main-thread teardown so a
                    // late analyzer callback cannot cross a background/detach boundary.
                    requests.invalidate(token)
                }
                mainExecutor.execute { onScopeWatchEvent(token, event) }
            }
        scopeWatcher = watcher
        watcher.start(lifecycleOwner)
    }

    @MainThread
    private fun onScopeWatchEvent(
        token: CameraSessionRequestGate.Token,
        event: CameraScopeWatchEvent,
    ) {
        if (boundToken !== token) return
        if (event == CameraScopeWatchEvent.INVALIDATE) {
            requests.invalidate(token)
            abortBindingIfOwned(token)
            return
        }
        if (!requests.isCurrent(token)) return
        if (boundScopeSnapshot != null) validateBoundScope(token)
    }

    @MainThread
    private fun establishOrValidateBoundScope(token: CameraSessionRequestGate.Token): Boolean {
        if (boundToken !== token || !requests.isCurrent(token)) return false
        if (boundScopeSnapshot != null) return validateBoundScope(token)
        val handle = boundCameraHandle ?: return invalidateScope(token)
        val lensSelection = boundLensSelection ?: return invalidateScope(token)
        val useCases = boundUseCases ?: return invalidateScope(token)
        val observed = resolveCurrentScope(handle, lensSelection, useCases) ?: return invalidateScope(token)
        if (observed.lens != lensSelection.frameLens()) return invalidateScope(token)
        if (!requests.activate(token, handle, observed)) return invalidateScope(token)
        boundScopeSnapshot = observed
        return true
    }

    @MainThread
    private fun validateBoundScope(token: CameraSessionRequestGate.Token): Boolean {
        if (boundToken !== token || !requests.isCurrent(token)) return false
        val handle = boundCameraHandle ?: return invalidateScope(token)
        val lensSelection = boundLensSelection ?: return invalidateScope(token)
        val useCases = boundUseCases ?: return invalidateScope(token)
        val expected = boundScopeSnapshot ?: return invalidateScope(token)
        val observed = resolveCurrentScope(handle, lensSelection, useCases)
        if (observed != expected) return invalidateScope(token)
        return true
    }

    @MainThread
    private fun resolveCurrentScope(
        cameraHandle: BoundCameraHandle,
        lensSelection: CameraLensSelection,
        useCases: CameraUseCaseBundle,
    ): BoundCameraScopeSnapshot? =
        try {
            val currentViewPort = viewPortProvider.current(previewView) ?: return null
            val sensorToViewTransform = previewTransformProvider.current(previewView) ?: return null
            scopeResolver.resolve(
                BoundCameraScopeInput(
                    cameraHandle = cameraHandle,
                    lens = lensSelection.frameLens(),
                    viewPort = currentViewPort,
                    useCases = useCases,
                    previewSurfaceWidth = previewView.width,
                    previewSurfaceHeight = previewView.height,
                    previewSensorToViewTransform = sensorToViewTransform,
                    previewViewImplementationMode = previewView.implementationMode,
                    previewViewScaleType = previewView.scaleType,
                ),
            )
        } catch (_: RuntimeException) {
            null
        }

    @MainThread
    private fun invalidateScope(token: CameraSessionRequestGate.Token): Boolean {
        requests.invalidate(token)
        abortBindingIfOwned(token)
        return false
    }

    @MainThread
    private fun releaseBinding(poseTerminalPhase: LivePoseInferencePhase): Boolean {
        poseReducer.terminateCurrent(poseTerminalPhase)
        requests.cancel()
        var clean = !cleanupPoisoned
        clean = clearViewPortListener() && clean
        clean = clearStreamObserver() && clean
        clean = clearScopeWatcher() && clean
        val provider = boundProvider
        val bound = boundUseCases
        val pending = pendingUseCases
        val boundAnalyzer = boundLivePoseAnalyzer
        val pendingAnalyzer = pendingLivePoseAnalyzer
        boundProvider = null
        boundUseCases = null
        boundCameraHandle = null
        boundLensSelection = null
        boundScopeSnapshot = null
        boundToken = null
        pendingUseCases = null
        boundLivePoseAnalyzer = null
        pendingLivePoseAnalyzer = null
        dualPlayerTrackingBridge = null

        listOfNotNull(boundAnalyzer, pendingAnalyzer).distinct().forEach { analyzer ->
            analyzer.release()
        }

        val analyses = listOfNotNull(bound?.analysis, pending?.analysis).distinct()
        analyses.forEach { analysis ->
            try {
                analysis.clearAnalyzer()
            } catch (_: RuntimeException) {
                clean = false
            }
        }
        if (provider != null && bound != null) {
            try {
                provider.unbind(arrayOf(bound.preview, bound.analysis))
            } catch (_: RuntimeException) {
                clean = false
            }
        }
        if (!clean) cleanupPoisoned = true
        return clean
    }

    @MainThread
    private fun clearScopeWatcher(): Boolean {
        val watcher = scopeWatcher
        scopeWatcher = null
        return if (watcher == null) {
            true
        } else {
            try {
                watcher.stop()
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    @MainThread
    private fun clearViewPortListener(): Boolean {
        val listener = viewPortListener
        viewPortListener = null
        viewPortListenerReady = false
        val removalRequired = viewPortListenerRemovalRequired
        viewPortListenerRemovalRequired = false
        val clean =
            if (!removalRequired) {
                listener == null
            } else if (listener == null) {
                false
            } else {
                try {
                    viewPortLayoutListenerSource.remove(listener)
                    true
                } catch (_: RuntimeException) {
                    false
                }
            }
        if (!clean) cleanupPoisoned = true
        return clean
    }

    @MainThread
    private fun clearStreamObserver(): Boolean {
        val observer = streamObserver
        streamObserver = null
        if (observer == null) return true
        return try {
            streamStateSource.remove(observer)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun statusForFailure(
        failure: Throwable,
        lensSelection: CameraLensSelection,
    ): FrontCameraPreviewStatus =
        when (failure) {
            is SecurityException -> FrontCameraPreviewStatus.PERMISSION_MISSING
            is CameraInfoUnavailableException -> unavailableStatus(lensSelection)
            else -> FrontCameraPreviewStatus.BIND_FAILED
        }

    private fun unavailableStatus(lensSelection: CameraLensSelection): FrontCameraPreviewStatus =
        when (lensSelection) {
            CameraLensSelection.FRONT -> FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE
            CameraLensSelection.BACK -> FrontCameraPreviewStatus.BACK_CAMERA_UNAVAILABLE
        }

    private fun emit(status: FrontCameraPreviewStatus) {
        statusSink?.invoke(status)
    }
}

internal fun isSoloCombatBindingProfileSupported(
    profile: DualPlayerCombatProfile,
): Boolean = profile.supportsSoloCombat
