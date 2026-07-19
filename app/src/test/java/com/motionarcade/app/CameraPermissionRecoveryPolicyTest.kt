package com.motionarcade.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionRecoveryPolicyTest {
    @Test
    fun initialRequestUsesSystemPermissionDialog() {
        assertEquals(
            CameraPermissionRecoveryRoute.REQUEST_SYSTEM_PERMISSION,
            cameraPermissionRecoveryRoute(
                permissionDenied = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun deniedRequestWithRationaleCanRequestAgain() {
        assertEquals(
            CameraPermissionRecoveryRoute.REQUEST_SYSTEM_PERMISSION,
            cameraPermissionRecoveryRoute(
                permissionDenied = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun deniedRequestWithoutRationaleOpensAppSettings() {
        assertEquals(
            CameraPermissionRecoveryRoute.OPEN_APP_SETTINGS,
            cameraPermissionRecoveryRoute(
                permissionDenied = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun persistedDenialOpensSettingsAfterProcessRestart() {
        assertEquals(
            CameraPermissionRecoveryRoute.OPEN_APP_SETTINGS,
            cameraPermissionRecoveryRoute(
                permissionDenied = true,
                shouldShowRationale = false,
            ),
        )
    }
}
