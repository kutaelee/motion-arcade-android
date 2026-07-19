package com.motionarcade.vision.camera

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Rational
import android.view.Surface
import android.view.View
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.pose.LivePoseInputFrame
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import com.motionarcade.vision.pose.LivePoseSemanticSink
import com.motionarcade.vision.pose.LivePoseSemanticSummary
import com.motionarcade.vision.pose.LivePoseSession
import com.motionarcade.vision.pose.LivePoseSessionCallbacks
import com.motionarcade.vision.pose.LivePoseSessionFactory
import com.motionarcade.vision.pose.emitValidLivePoseResult
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraSessionControllerTest {
    @Test
    fun missingPermissionDoesNotRequestOrBindCamera() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateway = RecordingGateway(RecordingProviderSession())
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val controller =
            controller(
                context = context,
                previewView = PreviewView(context),
                permissionGranted = false,
                gateway = gateway,
                executorOwner = executorOwner,
            )

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(listOf(FrontCameraPreviewStatus.PERMISSION_MISSING), statuses)
        assertEquals(0, gateway.requestCount)
        assertEquals(0, gateway.session.bindGroups.size)
        controller.release()
        assertTrue(executorOwner.released)
    }

    @Test
    fun rebindOwnsOneSharedGroupAndReleaseInvalidatesAndUnbindsBothUseCases() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val watcherFactory = RecordingScopeWatcherFactory()
        val streamStateSource = RecordingStreamStateSource()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val lifecycleOwner = ResumedLifecycleOwner()
        val controller =
            controller(
                context = context,
                previewView = previewView,
                permissionGranted = true,
                gateway = gateway,
                executorOwner = executorOwner,
                watcherFactory = watcherFactory,
                streamStateSource = streamStateSource,
            )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(1, watcherFactory.activeCount)
        assertEquals(1, streamStateSource.observerCount)
        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(2, gateway.requestCount)
        assertEquals(2, provider.bindGroups.size)
        assertEquals(1, provider.unbound.size)
        provider.bindGroups.forEach { group ->
            assertEquals(2, group.useCases.size)
            val groupViewPort = requireNotNull(group.viewPort)
            assertEquals(Rational(4, 3), groupViewPort.aspectRatio)
            assertEquals(Surface.ROTATION_0, groupViewPort.rotation)
        }
        assertEquals(provider.bindGroups.first().useCases, provider.unbound.first().toList())
        assertEquals(2, watcherFactory.watchers.size)
        assertEquals(1, watcherFactory.watchers.first().stopCount)
        assertEquals(1, watcherFactory.activeCount)
        assertEquals(1, streamStateSource.observerCount)

        streamStateSource.emit(PreviewView.StreamState.STREAMING)
        assertEquals(FrontCameraPreviewStatus.ACTIVE, statuses.last())

        controller.release()

        assertEquals(2, provider.unbound.size)
        assertEquals(provider.bindGroups.last().useCases, provider.unbound.last().toList())
        assertTrue(executorOwner.released)
        assertEquals(FrontCameraPreviewStatus.RELEASED, statuses.last())
        assertEquals(0, watcherFactory.activeCount)
        assertEquals(0, streamStateSource.observerCount)
        assertEquals(
            CameraFrameCounterSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0),
            controller.frameCounters(),
        )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(2, gateway.requestCount)
        assertEquals(FrontCameraPreviewStatus.RELEASED, statuses.last())
    }

    @Test
    fun unavailableFrontCameraNeverBindsAndReportsFiniteFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingProviderSession(frontAvailable = false)
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val controller =
            controller(
                context = context,
                previewView = PreviewView(context),
                permissionGranted = true,
                gateway = gateway,
                executorOwner = executorOwner,
            )

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, gateway.requestCount)
        assertTrue(provider.bindGroups.isEmpty())
        assertEquals(FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE, statuses.last())
        controller.release()
    }

    @Test
    fun unavailableBackCameraNeverFallsBackToFront() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingProviderSession(frontAvailable = true, backAvailable = false)
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val controller =
            controller(
                context = context,
                previewView = PreviewView(context),
                permissionGranted = true,
                gateway = gateway,
                executorOwner = executorOwner,
            )

        controller.bind(
            ResumedLifecycleOwner(),
            CameraLensSelection.BACK,
            CameraFrameMetadataSink.NONE,
            statuses::add,
        )

        assertEquals(1, gateway.requestCount)
        assertTrue(provider.bindGroups.isEmpty())
        assertTrue(provider.bindSelections.isEmpty())
        assertEquals(FrontCameraPreviewStatus.BACK_CAMERA_UNAVAILABLE, statuses.last())
        controller.release()
    }

    @Test
    fun switchingFrontToBackUnbindsOldGenerationAndLabelsBackMetadataExactly() {
        val fixture = lateAnalyzerFixture(Executor(Runnable::run))
        val metadata = mutableListOf<CameraFrameMetadata>()
        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraFrameMetadataSink(metadata::add),
            fixture.statuses::add,
        )
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        val frontAnalyzer = requireNotNull(fixture.capturedAnalyzer)

        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraLensSelection.BACK,
            CameraFrameMetadataSink(metadata::add),
            fixture.statuses::add,
        )

        assertEquals(
            listOf(CameraLensSelection.FRONT, CameraLensSelection.BACK),
            fixture.provider.bindSelections,
        )
        assertEquals(1, fixture.provider.unbound.size)
        frontAnalyzer.analyze(rgbaControllerImageProxy(100L, AtomicInteger()))
        assertTrue(metadata.isEmpty())
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        requireNotNull(fixture.capturedAnalyzer).analyze(
            rgbaControllerImageProxy(101L, AtomicInteger()),
        )

        val backMetadata = metadata.single()
        assertEquals(CameraFrameLens.BACK, backMetadata.lens)
        assertFalse(backMetadata.previewMirrored)
        assertFalse(backMetadata.analysisMirrored)
        assertEquals(FrontCameraPreviewStatus.ACTIVE, fixture.statuses.last())
        fixture.controller.release()
        assertEquals(2, fixture.provider.unbound.size)
    }

    @Test
    fun missingViewPortWaitsWithoutGuessingThenBindsAfterExactLayoutScopeExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val lifecycleOwner = ResumedLifecycleOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        var currentViewPort: ViewPort? = null
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = CameraViewPortProvider { currentViewPort },
                scopeResolver = BoundCameraScopeResolver(::resolvedSnapshot),
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = RecordingScopeWatcherFactory(),
                streamStateSource = RecordingStreamStateSource(),
                mainExecutor = Executor(Runnable::run),
                analysisExecutorOwner = executorOwner,
            )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(0, gateway.requestCount)
        assertEquals(FrontCameraPreviewStatus.STARTING, statuses.last())

        currentViewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        previewView.layout(0, 0, 640, 480)

        assertEquals(1, gateway.requestCount)
        assertEquals(1, provider.bindGroups.size)
        controller.release()
    }

    @Test
    fun viewPortLayoutAddThatStoresCallbacksThenThrowsIsRemovedBeforeAnyProviderRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        val viewPortProvider = FirstReadMissingViewPortProvider(viewPort)
        val listenerSource =
            FaultInjectingViewPortLayoutListenerSource(
                callbackView = previewView,
                addFailureAfterStoreAndCallback = IllegalStateException("layout add failed"),
            )
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                streamStateSource = RecordingStreamStateSource(),
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = viewPortProvider,
                viewPortLayoutListenerSource = listenerSource,
                scopeResolver = BoundCameraScopeResolver(::resolvedSnapshot),
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = RecordingScopeWatcherFactory(),
                mainExecutor = Executor(Runnable::run),
                analysisExecutorOwner = DirectExecutorOwner(),
            )

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, viewPortProvider.readCount)
        assertEquals(1, listenerSource.addCount)
        assertEquals(1, listenerSource.removeCount)
        assertEquals(0, gateway.requestCount)
        assertTrue(provider.bindGroups.isEmpty())
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        val statusCount = statuses.size
        listenerSource.emitLastListener()
        assertEquals(0, gateway.requestCount)
        assertEquals(statusCount, statuses.size)
        controller.release()
    }

    @Test
    fun viewPortLayoutRemoveFailureAfterSuccessfulAddPoisonsAndPermanentlyBlocksRebind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        val viewPortProvider = FirstReadMissingViewPortProvider(viewPort)
        val listenerSource =
            FaultInjectingViewPortLayoutListenerSource(
                callbackView = previewView,
                removeFailure = IllegalStateException("layout remove failed"),
            )
        val lifecycleOwner = ResumedLifecycleOwner()
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                streamStateSource = RecordingStreamStateSource(),
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = viewPortProvider,
                viewPortLayoutListenerSource = listenerSource,
                scopeResolver = BoundCameraScopeResolver(::resolvedSnapshot),
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = RecordingScopeWatcherFactory(),
                mainExecutor = Executor(Runnable::run),
                analysisExecutorOwner = executorOwner,
            )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(2, viewPortProvider.readCount)
        assertEquals(1, listenerSource.addCount)
        assertEquals(1, listenerSource.removeCount)
        assertEquals(0, gateway.requestCount)
        assertTrue(provider.bindGroups.isEmpty())
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        val statusCount = statuses.size
        listenerSource.emitLastListener()
        assertEquals(0, gateway.requestCount)
        assertEquals(statusCount, statuses.size)

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(1, listenerSource.addCount)
        assertEquals(1, listenerSource.removeCount)
        assertEquals(0, gateway.requestCount)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        controller.release()
        assertTrue(executorOwner.released)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
    }

    @Test
    fun providerCompletionAfterReleaseIsGenerationStaleAndCannotBind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val provider = RecordingProviderSession()
        val gateway = DeferredGateway()
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = CameraViewPortProvider { viewPort },
                scopeResolver = BoundCameraScopeResolver { error("stale completion must not resolve") },
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = RecordingScopeWatcherFactory(),
                streamStateSource = RecordingStreamStateSource(),
                mainExecutor = Executor(Runnable::run),
                analysisExecutorOwner = executorOwner,
            )

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)
        assertTrue(gateway.hasPendingResult)
        controller.release()
        gateway.complete(Result.success(provider))

        assertTrue(provider.bindGroups.isEmpty())
        assertEquals(FrontCameraPreviewStatus.RELEASED, statuses.last())
        assertTrue(executorOwner.released)
    }

    @Test
    fun partialBindThrowStillClearsAnalyzerAndAttemptsBothUseCaseUnbinds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bindFailure = IllegalStateException("bind failed after side effect")
        val cleanupFailure = IllegalArgumentException("unbind failed")
        val provider =
            RecordingProviderSession(
                bindFailureAfterRecord = bindFailure,
                unbindFailureAfterRecord = cleanupFailure,
            )
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val controller =
            controller(
                context = context,
                previewView = PreviewView(context),
                permissionGranted = true,
                gateway = gateway,
                executorOwner = executorOwner,
            )

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, provider.bindGroups.size)
        assertEquals(1, provider.unbound.size)
        assertEquals(provider.bindGroups.single().useCases, provider.unbound.single().toList())
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
        controller.release()
        assertTrue(executorOwner.released)
    }

    @Test
    fun uncertainListenerTeardownStillUnbindsAndPermanentlyBlocksRebind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val watcherFactory = RecordingScopeWatcherFactory(stopResult = false)
        val streamStateSource =
            RecordingStreamStateSource(
                removeFailure = IllegalStateException("observer removal uncertain"),
            )
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val lifecycleOwner = ResumedLifecycleOwner()
        val controller =
            controller(
                context = context,
                previewView = previewView,
                permissionGranted = true,
                gateway = gateway,
                executorOwner = executorOwner,
                watcherFactory = watcherFactory,
                streamStateSource = streamStateSource,
            )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        streamStateSource.emit(PreviewView.StreamState.STREAMING)
        assertEquals(FrontCameraPreviewStatus.ACTIVE, statuses.last())

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, gateway.requestCount)
        assertEquals(1, provider.unbound.size)
        assertEquals(provider.bindGroups.single().useCases, provider.unbound.single().toList())
        assertEquals(1, streamStateSource.removeCount)
        assertEquals(1, watcherFactory.watchers.single().stopCount)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        val statusCountAfterFailure = statuses.size
        streamStateSource.emit(PreviewView.StreamState.STREAMING)
        watcherFactory.watchers.single().emit()
        assertEquals(statusCountAfterFailure, statuses.size)

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(1, gateway.requestCount)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
        controller.release()
        assertTrue(executorOwner.released)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
    }

    @Test
    fun watcherStartWithUncertainPartialCleanupPoisonsSurfaceAndBlocksRebind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val watcherFactory =
            RecordingScopeWatcherFactory(
                stopResult = false,
                startFailure = IllegalStateException("partial watcher install failed"),
            )
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val controller =
            controller(
                context = context,
                previewView = previewView,
                permissionGranted = true,
                gateway = gateway,
                executorOwner = executorOwner,
                watcherFactory = watcherFactory,
            )

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, gateway.requestCount)
        assertEquals(1, provider.bindGroups.size)
        assertEquals(1, provider.unbound.size)
        assertEquals(1, watcherFactory.watchers.single().stopCount)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, gateway.requestCount)
        assertEquals(1, provider.bindGroups.size)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
        controller.release()
        assertTrue(executorOwner.released)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
    }

    @Test
    fun lifecycleAfterSideEffectRemovalFailureLeavesLateCallbackButPermanentlyBlocksRebind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView =
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layout(0, 0, 640, 480)
            }
        val provider = RecordingProviderSession()
        val gateway = DirectCallbackGateway(provider)
        val executorOwner = DirectExecutorOwner()
        // Analyzer construction uses execution #1 for the INITIALIZING pose snapshot. Fail the
        // next execution so this fixture still targets lifecycle observer registration cleanup.
        val mainExecutor = FailAtExecutionThenDirectExecutor(failAtExecution = 2)
        val lifecycleOwner =
            AfterSideEffectResumedLifecycleOwner(
                removalFailure = IllegalStateException("lifecycle observer removal uncertain"),
            )
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                streamStateSource = RecordingStreamStateSource(),
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = CameraViewPortProvider { viewPort },
                scopeResolver = BoundCameraScopeResolver(::resolvedSnapshot),
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = PlatformCameraScopeWatcherFactory(context),
                mainExecutor = mainExecutor,
                analysisExecutorOwner = executorOwner,
            )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)

        assertEquals(1, gateway.requestCount)
        assertEquals(1, provider.bindGroups.size)
        assertEquals(1, provider.unbound.size)
        assertEquals(1, lifecycleOwner.removeAttempts)
        assertEquals(1, lifecycleOwner.trackedObserverCount)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        val executionsBeforeLateCallback = mainExecutor.executionCount
        val statusCountBeforeLateCallback = statuses.size
        lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
        assertTrue(mainExecutor.executionCount > executionsBeforeLateCallback)
        assertEquals(statusCountBeforeLateCallback, statuses.size)

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(1, gateway.requestCount)
        assertEquals(1, provider.bindGroups.size)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())

        controller.release()
        assertTrue(executorOwner.released)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
    }

    @Test
    fun activeThenIdleRevokesGateBeforeAnyLateAnalyzerMetadataHandoff() {
        val fixture = lateAnalyzerFixture(Executor(Runnable::run))
        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraFrameMetadataSink { fixture.metadataDelivered += 1 },
            fixture.statuses::add,
        )
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        assertEquals(FrontCameraPreviewStatus.ACTIVE, fixture.statuses.last())

        fixture.streamStateSource.emit(PreviewView.StreamState.IDLE)
        val closeCount = AtomicInteger()
        requireNotNull(fixture.capturedAnalyzer).analyze(testImageProxy(11L, closeCount))

        assertEquals(0, fixture.metadataDelivered)
        assertEquals(1, closeCount.get())
        assertEquals(1, fixture.controller.frameCounters().staleGenerationRejected)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, fixture.statuses.last())
        assertEquals(1, fixture.provider.unbound.size)
    }

    @Test
    fun activePoseIsExplicitlyFailedOnCameraAbortAndOldGenerationCannotReviveIt() {
        val fixture = lateAnalyzerFixture(Executor(Runnable::run))
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val semanticSummaries = mutableListOf<LivePoseSemanticSummary>()
        val semanticDelivered = CountDownLatch(1)
        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraFrameMetadataSink.NONE,
            LivePoseInferenceSink(snapshots::add),
            LivePoseSemanticSink {
                semanticSummaries += it
                semanticDelivered.countDown()
            },
            fixture.statuses::add,
        )
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        val closeCount = AtomicInteger()
        requireNotNull(fixture.capturedAnalyzer).analyze(rgbaControllerImageProxy(21L, closeCount))
        fixture.livePoseCallbacks.emitValidLivePoseResult(
            2,
            fixture.livePoseSession.taskTimestampMs,
        )
        assertTrue(semanticDelivered.await(2L, TimeUnit.SECONDS))
        val active = snapshots.last()
        assertEquals(LivePoseInferencePhase.ACTIVE, active.phase)
        assertEquals(2, active.poseCount)
        assertEquals(2, semanticSummaries.single().poseCount)
        assertEquals(
            semanticSummaries.single().revision,
            requireNotNull(fixture.controller.latestPoseSemanticSummary()).revision,
        )

        fixture.streamStateSource.emit(PreviewView.StreamState.IDLE)
        val terminal = snapshots.last()
        val snapshotCountAtTerminal = snapshots.size
        fixture.livePoseCallbacks.emitValidLivePoseResult(
            1,
            fixture.livePoseSession.taskTimestampMs,
        )

        assertEquals(1, closeCount.get())
        assertEquals(1, fixture.livePoseSession.closeCount)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, fixture.statuses.last())
        assertEquals(LivePoseInferencePhase.FAILED, terminal.phase)
        assertEquals(null, terminal.poseCount)
        assertEquals(active.sessionGeneration, terminal.sessionGeneration)
        assertTrue(terminal.revision > active.revision)
        assertEquals(snapshotCountAtTerminal, snapshots.size)
        assertEquals(1, semanticSummaries.size)
        assertEquals(null, fixture.controller.latestPoseSemanticSummary())
    }

    @Test
    fun productionRebindResetsTemporalEpochAndFencesOldGenerationCallbacks() {
        val fixture = lateAnalyzerFixture(Executor(Runnable::run))
        val summaries = mutableListOf<LivePoseSemanticSummary>()
        val firstDelivered = CountDownLatch(1)
        val secondDelivered = CountDownLatch(1)
        val unexpectedThirdDelivery = CountDownLatch(1)
        val semanticSink =
            LivePoseSemanticSink { summary ->
                val count =
                    synchronized(summaries) {
                        summaries += summary
                        summaries.size
                    }
                if (count == 1) firstDelivered.countDown()
                if (count == 2) secondDelivered.countDown()
                if (count >= 3) unexpectedThirdDelivery.countDown()
            }

        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraFrameMetadataSink.NONE,
            LivePoseInferenceSink.NONE,
            semanticSink,
            fixture.statuses::add,
        )
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        val firstAnalyzer = requireNotNull(fixture.capturedAnalyzer)
        firstAnalyzer.analyze(rgbaControllerImageProxy(31L, AtomicInteger()))
        val firstCallbacks = fixture.livePoseCallbacks
        firstCallbacks.emitValidLivePoseResult(
            1,
            fixture.livePoseSession.taskTimestampMs,
        )
        assertTrue(firstDelivered.await(2L, TimeUnit.SECONDS))

        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraFrameMetadataSink.NONE,
            LivePoseInferenceSink.NONE,
            semanticSink,
            fixture.statuses::add,
        )
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        val secondAnalyzer = requireNotNull(fixture.capturedAnalyzer)
        assertTrue(firstAnalyzer !== secondAnalyzer)
        secondAnalyzer.analyze(rgbaControllerImageProxy(32L, AtomicInteger()))
        fixture.livePoseCallbacks.emitValidLivePoseResult(
            2,
            fixture.livePoseSession.taskTimestampMs,
        )
        assertTrue(secondDelivered.await(2L, TimeUnit.SECONDS))
        val delivered = synchronized(summaries) { summaries.toList() }
        assertEquals(2, delivered.size)
        assertTrue(delivered[1].sessionGeneration > delivered[0].sessionGeneration)
        assertEquals(
            listOf(
                LivePoseContinuityBoundary.RESET_GENERATION,
                LivePoseContinuityBoundary.RESET_GENERATION,
            ),
            delivered.map { it.continuityBoundary },
        )
        assertTrue(delivered[1].temporalEpoch > delivered[0].temporalEpoch)

        firstCallbacks.emitValidLivePoseResult(
            1,
            fixture.livePoseSession.taskTimestampMs,
        )
        assertFalse(unexpectedThirdDelivery.await(200L, TimeUnit.MILLISECONDS))
        assertEquals(2, synchronized(summaries) { summaries.size })
        assertEquals(
            delivered[1].sessionGeneration,
            requireNotNull(fixture.controller.latestPoseSemanticSummary()).sessionGeneration,
        )
        fixture.controller.release()
    }

    @Test
    fun lifecycleInvalidationRevokesBeforeQueuedOwnedTeardownAndLateAnalyzer() {
        val queuedMain = QueueingExecutor()
        val fixture = lateAnalyzerFixture(queuedMain)
        fixture.controller.bind(
            fixture.lifecycleOwner,
            CameraFrameMetadataSink { fixture.metadataDelivered += 1 },
            fixture.statuses::add,
        )
        queuedMain.runAll()
        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)
        assertEquals(FrontCameraPreviewStatus.ACTIVE, fixture.statuses.last())

        fixture.watcherFactory.watchers.single().emit(CameraScopeWatchEvent.INVALIDATE)
        val closeCount = AtomicInteger()
        requireNotNull(fixture.capturedAnalyzer).analyze(testImageProxy(12L, closeCount))

        assertEquals(0, fixture.metadataDelivered)
        assertEquals(1, closeCount.get())
        assertEquals(1, fixture.controller.frameCounters().staleGenerationRejected)
        assertTrue(providerHasNoUnbindYet(fixture.provider))

        queuedMain.runAll()
        assertEquals(1, fixture.provider.unbound.size)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, fixture.statuses.last())
    }

    @Test
    fun boundSurfaceAndViewPortDriftAbortWithoutAutomaticRebind() {
        val surfaceFixture = boundDriftFixture()
        surfaceFixture.previewView.layout(0, 0, 800, 600)
        surfaceFixture.watcherFactory.watchers.single().emit()
        assertScopeAbort(surfaceFixture)

        listOf(
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_90)
                .setScaleType(ViewPort.FILL_CENTER)
                .build(),
            ViewPort.Builder(Rational(16, 9), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build(),
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL)
                .build(),
        ).forEach { changedViewPort ->
            val viewPortFixture = boundDriftFixture()
            viewPortFixture.viewPortProvider.viewPort = changedViewPort
            viewPortFixture.watcherFactory.watchers.single().emit()
            assertScopeAbort(viewPortFixture)
        }
    }

    @Test
    fun boundResolutionAndCropDriftAbortWithoutMixingScope() {
        val mutations =
            listOf<(BoundCameraScopeSnapshot) -> BoundCameraScopeSnapshot>(
                { snapshot ->
                    snapshot.copy(
                        analysisWidth = 800,
                        analysisHeight = 600,
                        analysisCrop = CameraFrameCrop(0, 0, 800, 600),
                    )
                },
                { snapshot -> snapshot.copy(analysisCrop = CameraFrameCrop(8, 4, 632, 476)) },
                { snapshot -> snapshot.copy(analysisRotationDegrees = 90) },
                { snapshot ->
                    snapshot.copy(
                        previewWidth = 800,
                        previewHeight = 600,
                        previewCrop = CameraFrameCrop(0, 0, 800, 600),
                    )
                },
                { snapshot -> snapshot.copy(previewCrop = CameraFrameCrop(8, 4, 632, 476)) },
                { snapshot -> snapshot.copy(previewRotationDegrees = 90) },
            )
        mutations.forEach { mutation ->
            val fixture = boundDriftFixture()
            fixture.scopeResolver.mutation = mutation
            fixture.watcherFactory.watchers.single().emit()
            assertScopeAbort(fixture)
        }
    }

    @Test
    fun boundCameraIdentityAndHashedCamera2TokenDriftAbort() {
        listOf<(MutableCameraHandleFixture) -> Unit>(
            { camera -> camera.cameraIdentity = Any() },
            { camera -> camera.publicCamera2Id = "front-camera-1" },
        ).forEach { mutation ->
            val fixture = boundDriftFixture()
            mutation(fixture.provider.camera)
            fixture.watcherFactory.watchers.single().emit()
            assertScopeAbort(fixture)
        }
    }

    @Test
    fun rawSensorToViewMatrixBitMutationAbortsEvenWhenFloatComparesEqual() {
        val fixture = boundDriftFixture()
        assertTrue(0.0f == -0.0f)
        fixture.transformProvider.snapshot =
            testPreviewSensorToViewTransform(
                values =
                    floatArrayOf(
                        1f,
                        -0.0f,
                        0f,
                        0f,
                        1f,
                        0f,
                        0f,
                        0f,
                        1f,
                    ),
            )

        fixture.watcherFactory.watchers.single().emit()

        assertScopeAbort(fixture)
    }

    @Test
    fun previewV2ConfigurationDriftAborts() {
        val fixture = boundDriftFixture()
        fixture.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        fixture.watcherFactory.watchers.single().emit()

        assertScopeAbort(fixture)
    }

    @Test
    fun streamingWithoutPublicSensorToViewTransformFailsClosed() {
        val fixture = boundDriftFixture(emitStreaming = false)
        fixture.transformProvider.snapshot = null

        fixture.streamStateSource.emit(PreviewView.StreamState.STREAMING)

        assertScopeAbort(fixture)
    }

    @Test
    fun generationOverflowFailsClosedAndPermanentlyReleasesExecutorOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val executorOwner = DirectExecutorOwner()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val lifecycleOwner = ResumedLifecycleOwner()
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = CameraViewPortProvider { viewPort },
                scopeResolver = BoundCameraScopeResolver { error("overflow must not resolve") },
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = RecordingScopeWatcherFactory(),
                streamStateSource = RecordingStreamStateSource(),
                mainExecutor = Executor(Runnable::run),
                analysisExecutorOwner = executorOwner,
                requests = CameraSessionRequestGate(initialGeneration = Long.MAX_VALUE),
            )

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, statuses.last())
        assertTrue(executorOwner.released)
        assertEquals(0, gateway.requestCount)

        controller.bind(lifecycleOwner, CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(FrontCameraPreviewStatus.RELEASED, statuses.last())
        assertEquals(0, gateway.requestCount)
    }

    private data class DriftControllerFixture(
        val controller: CameraSessionController,
        val previewView: PreviewView,
        val provider: RecordingProviderSession,
        val viewPortProvider: MutableViewPortProvider,
        val scopeResolver: MutableScopeResolver,
        val transformProvider: MutablePreviewTransformProvider,
        val watcherFactory: RecordingScopeWatcherFactory,
        val streamStateSource: RecordingStreamStateSource,
        val statuses: MutableList<FrontCameraPreviewStatus>,
    )

    private class LateAnalyzerFixture(
        val controller: CameraSessionController,
        val lifecycleOwner: LifecycleOwner,
        val provider: RecordingProviderSession,
        val watcherFactory: RecordingScopeWatcherFactory,
        val streamStateSource: RecordingStreamStateSource,
        val statuses: MutableList<FrontCameraPreviewStatus>,
    ) {
        var capturedAnalyzer: ImageAnalysis.Analyzer? = null
        var metadataDelivered: Int = 0
        val livePoseSession = RecordingLivePoseSession()
        lateinit var livePoseCallbacks: LivePoseSessionCallbacks
    }

    private fun lateAnalyzerFixture(mainExecutor: Executor): LateAnalyzerFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView =
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layout(0, 0, 640, 480)
            }
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val watcherFactory = RecordingScopeWatcherFactory()
        val streamStateSource = RecordingStreamStateSource()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val lifecycleOwner = ResumedLifecycleOwner()
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        lateinit var fixture: LateAnalyzerFixture
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                streamStateSource = streamStateSource,
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = CameraViewPortProvider { viewPort },
                scopeResolver = BoundCameraScopeResolver(::resolvedSnapshot),
                previewTransformProvider = MutablePreviewTransformProvider(),
                scopeWatcherFactory = watcherFactory,
                mainExecutor = mainExecutor,
                analysisExecutorOwner = DirectExecutorOwner(),
                livePoseSessionFactory = LivePoseSessionFactory { callbacks ->
                    fixture.livePoseCallbacks = callbacks
                    fixture.livePoseSession
                },
                useCaseFactory = { boundViewPort, analyzerExecutor, analyzer ->
                    fixture.capturedAnalyzer = analyzer
                    CameraUseCaseFactory.create(boundViewPort, analyzerExecutor, analyzer)
                },
            )
        fixture =
            LateAnalyzerFixture(
                controller = controller,
                lifecycleOwner = lifecycleOwner,
                provider = provider,
                watcherFactory = watcherFactory,
                streamStateSource = streamStateSource,
                statuses = statuses,
            )
        return fixture
    }

    private fun providerHasNoUnbindYet(provider: RecordingProviderSession): Boolean =
        provider.unbound.isEmpty()

    private fun boundDriftFixture(emitStreaming: Boolean = true): DriftControllerFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView =
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layout(0, 0, 640, 480)
            }
        val provider = RecordingProviderSession()
        val gateway = RecordingGateway(provider)
        val viewPortProvider =
            MutableViewPortProvider(
                ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                    .setScaleType(ViewPort.FILL_CENTER)
                    .build(),
            )
        val scopeResolver = MutableScopeResolver(::resolvedSnapshot)
        val transformProvider = MutablePreviewTransformProvider()
        val watcherFactory = RecordingScopeWatcherFactory()
        val streamStateSource = RecordingStreamStateSource()
        val statuses = mutableListOf<FrontCameraPreviewStatus>()
        val controller =
            CameraSessionController(
                context = context,
                previewView = previewView,
                streamStateSource = streamStateSource,
                permissionChecker = CameraPermissionChecker { true },
                providerGateway = gateway,
                viewPortProvider = viewPortProvider,
                scopeResolver = scopeResolver,
                previewTransformProvider = transformProvider,
                scopeWatcherFactory = watcherFactory,
                mainExecutor = Executor(Runnable::run),
                analysisExecutorOwner = DirectExecutorOwner(),
            )
        controller.bind(ResumedLifecycleOwner(), CameraFrameMetadataSink.NONE, statuses::add)
        assertEquals(1, watcherFactory.activeCount)
        assertEquals(1, streamStateSource.observerCount)
        if (emitStreaming) {
            streamStateSource.emit(PreviewView.StreamState.STREAMING)
            assertEquals(FrontCameraPreviewStatus.ACTIVE, statuses.last())
        }
        return DriftControllerFixture(
            controller = controller,
            previewView = previewView,
            provider = provider,
            viewPortProvider = viewPortProvider,
            scopeResolver = scopeResolver,
            transformProvider = transformProvider,
            watcherFactory = watcherFactory,
            streamStateSource = streamStateSource,
            statuses = statuses,
        )
    }

    private fun assertScopeAbort(fixture: DriftControllerFixture) {
        assertEquals(FrontCameraPreviewStatus.BIND_FAILED, fixture.statuses.last())
        assertEquals(1, fixture.provider.unbound.size)
        assertEquals(
            fixture.provider.bindGroups.single().useCases,
            fixture.provider.unbound.single().toList(),
        )
        assertEquals(0, fixture.watcherFactory.activeCount)
        assertEquals(0, fixture.streamStateSource.observerCount)
    }

    private fun resolvedSnapshot(input: BoundCameraScopeInput): BoundCameraScopeSnapshot {
        val aspect = input.viewPort.aspectRatio
        return testBoundCameraScopeSnapshot(
            cameraHandle = input.cameraHandle,
            lens = input.lens,
            previewSurfaceWidth = input.previewSurfaceWidth,
            previewSurfaceHeight = input.previewSurfaceHeight,
            previewSensorToViewTransform = input.previewSensorToViewTransform,
            targetRotation = input.viewPort.rotation,
            viewPortAspectNumerator = aspect.numerator,
            viewPortAspectDenominator = aspect.denominator,
            viewPortScaleType = input.viewPort.scaleType,
            viewPortLayoutDirection = input.viewPort.layoutDirection,
            previewViewImplementationMode = input.previewViewImplementationMode,
            previewViewScaleType = input.previewViewScaleType,
        )
    }

    private fun controller(
        context: Context,
        previewView: PreviewView,
        permissionGranted: Boolean,
        gateway: RecordingGateway,
        executorOwner: DirectExecutorOwner,
        scopeResolver: BoundCameraScopeResolver = BoundCameraScopeResolver(::resolvedSnapshot),
        transformProvider: MutablePreviewTransformProvider = MutablePreviewTransformProvider(),
        watcherFactory: RecordingScopeWatcherFactory = RecordingScopeWatcherFactory(),
        streamStateSource: RecordingStreamStateSource = RecordingStreamStateSource(),
    ): CameraSessionController {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.layout(0, 0, 640, 480)
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_0)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        return CameraSessionController(
            context = context,
            previewView = previewView,
            permissionChecker = CameraPermissionChecker { permissionGranted },
            providerGateway = gateway,
            viewPortProvider = CameraViewPortProvider { viewPort },
            scopeResolver = scopeResolver,
            previewTransformProvider = transformProvider,
            scopeWatcherFactory = watcherFactory,
            streamStateSource = streamStateSource,
            mainExecutor = Executor(Runnable::run),
            analysisExecutorOwner = executorOwner,
        )
    }

    private class RecordingGateway(val session: RecordingProviderSession) : CameraProviderGateway {
        var requestCount = 0

        override fun request(
            callbackExecutor: Executor,
            callback: (Result<CameraProviderSession>) -> Unit,
        ) {
            requestCount += 1
            callbackExecutor.execute { callback(Result.success(session)) }
        }
    }

    private class DirectCallbackGateway(val session: RecordingProviderSession) : CameraProviderGateway {
        var requestCount = 0

        override fun request(
            callbackExecutor: Executor,
            callback: (Result<CameraProviderSession>) -> Unit,
        ) {
            requestCount += 1
            callback(Result.success(session))
        }
    }

    private class DeferredGateway : CameraProviderGateway {
        private var completion: ((Result<CameraProviderSession>) -> Unit)? = null
        val hasPendingResult: Boolean
            get() = completion != null

        override fun request(
            callbackExecutor: Executor,
            callback: (Result<CameraProviderSession>) -> Unit,
        ) {
            completion = { result -> callbackExecutor.execute { callback(result) } }
        }

        fun complete(result: Result<CameraProviderSession>) {
            val pending = requireNotNull(completion)
            completion = null
            pending(result)
        }
    }

    private class RecordingProviderSession(
        private val frontAvailable: Boolean = true,
        private val backAvailable: Boolean = true,
        private val bindFailureAfterRecord: RuntimeException? = null,
        private val unbindFailureAfterRecord: RuntimeException? = null,
    ) : CameraProviderSession {
        val bindGroups = mutableListOf<UseCaseGroup>()
        val bindSelections = mutableListOf<CameraLensSelection>()
        val unbound = mutableListOf<Array<out UseCase>>()

        override fun hasFrontCamera(): Boolean = frontAvailable

        override fun hasCamera(lensSelection: CameraLensSelection): Boolean =
            when (lensSelection) {
                CameraLensSelection.FRONT -> frontAvailable
                CameraLensSelection.BACK -> backAvailable
            }

        val camera = MutableCameraHandleFixture()

        override fun bindFront(
            lifecycleOwner: LifecycleOwner,
            useCaseGroup: UseCaseGroup,
        ): BoundCameraHandle =
            recordBind(lifecycleOwner, CameraLensSelection.FRONT, useCaseGroup)

        override fun bind(
            lifecycleOwner: LifecycleOwner,
            lensSelection: CameraLensSelection,
            useCaseGroup: UseCaseGroup,
        ): BoundCameraHandle = recordBind(lifecycleOwner, lensSelection, useCaseGroup)

        private fun recordBind(
            lifecycleOwner: LifecycleOwner,
            lensSelection: CameraLensSelection,
            useCaseGroup: UseCaseGroup,
        ): BoundCameraHandle {
            assertTrue(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))
            bindGroups += useCaseGroup
            bindSelections += lensSelection
            bindFailureAfterRecord?.let { throw it }
            return camera.handle
        }

        override fun unbind(useCases: Array<out UseCase>) {
            unbound += useCases
            unbindFailureAfterRecord?.let { throw it }
        }
    }

    private class DirectExecutorOwner : CameraAnalysisExecutorOwner {
        override val executor: Executor = Executor(Runnable::run)
        var released = false

        override fun release() {
            assertFalse(released)
            released = true
        }
    }

    private class RecordingLivePoseSession : LivePoseSession {
        var taskTimestampMs: Long = -1L
        var closeCount = 0

        override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
            this.taskTimestampMs = taskTimestampMs
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class RecordingScopeWatcherFactory(
        private val stopResult: Boolean = true,
        private val startFailure: RuntimeException? = null,
    ) : CameraScopeWatcherFactory {
        val watchers = mutableListOf<RecordingScopeWatcher>()

        override fun create(
            previewView: PreviewView,
            onEvent: (CameraScopeWatchEvent) -> Unit,
        ): CameraScopeWatcher =
            RecordingScopeWatcher(onEvent, stopResult, startFailure).also(watchers::add)

        val activeCount: Int
            get() = watchers.count(RecordingScopeWatcher::active)
    }

    private class RecordingScopeWatcher(
        private val onEvent: (CameraScopeWatchEvent) -> Unit,
        private val stopResult: Boolean,
        private val startFailure: RuntimeException?,
    ) : CameraScopeWatcher {
        var active = false
            private set
        var stopCount = 0
            private set

        override fun start(lifecycleOwner: LifecycleOwner) {
            assertFalse(active)
            active = true
            startFailure?.let { throw it }
        }

        override fun stop(): Boolean {
            if (active) {
                stopCount += 1
                if (stopResult) active = false
            }
            return stopResult
        }

        fun emit(event: CameraScopeWatchEvent = CameraScopeWatchEvent.VALIDATE) {
            check(active)
            onEvent(event)
        }
    }

    private class RecordingStreamStateSource(
        private val removeFailure: RuntimeException? = null,
    ) : CameraStreamStateSource {
        private val observers = mutableListOf<Observer<PreviewView.StreamState>>()
        var removeCount = 0
            private set

        override fun observe(
            lifecycleOwner: LifecycleOwner,
            observer: Observer<PreviewView.StreamState>,
        ) {
            observers += observer
        }

        override fun remove(observer: Observer<PreviewView.StreamState>) {
            removeCount += 1
            removeFailure?.let { throw it }
            observers.remove(observer)
        }

        val observerCount: Int
            get() = observers.size

        fun emit(state: PreviewView.StreamState) {
            observers.toList().forEach { it.onChanged(state) }
        }
    }

    private class MutableViewPortProvider(var viewPort: ViewPort) : CameraViewPortProvider {
        override fun current(previewView: PreviewView): ViewPort = viewPort
    }

    private class FirstReadMissingViewPortProvider(private val available: ViewPort) :
        CameraViewPortProvider {
        var readCount = 0
            private set

        override fun current(previewView: PreviewView): ViewPort? {
            readCount += 1
            return if (readCount == 1) null else available
        }
    }

    private class FaultInjectingViewPortLayoutListenerSource(
        private val callbackView: View,
        private val addFailureAfterStoreAndCallback: RuntimeException? = null,
        private val removeFailure: RuntimeException? = null,
    ) : CameraViewPortLayoutListenerSource {
        private var storedListener: View.OnLayoutChangeListener? = null
        private var lastListener: View.OnLayoutChangeListener? = null
        var addCount = 0
            private set
        var removeCount = 0
            private set

        override fun add(listener: View.OnLayoutChangeListener) {
            addCount += 1
            storedListener = listener
            lastListener = listener
            listener.onLayoutChange(callbackView, 0, 0, 1, 1, 0, 0, 0, 0)
            addFailureAfterStoreAndCallback?.let { throw it }
        }

        override fun remove(listener: View.OnLayoutChangeListener) {
            removeCount += 1
            removeFailure?.let { throw it }
            if (storedListener === listener) storedListener = null
        }

        fun emitLastListener() {
            lastListener?.onLayoutChange(callbackView, 0, 0, 1, 1, 0, 0, 0, 0)
        }
    }

    private class MutableScopeResolver(
        private val base: (BoundCameraScopeInput) -> BoundCameraScopeSnapshot,
    ) : BoundCameraScopeResolver {
        var mutation: (BoundCameraScopeSnapshot) -> BoundCameraScopeSnapshot = { it }

        override fun resolve(input: BoundCameraScopeInput): BoundCameraScopeSnapshot =
            mutation(base(input))
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override fun getLifecycle(): Lifecycle = registry
    }

    private class QueueingExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            pending.addLast(command)
        }

        fun runAll() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private class FailAtExecutionThenDirectExecutor(
        private val failAtExecution: Int,
    ) : Executor {
        var executionCount = 0
            private set

        override fun execute(command: Runnable) {
            executionCount += 1
            if (executionCount == failAtExecution) {
                throw IllegalStateException("main executor rejected callback")
            }
            command.run()
        }
    }

    private fun testImageProxy(
        timestamp: Long,
        closeCount: AtomicInteger,
    ): ImageProxy {
        val imageInfo =
            Proxy.newProxyInstance(
                ImageInfo::class.java.classLoader,
                arrayOf(ImageInfo::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getTimestamp" -> timestamp
                    "getRotationDegrees" -> 0
                    "toString" -> "ControllerLateImageInfo"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            } as ImageInfo
        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getImageInfo" -> imageInfo
                "getWidth" -> 640
                "getHeight" -> 480
                "getCropRect" -> Rect(0, 0, 640, 480)
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "toString" -> "ControllerLateImageProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        } as ImageProxy
    }

    private fun rgbaControllerImageProxy(
        timestamp: Long,
        closeCount: AtomicInteger,
    ): ImageProxy {
        val source = ByteBuffer.allocateDirect(640 * 480 * 4)
        val plane =
            Proxy.newProxyInstance(
                ImageProxy.PlaneProxy::class.java.classLoader,
                arrayOf(ImageProxy.PlaneProxy::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getBuffer" -> source.duplicate()
                    "getPixelStride" -> 4
                    "getRowStride" -> 640 * 4
                    "toString" -> "ControllerRgbaPlane"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            } as ImageProxy.PlaneProxy
        val imageInfo =
            Proxy.newProxyInstance(
                ImageInfo::class.java.classLoader,
                arrayOf(ImageInfo::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getTimestamp" -> timestamp
                    "getRotationDegrees" -> 0
                    "toString" -> "ControllerRgbaImageInfo"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            } as ImageInfo
        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getFormat" -> PixelFormat.RGBA_8888
                "getPlanes" -> arrayOf(plane)
                "getImageInfo" -> imageInfo
                "getWidth" -> 640
                "getHeight" -> 480
                "getCropRect" -> Rect(0, 0, 640, 480)
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "toString" -> "ControllerRgbaImageProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        } as ImageProxy
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
}
