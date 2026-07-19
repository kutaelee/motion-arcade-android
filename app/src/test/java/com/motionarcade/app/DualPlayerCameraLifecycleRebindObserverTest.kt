package com.motionarcade.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class DualPlayerCameraLifecycleRebindObserverTest {
    @Test
    fun pauseThenResumeInvalidatesAndRequestsExactlyOneNewBindEpoch() {
        var rebinds = 0
        var invalidations = 0
        val observer = DualPlayerCameraLifecycleRebindObserver(
            initiallyResumed = false,
            onResumeRebind = { rebinds += 1 },
            onInactive = { invalidations += 1 },
        )

        observer.onStateChanged(OWNER, Lifecycle.Event.ON_CREATE)
        observer.onStateChanged(OWNER, Lifecycle.Event.ON_START)
        observer.onStateChanged(OWNER, Lifecycle.Event.ON_RESUME)
        assertEquals(1, rebinds)

        observer.onStateChanged(OWNER, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(OWNER, Lifecycle.Event.ON_RESUME)
        assertEquals(1, invalidations)
        assertEquals(2, rebinds)
    }

    @Test
    fun alreadyResumedObserverSkipsRegistryCatchUpButRebindsAfterNextPause() {
        var rebinds = 0
        val observer = DualPlayerCameraLifecycleRebindObserver(
            initiallyResumed = true,
            onResumeRebind = { rebinds += 1 },
            onInactive = {},
        )
        observer.onStateChanged(OWNER, Lifecycle.Event.ON_RESUME)
        assertEquals(0, rebinds)

        observer.onStateChanged(OWNER, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(OWNER, Lifecycle.Event.ON_RESUME)
        assertEquals(1, rebinds)
    }

    private companion object {
        val OWNER = object : LifecycleOwner {
            override val lifecycle: Lifecycle = object : Lifecycle() {
                override val currentState: State = State.RESUMED
                override fun addObserver(observer: LifecycleObserver) = Unit
                override fun removeObserver(observer: LifecycleObserver) = Unit
            }
        }
    }
}
