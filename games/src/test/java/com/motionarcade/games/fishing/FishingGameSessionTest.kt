package com.motionarcade.games.fishing

import com.motionarcade.core.contract.ContractResult
import com.motionarcade.core.contract.DeterministicEventId
import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingGameSessionTest {
    @Test
    fun acceptQueuesWithoutRuleMutationAndTickAppliesAtBoundary() {
        val game = start("tick-boundary", 1L)
        val before = game.snapshot

        val accepted = game.acceptCurrent(
            event("tick-boundary", 0, MotionType.FISH_CAST, tick = 0),
        )

        assertTrue(accepted is FishingInputResult.Queued)
        assertEquals(before.phase, game.snapshot.phase)
        assertEquals(before.simulationTick, game.snapshot.simulationTick)
        assertEquals(before.score, game.snapshot.score)
        assertEquals(0L, game.snapshot.acceptedSequenceWatermark)
        assertEquals(-1L, game.snapshot.lastAppliedSequence)
        game.advanceTicks(1)
        assertEquals(FishingPhase.BITE_WAIT, game.snapshot.phase)
        assertEquals(0L, game.snapshot.lastAppliedSequence)
    }

    @Test
    fun motionAndTouchReplaysProduceIdenticalCompletedResult() {
        val motion = completeSession(InputSource.MOTION, seed = 11L)
        val touch = completeSession(InputSource.TOUCH, seed = 11L)

        assertEquals(motion, touch)
        assertEquals(FishingPhase.RESULT, motion.phase)
        assertEquals(FishingOutcome.CAUGHT, motion.outcome)
        assertTrue(motion.score > 0)
        assertTrue(motion.pendingRewardId != null)
    }

    @Test
    fun callbackDeliveryInterleaveDoesNotChangeHookOutcome() {
        val early = start("interleave", 6L)
        val late = start("interleave", 6L)
        castAndOpenHook(early)
        castAndOpenHook(late)
        val observedTick = early.snapshot.simulationTick + 1L
        val deadline = requireNotNull(early.snapshot.hookDeadlineTick)
        val commonFinalTick = deadline + 21L
        val hook = event("interleave", 1, MotionType.FISH_HOOK, observedTick)

        assertTrue(early.acceptCurrent(hook) is FishingInputResult.Queued)
        advanceTo(early, commonFinalTick)
        advanceTo(late, deadline + 20L)
        assertTrue(late.acceptCurrent(hook) is FishingInputResult.Queued)
        advanceTo(late, commonFinalTick)

        assertEquals(early.snapshot, late.snapshot)
        assertEquals(FishingPhase.REELING, late.snapshot.phase)
    }

    @Test
    fun seededSelectionCanReachBothInitialFish() {
        val selected = (0L..100L).map { seed ->
            val game = start("seed-$seed", seed)
            enqueueAndAdvance(game, 0, MotionType.FISH_CAST, tick = 0)
            requireNotNull(game.snapshot.fish)
        }.toSet()

        assertEquals(FishingFish.entries.toSet(), selected)
    }

    @Test
    fun rareSelectionCombinesTechniqueDifficultyFailureStreakAndSeed() {
        val differentiatingSeed = (0L..10_000L).first { seed ->
            selectedFish(seed, quality = 0f, failureStreak = 0, difficulty = 0) !=
                selectedFish(seed, quality = 1f, failureStreak = 8, difficulty = 10)
        }

        assertEquals(
            FishingFish.SUNFIN,
            selectedFish(differentiatingSeed, 0f, 0, 0),
        )
        assertEquals(
            FishingFish.MOON_CARP,
            selectedFish(differentiatingSeed, 1f, 8, 10),
        )
    }

    @Test
    fun invalidPhaseInputIsOrderedButCannotChangeRules() {
        val game = start("phase", 3L)

        enqueueAndAdvance(game, 0, MotionType.FISH_HOOK, tick = 0)

        assertEquals(FishingPhase.READY, game.snapshot.phase)
        assertEquals(0L, game.snapshot.lastAppliedSequence)
        enqueueAndAdvance(game, 1, MotionType.FISH_CAST, tick = 1)
        assertEquals(FishingPhase.BITE_WAIT, game.snapshot.phase)
    }

    @Test
    fun duplicateForeignSessionAndTimestampRewindAreRejectedBeforeMutation() {
        val game = start("owned", 4L)
        val cast = event("owned", 0, MotionType.FISH_CAST, tick = 1)
        assertTrue(game.acceptCurrent(cast) is FishingInputResult.Queued)
        val before = game.snapshot

        assertRejected(FishingInputRejection.SEQUENCE_REWIND, game.acceptCurrent(cast))
        assertRejected(
            FishingInputRejection.SESSION_MISMATCH,
            game.acceptCurrent(event("foreign", 1, MotionType.FISH_CAST, tick = 2)),
        )
        assertRejected(
            FishingInputRejection.TIMESTAMP_REWIND,
            game.acceptCurrent(event("owned", 1, MotionType.FISH_CAST, tick = 0)),
        )
        assertEquals(before, game.snapshot)
    }

    @Test
    fun pauseFreezesTicksFlushesInputAndRequiresExplicitResume() {
        val game = start("pause", 5L)
        enqueueAndAdvance(game, 0, MotionType.FISH_CAST, tick = 0)
        val beforePause = game.pause(PauseReason.USER)

        assertEquals(SessionStatus.PAUSED, beforePause.status)
        assertEquals(PauseReason.USER, beforePause.pauseReason)
        game.advanceTicks(FishingGameSession.MAX_CATCH_UP_TICKS)
        val ignored = game.acceptCurrent(event("pause", 1, MotionType.FISH_HOOK, tick = 1))

        assertEquals(beforePause, game.snapshot)
        assertEquals(
            FishingIgnoreReason.SESSION_PAUSED,
            (ignored as FishingInputResult.Ignored).reason,
        )
        assertTrue(game.resume(nowNs = 10_000L * FishingGameSession.FIXED_STEP_NS))
        game.advanceTicks(1)
        assertEquals(beforePause.simulationTick + 1L, game.snapshot.simulationTick)
        assertFalse(game.snapshot.paused)
    }

    @Test
    fun longPauseRebasesMonotonicTimeFlushesQueuedInputAndRejectsOldEpoch() {
        val game = start("long-pause", 5L)
        assertTrue(
            game.acceptCurrent(
                eventFor(game, 0, MotionType.FISH_CAST, tick = 1L),
            ) is FishingInputResult.Queued,
        )
        val paused = game.pause(PauseReason.USER)
        repeat(100) { game.advanceTicks(FishingGameSession.MAX_CATCH_UP_TICKS) }
        assertEquals(paused, game.snapshot)

        val resumedAtNs = 9_000_000L * FishingGameSession.FIXED_STEP_NS
        assertTrue(game.resume(resumedAtNs))
        assertEquals(1L, game.snapshot.eventTimelineEpoch)
        assertEquals(resumedAtNs, game.snapshot.eventTimelineBaseNs)
        assertEquals(paused.simulationTick, game.snapshot.eventTimelineSimulationBaseTick)
        assertRejected(
            FishingInputRejection.TIMELINE_EPOCH_MISMATCH,
            game.accept(
                event("long-pause", 1, MotionType.FISH_CAST, tick = 2L),
                eventTimelineEpoch = 0L,
            ),
        )

        assertTrue(
            game.acceptCurrent(
                eventFor(
                    game,
                    sequence = 1L,
                    type = MotionType.FISH_CAST,
                    tick = game.snapshot.simulationTick,
                ),
            ) is FishingInputResult.Queued,
        )
        game.advanceTicks(1)
        assertEquals(FishingPhase.BITE_WAIT, game.snapshot.phase)
        assertEquals(1L, game.snapshot.lastAppliedSequence)
    }

    @Test
    fun wallClockPauseDoesNotAdvanceBiteTimerAfterRebase() {
        val game = start("pause-timer", 5L)
        enqueueAndAdvance(game, 0, MotionType.FISH_CAST, tick = 0)
        val biteTick = requireNotNull(game.snapshot.biteAtTick)
        val frozenTick = game.snapshot.simulationTick

        game.pause(PauseReason.APP_BACKGROUND)
        game.advanceTicks(FishingGameSession.MAX_CATCH_UP_TICKS)
        assertTrue(
            game.resume(nowNs = 500_000_000L * FishingGameSession.FIXED_STEP_NS),
        )
        assertEquals(frozenTick, game.snapshot.simulationTick)
        advanceTo(game, biteTick - 1L)
        assertEquals(FishingPhase.BITE_WAIT, game.snapshot.phase)
        game.advanceTicks(1)
        assertEquals(FishingPhase.HOOK_WINDOW, game.snapshot.phase)
    }

    @Test
    fun processDeathRestoreAlwaysReturnsPausedAndPreservesVersionedState() {
        val original = start("restore", 7L)
        enqueueAndAdvance(original, 0, MotionType.FISH_CAST, tick = 0)
        val checkpoint = original.checkpoint()

        val restored = FishingGameSession.restore(checkpoint)

        assertEquals(SessionStatus.PAUSED, restored.snapshot.status)
        assertEquals(PauseReason.APP_BACKGROUND, restored.snapshot.pauseReason)
        assertEquals(checkpoint.prngState, restored.snapshot.prngState)
        restored.advanceTicks(1)
        assertEquals(checkpoint.simulationTick, restored.snapshot.simulationTick)
        assertTrue(restored.resume(nowNs = 20_000L * FishingGameSession.FIXED_STEP_NS))
    }

    @Test
    fun restoreRejectsPreLedgerSchemaInsteadOfInventingMissingEpochHistory() {
        val checkpoint = start("restore-schema", 7L).checkpoint()

        assertEquals(3, checkpoint.schemaVersion)
        assertEquals(1, checkpoint.eventTimelineLedgerVersion)
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(checkpoint.copy(schemaVersion = 2))
        }
    }

    @Test
    fun processRestoreRearmsTimeWithoutConsumingFrozenTimerOrOldCallback() {
        val original = start("restore-rearm", 7L)
        enqueueAndAdvance(original, 0, MotionType.FISH_CAST, tick = 0)
        val checkpoint = original.checkpoint()
        val restored = FishingGameSession.restore(checkpoint)
        val resumedAtNs = 700_000_000L * FishingGameSession.FIXED_STEP_NS

        assertTrue(restored.resume(resumedAtNs))
        assertEquals(checkpoint.simulationTick, restored.snapshot.simulationTick)
        assertRejected(
            FishingInputRejection.TIMELINE_EPOCH_MISMATCH,
            restored.accept(
                event("restore-rearm", 1, MotionType.FISH_HOOK, tick = checkpoint.simulationTick),
                eventTimelineEpoch = checkpoint.eventTimelineEpoch,
            ),
        )
        advanceTo(restored, requireNotNull(checkpoint.biteAtTick) - 1L)
        assertEquals(FishingPhase.BITE_WAIT, restored.snapshot.phase)
        restored.advanceTicks(1)
        assertEquals(FishingPhase.HOOK_WINDOW, restored.snapshot.phase)
    }

    @Test
    fun resumeReturnsFalseForMonotonicClockRollback() {
        val game = start("resume-clock", 7L, originNs = 10_000L)
        game.pause(PauseReason.APP_BACKGROUND)
        val paused = game.snapshot

        assertFalse(game.resume(nowNs = 9_999L))
        assertEquals(paused, game.snapshot)
    }

    @Test
    fun resumeRejectsTimeBeforeAppliedEventTimestampWatermark() {
        val game = start("resume-applied-watermark", 7L)
        val castTimestampNs = FishingGameSession.FIXED_STEP_NS + 10_000L
        val cast = event(
            "resume-applied-watermark",
            0L,
            MotionType.FISH_CAST,
            tick = 0L,
        ).copy(eventTimestampNs = castTimestampNs)

        assertTrue(game.acceptCurrent(cast) is FishingInputResult.Queued)
        game.advanceTicks(1)
        assertEquals(castTimestampNs, game.snapshot.acceptedEventTimestampWatermarkNs)
        game.pause(PauseReason.APP_BACKGROUND)
        val paused = game.snapshot

        assertFalse(game.resume(nowNs = 10_000L))
        assertEquals(paused, game.snapshot)
        assertTrue(game.resume(nowNs = castTimestampNs))
    }

    @Test
    fun queueFlushAndProcessRestorePreserveFutureTimestampWatermark() {
        val game = start("resume-queued-watermark", 7L)
        val futureTimestampNs = FishingGameSession.FIXED_STEP_NS * 5L + 10_000L
        val future = event(
            "resume-queued-watermark",
            0L,
            MotionType.FISH_CAST,
            tick = 0L,
        ).copy(eventTimestampNs = futureTimestampNs)

        assertTrue(game.acceptCurrent(future) is FishingInputResult.Queued)
        game.pause(PauseReason.APP_BACKGROUND)
        val checkpoint = game.checkpoint()
        assertEquals(futureTimestampNs, checkpoint.acceptedEventTimestampWatermarkNs)
        assertFalse(game.resume(nowNs = 10_000L))

        val restored = FishingGameSession.restore(checkpoint)
        assertEquals(futureTimestampNs, restored.snapshot.acceptedEventTimestampWatermarkNs)
        assertFalse(restored.resume(nowNs = 10_000L))
        assertTrue(restored.resume(nowNs = futureTimestampNs))
    }

    @Test
    fun timelineLedgerRecordsEveryEpochIsImmutableAndHasBoundedResumeBehavior() {
        val game = start("timeline-ledger", 7L, originNs = 10L)

        repeat(FishingGameSession.TIMELINE_LEDGER_CAPACITY - 1) { index ->
            game.pause(PauseReason.APP_BACKGROUND)
            assertTrue(game.resume(nowNs = 11L + index.toLong()))
        }

        val full = game.snapshot
        assertEquals(FishingGameSession.TIMELINE_LEDGER_CAPACITY, full.eventTimelineLedger.size)
        assertEquals(full.eventTimelineEpoch, full.eventTimelineLedger.last().epoch)
        assertEquals(full.eventTimelineBaseNs, full.eventTimelineLedger.last().baseNs)
        assertEquals(
            full.eventTimelineSimulationBaseTick,
            full.eventTimelineLedger.last().simulationBaseTick,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (full.eventTimelineLedger as MutableList).add(
                FishingTimelineEpoch(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
            )
        }

        game.pause(PauseReason.APP_BACKGROUND)
        val beforeOverflowResume = game.snapshot
        assertFalse(game.resume(nowNs = full.eventTimelineBaseNs + 1L))
        assertEquals(beforeOverflowResume, game.snapshot)
        assertEquals(
            beforeOverflowResume,
            FishingGameSession.restore(beforeOverflowResume).checkpoint(),
        )
        val oversizedLedger = beforeOverflowResume.eventTimelineLedger +
            FishingTimelineEpoch(
                epoch = FishingGameSession.TIMELINE_LEDGER_CAPACITY.toLong(),
                baseNs = beforeOverflowResume.eventTimelineBaseNs,
                simulationBaseTick = beforeOverflowResume.eventTimelineSimulationBaseTick,
            )
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                beforeOverflowResume.copy(
                    eventTimelineEpoch = FishingGameSession.TIMELINE_LEDGER_CAPACITY.toLong(),
                    eventTimelineLedger = oversizedLedger,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                beforeOverflowResume.copy(eventTimelineEpoch = Long.MAX_VALUE),
            )
        }
    }

    @Test
    fun restoreRejectsTimelineLedgerVersionGapsFinalMismatchAndIntermediateLowering() {
        val game = start("ledger-structure", 9L, originNs = 100L)
        enqueueAndAdvance(game, 0L, MotionType.FISH_CAST, tick = 0L)
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(1_000L * FishingGameSession.FIXED_STEP_NS))
        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        enqueueAndAdvance(
            game,
            1L,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(2_000L * FishingGameSession.FIXED_STEP_NS))
        val checkpoint = game.checkpoint()
        FishingGameSession.restore(checkpoint)

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                checkpoint.copy(
                    eventTimelineLedgerVersion = checkpoint.eventTimelineLedgerVersion + 1,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                checkpoint.copy(
                    eventTimelineLedger = checkpoint.eventTimelineLedger.filterNot {
                        it.epoch == 1L
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                checkpoint.copy(
                    eventTimelineLedger = checkpoint.eventTimelineLedger.map { row ->
                        if (row.epoch == checkpoint.eventTimelineEpoch) {
                            row.copy(baseNs = row.baseNs + 1L)
                        } else {
                            row
                        }
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                checkpoint.copy(
                    eventTimelineLedger = checkpoint.eventTimelineLedger.map { row ->
                        if (row.epoch == 1L) row.copy(baseNs = row.baseNs - 1L) else row
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                checkpoint.copy(
                    eventTimelineLedger = checkpoint.eventTimelineLedger.map { row ->
                        if (row.epoch == 1L) {
                            row.copy(baseNs = checkpoint.eventTimelineLedger.first().baseNs - 1L)
                        } else {
                            row
                        }
                    },
                ),
            )
        }
    }

    @Test
    fun restoreRejectsIntermediateEpochBaseLoweredBelowPriorReceiptTimestamp() {
        val game = start("ledger-intermediate-base", 9L, originNs = 100L)
        enqueueAndAdvance(game, 0L, MotionType.FISH_CAST, tick = 1L)
        val castTimestamp = requireNotNull(game.snapshot.castEventTimestampNs)
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(castTimestamp + 100L))
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(castTimestamp + 200L))
        val checkpoint = game.checkpoint()
        FishingGameSession.restore(checkpoint)
        val loweredIntermediate = checkpoint.copy(
            eventTimelineLedger = checkpoint.eventTimelineLedger.map { row ->
                if (row.epoch == 1L) row.copy(baseNs = castTimestamp - 1L) else row
            },
        )

        assertTrue(loweredIntermediate.eventTimelineLedger[1].baseNs >
            loweredIntermediate.eventTimelineLedger[0].baseNs)
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(loweredIntermediate)
        }
    }

    @Test
    fun restoreRejectsCoherentSameEpochReceiptShiftAgainstIndependentLedgerRow() {
        val game = start("ledger-receipt-binding", 9L)
        castAndOpenHook(game)
        enqueueAndAdvance(
            game,
            1L,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        val checkpoint = game.checkpoint()
        FishingGameSession.restore(checkpoint)
        val shifted = checkpoint.copy(
            hookEventTimelineBaseNs = requireNotNull(checkpoint.hookEventTimelineBaseNs) + 1L,
            hookEventTimestampNs = requireNotNull(checkpoint.hookEventTimestampNs) + 1L,
            lastAppliedEventTimelineBaseNs =
                requireNotNull(checkpoint.lastAppliedEventTimelineBaseNs) + 1L,
            lastAppliedEventTimestampNs = checkpoint.lastAppliedEventTimestampNs + 1L,
            lastStateChangingEventTimelineBaseNs =
                requireNotNull(checkpoint.lastStateChangingEventTimelineBaseNs) + 1L,
            lastStateChangingEventTimestampNs =
                checkpoint.lastStateChangingEventTimestampNs + 1L,
            acceptedEventTimestampWatermarkNs =
                checkpoint.acceptedEventTimestampWatermarkNs + 1L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(shifted)
        }
    }

    @Test
    fun restoreMapsAcceptedTimestampWatermarkToCurrentTimelineWithoutOverflow() {
        val ready = start("watermark-mapping", 7L).checkpoint()
        FishingGameSession.restore(ready)
        val tooFarTimestamp = Math.multiplyExact(
            FishingGameSession.MAX_FUTURE_EVENT_TICKS + 1L,
            FishingGameSession.FIXED_STEP_NS,
        )

        listOf(-1L, Long.MAX_VALUE, tooFarTimestamp).forEach { invalidWatermark ->
            assertThrows(IllegalArgumentException::class.java) {
                FishingGameSession.restore(
                    ready.copy(acceptedEventTimestampWatermarkNs = invalidWatermark),
                )
            }
        }

        val game = start("watermark-receipt-floor", 7L)
        enqueueAndAdvance(game, 0L, MotionType.FISH_CAST, tick = 0L)
        val cast = game.checkpoint()
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                cast.copy(
                    acceptedEventTimestampWatermarkNs =
                        requireNotNull(cast.castEventTimestampNs) - 1L,
                ),
            )
        }
    }

    @Test
    fun restoreValidatesPastEpochCastAndHookTimestampReceipts() {
        val game = start("past-epoch-provenance", 9L)
        enqueueAndAdvance(game, 0L, MotionType.FISH_CAST, tick = 0L)
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(1_000L * FishingGameSession.FIXED_STEP_NS))
        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        enqueueAndAdvance(
            game,
            1L,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(2_000L * FishingGameSession.FIXED_STEP_NS))
        val checkpoint = game.checkpoint()
        assertEquals(0L, checkpoint.castEventEpoch)
        assertEquals(1L, checkpoint.hookEventEpoch)
        assertEquals(2L, checkpoint.eventTimelineEpoch)

        listOf(-1L, Long.MAX_VALUE).forEach { invalidTimestamp ->
            assertThrows(IllegalArgumentException::class.java) {
                FishingGameSession.restore(
                    checkpoint.copy(castEventTimestampNs = invalidTimestamp),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                FishingGameSession.restore(
                    checkpoint.copy(hookEventTimestampNs = invalidTimestamp),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                checkpoint.copy(
                    castEventTimelineBaseNs =
                        requireNotNull(checkpoint.castEventTimelineBaseNs) + 1L,
                ),
            )
        }
    }

    @Test
    fun restoreRejectsSameEpochReceiptShiftEvenWhenTimestampsAreRewritten() {
        val game = start("same-epoch-receipt", 9L)
        castAndOpenHook(game)
        enqueueAndAdvance(
            game,
            1L,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(1_000L * FishingGameSession.FIXED_STEP_NS))
        val checkpoint = game.checkpoint()
        val shifted = checkpoint.copy(
            hookEventTimelineBaseNs = requireNotNull(checkpoint.hookEventTimelineBaseNs) + 1L,
            hookEventTimestampNs = requireNotNull(checkpoint.hookEventTimestampNs) + 1L,
            lastStateChangingEventTimelineBaseNs =
                requireNotNull(checkpoint.lastStateChangingEventTimelineBaseNs) + 1L,
            lastStateChangingEventTimestampNs =
                checkpoint.lastStateChangingEventTimestampNs + 1L,
            lastAppliedEventTimelineBaseNs =
                requireNotNull(checkpoint.lastAppliedEventTimelineBaseNs) + 1L,
            lastAppliedEventTimestampNs = checkpoint.lastAppliedEventTimestampNs + 1L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(shifted)
        }
    }

    @Test
    fun restoreRejectsRewrittenHookEpochReceiptThatPredatesCast() {
        val game = start("cross-epoch-hook-receipt", 9L)
        enqueueAndAdvance(game, 0L, MotionType.FISH_CAST, tick = 1L)
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(1_000L * FishingGameSession.FIXED_STEP_NS))
        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        enqueueAndAdvance(
            game,
            1L,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(2_000L * FishingGameSession.FIXED_STEP_NS))
        val checkpoint = game.checkpoint()
        val hookTick = requireNotNull(checkpoint.hookEventTick)
        val hookSimulationBaseTick =
            requireNotNull(checkpoint.hookEventTimelineSimulationBaseTick)
        val rewrittenTimestampNs = Math.multiplyExact(
            hookTick - hookSimulationBaseTick,
            FishingGameSession.FIXED_STEP_NS,
        )
        val rewritten = checkpoint.copy(
            hookEventTimelineBaseNs = 0L,
            hookEventTimestampNs = rewrittenTimestampNs,
            lastStateChangingEventTimelineBaseNs = 0L,
            lastStateChangingEventTimestampNs = rewrittenTimestampNs,
            lastAppliedEventTimelineBaseNs = 0L,
            lastAppliedEventTimestampNs = rewrittenTimestampNs,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(rewritten)
        }
    }

    @Test
    fun restoreRejectsReelProvenanceEpochRewindBehindHook() {
        val game = start("reel-epoch-rewind", 9L)
        enqueueAndAdvance(game, 0L, MotionType.FISH_CAST, tick = 0L)
        game.pause(PauseReason.APP_BACKGROUND)
        assertTrue(game.resume(1_000L * FishingGameSession.FIXED_STEP_NS))
        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        enqueueAndAdvance(
            game,
            1L,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        enqueueAndAdvance(
            game,
            2L,
            MotionType.FISH_REEL_CYCLE,
            tick = game.snapshot.simulationTick,
        )
        val checkpoint = game.checkpoint()
        val reelTick = requireNotNull(checkpoint.lastStateChangingEventTick)
        val rewrittenTimestampNs = Math.multiplyExact(
            reelTick,
            FishingGameSession.FIXED_STEP_NS,
        )
        val rewritten = checkpoint.copy(
            lastStateChangingEventEpoch = 0L,
            lastStateChangingEventTimestampNs = rewrittenTimestampNs,
            lastStateChangingEventTimelineBaseNs = 0L,
            lastStateChangingEventTimelineSimulationBaseTick = 0L,
            lastAppliedEventEpoch = 0L,
            lastAppliedEventTimestampNs = rewrittenTimestampNs,
            lastAppliedEventTimelineBaseNs = 0L,
            lastAppliedEventTimelineSimulationBaseTick = 0L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(rewritten)
        }
    }

    @Test
    fun checkpointRoundTripPreservesSingleAndMultiEpochProvenanceReceipts() {
        val singleEpoch = start("receipt-roundtrip-single", 9L)
        castAndOpenHook(singleEpoch)
        enqueueAndAdvance(
            singleEpoch,
            1L,
            MotionType.FISH_HOOK,
            tick = singleEpoch.snapshot.simulationTick,
        )
        assertProvenanceRoundTrip(singleEpoch.checkpoint())

        singleEpoch.pause(PauseReason.APP_BACKGROUND)
        assertTrue(singleEpoch.resume(1_000L * FishingGameSession.FIXED_STEP_NS))
        enqueueAndAdvance(
            singleEpoch,
            2L,
            MotionType.FISH_REEL_CYCLE,
            tick = singleEpoch.snapshot.simulationTick,
        )
        val multiEpoch = singleEpoch.checkpoint()
        assertEquals(0L, multiEpoch.hookEventEpoch)
        assertEquals(1L, multiEpoch.lastAppliedEventEpoch)
        assertProvenanceRoundTrip(multiEpoch)

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(multiEpoch.copy(lastAppliedEventTimelineBaseNs = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                multiEpoch.copy(lastStateChangingEventTimelineSimulationBaseTick = null),
            )
        }
    }

    @Test
    fun processRestorePreservesFlushedQueueSequenceWatermark() {
        val original = start("restore-queued", 7L)
        assertTrue(
            original.acceptCurrent(
                eventFor(original, 0, MotionType.FISH_CAST, tick = 1L),
            ) is FishingInputResult.Queued,
        )
        original.pause(PauseReason.APP_BACKGROUND)
        val checkpoint = original.checkpoint()
        assertEquals(0L, checkpoint.acceptedSequenceWatermark)
        assertEquals(-1L, checkpoint.lastAppliedSequence)

        val restored = FishingGameSession.restore(checkpoint)
        assertTrue(restored.resume(800_000_000L * FishingGameSession.FIXED_STEP_NS))
        val replay = eventFor(
            restored,
            sequence = 0L,
            type = MotionType.FISH_CAST,
            tick = restored.snapshot.simulationTick,
        )
        assertRejected(
            FishingInputRejection.SEQUENCE_REWIND,
            restored.acceptCurrent(replay),
        )
        restored.advanceTicks(1)
        assertEquals(FishingPhase.READY, restored.snapshot.phase)
    }

    @Test
    fun restoreRebasesTheVersionedEventTimeline() {
        val originTick = 100L
        val originNs = Math.multiplyExact(originTick, FishingGameSession.FIXED_STEP_NS)
        val original = start("origin", 7L, originNs = originNs)

        val restored = FishingGameSession.restore(original.checkpoint())
        assertEquals(originNs, restored.snapshot.eventTimelineBaseNs)
        assertEquals(0L, restored.snapshot.eventTimelineEpoch)
        val resumedAtTick = 1_100L
        assertTrue(
            restored.resume(
                nowNs = Math.multiplyExact(resumedAtTick, FishingGameSession.FIXED_STEP_NS),
            ),
        )
        val queued = restored.acceptCurrent(
            event("origin", 0, MotionType.FISH_CAST, tick = resumedAtTick),
        ) as FishingInputResult.Queued

        assertEquals(0L, queued.scheduledTick)
        assertEquals(1L, restored.snapshot.eventTimelineEpoch)
    }

    @Test
    fun firstMissUsesRecoveryAndSecondSettledMissEscapes() {
        val game = start("recovery", 6L)
        castAndOpenHook(game)
        settleCurrentHookWindow(game)

        assertEquals(FishingPhase.BITE_WAIT, game.snapshot.phase)
        assertEquals(0, game.snapshot.recoveryTokens)
        assertEquals(null, game.snapshot.outcome)

        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        settleCurrentHookWindow(game)
        assertEquals(FishingPhase.RESULT, game.snapshot.phase)
        assertEquals(FishingOutcome.ESCAPED, game.snapshot.outcome)
    }

    @Test
    fun recoveryScheduleIsExactAndRemainingWindowRestores() {
        val game = start("recovery-schedule", 6L)
        castAndOpenHook(game)
        val initialBite = requireNotNull(game.snapshot.hookDeadlineTick) - 30L
        settleCurrentHookWindow(game)
        val scheduledAt = requireNotNull(game.snapshot.recoveryScheduledAtTick)

        assertEquals(
            initialBite + 30L + FishingGameSession.MAX_EVENT_LATENESS_TICKS + 1L,
            scheduledAt,
        )
        assertEquals(scheduledAt + 30L, game.snapshot.biteAtTick)
        game.advanceTicks(5)
        val restored = FishingGameSession.restore(game.checkpoint())
        assertEquals(scheduledAt, restored.snapshot.recoveryScheduledAtTick)
        assertEquals(scheduledAt + 30L, restored.snapshot.biteAtTick)
        assertTrue(restored.snapshot.simulationTick in scheduledAt..(scheduledAt + 30L))
    }

    @Test
    fun oversizedTickAdvanceStopsAtTerminalTransition() {
        val game = start("terminal-tick", 6L)
        castAndOpenHook(game)
        settleCurrentHookWindow(game)
        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        val expectedTerminalTick = requireNotNull(game.snapshot.hookDeadlineTick) +
            FishingGameSession.MAX_EVENT_LATENESS_TICKS + 1L

        while (game.snapshot.phase != FishingPhase.RESULT) {
            game.advanceTicks(FishingGameSession.MAX_CATCH_UP_TICKS)
        }

        assertEquals(FishingPhase.RESULT, game.snapshot.phase)
        assertEquals(expectedTerminalTick, game.snapshot.simulationTick)
    }

    @Test
    fun checkpointRestoreProducesSameContinuationAfterExplicitResume() {
        val original = start("resume", 7L)
        castAndOpenHook(original)
        enqueueAndAdvance(
            original,
            1,
            MotionType.FISH_HOOK,
            tick = original.snapshot.simulationTick,
        )
        enqueueAndAdvance(
            original,
            2,
            MotionType.FISH_REEL_CYCLE,
            tick = original.snapshot.simulationTick,
        )
        val checkpoint = original.checkpoint()
        val restored = FishingGameSession.restore(checkpoint)
        original.pause(PauseReason.APP_BACKGROUND)
        val resumedAtNs = 50_000L * FishingGameSession.FIXED_STEP_NS
        assertTrue(original.resume(resumedAtNs))
        assertTrue(restored.resume(resumedAtNs))

        completeFromCurrent(original, nextSequence = 3)
        completeFromCurrent(restored, nextSequence = 3)

        assertEquals(original.snapshot, restored.snapshot)
    }

    @Test
    fun hookWindowCheckpointReturnsPreWindowSafeState() {
        val game = start("safe", 8L)
        castAndOpenHook(game)

        assertEquals(FishingPhase.HOOK_WINDOW, game.snapshot.phase)
        assertEquals(FishingPhase.BITE_WAIT, game.checkpoint().phase)
        assertTrue(game.checkpoint().simulationTick < game.snapshot.simulationTick)
    }

    @Test
    fun rewardCommitIsIdempotentAndSnapshotLedgerCannotBeMutated() {
        val game = start("reward", 9L)
        completeFromStart(game)
        val reward = requireNotNull(game.snapshot.pendingRewardId)

        assertTrue(game.commitReward(reward))
        assertFalse(game.commitReward(reward))
        val exposed = game.snapshot.committedRewardIds
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (exposed as MutableSet<String>).clear()
        }
        assertEquals(setOf(reward), game.snapshot.committedRewardIds)
        assertEquals(null, game.snapshot.pendingRewardId)
    }

    @Test
    fun resultIsTerminalForTicksPauseAndLaterInput() {
        val game = start("terminal", 9L)
        completeFromStart(game)
        val completed = game.snapshot

        val result = game.acceptCurrent(
            event("terminal", 100, MotionType.FISH_CAST, tick = completed.simulationTick),
        )
        game.advanceTicks(FishingGameSession.MAX_CATCH_UP_TICKS)
        game.pause(PauseReason.USER)

        assertEquals(
            FishingIgnoreReason.SESSION_ALREADY_FINISHED,
            (result as FishingInputResult.Ignored).reason,
        )
        assertEquals(completed, game.snapshot)
    }

    @Test
    fun restoreRejectsImpossibleNettingLostRewardContentAndScore() {
        val ready = start("invalid-ready", 9L).snapshot
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(ready.copy(score = Int.MAX_VALUE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(ready.copy(contentRevision = "future"))
        }

        val biteGame = start("invalid-bite", 9L)
        enqueueAndAdvance(biteGame, 0, MotionType.FISH_CAST, tick = 0)
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                biteGame.checkpoint().copy(score = FishingGameSession.MAX_SCORE),
            )
        }

        val nettingGame = start("invalid-net", 9L)
        completeUntilNetting(nettingGame)
        val netting = nettingGame.checkpoint()
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(netting.copy(reelCycles = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(netting.copy(tension = 0))
        }

        val completed = completeSession(InputSource.FIXTURE, 9L)
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(completed.copy(pendingRewardId = null))
        }
    }

    @Test
    fun restoreAcceptsNullRejectingJavaSetWithoutNpe() {
        val ready = start("java-set", 9L).snapshot.copy(
            committedRewardIds = java.util.Set.of<String>(),
        )

        val restored = FishingGameSession.restore(ready)

        assertEquals(emptySet<String>(), restored.snapshot.committedRewardIds)
        assertTrue(restored.snapshot.paused)
    }

    @Test
    fun restoreRejectsScoreAboveReachablePhaseMaximum() {
        val source = start("score-cap", 9L)
        castAndOpenHook(source)
        enqueueAndAdvance(
            source,
            1,
            MotionType.FISH_HOOK,
            tick = source.snapshot.simulationTick,
        )
        val injected = source.checkpoint().copy(score = FishingGameSession.MAX_SCORE)

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(injected)
        }
    }

    @Test
    fun restoreRejectsSingleFieldCastQualityBiteScoreAndTimestampTampering() {
        val source = start("cast-provenance", 9L)
        enqueueAndAdvance(
            source,
            sequence = 0,
            type = MotionType.FISH_CAST,
            tick = 0,
            quality = 0.8f,
        )
        val bite = source.checkpoint()
        assertEquals(160, bite.score)
        assertEquals(0L, bite.lastAppliedEventTimestampNs)
        assertTrue(requireNotNull(bite.biteAtTick) > bite.simulationTick)

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(bite.copy(castQuality = 0.79f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(bite.copy(score = 200))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(bite.copy(lastAppliedEventTimestampNs = 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                bite.copy(simulationTick = requireNotNull(bite.biteAtTick)),
            )
        }
    }

    @Test
    fun restoreRejectsHookWatermarkAndProgressScoreComponentTampering() {
        val source = start("hook-provenance", 9L)
        castAndOpenHook(source)
        enqueueAndAdvance(
            source,
            sequence = 1,
            type = MotionType.FISH_HOOK,
            tick = source.snapshot.simulationTick,
        )
        enqueueAndAdvance(
            source,
            sequence = 2,
            type = MotionType.FISH_REEL_CYCLE,
            tick = source.snapshot.simulationTick,
        )
        val progress = source.checkpoint()
        assertTrue(requireNotNull(progress.hookEventTick) > 21L)

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(progress.copy(hookEventTick = 21L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(progress.copy(lastAppliedEventTick = 21L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(progress.copy(score = progress.score + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(progress.copy(reelScore = progress.reelScore + 1))
        }
    }

    @Test
    fun restoreRejectsProgressSequenceArithmeticOverflowAtNettingAndResult() {
        val checkpoints = buildList {
            listOf(1L, 9L).forEach { seed ->
                val netting = start("overflow-net-$seed", seed)
                completeUntilNetting(netting)
                add(netting.checkpoint())
                add(completeSession(InputSource.FIXTURE, seed))
            }
        }
        assertEquals(FishingFish.entries.toSet(), checkpoints.mapNotNull { it.fish }.toSet())

        checkpoints.forEach { checkpoint ->
            val impossible = checkpoint.copy(
                castEventSequence = Long.MAX_VALUE - 6L,
                hookEventSequence = Long.MAX_VALUE - 5L,
                acceptedSequenceWatermark = Long.MAX_VALUE - 1L,
                lastAppliedSequence = Long.MAX_VALUE - 1L,
                lastStateChangingSequence = Long.MAX_VALUE - 1L,
            )
            assertThrows(IllegalArgumentException::class.java) {
                FishingGameSession.restore(impossible)
            }
        }
    }

    @Test
    fun restoreRejectsRecoveryBiteAndEscapedTokenTampering() {
        val source = start("recovery-tamper", 6L)
        castAndOpenHook(source)
        settleCurrentHookWindow(source)
        val recovery = source.checkpoint()
        assertTrue(requireNotNull(recovery.biteAtTick) > recovery.simulationTick)

        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                recovery.copy(biteAtTick = requireNotNull(recovery.biteAtTick) + 1_000L),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(
                recovery.copy(simulationTick = requireNotNull(recovery.biteAtTick)),
            )
        }

        advanceTo(source, requireNotNull(source.snapshot.biteAtTick))
        settleCurrentHookWindow(source)
        val escaped = source.snapshot
        assertEquals(0, escaped.recoveryTokens)
        assertThrows(IllegalArgumentException::class.java) {
            FishingGameSession.restore(escaped.copy(recoveryTokens = 1))
        }
    }

    @Test
    fun reachableSunfinAndMoonCarpProgressAndResultsRestore() {
        val seedsByFish = FishingFish.entries.associateWith { expected ->
            (0L..1_000L).first { seed ->
                selectedFish(seed, quality = 0.8f, failureStreak = 0, difficulty = 1) == expected
            }
        }

        seedsByFish.forEach { (expectedFish, seed) ->
            val game = start("reachable-${expectedFish.name}", seed)
            completeUntilNetting(game)
            val progress = game.checkpoint()
            val restoredProgress = FishingGameSession.restore(progress)
            assertEquals(expectedFish, restoredProgress.snapshot.fish)
            assertTrue(
                restoredProgress.resume(
                    nowNs = (1_000_000L + seed) * FishingGameSession.FIXED_STEP_NS,
                ),
            )
            completeFromCurrent(
                restoredProgress,
                nextSequence = progress.lastAppliedSequence + 1L,
            )
            val restoredResult = FishingGameSession.restore(restoredProgress.snapshot)
            assertEquals(FishingOutcome.CAUGHT, restoredResult.snapshot.outcome)
            assertEquals(expectedFish, restoredResult.snapshot.fish)
        }
    }

    @Test
    fun eventOlderThanBoundedLatenessIsRejected() {
        val game = start("late", 1L)
        advanceTo(game, FishingGameSession.MAX_EVENT_LATENESS_TICKS + 1L)

        assertRejected(
            FishingInputRejection.EVENT_TOO_OLD,
            game.acceptCurrent(event("late", 0, MotionType.FISH_CAST, tick = 0)),
        )
        assertEquals(FishingPhase.READY, game.snapshot.phase)
    }

    @Test
    fun eventTooFarInFutureIsRejectedWithoutPoisoningNormalInput() {
        val game = start("future", 1L)

        assertRejected(
            FishingInputRejection.EVENT_TOO_FAR_IN_FUTURE,
            game.acceptCurrent(event("future", 0, MotionType.FISH_HOOK, tick = 1_000_000L)),
        )
        assertTrue(
            game.acceptCurrent(event("future", 0, MotionType.FISH_CAST, tick = 0)) is
                FishingInputResult.Queued,
        )
        game.advanceTicks(1)
        assertEquals(FishingPhase.BITE_WAIT, game.snapshot.phase)
    }

    @Test
    fun oneGameLoopCallRejectsUnboundedCatchUp() {
        val game = start("catch-up", 1L)

        assertThrows(IllegalArgumentException::class.java) {
            game.advanceTicks(FishingGameSession.MAX_CATCH_UP_TICKS + 1)
        }
        assertEquals(0L, game.snapshot.simulationTick)
    }

    @Test
    fun naturallyReachableMoonCarpIntermediateTensionRestores() {
        val moonSeed = (0L..100L).first {
            selectedFish(it, quality = 0.8f, failureStreak = 0, difficulty = 1) ==
                FishingFish.MOON_CARP
        }
        val game = start("moon-tension", moonSeed)
        castAndOpenHook(game)
        enqueueAndAdvance(
            game,
            1,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        var sequence = 2L
        while (!(game.snapshot.phase == FishingPhase.TENSION && game.snapshot.tension == 49)) {
            val type = when (game.snapshot.phase) {
                FishingPhase.REELING -> MotionType.FISH_REEL_CYCLE
                FishingPhase.TENSION -> MotionType.FISH_TENSION_LEFT
                else -> error("Unexpected Moon Carp phase ${game.snapshot.phase}")
            }
            enqueueAndAdvance(game, sequence++, type, tick = game.snapshot.simulationTick)
        }

        val restored = FishingGameSession.restore(game.checkpoint())

        assertEquals(49, restored.snapshot.tension)
        assertEquals(FishingPhase.TENSION, restored.snapshot.phase)
        assertTrue(restored.snapshot.paused)
    }

    @Test
    fun queueOverflowPausesAtNextTickWithoutApplyingPartialInputs() {
        val game = start("overflow", 1L)
        repeat(FishingGameSession.INPUT_QUEUE_CAPACITY) { sequence ->
            assertTrue(
                game.acceptCurrent(
                    event("overflow", sequence.toLong(), MotionType.FISH_HOOK, tick = 0),
                ) is FishingInputResult.Queued,
            )
        }

        assertRejected(
            FishingInputRejection.QUEUE_OVERFLOW,
            game.acceptCurrent(
                event(
                    "overflow",
                    FishingGameSession.INPUT_QUEUE_CAPACITY.toLong(),
                    MotionType.FISH_HOOK,
                    tick = 0,
                ),
            ),
        )
        assertEquals(FishingPhase.READY, game.snapshot.phase)
        game.advanceTicks(1)
        assertEquals(SessionStatus.PAUSED, game.snapshot.status)
        assertEquals(PauseReason.EVENT_QUEUE_OVERFLOW, game.snapshot.pauseReason)
        assertEquals(-1L, game.snapshot.lastAppliedSequence)
    }

    @Test
    fun sameSeedAndReplayAdvancePrngStateDeterministically() {
        val first = completeSession(InputSource.FIXTURE, 10L)
        val replay = completeSession(InputSource.FIXTURE, 10L)
        val other = (11L..100L)
            .map { completeSession(InputSource.FIXTURE, it) }
            .first { it.fish != first.fish }

        assertEquals(first, replay)
        assertNotEquals(10L, first.prngState)
        assertNotEquals(first.fish, other.fish)
    }

    private fun selectedFish(
        seed: Long,
        quality: Float,
        failureStreak: Int,
        difficulty: Int,
    ): FishingFish {
        val sessionId = "selection-$seed-$quality-$failureStreak-$difficulty"
        val game = start(
            sessionId = sessionId,
            seed = seed,
            failureStreak = failureStreak,
            difficulty = difficulty,
        )
        enqueueAndAdvance(game, 0, MotionType.FISH_CAST, 0, quality = quality)
        return requireNotNull(game.snapshot.fish)
    }

    private fun completeSession(source: InputSource, seed: Long): FishingSnapshot {
        val sessionId = "complete-$seed"
        val game = start(sessionId, seed)
        completeFromStart(game, source)
        return game.snapshot
    }

    private fun completeFromStart(
        game: FishingGameSession,
        source: InputSource = InputSource.MOTION,
    ) {
        castAndOpenHook(game, source)
        enqueueAndAdvance(
            game,
            1,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
            source = source,
        )
        completeFromCurrent(game, 2, source)
    }

    private fun completeUntilNetting(game: FishingGameSession) {
        castAndOpenHook(game)
        enqueueAndAdvance(
            game,
            1,
            MotionType.FISH_HOOK,
            tick = game.snapshot.simulationTick,
        )
        var sequence = 2L
        while (game.snapshot.phase != FishingPhase.NETTING) {
            val type = when (game.snapshot.phase) {
                FishingPhase.REELING -> MotionType.FISH_REEL_CYCLE
                FishingPhase.TENSION -> MotionType.FISH_TENSION_LEFT
                else -> error("Unexpected continuation phase ${game.snapshot.phase}")
            }
            enqueueAndAdvance(game, sequence++, type, tick = game.snapshot.simulationTick)
        }
    }

    private fun completeFromCurrent(
        game: FishingGameSession,
        nextSequence: Long,
        source: InputSource = InputSource.MOTION,
    ) {
        var sequence = nextSequence
        while (game.snapshot.phase != FishingPhase.NETTING) {
            val type = when (game.snapshot.phase) {
                FishingPhase.REELING -> MotionType.FISH_REEL_CYCLE
                FishingPhase.TENSION -> MotionType.FISH_TENSION_LEFT
                else -> error("Unexpected continuation phase ${game.snapshot.phase}")
            }
            enqueueAndAdvance(
                game,
                sequence++,
                type,
                tick = game.snapshot.simulationTick,
                source = source,
            )
        }
        enqueueAndAdvance(
            game,
            sequence,
            MotionType.FISH_NET,
            tick = game.snapshot.simulationTick,
            source = source,
        )
    }

    private fun castAndOpenHook(
        game: FishingGameSession,
        source: InputSource = InputSource.MOTION,
    ) {
        enqueueAndAdvance(game, 0, MotionType.FISH_CAST, tick = 0, source = source)
        advanceTo(game, requireNotNull(game.snapshot.biteAtTick))
        assertEquals(FishingPhase.HOOK_WINDOW, game.snapshot.phase)
    }

    private fun settleCurrentHookWindow(game: FishingGameSession) {
        val settleTick = requireNotNull(game.snapshot.hookDeadlineTick) +
            FishingGameSession.MAX_EVENT_LATENESS_TICKS + 1L
        advanceTo(game, settleTick)
    }

    private fun assertProvenanceRoundTrip(checkpoint: FishingSnapshot) {
        val restored = FishingGameSession.restore(checkpoint).checkpoint()
        assertEquals(
            checkpoint.acceptedEventTimestampWatermarkNs,
            restored.acceptedEventTimestampWatermarkNs,
        )
        assertEquals(
            checkpoint.lastAppliedEventTimelineBaseNs,
            restored.lastAppliedEventTimelineBaseNs,
        )
        assertEquals(
            checkpoint.lastAppliedEventTimelineSimulationBaseTick,
            restored.lastAppliedEventTimelineSimulationBaseTick,
        )
        assertEquals(
            checkpoint.lastStateChangingEventTimelineBaseNs,
            restored.lastStateChangingEventTimelineBaseNs,
        )
        assertEquals(
            checkpoint.lastStateChangingEventTimelineSimulationBaseTick,
            restored.lastStateChangingEventTimelineSimulationBaseTick,
        )
    }

    @Test
    fun cameraRecalibrationPreservesStateDropsQueueAndRejectsOldRevision() {
        val game = start("fish-camera-switch", seed = 43L)
        val before = game.snapshot
        assertTrue(
            game.acceptCurrent(eventFor(game, 0L, MotionType.FISH_CAST, tick = 1L))
                is FishingInputResult.Queued,
        )

        val recalibrated = game.recalibrate(2)

        assertEquals(before.simulationTick, recalibrated.simulationTick)
        assertEquals(before.score, recalibrated.score)
        assertEquals(before.phase, recalibrated.phase)
        assertEquals(2, recalibrated.calibrationRevision)
        assertEquals(PauseReason.CAMERA_SWITCH, recalibrated.pauseReason)
        assertTrue(game.resume(1_000_000_000L))
        game.advanceTicks(2)
        assertEquals(before.phase, game.snapshot.phase)
        assertRejected(
            FishingInputRejection.CONTRACT_REJECTED,
            game.acceptCurrent(
                eventFor(game, 1L, MotionType.FISH_CAST, game.snapshot.simulationTick + 1L),
            ),
        )
        assertTrue(
            game.acceptCurrent(
                eventFor(game, 1L, MotionType.FISH_CAST, game.snapshot.simulationTick + 1L)
                    .copy(calibrationRevision = 2),
            ) is FishingInputResult.Queued,
        )
        assertTrue(runCatching { game.recalibrate(2) }.isFailure)
    }

    private fun enqueueAndAdvance(
        game: FishingGameSession,
        sequence: Long,
        type: MotionType,
        tick: Long,
        source: InputSource = InputSource.MOTION,
        quality: Float = 0.8f,
    ) {
        val result = game.acceptCurrent(eventFor(game, sequence, type, tick, source, quality))
        assertTrue("Expected queued input, got $result", result is FishingInputResult.Queued)
        val scheduled = (result as FishingInputResult.Queued).scheduledTick
        val ticks = (scheduled - game.snapshot.simulationTick).coerceAtLeast(1L)
        game.advanceTicks(ticks.toInt())
    }

    private fun advanceTo(game: FishingGameSession, tick: Long) {
        require(tick >= game.snapshot.simulationTick)
        while (game.snapshot.simulationTick < tick && game.snapshot.phase != FishingPhase.RESULT) {
            val remaining = tick - game.snapshot.simulationTick
            game.advanceTicks(
                remaining.coerceAtMost(FishingGameSession.MAX_CATCH_UP_TICKS.toLong()).toInt(),
            )
        }
    }

    private fun event(
        sessionId: String,
        sequence: Long,
        type: MotionType,
        tick: Long,
        source: InputSource = InputSource.MOTION,
        quality: Float = 0.8f,
    ): MotionEventEnvelope = MotionEventEnvelope(
        eventId = when (val id = DeterministicEventId.create(sessionId, PlayerId.P1, sequence)) {
            is ContractResult.Valid -> id.value
            is ContractResult.Invalid -> error("Test session ID must be valid")
        },
        sessionId = sessionId,
        playerId = PlayerId.P1,
        sequenceNumber = sequence,
        type = type,
        quality = quality,
        confidence = 0.9f,
        eventTimestampNs = Math.multiplyExact(tick, FishingGameSession.FIXED_STEP_NS),
        calibrationRevision = 1,
        source = source,
        metadata = emptyMap(),
    )

    private fun FishingGameSession.acceptCurrent(
        event: MotionEventEnvelope,
    ): FishingInputResult = accept(event, snapshot.eventTimelineEpoch)

    private fun eventFor(
        game: FishingGameSession,
        sequence: Long,
        type: MotionType,
        tick: Long,
        source: InputSource = InputSource.MOTION,
        quality: Float = 0.8f,
    ): MotionEventEnvelope {
        val timeline = game.snapshot
        require(tick >= timeline.eventTimelineSimulationBaseTick)
        val timestampNs = Math.addExact(
            timeline.eventTimelineBaseNs,
            Math.multiplyExact(
                tick - timeline.eventTimelineSimulationBaseTick,
                FishingGameSession.FIXED_STEP_NS,
            ),
        )
        return event(
            sessionId = timeline.sessionId,
            sequence = sequence,
            type = type,
            tick = 0L,
            source = source,
            quality = quality,
        ).copy(eventTimestampNs = timestampNs)
    }

    private fun assertRejected(
        expected: FishingInputRejection,
        actual: FishingInputResult,
    ) {
        assertEquals(expected, (actual as FishingInputResult.Rejected).reason)
    }

    private fun start(
        sessionId: String,
        seed: Long,
        failureStreak: Int = 0,
        difficulty: Int = 1,
        originNs: Long = 0L,
    ): FishingGameSession = FishingGameSession.start(
        sessionId = sessionId,
        seed = seed,
        calibrationRevision = 1,
        eventTimelineOriginNs = originNs,
        failureStreak = failureStreak,
        regionDifficulty = difficulty,
    )
}
