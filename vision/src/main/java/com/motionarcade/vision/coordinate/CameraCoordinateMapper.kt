package com.motionarcade.vision.coordinate

import com.motionarcade.core.contract.LensFacing

/**
 * Maps buffer observations into analysis and preview-display spaces.
 *
 * Lens facing is intentionally absent from the buffer-to-analysis transform. The
 * front-camera mirror is appended only to the display transform, preserving
 * anatomical LEFT/RIGHT semantics for later pose normalization and game rules.
 */
class CameraCoordinateMapper private constructor(
    private val bufferToAnalysisTransform: AffineTransform2D,
    private val analysisToBufferTransform: AffineTransform2D,
    private val analysisToDisplayTransform: AffineTransform2D,
    private val diagnosticSink: TransformDiagnosticSink,
) {
    fun bufferToAnalysis(point: Point2D): PointMappingResult = map(bufferToAnalysisTransform, point)

    fun analysisToBuffer(point: Point2D): PointMappingResult = map(analysisToBufferTransform, point)

    fun analysisToDisplay(point: Point2D): PointMappingResult = map(analysisToDisplayTransform, point)

    private fun map(transform: AffineTransform2D, point: Point2D): PointMappingResult {
        if (!point.x.isFinite() || !point.y.isFinite()) {
            return droppedPoint(TransformIssue.NON_FINITE_POINT)
        }
        val mapped = transform.map(point)
        if (!mapped.x.isFinite() || !mapped.y.isFinite()) {
            return droppedPoint(TransformIssue.NON_FINITE_POINT)
        }
        return PointMappingResult.Mapped(mapped)
    }

    private fun droppedPoint(issue: TransformIssue): PointMappingResult.Dropped {
        val diagnostic = TransformDiagnostic(issue, TransformDisposition.DROP_FRAME)
        diagnosticSink.record(diagnostic)
        return PointMappingResult.Dropped(diagnostic)
    }

    companion object {
        fun create(
            metadata: CameraCoordinateMetadata,
            diagnosticSink: TransformDiagnosticSink = TransformDiagnosticSink.NONE,
        ): CoordinateSetupResult {
            val bufferSize = metadata.bufferSize ?: return drop(
                TransformIssue.MISSING_BUFFER_SIZE,
                diagnosticSink,
            )
            val analysisCrop = metadata.analysisCropInBuffer ?: return drop(
                TransformIssue.MISSING_ANALYSIS_CROP,
                diagnosticSink,
            )
            val analysisRotation = metadata.analysisRotation ?: return drop(
                TransformIssue.MISSING_ANALYSIS_ROTATION,
                diagnosticSink,
            )
            val previewCrop = metadata.previewCropInBuffer ?: return drop(
                TransformIssue.MISSING_PREVIEW_CROP,
                diagnosticSink,
            )
            val displayRotation = metadata.displayRotation ?: return drop(
                TransformIssue.MISSING_DISPLAY_ROTATION,
                diagnosticSink,
            )

            if (!bufferSize.isFinite()) {
                return drop(TransformIssue.NON_FINITE_METADATA, diagnosticSink)
            }
            if (!bufferSize.isPositive()) {
                return drop(TransformIssue.INVALID_BUFFER_SIZE, diagnosticSink)
            }

            validateCrop(analysisCrop, bufferSize, TransformIssue.INVALID_ANALYSIS_CROP)?.let { issue ->
                return drop(issue, diagnosticSink)
            }
            validateCrop(previewCrop, bufferSize, TransformIssue.INVALID_PREVIEW_CROP)?.let { issue ->
                return drop(issue, diagnosticSink)
            }

            val displaySize = metadata.displaySize
            if (displaySize != null && !displaySize.isFinite()) {
                return drop(TransformIssue.NON_FINITE_METADATA, diagnosticSink)
            }
            if (displaySize != null && !displaySize.isPositive()) {
                return drop(TransformIssue.INVALID_DISPLAY_SIZE, diagnosticSink)
            }

            val previewMappingBasis = metadata.previewMappingBasis ?: return pause(
                TransformIssue.MISSING_PREVIEW_MAPPING_BASIS,
                diagnosticSink,
            )
            if (previewMappingBasis == PreviewMappingBasis.UNRESOLVED) {
                return pause(TransformIssue.UNRESOLVED_PREVIEW_MAPPING, diagnosticSink)
            }
            val resolvedDisplaySize = displaySize ?: return pause(
                TransformIssue.MISSING_DISPLAY_SIZE,
                diagnosticSink,
            )
            val lensFacing = metadata.lensFacing ?: return pause(
                TransformIssue.MISSING_LENS_FACING,
                diagnosticSink,
            )
            val scaleMode = metadata.previewScaleMode ?: return pause(
                TransformIssue.MISSING_PREVIEW_SCALE_MODE,
                diagnosticSink,
            )
            if (
                previewMappingBasis == PreviewMappingBasis.SHARED_VIEWPORT &&
                previewCrop != analysisCrop
            ) {
                return pause(TransformIssue.INCONSISTENT_SHARED_VIEWPORT, diagnosticSink)
            }

            val analysisRotationTransform = AffineTransform2D.quarterTurn(analysisRotation, bufferSize)
            val rotatedAnalysisCrop = analysisRotationTransform.mapBounds(analysisCrop)
            val bufferToAnalysis =
                analysisRotationTransform.then(AffineTransform2D.cropNormalizer(rotatedAnalysisCrop))
            val analysisToBuffer = bufferToAnalysis.inverseOrNull()
                ?: return drop(TransformIssue.SINGULAR_TRANSFORM, diagnosticSink)

            val displayRotationTransform = AffineTransform2D.quarterTurn(displayRotation, bufferSize)
            val rotatedPreviewCrop = displayRotationTransform.mapBounds(previewCrop)
            val bufferToPreviewNormalized =
                displayRotationTransform.then(AffineTransform2D.cropNormalizer(rotatedPreviewCrop))
            val normalizedToDisplay =
                AffineTransform2D.normalizedContentToDisplay(
                    contentSize = Size2D(rotatedPreviewCrop.width, rotatedPreviewCrop.height),
                    displaySize = resolvedDisplaySize,
                    scaleMode = scaleMode,
                )
            var bufferToDisplay = bufferToPreviewNormalized.then(normalizedToDisplay)
            if (lensFacing == LensFacing.FRONT) {
                bufferToDisplay = bufferToDisplay.then(AffineTransform2D.horizontalMirror(resolvedDisplaySize.width))
            }
            val analysisToDisplay = analysisToBuffer.then(bufferToDisplay)

            if (!bufferToAnalysis.isInvertible() || !analysisToDisplay.isFinite()) {
                return drop(TransformIssue.SINGULAR_TRANSFORM, diagnosticSink)
            }
            return CoordinateSetupResult.Ready(
                CameraCoordinateMapper(
                    bufferToAnalysisTransform = bufferToAnalysis,
                    analysisToBufferTransform = analysisToBuffer,
                    analysisToDisplayTransform = analysisToDisplay,
                    diagnosticSink = diagnosticSink,
                ),
            )
        }

        private fun validateCrop(
            crop: Rect2D,
            bufferSize: Size2D,
            invalidIssue: TransformIssue,
        ): TransformIssue? {
            if (!crop.isFinite()) return TransformIssue.NON_FINITE_METADATA
            if (crop.width == 0.0 || crop.height == 0.0) return TransformIssue.SINGULAR_TRANSFORM
            if (
                crop.width < 0.0 ||
                crop.height < 0.0 ||
                crop.left < 0.0 ||
                crop.top < 0.0 ||
                crop.right > bufferSize.width ||
                crop.bottom > bufferSize.height
            ) {
                return invalidIssue
            }
            return null
        }

        private fun drop(
            issue: TransformIssue,
            diagnosticSink: TransformDiagnosticSink,
        ): CoordinateSetupResult.DropFrame {
            val diagnostic = TransformDiagnostic(issue, TransformDisposition.DROP_FRAME)
            diagnosticSink.record(diagnostic)
            return CoordinateSetupResult.DropFrame(diagnostic)
        }

        private fun pause(
            issue: TransformIssue,
            diagnosticSink: TransformDiagnosticSink,
        ): CoordinateSetupResult.PauseInput {
            val diagnostic = TransformDiagnostic(issue, TransformDisposition.PAUSE_INPUT)
            diagnosticSink.record(diagnostic)
            return CoordinateSetupResult.PauseInput(diagnostic)
        }

        private fun Size2D.isFinite(): Boolean = width.isFinite() && height.isFinite()

        private fun Size2D.isPositive(): Boolean = width > 0.0 && height > 0.0

        private fun Rect2D.isFinite(): Boolean =
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
    }
}
