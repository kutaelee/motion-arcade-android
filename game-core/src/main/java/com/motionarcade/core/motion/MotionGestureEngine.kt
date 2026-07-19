package com.motionarcade.core.motion

import com.motionarcade.core.contract.ContractResult
import com.motionarcade.core.contract.DeterministicEventId
import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionEventEnvelope
import com.motionarcade.core.contract.MotionEventGate
import com.motionarcade.core.contract.MotionEventGateResult
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import java.util.Collections
import java.util.LinkedHashMap

enum class GesturePhase {
    IDLE,
    CANDIDATE,
    COOLDOWN,
}

data class GestureDefinition(
    val type: MotionType,
    val entryThreshold: Float,
    val exitThreshold: Float,
    val minimumConfidence: Float,
    val minimumHoldNs: Long,
    val maximumCandidateNs: Long,
    val cooldownNs: Long,
    val neutralRearmNs: Long,
    val exclusivityGroup: String? = null,
    val priority: Int = 0,
) {
    init {
        require(entryThreshold.isFinite() && entryThreshold in 0f..1f)
        require(exitThreshold.isFinite() && exitThreshold in 0f..entryThreshold)
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        require(minimumHoldNs >= 0L)
        require(maximumCandidateNs >= minimumHoldNs)
        require(cooldownNs >= 0L)
        require(neutralRearmNs >= 0L)
        require(exclusivityGroup == null || EXCLUSIVITY_GROUP_PATTERN.matches(exclusivityGroup))
    }

    private companion object {
        val EXCLUSIVITY_GROUP_PATTERN = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }
}

/**
 * A normalized, MediaPipe-independent gesture signal. Landmark interpretation remains in :vision;
 * games and this engine consume only a bounded scalar activation plus event quality metadata.
 */
data class MotionSignalSample(
    val playerId: PlayerId,
    val type: MotionType,
    val timestampNs: Long,
    val activation: Float,
    val quality: Float,
    val confidence: Float,
    val calibrationRevision: Int,
    val source: InputSource = InputSource.MOTION,
    val metadata: Map<String, Float> = emptyMap(),
)

enum class MotionSampleRejection {
    UNKNOWN_GESTURE,
    NON_MONOTONIC_TIMESTAMP,
    INVALID_SAMPLE,
    CALIBRATION_REVISION_MISMATCH,
    SEQUENCE_EXHAUSTED,
    CONTRACT_REJECTED,
}

sealed interface MotionSampleResult {
    data class Advanced(val phase: GesturePhase) : MotionSampleResult

    data class Emitted(val event: MotionEventEnvelope) : MotionSampleResult

    data class Rejected(val reason: MotionSampleRejection) : MotionSampleResult
}

/**
 * Timestamp-driven IDLE -> CANDIDATE -> one emit -> COOLDOWN state machine.
 *
 * A confirmed or timed-out cycle cannot arm again until both cooldown and a continuous neutral
 * interval have elapsed. This makes event count independent of camera frame rate and prevents a
 * held pose from emitting repeatedly.
 */
class MotionGestureEngine(
    private val sessionId: String,
    definitions: Collection<GestureDefinition>,
    activeCalibrationRevision: Int,
) {
    private data class SignalKey(val playerId: PlayerId, val type: MotionType)

    private data class GroupKey(val playerId: PlayerId, val group: String)

    private data class SignalState(
        var phase: GesturePhase = GesturePhase.IDLE,
        var candidateStartedNs: Long? = null,
        var cooldownStartedNs: Long? = null,
        var neutralStartedNs: Long? = null,
    )

    private val definitionsByType = definitions.associateBy { it.type }
    private val exclusiveOrder = Comparator<IndexedValue<MotionSignalSample>> { left, right ->
        val leftSample = left.value
        val rightSample = right.value
        compareValues(leftSample.timestampNs, rightSample.timestampNs)
            .takeIf { it != 0 }
            ?: compareValues(leftSample.playerId.ordinal, rightSample.playerId.ordinal)
                .takeIf { it != 0 }
            ?: compareValues(
                requireNotNull(definitionsByType[rightSample.type]).priority,
                requireNotNull(definitionsByType[leftSample.type]).priority,
            ).takeIf { it != 0 }
            ?: compareValues(rightSample.activation, leftSample.activation)
                .takeIf { it != 0 }
            ?: compareValues(leftSample.type.name, rightSample.type.name)
                .takeIf { it != 0 }
            ?: compareValues(left.index, right.index)
    }
    private val states = mutableMapOf<SignalKey, SignalState>()
    private val exclusiveOwners = mutableMapOf<GroupKey, MotionType>()
    private val lastFrameTimestampByPlayer = mutableMapOf<PlayerId, Long>()
    private val lastSequenceByPlayer = mutableMapOf<PlayerId, Long>()
    private val neutralRearmRequired = mutableSetOf<PlayerId>()
    private val neutralRearmStartedByPlayer = mutableMapOf<PlayerId, Long>()
    private val gate = MotionEventGate(activeCalibrationRevision)
    private var calibrationRevision = activeCalibrationRevision

    init {
        require(sessionId.length in 1..120)
        require(activeCalibrationRevision >= 0)
        require(definitionsByType.size == definitions.size) { "Gesture types must be unique" }
        require(definitionsByType.isNotEmpty()) { "At least one gesture definition is required" }
    }

    /**
     * Processes one complete signal frame. Each represented player must provide exactly one signal
     * for every configured gesture definition, all from the same camera frame and one strictly
     * increasing timestamp. Missing or duplicate gesture signals reject that player's whole frame
     * without advancing its state. Results preserve caller order even though exclusive candidates
     * use deterministic priority.
     */
    @Synchronized
    fun processFrame(samples: List<MotionSignalSample>): List<MotionSampleResult> {
        if (samples.isEmpty()) return emptyList()
        val results = MutableList<MotionSampleResult?>(samples.size) { null }
        val admitted = mutableListOf<IndexedValue<MotionSignalSample>>()

        samples.withIndex().groupBy { it.value.playerId }.values.forEach { playerRows ->
            val typeCounts = playerRows.groupingBy { it.value.type }.eachCount()
            if (
                typeCounts.keys != definitionsByType.keys ||
                typeCounts.values.any { count -> count != 1 }
            ) {
                playerRows.forEach { row ->
                    results[row.index] =
                        MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
                }
                return@forEach
            }
            if (playerRows.any { row -> !row.value.isValid() }) {
                playerRows.forEach { row ->
                    results[row.index] =
                        MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
                }
                return@forEach
            }
            if (playerRows.any { row -> row.value.calibrationRevision != calibrationRevision }) {
                playerRows.forEach { row ->
                    results[row.index] = MotionSampleResult.Rejected(
                        MotionSampleRejection.CALIBRATION_REVISION_MISMATCH,
                    )
                }
                return@forEach
            }
            val timestamps = playerRows.map { it.value.timestampNs }.distinct()
            if (timestamps.size != 1) {
                playerRows.forEach { row ->
                    results[row.index] =
                        MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
                }
                return@forEach
            }
            val timestamp = timestamps.single()
            val playerId = playerRows.first().value.playerId
            val previousFrame = lastFrameTimestampByPlayer[playerId]
            if (previousFrame != null && timestamp <= previousFrame) {
                playerRows.forEach { row ->
                    results[row.index] = MotionSampleResult.Rejected(
                        MotionSampleRejection.NON_MONOTONIC_TIMESTAMP,
                    )
                }
                return@forEach
            }
            lastFrameTimestampByPlayer[playerId] = timestamp
            if (playerId in neutralRearmRequired) {
                val completeNeutralFrame = playerRows.all { row ->
                    val sample = row.value
                    val definition = requireNotNull(definitionsByType[sample.type])
                    sample.isEligible(definition) && sample.activation <= definition.exitThreshold
                }
                if (completeNeutralFrame) {
                    val started = neutralRearmStartedByPlayer.getOrPut(playerId) { timestamp }
                    val requiredNeutralNs = playerRows.maxOf { row ->
                        requireNotNull(definitionsByType[row.value.type]).neutralRearmNs
                    }
                    if (timestamp - started >= requiredNeutralNs) {
                        neutralRearmRequired.remove(playerId)
                        neutralRearmStartedByPlayer.remove(playerId)
                        playerRows.forEach { row ->
                            results[row.index] = MotionSampleResult.Advanced(GesturePhase.IDLE)
                        }
                    } else {
                        playerRows.forEach { row ->
                            results[row.index] = MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
                        }
                    }
                } else {
                    neutralRearmStartedByPlayer.remove(playerId)
                    playerRows.forEach { row ->
                        results[row.index] = MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
                    }
                }
                return@forEach
            }
            admitted += playerRows
        }

        admitted.sortedWith(exclusiveOrder).forEach { row ->
            results[row.index] = processAdmitted(row.value)
        }
        return results.map { requireNotNull(it) }
    }

    /**
     * Emits one explicit touch-fallback action through the same event sequence and contract gate
     * used by motion confirmations. Touch input is already semantic, so it does not pretend to be
     * a camera gesture or fabricate a hold interval. A completed tap implies release, so a later
     * tap may time-rearm only after the configured cooldown. Until then, both repeated and
     * cross-gesture taps in the same exclusivity group remain blocked. Motion input keeps its
     * stricter neutral-signal re-arm rule.
     */
    @Synchronized
    fun processTouch(
        playerId: PlayerId,
        type: MotionType,
        timestampNs: Long,
        quality: Float = 1.0f,
        metadata: Map<String, Float> = emptyMap(),
    ): MotionSampleResult {
        val definition = definitionsByType[type]
            ?: return MotionSampleResult.Rejected(MotionSampleRejection.UNKNOWN_GESTURE)
        val metadataSnapshot = immutableCallerMetadata(metadata)
            ?: return MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
        val sample = MotionSignalSample(
            playerId = playerId,
            type = type,
            timestampNs = timestampNs,
            activation = 1.0f,
            quality = quality,
            confidence = 1.0f,
            calibrationRevision = calibrationRevision,
            source = InputSource.TOUCH,
            metadata = metadataSnapshot,
        )
        if (!sample.isValid()) {
            return MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
        }
        val previousTimestamp = lastFrameTimestampByPlayer[playerId]
        if (previousTimestamp != null && timestampNs <= previousTimestamp) {
            return MotionSampleResult.Rejected(MotionSampleRejection.NON_MONOTONIC_TIMESTAMP)
        }
        lastFrameTimestampByPlayer[playerId] = timestampNs
        val state = states.getOrPut(SignalKey(playerId, type), ::SignalState)
        val groupKey = definition.exclusivityGroup?.let { GroupKey(playerId, it) }
        val ownerType = groupKey?.let(exclusiveOwners::get)
        if (ownerType != null) {
            val ownerDefinition = requireNotNull(definitionsByType[ownerType])
            val ownerState = states[SignalKey(playerId, ownerType)]
            if (ownerState == null || !touchCooldownElapsed(ownerState, ownerDefinition, timestampNs)) {
                return MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
            }
            resetState(ownerState)
            exclusiveOwners.remove(groupKey)
        }
        if (state.phase == GesturePhase.COOLDOWN) {
            if (!touchCooldownElapsed(state, definition, timestampNs)) {
                return MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
            }
            resetState(state)
        }
        return confirm(sample, definition, state, holdNs = 0L)
    }

    private fun processAdmitted(sample: MotionSignalSample): MotionSampleResult {
        val definition = requireNotNull(definitionsByType[sample.type])
        val key = SignalKey(sample.playerId, sample.type)
        val state = states.getOrPut(key, ::SignalState)
        val groupKey = definition.exclusivityGroup?.let { GroupKey(sample.playerId, it) }
        val owner = groupKey?.let(exclusiveOwners::get)
        if (owner != null && owner != sample.type) {
            enterCooldown(state, sample, definition)
            return MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
        }

        val result = when (state.phase) {
            GesturePhase.IDLE -> processIdle(sample, definition, state)
            GesturePhase.CANDIDATE -> processCandidate(sample, definition, state)
            GesturePhase.COOLDOWN -> processCooldown(sample, definition, state)
        }
        if (
            groupKey != null &&
            exclusiveOwners[groupKey] == sample.type &&
            result == MotionSampleResult.Advanced(GesturePhase.IDLE)
        ) {
            exclusiveOwners.remove(groupKey)
        }
        return result
    }

    @Synchronized
    fun advanceCalibrationRevision(nextRevision: Int): Boolean {
        if (!gate.advanceCalibrationRevision(nextRevision)) return false
        calibrationRevision = nextRevision
        states.clear()
        exclusiveOwners.clear()
        return true
    }

    /** Clears only in-flight candidates/cooldowns when a game phase changes. */
    @Synchronized
    fun resetTransientState(playerId: PlayerId): Boolean {
        if (playerId == PlayerId.AI) return false
        states.keys.removeAll { key -> key.playerId == playerId }
        exclusiveOwners.keys.removeAll { key -> key.playerId == playerId }
        return true
    }

    /**
     * Re-arms one player after a safe pause/process checkpoint without restoring transient gesture
     * candidates. The sequence watermark prevents deterministic event IDs from being reused, and
     * the timestamp fence rejects camera callbacks captured before recovery completed.
     *
     * Watermarks are monotonic: a caller can advance a fence but can never roll engine state back.
     */
    @Synchronized
    fun rearmAfterCheckpoint(
        playerId: PlayerId,
        acceptedSequenceWatermark: Long,
        timestampFenceNs: Long,
    ): Boolean {
        if (
            playerId == PlayerId.AI ||
            acceptedSequenceWatermark < -1L ||
            timestampFenceNs < 0L
        ) {
            return false
        }
        val previousSequence = lastSequenceByPlayer[playerId] ?: -1L
        val previousTimestamp = lastFrameTimestampByPlayer[playerId] ?: -1L
        if (
            acceptedSequenceWatermark < previousSequence ||
            timestampFenceNs < previousTimestamp
        ) {
            return false
        }

        states.keys.removeAll { key -> key.playerId == playerId }
        exclusiveOwners.keys.removeAll { key -> key.playerId == playerId }
        neutralRearmRequired += playerId
        neutralRearmStartedByPlayer.remove(playerId)
        if (acceptedSequenceWatermark == -1L) {
            lastSequenceByPlayer.remove(playerId)
        } else {
            lastSequenceByPlayer[playerId] = acceptedSequenceWatermark
        }
        lastFrameTimestampByPlayer[playerId] = timestampFenceNs
        return true
    }

    /** True only after this player has held the configured neutral pose long enough to re-arm. */
    @Synchronized
    fun isNeutralRearmComplete(playerId: PlayerId): Boolean =
        playerId !in neutralRearmRequired

    private fun processIdle(
        sample: MotionSignalSample,
        definition: GestureDefinition,
        state: SignalState,
    ): MotionSampleResult {
        if (!sample.isEligible(definition) || sample.activation < definition.entryThreshold) {
            return MotionSampleResult.Advanced(GesturePhase.IDLE)
        }
        state.phase = GesturePhase.CANDIDATE
        state.candidateStartedNs = sample.timestampNs
        return if (definition.minimumHoldNs == 0L) {
            confirm(sample, definition, state, 0L)
        } else {
            MotionSampleResult.Advanced(GesturePhase.CANDIDATE)
        }
    }

    private fun processCandidate(
        sample: MotionSignalSample,
        definition: GestureDefinition,
        state: SignalState,
    ): MotionSampleResult {
        val started = requireNotNull(state.candidateStartedNs)
        val elapsed = sample.timestampNs - started
        if (!sample.isEligible(definition) || sample.activation < definition.exitThreshold) {
            enterCooldown(state, sample, definition)
            return MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
        }
        if (elapsed > definition.maximumCandidateNs) {
            enterCooldown(state, sample, definition)
            return MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
        }
        if (sample.activation >= definition.entryThreshold && elapsed >= definition.minimumHoldNs) {
            return confirm(sample, definition, state, elapsed)
        }
        return MotionSampleResult.Advanced(GesturePhase.CANDIDATE)
    }

    private fun processCooldown(
        sample: MotionSignalSample,
        definition: GestureDefinition,
        state: SignalState,
    ): MotionSampleResult {
        if (sample.isEligible(definition) && sample.activation <= definition.exitThreshold) {
            if (state.neutralStartedNs == null) state.neutralStartedNs = sample.timestampNs
        } else {
            state.neutralStartedNs = null
        }
        val cooldownElapsed = sample.timestampNs - requireNotNull(state.cooldownStartedNs)
        val neutralElapsed = state.neutralStartedNs?.let { sample.timestampNs - it }
        if (
            cooldownElapsed >= definition.cooldownNs &&
            neutralElapsed != null &&
            neutralElapsed >= definition.neutralRearmNs
        ) {
            state.phase = GesturePhase.IDLE
            state.candidateStartedNs = null
            state.cooldownStartedNs = null
            state.neutralStartedNs = null
            return MotionSampleResult.Advanced(GesturePhase.IDLE)
        }
        return MotionSampleResult.Advanced(GesturePhase.COOLDOWN)
    }

    private fun confirm(
        sample: MotionSignalSample,
        definition: GestureDefinition,
        state: SignalState,
        holdNs: Long,
    ): MotionSampleResult {
        enterCooldown(state, sample, definition)
        val lastSequence = lastSequenceByPlayer[sample.playerId]
        if (lastSequence == Long.MAX_VALUE) {
            return MotionSampleResult.Rejected(MotionSampleRejection.SEQUENCE_EXHAUSTED)
        }
        val sequence = if (lastSequence == null) 0L else lastSequence + 1L
        val eventId = when (val result = DeterministicEventId.create(sessionId, sample.playerId, sequence)) {
            is ContractResult.Valid -> result.value
            is ContractResult.Invalid -> {
                return MotionSampleResult.Rejected(MotionSampleRejection.CONTRACT_REJECTED)
            }
        }
        val eventMetadata = immutableEventMetadata(
            callerMetadata = sample.metadata,
            activation = sample.activation,
            holdNs = holdNs,
        ) ?: return MotionSampleResult.Rejected(MotionSampleRejection.INVALID_SAMPLE)
        val event = MotionEventEnvelope(
            eventId = eventId,
            sessionId = sessionId,
            playerId = sample.playerId,
            sequenceNumber = sequence,
            type = sample.type,
            quality = sample.quality,
            confidence = sample.confidence,
            eventTimestampNs = sample.timestampNs,
            calibrationRevision = sample.calibrationRevision,
            source = sample.source,
            metadata = eventMetadata,
        )
        return when (gate.evaluateAndRecord(event)) {
            is MotionEventGateResult.Accepted -> {
                lastSequenceByPlayer[sample.playerId] = sequence
                definition.exclusivityGroup?.let { group ->
                    exclusiveOwners[GroupKey(sample.playerId, group)] = sample.type
                }
                MotionSampleResult.Emitted(event)
            }
            is MotionEventGateResult.Rejected -> {
                MotionSampleResult.Rejected(MotionSampleRejection.CONTRACT_REJECTED)
            }
        }
    }

    private fun enterCooldown(
        state: SignalState,
        sample: MotionSignalSample,
        definition: GestureDefinition,
    ) {
        state.phase = GesturePhase.COOLDOWN
        state.candidateStartedNs = null
        state.cooldownStartedNs = sample.timestampNs
        state.neutralStartedNs =
            if (
                sample.isEligible(definition) &&
                sample.activation <= definition.exitThreshold
            ) {
                sample.timestampNs
            } else {
                null
            }
    }

    private fun touchCooldownElapsed(
        state: SignalState,
        definition: GestureDefinition,
        timestampNs: Long,
    ): Boolean {
        if (state.phase != GesturePhase.COOLDOWN) return true
        val startedNs = state.cooldownStartedNs ?: return false
        if (timestampNs < startedNs) return false
        return timestampNs - startedNs >= definition.cooldownNs
    }

    private fun resetState(state: SignalState) {
        state.phase = GesturePhase.IDLE
        state.candidateStartedNs = null
        state.cooldownStartedNs = null
        state.neutralStartedNs = null
    }

    private fun immutableCallerMetadata(source: Map<String, Float>): Map<String, Float>? {
        val copy = LinkedHashMap<String, Float>()
        return try {
            val iterator = source.entries.iterator()
            while (iterator.hasNext()) {
                if (copy.size >= MAX_CALLER_METADATA) return null
                val entry = iterator.next()
                val key = entry.key
                val value = entry.value
                if (
                    key in RESERVED_METADATA ||
                    !value.isFinite() ||
                    copy.put(key, value) != null
                ) {
                    return null
                }
            }
            Collections.unmodifiableMap(copy)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun immutableEventMetadata(
        callerMetadata: Map<String, Float>,
        activation: Float,
        holdNs: Long,
    ): Map<String, Float>? {
        val copy = immutableCallerMetadata(callerMetadata) ?: return null
        val eventMetadata = LinkedHashMap<String, Float>(copy.size + RESERVED_METADATA.size)
        eventMetadata.putAll(copy)
        eventMetadata["activation"] = activation
        eventMetadata["holdMs"] = holdNs / NANOS_PER_MILLISECOND.toFloat()
        return Collections.unmodifiableMap(eventMetadata)
    }

    private fun MotionSignalSample.isEligible(definition: GestureDefinition): Boolean =
        confidence >= definition.minimumConfidence

    private fun MotionSignalSample.isValid(): Boolean =
        playerId != PlayerId.AI &&
            timestampNs >= 0L &&
            activation.isFinite() && activation in 0f..1f &&
            quality.isFinite() && quality in 0f..1f &&
            confidence.isFinite() && confidence in 0f..1f &&
            calibrationRevision >= 0 &&
            metadata.size <= MAX_CALLER_METADATA &&
            metadata.keys.none { it in RESERVED_METADATA } &&
            metadata.values.all(Float::isFinite)

    private companion object {
        const val MAX_CALLER_METADATA = 30
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val RESERVED_METADATA = setOf("activation", "holdMs")

    }
}
