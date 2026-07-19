package com.motionarcade.core.motion

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import org.junit.Assert.assertEquals
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
        assertTrue(MotionFixtureGovernance.validate(fixtures.map { it.descriptor }).isEmpty())

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

    private fun validCatalog(): List<MotionFixtureDescriptor> {
        val positives = FixturePartition.entries.flatMap { partition ->
            MotionType.entries.take(6).mapIndexed { index, type ->
                descriptor(
                    id = "${partition.name.lowercase()}-positive-$index",
                    partition = partition,
                    motionType = type,
                )
            }
        }
        val hardNegatives = FixturePartition.entries.flatMap { partition ->
            MotionType.entries.take(6).mapIndexed { index, type ->
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
    ) = MotionFixtureDescriptor(
        id,
        partition,
        subject,
        session,
        label,
        durationNs,
        motionType,
    )

    private fun parse(line: String): ExecutableFixture {
        val fields = line.split('\t')
        require(fields.size == 9)
        return ExecutableFixture(
            descriptor = MotionFixtureDescriptor(
                fixtureId = fields[0],
                partition = FixturePartition.valueOf(fields[1]),
                subjectId = fields[2],
                sessionId = fields[3],
                label = FixtureLabel.valueOf(fields[4]),
                durationNs = fields[5].toLong(),
                motionType = fields[6].takeUnless { it == "-" }?.let(MotionType::valueOf),
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
