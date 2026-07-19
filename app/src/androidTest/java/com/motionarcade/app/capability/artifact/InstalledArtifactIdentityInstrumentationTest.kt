package com.motionarcade.app.capability.artifact

import android.content.Context
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledArtifactIdentityInstrumentationTest {
    @Test
    fun installedBaseAndSplitsProduceStableSetOffMainThreadWithoutPathDisclosure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reader = AndroidInstalledArtifactIdentityReader(context)
        val installedApkPaths = buildList {
            add(context.applicationInfo.sourceDir)
            addAll(context.applicationInfo.splitSourceDirs.orEmpty())
        }
        val installedApkBytes = installedApkPaths.sumOf { File(it).length() }
        val readDurationNs = AtomicLong(-1L)
        val executor = Executors.newSingleThreadExecutor()
        val first = try {
            executor.submit<InstalledArtifactIdentityResult> {
                val startedAtNs = SystemClock.elapsedRealtimeNanos()
                reader.read().also {
                    readDurationNs.set(SystemClock.elapsedRealtimeNanos() - startedAtNs)
                }
            }
                .get(10L, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(
            "expected verified installed artifact identity; failure=" +
                (first as? InstalledArtifactIdentityResult.Incomplete)?.reason +
                "; readDurationMs=" +
                TimeUnit.NANOSECONDS.toMillis(readDurationNs.get()) +
                "; installedApkCount=${installedApkPaths.size}" +
                "; installedApkBytes=$installedApkBytes",
            first is InstalledArtifactIdentityResult.Verified,
        )
        val verified = first as InstalledArtifactIdentityResult.Verified
        assertTrue(verified.entries.isNotEmpty())
        assertEquals("base", verified.entries.first().logicalName)
        assertTrue(verified.entries.all { it.apkLength > 0uL })
        assertFalse(first.toString().contains(context.applicationInfo.sourceDir))
        assertEquals(
            InstalledArtifactIdentityResult.Incomplete(
                InstalledArtifactIdentityFailure.ALREADY_CONSUMED,
            ),
            reader.read(),
        )
    }

    @Test
    fun noFollowAndRegularNonemptyChecksFailClosedOnDeviceOs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val target = File(context.noBackupFilesDir, "artifact_target_$suffix.apk")
        val link = File(context.noBackupFilesDir, "artifact_link_$suffix.apk")
        val empty = File(context.noBackupFilesDir, "artifact_empty_$suffix.apk")
        try {
            target.writeBytes(byteArrayOf(1, 2, 3, 4))
            empty.createNewFile()
            Os.symlink(target.absolutePath, link.absolutePath)

            assertEquals(
                InstalledArtifactIdentityFailure.FILE_OPEN_FAILED,
                captureFailure { AndroidStableApkSource.open(link.absolutePath, deadline()) },
            )

            val emptySession = AndroidStableApkSource.open(empty.absolutePath, deadline())
            try {
                assertEquals(
                    InstalledArtifactIdentityFailure.FILE_NOT_REGULAR,
                    captureFailure { emptySession.read(deadline()) },
                )
            } finally {
                emptySession.close()
            }
        } finally {
            link.delete()
            empty.delete()
            target.delete()
        }
    }

    @Test
    fun samePathReplacementAfterHashCannotProduceStableIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val current = File(context.noBackupFilesDir, "artifact_current_$suffix.apk")
        val retired = File(context.noBackupFilesDir, "artifact_retired_$suffix.apk")
        current.writeBytes(ByteArray(64) { 0x11 })
        val session = AndroidStableApkSource.open(current.absolutePath, deadline())
        try {
            session.read(deadline())
            assertTrue(current.renameTo(retired))
            current.writeBytes(ByteArray(64) { 0x22 })
            assertEquals(
                InstalledArtifactIdentityFailure.FILE_CHANGED_DURING_READ,
                captureFailure { session.confirmCurrentPath(deadline()) },
            )
        } finally {
            session.close()
            current.delete()
            retired.delete()
        }
    }

    private fun deadline(): IdentityDeadline =
        IdentityDeadline.start(MonotonicClock { SystemClock.elapsedRealtimeNanos() })

    private fun captureFailure(block: () -> Unit): InstalledArtifactIdentityFailure =
        try {
            block()
            throw AssertionError("expected fail-closed artifact read")
        } catch (failure: ArtifactReadAbort) {
            failure.reason
        }
}
