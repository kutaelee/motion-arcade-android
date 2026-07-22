package com.motionarcade.core.motion

import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.MotionTypeRegistry
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

enum class FixturePartition { TUNE, HOLDOUT }

enum class FixtureLabel { POSITIVE, HARD_NEGATIVE, NEUTRAL }

enum class FixtureSourceKind { SYNTHETIC, RECORDED }

data class MotionFixtureDescriptor(
    val fixtureId: String,
    val partition: FixturePartition,
    val subjectId: String,
    val sessionId: String,
    val label: FixtureLabel,
    val durationNs: Long,
    val motionType: MotionType?,
    val sourceKind: FixtureSourceKind,
    val occurrenceCount: Int,
    val sourceArtifactSha256: String? = null,
    val occurrenceManifestSha256: String? = null,
)

data class MotionFixtureOccurrence(
    val occurrenceId: String,
    val trialId: String,
    val sessionId: String,
    val startNs: Long,
    val endExclusiveNs: Long,
    val rearmCompleted: Boolean,
) {
    internal fun isValidFor(descriptor: MotionFixtureDescriptor): Boolean =
        occurrenceId.isSafeEvidenceId() && trialId.isSafeEvidenceId() &&
            sessionId == descriptor.sessionId &&
            startNs >= 0L && endExclusiveNs > startNs && endExclusiveNs <= descriptor.durationNs &&
            endExclusiveNs - startNs >= MIN_RELEASE_OCCURRENCE_DURATION_NS && rearmCompleted
}

data class MotionFixtureCaptureApproval(
    val approvalId: String,
    val reviewerId: String,
    val descriptorSha256: String,
    val sourceArtifactSha256: String,
    val occurrenceManifestSha256: String,
)

fun interface MotionFixtureCaptureApprovalVerifier {
    fun isTrusted(approval: MotionFixtureCaptureApproval): Boolean

    companion object {
        val DENY_ALL = MotionFixtureCaptureApprovalVerifier { false }
    }
}

/** Cheap caller-owned input. Release validation admits it only after aggregate preflight and
 * creates a bounded private snapshot before hashing or traversing occurrence data. */
class MotionFixtureReleaseEvidence(
    val descriptor: MotionFixtureDescriptor,
    artifactBytes: ByteArray,
    occurrences: Collection<MotionFixtureOccurrence>,
    val captureApproval: MotionFixtureCaptureApproval? = null,
) {
    internal val suppliedArtifactByteCount: Int = artifactBytes.size
    internal val artifactBytes: ByteArray = artifactBytes
    internal val suppliedOccurrenceCount: Int = occurrences.size
    val occurrences: Collection<MotionFixtureOccurrence> = occurrences
}

internal sealed interface MotionFixtureEvidenceAdmission {
    data class Admitted(
        val originalToSnapshot: Map<MotionFixtureReleaseEvidence, MotionFixtureReleaseEvidence>,
        val snapshots: List<MotionFixtureReleaseEvidence>,
    ) : MotionFixtureEvidenceAdmission

    data class Rejected(
        val violations: Set<FixtureGovernanceViolation>,
    ) : MotionFixtureEvidenceAdmission
}

internal sealed interface MotionFixtureCatalogAdmission {
    data class Admitted(val fixtures: List<MotionFixtureDescriptor>) : MotionFixtureCatalogAdmission
    data object Rejected : MotionFixtureCatalogAdmission
}

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
    SYNTHETIC_HOLDOUT_NOT_RELEASE_EVIDENCE,
    MISSING_OR_INVALID_RECORDED_ARTIFACT,
    MISSING_TRUSTED_CAPTURE_APPROVAL,
    DUPLICATE_RELEASE_EVIDENCE,
    DUPLICATE_OCCURRENCE_ID,
    INVALID_OCCURRENCE,
    OCCURRENCE_COUNT_MISMATCH,
    INSUFFICIENT_RECORDED_HOLDOUT_POSITIVES,
    INSUFFICIENT_RECORDED_HOLDOUT_HARD_NEGATIVES,
    MISSING_RECORDED_HOLDOUT_SIXTY_SECOND_NEUTRAL,
    INPUT_LIMIT_EXCEEDED,
    ARITHMETIC_OVERFLOW,
}

object MotionFixtureGovernance {
    internal const val REQUIRED_NEUTRAL_NS = 60_000_000_000L
    private const val REQUIRED_RELEASE_OCCURRENCES = 30L
    private const val MAX_FIXTURES = 4_096
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    val requiredMotionTypes: Set<MotionType>
        get() = MotionTypeRegistry.executableTypes

    fun validate(fixtures: Collection<MotionFixtureDescriptor>): Set<FixtureGovernanceViolation> =
        when (val admission = admitFixtures(fixtures)) {
            MotionFixtureCatalogAdmission.Rejected -> setOf(FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED)
            is MotionFixtureCatalogAdmission.Admitted -> validateAdmittedFixtures(admission.fixtures)
        }

    internal fun admitFixtures(
        fixtures: Collection<MotionFixtureDescriptor>,
    ): MotionFixtureCatalogAdmission {
        val declaredCount = fixtures.size
        if (declaredCount !in 0..MAX_FIXTURES) return MotionFixtureCatalogAdmission.Rejected
        val snapshot = ArrayList<MotionFixtureDescriptor>(declaredCount)
        val iterator = fixtures.iterator()
        while (iterator.hasNext()) {
            if (snapshot.size == MAX_FIXTURES) return MotionFixtureCatalogAdmission.Rejected
            snapshot += iterator.next()
        }
        return MotionFixtureCatalogAdmission.Admitted(snapshot.toList())
    }

    private fun validateAdmittedFixtures(
        fixtures: List<MotionFixtureDescriptor>,
    ): Set<FixtureGovernanceViolation> {
        val violations = linkedSetOf<FixtureGovernanceViolation>()
        if (fixtures.any { !it.isValid() }) violations += FixtureGovernanceViolation.INVALID_DESCRIPTOR
        if (fixtures.groupingBy { it.fixtureId }.eachCount().any { it.value > 1 }) {
            violations += FixtureGovernanceViolation.DUPLICATE_FIXTURE_ID
        }
        if (fixtures.groupBy { it.subjectId }.values.any { rows -> rows.map { it.partition }.distinct().size > 1 }) {
            violations += FixtureGovernanceViolation.SUBJECT_PARTITION_LEAK
        }
        if (fixtures.groupBy { it.sessionId }.values.any { rows -> rows.map { it.partition }.distinct().size > 1 }) {
            violations += FixtureGovernanceViolation.SESSION_PARTITION_LEAK
        }
        val positiveCoverage = coverage(fixtures, FixtureLabel.POSITIVE)
        if (positiveCoverage[FixturePartition.TUNE].orEmpty() != requiredMotionTypes) {
            violations += FixtureGovernanceViolation.INSUFFICIENT_TUNE_MOTION_COVERAGE
        }
        if (positiveCoverage[FixturePartition.HOLDOUT].orEmpty() != requiredMotionTypes) {
            violations += FixtureGovernanceViolation.INSUFFICIENT_HOLDOUT_MOTION_COVERAGE
        }
        val hardNegativeCoverage = coverage(fixtures, FixtureLabel.HARD_NEGATIVE)
        if (hardNegativeCoverage[FixturePartition.TUNE].orEmpty() != requiredMotionTypes) {
            violations += FixtureGovernanceViolation.MISSING_TUNE_HARD_NEGATIVE_COVERAGE
        }
        if (hardNegativeCoverage[FixturePartition.HOLDOUT].orEmpty() != requiredMotionTypes) {
            violations += FixtureGovernanceViolation.MISSING_HOLDOUT_HARD_NEGATIVE_COVERAGE
        }
        FixturePartition.entries.forEach { partition ->
            val duration = neutralDuration(fixtures.filter { it.partition == partition })
            if (duration == null) violations += FixtureGovernanceViolation.ARITHMETIC_OVERFLOW
            if (duration == null || duration < REQUIRED_NEUTRAL_NS) {
                violations += when (partition) {
                    FixturePartition.TUNE -> FixtureGovernanceViolation.MISSING_TUNE_SIXTY_SECOND_NEUTRAL
                    FixturePartition.HOLDOUT -> FixtureGovernanceViolation.MISSING_HOLDOUT_SIXTY_SECOND_NEUTRAL
                }
            }
        }
        fixtures.groupBy { it.partition to it.subjectId }.values.forEach { subjectRows ->
            val duration = neutralDuration(subjectRows)
            if (duration == null) violations += FixtureGovernanceViolation.ARITHMETIC_OVERFLOW
            if (duration == null || duration < REQUIRED_NEUTRAL_NS) {
                violations += FixtureGovernanceViolation.MISSING_SUBJECT_SIXTY_SECOND_NEUTRAL
            }
        }
        return violations
    }

    /**
     * Release evidence requires captured artifact bytes, their recomputed SHA-256, session-bound
     * occurrence identities, and exact occurrence counts. A descriptor relabel alone cannot pass.
     */
    fun validateReleaseEvidence(
        fixtures: Collection<MotionFixtureDescriptor>,
        evidence: Collection<MotionFixtureReleaseEvidence>,
        approvalVerifier: MotionFixtureCaptureApprovalVerifier =
            MotionFixtureCaptureApprovalVerifier.DENY_ALL,
    ): Set<FixtureGovernanceViolation> {
        val fixtureAdmission = admitFixtures(fixtures)
        if (fixtureAdmission is MotionFixtureCatalogAdmission.Rejected) {
            return setOf(FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED)
        }
        fixtureAdmission as MotionFixtureCatalogAdmission.Admitted
        return when (val admission = admitEvidence(evidence)) {
            is MotionFixtureEvidenceAdmission.Rejected ->
                validateAdmittedFixtures(fixtureAdmission.fixtures) + admission.violations
            is MotionFixtureEvidenceAdmission.Admitted -> validateAdmittedReleaseEvidence(
                fixtureAdmission.fixtures,
                admission.snapshots,
                approvalVerifier,
            )
        }
    }

    internal fun admitEvidence(
        evidence: Collection<MotionFixtureReleaseEvidence>,
    ): MotionFixtureEvidenceAdmission {
        val declaredEvidenceCount = evidence.size
        if (declaredEvidenceCount !in 0..MAX_FIXTURES) return evidenceLimitRejection()
        val boundedEvidence = ArrayList<MotionFixtureReleaseEvidence>(declaredEvidenceCount)
        val evidenceIterator = evidence.iterator()
        while (evidenceIterator.hasNext()) {
            if (boundedEvidence.size == MAX_FIXTURES) return evidenceLimitRejection()
            boundedEvidence += evidenceIterator.next()
        }
        if (boundedEvidence.any { it.suppliedOccurrenceCount !in 0..MAX_TOTAL_RELEASE_OCCURRENCES }) {
            return evidenceLimitRejection()
        }
        val totalArtifactBytes = boundedEvidence.sumExactOrNull { it.artifactBytes.size.toLong() }
        val suppliedOccurrences = boundedEvidence.sumExactOrNull { it.suppliedOccurrenceCount.toLong() }
        if (
            totalArtifactBytes == null || totalArtifactBytes > MAX_TOTAL_RELEASE_ARTIFACT_BYTES ||
            suppliedOccurrences == null || suppliedOccurrences > MAX_TOTAL_RELEASE_OCCURRENCES
        ) {
            return MotionFixtureEvidenceAdmission.Rejected(
                buildSet {
                    add(FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED)
                    if (totalArtifactBytes == null || suppliedOccurrences == null) {
                        add(FixtureGovernanceViolation.ARITHMETIC_OVERFLOW)
                    }
                },
            )
        }
        var actualOccurrenceCount = 0
        val snapshots = java.util.IdentityHashMap<MotionFixtureReleaseEvidence, MotionFixtureReleaseEvidence>()
        val orderedSnapshots = ArrayList<MotionFixtureReleaseEvidence>(boundedEvidence.size)
        boundedEvidence.forEach { source ->
            if (snapshots.containsKey(source)) {
                return MotionFixtureEvidenceAdmission.Rejected(
                    setOf(FixtureGovernanceViolation.DUPLICATE_RELEASE_EVIDENCE),
                )
            }
            val occurrenceSnapshot = ArrayList<MotionFixtureOccurrence>(source.suppliedOccurrenceCount)
            val iterator = source.occurrences.iterator()
            while (iterator.hasNext()) {
                if (actualOccurrenceCount == MAX_TOTAL_RELEASE_OCCURRENCES) return evidenceLimitRejection()
                occurrenceSnapshot += iterator.next()
                actualOccurrenceCount += 1
            }
            if (
                occurrenceSnapshot.size != source.suppliedOccurrenceCount ||
                source.artifactBytes.size != source.suppliedArtifactByteCount
            ) return evidenceLimitRejection()
            val snapshot = MotionFixtureReleaseEvidence(
                descriptor = source.descriptor,
                artifactBytes = source.artifactBytes.copyOf(),
                occurrences = occurrenceSnapshot.toList(),
                captureApproval = source.captureApproval,
            )
            snapshots[source] = snapshot
            orderedSnapshots += snapshot
        }
        return MotionFixtureEvidenceAdmission.Admitted(snapshots, orderedSnapshots)
    }

    private fun evidenceLimitRejection() = MotionFixtureEvidenceAdmission.Rejected(
        setOf(FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED),
    )

    internal fun validateAdmittedReleaseEvidence(
        fixtures: List<MotionFixtureDescriptor>,
        evidence: Collection<MotionFixtureReleaseEvidence>,
        approvalVerifier: MotionFixtureCaptureApprovalVerifier,
    ): Set<FixtureGovernanceViolation> {
        val violations = linkedSetOf<FixtureGovernanceViolation>()
        violations += validateAdmittedFixtures(fixtures)
        if (fixtures.size > MAX_FIXTURES || evidence.size > MAX_FIXTURES) {
            violations += FixtureGovernanceViolation.INPUT_LIMIT_EXCEEDED
            return violations
        }
        val evidenceCounts = evidence.groupingBy { it.descriptor.fixtureId }.eachCount()
        if (evidenceCounts.any { it.value > 1 }) {
            violations += FixtureGovernanceViolation.DUPLICATE_RELEASE_EVIDENCE
        }
        val catalogById = fixtures.associateBy(MotionFixtureDescriptor::fixtureId)
        if (evidence.any { catalogById[it.descriptor.fixtureId] != it.descriptor }) {
            violations += FixtureGovernanceViolation.MISSING_OR_INVALID_RECORDED_ARTIFACT
        }
        val uniqueEvidence = evidence.associateBy { it.descriptor.fixtureId }
        val holdout = fixtures.filter { it.partition == FixturePartition.HOLDOUT }
        if (holdout.any { it.sourceKind != FixtureSourceKind.RECORDED }) {
            violations += FixtureGovernanceViolation.SYNTHETIC_HOLDOUT_NOT_RELEASE_EVIDENCE
        }
        val validEvidence = linkedMapOf<String, MotionFixtureReleaseEvidence>()
        val occurrenceIds = linkedSetOf<String>()
        val trialIds = linkedSetOf<String>()
        holdout.filter { it.sourceKind == FixtureSourceKind.RECORDED }.forEach { descriptor ->
            val releaseEvidence = uniqueEvidence[descriptor.fixtureId]
            if (releaseEvidence == null) {
                violations += FixtureGovernanceViolation.MISSING_OR_INVALID_RECORDED_ARTIFACT
                return@forEach
            }
            if (releaseEvidence.occurrences.any { !it.isValidFor(descriptor) }) {
                violations += FixtureGovernanceViolation.INVALID_OCCURRENCE
                return@forEach
            }
            if (releaseEvidence.occurrences.any { !occurrenceIds.add(it.occurrenceId) }) {
                violations += FixtureGovernanceViolation.DUPLICATE_OCCURRENCE_ID
                return@forEach
            }
            if (releaseEvidence.occurrences.any { !trialIds.add(it.trialId) }) {
                violations += FixtureGovernanceViolation.DUPLICATE_OCCURRENCE_ID
                return@forEach
            }
            val ordered = releaseEvidence.occurrences.sortedBy(MotionFixtureOccurrence::startNs)
            if (ordered.zipWithNext().any { (left, right) -> left.endExclusiveNs > right.startNs }) {
                violations += FixtureGovernanceViolation.INVALID_OCCURRENCE
                return@forEach
            }
            if (!artifactMatches(descriptor, releaseEvidence)) {
                violations += FixtureGovernanceViolation.MISSING_OR_INVALID_RECORDED_ARTIFACT
                return@forEach
            }
            val approval = releaseEvidence.captureApproval
            if (
                approval == null || !approvalMatches(descriptor, approval) ||
                !approvalVerifier.isTrusted(approval)
            ) {
                violations += FixtureGovernanceViolation.MISSING_TRUSTED_CAPTURE_APPROVAL
                return@forEach
            }
            val expectedCount = if (descriptor.label == FixtureLabel.NEUTRAL) 0 else descriptor.occurrenceCount
            if (releaseEvidence.occurrences.size != expectedCount) {
                violations += FixtureGovernanceViolation.OCCURRENCE_COUNT_MISMATCH
                return@forEach
            }
            validEvidence[descriptor.fixtureId] = releaseEvidence
        }
        requiredMotionTypes.forEach { motionType ->
            val positiveCount = occurrenceCount(validEvidence.values, FixtureLabel.POSITIVE, motionType, violations)
            if (positiveCount < REQUIRED_RELEASE_OCCURRENCES) {
                violations += FixtureGovernanceViolation.INSUFFICIENT_RECORDED_HOLDOUT_POSITIVES
            }
            val negativeCount = occurrenceCount(
                validEvidence.values,
                FixtureLabel.HARD_NEGATIVE,
                motionType,
                violations,
            )
            if (negativeCount < REQUIRED_RELEASE_OCCURRENCES) {
                violations += FixtureGovernanceViolation.INSUFFICIENT_RECORDED_HOLDOUT_HARD_NEGATIVES
            }
        }
        val recordedSubjects = holdout.filter { it.sourceKind == FixtureSourceKind.RECORDED }
            .mapTo(linkedSetOf(), MotionFixtureDescriptor::subjectId)
        if (recordedSubjects.isEmpty() || recordedSubjects.any { subjectId ->
                val duration = neutralDuration(
                    validEvidence.values.map(MotionFixtureReleaseEvidence::descriptor).filter {
                        it.subjectId == subjectId
                    },
                )
                if (duration == null) violations += FixtureGovernanceViolation.ARITHMETIC_OVERFLOW
                duration == null || duration < REQUIRED_NEUTRAL_NS
            }
        ) {
            violations += FixtureGovernanceViolation.MISSING_RECORDED_HOLDOUT_SIXTY_SECOND_NEUTRAL
        }
        return violations
    }

    internal fun isValidDescriptor(descriptor: MotionFixtureDescriptor): Boolean = descriptor.isValid()

    internal fun isValidEvidence(evidence: MotionFixtureReleaseEvidence): Boolean =
        evidence.descriptor.partition == FixturePartition.HOLDOUT &&
            evidence.descriptor.isValid() && artifactMatches(evidence.descriptor, evidence) &&
            evidence.suppliedArtifactByteCount <= MAX_TOTAL_RELEASE_ARTIFACT_BYTES &&
            evidence.suppliedOccurrenceCount <= MAX_TOTAL_RELEASE_OCCURRENCES &&
            evidence.occurrences.distinctBy(MotionFixtureOccurrence::occurrenceId).size ==
            evidence.occurrences.size &&
            evidence.occurrences.distinctBy(MotionFixtureOccurrence::trialId).size ==
            evidence.occurrences.size &&
            evidence.occurrences.all { it.isValidFor(evidence.descriptor) } &&
            evidence.occurrences.sortedBy(MotionFixtureOccurrence::startNs).zipWithNext()
                .none { (left, right) -> left.endExclusiveNs > right.startNs } &&
            evidence.occurrences.size ==
            if (evidence.descriptor.label == FixtureLabel.NEUTRAL) 0 else evidence.descriptor.occurrenceCount

    private fun MotionFixtureDescriptor.isValid(): Boolean =
        fixtureId.isNotBlank() && fixtureId.length <= MAX_EVIDENCE_ID_LENGTH &&
            fixtureId.isSafeEvidenceId() &&
            subjectId.isSafeEvidenceId() && sessionId.isSafeEvidenceId() &&
            durationNs > 0L &&
            when (label) {
                FixtureLabel.POSITIVE, FixtureLabel.HARD_NEGATIVE -> motionType != null && occurrenceCount > 0
                FixtureLabel.NEUTRAL -> motionType == null && occurrenceCount == 0
            } &&
            when (sourceKind) {
                FixtureSourceKind.SYNTHETIC ->
                    sourceArtifactSha256 == null && occurrenceManifestSha256 == null
                FixtureSourceKind.RECORDED ->
                    sourceArtifactSha256?.matches(SHA256_PATTERN) == true &&
                        occurrenceManifestSha256?.matches(SHA256_PATTERN) == true
            }

    private fun artifactMatches(
        descriptor: MotionFixtureDescriptor,
        evidence: MotionFixtureReleaseEvidence,
    ): Boolean {
        if (
            evidence.suppliedArtifactByteCount == 0 ||
            evidence.suppliedArtifactByteCount > MAX_TOTAL_RELEASE_ARTIFACT_BYTES ||
            evidence.artifactBytes.size != evidence.suppliedArtifactByteCount
        ) return false
        val expected = descriptor.sourceArtifactSha256 ?: return false
        val actual = sha256Hex(evidence.artifactBytes)
        val expectedManifest = descriptor.occurrenceManifestSha256 ?: return false
        val actualManifest = computeOccurrenceManifestSha256(
            fixtureId = descriptor.fixtureId,
            sessionId = descriptor.sessionId,
            sourceArtifactSha256 = actual,
            occurrences = evidence.occurrences,
        )
        return actual == expected && actualManifest == expectedManifest
    }

    internal fun computeDescriptorSha256(descriptor: MotionFixtureDescriptor): String {
        val canonical = buildString {
            append("motion-arcade-fixture-descriptor-v1\n")
            append("fixtureId=").append(descriptor.fixtureId).append('\n')
            append("partition=").append(descriptor.partition.name).append('\n')
            append("subjectId=").append(descriptor.subjectId).append('\n')
            append("sessionId=").append(descriptor.sessionId).append('\n')
            append("label=").append(descriptor.label.name).append('\n')
            append("durationNs=").append(descriptor.durationNs).append('\n')
            append("motionType=").append(descriptor.motionType?.name ?: "-").append('\n')
            append("sourceKind=").append(descriptor.sourceKind.name).append('\n')
            append("occurrenceCount=").append(descriptor.occurrenceCount).append('\n')
            append("sourceArtifactSha256=").append(descriptor.sourceArtifactSha256 ?: "-").append('\n')
            append("occurrenceManifestSha256=")
                .append(descriptor.occurrenceManifestSha256 ?: "-").append('\n')
        }
        return sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    private fun approvalMatches(
        descriptor: MotionFixtureDescriptor,
        approval: MotionFixtureCaptureApproval,
    ): Boolean =
        approval.approvalId.isSafeEvidenceId() && approval.reviewerId.isSafeEvidenceId() &&
            approval.descriptorSha256 == computeDescriptorSha256(descriptor) &&
            approval.sourceArtifactSha256 == descriptor.sourceArtifactSha256 &&
            approval.occurrenceManifestSha256 == descriptor.occurrenceManifestSha256

    internal fun computeOccurrenceManifestSha256(
        fixtureId: String,
        sessionId: String,
        sourceArtifactSha256: String,
        occurrences: Collection<MotionFixtureOccurrence>,
    ): String {
        val canonical = buildString {
            append("motion-arcade-occurrence-manifest-v1\n")
            append("fixtureId=").append(fixtureId).append('\n')
            append("sessionId=").append(sessionId).append('\n')
            append("sourceArtifactSha256=").append(sourceArtifactSha256).append('\n')
            occurrences.sortedBy(MotionFixtureOccurrence::occurrenceId).forEach { occurrence ->
                append("occurrence=")
                    .append(occurrence.occurrenceId).append('\t')
                    .append(occurrence.trialId).append('\t')
                    .append(occurrence.sessionId).append('\t')
                    .append(occurrence.startNs).append('\t')
                    .append(occurrence.endExclusiveNs).append('\t')
                    .append(occurrence.rearmCompleted).append('\n')
            }
        }
        return sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    private fun coverage(
        fixtures: Collection<MotionFixtureDescriptor>,
        label: FixtureLabel,
    ): Map<FixturePartition, Set<MotionType>> = fixtures.filter { it.label == label }
        .groupBy { it.partition }
        .mapValues { (_, rows) -> rows.mapNotNull { it.motionType }.toSet() }

    private fun neutralDuration(fixtures: Collection<MotionFixtureDescriptor>): Long? = try {
        fixtures.filter { it.label == FixtureLabel.NEUTRAL }.fold(0L) { total, fixture ->
            Math.addExact(total, fixture.durationNs)
        }
    } catch (_: ArithmeticException) {
        null
    }

    private fun occurrenceCount(
        evidence: Collection<MotionFixtureReleaseEvidence>,
        label: FixtureLabel,
        motionType: MotionType,
        violations: MutableSet<FixtureGovernanceViolation>,
    ): Long = try {
        evidence.filter { it.descriptor.label == label && it.descriptor.motionType == motionType }
            .fold(0L) { total, row -> Math.addExact(total, row.occurrences.size.toLong()) }
    } catch (_: ArithmeticException) {
        violations += FixtureGovernanceViolation.ARITHMETIC_OVERFLOW
        0L
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(bytes: ByteArray): String = sha256Hex(bytes)

    private inline fun <T> Collection<T>.sumExactOrNull(selector: (T) -> Long): Long? = try {
        fold(0L) { total, value -> Math.addExact(total, selector(value)) }
    } catch (_: ArithmeticException) {
        null
    }
}

internal const val MAX_EVIDENCE_ID_LENGTH: Int = 120
internal const val MIN_RELEASE_OCCURRENCE_DURATION_NS: Long = 100_000_000L
internal const val MAX_TOTAL_RELEASE_ARTIFACT_BYTES: Int = 64 * 1024 * 1024
internal const val MAX_TOTAL_RELEASE_OCCURRENCES: Int = 100_000

private val SAFE_EVIDENCE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
internal fun String.isSafeEvidenceId(): Boolean = matches(SAFE_EVIDENCE_ID_PATTERN)
internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
