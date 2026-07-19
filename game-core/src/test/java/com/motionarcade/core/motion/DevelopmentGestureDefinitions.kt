package com.motionarcade.core.motion

import com.motionarcade.core.contract.MotionType

/** Test-only thresholds. Product code must load its versioned gesture config. */
object DevelopmentGestureDefinitions {
    val all: List<GestureDefinition> = MotionType.entries.map { type ->
        GestureDefinition(
            type = type,
            entryThreshold = 0.75f,
            exitThreshold = 0.35f,
            minimumConfidence = 0.5f,
            minimumHoldNs = 120_000_000L,
            maximumCandidateNs = 1_000_000_000L,
            cooldownNs = 180_000_000L,
            neutralRearmNs = 120_000_000L,
            exclusivityGroup = when (type) {
                MotionType.FISH_READY,
                MotionType.FISH_CAST,
                MotionType.FISH_HOOK,
                MotionType.FISH_REEL_CYCLE,
                MotionType.FISH_TENSION_LEFT,
                MotionType.FISH_TENSION_RIGHT,
                MotionType.FISH_NET,
                -> "FISHING_ARMS"
                MotionType.PUNCH_JAB, MotionType.PUNCH_HOOK -> "BOXING_STRIKE"
                MotionType.BOXING_GUARD -> "BOXING_GUARD"
                MotionType.MONSTER_BLOCK,
                MotionType.MONSTER_REVIVE,
                MotionType.MONSTER_SKILL_ONE,
                MotionType.MONSTER_SKILL_TWO,
                MotionType.MONSTER_MAGIC_CHARGE,
                MotionType.TEAM_ULTIMATE,
                -> "MONSTER_ARMS"
                MotionType.DODGE_LEFT, MotionType.DODGE_RIGHT -> "BOXING_DODGE"
            },
            priority = when (type) {
                MotionType.PUNCH_HOOK -> 20
                MotionType.PUNCH_JAB -> 10
                else -> 0
            },
        )
    }
}
