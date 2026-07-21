package com.motionarcade.app.monster

import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.games.monster.MonsterRaidGameSession
import com.motionarcade.games.monster.MonsterRaidBossPhase
import com.motionarcade.games.monster.MonsterRaidEnemy
import com.motionarcade.games.monster.MonsterRaidStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterRaidCheckpointCodecTest {
    @Test
    fun typedEnvelopeMigratesPreEnvelopeV2FixturesToCanonicalV3ForBothModes() {
        val solo = MonsterRaidGameSession.start("monster-legacy-solo", 71L, 2, GameMode.SOLO)
            .checkpointForAppBackground()
        val dual = MonsterRaidGameSession.start("monster-legacy-dual", 73L, 2, GameMode.DUAL)
            .checkpointForAppBackground()
        listOf(
            solo to MonsterRaidLegacyCheckpointFixtures.soloPausedV2,
            dual to MonsterRaidLegacyCheckpointFixtures.dualPausedV2,
        ).forEach { (expected, legacy) ->
            val encoded = MonsterRaidCheckpointCodec.encode(expected)
            val envelope = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded))
            assertEquals(GameId.MONSTER, envelope.gameId)
            assertEquals(expected.mode, envelope.mode)
            assertEquals(MonsterRaidCheckpointCodec.PAYLOAD_CODEC_ID, envelope.payloadCodecId)
            assertEquals(MonsterRaidCheckpointCodec.PAYLOAD_CODEC_VERSION, envelope.payloadCodecVersion)
            val migrated = requireNotNull(MonsterRaidCheckpointCodec.decode(legacy))
            assertEquals(expected, migrated)
            val rewritten = MonsterRaidCheckpointCodec.encode(migrated)
            assertArrayEquals(encoded, rewritten)
            assertEquals(migrated, MonsterRaidCheckpointCodec.decode(rewritten))
            assertTrue(
                !legacy.contentEquals(
                    requireNotNull(TypedCheckpointEnvelopeCodec.decode(rewritten)).payload,
                ),
            )
            val legacyEnvelope = TypedCheckpointEnvelopeCodec.encode(
                GameId.MONSTER,
                expected.mode,
                MonsterRaidCheckpointCodec.PAYLOAD_CODEC_ID,
                2,
                legacy,
            )
            assertEquals(expected, MonsterRaidCheckpointCodec.decode(legacyEnvelope))
            val mismatchedVersionEnvelope = TypedCheckpointEnvelopeCodec.encode(
                GameId.MONSTER,
                expected.mode,
                MonsterRaidCheckpointCodec.PAYLOAD_CODEC_ID,
                MonsterRaidCheckpointCodec.PAYLOAD_CODEC_VERSION,
                legacy,
            )
            assertNull(MonsterRaidCheckpointCodec.decode(mismatchedVersionEnvelope))
        }
    }

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
    fun encodedCheckpointContinuesTheSameBossPrngSequenceForBothModes() {
        GameMode.entries.forEach { mode ->
            val source = MonsterRaidGameSession.start("raid-codec-prng-${mode.name}", 41L, 2, mode)
            val bossCheckpoint = source.snapshot.copy(
                stage = MonsterRaidStage.BOSS,
                enemy = MonsterRaidEnemy.TEMPEST_TITAN,
                enemyHealth = MonsterRaidGameSession.BOSS_HEALTH,
                bossPhase = MonsterRaidBossPhase.PHASE_1,
            )
            val checkpoint = MonsterRaidGameSession.restore(bossCheckpoint).snapshot
            val control = MonsterRaidGameSession.restore(checkpoint)
            val restored = MonsterRaidGameSession.restore(
                requireNotNull(MonsterRaidCheckpointCodec.decode(MonsterRaidCheckpointCodec.encode(checkpoint))),
            )
            assertTrue(control.resume())
            assertTrue(restored.resume())

            repeat(45) {
                assertEquals(control.advanceTicks(1), restored.advanceTicks(1))
            }

            assertTrue(control.snapshot.bossAttackOrdinal >= 2)
            assertEquals(control.snapshot.bossAttackOrdinal.toLong(), control.snapshot.prngState)
        }
    }

    @Test
    fun deterministicEncodingAndMalformedStateFailClosed() {
        val snapshot = MonsterRaidGameSession.start("raid-codec-negative", 9L, 2, GameMode.DUAL)
            .checkpointForAppBackground()
        val encoded = MonsterRaidCheckpointCodec.encode(snapshot)

        assertArrayEquals(encoded, MonsterRaidCheckpointCodec.encode(snapshot))
        assertNull(MonsterRaidCheckpointCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(MonsterRaidCheckpointCodec.decode(ByteArray(MonsterRaidCheckpointCodec.MAX_ENCODED_BYTES + 1)))
        val payload = requireNotNull(TypedCheckpointEnvelopeCodec.decode(encoded)).payload
        assertNull(MonsterRaidCheckpointCodec.decode(payload))
        val wrongRoute = TypedCheckpointEnvelopeCodec.encode(
            GameId.MONSTER,
            GameMode.SOLO,
            MonsterRaidCheckpointCodec.PAYLOAD_CODEC_ID,
            MonsterRaidCheckpointCodec.PAYLOAD_CODEC_VERSION,
            payload,
        )
        assertNull(MonsterRaidCheckpointCodec.decode(wrongRoute))
        assertNull(MonsterRaidCheckpointCodec.decode("""{"schemaVersion":1,"state":{}}""".toByteArray()))
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
