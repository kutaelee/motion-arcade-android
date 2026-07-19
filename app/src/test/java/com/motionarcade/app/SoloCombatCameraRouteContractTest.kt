package com.motionarcade.app

import com.motionarcade.vision.motion.DualPlayerCombatProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SoloCombatCameraRouteContractTest {
    @Test
    fun selectsProfileScopedBoxingAndMonsterConfigs() {
        listOf(DualPlayerCombatProfile.BOXING, DualPlayerCombatProfile.MONSTER).forEach { profile ->
            val config = soloCombatMotionConfig(profile, calibrationRevision = 7)

            assertEquals(profile, config.profile)
            assertEquals(7, config.calibrationRevision)
        }
    }

    @Test
    fun fishingProfileIsRejectedBeforeCameraBinding() {
        assertThrows(IllegalArgumentException::class.java) {
            soloCombatMotionConfig(DualPlayerCombatProfile.FISHING)
        }
    }

    @Test
    fun profileAwareSemanticsDistinguishOpponentFromPartner() {
        assertEquals("boxing", soloCombatProfileLabel(DualPlayerCombatProfile.BOXING))
        assertEquals("monster raid", soloCombatProfileLabel(DualPlayerCombatProfile.MONSTER))
        assertTrue(soloCombatActiveStatusText(DualPlayerCombatProfile.BOXING).contains("AI opponent"))
        assertTrue(soloCombatActiveStatusText(DualPlayerCombatProfile.MONSTER).contains("AI partner"))
    }
}
