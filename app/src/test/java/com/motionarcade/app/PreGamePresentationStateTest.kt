package com.motionarcade.app

import com.motionarcade.app.boxing.BOXER_OPTION_COUNT
import com.motionarcade.app.boxing.NO_BOXER_SELECTED
import com.motionarcade.app.boxing.boxingCharacterDrawable
import com.motionarcade.app.boxing.boxingCharacterName
import com.motionarcade.app.boxing.canConfirmBoxingMatchup
import com.motionarcade.app.fishing.FISHING_ROD_OPTION_COUNT
import com.motionarcade.app.fishing.NO_FISHING_ROD_SELECTED
import com.motionarcade.app.fishing.isFishingRodSelectionValid
import com.motionarcade.app.fishing.fishingArtPlayback
import com.motionarcade.app.fishing.fishingSelectionAtlasOffset
import com.motionarcade.app.fishing.fishingSelectionFrameForRod
import com.motionarcade.app.monster.MonsterArtCrop
import com.motionarcade.app.monster.monsterEnemyCrop
import com.motionarcade.games.monster.MonsterRaidBossPhase
import com.motionarcade.games.monster.MonsterRaidEnemy
import com.motionarcade.games.monster.MonsterRaidStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreGamePresentationStateTest {
    @Test
    fun fishingRequiresAnExplicitInRangeRodSelection() {
        assertFalse(isFishingRodSelectionValid(NO_FISHING_ROD_SELECTED))
        repeat(FISHING_ROD_OPTION_COUNT) { assertTrue(isFishingRodSelectionValid(it)) }
        assertFalse(isFishingRodSelectionValid(FISHING_ROD_OPTION_COUNT))
    }

    @Test
    fun boxingAcceptsEveryDistinctInRangeMatchupAndMapsEveryRealCharacter() {
        assertFalse(canConfirmBoxingMatchup(NO_BOXER_SELECTED, NO_BOXER_SELECTED))
        assertFalse(canConfirmBoxingMatchup(0, NO_BOXER_SELECTED))
        assertFalse(canConfirmBoxingMatchup(1, 1))
        assertTrue(canConfirmBoxingMatchup(0, BOXER_OPTION_COUNT - 1))
        assertTrue(canConfirmBoxingMatchup(1, 2))
        assertEquals(
            BOXER_OPTION_COUNT,
            (0 until BOXER_OPTION_COUNT).map(::boxingCharacterDrawable).toSet().size,
        )
        assertEquals(
            BOXER_OPTION_COUNT,
            (0 until BOXER_OPTION_COUNT).map(::boxingCharacterName).toSet().size,
        )
    }

    @Test
    fun sharedHudAnimationClampsNegativeTicksAndAdvancesEveryEightTicks() {
        assertEquals(0, sharedHudFrameForTick(-1))
        assertEquals(0, sharedHudFrameForTick(7))
        assertEquals(1, sharedHudFrameForTick(8))
        assertEquals(2, sharedHudFrameForTick(16))
        assertEquals(3, sharedHudFrameForTick(24))
        assertEquals(0, sharedHudFrameForTick(32))
    }

    @Test
    fun monsterV1SheetsSelectCommonEnemiesAndEveryBossPhase() {
        assertEquals(
            MonsterArtCrop(384, 256, 384, 512),
            monsterEnemyCrop(MonsterRaidStage.WAVE, MonsterRaidEnemy.MOSS_CRAWLER, null),
        )
        assertEquals(
            MonsterArtCrop(0, 256, 512, 512),
            monsterEnemyCrop(
                MonsterRaidStage.BOSS,
                MonsterRaidEnemy.TEMPEST_TITAN,
                MonsterRaidBossPhase.PHASE_1,
            ),
        )
        assertEquals(
            MonsterArtCrop(1024, 256, 512, 512),
            monsterEnemyCrop(
                MonsterRaidStage.BOSS,
                MonsterRaidEnemy.TEMPEST_TITAN,
                MonsterRaidBossPhase.PHASE_3,
            ),
        )
    }

    @Test
    fun generatedSelectionStatesMapToTheAuthoredAtlasCells() {
        assertEquals(0, fishingSelectionFrameForRod(NO_FISHING_ROD_SELECTED))
        assertEquals(1, fishingSelectionFrameForRod(0))
        assertEquals(2, fishingSelectionFrameForRod(1))
        assertEquals(3, fishingSelectionFrameForRod(2))
        assertEquals(androidx.compose.ui.unit.IntOffset(0, 0), fishingSelectionAtlasOffset(0))
        assertEquals(androidx.compose.ui.unit.IntOffset(540, 0), fishingSelectionAtlasOffset(1))
        assertEquals(androidx.compose.ui.unit.IntOffset(0, 960), fishingSelectionAtlasOffset(2))
        assertEquals(androidx.compose.ui.unit.IntOffset(540, 960), fishingSelectionAtlasOffset(3))
    }

    @Test
    fun fishingCatchPlaybackReachesGameplayRecoveryAndEveryCatchFrame() {
        assertEquals(listOf(2), fishingArtPlayback(false, 2).map { it.frame })
        val catchPlayback = fishingArtPlayback(true, 3)
        assertEquals(listOf(false, true, true, true, true), catchPlayback.map { it.catchSheet })
        assertEquals(listOf(3, 0, 1, 2, 3), catchPlayback.map { it.frame })
    }
}
