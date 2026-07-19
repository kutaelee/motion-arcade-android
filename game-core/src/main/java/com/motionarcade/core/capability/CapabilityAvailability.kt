package com.motionarcade.core.capability

/** Stable game-facing ML capability. This enum is not a persisted wire encoding. */
enum class MlCapability {
    A,
    B,
    C,
    UNSUPPORTED,
}

/** Slice 1B deliberately exposes only the conservative effect profile. */
enum class EffectCapability {
    CONSERVATIVE_UNVERIFIED,
}
