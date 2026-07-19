package com.motionarcade.app

import com.motionarcade.core.contract.PauseReason
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloCombatGameplayAdmissionGateTest {
    @Test
    fun opensOnlyForOnePoseAndCoherentCurrentGenerationObservation() {
        val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
        gate.onBindingStarted(7L)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)

        assertTrue(gate.onInference(activeInference(7L, poseCount = 1, callbackCount = 3L)))
        assertTrue(gate.onSoloCombatMotion(7L, revision = 2L, usableForSolo = true))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(gate.onSoloCombatMotion(7L, revision = 3L, usableForSolo = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    @Test
    fun zeroOrTwoPosesCannotOpenSoloGameplay() {
        listOf(0, 2).forEach { poseCount ->
            val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
            gate.onBindingStarted(8L + poseCount)
            gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)
            assertTrue(gate.onInference(activeInference(8L + poseCount, poseCount, callbackCount = 1L)))
            assertTrue(gate.onSoloCombatMotion(8L + poseCount, 1L, usableForSolo = true))
            assertFalse(gate.isGameplayInputAllowed())
        }
    }

    @Test
    fun cameraGapDiscardsProofAndRequiresFreshCoherentCallbacks() {
        val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
        open(gate, generation = 9L, revision = 1L)

        gate.onPreviewStatus(FrontCameraPreviewStatus.IDLE)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(gate.onInference(activeInference(9L, callbackCount = 2L)))
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(gate.onSoloCombatMotion(9L, revision = 2L, usableForSolo = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    @Test
    fun callbackErrorFailsClosedNotifiesOnceAndDoesNotReuseOldFrame() {
        val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        gate.registerSafetyStop { throw IllegalStateException("presentation failed") }
        open(gate, generation = 10L, revision = 1L)

        assertTrue(gate.onCallbackError(10L))
        assertTrue(gate.onCallbackError(10L))

        assertFalse(gate.isGameplayInputAllowed())
        assertEquals(listOf(PauseReason.POSE_LOST), stops)
        assertNull(gate.runWhenAllowed { "must not run" })
        assertTrue(gate.onInference(activeInference(10L, callbackCount = 2L)))
        assertFalse(gate.isGameplayInputAllowed())
    }

    @Test
    fun staleGenerationCallbackErrorCannotCloseCurrentBinding() {
        val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
        open(gate, generation = 14L, revision = 1L)

        assertFalse(gate.onCallbackError(13L))
        assertTrue(gate.isGameplayInputAllowed())
    }

    @Test
    fun staleGenerationDeliveriesCannotOpenOrEnterCurrentBinding() {
        val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
        open(gate, generation = 11L, revision = 1L)
        gate.onBindingStarted(12L)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)

        assertFalse(gate.onInference(activeInference(11L, callbackCount = 2L)))
        assertFalse(gate.onSoloCombatMotion(11L, revision = 2L, usableForSolo = true))
        assertNull(gate.runWhenAllowed(11L) { "stale" })
        assertFalse(gate.isGameplayInputAllowed())
    }

    @Test
    fun unusableFrameHardStopsAndFreshInferencePlusFrameCanRearm() {
        val gate = SoloCombatGameplayAdmissionGate(configAvailable = true)
        val stops = mutableListOf<PauseReason>()
        gate.registerSafetyStop(stops::add)
        open(gate, generation = 13L, revision = 1L)

        assertTrue(gate.onSoloCombatMotion(13L, revision = 2L, usableForSolo = false))
        assertEquals(listOf(PauseReason.POSE_LOST), stops)
        assertFalse(gate.isGameplayInputAllowed())
        assertTrue(gate.onInference(activeInference(13L, callbackCount = 2L)))
        assertTrue(gate.onSoloCombatMotion(13L, revision = 2L, usableForSolo = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    private fun open(
        gate: SoloCombatGameplayAdmissionGate,
        generation: Long,
        revision: Long,
    ) {
        gate.onBindingStarted(generation)
        gate.onPreviewStatus(FrontCameraPreviewStatus.ACTIVE)
        assertTrue(gate.onInference(activeInference(generation, callbackCount = revision)))
        assertTrue(gate.onSoloCombatMotion(generation, revision, usableForSolo = true))
        assertTrue(gate.isGameplayInputAllowed())
    }

    private fun activeInference(
        generation: Long,
        poseCount: Int = 1,
        callbackCount: Long,
    ): LivePoseInferenceSnapshot =
        LivePoseInferenceSnapshot(
            sessionGeneration = generation,
            revision = callbackCount,
            phase = LivePoseInferencePhase.ACTIVE,
            poseCount = poseCount,
            callbackCount = callbackCount,
            resultTimestampMs = 1L,
        )
}
