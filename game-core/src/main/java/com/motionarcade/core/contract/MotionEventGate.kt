package com.motionarcade.core.contract

/** Produces the SSOT-recommended deterministic event ID without hashing or truncation. */
object DeterministicEventId {
    fun create(
        sessionId: String,
        playerId: PlayerId,
        sequenceNumber: Long,
    ): ContractResult<String> {
        if (sessionId.length !in 1..120) {
            return invalid(
                ContractViolation.OutOfRange(
                    "$/sessionId",
                    "string length 1..120",
                    sessionId.length.toString(),
                ),
            )
        }
        if (sequenceNumber < 0L) {
            return invalid(
                ContractViolation.OutOfRange(
                    "$/sequenceNumber",
                    "0..Long.MAX_VALUE",
                    sequenceNumber.toString(),
                ),
            )
        }
        return ContractResult.Valid("$sessionId/${playerId.name}/$sequenceNumber")
    }
}

sealed interface MotionEventGateViolation {
    data class InvalidContract(val violations: List<ContractViolation>) : MotionEventGateViolation

    data class DuplicateEventId(val eventId: String) : MotionEventGateViolation

    data class SequenceRewind(
        val playerId: PlayerId,
        val lastAccepted: Long,
        val received: Long,
    ) : MotionEventGateViolation

    data class EventTimestampRewind(
        val playerId: PlayerId,
        val lastAccepted: Long,
        val received: Long,
    ) : MotionEventGateViolation

    data class StaleCalibrationRevision(
        val active: Int,
        val received: Int,
    ) : MotionEventGateViolation

    data class FutureCalibrationRevision(
        val active: Int,
        val received: Int,
    ) : MotionEventGateViolation
}

sealed interface MotionEventGateResult {
    data class Accepted(val event: MotionEventEnvelope) : MotionEventGateResult

    data class Rejected(val violation: MotionEventGateViolation) : MotionEventGateResult
}

/**
 * Stateful pre-mutation gate. Evaluation and state recording are one synchronized action.
 * Sequence numbers are independent per player, so equal timestamps never collapse P1/P2.
 */
class MotionEventGate(
    activeCalibrationRevision: Int,
    private val recentEventCapacity: Int = 512,
) {
    private var calibrationRevision: Int = activeCalibrationRevision
    private val lastSequenceByPlayer = mutableMapOf<PlayerId, Long>()
    private val lastEventTimestampByPlayer = mutableMapOf<PlayerId, Long>()
    private val recentEventIds = LinkedHashSet<String>()

    init {
        require(activeCalibrationRevision >= 0) { "activeCalibrationRevision must be non-negative" }
        require(recentEventCapacity > 0) { "recentEventCapacity must be positive" }
    }

    @Synchronized
    fun evaluateAndRecord(event: MotionEventEnvelope): MotionEventGateResult {
        when (val validation = ContractValidators.validate(event)) {
            is ContractResult.Invalid -> return MotionEventGateResult.Rejected(
                MotionEventGateViolation.InvalidContract(validation.violations),
            )
            is ContractResult.Valid -> Unit
        }
        if (event.eventId in recentEventIds) {
            return MotionEventGateResult.Rejected(
                MotionEventGateViolation.DuplicateEventId(event.eventId),
            )
        }
        if (event.calibrationRevision < calibrationRevision) {
            return MotionEventGateResult.Rejected(
                MotionEventGateViolation.StaleCalibrationRevision(
                    calibrationRevision,
                    event.calibrationRevision,
                ),
            )
        }
        if (event.calibrationRevision > calibrationRevision) {
            return MotionEventGateResult.Rejected(
                MotionEventGateViolation.FutureCalibrationRevision(
                    calibrationRevision,
                    event.calibrationRevision,
                ),
            )
        }
        val lastSequence = lastSequenceByPlayer[event.playerId]
        if (lastSequence != null && event.sequenceNumber <= lastSequence) {
            return MotionEventGateResult.Rejected(
                MotionEventGateViolation.SequenceRewind(
                    event.playerId,
                    lastSequence,
                    event.sequenceNumber,
                ),
            )
        }
        val lastTimestamp = lastEventTimestampByPlayer[event.playerId]
        if (lastTimestamp != null && event.eventTimestampNs < lastTimestamp) {
            return MotionEventGateResult.Rejected(
                MotionEventGateViolation.EventTimestampRewind(
                    event.playerId,
                    lastTimestamp,
                    event.eventTimestampNs,
                ),
            )
        }

        lastSequenceByPlayer[event.playerId] = event.sequenceNumber
        lastEventTimestampByPlayer[event.playerId] = event.eventTimestampNs
        recentEventIds += event.eventId
        if (recentEventIds.size > recentEventCapacity) {
            recentEventIds.remove(recentEventIds.first())
        }
        return MotionEventGateResult.Accepted(event)
    }

    @Synchronized
    fun advanceCalibrationRevision(nextRevision: Int): Boolean {
        if (nextRevision <= calibrationRevision) return false
        calibrationRevision = nextRevision
        return true
    }
}
