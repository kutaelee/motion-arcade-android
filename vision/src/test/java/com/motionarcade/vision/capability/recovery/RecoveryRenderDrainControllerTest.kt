package com.motionarcade.vision.capability.recovery

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRenderDrainControllerTest {
    @Test
    fun sameLooperStillAttemptsTerminationAndFailsClosed() {
        val operations = FakeDrainOperations(sameLooper = true)

        assertFails(operations)

        assertTrue(operations.quitCalls >= 1)
        assertTrue(operations.alive)
    }

    @Test
    fun postThrowStillTerminatesAndBoundedJoins() {
        val operations = FakeDrainOperations(postFailure = IllegalStateException("post failed"))

        assertFails(operations)

        assertTrue(operations.quitCalls >= 1)
        assertTrue(operations.joinCalls >= 1)
        assertFalse(operations.alive)
    }

    @Test
    fun awaitInterruptStillTerminatesJoinsAndRestoresInterrupt() {
        val operations = FakeDrainOperations(signalBarrier = false)
        Thread.currentThread().interrupt()
        try {
            assertFails(operations)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(operations.joinCalls >= 1)
            assertFalse(operations.alive)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun quitThrowStillRunsFinalBoundedJoin() {
        val operations = FakeDrainOperations(quitThrowsOnCalls = setOf(1, 2))

        assertFails(operations)

        assertTrue(operations.quitCalls >= 2)
        assertTrue(operations.joinCalls >= 1)
        assertFalse(operations.alive)
    }

    @Test
    fun joinInterruptRetriesCleanupJoinAndFailsClosed() {
        val operations =
            FakeDrainOperations(
                joinActions = ArrayDeque(listOf(JoinAction.INTERRUPT, JoinAction.STOPPED)),
            )

        assertFails(operations)

        assertEquals(2, operations.joinCalls)
        assertFalse(operations.alive)
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()
    }

    @Test
    fun joinTimeoutInterruptsTargetAndPerformsSecondBoundedJoin() {
        val operations =
            FakeDrainOperations(
                joinActions =
                    ArrayDeque(
                        listOf(
                            JoinAction.TIMEOUT,
                            JoinAction.TIMEOUT,
                            JoinAction.STOPPED,
                        ),
                    ),
            )

        assertFails(operations)

        assertEquals(3, operations.joinCalls)
        assertEquals(1, operations.targetInterruptCalls)
        assertFalse(operations.alive)
    }

    @Test
    fun workerStillAliveAfterFinalInterruptAndJoinIsNeverAccepted() {
        val operations =
            FakeDrainOperations(
                joinActions =
                    ArrayDeque(
                        listOf(
                            JoinAction.TIMEOUT,
                            JoinAction.TIMEOUT,
                            JoinAction.TIMEOUT,
                        ),
                    ),
            )

        assertFails(operations)

        assertTrue(operations.alive)
        assertEquals(1, operations.targetInterruptCalls)
    }

    private fun assertFails(operations: FakeDrainOperations) {
        assertThrows(IllegalStateException::class.java) {
            RecoveryRenderDrainController(operations, 10L).drainDiscardThroughDeadline()
        }
    }

    private enum class JoinAction {
        STOPPED,
        TIMEOUT,
        INTERRUPT,
    }

    private class FakeDrainOperations(
        private val sameLooper: Boolean = false,
        private val signalBarrier: Boolean = true,
        private val postFailure: Throwable? = null,
        private val quitThrowsOnCalls: Set<Int> = emptySet(),
        private val joinActions: ArrayDeque<JoinAction> = ArrayDeque(),
    ) : RecoveryRenderDrainOperations {
        var alive = true
        var quitCalls = 0
        var joinCalls = 0
        var targetInterruptCalls = 0

        override fun isCurrentLooper(): Boolean = sameLooper

        override fun postBarrier(signal: () -> Unit): Boolean {
            postFailure?.let { throw it }
            if (signalBarrier) signal()
            return true
        }

        override fun quitSafely(): Boolean {
            quitCalls += 1
            if (quitCalls in quitThrowsOnCalls) throw IllegalStateException("quit failed")
            return true
        }

        override fun joinBounded(timeoutMillis: Long) {
            require(timeoutMillis > 0L)
            joinCalls += 1
            when (joinActions.pollFirst() ?: JoinAction.STOPPED) {
                JoinAction.STOPPED -> alive = false
                JoinAction.TIMEOUT -> Unit
                JoinAction.INTERRUPT -> throw InterruptedException("join interrupted")
            }
        }

        override fun isAlive(): Boolean = alive

        override fun interruptTarget() {
            targetInterruptCalls += 1
        }

        override fun nanoTime(): Long = 0L
    }
}
