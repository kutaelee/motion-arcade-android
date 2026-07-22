package com.motionarcade.core.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import java.security.MessageDigest
import java.util.AbstractCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFixtureGovernanceTest {
    private enum class SignalPattern { POSITIVE_HOLD, SHORT_SPIKES, CONSTANT_NEUTRAL }

    private data class ExecutableFixture(
        val descriptor: MotionFixtureDescriptor,
        val stepNs: Long,
        val pattern: SignalPattern,
    ) {
        fun samples(): List<MotionSignalSample> {
            require(descriptor.durationNs % stepNs == 0L)
            val type = descriptor.motionType ?: MotionType.FISH_CAST
            return (0L..descriptor.durationNs step stepNs).mapIndexed { index, timestamp ->
                val activation = when (pattern) {
                    SignalPattern.POSITIVE_HOLD -> when (index) {
                        1, 2, 3 -> 0.9f
                        else -> 0.1f
                    }
                    SignalPattern.SHORT_SPIKES -> if (index % 10 == 1) 0.9f else 0.1f
                    SignalPattern.CONSTANT_NEUTRAL -> 0.1f
                }
                MotionSignalSample(
                    playerId = PlayerId.P1,
                    type = type,
                    timestampNs = timestamp,
                    activation = activation,
                    quality = 0.8f,
                    confidence = 0.9f,
                    calibrationRevision = 1,
                    source = InputSource.FIXTURE,
                )
            }
        }
    }

    @Test
    fun tuneAndHoldoutSubjectsAndSessionsAreDisjoint() {
        assertTrue(MotionFixtureGovernance.validate(validCatalog()).isEmpty())
    }

    @Test
    fun subjectOrSessionLeakIsRejected() {
        val subjectLeak = validCatalog() + descriptor(
            id = "leak-subject",
            partition = FixturePartition.HOLDOUT,
            subject = "tune-person",
            session = "holdout-new-session",
        )
        val sessionLeak = validCatalog() + descriptor(
            id = "leak-session",
            partition = FixturePartition.HOLDOUT,
            subject = "holdout-new-person",
            session = "tune-positive-session",
        )

        assertTrue(
            FixtureGovernanceViolation.SUBJECT_PARTITION_LEAK in
                MotionFixtureGovernance.validate(subjectLeak),
        )
        assertTrue(
            FixtureGovernanceViolation.SESSION_PARTITION_LEAK in
                MotionFixtureGovernance.validate(sessionLeak),
        )
    }

    @Test
    fun executableFixtureCatalogCoversPositiveHardNegativeAndNeutralTimelines() {
        val stream = requireNotNull(
            javaClass.getResourceAsStream("/motion-fixtures/signal-fixtures.tsv"),
        )
        val fixtures = stream.bufferedReader().useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).map(::parse).toList()
        }
        assertEquals(26, fixtures.size)
        assertEquals(
            setOf(
                FixtureGovernanceViolation.INSUFFICIENT_TUNE_MOTION_COVERAGE,
                FixtureGovernanceViolation.INSUFFICIENT_HOLDOUT_MOTION_COVERAGE,
                FixtureGovernanceViolation.MISSING_TUNE_HARD_NEGATIVE_COVERAGE,
                FixtureGovernanceViolation.MISSING_HOLDOUT_HARD_NEGATIVE_COVERAGE,
            ),
            MotionFixtureGovernance.validate(fixtures.map { it.descriptor }),
        )

        fixtures.forEach { fixture ->
            val samples = fixture.samples()
            assertEquals(0L, samples.first().timestampNs)
            assertEquals(fixture.descriptor.durationNs, samples.last().timestampNs)
            if (fixture.descriptor.label == FixtureLabel.HARD_NEGATIVE) {
                assertTrue(samples.any { it.activation >= 0.75f })
            }
            val engine = MotionGestureEngine(
                sessionId = fixture.descriptor.fixtureId,
                definitions = DevelopmentGestureDefinitions.all,
                activeCalibrationRevision = 1,
            )
            val events = samples.mapNotNull { sample ->
                (
                    engine.processFrame(completeFrame(sample)).first() as?
                        MotionSampleResult.Emitted
                )?.event
            }
            val expectedEvents =
                if (fixture.descriptor.label == FixtureLabel.POSITIVE) 1 else 0
            assertEquals(fixture.descriptor.fixtureId, expectedEvents, events.size)
        }
    }

    @Test
    fun everyExecutableMotionTypeIsRequiredInsteadOfAnyEqualSizedSubset() {
        val valid = validCatalog()
        val missing = valid.filterNot {
            it.partition == FixturePartition.HOLDOUT &&
                it.label == FixtureLabel.POSITIVE &&
                it.motionType == MotionType.TEAM_ULTIMATE
        }
        val duplicateReplacement = missing + descriptor(
            id = "replacement-that-does-not-fill-required-type",
            partition = FixturePartition.HOLDOUT,
            motionType = MotionType.FISH_CAST,
        )

        assertTrue(
            FixtureGovernanceViolation.INSUFFICIENT_HOLDOUT_MOTION_COVERAGE in
                MotionFixtureGovernance.validate(missing),
        )
        assertTrue(
            FixtureGovernanceViolation.INSUFFICIENT_HOLDOUT_MOTION_COVERAGE in
                MotionFixtureGovernance.validate(duplicateReplacement),
        )
        assertEquals(MotionType.entries.toSet(), MotionFixtureGovernance.requiredMotionTypes)
    }

    @Test
    fun syntheticHoldoutCanNeverSatisfyReleaseEvidence() {
        val catalog = validCatalog()
        val violations = MotionFixtureGovernance.validateReleaseEvidence(catalog, emptyList())

        assertTrue(FixtureGovernanceViolation.SYNTHETIC_HOLDOUT_NOT_RELEASE_EVIDENCE in violations)
        assertTrue(FixtureGovernanceViolation.INSUFFICIENT_RECORDED_HOLDOUT_POSITIVES in violations)
        assertTrue(FixtureGovernanceViolation.INSUFFICIENT_RECORDED_HOLDOUT_HARD_NEGATIVES in violations)
        assertTrue(FixtureGovernanceViolation.MISSING_RECORDED_HOLDOUT_SIXTY_SECOND_NEUTRAL in violations)

        val relabeledOnly = catalog.map { fixture ->
            fixture.copy(
                sourceKind = FixtureSourceKind.RECORDED,
                occurrenceCount = if (fixture.label == FixtureLabel.NEUTRAL) 0 else 30,
            )
        }
        val relabeledViolations = MotionFixtureGovernance.validateReleaseEvidence(
            relabeledOnly,
            emptyList(),
        )
        assertTrue(FixtureGovernanceViolation.INVALID_DESCRIPTOR in relabeledViolations)
        assertTrue(
            FixtureGovernanceViolation.MISSING_OR_INVALID_RECORDED_ARTIFACT in relabeledViolations,
        )
    }

    @Test
    fun capturedArtifactDigestSessionAndOccurrenceIdentityAreAllRequired() {
        val (catalog, evidence) = recordedReleaseCatalog()
        val defaultViolations = MotionFixtureGovernance.validateReleaseEvidence(catalog, evidence)
        assertTrue(
            FixtureGovernanceViolation.MISSING_TRUSTED_CAPTURE_APPROVAL in defaultViolations,
        )
        val structuralTestOnlyVerifier = MotionFixtureCaptureApprovalVerifier { approval ->
            approval.reviewerId == STRUCTURAL_TEST_ONLY_REVIEWER
        }
        assertTrue(
            MotionFixtureGovernance.validateReleaseEvidence(
                catalog,
                evidence,
                structuralTestOnlyVerifier,
            ).isEmpty(),
        )

        val target = evidence.first { it.occurrences.isNotEmpty() }
        val descriptor = target.descriptor
        val targetBytes = structuralArtifactBytes(target.descriptor)
        val mutatedBytes = targetBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val badDigestEvidence = evidence - target + MotionFixtureReleaseEvidence(
            descriptor,
            mutatedBytes,
            target.occurrences,
        )
        assertTrue(
            FixtureGovernanceViolation.MISSING_OR_INVALID_RECORDED_ARTIFACT in
                MotionFixtureGovernance.validateReleaseEvidence(catalog, badDigestEvidence),
        )

        val wrongSession = target.occurrences.first().copy(sessionId = "wrong-session")
        val wrongSessionEvidence = evidence - target + MotionFixtureReleaseEvidence(
            descriptor,
            targetBytes,
            listOf(wrongSession) + target.occurrences.drop(1),
        )
        assertTrue(
            FixtureGovernanceViolation.INVALID_OCCURRENCE in
                MotionFixtureGovernance.validateReleaseEvidence(catalog, wrongSessionEvidence),
        )

        val duplicate = target.occurrences.first().copy(
            occurrenceId = evidence.last { it.occurrences.isNotEmpty() }.occurrences.first().occurrenceId,
        )
        val duplicateEvidence = evidence - target + MotionFixtureReleaseEvidence(
            descriptor,
            targetBytes,
            listOf(duplicate) + target.occurrences.drop(1),
        )
        assertTrue(
            FixtureGovernanceViolation.DUPLICATE_OCCURRENCE_ID in
                MotionFixtureGovernance.validateReleaseEvidence(catalog, duplicateEvidence),
        )
    }

    @Test
    fun qualityGraderSeparatesRecallConfusionHardNegativeAndNeutralFalseRate() {
        val castPositive = recordedEvidence(descriptor(
            id = "cast-positive-3",
            partition = FixturePartition.HOLDOUT,
            motionType = MotionType.FISH_CAST,
            occurrenceCount = 3,
        ))
        val jabNegative = recordedEvidence(descriptor(
            id = "jab-negative-2",
            partition = FixturePartition.HOLDOUT,
            label = FixtureLabel.HARD_NEGATIVE,
            motionType = MotionType.PUNCH_JAB,
            occurrenceCount = 2,
        ))
        val neutral = recordedEvidence(descriptor(
            id = "neutral-120s",
            partition = FixturePartition.HOLDOUT,
            label = FixtureLabel.NEUTRAL,
            motionType = null,
            durationNs = 120_000_000_000L,
            occurrenceCount = 0,
        ))
        val result = MotionFixtureQualityGrader.grade(
            listOf(
                MotionFixtureEvaluation(
                    castPositive,
                    listOf(
                        event("cast-1", MotionType.FISH_CAST, castPositive.occurrences.elementAt(0)),
                        event("cast-2", MotionType.FISH_CAST, castPositive.occurrences.elementAt(1)),
                        event("hook-confusion", MotionType.PUNCH_HOOK, castPositive.occurrences.elementAt(1)),
                    ),
                ),
                MotionFixtureEvaluation(
                    jabNegative,
                    listOf(
                        event("jab-fp-1", MotionType.PUNCH_JAB, jabNegative.occurrences.elementAt(0)),
                        event("jab-fp-2", MotionType.PUNCH_JAB, jabNegative.occurrences.elementAt(1)),
                    ),
                ),
                MotionFixtureEvaluation(
                    neutral,
                    listOf(
                        MotionFixtureDetectedEvent("neutral-fp-1", MotionType.PUNCH_HOOK, null, 0L),
                        MotionFixtureDetectedEvent("neutral-fp-2", MotionType.PUNCH_HOOK, null, 1L),
                    ),
                ),
            ),
        )
        val grade = (result as MotionQualityGradingResult.Graded).grade

        assertEquals(MotionTypeQuality(2L, 0L, 1L), grade.byMotionType.getValue(MotionType.FISH_CAST))
        assertEquals(MotionTypeQuality(0L, 3L, 0L), grade.byMotionType.getValue(MotionType.PUNCH_HOOK))
        assertEquals(MotionTypeQuality(0L, 2L, 0L), grade.byMotionType.getValue(MotionType.PUNCH_JAB))
        assertEquals(1L, grade.confusionCounts[MotionType.FISH_CAST to MotionType.PUNCH_HOOK])
        assertEquals(1.0, grade.neutralFalseEventsPerMinute, 0.0)
        assertEquals(2.0 / 3.0, grade.byMotionType.getValue(MotionType.FISH_CAST).recall, 0.0)
        assertEquals(1.0, grade.byMotionType.getValue(MotionType.FISH_CAST).precision, 0.0)
    }

    @Test
    fun duplicateConfirmedEventsAndPerSubjectNeutralOutlierFailTheReleaseGate() {
        val positive = recordedEvidence(
            descriptor(
                id = "duplicate-positive",
                partition = FixturePartition.HOLDOUT,
                motionType = MotionType.FISH_CAST,
            ),
        )
        val quietNeutral = recordedEvidence(
            descriptor(
                id = "quiet-neutral",
                partition = FixturePartition.HOLDOUT,
                subject = "quiet-subject",
                session = "quiet-session",
                label = FixtureLabel.NEUTRAL,
                motionType = null,
                durationNs = 120_000_000_000L,
                occurrenceCount = 0,
            ),
        )
        val noisyNeutral = recordedEvidence(
            descriptor(
                id = "noisy-neutral",
                partition = FixturePartition.HOLDOUT,
                subject = "noisy-subject",
                session = "noisy-session",
                label = FixtureLabel.NEUTRAL,
                motionType = null,
                durationNs = 60_000_000_000L,
                occurrenceCount = 0,
            ),
        )
        val result = MotionFixtureQualityGrader.grade(
            listOf(
                MotionFixtureEvaluation(
                    positive,
                    listOf(
                        event("duplicate-a", MotionType.FISH_CAST, positive.occurrences.single()),
                        event("duplicate-b", MotionType.FISH_CAST, positive.occurrences.single()),
                    ),
                ),
                MotionFixtureEvaluation(quietNeutral, emptyList()),
                MotionFixtureEvaluation(
                    noisyNeutral,
                    listOf(MotionFixtureDetectedEvent("noisy-fp", MotionType.PUNCH_JAB, null, 0L)),
                ),
            ),
        )
        val grade = (result as MotionQualityGradingResult.Graded).grade
        val gate = MotionFixtureQualityGrader.validateReleaseQuality(grade)

        assertEquals(1L, grade.duplicateConfirmedEventCount)
        assertTrue(grade.neutralFalseEventsPerMinute < 1.0)
        assertEquals(1.0, grade.neutralFalseEventsPerMinuteBySubject.getValue("noisy-subject"), 0.0)
        assertTrue(MotionQualityGateViolation.DUPLICATE_CONFIRMED_EVENT in gate)
        assertTrue(
            MotionQualityGateViolation.NEUTRAL_FALSE_RATE_NOT_BELOW_ONE_PER_MINUTE in gate,
        )
    }

    @Test
    fun malformedEvidenceAndDuplicateEventIdsAreTypedRejections() {
        val evidence = recordedEvidence(
            descriptor(
                id = "typed-rejection",
                partition = FixturePartition.HOLDOUT,
                motionType = MotionType.FISH_CAST,
            ),
        )
        val occurrence = evidence.occurrences.single()
        val result = MotionFixtureQualityGrader.grade(
            listOf(
                MotionFixtureEvaluation(
                    evidence,
                    listOf(
                        event("same-event", MotionType.FISH_CAST, occurrence),
                        event("same-event", MotionType.FISH_CAST, occurrence),
                    ),
                ),
            ),
        )

        assertTrue(result is MotionQualityGradingResult.Rejected)
        assertTrue(
            MotionQualityGradingViolation.DUPLICATE_EVENT_ID in
                (result as MotionQualityGradingResult.Rejected).violations,
        )

        val malformed = MotionFixtureReleaseEvidence(
            evidence.descriptor,
            "mutated-artifact".toByteArray(),
            evidence.occurrences,
            evidence.captureApproval,
        )
        val malformedResult = MotionFixtureQualityGrader.grade(
            listOf(MotionFixtureEvaluation(malformed, emptyList())),
        ) as MotionQualityGradingResult.Rejected
        assertTrue(MotionQualityGradingViolation.INVALID_EVIDENCE in malformedResult.violations)
    }

    @Test
    fun compositeReleaseEvaluatorRejectsMissingTrustAndDuplicateEvidenceReuse() {
        val (catalog, evidence) = recordedReleaseCatalog()
        val evaluations = perfectStructuralEvaluations(evidence)

        val denied = MotionFixtureQualityGrader.evaluateRelease(catalog, evidence, evaluations)
            as MotionReleaseEvaluationResult.Rejected
        assertTrue(
            FixtureGovernanceViolation.MISSING_TRUSTED_CAPTURE_APPROVAL in
                denied.governanceViolations,
        )

        val structuralTestOnlyVerifier = MotionFixtureCaptureApprovalVerifier { approval ->
            approval.reviewerId == STRUCTURAL_TEST_ONLY_REVIEWER
        }
        assertTrue(
            FixtureGovernanceViolation.DUPLICATE_RELEASE_EVIDENCE in
                MotionFixtureGovernance.validateReleaseEvidence(
                    catalog,
                    evidence + evidence.first(),
                    structuralTestOnlyVerifier,
                ),
        )
        val withoutTrustedEvaluator = MotionFixtureQualityGrader.evaluateRelease(
            catalog,
            evidence,
            evaluations,
            structuralTestOnlyVerifier,
        ) as MotionReleaseEvaluationResult.Rejected
        assertTrue(
            MotionQualityGradingViolation.MISSING_TRUSTED_EVALUATOR_RECEIPT in
                withoutTrustedEvaluator.gradingViolations,
        )
        val structuralEvaluatorVerifier = MotionFixtureEvaluatorReceiptVerifier { receipt ->
            receipt.evaluatorId == STRUCTURAL_TEST_ONLY_EVALUATOR
        }
        assertTrue(
            MotionFixtureQualityGrader.evaluateRelease(
                catalog,
                evidence,
                evaluations,
                structuralTestOnlyVerifier,
                structuralEvaluatorVerifier,
            ) is MotionReleaseEvaluationResult.Approved,
        )

        val duplicate = MotionFixtureQualityGrader.grade(
            listOf(evaluations.first(), evaluations.first().copy(detectedEvents = emptyList())),
        ) as MotionQualityGradingResult.Rejected
        assertTrue(
            MotionQualityGradingViolation.DUPLICATE_EVALUATION_FIXTURE_ID in duplicate.violations,
        )
        assertTrue(
            MotionQualityGradingViolation.DUPLICATE_EVALUATION_OCCURRENCE_ID in duplicate.violations,
        )
    }

    @Test
    fun compositeSnapshotsStatefulEventsOnceAndBoundsLyingCollectionsBeforeReceiptHash() {
        val (catalog, evidence) = recordedReleaseCatalog()
        val baseline = perfectStructuralEvaluations(evidence)
        val target = baseline.first { it.detectedEvents.isNotEmpty() }
        var iteratorCalls = 0
        val statefulEvents = object : AbstractCollection<MotionFixtureDetectedEvent>() {
            override val size: Int = target.detectedEvents.size
            override fun iterator(): MutableIterator<MotionFixtureDetectedEvent> {
                iteratorCalls += 1
                val selected = if (iteratorCalls == 1) {
                    target.detectedEvents.toList()
                } else {
                    target.detectedEvents.map { it.copy(motionType = MotionType.PUNCH_HOOK) }
                }
                return selected.toMutableList().iterator()
            }
        }
        val stateful = baseline.map { evaluation ->
            if (evaluation === target) evaluation.copy(detectedEvents = statefulEvents) else evaluation
        }
        val captureVerifier = MotionFixtureCaptureApprovalVerifier {
            it.reviewerId == STRUCTURAL_TEST_ONLY_REVIEWER
        }
        val evaluatorVerifier = MotionFixtureEvaluatorReceiptVerifier {
            it.evaluatorId == STRUCTURAL_TEST_ONLY_EVALUATOR
        }
        assertTrue(
            MotionFixtureQualityGrader.evaluateRelease(
                catalog,
                evidence,
                stateful,
                captureVerifier,
                evaluatorVerifier,
            ) is MotionReleaseEvaluationResult.Approved,
        )
        assertEquals(1, iteratorCalls)

        var emittedEvents = 0
        val repeated = target.detectedEvents.first()
        val lyingOversizedEvents = object : AbstractCollection<MotionFixtureDetectedEvent>() {
            override val size: Int = 0
            override fun iterator(): MutableIterator<MotionFixtureDetectedEvent> = object : MutableIterator<MotionFixtureDetectedEvent> {
                override fun hasNext(): Boolean = emittedEvents <= MAX_TOTAL_RELEASE_EVENTS
                override fun next(): MotionFixtureDetectedEvent {
                    emittedEvents += 1
                    return repeated
                }
                override fun remove() = error("not supported")
            }
        }
        val oversized = baseline.map { evaluation ->
            if (evaluation === target) evaluation.copy(detectedEvents = lyingOversizedEvents) else evaluation
        }
        val rejected = MotionFixtureQualityGrader.evaluateRelease(
            catalog,
            evidence,
            oversized,
            captureVerifier,
            evaluatorVerifier,
        ) as MotionReleaseEvaluationResult.Rejected
        assertTrue(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED in rejected.gradingViolations)
        assertTrue(emittedEvents <= MAX_TOTAL_RELEASE_EVENTS + 1)
    }

    @Test
    fun governanceSnapshotsFixtureCatalogOnceAndBoundsLyingCatalogSize() {
        val (catalog, evidence) = recordedReleaseCatalog()
        val captureVerifier = MotionFixtureCaptureApprovalVerifier {
            it.reviewerId == STRUCTURAL_TEST_ONLY_REVIEWER
        }
        var iteratorCalls = 0
        val statefulCatalog = object : AbstractCollection<MotionFixtureDescriptor>() {
            override val size: Int = catalog.size
            override fun iterator(): MutableIterator<MotionFixtureDescriptor> {
                iteratorCalls += 1
                return (if (iteratorCalls == 1) catalog else emptyList()).toMutableList().iterator()
            }
        }
        assertTrue(
            MotionFixtureGovernance.validateReleaseEvidence(
                statefulCatalog,
                evidence,
                captureVerifier,
            ).isEmpty(),
        )
        assertEquals(1, iteratorCalls)

        var emittedFixtures = 0
        val repeated = catalog.first()
        val lyingCatalog = object : AbstractCollection<MotionFixtureDescriptor>() {
            override val size: Int = 0
            override fun iterator(): MutableIterator<MotionFixtureDescriptor> = object : MutableIterator<MotionFixtureDescriptor> {
                override fun hasNext(): Boolean = emittedFixtures <= 4_096
                override fun next(): MotionFixtureDescriptor {
                    emittedFixtures += 1
                    return repeated
                }
                override fun remove() = error("not supported")
            }
        }
        assertEquals(
            setOf(FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED),
            MotionFixtureGovernance.validateReleaseEvidence(lyingCatalog, evidence, captureVerifier),
        )
        assertTrue(emittedFixtures <= 4_097)
    }

    @Test
    fun splitNeutralClipsAggregatePerSubjectAndOversizedCollectionsFailTyped() {
        val (baseCatalog, baseEvidence) = recordedReleaseCatalog()
        val originalNeutral = baseEvidence.single { it.descriptor.label == FixtureLabel.NEUTRAL }
        val firstNeutral = recordedEvidence(
            originalNeutral.descriptor.copy(
                fixtureId = "holdout-neutral-a",
                sessionId = "holdout-neutral-session-a",
                durationNs = 30_000_000_000L,
                sourceKind = FixtureSourceKind.SYNTHETIC,
                sourceArtifactSha256 = null,
                occurrenceManifestSha256 = null,
            ),
        )
        val secondNeutral = recordedEvidence(
            originalNeutral.descriptor.copy(
                fixtureId = "holdout-neutral-b",
                sessionId = "holdout-neutral-session-b",
                durationNs = 30_000_000_000L,
                sourceKind = FixtureSourceKind.SYNTHETIC,
                sourceArtifactSha256 = null,
                occurrenceManifestSha256 = null,
            ),
        )
        val catalog = baseCatalog - originalNeutral.descriptor +
            listOf(firstNeutral.descriptor, secondNeutral.descriptor)
        val evidence = baseEvidence - originalNeutral + listOf(firstNeutral, secondNeutral)
        val structuralTestOnlyVerifier = MotionFixtureCaptureApprovalVerifier { approval ->
            approval.reviewerId == STRUCTURAL_TEST_ONLY_REVIEWER
        }
        assertTrue(
            MotionFixtureGovernance.validateReleaseEvidence(
                catalog,
                evidence,
                structuralTestOnlyVerifier,
            ).isEmpty(),
        )

        val oversizedOccurrences = object : AbstractCollection<MotionFixtureOccurrence>() {
            override val size: Int = MAX_TOTAL_RELEASE_OCCURRENCES + 1
            override fun iterator(): MutableIterator<MotionFixtureOccurrence> =
                mutableListOf<MotionFixtureOccurrence>().iterator()
        }
        val oversizedEvidence = MotionFixtureReleaseEvidence(
            firstNeutral.descriptor,
            "bounded".toByteArray(),
            oversizedOccurrences,
        )
        assertTrue(
            FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED in
                MotionFixtureGovernance.validateReleaseEvidence(catalog, listOf(oversizedEvidence)),
        )

        var emittedOccurrences = 0
        val repeatedOccurrence = baseEvidence.first { it.occurrences.isNotEmpty() }.occurrences.first()
        val lyingOccurrences = object : AbstractCollection<MotionFixtureOccurrence>() {
            override val size: Int = 0
            override fun iterator(): MutableIterator<MotionFixtureOccurrence> = object : MutableIterator<MotionFixtureOccurrence> {
                override fun hasNext(): Boolean = emittedOccurrences <= MAX_TOTAL_RELEASE_OCCURRENCES
                override fun next(): MotionFixtureOccurrence {
                    emittedOccurrences += 1
                    return repeatedOccurrence
                }
                override fun remove() = error("not supported")
            }
        }
        val lyingEvidence = MotionFixtureReleaseEvidence(
            firstNeutral.descriptor,
            "bounded".toByteArray(),
            lyingOccurrences,
        )
        assertTrue(
            FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED in
                MotionFixtureGovernance.validateReleaseEvidence(catalog, listOf(lyingEvidence)),
        )
        assertTrue(emittedOccurrences <= MAX_TOTAL_RELEASE_OCCURRENCES + 1)

        val oversizedEvents = object : AbstractCollection<MotionFixtureDetectedEvent>() {
            override val size: Int = MAX_TOTAL_RELEASE_EVENTS + 1
            override fun iterator(): MutableIterator<MotionFixtureDetectedEvent> =
                mutableListOf<MotionFixtureDetectedEvent>().iterator()
        }
        val rejected = MotionFixtureQualityGrader.grade(
            listOf(MotionFixtureEvaluation(firstNeutral, oversizedEvents)),
        ) as MotionQualityGradingResult.Rejected
        assertTrue(MotionQualityGradingViolation.INPUT_LIMIT_EXCEEDED in rejected.violations)
    }

    @Test
    fun canonicalIdentifiersAndMatchedEventTimestampsRejectDelimiterOrIntervalForgery() {
        val unsafeDescriptor = descriptor(
            id = "unsafe\nfixture",
            partition = FixturePartition.HOLDOUT,
        )
        assertTrue(
            FixtureGovernanceViolation.INVALID_DESCRIPTOR in
                MotionFixtureGovernance.validate(validCatalog() + unsafeDescriptor),
        )

        val evidence = recordedEvidence(
            descriptor(
                id = "timestamp-bound",
                partition = FixturePartition.HOLDOUT,
                motionType = MotionType.FISH_CAST,
            ),
        )
        val occurrence = evidence.occurrences.single()
        val rejected = MotionFixtureQualityGrader.grade(
            listOf(
                MotionFixtureEvaluation(
                    evidence,
                    listOf(
                        MotionFixtureDetectedEvent(
                            eventId = "unsafe-event",
                            motionType = MotionType.FISH_CAST,
                            matchedOccurrenceId = occurrence.occurrenceId,
                            eventTimestampNs = occurrence.endExclusiveNs,
                        ),
                        MotionFixtureDetectedEvent(
                            eventId = "unsafe\tevent",
                            motionType = MotionType.FISH_CAST,
                            matchedOccurrenceId = occurrence.occurrenceId,
                            eventTimestampNs = occurrence.startNs,
                        ),
                    ),
                ),
            ),
        ) as MotionQualityGradingResult.Rejected
        assertTrue(MotionQualityGradingViolation.INVALID_EVENT_TIMESTAMP in rejected.violations)
        assertTrue(MotionQualityGradingViolation.INVALID_EVENT_ID in rejected.violations)
    }

    private fun validCatalog(): List<MotionFixtureDescriptor> {
        val positives = FixturePartition.entries.flatMap { partition ->
            MotionType.entries.mapIndexed { index, type ->
                descriptor(
                    id = "${partition.name.lowercase()}-positive-$index",
                    partition = partition,
                    motionType = type,
                )
            }
        }
        val hardNegatives = FixturePartition.entries.flatMap { partition ->
            MotionType.entries.mapIndexed { index, type ->
                descriptor(
                    id = "${partition.name.lowercase()}-hard-negative-$index",
                    partition = partition,
                    label = FixtureLabel.HARD_NEGATIVE,
                    durationNs = 10_000_000_000L,
                    motionType = type,
                )
            }
        }
        val neutrals = FixturePartition.entries.map { partition ->
            descriptor(
                id = "${partition.name.lowercase()}-neutral",
                partition = partition,
                label = FixtureLabel.NEUTRAL,
                durationNs = 60_000_000_000L,
                motionType = null,
            )
        }
        return positives + hardNegatives + neutrals
    }

    private fun descriptor(
        id: String,
        partition: FixturePartition,
        subject: String = if (partition == FixturePartition.TUNE) "tune-person" else "holdout-person",
        session: String = if (partition == FixturePartition.TUNE) "tune-positive-session" else "holdout-positive-session",
        label: FixtureLabel = FixtureLabel.POSITIVE,
        durationNs: Long = 600_000_000L,
        motionType: MotionType? = MotionType.FISH_CAST,
        sourceKind: FixtureSourceKind = FixtureSourceKind.SYNTHETIC,
        occurrenceCount: Int = if (label == FixtureLabel.NEUTRAL) 0 else 1,
        sourceArtifactSha256: String? = null,
        occurrenceManifestSha256: String? = null,
    ) = MotionFixtureDescriptor(
        id,
        partition,
        subject,
        session,
        label,
        durationNs,
        motionType,
        sourceKind,
        occurrenceCount,
        sourceArtifactSha256,
        occurrenceManifestSha256,
    )

    private fun recordedReleaseCatalog(): Pair<List<MotionFixtureDescriptor>, List<MotionFixtureReleaseEvidence>> {
        val tune = validCatalog().filter { it.partition == FixturePartition.TUNE }
        val evidence = validCatalog().filter { it.partition == FixturePartition.HOLDOUT }.map { descriptor ->
            recordedEvidence(
                descriptor.copy(
                    occurrenceCount = if (descriptor.label == FixtureLabel.NEUTRAL) 0 else 30,
                ),
            )
        }
        return tune + evidence.map(MotionFixtureReleaseEvidence::descriptor) to evidence
    }

    private fun recordedEvidence(source: MotionFixtureDescriptor): MotionFixtureReleaseEvidence {
        val bytes = structuralArtifactBytes(source)
        val artifactSha256 = sha256(bytes)
        val minimumDuration = Math.multiplyExact(
            source.occurrenceCount.toLong(),
            MIN_RELEASE_OCCURRENCE_DURATION_NS,
        ).coerceAtLeast(source.durationNs)
        val occurrences = (0 until source.occurrenceCount).map { index ->
            val startNs = Math.multiplyExact(index.toLong(), MIN_RELEASE_OCCURRENCE_DURATION_NS)
            MotionFixtureOccurrence(
                occurrenceId = "${source.fixtureId}-occurrence-$index",
                trialId = "${source.fixtureId}-trial-$index",
                sessionId = source.sessionId,
                startNs = startNs,
                endExclusiveNs = Math.addExact(startNs, MIN_RELEASE_OCCURRENCE_DURATION_NS),
                rearmCompleted = true,
            )
        }
        val descriptor = source.copy(
            durationNs = minimumDuration,
            sourceKind = FixtureSourceKind.RECORDED,
            sourceArtifactSha256 = artifactSha256,
            occurrenceManifestSha256 = MotionFixtureGovernance.computeOccurrenceManifestSha256(
                fixtureId = source.fixtureId,
                sessionId = source.sessionId,
                sourceArtifactSha256 = artifactSha256,
                occurrences = occurrences,
            ),
        )
        val approval = MotionFixtureCaptureApproval(
            approvalId = "structural-test-approval-${source.fixtureId}",
            reviewerId = STRUCTURAL_TEST_ONLY_REVIEWER,
            descriptorSha256 = MotionFixtureGovernance.computeDescriptorSha256(descriptor),
            sourceArtifactSha256 = requireNotNull(descriptor.sourceArtifactSha256),
            occurrenceManifestSha256 = requireNotNull(descriptor.occurrenceManifestSha256),
        )
        return MotionFixtureReleaseEvidence(descriptor, bytes, occurrences, approval)
    }

    private fun event(
        eventId: String,
        motionType: MotionType,
        occurrence: MotionFixtureOccurrence,
    ) = MotionFixtureDetectedEvent(
        eventId,
        motionType,
        occurrence.occurrenceId,
        occurrence.startNs,
    )

    private fun perfectStructuralEvaluations(
        evidence: Collection<MotionFixtureReleaseEvidence>,
    ): List<MotionFixtureEvaluation> = evidence.map { fixture ->
        val events = if (fixture.descriptor.label == FixtureLabel.POSITIVE) {
            fixture.occurrences.map { occurrence ->
                event(
                    eventId = "event-${occurrence.occurrenceId}",
                    motionType = requireNotNull(fixture.descriptor.motionType),
                    occurrence = occurrence,
                )
            }
        } else {
            emptyList()
        }
        val descriptor = fixture.descriptor
        val receipt = MotionFixtureEvaluatorReceipt(
            receiptId = "structural-receipt-${descriptor.fixtureId}",
            evaluatorId = STRUCTURAL_TEST_ONLY_EVALUATOR,
            fixtureId = descriptor.fixtureId,
            descriptorSha256 = MotionFixtureGovernance.computeDescriptorSha256(descriptor),
            sourceArtifactSha256 = requireNotNull(descriptor.sourceArtifactSha256),
            occurrenceManifestSha256 = requireNotNull(descriptor.occurrenceManifestSha256),
            engineVersionSha256 = sha256("structural-engine-version".toByteArray()),
            motionConfigSha256 = sha256("structural-motion-config".toByteArray()),
            detectedEventsSha256 = MotionFixtureQualityGrader.computeDetectedEventsSha256(events),
        )
        MotionFixtureEvaluation(fixture, events, receipt)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun structuralArtifactBytes(descriptor: MotionFixtureDescriptor): ByteArray =
        "structural-test-pose:${descriptor.fixtureId}:${descriptor.sessionId}".toByteArray()

    private companion object {
        const val STRUCTURAL_TEST_ONLY_REVIEWER = "structural-test-only-not-human-evidence"
        const val STRUCTURAL_TEST_ONLY_EVALUATOR = "structural-test-only-not-runtime-evidence"
    }

    private fun parse(line: String): ExecutableFixture {
        val fields = line.split('\t')
        require(fields.size == 11)
        return ExecutableFixture(
            descriptor = MotionFixtureDescriptor(
                fixtureId = fields[0],
                partition = FixturePartition.valueOf(fields[1]),
                subjectId = fields[2],
                sessionId = fields[3],
                label = FixtureLabel.valueOf(fields[4]),
                durationNs = fields[5].toLong(),
                motionType = fields[6].takeUnless { it == "-" }?.let(MotionType::valueOf),
                sourceKind = FixtureSourceKind.valueOf(fields[9]),
                occurrenceCount = fields[10].toInt(),
            ),
            stepNs = fields[7].toLong(),
            pattern = SignalPattern.valueOf(fields[8]),
        )
    }

    private fun completeFrame(target: MotionSignalSample): List<MotionSignalSample> =
        listOf(target) + DevelopmentGestureDefinitions.all.mapNotNull { definition ->
            if (definition.type == target.type) {
                null
            } else {
                target.copy(
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
