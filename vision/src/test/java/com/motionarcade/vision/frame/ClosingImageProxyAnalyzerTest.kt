package com.motionarcade.vision.frame

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ClosingImageProxyAnalyzerTest {
    @Test
    fun successPathProcessesTimestampAndClosesExactlyOnce() {
        var closeCount = 0
        var observed: AcceptedFrameTimestamp? = null
        val analyzer =
            ClosingImageProxyAnalyzer(
                MonotonicFrameSequencer(),
                ImageProxyFrameProcessor { _, timestamp -> observed = timestamp },
            )

        analyzer.analyze(imageProxy(timestamp = { 42L }, close = { closeCount += 1 }))

        assertEquals(AcceptedFrameTimestamp(0L, 42L), observed)
        assertEquals(1, closeCount)
    }

    @Test
    fun processorExceptionStillClosesExactlyOnceAndPropagatesOriginal() {
        var closeCount = 0
        val processingFailure = IllegalStateException("processor failed")
        val analyzer =
            ClosingImageProxyAnalyzer(
                MonotonicFrameSequencer(),
                ImageProxyFrameProcessor { _, _ -> throw processingFailure },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                analyzer.analyze(imageProxy(timestamp = { 42L }, close = { closeCount += 1 }))
            }

        assertSame(processingFailure, thrown)
        assertEquals(1, closeCount)
    }

    @Test
    fun timestampReadExceptionStillClosesExactlyOnce() {
        var closeCount = 0
        val timestampFailure = IllegalArgumentException("timestamp unavailable")
        val analyzer =
            ClosingImageProxyAnalyzer(
                MonotonicFrameSequencer(),
                ImageProxyFrameProcessor { _, _ -> error("must not process") },
            )

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                analyzer.analyze(
                    imageProxy(
                        timestamp = { throw timestampFailure },
                        close = { closeCount += 1 },
                    ),
                )
            }

        assertSame(timestampFailure, thrown)
        assertEquals(1, closeCount)
    }

    @Test
    fun closeFailureIsSuppressedBehindPrimaryProcessingFailure() {
        val processingFailure = IllegalStateException("processor failed")
        val closeFailure = IllegalArgumentException("close failed")
        val analyzer =
            ClosingImageProxyAnalyzer(
                MonotonicFrameSequencer(),
                ImageProxyFrameProcessor { _, _ -> throw processingFailure },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                analyzer.analyze(imageProxy(timestamp = { 42L }, close = { throw closeFailure }))
            }

        assertSame(processingFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(closeFailure, thrown.suppressed.single())
    }

    @Test
    fun closeFailureWithoutPrimaryFailureIsPropagated() {
        val closeFailure = IllegalArgumentException("close failed")
        val analyzer =
            ClosingImageProxyAnalyzer(
                MonotonicFrameSequencer(),
                ImageProxyFrameProcessor { _, _ -> },
            )

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                analyzer.analyze(imageProxy(timestamp = { 42L }, close = { throw closeFailure }))
            }

        assertSame(closeFailure, thrown)
    }

    @Test
    fun outOfOrderFrameIsReportedNotProcessedAndStillClosed() {
        var closeCount = 0
        var processCount = 0
        val rejections = mutableListOf<FrameTimestampDecision.Rejected>()
        val analyzer =
            ClosingImageProxyAnalyzer(
                MonotonicFrameSequencer(),
                ImageProxyFrameProcessor { _, _ -> processCount += 1 },
                TimestampRejectionSink(rejections::add),
            )

        analyzer.analyze(imageProxy(timestamp = { 10L }, close = { closeCount += 1 }))
        analyzer.analyze(imageProxy(timestamp = { 9L }, close = { closeCount += 1 }))

        assertEquals(1, processCount)
        assertEquals(2, closeCount)
        assertEquals(1, rejections.size)
        assertEquals(TimestampRejectionReason.OUT_OF_ORDER_TIMESTAMP, rejections.single().reason)
    }

    @Test
    fun rejectionSinkExceptionStillClosesFrame() {
        var closeCount = 0
        val sinkFailure = IllegalStateException("diagnostic sink failed")
        val sequencer = MonotonicFrameSequencer()
        val analyzer =
            ClosingImageProxyAnalyzer(
                sequencer,
                ImageProxyFrameProcessor { _, _ -> },
                TimestampRejectionSink { throw sinkFailure },
            )
        analyzer.analyze(imageProxy(timestamp = { 10L }, close = { closeCount += 1 }))

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                analyzer.analyze(imageProxy(timestamp = { 9L }, close = { closeCount += 1 }))
            }

        assertSame(sinkFailure, thrown)
        assertEquals(2, closeCount)
    }

    private fun imageProxy(
        timestamp: () -> Long,
        close: () -> Unit,
    ): ImageProxy {
        val imageInfo =
            Proxy.newProxyInstance(
                ImageInfo::class.java.classLoader,
                arrayOf(ImageInfo::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getTimestamp" -> timestamp()
                    "toString" -> "TestImageInfo"
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
                "close" -> {
                    close()
                    null
                }
                "toString" -> "TestImageProxy"
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
