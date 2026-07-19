package com.motionarcade.core.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEventGateTest {
    @Test
    fun deterministicId_isStableDistinctAndWithinSchemaMaximum() {
        val first = validId("session-1", PlayerId.P1, 5)
        val repeated = validId("session-1", PlayerId.P1, 5)
        val p2 = validId("session-1", PlayerId.P2, 5)
        val maximum = validId("s".repeat(120), PlayerId.P1, Long.MAX_VALUE)

        assertEquals(first, repeated)
        assertNotEquals(first, p2)
        assertTrue(maximum.length <= 160)
    }

    @Test
    fun duplicateEventId_isRejectedExactlyOnce() {
        val gate = MotionEventGate(activeCalibrationRevision = 1)
        val event = event(PlayerId.P1, sequence = 0, revision = 1)

        assertTrue(gate.evaluateAndRecord(event) is MotionEventGateResult.Accepted)
        val duplicate = gate.evaluateAndRecord(event)

        assertRejected<MotionEventGateViolation.DuplicateEventId>(duplicate)
    }

    @Test
    fun rewindIsRejectedPerPlayerAndNonDeterministicIdFailsContract() {
        val gate = MotionEventGate(activeCalibrationRevision = 1)
        assertTrue(gate.evaluateAndRecord(event(PlayerId.P1, 4, 1)) is MotionEventGateResult.Accepted)

        val rewind = gate.evaluateAndRecord(event(PlayerId.P1, 3, 1))
        val sameSequence = gate.evaluateAndRecord(
            event(PlayerId.P1, 4, 1).copy(eventId = "different-id"),
        )

        assertRejected<MotionEventGateViolation.SequenceRewind>(rewind)
        assertRejected<MotionEventGateViolation.InvalidContract>(sameSequence)
    }

    @Test
    fun staleAndFutureCalibrationRevisionsAreRejectedWithoutPoisoningSequence() {
        val gate = MotionEventGate(activeCalibrationRevision = 2)

        assertRejected<MotionEventGateViolation.StaleCalibrationRevision>(
            gate.evaluateAndRecord(event(PlayerId.P1, 0, 1)),
        )
        assertRejected<MotionEventGateViolation.FutureCalibrationRevision>(
            gate.evaluateAndRecord(event(PlayerId.P1, 0, 3)),
        )
        assertTrue(gate.evaluateAndRecord(event(PlayerId.P1, 0, 2)) is MotionEventGateResult.Accepted)
    }

    @Test
    fun equalTimestampP1AndP2EventsAreBothPreservedInArrivalOrder() {
        val gate = MotionEventGate(activeCalibrationRevision = 7)
        val timestamp = 42L
        val input = listOf(
            event(PlayerId.P1, sequence = 9, revision = 7, timestamp = timestamp),
            event(PlayerId.P2, sequence = 3, revision = 7, timestamp = timestamp),
        )

        val accepted = input.mapNotNull { item ->
            (gate.evaluateAndRecord(item) as? MotionEventGateResult.Accepted)?.event
        }

        assertEquals(listOf(PlayerId.P1, PlayerId.P2), accepted.map { it.playerId })
        assertEquals(listOf(9L, 3L), accepted.map { it.sequenceNumber })
        assertTrue(accepted.all { it.eventTimestampNs == timestamp })
    }

    @Test
    fun higherSequenceWithAnOlderTimestampIsRejectedWithoutAdvancingTheGate() {
        val gate = MotionEventGate(activeCalibrationRevision = 7)
        assertTrue(
            gate.evaluateAndRecord(event(PlayerId.P1, sequence = 1, revision = 7, timestamp = 100))
                is MotionEventGateResult.Accepted,
        )

        val rewind = gate.evaluateAndRecord(event(PlayerId.P1, sequence = 2, revision = 7, timestamp = 99))

        assertRejected<MotionEventGateViolation.EventTimestampRewind>(rewind)
        assertTrue(
            gate.evaluateAndRecord(event(PlayerId.P1, sequence = 2, revision = 7, timestamp = 101))
                is MotionEventGateResult.Accepted,
        )
    }

    @Test
    fun invalidContractIsRejectedBeforeStateRecording() {
        val gate = MotionEventGate(activeCalibrationRevision = 1)
        val invalid = event(PlayerId.P1, 0, 1).copy(confidence = Float.NaN)

        assertRejected<MotionEventGateViolation.InvalidContract>(gate.evaluateAndRecord(invalid))
        assertTrue(gate.evaluateAndRecord(event(PlayerId.P1, 0, 1)) is MotionEventGateResult.Accepted)
    }

    @Test
    fun calibrationAdvanceRequiresStrictIncreaseAndRejectsOldEvents() {
        val gate = MotionEventGate(activeCalibrationRevision = 1)

        assertTrue(gate.advanceCalibrationRevision(2))
        assertTrue(!gate.advanceCalibrationRevision(2))
        assertRejected<MotionEventGateViolation.StaleCalibrationRevision>(
            gate.evaluateAndRecord(event(PlayerId.P1, 0, 1)),
        )
        assertTrue(gate.evaluateAndRecord(event(PlayerId.P1, 0, 2)) is MotionEventGateResult.Accepted)
    }

    private fun event(
        player: PlayerId,
        sequence: Long,
        revision: Int,
        timestamp: Long = 100,
    ): MotionEventEnvelope = MotionEventEnvelope(
        eventId = validId("session-1", player, sequence),
        sessionId = "session-1",
        playerId = player,
        sequenceNumber = sequence,
        type = MotionType.FISH_CAST,
        quality = 1f,
        confidence = 1f,
        eventTimestampNs = timestamp,
        calibrationRevision = revision,
        source = InputSource.FIXTURE,
        metadata = emptyMap(),
    )

    private fun validId(sessionId: String, playerId: PlayerId, sequence: Long): String =
        (DeterministicEventId.create(sessionId, playerId, sequence) as ContractResult.Valid).value

    private inline fun <reified T : MotionEventGateViolation> assertRejected(
        result: MotionEventGateResult,
    ): T {
        assertTrue("Expected Rejected, got $result", result is MotionEventGateResult.Rejected)
        val violation = (result as MotionEventGateResult.Rejected).violation
        assertTrue("Expected ${T::class.java.simpleName}, got $violation", violation is T)
        return violation as T
    }
}
