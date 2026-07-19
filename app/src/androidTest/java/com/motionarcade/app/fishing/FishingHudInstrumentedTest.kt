package com.motionarcade.app.fishing

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.motionarcade.app.MainActivity
import org.junit.Rule
import org.junit.Test

class FishingHudInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun portraitLargeTextKeepsActionsAndMetersReachable() {
        render(widthDp = 300, heightDp = 600, fontScale = 2f)

        composeRule.onNodeWithText("릴 한 바퀴 감기")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("일시정지")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("낚시 진행도 50퍼센트")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
    }

    @Test
    fun landscapeLargeTextKeepsPrimaryActionReachable() {
        render(widthDp = 340, heightDp = 300, fontScale = 2f)

        composeRule.onNodeWithText("릴 한 바퀴 감기")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("일시정지")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("낚싯줄 장력 60퍼센트")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.6f, 0f..1f))
    }

    private fun render(widthDp: Int, heightDp: Int, fontScale: Float) {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Box(modifier = Modifier.size(widthDp.dp, heightDp.dp)) {
                        FishingHud(
                            model = model(),
                            onAction = {},
                            onPause = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun model(): FishingHudModel = FishingHudModel(
        phaseLabel = "릴 감기",
        instruction = "한 손으로 작은 원을 그리거나 버튼을 반복해서 누르세요.",
        scoreLabel = "점수 420",
        progressLabel = "진행 50%",
        progressDescription = "낚시 진행도 50퍼센트",
        progress = 0.5f,
        tensionLabel = "장력 60%",
        tensionDescription = "낚싯줄 장력 60퍼센트",
        tension = 0.6f,
        trackingStatusLabel = "1명 인식 · 솔로 모션 준비",
        actionLabel = "릴 한 바퀴 감기",
        actionEnabled = true,
        pauseLabel = "일시정지",
        pauseEnabled = true,
    )
}
