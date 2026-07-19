package com.motionarcade.vision.camera

import androidx.camera.view.PreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionRequestGateTest {
    @Test
    fun newRequestInvalidatesEveryOlderAsyncCompletion() {
        val gate = CameraSessionRequestGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))

        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun cancelMakesOutstandingCompletionStateInert() {
        val gate = CameraSessionRequestGate()
        val request = gate.begin()

        gate.cancel()

        assertFalse(gate.isCurrent(request))
    }

    @Test
    fun onlyObservedStreamingStateCanReportActive() {
        assertEquals(
            FrontCameraPreviewStatus.STARTING,
            streamStatus(PreviewView.StreamState.IDLE),
        )
        assertEquals(
            FrontCameraPreviewStatus.ACTIVE,
            streamStatus(PreviewView.StreamState.STREAMING),
        )
    }
}
