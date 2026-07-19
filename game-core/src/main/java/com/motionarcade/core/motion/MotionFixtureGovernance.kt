package com.motionarcade.core.motion

import com.motionarcade.core.contract.MotionType

enum class FixturePartition { TUNE, HOLDOUT }

enum class FixtureLabel { POSITIVE, HARD_NEGATIVE, NEUTRAL }

data class MotionFixtureDescriptor(
    val fixtureId: String,
    val partition: FixturePartition,
    val subjectId: String,
    val sessionId: String,
    val label: FixtureLabel,
    val durationNs: Long,
    val motionType: MotionType?,
)

enum class FixtureGovernanceViolation {
    INVALID_DESCRIPTOR,
    DUPLICATE_FIXTURE_ID,
    SUBJECT_PARTITION_LEAK,
    SESSION_PARTITION_LEAK,
    MISSING_TUNE_HARD_NEGATIVE_COVERAGE,
    MISSING_HOLDOUT_HARD_NEGATIVE_COVERAGE,
    MISSING_TUNE_SIXTY_SECOND_NEUTRAL,
    MISSING_HOLDOUT_SIXTY_SECOND_NEUTRAL,
    MISSING_SUBJECT_SIXTY_SECOND_NEUTRAL,
    INSUFFICIENT_TUNE_MOTION_COVERAGE,
    INSUFFICIENT_HOLDOUT_MOTION_COVERAGE,
}

object MotionFixtureGovernance {
    private const val REQUIRED_NEUTRAL_NS = 60_000_000_000L
    private const val REQUIRED_MOTION_TYPES = 6

    fun validate(fixtures: Collection<MotionFixtureDescriptor>): Set<FixtureGovernanceViolation> {
        val violations = linkedSetOf<FixtureGovernanceViolation>()
        if (fixtures.any { !it.isValid() }) {
            violations += FixtureGovernanceViolation.INVALID_DESCRIPTOR
        }
        if (fixtures.groupingBy { it.fixtureId }.eachCount().any { it.value > 1 }) {
            violations += FixtureGovernanceViolation.DUPLICATE_FIXTURE_ID
        }
        if (fixtures.groupBy { it.subjectId }.values.any { rows -> rows.map { it.partition }.distinct().size > 1 }) {
            violations += FixtureGovernanceViolation.SUBJECT_PARTITION_LEAK
        }
        if (fixtures.groupBy { it.sessionId }.values.any { rows -> rows.map { it.partition }.distinct().size > 1 }) {
            violations += FixtureGovernanceViolation.SESSION_PARTITION_LEAK
        }
        val positiveCoverage = fixtures
            .filter { it.label == FixtureLabel.POSITIVE }
            .groupBy { it.partition }
            .mapValues { (_, rows) -> rows.mapNotNull { it.motionType }.toSet() }
        if (positiveCoverage[FixturePartition.TUNE].orEmpty().size < REQUIRED_MOTION_TYPES) {
            violations += FixtureGovernanceViolation.INSUFFICIENT_TUNE_MOTION_COVERAGE
        }
        if (positiveCoverage[FixturePartition.HOLDOUT].orEmpty().size < REQUIRED_MOTION_TYPES) {
            violations += FixtureGovernanceViolation.INSUFFICIENT_HOLDOUT_MOTION_COVERAGE
        }
        val hardNegativeCoverage = fixtures
            .filter { it.label == FixtureLabel.HARD_NEGATIVE }
            .groupBy { it.partition }
            .mapValues { (_, rows) -> rows.mapNotNull { it.motionType }.toSet() }
        if (!hardNegativeCoverage[FixturePartition.TUNE].orEmpty().containsAll(
                positiveCoverage[FixturePartition.TUNE].orEmpty(),
            )
        ) {
            violations += FixtureGovernanceViolation.MISSING_TUNE_HARD_NEGATIVE_COVERAGE
        }
        if (!hardNegativeCoverage[FixturePartition.HOLDOUT].orEmpty().containsAll(
                positiveCoverage[FixturePartition.HOLDOUT].orEmpty(),
            )
        ) {
            violations += FixtureGovernanceViolation.MISSING_HOLDOUT_HARD_NEGATIVE_COVERAGE
        }
        FixturePartition.entries.forEach { partition ->
            if (
                fixtures.none {
                    it.partition == partition &&
                        it.label == FixtureLabel.NEUTRAL &&
                        it.durationNs >= REQUIRED_NEUTRAL_NS
                }
            ) {
                violations += when (partition) {
                    FixturePartition.TUNE ->
                        FixtureGovernanceViolation.MISSING_TUNE_SIXTY_SECOND_NEUTRAL
                    FixturePartition.HOLDOUT ->
                        FixtureGovernanceViolation.MISSING_HOLDOUT_SIXTY_SECOND_NEUTRAL
                }
            }
        }
        fixtures.groupBy { it.partition to it.subjectId }.values.forEach { subjectRows ->
            if (
                subjectRows.none {
                    it.label == FixtureLabel.NEUTRAL && it.durationNs >= REQUIRED_NEUTRAL_NS
                }
            ) {
                violations += FixtureGovernanceViolation.MISSING_SUBJECT_SIXTY_SECOND_NEUTRAL
            }
        }
        return violations
    }

    private fun MotionFixtureDescriptor.isValid(): Boolean =
        fixtureId.isNotBlank() && fixtureId.length <= 120 &&
            subjectId.isNotBlank() && subjectId.length <= 120 &&
            sessionId.isNotBlank() && sessionId.length <= 120 &&
            durationNs > 0L &&
            when (label) {
                FixtureLabel.POSITIVE, FixtureLabel.HARD_NEGATIVE -> motionType != null
                FixtureLabel.NEUTRAL -> motionType == null
            }
}
