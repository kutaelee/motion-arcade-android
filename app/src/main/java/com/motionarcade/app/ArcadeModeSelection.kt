package com.motionarcade.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal enum class ArcadeGameChoice {
    FISHING,
    BOXING,
    MONSTER,
}

internal enum class ArcadePlayerCount(val value: Int) {
    SOLO(1),
    DUAL(2),
}

@Composable
internal fun ArcadeModeSelection(
    onModeSelected: (ArcadeGameChoice, ArcadePlayerCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedGame by rememberSaveable { mutableStateOf<ArcadeGameChoice?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07131F), Color(0xFF10263B), Color(0xFF07131F)),
                ),
            )
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (selectedGame == null) "게임 선택" else "인원 선택",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = if (selectedGame == null) {
                "플레이할 게임을 먼저 고르세요."
            } else {
                "1인은 AI 동료/상대와, 2인은 한 카메라에서 함께 플레이합니다."
            },
            color = Color(0xFFBBD0E2),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (selectedGame == null) {
            GameChoiceCard(
                title = "낚시",
                subtitle = "캐스팅 · 릴 · 장력 · 뜰채",
                accent = Color(0xFF2BA9A1),
                onClick = { selectedGame = ArcadeGameChoice.FISHING },
            )
            GameChoiceCard(
                title = "격투",
                subtitle = "잽 · 훅 · 가드 · 회피",
                accent = Color(0xFF25C7D9),
                onClick = { selectedGame = ArcadeGameChoice.BOXING },
            )
            GameChoiceCard(
                title = "협동 원정",
                subtitle = "역할 분담 · 부활 · 협동 필살기",
                accent = Color(0xFFF0A83B),
                onClick = { selectedGame = ArcadeGameChoice.MONSTER },
            )
        } else {
            val game = requireNotNull(selectedGame)
            PlayerCountCard(
                title = "1인 플레이",
                subtitle = "AI와 함께 바로 시작",
                accent = Color(0xFF35BFC2),
                onClick = { onModeSelected(game, ArcadePlayerCount.SOLO) },
            )
            PlayerCountCard(
                title = "2인 플레이",
                subtitle = "두 사람의 상체가 함께 보이면 시작",
                accent = Color(0xFFFF765E),
                onClick = { onModeSelected(game, ArcadePlayerCount.DUAL) },
            )
            OutlinedButton(
                onClick = { selectedGame = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("게임 다시 선택")
            }
        }
    }
}

@Composable
private fun GameChoiceCard(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) = SelectionCard(title, subtitle, accent, onClick)

@Composable
private fun PlayerCountCard(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) = SelectionCard(title, subtitle, accent, onClick)

@Composable
private fun SelectionCard(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF21A3045)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", color = accent, style = MaterialTheme.typography.headlineMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    color = Color(0xFFBBD0E2),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("›", color = accent, style = MaterialTheme.typography.headlineMedium)
        }
    }
}
