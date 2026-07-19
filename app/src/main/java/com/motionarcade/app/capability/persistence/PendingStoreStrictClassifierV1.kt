package com.motionarcade.app.capability.persistence

import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.recovery.RecoveryJournalMode
import java.util.concurrent.atomic.AtomicBoolean

internal enum class StrictModeStoreFileStateV1 {
    ABSENT,
    PRESENT,
}

/**
 * Opaque output of the future owner-free ModeStoreDiskVerifierV1. It is bound to the
 * same exact authority, frozen no-backup root and path bundle as the journal strict read.
 * There is deliberately no production raw-identity factory in this slice.
 */
internal class StrictModeStoreObservationV1 private constructor(
    private val authorityIdentity: JournalStorageAuthorityV1,
    private val generation: Long,
    private val pathsIdentity: JournalPathsV1,
    private val rootIdentity: JournalFrozenNoBackupRootV1,
    private val runtimeArtifactSha256: Sha256Digest,
    private val mode: RecoveryJournalMode,
    private val directoryDevice: Long,
    private val directoryInode: Long,
    internal val fileState: StrictModeStoreFileStateV1,
    internal val completeFileLength: ULong,
    internal val completeFileSha256: Sha256Digest?,
    internal val embeddedRecordSha256: Sha256Digest?,
) {
    private val claimed = AtomicBoolean(false)

    init {
        require(generation >= 0L)
        require(directoryDevice >= 0L)
        require(directoryInode >= 0L)
        when (fileState) {
            StrictModeStoreFileStateV1.ABSENT ->
                require(
                    completeFileLength == 0uL &&
                        completeFileSha256 == null &&
                        embeddedRecordSha256 == null,
                )
            StrictModeStoreFileStateV1.PRESENT ->
                require(
                    completeFileLength > 0uL &&
                        completeFileSha256 != null &&
                        embeddedRecordSha256 != null,
                )
        }
    }

    internal fun claimFor(
        authority: JournalStorageAuthorityV1,
        pendingHandle: ValidatedCurrentPendingHandleV1,
    ): Boolean {
        if (
            authority !== authorityIdentity ||
            generation != pendingHandle.generation ||
            pathsIdentity !== pendingHandle.pathsIdentity ||
            rootIdentity !== pendingHandle.rootIdentity ||
            runtimeArtifactSha256 != pendingHandle.runtimeArtifactSha256 ||
            mode != pendingHandle.mode ||
            !authority.matchesBoundOwner(authorityIdentity, generation, pathsIdentity)
        ) {
            return false
        }
        return claimed.compareAndSet(false, true)
    }

    internal fun directoryIdentity(): JournalDirectoryIdentityV1 =
        JournalDirectoryIdentityV1(directoryDevice, directoryInode)
}

/**
 * The future journal reducer implements this boundary. The bridge receives the exact receipt
 * object after its authority binding and one-shot CAS have succeeded. No boolean, kind enum or
 * raw pending payload can substitute for receipt delivery.
 */
internal interface PendingStoreReceiptConsumingReducerBridgeV1 {
    fun consumeManualRetrySaveNotCommitted(receipt: StrictModeStoreIdentityReceiptV1)

    fun consumeCommittedFinalClear(receipt: StrictModeStoreIdentityReceiptV1)
}

private enum class ReceiptTransitionV1 {
    MANUAL_RETRY_SAVE_NOT_COMMITTED,
    COMMITTED_FINAL_CLEAR,
}

/** Non-public, verifier-bound, journal-byte-bound and one-shot transition evidence. */
internal class StrictModeStoreIdentityReceiptV1 private constructor(
    private val authorityIdentity: JournalStorageAuthorityV1,
    private val generation: Long,
    private val pathsIdentity: JournalPathsV1,
    private val rootIdentity: JournalFrozenNoBackupRootV1,
    private val runtimeArtifactSha256: Sha256Digest,
    private val mode: RecoveryJournalMode,
    private val journalModeDirectoryIdentity: JournalDirectoryIdentityV1,
    private val journalFileIdentity: JournalFileIdentityV1,
    private val completeJournalLength: Int,
    private val completeJournalSha256: Sha256Digest,
    private val pendingBindingSha256: Sha256Digest,
    private val modeStoreDirectoryIdentity: JournalDirectoryIdentityV1,
    private val transition: ReceiptTransitionV1,
) {
    private val consumed = AtomicBoolean(false)

    internal fun deliverExpectedOld(
        authority: JournalStorageAuthorityV1,
        bridge: PendingStoreReceiptConsumingReducerBridgeV1,
    ) {
        deliver(authority, ReceiptTransitionV1.MANUAL_RETRY_SAVE_NOT_COMMITTED, bridge)
    }

    internal fun deliverIntendedNew(
        authority: JournalStorageAuthorityV1,
        bridge: PendingStoreReceiptConsumingReducerBridgeV1,
    ) {
        deliver(authority, ReceiptTransitionV1.COMMITTED_FINAL_CLEAR, bridge)
    }

    private fun deliver(
        authority: JournalStorageAuthorityV1,
        requiredTransition: ReceiptTransitionV1,
        bridge: PendingStoreReceiptConsumingReducerBridgeV1,
    ) {
        if (
            transition != requiredTransition ||
            authority !== authorityIdentity ||
            !authority.matchesBoundOwner(authorityIdentity, generation, pathsIdentity) ||
            pathsIdentity.frozenNoBackupRoot !== rootIdentity ||
            pathsIdentity.runtimeArtifactId.digest != runtimeArtifactSha256 ||
            pathsIdentity.mode != mode ||
            rootIdentity.directoryIdentity != pathsIdentity.frozenNoBackupRoot.directoryIdentity ||
            completeJournalLength <= 0 ||
            journalModeDirectoryIdentity.device < 0L ||
            journalModeDirectoryIdentity.inode < 0L ||
            journalFileIdentity.device < 0L ||
            journalFileIdentity.inode < 0L ||
            modeStoreDirectoryIdentity.device < 0L ||
            modeStoreDirectoryIdentity.inode < 0L ||
            !consumed.compareAndSet(false, true)
        ) {
            return
        }
        when (transition) {
            ReceiptTransitionV1.MANUAL_RETRY_SAVE_NOT_COMMITTED ->
                bridge.consumeManualRetrySaveNotCommitted(this)
            ReceiptTransitionV1.COMMITTED_FINAL_CLEAR ->
                bridge.consumeCommittedFinalClear(this)
        }
    }

    internal companion object {
        fun classify(
            authority: JournalStorageAuthorityV1,
            pendingHandle: ValidatedCurrentPendingHandleV1,
            observation: StrictModeStoreObservationV1,
        ): PendingStoreStrictClassificationV1 = pendingHandle.classify(authority, observation)

        fun issueExpectedOld(
            authority: JournalStorageAuthorityV1,
            material: ValidatedExpectedOldReceiptMaterialV1,
        ): PendingStoreStrictClassificationV1 {
            if (!material.isAuthentic(authority)) {
                return PendingStoreStrictClassificationV1.AuthorityRejected
            }
            return PendingStoreStrictClassificationV1.ExactExpectedOld(
                receipt(authority, material.source, material, null),
            )
        }

        fun issueIntendedNew(
            authority: JournalStorageAuthorityV1,
            material: ValidatedIntendedNewReceiptMaterialV1,
        ): PendingStoreStrictClassificationV1 {
            if (!material.isAuthentic(authority)) {
                return PendingStoreStrictClassificationV1.AuthorityRejected
            }
            return PendingStoreStrictClassificationV1.ExactIntendedNew(
                receipt(authority, material.source, null, material),
            )
        }

        private fun receipt(
            authority: JournalStorageAuthorityV1,
            source: ValidatedCurrentPendingHandleV1,
            expectedOld: ValidatedExpectedOldReceiptMaterialV1?,
            intendedNew: ValidatedIntendedNewReceiptMaterialV1?,
        ): StrictModeStoreIdentityReceiptV1 {
            check((expectedOld == null) != (intendedNew == null))
            return StrictModeStoreIdentityReceiptV1(
                authorityIdentity = authority,
                generation = source.generation,
                pathsIdentity = source.pathsIdentity,
                rootIdentity = source.rootIdentity,
                runtimeArtifactSha256 = source.runtimeArtifactSha256,
                mode = source.mode,
                journalModeDirectoryIdentity = source.journalModeDirectoryIdentity,
                journalFileIdentity = source.journalFileIdentity,
                completeJournalLength = source.completeJournalLength,
                completeJournalSha256 = source.completeJournalSha256,
                pendingBindingSha256 =
                    expectedOld?.pendingBindingSha256 ?: checkNotNull(intendedNew).pendingBindingSha256,
                modeStoreDirectoryIdentity =
                    expectedOld?.modeStoreDirectoryIdentity ?: checkNotNull(intendedNew).modeStoreDirectoryIdentity,
                transition = if (expectedOld != null) {
                    ReceiptTransitionV1.MANUAL_RETRY_SAVE_NOT_COMMITTED
                } else {
                    ReceiptTransitionV1.COMMITTED_FINAL_CLEAR
                },
            )
        }
    }
}

internal sealed interface PendingStoreStrictClassificationV1 {
    class ExactExpectedOld internal constructor(
        private val receipt: StrictModeStoreIdentityReceiptV1,
    ) : PendingStoreStrictClassificationV1 {
        fun deliverTo(
            authority: JournalStorageAuthorityV1,
            bridge: PendingStoreReceiptConsumingReducerBridgeV1,
        ) {
            receipt.deliverExpectedOld(authority, bridge)
        }
    }

    class ExactIntendedNew internal constructor(
        private val receipt: StrictModeStoreIdentityReceiptV1,
    ) : PendingStoreStrictClassificationV1 {
        fun deliverTo(
            authority: JournalStorageAuthorityV1,
            bridge: PendingStoreReceiptConsumingReducerBridgeV1,
        ) {
            receipt.deliverIntendedNew(authority, bridge)
        }
    }

    data object ThirdIdentity : PendingStoreStrictClassificationV1

    data object AuthorityRejected : PendingStoreStrictClassificationV1

    data object PendingRejected : PendingStoreStrictClassificationV1
}
