package com.motionarcade.vision.coordinate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayErrorMetricTest {
    @Test
    fun reportsRmsMaximumAndTargetVerdictAgainstPreviewShortEdge() {
        val result =
            OverlayErrorMetric.measure(
                samples =
                    listOf(
                        sample("left_shoulder", 0.0),
                        sample("right_shoulder", 30.0),
                    ),
                previewShortEdgePx = 1000.0,
            )

        val report = (result as OverlayMeasurementResult.Measured).report
        assertEquals(2, report.sampleCount)
        assertEquals(30.0, report.maximumErrorPx, EPSILON)
        assertEquals(0.03, report.maximumErrorRatio, EPSILON)
        assertEquals(30.0 / kotlin.math.sqrt(2.0), report.rootMeanSquareErrorPx, EPSILON)
        assertEquals(OverlayAccuracyVerdict.TARGET, report.verdict)
    }

    @Test
    fun fixedThresholdsDistinguishReviewReleaseBlockAndDevelopmentStop() {
        assertEquals(OverlayAccuracyVerdict.REVIEW, verdictForError(40.0))
        assertEquals(OverlayAccuracyVerdict.REVIEW, verdictForError(50.0))
        assertEquals(OverlayAccuracyVerdict.RELEASE_BLOCK, verdictForError(60.0))
        assertEquals(OverlayAccuracyVerdict.DEVELOPMENT_STOP, verdictForError(100.0))
    }

    @Test
    fun emptyNonFiniteOrInvalidPreviewMeasurementsFailClosed() {
        assertTrue(OverlayErrorMetric.measure(emptyList(), 1000.0) is OverlayMeasurementResult.Invalid)
        assertTrue(
            OverlayErrorMetric.measure(listOf(sample("wrist", Double.NaN)), 1000.0) is
                OverlayMeasurementResult.Invalid,
        )
        assertTrue(
            OverlayErrorMetric.measure(listOf(sample("wrist", 0.0)), 0.0) is
                OverlayMeasurementResult.Invalid,
        )
    }

    private fun verdictForError(errorPx: Double): OverlayAccuracyVerdict {
        val result = OverlayErrorMetric.measure(listOf(sample("hip", errorPx)), 1000.0)
        return (result as OverlayMeasurementResult.Measured).report.verdict
    }

    private fun sample(landmarkId: String, errorPx: Double): OverlaySample =
        OverlaySample(
            landmarkId = landmarkId,
            canonicalPoint = Point2D(0.0, 0.0),
            expectedDisplayPoint = Point2D(100.0, 200.0),
            observedDisplayPoint = Point2D(100.0 + errorPx, 200.0),
        )

    companion object {
        private const val EPSILON = 1e-10
    }
}
