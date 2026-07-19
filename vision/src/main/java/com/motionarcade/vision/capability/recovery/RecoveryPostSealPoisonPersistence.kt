package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opaque storage owner for a mode-wide post-seal poison mutation. There is intentionally no
 * production factory in :vision; the Android strict-read/checked-replace owner must issue it.
 * Until that integration exists, recovery clean persistence remains fail-closed.
 */
sealed interface RecoveryDurablePostSealPoisonSink

internal enum class RecoveryPostSealPoisonWriteEvidence {
    /** The exact-current checked replace and both file and parent fsync completed. */
    CHECKED_REPLACE_FSYNCED,
    FAILED_OR_UNCERTAIN,
}

/**
 * The Android implementation must perform a new owner-free strict read on every call. A strict
 * read accepts only the canonical complete journal payload and never returns cached owner state.
 */
internal interface RecoveryPostSealPoisonStoragePort {
    fun freshStrictRead(): RecoveryJournalPayloadV5?

    /**
     * Replaces only if the complete current canonical payload still equals [expectedCurrentPayload]
     * and its digest equals [expectedCurrentPayloadSha256]. Success means the replacement file and
     * parent directory were fsynced; the caller independently performs the final fresh reread.
     */
    fun checkedReplaceAndFsync(
        expectedCurrentPayload: RecoveryJournalPayloadV5,
        expectedCurrentPayloadSha256: Sha256Digest,
        poisonedPayload: RecoveryJournalPayloadV5,
    ): RecoveryPostSealPoisonWriteEvidence
}

/** Opaque one-shot fence carried by clean reducer results into the checked journal writer. */
sealed interface RecoveryCleanJournalPersistenceFence

/** Exact checked-replace input. This value is data, not authority. */
class RecoveryJournalPersistenceDecision internal constructor(
    val expectedOldPayloadSha256: Sha256Digest,
    val payload: RecoveryJournalPayloadV5,
    /** True only when the defensive poison writer already proved this exact payload durable. */
    val alreadyDurablyCommitted: Boolean = false,
)

object RecoveryPostSealPoisonPersistenceBoundary {
    private class IssuedRecoveryDurablePostSealPoisonSink private constructor(
        val issuerIdentity: Any,
        val sinkGeneration: Long,
        val storagePort: RecoveryPostSealPoisonStoragePort,
    ) : RecoveryDurablePostSealPoisonSink {
        val writeInFlight = AtomicBoolean(false)
        val committed = AtomicBoolean(false)
        var committedPoisonPayload: RecoveryJournalPayloadV5? = null

        companion object {
            fun issue(
                issuerIdentity: Any,
                sinkGeneration: Long,
                storagePort: RecoveryPostSealPoisonStoragePort,
            ): IssuedRecoveryDurablePostSealPoisonSink =
                IssuedRecoveryDurablePostSealPoisonSink(
                    issuerIdentity,
                    sinkGeneration,
                    storagePort,
                )
        }
    }

    private val issuerIdentity = Any()

    internal fun isGenuineSink(candidate: RecoveryDurablePostSealPoisonSink): Boolean =
        genuineSink(candidate) != null

    /**
     * Performs one defensive mutation against a fresh exact TEARDOWN_PENDING or clean payload.
     * Any third state, stale checked replace, fsync uncertainty, or non-exact reread fails closed.
     * The returned payload is the exact durably reread poison payload; null makes no commit claim.
     */
    internal fun persistPoison(
        sink: RecoveryDurablePostSealPoisonSink,
        knownTeardownPayload: RecoveryJournalPayloadV5,
        knownCleanPayload: RecoveryJournalPayloadV5?,
    ): RecoveryJournalPayloadV5? {
        val issued = genuineSink(sink) ?: return null
        val poisonMode = postSealModePoison()
        val teardownPoison = poisonOnly(knownTeardownPayload, poisonMode) ?: return null
        val cleanPoison = knownCleanPayload?.let { poisonOnly(it, poisonMode) ?: return null }
        synchronized(issued) {
            if (issued.committed.get()) {
                return issued.committedPoisonPayload?.takeIf {
                    it == teardownPoison || it == cleanPoison
                }
            }
            if (!issued.writeInFlight.compareAndSet(false, true)) return null
        }

        val committedPayload = try {
            persistAgainstFreshCurrent(
                issued.storagePort,
                knownTeardownPayload,
                knownCleanPayload,
                poisonMode,
            )
        } catch (_: Throwable) {
            null
        }
        return if (committedPayload != null) {
            synchronized(issued) {
                issued.committedPoisonPayload = committedPayload
                issued.committed.set(true)
            }
            committedPayload
        } else {
            issued.writeInFlight.set(false)
            null
        }
    }

    /**
     * The fence issuer and validator live inside [RecoveryCleanClosureBoundary]. This facade keeps
     * the persistence call site narrow without exposing a JVM-callable fence-mint bridge.
     */
    fun resolveForCheckedPersistence(
        fence: RecoveryCleanJournalPersistenceFence,
    ): RecoveryJournalPersistenceDecision? =
        RecoveryCleanClosureBoundary.resolvePersistenceFence(fence)

    private fun persistAgainstFreshCurrent(
        port: RecoveryPostSealPoisonStoragePort,
        knownTeardownPayload: RecoveryJournalPayloadV5,
        knownCleanPayload: RecoveryJournalPayloadV5?,
        poisonMode: ModeControlV5.PostSealModePoison,
    ): RecoveryJournalPayloadV5? {
        val teardownDigest = payloadSha256(knownTeardownPayload) ?: return null
        val cleanDigest = knownCleanPayload?.let { payloadSha256(it) ?: return null }
        val freshCurrent = port.freshStrictRead() ?: return null
        val freshDigest = payloadSha256(freshCurrent) ?: return null
        val exactCurrent = when {
            freshCurrent == knownTeardownPayload && freshDigest == teardownDigest ->
                knownTeardownPayload
            knownCleanPayload != null &&
                freshCurrent == knownCleanPayload && freshDigest == cleanDigest ->
                knownCleanPayload
            else -> return null
        }
        val poisonPayload = poisonOnly(exactCurrent, poisonMode) ?: return null
        val poisonDigest = payloadSha256(poisonPayload) ?: return null
        if (port.checkedReplaceAndFsync(exactCurrent, freshDigest, poisonPayload) !=
            RecoveryPostSealPoisonWriteEvidence.CHECKED_REPLACE_FSYNCED
        ) {
            return null
        }
        val exactReread = port.freshStrictRead() ?: return null
        return exactReread.takeIf {
            it == poisonPayload && payloadSha256(it) == poisonDigest
        }
    }

    private fun poisonOnly(
        current: RecoveryJournalPayloadV5,
        poisonMode: ModeControlV5.PostSealModePoison,
    ): RecoveryJournalPayloadV5? {
        if (current.modeControl != ModeControlV5.None) return null
        return current.copy(modeControl = poisonMode)
    }

    private fun genuineSink(
        candidate: RecoveryDurablePostSealPoisonSink,
    ): IssuedRecoveryDurablePostSealPoisonSink? {
        val issued = candidate as? IssuedRecoveryDurablePostSealPoisonSink ?: return null
        return issued.takeIf {
            it.javaClass == IssuedRecoveryDurablePostSealPoisonSink::class.java &&
                it.issuerIdentity === issuerIdentity && it.sinkGeneration > 0L
        }
    }

    private fun payloadSha256(value: RecoveryJournalPayloadV5): Sha256Digest? =
        when (val encoded = RecoveryJournalV5Codec.encodePayload(value)) {
            is CapabilityDomainResult.Valid -> CanonicalManifestCodec.sha256(encoded.value)
            is CapabilityDomainResult.Invalid -> null
        }

    private fun postSealModePoison(): ModeControlV5.PostSealModePoison =
        ModeControlV5.PostSealModePoison(
            reason = PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
            cacheInvalidated = false,
        )
}
