package com.motionarcade.core.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityAvailabilityTest {
    @Test
    fun mlCapabilityRegistryIsExactAndDoesNotFoldDualIncompleteIntoMlTier() {
        assertEquals(
            listOf("A", "B", "C", "UNSUPPORTED"),
            MlCapability.entries.map(MlCapability::name),
        )
    }

    @Test
    fun sliceOneBEffectRegistryIsConservativeOnly() {
        assertEquals(
            listOf("CONSERVATIVE_UNVERIFIED"),
            EffectCapability.entries.map(EffectCapability::name),
        )
    }
}
