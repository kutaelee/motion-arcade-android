package com.motionarcade.app.capability.persistence

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.recovery.RecoveryJournalPayloadV5
import com.motionarcade.vision.capability.recovery.RecoveryJournalV5Codec
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * CheckedAtomicReplaceV1 kernel. Authority admission serializes all operations for this exact
 * frozen root/path bundle. This class never creates a deadline and never promotes `.next`.
 */
internal class CheckedAtomicReplaceV1 private constructor(
    private val paths: JournalPathsV1,
    private val fileSystem: JournalFileSystemV1,
    private val clock: JournalMonotonicClockV1,
    private val authority: JournalStorageAuthorityV1,
) {
    private val activeOperationOwner = ThreadLocal<Any>()
    private val strictReadIssuanceCandidate = ThreadLocal<JournalPresentDiskResultV1>()
    private val frozenDirectories = IdentityHashMap<JournalPathV1, JournalDirectoryIdentityV1>()

    init {
        require(authority.matches(paths))
        require(fileSystem.providerKey == paths.frozenNoBackupRoot.providerKey)
        frozenDirectories[paths.noBackupRoot] = paths.frozenNoBackupRoot.directoryIdentity
    }

    fun read(token: JournalReadTokenV1): JournalReadOutcomeV1 {
        if (!authority.isUsable() || !token.claimFor(authority)) {
            return JournalReadOutcomeV1.StateInert
        }
        val operationOwner = authority.admitOperation(paths) ?: return JournalReadOutcomeV1.StateInert
        activeOperationOwner.set(operationOwner)
        var outcome: JournalReadOutcomeV1? = null
        var uncontended = false
        try {
            outcome = readAdmitted(token, operationOwner)
        } finally {
            activeOperationOwner.remove()
            uncontended = authority.releaseOperation(operationOwner)
        }
        return if (!uncontended && outcome is JournalReadOutcomeV1.Completed) {
            JournalReadOutcomeV1.Failed(JournalOperationFailureV1.OPERATION_CONTENTION)
        } else {
            checkNotNull(outcome)
        }
    }

    private fun readAdmitted(
        token: JournalReadTokenV1,
        operationOwner: Any,
    ): JournalReadOutcomeV1 {
        when (token.snapshot()) {
            JournalReadTokenStateV1.EXPIRED -> return poisonedReadExpiry()
            JournalReadTokenStateV1.INVALID_CLOCK -> return poisonedInvalidReadClock()
            JournalReadTokenStateV1.COMPLETED -> return JournalReadOutcomeV1.StateInert
            JournalReadTokenStateV1.OPEN -> Unit
        }
        when (captureTime(token)) {
            CapturedTimeV1.OnTime -> Unit
            CapturedTimeV1.Expired -> return poisonedReadExpiry()
            CapturedTimeV1.Invalid -> return poisonedInvalidReadClock()
        }

        val execution = try {
            bootstrap()
            val diskResult = recoverNextAndReadBase()
            val pendingHandle = if (diskResult is JournalPresentDiskResultV1) {
                val modeIdentity = authority.modeDirectoryIdentity(operationOwner, paths)
                    ?: throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
                strictReadIssuanceCandidate.set(diskResult)
                try {
                    ValidatedCurrentPendingHandleV1.issueFromStrictRead(
                        kernel = this,
                        authority = authority,
                        operationOwner = operationOwner,
                        paths = paths,
                        result = diskResult,
                        modeDirectoryIdentity = modeIdentity,
                    )
                } finally {
                    strictReadIssuanceCandidate.remove()
                }
            } else {
                null
            }
            JournalReadOutcomeV1.Completed(diskResult, pendingHandle)
        } catch (failure: KernelFailure) {
            JournalReadOutcomeV1.Failed(failure.reason)
        } catch (_: Exception) {
            JournalReadOutcomeV1.Failed(JournalOperationFailureV1.FILESYSTEM_FAILURE)
        }

        val completion = captureTransition(token::admitAtWithAssessment)
        val outcome = when (completion.assessment) {
            JournalClockAssessmentV1.ON_TIME -> if (completion.transitioned) {
                execution
            } else {
                when (token.snapshot()) {
                    JournalReadTokenStateV1.EXPIRED -> JournalReadOutcomeV1.Expired
                    JournalReadTokenStateV1.INVALID_CLOCK ->
                        JournalReadOutcomeV1.Failed(JournalOperationFailureV1.INVALID_CLOCK)
                    else -> JournalReadOutcomeV1.StateInert
                }
            }
            JournalClockAssessmentV1.EXPIRED -> JournalReadOutcomeV1.Expired
            JournalClockAssessmentV1.INVALID ->
                JournalReadOutcomeV1.Failed(JournalOperationFailureV1.INVALID_CLOCK)
        }
        if (outcome !is JournalReadOutcomeV1.Completed) authority.poison()
        return outcome
    }

    /**
     * Consumes the exact same-thread result identity staged only between a strict base read and
     * handle issuance. A caller-created or previously returned disk result cannot satisfy this
     * proof even if all of its value fields are byte-for-byte equal.
     */
    internal fun consumeStrictReadIssuanceProof(
        operationOwner: Any,
        candidatePaths: JournalPathsV1,
        result: JournalPresentDiskResultV1,
    ): Boolean {
        if (
            candidatePaths !== paths ||
            activeOperationOwner.get() !== operationOwner ||
            !authority.ownsOperation(operationOwner) ||
            strictReadIssuanceCandidate.get() !== result
        ) {
            return false
        }
        strictReadIssuanceCandidate.remove()
        return true
    }

    fun replace(
        token: JournalMutationTokenV1,
        plan: JournalReplacePlanV1,
    ): JournalMutationOutcomeV1 {
        if (!authority.isUsable()) return JournalMutationOutcomeV1.StateInert
        val claim = plan.claimFor(authority, token) ?: return JournalMutationOutcomeV1.StateInert
        val operationOwner = authority.admitOperation(paths) ?: return JournalMutationOutcomeV1.StateInert
        activeOperationOwner.set(operationOwner)
        var outcome: JournalMutationOutcomeV1? = null
        var uncontended = false
        try {
            outcome = replaceAdmitted(token, claim)
        } finally {
            activeOperationOwner.remove()
            uncontended = authority.releaseOperation(operationOwner)
        }
        return if (!uncontended && outcome is JournalMutationOutcomeV1.Completed) {
            JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.OPERATION_CONTENTION)
        } else {
            checkNotNull(outcome)
        }
    }

    private fun replaceAdmitted(
        token: JournalMutationTokenV1,
        claim: JournalReplaceClaimV1,
    ): JournalMutationOutcomeV1 {
        when (token.snapshot()) {
            JournalCommitStateV1.PRE_COMMIT -> Unit
            JournalCommitStateV1.EXPIRED_PRE_COMMIT -> {
                authority.poison()
                return JournalMutationOutcomeV1.ExpiredPreCommit(cleanupProven = false)
            }
            JournalCommitStateV1.COMMIT_AUTHORIZED,
            JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION,
            JournalCommitStateV1.FAILED_AFTER_AUTHORIZATION,
            JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION,
            -> {
                authority.poison()
                return JournalMutationOutcomeV1.StateInert
            }
            JournalCommitStateV1.COMPLETED,
            JournalCommitStateV1.NOT_COMMITTED,
            -> return JournalMutationOutcomeV1.StateInert
            JournalCommitStateV1.INVALID_CLOCK_PRE_COMMIT -> {
                authority.poison()
                return JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.INVALID_CLOCK)
            }
        }
        val before = captureTime(token)
        if (before != CapturedTimeV1.OnTime) return expiredMutationOutcome(token, true)
        if (token.snapshot() != JournalCommitStateV1.PRE_COMMIT) {
            return expiredMutationOutcome(token, false)
        }

        var nextCreateAttempted = false
        val intendedBytes: ByteArray
        val intendedDisk: JournalPresentDiskResultV1
        try {
            bootstrap()
            val current = recoverNextAndReadBase()
            if (!sameDiskBytes(current, claim.expected)) {
                throw KernelFailure(JournalOperationFailureV1.EXPECTED_BASE_MISMATCH)
            }
            if (claim.intended.recoveryBuildId != paths.runtimeArtifactId || claim.intended.mode != paths.mode) {
                throw KernelFailure(JournalOperationFailureV1.CODEC_REJECTED)
            }
            intendedBytes = validEncodedFile(claim.intended)
            intendedDisk = presentFromBytes(intendedBytes)
            requirePathAbsent(paths.next, JournalOperationFailureV1.NEXT_ALREADY_EXISTS)
            // O_CREAT|O_EXCL may create the inode and then report a failure. Mark the attempt
            // before crossing the syscall boundary so failure handling must inspect disk.
            nextCreateAttempted = true
            writeNext(intendedBytes)

            val verifiedNext = strictReadFile(paths.next)
            if (!sameDiskBytes(verifiedNext, intendedDisk)) {
                throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            }
            val baseImmediatelyBeforeCommit = strictReadBase()
            if (!sameDiskBytes(baseImmediatelyBeforeCommit, claim.expected)) {
                throw KernelFailure(JournalOperationFailureV1.EXPECTED_BASE_MISMATCH)
            }
            prepareRenameChecked(paths.next, paths.base)
        } catch (failure: KernelFailure) {
            return finishPreCommitFailure(token, claim.expected, nextCreateAttempted, failure.reason)
        } catch (_: Exception) {
            return finishPreCommitFailure(
                token,
                claim.expected,
                nextCreateAttempted,
                JournalOperationFailureV1.FILESYSTEM_FAILURE,
            )
        }

        val authorization = captureTransition(token::authorizeAtWithAssessment)
        if (authorization.assessment != JournalClockAssessmentV1.ON_TIME || !authorization.transitioned) {
            val cleanup = cleanupNextPreserving(claim.expected)
            return expiredMutationOutcome(token, cleanup)
        }
        try {
            renamePrepared(paths.next, paths.base)
        } catch (_: JournalFileSystemExceptionV1) {
            return settleAfterAuthorization(
                token = token,
                expected = claim.expected,
                intended = intendedDisk,
                failureIfUnknown = JournalOperationFailureV1.RENAME_FAILED,
            )
        } catch (_: Exception) {
            return settleAfterAuthorization(
                token = token,
                expected = claim.expected,
                intended = intendedDisk,
                failureIfUnknown = JournalOperationFailureV1.RENAME_FAILED,
            )
        }

        return settleAfterAuthorization(
            token = token,
            expected = claim.expected,
            intended = intendedDisk,
            failureIfUnknown = JournalOperationFailureV1.POST_RENAME_VERIFICATION_FAILED,
        )
    }

    private fun bootstrap() {
        validateDirectory(paths.noBackupRoot, sync = false)
        var parent = paths.noBackupRoot
        for (child in listOf(paths.recoveryRoot, paths.artifactRoot, paths.modeRoot)) {
            validateDirectory(parent, sync = false)
            ensureDirectory(child)
            validateDirectory(child, sync = true)
            validateDirectory(parent, sync = true)
            parent = child
        }
    }

    private fun ensureDirectory(path: JournalPathV1) {
        if (lstatOrNull(path) == null) {
            try {
                mkdirChecked(path, JOURNAL_DIRECTORY_MODE)
            } catch (failure: JournalFileSystemExceptionV1) {
                if (failure.failure != JournalFileSystemFailureV1.ALREADY_EXISTS) {
                    throw KernelFailure(JournalOperationFailureV1.DIRECTORY_BOOTSTRAP_FAILED)
                }
            }
        }
        val stat = lstatOrNull(path)
            ?: throw KernelFailure(JournalOperationFailureV1.DIRECTORY_BOOTSTRAP_FAILED)
        if (stat.type != JournalNodeTypeV1.DIRECTORY) {
            throw KernelFailure(JournalOperationFailureV1.PATH_TYPE_INVALID)
        }
        freezeOrVerifyDirectory(path, stat)
    }

    private fun freezeOrVerifyDirectory(
        path: JournalPathV1,
        stat: JournalStatV1,
    ) {
        if (stat.type != JournalNodeTypeV1.DIRECTORY) {
            throw KernelFailure(JournalOperationFailureV1.PATH_TYPE_INVALID)
        }
        val identity = JournalDirectoryIdentityV1(stat.device, stat.inode)
        val existing = frozenDirectories[path]
        if (existing != null && existing != identity) {
            throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        }
        if (existing == null) frozenDirectories[path] = identity
        if (path === paths.modeRoot) {
            val owner = requireActiveOperationOwner()
            if (!authority.bindModeDirectory(owner, paths, identity)) {
                throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            }
        }
    }

    private fun validateDirectory(
        path: JournalPathV1,
        sync: Boolean,
    ) {
        val before = lstatOrNull(path)
            ?: throw KernelFailure(JournalOperationFailureV1.DIRECTORY_BOOTSTRAP_FAILED)
        val expected = frozenDirectories[path]
            ?: throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        if (before.type != JournalNodeTypeV1.DIRECTORY) {
            throw KernelFailure(JournalOperationFailureV1.PATH_TYPE_INVALID)
        }
        if (before.device != expected.device || before.inode != expected.inode) {
            throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        }
        withReadHandle(
            path = path,
            openFailure = JournalOperationFailureV1.DIRECTORY_BOOTSTRAP_FAILED,
        ) { handle ->
            val opened = fstat(handle, JournalOperationFailureV1.DIRECTORY_BOOTSTRAP_FAILED)
            if (opened.type != JournalNodeTypeV1.DIRECTORY || !sameNode(before, opened)) {
                throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            }
            if (sync) {
                try {
                    fileSystem.fsync(handle)
                } catch (_: JournalFileSystemExceptionV1) {
                    throw KernelFailure(JournalOperationFailureV1.DIRECTORY_SYNC_FAILED)
                }
            }
        }
    }

    private fun recoverNextAndReadBase(): JournalDiskResultV1 {
        val names = enumerateSelectedDirectory()
        val baseStat = lstatOrNull(paths.base)
        val nextStat = lstatOrNull(paths.next)
        if ((baseStat != null) != names.contains(BASE_NAME) ||
            (nextStat != null) != names.contains(NEXT_NAME)
        ) {
            throw KernelFailure(JournalOperationFailureV1.DIRECTORY_ENUMERATION_FAILED)
        }

        if (nextStat == null) return strictReadBase()
        requireCleanableNext(nextStat)
        val beforeCleanup = strictReadBase()
        removeNext()
        validateDirectory(paths.modeRoot, sync = true)
        requirePathAbsent(paths.next, JournalOperationFailureV1.NEXT_CLEANUP_FAILED)
        val afterCleanup = strictReadBase()
        if (!sameDiskBytes(beforeCleanup, afterCleanup)) {
            throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        }
        return afterCleanup
    }

    private fun enumerateSelectedDirectory(): Set<String> {
        val stream = try {
            openDirectoryChecked(paths.modeRoot)
        } catch (_: JournalFileSystemExceptionV1) {
            throw KernelFailure(JournalOperationFailureV1.DIRECTORY_ENUMERATION_FAILED)
        }
        var primary: Exception? = null
        try {
            val iterator = stream.iterator()
            val names = LinkedHashSet<String>(2)
            while (iterator.hasNext()) {
                if (names.size == 2) {
                    throw KernelFailure(JournalOperationFailureV1.UNEXPECTED_SIBLING)
                }
                val entry = iterator.next()
                if (entry.providerKey != fileSystem.providerKey || entry.parent !== paths.modeRoot) {
                    throw KernelFailure(JournalOperationFailureV1.PATH_OR_PROVIDER_MISMATCH)
                }
                if (entry.fileName != BASE_NAME && entry.fileName != NEXT_NAME) {
                    throw KernelFailure(JournalOperationFailureV1.UNEXPECTED_SIBLING)
                }
                if (!names.add(entry.fileName)) {
                    throw KernelFailure(JournalOperationFailureV1.UNEXPECTED_SIBLING)
                }
            }
            return names
        } catch (failure: KernelFailure) {
            primary = failure
            throw failure
        } catch (failure: Exception) {
            primary = failure
            throw KernelFailure(JournalOperationFailureV1.DIRECTORY_ENUMERATION_FAILED)
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
                if (primary == null) throw KernelFailure(JournalOperationFailureV1.CLOSE_FAILED)
            }
        }
    }

    private fun strictReadBase(): JournalDiskResultV1 {
        val stat = lstatOrNull(paths.base) ?: return JournalDiskResultV1.Absent
        return strictReadFile(paths.base, stat)
    }

    private fun strictReadFile(path: JournalPathV1): JournalPresentDiskResultV1 {
        val stat = lstatOrNull(path)
            ?: throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        return strictReadFile(path, stat)
    }

    private fun strictReadFile(
        path: JournalPathV1,
        lstat: JournalStatV1,
    ): JournalPresentDiskResultV1 {
        if (lstat.type != JournalNodeTypeV1.REGULAR_FILE) {
            throw KernelFailure(JournalOperationFailureV1.PATH_TYPE_INVALID)
        }
        val (bytes, fileIdentity) = withReadHandle(
            path = path,
            openFailure = JournalOperationFailureV1.FILE_READ_FAILED,
        ) { handle ->
            val opened = fstat(handle, JournalOperationFailureV1.FILE_READ_FAILED)
            if (opened.type != JournalNodeTypeV1.REGULAR_FILE || !sameNode(lstat, opened)) {
                throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            }
            if (opened.size !in 1L..RecoveryJournalV5Codec.MAXIMUM_FILE_BYTES.toLong()) {
                throw KernelFailure(JournalOperationFailureV1.FILE_SIZE_INVALID)
            }
            val frozenLength = opened.size.toInt()
            val snapshot = ByteArray(frozenLength)
            var offset = 0
            while (offset < frozenLength) {
                val read = try {
                    fileSystem.read(handle, snapshot, offset, frozenLength - offset)
                } catch (_: JournalFileSystemExceptionV1) {
                    throw KernelFailure(JournalOperationFailureV1.FILE_READ_FAILED)
                }
                if (read <= 0 || read > frozenLength - offset) {
                    throw KernelFailure(JournalOperationFailureV1.READ_PROGRESS_INVALID)
                }
                offset += read
            }
            val eofProbe = try {
                fileSystem.read(handle, ByteArray(1), 0, 1)
            } catch (_: JournalFileSystemExceptionV1) {
                throw KernelFailure(JournalOperationFailureV1.FILE_READ_FAILED)
            }
            if (eofProbe != 0) throw KernelFailure(JournalOperationFailureV1.TRAILING_BYTES)
            val final = fstat(handle, JournalOperationFailureV1.FILE_READ_FAILED)
            if (final.type != JournalNodeTypeV1.REGULAR_FILE ||
                !sameNode(opened, final) ||
                final.size != opened.size
            ) {
                throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            }
            snapshot to JournalFileIdentityV1(opened.device, opened.inode)
        }
        return presentFromBytes(bytes, fileIdentity)
    }

    private fun presentFromBytes(
        bytes: ByteArray,
        fileIdentity: JournalFileIdentityV1? = null,
    ): JournalPresentDiskResultV1 {
        val payload = when (
            val decoded = RecoveryJournalV5Codec.decodeFile(
                bytes = bytes,
                expectedRecoveryBuildId = paths.runtimeArtifactId,
                expectedMode = paths.mode,
            )
        ) {
            is CapabilityDomainResult.Valid -> decoded.value
            is CapabilityDomainResult.Invalid ->
                throw KernelFailure(JournalOperationFailureV1.CODEC_REJECTED)
        }
        return JournalPresentDiskResultV1(
            completeFileLength = bytes.size,
            completeFileSha256 = sha256(bytes),
            completeFileBytes = bytes,
            payload = payload,
            fileIdentity = fileIdentity,
        )
    }

    private fun validEncodedFile(intended: RecoveryJournalPayloadV5): ByteArray =
        when (val encoded = RecoveryJournalV5Codec.encodeFile(intended)) {
            is CapabilityDomainResult.Valid -> encoded.value.copyOf()
            is CapabilityDomainResult.Invalid ->
                throw KernelFailure(JournalOperationFailureV1.CODEC_REJECTED)
        }

    private fun writeNext(bytes: ByteArray) {
        val handle = try {
            openExclusiveCreateChecked(paths.next, JOURNAL_FILE_MODE)
        } catch (failure: JournalFileSystemExceptionV1) {
            if (failure.failure == JournalFileSystemFailureV1.ALREADY_EXISTS) {
                throw KernelFailure(JournalOperationFailureV1.NEXT_ALREADY_EXISTS)
            }
            throw KernelFailure(JournalOperationFailureV1.FILE_WRITE_FAILED)
        }
        var primary: Exception? = null
        try {
            val created = fstat(handle, JournalOperationFailureV1.FILE_WRITE_FAILED)
            if (created.type != JournalNodeTypeV1.REGULAR_FILE || created.size != 0L) {
                throw KernelFailure(JournalOperationFailureV1.PATH_TYPE_INVALID)
            }
            var offset = 0
            while (offset < bytes.size) {
                val written = try {
                    fileSystem.write(handle, bytes, offset, bytes.size - offset)
                } catch (_: JournalFileSystemExceptionV1) {
                    throw KernelFailure(JournalOperationFailureV1.FILE_WRITE_FAILED)
                }
                if (written <= 0 || written > bytes.size - offset) {
                    throw KernelFailure(JournalOperationFailureV1.WRITE_PROGRESS_INVALID)
                }
                offset += written
            }
            try {
                fileSystem.fsync(handle)
            } catch (_: JournalFileSystemExceptionV1) {
                throw KernelFailure(JournalOperationFailureV1.FILE_SYNC_FAILED)
            }
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            try {
                fileSystem.close(handle)
            } catch (_: Exception) {
                if (primary == null) throw KernelFailure(JournalOperationFailureV1.CLOSE_FAILED)
            }
        }
    }

    private fun finishPreCommitFailure(
        token: JournalMutationTokenV1,
        expected: JournalDiskResultV1,
        nextCreateAttempted: Boolean,
        failure: JournalOperationFailureV1,
    ): JournalMutationOutcomeV1 {
        val cleanupProven = !nextCreateAttempted || cleanupNextPreserving(expected)
        when (captureTime(token)) {
            CapturedTimeV1.Expired -> {
                authority.poison()
                return JournalMutationOutcomeV1.ExpiredPreCommit(cleanupProven)
            }
            CapturedTimeV1.Invalid -> {
                authority.poison()
                return if (cleanupProven) {
                    JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.INVALID_CLOCK)
                } else {
                    JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.INVALID_CLOCK)
                }
            }
            CapturedTimeV1.OnTime -> Unit
        }
        if (!cleanupProven) {
            token.expireAt(Long.MAX_VALUE)
            authority.poison()
            return JournalMutationOutcomeV1.ExpiredPreCommit(cleanupProven = false)
        }
        token.markNotCommitted()
        authority.poison()
        return JournalMutationOutcomeV1.NotCommitted(failure)
    }

    private fun cleanupNextPreserving(expected: JournalDiskResultV1): Boolean =
        try {
            val before = strictReadBase()
            if (!sameDiskBytes(before, expected)) return false
            val next = lstatOrNull(paths.next)
            if (next != null) {
                requireCleanableNext(next)
                removeNext()
            }
            validateDirectory(paths.modeRoot, sync = true)
            requirePathAbsent(paths.next, JournalOperationFailureV1.NEXT_CLEANUP_FAILED)
            sameDiskBytes(strictReadBase(), expected)
        } catch (_: Exception) {
            false
        }

    private fun settleAfterAuthorization(
        token: JournalMutationTokenV1,
        expected: JournalDiskResultV1,
        intended: JournalPresentDiskResultV1,
        failureIfUnknown: JournalOperationFailureV1,
    ): JournalMutationOutcomeV1 {
        val disk = try {
            validateDirectory(paths.modeRoot, sync = true)
            val next = lstatOrNull(paths.next)
            val base = strictReadBase()
            Triple(next, base, true)
        } catch (_: Exception) {
            null
        }
        if (disk == null) {
            token.failAfterAuthorization()
            return outcomeUnknown(token, failureIfUnknown)
        }

        val (next, base) = disk
        if (next == null && sameDiskBytes(base, intended)) {
            val completion = captureTransition(token::completeAtWithAssessment)
            return when (completion.assessment) {
                JournalClockAssessmentV1.ON_TIME -> if (completion.transitioned) {
                    JournalMutationOutcomeV1.Completed(intended)
                } else {
                    outcomeUnknown(token, JournalOperationFailureV1.DEADLINE_EXPIRED)
                }
                JournalClockAssessmentV1.EXPIRED ->
                    outcomeUnknown(token, JournalOperationFailureV1.DEADLINE_EXPIRED)
                JournalClockAssessmentV1.INVALID ->
                    outcomeUnknown(token, JournalOperationFailureV1.INVALID_CLOCK)
            }
        }
        if (sameDiskBytes(base, expected)) {
            val cleanup = if (next == null) {
                true
            } else {
                cleanupNextPreserving(expected)
            }
            when (captureTime(token)) {
                CapturedTimeV1.OnTime -> if (cleanup && token.markNotCommitted()) {
                    authority.poison()
                    return JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.RENAME_FAILED)
                }
                CapturedTimeV1.Invalid ->
                    return outcomeUnknown(token, JournalOperationFailureV1.INVALID_CLOCK)
                CapturedTimeV1.Expired -> Unit
            }
            token.failAfterAuthorization()
            return outcomeUnknown(
                token,
                if (cleanup) JournalOperationFailureV1.DEADLINE_EXPIRED else failureIfUnknown,
            )
        }
        token.failAfterAuthorization()
        return outcomeUnknown(token, failureIfUnknown)
    }

    private fun outcomeUnknown(
        token: JournalMutationTokenV1,
        failure: JournalOperationFailureV1,
    ): JournalMutationOutcomeV1 {
        val reason = when (token.snapshot()) {
            JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION ->
                JournalOperationFailureV1.DEADLINE_EXPIRED
            JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION ->
                JournalOperationFailureV1.INVALID_CLOCK
            else -> failure
        }
        authority.poison()
        return JournalMutationOutcomeV1.OutcomeUnknown(reason)
    }

    private fun expiredMutationOutcome(
        token: JournalMutationTokenV1,
        cleanupProven: Boolean,
    ): JournalMutationOutcomeV1 {
        val outcome = when (token.snapshot()) {
            JournalCommitStateV1.EXPIRED_PRE_COMMIT ->
                JournalMutationOutcomeV1.ExpiredPreCommit(cleanupProven)
            JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION,
            JournalCommitStateV1.FAILED_AFTER_AUTHORIZATION,
            -> JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.DEADLINE_EXPIRED)
            JournalCommitStateV1.INVALID_CLOCK_PRE_COMMIT -> if (cleanupProven) {
                JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.INVALID_CLOCK)
            } else {
                JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.INVALID_CLOCK)
            }
            JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION ->
                JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.INVALID_CLOCK)
            else -> JournalMutationOutcomeV1.StateInert
        }
        if (outcome !is JournalMutationOutcomeV1.StateInert) authority.poison()
        return outcome
    }

    private fun poisonedReadExpiry(): JournalReadOutcomeV1 {
        authority.poison()
        return JournalReadOutcomeV1.Expired
    }

    private fun poisonedInvalidReadClock(): JournalReadOutcomeV1 {
        authority.poison()
        return JournalReadOutcomeV1.Failed(JournalOperationFailureV1.INVALID_CLOCK)
    }

    private fun requireCleanableNext(stat: JournalStatV1) {
        if (stat.type != JournalNodeTypeV1.REGULAR_FILE) {
            throw KernelFailure(JournalOperationFailureV1.PATH_TYPE_INVALID)
        }
        if (stat.size !in 0L..RecoveryJournalV5Codec.MAXIMUM_FILE_BYTES.toLong()) {
            throw KernelFailure(JournalOperationFailureV1.FILE_SIZE_INVALID)
        }
    }

    private fun removeNext() {
        try {
            removeChecked(paths.next)
        } catch (_: JournalFileSystemExceptionV1) {
            throw KernelFailure(JournalOperationFailureV1.NEXT_CLEANUP_FAILED)
        }
    }

    private fun requirePathAbsent(
        path: JournalPathV1,
        failure: JournalOperationFailureV1,
    ) {
        if (lstatOrNull(path) != null) throw KernelFailure(failure)
    }

    private fun requireActiveOperationOwner(): Any =
        activeOperationOwner.get()
            ?: throw KernelFailure(JournalOperationFailureV1.TOKEN_ALREADY_RESOLVED)

    private fun lstatChecked(path: JournalPathV1): JournalStatV1 {
        revalidateParentImmediately(path)
        return fileSystem.lstat(path)
    }

    private fun mkdirChecked(
        path: JournalPathV1,
        mode: Int,
    ) {
        revalidateParentImmediately(path)
        fileSystem.mkdir(path, mode)
    }

    private fun openReadOnlyChecked(path: JournalPathV1): JournalFileHandleV1 {
        revalidateParentImmediately(path)
        return fileSystem.openReadOnly(path)
    }

    private fun openExclusiveCreateChecked(
        path: JournalPathV1,
        mode: Int,
    ): JournalFileHandleV1 {
        revalidateParentImmediately(path)
        return fileSystem.openExclusiveCreate(path, mode)
    }

    private fun removeChecked(path: JournalPathV1) {
        revalidateParentImmediately(path)
        fileSystem.remove(path)
    }

    private fun prepareRenameChecked(
        source: JournalPathV1,
        target: JournalPathV1,
    ) {
        revalidateParentImmediately(source)
        revalidateParentImmediately(target)
    }

    /** No filesystem call may occur between the timely authorization CAS and this rename. */
    private fun renamePrepared(
        source: JournalPathV1,
        target: JournalPathV1,
    ) {
        fileSystem.rename(source, target)
    }

    private fun openDirectoryChecked(path: JournalPathV1): JournalDirectoryStreamV1 {
        revalidateParentImmediately(path)
        return fileSystem.openDirectory(path)
    }

    /**
     * Public Android path APIs do not provide openat-style handles. Under the contract's private,
     * single-process filesystem assumption, every operation therefore revalidates the exact
     * immediate parent chain. The final syscall before the requested child operation validates
     * that child's immediate frozen parent.
     */
    private fun revalidateParentImmediately(path: JournalPathV1) {
        if (!paths.containsExact(path) || !authority.ownsOperation(requireActiveOperationOwner())) {
            throw KernelFailure(JournalOperationFailureV1.PATH_OR_PROVIDER_MISMATCH)
        }
        val parent = paths.parentOfExact(path) ?: return
        verifyFrozenDirectory(parent)
    }

    private fun verifyFrozenDirectory(path: JournalPathV1) {
        val expected = frozenDirectories[path]
            ?: throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        val parent = paths.parentOfExact(path)
        if (parent != null) verifyFrozenDirectory(parent)
        val before = rawLstatForParent(path)
        if (before.type != JournalNodeTypeV1.DIRECTORY ||
            before.device != expected.device ||
            before.inode != expected.inode
        ) {
            throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        }
        if (parent != null) verifyFrozenDirectory(parent)
        val handle = rawOpenReadOnlyForParent(path)
        var primary: Exception? = null
        try {
            val opened = fstat(handle, JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            if (opened.type != JournalNodeTypeV1.DIRECTORY ||
                opened.device != expected.device ||
                opened.inode != expected.inode
            ) {
                throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
            }
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            try {
                fileSystem.close(handle)
            } catch (_: Exception) {
                if (primary == null) throw KernelFailure(JournalOperationFailureV1.CLOSE_FAILED)
            }
        }
    }

    private fun rawLstatForParent(path: JournalPathV1): JournalStatV1 =
        try {
            fileSystem.lstat(path)
        } catch (_: JournalFileSystemExceptionV1) {
            throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        }

    private fun rawOpenReadOnlyForParent(path: JournalPathV1): JournalFileHandleV1 =
        try {
            fileSystem.openReadOnly(path)
        } catch (_: JournalFileSystemExceptionV1) {
            throw KernelFailure(JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
        }

    private fun lstatOrNull(path: JournalPathV1): JournalStatV1? =
        try {
            lstatChecked(path)
        } catch (failure: JournalFileSystemExceptionV1) {
            if (failure.failure == JournalFileSystemFailureV1.NOT_FOUND) {
                null
            } else {
                throw KernelFailure(JournalOperationFailureV1.FILESYSTEM_FAILURE)
            }
        }

    private fun fstat(
        handle: JournalFileHandleV1,
        failure: JournalOperationFailureV1,
    ): JournalStatV1 =
        try {
            fileSystem.fstat(handle)
        } catch (_: JournalFileSystemExceptionV1) {
            throw KernelFailure(failure)
        }

    private inline fun <T> withReadHandle(
        path: JournalPathV1,
        openFailure: JournalOperationFailureV1,
        block: (JournalFileHandleV1) -> T,
    ): T {
        val handle = try {
            openReadOnlyChecked(path)
        } catch (_: JournalFileSystemExceptionV1) {
            throw KernelFailure(openFailure)
        }
        var primary: Exception? = null
        try {
            return block(handle)
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            try {
                fileSystem.close(handle)
            } catch (_: Exception) {
                if (primary == null) throw KernelFailure(JournalOperationFailureV1.CLOSE_FAILED)
            }
        }
    }

    private fun captureTime(token: JournalReadTokenV1): CapturedTimeV1 {
        val now = try {
            clock.nowNs()
        } catch (_: Exception) {
            token.observeAt(Long.MIN_VALUE)
            return CapturedTimeV1.Invalid
        }
        return when (token.observeAt(now)) {
            JournalClockAssessmentV1.ON_TIME -> CapturedTimeV1.OnTime
            JournalClockAssessmentV1.EXPIRED -> CapturedTimeV1.Expired
            JournalClockAssessmentV1.INVALID -> CapturedTimeV1.Invalid
        }
    }

    private fun captureTime(token: JournalMutationTokenV1): CapturedTimeV1 {
        val now = try {
            clock.nowNs()
        } catch (_: Exception) {
            token.observeAt(Long.MIN_VALUE)
            return CapturedTimeV1.Invalid
        }
        return when (token.observeAt(now)) {
            JournalClockAssessmentV1.ON_TIME -> CapturedTimeV1.OnTime
            JournalClockAssessmentV1.EXPIRED -> CapturedTimeV1.Expired
            JournalClockAssessmentV1.INVALID -> CapturedTimeV1.Invalid
        }
    }

    private inline fun captureTransition(
        transitionAt: (Long) -> JournalTimedTransitionV1,
    ): JournalTimedTransitionV1 {
        val now = try {
            clock.nowNs()
        } catch (_: Exception) {
            return transitionAt(Long.MIN_VALUE)
        }
        return transitionAt(now)
    }

    private sealed interface CapturedTimeV1 {
        data object OnTime : CapturedTimeV1

        data object Expired : CapturedTimeV1

        data object Invalid : CapturedTimeV1
    }

    private fun sameDiskBytes(
        left: JournalDiskResultV1,
        right: JournalDiskResultV1,
    ): Boolean =
        when {
            left === JournalDiskResultV1.Absent && right === JournalDiskResultV1.Absent -> true
            left is JournalPresentDiskResultV1 && right is JournalPresentDiskResultV1 ->
                left.completeFileLength == right.completeFileLength &&
                    left.completeFileSha256 == right.completeFileSha256 &&
                    MessageDigest.isEqual(left.copyCompleteFileBytes(), right.copyCompleteFileBytes())
            else -> false
        }

    private fun sameNode(
        left: JournalStatV1,
        right: JournalStatV1,
    ): Boolean = left.device == right.device && left.inode == right.inode

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes)
        return when (val digest = Sha256Digest.fromBytes(raw)) {
            is CapabilityDomainResult.Valid -> digest.value
            is CapabilityDomainResult.Invalid -> error("SHA-256 provider returned a non-32-byte digest")
        }
    }

    private class KernelFailure(val reason: JournalOperationFailureV1) : Exception(reason.name)

    private companion object {
        const val BASE_NAME = "journal.bin"
        const val NEXT_NAME = "journal.bin.next"
    }
}
