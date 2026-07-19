package com.motionarcade.app.monster

import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.games.monster.MonsterRaidGameSession
import com.motionarcade.games.monster.MonsterRaidStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterRaidCheckpointCodecTest {
    @Test
    fun roundTripPreservesRaidAndBothRoleStatesThenRestoresPaused() {
        val game = MonsterRaidGameSession.start("raid-codec", 31L, 2, GameMode.DUAL)
        game.advanceTicks(5)
        val encoded = MonsterRaidCheckpointCodec.encode(game.checkpointForAppBackground())
        val restored = requireNotNull(MonsterRaidCheckpointCodec.decode(encoded))

        assertTrue(restored.paused)
        assertEquals(MonsterRaidStage.WAVE, restored.stage)
        assertEquals(game.snapshot.players, restored.players)
        assertEquals(setOf(PlayerId.P1, PlayerId.P2), restored.players.keys)
        assertEquals(game.snapshot.prngAlgorithmId, restored.prngAlgorithmId)
        assertEquals(game.snapshot.simulationTick, restored.simulationTick)
    }

    @Test
    fun deterministicEncodingAndMalformedStateFailClosed() {
        val snapshot = MonsterRaidGameSession.start("raid-codec-negative", 9L, 2, GameMode.DUAL)
            .checkpointForAppBackground()
        val encoded = MonsterRaidCheckpointCodec.encode(snapshot)

        assertArrayEquals(encoded, MonsterRaidCheckpointCodec.encode(snapshot))
        assertNull(MonsterRaidCheckpointCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(MonsterRaidCheckpointCodec.decode(ByteArray(MonsterRaidCheckpointCodec.MAX_ENCODED_BYTES + 1)))
        assertTrue(
            runCatching {
                MonsterRaidCheckpointCodec.encode(
                    snapshot.copy(enemyHealth = MonsterRaidGameSession.WAVE_HEALTH + 1),
                )
            }.isFailure,
        )
    }

    @Test
    fun compatibleDecodeRejectsCrossModeAndCalibrationRestore() {
        val solo = MonsterRaidGameSession.start("raid-solo", 17L, 4, GameMode.SOLO)
            .checkpointForAppBackground()
        val encoded = MonsterRaidCheckpointCodec.encode(solo)

        assertEquals(
            GameMode.SOLO,
            requireNotNull(
                MonsterRaidCheckpointCodec.decodeCompatible(encoded, 4, GameMode.SOLO),
            ).mode,
        )
        assertNull(MonsterRaidCheckpointCodec.decodeCompatible(encoded, 4, GameMode.DUAL))
        assertNull(MonsterRaidCheckpointCodec.decodeCompatible(encoded, 5, GameMode.SOLO))
    }
}
