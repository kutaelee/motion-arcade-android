package com.motionarcade.vision.camera

import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.concurrent.Executor
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
class CameraUseCaseContractTest {
    @Test
    fun resolutionOrderingUsesExactNormalizedRatioAreaAndDimensionTies() {
        val ordered =
            CameraResolutionPolicy.order(
                listOf(
                    Size(1280, 720),
                    Size(1024, 768),
                    Size(640, 480),
                    Size(800, 600),
                    Size(0, 480),
                    Size(480, 640),
                    Size(320, 240),
                ),
            )

        assertEquals(
            listOf(
                Size(480, 640),
                Size(640, 480),
                Size(800, 600),
                Size(320, 240),
                Size(1024, 768),
                Size(1280, 720),
            ),
            ordered,
        )
    }

    @Test
    fun resolutionRatioComparisonCannotOverflowAtIntSizedDeviceBounds() {
        val nearSquare = Size(Int.MAX_VALUE, Int.MAX_VALUE - 1)
        val exactFourByThree = Size(2_000_000_000, 1_500_000_000)

        assertEquals(
            listOf(exactFourByThree, nearSquare),
            CameraResolutionPolicy.order(listOf(nearSquare, exactFourByThree)),
        )
    }

    @Test
    fun resolutionFilterRejectsUnknownRotationInsteadOfGuessing() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraResolutionPolicy.filter(listOf(Size(640, 480)), 45)
        }
    }

    @Test
    fun previewAndAnalysisShareOneViewPortUseCaseGroupAndExactAnalysisPolicy() {
        val viewPort =
            ViewPort.Builder(Rational(4, 3), Surface.ROTATION_90)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
        val bundle =
            CameraUseCaseFactory.create(
                viewPort = viewPort,
                analyzerExecutor = Executor(Runnable::run),
                analyzer = ImageAnalysis.Analyzer { image -> image.close() },
            )

        try {
            assertSame(viewPort, bundle.group.viewPort)
            assertEquals(2, bundle.group.useCases.size)
            assertSame(bundle.preview, bundle.group.useCases[0])
            assertSame(bundle.analysis, bundle.group.useCases[1])
            assertEquals(Surface.ROTATION_90, bundle.preview.targetRotation)
            assertEquals(Surface.ROTATION_90, bundle.analysis.targetRotation)
            assertEquals(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST, bundle.analysis.backpressureStrategy)
            assertEquals(1, bundle.analysis.imageQueueDepth)
            assertEquals(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888, bundle.analysis.outputImageFormat)
            assertFalse(bundle.analysis.isOutputImageRotationEnabled)

            val selector = requireNotNull(bundle.analysis.resolutionSelector)
            val strategy = requireNotNull(selector.resolutionStrategy)
            assertEquals(Size(640, 480), strategy.boundSize)
            assertEquals(
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                strategy.fallbackRule,
            )
            assertSame(CameraResolutionPolicy, selector.resolutionFilter)
            assertTrue(bundle.group.effects.isEmpty())
        } finally {
            bundle.analysis.clearAnalyzer()
        }
    }

    @Test
    fun sensorToViewMatrixRejectsZeroAxisCollapseAndNumericallyNearSingularInputs() {
        val invalidMatrices =
            listOf(
                FloatArray(9),
                floatArrayOf(
                    1f, 0f, 0f,
                    0f, 0f, 0f,
                    0f, 0f, 1f,
                ),
                floatArrayOf(
                    1f, 0f, 0f,
                    0f, 1.0e-20f, 0f,
                    0f, 0f, 1f,
                ),
            )

        invalidMatrices.forEach { values ->
            assertThrows(IllegalArgumentException::class.java) {
                ExactMatrix3x3.fromValues(values)
            }
        }
    }

    @Test
    fun sensorToViewMatrixDefensivelyCopiesExactRawFloatBits() {
        val values =
            floatArrayOf(
                1f, -0.0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
        val captured = ExactMatrix3x3.fromValues(values)

        values[0] = 2f
        values[1] = 0.0f

        assertEquals(
            ExactMatrix3x3.fromValues(
                floatArrayOf(
                    1f, -0.0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f,
                ),
            ),
            captured,
        )
        assertFalse(
            captured ==
                ExactMatrix3x3.fromValues(
                    floatArrayOf(
                        1f, 0.0f, 0f,
                        0f, 1f, 0f,
                        0f, 0f, 1f,
                    ),
                ),
        )
    }
}
