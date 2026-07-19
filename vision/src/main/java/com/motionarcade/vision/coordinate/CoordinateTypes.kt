package com.motionarcade.vision.coordinate

import com.motionarcade.core.contract.LensFacing
import java.util.EnumMap

data class Point2D(
    val x: Double,
    val y: Double,
)

data class Size2D(
    val width: Double,
    val height: Double,
)

data class Rect2D(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double
        get() = right - left

    val height: Double
        get() = bottom - top
}

enum class QuarterTurn(val degrees: Int) {
    DEG_0(0),
    DEG_90(90),
    DEG_180(180),
    DEG_270(270),
    ;

    companion object {
        fun fromDegrees(degrees: Int): QuarterTurn? = entries.firstOrNull { it.degrees == degrees }
    }
}

enum class PreviewScaleMode {
    FIT_CENTER,
    FILL_CENTER,
}

enum class PreviewMappingBasis {
    SHARED_VIEWPORT,
    EXPLICIT_CROP_RECTS,
    UNRESOLVED,
}

/**
 * Camera metadata needed to map one ephemeral analysis frame.
 *
 * Crop rectangles are expressed in the original buffer coordinate space. Nullable
 * fields make missing CameraX metadata an explicit, fail-closed outcome instead of
 * encouraging a guessed default.
 */
data class CameraCoordinateMetadata(
    val bufferSize: Size2D?,
    val analysisCropInBuffer: Rect2D?,
    val analysisRotation: QuarterTurn?,
    val previewCropInBuffer: Rect2D?,
    val previewMappingBasis: PreviewMappingBasis?,
    val displayRotation: QuarterTurn?,
    val displaySize: Size2D?,
    val lensFacing: LensFacing?,
    val previewScaleMode: PreviewScaleMode?,
)

enum class TransformDisposition {
    DROP_FRAME,
    PAUSE_INPUT,
}

enum class TransformIssue {
    MISSING_BUFFER_SIZE,
    MISSING_ANALYSIS_CROP,
    MISSING_ANALYSIS_ROTATION,
    MISSING_PREVIEW_CROP,
    MISSING_PREVIEW_MAPPING_BASIS,
    UNRESOLVED_PREVIEW_MAPPING,
    INCONSISTENT_SHARED_VIEWPORT,
    MISSING_DISPLAY_ROTATION,
    MISSING_DISPLAY_SIZE,
    MISSING_LENS_FACING,
    MISSING_PREVIEW_SCALE_MODE,
    NON_FINITE_METADATA,
    INVALID_BUFFER_SIZE,
    INVALID_DISPLAY_SIZE,
    INVALID_ANALYSIS_CROP,
    INVALID_PREVIEW_CROP,
    SINGULAR_TRANSFORM,
    NON_FINITE_POINT,
}

data class TransformDiagnostic(
    val issue: TransformIssue,
    val disposition: TransformDisposition,
)

fun interface TransformDiagnosticSink {
    fun record(diagnostic: TransformDiagnostic)

    companion object {
        val NONE = TransformDiagnosticSink { }
    }
}

/** A privacy-safe counter: it retains categories only, never frame or landmark data. */
class TransformDiagnosticCounter : TransformDiagnosticSink {
    private val counts = EnumMap<TransformIssue, Long>(TransformIssue::class.java)

    @Synchronized
    override fun record(diagnostic: TransformDiagnostic) {
        counts[diagnostic.issue] = (counts[diagnostic.issue] ?: 0L) + 1L
    }

    @Synchronized
    fun count(issue: TransformIssue): Long = counts[issue] ?: 0L

    @Synchronized
    fun snapshot(): Map<TransformIssue, Long> = counts.toMap()
}

sealed interface CoordinateSetupResult {
    data class Ready(val mapper: CameraCoordinateMapper) : CoordinateSetupResult

    data class DropFrame(val diagnostic: TransformDiagnostic) : CoordinateSetupResult

    data class PauseInput(val diagnostic: TransformDiagnostic) : CoordinateSetupResult
}

sealed interface PointMappingResult {
    data class Mapped(val point: Point2D) : PointMappingResult

    data class Dropped(val diagnostic: TransformDiagnostic) : PointMappingResult
}
