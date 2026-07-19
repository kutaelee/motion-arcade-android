package com.motionarcade.vision.motion

import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.motion.GestureDefinition
import com.motionarcade.vision.pose.FishingPoseSignalConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

class FishingMotionConfig internal constructor(
    val configId: String,
    val schemaVersion: Int,
    val calibrationRevision: Int,
    gestureDefinitions: Collection<GestureDefinition>,
    internal val poseConfig: FishingPoseSignalConfig,
) {
    val gestureDefinitions: List<GestureDefinition> = Collections.unmodifiableList(
        gestureDefinitions.map { it.copy() },
    )

    init {
        require(configId.matches(CONFIG_ID_PATTERN))
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION)
        require(calibrationRevision > 0)
        require(gestureDefinitions.map { it.type }.toSet() == FISHING_MOTION_TYPES)
        require(gestureDefinitions.size == FISHING_MOTION_TYPES.size)
        require(poseConfig.configId == configId)
        require(poseConfig.schemaVersion == schemaVersion)
    }

    /** Returns the same SSOT-derived thresholds under a new camera calibration epoch. */
    fun withCalibrationRevision(revision: Int): FishingMotionConfig {
        require(revision > calibrationRevision) { "calibration revision must increase" }
        return FishingMotionConfig(
            configId = configId,
            schemaVersion = schemaVersion,
            calibrationRevision = revision,
            gestureDefinitions = gestureDefinitions,
            poseConfig = poseConfig,
        )
    }

    private companion object {
        val CONFIG_ID_PATTERN = Regex("^[a-z][a-z0-9-]{2,63}$")
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

object FishingMotionConfigLoader {
    const val ASSET_PATH = "gesture-config/fishing-v1.properties"
    private const val EXPECTED_FORMAT = "motion-arcade-fishing-motion"
    private const val MAX_CONFIG_BYTES = 32 * 1024
    private const val MAX_LINE_CHARS = 512

    private val gestureTypes = listOf(
        MotionType.FISH_READY,
        MotionType.FISH_CAST,
        MotionType.FISH_HOOK,
        MotionType.FISH_REEL_CYCLE,
        MotionType.FISH_TENSION_LEFT,
        MotionType.FISH_TENSION_RIGHT,
        MotionType.FISH_NET,
    )
    private val rootKeys = setOf("format", "schemaVersion", "configId", "calibrationRevision")
    private val poseKeys = setOf(
        "pose.minimumLandmarkConfidence",
        "pose.minimumShoulderWidth",
        "pose.minimumTorsoLength",
        "pose.readyMaximumWristDistanceShoulderWidths",
        "pose.castMinimumWristTravelShoulderWidths",
        "pose.castMinimumElbowExtensionDelta",
        "pose.castWindowNs",
        "pose.hookMinimumRiseTorsoLengths",
        "pose.hookWindowNs",
        "pose.reelMinimumRadiusShoulderWidths",
        "pose.reelMinimumAccumulatedRadians",
        "pose.reelMinimumElbowAngleRangeRadians",
        "pose.reelMaximumStepRadians",
        "pose.reelMaximumFrameGapNs",
        "pose.tensionMinimumLeanTorsoLengths",
        "pose.netMinimumRiseTorsoLengths",
        "pose.netWindowNs",
        "pose.maximumHistorySamples",
    )
    private val gestureFields = setOf(
        "entryThreshold",
        "exitThreshold",
        "minimumConfidence",
        "minimumHoldNs",
        "maximumCandidateNs",
        "cooldownNs",
        "neutralRearmNs",
        "exclusivityGroup",
        "priority",
    )
    private val expectedKeys = rootKeys + poseKeys + gestureTypes.flatMap { type ->
        gestureFields.map { field -> "gesture.${type.name}.$field" }
    }

    fun load(input: InputStream): FishingMotionConfig {
        val values = parseClosedProperties(readBoundedUtf8(input))
        check(values["format"] == EXPECTED_FORMAT) { "Unexpected fishing config format" }
        val schemaVersion = values.requireInt("schemaVersion")
        check(schemaVersion == 1) { "Unsupported fishing config schema" }
        val configId = values.requireValue("configId")
        val calibrationRevision = values.requireInt("calibrationRevision")
        val poseConfig = FishingPoseSignalConfig(
            configId = configId,
            schemaVersion = schemaVersion,
            minimumLandmarkConfidence = values.requireFloat("pose.minimumLandmarkConfidence"),
            minimumShoulderWidth = values.requireFloat("pose.minimumShoulderWidth"),
            minimumTorsoLength = values.requireFloat("pose.minimumTorsoLength"),
            readyMaximumWristDistanceShoulderWidths =
                values.requireFloat("pose.readyMaximumWristDistanceShoulderWidths"),
            castMinimumWristTravelShoulderWidths =
                values.requireFloat("pose.castMinimumWristTravelShoulderWidths"),
            castMinimumElbowExtensionDelta =
                values.requireFloat("pose.castMinimumElbowExtensionDelta"),
            castWindowNs = values.requireLong("pose.castWindowNs"),
            hookMinimumRiseTorsoLengths =
                values.requireFloat("pose.hookMinimumRiseTorsoLengths"),
            hookWindowNs = values.requireLong("pose.hookWindowNs"),
            reelMinimumRadiusShoulderWidths =
                values.requireFloat("pose.reelMinimumRadiusShoulderWidths"),
            reelMinimumAccumulatedRadians =
                values.requireFloat("pose.reelMinimumAccumulatedRadians"),
            reelMinimumElbowAngleRangeRadians =
                values.requireFloat("pose.reelMinimumElbowAngleRangeRadians"),
            reelMaximumStepRadians = values.requireFloat("pose.reelMaximumStepRadians"),
            reelMaximumFrameGapNs = values.requireLong("pose.reelMaximumFrameGapNs"),
            tensionMinimumLeanTorsoLengths =
                values.requireFloat("pose.tensionMinimumLeanTorsoLengths"),
            netMinimumRiseTorsoLengths =
                values.requireFloat("pose.netMinimumRiseTorsoLengths"),
            netWindowNs = values.requireLong("pose.netWindowNs"),
            maximumHistorySamples = values.requireInt("pose.maximumHistorySamples"),
        )
        val definitions = gestureTypes.map { type ->
            val prefix = "gesture.${type.name}."
            GestureDefinition(
                type = type,
                entryThreshold = values.requireFloat(prefix + "entryThreshold"),
                exitThreshold = values.requireFloat(prefix + "exitThreshold"),
                minimumConfidence = values.requireFloat(prefix + "minimumConfidence"),
                minimumHoldNs = values.requireLong(prefix + "minimumHoldNs"),
                maximumCandidateNs = values.requireLong(prefix + "maximumCandidateNs"),
                cooldownNs = values.requireLong(prefix + "cooldownNs"),
                neutralRearmNs = values.requireLong(prefix + "neutralRearmNs"),
                exclusivityGroup = values.requireValue(prefix + "exclusivityGroup"),
                priority = values.requireInt(prefix + "priority"),
            )
        }
        return FishingMotionConfig(
            configId = configId,
            schemaVersion = schemaVersion,
            calibrationRevision = calibrationRevision,
            gestureDefinitions = definitions,
            poseConfig = poseConfig,
        )
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(read > 0) { "Fishing config stream made no progress" }
            total += read
            check(total <= MAX_CONFIG_BYTES) { "Fishing config exceeds byte limit" }
            output.write(buffer, 0, read)
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun parseClosedProperties(text: String): Map<String, String> {
        check(!text.startsWith('\uFEFF')) { "Fishing config must not contain a BOM" }
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            check(line.length <= MAX_LINE_CHARS) { "Fishing config line ${index + 1} is too long" }
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            check(line == line.trim()) { "Fishing config line ${index + 1} has outer whitespace" }
            val separator = line.indexOf('=')
            check(separator > 0 && separator == line.lastIndexOf('=')) {
                "Fishing config line ${index + 1} must contain one equals sign"
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            check(KEY_PATTERN.matches(key)) { "Invalid fishing config key on line ${index + 1}" }
            check(value.isNotEmpty() && value == value.trim()) {
                "Invalid fishing config value on line ${index + 1}"
            }
            check(key in expectedKeys) { "Unknown fishing config key: $key" }
            check(result.put(key, value) == null) { "Duplicate fishing config key: $key" }
        }
        val missing = expectedKeys - result.keys
        check(missing.isEmpty()) { "Missing fishing config keys: ${missing.sorted()}" }
        return Collections.unmodifiableMap(result)
    }

    private fun Map<String, String>.requireValue(key: String): String =
        checkNotNull(this[key]) { "Missing fishing config key: $key" }

    private fun Map<String, String>.requireInt(key: String): Int =
        requireValue(key).toIntOrNull() ?: error("Fishing config key $key is not an integer")

    private fun Map<String, String>.requireLong(key: String): Long =
        requireValue(key).toLongOrNull() ?: error("Fishing config key $key is not a long")

    private fun Map<String, String>.requireFloat(key: String): Float =
        requireValue(key).toFloatOrNull()?.takeIf { it.isFinite() }
            ?: error("Fishing config key $key is not a finite float")

    private val KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9._]*$")
}

internal val FISHING_MOTION_TYPES = setOf(
    MotionType.FISH_READY,
    MotionType.FISH_CAST,
    MotionType.FISH_HOOK,
    MotionType.FISH_REEL_CYCLE,
    MotionType.FISH_TENSION_LEFT,
    MotionType.FISH_TENSION_RIGHT,
    MotionType.FISH_NET,
)
