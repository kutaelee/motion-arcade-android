package com.motionarcade.vision.coordinate

import com.motionarcade.core.contract.LensFacing
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCoordinateMapperTest {
    @Test
    fun rotationGoldenPointsAreNumericallyFixed() {
        data class Golden(
            val rotation: QuarterTurn,
            val displaySize: Size2D,
            val expectedAnalysis: Point2D,
            val expectedDisplay: Point2D,
        )

        val fixtures =
            listOf(
                Golden(QuarterTurn.DEG_0, Size2D(640.0, 480.0), Point2D(0.25, 0.25), Point2D(160.0, 120.0)),
                Golden(QuarterTurn.DEG_90, Size2D(480.0, 640.0), Point2D(0.75, 0.25), Point2D(360.0, 160.0)),
                Golden(QuarterTurn.DEG_180, Size2D(640.0, 480.0), Point2D(0.75, 0.75), Point2D(480.0, 360.0)),
                Golden(QuarterTurn.DEG_270, Size2D(480.0, 640.0), Point2D(0.25, 0.75), Point2D(120.0, 480.0)),
            )
        val bufferSize = Size2D(640.0, 480.0)
        val fullCrop = Rect2D(0.0, 0.0, 640.0, 480.0)

        fixtures.forEach { fixture ->
            val mapper =
                readyMapper(
                    metadata(
                        bufferSize = bufferSize,
                        crop = fullCrop,
                        rotation = fixture.rotation,
                        displaySize = fixture.displaySize,
                    ),
                )
            val analysis = mapper.bufferToAnalysis(Point2D(160.0, 120.0)).mappedPoint()
            assertPoint(fixture.expectedAnalysis, analysis)
            assertPoint(fixture.expectedDisplay, mapper.analysisToDisplay(analysis).mappedPoint())
        }
    }

    @Test
    fun all96RotationLensAspectScaleCropCombinationsMatchIndependentOracle() {
        val bufferSizes =
            listOf(
                Size2D(640.0, 480.0),
                Size2D(1280.0, 720.0),
                Size2D(720.0, 1280.0),
            )
        val displaySize = Size2D(1080.0, 1920.0)
        var evaluatedCombinations = 0

        bufferSizes.forEach { bufferSize ->
            val crops =
                listOf(
                    Rect2D(0.0, 0.0, bufferSize.width, bufferSize.height),
                    Rect2D(
                        left = bufferSize.width * 0.10,
                        top = bufferSize.height * 0.125,
                        right = bufferSize.width * 0.90,
                        bottom = bufferSize.height * 0.875,
                    ),
                )
            QuarterTurn.entries.forEach { rotation ->
                LensFacing.entries.forEach { lensFacing ->
                    PreviewScaleMode.entries.forEach { scaleMode ->
                        crops.forEach { crop ->
                            val point =
                                Point2D(
                                    x = crop.left + crop.width * 0.23,
                                    y = crop.top + crop.height * 0.61,
                                )
                            val mapper =
                                readyMapper(
                                    metadata(
                                        bufferSize = bufferSize,
                                        crop = crop,
                                        rotation = rotation,
                                        displaySize = displaySize,
                                        lensFacing = lensFacing,
                                        scaleMode = scaleMode,
                                    ),
                                )
                            val expectedAnalysis = oracleNormalized(point, crop, bufferSize, rotation)
                            val actualAnalysis = mapper.bufferToAnalysis(point).mappedPoint()
                            assertPoint(expectedAnalysis, actualAnalysis)

                            val expectedDisplay =
                                oracleDisplay(
                                    point = point,
                                    previewCrop = crop,
                                    bufferSize = bufferSize,
                                    rotation = rotation,
                                    displaySize = displaySize,
                                    lensFacing = lensFacing,
                                    scaleMode = scaleMode,
                                )
                            assertPoint(expectedDisplay, mapper.analysisToDisplay(actualAnalysis).mappedPoint())
                            assertPoint(point, mapper.analysisToBuffer(actualAnalysis).mappedPoint())
                            evaluatedCombinations += 1
                        }
                    }
                }
            }
        }

        assertEquals(96, evaluatedCombinations)
    }

    @Test
    fun frontMirrorChangesDisplayOnlyAndPreservesAnalysisCoordinates() {
        val back = readyMapper(metadata(lensFacing = LensFacing.BACK))
        val front = readyMapper(metadata(lensFacing = LensFacing.FRONT))
        val bufferPoint = Point2D(160.0, 120.0)

        val backAnalysis = back.bufferToAnalysis(bufferPoint).mappedPoint()
        val frontAnalysis = front.bufferToAnalysis(bufferPoint).mappedPoint()
        assertPoint(backAnalysis, frontAnalysis)

        val backDisplay = back.analysisToDisplay(backAnalysis).mappedPoint()
        val frontDisplay = front.analysisToDisplay(frontAnalysis).mappedPoint()
        assertEquals(640.0 - backDisplay.x, frontDisplay.x, EPSILON)
        assertEquals(backDisplay.y, frontDisplay.y, EPSILON)
    }

    @Test
    fun explicitPreviewCropMapsThroughCommonBufferSpace() {
        val bufferSize = Size2D(1000.0, 800.0)
        val setup =
            CameraCoordinateMapper.create(
                CameraCoordinateMetadata(
                    bufferSize = bufferSize,
                    analysisCropInBuffer = Rect2D(0.0, 0.0, 1000.0, 800.0),
                    analysisRotation = QuarterTurn.DEG_0,
                    previewCropInBuffer = Rect2D(100.0, 100.0, 900.0, 700.0),
                    previewMappingBasis = PreviewMappingBasis.EXPLICIT_CROP_RECTS,
                    displayRotation = QuarterTurn.DEG_0,
                    displaySize = Size2D(400.0, 300.0),
                    lensFacing = LensFacing.BACK,
                    previewScaleMode = PreviewScaleMode.FIT_CENTER,
                ),
            )
        val mapper = (setup as CoordinateSetupResult.Ready).mapper
        val analysis = mapper.bufferToAnalysis(Point2D(300.0, 250.0)).mappedPoint()

        assertPoint(Point2D(0.30, 0.3125), analysis)
        assertPoint(Point2D(100.0, 75.0), mapper.analysisToDisplay(analysis).mappedPoint())
    }

    @Test
    fun rotatedOffsetCropHasFixedNumericOracle() {
        val crop = Rect2D(100.0, 100.0, 900.0, 700.0)
        val mapper =
            readyMapper(
                metadata(
                    bufferSize = Size2D(1000.0, 800.0),
                    crop = crop,
                    rotation = QuarterTurn.DEG_90,
                    displaySize = Size2D(300.0, 400.0),
                ),
            )
        val analysis = mapper.bufferToAnalysis(Point2D(300.0, 250.0)).mappedPoint()

        assertPoint(Point2D(0.75, 0.25), analysis)
        assertPoint(Point2D(225.0, 100.0), mapper.analysisToDisplay(analysis).mappedPoint())
    }

    @Test
    fun fitAndFillHaveDifferentFixedLetterboxAndCropOffsets() {
        val topLeft = Point2D(0.0, 0.0)
        val fit =
            readyMapper(
                metadata(
                    displaySize = Size2D(1920.0, 1080.0),
                    scaleMode = PreviewScaleMode.FIT_CENTER,
                ),
            )
        val fill =
            readyMapper(
                metadata(
                    displaySize = Size2D(1920.0, 1080.0),
                    scaleMode = PreviewScaleMode.FILL_CENTER,
                ),
            )

        assertPoint(Point2D(240.0, 0.0), fit.analysisToDisplay(topLeft).mappedPoint())
        assertPoint(Point2D(0.0, -180.0), fill.analysisToDisplay(topLeft).mappedPoint())
        assertPoint(Point2D(1680.0, 1080.0), fit.analysisToDisplay(Point2D(1.0, 1.0)).mappedPoint())
        assertPoint(Point2D(1920.0, 1260.0), fill.analysisToDisplay(Point2D(1.0, 1.0)).mappedPoint())
    }

    @Test
    fun analysisAndDisplayRotationsAreIndependent() {
        val setup =
            CameraCoordinateMapper.create(
                metadata(
                    rotation = QuarterTurn.DEG_0,
                    displayRotation = QuarterTurn.DEG_90,
                    displaySize = Size2D(480.0, 640.0),
                ),
            )
        val mapper = (setup as CoordinateSetupResult.Ready).mapper
        val analysis = mapper.bufferToAnalysis(Point2D(160.0, 120.0)).mappedPoint()

        assertPoint(Point2D(0.25, 0.25), analysis)
        assertPoint(Point2D(360.0, 160.0), mapper.analysisToDisplay(analysis).mappedPoint())
    }

    @Test
    fun invalidAnalysisMetadataDropsFrameAndIncrementsCategoryOnly() {
        val counter = TransformDiagnosticCounter()
        val missingRotation =
            CameraCoordinateMapper.create(
                metadata().copy(analysisRotation = null),
                counter,
            )
        assertTrue(missingRotation is CoordinateSetupResult.DropFrame)
        assertEquals(TransformIssue.MISSING_ANALYSIS_ROTATION, (missingRotation as CoordinateSetupResult.DropFrame).diagnostic.issue)
        assertEquals(1L, counter.count(TransformIssue.MISSING_ANALYSIS_ROTATION))

        val singular =
            CameraCoordinateMapper.create(
                metadata().copy(analysisCropInBuffer = Rect2D(10.0, 0.0, 10.0, 100.0)),
                counter,
            )
        assertTrue(singular is CoordinateSetupResult.DropFrame)
        assertEquals(TransformIssue.SINGULAR_TRANSFORM, (singular as CoordinateSetupResult.DropFrame).diagnostic.issue)
        assertEquals(1L, counter.count(TransformIssue.SINGULAR_TRANSFORM))

        val missingCrop =
            CameraCoordinateMapper.create(
                metadata().copy(analysisCropInBuffer = null),
                counter,
            )
        assertTrue(missingCrop is CoordinateSetupResult.DropFrame)
        assertEquals(TransformIssue.MISSING_ANALYSIS_CROP, (missingCrop as CoordinateSetupResult.DropFrame).diagnostic.issue)
        assertEquals(1L, counter.count(TransformIssue.MISSING_ANALYSIS_CROP))

        val nonFinite =
            CameraCoordinateMapper.create(
                metadata().copy(bufferSize = Size2D(Double.NaN, 480.0)),
                counter,
            )
        assertTrue(nonFinite is CoordinateSetupResult.DropFrame)
        assertEquals(TransformIssue.NON_FINITE_METADATA, (nonFinite as CoordinateSetupResult.DropFrame).diagnostic.issue)
        assertEquals(1L, counter.count(TransformIssue.NON_FINITE_METADATA))
    }

    @Test
    fun invalidFrameMetadataOutranksUnresolvedOrMissingViewportBasis() {
        data class Fixture(
            val name: String,
            val metadata: CameraCoordinateMetadata,
            val expectedIssue: TransformIssue,
        )

        val fixtures =
            listOf(
                Fixture(
                    "singular analysis crop with unresolved mapping",
                    metadata().copy(
                        analysisCropInBuffer = Rect2D(10.0, 0.0, 10.0, 100.0),
                        previewMappingBasis = PreviewMappingBasis.UNRESOLVED,
                    ),
                    TransformIssue.SINGULAR_TRANSFORM,
                ),
                Fixture(
                    "non-finite buffer with unresolved mapping",
                    metadata().copy(
                        bufferSize = Size2D(Double.NaN, 480.0),
                        previewMappingBasis = PreviewMappingBasis.UNRESOLVED,
                    ),
                    TransformIssue.NON_FINITE_METADATA,
                ),
                Fixture(
                    "missing preview crop with unresolved mapping",
                    metadata().copy(
                        previewCropInBuffer = null,
                        previewMappingBasis = PreviewMappingBasis.UNRESOLVED,
                    ),
                    TransformIssue.MISSING_PREVIEW_CROP,
                ),
                Fixture(
                    "invalid preview crop with unresolved mapping",
                    metadata().copy(
                        previewCropInBuffer = Rect2D(-1.0, 0.0, 640.0, 480.0),
                        previewMappingBasis = PreviewMappingBasis.UNRESOLVED,
                    ),
                    TransformIssue.INVALID_PREVIEW_CROP,
                ),
                Fixture(
                    "missing display rotation with unresolved mapping",
                    metadata().copy(
                        displayRotation = null,
                        previewMappingBasis = PreviewMappingBasis.UNRESOLVED,
                    ),
                    TransformIssue.MISSING_DISPLAY_ROTATION,
                ),
                Fixture(
                    "non-finite display size with unresolved mapping",
                    metadata().copy(
                        displaySize = Size2D(640.0, Double.POSITIVE_INFINITY),
                        previewMappingBasis = PreviewMappingBasis.UNRESOLVED,
                    ),
                    TransformIssue.NON_FINITE_METADATA,
                ),
                Fixture(
                    "singular analysis crop with missing mapping basis",
                    metadata().copy(
                        analysisCropInBuffer = Rect2D(10.0, 0.0, 10.0, 100.0),
                        previewMappingBasis = null,
                    ),
                    TransformIssue.SINGULAR_TRANSFORM,
                ),
            )

        fixtures.forEach { fixture ->
            val result = CameraCoordinateMapper.create(fixture.metadata)

            assertTrue("${fixture.name}: expected DropFrame but was $result", result is CoordinateSetupResult.DropFrame)
            assertEquals(
                fixture.name,
                fixture.expectedIssue,
                (result as CoordinateSetupResult.DropFrame).diagnostic.issue,
            )
            assertEquals(TransformDisposition.DROP_FRAME, result.diagnostic.disposition)
        }
    }

    @Test
    fun missingPreviewCropDropsFrameInsteadOfGuessing() {
        val counter = TransformDiagnosticCounter()
        val result =
            CameraCoordinateMapper.create(
                metadata().copy(previewCropInBuffer = null),
                counter,
            )

        assertTrue(result is CoordinateSetupResult.DropFrame)
        val diagnostic = (result as CoordinateSetupResult.DropFrame).diagnostic
        assertEquals(TransformIssue.MISSING_PREVIEW_CROP, diagnostic.issue)
        assertEquals(TransformDisposition.DROP_FRAME, diagnostic.disposition)
        assertEquals(1L, counter.count(TransformIssue.MISSING_PREVIEW_CROP))
    }

    @Test
    fun unresolvedOrInconsistentViewportMappingPausesGameInput() {
        val counter = TransformDiagnosticCounter()
        val unresolved =
            CameraCoordinateMapper.create(
                metadata().copy(previewMappingBasis = PreviewMappingBasis.UNRESOLVED),
                counter,
            )
        assertTrue(unresolved is CoordinateSetupResult.PauseInput)
        assertEquals(
            TransformIssue.UNRESOLVED_PREVIEW_MAPPING,
            (unresolved as CoordinateSetupResult.PauseInput).diagnostic.issue,
        )

        val inconsistent =
            CameraCoordinateMapper.create(
                metadata().copy(
                    previewCropInBuffer = Rect2D(10.0, 10.0, 630.0, 470.0),
                    previewMappingBasis = PreviewMappingBasis.SHARED_VIEWPORT,
                ),
                counter,
            )
        assertTrue(inconsistent is CoordinateSetupResult.PauseInput)
        assertEquals(
            TransformIssue.INCONSISTENT_SHARED_VIEWPORT,
            (inconsistent as CoordinateSetupResult.PauseInput).diagnostic.issue,
        )
        assertEquals(1L, counter.count(TransformIssue.UNRESOLVED_PREVIEW_MAPPING))
        assertEquals(1L, counter.count(TransformIssue.INCONSISTENT_SHARED_VIEWPORT))
    }

    @Test
    fun nonFinitePointIsDroppedAndCountedWithoutCoordinateRetention() {
        val counter = TransformDiagnosticCounter()
        val mapper = readyMapper(metadata(), counter)
        val result = mapper.bufferToAnalysis(Point2D(Double.NaN, 2.0))

        assertTrue(result is PointMappingResult.Dropped)
        assertEquals(TransformIssue.NON_FINITE_POINT, (result as PointMappingResult.Dropped).diagnostic.issue)
        assertEquals(1L, counter.count(TransformIssue.NON_FINITE_POINT))
        assertEquals(mapOf(TransformIssue.NON_FINITE_POINT to 1L), counter.snapshot())
    }

    @Test
    fun unsupportedRotationHasNoGuessedFallback() {
        assertNull(QuarterTurn.fromDegrees(45))
        assertEquals(QuarterTurn.DEG_270, QuarterTurn.fromDegrees(270))
    }

    private fun metadata(
        bufferSize: Size2D = Size2D(640.0, 480.0),
        crop: Rect2D = Rect2D(0.0, 0.0, 640.0, 480.0),
        rotation: QuarterTurn = QuarterTurn.DEG_0,
        displayRotation: QuarterTurn = rotation,
        displaySize: Size2D = Size2D(640.0, 480.0),
        lensFacing: LensFacing = LensFacing.BACK,
        scaleMode: PreviewScaleMode = PreviewScaleMode.FIT_CENTER,
    ): CameraCoordinateMetadata =
        CameraCoordinateMetadata(
            bufferSize = bufferSize,
            analysisCropInBuffer = crop,
            analysisRotation = rotation,
            previewCropInBuffer = crop,
            previewMappingBasis = PreviewMappingBasis.SHARED_VIEWPORT,
            displayRotation = displayRotation,
            displaySize = displaySize,
            lensFacing = lensFacing,
            previewScaleMode = scaleMode,
        )

    private fun readyMapper(
        metadata: CameraCoordinateMetadata,
        sink: TransformDiagnosticSink = TransformDiagnosticSink.NONE,
    ): CameraCoordinateMapper {
        val result = CameraCoordinateMapper.create(metadata, sink)
        assertTrue("Expected ready mapper but was $result", result is CoordinateSetupResult.Ready)
        return (result as CoordinateSetupResult.Ready).mapper
    }

    private fun PointMappingResult.mappedPoint(): Point2D {
        assertTrue("Expected mapped point but was $this", this is PointMappingResult.Mapped)
        return (this as PointMappingResult.Mapped).point
    }

    private fun assertPoint(expected: Point2D, actual: Point2D) {
        assertEquals(expected.x, actual.x, EPSILON)
        assertEquals(expected.y, actual.y, EPSILON)
    }

    private fun oracleNormalized(
        point: Point2D,
        crop: Rect2D,
        bufferSize: Size2D,
        rotation: QuarterTurn,
    ): Point2D {
        val rotatedPoint = oracleRotate(point, bufferSize, rotation)
        val rotatedCrop = oracleRotatedBounds(crop, bufferSize, rotation)
        return Point2D(
            (rotatedPoint.x - rotatedCrop.left) / rotatedCrop.width,
            (rotatedPoint.y - rotatedCrop.top) / rotatedCrop.height,
        )
    }

    private fun oracleDisplay(
        point: Point2D,
        previewCrop: Rect2D,
        bufferSize: Size2D,
        rotation: QuarterTurn,
        displaySize: Size2D,
        lensFacing: LensFacing,
        scaleMode: PreviewScaleMode,
    ): Point2D {
        val normalized = oracleNormalized(point, previewCrop, bufferSize, rotation)
        val rotatedCrop = oracleRotatedBounds(previewCrop, bufferSize, rotation)
        val widthScale = displaySize.width / rotatedCrop.width
        val heightScale = displaySize.height / rotatedCrop.height
        val scale =
            when (scaleMode) {
                PreviewScaleMode.FIT_CENTER -> min(widthScale, heightScale)
                PreviewScaleMode.FILL_CENTER -> max(widthScale, heightScale)
            }
        val renderedWidth = rotatedCrop.width * scale
        val renderedHeight = rotatedCrop.height * scale
        var x = (displaySize.width - renderedWidth) / 2.0 + normalized.x * renderedWidth
        val y = (displaySize.height - renderedHeight) / 2.0 + normalized.y * renderedHeight
        if (lensFacing == LensFacing.FRONT) x = displaySize.width - x
        return Point2D(x, y)
    }

    private fun oracleRotatedBounds(
        rect: Rect2D,
        bufferSize: Size2D,
        rotation: QuarterTurn,
    ): Rect2D {
        val corners =
            listOf(
                Point2D(rect.left, rect.top),
                Point2D(rect.right, rect.top),
                Point2D(rect.left, rect.bottom),
                Point2D(rect.right, rect.bottom),
            ).map { oracleRotate(it, bufferSize, rotation) }
        return Rect2D(
            corners.minOf(Point2D::x),
            corners.minOf(Point2D::y),
            corners.maxOf(Point2D::x),
            corners.maxOf(Point2D::y),
        )
    }

    private fun oracleRotate(
        point: Point2D,
        size: Size2D,
        rotation: QuarterTurn,
    ): Point2D =
        when (rotation) {
            QuarterTurn.DEG_0 -> point
            QuarterTurn.DEG_90 -> Point2D(size.height - point.y, point.x)
            QuarterTurn.DEG_180 -> Point2D(size.width - point.x, size.height - point.y)
            QuarterTurn.DEG_270 -> Point2D(point.y, size.width - point.x)
        }

    companion object {
        private const val EPSILON = 1e-8
    }
}
