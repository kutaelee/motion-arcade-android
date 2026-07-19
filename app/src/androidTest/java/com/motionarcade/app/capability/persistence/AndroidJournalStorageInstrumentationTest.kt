package com.motionarcade.app.capability.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.recovery.RecoveryJournalMode
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidJournalStorageInstrumentationTest {
    @Test
    fun contextRootCaptureAndFreshAdapterProcessLifetimeSeamUseDeviceOsAndNio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val physicalRoot = context.applicationContext.noBackupFilesDir.canonicalPath
        val environment = AndroidJournalStorageEnvironmentV1.capture(
            context = context,
            runtimeArtifactId = RuntimeArtifactId(digest(31)),
            mode = RecoveryJournalMode.SOLO,
        )
        assertEquals(physicalRoot, environment.frozenNoBackupRoot.path.value)
        val rootStat = environment.fileSystem.lstat(environment.frozenNoBackupRoot.path)
        assertEquals(
            environment.frozenNoBackupRoot.directoryIdentity,
            JournalDirectoryIdentityV1(rootStat.device, rootStat.inode),
        )

        val fileName = "journal_adapter_${UUID.randomUUID().toString().replace("-", "")}.bin"
        val path = JournalPathV1.canonicalAbsolute(
            "${environment.frozenNoBackupRoot.path.value}/$fileName",
        )
        val expected = "checked-android-process-lifetime-seam".encodeToByteArray()
        removeIfPresent(environment.fileSystem, path)
        try {
            val writeHandle = environment.fileSystem.openExclusiveCreate(path, JOURNAL_FILE_MODE)
            try {
                var offset = 0
                while (offset < expected.size) {
                    val written = environment.fileSystem.write(
                        writeHandle,
                        expected,
                        offset,
                        expected.size - offset,
                    )
                    assertTrue(written > 0)
                    offset += written
                }
                environment.fileSystem.fsync(writeHandle)
            } finally {
                environment.fileSystem.close(writeHandle)
            }

            val names = mutableListOf<String>()
            val directory = environment.fileSystem.openDirectory(environment.frozenNoBackupRoot.path)
            try {
                val iterator = directory.iterator()
                while (iterator.hasNext()) names += iterator.next().fileName
            } finally {
                directory.close()
            }
            assertTrue(fileName in names)

            val freshAdapter = AndroidJournalFileSystemV1()
            val readHandle = freshAdapter.openReadOnly(path)
            val actual = ByteArray(expected.size)
            try {
                var offset = 0
                while (offset < actual.size) {
                    val read = freshAdapter.read(readHandle, actual, offset, actual.size - offset)
                    assertTrue(read > 0)
                    offset += read
                }
                assertEquals(0, freshAdapter.read(readHandle, ByteArray(1), 0, 1))
            } finally {
                freshAdapter.close(readHandle)
            }
            assertArrayEquals(expected, actual)
            val firstClock = environment.clock.nowNs()
            val secondClock = environment.clock.nowNs()
            assertTrue(firstClock >= 0L)
            assertTrue(secondClock >= firstClock)
        } finally {
            removeIfPresent(environment.fileSystem, path)
        }
    }

    private fun removeIfPresent(
        fileSystem: AndroidJournalFileSystemV1,
        path: JournalPathV1,
    ) {
        try {
            fileSystem.remove(path)
        } catch (failure: JournalFileSystemExceptionV1) {
            if (failure.failure != JournalFileSystemFailureV1.NOT_FOUND) throw failure
        }
    }

    private fun digest(seed: Int): Sha256Digest =
        when (val result = Sha256Digest.fromBytes(ByteArray(32) { (seed + it).toByte() })) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("invalid test digest")
        }
}
