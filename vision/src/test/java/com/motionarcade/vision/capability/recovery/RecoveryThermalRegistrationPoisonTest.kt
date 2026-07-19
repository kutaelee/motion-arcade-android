package com.motionarcade.vision.capability.recovery

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [SideEffectThenThrowPowerManagerShadow::class])
class RecoveryThermalRegistrationPoisonTest {
    @Before
    fun resetProcessBoundary() {
        resetGlobalRegistrationState()
        SideEffectThenThrowPowerManagerShadow.reset()
    }

    @After
    fun restoreProcessBoundary() {
        resetGlobalRegistrationState()
        SideEffectThenThrowPowerManagerShadow.reset()
    }

    @Test
    fun sideEffectThenThrowAndUncertainRemovalPermanentlyRejectActualSecondAttemptAndNativeGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertNull(ProcessThermalSafetyBoundary.ownApplicationProcess(context))
        assertEquals(1, SideEffectThenThrowPowerManagerShadow.attachCalls.get())
        assertEquals(1, SideEffectThenThrowPowerManagerShadow.removeCalls.get())

        // This is an actual second public-boundary attempt. The absorbing process latch rejects it
        // before another listener attach can be attempted.
        assertNull(ProcessThermalSafetyBoundary.ownApplicationProcess(context))
        assertEquals(1, SideEffectThenThrowPowerManagerShadow.attachCalls.get())

        val otherwiseClean = RecoveryClosureTestFixture.thermalMonitor()
        assertFalse(ProcessThermalSafetyBoundary.isCurrentSafeForNativeCreate(otherwiseClean))
    }

    private fun resetGlobalRegistrationState() {
        val type = ProcessThermalSafetyBoundary::class.java
        val latch = type.getDeclaredField("applicationRegistrationSafetyFailure").also {
            it.isAccessible = true
        }.get(ProcessThermalSafetyBoundary) as AtomicBoolean
        latch.set(false)
        listOf(
            "applicationProcessMonitor",
            "unavailableProcessMonitor",
        ).forEach { name ->
            type.getDeclaredField(name).also { it.isAccessible = true }
                .set(ProcessThermalSafetyBoundary, null)
        }
        type.getDeclaredField("applicationRegistrationInFlight").also { it.isAccessible = true }
            .setBoolean(ProcessThermalSafetyBoundary, false)
    }
}

@Implements(PowerManager::class)
class SideEffectThenThrowPowerManagerShadow {
    @Implementation
    fun getCurrentThermalStatus(): Int = PowerManager.THERMAL_STATUS_NONE

    @Implementation
    fun addThermalStatusListener(
        @Suppress("UNUSED_PARAMETER") executor: Executor,
        @Suppress("UNUSED_PARAMETER") listener: PowerManager.OnThermalStatusChangedListener,
    ) {
        attachCalls.incrementAndGet()
        throw IllegalStateException("listener installed before platform failure")
    }

    @Implementation
    fun removeThermalStatusListener(
        @Suppress("UNUSED_PARAMETER") listener: PowerManager.OnThermalStatusChangedListener,
    ) {
        removeCalls.incrementAndGet()
        throw IllegalStateException("listener removal result is uncertain")
    }

    companion object {
        val attachCalls = AtomicInteger()
        val removeCalls = AtomicInteger()

        fun reset() {
            attachCalls.set(0)
            removeCalls.set(0)
        }
    }
}
