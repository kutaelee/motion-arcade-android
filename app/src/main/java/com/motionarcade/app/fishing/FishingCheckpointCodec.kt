package com.motionarcade.app.fishing

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.FishingFish
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase
import com.motionarcade.games.fishing.FishingSnapshot
import com.motionarcade.games.fishing.FishingTimelineEpoch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.LinkedHashSet

/**
 * Bounded, versioned process-state encoding for a fishing safe checkpoint.
 *
 * This deliberately persists only deterministic game state. Camera frames, landmarks, tracking
 * predictions, gesture candidates, renderer state, and Android objects have no representation.
 */
internal object FishingCheckpointCodec {
    const val MAX_ENCODED_BYTES: Int = TypedCheckpointEnvelopeCodec.MAX_ENCODED_BYTES

    fun encode(snapshot: FishingSnapshot): ByteArray {
        // The domain restore path is the single semantic validator and canonicalizer for
        // checkpoint invariants. Persisting its checkpoint keeps non-result artifacts paused
        // without mutating the live controller that supplied the snapshot.
        val canonicalSnapshot = FishingGameSession.restore(snapshot).checkpoint()
        val payload = encodeLegacyPayload(canonicalSnapshot)
        return TypedCheckpointEnvelopeCodec.encode(
            gameId = GameId.FISHING,
            mode = GameMode.SOLO,
            payloadCodecId = PAYLOAD_CODEC_ID,
            payloadCodecVersion = PAYLOAD_CODEC_VERSION,
            payload = payload,
        )
    }

    private fun encodeLegacyPayload(snapshot: FishingSnapshot): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(PAYLOAD_CODEC_VERSION)
            output.writeInt(snapshot.schemaVersion)
            output.writeBoundedUtf(snapshot.sessionId)
            output.writeBoundedUtf(snapshot.gameId.name)
            output.writeBoundedUtf(snapshot.mode.name)
            output.writeBoundedUtf(snapshot.contentRevision)
            output.writeLong(snapshot.seed)
            output.writeBoundedUtf(snapshot.prngAlgorithmId)
            output.writeInt(snapshot.prngAlgorithmVersion)
            output.writeLong(snapshot.prngState)
            output.writeLong(snapshot.eventTimelineEpoch)
            output.writeLong(snapshot.eventTimelineBaseNs)
            output.writeLong(snapshot.eventTimelineSimulationBaseTick)
            output.writeInt(snapshot.eventTimelineLedgerVersion)
            output.writeInt(snapshot.eventTimelineLedger.size)
            snapshot.eventTimelineLedger.forEach { row ->
                output.writeLong(row.epoch)
                output.writeLong(row.baseNs)
                output.writeLong(row.simulationBaseTick)
            }
            output.writeInt(snapshot.calibrationRevision)
            output.writeLong(snapshot.simulationTick)
            output.writeBoundedUtf(snapshot.status.name)
            output.writeNullableEnum(snapshot.pauseReason)
            output.writeBoundedUtf(snapshot.phase.name)
            output.writeNullableEnum(snapshot.fish)
            output.writeNullableLong(snapshot.castEventSequence)
            output.writeNullableLong(snapshot.castEventEpoch)
            output.writeNullableLong(snapshot.castEventTick)
            output.writeNullableLong(snapshot.castEventTimestampNs)
            output.writeNullableLong(snapshot.castEventTimelineBaseNs)
            output.writeNullableLong(snapshot.castEventTimelineSimulationBaseTick)
            output.writeNullableFloat(snapshot.castQuality)
            output.writeNullableLong(snapshot.hookEventSequence)
            output.writeNullableLong(snapshot.hookEventEpoch)
            output.writeNullableLong(snapshot.hookEventTick)
            output.writeNullableLong(snapshot.hookEventTimestampNs)
            output.writeNullableLong(snapshot.hookEventTimelineBaseNs)
            output.writeNullableLong(snapshot.hookEventTimelineSimulationBaseTick)
            output.writeNullableFloat(snapshot.hookQuality)
            output.writeNullableLong(snapshot.biteAtTick)
            output.writeNullableLong(snapshot.hookDeadlineTick)
            output.writeNullableLong(snapshot.recoveryScheduledAtTick)
            output.writeInt(snapshot.reelCycles)
            output.writeInt(snapshot.tension)
            output.writeInt(snapshot.reelScore)
            output.writeInt(snapshot.tensionScore)
            output.writeNullableFloat(snapshot.netQuality)
            output.writeInt(snapshot.recoveryTokens)
            output.writeInt(snapshot.failureStreak)
            output.writeInt(snapshot.regionDifficulty)
            output.writeInt(snapshot.score)
            output.writeNullableEnum(snapshot.outcome)
            output.writeLong(snapshot.acceptedSequenceWatermark)
            output.writeLong(snapshot.acceptedEventTimestampWatermarkNs)
            output.writeLong(snapshot.lastAppliedSequence)
            output.writeLong(snapshot.lastAppliedEventEpoch)
            output.writeNullableLong(snapshot.lastAppliedEventTick)
            output.writeLong(snapshot.lastAppliedEventTimestampNs)
            output.writeNullableLong(snapshot.lastAppliedEventTimelineBaseNs)
            output.writeNullableLong(snapshot.lastAppliedEventTimelineSimulationBaseTick)
            output.writeLong(snapshot.lastStateChangingSequence)
            output.writeLong(snapshot.lastStateChangingEventEpoch)
            output.writeNullableLong(snapshot.lastStateChangingEventTick)
            output.writeLong(snapshot.lastStateChangingEventTimestampNs)
            output.writeNullableLong(snapshot.lastStateChangingEventTimelineBaseNs)
            output.writeNullableLong(snapshot.lastStateChangingEventTimelineSimulationBaseTick)
            output.writeNullableUtf(snapshot.pendingRewardId)
            val rewards = snapshot.committedRewardIds.sorted()
            output.writeInt(rewards.size)
            rewards.forEach { rewardId -> output.writeBoundedUtf(rewardId) }
        }
        return buffer.toByteArray().also { encoded ->
            require(encoded.size in 1..MAX_PAYLOAD_BYTES) {
                "Fishing checkpoint exceeds the process-state budget"
            }
        }
    }

    fun decode(encoded: ByteArray): FishingSnapshot? {
        if (encoded.size !in 1..MAX_ENCODED_BYTES) return null
        // Snapshot caller-owned mutable bytes only after the size preflight.
        val privateBytes = encoded.copyOf()
        val payload = if (TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(privateBytes)) {
            val envelope = TypedCheckpointEnvelopeCodec.decode(privateBytes) ?: return null
            if (
                envelope.gameId != GameId.FISHING || envelope.mode != GameMode.SOLO ||
                envelope.payloadCodecId != PAYLOAD_CODEC_ID ||
                envelope.payloadCodecVersion != PAYLOAD_CODEC_VERSION
            ) return null
            envelope.payload
        } else {
            // The only supported migration source is the exact prior game-owned binary payload.
            // The unrelated open generic JSON v1 contract is never treated as migration input.
            privateBytes
        }
        return decodeLegacyPayload(payload)
    }

    private fun decodeLegacyPayload(privateBytes: ByteArray): FishingSnapshot? {
        if (privateBytes.size !in 1..MAX_PAYLOAD_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(privateBytes)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == PAYLOAD_CODEC_VERSION)
                val schemaVersion = input.readInt()
                val sessionId = input.readBoundedUtf()
                val gameId = input.readEnum<GameId>()
                val mode = input.readEnum<GameMode>()
                val contentRevision = input.readBoundedUtf()
                val seed = input.readLong()
                val prngAlgorithmId = input.readBoundedUtf()
                val prngAlgorithmVersion = input.readInt()
                val prngState = input.readLong()
                val eventTimelineEpoch = input.readLong()
                val eventTimelineBaseNs = input.readLong()
                val eventTimelineSimulationBaseTick = input.readLong()
                val eventTimelineLedgerVersion = input.readInt()
                val timelineCount = input.readInt()
                require(timelineCount in 1..FishingGameSession.TIMELINE_LEDGER_CAPACITY)
                val timeline = ArrayList<FishingTimelineEpoch>(timelineCount)
                repeat(timelineCount) {
                    timeline += FishingTimelineEpoch(
                        epoch = input.readLong(),
                        baseNs = input.readLong(),
                        simulationBaseTick = input.readLong(),
                    )
                }
                val snapshot = FishingSnapshot(
                    schemaVersion = schemaVersion,
                    sessionId = sessionId,
                    gameId = gameId,
                    mode = mode,
                    contentRevision = contentRevision,
                    seed = seed,
                    prngAlgorithmId = prngAlgorithmId,
                    prngAlgorithmVersion = prngAlgorithmVersion,
                    prngState = prngState,
                    eventTimelineEpoch = eventTimelineEpoch,
                    eventTimelineBaseNs = eventTimelineBaseNs,
                    eventTimelineSimulationBaseTick = eventTimelineSimulationBaseTick,
                    eventTimelineLedgerVersion = eventTimelineLedgerVersion,
                    eventTimelineLedger = timeline,
                    calibrationRevision = input.readInt(),
                    simulationTick = input.readLong(),
                    status = input.readEnum(),
                    pauseReason = input.readNullableEnum(),
                    phase = input.readEnum(),
                    fish = input.readNullableEnum(),
                    castEventSequence = input.readNullableLong(),
                    castEventEpoch = input.readNullableLong(),
                    castEventTick = input.readNullableLong(),
                    castEventTimestampNs = input.readNullableLong(),
                    castEventTimelineBaseNs = input.readNullableLong(),
                    castEventTimelineSimulationBaseTick = input.readNullableLong(),
                    castQuality = input.readNullableFloat(),
                    hookEventSequence = input.readNullableLong(),
                    hookEventEpoch = input.readNullableLong(),
                    hookEventTick = input.readNullableLong(),
                    hookEventTimestampNs = input.readNullableLong(),
                    hookEventTimelineBaseNs = input.readNullableLong(),
                    hookEventTimelineSimulationBaseTick = input.readNullableLong(),
                    hookQuality = input.readNullableFloat(),
                    biteAtTick = input.readNullableLong(),
                    hookDeadlineTick = input.readNullableLong(),
                    recoveryScheduledAtTick = input.readNullableLong(),
                    reelCycles = input.readInt(),
                    tension = input.readInt(),
                    reelScore = input.readInt(),
                    tensionScore = input.readInt(),
                    netQuality = input.readNullableFloat(),
                    recoveryTokens = input.readInt(),
                    failureStreak = input.readInt(),
                    regionDifficulty = input.readInt(),
                    score = input.readInt(),
                    outcome = input.readNullableEnum<FishingOutcome>(),
                    acceptedSequenceWatermark = input.readLong(),
                    acceptedEventTimestampWatermarkNs = input.readLong(),
                    lastAppliedSequence = input.readLong(),
                    lastAppliedEventEpoch = input.readLong(),
                    lastAppliedEventTick = input.readNullableLong(),
                    lastAppliedEventTimestampNs = input.readLong(),
                    lastAppliedEventTimelineBaseNs = input.readNullableLong(),
                    lastAppliedEventTimelineSimulationBaseTick = input.readNullableLong(),
                    lastStateChangingSequence = input.readLong(),
                    lastStateChangingEventEpoch = input.readLong(),
                    lastStateChangingEventTick = input.readNullableLong(),
                    lastStateChangingEventTimestampNs = input.readLong(),
                    lastStateChangingEventTimelineBaseNs = input.readNullableLong(),
                    lastStateChangingEventTimelineSimulationBaseTick = input.readNullableLong(),
                    pendingRewardId = input.readNullableUtf(),
                    committedRewardIds = input.readRewardIds(),
                )
                require(input.available() == 0)
                // Restore both validates cross-field provenance and forces non-result games paused.
                FishingGameSession.restore(snapshot).checkpoint()
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writeBoundedUtf(value: String) {
        require(value.length <= MAX_STRING_CHARS)
        writeUTF(value)
    }

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedUtf(value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeNullableFloat(value: Float?) {
        writeBoolean(value != null)
        if (value != null) writeFloat(value)
    }

    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedUtf(value.name)
    }

    private fun DataInputStream.readBoundedUtf(): String = readUTF().also { value ->
        require(value.length <= MAX_STRING_CHARS)
    }

    private fun DataInputStream.readNullableUtf(): String? =
        if (readBoolean()) readBoundedUtf() else null

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun DataInputStream.readNullableFloat(): Float? =
        if (readBoolean()) readFloat() else null

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T =
        enumValueOf(readBoundedUtf())

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum() else null

    private fun DataInputStream.readRewardIds(): Set<String> {
        val count = readInt()
        require(count in 0..MAX_REWARD_IDS)
        val result = LinkedHashSet<String>(count)
        repeat(count) { require(result.add(readBoundedUtf())) }
        return result
    }

    private const val MAGIC = 0x4D_41_46_43
    const val PAYLOAD_CODEC_ID = "fishing-solo-checkpoint"
    const val PAYLOAD_CODEC_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 16 * 1024
    private const val MAX_STRING_CHARS = 512
    private const val MAX_REWARD_IDS = 1
}
