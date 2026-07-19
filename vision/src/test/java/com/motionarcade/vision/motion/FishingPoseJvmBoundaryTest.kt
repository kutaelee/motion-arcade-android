package com.motionarcade.vision.pose

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingPoseJvmBoundaryTest {
    @Test
    fun rawFishingPoseTypesArePackagePrivateFinalAndRedacted() {
        listOf(FishingPosePoint::class.java, FishingPoseSample::class.java).forEach { type ->
            assertFalse(Modifier.isPublic(type.modifiers))
            assertTrue(Modifier.isFinal(type.modifiers))
            assertTrue(type.declaredConstructors.all { !Modifier.isPublic(it.modifiers) })
            assertTrue(type.declaredMethods.all { !Modifier.isPublic(it.modifiers) || it.name == "toString" })
        }
        val point = FishingPosePoint(0.1234f, 0.5678f, -0.9f, 0.75f)
        val sample = FishingPoseSample(99L, 1, List(33) { point })

        assertEqualsRedacted(point.toString(), "0.1234", "0.5678", "-0.9")
        assertEqualsRedacted(sample.toString(), "0.1234", "0.5678", "-0.9")
    }

    private fun assertEqualsRedacted(value: String, vararg forbidden: String) {
        assertTrue(value.contains("redacted"))
        forbidden.forEach { coordinate -> assertFalse(value.contains(coordinate)) }
    }
}
