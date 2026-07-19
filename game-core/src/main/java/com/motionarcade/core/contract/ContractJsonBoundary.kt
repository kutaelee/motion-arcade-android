package com.motionarcade.core.contract

import java.math.BigDecimal
import java.math.BigInteger

/** Converts already-parsed JSON objects into closed, validated domain contracts. */
object ContractJsonBoundary {
    fun motionEvent(value: Map<*, *>): ContractResult<MotionEventEnvelope> {
        val root = closedObject(
            value,
            "$",
            required = setOf(
                "eventId",
                "sessionId",
                "playerId",
                "sequenceNumber",
                "type",
                "quality",
                "confidence",
                "eventTimestampNs",
                "calibrationRevision",
                "source",
                "metadata",
            ),
        ).valueOrReturn { return it }

        val eventId = boundedString(root["eventId"], "$/eventId", 1, 160).valueOrReturn { return it }
        val sessionId = boundedString(root["sessionId"], "$/sessionId", 1, 120).valueOrReturn { return it }
        val playerId = enumValue<PlayerId>(root["playerId"], "$/playerId").valueOrReturn { return it }
        val sequence = nonNegativeLong(root["sequenceNumber"], "$/sequenceNumber").valueOrReturn { return it }
        val motionWire = stringValue(root["type"], "$/type").valueOrReturn { return it }
        if (!MOTION_TYPE_PATTERN.matches(motionWire)) {
            return invalid(
                ContractViolation.InvalidFormat(
                    "$/type",
                    "^[A-Z][A-Z0-9_]{1,63}$",
                    motionWire,
                ),
            )
        }
        val type = MotionTypeRegistry.fromWire(motionWire)
            ?: return invalid(
                ContractViolation.UnknownEnumValue("$/type", "MotionType", motionWire),
            )
        val quality = boundedFloat(root["quality"], "$/quality", 0f, 1f).valueOrReturn { return it }
        val confidence = boundedFloat(root["confidence"], "$/confidence", 0f, 1f).valueOrReturn { return it }
        val timestamp = nonNegativeLong(root["eventTimestampNs"], "$/eventTimestampNs").valueOrReturn { return it }
        val revision = nonNegativeInt(root["calibrationRevision"], "$/calibrationRevision").valueOrReturn { return it }
        val source = enumValue<InputSource>(root["source"], "$/source").valueOrReturn { return it }
        val metadata = numericFloatMap(root["metadata"], "$/metadata", 32).valueOrReturn { return it }

        return ContractValidators.validate(
            MotionEventEnvelope(
                eventId = eventId,
                sessionId = sessionId,
                playerId = playerId,
                sequenceNumber = sequence,
                type = type,
                quality = quality,
                confidence = confidence,
                eventTimestampNs = timestamp,
                calibrationRevision = revision,
                source = source,
                metadata = metadata,
            ),
        )
    }

    fun calibrationProfile(value: Map<*, *>): ContractResult<CalibrationProfile> {
        val root = closedObject(
            value,
            "$",
            required = setOf(
                "schemaVersion",
                "revision",
                "mode",
                "lensFacing",
                "orientation",
                "players",
                "valid",
            ),
        ).valueOrReturn { return it }
        val schemaVersion = exactInt(root["schemaVersion"], "$/schemaVersion", 1).valueOrReturn { return it }
        val revision = nonNegativeInt(root["revision"], "$/revision").valueOrReturn { return it }
        val mode = enumValue<GameMode>(root["mode"], "$/mode").valueOrReturn { return it }
        val lens = enumValue<LensFacing>(root["lensFacing"], "$/lensFacing").valueOrReturn { return it }
        val orientation = enumValue<DeviceOrientation>(root["orientation"], "$/orientation").valueOrReturn { return it }
        val rawPlayers = listValue(root["players"], "$/players", minSize = 1, maxSize = 2).valueOrReturn { return it }
        val players = ArrayList<CalibrationPlayer>(rawPlayers.size)
        rawPlayers.forEachIndexed { index, item ->
            val player = calibrationPlayer(item, "$/players/$index").valueOrReturn { return it }
            players += player
        }
        val valid = booleanValue(root["valid"], "$/valid").valueOrReturn { return it }
        return ContractResult.Valid(
            CalibrationProfile(schemaVersion, revision, mode, lens, orientation, players.toList(), valid),
        )
    }

    fun poseFrame(value: Map<*, *>): ContractResult<NormalizedPoseFrame> {
        val root = closedObject(
            value,
            "$",
            required = setOf("frameId", "sourceTimestampNs", "calibrationRevision", "poses"),
        ).valueOrReturn { return it }
        val frameId = nonNegativeLong(root["frameId"], "$/frameId").valueOrReturn { return it }
        val timestamp = nonNegativeLong(root["sourceTimestampNs"], "$/sourceTimestampNs").valueOrReturn { return it }
        val revision = nonNegativeInt(root["calibrationRevision"], "$/calibrationRevision").valueOrReturn { return it }
        val rawPoses = listValue(root["poses"], "$/poses", maxSize = 2).valueOrReturn { return it }
        val poses = ArrayList<NormalizedPose>(rawPoses.size)
        rawPoses.forEachIndexed { index, item ->
            poses += pose(item, "$/poses/$index").valueOrReturn { return it }
        }
        return ContractResult.Valid(NormalizedPoseFrame(frameId, timestamp, revision, poses.toList()))
    }

    fun sessionSnapshot(value: Map<*, *>): ContractResult<GameSessionSnapshot> {
        val root = closedObject(
            value,
            "$",
            required = setOf(
                "schemaVersion",
                "sessionId",
                "gameId",
                "mode",
                "simulationTick",
                "status",
                "seed",
                "prng",
                "contentRevision",
                "state",
                "players",
                "committedRewardIds",
            ),
            optional = setOf("pauseReason"),
        ).valueOrReturn { return it }
        val schemaVersion = exactInt(root["schemaVersion"], "$/schemaVersion", 1).valueOrReturn { return it }
        val sessionId = boundedString(root["sessionId"], "$/sessionId", minLength = 1).valueOrReturn { return it }
        val gameId = enumValue<GameId>(root["gameId"], "$/gameId").valueOrReturn { return it }
        val mode = enumValue<GameMode>(root["mode"], "$/mode").valueOrReturn { return it }
        val tick = nonNegativeLong(root["simulationTick"], "$/simulationTick").valueOrReturn { return it }
        val status = enumValue<SessionStatus>(root["status"], "$/status").valueOrReturn { return it }
        val seed = longValue(root["seed"], "$/seed").valueOrReturn { return it }
        val prng = prngState(root["prng"], "$/prng").valueOrReturn { return it }
        val contentRevision = boundedString(root["contentRevision"], "$/contentRevision", minLength = 1).valueOrReturn { return it }
        val pauseReason = when (val rawReason = root["pauseReason"]) {
            null -> null
            else -> enumValue<PauseReason>(rawReason, "$/pauseReason").valueOrReturn { return it }
        }
        val stateMap = objectValue(root["state"], "$/state").valueOrReturn { return it }
        val state = TemporaryCanonicalJsonObject.fromMap(stateMap, "$/state").valueOrReturn { return it }
        val rawPlayers = listValue(root["players"], "$/players", minSize = 1, maxSize = 2).valueOrReturn { return it }
        val players = ArrayList<TemporaryCanonicalJsonObject>(rawPlayers.size)
        rawPlayers.forEachIndexed { index, item ->
            val playerMap = objectValue(item, "$/players/$index").valueOrReturn { return it }
            players += TemporaryCanonicalJsonObject.fromMap(playerMap, "$/players/$index").valueOrReturn { return it }
        }
        val rewards = stringList(root["committedRewardIds"], "$/committedRewardIds", unique = true).valueOrReturn { return it }
        return ContractResult.Valid(
            GameSessionSnapshot(
                schemaVersion,
                sessionId,
                gameId,
                mode,
                tick,
                status,
                seed,
                prng,
                contentRevision,
                pauseReason,
                state,
                players.toList(),
                rewards,
            ),
        )
    }

    fun diagnosticBundle(value: Map<*, *>): ContractResult<RedactedDiagnosticBundle> {
        val root = closedObject(
            value,
            "$",
            required = setOf(
                "schemaVersion",
                "appVersion",
                "device",
                "session",
                "metrics",
                "events",
                "containsRawImages",
                "containsPoseCoordinates",
            ),
            optional = setOf("userConsentedToPoseExport"),
        ).valueOrReturn { return it }
        val schemaVersion = exactInt(root["schemaVersion"], "$/schemaVersion", 1).valueOrReturn { return it }
        val appVersion = stringValue(root["appVersion"], "$/appVersion").valueOrReturn { return it }
        val device = diagnosticDevice(root["device"], "$/device").valueOrReturn { return it }
        val session = diagnosticSession(root["session"], "$/session").valueOrReturn { return it }
        val metrics = diagnosticMetrics(root["metrics"], "$/metrics").valueOrReturn { return it }
        val events = diagnosticEvents(root["events"], "$/events").valueOrReturn { return it }
        val containsRawImages = booleanValue(root["containsRawImages"], "$/containsRawImages").valueOrReturn { return it }
        if (containsRawImages) {
            return invalid(ContractViolation.SensitiveDiagnosticField("$/containsRawImages", "raw images"))
        }
        val containsPoseCoordinates = booleanValue(
            root["containsPoseCoordinates"],
            "$/containsPoseCoordinates",
        ).valueOrReturn { return it }
        if (containsPoseCoordinates) {
            return invalid(
                ContractViolation.SensitiveDiagnosticField(
                    "$/containsPoseCoordinates",
                    "pose coordinates",
                ),
            )
        }
        val consent = if (root.containsKey("userConsentedToPoseExport")) {
            booleanValue(root["userConsentedToPoseExport"], "$/userConsentedToPoseExport").valueOrReturn { return it }
        } else {
            false
        }
        return ContractResult.Valid(
            RedactedDiagnosticBundle(
                schemaVersion,
                appVersion,
                device,
                session,
                metrics,
                events,
                containsRawImages,
                containsPoseCoordinates,
                consent,
            ),
        )
    }

    private fun calibrationPlayer(value: Any?, path: String): ContractResult<CalibrationPlayer> {
        val root = closedObject(
            value,
            path,
            required = setOf(
                "playerId",
                "shoulderWidth",
                "torsoLength",
                "neutralVariance",
                "dominantSide",
                "stance",
                "oneArmMode",
            ),
        ).valueOrReturn { return it }
        val playerId = enumValue<PlayerId>(root["playerId"], "$path/playerId").valueOrReturn { return it }
        if (playerId == PlayerId.AI) {
            return invalid(ContractViolation.UnknownEnumValue("$path/playerId", "CalibrationPlayerId", "AI"))
        }
        val shoulder = positiveFloat(root["shoulderWidth"], "$path/shoulderWidth").valueOrReturn { return it }
        val torso = positiveFloat(root["torsoLength"], "$path/torsoLength").valueOrReturn { return it }
        val variance = nonNegativeFloat(root["neutralVariance"], "$path/neutralVariance").valueOrReturn { return it }
        val dominant = enumValue<DominantSide>(root["dominantSide"], "$path/dominantSide").valueOrReturn { return it }
        val stance = enumValue<Stance>(root["stance"], "$path/stance").valueOrReturn { return it }
        val oneArm = enumValue<OneArmMode>(root["oneArmMode"], "$path/oneArmMode").valueOrReturn { return it }
        return ContractResult.Valid(CalibrationPlayer(playerId, shoulder, torso, variance, dominant, stance, oneArm))
    }

    private fun pose(value: Any?, path: String): ContractResult<NormalizedPose> {
        val root = closedObject(
            value,
            path,
            required = setOf("trackId", "trackState", "landmarks", "scaleConfidence"),
        ).valueOrReturn { return it }
        val trackId = boundedString(root["trackId"], "$path/trackId", 1, 64).valueOrReturn { return it }
        val state = enumValue<TrackState>(root["trackState"], "$path/trackState").valueOrReturn { return it }
        val landmarkRoot = objectValue(root["landmarks"], "$path/landmarks").valueOrReturn { return it }
        if (landmarkRoot.isEmpty()) {
            return invalid(ContractViolation.OutOfRange("$path/landmarks", "at least 1 property", "0"))
        }
        val landmarks = LinkedHashMap<String, PoseLandmark>(landmarkRoot.size)
        landmarkRoot.forEach { (name, item) ->
            landmarks[name] = landmark(item, "$path/landmarks/${escapePointer(name)}").valueOrReturn { return it }
        }
        val scale = boundedFloat(root["scaleConfidence"], "$path/scaleConfidence", 0f, 1f).valueOrReturn { return it }
        return ContractResult.Valid(NormalizedPose(trackId, state, landmarks.toMap(), scale))
    }

    private fun landmark(value: Any?, path: String): ContractResult<PoseLandmark> {
        val root = closedObject(value, path, required = setOf("x", "y", "z", "visibility")).valueOrReturn { return it }
        val x = finiteFloat(root["x"], "$path/x").valueOrReturn { return it }
        val y = finiteFloat(root["y"], "$path/y").valueOrReturn { return it }
        val z = finiteFloat(root["z"], "$path/z").valueOrReturn { return it }
        val visibility = boundedFloat(root["visibility"], "$path/visibility", 0f, 1f).valueOrReturn { return it }
        return ContractResult.Valid(PoseLandmark(x, y, z, visibility))
    }

    private fun prngState(value: Any?, path: String): ContractResult<DeterministicPrngState> {
        val root = closedObject(
            value,
            path,
            required = setOf("algorithmId", "algorithmVersion", "state"),
        ).valueOrReturn { return it }
        val id = boundedString(root["algorithmId"], "$path/algorithmId", minLength = 1).valueOrReturn { return it }
        val version = positiveInt(root["algorithmVersion"], "$path/algorithmVersion").valueOrReturn { return it }
        val rawState = listValue(root["state"], "$path/state", minSize = 1).valueOrReturn { return it }
        val state = ArrayList<Long>(rawState.size)
        rawState.forEachIndexed { index, item ->
            state += longValue(item, "$path/state/$index").valueOrReturn { return it }
        }
        return ContractResult.Valid(DeterministicPrngState(id, version, state.toList()))
    }

    private fun diagnosticDevice(value: Any?, path: String): ContractResult<DiagnosticDevice> {
        val root = closedObject(
            value,
            path,
            required = setOf("model", "osVersion", "memoryClassMb"),
        ).valueOrReturn { return it }
        val model = stringValue(root["model"], "$path/model").valueOrReturn { return it }
        val os = stringValue(root["osVersion"], "$path/osVersion").valueOrReturn { return it }
        val memory = positiveInt(root["memoryClassMb"], "$path/memoryClassMb").valueOrReturn { return it }
        return ContractResult.Valid(DiagnosticDevice(model, os, memory))
    }

    private fun diagnosticSession(value: Any?, path: String): ContractResult<DiagnosticSession> {
        val rootResult = closedObject(
            value,
            path,
            required = setOf("gameId", "mode", "lensFacing", "orientation", "qualityTier"),
            diagnosticAllowlist = true,
        )
        val root = rootResult.valueOrReturn { return it }
        val game = enumValue<GameId>(root["gameId"], "$path/gameId").valueOrReturn { return it }
        val mode = enumValue<GameMode>(root["mode"], "$path/mode").valueOrReturn { return it }
        val lens = enumValue<LensFacing>(root["lensFacing"], "$path/lensFacing").valueOrReturn { return it }
        val orientation = enumValue<DeviceOrientation>(root["orientation"], "$path/orientation").valueOrReturn { return it }
        val quality = stringValue(root["qualityTier"], "$path/qualityTier").valueOrReturn { return it }
        return ContractResult.Valid(DiagnosticSession(game, mode, lens, orientation, quality))
    }

    private fun diagnosticMetrics(value: Any?, path: String): ContractResult<Map<DiagnosticMetric, Double>> {
        val root = objectValue(value, path).valueOrReturn { return it }
        val result = LinkedHashMap<DiagnosticMetric, Double>(root.size)
        root.forEach { (key, rawValue) ->
            val metric = enumFromWireOrSensitive<DiagnosticMetric>(key, "$path/${escapePointer(key)}").valueOrReturn { return it }
            result[metric] = finiteDouble(rawValue, "$path/${escapePointer(key)}").valueOrReturn { return it }
        }
        return ContractResult.Valid(result.toMap())
    }

    private fun diagnosticEvents(value: Any?, path: String): ContractResult<List<DiagnosticEvent>> {
        val rawEvents = listValue(value, path).valueOrReturn { return it }
        val events = ArrayList<DiagnosticEvent>(rawEvents.size)
        rawEvents.forEachIndexed { index, rawEvent ->
            val eventPath = "$path/$index"
            val root = closedObject(
                rawEvent,
                eventPath,
                required = setOf("type", "timestampNs"),
                optional = setOf("value"),
                diagnosticAllowlist = true,
            ).valueOrReturn { return it }
            val typeWire = stringValue(root["type"], "$eventPath/type").valueOrReturn { return it }
            val type = enumFromWireOrSensitive<DiagnosticEventType>(typeWire, "$eventPath/type").valueOrReturn { return it }
            val timestamp = nonNegativeLong(root["timestampNs"], "$eventPath/timestampNs").valueOrReturn { return it }
            val eventValue = when (val rawValue = root["value"]) {
                null -> null
                else -> finiteDouble(rawValue, "$eventPath/value").valueOrReturn { return it }
            }
            events += DiagnosticEvent(type, timestamp, eventValue)
        }
        return ContractResult.Valid(events.toList())
    }
}

private val MOTION_TYPE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,63}$")

private inline fun <T> ContractResult<T>.valueOrReturn(
    onInvalid: (ContractResult.Invalid) -> Nothing,
): T = when (this) {
    is ContractResult.Valid -> value
    is ContractResult.Invalid -> onInvalid(this)
}

private fun closedObject(
    value: Any?,
    path: String,
    required: Set<String>,
    optional: Set<String> = emptySet(),
    diagnosticAllowlist: Boolean = false,
): ContractResult<Map<String, Any?>> {
    val root = objectValue(value, path).valueOrReturn { return it }
    required.forEach { property ->
        if (!root.containsKey(property)) {
            return invalid(ContractViolation.MissingProperty(path, property))
        }
    }
    val allowed = required + optional
    root.keys.firstOrNull { it !in allowed }?.let { property ->
        return if (diagnosticAllowlist) {
            invalid(ContractViolation.SensitiveDiagnosticField(path, property))
        } else {
            invalid(ContractViolation.UnknownProperty(path, property))
        }
    }
    return ContractResult.Valid(root)
}

private fun objectValue(value: Any?, path: String): ContractResult<Map<String, Any?>> {
    if (value !is Map<*, *>) {
        return invalid(ContractViolation.TypeMismatch(path, "object", typeName(value)))
    }
    val result = LinkedHashMap<String, Any?>(value.size)
    value.forEach { (key, item) ->
        if (key !is String) {
            return invalid(ContractViolation.TypeMismatch(path, "string property name", typeName(key)))
        }
        result[key] = item
    }
    return ContractResult.Valid(result.toMap())
}

private fun listValue(
    value: Any?,
    path: String,
    minSize: Int = 0,
    maxSize: Int = Int.MAX_VALUE,
): ContractResult<List<Any?>> {
    if (value !is List<*>) {
        return invalid(ContractViolation.TypeMismatch(path, "array", typeName(value)))
    }
    if (value.size !in minSize..maxSize) {
        return invalid(
            ContractViolation.OutOfRange(path, "array size $minSize..$maxSize", value.size.toString()),
        )
    }
    return ContractResult.Valid(value.toList())
}

private fun stringList(
    value: Any?,
    path: String,
    unique: Boolean,
): ContractResult<List<String>> {
    val values = listValue(value, path).valueOrReturn { return it }
    val result = ArrayList<String>(values.size)
    values.forEachIndexed { index, item ->
        val parsed = stringValue(item, "$path/$index").valueOrReturn { return it }
        if (unique && parsed in result) {
            return invalid(ContractViolation.DuplicateValue("$path/$index", parsed))
        }
        result += parsed
    }
    return ContractResult.Valid(result.toList())
}

private fun numericFloatMap(
    value: Any?,
    path: String,
    maxSize: Int,
): ContractResult<Map<String, Float>> {
    val root = objectValue(value, path).valueOrReturn { return it }
    if (root.size > maxSize) {
        return invalid(ContractViolation.OutOfRange(path, "at most $maxSize properties", root.size.toString()))
    }
    val result = LinkedHashMap<String, Float>(root.size)
    root.forEach { (key, item) ->
        result[key] = finiteFloat(item, "$path/${escapePointer(key)}").valueOrReturn { return it }
    }
    return ContractResult.Valid(result.toMap())
}

private fun stringValue(value: Any?, path: String): ContractResult<String> =
    if (value is String) ContractResult.Valid(value)
    else invalid(ContractViolation.TypeMismatch(path, "string", typeName(value)))

private fun boundedString(
    value: Any?,
    path: String,
    minLength: Int = 0,
    maxLength: Int = Int.MAX_VALUE,
): ContractResult<String> {
    val parsed = stringValue(value, path).valueOrReturn { return it }
    if (parsed.length !in minLength..maxLength) {
        return invalid(
            ContractViolation.OutOfRange(
                path,
                "string length $minLength..$maxLength",
                parsed.length.toString(),
            ),
        )
    }
    return ContractResult.Valid(parsed)
}

private fun booleanValue(value: Any?, path: String): ContractResult<Boolean> =
    if (value is Boolean) ContractResult.Valid(value)
    else invalid(ContractViolation.TypeMismatch(path, "boolean", typeName(value)))

private inline fun <reified T : Enum<T>> enumValue(value: Any?, path: String): ContractResult<T> {
    val wire = stringValue(value, path).valueOrReturn { return it }
    return enumFromWire(wire, path)
}

private inline fun <reified T : Enum<T>> enumFromWire(wire: String, path: String): ContractResult<T> =
    enumValues<T>().firstOrNull { it.name == wire }?.let { ContractResult.Valid(it) }
        ?: invalid(ContractViolation.UnknownEnumValue(path, T::class.java.simpleName, wire))

private inline fun <reified T : Enum<T>> enumFromWireOrSensitive(
    wire: String,
    path: String,
): ContractResult<T> =
    enumValues<T>().firstOrNull { it.name == wire }?.let { ContractResult.Valid(it) }
        ?: invalid(ContractViolation.SensitiveDiagnosticField(path, wire))

private fun nonNegativeLong(value: Any?, path: String): ContractResult<Long> {
    val parsed = longValue(value, path).valueOrReturn { return it }
    return if (parsed >= 0L) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "0..Long.MAX_VALUE", parsed.toString()))
}

private fun nonNegativeInt(value: Any?, path: String): ContractResult<Int> {
    val parsed = intValue(value, path).valueOrReturn { return it }
    return if (parsed >= 0) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "0..Int.MAX_VALUE", parsed.toString()))
}

private fun positiveInt(value: Any?, path: String): ContractResult<Int> {
    val parsed = intValue(value, path).valueOrReturn { return it }
    return if (parsed >= 1) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "1..Int.MAX_VALUE", parsed.toString()))
}

private fun exactInt(value: Any?, path: String, expected: Int): ContractResult<Int> {
    val parsed = intValue(value, path).valueOrReturn { return it }
    return if (parsed == expected) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "exactly $expected", parsed.toString()))
}

private fun intValue(value: Any?, path: String): ContractResult<Int> {
    val integer = integerValue(value, path).valueOrReturn { return it }
    val minimum = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    val maximum = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    return if (integer < minimum || integer > maximum) {
        invalid(ContractViolation.OutOfRange(path, "Int.MIN_VALUE..Int.MAX_VALUE", integer.toString()))
    } else {
        ContractResult.Valid(integer.toInt())
    }
}

private fun longValue(value: Any?, path: String): ContractResult<Long> {
    val integer = integerValue(value, path).valueOrReturn { return it }
    val minimum = BigInteger.valueOf(Long.MIN_VALUE)
    val maximum = BigInteger.valueOf(Long.MAX_VALUE)
    return if (integer < minimum || integer > maximum) {
        invalid(ContractViolation.OutOfRange(path, "Long.MIN_VALUE..Long.MAX_VALUE", integer.toString()))
    } else {
        ContractResult.Valid(integer.toLong())
    }
}

private fun integerValue(value: Any?, path: String): ContractResult<BigInteger> = when (value) {
    is Byte -> ContractResult.Valid(BigInteger.valueOf(value.toLong()))
    is Short -> ContractResult.Valid(BigInteger.valueOf(value.toLong()))
    is Int -> ContractResult.Valid(BigInteger.valueOf(value.toLong()))
    is Long -> ContractResult.Valid(BigInteger.valueOf(value))
    is BigInteger -> ContractResult.Valid(value)
    is BigDecimal -> exactInteger(value, path)
    is Float -> floatingInteger(value.toDouble(), path)
    is Double -> floatingInteger(value, path)
    else -> invalid(ContractViolation.TypeMismatch(path, "integer", typeName(value)))
}

private fun floatingInteger(value: Double, path: String): ContractResult<BigInteger> =
    if (!value.isFinite()) {
        invalid(ContractViolation.OutOfRange(path, "finite integer", value.toString()))
    } else {
        exactInteger(BigDecimal.valueOf(value), path)
    }

private fun exactInteger(value: BigDecimal, path: String): ContractResult<BigInteger> =
    try {
        ContractResult.Valid(value.toBigIntegerExact())
    } catch (_: ArithmeticException) {
        invalid(ContractViolation.TypeMismatch(path, "integer", value.toPlainString()))
    }

private fun positiveFloat(value: Any?, path: String): ContractResult<Float> {
    val parsed = finiteFloat(value, path).valueOrReturn { return it }
    return if (parsed > 0f) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "> 0", parsed.toString()))
}

private fun nonNegativeFloat(value: Any?, path: String): ContractResult<Float> {
    val parsed = finiteFloat(value, path).valueOrReturn { return it }
    return if (parsed >= 0f) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, ">= 0", parsed.toString()))
}

private fun boundedFloat(
    value: Any?,
    path: String,
    minimum: Float,
    maximum: Float,
): ContractResult<Float> {
    val parsed = finiteFloat(value, path).valueOrReturn { return it }
    return if (parsed in minimum..maximum) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "$minimum..$maximum", parsed.toString()))
}

private fun finiteFloat(value: Any?, path: String): ContractResult<Float> {
    if (value !is Number) {
        return invalid(ContractViolation.TypeMismatch(path, "number", typeName(value)))
    }
    val parsed = value.toFloat()
    return if (parsed.isFinite()) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "finite Float", value.toString()))
}

private fun finiteDouble(value: Any?, path: String): ContractResult<Double> {
    if (value !is Number) {
        return invalid(ContractViolation.TypeMismatch(path, "number", typeName(value)))
    }
    val parsed = value.toDouble()
    return if (parsed.isFinite()) ContractResult.Valid(parsed)
    else invalid(ContractViolation.OutOfRange(path, "finite Double", value.toString()))
}

private fun typeName(value: Any?): String = value?.javaClass?.simpleName ?: "null"

private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")
