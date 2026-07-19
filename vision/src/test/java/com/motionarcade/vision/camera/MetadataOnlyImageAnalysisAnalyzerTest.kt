package com.motionarcade.vision.camera

import android.graphics.Rect
import android.view.Surface
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetadataOnlyImageAnalysisAnalyzerTest {
    @Test
    fun successHandsOffImmutableRotationAndMirrorMetadataThenClosesExactlyOnce() {
        val fixture = activeFixture(rotationDegrees = 270, targetRotation = Surface.ROTATION_90)
        val closeCount = AtomicInteger()
        var metadata: CameraFrameMetadata? = null
        val analyzer = fixture.analyzer(CameraFrameMetadataSink { metadata = it })

        analyzer.analyze(imageProxy(timestamp = 91L, rotationDegrees = 270, closeCount = closeCount))

        assertEquals(1, closeCount.get())
        assertEquals(
            CameraFrameMetadata(
                sessionGeneration = fixture.token.generation,
                frameOrdinal = 0L,
                sourceTimestampNanos = 91L,
                analysisWidth = 640,
                analysisHeight = 480,
                crop = CameraFrameCrop(0, 0, 640, 480),
                clockwiseRotationDegrees = 270,
                targetSurfaceRotation = Surface.ROTATION_90,
                lens = CameraFrameLens.FRONT,
                analysisMirrored = false,
                previewMirrored = true,
            ),
            metadata,
        )
        assertFalse(requireNotNull(metadata).analysisMirrored)
        assertTrue(requireNotNull(metadata).previewMirrored)
        assertEquals(
            CameraFrameCounterSnapshot(
                callbacksReceived = 1,
                metadataDelivered = 1,
                staleGenerationRejected = 0,
                capacityRejected = 0,
                metadataRejected = 0,
                processingFailures = 0,
                closeAttempts = 1,
                closeSucceeded = 1,
                closeFailures = 0,
            ),
            fixture.counters.snapshot(),
        )
    }

    @Test
    fun rebindAndLifecycleStopMakeOldGenerationStaleAndEachCallbackStillCloses() {
        val fixture = activeFixture()
        var delivered = 0
        val analyzer = fixture.analyzer(CameraFrameMetadataSink { delivered += 1 })
        val firstCloseCount = AtomicInteger()
        val secondCloseCount = AtomicInteger()

        fixture.gate.begin()
        analyzer.analyze(imageProxy(timestamp = 1L, closeCount = firstCloseCount))
        fixture.gate.cancel()
        analyzer.analyze(imageProxy(timestamp = 2L, closeCount = secondCloseCount))

        assertEquals(0, delivered)
        assertEquals(1, firstCloseCount.get())
        assertEquals(1, secondCloseCount.get())
        assertEquals(2, fixture.counters.snapshot().staleGenerationRejected)
        assertEquals(2, fixture.counters.snapshot().closeAttempts)
        assertEquals(2, fixture.counters.snapshot().closeSucceeded)
    }

    @Test
    fun scopeMismatchAndNonMonotonicTimestampAreRejectedWithoutPixelHandoff() {
        val fixture = activeFixture()
        var delivered = 0
        var violations = 0
        val analyzer =
            fixture.analyzer(
                sink = CameraFrameMetadataSink { delivered += 1 },
                violationSink = CameraFrameScopeViolationSink { violations += 1 },
            )
        val closes = AtomicInteger()

        analyzer.analyze(imageProxy(timestamp = 10L, closeCount = closes))
        analyzer.analyze(imageProxy(timestamp = 9L, closeCount = closes))
        analyzer.analyze(imageProxy(timestamp = 11L, width = 641, closeCount = closes))

        assertEquals(1, delivered)
        assertEquals(1, violations)
        assertEquals(3, closes.get())
        assertEquals(1, fixture.counters.snapshot().metadataRejected)
        assertEquals(1, fixture.counters.snapshot().staleGenerationRejected)
        assertEquals(1, fixture.counters.snapshot().metadataDelivered)
    }

    @Test
    fun metadataSinkExceptionClosesOnceAndCloseFailureIsSuppressedBehindIt() {
        val fixture = activeFixture()
        val sinkFailure = IllegalStateException("metadata sink failed")
        val closeFailure = IllegalArgumentException("close failed")
        val closeCount = AtomicInteger()
        val analyzer = fixture.analyzer(CameraFrameMetadataSink { throw sinkFailure })

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                analyzer.analyze(
                    imageProxy(
                        timestamp = 1L,
                        closeCount = closeCount,
                        closeFailure = closeFailure,
                    ),
                )
            }

        assertSame(sinkFailure, thrown)
        assertEquals(1, closeCount.get())
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
        assertEquals(1, fixture.counters.snapshot().processingFailures)
        assertEquals(1, fixture.counters.snapshot().closeAttempts)
        assertEquals(0, fixture.counters.snapshot().closeSucceeded)
        assertEquals(1, fixture.counters.snapshot().closeFailures)
        assertFalse(fixture.gate.isCurrent(fixture.token))
    }

    @Test
    fun concurrentSecondCallbackIsLatestOnlyRejectedAtCapacityOneAndBothClose() {
        val fixture = activeFixture()
        val firstEnteredSink = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstClose = AtomicInteger()
        val secondClose = AtomicInteger()
        val analyzer =
            fixture.analyzer(
                CameraFrameMetadataSink {
                    firstEnteredSink.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit {
                analyzer.analyze(imageProxy(timestamp = 1L, closeCount = firstClose))
            }
            assertTrue(firstEnteredSink.await(2, TimeUnit.SECONDS))

            // Must complete while the sink is blocked: downstream code is outside the gate lock.
            fixture.gate.cancel()
            analyzer.analyze(imageProxy(timestamp = 2L, closeCount = secondClose))
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)

            assertEquals(1, firstClose.get())
            assertEquals(1, secondClose.get())
            assertEquals(1, fixture.counters.snapshot().capacityRejected)
            assertEquals(1, fixture.counters.snapshot().metadataDelivered)
            assertEquals(2, fixture.counters.snapshot().closeSucceeded)
        } finally {
            releaseFirst.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun capacityLeaseRemainsHeldUntilDelayedFailingCloseResolves() {
        val fixture = activeFixture()
        val firstCloseEntered = CountDownLatch(1)
        val releaseFirstClose = CountDownLatch(1)
        val firstCloseFailure = IllegalStateException("first close failed")
        val firstClose = AtomicInteger()
        val secondClose = AtomicInteger()
        var delivered = 0
        var abortCalls = 0
        val analyzer =
            fixture.analyzer(
                sink = CameraFrameMetadataSink { delivered += 1 },
                violationSink = CameraFrameScopeViolationSink { abortCalls += 1 },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first =
                worker.submit {
                    analyzer.analyze(
                        imageProxy(
                            timestamp = 1L,
                            closeCount = firstClose,
                            closeFailure = firstCloseFailure,
                            closeEntered = firstCloseEntered,
                            closeRelease = releaseFirstClose,
                        ),
                    )
                }
            assertTrue(firstCloseEntered.await(2, TimeUnit.SECONDS))

            analyzer.analyze(imageProxy(timestamp = 2L, closeCount = secondClose))

            assertFalse(first.isDone)
            assertEquals(1, delivered)
            assertEquals(1, firstClose.get())
            assertEquals(1, secondClose.get())
            assertEquals(1, fixture.counters.snapshot().capacityRejected)

            releaseFirstClose.countDown()
            val wrapper =
                assertThrows(ExecutionException::class.java) {
                    first.get(2, TimeUnit.SECONDS)
                }
            assertSame(firstCloseFailure, wrapper.cause)
            assertEquals(1, abortCalls)
            assertFalse(fixture.gate.isCurrent(fixture.token))
            assertEquals(2, fixture.counters.snapshot().closeAttempts)
            assertEquals(1, fixture.counters.snapshot().closeSucceeded)
            assertEquals(1, fixture.counters.snapshot().closeFailures)
        } finally {
            releaseFirstClose.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun imageMetadataReadExceptionStillClosesExactlyOnce() {
        val fixture = activeFixture()
        val closeCount = AtomicInteger()
        val readFailure = IllegalArgumentException("timestamp unavailable")
        val analyzer = fixture.analyzer(CameraFrameMetadataSink { error("must not deliver") })

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                analyzer.analyze(
                    imageProxy(
                        timestamp = 0L,
                        closeCount = closeCount,
                        timestampFailure = readFailure,
                    ),
                )
            }

        assertSame(readFailure, thrown)
        assertEquals(1, closeCount.get())
        assertEquals(1, fixture.counters.snapshot().processingFailures)
        assertEquals(1, fixture.counters.snapshot().closeSucceeded)
        assertFalse(fixture.gate.isCurrent(fixture.token))
    }

    @Test
    fun closeFailureWithoutProcessingFailureInvalidatesSessionAndPreservesCloseAsPrimary() {
        val fixture = activeFixture()
        val closeFailure = IllegalStateException("close uncertain")
        val abortFailure = IllegalArgumentException("abort observer failed")
        val closeCount = AtomicInteger()
        var abortCalls = 0
        val analyzer =
            fixture.analyzer(
                sink = CameraFrameMetadataSink.NONE,
                violationSink =
                    CameraFrameScopeViolationSink {
                        abortCalls += 1
                        throw abortFailure
                    },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                analyzer.analyze(
                    imageProxy(
                        timestamp = 1L,
                        closeCount = closeCount,
                        closeFailure = closeFailure,
                    ),
                )
            }

        assertSame(closeFailure, thrown)
        assertEquals(listOf(abortFailure), thrown.suppressed.toList())
        assertEquals(1, abortCalls)
        assertEquals(1, closeCount.get())
        assertFalse(fixture.gate.isCurrent(fixture.token))
    }

    @Test
    fun identicalFailureObjectAcrossSinkAbortAndCloseCannotMaskOriginal() {
        val fixture = activeFixture()
        val sharedFailure = IllegalStateException("shared failure")
        val closeCount = AtomicInteger()
        val analyzer =
            fixture.analyzer(
                sink = CameraFrameMetadataSink { throw sharedFailure },
                violationSink = CameraFrameScopeViolationSink { throw sharedFailure },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                analyzer.analyze(
                    imageProxy(
                        timestamp = 1L,
                        closeCount = closeCount,
                        closeFailure = sharedFailure,
                    ),
                )
            }

        assertSame(sharedFailure, thrown)
        assertTrue(thrown.suppressed.isEmpty())
        assertEquals(1, closeCount.get())
        assertFalse(fixture.gate.isCurrent(fixture.token))
    }

    @Test
    fun cameraIdentityOrHashedTokenDriftInvalidatesThenLateCallbackIsStale() {
        listOf<(MutableCameraHandleFixture) -> Unit>(
            { camera -> camera.cameraIdentity = Any() },
            { camera -> camera.publicCamera2Id = "front-camera-changed" },
        ).forEach { mutation ->
            val fixture = activeFixture()
            var delivered = 0
            var abortCalls = 0
            val closes = AtomicInteger()
            val analyzer =
                fixture.analyzer(
                    sink = CameraFrameMetadataSink { delivered += 1 },
                    violationSink = CameraFrameScopeViolationSink { abortCalls += 1 },
                )

            analyzer.analyze(imageProxy(timestamp = 1L, closeCount = closes))
            mutation(fixture.camera)
            analyzer.analyze(imageProxy(timestamp = 2L, closeCount = closes))
            analyzer.analyze(imageProxy(timestamp = 3L, closeCount = closes))

            assertEquals(1, delivered)
            assertEquals(1, abortCalls)
            assertEquals(3, closes.get())
            assertEquals(1, fixture.counters.snapshot().metadataRejected)
            assertEquals(1, fixture.counters.snapshot().staleGenerationRejected)
            assertEquals(3, fixture.counters.snapshot().closeSucceeded)
            assertFalse(fixture.gate.isCurrent(fixture.token))
        }
    }

    private data class ActiveFixture(
        val gate: CameraSessionRequestGate,
        val token: CameraSessionRequestGate.Token,
        val counters: CameraFrameCounters,
        val camera: MutableCameraHandleFixture,
    ) {
        fun analyzer(
            sink: CameraFrameMetadataSink,
            violationSink: CameraFrameScopeViolationSink = CameraFrameScopeViolationSink.NONE,
        ): MetadataOnlyImageAnalysisAnalyzer =
            MetadataOnlyImageAnalysisAnalyzer(gate, token, sink, counters, violationSink)
    }

    private fun activeFixture(
        rotationDegrees: Int = 0,
        targetRotation: Int = Surface.ROTATION_0,
    ): ActiveFixture {
        val gate = CameraSessionRequestGate()
        val token = gate.begin()
        val camera = MutableCameraHandleFixture()
        assertTrue(
            gate.activate(
                token = token,
                cameraHandle = camera.handle,
                scope =
                    testBoundCameraScopeSnapshot(
                        cameraHandle = camera.handle,
                        analysisRotationDegrees = rotationDegrees,
                        previewRotationDegrees = rotationDegrees,
                        targetRotation = targetRotation,
                    ),
            ),
        )
        return ActiveFixture(gate, token, CameraFrameCounters(), camera)
    }

    private fun imageProxy(
        timestamp: Long,
        rotationDegrees: Int = 0,
        width: Int = 640,
        height: Int = 480,
        crop: Rect = Rect(0, 0, 640, 480),
        closeCount: AtomicInteger,
        closeFailure: Throwable? = null,
        timestampFailure: Throwable? = null,
        closeEntered: CountDownLatch? = null,
        closeRelease: CountDownLatch? = null,
    ): ImageProxy {
        val imageInfo =
            Proxy.newProxyInstance(
                ImageInfo::class.java.classLoader,
                arrayOf(ImageInfo::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getTimestamp" -> timestampFailure?.let { throw it } ?: timestamp
                    "getRotationDegrees" -> rotationDegrees
                    "toString" -> "MetadataOnlyTestImageInfo"
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
                "getWidth" -> width
                "getHeight" -> height
                "getCropRect" -> Rect(crop)
                "close" -> {
                    closeCount.incrementAndGet()
                    closeEntered?.countDown()
                    if (closeRelease != null) {
                        check(closeRelease.await(2, TimeUnit.SECONDS)) { "close release timed out" }
                    }
                    closeFailure?.let { throw it }
                    null
                }
                "toString" -> "MetadataOnlyTestImageProxy"
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
