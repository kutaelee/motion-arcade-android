package com.motionarcade.app.boxing

import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.games.boxing.BoxingAiAttack
import com.motionarcade.games.boxing.BoxingGameSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxingCombatArtPresentationTest {
    private val neutral = BoxingGameSession.start(
        sessionId = "art-test",
        seed = 17L,
        calibrationRevision = 1,
        mode = GameMode.SOLO,
    ).snapshot

    private val dualPlayer = BoxingGameSession.start(
        sessionId = "arena-test",
        seed = 18L,
        calibrationRevision = 1,
        mode = GameMode.DUAL,
    ).snapshot.players.getValue(PlayerId.P1)

    @Test
    fun `v1 arena maps semantic actions to bounded transforms and fx crops`() {
        assertEquals(BoxingVisualAction.ATTACK, boxingVisualActionFor(MotionType.PUNCH_JAB))
        assertEquals(BoxingVisualAction.ATTACK, boxingVisualActionFor(MotionType.PUNCH_HOOK))
        assertEquals(BoxingVisualAction.GUARD, boxingVisualActionFor(MotionType.BOXING_GUARD))
        assertEquals(BoxingVisualAction.DODGE_LEFT, boxingVisualActionFor(MotionType.DODGE_LEFT))
        assertEquals(BoxingVisualAction.DODGE_RIGHT, boxingVisualActionFor(MotionType.DODGE_RIGHT))
        assertNull(boxingFxCrop(BoxingVisualAction.NEUTRAL))

        BoxingVisualAction.entries.filter { it != BoxingVisualAction.NEUTRAL }.forEach { action ->
            val crop = requireNotNull(boxingFxCrop(action))
            assertTrue(crop.x >= 0 && crop.y >= 0)
            assertTrue(crop.width > 0 && crop.height > 0)
            assertTrue(crop.x + crop.width <= 1536)
            assertTrue(crop.y + crop.height <= 1024)
        }

        assertTrue(boxerVisualTransform(BoxingVisualAction.ATTACK, isLeft = true).translationXFraction > 0f)
        assertTrue(boxerVisualTransform(BoxingVisualAction.ATTACK, isLeft = false).translationXFraction < 0f)
    }

    @Test
    fun `health defense and dodge transitions select honest feedback`() {
        assertEquals(
            BoxingVisualAction.HIT,
            defensiveVisualTransition(dualPlayer, dualPlayer.copy(health = dualPlayer.health - 1)),
        )
        assertEquals(
            BoxingVisualAction.GUARD,
            defensiveVisualTransition(
                dualPlayer,
                dualPlayer.copy(blockedAttackCount = dualPlayer.blockedAttackCount + 1),
            ),
        )
        assertNull(defensiveVisualTransition(dualPlayer, dualPlayer))
    }

    @Test
    fun `portrait atlas offsets address all four row-major cells`() {
        assertEquals(IntOffsetPair(0, 0), boxingArtSourceOffset(0))
        assertEquals(IntOffsetPair(540, 0), boxingArtSourceOffset(1))
        assertEquals(IntOffsetPair(0, 960), boxingArtSourceOffset(2))
        assertEquals(IntOffsetPair(540, 960), boxingArtSourceOffset(3))
    }

    @Test
    fun `accepted player punch reaches load contact recovery and neutral`() {
        val load = boxingArtForAcceptedPlayerPunch()
        assertEquals(BoxingCombatArtState(BoxingCombatArtSheet.RUSH_ATTACKS, 1), load)

        val contactSnapshot = neutral.copy(lastPlayerDamage = 8)
        val contact = boxingArtForAdvance(neutral, contactSnapshot, load)
        assertEquals(BoxingCombatArtState(BoxingCombatArtSheet.RUSH_ATTACKS, 2), contact)

        val recovery = boxingArtForAdvance(contactSnapshot, neutral, contact)
        assertEquals(BoxingCombatArtState(BoxingCombatArtSheet.RUSH_ATTACKS, 3), recovery)
        assertEquals(BoxingCombatArtState(), boxingArtForAdvance(neutral, neutral, recovery))
    }

    @Test
    fun `ai telegraph and resolved defense use volt sequence`() {
        val telegraph = neutral.copy(
            aiTelegraph = BoxingAiAttack.STRAIGHT,
            aiTelegraphTicksRemaining = 2,
        )
        val load = boxingArtForAdvance(neutral, telegraph, BoxingCombatArtState())
        assertEquals(BoxingCombatArtState(BoxingCombatArtSheet.VOLT_ATTACKS, 1), load)

        val guarded = neutral.copy(blockedAttackCount = neutral.blockedAttackCount + 1)
        val contact = boxingArtForAdvance(telegraph, guarded, load)
        assertEquals(BoxingCombatArtState(BoxingCombatArtSheet.VOLT_ATTACKS, 2), contact)

        val recovery = boxingArtForAdvance(guarded, guarded, contact)
        assertEquals(BoxingCombatArtState(BoxingCombatArtSheet.VOLT_ATTACKS, 3), recovery)
    }

    @Test
    fun `successful dodge never renders glove contact`() {
        val telegraph = neutral.copy(
            aiTelegraph = BoxingAiAttack.STRAIGHT,
            aiTelegraphTicksRemaining = 1,
        )
        val dodged = neutral.copy(dodgedAttackCount = neutral.dodgedAttackCount + 1)
        assertEquals(
            BoxingCombatArtState(),
            boxingArtForAdvance(
                telegraph,
                dodged,
                BoxingCombatArtState(BoxingCombatArtSheet.VOLT_ATTACKS, BOXING_ART_LOAD_FRAME),
            ),
        )
    }

    @Test
    fun `simultaneous player and ai resolution stays neutral instead of inventing priority`() {
        val simultaneous = neutral.copy(
            lastPlayerDamage = 8,
            lastReceivedDamage = 6,
        )
        assertEquals(
            BoxingCombatArtState(),
            boxingArtForAdvance(neutral, simultaneous, boxingArtForAcceptedPlayerPunch()),
        )
    }
}
