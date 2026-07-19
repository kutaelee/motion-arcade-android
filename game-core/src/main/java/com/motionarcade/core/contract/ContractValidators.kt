package com.motionarcade.core.contract

/** Validation for domain objects constructed without the JSON boundary. */
object ContractValidators {
    fun validate(value: MotionEventEnvelope): ContractResult<MotionEventEnvelope> {
        val violations = mutableListOf<ContractViolation>()
        boundedString(value.eventId, "$/eventId", 1, 160, violations)
        boundedString(value.sessionId, "$/sessionId", 1, 120, violations)
        nonNegative(value.sequenceNumber, "$/sequenceNumber", violations)
        if (value.sessionId.length in 1..120 && value.sequenceNumber >= 0L) {
            val expectedId = when (
                val generated = DeterministicEventId.create(
                    value.sessionId,
                    value.playerId,
                    value.sequenceNumber,
                )
            ) {
                is ContractResult.Valid -> generated.value
                is ContractResult.Invalid -> error("Preconditions guarantee a deterministic ID")
            }
            if (value.eventId != expectedId) {
                violations += ContractViolation.InvalidFormat(
                    "$/eventId",
                    "deterministic sessionId/playerId/sequenceNumber ID",
                    value.eventId,
                )
            }
        }
        boundedUnit(value.quality, "$/quality", violations)
        boundedUnit(value.confidence, "$/confidence", violations)
        nonNegative(value.eventTimestampNs, "$/eventTimestampNs", violations)
        nonNegative(value.calibrationRevision, "$/calibrationRevision", violations)
        if (value.metadata.size > 32) {
            violations += ContractViolation.OutOfRange(
                "$/metadata",
                "at most 32 properties",
                value.metadata.size.toString(),
            )
        }
        value.metadata.forEach { (key, number) -> finite(number, "$/metadata/${pointer(key)}", violations) }
        return result(value, violations)
    }

    fun validate(value: CalibrationProfile): ContractResult<CalibrationProfile> {
        val violations = mutableListOf<ContractViolation>()
        exact(value.schemaVersion, "$/schemaVersion", 1, violations)
        nonNegative(value.revision, "$/revision", violations)
        size(value.players.size, "$/players", 1, 2, violations)
        value.players.forEachIndexed { index, player ->
            val path = "$/players/$index"
            if (player.playerId == PlayerId.AI) {
                violations += ContractViolation.UnknownEnumValue("$path/playerId", "CalibrationPlayerId", "AI")
            }
            positive(player.shoulderWidth, "$path/shoulderWidth", violations)
            positive(player.torsoLength, "$path/torsoLength", violations)
            nonNegative(player.neutralVariance, "$path/neutralVariance", violations)
        }
        return result(value, violations)
    }

    fun validate(value: NormalizedPoseFrame): ContractResult<NormalizedPoseFrame> {
        val violations = mutableListOf<ContractViolation>()
        nonNegative(value.frameId, "$/frameId", violations)
        nonNegative(value.sourceTimestampNs, "$/sourceTimestampNs", violations)
        nonNegative(value.calibrationRevision, "$/calibrationRevision", violations)
        size(value.poses.size, "$/poses", 0, 2, violations)
        value.poses.forEachIndexed { index, pose ->
            val path = "$/poses/$index"
            boundedString(pose.trackId, "$path/trackId", 1, 64, violations)
            if (pose.landmarks.isEmpty()) {
                violations += ContractViolation.OutOfRange("$path/landmarks", "at least 1 property", "0")
            }
            pose.landmarks.forEach { (name, landmark) ->
                val landmarkPath = "$path/landmarks/${pointer(name)}"
                finite(landmark.x, "$landmarkPath/x", violations)
                finite(landmark.y, "$landmarkPath/y", violations)
                finite(landmark.z, "$landmarkPath/z", violations)
                boundedUnit(landmark.visibility, "$landmarkPath/visibility", violations)
            }
            boundedUnit(pose.scaleConfidence, "$path/scaleConfidence", violations)
        }
        return result(value, violations)
    }

    fun validate(value: GameSessionSnapshot): ContractResult<GameSessionSnapshot> {
        val violations = mutableListOf<ContractViolation>()
        exact(value.schemaVersion, "$/schemaVersion", 1, violations)
        boundedString(value.sessionId, "$/sessionId", 1, Int.MAX_VALUE, violations)
        nonNegative(value.simulationTick, "$/simulationTick", violations)
        boundedString(value.prng.algorithmId, "$/prng/algorithmId", 1, Int.MAX_VALUE, violations)
        if (value.prng.algorithmVersion < 1) {
            violations += ContractViolation.OutOfRange(
                "$/prng/algorithmVersion",
                "1..Int.MAX_VALUE",
                value.prng.algorithmVersion.toString(),
            )
        }
        if (value.prng.state.isEmpty()) {
            violations += ContractViolation.OutOfRange("$/prng/state", "at least 1 item", "0")
        }
        boundedString(value.contentRevision, "$/contentRevision", 1, Int.MAX_VALUE, violations)
        size(value.players.size, "$/players", 1, 2, violations)
        value.committedRewardIds.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value > 1 }
            ?.let { violations += ContractViolation.DuplicateValue("$/committedRewardIds", it.key) }
        return result(value, violations)
    }

    fun validate(value: RedactedDiagnosticBundle): ContractResult<RedactedDiagnosticBundle> {
        val violations = mutableListOf<ContractViolation>()
        exact(value.schemaVersion, "$/schemaVersion", 1, violations)
        if (value.device.memoryClassMb < 1) {
            violations += ContractViolation.OutOfRange(
                "$/device/memoryClassMb",
                "1..Int.MAX_VALUE",
                value.device.memoryClassMb.toString(),
            )
        }
        value.metrics.forEach { (metric, number) -> finite(number, "$/metrics/${metric.name}", violations) }
        value.events.forEachIndexed { index, event ->
            nonNegative(event.timestampNs, "$/events/$index/timestampNs", violations)
            event.value?.let { finite(it, "$/events/$index/value", violations) }
        }
        if (value.containsRawImages) {
            violations += ContractViolation.SensitiveDiagnosticField("$/containsRawImages", "raw images")
        }
        if (value.containsPoseCoordinates) {
            violations += ContractViolation.SensitiveDiagnosticField(
                "$/containsPoseCoordinates",
                "pose coordinates",
            )
        }
        return result(value, violations)
    }

    private fun boundedString(
        value: String,
        path: String,
        minimum: Int,
        maximum: Int,
        violations: MutableList<ContractViolation>,
    ) {
        if (value.length !in minimum..maximum) {
            violations += ContractViolation.OutOfRange(
                path,
                "string length $minimum..$maximum",
                value.length.toString(),
            )
        }
    }

    private fun boundedUnit(value: Float, path: String, violations: MutableList<ContractViolation>) {
        finite(value, path, violations)
        if (value.isFinite() && value !in 0f..1f) {
            violations += ContractViolation.OutOfRange(path, "0.0..1.0", value.toString())
        }
    }

    private fun positive(value: Float, path: String, violations: MutableList<ContractViolation>) {
        finite(value, path, violations)
        if (value.isFinite() && value <= 0f) {
            violations += ContractViolation.OutOfRange(path, "> 0", value.toString())
        }
    }

    private fun nonNegative(value: Float, path: String, violations: MutableList<ContractViolation>) {
        finite(value, path, violations)
        if (value.isFinite() && value < 0f) {
            violations += ContractViolation.OutOfRange(path, ">= 0", value.toString())
        }
    }

    private fun finite(value: Number, path: String, violations: MutableList<ContractViolation>) {
        if (!value.toDouble().isFinite()) {
            violations += ContractViolation.OutOfRange(path, "finite number", value.toString())
        }
    }

    private fun nonNegative(value: Long, path: String, violations: MutableList<ContractViolation>) {
        if (value < 0L) {
            violations += ContractViolation.OutOfRange(path, "0..Long.MAX_VALUE", value.toString())
        }
    }

    private fun nonNegative(value: Int, path: String, violations: MutableList<ContractViolation>) {
        if (value < 0) {
            violations += ContractViolation.OutOfRange(path, "0..Int.MAX_VALUE", value.toString())
        }
    }

    private fun exact(
        value: Int,
        path: String,
        expected: Int,
        violations: MutableList<ContractViolation>,
    ) {
        if (value != expected) {
            violations += ContractViolation.OutOfRange(path, "exactly $expected", value.toString())
        }
    }

    private fun size(
        value: Int,
        path: String,
        minimum: Int,
        maximum: Int,
        violations: MutableList<ContractViolation>,
    ) {
        if (value !in minimum..maximum) {
            violations += ContractViolation.OutOfRange(path, "array size $minimum..$maximum", value.toString())
        }
    }

    private fun <T> result(value: T, violations: List<ContractViolation>): ContractResult<T> =
        if (violations.isEmpty()) ContractResult.Valid(value) else ContractResult.Invalid(violations)

    private fun pointer(value: String): String = value.replace("~", "~0").replace("/", "~1")
}
