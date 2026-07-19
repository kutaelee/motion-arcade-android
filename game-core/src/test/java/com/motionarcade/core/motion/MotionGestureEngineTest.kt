package com.motionarcade.core.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionGestureEngineTest {
    @Test
    fun heldGestureEmitsExactlyOnceAtDifferentFrameRates() {
        listOf(15, 30, 60).forEach { framesPerSecond ->
            val engine = engine()
            val interval = 1_000_000_000L / framesPerSecond
            val events = (0..(framesPerSecond * 3)).mapNotNull { frame ->
                engine.processOne(sample(frame * interval, activation = 0.9f)).eventOrNull()
            }
            assertEquals("fps=$framesPerSecond", 1, events.size)
            assertEquals(0L, events.single().sequenceNumber)
        }
    }

    @Test
    fun continuousNeutralRearmsThenSecondCycleEmitsOnce() {
        val engine = engine()
        val results = listOf(
            sample(0L, 0.9f),
            sample(120_000_000L, 0.9f),
            sample(200_000_000L, 0.9f),
            sample(300_000_000L, 0.1f),
            sample(500_000_000L, 0.1f),
            sample(600_000_000L, 0.9f),
            sample(720_000_000L, 0.9f),
        ).map { engine.processOne(it) }

        assertEquals(listOf(0L, 1L), results.mapNotNull { it.eventOrNull()?.sequenceNumber })
    }

    @Test
    fun simultaneousPlayersKeepIndependentSequencesAndEvents() {
        val engine = engine()
        engine.processTargets(
            sample(0L, 0.9f, PlayerId.P1),
            sample(0L, 0.9f, PlayerId.P2),
        )
        val confirmed = engine.processTargets(
            sample(120_000_000L, 0.9f, PlayerId.P1),
            sample(120_000_000L, 0.9f, PlayerId.P2),
        )
        val p1 = confirmed[0].eventOrNull()
        val p2 = confirmed[1].eventOrNull()

        assertEquals(0L, requireNotNull(p1).sequenceNumber)
        assertEquals(0L, requireNotNull(p2).sequenceNumber)
        assertEquals("slice-2a/P1/0", p1.eventId)
        assertEquals("slice-2a/P2/0", p2.eventId)
    }

    @Test
    fun nonMonotonicAndWrongCalibrationSamplesFailClosed() {
        val engine = engine()
        engine.processOne(sample(10L, 0.9f))

        assertEquals(
            MotionSampleRejection.NON_MONOTONIC_TIMESTAMP,
            (engine.processOne(sample(10L, 0.9f)) as MotionSampleResult.Rejected).reason,
        )
        assertEquals(
            MotionSampleRejection.CALIBRATION_REVISION_MISMATCH,
            (engine.processOne(sample(20L, 0.9f).copy(calibrationRevision = 2)) as
                MotionSampleResult.Rejected).reason,
        )
    }

    @Test
    fun calibrationAdvanceCancelsCandidateAndPreservesEventSequence() {
        val engine = engine()
        engine.processOne(sample(0L, 0.9f))
        assertTrue(engine.advanceCalibrationRevision(2))
        assertEquals(
            MotionSampleRejection.CALIBRATION_REVISION_MISMATCH,
            (engine.processOne(sample(120_000_000L, 0.9f)) as MotionSampleResult.Rejected).reason,
        )
        engine.processOne(sample(130_000_000L, 0.9f).copy(calibrationRevision = 2))
        val event = engine.processOne(
            sample(250_000_000L, 0.9f).copy(calibrationRevision = 2),
        ).eventOrNull()
        assertEquals(0L, requireNotNull(event).sequenceNumber)
        assertEquals(2, event.calibrationRevision)
    }

    @Test
    fun developmentCatalogExercisesAtLeastSixDistinctGestures() {
        val events = MotionType.entries.mapIndexedNotNull { index, type ->
            val engine = MotionGestureEngine(
                "catalog-$index",
                DevelopmentGestureDefinitions.all,
                1,
            )
            val start = 0L
            engine.processOne(sample(start, 0.9f).copy(type = type))
            engine.processOne(sample(start + 120_000_000L, 0.9f).copy(type = type)).eventOrNull()
        }
        assertTrue(events.map { it.type }.toSet().size >= 6)
    }

    @Test
    fun developmentCatalogMapsEveryFishingGestureToOneExclusiveCandidateGroup() {
        val fishingTypes = setOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )
        val fishingDefinitions = DevelopmentGestureDefinitions.all.filter {
            it.type in fishingTypes
        }

        assertEquals(fishingTypes, fishingDefinitions.map { it.type }.toSet())
        assertTrue(fishingDefinitions.all { it.exclusivityGroup == "FISHING_ARMS" })
    }

    @Test
    fun sixtySecondNeutralAndHardNegativeEmitNothing() {
        val engine = engine()
        val step = 100_000_000L
        val events = (0L..60_000_000_000L step step).mapNotNull { timestamp ->
            val activation = if ((timestamp / step) % 2L == 0L) 0.2f else 0.7f
            engine.processOne(sample(timestamp, activation, source = InputSource.FIXTURE)).eventOrNull()
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun lowConfidenceCannotCountAsNeutralOrRearm() {
        val engine = engine()
        engine.processOne(sample(0L, 0.9f))
        assertTrue(engine.processOne(sample(120_000_000L, 0.9f)) is MotionSampleResult.Emitted)
        engine.processOne(sample(300_000_000L, 0.1f).copy(confidence = 0.1f))
        engine.processOne(sample(500_000_000L, 0.1f).copy(confidence = 0.1f))
        assertEquals(
            GesturePhase.COOLDOWN,
            (engine.processOne(sample(600_000_000L, 0.9f)) as MotionSampleResult.Advanced).phase,
        )
        engine.processOne(sample(700_000_000L, 0.1f))
        engine.processOne(sample(900_000_000L, 0.1f))
        engine.processOne(sample(1_000_000_000L, 0.9f))
        val second = engine.processOne(sample(1_120_000_000L, 0.9f)).eventOrNull()
        assertEquals(1L, requireNotNull(second).sequenceNumber)
    }

    @Test
    fun exclusiveSignalsEmitOneDeterministicWinnerPerCycle() {
        val engine = engine()
        val cast = sample(0L, 0.9f).copy(type = MotionType.FISH_CAST)
        val reel = sample(0L, 0.9f).copy(type = MotionType.FISH_REEL_CYCLE)
        engine.processTargets(reel, cast)
        val confirmed = engine.processTargets(
            reel.copy(timestampNs = 120_000_000L),
            cast.copy(timestampNs = 120_000_000L),
        )

        val events = confirmed.mapNotNull { it.eventOrNull() }
        assertEquals(1, events.size)
        assertEquals(MotionType.FISH_CAST, events.single().type)
    }

    @Test
    fun calibrationAdvanceDoesNotPermitPlayerTimestampRollback() {
        val engine = engine()
        engine.processOne(sample(1_000_000_000L, 0.9f))
        assertTrue(engine.advanceCalibrationRevision(2))

        assertEquals(
            MotionSampleRejection.NON_MONOTONIC_TIMESTAMP,
            (
                engine.processOne(
                    sample(100_000_000L, 0.9f).copy(calibrationRevision = 2),
                ) as MotionSampleResult.Rejected
            ).reason,
        )
    }

    @Test
    fun duplicateTypeRejectsWholePlayerFrameWithoutMutatingStateOrOtherPlayer() {
        val engine = engine()
        val p1Hook = sample(0L, 0.9f, PlayerId.P1).copy(type = MotionType.PUNCH_HOOK)
        val p1Jab = sample(0L, 0.9f, PlayerId.P1).copy(type = MotionType.PUNCH_JAB)
        val p2Cast = sample(0L, 0.9f, PlayerId.P2)
        val malformed = completeFrame(listOf(p1Hook, p2Cast, p1Jab)).toMutableList().apply {
            add(2, p1Hook)
        }

        val rejected = engine.processFrame(malformed)

        malformed.zip(rejected).filter { (sample, _) -> sample.playerId == PlayerId.P1 }
            .forEach { (_, result) ->
                assertEquals(
                    MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE),
                    result,
                )
            }
        assertEquals(MotionSampleResult.Advanced(GesturePhase.CANDIDATE), rejected[1])

        assertEquals(
            MotionSampleResult.Advanced(GesturePhase.CANDIDATE),
            engine.processOne(p1Hook),
        )
        assertTrue(
            engine.processOne(p2Cast.copy(timestampNs = 120_000_000L)) is
                MotionSampleResult.Emitted,
        )
        val p1Event = engine.processOne(p1Hook.copy(timestampNs = 120_000_000L)).eventOrNull()
        assertEquals(MotionType.PUNCH_HOOK, requireNotNull(p1Event).type)
    }

    @Test
    fun sparsePlayerFrameIsRejectedWithoutLockingExclusiveOwnerOrWatermark() {
        val engine = engine()
        val cast = sample(0L, 0.9f)

        assertEquals(
            MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE),
            engine.processFrame(listOf(cast)).single(),
        )
        assertEquals(
            MotionSampleResult.Advanced(GesturePhase.CANDIDATE),
            engine.processOne(cast),
        )
        assertTrue(
            engine.processOne(cast.copy(timestampNs = 120_000_000L)) is
                MotionSampleResult.Emitted,
        )

        val sparseReel = cast.copy(
            timestampNs = 300_000_000L,
            type = MotionType.FISH_REEL_CYCLE,
        )
        assertEquals(
            MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE),
            engine.processFrame(listOf(sparseReel)).single(),
        )
        engine.processOne(cast.copy(timestampNs = 300_000_000L, activation = 0.1f))
        engine.processOne(cast.copy(timestampNs = 500_000_000L, activation = 0.1f))
        val reel = cast.copy(timestampNs = 600_000_000L, type = MotionType.FISH_REEL_CYCLE)
        assertEquals(
            MotionSampleResult.Advanced(GesturePhase.CANDIDATE),
            engine.processOne(reel),
        )
        val event = engine.processOne(reel.copy(timestampNs = 720_000_000L)).eventOrNull()
        assertEquals(MotionType.FISH_REEL_CYCLE, requireNotNull(event).type)
    }

    @Test
    fun mixedTimestampFrameIsAtomicAndDoesNotPoisonWatermark() {
        val engine = engine()
        val cast = sample(10L, 0.9f)
        val malformed = completeFrame(listOf(cast)).mapIndexed { index, signal ->
            if (index == 1) signal.copy(timestampNs = 11L) else signal
        }

        assertTrue(
            engine.processFrame(malformed).all {
                it == MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
            },
        )
        assertEquals(
            MotionSampleResult.Advanced(GesturePhase.CANDIDATE),
            engine.processOne(cast),
        )
        assertTrue(
            engine.processOne(cast.copy(timestampNs = 120_000_010L)) is
                MotionSampleResult.Emitted,
        )
    }

    @Test
    fun touchFallbackSharesMotionSequenceAndContractIdentity() {
        val engine = engine()
        engine.processOne(sample(0L, 0.9f))
        val cast = requireNotNull(
            engine.processOne(sample(120_000_000L, 0.9f)).eventOrNull(),
        )

        val reel = requireNotNull(
            engine.processTouch(
                playerId = PlayerId.P1,
                type = MotionType.FISH_REEL_CYCLE,
                timestampNs = 300_000_000L,
                quality = 0.75f,
                metadata = mapOf("buttonHoldMs" to 80f),
            ).eventOrNull(),
        )

        assertEquals(listOf(0L, 1L), listOf(cast.sequenceNumber, reel.sequenceNumber))
        assertEquals("slice-2a/P1/1", reel.eventId)
        assertEquals(InputSource.TOUCH, reel.source)
        assertEquals(0f, reel.metadata.getValue("holdMs"))
        assertEquals(80f, reel.metadata.getValue("buttonHoldMs"))
    }

    @Test
    fun touchFallbackOwnsThePlayerTimestampWatermark() {
        val engine = engine()
        assertTrue(
            engine.processTouch(
                playerId = PlayerId.P1,
                type = MotionType.FISH_CAST,
                timestampNs = 500_000_000L,
            ) is MotionSampleResult.Emitted,
        )

        assertEquals(
            MotionSampleRejection.NON_MONOTONIC_TIMESTAMP,
            (
                engine.processOne(sample(500_000_000L, 0.1f)) as
                    MotionSampleResult.Rejected
            ).reason,
        )
        assertEquals(
            MotionSampleRejection.NON_MONOTONIC_TIMESTAMP,
            (
                engine.processTouch(
                    playerId = PlayerId.P1,
                    type = MotionType.FISH_REEL_CYCLE,
                    timestampNs = 499_999_999L,
                ) as MotionSampleResult.Rejected
            ).reason,
        )
    }

    @Test
    fun touchFallbackBlocksRepeatedAndCrossGestureTapsUntilCooldownExpires() {
        val engine = engine()
        val first = engine.processTouch(PlayerId.P1, MotionType.FISH_CAST, 0L)
        val repeated = engine.processTouch(PlayerId.P1, MotionType.FISH_CAST, 1L)
        val crossGesture = engine.processTouch(
            PlayerId.P1,
            MotionType.FISH_HOOK,
            179_999_999L,
        )
        val rearmed = engine.processTouch(
            PlayerId.P1,
            MotionType.FISH_HOOK,
            180_000_000L,
        )

        assertEquals(0L, requireNotNull(first.eventOrNull()).sequenceNumber)
        assertEquals(MotionSampleResult.Advanced(GesturePhase.COOLDOWN), repeated)
        assertEquals(MotionSampleResult.Advanced(GesturePhase.COOLDOWN), crossGesture)
        assertEquals(1L, requireNotNull(rearmed.eventOrNull()).sequenceNumber)
    }

    @Test
    fun emittedMetadataBreaksCallerAliasAndRejectsPostGateMutation() {
        val engine = engine()
        val callerMetadata = linkedMapOf("buttonHoldMs" to 80f)
        val event = requireNotNull(
            engine.processTouch(
                playerId = PlayerId.P1,
                type = MotionType.FISH_CAST,
                timestampNs = 0L,
                metadata = callerMetadata,
            ).eventOrNull(),
        )
        callerMetadata["buttonHoldMs"] = 999f

        assertEquals(80f, event.metadata.getValue("buttonHoldMs"))
        @Suppress("UNCHECKED_CAST")
        val runtimeMutableView = event.metadata as MutableMap<String, Float>
        assertThrows(UnsupportedOperationException::class.java) {
            runtimeMutableView["postGate"] = 1f
        }
    }

    @Test
    fun touchFallbackRejectsInvalidPlayerQualityAndReservedMetadata() {
        val engine = engine()
        val results = listOf(
            engine.processTouch(PlayerId.AI, MotionType.FISH_CAST, 1L),
            engine.processTouch(PlayerId.P1, MotionType.FISH_CAST, 2L, quality = Float.NaN),
            engine.processTouch(
                PlayerId.P2,
                MotionType.FISH_CAST,
                3L,
                metadata = mapOf("activation" to 1f),
            ),
        )

        assertTrue(
            results.all {
                it == MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
            },
        )
    }

    @Test
    fun checkpointRearmPreservesSequenceAndRejectsPreRecoveryCallbacks() {
        val engine = engine()
        val first = engine.processTouch(PlayerId.P1, MotionType.FISH_CAST, 0L)
        assertEquals(0L, requireNotNull(first.eventOrNull()).sequenceNumber)

        assertTrue(
            engine.rearmAfterCheckpoint(
                playerId = PlayerId.P1,
                acceptedSequenceWatermark = 0L,
                timestampFenceNs = 1_000_000_000L,
            ),
        )
        assertEquals(
            MotionSampleRejection.NON_MONOTONIC_TIMESTAMP,
            (
                engine.processTouch(
                    PlayerId.P1,
                    MotionType.FISH_HOOK,
                    1_000_000_000L,
                ) as MotionSampleResult.Rejected
                ).reason,
        )
        val resumed = engine.processTouch(
            PlayerId.P1,
            MotionType.FISH_HOOK,
            1_000_000_001L,
        )
        assertEquals(1L, requireNotNull(resumed.eventOrNull()).sequenceNumber)
    }

    @Test
    fun checkpointRearmCannotRollbackSequenceTimestampOrAcceptAi() {
        val engine = engine()
        assertTrue(engine.rearmAfterCheckpoint(PlayerId.P1, 5L, 500L))

        assertEquals(false, engine.rearmAfterCheckpoint(PlayerId.P1, 4L, 600L))
        assertEquals(false, engine.rearmAfterCheckpoint(PlayerId.P1, 5L, 499L))
        assertEquals(false, engine.rearmAfterCheckpoint(PlayerId.AI, 5L, 600L))

        val resumed = engine.processTouch(PlayerId.P1, MotionType.FISH_CAST, 601L)
        assertEquals(6L, requireNotNull(resumed.eventOrNull()).sequenceNumber)
    }

    @Test
    fun checkpointRearmRequiresACompleteContinuousNeutralFrameBeforeMotion() {
        val engine = engine()
        assertTrue(engine.rearmAfterCheckpoint(PlayerId.P1, -1L, 1_000_000_000L))
        assertEquals(false, engine.isNeutralRearmComplete(PlayerId.P1))

        val heldHigh = sample(1_000_000_001L, 0.9f)
        assertTrue(
            engine.processTargets(heldHigh).none { it is MotionSampleResult.Emitted },
        )
        assertTrue(
            engine.processTargets(heldHigh.copy(timestampNs = 1_300_000_000L))
                .none { it is MotionSampleResult.Emitted },
        )

        val neutralStart = sample(1_400_000_000L, 0.1f)
        assertTrue(
            engine.processTargets(neutralStart).all {
                it == MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
            },
        )
        assertTrue(
            engine.processTargets(neutralStart.copy(timestampNs = 1_519_999_999L)).all {
                it == MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
            },
        )
        assertEquals(false, engine.isNeutralRearmComplete(PlayerId.P1))
        assertTrue(
            engine.processTargets(neutralStart.copy(timestampNs = 1_520_000_000L)).all {
                it == MotionSampleResult.Advanced(GesturePhase.IDLE)
            },
        )
        assertTrue(engine.isNeutralRearmComplete(PlayerId.P1))

        assertEquals(
            MotionSampleResult.Advanced(GesturePhase.CANDIDATE),
            engine.processOne(sample(1_520_000_001L, 0.9f)),
        )
        val emitted = engine.processOne(sample(1_640_000_001L, 0.9f)).eventOrNull()
        assertEquals(0L, requireNotNull(emitted).sequenceNumber)
    }

    @Test
    fun touchFallbackRemainsAvailableWhileMotionNeutralRearmIsRequired() {
        val engine = engine()
        assertTrue(engine.rearmAfterCheckpoint(PlayerId.P1, -1L, 1_000_000_000L))

        val touch = engine.processTouch(
            PlayerId.P1,
            MotionType.FISH_CAST,
            1_000_000_001L,
        )
        assertEquals(0L, requireNotNull(touch.eventOrNull()).sequenceNumber)

        assertTrue(
            engine.processTargets(sample(1_300_000_000L, 0.9f))
                .none { it is MotionSampleResult.Emitted },
        )
    }

    private fun engine(): MotionGestureEngine = MotionGestureEngine(
        sessionId = "slice-2a",
        definitions = DevelopmentGestureDefinitions.all,
        activeCalibrationRevision = 1,
    )

    private fun sample(
        timestampNs: Long,
        activation: Float,
        playerId: PlayerId = PlayerId.P1,
        source: InputSource = InputSource.MOTION,
    ) = MotionSignalSample(
        playerId = playerId,
        type = MotionType.FISH_CAST,
        timestampNs = timestampNs,
        activation = activation,
        quality = 0.8f,
        confidence = 0.9f,
        calibrationRevision = 1,
        source = source,
    )

    private fun MotionSampleResult.eventOrNull() =
        (this as? MotionSampleResult.Emitted)?.event

    private fun MotionGestureEngine.processOne(sample: MotionSignalSample): MotionSampleResult =
        processTargets(sample).single()

    private fun MotionGestureEngine.processTargets(
        vararg targetSamples: MotionSignalSample,
    ): List<MotionSampleResult> =
        processFrame(completeFrame(targetSamples.toList())).take(targetSamples.size)

    private fun completeFrame(
        targetSamples: List<MotionSignalSample>,
    ): List<MotionSignalSample> {
        require(targetSamples.isNotEmpty())
        val fillers = targetSamples.groupBy(MotionSignalSample::playerId).flatMap { (_, targets) ->
            val template = targets.first()
            require(targets.map(MotionSignalSample::type).distinct().size == targets.size)
            require(targets.all { it.timestampNs == template.timestampNs })
            require(targets.all { it.calibrationRevision == template.calibrationRevision })
            val targetTypes = targets.mapTo(mutableSetOf(), MotionSignalSample::type)
            DevelopmentGestureDefinitions.all.mapNotNull { definition ->
                if (definition.type in targetTypes) {
                    null
                } else {
                    template.copy(
                        type = definition.type,
                        activation = 0.1f,
                        quality = 1f,
                        confidence = 1f,
                        source = InputSource.FIXTURE,
                        metadata = emptyMap(),
                    )
                }
            }
        }
        return targetSamples + fillers
    }
}
