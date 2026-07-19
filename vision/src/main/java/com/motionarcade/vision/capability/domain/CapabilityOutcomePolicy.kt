package com.motionarcade.vision.capability.domain

import com.motionarcade.core.capability.EffectCapability
import com.motionarcade.core.capability.MlCapability

/** Exact CapabilityModeStoreV4 mode-outcome wire registry. */
enum class CapabilityModeOutcome(val wireValue: Int) {
    SOLO_SUPPORT(0),
    SOLO_BELOW_FLOOR(1),
    DUAL_FULL(2),
    DUAL_CONDITIONAL(3),
    DUAL_BELOW_FLOOR(4),
    SCOPED_UNSUPPORTED(5),
}

sealed interface ModeCapabilityEvidence {
    data class Persisted(val outcome: CapabilityModeOutcome) : ModeCapabilityEvidence

    data object Unrequested : ModeCapabilityEvidence

    data object Incomplete : ModeCapabilityEvidence
}

enum class DerivedMlCapability {
    A,
    B,
    C,
    UNSUPPORTED,
    SOLO_VALID_DUAL_INCOMPLETE,
}

/** Game-facing result; effect quality cannot be promoted by an ML measurement. */
class DerivedCapability internal constructor(
    val mlOutcome: DerivedMlCapability,
) {
    val mlCapability: MlCapability?
        get() = when (mlOutcome) {
            DerivedMlCapability.A -> MlCapability.A
            DerivedMlCapability.B -> MlCapability.B
            DerivedMlCapability.C -> MlCapability.C
            DerivedMlCapability.UNSUPPORTED -> MlCapability.UNSUPPORTED
            DerivedMlCapability.SOLO_VALID_DUAL_INCOMPLETE -> null
        }

    val effectCapability: EffectCapability
        get() = EffectCapability.CONSERVATIVE_UNVERIFIED
}

sealed interface CapabilityDerivation {
    data class Derived(val capability: DerivedCapability) : CapabilityDerivation

    /** No stable solo authority exists yet; this state is never persisted as Unsupported. */
    data object ProbeIncomplete : CapabilityDerivation
}

object CapabilityOutcomePolicy {
    fun derive(
        solo: ModeCapabilityEvidence,
        dual: ModeCapabilityEvidence,
    ): CapabilityDomainResult<CapabilityDerivation> {
        validateSolo(solo)?.let { return it }
        validateDual(dual)?.let { return it }

        if (solo == ModeCapabilityEvidence.Incomplete) {
            return CapabilityDomainResult.Valid(CapabilityDerivation.ProbeIncomplete)
        }

        val soloOutcome = (solo as ModeCapabilityEvidence.Persisted).outcome
        if (
            soloOutcome == CapabilityModeOutcome.SOLO_BELOW_FLOOR ||
            soloOutcome == CapabilityModeOutcome.SCOPED_UNSUPPORTED
        ) {
            return derived(DerivedMlCapability.UNSUPPORTED)
        }

        val outcome = when (dual) {
            ModeCapabilityEvidence.Incomplete -> DerivedMlCapability.SOLO_VALID_DUAL_INCOMPLETE
            ModeCapabilityEvidence.Unrequested -> DerivedMlCapability.C
            is ModeCapabilityEvidence.Persisted -> when (dual.outcome) {
                CapabilityModeOutcome.DUAL_FULL -> DerivedMlCapability.A
                CapabilityModeOutcome.DUAL_CONDITIONAL -> DerivedMlCapability.B
                CapabilityModeOutcome.DUAL_BELOW_FLOOR,
                CapabilityModeOutcome.SCOPED_UNSUPPORTED,
                -> DerivedMlCapability.C

                CapabilityModeOutcome.SOLO_SUPPORT,
                CapabilityModeOutcome.SOLO_BELOW_FLOOR,
                -> error("validated dual evidence contained a solo-only outcome")
            }
        }
        return derived(outcome)
    }

    private fun validateSolo(
        evidence: ModeCapabilityEvidence,
    ): CapabilityDomainResult.Invalid? = when (evidence) {
        ModeCapabilityEvidence.Unrequested -> invalidResult(
            path = "$/solo",
            expected = "persisted solo outcome or incomplete",
            actual = "unrequested",
        )

        is ModeCapabilityEvidence.Persisted -> when (evidence.outcome) {
            CapabilityModeOutcome.SOLO_SUPPORT,
            CapabilityModeOutcome.SOLO_BELOW_FLOOR,
            CapabilityModeOutcome.SCOPED_UNSUPPORTED,
            -> null

            else -> invalidResult(
                path = "$/solo/outcome",
                expected = "solo-scoped mode outcome",
                actual = evidence.outcome.name,
            )
        }

        ModeCapabilityEvidence.Incomplete -> null
    }

    private fun validateDual(
        evidence: ModeCapabilityEvidence,
    ): CapabilityDomainResult.Invalid? = when (evidence) {
        ModeCapabilityEvidence.Unrequested,
        ModeCapabilityEvidence.Incomplete,
        -> null

        is ModeCapabilityEvidence.Persisted -> when (evidence.outcome) {
            CapabilityModeOutcome.DUAL_FULL,
            CapabilityModeOutcome.DUAL_CONDITIONAL,
            CapabilityModeOutcome.DUAL_BELOW_FLOOR,
            CapabilityModeOutcome.SCOPED_UNSUPPORTED,
            -> null

            else -> invalidResult(
                path = "$/dual/outcome",
                expected = "dual-scoped mode outcome",
                actual = evidence.outcome.name,
            )
        }
    }

    private fun derived(outcome: DerivedMlCapability): CapabilityDomainResult<CapabilityDerivation> =
        CapabilityDomainResult.Valid(
            CapabilityDerivation.Derived(DerivedCapability(outcome)),
        )

    private fun invalidResult(
        path: String,
        expected: String,
        actual: String,
    ): CapabilityDomainResult.Invalid = CapabilityDomainResult.Invalid(
        listOf(CapabilityDomainViolation.InvalidValue(path, expected, actual)),
    )
}
