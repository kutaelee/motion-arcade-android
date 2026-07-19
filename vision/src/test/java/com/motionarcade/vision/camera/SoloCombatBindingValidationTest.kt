package com.motionarcade.vision.camera

import com.motionarcade.vision.motion.DualPlayerCombatProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloCombatBindingValidationTest {
    @Test
    fun onlyBoxingAndMonsterProfilesCanEnterSoloCombatBinding() {
        assertTrue(isSoloCombatBindingProfileSupported(DualPlayerCombatProfile.BOXING))
        assertTrue(isSoloCombatBindingProfileSupported(DualPlayerCombatProfile.MONSTER))
        assertFalse(isSoloCombatBindingProfileSupported(DualPlayerCombatProfile.FISHING))
    }
}
