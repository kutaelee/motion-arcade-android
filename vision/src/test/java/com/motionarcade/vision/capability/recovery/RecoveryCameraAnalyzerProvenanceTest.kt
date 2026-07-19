package com.motionarcade.vision.capability.recovery

import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCameraAnalyzerProvenanceTest {
    @Test
    fun onlyExactSinglePlaneFullCropUnrotatedRgbaLayoutIsAccepted() {
        val invalid =
            listOf<() -> ImageProxy>(
                { RecoveryClosureTestFixture.imageProxy(width = 0) },
                { RecoveryClosureTestFixture.imageProxy(height = 0) },
                { RecoveryClosureTestFixture.imageProxy(format = PixelFormat.RGB_565) },
                { RecoveryClosureTestFixture.imageProxy(planeCount = 2) },
                { RecoveryClosureTestFixture.imageProxy(pixelStride = 3) },
                {
                    RecoveryClosureTestFixture.imageProxy(
                        width = 2,
                        height = 2,
                        rowStride = 7,
                    )
                },
                {
                    RecoveryClosureTestFixture.imageProxy(
                        width = 2,
                        height = 2,
                        cropRight = 1,
                    )
                },
                { RecoveryClosureTestFixture.imageProxy(rotationDegrees = 90) },
                {
                    RecoveryClosureTestFixture.imageProxy(
                        width = 2,
                        height = 2,
                        sourceBuffer = ByteBuffer.allocateDirect(15),
                    )
                },
                {
                    RecoveryClosureTestFixture.imageProxy(
                        width = 2,
                        height = 2,
                        sourceBuffer = ByteBuffer.allocateDirect(17),
                    )
                },
            )

        invalid.forEachIndexed { index, sourceFactory ->
            val closeCalls = AtomicInteger()
            val proxy = sourceFactory()
            val proxyWithClose = proxyWithForwardedClose(proxy) { closeCalls.incrementAndGet() }
            val owner = RecoveryClosureTestFixture.cameraPipelineOwner(generation = 90_000L + index)
            val generation = 91_000L + index
            assertTrue(RecoveryCameraPipelineBoundary.claimForResourceGeneration(owner, generation))
            val source =
                requireNotNull(RecoveryClosureTestFixture.analyzerSource(owner, proxyWithClose))
            try {
                assertNull(RecoveryCameraPipelineBoundary.ownFrame(owner, generation, source))
            } finally {
                RecoveryClosureTestFixture.finishAnalyzerSource(source)
            }
            assertEquals(1, closeCalls.get())
        }
    }

    @Test
    fun activeRowsAreCopiedIntoNewExactDirectOwnerBufferAndPaddingIsExcluded() {
        val sourceBuffer =
            ByteBuffer.allocateDirect(24).apply {
                for (value in 1..24) put(value.toByte())
                clear()
            }
        val owner = RecoveryClosureTestFixture.cameraPipelineOwner(generation = 92_001L)
        val generation = 92_002L
        assertTrue(RecoveryCameraPipelineBoundary.claimForResourceGeneration(owner, generation))
        val source =
            requireNotNull(
                RecoveryClosureTestFixture.analyzerSource(
                    owner,
                    RecoveryClosureTestFixture.imageProxy(
                        width = 2,
                        height = 2,
                        rowStride = 12,
                        sourceBuffer = sourceBuffer,
                    ),
                ),
            )
        val frame =
            try {
                requireNotNull(RecoveryCameraPipelineBoundary.ownFrame(owner, generation, source))
            } finally {
                RecoveryClosureTestFixture.finishAnalyzerSource(source)
            }
        val field = frame.javaClass.getDeclaredField("rgbaBuffer").also { it.isAccessible = true }
        val owned = field.get(frame) as ByteBuffer

        assertTrue(owned.isDirect)
        assertFalse(owned.isReadOnly)
        assertEquals(16, owned.capacity())
        assertEquals(0, owned.position())
        assertEquals(16, owned.limit())
        assertEquals(
            (1..8).plus(13..20).map(Int::toByte),
            (0 until owned.capacity()).map(owned::get),
        )
        assertFalse(owned === sourceBuffer)
    }

    @Test
    fun noCallerSuppliedByteBufferCanMintFrameAuthority() {
        val authorityTypes =
            setOf(
                RecoveryCameraPipelineBoundary::class.java,
                RecoveryCleanClosureBoundary::class.java,
            )
        assertTrue(
            authorityTypes.flatMap { it.declaredMethods.asList() }
                .filter { it.name.contains("Frame", ignoreCase = true) }
                .none { method -> method.parameterTypes.any { it == ByteBuffer::class.java } },
        )
    }

    private fun proxyWithForwardedClose(
        source: ImageProxy,
        onClose: () -> Unit,
    ): ImageProxy =
        RecoveryClosureTestFixture.imageProxy(
            width = source.width,
            height = source.height,
            format = source.format,
            pixelStride = source.planes.firstOrNull()?.pixelStride ?: 4,
            rowStride = source.planes.firstOrNull()?.rowStride ?: 4,
            cropLeft = source.cropRect.left,
            cropTop = source.cropRect.top,
            cropRight = source.cropRect.right,
            cropBottom = source.cropRect.bottom,
            rotationDegrees = source.imageInfo.rotationDegrees,
            planeCount = source.planes.size,
            sourceBuffer = source.planes.firstOrNull()?.buffer ?: ByteBuffer.allocateDirect(0),
            onClose = onClose,
        )
}
