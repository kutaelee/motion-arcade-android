package com.motionarcade.vision.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicFrameSequencerTest {
    @Test
    fun assignsIncreasingFrameIdsToStrictlyMonotonicSourceTime() {
        val sequencer = MonotonicFrameSequencer()

        assertAccepted(sequencer.accept(0L), frameId = 0L, timestamp = 0L)
        assertAccepted(sequencer.accept(10L), frameId = 1L, timestamp = 10L)
        assertAccepted(sequencer.accept(11L), frameId = 2L, timestamp = 11L)
        assertEquals(11L, sequencer.lastAcceptedTimestampNs())
    }

    @Test
    fun negativeDuplicateAndOutOfOrderTimeAreRejectedWithoutAdvancingState() {
        val sequencer = MonotonicFrameSequencer()

        assertRejected(sequencer.accept(-1L), TimestampRejectionReason.NEGATIVE_TIMESTAMP)
        assertNull(sequencer.lastAcceptedTimestampNs())
        assertAccepted(sequencer.accept(100L), frameId = 0L, timestamp = 100L)
        assertRejected(sequencer.accept(100L), TimestampRejectionReason.DUPLICATE_TIMESTAMP)
        assertRejected(sequencer.accept(99L), TimestampRejectionReason.OUT_OF_ORDER_TIMESTAMP)
        assertAccepted(sequencer.accept(101L), frameId = 1L, timestamp = 101L)
    }

    @Test
    fun frameIdExhaustionFailsClosed() {
        val sequencer = MonotonicFrameSequencer(initialFrameId = Long.MAX_VALUE)

        assertAccepted(sequencer.accept(1L), frameId = Long.MAX_VALUE, timestamp = 1L)
        assertRejected(sequencer.accept(2L), TimestampRejectionReason.FRAME_ID_EXHAUSTED)
        assertEquals(1L, sequencer.lastAcceptedTimestampNs())
    }

    private fun assertAccepted(
        decision: FrameTimestampDecision,
        frameId: Long,
        timestamp: Long,
    ) {
        assertTrue(decision is FrameTimestampDecision.Accepted)
        val frame = (decision as FrameTimestampDecision.Accepted).frame
        assertEquals(frameId, frame.frameId)
        assertEquals(timestamp, frame.sourceTimestampNs)
    }

    private fun assertRejected(
        decision: FrameTimestampDecision,
        reason: TimestampRejectionReason,
    ) {
        assertTrue(decision is FrameTimestampDecision.Rejected)
        assertEquals(reason, (decision as FrameTimestampDecision.Rejected).reason)
    }
}
