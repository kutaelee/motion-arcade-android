package com.motionarcade.app.fishing

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FishingCheckpointMainWriterTest {
    @Test
    fun workerPublicationsKeepOneRunnableAndCommitOnlyLatestOnMain() {
        val handle = SavedStateHandle()
        val dispatcher = ManualMainDispatcher()
        val writer = FishingCheckpointMainWriter(handle, KEY, dispatcher)

        assertEquals(FishingCheckpointPublishResult.QUEUED, writer.publish(byteArrayOf(1)))
        assertEquals(FishingCheckpointPublishResult.QUEUED, writer.publish(byteArrayOf(2)))
        assertEquals(1, dispatcher.pendingCount)
        assertEquals(1, writer.pendingCount())
        assertNull(handle.get<ByteArray>(KEY))

        dispatcher.runPendingOnMain()

        assertArrayEquals(byteArrayOf(2), handle.get<ByteArray>(KEY))
        assertEquals(0, writer.pendingCount())
        assertEquals(
            FishingCheckpointCloseResult.CLOSED_NO_PENDING,
            dispatcher.runOnMain { writer.flushAndCloseOnMain() },
        )
    }

    @Test
    fun lifecycleCloseFlushesLatestDelayedImmutableCheckpointBeforeRejectingNewWrites() {
        val handle = SavedStateHandle()
        val dispatcher = ManualMainDispatcher()
        val writer = FishingCheckpointMainWriter(handle, KEY, dispatcher)
        assertEquals(FishingCheckpointPublishResult.QUEUED, writer.publish(byteArrayOf(1)))
        val latest = byteArrayOf(2)
        assertEquals(FishingCheckpointPublishResult.QUEUED, writer.publish(latest))
        latest[0] = 9

        assertEquals(
            FishingCheckpointCloseResult.CLOSED_AFTER_FLUSH,
            dispatcher.runOnMain { writer.flushAndCloseOnMain() },
        )
        assertArrayEquals(byteArrayOf(2), handle.get<ByteArray>(KEY))
        assertEquals(FishingCheckpointPublishResult.REJECTED, writer.publish(byteArrayOf(3)))

        dispatcher.runPendingOnMain()

        assertArrayEquals(byteArrayOf(2), handle.get<ByteArray>(KEY))
        assertEquals(0, writer.pendingCount())
    }

    @Test
    fun offMainCloseReportsRequiredMainFlushWithoutDiscardingPendingCheckpoint() {
        val handle = SavedStateHandle()
        val dispatcher = ManualMainDispatcher()
        val writer = FishingCheckpointMainWriter(handle, KEY, dispatcher)
        assertEquals(FishingCheckpointPublishResult.QUEUED, writer.publish(byteArrayOf(4)))

        assertEquals(
            FishingCheckpointCloseResult.MAIN_THREAD_REQUIRED,
            writer.flushAndCloseOnMain(),
        )
        assertEquals(1, writer.pendingCount())
        assertEquals(
            FishingCheckpointCloseResult.CLOSED_AFTER_FLUSH,
            dispatcher.runOnMain { writer.flushAndCloseOnMain() },
        )

        assertArrayEquals(byteArrayOf(4), handle.get<ByteArray>(KEY))
        dispatcher.runPendingOnMain()
        assertArrayEquals(byteArrayOf(4), handle.get<ByteArray>(KEY))
    }

    private class ManualMainDispatcher : FishingMainThreadDispatcher {
        private var onMain = false
        private var pending: (() -> Unit)? = null

        val pendingCount: Int get() = if (pending == null) 0 else 1

        override fun isMainThread(): Boolean = onMain

        override fun dispatch(task: () -> Unit): Boolean {
            check(pending == null)
            pending = task
            return true
        }

        fun runPendingOnMain() {
            val task = pending ?: return
            pending = null
            runOnMain(task)
        }

        fun <T> runOnMain(task: () -> T): T {
            onMain = true
            try {
                return task()
            } finally {
                onMain = false
            }
        }
    }

    private companion object {
        const val KEY = "checkpoint"
    }
}
