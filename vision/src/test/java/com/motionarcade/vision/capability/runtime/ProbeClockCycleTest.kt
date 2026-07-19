package com.motionarcade.vision.capability.runtime

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeClockCycleTest {
    @Test
    fun clockHelperAbortJoinTerminatesBoundedAndStaleCaptureCannotCommit() {
        val machineRef = AtomicReference<ProbeStateMachine>()
        val helperStillAlive = AtomicBoolean(true)
        val clockHeldMachineMonitor = AtomicBoolean(true)
        val clock = ProbeClock {
            val machine = machineRef.get()
            clockHeldMachineMonitor.set(Thread.holdsLock(machine))
            val helper = Thread {
                machine.abortIncomplete()
            }.apply {
                isDaemon = true
                name = "probe-clock-abort-helper"
            }
            helper.start()
            helper.join(5_000L)
            helperStillAlive.set(helper.isAlive)
            1L
        }
        val routeKey = RuntimeRouteKey(
            ProbeAttemptEpoch(31uL),
            1,
            RuntimeGeneration(1L),
        )
        val runtime = object : PoseRuntime() {
            override val identity = RuntimeIdentity(310L)
            override fun close(): RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN
        }
        val opened = RuntimeTestDeepFixture.openForRoute(
            routeKey = routeKey,
            runtime = runtime,
            clock = clock,
        )
        val machine = RuntimeTestDeepFixture.stateMachine(opened)
        machineRef.set(machine)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { machine.startWarmup() }.get(5L, TimeUnit.SECONDS)

            assertFalse(result)
            assertFalse(clockHeldMachineMonitor.get())
            assertFalse(helperStillAlive.get())
            assertNotEquals(ProbeMachineState.WARMUP, machine.state)
            val claim = requireNotNull(machine.beginClose())
            val closeExecution = requireNotNull(RuntimeOwnerBoundary.close(claim, opened))
            val completion = requireNotNull(machine.completeClose(claim, closeExecution))
            assertEquals(
                ProbeCloseCompletionKind.SEALED,
                requireNotNull(machine.consumeCloseCompletion(completion, claim)).kind,
            )
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS))
        }
    }
}
