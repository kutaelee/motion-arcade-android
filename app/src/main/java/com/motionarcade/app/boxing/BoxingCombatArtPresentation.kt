package com.motionarcade.app.boxing

import com.motionarcade.games.boxing.BoxingSnapshot
import com.motionarcade.games.boxing.BoxingDodgeDirection

internal enum class BoxingCombatArtSheet {
    RUSH_ATTACKS,
    VOLT_ATTACKS,
}

internal data class BoxingCombatArtState(
    val sheet: BoxingCombatArtSheet = BoxingCombatArtSheet.RUSH_ATTACKS,
    val frame: Int = BOXING_ART_NEUTRAL_FRAME,
)

internal fun soloPlayerVisualAction(
    snapshot: BoxingSnapshot,
    art: BoxingCombatArtState,
): BoxingVisualAction = when {
    snapshot.lastReceivedDamage > 0 -> BoxingVisualAction.HIT
    snapshot.guardTicksRemaining > 0 -> BoxingVisualAction.GUARD
    snapshot.dodgeTicksRemaining > 0 && snapshot.dodgeDirection == BoxingDodgeDirection.LEFT ->
        BoxingVisualAction.DODGE_LEFT
    snapshot.dodgeTicksRemaining > 0 && snapshot.dodgeDirection == BoxingDodgeDirection.RIGHT ->
        BoxingVisualAction.DODGE_RIGHT
    art.sheet == BoxingCombatArtSheet.RUSH_ATTACKS && art.frame != BOXING_ART_NEUTRAL_FRAME ->
        BoxingVisualAction.ATTACK
    else -> BoxingVisualAction.NEUTRAL
}

internal fun soloAiVisualAction(
    snapshot: BoxingSnapshot,
    art: BoxingCombatArtState,
): BoxingVisualAction = when {
    snapshot.lastPlayerDamage > 0 -> BoxingVisualAction.HIT
    art.sheet == BoxingCombatArtSheet.VOLT_ATTACKS && art.frame != BOXING_ART_NEUTRAL_FRAME ->
        BoxingVisualAction.ATTACK
    else -> BoxingVisualAction.NEUTRAL
}

internal fun boxingArtForAcceptedPlayerPunch(): BoxingCombatArtState =
    BoxingCombatArtState(BoxingCombatArtSheet.RUSH_ATTACKS, BOXING_ART_LOAD_FRAME)

internal fun boxingArtForAdvance(
    previous: BoxingSnapshot,
    current: BoxingSnapshot,
    priorArt: BoxingCombatArtState,
): BoxingCombatArtState {
    val playerContact = current.lastPlayerDamage > 0
    val aiContact = current.lastReceivedDamage > 0 ||
        current.blockedAttackCount > previous.blockedAttackCount
    return when {
        playerContact && aiContact -> BoxingCombatArtState()
        playerContact -> BoxingCombatArtState(BoxingCombatArtSheet.RUSH_ATTACKS, BOXING_ART_CONTACT_FRAME)
        aiContact -> BoxingCombatArtState(BoxingCombatArtSheet.VOLT_ATTACKS, BOXING_ART_CONTACT_FRAME)
        priorArt.frame == BOXING_ART_CONTACT_FRAME -> priorArt.copy(frame = BOXING_ART_RECOVERY_FRAME)
        current.aiTelegraph != null ->
            BoxingCombatArtState(BoxingCombatArtSheet.VOLT_ATTACKS, BOXING_ART_LOAD_FRAME)
        else -> BoxingCombatArtState()
    }
}

internal fun boxingArtSourceOffset(frame: Int): IntOffsetPair {
    require(frame in 0 until BOXING_ART_FRAME_COUNT)
    return IntOffsetPair(
        x = (frame % BOXING_ART_COLUMNS) * BOXING_ART_FRAME_WIDTH_PX,
        y = (frame / BOXING_ART_COLUMNS) * BOXING_ART_FRAME_HEIGHT_PX,
    )
}

internal data class IntOffsetPair(val x: Int, val y: Int)

internal const val BOXING_ART_NEUTRAL_FRAME = 0
internal const val BOXING_ART_LOAD_FRAME = 1
internal const val BOXING_ART_CONTACT_FRAME = 2
internal const val BOXING_ART_RECOVERY_FRAME = 3
