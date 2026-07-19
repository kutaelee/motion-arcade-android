package com.motionarcade.app.capability.persistence

import android.content.Context
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.recovery.ExpectedOldFileState
import com.motionarcade.vision.capability.recovery.PendingStoreCommitV5
import com.motionarcade.vision.capability.recovery.RecoveryJournalMode
import com.motionarcade.vision.capability.recovery.RecoveryJournalPayloadV5
import com.motionarcade.vision.capability.recovery.RecoveryJournalV5Codec
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val JOURNAL_OPERATION_DEADLINE_NS: Long = 1_000_000_000L
internal const val JOURNAL_DIRECTORY_MODE: Int = 448 // 0700
internal const val JOURNAL_FILE_MODE: Int = 384 // 0600

/**
 * One canonical path object. Equality is deliberately object identity: reconstructing the same
 * string does not create a path that a bound kernel will accept.
 */
internal class JournalPathV1 private constructor(val value: String) {
    companion object {
        fun canonicalAbsolute(value: String): JournalPathV1 {
            require(value.startsWith('/'))
            require(value == "/" || !value.endsWith('/'))
            require(!value.contains('\\'))
            require(!value.contains('\u0000'))
            require(!value.contains("//"))
            require(
                value == "/" || value.split('/').drop(1).all { component ->
                    component.isNotEmpty() &&
                        component != "." &&
                        component != ".." &&
                        CANONICAL_COMPONENT.matches(component)
                },
            )
            return JournalPathV1(value)
        }

        private val CANONICAL_COMPONENT = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")
    }
}

internal data class JournalDirectoryIdentityV1(
    val device: Long,
    val inode: Long,
) {
    init {
        require(device >= 0L)
        require(inode >= 0L)
    }
}

internal data class JournalFileIdentityV1(
    val device: Long,
    val inode: Long,
) {
    init {
        require(device >= 0L)
        require(inode >= 0L)
    }
}

/**
 * Exact app-private root captured from [Context.getNoBackupFilesDir]. There is no raw-string
 * production factory. Test code may exercise the private constructor through reflection because
 * hostile arbitrary same-process reflection is outside the documented trust boundary.
 */
internal class JournalFrozenNoBackupRootV1 private constructor(
    val path: JournalPathV1,
    val directoryIdentity: JournalDirectoryIdentityV1,
    val providerKey: JournalProviderKeyV1,
) {
    companion object {
        fun captureAndroid(context: Context): JournalFrozenNoBackupRootV1 {
            val applicationContext = context.applicationContext
            val canonicalPath = applicationContext.noBackupFilesDir.canonicalPath
            val path = JournalPathV1.canonicalAbsolute(canonicalPath)
            val fileSystem = AndroidJournalFileSystemV1()
            val before = fileSystem.lstat(path)
            require(before.type == JournalNodeTypeV1.DIRECTORY)
            val handle = fileSystem.openReadOnly(path)
            var primary: Exception? = null
            try {
                val opened = fileSystem.fstat(handle)
                require(
                    opened.type == JournalNodeTypeV1.DIRECTORY &&
                        opened.device == before.device &&
                        opened.inode == before.inode,
                )
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                try {
                    fileSystem.close(handle)
                } catch (failure: Exception) {
                    if (primary == null) throw failure
                }
            }
            return JournalFrozenNoBackupRootV1(
                path = path,
                directoryIdentity = JournalDirectoryIdentityV1(before.device, before.inode),
                providerKey = fileSystem.providerKey,
            )
        }
    }
}

/** Non-copyable fixed journal path bundle. Only [create] derives its five trusted paths. */
internal class JournalPathsV1 private constructor(
    val frozenNoBackupRoot: JournalFrozenNoBackupRootV1,
    val noBackupRoot: JournalPathV1,
    val recoveryRoot: JournalPathV1,
    val artifactRoot: JournalPathV1,
    val modeRoot: JournalPathV1,
    val base: JournalPathV1,
    val next: JournalPathV1,
    val runtimeArtifactId: RuntimeArtifactId,
    val mode: RecoveryJournalMode,
) {
    internal fun containsExact(path: JournalPathV1): Boolean =
        path === noBackupRoot ||
            path === recoveryRoot ||
            path === artifactRoot ||
            path === modeRoot ||
            path === base ||
            path === next

    internal fun parentOfExact(path: JournalPathV1): JournalPathV1? = when {
        path === noBackupRoot -> null
        path === recoveryRoot -> noBackupRoot
        path === artifactRoot -> recoveryRoot
        path === modeRoot -> artifactRoot
        path === base || path === next -> modeRoot
        else -> throw IllegalArgumentException("path is not a member of this frozen bundle")
    }

    companion object {
        fun create(
            frozenNoBackupRoot: JournalFrozenNoBackupRootV1,
            runtimeArtifactId: RuntimeArtifactId,
            mode: RecoveryJournalMode,
        ): JournalPathsV1 {
            val root = frozenNoBackupRoot.path
            val prefix = if (root.value == "/") "" else root.value
            val artifactHex = runtimeArtifactId.digest.toLowerHex()
            check(LOWER_HEX_64.matches(artifactHex))
            val modeName = when (mode) {
                RecoveryJournalMode.SOLO -> "solo"
                RecoveryJournalMode.DUAL -> "dual"
            }
            val recovery = JournalPathV1.canonicalAbsolute("$prefix/capability_recovery_v5")
            val artifact = JournalPathV1.canonicalAbsolute("${recovery.value}/$artifactHex")
            val modeRoot = JournalPathV1.canonicalAbsolute("${artifact.value}/$modeName")
            return JournalPathsV1(
                frozenNoBackupRoot = frozenNoBackupRoot,
                noBackupRoot = root,
                recoveryRoot = recovery,
                artifactRoot = artifact,
                modeRoot = modeRoot,
                base = JournalPathV1.canonicalAbsolute("${modeRoot.value}/journal.bin"),
                next = JournalPathV1.canonicalAbsolute("${modeRoot.value}/journal.bin.next"),
                runtimeArtifactId = runtimeArtifactId,
                mode = mode,
            )
        }

        private val LOWER_HEX_64 = Regex("^[0-9a-f]{64}$")
    }
}

internal enum class JournalNodeTypeV1 {
    DIRECTORY,
    REGULAR_FILE,
    SYMBOLIC_LINK,
    OTHER,
}

internal data class JournalStatV1(
    val device: Long,
    val inode: Long,
    val type: JournalNodeTypeV1,
    val size: Long,
)

internal interface JournalFileHandleV1

@JvmInline
internal value class JournalProviderKeyV1(val value: String)

internal data class JournalDirectoryEntryV1(
    val providerKey: JournalProviderKeyV1,
    val parent: JournalPathV1,
    val fileName: String,
)

internal interface JournalDirectoryIteratorV1 {
    fun hasNext(): Boolean

    fun next(): JournalDirectoryEntryV1
}

internal interface JournalDirectoryStreamV1 {
    /** The kernel calls this exactly once. */
    fun iterator(): JournalDirectoryIteratorV1

    fun close()
}

internal enum class JournalFileSystemFailureV1 {
    NOT_FOUND,
    ALREADY_EXISTS,
    INTERRUPTED,
    SECURITY,
    IO,
}

internal class JournalFileSystemExceptionV1(
    val failure: JournalFileSystemFailureV1,
) : Exception(failure.name)

/** Platform-neutral syscall boundary. Implementations must not repair or normalize paths. */
internal interface JournalFileSystemV1 {
    val providerKey: JournalProviderKeyV1

    fun lstat(path: JournalPathV1): JournalStatV1

    fun mkdir(path: JournalPathV1, mode: Int)

    fun openReadOnly(path: JournalPathV1): JournalFileHandleV1

    fun openExclusiveCreate(
        path: JournalPathV1,
        mode: Int,
    ): JournalFileHandleV1

    fun fstat(handle: JournalFileHandleV1): JournalStatV1

    fun read(
        handle: JournalFileHandleV1,
        destination: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int

    fun write(
        handle: JournalFileHandleV1,
        source: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int

    fun fsync(handle: JournalFileHandleV1)

    fun close(handle: JournalFileHandleV1)

    fun remove(path: JournalPathV1)

    fun rename(source: JournalPathV1, target: JournalPathV1)

    fun openDirectory(path: JournalPathV1): JournalDirectoryStreamV1
}

internal fun interface JournalMonotonicClockV1 {
    fun nowNs(): Long
}

/**
 * Process-local owner identity for one artifact/mode generation.
 *
 * There is deliberately no storage-side constructor or factory. The integration layer must bind
 * its single reducer/state-gate owner to this private constructor; until that wiring exists the
 * mutation kernel is inert rather than accepting a raw payload fallback.
 */
internal class JournalStorageAuthorityV1 private constructor(
    private val issuerIdentity: Any,
    val generation: Long,
    private val pathsIdentity: JournalPathsV1,
) {
    private val poisoned = AtomicBoolean(false)
    private val operationLock = Any()
    private var operationOwner: Any? = null
    private val frozenModeDirectory = AtomicReference<JournalDirectoryIdentityV1?>(null)

    init {
        require(generation >= 0L)
    }

    internal fun matches(
        issuerIdentity: Any,
        generation: Long,
        runtimeArtifactSha256: Sha256Digest,
        mode: RecoveryJournalMode,
        paths: JournalPathsV1,
    ): Boolean =
        !poisoned.get() &&
            this.issuerIdentity === issuerIdentity &&
            this.generation == generation &&
            pathsIdentity === paths &&
            pathsIdentity.frozenNoBackupRoot === paths.frozenNoBackupRoot &&
            pathsIdentity.runtimeArtifactId.digest == runtimeArtifactSha256 &&
            pathsIdentity.mode == mode

    internal fun matches(paths: JournalPathsV1): Boolean =
        !poisoned.get() &&
            pathsIdentity === paths &&
            pathsIdentity.frozenNoBackupRoot === paths.frozenNoBackupRoot

    internal fun matchesBoundOwner(
        authorityIdentity: JournalStorageAuthorityV1,
        generation: Long,
        paths: JournalPathsV1,
    ): Boolean =
        this === authorityIdentity &&
            this.generation == generation &&
            matches(paths)

    /**
     * Non-blocking single-owner admission. Contention is a contract violation: the contender is
     * rejected before filesystem access and the whole generation is poisoned. The admitted owner
     * remains the sole filesystem actor and releases its identity in `finally`.
     */
    internal fun admitOperation(paths: JournalPathsV1): Any? {
        if (!matches(paths)) return null
        val candidate = Any()
        return synchronized(operationLock) {
            if (!matches(paths)) return@synchronized null
            if (operationOwner != null) {
                poisoned.set(true)
                return@synchronized null
            }
            operationOwner = candidate
            candidate
        }
    }

    internal fun releaseOperation(owner: Any): Boolean = synchronized(operationLock) {
        if (operationOwner !== owner) {
            poisoned.set(true)
            return@synchronized false
        }
        operationOwner = null
        !poisoned.get()
    }

    internal fun ownsOperation(owner: Any): Boolean = synchronized(operationLock) {
        operationOwner === owner
    }

    internal fun bindModeDirectory(
        owner: Any,
        paths: JournalPathsV1,
        identity: JournalDirectoryIdentityV1,
    ): Boolean {
        if (!ownsOperation(owner) || !matches(paths)) return false
        val existing = frozenModeDirectory.get()
        if (existing != null) return existing == identity
        return frozenModeDirectory.compareAndSet(null, identity) || frozenModeDirectory.get() == identity
    }

    internal fun modeDirectoryIdentity(
        owner: Any,
        paths: JournalPathsV1,
    ): JournalDirectoryIdentityV1? =
        if (ownsOperation(owner) && matches(paths)) frozenModeDirectory.get() else null

    internal fun isUsable(): Boolean = !poisoned.get()

    internal fun poison() {
        poisoned.set(true)
    }
}

internal class JournalPresentDiskResultV1(
    val completeFileLength: Int,
    val completeFileSha256: Sha256Digest,
    completeFileBytes: ByteArray,
    val payload: RecoveryJournalPayloadV5,
    val fileIdentity: JournalFileIdentityV1? = null,
) : JournalDiskResultV1 {
    private val completeFileBytes = completeFileBytes.copyOf()

    init {
        require(completeFileLength == this.completeFileBytes.size)
    }

    fun copyCompleteFileBytes(): ByteArray = completeFileBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is JournalPresentDiskResultV1 &&
                completeFileLength == other.completeFileLength &&
                completeFileSha256 == other.completeFileSha256 &&
                completeFileBytes.contentEquals(other.completeFileBytes) &&
                payload == other.payload)

    override fun hashCode(): Int {
        var result = completeFileLength
        result = 31 * result + completeFileSha256.hashCode()
        result = 31 * result + completeFileBytes.contentHashCode()
        result = 31 * result + payload.hashCode()
        return result
    }

    override fun toString(): String =
        "Present(length=$completeFileLength,payload=$payload)"
}

internal sealed interface JournalDiskResultV1 {
    data object Absent : JournalDiskResultV1
}

/**
 * One-shot proof that a pending value came from the exact complete bytes returned by a strict
 * current-base read. Its constructor is private and its only issuer requires a live authority
 * operation admission plus a frozen on-disk file identity.
 */
internal class ValidatedCurrentPendingHandleV1 private constructor(
    private val authorityIdentity: JournalStorageAuthorityV1,
    internal val generation: Long,
    internal val pathsIdentity: JournalPathsV1,
    internal val runtimeArtifactSha256: Sha256Digest,
    internal val mode: RecoveryJournalMode,
    internal val rootIdentity: JournalFrozenNoBackupRootV1,
    internal val journalModeDirectoryIdentity: JournalDirectoryIdentityV1,
    internal val journalFileIdentity: JournalFileIdentityV1,
    internal val completeJournalLength: Int,
    internal val completeJournalSha256: Sha256Digest,
    private val pending: PendingStoreCommitV5,
) {
    private val claimed = AtomicBoolean(false)
    private val receiptSeal = Any()

    internal fun claimFor(authority: JournalStorageAuthorityV1): Boolean {
        if (
            authority !== authorityIdentity ||
            !authority.matchesBoundOwner(authorityIdentity, generation, pathsIdentity) ||
            pathsIdentity.runtimeArtifactId.digest != runtimeArtifactSha256 ||
            pathsIdentity.mode != mode
        ) {
            return false
        }
        return claimed.compareAndSet(false, true)
    }

    internal fun classify(
        authority: JournalStorageAuthorityV1,
        observation: StrictModeStoreObservationV1,
    ): PendingStoreStrictClassificationV1 {
        if (!claimFor(authority) || !observation.claimFor(authority, this)) {
            return PendingStoreStrictClassificationV1.AuthorityRejected
        }
        if (!isStructurallyValidPending(pending)) {
            authority.poison()
            return PendingStoreStrictClassificationV1.PendingRejected
        }
        val pendingBinding = computePendingBindingSha256(pending)
        val modeStoreDirectoryIdentity = observation.directoryIdentity()
        return when {
            observation.fileState == StrictModeStoreFileStateV1.PRESENT &&
                observation.completeFileLength == pending.intendedNewFileLength &&
                observation.completeFileSha256 == pending.intendedNewFileSha256 &&
                observation.embeddedRecordSha256 == pending.intendedNewRecordSha256 ->
                StrictModeStoreIdentityReceiptV1.issueIntendedNew(
                    authority,
                    ValidatedIntendedNewReceiptMaterialV1(
                        source = this,
                        seal = receiptSeal,
                        pendingBindingSha256 = pendingBinding,
                        modeStoreDirectoryIdentity = modeStoreDirectoryIdentity,
                    ),
                )
            pending.expectedOldFileState == ExpectedOldFileState.ABSENT &&
                observation.fileState == StrictModeStoreFileStateV1.ABSENT ->
                StrictModeStoreIdentityReceiptV1.issueExpectedOld(
                    authority,
                    ValidatedExpectedOldReceiptMaterialV1(
                        source = this,
                        seal = receiptSeal,
                        pendingBindingSha256 = pendingBinding,
                        modeStoreDirectoryIdentity = modeStoreDirectoryIdentity,
                    ),
                )
            pending.expectedOldFileState == ExpectedOldFileState.HASH_PRESENT &&
                observation.fileState == StrictModeStoreFileStateV1.PRESENT &&
                observation.completeFileLength == pending.expectedOldFileLength &&
                observation.completeFileSha256 == pending.expectedOldFileSha256 ->
                StrictModeStoreIdentityReceiptV1.issueExpectedOld(
                    authority,
                    ValidatedExpectedOldReceiptMaterialV1(
                        source = this,
                        seal = receiptSeal,
                        pendingBindingSha256 = pendingBinding,
                        modeStoreDirectoryIdentity = modeStoreDirectoryIdentity,
                    ),
                )
            else -> {
                authority.poison()
                PendingStoreStrictClassificationV1.ThirdIdentity
            }
        }
    }

    internal fun matchesReceiptSeal(candidate: Any): Boolean = candidate === receiptSeal

    internal fun isBoundTo(authority: JournalStorageAuthorityV1): Boolean =
        authority === authorityIdentity &&
            authority.matchesBoundOwner(authorityIdentity, generation, pathsIdentity)

    internal companion object {
        fun issueFromStrictRead(
            kernel: CheckedAtomicReplaceV1,
            authority: JournalStorageAuthorityV1,
            operationOwner: Any,
            paths: JournalPathsV1,
            result: JournalPresentDiskResultV1,
            modeDirectoryIdentity: JournalDirectoryIdentityV1,
        ): ValidatedCurrentPendingHandleV1? {
            if (!kernel.consumeStrictReadIssuanceProof(operationOwner, paths, result)) return null
            if (!authority.ownsOperation(operationOwner) || !authority.matches(paths)) return null
            val pending = result.payload.pendingStoreCommit ?: return null
            val fileIdentity = result.fileIdentity ?: return null
            val root = paths.frozenNoBackupRoot
            if (authority.modeDirectoryIdentity(operationOwner, paths) != modeDirectoryIdentity) return null
            return ValidatedCurrentPendingHandleV1(
                authorityIdentity = authority,
                generation = authority.generation,
                pathsIdentity = paths,
                runtimeArtifactSha256 = paths.runtimeArtifactId.digest,
                mode = paths.mode,
                rootIdentity = root,
                journalModeDirectoryIdentity = modeDirectoryIdentity,
                journalFileIdentity = fileIdentity,
                completeJournalLength = result.completeFileLength,
                completeJournalSha256 = result.completeFileSha256,
                pending = pending,
            )
        }

        private fun isStructurallyValidPending(value: PendingStoreCommitV5): Boolean {
            if (value.attemptEpoch == 0uL || value.retryUsed != (value.retryContextId != null)) return false
            val oldIsValid = when (value.expectedOldFileState) {
                ExpectedOldFileState.ABSENT ->
                    value.expectedOldFileLength == 0uL && value.expectedOldFileSha256 == null
                ExpectedOldFileState.HASH_PRESENT ->
                    value.expectedOldFileLength in
                        1uL..RecoveryJournalV5Codec.MAXIMUM_MODE_STORE_FILE_BYTES &&
                        value.expectedOldFileSha256 != null
            }
            return oldIsValid &&
                value.intendedNewFileLength in 1uL..RecoveryJournalV5Codec.MAXIMUM_MODE_STORE_FILE_BYTES
        }

        private fun computePendingBindingSha256(value: PendingStoreCommitV5): Sha256Digest {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("pending-store-commit-v5/storage-binding-v2\u0000".encodeToByteArray())
            digest.update(value.probeBaseScopeId.digest.copyBytes())
            digest.update(uint64(value.attemptEpoch))
            digest.update(value.capabilityResultId.digest.copyBytes())
            digest.update(value.selectedDelegate.wireValue.toByte())
            digest.update((if (value.retryUsed) 1 else 0).toByte())
            digest.update((if (value.retryContextId == null) 0 else 1).toByte())
            value.retryContextId?.let { digest.update(it.copyBytes()) }
            digest.update(value.expectedOldFileState.wireValue.toByte())
            digest.update(uint64(value.expectedOldFileLength))
            digest.update((if (value.expectedOldFileSha256 == null) 0 else 1).toByte())
            value.expectedOldFileSha256?.let { digest.update(it.copyBytes()) }
            digest.update(uint64(value.intendedNewFileLength))
            digest.update(value.intendedNewFileSha256.copyBytes())
            digest.update(value.intendedNewRecordSha256.copyBytes())
            return when (val result = Sha256Digest.fromBytes(digest.digest())) {
                is CapabilityDomainResult.Valid -> result.value
                is CapabilityDomainResult.Invalid ->
                    error("SHA-256 provider returned an invalid digest")
            }
        }

        private fun uint64(value: ULong): ByteArray =
            ByteArray(ULong.SIZE_BYTES) { index ->
                ((value shr ((ULong.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)) and 0xffuL).toByte()
            }
    }
}

internal class ValidatedExpectedOldReceiptMaterialV1 internal constructor(
    internal val source: ValidatedCurrentPendingHandleV1,
    private val seal: Any,
    internal val pendingBindingSha256: Sha256Digest,
    internal val modeStoreDirectoryIdentity: JournalDirectoryIdentityV1,
) {
    internal fun isAuthentic(authority: JournalStorageAuthorityV1): Boolean =
        source.matchesReceiptSeal(seal) &&
            authority.matchesBoundOwner(source = source)
}

internal class ValidatedIntendedNewReceiptMaterialV1 internal constructor(
    internal val source: ValidatedCurrentPendingHandleV1,
    private val seal: Any,
    internal val pendingBindingSha256: Sha256Digest,
    internal val modeStoreDirectoryIdentity: JournalDirectoryIdentityV1,
) {
    internal fun isAuthentic(authority: JournalStorageAuthorityV1): Boolean =
        source.matchesReceiptSeal(seal) &&
            authority.matchesBoundOwner(source = source)
}

private fun JournalStorageAuthorityV1.matchesBoundOwner(
    source: ValidatedCurrentPendingHandleV1,
): Boolean =
    source.isBoundTo(this) &&
        source.runtimeArtifactSha256 == source.pathsIdentity.runtimeArtifactId.digest &&
        source.mode == source.pathsIdentity.mode &&
        source.rootIdentity === source.pathsIdentity.frozenNoBackupRoot

/** Opaque reducer-issued transition; raw valid payloads cannot construct this plan. */
internal class JournalReplacePlanV1 private constructor(
    private val expected: JournalDiskResultV1,
    private val intended: RecoveryJournalPayloadV5,
    private val issuerIdentity: Any,
    private val generation: Long,
    private val runtimeArtifactSha256: Sha256Digest,
    private val mode: RecoveryJournalMode,
    private val pathsIdentity: JournalPathsV1,
    private val planIdentity: Any,
) {
    private val consumed = AtomicBoolean(false)

    internal fun claimFor(
        authority: JournalStorageAuthorityV1,
        token: JournalMutationTokenV1,
    ): JournalReplaceClaimV1? {
        if (!authority.matches(issuerIdentity, generation, runtimeArtifactSha256, mode, pathsIdentity) ||
            !token.isBoundTo(
                authority,
                issuerIdentity,
                generation,
                runtimeArtifactSha256,
                mode,
                pathsIdentity,
                planIdentity,
            )
        ) {
            return null
        }
        if (!consumed.compareAndSet(false, true) || !token.claimOnce()) return null
        return JournalReplaceClaimV1(expected, intended)
    }
}

internal class JournalReplaceClaimV1 internal constructor(
    val expected: JournalDiskResultV1,
    val intended: RecoveryJournalPayloadV5,
)

internal enum class JournalOperationFailureV1 {
    INVALID_CLOCK,
    DEADLINE_EXPIRED,
    OPERATION_CONTENTION,
    DIRECTORY_BOOTSTRAP_FAILED,
    DIRECTORY_ENUMERATION_FAILED,
    UNEXPECTED_SIBLING,
    PATH_OR_PROVIDER_MISMATCH,
    PATH_TYPE_INVALID,
    FILE_SIZE_INVALID,
    FILE_IDENTITY_CHANGED,
    FILE_READ_FAILED,
    READ_PROGRESS_INVALID,
    TRAILING_BYTES,
    FILE_WRITE_FAILED,
    WRITE_PROGRESS_INVALID,
    FILE_SYNC_FAILED,
    DIRECTORY_SYNC_FAILED,
    CLOSE_FAILED,
    CODEC_REJECTED,
    EXPECTED_BASE_MISMATCH,
    NEXT_ALREADY_EXISTS,
    NEXT_CLEANUP_FAILED,
    RENAME_FAILED,
    POST_RENAME_VERIFICATION_FAILED,
    TOKEN_ALREADY_RESOLVED,
    FILESYSTEM_FAILURE,
}

internal sealed interface JournalReadOutcomeV1 {
    data class Completed(
        val diskResult: JournalDiskResultV1,
        val validatedCurrentPending: ValidatedCurrentPendingHandleV1? = null,
    ) : JournalReadOutcomeV1

    data class Failed(val failure: JournalOperationFailureV1) : JournalReadOutcomeV1

    data object Expired : JournalReadOutcomeV1

    data object StateInert : JournalReadOutcomeV1
}

internal sealed interface JournalMutationOutcomeV1 {
    /** Diagnostic commit result only. It is not a native-work or reducer-transition authority. */
    data class Completed(val diskResult: JournalPresentDiskResultV1) : JournalMutationOutcomeV1

    /** A finite operation failure; the caller must not retry with another owner in this process. */
    data class NotCommitted(val failure: JournalOperationFailureV1) : JournalMutationOutcomeV1

    data class ExpiredPreCommit(val cleanupProven: Boolean) : JournalMutationOutcomeV1

    data class OutcomeUnknown(val failure: JournalOperationFailureV1) : JournalMutationOutcomeV1

    data object StateInert : JournalMutationOutcomeV1
}

/** Trusted state-gate deadline. No caller-controlled production mint exists in this slice. */
internal class JournalOperationDeadlineV1 private constructor(
    val startedAtNs: Long,
    val deadlineNs: Long,
) {
    fun assess(nowNs: Long): JournalClockAssessmentV1 {
        if (startedAtNs < 0L || nowNs < startedAtNs) return JournalClockAssessmentV1.INVALID
        val expectedDeadline = try {
            Math.addExact(startedAtNs, JOURNAL_OPERATION_DEADLINE_NS)
        } catch (_: ArithmeticException) {
            return JournalClockAssessmentV1.INVALID
        }
        if (deadlineNs != expectedDeadline) return JournalClockAssessmentV1.INVALID
        return if (nowNs > deadlineNs) JournalClockAssessmentV1.EXPIRED else JournalClockAssessmentV1.ON_TIME
    }

    fun isOnTime(nowNs: Long): Boolean = assess(nowNs) == JournalClockAssessmentV1.ON_TIME

    fun isExpired(nowNs: Long): Boolean = assess(nowNs) == JournalClockAssessmentV1.EXPIRED
}

internal enum class JournalClockAssessmentV1 {
    ON_TIME,
    EXPIRED,
    INVALID,
}

internal data class JournalTimedTransitionV1(
    val assessment: JournalClockAssessmentV1,
    val transitioned: Boolean,
)

internal enum class JournalReadTokenStateV1 {
    OPEN,
    COMPLETED,
    EXPIRED,
    INVALID_CLOCK,
}

internal class JournalReadTokenV1 private constructor(
    val deadline: JournalOperationDeadlineV1,
    private val issuerIdentity: Any,
    private val generation: Long,
    private val runtimeArtifactSha256: Sha256Digest,
    private val mode: RecoveryJournalMode,
    private val pathsIdentity: JournalPathsV1,
) {
    private val state = AtomicReference(JournalReadTokenStateV1.OPEN)
    private val claimed = AtomicBoolean(false)
    private val lastObservedNs = AtomicLong(deadline.startedAtNs)
    private val observationLock = Any()

    fun snapshot(): JournalReadTokenStateV1 = state.get()

    internal fun claimFor(authority: JournalStorageAuthorityV1): Boolean =
        authority.matches(issuerIdentity, generation, runtimeArtifactSha256, mode, pathsIdentity) &&
            claimed.compareAndSet(false, true)

    internal fun observeAt(nowNs: Long): JournalClockAssessmentV1 = synchronized(observationLock) {
        val assessment = observeMonotonicDeadline(lastObservedNs, deadline, nowNs)
        when (assessment) {
            JournalClockAssessmentV1.ON_TIME -> Unit
            JournalClockAssessmentV1.EXPIRED ->
                state.compareAndSet(JournalReadTokenStateV1.OPEN, JournalReadTokenStateV1.EXPIRED)
            JournalClockAssessmentV1.INVALID ->
                state.compareAndSet(JournalReadTokenStateV1.OPEN, JournalReadTokenStateV1.INVALID_CLOCK)
        }
        assessment
    }

    fun expireAt(nowNs: Long): JournalReadTokenStateV1 {
        observeAt(nowNs)
        return state.get()
    }

    internal fun admitAtWithAssessment(nowNs: Long): JournalTimedTransitionV1 {
        val assessment = observeAt(nowNs)
        return JournalTimedTransitionV1(
            assessment = assessment,
            transitioned = assessment == JournalClockAssessmentV1.ON_TIME &&
                state.compareAndSet(JournalReadTokenStateV1.OPEN, JournalReadTokenStateV1.COMPLETED),
        )
    }

    fun admitAt(nowNs: Long): Boolean = admitAtWithAssessment(nowNs).transitioned
}

internal enum class JournalCommitStateV1 {
    PRE_COMMIT,
    COMMIT_AUTHORIZED,
    COMPLETED,
    NOT_COMMITTED,
    EXPIRED_PRE_COMMIT,
    EXPIRED_AFTER_AUTHORIZATION,
    FAILED_AFTER_AUTHORIZATION,
    INVALID_CLOCK_PRE_COMMIT,
    INVALID_CLOCK_AFTER_AUTHORIZATION,
}

internal class JournalMutationTokenV1 private constructor(
    val deadline: JournalOperationDeadlineV1,
    private val issuerIdentity: Any,
    private val generation: Long,
    private val runtimeArtifactSha256: Sha256Digest,
    private val mode: RecoveryJournalMode,
    private val pathsIdentity: JournalPathsV1,
    private val planIdentity: Any,
) {
    private val state = AtomicReference(JournalCommitStateV1.PRE_COMMIT)
    private val claimed = AtomicBoolean(false)
    private val lastObservedNs = AtomicLong(deadline.startedAtNs)
    private val observationLock = Any()

    fun snapshot(): JournalCommitStateV1 = state.get()

    internal fun isBoundTo(
        authority: JournalStorageAuthorityV1,
        issuerIdentity: Any,
        generation: Long,
        runtimeArtifactSha256: Sha256Digest,
        mode: RecoveryJournalMode,
        pathsIdentity: JournalPathsV1,
        planIdentity: Any,
    ): Boolean =
        authority.matches(issuerIdentity, generation, runtimeArtifactSha256, mode, pathsIdentity) &&
            this.issuerIdentity === issuerIdentity &&
            this.generation == generation &&
            this.runtimeArtifactSha256 == runtimeArtifactSha256 &&
            this.mode == mode &&
            this.pathsIdentity === pathsIdentity &&
            this.planIdentity === planIdentity

    internal fun claimOnce(): Boolean = claimed.compareAndSet(false, true)

    internal fun observeAt(nowNs: Long): JournalClockAssessmentV1 = synchronized(observationLock) {
        val assessment = observeMonotonicDeadline(lastObservedNs, deadline, nowNs)
        if (assessment == JournalClockAssessmentV1.ON_TIME) return@synchronized assessment
        var resolved = false
        while (!resolved) {
            when (val current = state.get()) {
                JournalCommitStateV1.PRE_COMMIT ->
                    resolved = state.compareAndSet(
                        current,
                        if (assessment == JournalClockAssessmentV1.INVALID) {
                            JournalCommitStateV1.INVALID_CLOCK_PRE_COMMIT
                        } else {
                            JournalCommitStateV1.EXPIRED_PRE_COMMIT
                        },
                    )
                JournalCommitStateV1.COMMIT_AUTHORIZED ->
                    resolved = state.compareAndSet(
                        current,
                        if (assessment == JournalClockAssessmentV1.INVALID) {
                            JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION
                        } else {
                            JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION
                        },
                    )
                else -> resolved = true
            }
        }
        assessment
    }

    fun expireAt(nowNs: Long): JournalCommitStateV1 {
        observeAt(nowNs)
        return state.get()
    }

    internal fun authorizeAtWithAssessment(nowNs: Long): JournalTimedTransitionV1 {
        val assessment = observeAt(nowNs)
        return JournalTimedTransitionV1(
            assessment = assessment,
            transitioned = assessment == JournalClockAssessmentV1.ON_TIME &&
                state.compareAndSet(JournalCommitStateV1.PRE_COMMIT, JournalCommitStateV1.COMMIT_AUTHORIZED),
        )
    }

    fun authorizeAt(nowNs: Long): Boolean = authorizeAtWithAssessment(nowNs).transitioned

    internal fun completeAtWithAssessment(nowNs: Long): JournalTimedTransitionV1 {
        val assessment = observeAt(nowNs)
        return JournalTimedTransitionV1(
            assessment = assessment,
            transitioned = assessment == JournalClockAssessmentV1.ON_TIME &&
                state.compareAndSet(JournalCommitStateV1.COMMIT_AUTHORIZED, JournalCommitStateV1.COMPLETED),
        )
    }

    fun completeAt(nowNs: Long): Boolean = completeAtWithAssessment(nowNs).transitioned

    fun markNotCommitted(): Boolean {
        while (true) {
            when (val current = state.get()) {
                JournalCommitStateV1.PRE_COMMIT,
                JournalCommitStateV1.COMMIT_AUTHORIZED,
                -> if (state.compareAndSet(current, JournalCommitStateV1.NOT_COMMITTED)) return true
                else -> return current == JournalCommitStateV1.NOT_COMMITTED
            }
        }
    }

    fun failAfterAuthorization(): JournalCommitStateV1 {
        state.compareAndSet(
            JournalCommitStateV1.COMMIT_AUTHORIZED,
            JournalCommitStateV1.FAILED_AFTER_AUTHORIZATION,
        )
        return state.get()
    }
}

private fun observeMonotonicDeadline(
    lastObservedNs: AtomicLong,
    deadline: JournalOperationDeadlineV1,
    nowNs: Long,
): JournalClockAssessmentV1 {
    val assessment = deadline.assess(nowNs)
    if (assessment == JournalClockAssessmentV1.INVALID) return assessment
    while (true) {
        val previous = lastObservedNs.get()
        if (nowNs < previous) return JournalClockAssessmentV1.INVALID
        if (lastObservedNs.compareAndSet(previous, nowNs)) return assessment
    }
}
