package com.motionarcade.vision.capability.domain

import com.motionarcade.core.capability.EffectCapability
import com.motionarcade.core.capability.MlCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityOutcomePolicyTest {
    @Test
    fun modeOutcomeWireValuesAreExactAndContiguous() {
        assertEquals(
            listOf(
                CapabilityModeOutcome.SOLO_SUPPORT to 0,
                CapabilityModeOutcome.SOLO_BELOW_FLOOR to 1,
                CapabilityModeOutcome.DUAL_FULL to 2,
                CapabilityModeOutcome.DUAL_CONDITIONAL to 3,
                CapabilityModeOutcome.DUAL_BELOW_FLOOR to 4,
                CapabilityModeOutcome.SCOPED_UNSUPPORTED to 5,
            ),
            CapabilityModeOutcome.entries.map { it to it.wireValue },
        )
    }

    @Test
    fun exactCombinedAThenBThenCPrecedenceIsDerivedFromPairedModes() {
        assertDerived(
            dual = persisted(CapabilityModeOutcome.DUAL_FULL),
            expectedOutcome = DerivedMlCapability.A,
            expectedPlatform = MlCapability.A,
        )
        assertDerived(
            dual = persisted(CapabilityModeOutcome.DUAL_CONDITIONAL),
            expectedOutcome = DerivedMlCapability.B,
            expectedPlatform = MlCapability.B,
        )
        assertDerived(
            dual = persisted(CapabilityModeOutcome.DUAL_BELOW_FLOOR),
            expectedOutcome = DerivedMlCapability.C,
            expectedPlatform = MlCapability.C,
        )
        assertDerived(
            dual = persisted(CapabilityModeOutcome.SCOPED_UNSUPPORTED),
            expectedOutcome = DerivedMlCapability.C,
            expectedPlatform = MlCapability.C,
        )
        assertDerived(
            dual = ModeCapabilityEvidence.Unrequested,
            expectedOutcome = DerivedMlCapability.C,
            expectedPlatform = MlCapability.C,
        )
    }

    @Test
    fun selectedSoloBelowFloorOrScopedUnsupportedBlocksGames() {
        listOf(
            CapabilityModeOutcome.SOLO_BELOW_FLOOR,
            CapabilityModeOutcome.SCOPED_UNSUPPORTED,
        ).forEach { soloOutcome ->
            val capability = derived(
                solo = persisted(soloOutcome),
                dual = ModeCapabilityEvidence.Unrequested,
            )
            assertEquals(DerivedMlCapability.UNSUPPORTED, capability.mlOutcome)
            assertEquals(MlCapability.UNSUPPORTED, capability.mlCapability)
            assertEquals(EffectCapability.CONSERVATIVE_UNVERIFIED, capability.effectCapability)
        }
    }

    @Test
    fun requestedDualIncompleteIsNotCAndDoesNotInventPlatformEnum() {
        val capability = derived(
            solo = persisted(CapabilityModeOutcome.SOLO_SUPPORT),
            dual = ModeCapabilityEvidence.Incomplete,
        )

        assertEquals(DerivedMlCapability.SOLO_VALID_DUAL_INCOMPLETE, capability.mlOutcome)
        assertNull(capability.mlCapability)
        assertEquals(EffectCapability.CONSERVATIVE_UNVERIFIED, capability.effectCapability)
    }

    @Test
    fun missingSoloAuthorityStaysIncompleteRatherThanUnsupported() {
        val result = valid(
            CapabilityOutcomePolicy.derive(
                solo = ModeCapabilityEvidence.Incomplete,
                dual = ModeCapabilityEvidence.Unrequested,
            ),
        )

        assertEquals(CapabilityDerivation.ProbeIncomplete, result)
    }

    @Test
    fun modeScopeMismatchesAndUnrequestedSoloFailClosed() {
        assertTrue(
            CapabilityOutcomePolicy.derive(
                solo = persisted(CapabilityModeOutcome.DUAL_FULL),
                dual = ModeCapabilityEvidence.Unrequested,
            ) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            CapabilityOutcomePolicy.derive(
                solo = persisted(CapabilityModeOutcome.SOLO_SUPPORT),
                dual = persisted(CapabilityModeOutcome.SOLO_SUPPORT),
            ) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            CapabilityOutcomePolicy.derive(
                solo = ModeCapabilityEvidence.Unrequested,
                dual = ModeCapabilityEvidence.Unrequested,
            ) is CapabilityDomainResult.Invalid,
        )
    }

    @Test
    fun effectCapabilityIsConservativeForEveryDerivedMlOutcome() {
        DerivedMlCapability.entries.forEach { outcome ->
            assertEquals(
                EffectCapability.CONSERVATIVE_UNVERIFIED,
                DerivedCapability(outcome).effectCapability,
            )
        }
    }

    private fun assertDerived(
        dual: ModeCapabilityEvidence,
        expectedOutcome: DerivedMlCapability,
        expectedPlatform: MlCapability,
    ) {
        val capability = derived(
            solo = persisted(CapabilityModeOutcome.SOLO_SUPPORT),
            dual = dual,
        )
        assertEquals(expectedOutcome, capability.mlOutcome)
        assertEquals(expectedPlatform, capability.mlCapability)
        assertEquals(EffectCapability.CONSERVATIVE_UNVERIFIED, capability.effectCapability)
    }

    private fun persisted(outcome: CapabilityModeOutcome): ModeCapabilityEvidence =
        ModeCapabilityEvidence.Persisted(outcome)

    private fun derived(
        solo: ModeCapabilityEvidence,
        dual: ModeCapabilityEvidence,
    ): DerivedCapability {
        val result = valid(CapabilityOutcomePolicy.derive(solo, dual))
        return (result as CapabilityDerivation.Derived).capability
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }
}
