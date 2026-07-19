package com.motionarcade.app.fishing

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.GestureDefinition
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingGameControllerTest {
    @Test
    fun touchFallbackCompletesDeterministicStartToResultFlow() {
        val harness = Harness("touch-complete")
        val emittedSequences = mutableListOf<Long>()

        emittedSequences += harness.touch(MotionType.FISH_CAST).event.sequenceNumber
        harness.tick()
        harness.advanceUntil(FishingPhase.HOOK_WINDOW)
        emittedSequences += harness.touch(MotionType.FISH_HOOK).event.sequenceNumber
        harness.tick()
        while (harness.snapshot.phase != FishingPhase.RESULT) {
            val type = requireNotNull(harness.snapshot.suggestedTouchType())
            emittedSequences += harness.touch(type).event.sequenceNumber
            harness.tick()
        }

        assertEquals(emittedSequences.indices.map(Int::toLong), emittedSequences)
        assertEquals(FishingOutcome.CAUGHT, harness.snapshot.outcome)
        assertTrue(harness.snapshot.score > 0)
        assertNotNull(harness.snapshot.pendingRewardId)
    }

    @Test
    fun pauseFreezesSimulationAndResumeStartsNewTimelineWithoutCatchUp() {
        val harness = Harness("pause")
        harness.touch(MotionType.FISH_CAST)
        harness.tick()
        val beforePause = harness.controller.pause(PauseReason.APP_BACKGROUND)
        val farFuture = harness.nowNs + 500L * FishingGameSession.FIXED_STEP_NS

        val whilePaused = harness.controller.advanceTo(farFuture)

        assertEquals(beforePause.simulationTick, whilePaused.simulationTick)
        assertTrue(harness.controller.resume(farFuture))
        val resumed = harness.controller.snapshot
        assertEquals(beforePause.simulationTick, resumed.simulationTick)
        assertEquals(beforePause.eventTimelineEpoch + 1L, resumed.eventTimelineEpoch)
        assertEquals(farFuture, resumed.eventTimelineBaseNs)
        harness.nowNs = farFuture
        harness.tick()
        assertEquals(beforePause.simulationTick + 1L, harness.snapshot.simulationTick)
    }

    @Test
    fun confirmedMotionFrameUsesMotionSourceAndSameGameQueue() {
        val harness = Harness("motion")
        val armedAtNs = armReady(harness.controller, 0L)
        val first = harness.controller.submitMotionFrame(
            completeFrame(timestampNs = armedAtNs + 1L, castActivation = 0.9f),
        )
        val second = harness.controller.submitMotionFrame(
            completeFrame(timestampNs = armedAtNs + 120_000_001L, castActivation = 0.9f),
        )

        assertTrue(first.isEmpty())
        val accepted = second.single() as FishingControllerInputResult.Accepted
        assertEquals(InputSource.MOTION, accepted.event.source)
        assertEquals(MotionType.FISH_CAST, accepted.event.type)
        assertEquals(1L, accepted.event.sequenceNumber)
        harness.nowNs = armedAtNs + 150_000_000L
        repeat(2) { harness.controller.advanceTo(harness.nowNs) }
        assertEquals(FishingPhase.BITE_WAIT, harness.snapshot.phase)
    }

    @Test
    fun controllerClockRewindFailsBeforeStateMutation() {
        val harness = Harness("rewind")
        harness.tick()
        val before = harness.snapshot

        assertThrows(IllegalArgumentException::class.java) {
            harness.controller.advanceTo(harness.nowNs - 1L)
        }
        assertEquals(before, harness.snapshot)
    }

    @Test
    fun delayedCameraFrameUsesSessionLatenessWindowWithoutRewindingControllerClock() {
        val harness = Harness("delayed-motion")
        harness.tick(18)
        val simulationTickBeforeFrames = harness.snapshot.simulationTick
        val armedAtNs = armReady(harness.controller, 0L)

        val first = harness.controller.submitMotionFrame(
            completeFrame(timestampNs = armedAtNs + 60_000_000L, castActivation = 0.9f),
        )
        val second = harness.controller.submitMotionFrame(
            completeFrame(timestampNs = armedAtNs + 180_000_000L, castActivation = 0.9f),
        )

        assertTrue(first.isEmpty())
        assertTrue(second.single() is FishingControllerInputResult.Accepted)
        assertEquals(simulationTickBeforeFrames, harness.snapshot.simulationTick)
        harness.tick()
        assertEquals(FishingPhase.BITE_WAIT, harness.snapshot.phase)
    }

    @Test
    fun suggestedTouchActionIsDisabledWhileWaitingAndAtResult() {
        val harness = Harness("suggested")
        assertEquals(MotionType.FISH_CAST, harness.snapshot.suggestedTouchType())
        harness.touch(MotionType.FISH_CAST)
        harness.tick()
        assertEquals(FishingPhase.BITE_WAIT, harness.snapshot.phase)
        assertNull(harness.snapshot.suggestedTouchType())

        harness.advanceUntil(FishingPhase.HOOK_WINDOW)
        harness.touch(MotionType.FISH_HOOK)
        harness.tick()
        while (harness.snapshot.phase != FishingPhase.RESULT) {
            harness.touch(requireNotNull(harness.snapshot.suggestedTouchType()))
            harness.tick()
        }
        assertNull(harness.snapshot.suggestedTouchType())
    }

    @Test
    fun restoredControllerStaysInactiveFencesStaleFramesAndContinuesSequence() {
        val original = FishingGameController.start(
            sessionId = "controller-restore",
            seed = 31L,
            calibrationRevision = 1,
            originNs = 0L,
            gestureDefinitions = TEST_DEFINITIONS,
        )
        val first = original.submitTouch(MotionType.FISH_CAST, 0L)
            as FishingControllerInputResult.Accepted
        assertEquals(0L, first.event.sequenceNumber)
        original.pause(PauseReason.APP_BACKGROUND)

        val resumeAtNs = 1_000_000_000L
        val restored = FishingGameController.restore(
            original.checkpoint(),
            resumeAtNs,
            TEST_DEFINITIONS,
        )
        val whilePaused = restored.submitTouch(MotionType.FISH_CAST, resumeAtNs)
        assertEquals(
            "touch_session_inactive",
            (whilePaused as FishingControllerInputResult.Ignored).detail,
        )
        assertTrue(restored.resume(resumeAtNs))

        val stale = restored.submitMotionFrame(
            completeFrame(timestampNs = 0L, castActivation = 0.9f),
        )
        assertTrue(stale.isNotEmpty())
        assertTrue(
            stale.all { result ->
                result is FishingControllerInputResult.Ignored &&
                    result.detail == "motion_rejected_NON_MONOTONIC_TIMESTAMP"
            },
        )
        val continued = restored.submitTouch(MotionType.FISH_CAST, resumeAtNs + 1L)
            as FishingControllerInputResult.Accepted
        assertEquals(1L, continued.event.sequenceNumber)
    }

    @Test
    fun phaseFilterPreventsHigherPriorityOverlappingGestureFromStealingAction() {
        val harness = Harness("phase-filter", PRIORITIZED_DEFINITIONS)

        val ready = harness.controller.submitMotionFrame(
            completeFrame(
                timestampNs = 1L,
                activations = mapOf(
                    MotionType.FISH_READY to 1f,
                    MotionType.FISH_CAST to 1f,
                    MotionType.FISH_NET to 1f,
                ),
            ),
        )
        assertEquals(
            "motion_ready_armed",
            (ready.single() as FishingControllerInputResult.Ignored).detail,
        )
        val cast = harness.controller.submitMotionFrame(
            completeFrame(
                timestampNs = 2L,
                activations = mapOf(
                    MotionType.FISH_READY to 1f,
                    MotionType.FISH_CAST to 1f,
                    MotionType.FISH_NET to 1f,
                ),
            ),
        )
        assertEquals(
            MotionType.FISH_CAST,
            (cast.single() as FishingControllerInputResult.Accepted).event.type,
        )
        harness.nowNs = 2L
        harness.tick()

        var waitFrames = 0
        var hookBoundary: List<FishingControllerInputResult>? = null
        while (harness.snapshot.phase != FishingPhase.HOOK_WINDOW) {
            check(waitFrames++ < 1_000) { "hook window was not reached" }
            harness.tick()
            val results = harness.controller.submitMotionFrame(
                completeFrame(harness.nowNs, activations = emptyMap()),
            )
            if (harness.snapshot.phase == FishingPhase.HOOK_WINDOW) {
                hookBoundary = results
            }
        }
        assertEquals(
            "motion_phase_boundary_fenced",
            (checkNotNull(hookBoundary).single() as FishingControllerInputResult.Ignored).detail,
        )
        val hookTimestampNs = harness.nowNs + 1L
        val heldAcrossBoundary = harness.controller.submitMotionFrame(
            completeFrame(
                timestampNs = hookTimestampNs,
                activations = mapOf(
                    MotionType.FISH_HOOK to 1f,
                    MotionType.FISH_NET to 1f,
                ),
            ),
        )
        assertTrue(heldAcrossBoundary.isEmpty())

        harness.controller.submitMotionFrame(
            completeFrame(timestampNs = hookTimestampNs + 1L, activations = emptyMap()),
        )
        val hook = harness.controller.submitMotionFrame(
            completeFrame(
                timestampNs = hookTimestampNs + 2L,
                activations = mapOf(
                    MotionType.FISH_HOOK to 1f,
                    MotionType.FISH_NET to 1f,
                ),
            ),
        )

        assertEquals(1, hook.filterIsInstance<FishingControllerInputResult.Accepted>().size)
        assertEquals(
            MotionType.FISH_HOOK,
            hook.filterIsInstance<FishingControllerInputResult.Accepted>().single().event.type,
        )
    }

    private class Harness(
        sessionId: String,
        definitions: Collection<GestureDefinition> = TEST_DEFINITIONS,
    ) {
        var nowNs = 0L
        val controller = FishingGameController.start(
            sessionId = sessionId,
            seed = 7L,
            calibrationRevision = 1,
            originNs = nowNs,
            gestureDefinitions = definitions,
        )
        val snapshot get() = controller.snapshot

        fun tick(count: Int = 1) {
            repeat(count) {
                nowNs += FishingGameSession.FIXED_STEP_NS
                controller.advanceTo(nowNs)
            }
        }

        fun touch(type: MotionType): FishingControllerInputResult.Accepted {
            repeat(20) {
                val result = controller.submitTouch(type, nowNs)
                if (result is FishingControllerInputResult.Accepted) return result
                tick()
            }
            error("touch $type did not leave cooldown; current=${snapshot.phase}")
        }

        fun advanceUntil(phase: FishingPhase) {
            repeat(1_000) {
                if (snapshot.phase == phase) return
                tick()
            }
            error("phase $phase was not reached; current=${snapshot.phase}")
        }
    }

    private fun completeFrame(
        timestampNs: Long,
        castActivation: Float,
    ): List<MotionSignalSample> = completeFrame(
        timestampNs,
        mapOf(MotionType.FISH_CAST to castActivation),
    )

    private fun completeFrame(
        timestampNs: Long,
        activations: Map<MotionType, Float>,
    ): List<MotionSignalSample> = FISHING_TYPES.map { type ->
        MotionSignalSample(
            playerId = PlayerId.P1,
            type = type,
            timestampNs = timestampNs,
            activation = activations[type] ?: 0.1f,
            quality = 0.9f,
            confidence = 0.9f,
            calibrationRevision = 1,
            source = InputSource.MOTION,
        )
    }

    private fun armReady(controller: FishingGameController, startedAtNs: Long): Long {
        val first = controller.submitMotionFrame(
            completeFrame(
                startedAtNs,
                activations = mapOf(MotionType.FISH_READY to 0.9f),
            ),
        )
        assertTrue(first.isEmpty())
        val armedAtNs = startedAtNs + 120_000_000L
        val second = controller.submitMotionFrame(
            completeFrame(
                armedAtNs,
                activations = mapOf(MotionType.FISH_READY to 0.9f),
            ),
        )
        assertEquals(
            "motion_ready_armed",
            (second.single() as FishingControllerInputResult.Ignored).detail,
        )
        return armedAtNs
    }

    private companion object {
        val FISHING_TYPES = listOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )
        val TEST_DEFINITIONS = FISHING_TYPES.map { type ->
            GestureDefinition(
                type = type,
                entryThreshold = 0.75f,
                exitThreshold = 0.35f,
                minimumConfidence = 0.5f,
                minimumHoldNs = 120_000_000L,
                maximumCandidateNs = 1_000_000_000L,
                cooldownNs = 180_000_000L,
                neutralRearmNs = 120_000_000L,
                exclusivityGroup = "FISHING_ARMS",
            )
        }
        val PRIORITIZED_DEFINITIONS = FISHING_TYPES.map { type ->
            GestureDefinition(
                type = type,
                entryThreshold = 0.75f,
                exitThreshold = 0.35f,
                minimumConfidence = 0.5f,
                minimumHoldNs = 0L,
                maximumCandidateNs = 1_000_000_000L,
                cooldownNs = 0L,
                neutralRearmNs = 0L,
                exclusivityGroup = "FISHING_ARMS",
                priority = when (type) {
                    MotionType.FISH_NET -> 40
                    MotionType.FISH_HOOK -> 20
                    else -> 10
                },
            )
        }
    }
}
