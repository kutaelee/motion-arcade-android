package com.motionarcade.vision.tracking

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.TrackState
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Kinematic-only input for one pose in one frame.
 *
 * [observationId] is scoped to the containing frame. It is not a persistent identity and callers
 * must not derive it from a face, appearance descriptor, or other biometric identifier.
 */
data class PlayerTrackObservation(
    val observationId: Int,
    val pelvis: NormalizedPoint,
    val shoulderCenter: NormalizedPoint,
    val bodyScale: Double,
    val facingDirection: Double,
    val bounds: NormalizedBounds,
    val confidence: Double,
    val neutral: Boolean,
    /** Upstream viewport logic has positively observed this candidate leaving the playable frame. */
    val definitiveFrameExit: Boolean,
)

/**
 * Caller-generated identity for one detector frame.
 *
 * [sessionNonce] must be opaque and independent of pose, face, appearance, or any other biometric
 * input. [frameSequence] must strictly increase within that nonce's tracker session. The tracker
 * compares the nonce and sequence only for exact binding provenance and O(1) replay/rewind fences.
 */
data class PlayerObservationFrameToken(
    val sessionNonce: String,
    val frameSequence: Long,
) {
    init {
        require(sessionNonce.isNotBlank()) { "frame session nonce must not be blank" }
        require(sessionNonce.length <= 128) { "frame session nonce must not exceed 128 characters" }
        require(frameSequence > 0L) { "frame sequence must be positive" }
    }
}

data class PlayerObservationFrame(
    val frameToken: PlayerObservationFrameToken,
    val timestampNanos: Long,
    val calibrationRevision: Long,
    val observations: List<PlayerTrackObservation>,
)

data class NormalizedPoint(
    val x: Double,
    val y: Double,
)

data class NormalizedBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/** The initial or explicit re-arm role selection for the latest frame. */
data class RoleObservationBinding(
    val sourceFrameToken: PlayerObservationFrameToken,
    val sourceTimestampNanos: Long,
    val calibrationRevision: Long,
    val p1ObservationId: Int,
    val p2ObservationId: Int,
) {
    init {
        require(sourceTimestampNanos >= 0L) { "binding source timestamp must be non-negative" }
        require(calibrationRevision >= 0L) { "binding calibration revision must be non-negative" }
    }
}

/**
 * Versioned, caller-owned thresholds. There is intentionally no production default: release
 * values must come from a reviewed configuration and its fixtures rather than this algorithm.
 */
data class PlayerTrackerConfig(
    val configId: String,
    val schemaVersion: Int,
    val pelvisDistanceWeight: Double,
    val shoulderDistanceWeight: Double,
    val velocityMismatchWeight: Double,
    val scaleMismatchWeight: Double,
    val directionDiscontinuityWeight: Double,
    val lanePenaltyWeight: Double,
    val absoluteAssignmentGate: Double,
    val assignmentMargin: Double,
    val laneToleranceNormalized: Double,
    val minimumBodyScale: Double,
    val maximumNormalizedSpeedPerSecond: Double,
    val minimumObservationConfidence: Double,
    val rearmMinimumConfidence: Double,
    val tentativeDurationNanos: Long,
    val occlusionGraceNanos: Long,
    val lostAfterNanos: Long,
    val rearmNeutralDurationNanos: Long,
    val crossingHysteresisFrames: Int,
    val crossingHysteresisNanos: Long,
    val overlapIouPauseThreshold: Double,
    val proximityBodyScalePauseThreshold: Double,
    val maximumPredictionHorizonNanos: Long,
    val maximumStableObservationGapNanos: Long,
) {
    init {
        require(configId.isNotBlank()) { "configId must not be blank" }
        require(schemaVersion == 1) { "unsupported player tracker config schemaVersion: $schemaVersion" }
        val weights = listOf(
            pelvisDistanceWeight,
            shoulderDistanceWeight,
            velocityMismatchWeight,
            scaleMismatchWeight,
            directionDiscontinuityWeight,
            lanePenaltyWeight,
        )
        require(weights.all { it.isFinite() && it >= 0.0 }) { "cost weights must be finite and non-negative" }
        require(weights.any { it > 0.0 }) { "at least one cost weight must be positive" }
        require(absoluteAssignmentGate.isFinite() && absoluteAssignmentGate > 0.0) {
            "absoluteAssignmentGate must be finite and positive"
        }
        require(assignmentMargin.isFinite() && assignmentMargin > 0.0) {
            "assignmentMargin must be finite and positive"
        }
        require(laneToleranceNormalized.isFinite() && laneToleranceNormalized in 0.0..1.0) {
            "laneToleranceNormalized must be in 0..1"
        }
        require(minimumBodyScale.isFinite() && minimumBodyScale > 0.0 && minimumBodyScale <= 1.0) {
            "minimumBodyScale must be in (0, 1]"
        }
        require(maximumNormalizedSpeedPerSecond.isFinite() && maximumNormalizedSpeedPerSecond > 0.0) {
            "maximumNormalizedSpeedPerSecond must be finite and positive"
        }
        require(minimumObservationConfidence.isFinite() && minimumObservationConfidence in 0.0..1.0) {
            "minimumObservationConfidence must be in 0..1"
        }
        require(rearmMinimumConfidence.isFinite() && rearmMinimumConfidence in minimumObservationConfidence..1.0) {
            "rearmMinimumConfidence must be between minimumObservationConfidence and 1"
        }
        require(tentativeDurationNanos in 300_000_000L..500_000_000L) {
            "tentativeDurationNanos must satisfy the 300-500 ms SSOT interval"
        }
        require(occlusionGraceNanos in 1L..400_000_000L) {
            "occlusionGraceNanos must be within 400 ms"
        }
        require(lostAfterNanos >= 1_200_000_000L && lostAfterNanos > occlusionGraceNanos) {
            "lostAfterNanos must be at least 1.2 seconds and exceed occlusion grace"
        }
        require(rearmNeutralDurationNanos >= 1_000_000_000L) {
            "rearmNeutralDurationNanos must be at least one second"
        }
        require(crossingHysteresisFrames > 0 || crossingHysteresisNanos > 0L) {
            "crossing hysteresis needs a positive frame or time threshold"
        }
        require(crossingHysteresisFrames >= 0 && crossingHysteresisNanos >= 0L) {
            "crossing hysteresis thresholds must be non-negative"
        }
        require(overlapIouPauseThreshold.isFinite() && overlapIouPauseThreshold > 0.0 &&
            overlapIouPauseThreshold <= 1.0
        ) {
            "overlapIouPauseThreshold must be in (0, 1]"
        }
        require(
            proximityBodyScalePauseThreshold.isFinite() &&
                proximityBodyScalePauseThreshold > 0.0,
        ) { "proximityBodyScalePauseThreshold must be finite and positive" }
        require(maximumPredictionHorizonNanos in 0L..occlusionGraceNanos) {
            "maximumPredictionHorizonNanos must be within occlusion grace"
        }
        require(maximumStableObservationGapNanos in 1L..occlusionGraceNanos) {
            "maximumStableObservationGapNanos must be within occlusion grace"
        }
    }
}

/**
 * This slice deliberately has no release-approved state. Promotion is an external review gate,
 * so a runtime loaded here can only identify itself as an unverified candidate.
 */
enum class PlayerTrackerConfigReviewStatus {
    CANDIDATE_UNVERIFIED,
}

/** Exact-source evidence produced only after strict JSON parsing and config validation. */
class LoadedPlayerTrackerConfig internal constructor(
    val config: PlayerTrackerConfig,
    val schemaId: String,
    val sourceSha256Hex: String,
    val reviewStatus: PlayerTrackerConfigReviewStatus,
) {
    init {
        require(schemaId == PlayerTrackerConfigJson.SCHEMA_ID) { "unexpected config schema id" }
        require(sourceSha256Hex.matches(Regex("[0-9a-f]{64}"))) { "invalid source SHA-256" }
        require(reviewStatus == PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED) {
            "this slice accepts candidate/unverified configs only"
        }
    }
}

sealed interface PlayerTrackerConfigLoadResult {
    data class Success(val loaded: LoadedPlayerTrackerConfig) : PlayerTrackerConfigLoadResult

    data class Failure(
        val violation: PlayerTrackerConfigViolation,
        val detail: String,
    ) : PlayerTrackerConfigLoadResult
}

enum class PlayerTrackerConfigViolation {
    DOCUMENT_TOO_LARGE,
    INVALID_UTF8,
    MALFORMED_JSON,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    NON_FINITE_NUMBER,
    INTEGER_OUT_OF_RANGE,
    UNSUPPORTED_SCHEMA,
    REVIEW_STATUS_NOT_ALLOWED,
    INVALID_CONFIG_VALUE,
}

/** Asset locations are versioned configuration inputs, not approval state. */
object PlayerTrackerConfigAssets {
    const val DUAL_PLAYER_CANDIDATE_V1: String =
        "tracking-config/dual-player-candidate-v1.json"
}

/**
 * Strict loader and canonical writer for the versioned, flat JSON SSOT.
 *
 * Unknown, duplicate, or missing fields are rejected. JSON numbers destined for integral fields
 * must use integral syntax, and every floating-point value must be finite before the config
 * constructor applies its semantic bounds.
 */
object PlayerTrackerConfigJson {
    const val SCHEMA_ID = "motion-arcade.player-tracker-config.v1"
    private const val MAX_SOURCE_BYTES = 32 * 1024
    private const val REVIEW_STATUS_FIELD_VALUE = "CANDIDATE_UNVERIFIED"

    private val CONFIG_FIELD_NAMES = linkedSetOf(
        "schemaId",
        "reviewStatus",
        "configId",
        "schemaVersion",
        "pelvisDistanceWeight",
        "shoulderDistanceWeight",
        "velocityMismatchWeight",
        "scaleMismatchWeight",
        "directionDiscontinuityWeight",
        "lanePenaltyWeight",
        "absoluteAssignmentGate",
        "assignmentMargin",
        "laneToleranceNormalized",
        "minimumBodyScale",
        "maximumNormalizedSpeedPerSecond",
        "minimumObservationConfidence",
        "rearmMinimumConfidence",
        "tentativeDurationNanos",
        "occlusionGraceNanos",
        "lostAfterNanos",
        "rearmNeutralDurationNanos",
        "crossingHysteresisFrames",
        "crossingHysteresisNanos",
        "overlapIouPauseThreshold",
        "proximityBodyScalePauseThreshold",
        "maximumPredictionHorizonNanos",
        "maximumStableObservationGapNanos",
    )

    fun load(sourceBytes: ByteArray): PlayerTrackerConfigLoadResult {
        if (sourceBytes.size > MAX_SOURCE_BYTES) {
            return failure(PlayerTrackerConfigViolation.DOCUMENT_TOO_LARGE, "config exceeds $MAX_SOURCE_BYTES bytes")
        }
        val sourceSnapshot = sourceBytes.copyOf()
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sourceSnapshot))
                .toString()
        } catch (_: Exception) {
            return failure(PlayerTrackerConfigViolation.INVALID_UTF8, "config is not strict UTF-8")
        }

        val parsed = StrictFlatJsonParser(source).parse()
        val fields = when (parsed) {
            is ParseResult.Fields -> parsed.values
            is ParseResult.Error -> return failure(parsed.violation, parsed.detail)
        }
        val unknown = fields.keys - CONFIG_FIELD_NAMES
        if (unknown.isNotEmpty()) {
            return failure(
                PlayerTrackerConfigViolation.UNKNOWN_FIELD,
                "unknown field: ${unknown.sorted().first()}",
            )
        }
        val missing = CONFIG_FIELD_NAMES - fields.keys
        if (missing.isNotEmpty()) {
            return failure(
                PlayerTrackerConfigViolation.MISSING_FIELD,
                "missing field: ${missing.first()}",
            )
        }

        val reader = FieldReader(fields)
        val schemaId = reader.string("schemaId") ?: return reader.failure()
        if (schemaId != SCHEMA_ID) {
            return failure(PlayerTrackerConfigViolation.UNSUPPORTED_SCHEMA, "unsupported schemaId: $schemaId")
        }
        val reviewStatus = reader.string("reviewStatus") ?: return reader.failure()
        if (reviewStatus != REVIEW_STATUS_FIELD_VALUE) {
            return failure(
                PlayerTrackerConfigViolation.REVIEW_STATUS_NOT_ALLOWED,
                "only $REVIEW_STATUS_FIELD_VALUE is accepted",
            )
        }
        val schemaVersion = reader.int("schemaVersion") ?: return reader.failure()
        if (schemaVersion != 1) {
            return failure(
                PlayerTrackerConfigViolation.UNSUPPORTED_SCHEMA,
                "unsupported schemaVersion: $schemaVersion",
            )
        }

        val config = try {
            PlayerTrackerConfig(
                configId = reader.string("configId") ?: return reader.failure(),
                schemaVersion = schemaVersion,
                pelvisDistanceWeight = reader.double("pelvisDistanceWeight") ?: return reader.failure(),
                shoulderDistanceWeight = reader.double("shoulderDistanceWeight") ?: return reader.failure(),
                velocityMismatchWeight = reader.double("velocityMismatchWeight") ?: return reader.failure(),
                scaleMismatchWeight = reader.double("scaleMismatchWeight") ?: return reader.failure(),
                directionDiscontinuityWeight =
                    reader.double("directionDiscontinuityWeight") ?: return reader.failure(),
                lanePenaltyWeight = reader.double("lanePenaltyWeight") ?: return reader.failure(),
                absoluteAssignmentGate = reader.double("absoluteAssignmentGate") ?: return reader.failure(),
                assignmentMargin = reader.double("assignmentMargin") ?: return reader.failure(),
                laneToleranceNormalized = reader.double("laneToleranceNormalized") ?: return reader.failure(),
                minimumBodyScale = reader.double("minimumBodyScale") ?: return reader.failure(),
                maximumNormalizedSpeedPerSecond =
                    reader.double("maximumNormalizedSpeedPerSecond") ?: return reader.failure(),
                minimumObservationConfidence =
                    reader.double("minimumObservationConfidence") ?: return reader.failure(),
                rearmMinimumConfidence = reader.double("rearmMinimumConfidence") ?: return reader.failure(),
                tentativeDurationNanos = reader.long("tentativeDurationNanos") ?: return reader.failure(),
                occlusionGraceNanos = reader.long("occlusionGraceNanos") ?: return reader.failure(),
                lostAfterNanos = reader.long("lostAfterNanos") ?: return reader.failure(),
                rearmNeutralDurationNanos =
                    reader.long("rearmNeutralDurationNanos") ?: return reader.failure(),
                crossingHysteresisFrames = reader.int("crossingHysteresisFrames") ?: return reader.failure(),
                crossingHysteresisNanos = reader.long("crossingHysteresisNanos") ?: return reader.failure(),
                overlapIouPauseThreshold =
                    reader.double("overlapIouPauseThreshold") ?: return reader.failure(),
                proximityBodyScalePauseThreshold =
                    reader.double("proximityBodyScalePauseThreshold") ?: return reader.failure(),
                maximumPredictionHorizonNanos =
                    reader.long("maximumPredictionHorizonNanos") ?: return reader.failure(),
                maximumStableObservationGapNanos =
                    reader.long("maximumStableObservationGapNanos") ?: return reader.failure(),
            )
        } catch (error: IllegalArgumentException) {
            return failure(
                PlayerTrackerConfigViolation.INVALID_CONFIG_VALUE,
                error.message ?: "invalid config value",
            )
        }
        return PlayerTrackerConfigLoadResult.Success(
            LoadedPlayerTrackerConfig(
                config = config,
                schemaId = schemaId,
                sourceSha256Hex = sha256(sourceSnapshot),
                reviewStatus = PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED,
            ),
        )
    }

    /** Canonical bytes are intended for deterministic tests and candidate generation only. */
    fun encodeCandidate(config: PlayerTrackerConfig): ByteArray {
        val fields = linkedMapOf<String, String>()
        fields["schemaId"] = quote(SCHEMA_ID)
        fields["reviewStatus"] = quote(REVIEW_STATUS_FIELD_VALUE)
        fields["configId"] = quote(config.configId)
        fields["schemaVersion"] = config.schemaVersion.toString()
        fields["pelvisDistanceWeight"] = config.pelvisDistanceWeight.toString()
        fields["shoulderDistanceWeight"] = config.shoulderDistanceWeight.toString()
        fields["velocityMismatchWeight"] = config.velocityMismatchWeight.toString()
        fields["scaleMismatchWeight"] = config.scaleMismatchWeight.toString()
        fields["directionDiscontinuityWeight"] = config.directionDiscontinuityWeight.toString()
        fields["lanePenaltyWeight"] = config.lanePenaltyWeight.toString()
        fields["absoluteAssignmentGate"] = config.absoluteAssignmentGate.toString()
        fields["assignmentMargin"] = config.assignmentMargin.toString()
        fields["laneToleranceNormalized"] = config.laneToleranceNormalized.toString()
        fields["minimumBodyScale"] = config.minimumBodyScale.toString()
        fields["maximumNormalizedSpeedPerSecond"] = config.maximumNormalizedSpeedPerSecond.toString()
        fields["minimumObservationConfidence"] = config.minimumObservationConfidence.toString()
        fields["rearmMinimumConfidence"] = config.rearmMinimumConfidence.toString()
        fields["tentativeDurationNanos"] = config.tentativeDurationNanos.toString()
        fields["occlusionGraceNanos"] = config.occlusionGraceNanos.toString()
        fields["lostAfterNanos"] = config.lostAfterNanos.toString()
        fields["rearmNeutralDurationNanos"] = config.rearmNeutralDurationNanos.toString()
        fields["crossingHysteresisFrames"] = config.crossingHysteresisFrames.toString()
        fields["crossingHysteresisNanos"] = config.crossingHysteresisNanos.toString()
        fields["overlapIouPauseThreshold"] = config.overlapIouPauseThreshold.toString()
        fields["proximityBodyScalePauseThreshold"] = config.proximityBodyScalePauseThreshold.toString()
        fields["maximumPredictionHorizonNanos"] = config.maximumPredictionHorizonNanos.toString()
        fields["maximumStableObservationGapNanos"] = config.maximumStableObservationGapNanos.toString()
        val json = fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
            "${quote(it.key)}:${it.value}"
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun failure(
        violation: PlayerTrackerConfigViolation,
        detail: String,
    ): PlayerTrackerConfigLoadResult.Failure = PlayerTrackerConfigLoadResult.Failure(violation, detail)

    private sealed interface JsonScalar {
        data class StringValue(val value: String) : JsonScalar
        data class NumberValue(val raw: String) : JsonScalar
    }

    private sealed interface ParseResult {
        data class Fields(val values: LinkedHashMap<String, JsonScalar>) : ParseResult
        data class Error(
            val violation: PlayerTrackerConfigViolation,
            val detail: String,
        ) : ParseResult
    }

    private class FieldReader(
        private val fields: Map<String, JsonScalar>,
    ) {
        private var error: PlayerTrackerConfigLoadResult.Failure? = null

        fun string(name: String): String? {
            val value = fields.getValue(name)
            if (value is JsonScalar.StringValue) return value.value
            wrongType(name, "string")
            return null
        }

        fun double(name: String): Double? {
            val value = fields.getValue(name)
            if (value !is JsonScalar.NumberValue) {
                wrongType(name, "number")
                return null
            }
            val parsed = value.raw.toDoubleOrNull()
            if (parsed == null) {
                wrongType(name, "number")
                return null
            }
            if (!parsed.isFinite()) {
                error = failure(PlayerTrackerConfigViolation.NON_FINITE_NUMBER, "$name must be finite")
                return null
            }
            return parsed
        }

        fun long(name: String): Long? {
            val value = fields.getValue(name)
            if (value !is JsonScalar.NumberValue || !INTEGER_PATTERN.matches(value.raw)) {
                wrongType(name, "integer")
                return null
            }
            val parsed = value.raw.toLongOrNull()
            if (parsed == null) {
                error = failure(PlayerTrackerConfigViolation.INTEGER_OUT_OF_RANGE, "$name is out of Long range")
            }
            return parsed
        }

        fun int(name: String): Int? {
            val parsed = long(name) ?: return null
            if (parsed !in Int.MIN_VALUE..Int.MAX_VALUE) {
                error = failure(PlayerTrackerConfigViolation.INTEGER_OUT_OF_RANGE, "$name is out of Int range")
                return null
            }
            return parsed.toInt()
        }

        fun failure(): PlayerTrackerConfigLoadResult.Failure = checkNotNull(error)

        private fun wrongType(name: String, expected: String) {
            error = failure(PlayerTrackerConfigViolation.WRONG_TYPE, "$name must be a JSON $expected")
        }
    }

    private class StrictFlatJsonParser(
        private val source: String,
    ) {
        private var index = 0

        fun parse(): ParseResult {
            skipWhitespace()
            if (!consume('{')) return malformed("root must be an object")
            skipWhitespace()
            val values = linkedMapOf<String, JsonScalar>()
            if (consume('}')) return finish(values)
            while (true) {
                skipWhitespace()
                val key = parseString() ?: return malformed("object key must be a JSON string")
                if (values.containsKey(key)) {
                    return ParseResult.Error(
                        PlayerTrackerConfigViolation.DUPLICATE_FIELD,
                        "duplicate field: $key",
                    )
                }
                skipWhitespace()
                if (!consume(':')) return malformed("missing colon after $key")
                skipWhitespace()
                val value = when (peek()) {
                    '"' -> parseString()?.let(JsonScalar::StringValue)
                    '-', in '0'..'9' -> parseNumber()?.let(JsonScalar::NumberValue)
                    else -> null
                } ?: return malformed("$key must contain a string or number")
                values[key] = value
                skipWhitespace()
                when {
                    consume('}') -> return finish(values)
                    consume(',') -> {
                        skipWhitespace()
                        if (peek() == '}') return malformed("trailing comma is not allowed")
                    }
                    else -> return malformed("expected comma or object end")
                }
            }
        }

        private fun finish(values: LinkedHashMap<String, JsonScalar>): ParseResult {
            skipWhitespace()
            return if (index == source.length) {
                ParseResult.Fields(values)
            } else {
                malformed("trailing content after object")
            }
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> {
                        if (index >= source.length) return null
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) return null
                                val digits = source.substring(index, index + 4)
                                val codePoint = digits.toIntOrNull(16) ?: return null
                                result.append(codePoint.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                    }
                    character.code < 0x20 -> return null
                    else -> result.append(character)
                }
            }
            return null
        }

        private fun parseNumber(): String? {
            val start = index
            if (peek() == '-') index += 1
            when (peek()) {
                '0' -> index += 1
                in '1'..'9' -> {
                    index += 1
                    while (peek() in '0'..'9') index += 1
                }
                else -> return null
            }
            if (peek() == '.') {
                index += 1
                if (peek() !in '0'..'9') return null
                while (peek() in '0'..'9') index += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                index += 1
                if (peek() == '+' || peek() == '-') index += 1
                if (peek() !in '0'..'9') return null
                while (peek() in '0'..'9') index += 1
            }
            return source.substring(start, index)
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index += 1
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun consume(expected: Char): Boolean {
            if (peek() != expected) return false
            index += 1
            return true
        }

        private fun malformed(detail: String): ParseResult.Error = ParseResult.Error(
            PlayerTrackerConfigViolation.MALFORMED_JSON,
            "$detail at character $index",
        )
    }

    private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
}

@JvmInline
value class TrackId(val value: Long) {
    init {
        require(value > 0L) { "track id must be positive" }
    }
}

enum class IdentityPauseReason {
    NONE,
    TENTATIVE_TRACKS,
    INSUFFICIENT_OBSERVATIONS,
    ASSIGNMENT_AMBIGUOUS,
    CROSSING_HYSTERESIS,
    PLAYER_OVERLAP,
    REARM_REQUIRED,
    REARM_STABILITY,
    CALIBRATION_CHANGED,
}

data class RoleTrackAssignment(
    val roleId: PlayerId,
    val trackId: TrackId?,
    val observationId: Int?,
    val state: TrackState,
    val assignmentCost: Double?,
    val roleSequenceNumber: Long,
) {
    init {
        require(roleId == PlayerId.P1 || roleId == PlayerId.P2) { "only P1/P2 role assignments are supported" }
        require(observationId == null || observationId >= 0) { "observationId must be non-negative" }
        require(assignmentCost == null || assignmentCost.isFinite() && assignmentCost >= 0.0) {
            "assignmentCost must be finite and non-negative"
        }
        require(roleSequenceNumber > 0L) { "roleSequenceNumber must be positive" }
        require(state != TrackState.REARM || trackId == null) { "REARM must not claim a track" }
        val mustNotClaimObservation =
            state == TrackState.OCCLUDED || state == TrackState.AMBIGUOUS || state == TrackState.LOST
        require(!mustNotClaimObservation || observationId == null) {
            "$state must not claim a current observation"
        }
    }
}

data class RoleTrackTransition(
    val roleId: PlayerId,
    val sequenceNumber: Long,
    val previousState: TrackState?,
    val currentState: TrackState,
    val timestampNanos: Long,
) {
    init {
        require(roleId == PlayerId.P1 || roleId == PlayerId.P2) { "only P1/P2 transitions are supported" }
        require(sequenceNumber > 0L) { "transition sequenceNumber must be positive" }
        require(previousState == null || previousState != currentState) { "transition must change state" }
        require(timestampNanos >= 0L) { "transition timestamp must be non-negative" }
    }
}

data class PlayerTrackerOutput(
    val configId: String,
    val configSchemaVersion: Int,
    val configSchemaId: String,
    val configSourceSha256Hex: String,
    val configReviewStatus: PlayerTrackerConfigReviewStatus,
    val timestampNanos: Long,
    val calibrationRevision: Long,
    val assignments: List<RoleTrackAssignment>,
    val transitions: List<RoleTrackTransition>,
    val pauseRequired: Boolean,
    val pauseReason: IdentityPauseReason,
) {
    init {
        require(configId.isNotBlank()) { "configId must not be blank" }
        require(configSchemaVersion == 1) { "unsupported output config schema version" }
        require(configSchemaId == PlayerTrackerConfigJson.SCHEMA_ID) { "unsupported output config schema id" }
        require(configSourceSha256Hex.matches(Regex("[0-9a-f]{64}"))) { "invalid output config SHA-256" }
        require(configReviewStatus == PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED) {
            "this slice may emit candidate/unverified configs only"
        }
        require(timestampNanos >= 0L) { "output timestamp must be non-negative" }
        require(calibrationRevision >= 0L) { "output calibration revision must be non-negative" }
        require(assignments.size == 2) { "an output must preserve both role assignments" }
        require(assignments.map(RoleTrackAssignment::roleId) == listOf(PlayerId.P1, PlayerId.P2)) {
            "assignments must be ordered P1 then P2 without replacement"
        }
        require(transitions.map(RoleTrackTransition::roleId).distinct().size == transitions.size) {
            "a frame must contain at most one transition for each role"
        }
        require(transitions.all { it.timestampNanos == timestampNanos }) {
            "cross-player transitions must share the output timestamp"
        }
        require(pauseRequired == (pauseReason != IdentityPauseReason.NONE)) {
            "pauseRequired and pauseReason must agree"
        }
        require(pauseRequired || assignments.all { it.state == TrackState.ACTIVE }) {
            "unpaused output requires both roles ACTIVE"
        }
    }
}

sealed interface PlayerTrackerResult {
    data class Accepted(val output: PlayerTrackerOutput) : PlayerTrackerResult

    data class Rejected(
        val violation: PlayerTrackerViolation,
        val lastAcceptedOutput: PlayerTrackerOutput?,
    ) : PlayerTrackerResult
}

sealed interface RearmRequestResult {
    data class Accepted(val output: PlayerTrackerOutput) : RearmRequestResult

    data class Rejected(
        val violation: PlayerTrackerViolation,
        val lastAcceptedOutput: PlayerTrackerOutput?,
    ) : RearmRequestResult
}

enum class PlayerTrackerViolation {
    ALREADY_INITIALIZED,
    NOT_INITIALIZED,
    INVALID_FRAME,
    FRAME_TOKEN_REPLAY,
    TIMESTAMP_NOT_STRICTLY_INCREASING,
    CALIBRATION_REVISION_ROLLBACK,
    INVALID_ROLE_BINDING,
    REARM_NOT_REQUIRED,
}
