package com.motionarcade.app.capability.persistence

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityResultId
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.recovery.ExpectedOldFileState
import com.motionarcade.vision.capability.recovery.PendingStoreCommitV5
import com.motionarcade.vision.capability.recovery.RecoveryJournalMode
import com.motionarcade.vision.capability.recovery.RecoveryJournalPayloadV5
import com.motionarcade.vision.capability.recovery.RecoveryJournalV5Codec
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckedAtomicReplaceV1Test {
    private data class TestAuthorityBinding(
        val authority: JournalStorageAuthorityV1,
        val issuerIdentity: Any,
        val generation: Long,
        val artifact: RuntimeArtifactId,
        val mode: RecoveryJournalMode,
        val paths: JournalPathsV1,
    )

    private var currentBinding: TestAuthorityBinding? = null
    private var currentPlanIdentity: Any? = null

    @Test
    fun fixedPathsAreCanonicalAndArtifactModeIsolated() {
        val artifactA = artifact(1)
        val artifactB = artifact(2)
        val solo = paths(artifactA, RecoveryJournalMode.SOLO)
        val dual = paths(artifactA, RecoveryJournalMode.DUAL)
        val other = paths(artifactB, RecoveryJournalMode.SOLO)

        assertEquals(
            "/private/no_backup/capability_recovery_v5/${artifactA.digest.toLowerHex()}/solo/journal.bin",
            solo.base.value,
        )
        assertEquals("${solo.base.value}.next", solo.next.value)
        assertNotEquals(solo.modeRoot, dual.modeRoot)
        assertNotEquals(solo.artifactRoot, other.artifactRoot)
        assertTrue(solo.base.value.startsWith("${solo.noBackupRoot.value}/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noncanonicalRootIsRejected() {
        JournalPathV1.canonicalAbsolute("/private/../escape")
    }

    @Test
    fun everyNoncanonicalRootSegmentAndTraversalSpellingIsRejected() {
        val rejected = listOf(
            "/private/./no_backup",
            "/private/../no_backup",
            "/private//no_backup",
            "/private/no_backup/",
            "/private/no backup",
            "/private/%2e%2e/no_backup",
            "/private/한글/no_backup",
            "/private\\no_backup",
        )

        rejected.forEach { value ->
            try {
                JournalPathV1.canonicalAbsolute(value)
                throw AssertionError("accepted noncanonical root: $value")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun fixedPathBundleIsNoncopyableAndHasOnlyTheExactJournalHierarchy() {
        val fixed = paths(artifact(3), RecoveryJournalMode.DUAL)
        assertTrue(
            JournalPathsV1::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertFalse(JournalPathsV1::class.java.declaredMethods.any { it.name == "copy" })
        assertFalse(JournalPathsV1::class.java.declaredMethods.any { it.name.startsWith("component") })
        assertEquals(
            "${fixed.noBackupRoot.value}/capability_recovery_v5/" +
                "${fixed.runtimeArtifactId.digest.toLowerHex()}/dual/journal.bin",
            fixed.base.value,
        )
        assertEquals("${fixed.base.value}.next", fixed.next.value)
    }

    @Test
    fun authorityRejectsAnotherAbsoluteRootAndSyntheticEqualPathObjects() {
        val fixture = fixture()
        val binding = requireNotNull(currentBinding)
        val syntheticBase = JournalPathV1.canonicalAbsolute(fixture.paths.base.value)
        val sameTextNewRoot = pathsAtRoot(
            rootValue = fixture.paths.noBackupRoot.value,
            artifact = fixture.paths.runtimeArtifactId,
            mode = fixture.paths.mode,
        )
        val anotherAbsoluteRoot = pathsAtRoot(
            rootValue = "/private/another_no_backup",
            artifact = fixture.paths.runtimeArtifactId,
            mode = fixture.paths.mode,
        )

        assertFalse(fixture.paths.containsExact(syntheticBase))
        assertFalse(fixture.paths.base === syntheticBase)
        assertFalse(binding.authority.matches(sameTextNewRoot))
        assertFalse(binding.authority.matches(anotherAbsoluteRoot))
        assertFalse(
            JournalPathsV1::class.java.declaredMethods.any { method ->
                method.parameterTypes.any { it == String::class.java }
            },
        )
    }

    @Test
    fun bootstrapCreatesAndSyncsOnlySelectedMode() {
        val fixture = fixture(readyDirectories = false)

        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            fixture.kernel.read(readToken()),
        )
        val mkdirPaths = fixture.fs.calls
            .filter { it.kind == FakeJournalCallKindV1.MKDIR }
            .mapNotNull { it.path }
        assertEquals(
            listOf(
                fixture.paths.recoveryRoot.value,
                fixture.paths.artifactRoot.value,
                fixture.paths.modeRoot.value,
            ),
            mkdirPaths,
        )
        assertFalse(fixture.fs.calls.any { it.path?.contains("/dual") == true })
        assertTrue(fixture.fs.calls.any { it.kind == FakeJournalCallKindV1.FSYNC })
    }

    @Test
    fun bootstrapRevalidatesParentImmediatelyBeforeEachChildOperation() {
        val fixture = fixture(readyDirectories = false)
        var swapped = false
        fixture.fs.afterCallObserver = { call ->
            if (!swapped &&
                call.kind == FakeJournalCallKindV1.CLOSE &&
                call.path == fixture.paths.noBackupRoot.value
            ) {
                swapped = true
                fixture.disk.putSymbolicLink(fixture.paths.noBackupRoot)
            }
        }

        assertFailure(fixture.kernel.read(readToken()), JournalOperationFailureV1.PATH_TYPE_INVALID)
        assertFalse(fixture.fs.calls.any { it.kind == FakeJournalCallKindV1.MKDIR })
    }

    @Test
    fun everyManagedPathOperationIsImmediatelyPrecededByItsFrozenParentValidation() {
        val fixture = fixture(readyDirectories = false)
        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            fixture.kernel.read(readToken()),
        )
        val parents = mapOf(
            fixture.paths.recoveryRoot.value to fixture.paths.noBackupRoot.value,
            fixture.paths.artifactRoot.value to fixture.paths.recoveryRoot.value,
            fixture.paths.modeRoot.value to fixture.paths.artifactRoot.value,
            fixture.paths.base.value to fixture.paths.modeRoot.value,
            fixture.paths.next.value to fixture.paths.modeRoot.value,
        )
        val pathOperations = setOf(
            FakeJournalCallKindV1.LSTAT,
            FakeJournalCallKindV1.MKDIR,
            FakeJournalCallKindV1.OPEN,
            FakeJournalCallKindV1.REMOVE,
            FakeJournalCallKindV1.RENAME,
            FakeJournalCallKindV1.OPEN_DIRECTORY,
        )
        fixture.fs.calls.forEachIndexed { index, call ->
            val parent = call.path?.let(parents::get)
            if (call.kind in pathOperations && parent != null) {
                assertTrue("no parent validation immediately before $call", index > 0)
                val previous = fixture.fs.calls[index - 1]
                assertEquals(FakeJournalCallKindV1.CLOSE, previous.kind)
                assertEquals(parent, previous.path)
            }
        }
    }

    @Test
    fun strictReadSupportsPartialProgressAndReturnsDefensiveBytes() {
        val fixture = fixture()
        val bytes = fileBytes(fixture.payload)
        fixture.disk.putFile(fixture.paths.base, bytes)
        fixture.fs.maximumReadChunk = 3

        val outcome = fixture.kernel.read(readToken()) as JournalReadOutcomeV1.Completed
        val present = outcome.diskResult as JournalPresentDiskResultV1
        val extracted = present.copyCompleteFileBytes()
        extracted[0] = (extracted[0].toInt() xor 0x7f).toByte()

        assertArrayEquals(bytes, present.copyCompleteFileBytes())
        assertEquals(bytes.size, present.completeFileLength)
        assertEquals(fixture.payload, present.payload)
        assertTrue(fixture.fs.calls.count { it.kind == FakeJournalCallKindV1.READ } > 2)
    }

    @Test
    fun exactNotFoundAloneProducesAbsent() {
        val absent = fixture()
        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            absent.kernel.read(readToken()),
        )

        val io = fixture()
        io.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.LSTAT,
                occurrence = -1,
                path = io.paths.base.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertTrue(io.kernel.read(readToken()) is JournalReadOutcomeV1.Failed)
    }

    @Test
    fun prematureZeroExtraEofAndLengthChangeAreRejected() {
        val zero = fixtureWithBase()
        zero.fs.forceZeroReadAtCall = 1
        assertFailure(zero.kernel.read(readToken()), JournalOperationFailureV1.READ_PROGRESS_INVALID)

        val extra = fixtureWithBase()
        extra.fs.returnExtraByteAtEof = true
        assertFailure(extra.kernel.read(readToken()), JournalOperationFailureV1.TRAILING_BYTES)

        val changed = fixtureWithBase()
        var baseReads = 0
        changed.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.READ && call.path == changed.paths.base.value) {
                baseReads += 1
                if (baseReads == 2) changed.disk.append(changed.paths.base, byteArrayOf(1))
            }
        }
        assertFailure(changed.kernel.read(readToken()), JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
    }

    @Test
    fun sizeSymlinkAndNonregularBaseAreRejectedWithoutRepair() {
        val huge = fixture()
        huge.disk.putFile(huge.paths.base, ByteArray(RecoveryJournalV5Codec.MAXIMUM_FILE_BYTES + 1))
        assertFailure(huge.kernel.read(readToken()), JournalOperationFailureV1.FILE_SIZE_INVALID)

        val symlink = fixture()
        symlink.disk.putSymbolicLink(symlink.paths.base)
        assertFailure(symlink.kernel.read(readToken()), JournalOperationFailureV1.PATH_TYPE_INVALID)

        val other = fixture()
        other.disk.putOther(other.paths.base)
        assertFailure(other.kernel.read(readToken()), JournalOperationFailureV1.PATH_TYPE_INVALID)
        assertFalse(other.fs.calls.any { it.kind == FakeJournalCallKindV1.REMOVE })
    }

    @Test
    fun unexpectedDuplicateAndMismatchedDirectoryEntriesBlock() {
        val unexpected = fixture()
        val odd = JournalPathV1.canonicalAbsolute("${unexpected.paths.modeRoot.value}/journal.bin.bak")
        unexpected.disk.putFile(odd, byteArrayOf(1))
        assertFailure(unexpected.kernel.read(readToken()), JournalOperationFailureV1.UNEXPECTED_SIBLING)

        val duplicate = fixtureWithBase()
        duplicate.fs.directoryEntriesOverride = listOf(
            entry(duplicate, "journal.bin"),
            entry(duplicate, "journal.bin"),
        )
        assertFailure(duplicate.kernel.read(readToken()), JournalOperationFailureV1.UNEXPECTED_SIBLING)

        val mismatch = fixtureWithBase()
        mismatch.fs.directoryEntriesOverride = listOf(
            JournalDirectoryEntryV1(
                JournalProviderKeyV1("other"),
                mismatch.paths.modeRoot,
                "journal.bin",
            ),
        )
        assertFailure(mismatch.kernel.read(readToken()), JournalOperationFailureV1.PATH_OR_PROVIDER_MISMATCH)
    }

    @Test
    fun iteratorAndStreamCloseFailuresBlockAndIteratorIsObtainedOnce() {
        val iteratorFailure = fixture()
        iteratorFailure.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.DIRECTORY_HAS_NEXT,
                occurrence = -1,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertFailure(
            iteratorFailure.kernel.read(readToken()),
            JournalOperationFailureV1.DIRECTORY_ENUMERATION_FAILED,
        )
        assertEquals(
            1,
            iteratorFailure.fs.calls.count { it.kind == FakeJournalCallKindV1.DIRECTORY_ITERATOR },
        )

        val closeFailure = fixture()
        closeFailure.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.DIRECTORY_CLOSE,
                occurrence = -1,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertFailure(closeFailure.kernel.read(readToken()), JournalOperationFailureV1.CLOSE_FAILED)
    }

    @Test
    fun partialNextIsRemovedOnlyBesideAbsentOrStrictValidBase() {
        val absent = fixture()
        absent.disk.putFile(absent.paths.next, byteArrayOf(1, 2, 3))
        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            absent.kernel.read(readToken()),
        )
        assertEquals(null, absent.disk.bytes(absent.paths.next))

        val valid = fixtureWithBase()
        val old = checkNotNull(valid.disk.bytes(valid.paths.base))
        valid.disk.putFile(valid.paths.next, byteArrayOf(9))
        assertTrue(valid.kernel.read(readToken()) is JournalReadOutcomeV1.Completed)
        assertArrayEquals(old, valid.disk.bytes(valid.paths.base))
        assertEquals(null, valid.disk.bytes(valid.paths.next))

        val corrupt = fixture()
        corrupt.disk.putFile(corrupt.paths.base, byteArrayOf(8, 8))
        corrupt.disk.putFile(corrupt.paths.next, byteArrayOf(9))
        assertTrue(corrupt.kernel.read(readToken()) is JournalReadOutcomeV1.Failed)
        assertArrayEquals(byteArrayOf(9), corrupt.disk.bytes(corrupt.paths.next))
        assertFalse(corrupt.fs.calls.any { it.kind == FakeJournalCallKindV1.REMOVE })
    }

    @Test
    fun cleanReplaceUsesExclusiveNextSingleRenameAndNeverPredeletesBase() {
        val fixture = fixture()
        val expected = JournalDiskResultV1.Absent
        val outcome = fixture.kernel.replace(
            mutationToken(),
            replacePlan(expected, fixture.payload),
        )

        val completed = outcome as JournalMutationOutcomeV1.Completed
        assertArrayEquals(fileBytes(fixture.payload), fixture.disk.bytes(fixture.paths.base))
        assertEquals(null, fixture.disk.bytes(fixture.paths.next))
        assertEquals(1, fixture.fs.calls.count { it.kind == FakeJournalCallKindV1.RENAME })
        assertFalse(
            fixture.fs.calls.any {
                it.kind == FakeJournalCallKindV1.REMOVE && it.path == fixture.paths.base.value
            },
        )
        assertEquals(fixture.payload, completed.diskResult.payload)
    }

    @Test
    fun existingBaseIsAtomicallyOverwrittenWithoutDeleteOrFallbackRename() {
        val fixture = fixtureWithBase()
        val oldBytes = checkNotNull(fixture.disk.bytes(fixture.paths.base))
        val intended = fixture.payload.copy(lastEpoch = 1uL)

        assertTrue(
            fixture.kernel.replace(
                mutationToken(),
                replacePlan(present(oldBytes, fixture.payload), intended),
            ) is JournalMutationOutcomeV1.Completed,
        )
        assertArrayEquals(fileBytes(intended), fixture.disk.bytes(fixture.paths.base))
        assertEquals(1, fixture.fs.calls.count { it.kind == FakeJournalCallKindV1.RENAME })
        assertFalse(
            fixture.fs.calls.any {
                it.kind == FakeJournalCallKindV1.REMOVE && it.path == fixture.paths.base.value
            },
        )
    }

    @Test
    fun expectedComparisonUsesWholeBytesNotOnlyHashOrPayload() {
        val fixture = fixtureWithBase()
        val realBytes = checkNotNull(fixture.disk.bytes(fixture.paths.base))
        val real = present(realBytes, fixture.payload)
        val differentBytes = realBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forged = JournalPresentDiskResultV1(
            completeFileLength = real.completeFileLength,
            completeFileSha256 = real.completeFileSha256,
            completeFileBytes = differentBytes,
            payload = real.payload,
        )

        val outcome = fixture.kernel.replace(
            mutationToken(),
            replacePlan(forged, fixture.payload),
        )
        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.EXPECTED_BASE_MISMATCH),
            outcome,
        )
        assertFalse(fixture.fs.calls.any { it.kind == FakeJournalCallKindV1.RENAME })
    }

    @Test
    fun zeroOrInterruptedWriteCleansNextAndPreservesOld() {
        val zero = fixtureWithBase()
        val old = checkNotNull(zero.disk.bytes(zero.paths.base))
        zero.fs.forceZeroWriteAtCall = 1
        val zeroOutcome = zero.kernel.replace(
            mutationToken(),
            replacePlan(present(old, zero.payload), zero.payload),
        )
        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.WRITE_PROGRESS_INVALID),
            zeroOutcome,
        )
        assertArrayEquals(old, zero.disk.bytes(zero.paths.base))
        assertEquals(null, zero.disk.bytes(zero.paths.next))

        val interrupted = fixtureWithBase()
        val interruptedOld = checkNotNull(interrupted.disk.bytes(interrupted.paths.base))
        interrupted.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.WRITE,
                occurrence = -1,
                path = interrupted.paths.next.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.INTERRUPTED),
            ),
        )
        assertTrue(
            interrupted.kernel.replace(
                mutationToken(),
                replacePlan(present(interruptedOld, interrupted.payload), interrupted.payload),
            ) is JournalMutationOutcomeV1.NotCommitted,
        )
        assertArrayEquals(interruptedOld, interrupted.disk.bytes(interrupted.paths.base))
    }

    @Test
    fun exclusiveOpenFailAfterCreateIsStrictlyCleanedBeforeNotCommitted() {
        val fixture = fixture()
        fixture.fs.addRule(
            FakeJournalRuleV1(
                kind = FakeJournalCallKindV1.OPEN,
                occurrence = -1,
                path = fixture.paths.next.value,
                action = FakeJournalRuleActionV1.FailAfter(JournalFileSystemFailureV1.IO),
            ),
        )

        val outcome = fixture.kernel.replace(
            mutationToken(),
            replacePlan(JournalDiskResultV1.Absent, fixture.payload),
        )

        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.FILE_WRITE_FAILED),
            outcome,
        )
        assertEquals(null, fixture.disk.bytes(fixture.paths.next))
        assertEquals(null, fixture.disk.bytes(fixture.paths.base))
        assertTrue(
            fixture.fs.calls.any {
                it.kind == FakeJournalCallKindV1.REMOVE && it.path == fixture.paths.next.value
            },
        )
        assertTrue(
            fixture.fs.calls.any {
                it.kind == FakeJournalCallKindV1.FSYNC && it.path == fixture.paths.modeRoot.value
            },
        )
    }

    @Test
    fun exclusiveOpenFailAfterCreateWithResidualNextNeverReturnsNotCommitted() {
        val fixture = fixture()
        fixture.fs.addRule(
            FakeJournalRuleV1(
                kind = FakeJournalCallKindV1.OPEN,
                occurrence = -1,
                path = fixture.paths.next.value,
                action = FakeJournalRuleActionV1.FailAfter(JournalFileSystemFailureV1.IO),
            ),
        )
        fixture.fs.addRule(
            FakeJournalRuleV1(
                kind = FakeJournalCallKindV1.REMOVE,
                occurrence = -1,
                path = fixture.paths.next.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )

        val outcome = fixture.kernel.replace(
            mutationToken(),
            replacePlan(JournalDiskResultV1.Absent, fixture.payload),
        )

        assertEquals(JournalMutationOutcomeV1.ExpiredPreCommit(cleanupProven = false), outcome)
        assertFalse(outcome is JournalMutationOutcomeV1.NotCommitted)
        assertArrayEquals(ByteArray(0), fixture.disk.bytes(fixture.paths.next))
        assertEquals(JournalReadOutcomeV1.StateInert, fixture.kernel.read(readToken()))
    }

    @Test
    fun renameFailureClassifiesExactOldOrNewAndThirdIsUnknown() {
        val old = fixtureWithBase()
        val oldBytes = checkNotNull(old.disk.bytes(old.paths.base))
        old.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.RENAME,
                occurrence = -1,
                path = old.paths.next.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.RENAME_FAILED),
            old.kernel.replace(
                mutationToken(),
                replacePlan(present(oldBytes, old.payload), old.payload),
            ),
        )

        val newlyCommitted = fixture()
        newlyCommitted.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.RENAME,
                occurrence = -1,
                path = newlyCommitted.paths.next.value,
                action = FakeJournalRuleActionV1.FailAfter(JournalFileSystemFailureV1.IO),
            ),
        )
        assertTrue(
            newlyCommitted.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, newlyCommitted.payload),
            ) is JournalMutationOutcomeV1.Completed,
        )

        val third = fixture()
        third.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.RENAME) {
                third.disk.putFile(third.paths.base, byteArrayOf(7, 7, 7))
            }
        }
        third.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.RENAME,
                occurrence = -1,
                action = FakeJournalRuleActionV1.FailAfter(JournalFileSystemFailureV1.IO),
            ),
        )
        assertTrue(
            third.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, third.payload),
            ) is JournalMutationOutcomeV1.OutcomeUnknown,
        )
        assertArrayEquals(byteArrayOf(7, 7, 7), third.disk.bytes(third.paths.base))
    }

    @Test
    fun exactDeadlineIsAllowedAndPlusOneExpiresBeforeAnyFilesystemCall() {
        val exact = fixture(clockValue = JOURNAL_OPERATION_DEADLINE_NS)
        val exactToken = mutationToken(start = 0L)
        assertTrue(
            exact.kernel.replace(
                exactToken,
                replacePlan(JournalDiskResultV1.Absent, exact.payload),
            ) is JournalMutationOutcomeV1.Completed,
        )
        assertEquals(JournalCommitStateV1.COMPLETED, exactToken.snapshot())

        val late = fixture(clockValue = JOURNAL_OPERATION_DEADLINE_NS + 1L)
        val lateToken = mutationToken(start = 0L)
        assertEquals(
            JournalMutationOutcomeV1.ExpiredPreCommit(cleanupProven = true),
            late.kernel.replace(
                lateToken,
                replacePlan(JournalDiskResultV1.Absent, late.payload),
            ),
        )
        assertTrue(late.fs.calls.isEmpty())
    }

    @Test
    fun clockRegressionAndDeadlineOverflowReturnFiniteInvalidClockWithoutFilesystemAccess() {
        val readRegression = fixture(clockValue = 99L)
        assertEquals(
            JournalReadOutcomeV1.Failed(JournalOperationFailureV1.INVALID_CLOCK),
            readRegression.kernel.read(readToken(start = 100L)),
        )
        assertTrue(readRegression.fs.calls.isEmpty())

        val mutationRegression = fixture(clockValue = 99L)
        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.INVALID_CLOCK),
            mutationRegression.kernel.replace(
                mutationToken(start = 100L),
                replacePlan(JournalDiskResultV1.Absent, mutationRegression.payload),
            ),
        )
        assertTrue(mutationRegression.fs.calls.isEmpty())

        val overflowStart = Long.MAX_VALUE - (JOURNAL_OPERATION_DEADLINE_NS / 2L)
        val malformedOverflowDeadline = constructPrivate(
            JournalOperationDeadlineV1::class.java,
            overflowStart,
            Long.MAX_VALUE,
        )
        val overflow = fixture(clockValue = overflowStart)
        assertEquals(
            JournalReadOutcomeV1.Failed(JournalOperationFailureV1.INVALID_CLOCK),
            overflow.kernel.read(readTokenWithDeadline(malformedOverflowDeadline)),
        )
        assertTrue(overflow.fs.calls.isEmpty())
    }

    @Test
    fun inFlightReadAndPreCommitMutationRejectClockRegression() {
        val read = fixture(clockValue = 100L)
        var readRegressed = false
        read.fs.afterCallObserver = {
            if (!readRegressed) {
                readRegressed = true
                read.clock.value = 50L
            }
        }
        val readToken = readToken(start = 0L)
        assertEquals(
            JournalReadOutcomeV1.Failed(JournalOperationFailureV1.INVALID_CLOCK),
            read.kernel.read(readToken),
        )
        assertEquals(JournalReadTokenStateV1.INVALID_CLOCK, readToken.snapshot())
        assertTrue(read.fs.calls.isNotEmpty())

        val mutation = fixture(clockValue = 100L)
        var mutationRegressed = false
        mutation.fs.afterCallObserver = {
            if (!mutationRegressed) {
                mutationRegressed = true
                mutation.clock.value = 50L
            }
        }
        val mutationToken = mutationToken(start = 0L)
        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.INVALID_CLOCK),
            mutation.kernel.replace(
                mutationToken,
                replacePlan(JournalDiskResultV1.Absent, mutation.payload),
            ),
        )
        assertEquals(JournalCommitStateV1.INVALID_CLOCK_PRE_COMMIT, mutationToken.snapshot())
        assertEquals(null, mutation.disk.bytes(mutation.paths.base))
        assertEquals(null, mutation.disk.bytes(mutation.paths.next))
        assertFalse(mutation.fs.calls.any { it.kind == FakeJournalCallKindV1.RENAME })
    }

    @Test
    fun lowerWatchdogObservationAfterAuthorizationIsUnknownAndRenameCasIsAdjacent() {
        val fixture = fixture(clockValue = 100L)
        val token = mutationToken(start = 0L)
        val watchdog = Executors.newSingleThreadExecutor()
        var renameStarted = false
        var filesystemCallsBetweenAuthorizationAndRename = 0
        fixture.fs.beforeCallObserver = { call ->
            if (!renameStarted && token.snapshot() == JournalCommitStateV1.COMMIT_AUTHORIZED) {
                if (call.kind == FakeJournalCallKindV1.RENAME) {
                    renameStarted = true
                    assertEquals(
                        JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION,
                        watchdog.submit<JournalCommitStateV1> { token.expireAt(50L) }
                            .get(10, TimeUnit.SECONDS),
                    )
                } else {
                    filesystemCallsBetweenAuthorizationAndRename += 1
                }
            }
        }
        try {
            assertEquals(
                JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.INVALID_CLOCK),
                fixture.kernel.replace(
                    token,
                    replacePlan(JournalDiskResultV1.Absent, fixture.payload),
                ),
            )
        } finally {
            watchdog.shutdownNow()
        }
        assertTrue(renameStarted)
        assertEquals(0, filesystemCallsBetweenAuthorizationAndRename)
        assertEquals(JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION, token.snapshot())
        assertArrayEquals(fileBytes(fixture.payload), fixture.disk.bytes(fixture.paths.base))
        assertEquals(null, fixture.disk.bytes(fixture.paths.next))
    }

    @Test
    fun clockRegressionObservedAfterRenameIsOutcomeUnknown() {
        val fixture = fixture(clockValue = 100L)
        fixture.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.RENAME) {
                fixture.clock.value = 50L
            }
        }
        val token = mutationToken(start = 0L)
        assertEquals(
            JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.INVALID_CLOCK),
            fixture.kernel.replace(
                token,
                replacePlan(JournalDiskResultV1.Absent, fixture.payload),
            ),
        )
        assertEquals(JournalCommitStateV1.INVALID_CLOCK_AFTER_AUTHORIZATION, token.snapshot())
        assertArrayEquals(fileBytes(fixture.payload), fixture.disk.bytes(fixture.paths.base))
        assertEquals(null, fixture.disk.bytes(fixture.paths.next))
    }

    @Test
    fun plusOneAfterAuthorizationIsOutcomeUnknownDespiteWholeNewDisk() {
        val fixture = fixture()
        fixture.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.RENAME) {
                fixture.clock.value = JOURNAL_OPERATION_DEADLINE_NS + 1L
            }
        }
        val token = mutationToken()
        val outcome = fixture.kernel.replace(
            token,
            replacePlan(JournalDiskResultV1.Absent, fixture.payload),
        )

        assertEquals(
            JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.DEADLINE_EXPIRED),
            outcome,
        )
        assertEquals(JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION, token.snapshot())
        assertArrayEquals(fileBytes(fixture.payload), fixture.disk.bytes(fixture.paths.base))
    }

    @Test
    fun plusOneDuringAuthorizedOldCleanupCannotBecomeNotCommitted() {
        val fixture = fixtureWithBase()
        val old = checkNotNull(fixture.disk.bytes(fixture.paths.base))
        fixture.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.RENAME,
                occurrence = -1,
                path = fixture.paths.next.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        fixture.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.REMOVE && call.path == fixture.paths.next.value) {
                fixture.clock.value = JOURNAL_OPERATION_DEADLINE_NS + 1L
            }
        }
        val token = mutationToken()
        val outcome = fixture.kernel.replace(
            token,
            replacePlan(present(old, fixture.payload), fixture.payload.copy(lastEpoch = 1uL)),
        )

        assertEquals(
            JournalMutationOutcomeV1.OutcomeUnknown(JournalOperationFailureV1.DEADLINE_EXPIRED),
            outcome,
        )
        assertEquals(JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION, token.snapshot())
        assertArrayEquals(old, fixture.disk.bytes(fixture.paths.base))
        assertEquals(null, fixture.disk.bytes(fixture.paths.next))
    }

    @Test
    fun watchdogAndOwnerCommitOrdersAreLinearized() {
        val watchdogFirst = mutationToken()
        assertEquals(
            JournalCommitStateV1.EXPIRED_PRE_COMMIT,
            watchdogFirst.expireAt(JOURNAL_OPERATION_DEADLINE_NS + 1L),
        )
        assertFalse(watchdogFirst.authorizeAt(JOURNAL_OPERATION_DEADLINE_NS))

        val ownerFirst = mutationToken()
        assertTrue(ownerFirst.authorizeAt(JOURNAL_OPERATION_DEADLINE_NS))
        assertEquals(
            JournalCommitStateV1.EXPIRED_AFTER_AUTHORIZATION,
            ownerFirst.expireAt(JOURNAL_OPERATION_DEADLINE_NS + 1L),
        )
        assertFalse(ownerFirst.completeAt(JOURNAL_OPERATION_DEADLINE_NS))

        val completionFirst = mutationToken()
        assertTrue(completionFirst.authorizeAt(JOURNAL_OPERATION_DEADLINE_NS))
        assertTrue(completionFirst.completeAt(JOURNAL_OPERATION_DEADLINE_NS))
        assertEquals(
            JournalCommitStateV1.COMPLETED,
            completionFirst.expireAt(JOURNAL_OPERATION_DEADLINE_NS + 1L),
        )
    }

    @Test
    fun resolvedAndLateTokensAreIdempotentAndStateInert() {
        val fixture = fixture()
        val readToken = readToken()
        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            fixture.kernel.read(readToken),
        )
        val callCount = fixture.fs.calls.size
        assertEquals(JournalReadOutcomeV1.StateInert, fixture.kernel.read(readToken))
        assertEquals(callCount, fixture.fs.calls.size)

        val expired = readToken()
        expired.expireAt(JOURNAL_OPERATION_DEADLINE_NS + 1L)
        assertEquals(JournalReadOutcomeV1.Expired, fixture.kernel.read(expired))
        assertEquals(callCount, fixture.fs.calls.size)
        assertEquals(JournalReadOutcomeV1.StateInert, fixture.kernel.read(readToken()))

        val mutationFixture = fixture()
        val mutation = mutationToken()
        assertTrue(
            mutationFixture.kernel.replace(
                mutation,
                replacePlan(JournalDiskResultV1.Absent, mutationFixture.payload),
            ) is JournalMutationOutcomeV1.Completed,
        )
        val afterMutation = mutationFixture.fs.calls.size
        assertEquals(
            JournalMutationOutcomeV1.StateInert,
            mutationFixture.kernel.replace(
                mutation,
                replacePlan(JournalDiskResultV1.Absent, mutationFixture.payload),
            ),
        )
        assertEquals(afterMutation, mutationFixture.fs.calls.size)
    }

    @Test
    fun ordinaryApiHasNoAuthorityTokenPlanHandleOrReceiptConstructor() {
        // Test reflection is an explicit fixture mechanism. Arbitrary hostile same-process
        // setAccessible is outside the storage contract and is not claimed to be resisted.
        listOf(
            JournalFrozenNoBackupRootV1::class.java,
            JournalStorageAuthorityV1::class.java,
            CheckedAtomicReplaceV1::class.java,
            JournalOperationDeadlineV1::class.java,
            JournalReadTokenV1::class.java,
            JournalMutationTokenV1::class.java,
            JournalReplacePlanV1::class.java,
            ValidatedCurrentPendingHandleV1::class.java,
            StrictModeStoreObservationV1::class.java,
            StrictModeStoreIdentityReceiptV1::class.java,
            AndroidJournalStorageEnvironmentV1::class.java,
        ).forEach { type ->
            assertTrue(
                "$type exposes a non-private constructor",
                type.declaredConstructors
                    .filterNot { it.isSynthetic }
                    .all { Modifier.isPrivate(it.modifiers) },
            )
        }
        assertFalse(
            JournalReplacePlanV1::class.java.declaredMethods.any {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.any { parameter ->
                        parameter == RecoveryJournalPayloadV5::class.java || parameter == ByteArray::class.java
                    }
            },
        )
    }

    @Test
    fun crossOwnerCapabilityIsRejectedWithoutConsumptionAndReplayIsInert() {
        val original = fixture()
        val token = mutationToken()
        val plan = replacePlan(JournalDiskResultV1.Absent, original.payload)
        val otherOwner = fixture()

        assertEquals(JournalMutationOutcomeV1.StateInert, otherOwner.kernel.replace(token, plan))
        assertTrue(otherOwner.fs.calls.isEmpty())
        assertTrue(original.kernel.replace(token, plan) is JournalMutationOutcomeV1.Completed)
        val callsAfterCommit = original.fs.calls.size
        assertEquals(JournalMutationOutcomeV1.StateInert, original.kernel.replace(token, plan))
        assertEquals(callsAfterCommit, original.fs.calls.size)
    }

    @Test
    fun wrongGenerationCapabilityFailsBeforeFilesystemAccess() {
        val fixture = fixture()
        val binding = requireNotNull(currentBinding)
        val planIdentity = Any()
        val wrongGenerationToken = constructPrivate(
            JournalMutationTokenV1::class.java,
            deadline(0L),
            binding.issuerIdentity,
            binding.generation + 1L,
            binding.artifact.digest,
            binding.mode,
            binding.paths,
            planIdentity,
        )
        val wrongGenerationPlan = constructPrivate(
            JournalReplacePlanV1::class.java,
            JournalDiskResultV1.Absent,
            fixture.payload,
            binding.issuerIdentity,
            binding.generation + 1L,
            binding.artifact.digest,
            binding.mode,
            binding.paths,
            planIdentity,
        )

        assertEquals(
            JournalMutationOutcomeV1.StateInert,
            fixture.kernel.replace(wrongGenerationToken, wrongGenerationPlan),
        )
        assertTrue(fixture.fs.calls.isEmpty())
    }

    @Test
    fun concurrentMutationAndReadCapabilitiesAdmitExactlyOneOwnerCall() {
        val mutationFixture = fixture()
        val token = mutationToken()
        val plan = replacePlan(JournalDiskResultV1.Absent, mutationFixture.payload)
        val mutationStart = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val mutationFutures = List(2) {
            pool.submit<JournalMutationOutcomeV1> {
                mutationStart.await()
                mutationFixture.kernel.replace(token, plan)
            }
        }
        mutationStart.countDown()
        val mutationOutcomes = mutationFutures.map { it.get(10, TimeUnit.SECONDS) }

        assertEquals(1, mutationOutcomes.count { it is JournalMutationOutcomeV1.Completed })
        assertEquals(1, mutationOutcomes.count { it is JournalMutationOutcomeV1.StateInert })
        assertEquals(1, mutationFixture.fs.calls.count { it.kind == FakeJournalCallKindV1.RENAME })

        val readFixture = fixtureWithBase()
        val readToken = readToken()
        val readStart = CountDownLatch(1)
        val readFutures = List(2) {
            pool.submit<JournalReadOutcomeV1> {
                readStart.await()
                readFixture.kernel.read(readToken)
            }
        }
        readStart.countDown()
        val readOutcomes = readFutures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, readOutcomes.count { it is JournalReadOutcomeV1.Completed })
        assertEquals(1, readOutcomes.count { it is JournalReadOutcomeV1.StateInert })
    }

    @Test
    fun distinctValidTokensCannotEnterFilesystemConcurrentlyAndContentionPoisonsGeneration() {
        val fixture = fixture()
        val firstToken = mutationToken()
        val firstPlan = replacePlan(JournalDiskResultV1.Absent, fixture.payload)
        val secondToken = mutationToken()
        val secondPlan = replacePlan(JournalDiskResultV1.Absent, fixture.payload)
        val firstEnteredFilesystem = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)
        fixture.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.LSTAT &&
                call.path == fixture.paths.noBackupRoot.value &&
                blockedOnce.compareAndSet(false, true)
            ) {
                firstEnteredFilesystem.countDown()
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<JournalMutationOutcomeV1> {
            fixture.kernel.replace(firstToken, firstPlan)
        }
        assertTrue(firstEnteredFilesystem.await(10, TimeUnit.SECONDS))
        val callsWhileFirstBlocked = fixture.fs.calls.size
        val second = pool.submit<JournalMutationOutcomeV1> {
            fixture.kernel.replace(secondToken, secondPlan)
        }
        assertEquals(JournalMutationOutcomeV1.StateInert, second.get(10, TimeUnit.SECONDS))
        assertEquals(callsWhileFirstBlocked, fixture.fs.calls.size)
        releaseFirst.countDown()
        assertTrue(first.get(10, TimeUnit.SECONDS) is JournalMutationOutcomeV1.NotCommitted)
        pool.shutdownNow()
        val callsAfterOwnerReturn = fixture.fs.calls.size

        assertEquals(JournalReadOutcomeV1.StateInert, fixture.kernel.read(readToken()))
        assertEquals(callsAfterOwnerReturn, fixture.fs.calls.size)
        assertEquals(0, fixture.fs.calls.count { it.kind == FakeJournalCallKindV1.RENAME })
    }

    @Test
    fun contentionAfterRenameStillPoisonsOwnerCompletionAndFutureOperations() {
        val fixture = fixture()
        val firstEnteredRename = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)
        fixture.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.RENAME &&
                blockedOnce.compareAndSet(false, true)
            ) {
                firstEnteredRename.countDown()
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<JournalMutationOutcomeV1> {
            fixture.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, fixture.payload),
            )
        }
        assertTrue(firstEnteredRename.await(10, TimeUnit.SECONDS))
        val callsWhileFirstBlocked = fixture.fs.calls.size
        val second = pool.submit<JournalReadOutcomeV1> {
            fixture.kernel.read(readToken())
        }
        assertEquals(JournalReadOutcomeV1.StateInert, second.get(10, TimeUnit.SECONDS))
        assertEquals(callsWhileFirstBlocked, fixture.fs.calls.size)
        releaseFirst.countDown()
        assertEquals(
            JournalMutationOutcomeV1.OutcomeUnknown(
                JournalOperationFailureV1.OPERATION_CONTENTION,
            ),
            first.get(10, TimeUnit.SECONDS),
        )
        pool.shutdownNow()
        val callsAfterOwnerReturn = fixture.fs.calls.size

        assertEquals(JournalReadOutcomeV1.StateInert, fixture.kernel.read(readToken()))
        assertEquals(callsAfterOwnerReturn, fixture.fs.calls.size)
        assertEquals(1, fixture.fs.calls.count { it.kind == FakeJournalCallKindV1.RENAME })
    }

    @Test
    fun pendingClassifierAcceptsOnlyStrictCurrentJournalHandleAndDeliversReceiptOnce() {
        val fixture = fixture()
        val pending = pending(ExpectedOldFileState.ABSENT)
        val handle = pendingHandle(fixture, pending)
        val binding = requireNotNull(currentBinding)
        val observation = strictObservation(
            binding = binding,
            fileState = StrictModeStoreFileStateV1.ABSENT,
            completeFileLength = 0uL,
            completeFileSha256 = null,
            embeddedRecordSha256 = null,
        )

        val classified = StrictModeStoreIdentityReceiptV1.classify(
            binding.authority,
            handle,
            observation,
        ) as PendingStoreStrictClassificationV1.ExactExpectedOld
        assertEquals(
            PendingStoreStrictClassificationV1.AuthorityRejected,
            StrictModeStoreIdentityReceiptV1.classify(
                binding.authority,
                handle,
                strictObservation(
                    binding,
                    StrictModeStoreFileStateV1.ABSENT,
                    0uL,
                    null,
                    null,
                ),
            ),
        )
        val bridge = RecordingReceiptBridgeV1()
        classified.deliverTo(binding.authority, bridge)
        classified.deliverTo(binding.authority, bridge)
        assertEquals(1, bridge.manualRetrySaveNotCommitted.get())
        assertEquals(0, bridge.committedFinalClear.get())
        assertFalse(
            StrictModeStoreIdentityReceiptV1::class.java.declaredMethods.any { method ->
                method.parameterTypes.any { it == PendingStoreCommitV5::class.java } ||
                    method.returnType == Boolean::class.javaPrimitiveType
            },
        )
    }

    @Test
    fun returnedOrValueCopiedDiskResultCannotMintAnotherPendingHandle() {
        val fixture = fixture()
        val pending = pending(ExpectedOldFileState.ABSENT)
        val payload = fixture.payload.copy(
            lastEpoch = pending.attemptEpoch,
            pendingStoreCommit = pending,
        )
        fixture.disk.putFile(fixture.paths.base, fileBytes(payload))
        val completed = fixture.kernel.read(readToken()) as JournalReadOutcomeV1.Completed
        assertTrue(completed.validatedCurrentPending != null)
        val returned = completed.diskResult as JournalPresentDiskResultV1
        val binding = requireNotNull(currentBinding)
        val owner = requireNotNull(binding.authority.admitOperation(binding.paths))
        try {
            val modeDirectoryIdentity = requireNotNull(
                binding.authority.modeDirectoryIdentity(owner, binding.paths),
            )
            assertEquals(
                null,
                ValidatedCurrentPendingHandleV1.issueFromStrictRead(
                    kernel = fixture.kernel,
                    authority = binding.authority,
                    operationOwner = owner,
                    paths = binding.paths,
                    result = returned,
                    modeDirectoryIdentity = modeDirectoryIdentity,
                ),
            )
            val copied = JournalPresentDiskResultV1(
                completeFileLength = returned.completeFileLength,
                completeFileSha256 = returned.completeFileSha256,
                completeFileBytes = returned.copyCompleteFileBytes(),
                payload = returned.payload,
                fileIdentity = returned.fileIdentity,
            )
            assertEquals(
                null,
                ValidatedCurrentPendingHandleV1.issueFromStrictRead(
                    kernel = fixture.kernel,
                    authority = binding.authority,
                    operationOwner = owner,
                    paths = binding.paths,
                    result = copied,
                    modeDirectoryIdentity = modeDirectoryIdentity,
                ),
            )
        } finally {
            assertTrue(binding.authority.releaseOperation(owner))
        }
    }

    @Test
    fun pendingClassifierSeparatesIntendedNewExpectedOldAndThirdIdentity() {
        val oldHash = digest(44)
        val newHash = digest(45)
        val recordHash = digest(46)
        val pending = pending(
            expectedOldState = ExpectedOldFileState.HASH_PRESENT,
            expectedOldLength = 91uL,
            expectedOldHash = oldHash,
            intendedLength = 113uL,
            intendedHash = newHash,
            intendedRecordHash = recordHash,
        )

        val expectedFixture = fixture()
        val expectedHandle = pendingHandle(expectedFixture, pending)
        val expectedBinding = requireNotNull(currentBinding)
        val expectedOld = StrictModeStoreIdentityReceiptV1.classify(
            expectedBinding.authority,
            expectedHandle,
            strictObservation(
                expectedBinding,
                StrictModeStoreFileStateV1.PRESENT,
                91uL,
                oldHash,
                digest(99),
            ),
        )
        val intendedFixture = fixture()
        val intendedHandle = pendingHandle(intendedFixture, pending)
        val intendedBinding = requireNotNull(currentBinding)
        val intendedNew = StrictModeStoreIdentityReceiptV1.classify(
            intendedBinding.authority,
            intendedHandle,
            strictObservation(
                intendedBinding,
                StrictModeStoreFileStateV1.PRESENT,
                113uL,
                newHash,
                recordHash,
            ),
        )
        val thirdFixture = fixture()
        val thirdHandle = pendingHandle(thirdFixture, pending)
        val thirdBinding = requireNotNull(currentBinding)
        val sameResultIdButThirdBytes = StrictModeStoreIdentityReceiptV1.classify(
            thirdBinding.authority,
            thirdHandle,
            strictObservation(
                thirdBinding,
                StrictModeStoreFileStateV1.PRESENT,
                113uL,
                digest(47),
                recordHash,
            ),
        )

        assertTrue(expectedOld is PendingStoreStrictClassificationV1.ExactExpectedOld)
        assertTrue(intendedNew is PendingStoreStrictClassificationV1.ExactIntendedNew)
        assertEquals(PendingStoreStrictClassificationV1.ThirdIdentity, sameResultIdButThirdBytes)
        assertEquals(JournalReadOutcomeV1.StateInert, thirdFixture.kernel.read(readToken()))
    }

    @Test
    fun pendingReceiptIsPrivateCrossOwnerRejectedAndConcurrentDeliveryIsOneShot() {
        val first = fixture()
        val pending = pending(ExpectedOldFileState.ABSENT)
        val handle = pendingHandle(first, pending)
        val firstBinding = requireNotNull(currentBinding)
        val observation = strictObservation(
            firstBinding,
            StrictModeStoreFileStateV1.ABSENT,
            0uL,
            null,
            null,
        )
        val other = fixture()
        val otherBinding = requireNotNull(currentBinding)

        assertTrue(
            StrictModeStoreIdentityReceiptV1::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertEquals(
            PendingStoreStrictClassificationV1.AuthorityRejected,
            StrictModeStoreIdentityReceiptV1.classify(otherBinding.authority, handle, observation),
        )
        val classification = StrictModeStoreIdentityReceiptV1.classify(
            firstBinding.authority,
            handle,
            observation,
        ) as PendingStoreStrictClassificationV1.ExactExpectedOld
        val bridge = RecordingReceiptBridgeV1()
        classification.deliverTo(otherBinding.authority, bridge)
        assertEquals(0, bridge.manualRetrySaveNotCommitted.get())

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = List(2) {
            pool.submit<Unit> {
                start.await()
                classification.deliverTo(firstBinding.authority, bridge)
            }
        }
        start.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, bridge.manualRetrySaveNotCommitted.get())
        assertEquals(0, bridge.committedFinalClear.get())
        assertEquals(null, first.disk.bytes(first.paths.next))
        assertEquals(null, other.disk.bytes(other.paths.next))
    }

    @Test
    fun processCutsBeforeRenameRecoverOldAndAfterRenameRecoverNew() {
        val cutKinds = listOf(
            FakeJournalCallKindV1.OPEN,
            FakeJournalCallKindV1.WRITE,
            FakeJournalCallKindV1.FSYNC,
            FakeJournalCallKindV1.CLOSE,
        )
        for (kind in cutKinds) {
            val fixture = fixture()
            fixture.fs.maximumWriteChunk = 5
            fixture.fs.addRule(
                FakeJournalRuleV1(
                    kind = kind,
                    occurrence = -1,
                    path = fixture.paths.next.value,
                    action = FakeJournalRuleActionV1.CrashAfter,
                ),
            )
            try {
                fixture.kernel.replace(
                    mutationToken(),
                    replacePlan(JournalDiskResultV1.Absent, fixture.payload),
                )
                throw AssertionError("cut $kind did not terminate the process")
            } catch (_: SimulatedProcessDeathV1) {
                val restarted = kernel(fixture.paths, fixture.fs.restart(), MutableJournalClockV1(0L))
                assertEquals(
                    "cut=$kind",
                    JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
                    restarted.read(readToken()),
                )
                assertEquals(null, fixture.disk.bytes(fixture.paths.next))
            }
        }

        val renamed = fixture()
        renamed.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.RENAME,
                occurrence = -1,
                path = renamed.paths.next.value,
                action = FakeJournalRuleActionV1.CrashAfter,
            ),
        )
        try {
            renamed.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, renamed.payload),
            )
            throw AssertionError("rename cut did not terminate the process")
        } catch (_: SimulatedProcessDeathV1) {
            val restarted = kernel(renamed.paths, renamed.fs.restart(), MutableJournalClockV1(0L))
            val outcome = restarted.read(readToken()) as JournalReadOutcomeV1.Completed
            assertEquals(renamed.payload, (outcome.diskResult as JournalPresentDiskResultV1).payload)
            assertEquals(null, renamed.disk.bytes(renamed.paths.next))
        }
    }

    @Test
    fun authorizedButNotRenamedCutRecoversOldWithoutPromotingNext() {
        val fixture = fixture()
        val fullNext = fileBytes(fixture.payload)
        fixture.disk.putFile(fixture.paths.next, fullNext)
        val token = mutationToken()
        assertTrue(token.authorizeAt(0L))

        val restarted = kernel(fixture.paths, fixture.fs.restart(), MutableJournalClockV1(0L))
        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            restarted.read(readToken()),
        )
        assertEquals(null, fixture.disk.bytes(fixture.paths.base))
        assertEquals(null, fixture.disk.bytes(fixture.paths.next))
    }

    @Test
    fun modeAndArtifactIsolationLeavesUnselectedBytesUnopened() {
        val selected = fixture()
        val artifactA = selected.paths.runtimeArtifactId
        val dualPaths = paths(artifactA, RecoveryJournalMode.DUAL)
        val otherPaths = paths(artifact(99), RecoveryJournalMode.SOLO)
        putReadyDirectories(selected.disk, dualPaths)
        putReadyDirectories(selected.disk, otherPaths)
        val dualPayload = RecoveryJournalPayloadV5.empty(artifactA, RecoveryJournalMode.DUAL)
        val otherPayload = RecoveryJournalPayloadV5.empty(otherPaths.runtimeArtifactId, RecoveryJournalMode.SOLO)
        val dualBytes = fileBytes(dualPayload)
        val otherBytes = fileBytes(otherPayload)
        selected.disk.putFile(dualPaths.base, dualBytes)
        selected.disk.putFile(otherPaths.base, otherBytes)

        assertTrue(
            selected.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, selected.payload),
            ) is JournalMutationOutcomeV1.Completed,
        )
        assertArrayEquals(dualBytes, selected.disk.bytes(dualPaths.base))
        assertArrayEquals(otherBytes, selected.disk.bytes(otherPaths.base))
        assertFalse(selected.fs.calls.any { it.path?.startsWith(dualPaths.modeRoot.value) == true })
        assertFalse(selected.fs.calls.any { it.path?.startsWith(otherPaths.artifactRoot.value) == true })
    }

    @Test
    fun closeAndCleanupFailuresNeverBecomeSuccessfulDiskResults() {
        val close = fixtureWithBase()
        close.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.CLOSE,
                occurrence = -1,
                path = close.paths.base.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertFailure(close.kernel.read(readToken()), JournalOperationFailureV1.CLOSE_FAILED)

        val cleanup = fixture()
        cleanup.disk.putFile(cleanup.paths.next, byteArrayOf(1))
        cleanup.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.REMOVE,
                occurrence = -1,
                path = cleanup.paths.next.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertTrue(cleanup.kernel.read(readToken()) is JournalReadOutcomeV1.Failed)
        assertArrayEquals(byteArrayOf(1), cleanup.disk.bytes(cleanup.paths.next))
    }

    @Test
    fun readDeadlineIncludesQueueDelayAndNeverResetsAtKernelEntry() {
        val start = 41L
        val exact = fixture(clockValue = start + JOURNAL_OPERATION_DEADLINE_NS)
        assertEquals(
            JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
            exact.kernel.read(readToken(start)),
        )

        val plusOne = fixture(clockValue = start + JOURNAL_OPERATION_DEADLINE_NS + 1L)
        assertEquals(JournalReadOutcomeV1.Expired, plusOne.kernel.read(readToken(start)))
        assertTrue(plusOne.fs.calls.isEmpty())
    }

    @Test
    fun payloadArtifactAndModeMustMatchRequestedShard() {
        val fixture = fixture()
        val wrongArtifact = RecoveryJournalPayloadV5.empty(artifact(77), RecoveryJournalMode.SOLO)
        fixture.disk.putFile(fixture.paths.base, fileBytes(wrongArtifact))
        assertFailure(fixture.kernel.read(readToken()), JournalOperationFailureV1.CODEC_REJECTED)

        val wrongMode = fixture()
        val dual = RecoveryJournalPayloadV5.empty(wrongMode.paths.runtimeArtifactId, RecoveryJournalMode.DUAL)
        wrongMode.disk.putFile(wrongMode.paths.base, fileBytes(dual))
        assertFailure(wrongMode.kernel.read(readToken()), JournalOperationFailureV1.CODEC_REJECTED)
    }

    @Test
    fun lstatOpenIdentitySwapIsRejected() {
        val fixture = fixtureWithBase()
        var baseLstats = 0
        fixture.fs.afterCallObserver = { call ->
            if (call.kind == FakeJournalCallKindV1.LSTAT && call.path == fixture.paths.base.value) {
                baseLstats += 1
                if (baseLstats == 2) {
                    fixture.disk.putFile(fixture.paths.base, fileBytes(fixture.payload))
                }
            }
        }
        assertFailure(fixture.kernel.read(readToken()), JournalOperationFailureV1.FILE_IDENTITY_CHANGED)
    }

    @Test
    fun fileAndDirectorySyncFailuresFailClosed() {
        val fileSync = fixture()
        fileSync.fs.addRule(
            FakeJournalRuleV1(
                FakeJournalCallKindV1.FSYNC,
                occurrence = -1,
                path = fileSync.paths.next.value,
                action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
            ),
        )
        assertTrue(
            fileSync.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, fileSync.payload),
            ) is JournalMutationOutcomeV1.NotCommitted,
        )
        assertFalse(fileSync.fs.calls.any { it.kind == FakeJournalCallKindV1.RENAME })

        val directorySync = fixture()
        var armed = false
        directorySync.fs.afterCallObserver = { call ->
            if (!armed && call.kind == FakeJournalCallKindV1.RENAME) {
                armed = true
                val nextFsync = directorySync.fs.calls.count { it.kind == FakeJournalCallKindV1.FSYNC } + 1
                directorySync.fs.addRule(
                    FakeJournalRuleV1(
                        FakeJournalCallKindV1.FSYNC,
                        occurrence = nextFsync,
                        path = directorySync.paths.modeRoot.value,
                        action = FakeJournalRuleActionV1.FailBefore(JournalFileSystemFailureV1.IO),
                    ),
                )
            }
        }
        assertTrue(
            directorySync.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, directorySync.payload),
            ) is JournalMutationOutcomeV1.OutcomeUnknown,
        )
        assertArrayEquals(fileBytes(directorySync.payload), directorySync.disk.bytes(directorySync.paths.base))
    }

    @Test
    fun kernelExposesOnlyFixedReadAndExclusiveCreateOpenPurposes() {
        val fixture = fixture()
        assertTrue(
            fixture.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, fixture.payload),
            ) is JournalMutationOutcomeV1.Completed,
        )
        val nextOpen = fixture.fs.opens.single {
            it.path === fixture.paths.next && it.purpose == FakeJournalOpenPurposeV1.EXCLUSIVE_CREATE
        }
        assertEquals(JOURNAL_FILE_MODE, nextOpen.mode)
        assertTrue(
            fixture.fs.opens
                .filter { it.purpose == FakeJournalOpenPurposeV1.READ_ONLY }
                .all { it.mode == 0 },
        )
        // A second direct plan sees PRESENT, so no unchecked truncate/fallback path exists.
        val base = checkNotNull(fixture.disk.bytes(fixture.paths.base))
        assertEquals(
            JournalMutationOutcomeV1.NotCommitted(JournalOperationFailureV1.EXPECTED_BASE_MISMATCH),
            fixture.kernel.replace(
                mutationToken(),
                replacePlan(JournalDiskResultV1.Absent, fixture.payload),
            ),
        )
        assertArrayEquals(base, fixture.disk.bytes(fixture.paths.base))
    }

    @Test
    fun bootstrapProcessCutsAreRestartIdempotent() {
        val cutTargets = listOf(
            FakeJournalCallKindV1.MKDIR to { f: Fixture -> f.paths.recoveryRoot.value },
            FakeJournalCallKindV1.MKDIR to { f: Fixture -> f.paths.artifactRoot.value },
            FakeJournalCallKindV1.MKDIR to { f: Fixture -> f.paths.modeRoot.value },
            FakeJournalCallKindV1.FSYNC to { f: Fixture -> f.paths.recoveryRoot.value },
            FakeJournalCallKindV1.FSYNC to { f: Fixture -> f.paths.artifactRoot.value },
            FakeJournalCallKindV1.FSYNC to { f: Fixture -> f.paths.modeRoot.value },
        )
        for ((kind, pathOf) in cutTargets) {
            val fixture = fixture(readyDirectories = false)
            fixture.fs.addRule(
                FakeJournalRuleV1(
                    kind = kind,
                    occurrence = -1,
                    path = pathOf(fixture),
                    action = FakeJournalRuleActionV1.CrashAfter,
                ),
            )
            try {
                fixture.kernel.read(readToken())
                throw AssertionError("bootstrap cut did not terminate: $kind ${pathOf(fixture)}")
            } catch (_: SimulatedProcessDeathV1) {
                val restarted = kernel(fixture.paths, fixture.fs.restart(), MutableJournalClockV1(0L))
                assertEquals(
                    JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
                    restarted.read(readToken()),
                )
            }
        }
    }

    @Test
    fun cleanupProcessCutsReinspectAndRemainOldOrAbsent() {
        for (cutAfterParentSync in listOf(false, true)) {
            val fixture = fixture()
            fixture.disk.putFile(fixture.paths.next, byteArrayOf(1, 2, 3))
            if (!cutAfterParentSync) {
                fixture.fs.addRule(
                    FakeJournalRuleV1(
                        FakeJournalCallKindV1.REMOVE,
                        occurrence = -1,
                        path = fixture.paths.next.value,
                        action = FakeJournalRuleActionV1.CrashAfter,
                    ),
                )
            } else {
                var armed = false
                fixture.fs.afterCallObserver = { call ->
                    if (!armed && call.kind == FakeJournalCallKindV1.REMOVE) {
                        armed = true
                        val nextFsync = fixture.fs.calls.count { it.kind == FakeJournalCallKindV1.FSYNC } + 1
                        fixture.fs.addRule(
                            FakeJournalRuleV1(
                                FakeJournalCallKindV1.FSYNC,
                                occurrence = nextFsync,
                                path = fixture.paths.modeRoot.value,
                                action = FakeJournalRuleActionV1.CrashAfter,
                            ),
                        )
                    }
                }
            }
            try {
                fixture.kernel.read(readToken())
                throw AssertionError("cleanup cut did not terminate")
            } catch (_: SimulatedProcessDeathV1) {
                val restarted = kernel(fixture.paths, fixture.fs.restart(), MutableJournalClockV1(0L))
                assertEquals(
                    JournalReadOutcomeV1.Completed(JournalDiskResultV1.Absent),
                    restarted.read(readToken()),
                )
                assertEquals(null, fixture.disk.bytes(fixture.paths.next))
            }
        }
    }

    @Test
    fun postRenameDirectorySyncAndBaseReadCutsRecoverWholeNew() {
        for (cutKind in listOf(FakeJournalCallKindV1.FSYNC, FakeJournalCallKindV1.READ)) {
            val fixture = fixture()
            var armed = false
            fixture.fs.afterCallObserver = { call ->
                if (!armed && call.kind == FakeJournalCallKindV1.RENAME) {
                    armed = true
                    val nextOccurrence = fixture.fs.calls.count { it.kind == cutKind } + 1
                    fixture.fs.addRule(
                        FakeJournalRuleV1(
                            kind = cutKind,
                            occurrence = nextOccurrence,
                            path = if (cutKind == FakeJournalCallKindV1.FSYNC) {
                                fixture.paths.modeRoot.value
                            } else {
                                fixture.paths.base.value
                            },
                            action = FakeJournalRuleActionV1.CrashAfter,
                        ),
                    )
                }
            }
            try {
                fixture.kernel.replace(
                    mutationToken(),
                    replacePlan(JournalDiskResultV1.Absent, fixture.payload),
                )
                throw AssertionError("post-rename cut did not terminate: $cutKind")
            } catch (_: SimulatedProcessDeathV1) {
                val restarted = kernel(fixture.paths, fixture.fs.restart(), MutableJournalClockV1(0L))
                val result = restarted.read(readToken()) as JournalReadOutcomeV1.Completed
                assertEquals(fixture.payload, (result.diskResult as JournalPresentDiskResultV1).payload)
                assertEquals(null, fixture.disk.bytes(fixture.paths.next))
            }
        }
    }

    private data class Fixture(
        val paths: JournalPathsV1,
        val payload: RecoveryJournalPayloadV5,
        val disk: FakeJournalDiskV1,
        val fs: FakeJournalFileSystemV1,
        val clock: MutableJournalClockV1,
        val kernel: CheckedAtomicReplaceV1,
    )

    private class RecordingReceiptBridgeV1 : PendingStoreReceiptConsumingReducerBridgeV1 {
        val manualRetrySaveNotCommitted = AtomicInteger(0)
        val committedFinalClear = AtomicInteger(0)

        override fun consumeManualRetrySaveNotCommitted(receipt: StrictModeStoreIdentityReceiptV1) {
            manualRetrySaveNotCommitted.incrementAndGet()
        }

        override fun consumeCommittedFinalClear(receipt: StrictModeStoreIdentityReceiptV1) {
            committedFinalClear.incrementAndGet()
        }
    }

    private fun fixture(
        readyDirectories: Boolean = true,
        clockValue: Long = 0L,
        seed: Int = 1,
        mode: RecoveryJournalMode = RecoveryJournalMode.SOLO,
    ): Fixture {
        val artifact = artifact(seed)
        val paths = paths(artifact, mode)
        val payload = RecoveryJournalPayloadV5.empty(artifact, mode)
        val disk = FakeJournalDiskV1()
        disk.putDirectory(paths.noBackupRoot)
        if (readyDirectories) putReadyDirectories(disk, paths)
        val fs = FakeJournalFileSystemV1(disk)
        val clock = MutableJournalClockV1(clockValue)
        return Fixture(paths, payload, disk, fs, clock, kernel(paths, fs, clock))
    }

    private fun fixtureWithBase(): Fixture =
        fixture().also { it.disk.putFile(it.paths.base, fileBytes(it.payload)) }

    private fun pendingHandle(
        fixture: Fixture,
        pending: PendingStoreCommitV5,
    ): ValidatedCurrentPendingHandleV1 {
        val payload = fixture.payload.copy(
            lastEpoch = pending.attemptEpoch,
            pendingStoreCommit = pending,
        )
        fixture.disk.putFile(fixture.paths.base, fileBytes(payload))
        val outcome = fixture.kernel.read(readToken()) as JournalReadOutcomeV1.Completed
        assertEquals(payload, (outcome.diskResult as JournalPresentDiskResultV1).payload)
        return requireNotNull(outcome.validatedCurrentPending)
    }

    private fun kernel(
        paths: JournalPathsV1,
        fs: FakeJournalFileSystemV1,
        clock: MutableJournalClockV1,
    ): CheckedAtomicReplaceV1 {
        val issuerIdentity = Any()
        val generation = 1L
        val authority = constructPrivate(
            JournalStorageAuthorityV1::class.java,
            issuerIdentity,
            generation,
            paths,
        )
        currentBinding = TestAuthorityBinding(
            authority = authority,
            issuerIdentity = issuerIdentity,
            generation = generation,
            artifact = paths.runtimeArtifactId,
            mode = paths.mode,
            paths = paths,
        )
        return constructPrivate(
            CheckedAtomicReplaceV1::class.java,
            paths,
            fs,
            clock,
            authority,
        )
    }

    private fun paths(
        artifact: RuntimeArtifactId,
        mode: RecoveryJournalMode,
    ): JournalPathsV1 = pathsAtRoot("/private/no_backup", artifact, mode)

    private fun pathsAtRoot(
        rootValue: String,
        artifact: RuntimeArtifactId,
        mode: RecoveryJournalMode,
    ): JournalPathsV1 {
        val rootPath = JournalPathV1.canonicalAbsolute(rootValue)
        val frozenRoot = constructPrivate(
            JournalFrozenNoBackupRootV1::class.java,
            rootPath,
            JournalDirectoryIdentityV1(device = 1L, inode = 10L),
            "fake-posix",
        )
        return JournalPathsV1.create(frozenRoot, artifact, mode)
    }

    private fun putReadyDirectories(
        disk: FakeJournalDiskV1,
        paths: JournalPathsV1,
    ) {
        disk.putDirectory(paths.recoveryRoot)
        disk.putDirectory(paths.artifactRoot)
        disk.putDirectory(paths.modeRoot)
    }

    private fun entry(
        fixture: Fixture,
        name: String,
    ): JournalDirectoryEntryV1 =
        JournalDirectoryEntryV1(fixture.fs.providerKey, fixture.paths.modeRoot, name)

    private fun readToken(start: Long = 0L): JournalReadTokenV1 {
        return readTokenWithDeadline(deadline(start))
    }

    private fun readTokenWithDeadline(deadline: JournalOperationDeadlineV1): JournalReadTokenV1 {
        val binding = currentOrStandaloneBinding()
        return constructPrivate(
            JournalReadTokenV1::class.java,
            deadline,
            binding.issuerIdentity,
            binding.generation,
            binding.artifact.digest,
            binding.mode,
            binding.paths,
        )
    }

    private fun mutationToken(start: Long = 0L): JournalMutationTokenV1 {
        return mutationTokenWithDeadline(deadline(start))
    }

    private fun mutationTokenWithDeadline(deadline: JournalOperationDeadlineV1): JournalMutationTokenV1 {
        val binding = currentOrStandaloneBinding()
        val planIdentity = Any()
        currentPlanIdentity = planIdentity
        return constructPrivate(
            JournalMutationTokenV1::class.java,
            deadline,
            binding.issuerIdentity,
            binding.generation,
            binding.artifact.digest,
            binding.mode,
            binding.paths,
            planIdentity,
        )
    }

    private fun replacePlan(
        expected: JournalDiskResultV1,
        intended: RecoveryJournalPayloadV5,
    ): JournalReplacePlanV1 {
        val binding = currentOrStandaloneBinding()
        val planIdentity = requireNotNull(currentPlanIdentity)
        return constructPrivate(
            JournalReplacePlanV1::class.java,
            expected,
            intended,
            binding.issuerIdentity,
            binding.generation,
            binding.artifact.digest,
            binding.mode,
            binding.paths,
            planIdentity,
        )
    }

    private fun deadline(start: Long): JournalOperationDeadlineV1 =
        constructPrivate(
            JournalOperationDeadlineV1::class.java,
            start,
            Math.addExact(start, JOURNAL_OPERATION_DEADLINE_NS),
        )

    private fun currentOrStandaloneBinding(): TestAuthorityBinding =
        currentBinding ?: run {
            val artifact = artifact(1)
            val issuerIdentity = Any()
            val generation = 1L
            val paths = paths(artifact, RecoveryJournalMode.SOLO)
            TestAuthorityBinding(
                authority = constructPrivate(
                    JournalStorageAuthorityV1::class.java,
                    issuerIdentity,
                    generation,
                    paths,
                ),
                issuerIdentity = issuerIdentity,
                generation = generation,
                artifact = artifact,
                mode = RecoveryJournalMode.SOLO,
                paths = paths,
            ).also { currentBinding = it }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> constructPrivate(
        type: Class<T>,
        vararg arguments: Any?,
    ): T {
        val constructor = type.declaredConstructors.single { it.parameterCount == arguments.size }
        constructor.isAccessible = true
        return constructor.newInstance(*arguments) as T
    }

    private fun artifact(seed: Int): RuntimeArtifactId = RuntimeArtifactId(digest(seed))

    private fun digest(seed: Int): Sha256Digest =
        valid(Sha256Digest.fromBytes(ByteArray(32) { (seed + it).toByte() }))

    private fun pending(
        expectedOldState: ExpectedOldFileState,
        expectedOldLength: ULong = 0uL,
        expectedOldHash: Sha256Digest? = null,
        intendedLength: ULong = 113uL,
        intendedHash: Sha256Digest = digest(45),
        intendedRecordHash: Sha256Digest = digest(46),
    ): PendingStoreCommitV5 =
        PendingStoreCommitV5(
            probeBaseScopeId = ProbeBaseScopeId(digest(40)),
            attemptEpoch = 1uL,
            capabilityResultId = CapabilityResultId(digest(41)),
            selectedDelegate = ProbeDelegate.CPU,
            retryUsed = false,
            retryContextId = null,
            expectedOldFileState = expectedOldState,
            expectedOldFileLength = expectedOldLength,
            expectedOldFileSha256 = expectedOldHash,
            intendedNewFileLength = intendedLength,
            intendedNewFileSha256 = intendedHash,
            intendedNewRecordSha256 = intendedRecordHash,
        )

    private fun strictObservation(
        binding: TestAuthorityBinding,
        fileState: StrictModeStoreFileStateV1,
        completeFileLength: ULong,
        completeFileSha256: Sha256Digest?,
        embeddedRecordSha256: Sha256Digest?,
    ): StrictModeStoreObservationV1 =
        constructPrivate(
            StrictModeStoreObservationV1::class.java,
            binding.authority,
            binding.generation,
            binding.paths,
            binding.paths.frozenNoBackupRoot,
            binding.artifact.digest,
            binding.mode,
            7L,
            11L,
            fileState,
            completeFileLength.toLong(),
            completeFileSha256,
            embeddedRecordSha256,
        )

    private fun fileBytes(payload: RecoveryJournalPayloadV5): ByteArray =
        valid(RecoveryJournalV5Codec.encodeFile(payload))

    private fun present(
        bytes: ByteArray,
        payload: RecoveryJournalPayloadV5,
    ): JournalPresentDiskResultV1 =
        JournalPresentDiskResultV1(
            completeFileLength = bytes.size,
            completeFileSha256 = valid(Sha256Digest.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))),
            completeFileBytes = bytes,
            payload = payload,
        )

    private fun assertFailure(
        outcome: JournalReadOutcomeV1,
        expected: JournalOperationFailureV1,
    ) {
        assertEquals(JournalReadOutcomeV1.Failed(expected), outcome)
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error(result.toString())
        }
}
