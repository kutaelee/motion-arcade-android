package com.motionarcade.vision.capability.domain

import com.motionarcade.core.contract.GameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeWindowTest {
    @Test
    fun nearestRankCertificatesAreIntegerOnlyAndExactWithDuplicates() {
        val values = (1L..100L).toMutableList().apply {
            this[0] = 2L
        }
        val distribution = valid(IntegerDistributionPolicy.reduce(values))

        assertEquals(100L, distribution.count)
        assertEquals(RankCertificate(50L, 49L, 1L), distribution.p50)
        assertEquals(RankCertificate(90L, 89L, 1L), distribution.p90)
        assertEquals(RankCertificate(95L, 94L, 1L), distribution.p95)
        assertEquals(RankCertificate(99L, 98L, 1L), distribution.p99)
        assertEquals(RankCertificate(100L, 99L, 1L), distribution.maximum)
        assertTrue(IntegerDistributionPolicy.reduce(emptyList()) is CapabilityDomainResult.Invalid)
        assertTrue(IntegerDistributionPolicy.reduce(listOf(-1L)) is CapabilityDomainResult.Invalid)

        val one = valid(IntegerDistributionPolicy.reduce(listOf(7L)))
        assertEquals(
            listOf(
                RankCertificate(7L, 0L, 1L),
                RankCertificate(7L, 0L, 1L),
                RankCertificate(7L, 0L, 1L),
                RankCertificate(7L, 0L, 1L),
                RankCertificate(7L, 0L, 1L),
            ),
            listOf(one.p50, one.p90, one.p95, one.p99, one.maximum),
        )
        val three = valid(IntegerDistributionPolicy.reduce(listOf(0L, 1L, 2L)))
        assertEquals(RankCertificate(1L, 1L, 1L), three.p50)
        assertEquals(RankCertificate(2L, 2L, 1L), three.p90)
        assertEquals(three.p90, three.p95)
        assertEquals(three.p90, three.p99)
        assertEquals(three.p90, three.maximum)
    }

    @Test
    fun callbackInterarrivalHasExplicitAbsentFormAndExactConservation() {
        assertEquals(
            CallbackInterarrivalDistributions(
                OptionalIntegerDistribution.Absent,
                OptionalIntegerDistribution.Absent,
            ),
            valid(ProbeCallbackTimingPolicy.reduce(emptyList())),
        )
        assertEquals(
            CallbackInterarrivalDistributions(
                OptionalIntegerDistribution.Absent,
                OptionalIntegerDistribution.Absent,
            ),
            valid(
                ProbeCallbackTimingPolicy.reduce(
                    listOf(
                        ProbeCallbackTiming(
                            windowAdmissionOrdinal = 0L,
                            receivedAtNs = 1_000L,
                            sourceTimestampNs = 900L,
                        ),
                    ),
                ),
            ),
        )

        val timings = listOf(
            ProbeCallbackTiming(0L, receivedAtNs = 1_000L, sourceTimestampNs = 900L),
            ProbeCallbackTiming(1L, receivedAtNs = 1_001L, sourceTimestampNs = 902L),
            ProbeCallbackTiming(2L, receivedAtNs = 1_003L, sourceTimestampNs = 905L),
            ProbeCallbackTiming(3L, receivedAtNs = 1_006L, sourceTimestampNs = 909L),
        )
        val forward = valid(ProbeCallbackTimingPolicy.reduce(timings))
        val reversed = valid(ProbeCallbackTimingPolicy.reduce(timings.reversed()))
        assertEquals(forward, reversed)

        val camera = (forward.camera as OptionalIntegerDistribution.Present).distribution
        val source = (forward.source as OptionalIntegerDistribution.Present).distribution
        assertEquals(3L, camera.count)
        assertEquals(2L, camera.p50.value)
        assertEquals(3L, camera.p90.value)
        assertEquals(3L, camera.maximum.value)
        assertEquals(3L, source.count)
        assertEquals(3L, source.p50.value)
        assertEquals(4L, source.p90.value)
        assertEquals(4L, source.maximum.value)
    }

    @Test
    fun equalReceivedTimeUsesAdmissionOrderWithoutInventingSourceChronology() {
        val decreasingInAdmissionOrder = listOf(
            ProbeCallbackTiming(0L, receivedAtNs = 1_000L, sourceTimestampNs = 900L),
            ProbeCallbackTiming(1L, receivedAtNs = 1_000L, sourceTimestampNs = 800L),
        )
        val forward = ProbeCallbackTimingPolicy.reduce(decreasingInAdmissionOrder)
        val reversedInput = ProbeCallbackTimingPolicy.reduce(decreasingInAdmissionOrder.reversed())
        assertTrue(forward is CapabilityDomainResult.Invalid)
        assertEquals(forward, reversedInput)

        val validEqualReceived = listOf(
            ProbeCallbackTiming(0L, receivedAtNs = 1_000L, sourceTimestampNs = 800L),
            ProbeCallbackTiming(1L, receivedAtNs = 1_000L, sourceTimestampNs = 900L),
        )
        val validResult = valid(ProbeCallbackTimingPolicy.reduce(validEqualReceived.reversed()))
        val camera = (validResult.camera as OptionalIntegerDistribution.Present).distribution
        val source = (validResult.source as OptionalIntegerDistribution.Present).distribution
        assertEquals(1L, camera.count)
        assertEquals(0L, camera.maximum.value)
        assertEquals(RankCertificate(0L, 0L, 1L), camera.maximum)
        assertEquals(1L, source.count)
        assertEquals(100L, source.maximum.value)
    }

    @Test
    fun admissionOrdinalMustBeNonnegativeUniqueAndStrictlyContiguous() {
        val duplicate = listOf(
            ProbeCallbackTiming(0L, receivedAtNs = 1_000L, sourceTimestampNs = 800L),
            ProbeCallbackTiming(0L, receivedAtNs = 1_001L, sourceTimestampNs = 900L),
        )
        val gap = listOf(
            ProbeCallbackTiming(0L, receivedAtNs = 1_000L, sourceTimestampNs = 800L),
            ProbeCallbackTiming(2L, receivedAtNs = 1_001L, sourceTimestampNs = 900L),
        )
        val negative = listOf(
            ProbeCallbackTiming(-1L, receivedAtNs = 999L, sourceTimestampNs = 700L),
            ProbeCallbackTiming(0L, receivedAtNs = 1_000L, sourceTimestampNs = 800L),
        )

        listOf(duplicate, gap, negative).forEach { invalidTimings ->
            assertTrue(
                ProbeCallbackTimingPolicy.reduce(invalidTimings) is CapabilityDomainResult.Invalid,
            )
        }
    }

    @Test
    fun duplicateOrDecreasingSourceSequenceCannotTier() {
        val normal = samples(count = 100, mode = GameMode.SOLO)
        val duplicate = normal.toMutableList().apply {
            this[1] = this[1].copy(sourceTimestampNs = this[0].sourceTimestampNs)
        }
        val duplicateResult = evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, duplicate)
        assertIncomplete(
            duplicateResult,
            ProbeWindowIncompleteReason.INVALID_CALLBACK_TIMING,
        )
        assertFalse(
            ProbeWindowIncompleteReason.SAMPLE_CORRELATION_FAILED in
                (duplicateResult as ProbeWindowEvaluation.Incomplete).reasons,
        )

        val decreasing = normal.toMutableList().apply {
            this[1] = this[1].copy(sourceTimestampNs = this[0].sourceTimestampNs - 1L)
        }
        val decreasingResult = evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, decreasing)
        assertIncomplete(
            decreasingResult,
            ProbeWindowIncompleteReason.INVALID_CALLBACK_TIMING,
        )
        assertFalse(
            ProbeWindowIncompleteReason.SAMPLE_CORRELATION_FAILED in
                (decreasingResult as ProbeWindowEvaluation.Incomplete).reasons,
        )
    }

    @Test
    fun callbackTimingCountAndCompletedSampleCorrelationAreConserved() {
        val completed = samples(count = 4, mode = GameMode.SOLO)
        val missingTiming = ProbeWindowPolicy.evaluate(
            mode = GameMode.SOLO,
            kind = ProbeWindowKind.CANDIDATE,
            startNs = START_NS,
            endNs = START_NS + ProbeWindowKind.CANDIDATE.durationNs,
            counts = counts(completed.size),
            callbackTimings = timings(completed).dropLast(1),
            samples = completed,
        )
        assertIncomplete(
            missingTiming,
            ProbeWindowIncompleteReason.CALLBACK_TIMING_COUNT_MISMATCH,
        )

        val callbackTimings = timings(completed)
        val uncorrelated = completed.toMutableList().apply {
            this[0] = this[0].copy(windowAdmissionOrdinal = 99L)
        }
        val correlationResult = ProbeWindowPolicy.evaluate(
            mode = GameMode.SOLO,
            kind = ProbeWindowKind.CANDIDATE,
            startNs = START_NS,
            endNs = START_NS + ProbeWindowKind.CANDIDATE.durationNs,
            counts = counts(completed.size),
            callbackTimings = callbackTimings,
            samples = uncorrelated,
        )
        assertIncomplete(
            correlationResult,
            ProbeWindowIncompleteReason.SAMPLE_CORRELATION_FAILED,
        )
    }

    @Test
    fun jointWitnessWriterEmitsExactBoundedPartitionAndStrictValidatorAcceptsIt() {
        val quartiles = listOf(
            listOf(0L, 1L),
            listOf(1L, 2L),
            listOf(2L, 3L),
            listOf(3L, 4L),
        )
        val distribution = valid(IntegerDistributionPolicy.reduce(quartiles.flatten()))
        val q1Median = valid(IntegerDistributionPolicy.reduce(quartiles[0])).p50
        val q4Median = valid(IntegerDistributionPolicy.reduce(quartiles[3])).p50
        val growth = q4Median.value - q1Median.value
        val witness = valid(
            RepresentativeFrameAgeJointWitnessPolicy.write(
                quartileValues = quartiles,
                representativeDistribution = distribution,
                q1Median = q1Median,
                q4Median = q4Median,
                frameAgeGrowthNs = growth,
            ),
        )

        assertEquals(listOf(0L, 2L, 3L, 4L), witness.checkpointValues)
        assertEquals(
            listOf(
                listOf(0L, 1L, 1L, 0L, 0L, 0L, 0L, 0L, 0L),
                listOf(0L, 0L, 1L, 1L, 0L, 0L, 0L, 0L, 0L),
                listOf(0L, 0L, 0L, 1L, 0L, 1L, 0L, 0L, 0L),
                listOf(0L, 0L, 0L, 0L, 0L, 1L, 0L, 1L, 0L),
            ),
            witness.quartileRows,
        )
        assertTrue(
            RepresentativeFrameAgeJointWitnessPolicy.validate(
                witness = witness,
                representativeDistribution = distribution,
                quartileCounts = QuartileCounts(2L, 2L, 2L, 2L),
                q1Median = q1Median,
                q4Median = q4Median,
                frameAgeGrowthNs = growth,
            ) is CapabilityDomainResult.Valid,
        )
    }

    @Test
    fun jointWitnessRejectsImpossibleQ1AndReachabilityOrConservationMutation() {
        val quartiles = listOf(
            listOf(0L, 1L),
            listOf(1L, 2L),
            listOf(2L, 3L),
            listOf(3L, 4L),
        )
        val distribution = valid(IntegerDistributionPolicy.reduce(quartiles.flatten()))
        val q1Median = valid(IntegerDistributionPolicy.reduce(quartiles[0])).p50
        val q4Median = valid(IntegerDistributionPolicy.reduce(quartiles[3])).p50
        val witness = valid(
            RepresentativeFrameAgeJointWitnessPolicy.write(
                quartiles,
                distribution,
                q1Median,
                q4Median,
                q4Median.value - q1Median.value,
            ),
        )
        val counts = QuartileCounts(2L, 2L, 2L, 2L)

        val impossibleQ1 = RankCertificate(
            value = distribution.maximum.value + 1L,
            strictlyLessCount = 0L,
            equalCount = 1L,
        )
        assertTrue(
            RepresentativeFrameAgeJointWitnessPolicy.validate(
                witness,
                distribution,
                counts,
                impossibleQ1,
                q4Median,
                q4Median.value - impossibleQ1.value,
            ) is CapabilityDomainResult.Invalid,
        )

        val unreachableRows = witness.quartileRows.map { it.toMutableList() }.toMutableList()
        unreachableRows[2][3] = 0L
        unreachableRows[2][4] = 1L
        val unreachable = RepresentativeFrameAgeJointWitness(
            witness.checkpointValues,
            unreachableRows,
        )
        assertTrue(
            RepresentativeFrameAgeJointWitnessPolicy.validate(
                unreachable,
                distribution,
                counts,
                q1Median,
                q4Median,
                q4Median.value - q1Median.value,
            ) is CapabilityDomainResult.Invalid,
        )

        val nonConservingRows = witness.quartileRows.map { it.toMutableList() }.toMutableList()
        nonConservingRows[1][2] += 1L
        val nonConserving = RepresentativeFrameAgeJointWitness(
            witness.checkpointValues,
            nonConservingRows,
        )
        assertTrue(
            RepresentativeFrameAgeJointWitnessPolicy.validate(
                nonConserving,
                distribution,
                counts,
                q1Median,
                q4Median,
                q4Median.value - q1Median.value,
            ) is CapabilityDomainResult.Invalid,
        )
    }

    @Test
    fun jointWitnessValidatorRejectsShapeGlobalMedianMaximumAndGrowthContradictions() {
        val quartiles = listOf(
            listOf(0L, 1L),
            listOf(1L, 2L),
            listOf(2L, 3L),
            listOf(3L, 4L),
        )
        val distribution = valid(IntegerDistributionPolicy.reduce(quartiles.flatten()))
        val q1Median = valid(IntegerDistributionPolicy.reduce(quartiles[0])).p50
        val q4Median = valid(IntegerDistributionPolicy.reduce(quartiles[3])).p50
        val growth = q4Median.value - q1Median.value
        val counts = QuartileCounts(2L, 2L, 2L, 2L)
        val witness = valid(
            RepresentativeFrameAgeJointWitnessPolicy.write(
                quartiles,
                distribution,
                q1Median,
                q4Median,
                growth,
            ),
        )

        fun isInvalid(
            candidate: RepresentativeFrameAgeJointWitness = witness,
            candidateDistribution: IntegerDistribution = distribution,
            candidateQ1: RankCertificate = q1Median,
            candidateQ4: RankCertificate = q4Median,
            candidateGrowth: Long = growth,
        ): Boolean = RepresentativeFrameAgeJointWitnessPolicy.validate(
            candidate,
            candidateDistribution,
            counts,
            candidateQ1,
            candidateQ4,
            candidateGrowth,
        ) is CapabilityDomainResult.Invalid

        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    emptyList(),
                    witness.quartileRows,
                ),
            ),
        )
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    (0L..7L).toList(),
                    List(4) { List(17) { 0L } },
                ),
            ),
        )
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    witness.checkpointValues,
                    witness.quartileRows.dropLast(1),
                ),
            ),
        )
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    witness.checkpointValues,
                    witness.quartileRows.mapIndexed { index, row ->
                        if (index == 0) row.dropLast(1) else row
                    },
                ),
            ),
        )

        val globalContradictionRows = witness.quartileRows.map { it.toMutableList() }.toMutableList()
        globalContradictionRows[1][2] += 1L
        globalContradictionRows[1][3] -= 1L
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    witness.checkpointValues,
                    globalContradictionRows,
                ),
            ),
        )

        val q1ContradictionRows = witness.quartileRows.map { it.toMutableList() }.toMutableList()
        q1ContradictionRows[0][1] -= 1L
        q1ContradictionRows[0][2] += 1L
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    witness.checkpointValues,
                    q1ContradictionRows,
                ),
            ),
        )

        val q4ContradictionRows = witness.quartileRows.map { it.toMutableList() }.toMutableList()
        q4ContradictionRows[3][5] -= 1L
        q4ContradictionRows[3][3] += 1L
        q4ContradictionRows[2][5] += 1L
        q4ContradictionRows[2][3] -= 1L
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    witness.checkpointValues,
                    q4ContradictionRows,
                ),
            ),
        )

        val aboveMaximumRows = witness.quartileRows.map { it.toMutableList() }.toMutableList()
        aboveMaximumRows[3][7] -= 1L
        aboveMaximumRows[3][8] += 1L
        assertTrue(
            isInvalid(
                RepresentativeFrameAgeJointWitness(
                    witness.checkpointValues,
                    aboveMaximumRows,
                ),
            ),
        )
        assertTrue(isInvalid(candidateGrowth = growth + 1L))

        val impossibleP50 = distribution.copy(
            p50 = distribution.p50.copy(
                strictlyLessCount = 4L,
                equalCount = 1L,
            ),
        )
        assertTrue(isInvalid(candidateDistribution = impossibleP50))
    }

    @Test
    fun exactFiveAndTenSecondHalfOpenWindowsAndQuartileEdgesAreEnforced() {
        val edgeSamples = listOf(0L, 1_250_000_000L, 2_500_000_000L, 3_750_000_000L)
            .mapIndexed { index, offset ->
                sample(
                    windowAdmissionOrdinal = index.toLong(),
                    receivedAtNs = START_NS + offset,
                    poseCount = 1,
                )
            }
        val candidate = complete(
            evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, edgeSamples),
        )

        assertEquals(QuartileCounts(1L, 1L, 1L, 1L), candidate.summary.representativeQuartileCounts)
        assertEquals(MeasuredWindowOutcome.SOLO_BELOW_FLOOR, candidate.outcome)
        assertEquals(
            3L,
            (candidate.summary.cameraInterarrival as OptionalIntegerDistribution.Present)
                .distribution.count,
        )
        assertEquals(
            3L,
            (candidate.summary.sourceInterarrival as OptionalIntegerDistribution.Present)
                .distribution.count,
        )

        val steadySamples = samples(count = 200, mode = GameMode.SOLO, kind = ProbeWindowKind.SELECTED_STEADY)
        assertEquals(
            MeasuredWindowOutcome.SOLO_SUPPORT,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.SELECTED_STEADY, steadySamples)).outcome,
        )

        val atEnd = edgeSamples.dropLast(1) + sample(
            windowAdmissionOrdinal = 3L,
            receivedAtNs = START_NS + ProbeWindowKind.CANDIDATE.durationNs,
            poseCount = 1,
        )
        assertIncomplete(
            evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, atEnd),
            ProbeWindowIncompleteReason.INVALID_SAMPLE,
        )

        val wrongDuration = ProbeWindowPolicy.evaluate(
            mode = GameMode.SOLO,
            kind = ProbeWindowKind.CANDIDATE,
            startNs = START_NS,
            endNs = START_NS + ProbeWindowKind.CANDIDATE.durationNs + 1L,
            counts = counts(edgeSamples.size),
            callbackTimings = timings(edgeSamples),
            samples = edgeSamples,
        )
        assertIncomplete(wrongDuration, ProbeWindowIncompleteReason.INVALID_WINDOW)
    }

    @Test
    fun soloInclusiveRateAndTailThresholdsPassAndPlusOneFails() {
        val equality = samples(
            count = 100,
            mode = GameMode.SOLO,
            inferenceNs = { 50_000_000L },
            frameAgeNs = { quartile ->
                listOf(100_000_000L, 116_666_667L, 133_333_334L, 150_000_000L)[quartile - 1]
            },
        )
        val equalityResult = complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, equality))
        assertEquals(MeasuredWindowOutcome.SOLO_SUPPORT, equalityResult.outcome)
        assertEquals(50_000_000L, equalityResult.summary.representative.inferenceDurationNs.p95.value)
        assertEquals(50_000_000L, equalityResult.summary.frameAgeGrowthNs)

        val inferencePlusOne = samples(
            count = 100,
            mode = GameMode.SOLO,
            inferenceNs = { 50_000_001L },
        )
        assertEquals(
            MeasuredWindowOutcome.SOLO_BELOW_FLOOR,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, inferencePlusOne)).outcome,
        )

        val growthPlusOne = samples(
            count = 100,
            mode = GameMode.SOLO,
            frameAgeNs = { quartile ->
                listOf(100_000_000L, 116_666_667L, 133_333_334L, 150_000_001L)[quartile - 1]
            },
        )
        assertEquals(
            MeasuredWindowOutcome.SOLO_BELOW_FLOOR,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, growthPlusOne)).outcome,
        )

        val oneBelowRate = samples(count = 99, mode = GameMode.SOLO)
        assertEquals(
            MeasuredWindowOutcome.SOLO_BELOW_FLOOR,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, oneBelowRate)).outcome,
        )
    }

    @Test
    fun maximumRepresentativeAgeIsInclusiveAtOneSecondAndFailsAtPlusOne() {
        val equality = samples(
            count = 100,
            mode = GameMode.SOLO,
            frameAgeNs = { 1_000_000_000L },
        )
        assertEquals(
            MeasuredWindowOutcome.SOLO_SUPPORT,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, equality)).outcome,
        )

        val plusOne = samples(
            count = 100,
            mode = GameMode.SOLO,
            frameAgeNs = { 1_000_000_001L },
        )
        assertEquals(
            MeasuredWindowOutcome.SOLO_BELOW_FLOOR,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, plusOne)).outcome,
        )
    }

    @Test
    fun dualFullAndConditionalBoundariesUseExactRationalFps() {
        val fullEquality = samples(
            count = 75,
            mode = GameMode.DUAL,
            inferenceNs = { 66_666_667L },
            frameAgeNs = { quartile ->
                listOf(100_000_000L, 122_222_222L, 144_444_444L, 166_666_667L)[quartile - 1]
            },
        )
        assertEquals(
            MeasuredWindowOutcome.DUAL_FULL,
            complete(evaluate(GameMode.DUAL, ProbeWindowKind.CANDIDATE, fullEquality)).outcome,
        )

        val fullPlusOne = samples(
            count = 75,
            mode = GameMode.DUAL,
            inferenceNs = { 66_666_668L },
            frameAgeNs = { quartile ->
                listOf(100_000_000L, 122_222_222L, 144_444_444L, 166_666_668L)[quartile - 1]
            },
        )
        assertEquals(
            MeasuredWindowOutcome.DUAL_CONDITIONAL,
            complete(evaluate(GameMode.DUAL, ProbeWindowKind.CANDIDATE, fullPlusOne)).outcome,
        )

        val conditionalEquality = samples(
            count = 50,
            mode = GameMode.DUAL,
            inferenceNs = { 100_000_000L },
            frameAgeNs = { quartile ->
                listOf(100_000_000L, 133_333_333L, 166_666_666L, 200_000_000L)[quartile - 1]
            },
        )
        assertEquals(
            MeasuredWindowOutcome.DUAL_CONDITIONAL,
            complete(evaluate(GameMode.DUAL, ProbeWindowKind.CANDIDATE, conditionalEquality)).outcome,
        )

        val oneBelowConditionalRate = samples(count = 49, mode = GameMode.DUAL)
        assertEquals(
            MeasuredWindowOutcome.DUAL_BELOW_FLOOR,
            complete(evaluate(GameMode.DUAL, ProbeWindowKind.CANDIDATE, oneBelowConditionalRate)).outcome,
        )
    }

    @Test
    fun negativeFrameAgeGrowthIsValidAndPassesUpperBound() {
        val decreasingAge = samples(
            count = 100,
            mode = GameMode.SOLO,
            frameAgeNs = { quartile -> if (quartile == 1) 100_000_000L else 50_000_000L },
        )
        val result = complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, decreasingAge))

        assertEquals(-50_000_000L, result.summary.frameAgeGrowthNs)
        assertEquals(MeasuredWindowOutcome.SOLO_SUPPORT, result.outcome)
    }

    @Test
    fun fourRepresentativeSamplesCanBeMeasuredLowButMissingQuartileIsIncomplete() {
        val four = samples(count = 4, mode = GameMode.SOLO)
        assertEquals(
            MeasuredWindowOutcome.SOLO_BELOW_FLOOR,
            complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, four)).outcome,
        )

        val missingQ4 = four.mapIndexed { index, value ->
            if (index == 3) value.copy(poseCount = 0) else value
        }
        assertIncomplete(
            evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, missingQ4),
            ProbeWindowIncompleteReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
        )

        val onePersonDual = samples(count = 75, mode = GameMode.DUAL).map { it.copy(poseCount = 1) }
        assertIncomplete(
            evaluate(GameMode.DUAL, ProbeWindowKind.CANDIDATE, onePersonDual),
            ProbeWindowIncompleteReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
        )
    }

    @Test
    fun rejectionErrorAbortCapacityAndResourceSignalsAreNeverMeasuredLow() {
        val baseSamples = samples(count = 100, mode = GameMode.SOLO)
        val rejectionCounts = counts(100).copy(
            cameraCallbackCount = 101L,
            sourceRejectedDuplicateCount = 1L,
        )
        assertIncomplete(
            ProbeWindowPolicy.evaluate(
                GameMode.SOLO,
                ProbeWindowKind.CANDIDATE,
                START_NS,
                START_NS + ProbeWindowKind.CANDIDATE.durationNs,
                rejectionCounts,
                timings(baseSamples),
                baseSamples,
            ),
            ProbeWindowIncompleteReason.REJECTION_ERROR_OR_ABORT,
        )

        val safetySignals = listOf(
            counts(100).copy(capacityViolationCount = 1L) to
                ProbeWindowIncompleteReason.CAPACITY_INCONSISTENCY,
            counts(100).copy(resourceUncertaintyCount = 1L) to
                ProbeWindowIncompleteReason.RESOURCE_INCONSISTENCY,
            counts(100).copy(fatalEventCount = 1L) to ProbeWindowIncompleteReason.FATAL_EVENT,
        )
        safetySignals.forEach { (signalCounts, expected) ->
            assertIncomplete(
                ProbeWindowPolicy.evaluate(
                    GameMode.SOLO,
                    ProbeWindowKind.CANDIDATE,
                    START_NS,
                    START_NS + ProbeWindowKind.CANDIDATE.durationNs,
                    signalCounts,
                    timings(baseSamples),
                    baseSamples,
                ),
                expected,
            )
        }
    }

    @Test
    fun conservationMismatchAndOverflowFailClosedWithoutThrowing() {
        val samples = samples(count = 4, mode = GameMode.SOLO)
        assertIncomplete(
            ProbeWindowPolicy.evaluate(
                GameMode.SOLO,
                ProbeWindowKind.CANDIDATE,
                START_NS,
                START_NS + ProbeWindowKind.CANDIDATE.durationNs,
                counts(4).copy(cameraCallbackCount = 5L),
                timings(samples),
                samples,
            ),
            ProbeWindowIncompleteReason.COUNT_CONSERVATION_FAILED,
        )

        val overflow = ProbeWindowPolicy.evaluate(
            GameMode.SOLO,
            ProbeWindowKind.CANDIDATE,
            START_NS,
            START_NS + ProbeWindowKind.CANDIDATE.durationNs,
            ProbeWindowCounts(
                cameraCallbackCount = Long.MAX_VALUE,
                sourceAcceptedCount = Long.MAX_VALUE,
                sourceRejectedNegativeCount = 1L,
                completedCount = 0L,
            ),
            emptyList(),
            emptyList(),
        )
        assertIncomplete(overflow, ProbeWindowIncompleteReason.ARITHMETIC_OVERFLOW)
    }

    @Test
    fun sampleOrderAndCallerMutationCannotChangeAggregateAndRawTimingFieldsAreAbsent() {
        val mutable = samples(count = 100, mode = GameMode.SOLO).toMutableList()
        val forward = complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, mutable))
        val reversed = complete(evaluate(GameMode.SOLO, ProbeWindowKind.CANDIDATE, mutable.reversed()))
        assertEquals(forward, reversed)

        val capturedSummary = forward.summary
        mutable.clear()
        assertEquals(100L, capturedSummary.occupancyValidCompletedCount)
        assertEquals(100L, capturedSummary.representative.inferenceDurationNs.count)
        assertEquals(
            99L,
            (capturedSummary.cameraInterarrival as OptionalIntegerDistribution.Present)
                .distribution.count,
        )
        assertTrue(
            RepresentativeFrameAgeJointWitnessPolicy.validate(
                witness = capturedSummary.representativeFrameAgeJointWitness,
                representativeDistribution = capturedSummary.representative.frameAgeAtCompletionNs,
                quartileCounts = capturedSummary.representativeQuartileCounts,
                q1Median = capturedSummary.q1FrameAgeAtCompletionMedian,
                q4Median = capturedSummary.q4FrameAgeAtCompletionMedian,
                frameAgeGrowthNs = capturedSummary.frameAgeGrowthNs,
            ) is CapabilityDomainResult.Valid,
        )

        val forbiddenFragments = listOf(
            "device",
            "manufacturer",
            "modelname",
            "participant",
            "user",
            "bitmap",
            "pixel",
            "landmark",
            "filepath",
            "ordinal",
            "frameid",
        )
        val persistedAggregateFieldTypes = ProbeWindowSummary::class.java.declaredFields.map { it.type }
        assertFalse(
            persistedAggregateFieldTypes.any { type ->
                type == CompletedProbeSample::class.java ||
                    type == ProbeCallbackTiming::class.java ||
                    type.isArray
            },
        )
        val summaryFieldNames = ProbeWindowSummary::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(summaryFieldNames.any { name -> forbiddenFragments.any(name::contains) })

        val mutableCheckpoints = capturedSummary.representativeFrameAgeJointWitness
            .checkpointValues.toMutableList()
        val mutableRows = capturedSummary.representativeFrameAgeJointWitness
            .quartileRows.map { it.toMutableList() }.toMutableList()
        val defensiveWitness = RepresentativeFrameAgeJointWitness(mutableCheckpoints, mutableRows)
        val firstCheckpoint = defensiveWitness.checkpointValues.first()
        mutableCheckpoints[0] += 1L
        mutableRows[0][0] += 1L
        assertEquals(firstCheckpoint, defensiveWitness.checkpointValues.first())
        assertTrue(defensiveWitness.quartileRows[0][0] != mutableRows[0][0])
    }

    private fun evaluate(
        mode: GameMode,
        kind: ProbeWindowKind,
        samples: Collection<CompletedProbeSample>,
    ): ProbeWindowEvaluation = ProbeWindowPolicy.evaluate(
        mode = mode,
        kind = kind,
        startNs = START_NS,
        endNs = START_NS + kind.durationNs,
        counts = counts(samples.size),
        callbackTimings = timings(samples),
        samples = samples,
    )

    private fun samples(
        count: Int,
        mode: GameMode,
        kind: ProbeWindowKind = ProbeWindowKind.CANDIDATE,
        inferenceNs: (quartile: Int) -> Long = { 20_000_000L },
        frameAgeNs: (quartile: Int) -> Long = { 100_000_000L },
    ): List<CompletedProbeSample> {
        val duration = kind.durationNs
        val width = duration / 4L
        return List(count) { index ->
            val receivedAt = START_NS + (index.toLong() * duration / count.toLong())
            val quartile = ((receivedAt - START_NS) / width).toInt().coerceAtMost(3) + 1
            sample(
                windowAdmissionOrdinal = index.toLong(),
                receivedAtNs = receivedAt,
                poseCount = if (mode == GameMode.SOLO) 1 else 2,
                inferenceNs = inferenceNs(quartile),
                frameAgeNs = frameAgeNs(quartile),
            )
        }
    }

    private fun sample(
        windowAdmissionOrdinal: Long,
        receivedAtNs: Long,
        poseCount: Int,
        inferenceNs: Long = 20_000_000L,
        frameAgeNs: Long = 100_000_000L,
    ): CompletedProbeSample {
        val pipelineAge = maxOf(20_000_000L, inferenceNs)
        val safeFrameAge = maxOf(frameAgeNs, pipelineAge)
        val completedAt = receivedAtNs + pipelineAge
        return CompletedProbeSample(
            windowAdmissionOrdinal = windowAdmissionOrdinal,
            receivedAtNs = receivedAtNs,
            sourceTimestampNs = completedAt - safeFrameAge,
            submittedAtNs = completedAt - inferenceNs,
            completedAtNs = completedAt,
            poseCount = poseCount,
        )
    }

    private fun counts(completed: Int): ProbeWindowCounts = ProbeWindowCounts(
        cameraCallbackCount = completed.toLong(),
        sourceAcceptedCount = completed.toLong(),
        completedCount = completed.toLong(),
    )

    private fun timings(samples: Collection<CompletedProbeSample>): List<ProbeCallbackTiming> =
        samples.map { sample ->
            ProbeCallbackTiming(
                windowAdmissionOrdinal = sample.windowAdmissionOrdinal,
                receivedAtNs = sample.receivedAtNs,
                sourceTimestampNs = sample.sourceTimestampNs,
            )
        }

    private fun complete(evaluation: ProbeWindowEvaluation): ProbeWindowEvaluation.Complete =
        evaluation as? ProbeWindowEvaluation.Complete
            ?: error("Expected complete evaluation, got $evaluation")

    private fun assertIncomplete(
        evaluation: ProbeWindowEvaluation,
        reason: ProbeWindowIncompleteReason,
    ) {
        val incomplete = evaluation as? ProbeWindowEvaluation.Incomplete
            ?: error("Expected incomplete evaluation, got $evaluation")
        assertTrue("Expected $reason in ${incomplete.reasons}", reason in incomplete.reasons)
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }

    private companion object {
        const val START_NS = 2_000_000_000L
    }
}
