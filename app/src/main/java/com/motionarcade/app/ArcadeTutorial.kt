package com.motionarcade.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class ArcadeTutorialStep(
    val id: String,
    val title: String,
    val instruction: String,
    val practiceCue: String,
)

internal fun shouldPresentArcadeTutorial(
    mode: ArcadePlayMode,
    fishingRodConfirmed: Boolean,
    tutorialCompleted: Boolean,
): Boolean = !tutorialCompleted && (mode != ArcadePlayMode.FISHING || fishingRodConfirmed)

internal fun arcadeTutorialSteps(mode: ArcadePlayMode): List<ArcadeTutorialStep> {
    val framing = when (mode) {
        ArcadePlayMode.FISHING,
        ArcadePlayMode.FISHING_DUAL,
        -> ArcadeTutorialStep(
            id = "upper-body-framing",
            title = "상체와 양팔만 보여도 됩니다",
            instruction = "얼굴, 어깨, 팔꿈치, 손목과 양쪽 골반까지 화면에 들어오게 서세요. 무릎 아래나 전신은 필요하지 않습니다.",
            practiceCue = "두 팔을 천천히 들어 화면의 관절 안내가 안정되는지 확인하세요.",
        )

        else -> ArcadeTutorialStep(
            id = "upper-body-framing",
            title = "상체를 카메라에 맞추세요",
            instruction = "얼굴, 어깨, 팔꿈치, 손목과 양쪽 골반까지 보이면 플레이할 수 있습니다. 무릎 아래나 전신은 필요하지 않습니다.",
            practiceCue = "가드 자세를 잡고 화면의 관절 안내가 안정되는지 확인하세요.",
        )
    }
    val gameMotion = when (mode) {
        ArcadePlayMode.FISHING,
        ArcadePlayMode.FISHING_DUAL,
        -> ArcadeTutorialStep(
            id = "fishing-motion",
            title = "던지고, 걸고, 천천히 감으세요",
            instruction = "낚싯대를 던지는 팔 동작으로 캐스팅하고, 입질 뒤 당겨서 훅을 건 다음 양손을 번갈아 움직여 릴을 감습니다.",
            practiceCue = "긴급할 때는 화면의 터치 버튼으로 같은 동작을 입력할 수 있습니다.",
        )

        ArcadePlayMode.BOXING_SOLO,
        ArcadePlayMode.BOXING_DUAL,
        -> ArcadeTutorialStep(
            id = "boxing-motion",
            title = "가드와 펀치를 구분하세요",
            instruction = "두 손을 얼굴 가까이 올리면 가드, 한 팔을 앞으로 뻗으면 펀치입니다. 동작 뒤에는 팔을 가드 위치로 되돌려 다시 준비하세요.",
            practiceCue = "모션 입력이 어려우면 화면의 터치 공격·가드 버튼으로 계속 플레이할 수 있습니다.",
        )

        ArcadePlayMode.MONSTER_SOLO,
        ArcadePlayMode.MONSTER_DUAL,
        -> ArcadeTutorialStep(
            id = "monster-motion",
            title = "공격과 강공격을 준비하세요",
            instruction = "한 팔 공격을 반복하고, 양팔을 머리 위로 올린 뒤 힘껏 내리는 큰 동작으로 강공격을 사용하세요. 입력 뒤 중립 자세로 돌아오면 재무장됩니다.",
            practiceCue = "모션 입력이 어려우면 화면의 터치 공격·강공격 버튼으로 계속 플레이할 수 있습니다.",
        )
    }
    val playerRule = if (mode == ArcadePlayMode.FISHING_DUAL ||
        mode == ArcadePlayMode.BOXING_DUAL ||
        mode == ArcadePlayMode.MONSTER_DUAL
    ) {
        ArcadeTutorialStep(
            id = "dual-player-safety",
            title = "두 사람의 자리를 유지하세요",
            instruction = when (mode) {
                ArcadePlayMode.FISHING_DUAL -> "화면 왼쪽 P1은 낚싯대 동작, 오른쪽 P2는 뜰채와 장력 지원을 맡습니다. 서로 교차하거나 자리를 바꾸면 입력이 멈춥니다. ‘Set / re-arm P1 + P2’를 누르고 두 사람 모두 중립 자세를 유지해 역할을 다시 연결하세요."
                ArcadePlayMode.BOXING_DUAL -> "화면 왼쪽 P1과 오른쪽 P2가 각자 공격·가드를 사용합니다. 서로 교차하거나 가려지면 입력이 잠시 멈추고, 제자리에서 자세를 다시 잡으면 재무장됩니다."
                else -> "화면 왼쪽 P1과 오른쪽 P2가 함께 공격합니다. 서로 교차하거나 가려지면 입력이 잠시 멈추고, 제자리에서 자세를 다시 잡으면 재무장됩니다."
            },
            practiceCue = "팔이 서로 겹치지 않도록 한 팔 간격을 두고 서세요.",
        )
    } else {
        ArcadeTutorialStep(
            id = "solo-player-pacing",
            title = "한 번씩 정확하게 입력하세요",
            instruction = "동작을 끝낸 뒤 중립 자세로 돌아와 다음 입력을 준비하세요. 빠르게 흔드는 것보다 동작을 분리하는 편이 정확합니다.",
            practiceCue = "카메라가 흔들리지 않도록 기기를 고정하고 플레이하세요.",
        )
    }
    val recoveryInstruction = when (mode) {
        ArcadePlayMode.FISHING -> "화면의 이유 안내를 먼저 확인하고 조명·거리·가림을 고치세요. 게임 진행 자체가 중단되어 ‘게임 런타임 다시 시작’이 보이면 그 버튼을 누르세요. 모델 인식 실패 또는 카메라 연결 실패로 ‘카메라 다시 연결’이 보이면 해당 버튼을 누르세요."
        ArcadePlayMode.FISHING_DUAL,
        ArcadePlayMode.BOXING_DUAL,
        ArcadePlayMode.MONSTER_DUAL,
        -> "두 사람 추적이 멈추면 둘 다 중립 자세를 유지한 채 ‘Set / re-arm P1 + P2’를 누르세요. 카메라 또는 모델 자체가 멈췄다면 게임 선택으로 나갔다가 같은 모드로 다시 들어오세요."
        ArcadePlayMode.BOXING_SOLO,
        ArcadePlayMode.MONSTER_SOLO,
        -> "한 사람만 화면에 남기고 조명·거리·가림을 고치세요. 카메라 또는 모델이 계속 멈춘 상태면 게임 선택으로 나갔다가 같은 모드로 다시 들어오세요."
    }
    val recovery = ArcadeTutorialStep(
        id = "recognition-recovery",
        title = "인식이 멈추면 바로 복구하세요",
        instruction = "$recoveryInstruction 동작 인식은 서버가 아니라 기기 안에서 실행되므로 IP·포트·방화벽 설정은 필요하지 않습니다.",
        practiceCue = "안전정지 중에는 모션과 터치 모두 점수에 반영되지 않습니다. 인식이 안전 상태로 돌아온 뒤 터치 버튼을 대체 입력으로 사용할 수 있으며, 반복되면 표시된 정확한 이유를 QA로 보내 주세요.",
    )
    return listOf(framing, gameMotion, playerRule, recovery)
}

@Composable
internal fun ArcadeTutorialScreen(
    mode: ArcadePlayMode,
    onComplete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = arcadeTutorialSteps(mode)
    var index by rememberSaveable(mode.name) { mutableIntStateOf(0) }
    val step = steps[index]
    BackHandler {
        if (index == 0) onBack() else index -= 1
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF071723), Color(0xFF102C3D), Color(0xFF071723)),
                ),
            )
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "게임 전 모션 튜토리얼  ${index + 1}/${steps.size}",
            color = Color(0xFF72E4FF),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = step.title,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = step.instruction,
            color = Color(0xFFE8F4FA),
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF173E50)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "직접 확인",
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = step.practiceCue,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (index == 0) onBack() else index -= 1
                },
            ) {
                Text(if (index == 0) "게임 선택" else "이전")
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (index == steps.lastIndex) onComplete() else index += 1
                },
            ) {
                Text(if (index == steps.lastIndex) "게임 시작" else "다음")
            }
        }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onComplete,
        ) {
            Text("튜토리얼 건너뛰기")
        }
    }
}
