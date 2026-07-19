package com.motionarcade.vision.camera

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformCameraScopeWatcherTest {
    @Test
    fun stopRemovesPersistentLayoutLifecycleAndDisplayRegistrations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val owner = MutableLifecycleOwner()
        val events = mutableListOf<CameraScopeWatchEvent>()
        val watcher =
            PlatformCameraScopeWatcherFactory(context).create(previewView, events::add)

        watcher.start(owner)
        val startEventCount = events.size
        previewView.layout(0, 0, 640, 480)
        owner.handle(Lifecycle.Event.ON_START)
        assertTrue(events.size > startEventCount)

        assertTrue(watcher.stop())
        val stoppedEventCount = events.size
        previewView.layout(0, 0, 800, 600)
        owner.handle(Lifecycle.Event.ON_RESUME)

        assertEquals(stoppedEventCount, events.size)
    }

    @Test
    fun pauseStopAndDestroyAreInvalidatingLifecycleBoundaries() {
        assertLifecycleInvalidates(Lifecycle.Event.ON_PAUSE)
        assertLifecycleInvalidates(Lifecycle.Event.ON_STOP)
        assertLifecycleInvalidates(Lifecycle.Event.ON_DESTROY)
    }

    @Test
    fun uncertainCleanupResultIsStickyAcrossLaterSuccessfulOrEmptyStops() {
        val cleanup = StickyCameraScopeCleanup()

        assertTrue(cleanup.beginStop())
        assertFalse(cleanup.finishStop(clean = false))
        assertFalse(cleanup.beginStop())
        assertFalse(cleanup.finishStop(clean = true))
    }

    @Test
    fun resumedLifecycleSynchronousCallbackFailureRemovesAfterSideEffectObserver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val owner = AfterSideEffectResumedLifecycleOwner()
        val callbackFailure = IllegalStateException("synchronous callback failed")
        var callbackCount = 0
        val watcher =
            PlatformCameraScopeWatcherFactory(context).create(previewView) {
                callbackCount += 1
                if (callbackCount == 1) throw callbackFailure
            }

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                watcher.start(owner)
            }

        assertSame(callbackFailure, thrown)
        assertEquals(1, owner.removeAttempts)
        assertEquals(0, owner.trackedObserverCount)
        val callbacksAfterFailedStart = callbackCount
        owner.handle(Lifecycle.Event.ON_PAUSE)
        owner.handle(Lifecycle.Event.ON_STOP)
        assertEquals(callbacksAfterFailedStart, callbackCount)
        assertTrue(watcher.stop())
    }

    private fun assertLifecycleInvalidates(event: Lifecycle.Event) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewView = PreviewView(context)
        val owner = MutableLifecycleOwner()
        val events = mutableListOf<CameraScopeWatchEvent>()
        val watcher = PlatformCameraScopeWatcherFactory(context).create(previewView, events::add)
        watcher.start(owner)
        owner.handle(Lifecycle.Event.ON_START)
        owner.handle(Lifecycle.Event.ON_RESUME)
        when (event) {
            Lifecycle.Event.ON_STOP -> owner.handle(Lifecycle.Event.ON_PAUSE)
            Lifecycle.Event.ON_DESTROY -> {
                owner.handle(Lifecycle.Event.ON_PAUSE)
                owner.handle(Lifecycle.Event.ON_STOP)
            }
            else -> Unit
        }
        events.clear()

        owner.handle(event)

        assertEquals(listOf(CameraScopeWatchEvent.INVALIDATE), events)
        assertTrue(watcher.stop())
    }

    private class MutableLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }

        override fun getLifecycle(): Lifecycle = registry
    }
}
