package com.motionarcade.app

import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseFailureReason
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeTutorialTest {
    @Test
    fun `tutorial gate blocks every runtime until completion`() {
        ArcadePlayMode.entries
            .filterNot { it == ArcadePlayMode.FISHING }
            .forEach { mode ->
                assertTrue(shouldPresentArcadeTutorial(mode, fishingRodConfirmed = false, tutorialCompleted = false))
                assertTrue(!shouldPresentArcadeTutorial(mode, fishingRodConfirmed = false, tutorialCompleted = true))
            }

        assertTrue(!shouldPresentArcadeTutorial(ArcadePlayMode.FISHING, fishingRodConfirmed = false, tutorialCompleted = false))
        assertTrue(shouldPresentArcadeTutorial(ArcadePlayMode.FISHING, fishingRodConfirmed = true, tutorialCompleted = false))
        assertTrue(!shouldPresentArcadeTutorial(ArcadePlayMode.FISHING, fishingRodConfirmed = true, tutorialCompleted = true))
    }

    @Test
    fun `every mode has stable complete tutorial contract`() {
        ArcadePlayMode.entries.forEach { mode ->
            val steps = arcadeTutorialSteps(mode)

            assertEquals("$mode step count", 4, steps.size)
            assertEquals("$mode unique ids", steps.size, steps.map { it.id }.distinct().size)
            assertTrue("$mode upper-body framing", steps.any { "상체" in it.title || "전신은 필요하지" in it.instruction })
            assertTrue("$mode touch fallback", steps.any { "터치" in it.practiceCue })
            assertTrue("$mode recovery", steps.any { it.id == "recognition-recovery" })
            assertTrue("$mode no firewall", steps.any { "방화벽 설정은 필요하지" in it.instruction })
            assertTrue("$mode hip framing", steps.any { "양쪽 골반" in it.instruction })
            assertTrue("$mode paused scoring", steps.any { "모션과 터치 모두 점수에 반영되지" in it.practiceCue })
        }
    }

    @Test
    fun `each game teaches its own motion vocabulary`() {
        listOf(ArcadePlayMode.FISHING, ArcadePlayMode.FISHING_DUAL).forEach { mode ->
            val text = arcadeTutorialSteps(mode).joinToString { it.title + it.instruction }
            assertTrue("$mode cast", "캐스팅" in text)
            assertTrue("$mode hook", "훅" in text)
            assertTrue("$mode reel", "릴" in text)
        }
        listOf(ArcadePlayMode.BOXING_SOLO, ArcadePlayMode.BOXING_DUAL).forEach { mode ->
            val text = arcadeTutorialSteps(mode).joinToString { it.title + it.instruction }
            assertTrue("$mode guard", "가드" in text)
            assertTrue("$mode punch", "펀치" in text)
        }
        listOf(ArcadePlayMode.MONSTER_SOLO, ArcadePlayMode.MONSTER_DUAL).forEach { mode ->
            val text = arcadeTutorialSteps(mode).joinToString { it.title + it.instruction }
            assertTrue("$mode attack", "공격" in text)
            assertTrue("$mode strong attack", "강공격" in text)
            assertTrue("$mode re-arm", "재무장" in text)
        }
    }

    @Test
    fun `dual tutorials teach roles crossing spacing and rearm`() {
        val dualModes = listOf(
            ArcadePlayMode.FISHING_DUAL,
            ArcadePlayMode.BOXING_DUAL,
            ArcadePlayMode.MONSTER_DUAL,
        )

        dualModes.forEach { mode ->
            val steps = arcadeTutorialSteps(mode)
            val rule = steps.single { it.id == "dual-player-safety" }
            assertTrue("$mode player labels", "P1" in rule.instruction && "P2" in rule.instruction)
            assertTrue("$mode spacing", "한 팔 간격" in rule.practiceCue)
            if (mode != ArcadePlayMode.FISHING_DUAL) {
                assertTrue("$mode crossing", "교차" in rule.instruction)
                assertTrue("$mode rearm", "재무장" in rule.instruction)
            }
        }
        val dualFishingRule = arcadeTutorialSteps(ArcadePlayMode.FISHING_DUAL)
            .single { it.id == "dual-player-safety" }
        assertTrue("dual fishing explicit rearm", "Set / re-arm P1 + P2" in dualFishingRule.instruction)
        assertTrue("dual fishing neutral", "중립 자세" in dualFishingRule.instruction)
    }

    @Test
    fun `recovery controls match controls available in each route`() {
        val soloFishing = arcadeTutorialSteps(ArcadePlayMode.FISHING).single { it.id == "recognition-recovery" }
        assertTrue("solo fishing runtime restart", "게임 런타임 다시 시작" in soloFishing.instruction)
        assertTrue("solo fishing camera retry", "카메라 다시 연결" in soloFishing.instruction)
        val failedInference = LivePoseInferenceSnapshot(
            sessionGeneration = 1L,
            revision = 1L,
            phase = LivePoseInferencePhase.FAILED,
            poseCount = null,
            callbackCount = 0L,
            resultTimestampMs = null,
            failureReason = LivePoseFailureReason.SESSION_CREATE_FAILED,
        )
        assertEquals(
            setOf(FishingRecoveryControl.CAMERA_RECONNECT),
            fishingRecoveryControls(
                runtimeFailed = false,
                status = FrontCameraPreviewStatus.ACTIVE,
                inference = failedInference,
                rebindAttempts = 0,
            ),
        )
        assertEquals(
            setOf(FishingRecoveryControl.RUNTIME_RESTART),
            fishingRecoveryControls(
                runtimeFailed = true,
                status = FrontCameraPreviewStatus.ACTIVE,
                inference = LivePoseInferenceSnapshot.idle(),
                rebindAttempts = 0,
            ),
        )
        assertEquals(
            setOf(FishingRecoveryControl.CAMERA_RECONNECT),
            fishingRecoveryControls(
                runtimeFailed = false,
                status = FrontCameraPreviewStatus.BIND_FAILED,
                inference = LivePoseInferenceSnapshot.idle(),
                rebindAttempts = 0,
            ),
        )

        listOf(
            ArcadePlayMode.FISHING_DUAL,
            ArcadePlayMode.BOXING_DUAL,
            ArcadePlayMode.MONSTER_DUAL,
        ).forEach { mode ->
            val recovery = arcadeTutorialSteps(mode).single { it.id == "recognition-recovery" }
            assertTrue("$mode explicit rearm", "Set / re-arm P1 + P2" in recovery.instruction)
            assertTrue("$mode route restart", "게임 선택으로 나갔다가" in recovery.instruction)
        }
        listOf(ArcadePlayMode.BOXING_SOLO, ArcadePlayMode.MONSTER_SOLO).forEach { mode ->
            val recovery = arcadeTutorialSteps(mode).single { it.id == "recognition-recovery" }
            assertTrue("$mode route restart", "게임 선택으로 나갔다가" in recovery.instruction)
            assertTrue("$mode no absent button", "게임 런타임 다시 시작" !in recovery.instruction)
        }
    }
}
