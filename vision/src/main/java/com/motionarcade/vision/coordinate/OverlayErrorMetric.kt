package com.motionarcade.vision.coordinate

import kotlin.math.hypot
import kotlin.math.sqrt

data class OverlaySample(
    val landmarkId: String,
    val canonicalPoint: Point2D,
    val expectedDisplayPoint: Point2D,
    val observedDisplayPoint: Point2D,
)

enum class OverlayAccuracyVerdict {
    TARGET,
    REVIEW,
    RELEASE_BLOCK,
    DEVELOPMENT_STOP,
}

data class OverlayErrorReport(
    val sampleCount: Int,
    val rootMeanSquareErrorPx: Double,
    val maximumErrorPx: Double,
    val rootMeanSquareErrorRatio: Double,
    val maximumErrorRatio: Double,
    val verdict: OverlayAccuracyVerdict,
)

sealed interface OverlayMeasurementResult {
    data class Measured(val report: OverlayErrorReport) : OverlayMeasurementResult

    data class Invalid(val reason: String) : OverlayMeasurementResult
}

object OverlayErrorMetric {
    const val TARGET_RATIO = 0.03
    const val RELEASE_BLOCK_RATIO = 0.05
    const val DEVELOPMENT_STOP_RATIO = 0.10

    fun measure(
        samples: List<OverlaySample>,
        previewShortEdgePx: Double,
    ): OverlayMeasurementResult {
        if (samples.isEmpty()) return OverlayMeasurementResult.Invalid("no samples")
        if (!previewShortEdgePx.isFinite() || previewShortEdgePx <= 0.0) {
            return OverlayMeasurementResult.Invalid("invalid preview short edge")
        }
        if (samples.any { !it.isFinite() || it.landmarkId.isBlank() }) {
            return OverlayMeasurementResult.Invalid("invalid sample")
        }

        val errors =
            samples.map { sample ->
                hypot(
                    sample.observedDisplayPoint.x - sample.expectedDisplayPoint.x,
                    sample.observedDisplayPoint.y - sample.expectedDisplayPoint.y,
                )
            }
        val rms = sqrt(errors.sumOf { it * it } / errors.size)
        val maximum = errors.max()
        val rmsRatio = rms / previewShortEdgePx
        val maximumRatio = maximum / previewShortEdgePx
        val verdict =
            when {
                maximumRatio >= DEVELOPMENT_STOP_RATIO -> OverlayAccuracyVerdict.DEVELOPMENT_STOP
                maximumRatio > RELEASE_BLOCK_RATIO -> OverlayAccuracyVerdict.RELEASE_BLOCK
                maximumRatio <= TARGET_RATIO -> OverlayAccuracyVerdict.TARGET
                else -> OverlayAccuracyVerdict.REVIEW
            }
        return OverlayMeasurementResult.Measured(
            OverlayErrorReport(
                sampleCount = samples.size,
                rootMeanSquareErrorPx = rms,
                maximumErrorPx = maximum,
                rootMeanSquareErrorRatio = rmsRatio,
                maximumErrorRatio = maximumRatio,
                verdict = verdict,
            ),
        )
    }

    private fun OverlaySample.isFinite(): Boolean =
        canonicalPoint.isFinite() && expectedDisplayPoint.isFinite() && observedDisplayPoint.isFinite()

    private fun Point2D.isFinite(): Boolean = x.isFinite() && y.isFinite()
}
