package com.motionarcade.core.motion

import com.motionarcade.core.contract.MotionType

data class MotionFixtureDetectedEvent(
    val eventId: String,
    val motionType: MotionType,
    val matchedOccurrenceId: String?,
    val eventTimestampNs: Long,
)

data class MotionFixtureEvaluatorReceipt(
    val receiptId: String,
    val evaluatorId: String,
    val fixtureId: String,
    val descriptorSha256: String,
    val sourceArtifactSha256: String,
    val occurrenceManifestSha256: String,
    val engineVersionSha256: String,
    val motionConfigSha256: String,
    val detectedEventsSha256: String,
)

fun interface MotionFixtureEvaluatorReceiptVerifier {
    fun isTrusted(receipt: MotionFixtureEvaluatorReceipt): Boolean

    companion object {
        val DENY_ALL = MotionFixtureEvaluatorReceiptVerifier { false }
    }
}

data class MotionFixtureEvaluation(
    val evidence: MotionFixtureReleaseEvidence,
    val detectedEvents: Collection<MotionFixtureDetectedEvent>,
    val evaluatorReceipt: MotionFixtureEvaluatorReceipt? = null,
)

data class MotionTypeQuality(
    val truePositives: Long,
    val falsePositives: Long,
    val falseNegatives: Long,
) {
    val precision: Double
        get() {
            val denominator = Math.addExact(truePositives, falsePositives)
            return if (denominator == 0L) 0.0 else truePositives.toDouble() / denominator
        }
    val recall: Double
        get() {
            val denominator = Math.addExact(truePositives, falseNegatives)
            return if (denominator == 0L) 0.0 else truePositives.toDouble() / denominator
        }
}

class MotionQualityGrade internal constructor(
    val byMotionType: Map<MotionType, MotionTypeQuality>,
    val confusionCounts: Map<Pair<MotionType, MotionType>, Long>,
    val neutralFalseEventsPerMinute: Double,
    val neutralFalseEventsPerMinuteBySubject: Map<String, Double>,
    val worstSubjectNeutralFalseEventsPerMinute: Double,
    val duplicateConfirmedEventCount: Long,
)

enum class MotionQualityGradingViolation {
    INPUT_LIMIT_EXCEEDED,
    INVALID_EVIDENCE,
    DUPLICATE_EVENT_ID,
    DUPLICATE_EVALUATION_FIXTURE_ID,
    DUPLICATE_EVALUATION_OCCURRENCE_ID,
    EVIDENCE_SET_MISMATCH,
    UNKNOWN_OCCURRENCE_REFERENCE,
    INVALID_EVENT_ID,
    INVALID_EVENT_TIMESTAMP,
    MISSING_TRUSTED_EVALUATOR_RECEIPT,
    ARITHMETIC_OVERFLOW,
}

sealed interface MotionQualityGradingResult {
    data class Graded(val grade: MotionQualityGrade) : MotionQualityGradingResult
    data class Rejected(val violations: Set<MotionQualityGradingViolation>) : MotionQualityGradingResult
}

sealed interface MotionReleaseEvaluationResult {
    data class Approved(val grade: MotionQualityGrade) : MotionReleaseEvaluationResult
    data class Rejected(
        val governanceViolations: Set<FixtureGovernanceViolation>,
        val gradingViolations: Set<MotionQualityGradingViolation>,
        val qualityViolations: Set<MotionQualityGateViolation>,
    ) : MotionReleaseEvaluationResult
}

enum class MotionQualityGateViolation {
    PRECISION_BELOW_RELEASE_MINIMUM,
    RECALL_BELOW_RELEASE_MINIMUM,
    NEUTRAL_FALSE_RATE_NOT_BELOW_ONE_PER_MINUTE,
    DUPLICATE_CONFIRMED_EVENT,
}

object MotionFixtureQualityGrader {
    private const val MAX_EVALUATIONS = 4_096
    private const val MIN_PRECISION = 0.90
    private const val MIN_RECALL = 0.85
    private const val MAX_EXCLUSIVE_NEUTRAL_FALSE_EVENTS_PER_MINUTE = 1.0

    fun evaluateRelease(
        fixtures: Collection<MotionFixtureDescriptor>,
        evidence: Collection<MotionFixtureReleaseEvidence>,
        evaluations: Collection<MotionFixtureEvaluation>,
        approvalVerifier: MotionFixtureCaptureApprovalVerifier =
            MotionFixtureCaptureApprovalVerifier.DENY_ALL,
        evaluatorVerifier: MotionFixtureEvaluatorReceiptVerifier =
            MotionFixtureEvaluatorReceiptVerifier.DENY_ALL,
    ): MotionReleaseEvaluationResult {
        val fixtureAdmission = MotionFixtureGovernance.admitFixtures(fixtures)
        if (fixtureAdmission is MotionFixtureCatalogAdmission.Rejected) {
            return MotionReleaseEvaluationResult.Rejected(
                setOf(FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED),
                emptySet(),
                emptySet(),
            )
        }
        fixtureAdmission as MotionFixtureCatalogAdmission.Admitted
        val admittedFixtures = fixtureAdmission.fixtures
        val evidenceAdmission = MotionFixtureGovernance.admitEvidence(evidence)
        if (evidenceAdmission is MotionFixtureEvidenceAdmission.Rejected) {
            return MotionReleaseEvaluationResult.Rejected(
                MotionFixtureGovernance.validate(admittedFixtures) + evidenceAdmission.violations,
                emptySet(),
                emptySet(),
            )
        }
        evidenceAdmission as MotionFixtureEvidenceAdmission.Admitted
        val admittedEvidence = evidenceAdmission.snapshots
        val governance = MotionFixtureGovernance.validateAdmittedReleaseEvidence(
            admittedFixtures,
            admittedEvidence,
            approvalVerifier,
        )
        if (governance.isNotEmpty()) {
            return MotionReleaseEvaluationResult.Rejected(governance, emptySet(), emptySet())
        }
        val evaluationAdmission = admitEvaluations(
            evaluations,
            evidenceAdmission.originalToSnapshot,
        )
        if (evaluationAdmission is EvaluationAdmission.Rejected) {
            return MotionReleaseEvaluationResult.Rejected(
                emptySet(),
                evaluationAdmission.violations,
                emptySet(),
            )
        }
        evaluationAdmission as EvaluationAdmission.Admitted
        val admittedEvaluations = evaluationAdmission.evaluations
        val evidenceById = admittedEvidence.associateBy { it.descriptor.fixtureId }
        val evaluationCounts = admittedEvaluations.groupingBy { it.evidence.descriptor.fixtureId }.eachCount()
        val setViolations = linkedSetOf<MotionQualityGradingViolation>()
        if (evaluationCounts.any { it.value > 1 }) {
            setViolations += MotionQualityGradingViolation.DUPLICATE_EVALUATION_FIXTURE_ID
        }
        if (evaluationCounts.keys != evidenceById.keys) {
            setViolations += MotionQualityGradingViolation.EVIDENCE_SET_MISMATCH
        }
        if (admittedEvaluations.any { evaluation ->
                !evaluatorReceiptMatches(evaluation) ||
                    !evaluatorVerifier.isTrusted(requireNotNull(evaluation.evaluatorReceipt))
            }
        ) {
            setViolations += MotionQualityGradingViolation.MISSING_TRUSTED_EVALUATOR_RECEIPT
        }
        if (setViolations.isNotEmpty()) {
            return MotionReleaseEvaluationResult.Rejected(emptySet(), setViolations, emptySet())
        }
        return when (val grading = gradeAdmitted(admittedEvaluations)) {
            is MotionQualityGradingResult.Rejected ->
                MotionReleaseEvaluationResult.Rejected(emptySet(), grading.violations, emptySet())
            is MotionQualityGradingResult.Graded -> {
                val quality = validateReleaseQuality(grading.grade)
                if (quality.isEmpty()) {
                    MotionReleaseEvaluationResult.Approved(grading.grade)
                } else {
                    MotionReleaseEvaluationResult.Rejected(emptySet(), emptySet(), quality)
                }
            }
        }
    }

    internal fun grade(evaluations: Collection<MotionFixtureEvaluation>): MotionQualityGradingResult {
        val declaredEvaluationCount = evaluations.size
        if (declaredEvaluationCount !in 0..MAX_EVALUATIONS) {
            return rejected(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED)
        }
        val bounded = ArrayList<MotionFixtureEvaluation>(declaredEvaluationCount)
        val iterator = evaluations.iterator()
        while (iterator.hasNext()) {
            if (bounded.size == MAX_EVALUATIONS) return rejected(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED)
            bounded += iterator.next()
        }
        val seenEvidence = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<MotionFixtureReleaseEvidence, Boolean>(),
        )
        val uniqueEvidence = bounded.mapNotNull { evaluation ->
            evaluation.evidence.takeIf(seenEvidence::add)
        }
        val evidenceAdmission = MotionFixtureGovernance.admitEvidence(uniqueEvidence)
        if (evidenceAdmission is MotionFixtureEvidenceAdmission.Rejected) {
            return MotionQualityGradingResult.Rejected(
                buildSet {
                    add(MotionQualityGradingViolation.INVALID_EVIDENCE)
                    if (FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED in evidenceAdmission.violations) {
                        add(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED)
                    }
                    if (FixtureGovernanceViolation.ARITHMETIC_OVERFLOW in evidenceAdmission.violations) {
                        add(MotionQualityGradingViolation.ARITHMETIC_OVERFLOW)
                    }
                },
            )
        }
        evidenceAdmission as MotionFixtureEvidenceAdmission.Admitted
        return when (val admitted = admitEvaluations(bounded, evidenceAdmission.originalToSnapshot)) {
            is EvaluationAdmission.Rejected -> MotionQualityGradingResult.Rejected(admitted.violations)
            is EvaluationAdmission.Admitted -> gradeAdmitted(admitted.evaluations)
        }
    }

    private fun gradeAdmitted(
        evaluations: Collection<MotionFixtureEvaluation>,
    ): MotionQualityGradingResult {
        val violations = linkedSetOf<MotionQualityGradingViolation>()
        val eventIds = linkedSetOf<String>()
        val fixtureIds = linkedSetOf<String>()
        val occurrenceIds = linkedSetOf<String>()
        val trialIds = linkedSetOf<String>()
        evaluations.forEach { evaluation ->
            if (!MotionFixtureGovernance.isValidEvidence(evaluation.evidence)) {
                violations += MotionQualityGradingViolation.INVALID_EVIDENCE
            }
            if (!fixtureIds.add(evaluation.evidence.descriptor.fixtureId)) {
                violations += MotionQualityGradingViolation.DUPLICATE_EVALUATION_FIXTURE_ID
            }
            evaluation.evidence.occurrences.forEach { occurrence ->
                if (!occurrenceIds.add(occurrence.occurrenceId) || !trialIds.add(occurrence.trialId)) {
                    violations += MotionQualityGradingViolation.DUPLICATE_EVALUATION_OCCURRENCE_ID
                }
            }
            val occurrencesById = evaluation.evidence.occurrences.associateBy { it.occurrenceId }
            evaluation.detectedEvents.forEach { event ->
                if (!event.eventId.isSafeEvidenceId()) {
                    violations += MotionQualityGradingViolation.INVALID_EVENT_ID
                }
                if (!eventIds.add(event.eventId)) violations += MotionQualityGradingViolation.DUPLICATE_EVENT_ID
                val matched = event.matchedOccurrenceId?.let(occurrencesById::get)
                if (event.matchedOccurrenceId != null && matched == null) {
                    violations += MotionQualityGradingViolation.UNKNOWN_OCCURRENCE_REFERENCE
                }
                if (
                    event.eventTimestampNs < 0L ||
                    event.eventTimestampNs >= evaluation.evidence.descriptor.durationNs ||
                    (matched != null && event.eventTimestampNs !in matched.startNs until matched.endExclusiveNs)
                ) {
                    violations += MotionQualityGradingViolation.INVALID_EVENT_TIMESTAMP
                }
            }
        }
        if (violations.isNotEmpty()) return MotionQualityGradingResult.Rejected(violations)
        return try {
            gradeValidated(evaluations)
        } catch (_: ArithmeticException) {
            rejected(MotionQualityGradingViolation.ARITHMETIC_OVERFLOW)
        }
    }

    internal fun validateReleaseQuality(grade: MotionQualityGrade): Set<MotionQualityGateViolation> {
        val violations = linkedSetOf<MotionQualityGateViolation>()
        MotionFixtureGovernance.requiredMotionTypes.forEach { type ->
            val quality = grade.byMotionType.getValue(type)
            if (quality.precision < MIN_PRECISION) {
                violations += MotionQualityGateViolation.PRECISION_BELOW_RELEASE_MINIMUM
            }
            if (quality.recall < MIN_RECALL) {
                violations += MotionQualityGateViolation.RECALL_BELOW_RELEASE_MINIMUM
            }
        }
        if (
            grade.neutralFalseEventsPerMinute >= MAX_EXCLUSIVE_NEUTRAL_FALSE_EVENTS_PER_MINUTE ||
            grade.neutralFalseEventsPerMinuteBySubject.values.any {
                it >= MAX_EXCLUSIVE_NEUTRAL_FALSE_EVENTS_PER_MINUTE
            }
        ) {
            violations += MotionQualityGateViolation.NEUTRAL_FALSE_RATE_NOT_BELOW_ONE_PER_MINUTE
        }
        if (grade.duplicateConfirmedEventCount != 0L) {
            violations += MotionQualityGateViolation.DUPLICATE_CONFIRMED_EVENT
        }
        return violations
    }

    private fun gradeValidated(evaluations: Collection<MotionFixtureEvaluation>): MotionQualityGradingResult {
        val truePositives = MotionType.entries.associateWith { 0L }.toMutableMap()
        val falsePositives = MotionType.entries.associateWith { 0L }.toMutableMap()
        val falseNegatives = MotionType.entries.associateWith { 0L }.toMutableMap()
        val confusion = linkedMapOf<Pair<MotionType, MotionType>, Long>()
        val neutralDurationBySubject = linkedMapOf<String, Long>()
        val neutralEventsBySubject = linkedMapOf<String, Long>()
        var duplicateConfirmedEvents = 0L
        evaluations.forEach { evaluation ->
            val descriptor = evaluation.evidence.descriptor
            when (descriptor.label) {
                FixtureLabel.POSITIVE -> {
                    val expected = requireNotNull(descriptor.motionType)
                    val eventsByOccurrence = evaluation.detectedEvents.groupBy { it.matchedOccurrenceId }
                    evaluation.evidence.occurrences.forEach { occurrence ->
                        val events = eventsByOccurrence[occurrence.occurrenceId].orEmpty()
                        val correct = events.filter { it.motionType == expected }
                        if (correct.isEmpty()) {
                            falseNegatives.add(expected, 1L)
                        } else {
                            truePositives.add(expected, 1L)
                            if (correct.size > 1) {
                                val duplicates = correct.size.toLong() - 1L
                                duplicateConfirmedEvents = Math.addExact(duplicateConfirmedEvents, duplicates)
                                falsePositives.add(expected, duplicates)
                            }
                        }
                        events.filter { it.motionType != expected }.forEach { event ->
                            falsePositives.add(event.motionType, 1L)
                            confusion.add(expected to event.motionType, 1L)
                        }
                    }
                    eventsByOccurrence[null].orEmpty().forEach { event ->
                        falsePositives.add(event.motionType, 1L)
                    }
                }
                FixtureLabel.HARD_NEGATIVE -> evaluation.detectedEvents.forEach { event ->
                    falsePositives.add(event.motionType, 1L)
                }
                FixtureLabel.NEUTRAL -> {
                    val subject = descriptor.subjectId
                    neutralDurationBySubject.add(subject, descriptor.durationNs)
                    neutralEventsBySubject.add(subject, evaluation.detectedEvents.size.toLong())
                    evaluation.detectedEvents.forEach { event -> falsePositives.add(event.motionType, 1L) }
                }
            }
        }
        val perSubject = neutralDurationBySubject.mapValues { (subject, duration) ->
            ratePerMinute(neutralEventsBySubject[subject] ?: 0L, duration)
        }
        val totalNeutralDuration = neutralDurationBySubject.values.fold(0L, Math::addExact)
        val totalNeutralEvents = neutralEventsBySubject.values.fold(0L, Math::addExact)
        val grade = MotionQualityGrade(
            byMotionType = MotionType.entries.associateWith { type ->
                MotionTypeQuality(
                    truePositives.getValue(type),
                    falsePositives.getValue(type),
                    falseNegatives.getValue(type),
                )
            },
            confusionCounts = confusion.toMap(),
            neutralFalseEventsPerMinute = ratePerMinute(totalNeutralEvents, totalNeutralDuration),
            neutralFalseEventsPerMinuteBySubject = perSubject,
            worstSubjectNeutralFalseEventsPerMinute = perSubject.values.maxOrNull() ?: 0.0,
            duplicateConfirmedEventCount = duplicateConfirmedEvents,
        )
        return MotionQualityGradingResult.Graded(grade)
    }

    private fun ratePerMinute(events: Long, durationNs: Long): Double =
        if (durationNs == 0L) 0.0 else events.toDouble() * 60_000_000_000.0 / durationNs.toDouble()

    private fun MutableMap<MotionType, Long>.add(type: MotionType, amount: Long) {
        this[type] = Math.addExact(getValue(type), amount)
    }

    private fun MutableMap<Pair<MotionType, MotionType>, Long>.add(
        key: Pair<MotionType, MotionType>,
        amount: Long,
    ) {
        this[key] = Math.addExact(this[key] ?: 0L, amount)
    }

    private fun MutableMap<String, Long>.add(key: String, amount: Long) {
        this[key] = Math.addExact(this[key] ?: 0L, amount)
    }

    private fun rejected(violation: MotionQualityGradingViolation) =
        MotionQualityGradingResult.Rejected(setOf(violation))

    private fun admitEvaluations(
        evaluations: Collection<MotionFixtureEvaluation>,
        originalToSnapshot: Map<MotionFixtureReleaseEvidence, MotionFixtureReleaseEvidence>,
    ): EvaluationAdmission {
        val declaredEvaluationCount = evaluations.size
        if (declaredEvaluationCount !in 0..MAX_EVALUATIONS) {
            return evaluationLimitRejection()
        }
        val boundedEvaluations = ArrayList<MotionFixtureEvaluation>(declaredEvaluationCount)
        val iterator = evaluations.iterator()
        while (iterator.hasNext()) {
            if (boundedEvaluations.size == MAX_EVALUATIONS) return evaluationLimitRejection()
            boundedEvaluations += iterator.next()
        }
        val evaluationsWithDeclaredEventCount = boundedEvaluations.map { evaluation ->
            evaluation to evaluation.detectedEvents.size
        }
        if (evaluationsWithDeclaredEventCount.any { (_, count) -> count !in 0..MAX_TOTAL_RELEASE_EVENTS }) {
            return evaluationLimitRejection()
        }
        val suppliedEvents = evaluationsWithDeclaredEventCount.sumExactOrNull { (_, count) -> count.toLong() }
        if (suppliedEvents == null || suppliedEvents > MAX_TOTAL_RELEASE_EVENTS) {
            return MotionFixtureQualityGrader.EvaluationAdmission.Rejected(
                buildSet {
                    add(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED)
                    if (suppliedEvents == null) add(MotionQualityGradingViolation.ARITHMETIC_OVERFLOW)
                },
            )
        }
        var actualEventCount = 0
        val snapshots = ArrayList<MotionFixtureEvaluation>(boundedEvaluations.size)
        evaluationsWithDeclaredEventCount.forEach { (source, suppliedEventCount) ->
            val evidenceSnapshot = originalToSnapshot[source.evidence]
                ?: return EvaluationAdmission.Rejected(
                    setOf(MotionQualityGradingViolation.EVIDENCE_SET_MISMATCH),
                )
            val eventSnapshot = ArrayList<MotionFixtureDetectedEvent>(suppliedEventCount)
            val eventIterator = source.detectedEvents.iterator()
            while (eventIterator.hasNext()) {
                if (actualEventCount == MAX_TOTAL_RELEASE_EVENTS) return evaluationLimitRejection()
                eventSnapshot += eventIterator.next()
                actualEventCount += 1
            }
            if (eventSnapshot.size != suppliedEventCount) return evaluationLimitRejection()
            snapshots += MotionFixtureEvaluation(
                evidence = evidenceSnapshot,
                detectedEvents = eventSnapshot.toList(),
                evaluatorReceipt = source.evaluatorReceipt,
            )
        }
        return EvaluationAdmission.Admitted(snapshots)
    }

    private fun evaluationLimitRejection() = EvaluationAdmission.Rejected(
        setOf(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED),
    )

    private sealed interface EvaluationAdmission {
        data class Admitted(val evaluations: List<MotionFixtureEvaluation>) : EvaluationAdmission
        data class Rejected(
            val violations: Set<MotionQualityGradingViolation>,
        ) : EvaluationAdmission
    }

    internal fun computeDetectedEventsSha256(
        events: Collection<MotionFixtureDetectedEvent>,
    ): String {
        val canonical = buildString {
            append("motion-arcade-detected-events-v1\n")
            events.sortedBy(MotionFixtureDetectedEvent::eventId).forEach { event ->
                append("event=").append(event.eventId).append('\t')
                    .append(event.motionType.name).append('\t')
                    .append(event.matchedOccurrenceId ?: "-").append('\t')
                    .append(event.eventTimestampNs).append('\n')
            }
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun evaluatorReceiptMatches(evaluation: MotionFixtureEvaluation): Boolean {
        val receipt = evaluation.evaluatorReceipt ?: return false
        val descriptor = evaluation.evidence.descriptor
        return receipt.receiptId.isSafeEvidenceId() && receipt.evaluatorId.isSafeEvidenceId() &&
            receipt.fixtureId == descriptor.fixtureId &&
            receipt.descriptorSha256 == MotionFixtureGovernance.computeDescriptorSha256(descriptor) &&
            receipt.sourceArtifactSha256 == descriptor.sourceArtifactSha256 &&
            receipt.occurrenceManifestSha256 == descriptor.occurrenceManifestSha256 &&
            receipt.engineVersionSha256.matches(SHA256_HEX_PATTERN) &&
            receipt.motionConfigSha256.matches(SHA256_HEX_PATTERN) &&
            receipt.detectedEventsSha256 == computeDetectedEventsSha256(evaluation.detectedEvents)
    }

    private inline fun <T> Collection<T>.sumExactOrNull(selector: (T) -> Long): Long? = try {
        fold(0L) { total, value -> Math.addExact(total, selector(value)) }
    } catch (_: ArithmeticException) {
        null
    }
}

internal const val MAX_TOTAL_RELEASE_EVENTS: Int = 100_000
private val SHA256_HEX_PATTERN = Regex("[0-9a-f]{64}")
