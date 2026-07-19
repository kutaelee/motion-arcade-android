package com.motionarcade.app.capability.artifact

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.annotation.RequiresApi
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityIdentity
import com.motionarcade.vision.capability.domain.RuntimeArtifactEntryV1
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.io.FileDescriptor
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class InstalledArtifactIdentityFailure {
    ALREADY_CONSUMED,
    MAIN_THREAD_FORBIDDEN,
    PACKAGE_METADATA_UNAVAILABLE,
    PACKAGE_METADATA_AMBIGUOUS,
    INVALID_CLOCK,
    DEADLINE_EXPIRED,
    FILE_OPEN_FAILED,
    FILE_NOT_REGULAR,
    FILE_READ_FAILED,
    FILE_CHANGED_DURING_READ,
    FILE_CLOSE_FAILED,
    DOMAIN_REJECTED,
}

sealed interface InstalledArtifactEvidence {
    val logicalName: String
    val apkLength: ULong
    val apkSha256: Sha256Digest
}

sealed interface InstalledArtifactIdentityResult {
    /** Data evidence only. This interface is not a native-create authorization token. */
    sealed interface Verified : InstalledArtifactIdentityResult {
        val runtimeArtifactId: RuntimeArtifactId
        val entries: List<InstalledArtifactEvidence>
    }

    data class Incomplete(
        val reason: InstalledArtifactIdentityFailure,
    ) : InstalledArtifactIdentityResult
}

private class ReadInstalledArtifactEvidence(
    override val logicalName: String,
    override val apkLength: ULong,
    override val apkSha256: Sha256Digest,
) : InstalledArtifactEvidence

private class VerifiedInstalledArtifactIdentity(
    override val runtimeArtifactId: RuntimeArtifactId,
    entries: List<InstalledArtifactEvidence>,
) : InstalledArtifactIdentityResult.Verified {
    override val entries: List<InstalledArtifactEvidence> =
        Collections.unmodifiableList(ArrayList(entries))
}

/**
 * One-shot, fail-closed installed APK identity reader.
 *
 * Raw PackageManager paths are used only as transient open operands. They are never returned,
 * persisted, or placed in an exception/result message. All exact base/split descriptors remain
 * open as one set while each file is read twice, PackageManager is recaptured, and every path is
 * rebound to its original descriptor identity. No result is returned until every descriptor closes.
 */
class AndroidInstalledArtifactIdentityReader(context: Context) {
    private val delegate = InstalledArtifactIdentityReader(
        packageSnapshots = AndroidPackageSnapshotSource(context.applicationContext),
        files = AndroidStableApkSource,
        clock = MonotonicClock { SystemClock.elapsedRealtimeNanos() },
        enforceWorkerThread = true,
    )

    fun read(): InstalledArtifactIdentityResult = delegate.read()
}

internal data class PackageArtifactSnapshot(
    val packageName: String,
    val baseSourceDir: String?,
    val basePublicSourceDir: String?,
    val splitNames: List<String>?,
    val splitSourceDirs: List<String>?,
    val splitPublicSourceDirs: List<String>?,
)

internal fun interface PackageSnapshotSource {
    fun capture(): PackageArtifactSnapshot
}

internal fun interface MonotonicClock {
    fun nowNs(): Long
}

internal data class StableApkRead(
    val length: ULong,
    val sha256: Sha256Digest,
    val device: Long,
    val inode: Long,
)

internal interface StableApkSession {
    fun read(deadline: IdentityDeadline): StableApkRead

    fun confirmCurrentPath(deadline: IdentityDeadline)

    fun close()
}

internal fun interface StableApkSource {
    fun open(path: String, deadline: IdentityDeadline): StableApkSession
}

internal class InstalledArtifactIdentityReader(
    private val packageSnapshots: PackageSnapshotSource,
    private val files: StableApkSource,
    private val clock: MonotonicClock,
    private val enforceWorkerThread: Boolean,
) {
    private val consumed = AtomicBoolean(false)

    fun read(): InstalledArtifactIdentityResult {
        if (!consumed.compareAndSet(false, true)) {
            return incomplete(InstalledArtifactIdentityFailure.ALREADY_CONSUMED)
        }
        if (enforceWorkerThread && Looper.myLooper() == Looper.getMainLooper()) {
            return incomplete(InstalledArtifactIdentityFailure.MAIN_THREAD_FORBIDDEN)
        }

        val deadline = try {
            IdentityDeadline.start(clock)
        } catch (failure: ArtifactReadAbort) {
            return incomplete(failure.reason)
        }
        return try {
            deadline.observe()
            val before = packageSnapshots.capture()
            val planned = validateSnapshot(before)
            val stableSet = readStableSet(before, planned, deadline)
            val domainEntries = stableSet.map { (item, read) ->
                RuntimeArtifactEntryV1(item.logicalName, read.length, read.sha256)
            }
            val evidence = stableSet.mapTo(ArrayList(stableSet.size)) { (item, read) ->
                ReadInstalledArtifactEvidence(item.logicalName, read.length, read.sha256)
            }
            val id = when (val result = CapabilityIdentity.runtimeArtifactId(domainEntries)) {
                is CapabilityDomainResult.Valid -> result.value
                is CapabilityDomainResult.Invalid ->
                    abort(InstalledArtifactIdentityFailure.DOMAIN_REJECTED)
            }
            deadline.observe()
            VerifiedInstalledArtifactIdentity(id, evidence)
        } catch (failure: ArtifactReadAbort) {
            incomplete(failure.reason)
        } catch (_: SecurityException) {
            incomplete(InstalledArtifactIdentityFailure.PACKAGE_METADATA_UNAVAILABLE)
        } catch (_: Exception) {
            incomplete(InstalledArtifactIdentityFailure.PACKAGE_METADATA_UNAVAILABLE)
        }
    }

    private fun readStableSet(
        before: PackageArtifactSnapshot,
        planned: List<PlannedArtifact>,
        deadline: IdentityDeadline,
    ): List<Pair<PlannedArtifact, StableApkRead>> {
        val opened = ArrayList<Pair<PlannedArtifact, StableApkSession>>(planned.size)
        var primaryFailure: ArtifactReadAbort? = null
        var result: List<Pair<PlannedArtifact, StableApkRead>>? = null
        try {
            planned.forEach { item ->
                deadline.observe()
                opened += item to files.open(item.path, deadline)
            }

            val identities = HashSet<Pair<Long, Long>>()
            val reads = ArrayList<Pair<PlannedArtifact, StableApkRead>>(opened.size)
            opened.forEach { (item, session) ->
                deadline.observe()
                val read = session.read(deadline)
                if (!identities.add(read.device to read.inode)) {
                    abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
                }
                reads += item to read
            }

            deadline.observe()
            if (before != packageSnapshots.capture()) {
                abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
            }
            opened.forEach { (_, session) ->
                deadline.observe()
                session.confirmCurrentPath(deadline)
            }
            deadline.observe()
            if (before != packageSnapshots.capture()) {
                abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
            }
            result = reads
        } catch (failure: ArtifactReadAbort) {
            primaryFailure = failure
        } catch (_: Exception) {
            primaryFailure = ArtifactReadAbort(
                InstalledArtifactIdentityFailure.PACKAGE_METADATA_UNAVAILABLE,
            )
        } finally {
            var closeFailed = false
            opened.asReversed().forEach { (_, session) ->
                try {
                    session.close()
                } catch (_: Exception) {
                    closeFailed = true
                }
            }
            if (primaryFailure == null && closeFailed) {
                primaryFailure = ArtifactReadAbort(
                    InstalledArtifactIdentityFailure.FILE_CLOSE_FAILED,
                )
            }
        }
        primaryFailure?.let { throw it }
        return checkNotNull(result)
    }

    private fun validateSnapshot(snapshot: PackageArtifactSnapshot): List<PlannedArtifact> {
        if (snapshot.packageName.isBlank()) {
            abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
        }
        val base = snapshot.baseSourceDir
        if (base.isNullOrBlank() || base != snapshot.basePublicSourceDir || !base.startsWith('/')) {
            abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
        }

        val names = snapshot.splitNames
        val paths = snapshot.splitSourceDirs
        val publicPaths = snapshot.splitPublicSourceDirs
        val allAbsent = names == null && paths == null && publicPaths == null
        val allPresent = names != null && paths != null && publicPaths != null
        if (!allAbsent && !allPresent) {
            abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
        }
        if (allAbsent) return listOf(PlannedArtifact(BASE_LOGICAL_NAME, base))

        checkNotNull(names)
        checkNotNull(paths)
        checkNotNull(publicPaths)
        if (names.size != paths.size || paths.size != publicPaths.size || names.size > MAX_SPLITS) {
            abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
        }
        val logicalNames = HashSet<String>()
        val rawPaths = HashSet<String>()
        rawPaths += base
        val result = ArrayList<PlannedArtifact>(names.size + 1)
        result += PlannedArtifact(BASE_LOGICAL_NAME, base)
        names.indices.forEach { index ->
            val name = names[index]
            val path = paths[index]
            if (name.isBlank() || name == BASE_LOGICAL_NAME || !isStrictUtf8(name) ||
                path.isBlank() || !path.startsWith('/') || path != publicPaths[index] ||
                !logicalNames.add(name) || !rawPaths.add(path)
            ) {
                abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
            }
            result += PlannedArtifact(name, path)
        }
        return result
    }

    private fun isStrictUtf8(value: String): Boolean =
        try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
            true
        } catch (_: Exception) {
            false
        }

    private data class PlannedArtifact(val logicalName: String, val path: String)

    private companion object {
        const val BASE_LOGICAL_NAME = "base"
        const val MAX_SPLITS = 128
    }
}

internal class IdentityDeadline private constructor(
    private val clock: MonotonicClock,
    private val deadlineNs: Long,
    initialNs: Long,
) {
    private val lastObservedNs = AtomicLong(initialNs)

    fun observe(): Long {
        val now = try {
            clock.nowNs()
        } catch (_: Exception) {
            abort(InstalledArtifactIdentityFailure.INVALID_CLOCK)
        }
        while (true) {
            val previous = lastObservedNs.get()
            if (now < 0L || now < previous) {
                abort(InstalledArtifactIdentityFailure.INVALID_CLOCK)
            }
            if (lastObservedNs.compareAndSet(previous, now)) break
        }
        if (now > deadlineNs) abort(InstalledArtifactIdentityFailure.DEADLINE_EXPIRED)
        return now
    }

    companion object {
        fun start(clock: MonotonicClock): IdentityDeadline {
            val start = try {
                clock.nowNs()
            } catch (_: Exception) {
                abort(InstalledArtifactIdentityFailure.INVALID_CLOCK)
            }
            if (start < 0L) abort(InstalledArtifactIdentityFailure.INVALID_CLOCK)
            val deadline = try {
                Math.addExact(start, IDENTITY_TIMEOUT_NS)
            } catch (_: ArithmeticException) {
                abort(InstalledArtifactIdentityFailure.INVALID_CLOCK)
            }
            return IdentityDeadline(clock, deadline, start)
        }

        private const val IDENTITY_TIMEOUT_NS = 5_000_000_000L
    }
}

private class AndroidPackageSnapshotSource(context: Context) : PackageSnapshotSource {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    override fun capture(): PackageArtifactSnapshot {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            Api33ApplicationInfo.get(packageManager, packageName)
        } else {
            legacyApplicationInfo(packageManager, packageName)
        }
        if (info.packageName != packageName) {
            abort(InstalledArtifactIdentityFailure.PACKAGE_METADATA_AMBIGUOUS)
        }
        return PackageArtifactSnapshot(
            packageName = info.packageName,
            baseSourceDir = info.sourceDir,
            basePublicSourceDir = info.publicSourceDir,
            splitNames = info.splitNames?.toList(),
            splitSourceDirs = info.splitSourceDirs?.toList(),
            splitPublicSourceDirs = info.splitPublicSourceDirs?.toList(),
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyApplicationInfo(
        packageManager: PackageManager,
        packageName: String,
    ): ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
}

@RequiresApi(33)
private object Api33ApplicationInfo {
    fun get(packageManager: PackageManager, packageName: String): ApplicationInfo =
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0L),
        )
}

internal object AndroidStableApkSource : StableApkSource {
    override fun open(path: String, deadline: IdentityDeadline): StableApkSession {
        deadline.observe()
        val descriptor = try {
            Os.open(path, AndroidApkFileOps.openFlags(), 0)
        } catch (_: Exception) {
            abort(InstalledArtifactIdentityFailure.FILE_OPEN_FAILED)
        }
        return AndroidStableApkSession(path, descriptor)
    }
}

private class AndroidStableApkSession(
    private val path: String,
    private val descriptor: FileDescriptor,
) : StableApkSession {
    private var frozenStat: StructStat? = null
    private var closed = false

    override fun read(deadline: IdentityDeadline): StableApkRead {
        if (closed || frozenStat != null) {
            abort(InstalledArtifactIdentityFailure.FILE_READ_FAILED)
        }
        val before = AndroidApkFileOps.stat(descriptor)
        AndroidApkFileOps.validateRegular(before)
        val first = AndroidApkFileOps.digestPass(descriptor, before.st_size, deadline)
        deadline.observe()
        if (try {
                Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
            } catch (_: Exception) {
                abort(InstalledArtifactIdentityFailure.FILE_READ_FAILED)
            } != 0L
        ) {
            abort(InstalledArtifactIdentityFailure.FILE_READ_FAILED)
        }
        val second = AndroidApkFileOps.digestPass(descriptor, before.st_size, deadline)
        val after = AndroidApkFileOps.stat(descriptor)
        if (!AndroidApkFileOps.sameIdentity(before, after) ||
            !MessageDigest.isEqual(first, second)
        ) {
            abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        }
        val digest = when (val parsed = Sha256Digest.fromBytes(first)) {
            is CapabilityDomainResult.Valid -> parsed.value
            is CapabilityDomainResult.Invalid ->
                abort(InstalledArtifactIdentityFailure.FILE_READ_FAILED)
        }
        frozenStat = after
        return StableApkRead(
            length = before.st_size.toULong(),
            sha256 = digest,
            device = before.st_dev,
            inode = before.st_ino,
        )
    }

    override fun confirmCurrentPath(deadline: IdentityDeadline) {
        if (closed) abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        val expected = frozenStat
            ?: abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        deadline.observe()
        if (!AndroidApkFileOps.sameIdentity(expected, AndroidApkFileOps.stat(descriptor))) {
            abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        }
        val currentDescriptor = try {
            Os.open(path, AndroidApkFileOps.openFlags(), 0)
        } catch (_: Exception) {
            abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        }
        var primaryFailure: ArtifactReadAbort? = null
        try {
            val current = AndroidApkFileOps.stat(currentDescriptor)
            AndroidApkFileOps.validateRegular(current)
            if (!AndroidApkFileOps.sameIdentity(expected, current)) {
                abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
            }
        } catch (failure: ArtifactReadAbort) {
            primaryFailure = failure
        } catch (_: Exception) {
            primaryFailure = ArtifactReadAbort(
                InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ,
            )
        } finally {
            try {
                Os.close(currentDescriptor)
            } catch (_: Exception) {
                if (primaryFailure == null) {
                    primaryFailure = ArtifactReadAbort(
                        InstalledArtifactIdentityFailure.FILE_CLOSE_FAILED,
                    )
                }
            }
        }
        primaryFailure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        Os.close(descriptor)
    }
}

private object AndroidApkFileOps {
    fun digestPass(
        descriptor: FileDescriptor,
        length: Long,
        deadline: IdentityDeadline,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var remaining = length
        while (remaining > 0L) {
            deadline.observe()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = Os.read(descriptor, buffer, 0, requested)
            if (count <= 0 || count > requested) {
                abort(InstalledArtifactIdentityFailure.FILE_READ_FAILED)
            }
            digest.update(buffer, 0, count)
            remaining -= count.toLong()
        }
        deadline.observe()
        if (Os.read(descriptor, buffer, 0, 1) != 0) {
            abort(InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ)
        }
        return digest.digest()
    }

    fun stat(descriptor: FileDescriptor): StructStat =
        try {
            Os.fstat(descriptor)
        } catch (_: Exception) {
            abort(InstalledArtifactIdentityFailure.FILE_READ_FAILED)
        }

    fun validateRegular(stat: StructStat) {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0L ||
            stat.st_dev < 0L || stat.st_ino <= 0L || stat.st_nlink <= 0L
        ) {
            abort(InstalledArtifactIdentityFailure.FILE_NOT_REGULAR)
        }
    }

    fun sameIdentity(before: StructStat, after: StructStat): Boolean =
        before.st_dev == after.st_dev &&
            before.st_ino == after.st_ino &&
            before.st_mode == after.st_mode &&
            before.st_size == after.st_size &&
            before.st_nlink == after.st_nlink &&
            sameMutationTimes(before, after)

    private fun sameMutationTimes(before: StructStat, after: StructStat): Boolean =
        if (Build.VERSION.SDK_INT >= 27) {
            Api27StatTimes.same(before, after)
        } else {
            before.st_mtime == after.st_mtime && before.st_ctime == after.st_ctime
        }

    fun openFlags(): Int =
        OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            if (Build.VERSION.SDK_INT >= 27) Api27OpenFlags.closeOnExec() else 0

    // Keep the allocation fixed and bounded while reducing ART-to-Linux read crossings enough
    // to complete the required double digest inside the five-second identity deadline.
    private const val READ_BUFFER_BYTES = 1024 * 1024
}

@RequiresApi(27)
private object Api27OpenFlags {
    fun closeOnExec(): Int = OsConstants.O_CLOEXEC
}

@RequiresApi(27)
private object Api27StatTimes {
    fun same(before: StructStat, after: StructStat): Boolean =
        before.st_mtim == after.st_mtim && before.st_ctim == after.st_ctim
}

internal class ArtifactReadAbort(
    val reason: InstalledArtifactIdentityFailure,
) : RuntimeException(null, null, false, false)

internal fun abort(reason: InstalledArtifactIdentityFailure): Nothing = throw ArtifactReadAbort(reason)

private fun incomplete(reason: InstalledArtifactIdentityFailure): InstalledArtifactIdentityResult.Incomplete =
    InstalledArtifactIdentityResult.Incomplete(reason)
