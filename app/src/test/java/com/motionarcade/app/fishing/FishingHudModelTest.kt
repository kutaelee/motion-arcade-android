package com.motionarcade.app.fishing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FishingHudModelTest {
    @Test
    fun acceptsBoundedRenderOnlyState() {
        val model = model(progress = 0.5f, tension = 1f)

        assertEquals(0.5f, model.progress, 0f)
        assertEquals(1f, model.tension, 0f)
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeMeters() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, -0.01f, 1.01f).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                model(progress = invalid, tension = 0f)
            }
            assertThrows(IllegalArgumentException::class.java) {
                model(progress = 0f, tension = invalid)
            }
        }
    }

    private fun model(progress: Float, tension: Float): FishingHudModel = FishingHudModel(
        phaseLabel = "phase",
        instruction = "instruction",
        scoreLabel = "score",
        progressLabel = "progress",
        progressDescription = "progress description",
        progress = progress,
        tensionLabel = "tension",
        tensionDescription = "tension description",
        tension = tension,
        trackingStatusLabel = "tracking",
        actionLabel = "action",
        actionEnabled = true,
        pauseLabel = "pause",
        pauseEnabled = true,
    )
}
