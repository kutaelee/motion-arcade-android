package com.motionarcade.app

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.motionarcade.vision.camera.CameraLensSelection

@Composable
internal fun CameraLensControl(
    lensSelection: CameraLensSelection,
    onLensSelectionRequested: (CameraLensSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = if (lensSelection == CameraLensSelection.FRONT) {
        CameraLensSelection.BACK
    } else {
        CameraLensSelection.FRONT
    }
    OutlinedButton(
        modifier = modifier,
        onClick = { onLensSelectionRequested(next) },
    ) {
        Text(if (lensSelection == CameraLensSelection.FRONT) "전면 → 후면" else "후면 → 전면")
    }
}
