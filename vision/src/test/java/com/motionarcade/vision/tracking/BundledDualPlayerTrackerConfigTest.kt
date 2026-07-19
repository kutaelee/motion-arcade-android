package com.motionarcade.vision.tracking

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDualPlayerTrackerConfigTest {
    @Test
    fun candidateAssetLoadsStrictlyAndRemainsExplicitlyUnverified() {
        val bytes = Files.readAllBytes(bundledPath())

        val loaded =
            when (val result = PlayerTrackerConfigJson.load(bytes)) {
                is PlayerTrackerConfigLoadResult.Success -> result.loaded
                is PlayerTrackerConfigLoadResult.Failure -> error(
                    "bundled dual-player tracker candidate rejected: ${result.violation}: ${result.detail}",
                )
            }

        assertEquals("dual-player-candidate-v1", loaded.config.configId)
        assertEquals(PlayerTrackerConfigReviewStatus.CANDIDATE_UNVERIFIED, loaded.reviewStatus)
        assertTrue(loaded.sourceSha256Hex.matches(Regex("[0-9a-f]{64}")))
    }

    private fun bundledPath(): Path {
        val root = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(
            root.resolve("src/main/assets").resolve(PlayerTrackerConfigAssets.DUAL_PLAYER_CANDIDATE_V1),
            root.resolve("vision/src/main/assets").resolve(PlayerTrackerConfigAssets.DUAL_PLAYER_CANDIDATE_V1),
        ).firstOrNull(Files::isRegularFile)
            ?: error("bundled dual-player tracker candidate asset was not found from $root")
    }
}
