package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opaque proof that the exact ACTIVE bytes came from a checked, fsynced, fresh strict reread.
 *
 * There is deliberately no production issuer in the vision module. The Android journal-storage
 * owner must be integrated before recovery can start native work; a payload, digest, boolean,
 * marker, or caller implementation cannot substitute for this receipt.
 */
sealed interface RecoveryDurableActiveJournalReceipt

/** Opaque proof for the exact checked ACTIVE -> TEARDOWN_PENDING durable replacement. */
sealed interface RecoveryDurableTeardownJournalReceipt

internal data class RecoveryDurableActiveMaterial(
    val payload: RecoveryJournalPayloadV5,
    val payloadSha256: Sha256Digest,
    val canonicalNoBackupRoot: String,
    val rootIdentityDevice: Long,
    val rootIdentityInode: Long,
    val modeDirectoryDevice: Long,
    val modeDirectoryInode: Long,
    val canonicalJournalPath: String,
    val fileIdentityDevice: Long,
    val fileIdentityInode: Long,
)

internal data class RecoveryDurableTeardownMaterial(
    val payload: RecoveryJournalPayloadV5,
    val payloadSha256: Sha256Digest,
    val fileIdentityDevice: Long,
    val fileIdentityInode: Long,
)

internal object RecoveryDurableJournalReceiptBoundary {
private data class DurableDirectoryIdentity(
    val device: Long,
    val inode: Long,
)

private data class DurableFileIdentity(
    val device: Long,
    val inode: Long,
)

private class IssuedRecoveryDurableActiveJournalReceipt private constructor(
    val issuerIdentity: Any,
    val receiptGeneration: Long,
    val payload: RecoveryJournalPayloadV5,
    val payloadSha256: Sha256Digest,
    val canonicalNoBackupRoot: String,
    val rootIdentity: DurableDirectoryIdentity,
    val modeDirectoryIdentity: DurableDirectoryIdentity,
    val canonicalJournalPath: String,
    val fileIdentity: DurableFileIdentity,
) : RecoveryDurableActiveJournalReceipt {
    val consumed = AtomicBoolean(false)
}

private class IssuedRecoveryDurableTeardownJournalReceipt private constructor(
    val issuerIdentity: Any,
    val receiptGeneration: Long,
    val payload: RecoveryJournalPayloadV5,
    val payloadSha256: Sha256Digest,
    val canonicalNoBackupRoot: String,
    val rootIdentity: DurableDirectoryIdentity,
    val modeDirectoryIdentity: DurableDirectoryIdentity,
    val canonicalJournalPath: String,
    val previousFileIdentity: DurableFileIdentity,
    val fileIdentity: DurableFileIdentity,
) : RecoveryDurableTeardownJournalReceipt {
    val consumed = AtomicBoolean(false)
}

/**
 * Consumer-only boundary. No `issue`, `create`, value factory, verifier port, or boolean bridge is
 * exported. Until the checked Android storage owner is moved behind this exact issuer, every
 * production call fails closed because no genuine receipt can exist.
 */
    private val issuerIdentity = Any()

    fun consumeActive(
        receipt: RecoveryDurableActiveJournalReceipt,
    ): RecoveryDurableActiveMaterial? {
        val issued = receipt as? IssuedRecoveryDurableActiveJournalReceipt ?: return null
        val active = issued.payload.active ?: return null
        val digest = payloadSha256(issued.payload) ?: return null
        if (issued.javaClass != IssuedRecoveryDurableActiveJournalReceipt::class.java ||
            issued.issuerIdentity !== issuerIdentity ||
            issued.receiptGeneration <= 0L ||
            issued.payloadSha256 != digest ||
            active.state != JournalActiveState.ACTIVE ||
            !validPathBinding(
                issued.canonicalNoBackupRoot,
                issued.canonicalJournalPath,
                issued.rootIdentity,
                issued.modeDirectoryIdentity,
                issued.fileIdentity,
            ) ||
            !issued.consumed.compareAndSet(false, true)
        ) {
            return null
        }
        return RecoveryDurableActiveMaterial(
            payload = issued.payload,
            payloadSha256 = digest,
            canonicalNoBackupRoot = issued.canonicalNoBackupRoot,
            rootIdentityDevice = issued.rootIdentity.device,
            rootIdentityInode = issued.rootIdentity.inode,
            modeDirectoryDevice = issued.modeDirectoryIdentity.device,
            modeDirectoryInode = issued.modeDirectoryIdentity.inode,
            canonicalJournalPath = issued.canonicalJournalPath,
            fileIdentityDevice = issued.fileIdentity.device,
            fileIdentityInode = issued.fileIdentity.inode,
        )
    }

    fun consumeTeardown(
        receipt: RecoveryDurableTeardownJournalReceipt,
        active: RecoveryDurableActiveMaterial,
        expectedPayloadSha256: Sha256Digest,
    ): RecoveryDurableTeardownMaterial? {
        val issued = receipt as? IssuedRecoveryDurableTeardownJournalReceipt ?: return null
        val payloadActive = issued.payload.active ?: return null
        val digest = payloadSha256(issued.payload) ?: return null
        if (issued.javaClass != IssuedRecoveryDurableTeardownJournalReceipt::class.java ||
            issued.issuerIdentity !== issuerIdentity ||
            issued.receiptGeneration <= 0L ||
            issued.payloadSha256 != digest ||
            digest != expectedPayloadSha256 ||
            payloadActive.state != JournalActiveState.TEARDOWN_PENDING ||
            issued.canonicalNoBackupRoot != active.canonicalNoBackupRoot ||
            issued.rootIdentity.device != active.rootIdentityDevice ||
            issued.rootIdentity.inode != active.rootIdentityInode ||
            issued.modeDirectoryIdentity.device != active.modeDirectoryDevice ||
            issued.modeDirectoryIdentity.inode != active.modeDirectoryInode ||
            issued.canonicalJournalPath != active.canonicalJournalPath ||
            issued.previousFileIdentity.device != active.fileIdentityDevice ||
            issued.previousFileIdentity.inode != active.fileIdentityInode ||
            !validPathBinding(
                issued.canonicalNoBackupRoot,
                issued.canonicalJournalPath,
                issued.rootIdentity,
                issued.modeDirectoryIdentity,
                issued.fileIdentity,
            ) ||
            !issued.consumed.compareAndSet(false, true)
        ) {
            return null
        }
        return RecoveryDurableTeardownMaterial(
            payload = issued.payload,
            payloadSha256 = digest,
            fileIdentityDevice = issued.fileIdentity.device,
            fileIdentityInode = issued.fileIdentity.inode,
        )
    }

    private fun validPathBinding(
        root: String,
        journal: String,
        rootIdentity: DurableDirectoryIdentity,
        modeIdentity: DurableDirectoryIdentity,
        fileIdentity: DurableFileIdentity,
    ): Boolean =
        root.isNotBlank() &&
            journal.isNotBlank() &&
            journal != root &&
            journal.startsWith(root.trimEnd('/', '\\') + java.io.File.separator) &&
            rootIdentity.device >= 0L && rootIdentity.inode >= 0L &&
            modeIdentity.device >= 0L && modeIdentity.inode >= 0L &&
            fileIdentity.device >= 0L && fileIdentity.inode >= 0L

    private fun payloadSha256(value: RecoveryJournalPayloadV5): Sha256Digest? =
        when (val encoded = RecoveryJournalV5Codec.encodePayload(value)) {
            is CapabilityDomainResult.Valid -> CanonicalManifestCodec.sha256(encoded.value)
            is CapabilityDomainResult.Invalid -> null
        }
}
