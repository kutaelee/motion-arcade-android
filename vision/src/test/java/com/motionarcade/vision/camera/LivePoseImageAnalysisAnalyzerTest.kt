package com.motionarcade.vision.camera

import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Surface
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.pose.LivePoseInputFrame
import com.motionarcade.vision.pose.CapturingObservationDispatcher
import com.motionarcade.vision.pose.LivePoseSession
import com.motionarcade.vision.pose.LivePoseSessionCallbacks
import com.motionarcade.vision.pose.LivePoseSessionFactory
import com.motionarcade.vision.pose.emitValidLivePoseResult
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LivePoseImageAnalysisAnalyzerTest {
    @Test
    fun paddedRgbaFrameIsSubmittedThenZeroedAndProxyClosesExactlyOnce() {
        val fixture = activeFixture()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val observations = CapturingObservationDispatcher(fixture.token.generation)
        val session = CapturingSession()
        lateinit var callbacks: LivePoseSessionCallbacks
        val closeCount = AtomicInteger()
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = fixture.gate,
                sessionToken = fixture.token,
                metadataSink = CameraFrameMetadataSink.NONE,
                counters = fixture.counters,
                sessionFactory = LivePoseSessionFactory {
                    callbacks = it
                    session
                },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = observations,
                ownerExecutor = ExecutorDirect,
            )

        analyzer.analyze(rgbaImageProxy(closeCount = closeCount))
        callbacks.emitValidLivePoseResult(2, session.taskTimestampMs)

        assertEquals(1, closeCount.get())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), session.bytesAtSubmit)
        assertTrue(requireNotNull(session.retainedBuffer).allBytesAreZero())
        assertEquals(LivePoseInferencePhase.ACTIVE, snapshots.last().phase)
        assertEquals(2, snapshots.last().poseCount)
        assertEquals(listOf(2), observations.capturedPoseCounts())
        assertEquals(listOf(50L), observations.capturedSourceTimestampsNs())
        assertEquals(listOf(session.taskTimestampMs), observations.capturedTaskTimestampsMs())
        assertEquals(1L, fixture.counters.snapshot().metadataDelivered)
        assertEquals(1L, fixture.counters.snapshot().closeSucceeded)
    }

    @Test
    fun releaseClosesRuntimeOnceAndLateCallbackIsIgnored() {
        val fixture = activeFixture()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val session = CapturingSession()
        lateinit var callbacks: LivePoseSessionCallbacks
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = fixture.gate,
                sessionToken = fixture.token,
                metadataSink = CameraFrameMetadataSink.NONE,
                counters = fixture.counters,
                sessionFactory = LivePoseSessionFactory {
                    callbacks = it
                    session
                },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(fixture.token.generation),
                ownerExecutor = ExecutorDirect,
            )
        analyzer.analyze(rgbaImageProxy(closeCount = AtomicInteger()))

        analyzer.release()
        analyzer.release()
        callbacks.emitValidLivePoseResult(1, session.taskTimestampMs)

        assertEquals(1, session.closeCount)
        assertEquals(LivePoseInferencePhase.RELEASED, snapshots.last().phase)
    }

    @Test
    fun invalidPlaneFailsInferenceButStillClosesTheCameraFrame() {
        val fixture = activeFixture()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val closeCount = AtomicInteger()
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = fixture.gate,
                sessionToken = fixture.token,
                metadataSink = CameraFrameMetadataSink.NONE,
                counters = fixture.counters,
                sessionFactory = LivePoseSessionFactory { CapturingSession() },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(fixture.token.generation),
                ownerExecutor = ExecutorDirect,
            )

        analyzer.analyze(rgbaImageProxy(pixelStride = 3, closeCount = closeCount))

        assertEquals(1, closeCount.get())
        assertEquals(LivePoseInferencePhase.FAILED, snapshots.last().phase)
        assertEquals(1L, fixture.counters.snapshot().processingFailures)
        assertEquals(1L, fixture.counters.snapshot().closeSucceeded)
    }

    @Test
    fun frameWhileOneInferenceIsPendingIsDroppedAsBusyWithoutFailingThePipeline() {
        val fixture = activeFixture()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val session = CapturingSession()
        val firstCloseCount = AtomicInteger()
        val secondCloseCount = AtomicInteger()
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = fixture.gate,
                sessionToken = fixture.token,
                metadataSink = CameraFrameMetadataSink.NONE,
                counters = fixture.counters,
                sessionFactory = LivePoseSessionFactory { session },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(fixture.token.generation),
                ownerExecutor = ExecutorDirect,
            )

        analyzer.analyze(rgbaImageProxy(timestamp = 50L, closeCount = firstCloseCount))
        analyzer.analyze(rgbaImageProxy(timestamp = 51L, closeCount = secondCloseCount))

        assertEquals(1, session.detectCount)
        assertEquals(1, firstCloseCount.get())
        assertEquals(1, secondCloseCount.get())
        assertEquals(0L, fixture.counters.snapshot().processingFailures)
        assertEquals(LivePoseInferencePhase.WAITING_FOR_RESULT, snapshots.last().phase)
        analyzer.release()
    }

    @Test
    fun ownerExecutorRejectionCannotAbandonNativeClose() {
        val fixture = activeFixture()
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val nativeClosed = CountDownLatch(1)
        val session = CapturingSession(nativeClosed)
        val rejectingExecutor =
            java.util.concurrent.Executor {
                throw RejectedExecutionException("executor deliberately rejected close")
            }
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = fixture.gate,
                sessionToken = fixture.token,
                metadataSink = CameraFrameMetadataSink.NONE,
                counters = fixture.counters,
                sessionFactory = LivePoseSessionFactory { session },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(fixture.token.generation),
                ownerExecutor = rejectingExecutor,
            )

        analyzer.analyze(rgbaImageProxy(closeCount = AtomicInteger()))
        analyzer.release()
        analyzer.release()

        assertTrue("native close was abandoned after executor rejection", nativeClosed.await(2L, TimeUnit.SECONDS))
        assertEquals(1, session.closeCount)
        assertEquals(LivePoseInferencePhase.RELEASED, snapshots.last().phase)
    }

    @Test
    fun ownerRejectionDefersCloseUntilExecutingAnalyzeReleasesSerialOwnership() {
        val fixture = activeFixture()
        val detectEntered = CountDownLatch(1)
        val releaseDetect = CountDownLatch(1)
        val nativeClosed = CountDownLatch(1)
        val detectRunning = AtomicBoolean(false)
        val closeOverlappedDetect = AtomicBoolean(false)
        val session =
            object : LivePoseSession {
                override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
                    detectRunning.set(true)
                    detectEntered.countDown()
                    releaseDetect.await(5L, TimeUnit.SECONDS)
                    detectRunning.set(false)
                }

                override fun close() {
                    if (detectRunning.get()) closeOverlappedDetect.set(true)
                    nativeClosed.countDown()
                }
            }
        val rejectingOwner =
            java.util.concurrent.Executor {
                throw RejectedExecutionException("owner deliberately rejected close")
            }
        val analyzer =
            LivePoseImageAnalysisAnalyzer(
                sessionGate = fixture.gate,
                sessionToken = fixture.token,
                metadataSink = CameraFrameMetadataSink.NONE,
                counters = fixture.counters,
                sessionFactory = LivePoseSessionFactory { session },
                inferenceSink = LivePoseInferenceSink.NONE,
                observationDispatcher = CapturingObservationDispatcher(fixture.token.generation),
                ownerExecutor = rejectingOwner,
            )
        val analyze =
            Thread {
                analyzer.analyze(rgbaImageProxy(closeCount = AtomicInteger()))
            }
        analyze.start()
        assertTrue(detectEntered.await(2L, TimeUnit.SECONDS))

        analyzer.release()
        assertTrue("fallback close raced the executing analyzer", nativeClosed.count != 0L)
        releaseDetect.countDown()
        analyze.join(2_000L)

        assertTrue(nativeClosed.await(2L, TimeUnit.SECONDS))
        assertTrue("analyzer thread failed to release ownership", !analyze.isAlive)
        assertTrue("native close overlapped detectAsync", !closeOverlappedDetect.get())
    }

    private data class ActiveFixture(
        val gate: CameraSessionRequestGate,
        val token: CameraSessionRequestGate.Token,
        val counters: CameraFrameCounters,
    )

    private fun activeFixture(): ActiveFixture {
        val gate = CameraSessionRequestGate()
        val token = gate.begin()
        val camera = MutableCameraHandleFixture()
        assertTrue(
            gate.activate(
                token,
                camera.handle,
                testBoundCameraScopeSnapshot(
                    cameraHandle = camera.handle,
                    analysisWidth = 2,
                    analysisHeight = 1,
                    analysisCrop = CameraFrameCrop(0, 0, 2, 1),
                    analysisRotationDegrees = 90,
                    previewRotationDegrees = 90,
                    targetRotation = Surface.ROTATION_0,
                ),
            ),
        )
        return ActiveFixture(gate, token, CameraFrameCounters())
    }

    private class CapturingSession(
        private val closeSignal: CountDownLatch? = null,
    ) : LivePoseSession {
        var bytesAtSubmit: List<Int> = emptyList()
        var retainedBuffer: ByteBuffer? = null
        var taskTimestampMs: Long = -1L
        var detectCount: Int = 0
        var closeCount = 0

        override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
            detectCount += 1
            this.taskTimestampMs = taskTimestampMs
            retainedBuffer = frame.rgba
            bytesAtSubmit = frame.rgba.duplicate().run {
                position(0)
                List(remaining()) { get().toInt() and 0xff }
            }
        }

        override fun close() {
            closeCount += 1
            closeSignal?.countDown()
        }
    }

    private fun rgbaImageProxy(
        pixelStride: Int = 4,
        timestamp: Long = 50L,
        closeCount: AtomicInteger,
    ): ImageProxy {
        val source = ByteBuffer.allocateDirect(12).apply {
            put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 90, 91, 92, 93))
            flip()
        }
        val plane =
            Proxy.newProxyInstance(
                ImageProxy.PlaneProxy::class.java.classLoader,
                arrayOf(ImageProxy.PlaneProxy::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getBuffer" -> source.duplicate()
                    "getPixelStride" -> pixelStride
                    "getRowStride" -> 12
                    "toString" -> "LivePoseTestPlane"
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
                    "getRotationDegrees" -> 90
                    "toString" -> "LivePoseTestImageInfo"
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
                "getWidth" -> 2
                "getHeight" -> 1
                "getCropRect" -> Rect(0, 0, 2, 1)
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "toString" -> "LivePoseTestImageProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        } as ImageProxy
    }

    private fun ByteBuffer.allBytesAreZero(): Boolean =
        duplicate().run {
            clear()
            while (hasRemaining()) {
                if (get().toInt() != 0) return@run false
            }
            true
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

    private object ExecutorDirect : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
