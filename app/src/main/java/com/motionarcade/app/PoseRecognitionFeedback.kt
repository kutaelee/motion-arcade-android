package com.motionarcade.app

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.pose.LivePoseFailureReason
import com.motionarcade.vision.tracking.IdentityPauseReason

internal fun poseRecognitionFailureReason(
    status: FrontCameraPreviewStatus,
    inference: LivePoseInferenceSnapshot,
    expectedPlayers: Int,
    minimumUpperBodyConfidence: Float = 0.6f,
): String? {
    require(expectedPlayers in 1..2)
    require(minimumUpperBodyConfidence in 0f..1f)
    return when (status) {
        FrontCameraPreviewStatus.PERMISSION_MISSING ->
            "카메라 권한이 없어 인식할 수 없습니다. 설정에서 권한을 허용하세요."
        FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE ->
            "전면 카메라를 사용할 수 없습니다. 후면 카메라나 다른 기기를 선택하세요."
        FrontCameraPreviewStatus.BACK_CAMERA_UNAVAILABLE ->
            "후면 카메라를 사용할 수 없습니다. 전면 카메라로 전환하세요."
        FrontCameraPreviewStatus.BIND_FAILED ->
            "카메라 연결에 실패했습니다. 다른 앱의 카메라 사용을 종료하고 다시 시도하세요."
        else -> when (inference.phase) {
            LivePoseInferencePhase.FAILED -> when (inference.failureReason) {
                LivePoseFailureReason.RESULT_TIMEOUT ->
                    "온디바이스 동작 인식 결과가 1초 안에 도착하지 않았습니다. 기기 부하를 줄이고 카메라를 다시 시작하세요. 서버나 방화벽 문제는 아닙니다."
                LivePoseFailureReason.SESSION_CREATE_FAILED ->
                    "온디바이스 동작 인식 모델을 시작하지 못했습니다. 앱을 다시 시작하세요. 서버 연결은 사용하지 않습니다."
                LivePoseFailureReason.TIMESTAMP_REJECTED ->
                    "카메라 프레임 시간이 올바르지 않아 동작 인식을 멈췄습니다. 카메라를 다시 시작하세요."
                LivePoseFailureReason.FRAME_SUBMISSION_FAILED,
                LivePoseFailureReason.MEDIAPIPE_CALLBACK_ERROR,
                LivePoseFailureReason.CALLBACK_PROTOCOL_ERROR ->
                    "온디바이스 동작 인식 처리 오류가 발생했습니다. 카메라를 다시 시작하세요. 서버 연결은 사용하지 않습니다."
                LivePoseFailureReason.SESSION_CLOSE_FAILED,
                LivePoseFailureReason.CAMERA_PIPELINE_TERMINATED ->
                    "동작 인식 엔진이 로컬에서 멈췄습니다. 카메라를 다시 시작하세요. 서버나 방화벽 문제는 아닙니다."
                null -> "동작 인식 엔진이 멈췄습니다. 카메라를 다시 시작하세요."
            }
            LivePoseInferencePhase.ACTIVE -> when (val count = requireNotNull(inference.poseCount)) {
                expectedPlayers -> when {
                    inference.upperBodyOnScreen == false ->
                        "사람 수는 맞지만 필요한 상체 관절이 화면 밖입니다. 어깨·팔꿈치·손목·엉덩이를 프레임 안에 두세요. 전신은 필요하지 않습니다."
                    inference.upperBodyConfidenceFloor?.let { it < minimumUpperBodyConfidence } == true ->
                        "사람 수는 맞지만 상체 관절 신뢰도가 낮습니다. 손목·팔꿈치 가림을 풀고 조명을 밝힌 뒤 카메라를 흔들리지 않게 하세요."
                    else -> null
                }
                0 -> "필요한 상체 관절을 찾지 못했습니다. 어깨·팔꿈치·손목·엉덩이가 화면에 보이게 하세요. 전신은 필요하지 않습니다."
                else -> if (count < expectedPlayers) {
                    "${expectedPlayers}인 모드입니다. ${expectedPlayers - count}명이 더 필요하며 각 사람의 어깨·팔·엉덩이가 보여야 합니다."
                } else {
                    "${expectedPlayers}인 모드인데 사람이 더 감지됐습니다. 플레이어만 카메라 안에 남아 주세요."
                }
            }
            else -> null
        }
    }
}

internal fun dualTrackingFailureReason(reason: IdentityPauseReason?): String? = when (reason) {
    null, IdentityPauseReason.NONE -> null
    IdentityPauseReason.TENTATIVE_TRACKS -> "두 플레이어를 확인 중입니다. 서로 떨어져 잠시 중립 자세를 유지하세요."
    IdentityPauseReason.INSUFFICIENT_OBSERVATIONS -> "두 사람의 어깨·손목·엉덩이가 충분히 보이지 않습니다. 전신은 필요하지 않습니다."
    IdentityPauseReason.ASSIGNMENT_AMBIGUOUS -> "P1/P2 구분이 불확실합니다. 좌우 간격을 넓혀 주세요."
    IdentityPauseReason.CROSSING_HYSTERESIS -> "두 사람이 자리를 교차해 안전 정지했습니다. 각자 현재 위치에서 중립 자세를 유지하세요."
    IdentityPauseReason.PLAYER_OVERLAP -> "두 사람의 상체가 겹쳐 인식이 멈췄습니다. 서로 한 걸음 떨어지세요."
    IdentityPauseReason.REARM_REQUIRED -> "다시 시작하려면 두 사람 모두 팔을 내린 중립 자세를 유지하세요."
    IdentityPauseReason.REARM_STABILITY -> "중립 자세 안정성을 확인 중입니다. 카운트다운이 끝날 때까지 움직이지 마세요."
    IdentityPauseReason.CALIBRATION_CHANGED -> "카메라가 바뀌어 재보정이 필요합니다. 두 사람의 상체를 다시 보여 주세요."
}

@Composable
internal fun PoseRecognitionFailureToast(
    status: FrontCameraPreviewStatus,
    inference: LivePoseInferenceSnapshot,
    expectedPlayers: Int,
    minimumUpperBodyConfidence: Float = 0.6f,
) {
    val context = LocalContext.current
    val reason = poseRecognitionFailureReason(
        status,
        inference,
        expectedPlayers,
        minimumUpperBodyConfidence,
    )
    LaunchedEffect(reason) {
        if (reason != null) Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun DualTrackingFailureToast(reason: IdentityPauseReason?) {
    val context = LocalContext.current
    val message = dualTrackingFailureReason(reason)
    LaunchedEffect(message) {
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
