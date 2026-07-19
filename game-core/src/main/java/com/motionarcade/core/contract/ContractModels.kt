package com.motionarcade.core.contract

data class MotionEventEnvelope(
    val eventId: String,
    val sessionId: String,
    val playerId: PlayerId,
    val sequenceNumber: Long,
    val type: MotionType,
    val quality: Float,
    val confidence: Float,
    val eventTimestampNs: Long,
    val calibrationRevision: Int,
    val source: InputSource,
    val metadata: Map<String, Float>,
)

data class CalibrationPlayer(
    val playerId: PlayerId,
    val shoulderWidth: Float,
    val torsoLength: Float,
    val neutralVariance: Float,
    val dominantSide: DominantSide,
    val stance: Stance,
    val oneArmMode: OneArmMode,
)

data class CalibrationProfile(
    val schemaVersion: Int,
    val revision: Int,
    val mode: GameMode,
    val lensFacing: LensFacing,
    val orientation: DeviceOrientation,
    val players: List<CalibrationPlayer>,
    val valid: Boolean,
)

data class PoseLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)

data class NormalizedPose(
    val trackId: String,
    val trackState: TrackState,
    val landmarks: Map<String, PoseLandmark>,
    val scaleConfidence: Float,
)

data class NormalizedPoseFrame(
    val frameId: Long,
    val sourceTimestampNs: Long,
    val calibrationRevision: Int,
    val poses: List<NormalizedPose>,
)

data class DeterministicPrngState(
    val algorithmId: String,
    val algorithmVersion: Int,
    val state: List<Long>,
)

data class GameSessionSnapshot(
    val schemaVersion: Int,
    val sessionId: String,
    val gameId: GameId,
    val mode: GameMode,
    val simulationTick: Long,
    val status: SessionStatus,
    val seed: Long,
    val prng: DeterministicPrngState,
    val contentRevision: String,
    val pauseReason: PauseReason?,
    /** Temporary boundary; must be replaced by versioned game-state schemas before G1. */
    val state: TemporaryCanonicalJsonObject,
    /** Temporary boundary; must be replaced by a typed player-state schema before G1. */
    val players: List<TemporaryCanonicalJsonObject>,
    val committedRewardIds: List<String>,
)

data class DiagnosticDevice(
    val model: String,
    val osVersion: String,
    val memoryClassMb: Int,
)

data class DiagnosticSession(
    val gameId: GameId,
    val mode: GameMode,
    val lensFacing: LensFacing,
    val orientation: DeviceOrientation,
    val qualityTier: String,
)

data class DiagnosticEvent(
    val type: DiagnosticEventType,
    val timestampNs: Long,
    val value: Double?,
)

data class RedactedDiagnosticBundle(
    val schemaVersion: Int,
    val appVersion: String,
    val device: DiagnosticDevice,
    val session: DiagnosticSession,
    val metrics: Map<DiagnosticMetric, Double>,
    val events: List<DiagnosticEvent>,
    val containsRawImages: Boolean,
    val containsPoseCoordinates: Boolean,
    val userConsentedToPoseExport: Boolean,
)
