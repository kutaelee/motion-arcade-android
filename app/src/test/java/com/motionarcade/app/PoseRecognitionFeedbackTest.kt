package com.motionarcade.app

import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.pose.LivePoseFailureReason
import com.motionarcade.vision.tracking.IdentityPauseReason
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseRecognitionFeedbackTest {
    @Test
    fun onePlayerNeedsOnlyOneDetectedUpperBody() {
        assertNull(poseRecognitionFailureReason(FrontCameraPreviewStatus.ACTIVE, active(1), 1))
    }

    @Test
    fun zeroPoseExplainsRequiredJointsAndNoFullBodyRequirement() {
        val reason = requireNotNull(
            poseRecognitionFailureReason(FrontCameraPreviewStatus.ACTIVE, active(0), 1),
        )
        assertTrue(reason.contains("어깨"))
        assertTrue(reason.contains("전신은 필요하지 않습니다"))
    }

    @Test
    fun dualModeExplainsMissingSecondPlayer() {
        val reason = requireNotNull(
            poseRecognitionFailureReason(FrontCameraPreviewStatus.ACTIVE, active(1), 2),
        )
        assertTrue(reason.contains("1명이 더 필요"))
    }

    @Test
    fun detectedPoseWithOffscreenRequiredJointsExplainsFraming() {
        val reason = requireNotNull(
            poseRecognitionFailureReason(
                FrontCameraPreviewStatus.ACTIVE,
                active(1, confidence = 0.9f, onScreen = false),
                1,
            ),
        )
        assertTrue(reason.contains("화면 밖"))
        assertTrue(reason.contains("전신은 필요하지 않습니다"))
    }

    @Test
    fun detectedPoseWithLowJointConfidenceExplainsLightingAndOcclusion() {
        val reason = requireNotNull(
            poseRecognitionFailureReason(
                FrontCameraPreviewStatus.ACTIVE,
                active(1, confidence = 0.4f, onScreen = true),
                1,
            ),
        )
        assertTrue(reason.contains("신뢰도가 낮습니다"))
        assertTrue(reason.contains("가림"))
        assertTrue(reason.contains("조명"))
    }

    @Test
    fun overlapReasonGivesAnActionableSeparationInstruction() {
        val reason = requireNotNull(dualTrackingFailureReason(IdentityPauseReason.PLAYER_OVERLAP))
        assertTrue(reason.contains("겹쳐"))
        assertTrue(reason.contains("떨어지세요"))
    }

    @Test
    fun resultTimeoutExplainsLocalLoadAndRejectsFirewallAdvice() {
        val reason = requireNotNull(
            poseRecognitionFailureReason(
                FrontCameraPreviewStatus.ACTIVE,
                failed(LivePoseFailureReason.RESULT_TIMEOUT),
                1,
            ),
        )
        assertTrue(reason.contains("1초"))
        assertTrue(reason.contains("기기 부하"))
        assertTrue(reason.contains("방화벽 문제는 아닙니다"))
    }

    @Test
    fun modelCreationFailureExplainsOnDeviceRuntime() {
        val reason = requireNotNull(
            poseRecognitionFailureReason(
                FrontCameraPreviewStatus.ACTIVE,
                failed(LivePoseFailureReason.SESSION_CREATE_FAILED),
                1,
            ),
        )
        assertTrue(reason.contains("온디바이스"))
        assertTrue(reason.contains("서버 연결은 사용하지 않습니다"))
    }

    private fun failed(reason: LivePoseFailureReason) = LivePoseInferenceSnapshot(
        sessionGeneration = 1,
        revision = 1,
        phase = LivePoseInferencePhase.FAILED,
        poseCount = null,
        callbackCount = 0,
        resultTimestampMs = null,
        failureReason = reason,
    )

    private fun active(
        count: Int,
        confidence: Float? = null,
        onScreen: Boolean? = null,
    ) = LivePoseInferenceSnapshot(
        sessionGeneration = 1,
        revision = 1,
        phase = LivePoseInferencePhase.ACTIVE,
        poseCount = count,
        callbackCount = 1,
        resultTimestampMs = 1,
        upperBodyConfidenceFloor = confidence,
        upperBodyOnScreen = onScreen,
    )
}
