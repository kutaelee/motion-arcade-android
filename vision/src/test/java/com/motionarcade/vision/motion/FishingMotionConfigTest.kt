package com.motionarcade.vision.motion

import com.motionarcade.core.contract.MotionType
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingMotionConfigTest {
    @Test
    fun bundledCandidateIsClosedVersionedAndContainsAllSevenGestures() {
        val config = loadBundled()

        assertEquals("fishing-qa-candidate-v2", config.configId)
        assertEquals(1, config.schemaVersion)
        assertEquals(2, config.calibrationRevision)
        assertEquals(FISHING_MOTION_TYPES, config.gestureDefinitions.map { it.type }.toSet())
        assertEquals(
            0L,
            config.gestureDefinitions.single {
                it.type == MotionType.FISH_REEL_CYCLE
            }.minimumHoldNs,
        )
        assertEquals(
            500_000_000L,
            config.gestureDefinitions.single {
                it.type == MotionType.FISH_READY
            }.minimumHoldNs,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (config.gestureDefinitions as MutableList).clear()
        }
    }

    @Test
    fun dualFishingProfileReusesTheBundledGestureAndPoseThresholdSsot() {
        val solo = loadBundled()
        val dual = DualPlayerCombatMotionConfigs.fishing(solo)

        assertEquals(DualPlayerCombatProfile.FISHING, dual.profile)
        assertEquals(solo.configId, dual.configId)
        assertEquals(solo.calibrationRevision, dual.calibrationRevision)
        assertEquals(solo.gestureDefinitions, dual.gestureDefinitions)
        assertEquals(FISHING_MOTION_TYPES, dual.motionTypes)
        assertEquals(solo.poseConfig, dual.fishingPoseConfig)
        assertEquals(null, dual.poseConfig)
    }

    @Test
    fun cameraRecalibrationChangesOnlyTheRevision() {
        val source = loadBundled()
        val recalibrated = source.withCalibrationRevision(source.calibrationRevision + 1)

        assertEquals(source.configId, recalibrated.configId)
        assertEquals(source.schemaVersion, recalibrated.schemaVersion)
        assertEquals(source.gestureDefinitions, recalibrated.gestureDefinitions)
        assertEquals(source.poseConfig, recalibrated.poseConfig)
        assertEquals(source.calibrationRevision + 1, recalibrated.calibrationRevision)
        assertThrows(IllegalArgumentException::class.java) {
            source.withCalibrationRevision(source.calibrationRevision)
        }
    }

    @Test
    fun duplicateUnknownMissingAndOversizedConfigsFailClosed() {
        val bundled = bundledText()
        val cases = listOf(
            bundled + "\nconfigId=duplicate\n",
            bundled + "\nunknown.key=value\n",
            bundled.lineSequence()
                .filterNot { it.startsWith("pose.minimumTorsoLength=") }
                .joinToString("\n"),
        )

        cases.forEach { candidate ->
            assertThrows(IllegalStateException::class.java) {
                FishingMotionConfigLoader.load(
                    ByteArrayInputStream(candidate.toByteArray(StandardCharsets.UTF_8)),
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            FishingMotionConfigLoader.load(ByteArrayInputStream(ByteArray(32 * 1024 + 1)))
        }
    }

    @Test
    fun malformedUtf8AndNonFiniteThresholdFailClosed() {
        assertThrows(Exception::class.java) {
            FishingMotionConfigLoader.load(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        val nonFinite = bundledText().replace(
            "pose.minimumShoulderWidth=0.08",
            "pose.minimumShoulderWidth=NaN",
        )
        assertThrows(IllegalStateException::class.java) {
            FishingMotionConfigLoader.load(
                ByteArrayInputStream(nonFinite.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }

    private fun loadBundled(): FishingMotionConfig {
        return Files.newInputStream(bundledPath()).use(
            FishingMotionConfigLoader::load,
        )
    }

    private fun bundledText(): String = Files.newBufferedReader(bundledPath()).use {
        it.readText()
    }.also { assertTrue(it.isNotBlank()) }

    private fun bundledPath(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(
            workingDirectory.resolve("src/main/assets").resolve(FishingMotionConfigLoader.ASSET_PATH),
            workingDirectory.resolve("vision/src/main/assets")
                .resolve(FishingMotionConfigLoader.ASSET_PATH),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Bundled fishing config source was not found from $workingDirectory")
    }
}
