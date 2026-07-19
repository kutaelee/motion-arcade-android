package com.motionarcade.app

import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewPresentationPolicyTest {
    @Test
    fun permissionLossReturnsToPermissionGateInsteadOfRetryingBind() {
        FrontCameraPreviewStatus.entries.forEach { status ->
            assertEquals(
                status == FrontCameraPreviewStatus.PERMISSION_MISSING,
                cameraStatusRequiresPermission(status),
            )
        }
    }

    @Test
    fun retryIsLimitedToRecoverablePreviewBindingFailures() {
        FrontCameraPreviewStatus.entries.forEach { status ->
            assertEquals(
                status == FrontCameraPreviewStatus.BIND_FAILED ||
                    status == FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE,
                cameraStatusCanRetry(status),
            )
        }
    }

    @Test
    fun oneSavedRebindBudgetCoversCameraAndPoseFailuresWithoutAnInfiniteLoop() {
        val idle = LivePoseInferenceSnapshot.idle()
        val failed =
            LivePoseInferenceSnapshot(
                sessionGeneration = 1L,
                revision = 3L,
                phase = LivePoseInferencePhase.FAILED,
                poseCount = null,
                callbackCount = 1L,
                resultTimestampMs = null,
            )

        assertTrue(previewCanRetry(FrontCameraPreviewStatus.ACTIVE, failed, rebindAttempts = 0))
        assertTrue(
            previewCanRetry(
                FrontCameraPreviewStatus.BIND_FAILED,
                idle,
                rebindAttempts = 0,
            ),
        )
        FrontCameraPreviewStatus.entries.forEach { status ->
            assertFalse(previewCanRetry(status, failed, rebindAttempts = 1))
            assertFalse(previewCanRetry(status, idle, rebindAttempts = 1))
        }
        assertFalse(previewCanRetry(FrontCameraPreviewStatus.ACTIVE, idle, rebindAttempts = 0))
    }
}
