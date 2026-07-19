package com.motionarcade.app

import com.motionarcade.core.contract.PauseReason
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.tracking.IdentityPauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPlayerGameplayAdmissionGateTest {
    @Test
    fun opensOnlyAfterCurrentGenerationCameraInferenceAndSafeRoles() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)

        gate.onBindingStarted(7L)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)
        assertTrue(gate.onInference(activeInference(7L)))
        assertFalse(gate.isGameplayInputAllowed())

        assertTrue(gate.onTracking(7L, revision = 1L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(gate.onCombatMotion(7L, revision = 1L, usableForDual = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    @Test
    fun rebindRejectsPriorGenerationInferenceAndTrackingUntilNewSetupCompletes() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        open(gate, 3L)

        gate.onBindingStarted(4L)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)
        assertFalse(gate.isGameplayInputAllowed())
        assertFalse(gate.onInference(activeInference(3L)))
        assertFalse(gate.onTracking(3L, revision = 1L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertFalse(gate.isGameplayInputAllowed())

        assertTrue(gate.onInference(activeInference(4L)))
        assertTrue(gate.onTracking(4L, revision = 1L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertTrue(gate.onCombatMotion(4L, revision = 1L, usableForDual = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    @Test
    fun inferenceFailureSynchronouslyStopsGameAndRejectsGuardedAction() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 8L)

        assertTrue(gate.onInference(terminalInference(8L, LivePoseInferencePhase.FAILED)))
        assertFalse(gate.isGameplayInputAllowed())
        assertEquals(listOf(PauseReason.POSE_LOST), stops)
        assertNull(gate.runWhenAllowed { "must not run" })
    }

    @Test
    fun throwingSafetyCallbackCannotLeaveTheGateOpen() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        gate.registerSafetyStop { throw IllegalStateException("presentation failed") }
        open(gate, 81L)

        assertTrue(gate.onInference(terminalInference(81L, LivePoseInferencePhase.FAILED)))
        assertFalse(gate.isGameplayInputAllowed())
        assertNull(gate.runWhenAllowed { "must not run" })
    }

    @Test
    fun activeInferenceWithFewerThanTwoPosesImmediatelyClosesTheGate() {
        listOf(0, 1).forEach { poseCount ->
            val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
            open(gate, 82L + poseCount)

            assertTrue(gate.onInference(activeInference(82L + poseCount, poseCount)))
            assertFalse(gate.isGameplayInputAllowed())
            assertNull(gate.runWhenAllowed { "must not run" })
        }
    }

    @Test
    fun roleSafetyEdgeSynchronouslyStopsGameBeforeAnotherTickCanEnter() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 9L)

        assertTrue(
            gate.onTracking(
                generation = 9L,
                revision = 1L,
                pauseRequired = true,
                reason = IdentityPauseReason.PLAYER_OVERLAP,
            ),
        )
        assertFalse(gate.isGameplayInputAllowed())
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP), stops)
        assertNull(gate.runWhenAllowed { "must not run" })
    }

    @Test
    fun unusableCombatFrameClosesTheGateAndRejectsThatGenerationAction() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 10L)

        assertTrue(gate.onCombatMotion(10L, revision = 2L, usableForDual = false))
        assertFalse(gate.isGameplayInputAllowed())
        assertEquals(listOf(PauseReason.POSE_LOST), stops)
        assertNull(gate.runWhenAllowed(10L) { "must not run" })
    }

    @Test
    fun staleCombatFrameCannotOpenOrEnterTheCurrentGeneration() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        open(gate, 11L)
        gate.onBindingStarted(12L)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)

        assertFalse(gate.onCombatMotion(11L, revision = 1L, usableForDual = true))
        assertNull(gate.runWhenAllowed(11L) { "stale" })
        assertFalse(gate.isGameplayInputAllowed())
    }

    @Test
    fun newerInferenceCannotReusePriorSafeTrackingAndCombatRevisions() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 13L)

        assertTrue(gate.onInference(activeInference(13L, revision = 4L, callbackCount = 2L)))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(stops.isEmpty())
        assertTrue(gate.onTracking(13L, revision = 2L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(stops.isEmpty())
        assertTrue(gate.onCombatMotion(13L, revision = 2L, usableForDual = true))
        assertTrue(gate.isGameplayInputAllowed())
        assertTrue(stops.isEmpty())
    }

    @Test
    fun hardSafetyLossAfterSoftRevisionFenceStillStopsTheRunningGameExactlyOnce() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 14L)

        assertTrue(gate.onInference(activeInference(14L, revision = 4L, callbackCount = 2L)))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(stops.isEmpty())

        assertTrue(
            gate.onTracking(
                generation = 14L,
                revision = 2L,
                pauseRequired = true,
                reason = IdentityPauseReason.PLAYER_OVERLAP,
            ),
        )
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP), stops)
        assertFalse(gate.isGameplayInputAllowed())

        assertTrue(gate.onCombatMotion(14L, revision = 2L, usableForDual = false))
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP), stops)
    }

    @Test
    fun staggeredSafeRecoveryCannotRearmHardStopUntilTheRevisionIsCoherent() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 15L)

        assertTrue(gate.onInference(activeInference(15L, revision = 4L, callbackCount = 2L)))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(
            gate.onTracking(
                generation = 15L,
                revision = 2L,
                pauseRequired = true,
                reason = IdentityPauseReason.PLAYER_OVERLAP,
            ),
        )
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP), stops)

        assertTrue(gate.onTracking(15L, revision = 2L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(gate.onCombatMotion(15L, revision = 2L, usableForDual = false))
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP), stops)
    }

    @Test
    fun coherentReopenRearmsExactlyOneCallbackForTheNextIndependentHardSafetyLoss() {
        val gate = DualPlayerGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, 16L)

        assertTrue(gate.onInference(activeInference(16L, revision = 4L, callbackCount = 2L)))
        assertTrue(
            gate.onTracking(
                generation = 16L,
                revision = 2L,
                pauseRequired = true,
                reason = IdentityPauseReason.PLAYER_OVERLAP,
            ),
        )
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP), stops)

        assertTrue(gate.onTracking(16L, revision = 2L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertTrue(gate.onCombatMotion(16L, revision = 2L, usableForDual = true))
        assertTrue(gate.isGameplayInputAllowed())

        assertTrue(gate.onCombatMotion(16L, revision = 3L, usableForDual = false))
        assertEquals(listOf(PauseReason.PLAYER_OVERLAP, PauseReason.POSE_LOST), stops)
    }

    private fun open(gate: DualPlayerGameplayAdmissionGate, generation: Long) {
        gate.onBindingStarted(generation)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)
        assertTrue(gate.onInference(activeInference(generation)))
        assertTrue(gate.onTracking(generation, revision = 1L, pauseRequired = false, reason = IdentityPauseReason.NONE))
        assertTrue(gate.onCombatMotion(generation, revision = 1L, usableForDual = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    private fun activeInference(
        generation: Long,
        poseCount: Int = 2,
        revision: Long = 3L,
        callbackCount: Long = 1L,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = generation,
            revision = revision,
            phase = LivePoseInferencePhase.ACTIVE,
            poseCount = poseCount,
            callbackCount = callbackCount,
            resultTimestampMs = 1L,
        )

    private fun terminalInference(
        generation: Long,
        phase: LivePoseInferencePhase,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = generation,
            revision = 2L,
            phase = phase,
            poseCount = null,
            callbackCount = 1L,
            resultTimestampMs = null,
        )
}
