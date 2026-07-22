package com.motionarcade.app.checkpoint.typed

import com.motionarcade.core.contract.GameMode
import com.motionarcade.games.boxing.BoxingGameSession
import com.motionarcade.games.fishing.DualFishingGameSession
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.monster.MonsterRaidGameSession
import com.motionarcade.wire.snapshot.v1.BoxingDualStateProto
import com.motionarcade.wire.snapshot.v1.GameIdProto
import com.motionarcade.wire.snapshot.v1.GameModeProto
import com.motionarcade.wire.snapshot.v1.GameSessionSnapshotProto
import com.motionarcade.wire.snapshot.v1.GameStateProto
import com.motionarcade.wire.snapshot.v1.PlayerIdProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionSnapshotProtoAdapterTest {
    @Test
    fun fishingSoloRoundTripsToIdenticalCanonicalBytes() {
        val snapshot = FishingGameSession.start(
            sessionId = "typed-fishing-solo",
            seed = 11L,
            calibrationRevision = 2,
            eventTimelineOriginNs = 1_000_000L,
        ).snapshot

        val decoded = roundTrip(requireNotNull(GameSessionSnapshotProtoAdapter.encode(snapshot)))

        assertTrue(decoded is DecodedGameSessionSnapshot.FishingSolo)
        assertEquals(snapshot, (decoded as DecodedGameSessionSnapshot.FishingSolo).snapshot)
    }

    @Test
    fun fishingDualRoundTripsToIdenticalCanonicalBytes() {
        val snapshot = DualFishingGameSession.start(
            sessionId = "typed-fishing-dual",
            seed = 12L,
            calibrationRevision = 3,
        ).snapshot

        val decoded = roundTrip(requireNotNull(GameSessionSnapshotProtoAdapter.encode(snapshot)))

        assertTrue(decoded is DecodedGameSessionSnapshot.FishingDual)
        assertEquals(snapshot, (decoded as DecodedGameSessionSnapshot.FishingDual).snapshot)
    }

    @Test
    fun boxingSoloRoundTripsToIdenticalCanonicalBytes() {
        val snapshot = BoxingGameSession.start(
            sessionId = "typed-boxing-solo",
            seed = 13L,
            calibrationRevision = 4,
            mode = GameMode.SOLO,
        ).snapshot

        val decoded = roundTrip(requireNotNull(GameSessionSnapshotProtoAdapter.encode(snapshot)))

        assertTrue(decoded is DecodedGameSessionSnapshot.BoxingSolo)
        assertEquals(snapshot, (decoded as DecodedGameSessionSnapshot.BoxingSolo).snapshot)
    }

    @Test
    fun boxingDualRoundTripsToIdenticalCanonicalBytes() {
        val snapshot = BoxingGameSession.start(
            sessionId = "typed-boxing-dual",
            seed = 14L,
            calibrationRevision = 5,
            mode = GameMode.DUAL,
        ).snapshot

        val decoded = roundTrip(requireNotNull(GameSessionSnapshotProtoAdapter.encode(snapshot)))

        assertTrue(decoded is DecodedGameSessionSnapshot.BoxingDual)
        assertEquals(snapshot, (decoded as DecodedGameSessionSnapshot.BoxingDual).snapshot)
    }

    @Test
    fun monsterSoloRoundTripsToIdenticalCanonicalBytes() {
        val snapshot = MonsterRaidGameSession.start(
            sessionId = "typed-monster-solo",
            seed = 15L,
            calibrationRevision = 6,
            mode = GameMode.SOLO,
        ).snapshot

        val decoded = roundTrip(requireNotNull(GameSessionSnapshotProtoAdapter.encode(snapshot)))

        assertTrue(decoded is DecodedGameSessionSnapshot.MonsterSolo)
        assertEquals(snapshot, (decoded as DecodedGameSessionSnapshot.MonsterSolo).snapshot)
    }

    @Test
    fun monsterDualRoundTripsToIdenticalCanonicalBytes() {
        val snapshot = MonsterRaidGameSession.start(
            sessionId = "typed-monster-dual",
            seed = 16L,
            calibrationRevision = 7,
            mode = GameMode.DUAL,
        ).snapshot

        val decoded = roundTrip(requireNotNull(GameSessionSnapshotProtoAdapter.encode(snapshot)))

        assertTrue(decoded is DecodedGameSessionSnapshot.MonsterDual)
        assertEquals(snapshot, (decoded as DecodedGameSessionSnapshot.MonsterDual).snapshot)
    }

    @Test
    fun rejectsUnknownWireField() {
        val canonical = fishingSoloBytes()
        val unknownField100WithValueOne = byteArrayOf(0xa0.toByte(), 0x06, 0x01)

        assertRejected(canonical + unknownField100WithValueOne)
    }

    @Test
    fun rejectsNonCanonicalDuplicateKnownFieldEncoding() {
        val canonical = fishingSoloBytes()
        val duplicateSchemaVersionOne = byteArrayOf(0x08, 0x01)

        assertRejected(canonical + duplicateSchemaVersionOne)
    }

    @Test
    fun rejectsDuplicateCommittedRewards() {
        val mutated = fishingSoloProto().toBuilder()
            .addCommittedRewardIds("duplicate-reward")
            .addCommittedRewardIds("duplicate-reward")
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsStateVariantThatDoesNotMatchGame() {
        val mutated = fishingSoloProto().toBuilder()
            .setState(
                GameStateProto.newBuilder()
                    .setBoxingDual(BoxingDualStateProto.getDefaultInstance())
                    .build(),
            )
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsGameThatDoesNotMatchStateVariant() {
        val mutated = fishingSoloProto().toBuilder()
            .setGameId(GameIdProto.GAME_ID_BOXING)
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsModeThatDoesNotMatchStateVariant() {
        val mutated = fishingSoloProto().toBuilder()
            .setMode(GameModeProto.GAME_MODE_DUAL)
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsDuplicatePlayerIdentity() {
        val root = boxingDualProto()
        val duplicateP1 = root.getPlayers(1).toBuilder()
            .setBoxingDual(
                root.getPlayers(1).boxingDual.toBuilder()
                    .setPlayerId(PlayerIdProto.PLAYER_ID_P1)
                    .build(),
            )
            .build()
        val mutated = root.toBuilder()
            .setPlayers(1, duplicateP1)
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsNonCanonicalPlayerOrder() {
        val root = boxingDualProto()
        val mutated = root.toBuilder()
            .clearPlayers()
            .addPlayers(root.getPlayers(1))
            .addPlayers(root.getPlayers(0))
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsInvalidPlayerCardinality() {
        val mutated = boxingDualProto().toBuilder()
            .removePlayers(1)
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsUnspecifiedRequiredEnum() {
        val mutated = fishingSoloProto().toBuilder()
            .setGameId(GameIdProto.GAME_ID_UNSPECIFIED)
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsMalformedWireBytes() {
        assertRejected(byteArrayOf(0x80.toByte()))
    }

    @Test
    fun rejectsWirePayloadAboveTheCheckpointBudgetBeforeParsing() {
        assertRejected(ByteArray(GameSessionSnapshotProtoAdapter.MAX_WIRE_BYTES + 1))
    }

    @Test
    fun rejectsAiAsPendingDualMonsterUltimatePlayer() {
        val root = monsterDualProto()
        val state = root.state.monsterDual.toBuilder()
            .setPendingUltimatePlayer(PlayerIdProto.PLAYER_ID_AI)
            .setPendingUltimateTimestampNs(1L)
            .build()
        val mutated = root.toBuilder()
            .setState(root.state.toBuilder().setMonsterDual(state))
            .build()

        assertRejected(mutated.toByteArray())
    }

    @Test
    fun rejectsNegativePendingDualMonsterUltimateTimestamp() {
        val root = monsterDualProto()
        val state = root.state.monsterDual.toBuilder()
            .setPendingUltimatePlayer(PlayerIdProto.PLAYER_ID_P1)
            .setPendingUltimateTimestampNs(-1L)
            .build()
        val mutated = root.toBuilder()
            .setState(root.state.toBuilder().setMonsterDual(state))
            .build()

        assertRejected(mutated.toByteArray())
    }

    private fun roundTrip(canonical: ByteArray): DecodedGameSessionSnapshot {
        val decoded = GameSessionSnapshotProtoAdapter.decode(canonical)
        assertNotNull(decoded)
        val reencoded = when (val value = requireNotNull(decoded)) {
            is DecodedGameSessionSnapshot.FishingSolo -> GameSessionSnapshotProtoAdapter.encode(value.snapshot)
            is DecodedGameSessionSnapshot.FishingDual -> GameSessionSnapshotProtoAdapter.encode(value.snapshot)
            is DecodedGameSessionSnapshot.BoxingSolo -> GameSessionSnapshotProtoAdapter.encode(value.snapshot)
            is DecodedGameSessionSnapshot.BoxingDual -> GameSessionSnapshotProtoAdapter.encode(value.snapshot)
            is DecodedGameSessionSnapshot.MonsterSolo -> GameSessionSnapshotProtoAdapter.encode(value.snapshot)
            is DecodedGameSessionSnapshot.MonsterDual -> GameSessionSnapshotProtoAdapter.encode(value.snapshot)
        }
        assertNotNull(reencoded)
        assertArrayEquals(canonical, reencoded)
        return decoded
    }

    private fun fishingSoloBytes(): ByteArray = requireNotNull(
        GameSessionSnapshotProtoAdapter.encode(
            FishingGameSession.start(
                sessionId = "typed-reject-fishing-solo",
                seed = 101L,
                calibrationRevision = 8,
                eventTimelineOriginNs = 2_000_000L,
            ).snapshot,
        ),
    )

    private fun fishingSoloProto(): GameSessionSnapshotProto =
        GameSessionSnapshotProto.parseFrom(fishingSoloBytes())

    private fun boxingDualProto(): GameSessionSnapshotProto = GameSessionSnapshotProto.parseFrom(
        requireNotNull(
            GameSessionSnapshotProtoAdapter.encode(
                BoxingGameSession.start(
                    sessionId = "typed-reject-boxing-dual",
                    seed = 102L,
                    calibrationRevision = 9,
                    mode = GameMode.DUAL,
                ).snapshot,
            ),
        ),
    )

    private fun monsterDualProto(): GameSessionSnapshotProto = GameSessionSnapshotProto.parseFrom(
        requireNotNull(
            GameSessionSnapshotProtoAdapter.encode(
                MonsterRaidGameSession.start(
                    sessionId = "typed-reject-monster-dual",
                    seed = 103L,
                    calibrationRevision = 10,
                    mode = GameMode.DUAL,
                ).snapshot,
            ),
        ),
    )

    private fun assertRejected(bytes: ByteArray) {
        assertNull(GameSessionSnapshotProtoAdapter.decode(bytes))
    }
}
