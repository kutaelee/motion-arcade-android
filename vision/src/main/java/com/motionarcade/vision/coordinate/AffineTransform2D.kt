package com.motionarcade.vision.coordinate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Immutable affine transform using column-vector composition. */
internal data class AffineTransform2D(
    private val m00: Double,
    private val m01: Double,
    private val m02: Double,
    private val m10: Double,
    private val m11: Double,
    private val m12: Double,
) {
    val determinant: Double
        get() = m00 * m11 - m01 * m10

    fun isFinite(): Boolean =
        m00.isFinite() &&
            m01.isFinite() &&
            m02.isFinite() &&
            m10.isFinite() &&
            m11.isFinite() &&
            m12.isFinite()

    fun isInvertible(): Boolean = isFinite() && determinant.isFinite() && abs(determinant) > EPSILON

    fun map(point: Point2D): Point2D =
        Point2D(
            x = m00 * point.x + m01 * point.y + m02,
            y = m10 * point.x + m11 * point.y + m12,
        )

    fun mapBounds(rect: Rect2D): Rect2D {
        val corners =
            listOf(
                map(Point2D(rect.left, rect.top)),
                map(Point2D(rect.right, rect.top)),
                map(Point2D(rect.left, rect.bottom)),
                map(Point2D(rect.right, rect.bottom)),
            )
        return Rect2D(
            left = corners.minOf(Point2D::x),
            top = corners.minOf(Point2D::y),
            right = corners.maxOf(Point2D::x),
            bottom = corners.maxOf(Point2D::y),
        )
    }

    /** Returns a transform that applies this transform and then [next]. */
    fun then(next: AffineTransform2D): AffineTransform2D =
        AffineTransform2D(
            m00 = next.m00 * m00 + next.m01 * m10,
            m01 = next.m00 * m01 + next.m01 * m11,
            m02 = next.m00 * m02 + next.m01 * m12 + next.m02,
            m10 = next.m10 * m00 + next.m11 * m10,
            m11 = next.m10 * m01 + next.m11 * m11,
            m12 = next.m10 * m02 + next.m11 * m12 + next.m12,
        )

    fun inverseOrNull(): AffineTransform2D? {
        if (!isInvertible()) return null
        val inverseDeterminant = 1.0 / determinant
        return AffineTransform2D(
            m00 = m11 * inverseDeterminant,
            m01 = -m01 * inverseDeterminant,
            m02 = (m01 * m12 - m11 * m02) * inverseDeterminant,
            m10 = -m10 * inverseDeterminant,
            m11 = m00 * inverseDeterminant,
            m12 = (m10 * m02 - m00 * m12) * inverseDeterminant,
        ).takeIf(AffineTransform2D::isFinite)
    }

    companion object {
        private const val EPSILON = 1e-12

        fun translation(x: Double, y: Double): AffineTransform2D =
            AffineTransform2D(1.0, 0.0, x, 0.0, 1.0, y)

        fun scale(x: Double, y: Double): AffineTransform2D =
            AffineTransform2D(x, 0.0, 0.0, 0.0, y, 0.0)

        fun horizontalMirror(displayWidth: Double): AffineTransform2D =
            AffineTransform2D(-1.0, 0.0, displayWidth, 0.0, 1.0, 0.0)

        fun quarterTurn(rotation: QuarterTurn, sourceSize: Size2D): AffineTransform2D =
            when (rotation) {
                QuarterTurn.DEG_0 -> AffineTransform2D(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
                QuarterTurn.DEG_90 ->
                    AffineTransform2D(
                        0.0,
                        -1.0,
                        sourceSize.height,
                        1.0,
                        0.0,
                        0.0,
                    )
                QuarterTurn.DEG_180 ->
                    AffineTransform2D(
                        -1.0,
                        0.0,
                        sourceSize.width,
                        0.0,
                        -1.0,
                        sourceSize.height,
                    )
                QuarterTurn.DEG_270 ->
                    AffineTransform2D(
                        0.0,
                        1.0,
                        0.0,
                        -1.0,
                        0.0,
                        sourceSize.width,
                    )
            }

        fun cropNormalizer(rotatedCrop: Rect2D): AffineTransform2D =
            translation(-rotatedCrop.left, -rotatedCrop.top)
                .then(scale(1.0 / rotatedCrop.width, 1.0 / rotatedCrop.height))

        fun normalizedContentToDisplay(
            contentSize: Size2D,
            displaySize: Size2D,
            scaleMode: PreviewScaleMode,
        ): AffineTransform2D {
            val widthScale = displaySize.width / contentSize.width
            val heightScale = displaySize.height / contentSize.height
            val uniformScale =
                when (scaleMode) {
                    PreviewScaleMode.FIT_CENTER -> min(widthScale, heightScale)
                    PreviewScaleMode.FILL_CENTER -> max(widthScale, heightScale)
                }
            val renderedWidth = contentSize.width * uniformScale
            val renderedHeight = contentSize.height * uniformScale
            val offsetX = (displaySize.width - renderedWidth) / 2.0
            val offsetY = (displaySize.height - renderedHeight) / 2.0
            return scale(renderedWidth, renderedHeight).then(translation(offsetX, offsetY))
        }
    }
}
