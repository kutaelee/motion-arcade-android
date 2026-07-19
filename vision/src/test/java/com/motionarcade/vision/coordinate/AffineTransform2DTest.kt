package com.motionarcade.vision.coordinate

import org.junit.Assert.assertNull
import org.junit.Test

class AffineTransform2DTest {
    @Test
    fun zeroAndNonFiniteDeterminantsHaveNoInverse() {
        val zeroDeterminant = AffineTransform2D(1.0, 2.0, 0.0, 2.0, 4.0, 0.0)
        val nonFiniteDeterminant = AffineTransform2D(Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0)

        assertNull(zeroDeterminant.inverseOrNull())
        assertNull(nonFiniteDeterminant.inverseOrNull())
    }
}
