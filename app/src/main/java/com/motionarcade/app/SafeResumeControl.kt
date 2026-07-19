package com.motionarcade.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** Requires a stable neutral/rebound camera state for a visible three-second resume countdown. */
@Composable
internal fun SafeResumeControl(
    recoveryKey: Any,
    ready: Boolean,
    idleLabel: String,
    onResume: () -> Unit,
) {
    var remainingSeconds by remember(recoveryKey) { mutableStateOf<Int?>(null) }

    LaunchedEffect(recoveryKey, ready, remainingSeconds) {
        val remaining = remainingSeconds ?: return@LaunchedEffect
        if (!ready) {
            remainingSeconds = null
            return@LaunchedEffect
        }
        if (remaining > 0) {
            delay(1_000L)
            if (ready) remainingSeconds = remaining - 1
        } else {
            onResume()
            remainingSeconds = null
        }
    }

    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = ready && remainingSeconds == null,
        onClick = { remainingSeconds = RESUME_COUNTDOWN_SECONDS },
    ) {
        Text(
            remainingSeconds?.let { seconds ->
                if (seconds > 0) "Hold neutral · $seconds" else "Resume"
            } ?: if (ready) idleLabel else "Hold neutral to re-arm"
        )
    }
}

private const val RESUME_COUNTDOWN_SECONDS = 3
