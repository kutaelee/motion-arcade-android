package com.motionarcade.app.fishing

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.SavedStateHandle

internal interface FishingMainThreadDispatcher {
    fun isMainThread(): Boolean

    fun dispatch(task: () -> Unit): Boolean
}

internal enum class FishingCheckpointPublishResult {
    COMMITTED,
    QUEUED,
    REJECTED,
    ;

    val accepted: Boolean
        get() = this != REJECTED
}

internal enum class FishingCheckpointCloseResult {
    CLOSED_NO_PENDING,
    CLOSED_AFTER_FLUSH,
    MAIN_THREAD_REQUIRED,
}

private class AndroidFishingMainThreadDispatcher : FishingMainThreadDispatcher {
    private val mainLooper = Looper.getMainLooper()
    private val handler = Handler(mainLooper)

    override fun isMainThread(): Boolean = Looper.myLooper() == mainLooper

    override fun dispatch(task: () -> Unit): Boolean = handler.post(task)
}

/**
 * Bounded bridge from the fishing worker to SavedStateHandle's MainThread-only API.
 *
 * At most one runnable and one latest encoded checkpoint are retained. A checkpoint published on
 * Main is committed immediately; a queued older revision can never overwrite it. [publish]
 * distinguishes a committed checkpoint from one that is only queued. The lifecycle owner must use
 * [flushAndCloseOnMain] so an accepted queued checkpoint is not silently discarded during teardown.
 */
internal class FishingCheckpointMainWriter(
    private val savedStateHandle: SavedStateHandle,
    private val checkpointKey: String,
    private val dispatcher: FishingMainThreadDispatcher = AndroidFishingMainThreadDispatcher(),
) {
    private data class PendingCheckpoint(
        val revision: Long,
        val encoded: ByteArray,
    )

    private val lock = Any()
    private var nextRevision = 1L
    private var latestPublishedRevision = 0L
    private var pending: PendingCheckpoint? = null
    private var runnableScheduled = false
    private var closed = false

    fun publish(encoded: ByteArray): FishingCheckpointPublishResult {
        val entry = synchronized(lock) {
            if (closed || nextRevision <= 0L) return FishingCheckpointPublishResult.REJECTED
            val revision = nextRevision
            nextRevision = if (revision == Long.MAX_VALUE) 0L else revision + 1L
            latestPublishedRevision = revision
            PendingCheckpoint(revision, encoded.copyOf())
        }

        if (dispatcher.isMainThread()) {
            synchronized(lock) {
                if (closed) return FishingCheckpointPublishResult.REJECTED
                pending = null
            }
            savedStateHandle[checkpointKey] = entry.encoded
            return FishingCheckpointPublishResult.COMMITTED
        }

        val shouldSchedule = synchronized(lock) {
            if (closed) return FishingCheckpointPublishResult.REJECTED
            pending = entry
            if (runnableScheduled) {
                false
            } else {
                runnableScheduled = true
                true
            }
        }
        if (!shouldSchedule) return FishingCheckpointPublishResult.QUEUED
        if (dispatcher.dispatch(::drainOnMain)) return FishingCheckpointPublishResult.QUEUED

        synchronized(lock) {
            runnableScheduled = false
            if (pending?.revision == entry.revision) pending = null
        }
        return FishingCheckpointPublishResult.REJECTED
    }

    internal fun pendingCount(): Int = synchronized(lock) { if (pending == null) 0 else 1 }

    /**
     * Closes admission and commits the latest queued immutable checkpoint without waiting.
     *
     * SavedStateHandle is MainThread-only, so an off-Main caller receives an explicit result and
     * leaves the writer open. This prevents a teardown caller from accidentally dropping data and
     * lets the lifecycle retry the same operation on Main.
     */
    fun flushAndCloseOnMain(): FishingCheckpointCloseResult {
        if (!dispatcher.isMainThread()) {
            return FishingCheckpointCloseResult.MAIN_THREAD_REQUIRED
        }
        val entry = synchronized(lock) {
            if (closed) return FishingCheckpointCloseResult.CLOSED_NO_PENDING
            closed = true
            pending.also { pending = null }
        }
        if (entry == null) return FishingCheckpointCloseResult.CLOSED_NO_PENDING
        savedStateHandle[checkpointKey] = entry.encoded
        return FishingCheckpointCloseResult.CLOSED_AFTER_FLUSH
    }

    private fun drainOnMain() {
        check(dispatcher.isMainThread()) { "checkpoint delivery must run on Main" }
        val entry = synchronized(lock) {
            runnableScheduled = false
            pending.also { pending = null }
        } ?: return
        val isLatest = synchronized(lock) {
            !closed && entry.revision == latestPublishedRevision
        }
        if (isLatest) savedStateHandle[checkpointKey] = entry.encoded
    }
}
