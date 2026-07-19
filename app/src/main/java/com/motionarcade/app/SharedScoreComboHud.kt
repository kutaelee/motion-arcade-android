package com.motionarcade.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Compact code-rendered HUD. Readable values remain runtime state and the playfield stays visible.
 */
@Composable
internal fun SharedScoreComboHud(
    primaryText: String,
    secondaryText: String,
    frame: Int,
    modifier: Modifier = Modifier,
) {
    require(frame in 0 until SHARED_HUD_FRAME_COUNT)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                color = Color(0xFF102737).copy(alpha = 0.86f + frame * 0.02f),
                shape = RoundedCornerShape(16.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$primaryText  •  $secondaryText",
            modifier = Modifier.padding(horizontal = 24.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

internal fun sharedHudFrameForTick(tick: Long): Int =
    ((tick.coerceAtLeast(0L) / 8L) % SHARED_HUD_FRAME_COUNT).toInt()

private const val SHARED_HUD_FRAME_COUNT = 4
