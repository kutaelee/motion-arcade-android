package com.motionarcade.app.capability

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityModeOutcome

/**
 * Fail-closed projection from an authoritative terminal probe UI state to gameplay admission.
 *
 * This policy does not read persisted records or derive authority. Its caller must supply the
 * current service-issued [ProbeUiState.Result]; every other state is deliberately unplayable.
 */
object GameplayCapabilityAdmissionPolicy {
    fun isAdmitted(state: ProbeUiState?, mode: GameMode): Boolean {
        val result = state as? ProbeUiState.Result ?: return false
        val solo = result.summary.solo as? ModeCapabilityUi.Verified ?: return false
        if (solo.measurement.outcome != CapabilityModeOutcome.SOLO_SUPPORT) return false
        if (mode == GameMode.SOLO) return true

        val dual = result.summary.dual as? ModeCapabilityUi.Verified ?: return false
        return dual.measurement.outcome == CapabilityModeOutcome.DUAL_FULL ||
            dual.measurement.outcome == CapabilityModeOutcome.DUAL_CONDITIONAL
    }
}
