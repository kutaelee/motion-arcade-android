package com.motionarcade.app.capability.artifact

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityIdentity
import com.motionarcade.vision.capability.domain.RuntimeArtifactEntryV1
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledArtifactIdentityReaderTest {
    @Test
    fun exactBaseAndIndexPairedSplitsProduceDomainIdentityWithoutPaths() {
        val snapshot = snapshot(
            names = listOf("feature_b", "feature_a"),
            paths = listOf("/data/app/split-b.apk", "/data/app/split-a.apk"),
        )
        val reads = mapOf(
            "/data/app/base.apk" to read(seed = 1, length = 101uL, inode = 11L),
            "/data/app/split-b.apk" to read(seed = 2, length = 202uL, inode = 12L),
            "/data/app/split-a.apk" to read(seed = 3, length = 303uL, inode = 13L),
        )
        val reader = reader(snapshot, files = fakeFiles(reads))

        val verified = reader.read() as InstalledArtifactIdentityResult.Verified

        val expected = valid(
            CapabilityIdentity.runtimeArtifactId(
                listOf(
                    RuntimeArtifactEntryV1("base", 101uL, digest(1)),
                    RuntimeArtifactEntryV1("feature_b", 202uL, digest(2)),
                    RuntimeArtifactEntryV1("feature_a", 303uL, digest(3)),
                ),
            ),
        )
        assertEquals(expected, verified.runtimeArtifactId)
        assertEquals(listOf("base", "feature_b", "feature_a"), verified.entries.map { it.logicalName })
        assertFalse(verified.toString().contains("/data/app"))
        assertFalse(verified.entries.toString().contains("/data/app"))
    }

    @Test
    fun readerIsOneShotEvenAfterSuccessfulRead() {
        val reader = reader(snapshot(), files = fakeFiles(baseRead()))
        assertTrue(reader.read() is InstalledArtifactIdentityResult.Verified)
        assertIncomplete(reader.read(), InstalledArtifactIdentityFailure.ALREADY_CONSUMED)
    }

    @Test
    fun packageMetadataMustRemainByteEquivalentAcrossCapture() {
        val first = snapshot()
        val second = first.copy(baseSourceDir = "/data/app/replaced.apk", basePublicSourceDir = "/data/app/replaced.apk")
        val source = queuedSnapshots(first, second)
        val reader = reader(source, fakeFiles(baseRead()))

        assertIncomplete(reader.read(), InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
    }

    @Test
    fun splitArraysMustBeAllAbsentOrIndexPairedWithPublicPaths() {
        val cases = listOf(
            snapshot(names = listOf("x"), paths = null, publicPaths = null),
            snapshot(names = listOf("x"), paths = listOf("/data/app/x.apk"), publicPaths = null),
            snapshot(names = listOf("x"), paths = emptyList(), publicPaths = emptyList()),
            snapshot(names = listOf("x"), paths = listOf("/data/app/x.apk"), publicPaths = listOf("/data/app/y.apk")),
        )

        cases.forEach { value ->
            val result = reader(value, files = fakeFiles(baseRead())).read()
            assertIncomplete(result, InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
        }
    }

    @Test
    fun duplicateNamesPathsAndFileIdentitiesAreRejected() {
        val duplicateNames = snapshot(
            names = listOf("x", "x"),
            paths = listOf("/data/app/x.apk", "/data/app/y.apk"),
        )
        assertIncomplete(
            reader(duplicateNames, fakeFiles(baseRead())).read(),
            InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS,
        )

        val duplicatePaths = snapshot(
            names = listOf("x", "y"),
            paths = listOf("/data/app/x.apk", "/data/app/x.apk"),
        )
        assertIncomplete(
            reader(duplicatePaths, fakeFiles(baseRead())).read(),
            InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS,
        )

        val sameInode = snapshot(names = listOf("x"), paths = listOf("/data/app/x.apk"))
        assertIncomplete(
            reader(
                sameInode,
                fakeFiles(
                    mapOf(
                        "/data/app/base.apk" to read(1, 100uL, inode = 7L),
                        "/data/app/x.apk" to read(2, 200uL, inode = 7L),
                    ),
                ),
            ).read(),
            InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS,
        )
    }

    @Test
    fun baseAndSplitMetadataRejectRelativeBlankReservedOrMalformedValues() {
        val malformed = "bad\uD800"
        val cases = listOf(
            snapshot().copy(baseSourceDir = "relative.apk", basePublicSourceDir = "relative.apk"),
            snapshot().copy(basePublicSourceDir = "/data/app/other.apk"),
            snapshot(names = listOf(""), paths = listOf("/data/app/x.apk")),
            snapshot(names = listOf("base"), paths = listOf("/data/app/x.apk")),
            snapshot(names = listOf(malformed), paths = listOf("/data/app/x.apk")),
            snapshot(names = listOf("x"), paths = listOf("relative.apk")),
        )
        cases.forEach { value ->
            assertIncomplete(
                reader(value, fakeFiles(baseRead())).read(),
                InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS,
            )
        }
    }

    @Test
    fun clockRegressionAndStrictDeadlineExpiryFailClosed() {
        val regressionReader = reader(
            snapshot(),
            fakeFiles(baseRead(), observeInsideRead = true),
            clock = SequenceClock(0L, 100L, 50L),
        )
        assertIncomplete(regressionReader.read(), InstalledArtifactIdentityFailure.INVALID_CLOCK)

        val expiryReader = reader(
            snapshot(),
            fakeFiles(baseRead(), observeInsideRead = true),
            clock = SequenceClock(0L, 5_000_000_000L, 5_000_000_001L),
        )
        assertIncomplete(expiryReader.read(), InstalledArtifactIdentityFailure.DEADLINE_EXPIRED)
    }

    @Test
    fun deadlineStartOverflowIsInvalidClock() {
        val overflow = reader(
            snapshot(),
            fakeFiles(baseRead()),
            clock = ConstantClock(Long.MAX_VALUE - 1L),
        )
        assertIncomplete(overflow.read(), InstalledArtifactIdentityFailure.INVALID_CLOCK)
    }

    @Test
    fun fileFailureIsReturnedWithoutLeakingItsPath() {
        val reader = reader(
            snapshot(),
            StableApkSource { _, _ ->
                abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
            },
        )

        val result = reader.read()

        assertIncomplete(result, InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        assertFalse(result.toString().contains("/data/app"))
    }

    @Test
    fun stableSetKeepsEverySessionOpenThroughPathRebindingThenClosesInReverse() {
        val value = snapshot(names = listOf("feature"), paths = listOf("/data/app/feature.apk"))
        val reads = mapOf(
            "/data/app/base.apk" to read(seed = 1, length = 100uL, inode = 10L),
            "/data/app/feature.apk" to read(seed = 2, length = 200uL, inode = 11L),
        )
        val events = mutableListOf<String>()
        val source = recordingFiles(reads, events)

        assertTrue(reader(value, source).read() is InstalledArtifactIdentityResult.Verified)

        assertEquals(
            listOf(
                "open:/data/app/base.apk",
                "open:/data/app/feature.apk",
                "read:/data/app/base.apk",
                "read:/data/app/feature.apk",
                "confirm:/data/app/base.apk",
                "confirm:/data/app/feature.apk",
                "close:/data/app/feature.apk",
                "close:/data/app/base.apk",
            ),
            events,
        )
    }

    @Test
    fun samePathReplacementAfterHashRejectsWholeSetAndClosesEverySession() {
        val value = snapshot(names = listOf("feature"), paths = listOf("/data/app/feature.apk"))
        val reads = mapOf(
            "/data/app/base.apk" to read(seed = 1, length = 100uL, inode = 10L),
            "/data/app/feature.apk" to read(seed = 2, length = 200uL, inode = 11L),
        )
        val events = mutableListOf<String>()
        val source = recordingFiles(
            reads = reads,
            events = events,
            failConfirmPath = "/data/app/base.apk",
        )

        assertIncomplete(
            reader(value, source).read(),
            InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ,
        )
        assertTrue("close:/data/app/base.apk" in events)
        assertTrue("close:/data/app/feature.apk" in events)
        assertFalse("confirm:/data/app/feature.apk" in events)
    }

    @Test
    fun closeFailurePreventsVerifiedResultAfterOtherwiseStableSet() {
        val events = mutableListOf<String>()
        val source = recordingFiles(
            reads = baseRead(),
            events = events,
            failClosePath = "/data/app/base.apk",
        )

        assertIncomplete(
            reader(snapshot(), source).read(),
            InstalledArtifactIdentityFailure.FILE_CLOSE_FAILED,
        )
        assertEquals("close:/data/app/base.apk", events.last())
    }

    @Test
    fun exactFiveSecondDeadlineIsAdmittedButOneNanosecondLateIsRejected() {
        val equality = reader(
            snapshot(),
            fakeFiles(baseRead()),
            clock = SequenceClock(
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                5_000_000_000L,
            ),
        )
        assertTrue(equality.read() is InstalledArtifactIdentityResult.Verified)

        val expired = reader(
            snapshot(),
            fakeFiles(baseRead()),
            clock = SequenceClock(
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                5_000_000_001L,
            ),
        )
        assertIncomplete(expired.read(), InstalledArtifactIdentityFailure.DEADLINE_EXPIRED)
    }

    private fun reader(
        snapshot: PackageArtifactSnapshot,
        files: StableApkSource,
        clock: MonotonicClock = ConstantClock(0L),
    ): InstalledArtifactIdentityReader =
        reader(queuedSnapshots(snapshot, snapshot, snapshot), files, clock)

    private fun reader(
        source: PackageSnapshotSource,
        files: StableApkSource,
        clock: MonotonicClock = ConstantClock(0L),
    ): InstalledArtifactIdentityReader = InstalledArtifactIdentityReader(
        packageSnapshots = source,
        files = files,
        clock = clock,
        enforceWorkerThread = false,
    )

    private fun snapshot(
        names: List<String>? = null,
        paths: List<String>? = null,
        publicPaths: List<String>? = paths,
    ): PackageArtifactSnapshot = PackageArtifactSnapshot(
        packageName = "com.motionarcade.app.debug",
        baseSourceDir = "/data/app/base.apk",
        basePublicSourceDir = "/data/app/base.apk",
        splitNames = names,
        splitSourceDirs = paths,
        splitPublicSourceDirs = publicPaths,
    )

    private fun queuedSnapshots(vararg snapshots: PackageArtifactSnapshot): PackageSnapshotSource {
        val queue = ArrayDeque(snapshots.toList())
        return PackageSnapshotSource {
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private fun fakeFiles(
        reads: Map<String, StableApkRead>,
        observeInsideRead: Boolean = false,
    ): StableApkSource = StableApkSource { path, _ ->
        object : StableApkSession {
            override fun read(deadline: IdentityDeadline): StableApkRead {
                if (observeInsideRead) deadline.observe()
                return reads[path] ?: abort(InstalledArtifactIdentityFailure.FILE_OPEN_FAILED)
            }

            override fun confirmCurrentPath(deadline: IdentityDeadline) = Unit

            override fun close() = Unit
        }
    }

    private fun recordingFiles(
        reads: Map<String, StableApkRead>,
        events: MutableList<String>,
        failConfirmPath: String? = null,
        failClosePath: String? = null,
    ): StableApkSource = StableApkSource { path, _ ->
        events += "open:$path"
        object : StableApkSession {
            override fun read(deadline: IdentityDeadline): StableApkRead {
                events += "read:$path"
                return reads[path] ?: abort(InstalledArtifactIdentityFailure.FILE_OPEN_FAILED)
            }

            override fun confirmCurrentPath(deadline: IdentityDeadline) {
                events += "confirm:$path"
                if (path == failConfirmPath) {
                    abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
                }
            }

            override fun close() {
                events += "close:$path"
                if (path == failClosePath) throw IllegalStateException("close failed")
            }
        }
    }

    private fun baseRead(): Map<String, StableApkRead> =
        mapOf("/data/app/base.apk" to read(seed = 1, length = 100uL, inode = 10L))

    private fun read(seed: Int, length: ULong, inode: Long): StableApkRead =
        StableApkRead(length, digest(seed), device = 1L, inode = inode)

    private fun digest(seed: Int): Sha256Digest = valid(Sha256Digest.fromBytes(ByteArray(32) { seed.toByte() }))

    private fun assertIncomplete(
        result: InstalledArtifactIdentityResult,
        expected: InstalledArtifactIdentityFailure,
    ) {
        assertTrue(result is InstalledArtifactIdentityResult.Incomplete)
        assertEquals(expected, (result as InstalledArtifactIdentityResult.Incomplete).reason)
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error(result.toString())
        }

    private class ConstantClock(private val value: Long) : MonotonicClock {
        override fun nowNs(): Long = value
    }

    private class SequenceClock(vararg values: Long) : MonotonicClock {
        private val queue = ArrayDeque(values.toList())
        private var last = values.last()

        override fun nowNs(): Long {
            if (queue.isNotEmpty()) last = queue.removeFirst()
            return last
        }
    }
}
