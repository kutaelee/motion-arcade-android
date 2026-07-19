package com.motionarcade.core.contract

import java.util.Collections
import java.util.LinkedHashSet

enum class PlayerId { P1, P2, AI }

enum class InputSource { MOTION, TOUCH, FIXTURE }

enum class GameMode { SOLO, DUAL }

enum class LensFacing { FRONT, BACK }

enum class DeviceOrientation { PORTRAIT, LANDSCAPE }

enum class DominantSide { LEFT, RIGHT, UNSPECIFIED }

enum class Stance { ORTHODOX, SOUTHPAW, NEUTRAL }

enum class OneArmMode { OFF, LEFT_ONLY, RIGHT_ONLY }

enum class TrackState { TENTATIVE, ACTIVE, OCCLUDED, AMBIGUOUS, LOST, REARM }

enum class GameId { FISHING, BOXING, MONSTER }

enum class SessionStatus { COUNTDOWN, RUNNING, PAUSED, COMPLETED, ABORTED }

/** Closed runtime registry from the SSOT lifecycle contract. */
enum class PauseReason {
    USER,
    APP_BACKGROUND,
    ROTATION_REBIND,
    CAMERA_SWITCH,
    CAMERA_ERROR,
    POSE_LOST,
    PLAYER_OVERLAP,
    PLAYER_LANE_CROSS,
    THERMAL_CRITICAL,
    EVENT_QUEUE_OVERFLOW,
}

/**
 * Version 2 of the executable motion-type registry.
 *
 * The JSON wire remains a pattern-constrained string. Only SSOT-named values are
 * executable; an unknown wire value fails conversion before game state mutation.
 */
enum class MotionType {
    PUNCH_JAB,
    PUNCH_HOOK,
    BOXING_GUARD,
    DODGE_LEFT,
    DODGE_RIGHT,
    FISH_READY,
    FISH_CAST,
    FISH_HOOK,
    FISH_REEL_CYCLE,
    FISH_TENSION_LEFT,
    FISH_TENSION_RIGHT,
    FISH_NET,
    MONSTER_BLOCK,
    MONSTER_REVIVE,
    MONSTER_SKILL_ONE,
    MONSTER_SKILL_TWO,
    MONSTER_MAGIC_CHARGE,
    TEAM_ULTIMATE,
}

object MotionTypeRegistry {
    const val VERSION: Int = 6

    private val version1Types: Set<MotionType> = immutableTypes(
        setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.DODGE_LEFT,
            MotionType.FISH_CAST,
            MotionType.FISH_REEL_CYCLE,
            MotionType.MONSTER_BLOCK,
            MotionType.TEAM_ULTIMATE,
        ),
    )

    private val version2Types: Set<MotionType> = immutableTypes(
        setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.DODGE_LEFT,
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
            MotionType.MONSTER_BLOCK,
            MotionType.TEAM_ULTIMATE,
        ),
    )

    private val version3Types: Set<MotionType> = immutableTypes(
        MotionType.entries.filter {
            it != MotionType.MONSTER_REVIVE &&
                it != MotionType.MONSTER_SKILL_ONE &&
                it != MotionType.MONSTER_SKILL_TWO &&
                it != MotionType.MONSTER_MAGIC_CHARGE
        },
    )

    private val version4Types: Set<MotionType> = immutableTypes(
        MotionType.entries.filter {
            it != MotionType.MONSTER_SKILL_ONE &&
                it != MotionType.MONSTER_SKILL_TWO &&
                it != MotionType.MONSTER_MAGIC_CHARGE
        },
    )

    private val version5Types: Set<MotionType> = immutableTypes(
        MotionType.entries.filter { it != MotionType.MONSTER_MAGIC_CHARGE },
    )

    val executableTypes: Set<MotionType> = immutableTypes(MotionType.entries)

    fun fromWire(value: String): MotionType? = executableTypes.firstOrNull { it.name == value }

    fun executableTypesForVersion(version: Int): Set<MotionType>? = when (version) {
        1 -> version1Types
        2 -> version2Types
        3 -> version3Types
        4 -> version4Types
        5 -> version5Types
        VERSION -> executableTypes
        else -> null
    }

    fun isExecutableInVersion(version: Int, type: MotionType): Boolean =
        type in (executableTypesForVersion(version) ?: emptySet())

    private fun immutableTypes(values: Collection<MotionType>): Set<MotionType> =
        Collections.unmodifiableSet(LinkedHashSet(values))
}

enum class DiagnosticMetric {
    SOURCE_FPS,
    ANALYZER_DROP_COUNT,
    FRAME_AGE_MS,
    INFERENCE_P50_MS,
    INFERENCE_P95_MS,
    INFERENCE_P99_MS,
    POSE_COUNT,
    LOW_CONFIDENCE_DURATION_MS,
    TRANSFORM_INVALID_COUNT,
    TRACK_AMBIGUITY_COUNT,
    TRACK_AMBIGUITY_DURATION_MS,
    DUPLICATE_EVENT_COUNT,
    EVENT_END_TO_END_P95_MS,
    SIMULATION_TICK_DRIFT_MS,
    INPUT_QUEUE_DEPTH,
    FIXED_STEP_CATCH_UP_COUNT,
    RENDER_P50_MS,
    RENDER_P90_MS,
    RENDER_P99_MS,
    MEMORY_CLASS_MB,
    MEMORY_USED_MB,
    THERMAL_TIER,
    QUALITY_DEGRADATION_LEVEL,
}

enum class DiagnosticEventType {
    CAMERA_BOUND,
    DELEGATE_FALLBACK,
    CALIBRATION_DRIFT,
    TRACK_AMBIGUOUS,
    GAME_PAUSED,
    EVENT_DUPLICATE_REJECTED,
    THERMAL_DEGRADE,
    CHECKPOINT_SAVED,
}
