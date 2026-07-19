package com.motionarcade.app.monster

import com.motionarcade.core.contract.MotionType
import com.motionarcade.games.monster.MonsterRaidClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterHeroAnimationTest {
    @Test
    fun fourByFourCropsStayInsideNonDivisibleImageBounds() {
        val crops = MonsterHeroAction.entries.flatMap { action ->
            (0 until 4).map { frame -> monsterHeroCrop(1254, 1254, action, frame) }
        }

        assertEquals(16, crops.distinct().size)
        crops.forEach { crop ->
            assertTrue(crop.x >= 0 && crop.y >= 0)
            assertTrue(crop.x + crop.width <= 1254)
            assertTrue(crop.y + crop.height <= 1254)
        }
    }

    @Test
    fun classSpecificDefenseAndReviveUseTheirRegisteredRows() {
        assertEquals(
            MonsterHeroAction.DEFENSE_OR_CHARGE,
            monsterHeroAction(MonsterRaidClass.VANGUARD, MotionType.MONSTER_BLOCK),
        )
        assertEquals(
            MonsterHeroAction.DEFENSE_OR_CHARGE,
            monsterHeroAction(MonsterRaidClass.RANGER, MotionType.MONSTER_MAGIC_CHARGE),
        )
        assertEquals(
            MonsterHeroAction.REVIVE,
            monsterHeroAction(MonsterRaidClass.RANGER, MotionType.MONSTER_REVIVE),
        )
    }
}
