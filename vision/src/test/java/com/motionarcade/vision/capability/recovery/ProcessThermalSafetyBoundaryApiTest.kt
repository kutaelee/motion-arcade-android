package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ProcessThermalSafetyBoundaryApiTest {
    @Test
    @Config(sdk = [28])
    fun api28UsesDistinctUnavailableReceiptWithoutInventingStatusZero() {
        val monitor = requireNotNull(ProcessThermalSafetyBoundary.unavailableForCurrentApi())
        assertTrue(monitor.javaClass.simpleName.contains("UnavailableApi"))
        val measurement =
            requireNotNull(ProcessThermalSafetyBoundary.beginMeasurement(monitor, 808L))
        assertTrue(measurement.javaClass.simpleName.contains("UnavailableApi"))
        val cutoff =
            requireNotNull(ProcessThermalSafetyBoundary.endMeasurement(monitor, measurement))
        assertTrue(cutoff.javaClass.simpleName.contains("UnavailableApi"))
        assertTrue(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                monitor,
                measurement,
                cutoff,
                808L,
            ),
        )
        assertFalse(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                monitor,
                measurement,
                cutoff,
                808L,
            ),
        )
    }

    @Test
    @Config(sdk = [28])
    fun api28UnavailableEvidenceCanCompleteExactRuntimeClosure() {
        val key =
            RecoveryAttemptRouteKey(
                probeBaseScopeId = RecoveryJournalTestFixtures.scope(128),
                attemptEpoch = 1uL,
                delegate = ProbeDelegate.CPU,
                role = JournalRouteRole.CANDIDATE,
            )
        val monitor = requireNotNull(ProcessThermalSafetyBoundary.unavailableForCurrentApi())
        val harness =
            RecoveryClosureTestFixture.harness(
                key = key,
                outcome = RouteAttemptOutcome.MEASURED,
                suppliedThermalMonitor = monitor,
            )
        assertTrue(harness.completeCleanClosure() is RecoveryCleanClosureAuthority)
    }

    @Test
    @Config(sdk = [29])
    fun api29CannotUseUnavailableApiAuthority() {
        assertNull(ProcessThermalSafetyBoundary.unavailableForCurrentApi())
    }

    @Test
    @Config(sdk = [26])
    fun api26UnavailableReceiptStillRequiresExactResourceGeneration() {
        val monitor = requireNotNull(ProcessThermalSafetyBoundary.unavailableForCurrentApi())
        val measurement =
            requireNotNull(ProcessThermalSafetyBoundary.beginMeasurement(monitor, 826L))
        val cutoff =
            requireNotNull(ProcessThermalSafetyBoundary.endMeasurement(monitor, measurement))
        assertFalse(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                monitor,
                measurement,
                cutoff,
                827L,
            ),
        )
        assertTrue(
            ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                monitor,
                measurement,
                cutoff,
                826L,
            ),
        )
    }
}
