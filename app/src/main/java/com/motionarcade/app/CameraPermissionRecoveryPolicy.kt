package com.motionarcade.app

internal enum class CameraPermissionRecoveryRoute {
    REQUEST_SYSTEM_PERMISSION,
    OPEN_APP_SETTINGS,
}

internal enum class CameraPermissionInFlightAction {
    NONE,
    REQUEST_SYSTEM_PERMISSION,
    OPEN_APP_SETTINGS,
}

internal fun cameraPermissionRecoveryRoute(
    permissionDenied: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionRecoveryRoute =
    if (permissionDenied && !shouldShowRationale) {
        CameraPermissionRecoveryRoute.OPEN_APP_SETTINGS
    } else {
        CameraPermissionRecoveryRoute.REQUEST_SYSTEM_PERMISSION
    }
