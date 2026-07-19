package com.motionarcade.app.fishing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.motionarcade.app.R

internal const val NO_FISHING_ROD_SELECTED = -1
internal const val FISHING_ROD_OPTION_COUNT = 3

internal fun isFishingRodSelectionValid(index: Int): Boolean =
    index in 0 until FISHING_ROD_OPTION_COUNT

internal fun fishingSelectionFrameForRod(index: Int): Int =
    if (isFishingRodSelectionValid(index)) index + 1 else 0

internal fun fishingSelectionAtlasOffset(frame: Int): IntOffset {
    require(frame in 0 until FISHING_SELECTION_STATE_COUNT)
    return IntOffset(
        x = (frame % 2) * FISHING_SELECTION_FRAME_WIDTH_PX,
        y = (frame / 2) * FISHING_SELECTION_FRAME_HEIGHT_PX,
    )
}

@Composable
internal fun FishingRodSelectionPresentation(
    selectedRod: Int,
    onSelectRod: (Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val harbor = ImageBitmap.imageResource(R.drawable.fishing_harbor_day_v1)
    val rod = ImageBitmap.imageResource(R.drawable.fishing_rod_v1)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF082942), Color(0xFF0A5B78), Color(0xFF061D2E)),
                ),
            )
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "CHOOSE YOUR ROD",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "선택 결과는 이 화면에만 표시되며 게임 능력치에는 영향을 주지 않습니다.",
            color = Color(0xFFD8F3F5),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            drawImage(
                image = harbor,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(harbor.width, harbor.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
            val selectedShift = if (isFishingRodSelectionValid(selectedRod)) selectedRod - 1 else 0
            drawImage(
                image = rod,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(rod.width, rod.height),
                dstOffset = IntOffset(
                    (size.width * (0.04f + selectedShift * 0.012f)).toInt(),
                    (size.height * 0.02f).toInt(),
                ),
                dstSize = IntSize((size.width * 0.42f).toInt(), (size.height * 0.90f).toInt()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FishingRodOption("TIDE", 0, selectedRod, onSelectRod, Modifier.weight(1f))
            FishingRodOption("REEF", 1, selectedRod, onSelectRod, Modifier.weight(1f))
            FishingRodOption("NOVA", 2, selectedRod, onSelectRod, Modifier.weight(1f))
        }
        Button(
            onClick = onContinue,
            enabled = isFishingRodSelectionValid(selectedRod),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF47B61)),
        ) {
            Text("이 장비로 출항", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("게임·인원 다시 선택")
        }
    }
}

@Composable
private fun FishingRodOption(
    label: String,
    index: Int,
    selectedRod: Int,
    onSelectRod: (Int) -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = { onSelectRod(index) },
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selectedRod == index) Color(0xFF2BA9A1) else Color(0xB314364A),
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

private const val FISHING_SELECTION_FRAME_WIDTH_PX = 540
private const val FISHING_SELECTION_FRAME_HEIGHT_PX = 960
private const val FISHING_SELECTION_STATE_COUNT = 4
