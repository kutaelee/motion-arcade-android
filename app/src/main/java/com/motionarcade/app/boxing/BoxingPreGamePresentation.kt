package com.motionarcade.app.boxing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motionarcade.app.R

internal const val NO_BOXER_SELECTED = -1
internal const val BOXER_OPTION_COUNT = 4

internal fun canConfirmBoxingMatchup(playerOne: Int, playerTwo: Int): Boolean =
    playerOne in 0 until BOXER_OPTION_COUNT &&
        playerTwo in 0 until BOXER_OPTION_COUNT &&
        playerOne != playerTwo

internal fun boxingCharacterDrawable(index: Int): Int = when (index) {
    0 -> R.drawable.boxing_boxer_1_v1
    1 -> R.drawable.boxing_boxer_2_v1
    2 -> R.drawable.boxing_boxer_3_v1
    3 -> R.drawable.boxing_boxer_4_v1
    else -> throw IllegalArgumentException("boxer index must be in 0 until $BOXER_OPTION_COUNT")
}

internal fun boxingCharacterName(index: Int): String = when (index) {
    0 -> "EMBER"
    1 -> "RUSH"
    2 -> "VOLT"
    3 -> "NOVA"
    else -> throw IllegalArgumentException("boxer index must be in 0 until $BOXER_OPTION_COUNT")
}

@Composable
internal fun BoxingCharacterSelectionPresentation(
    playerOne: Int,
    playerTwo: Int,
    onSelectPlayerOne: (Int) -> Unit,
    onSelectPlayerTwo: (Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    playerOneLabel: String = "P1",
    playerTwoLabel: String = "P2",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF09070D), Color(0xFF241126), Color(0xFF08070B)),
                ),
            )
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("뒤로") }
            Text(
                text = "CHOOSE YOUR FIGHTERS",
                color = Color(0xFFFFD166),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        }
        BoxerChoiceRow(playerOneLabel, playerOne, onSelectPlayerOne)
        BoxerChoiceRow(playerTwoLabel, playerTwo, onSelectPlayerTwo)
        Text(
            text = "네 선수의 실제 원화를 비교해 서로 다른 두 선수를 선택하세요.",
            color = Color(0xFFFFD166),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "선택은 판정·피해량·스태미나에 유불리를 만들지 않습니다.",
            color = Color(0xFFD8CDDF),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onContinue,
            enabled = canConfirmBoxingMatchup(playerOne, playerTwo),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE45F7A)),
        ) {
            Text("대결 구도 확정", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BoxerChoiceRow(
    playerLabel: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(playerLabel, color = Color.White, fontWeight = FontWeight.Bold)
        (0 until BOXER_OPTION_COUNT).chunked(2).forEach { rowIndexes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowIndexes.forEach { index ->
                    OutlinedButton(
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected == index) {
                                boxerAccent(index)
                            } else {
                                Color(0xB3211728)
                            },
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(boxingCharacterDrawable(index)),
                                contentDescription = "$playerLabel ${boxingCharacterName(index)}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(128.dp),
                            )
                            Text(
                                boxingCharacterName(index),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun boxerAccent(index: Int): Color = when (index) {
    0 -> Color(0xFF8B6718)
    1 -> Color(0xFF9A2D35)
    2 -> Color(0xFF0B7681)
    else -> Color(0xFF60408D)
}
