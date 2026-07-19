package com.motionarcade.app.capability.persistence

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.annotation.RequiresApi
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.recovery.RecoveryJournalMode
import java.io.FileDescriptor
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths

/** The only production monotonic clock for this storage adapter. */
internal object AndroidJournalMonotonicClockV1 : JournalMonotonicClockV1 {
    override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
}

/**
 * Exact API policy from RecoveryJournalV5: API 26 omits O_CLOEXEC; API 27+ requires it.
 * The API-27 field reference lives in a lazily loaded nested class so API 26 never links it.
 */
internal object AndroidJournalOpenPolicyV1 {
    fun readOnlyFlags(): Int =
        OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExecForCurrentApi()

    fun exclusiveCreateFlags(): Int =
        OsConstants.O_WRONLY or
            OsConstants.O_CREAT or
            OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or
            closeOnExecForCurrentApi()

    private fun closeOnExecForCurrentApi(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            Api27CloseOnExec.value()
        } else {
            0
        }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private object Api27CloseOnExec {
        fun value(): Int = OsConstants.O_CLOEXEC
    }
}

/** Public-API Android implementation of the checked syscall boundary. */
internal class AndroidJournalFileSystemV1 : JournalFileSystemV1 {
    private class Handle(
        val descriptor: FileDescriptor,
    ) : JournalFileHandleV1

    override val providerKey: JournalProviderKeyV1 =
        JournalProviderKeyV1("android.system.Os+java.nio.file")

    override fun lstat(path: JournalPathV1): JournalStatV1 =
        syscall { stat(Os.lstat(path.value)) }

    override fun mkdir(
        path: JournalPathV1,
        mode: Int,
    ) {
        syscall { Os.mkdir(path.value, mode) }
    }

    override fun openReadOnly(path: JournalPathV1): JournalFileHandleV1 =
        syscall {
            Handle(
                Os.open(
                    path.value,
                    AndroidJournalOpenPolicyV1.readOnlyFlags(),
                    0,
                ),
            )
        }

    override fun openExclusiveCreate(
        path: JournalPathV1,
        mode: Int,
    ): JournalFileHandleV1 =
        syscall {
            Handle(
                Os.open(
                    path.value,
                    AndroidJournalOpenPolicyV1.exclusiveCreateFlags(),
                    mode,
                ),
            )
        }

    override fun fstat(handle: JournalFileHandleV1): JournalStatV1 =
        syscall { stat(Os.fstat(checked(handle).descriptor)) }

    override fun read(
        handle: JournalFileHandleV1,
        destination: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int = syscall {
        Os.read(checked(handle).descriptor, destination, offset, byteCount)
    }

    override fun write(
        handle: JournalFileHandleV1,
        source: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int = syscall {
        Os.write(checked(handle).descriptor, source, offset, byteCount)
    }

    override fun fsync(handle: JournalFileHandleV1) {
        syscall { Os.fsync(checked(handle).descriptor) }
    }

    override fun close(handle: JournalFileHandleV1) {
        syscall { Os.close(checked(handle).descriptor) }
    }

    override fun remove(path: JournalPathV1) {
        syscall { Os.remove(path.value) }
    }

    override fun rename(
        source: JournalPathV1,
        target: JournalPathV1,
    ) {
        syscall { Os.rename(source.value, target.value) }
    }

    override fun openDirectory(path: JournalPathV1): JournalDirectoryStreamV1 {
        val stream = nio { Files.newDirectoryStream(Paths.get(path.value)) }
        return AndroidDirectoryStream(path, stream)
    }

    private inner class AndroidDirectoryStream(
        private val parent: JournalPathV1,
        private val delegate: DirectoryStream<java.nio.file.Path>,
    ) : JournalDirectoryStreamV1 {
        private var iteratorIssued = false

        override fun iterator(): JournalDirectoryIteratorV1 {
            check(!iteratorIssued)
            iteratorIssued = true
            val iterator = nio { delegate.iterator() }
            return object : JournalDirectoryIteratorV1 {
                override fun hasNext(): Boolean = nio { iterator.hasNext() }

                override fun next(): JournalDirectoryEntryV1 = nio {
                    val entry = iterator.next()
                    JournalDirectoryEntryV1(
                        providerKey = providerKey,
                        parent = parent,
                        fileName = entry.fileName.toString(),
                    )
                }
            }
        }

        override fun close() {
            nio { delegate.close() }
        }
    }

    private fun checked(handle: JournalFileHandleV1): Handle =
        handle as? Handle ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)

    private fun stat(value: StructStat): JournalStatV1 =
        JournalStatV1(
            device = value.st_dev,
            inode = value.st_ino,
            type = when {
                OsConstants.S_ISDIR(value.st_mode) -> JournalNodeTypeV1.DIRECTORY
                OsConstants.S_ISREG(value.st_mode) -> JournalNodeTypeV1.REGULAR_FILE
                OsConstants.S_ISLNK(value.st_mode) -> JournalNodeTypeV1.SYMBOLIC_LINK
                else -> JournalNodeTypeV1.OTHER
            },
            size = value.st_size,
        )

    private inline fun <T> syscall(block: () -> T): T =
        try {
            block()
        } catch (failure: ErrnoException) {
            throw JournalFileSystemExceptionV1(mapErrno(failure.errno))
        } catch (_: SecurityException) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.SECURITY)
        }

    private inline fun <T> nio(block: () -> T): T =
        try {
            block()
        } catch (_: NoSuchFileException) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.NOT_FOUND)
        } catch (_: FileAlreadyExistsException) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.ALREADY_EXISTS)
        } catch (_: AccessDeniedException) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.SECURITY)
        } catch (_: DirectoryIteratorException) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        } catch (_: SecurityException) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.SECURITY)
        } catch (_: Exception) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        }

    private fun mapErrno(errno: Int): JournalFileSystemFailureV1 = when (errno) {
        OsConstants.ENOENT -> JournalFileSystemFailureV1.NOT_FOUND
        OsConstants.EEXIST -> JournalFileSystemFailureV1.ALREADY_EXISTS
        OsConstants.EINTR -> JournalFileSystemFailureV1.INTERRUPTED
        OsConstants.EACCES,
        OsConstants.EPERM,
        -> JournalFileSystemFailureV1.SECURITY
        else -> JournalFileSystemFailureV1.IO
    }
}

/**
 * Android-derived adapter inputs. This creates no reducer authority, deadline, token or plan;
 * those remain intentionally inert until the authoritative state-gate integration exists.
 */
internal class AndroidJournalStorageEnvironmentV1 private constructor(
    val frozenNoBackupRoot: JournalFrozenNoBackupRootV1,
    val paths: JournalPathsV1,
    val fileSystem: AndroidJournalFileSystemV1,
    val clock: JournalMonotonicClockV1,
) {
    companion object {
        fun capture(
            context: Context,
            runtimeArtifactId: RuntimeArtifactId,
            mode: RecoveryJournalMode,
        ): AndroidJournalStorageEnvironmentV1 {
            val root = JournalFrozenNoBackupRootV1.captureAndroid(context)
            val fileSystem = AndroidJournalFileSystemV1()
            check(fileSystem.providerKey == root.providerKey)
            return AndroidJournalStorageEnvironmentV1(
                frozenNoBackupRoot = root,
                paths = JournalPathsV1.create(root, runtimeArtifactId, mode),
                fileSystem = fileSystem,
                clock = AndroidJournalMonotonicClockV1,
            )
        }
    }
}
