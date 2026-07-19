package com.motionarcade.vision.capability.domain

import com.motionarcade.core.contract.GameMode
import java.util.Collections

/**
 * One window camera callback's timing tuple. This input is measurement-ephemeral and
 * contains no frame, participant, device, or file identity. The reducer canonicalizes
 * it chronologically and retains only inter-arrival rank certificates.
 */
data class ProbeCallbackTiming(
    val windowAdmissionOrdinal: Long,
    val receivedAtNs: Long,
    val sourceTimestampNs: Long,
)

/**
 * One completed callback sample. This value is measurement-ephemeral: callers pass it
 * to [ProbeWindowPolicy.evaluate] and must discard it after the aggregate is produced.
 * It deliberately contains no image, landmark, participant, device, or file identity.
 */
data class CompletedProbeSample(
    val windowAdmissionOrdinal: Long,
    val receivedAtNs: Long,
    val sourceTimestampNs: Long,
    val submittedAtNs: Long,
    val completedAtNs: Long,
    val poseCount: Int,
)

enum class ProbeWindowKind(val durationNs: Long) {
    CANDIDATE(ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS),
    SELECTED_STEADY(ProbeTimeContract.SELECTED_STEADY_DURATION_NS),
}

/** Mutually exclusive callback and accepted-input terminal dispositions. */
data class ProbeWindowCounts(
    val cameraCallbackCount: Long,
    val sourceAcceptedCount: Long,
    val sourceRejectedNegativeCount: Long = 0L,
    val sourceRejectedDuplicateCount: Long = 0L,
    val sourceRejectedOutOfOrderCount: Long = 0L,
    val sourceRejectedIdExhaustedCount: Long = 0L,
    val completedCount: Long,
    val inputConversionErrorCount: Long = 0L,
    val inferenceErrorCount: Long = 0L,
    val completionTimeoutCount: Long = 0L,
    val callbackResourceErrorCount: Long = 0L,
    val attemptAbortedCount: Long = 0L,
    val outsideWindowCallbackCount: Long = 0L,
    val capacityViolationCount: Long = 0L,
    val resourceUncertaintyCount: Long = 0L,
    val fatalEventCount: Long = 0L,
)

data class QuartileCounts(
    val q1: Long,
    val q2: Long,
    val q3: Long,
    val q4: Long,
) {
    fun allPositive(): Boolean = q1 > 0L && q2 > 0L && q3 > 0L && q4 > 0L

    fun allAtLeast(minimum: Long): Boolean =
        q1 >= minimum && q2 >= minimum && q3 >= minimum && q4 >= minimum
}

data class RankCertificate(
    val value: Long,
    val strictlyLessCount: Long,
    val equalCount: Long,
)

data class IntegerDistribution(
    val count: Long,
    val p50: RankCertificate,
    val p90: RankCertificate,
    val p95: RankCertificate,
    val p99: RankCertificate,
    val maximum: RankCertificate,
)

/** CapabilityModeStoreV4's required PRESENT/ABSENT distribution union. */
sealed interface OptionalIntegerDistribution {
    data object Absent : OptionalIntegerDistribution

    data class Present(val distribution: IntegerDistribution) : OptionalIntegerDistribution
}

data class CallbackInterarrivalDistributions(
    val camera: OptionalIntegerDistribution,
    val source: OptionalIntegerDistribution,
)

/** Pure integer nearest-rank reducer. No floating-point percentile calculation is used. */
object IntegerDistributionPolicy {
    fun reduce(values: Collection<Long>): CapabilityDomainResult<IntegerDistribution> {
        if (values.isEmpty()) {
            return invalid(
                path = "$/distribution",
                expected = "at least one non-negative sample",
                actual = "empty",
            )
        }
        if (values.any { it < 0L }) {
            return invalid(
                path = "$/distribution",
                expected = "non-negative integer samples",
                actual = "contains a negative value",
            )
        }

        val sorted = values.sorted()
        val count = sorted.size.toLong()
        val p50 = certificate(sorted, nearestRank(count, 50) ?: return rankOverflow(50))
        val p90 = certificate(sorted, nearestRank(count, 90) ?: return rankOverflow(90))
        val p95 = certificate(sorted, nearestRank(count, 95) ?: return rankOverflow(95))
        val p99 = certificate(sorted, nearestRank(count, 99) ?: return rankOverflow(99))
        val maximum = certificate(sorted, count)
        return CapabilityDomainResult.Valid(
            IntegerDistribution(
                count = count,
                p50 = p50,
                p90 = p90,
                p95 = p95,
                p99 = p99,
                maximum = maximum,
            ),
        )
    }

    private fun nearestRank(count: Long, percentile: Int): Long? = try {
        val whole = count / 100L
        val remainder = count % 100L
        val wholeContribution = Math.multiplyExact(whole, percentile.toLong())
        val remainderProduct = Math.multiplyExact(remainder, percentile.toLong())
        val remainderContribution = Math.addExact(remainderProduct, 99L) / 100L
        Math.addExact(wholeContribution, remainderContribution)
    } catch (_: ArithmeticException) {
        null
    }

    private fun certificate(sorted: List<Long>, oneBasedRank: Long): RankCertificate {
        val value = sorted[(oneBasedRank - 1L).toInt()]
        var first = 0
        while (sorted[first] < value) first += 1
        var afterLast = first
        while (afterLast < sorted.size && sorted[afterLast] == value) afterLast += 1
        return RankCertificate(
            value = value,
            strictlyLessCount = first.toLong(),
            equalCount = (afterLast - first).toLong(),
        )
    }

    private fun rankOverflow(percentile: Int): CapabilityDomainResult.Invalid =
        CapabilityDomainResult.Invalid(
            listOf(
                CapabilityDomainViolation.ArithmeticOverflow(
                    path = "$/distribution/p$percentile",
                    operation = "exact integer nearest-rank calculation",
                ),
            ),
        )
}

internal data class CanonicalCallbackTimingReduction(
    val orderedTimings: List<ProbeCallbackTiming>,
    val distributions: CallbackInterarrivalDistributions,
)

/** Canonical, order-independent reduction of ephemeral callback timing tuples. */
object ProbeCallbackTimingPolicy {
    fun reduce(
        callbackTimings: Collection<ProbeCallbackTiming>,
    ): CapabilityDomainResult<CallbackInterarrivalDistributions> =
        canonicalize(callbackTimings).map(CanonicalCallbackTimingReduction::distributions)

    internal fun canonicalize(
        callbackTimings: Collection<ProbeCallbackTiming>,
    ): CapabilityDomainResult<CanonicalCallbackTimingReduction> {
        val ordered = callbackTimings.sortedBy(ProbeCallbackTiming::windowAdmissionOrdinal)
        ordered.forEachIndexed { index, timing ->
            if (timing.windowAdmissionOrdinal != index.toLong()) {
                return invalid(
                    path = "$/callbackTimings/$index/windowAdmissionOrdinal",
                    expected = "unique contiguous ordinal ${index.toLong()}",
                    actual = timing.windowAdmissionOrdinal.toString(),
                )
            }
            if (timing.receivedAtNs < 0L) {
                return invalid(
                    path = "$/callbackTimings/$index/receivedAtNs",
                    expected = "non-negative monotonic timestamp",
                    actual = timing.receivedAtNs.toString(),
                )
            }
            if (timing.sourceTimestampNs < 0L || timing.sourceTimestampNs > timing.receivedAtNs) {
                return invalid(
                    path = "$/callbackTimings/$index/sourceTimestampNs",
                    expected = "0 <= sourceTimestampNs <= receivedAtNs",
                    actual = timing.sourceTimestampNs.toString(),
                )
            }
        }

        if (ordered.size <= 1) {
            return CapabilityDomainResult.Valid(
                CanonicalCallbackTimingReduction(
                    orderedTimings = ordered,
                    distributions = CallbackInterarrivalDistributions(
                        camera = OptionalIntegerDistribution.Absent,
                        source = OptionalIntegerDistribution.Absent,
                    ),
                ),
            )
        }

        val cameraInterarrivals = ArrayList<Long>(ordered.size - 1)
        val sourceInterarrivals = ArrayList<Long>(ordered.size - 1)
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            val cameraDelta = checkedSubtractExact(current.receivedAtNs, previous.receivedAtNs)
                ?: return overflow(
                    path = "$/callbackTimings/$index/receivedAtNs",
                    operation = "camera inter-arrival subtraction",
                )
            val sourceDelta = checkedSubtractExact(current.sourceTimestampNs, previous.sourceTimestampNs)
                ?: return overflow(
                    path = "$/callbackTimings/$index/sourceTimestampNs",
                    operation = "source inter-arrival subtraction",
                )
            if (cameraDelta < 0L) {
                return invalid(
                    path = "$/callbackTimings/$index/receivedAtNs",
                    expected = "nondecreasing camera admission in ordinal order",
                    actual = current.receivedAtNs.toString(),
                )
            }
            if (sourceDelta <= 0L) {
                return invalid(
                    path = "$/callbackTimings/$index/sourceTimestampNs",
                    expected = "strictly increasing source timestamps",
                    actual = current.sourceTimestampNs.toString(),
                )
            }
            cameraInterarrivals += cameraDelta
            sourceInterarrivals += sourceDelta
        }

        val camera = when (val reduced = IntegerDistributionPolicy.reduce(cameraInterarrivals)) {
            is CapabilityDomainResult.Valid -> OptionalIntegerDistribution.Present(reduced.value)
            is CapabilityDomainResult.Invalid -> return reduced
        }
        val source = when (val reduced = IntegerDistributionPolicy.reduce(sourceInterarrivals)) {
            is CapabilityDomainResult.Valid -> OptionalIntegerDistribution.Present(reduced.value)
            is CapabilityDomainResult.Invalid -> return reduced
        }
        val expectedCount = (ordered.size - 1).toLong()
        if (camera.distribution.count != expectedCount || source.distribution.count != expectedCount) {
            return invalid(
                path = "$/callbackTimings/interarrivalCount",
                expected = expectedCount.toString(),
                actual = "camera=${camera.distribution.count},source=${source.distribution.count}",
            )
        }
        return CapabilityDomainResult.Valid(
            CanonicalCallbackTimingReduction(
                orderedTimings = ordered,
                distributions = CallbackInterarrivalDistributions(camera, source),
            ),
        )
    }
}

data class TimingDistributions(
    val inferenceDurationNs: IntegerDistribution,
    val pipelineAgeNs: IntegerDistribution,
    val frameAgeAtReceiveNs: IntegerDistribution,
    val frameAgeAtCompletionNs: IntegerDistribution,
)

/**
 * Bounded aggregate witness for one representative frame-age multiset partitioned into
 * Q1..Q4. Both levels are defensively copied and unmodifiable; no raw sample survives.
 */
class RepresentativeFrameAgeJointWitness(
    checkpointValues: Collection<Long>,
    quartileRows: Collection<Collection<Long>>,
) {
    val checkpointValues: List<Long> = Collections.unmodifiableList(ArrayList(checkpointValues))
    val quartileRows: List<List<Long>> = Collections.unmodifiableList(
        quartileRows.mapTo(ArrayList(quartileRows.size)) { row ->
            Collections.unmodifiableList(ArrayList(row))
        },
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RepresentativeFrameAgeJointWitness &&
            checkpointValues == other.checkpointValues &&
            quartileRows == other.quartileRows

    override fun hashCode(): Int = 31 * checkpointValues.hashCode() + quartileRows.hashCode()

    override fun toString(): String =
        "RepresentativeFrameAgeJointWitness(checkpoints=$checkpointValues, rows=$quartileRows)"
}

/** Exact writer and strict benign-corruption validator for the V1 joint witness. */
object RepresentativeFrameAgeJointWitnessPolicy {
    private const val QUARTILE_COUNT = 4
    private const val MIN_CHECKPOINT_COUNT = 1
    private const val MAX_CHECKPOINT_COUNT = 7

    internal fun write(
        quartileValues: List<List<Long>>,
        representativeDistribution: IntegerDistribution,
        q1Median: RankCertificate,
        q4Median: RankCertificate,
        frameAgeGrowthNs: Long,
    ): CapabilityDomainResult<RepresentativeFrameAgeJointWitness> {
        if (quartileValues.size != QUARTILE_COUNT) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/quartileRows",
                expected = "exactly four quartiles",
                actual = quartileValues.size.toString(),
            )
        }
        if (quartileValues.any { row -> row.any { it < 0L } }) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/quartileRows",
                expected = "non-negative frame ages",
                actual = "negative value",
            )
        }

        val checkpoints = expectedCheckpoints(representativeDistribution, q1Median, q4Median)
        val rows = ArrayList<List<Long>>(QUARTILE_COUNT)
        for (values in quartileValues) {
            val buckets = MutableList(2 * checkpoints.size + 1) { 0L }
            for (value in values) {
                val bucketIndex = bucketIndex(value, checkpoints)
                buckets[bucketIndex] = try {
                    Math.addExact(buckets[bucketIndex], 1L)
                } catch (_: ArithmeticException) {
                    return overflow(
                        path = "$/representativeFrameAgeJointWitness/quartileRows",
                        operation = "bucket count increment",
                    )
                }
            }
            rows += buckets
        }

        val witness = RepresentativeFrameAgeJointWitness(checkpoints, rows)
        val quartileCounts = QuartileCounts(
            q1 = quartileValues[0].size.toLong(),
            q2 = quartileValues[1].size.toLong(),
            q3 = quartileValues[2].size.toLong(),
            q4 = quartileValues[3].size.toLong(),
        )
        return when (
            val validation = validate(
                witness = witness,
                representativeDistribution = representativeDistribution,
                quartileCounts = quartileCounts,
                q1Median = q1Median,
                q4Median = q4Median,
                frameAgeGrowthNs = frameAgeGrowthNs,
            )
        ) {
            is CapabilityDomainResult.Valid -> CapabilityDomainResult.Valid(witness)
            is CapabilityDomainResult.Invalid -> validation
        }
    }

    fun validate(
        witness: RepresentativeFrameAgeJointWitness,
        representativeDistribution: IntegerDistribution,
        quartileCounts: QuartileCounts,
        q1Median: RankCertificate,
        q4Median: RankCertificate,
        frameAgeGrowthNs: Long,
    ): CapabilityDomainResult<Unit> {
        validateDistribution(representativeDistribution)?.let { return it }
        if (!quartileCounts.allPositive()) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/quartileCounts",
                expected = "four positive counts",
                actual = quartileCounts.toString(),
            )
        }
        validateMedian(q1Median, quartileCounts.q1, "q1")?.let { return it }
        validateMedian(q4Median, quartileCounts.q4, "q4")?.let { return it }

        val checkpoints = witness.checkpointValues
        if (checkpoints.size !in MIN_CHECKPOINT_COUNT..MAX_CHECKPOINT_COUNT) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/checkpointCount",
                expected = "$MIN_CHECKPOINT_COUNT..$MAX_CHECKPOINT_COUNT",
                actual = checkpoints.size.toString(),
            )
        }
        if (checkpoints.any { it < 0L } || checkpoints.zipWithNext().any { (left, right) -> left >= right }) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/checkpointValues",
                expected = "strictly increasing non-negative integers",
                actual = checkpoints.toString(),
            )
        }
        val expectedCheckpoints = expectedCheckpoints(representativeDistribution, q1Median, q4Median)
        if (checkpoints != expectedCheckpoints) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/checkpointValues",
                expected = expectedCheckpoints.toString(),
                actual = checkpoints.toString(),
            )
        }
        if (
            checkpoints.last() != representativeDistribution.maximum.value ||
            checkpoints.any { it > representativeDistribution.maximum.value }
        ) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/checkpointValues",
                expected = "final checkpoint exactly the certified maximum",
                actual = checkpoints.toString(),
            )
        }

        val rows = witness.quartileRows
        val expectedRowSize = 2 * checkpoints.size + 1
        if (rows.size != QUARTILE_COUNT || rows.any { it.size != expectedRowSize }) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/quartileRows",
                expected = "four rows of $expectedRowSize counts",
                actual = rows.map { it.size }.toString(),
            )
        }
        if (rows.any { row -> row.any { it < 0L } }) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/quartileRows",
                expected = "non-negative bucket counts",
                actual = "negative count",
            )
        }

        rows.forEachIndexed { rowIndex, row ->
            if (checkpoints.first() == 0L && row.first() != 0L) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/quartileRows/$rowIndex/0",
                    expected = "zero for empty [0,0) bucket",
                    actual = row.first().toString(),
                )
            }
            for (checkpointIndex in 0 until checkpoints.lastIndex) {
                val adjacent = checkedAddExact(checkpoints[checkpointIndex], 1L)
                    ?: return overflow(
                        path = "$/representativeFrameAgeJointWitness/checkpointValues/$checkpointIndex",
                        operation = "adjacency check",
                    )
                val betweenIndex = 2 * checkpointIndex + 2
                if (adjacent >= checkpoints[checkpointIndex + 1] && row[betweenIndex] != 0L) {
                    return invalid(
                        path = "$/representativeFrameAgeJointWitness/quartileRows/$rowIndex/$betweenIndex",
                        expected = "zero for unreachable open interval",
                        actual = row[betweenIndex].toString(),
                    )
                }
            }
            if (row.last() != 0L) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/quartileRows/$rowIndex/${row.lastIndex}",
                    expected = "zero above the certified maximum",
                    actual = row.last().toString(),
                )
            }
        }

        val expectedQuartileCounts = listOf(
            quartileCounts.q1,
            quartileCounts.q2,
            quartileCounts.q3,
            quartileCounts.q4,
        )
        rows.forEachIndexed { index, row ->
            val sum = checkedSumExact(row)
                ?: return overflow(
                    path = "$/representativeFrameAgeJointWitness/quartileRows/$index",
                    operation = "row conservation sum",
                )
            if (sum != expectedQuartileCounts[index]) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/quartileRows/$index",
                    expected = "row sum ${expectedQuartileCounts[index]}",
                    actual = sum.toString(),
                )
            }
        }
        val quartileTotal = checkedSumExact(expectedQuartileCounts)
            ?: return overflow(
                path = "$/representativeFrameAgeJointWitness/quartileCounts",
                operation = "quartile conservation sum",
            )
        if (quartileTotal != representativeDistribution.count) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/quartileCounts",
                expected = "sum ${representativeDistribution.count}",
                actual = quartileTotal.toString(),
            )
        }

        val certificates = listOf(
            representativeDistribution.p50,
            representativeDistribution.p90,
            representativeDistribution.p95,
            representativeDistribution.p99,
            representativeDistribution.maximum,
        )
        for (certificate in certificates) {
            val checkpointIndex = checkpoints.binarySearch(certificate.value)
            if (checkpointIndex < 0) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/checkpointValues",
                    expected = "checkpoint for every global certificate",
                    actual = certificate.value.toString(),
                )
            }
            val less = sumBucketsBeforeEquality(rows, checkpointIndex)
                ?: return overflow(
                    path = "$/representativeFrameAgeJointWitness/quartileRows",
                    operation = "global strictly-less conservation",
                )
            val equal = checkedSumExact(rows.map { it[2 * checkpointIndex + 1] })
                ?: return overflow(
                    path = "$/representativeFrameAgeJointWitness/quartileRows",
                    operation = "global equality conservation",
                )
            if (less != certificate.strictlyLessCount || equal != certificate.equalCount) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/globalCertificate/${certificate.value}",
                    expected = "less=${certificate.strictlyLessCount},equal=${certificate.equalCount}",
                    actual = "less=$less,equal=$equal",
                )
            }
        }

        validateQuartileMedian(rows[0], checkpoints, q1Median, "q1")?.let { return it }
        validateQuartileMedian(rows[3], checkpoints, q4Median, "q4")?.let { return it }
        val recomputedGrowth = checkedSubtractExact(q4Median.value, q1Median.value)
            ?: return overflow(
                path = "$/representativeFrameAgeJointWitness/frameAgeGrowthNs",
                operation = "Q4 median - Q1 median",
            )
        if (recomputedGrowth != frameAgeGrowthNs) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/frameAgeGrowthNs",
                expected = recomputedGrowth.toString(),
                actual = frameAgeGrowthNs.toString(),
            )
        }
        return CapabilityDomainResult.Valid(Unit)
    }

    private fun validateDistribution(
        distribution: IntegerDistribution,
    ): CapabilityDomainResult.Invalid? {
        if (distribution.count <= 0L) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/distribution/count",
                expected = "positive",
                actual = distribution.count.toString(),
            )
        }
        val ranked = listOf(
            50 to distribution.p50,
            90 to distribution.p90,
            95 to distribution.p95,
            99 to distribution.p99,
        )
        ranked.forEach { (percentile, certificate) ->
            validateRankCertificate(certificate, distribution.count, percentile, false)?.let { return it }
        }
        validateRankCertificate(distribution.maximum, distribution.count, 100, true)?.let { return it }

        val certificates = ranked.map { it.second } + distribution.maximum
        for (certificate in certificates) {
            if (certificate.value == 0L && certificate.strictlyLessCount != 0L) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/distribution",
                    expected = "zero-valued certificate has zero strictly-less count",
                    actual = certificate.toString(),
                )
            }
        }
        certificates.zipWithNext().forEach { (prior, next) ->
            if (prior.value > next.value) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/distribution",
                    expected = "monotonic certificate values",
                    actual = "$prior then $next",
                )
            }
            if (
                prior.value == next.value &&
                (prior.strictlyLessCount != next.strictlyLessCount || prior.equalCount != next.equalCount)
            ) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/distribution",
                    expected = "equal values have identical certificates",
                    actual = "$prior then $next",
                )
            }
            if (prior.value < next.value) {
                val priorEnd = checkedAddExact(prior.strictlyLessCount, prior.equalCount)
                    ?: return overflow(
                        path = "$/representativeFrameAgeJointWitness/distribution",
                        operation = "certificate interval end",
                    )
                if (priorEnd > next.strictlyLessCount) {
                    return invalid(
                        path = "$/representativeFrameAgeJointWitness/distribution",
                        expected = "non-overlapping increasing certificates",
                        actual = "$prior then $next",
                    )
                }
                val adjacent = checkedAddExact(prior.value, 1L)
                if (adjacent != null && adjacent == next.value && priorEnd != next.strictlyLessCount) {
                    return invalid(
                        path = "$/representativeFrameAgeJointWitness/distribution",
                        expected = "adjacent values conserve every sample",
                        actual = "$prior then $next",
                    )
                }
            }
        }
        return null
    }

    private fun validateMedian(
        certificate: RankCertificate,
        count: Long,
        name: String,
    ): CapabilityDomainResult.Invalid? =
        validateRankCertificate(certificate, count, 50, false, name)

    private fun validateRankCertificate(
        certificate: RankCertificate,
        count: Long,
        percentile: Int,
        maximum: Boolean,
        name: String = "p$percentile",
    ): CapabilityDomainResult.Invalid? {
        if (
            certificate.value < 0L || certificate.strictlyLessCount < 0L ||
            certificate.equalCount <= 0L
        ) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/$name",
                expected = "non-negative value/less and positive equal count",
                actual = certificate.toString(),
            )
        }
        val end = checkedAddExact(certificate.strictlyLessCount, certificate.equalCount)
            ?: return overflow(
                path = "$/representativeFrameAgeJointWitness/$name",
                operation = "strictly-less + equal",
            )
        if (end > count) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/$name",
                expected = "certificate end <= $count",
                actual = end.toString(),
            )
        }
        if (maximum) {
            if (end != count) {
                return invalid(
                    path = "$/representativeFrameAgeJointWitness/$name",
                    expected = "maximum certificate ends at $count",
                    actual = end.toString(),
                )
            }
            return null
        }
        val rank = nearestRankExact(count, percentile)
            ?: return overflow(
                path = "$/representativeFrameAgeJointWitness/$name",
                operation = "nearest-rank calculation",
            )
        if (certificate.strictlyLessCount >= rank || rank > end) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/$name",
                expected = "less < rank $rank <= less+equal",
                actual = certificate.toString(),
            )
        }
        return null
    }

    private fun validateQuartileMedian(
        row: List<Long>,
        checkpoints: List<Long>,
        median: RankCertificate,
        name: String,
    ): CapabilityDomainResult.Invalid? {
        val checkpointIndex = checkpoints.binarySearch(median.value)
        if (checkpointIndex < 0) {
            return invalid(
                path = "$/representativeFrameAgeJointWitness/$name",
                expected = "median checkpoint present",
                actual = median.value.toString(),
            )
        }
        val less = checkedSumExact(row.subList(0, 2 * checkpointIndex + 1))
            ?: return overflow(
                path = "$/representativeFrameAgeJointWitness/$name",
                operation = "quartile strictly-less conservation",
            )
        val equal = row[2 * checkpointIndex + 1]
        return if (less == median.strictlyLessCount && equal == median.equalCount) {
            null
        } else {
            invalid(
                path = "$/representativeFrameAgeJointWitness/$name",
                expected = "less=${median.strictlyLessCount},equal=${median.equalCount}",
                actual = "less=$less,equal=$equal",
            )
        }
    }

    private fun sumBucketsBeforeEquality(
        rows: List<List<Long>>,
        checkpointIndex: Int,
    ): Long? = checkedSumExact(
        rows.flatMap { row -> row.subList(0, 2 * checkpointIndex + 1) },
    )

    private fun expectedCheckpoints(
        distribution: IntegerDistribution,
        q1Median: RankCertificate,
        q4Median: RankCertificate,
    ): List<Long> = listOf(
        distribution.p50.value,
        distribution.p90.value,
        distribution.p95.value,
        distribution.p99.value,
        distribution.maximum.value,
        q1Median.value,
        q4Median.value,
    ).distinct().sorted()

    private fun bucketIndex(value: Long, checkpoints: List<Long>): Int {
        if (value < checkpoints.first()) return 0
        checkpoints.forEachIndexed { index, checkpoint ->
            if (value == checkpoint) return 2 * index + 1
            if (index < checkpoints.lastIndex && value < checkpoints[index + 1]) {
                return 2 * index + 2
            }
        }
        return 2 * checkpoints.size
    }
}

private fun nearestRankExact(count: Long, percentile: Int): Long? = try {
    if (count <= 0L || percentile !in 1..100) return null
    val whole = count / 100L
    val remainder = count % 100L
    val wholeContribution = Math.multiplyExact(whole, percentile.toLong())
    val remainderProduct = Math.multiplyExact(remainder, percentile.toLong())
    val remainderContribution = Math.addExact(remainderProduct, 99L) / 100L
    Math.addExact(wholeContribution, remainderContribution)
} catch (_: ArithmeticException) {
    null
}

private fun checkedAddExact(left: Long, right: Long): Long? = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    null
}

private fun checkedSubtractExact(left: Long, right: Long): Long? = try {
    Math.subtractExact(left, right)
} catch (_: ArithmeticException) {
    null
}

private fun checkedSumExact(values: Iterable<Long>): Long? = try {
    values.fold(0L, Math::addExact)
} catch (_: ArithmeticException) {
    null
}

data class ProbeWindowSummary(
    val mode: GameMode,
    val kind: ProbeWindowKind,
    val durationNs: Long,
    val counts: ProbeWindowCounts,
    val occupancyValidCompletedCount: Long,
    val representativeQuartileCounts: QuartileCounts,
    val representative: TimingDistributions,
    val allCompletions: TimingDistributions,
    val cameraInterarrival: OptionalIntegerDistribution,
    val sourceInterarrival: OptionalIntegerDistribution,
    val q1FrameAgeAtCompletionMedian: RankCertificate,
    val q4FrameAgeAtCompletionMedian: RankCertificate,
    val frameAgeGrowthNs: Long,
    val representativeFrameAgeJointWitness: RepresentativeFrameAgeJointWitness,
)

enum class MeasuredWindowOutcome {
    SOLO_SUPPORT,
    SOLO_BELOW_FLOOR,
    DUAL_FULL,
    DUAL_CONDITIONAL,
    DUAL_BELOW_FLOOR,
}

enum class ProbeWindowIncompleteReason {
    INVALID_WINDOW,
    INVALID_COUNT,
    COUNT_CONSERVATION_FAILED,
    ARITHMETIC_OVERFLOW,
    CALLBACK_TIMING_COUNT_MISMATCH,
    INVALID_CALLBACK_TIMING,
    SAMPLE_COUNT_MISMATCH,
    SAMPLE_CORRELATION_FAILED,
    INVALID_SAMPLE,
    REJECTION_ERROR_OR_ABORT,
    CAPACITY_INCONSISTENCY,
    RESOURCE_INCONSISTENCY,
    FATAL_EVENT,
    WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
}

sealed interface ProbeWindowEvaluation {
    data class Complete(
        val summary: ProbeWindowSummary,
        val outcome: MeasuredWindowOutcome,
    ) : ProbeWindowEvaluation

    data class Incomplete(val reasons: Set<ProbeWindowIncompleteReason>) : ProbeWindowEvaluation {
        init {
            require(reasons.isNotEmpty()) { "Incomplete evaluation requires a reason" }
        }
    }
}

object ProbeWindowPolicy {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val SUPPORT_REPRESENTATIVE_COUNT = 30L
    private const val SUPPORT_REPRESENTATIVE_PER_QUARTILE = 5L
    private const val MAX_REPRESENTATIVE_FRAME_AGE_NS = 1_000_000_000L

    /**
     * Reduces a bounded ephemeral sample collection to immutable aggregate evidence.
     * The returned object has no reference to [samples].
     */
    fun evaluate(
        mode: GameMode,
        kind: ProbeWindowKind,
        startNs: Long,
        endNs: Long,
        counts: ProbeWindowCounts,
        callbackTimings: Collection<ProbeCallbackTiming>,
        samples: Collection<CompletedProbeSample>,
    ): ProbeWindowEvaluation {
        val structuralReasons = linkedSetOf<ProbeWindowIncompleteReason>()
        val durationNs = checkedSubtract(endNs, startNs)
        if (startNs < 0L || endNs < 0L || durationNs == null || durationNs != kind.durationNs) {
            structuralReasons += if (durationNs == null) {
                ProbeWindowIncompleteReason.ARITHMETIC_OVERFLOW
            } else {
                ProbeWindowIncompleteReason.INVALID_WINDOW
            }
        }

        val allCounts = listOf(
            counts.cameraCallbackCount,
            counts.sourceAcceptedCount,
            counts.sourceRejectedNegativeCount,
            counts.sourceRejectedDuplicateCount,
            counts.sourceRejectedOutOfOrderCount,
            counts.sourceRejectedIdExhaustedCount,
            counts.completedCount,
            counts.inputConversionErrorCount,
            counts.inferenceErrorCount,
            counts.completionTimeoutCount,
            counts.callbackResourceErrorCount,
            counts.attemptAbortedCount,
            counts.outsideWindowCallbackCount,
            counts.capacityViolationCount,
            counts.resourceUncertaintyCount,
            counts.fatalEventCount,
        )
        if (allCounts.any { it < 0L }) structuralReasons += ProbeWindowIncompleteReason.INVALID_COUNT

        val sourceDispositionTotal = checkedSum(
            counts.sourceAcceptedCount,
            counts.sourceRejectedNegativeCount,
            counts.sourceRejectedDuplicateCount,
            counts.sourceRejectedOutOfOrderCount,
            counts.sourceRejectedIdExhaustedCount,
        )
        val acceptedDispositionTotal = checkedSum(
            counts.completedCount,
            counts.inputConversionErrorCount,
            counts.inferenceErrorCount,
            counts.completionTimeoutCount,
            counts.callbackResourceErrorCount,
            counts.attemptAbortedCount,
        )
        if (sourceDispositionTotal == null || acceptedDispositionTotal == null) {
            structuralReasons += ProbeWindowIncompleteReason.ARITHMETIC_OVERFLOW
        } else if (
            sourceDispositionTotal != counts.cameraCallbackCount ||
            acceptedDispositionTotal != counts.sourceAcceptedCount
        ) {
            structuralReasons += ProbeWindowIncompleteReason.COUNT_CONSERVATION_FAILED
        }
        if (samples.size.toLong() != counts.completedCount) {
            structuralReasons += ProbeWindowIncompleteReason.SAMPLE_COUNT_MISMATCH
        }
        if (callbackTimings.size.toLong() != counts.cameraCallbackCount) {
            structuralReasons += ProbeWindowIncompleteReason.CALLBACK_TIMING_COUNT_MISMATCH
        }

        val callbackReduction = when (val reduced = ProbeCallbackTimingPolicy.canonicalize(callbackTimings)) {
            is CapabilityDomainResult.Valid -> reduced.value
            is CapabilityDomainResult.Invalid -> {
                structuralReasons += if (
                    reduced.violations.any { violation ->
                        violation is CapabilityDomainViolation.ArithmeticOverflow
                    }
                ) {
                    ProbeWindowIncompleteReason.ARITHMETIC_OVERFLOW
                } else {
                    ProbeWindowIncompleteReason.INVALID_CALLBACK_TIMING
                }
                null
            }
        }

        if (hasSourceRejectionOrProcessingFailure(counts)) {
            structuralReasons += ProbeWindowIncompleteReason.REJECTION_ERROR_OR_ABORT
        }
        if (counts.capacityViolationCount != 0L) {
            structuralReasons += ProbeWindowIncompleteReason.CAPACITY_INCONSISTENCY
        }
        if (counts.resourceUncertaintyCount != 0L || counts.callbackResourceErrorCount != 0L) {
            structuralReasons += ProbeWindowIncompleteReason.RESOURCE_INCONSISTENCY
        }
        if (counts.fatalEventCount != 0L) {
            structuralReasons += ProbeWindowIncompleteReason.FATAL_EVENT
        }

        val exactDuration = durationNs ?: return incomplete(structuralReasons)
        val quartileWidth = exactDuration / 4L
        val q2Start = checkedAdd(startNs, quartileWidth)
        val q3Start = q2Start?.let { checkedAdd(it, quartileWidth) }
        val q4Start = q3Start?.let { checkedAdd(it, quartileWidth) }
        if (q2Start == null || q3Start == null || q4Start == null) {
            structuralReasons += ProbeWindowIncompleteReason.ARITHMETIC_OVERFLOW
            return incomplete(structuralReasons)
        }

        // Correlation is independent from chronology validation.  A malformed timing
        // sequence may still correspond byte-for-byte to the completed samples; in that
        // case INVALID_CALLBACK_TIMING is the precise diagnostic and correlation must not
        // manufacture a second failure merely because canonicalization was unavailable.
        val remainingCallbackTuples = callbackTimings
            .groupingBy {
                Triple(
                    it.windowAdmissionOrdinal,
                    it.receivedAtNs,
                    it.sourceTimestampNs,
                )
            }
            .eachCount()
            .toMutableMap()
        val derivedSamples = ArrayList<DerivedSample>(samples.size)
        for (sample in samples) {
            val callbackKey = Triple(
                sample.windowAdmissionOrdinal,
                sample.receivedAtNs,
                sample.sourceTimestampNs,
            )
            val callbackMultiplicity = remainingCallbackTuples[callbackKey]
            if (callbackMultiplicity == null || callbackMultiplicity <= 0) {
                structuralReasons += ProbeWindowIncompleteReason.SAMPLE_CORRELATION_FAILED
            } else if (callbackMultiplicity == 1) {
                remainingCallbackTuples.remove(callbackKey)
            } else {
                remainingCallbackTuples[callbackKey] = callbackMultiplicity - 1
            }
            val derived = deriveSample(sample, startNs, endNs, q2Start, q3Start, q4Start)
            if (derived == null) {
                structuralReasons += ProbeWindowIncompleteReason.INVALID_SAMPLE
            } else {
                derivedSamples += derived
            }
        }
        if (structuralReasons.isNotEmpty()) return incomplete(structuralReasons)

        val requiredPoseCount = if (mode == GameMode.SOLO) 1 else 2
        val representative = derivedSamples.filter { it.poseCount == requiredPoseCount }
        val quartileCounts = QuartileCounts(
            q1 = representative.count { it.quartile == 1 }.toLong(),
            q2 = representative.count { it.quartile == 2 }.toLong(),
            q3 = representative.count { it.quartile == 3 }.toLong(),
            q4 = representative.count { it.quartile == 4 }.toLong(),
        )
        if (!quartileCounts.allPositive()) {
            return ProbeWindowEvaluation.Incomplete(
                setOf(ProbeWindowIncompleteReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE),
            )
        }

        val representativeDistributions = distributions(representative)
            ?: return arithmeticIncomplete()
        val allDistributions = distributions(derivedSamples)
            ?: return arithmeticIncomplete()
        val q1Median = distribution(representative.filter { it.quartile == 1 }.map { it.frameAgeAtCompletionNs })
            ?.p50 ?: return arithmeticIncomplete()
        val q4Median = distribution(representative.filter { it.quartile == 4 }.map { it.frameAgeAtCompletionNs })
            ?.p50 ?: return arithmeticIncomplete()
        val growth = checkedSubtract(q4Median.value, q1Median.value)
            ?: return arithmeticIncomplete()
        val quartileFrameAges = (1..4).map { quartile ->
            representative.filter { it.quartile == quartile }.map(DerivedSample::frameAgeAtCompletionNs)
        }
        val jointWitness = when (
            val written = RepresentativeFrameAgeJointWitnessPolicy.write(
                quartileValues = quartileFrameAges,
                representativeDistribution = representativeDistributions.frameAgeAtCompletionNs,
                q1Median = q1Median,
                q4Median = q4Median,
                frameAgeGrowthNs = growth,
            )
        ) {
            is CapabilityDomainResult.Valid -> written.value
            is CapabilityDomainResult.Invalid -> return if (
                written.violations.any { it is CapabilityDomainViolation.ArithmeticOverflow }
            ) {
                arithmeticIncomplete()
            } else {
                ProbeWindowEvaluation.Incomplete(setOf(ProbeWindowIncompleteReason.INVALID_SAMPLE))
            }
        }
        val interarrival = callbackReduction?.distributions
            ?: return ProbeWindowEvaluation.Incomplete(
                setOf(ProbeWindowIncompleteReason.INVALID_CALLBACK_TIMING),
            )

        val summary = ProbeWindowSummary(
            mode = mode,
            kind = kind,
            durationNs = exactDuration,
            counts = counts.copy(),
            occupancyValidCompletedCount = representative.size.toLong(),
            representativeQuartileCounts = quartileCounts,
            representative = representativeDistributions,
            allCompletions = allDistributions,
            cameraInterarrival = interarrival.camera,
            sourceInterarrival = interarrival.source,
            q1FrameAgeAtCompletionMedian = q1Median,
            q4FrameAgeAtCompletionMedian = q4Median,
            frameAgeGrowthNs = growth,
            representativeFrameAgeJointWitness = jointWitness,
        )
        return ProbeWindowEvaluation.Complete(summary, classify(summary))
    }

    private fun classify(summary: ProbeWindowSummary): MeasuredWindowOutcome {
        val supportReady =
            summary.occupancyValidCompletedCount >= SUPPORT_REPRESENTATIVE_COUNT &&
                summary.representativeQuartileCounts.allAtLeast(SUPPORT_REPRESENTATIVE_PER_QUARTILE)
        return when (summary.mode) {
            GameMode.SOLO -> {
                if (supportReady && passes(summary, fps = 20L, tailLimitNs = 50_000_000L)) {
                    MeasuredWindowOutcome.SOLO_SUPPORT
                } else {
                    MeasuredWindowOutcome.SOLO_BELOW_FLOOR
                }
            }

            GameMode.DUAL -> when {
                supportReady && passes(summary, fps = 15L, tailLimitNs = 66_666_667L) -> {
                    MeasuredWindowOutcome.DUAL_FULL
                }

                supportReady && passes(summary, fps = 10L, tailLimitNs = 100_000_000L) -> {
                    MeasuredWindowOutcome.DUAL_CONDITIONAL
                }

                else -> MeasuredWindowOutcome.DUAL_BELOW_FLOOR
            }
        }
    }

    private fun passes(summary: ProbeWindowSummary, fps: Long, tailLimitNs: Long): Boolean {
        val duration = summary.durationNs
        return atLeastFps(summary.counts.cameraCallbackCount, duration, fps) &&
            atLeastFps(summary.counts.completedCount, duration, fps) &&
            atLeastFps(summary.occupancyValidCompletedCount, duration, fps) &&
            summary.representative.inferenceDurationNs.p95.value <= tailLimitNs &&
            summary.frameAgeGrowthNs <= tailLimitNs &&
            summary.representative.frameAgeAtCompletionNs.maximum.value <=
            MAX_REPRESENTATIVE_FRAME_AGE_NS
    }

    private fun atLeastFps(count: Long, durationNs: Long, requiredFps: Long): Boolean = try {
        Math.multiplyExact(count, NANOS_PER_SECOND) >= Math.multiplyExact(requiredFps, durationNs)
    } catch (_: ArithmeticException) {
        false
    }

    private fun deriveSample(
        sample: CompletedProbeSample,
        startNs: Long,
        endNs: Long,
        q2Start: Long,
        q3Start: Long,
        q4Start: Long,
    ): DerivedSample? {
        if (
            sample.receivedAtNs < startNs || sample.receivedAtNs >= endNs ||
            sample.windowAdmissionOrdinal < 0L ||
            sample.sourceTimestampNs < 0L || sample.submittedAtNs < 0L ||
            sample.completedAtNs < 0L || sample.poseCount < 0 ||
            sample.sourceTimestampNs > sample.receivedAtNs ||
            sample.receivedAtNs > sample.submittedAtNs ||
            sample.submittedAtNs > sample.completedAtNs
        ) {
            return null
        }

        val frameAgeAtReceive = checkedSubtract(sample.receivedAtNs, sample.sourceTimestampNs) ?: return null
        val frameAgeAtCompletion = checkedSubtract(sample.completedAtNs, sample.sourceTimestampNs) ?: return null
        val pipelineAge = checkedSubtract(sample.completedAtNs, sample.receivedAtNs) ?: return null
        val inferenceDuration = checkedSubtract(sample.completedAtNs, sample.submittedAtNs) ?: return null
        val quartile = when {
            sample.receivedAtNs < q2Start -> 1
            sample.receivedAtNs < q3Start -> 2
            sample.receivedAtNs < q4Start -> 3
            else -> 4
        }
        return DerivedSample(
            poseCount = sample.poseCount,
            quartile = quartile,
            inferenceDurationNs = inferenceDuration,
            pipelineAgeNs = pipelineAge,
            frameAgeAtReceiveNs = frameAgeAtReceive,
            frameAgeAtCompletionNs = frameAgeAtCompletion,
        )
    }

    private fun distributions(samples: List<DerivedSample>): TimingDistributions? {
        val inference = distribution(samples.map { it.inferenceDurationNs }) ?: return null
        val pipeline = distribution(samples.map { it.pipelineAgeNs }) ?: return null
        val atReceive = distribution(samples.map { it.frameAgeAtReceiveNs }) ?: return null
        val atCompletion = distribution(samples.map { it.frameAgeAtCompletionNs }) ?: return null
        return TimingDistributions(inference, pipeline, atReceive, atCompletion)
    }

    private fun distribution(values: Collection<Long>): IntegerDistribution? =
        when (val result = IntegerDistributionPolicy.reduce(values)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> null
        }

    private fun hasSourceRejectionOrProcessingFailure(counts: ProbeWindowCounts): Boolean =
        counts.sourceRejectedNegativeCount != 0L ||
            counts.sourceRejectedDuplicateCount != 0L ||
            counts.sourceRejectedOutOfOrderCount != 0L ||
            counts.sourceRejectedIdExhaustedCount != 0L ||
            counts.inputConversionErrorCount != 0L ||
            counts.inferenceErrorCount != 0L ||
            counts.completionTimeoutCount != 0L ||
            counts.callbackResourceErrorCount != 0L ||
            counts.attemptAbortedCount != 0L

    private fun checkedSum(vararg values: Long): Long? = try {
        values.fold(0L, Math::addExact)
    } catch (_: ArithmeticException) {
        null
    }

    private fun checkedAdd(left: Long, right: Long): Long? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun checkedSubtract(left: Long, right: Long): Long? = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun incomplete(reasons: Set<ProbeWindowIncompleteReason>): ProbeWindowEvaluation.Incomplete =
        ProbeWindowEvaluation.Incomplete(
            reasons.ifEmpty { setOf(ProbeWindowIncompleteReason.INVALID_WINDOW) }.toSet(),
        )

    private fun arithmeticIncomplete(): ProbeWindowEvaluation.Incomplete =
        ProbeWindowEvaluation.Incomplete(setOf(ProbeWindowIncompleteReason.ARITHMETIC_OVERFLOW))

    private data class DerivedSample(
        val poseCount: Int,
        val quartile: Int,
        val inferenceDurationNs: Long,
        val pipelineAgeNs: Long,
        val frameAgeAtReceiveNs: Long,
        val frameAgeAtCompletionNs: Long,
    )
}
