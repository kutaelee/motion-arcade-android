package com.motionarcade.vision.motion

import com.motionarcade.core.motion.MotionFixtureGovernance
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionFixtureRegistryDriftTest {
    @Test
    fun `fixture gate exactly covers every motion type emitted by product profiles`() {
        val productProfileTypes = DualPlayerCombatProfile.entries
            .flatMapTo(linkedSetOf()) { profile -> profile.requiredMotionTypes }

        assertEquals(productProfileTypes, MotionFixtureGovernance.requiredMotionTypes)
    }
}
