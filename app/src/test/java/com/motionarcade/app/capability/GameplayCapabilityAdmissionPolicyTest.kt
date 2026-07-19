package com.motionarcade.app.capability

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityModeOutcome
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayCapabilityAdmissionPolicyTest {
    @Test
    fun onlyTerminalVerifiedSoloSupportAdmitsSolo() {
        val summary = CapabilitySummaryUi().withVerified(measurement(GameMode.SOLO, CapabilityModeOutcome.SOLO_SUPPORT))
        val terminal = result(summary)

        assertTrue(GameplayCapabilityAdmissionPolicy.isAdmitted(terminal, GameMode.SOLO))
        assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(ProbeUiState.Ready(summary), GameMode.SOLO))
        assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(null, GameMode.SOLO))
    }

    @Test
    fun belowFloorAndUnsupportedSoloRemainClosed() {
        listOf(
            CapabilityModeOutcome.SOLO_BELOW_FLOOR,
            CapabilityModeOutcome.SCOPED_UNSUPPORTED,
        ).forEach { outcome ->
            val summary = CapabilitySummaryUi().withVerified(measurement(GameMode.SOLO, outcome))
            assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(result(summary), GameMode.SOLO))
            assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(result(summary), GameMode.DUAL))
        }
    }

    @Test
    fun dualRequiresPairedSoloSupportAndFullOrConditionalDual() {
        listOf(CapabilityModeOutcome.DUAL_FULL, CapabilityModeOutcome.DUAL_CONDITIONAL).forEach { outcome ->
            val summary = supportedSolo().withVerified(measurement(GameMode.DUAL, outcome))
            assertTrue(GameplayCapabilityAdmissionPolicy.isAdmitted(result(summary), GameMode.DUAL))
        }

        val unpaired = CapabilitySummaryUi().withVerified(measurement(GameMode.DUAL, CapabilityModeOutcome.DUAL_FULL))
        val belowFloor = supportedSolo().withVerified(
            measurement(GameMode.DUAL, CapabilityModeOutcome.DUAL_BELOW_FLOOR),
        )
        val unsupported = supportedSolo().withVerified(
            measurement(GameMode.DUAL, CapabilityModeOutcome.SCOPED_UNSUPPORTED),
        )
        assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(result(unpaired), GameMode.DUAL))
        assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(result(belowFloor), GameMode.DUAL))
        assertFalse(GameplayCapabilityAdmissionPolicy.isAdmitted(result(unsupported), GameMode.DUAL))
    }

    private fun supportedSolo(): CapabilitySummaryUi = CapabilitySummaryUi().withVerified(
        measurement(GameMode.SOLO, CapabilityModeOutcome.SOLO_SUPPORT),
    )

    private fun result(summary: CapabilitySummaryUi): ProbeUiState.Result = ProbeUiState.Result(
        summary = summary,
        lastAttempt = ProbeAttemptCursor(
            startRequestId = ProbeStartRequestId(1L),
            identity = ProbeAttemptIdentity(sessionId = 1L, attemptId = 1L),
            mode = if (summary.dual is ModeCapabilityUi.Verified) GameMode.DUAL else GameMode.SOLO,
            revision = 1L,
        ),
        dualOptInAvailable = false,
    )

    private fun measurement(mode: GameMode, outcome: CapabilityModeOutcome): ModeMeasurementSummary {
        val unsupported = outcome == CapabilityModeOutcome.SCOPED_UNSUPPORTED
        return ModeMeasurementSummary(
            mode = mode,
            outcome = outcome,
            selectedDelegate = if (unsupported) ProbeDelegate.NONE else ProbeDelegate.CPU,
            representativeCompletionCount = if (unsupported) 0L else 1L,
            durationNs = if (unsupported) 0L else ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
            representativeInferenceP95Ns = if (unsupported) null else 1L,
            maximumRepresentativeFrameAgeNs = if (unsupported) null else 1L,
        )
    }
}
