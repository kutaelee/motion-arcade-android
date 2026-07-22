package com.motionarcade.app.checkpoint.typed

import com.motionarcade.core.contract.GameId
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.boxing.BoxingAiAttack
import com.motionarcade.games.boxing.BoxingDodgeDirection
import com.motionarcade.games.boxing.BoxingGameSession
import com.motionarcade.games.boxing.BoxingOutcome
import com.motionarcade.games.boxing.BoxingPhase
import com.motionarcade.games.boxing.BoxingPlayerState
import com.motionarcade.games.boxing.BoxingSnapshot
import com.motionarcade.games.fishing.DualFishingGameSession
import com.motionarcade.games.fishing.DualFishingPlayerState
import com.motionarcade.games.fishing.DualFishingSnapshot
import com.motionarcade.games.fishing.FishingFish
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase
import com.motionarcade.games.fishing.FishingSnapshot
import com.motionarcade.games.fishing.FishingTimelineEpoch
import com.motionarcade.games.monster.MonsterBossAttack
import com.motionarcade.games.monster.MonsterRaidBossPhase
import com.motionarcade.games.monster.MonsterRaidClass
import com.motionarcade.games.monster.MonsterRaidEnemy
import com.motionarcade.games.monster.MonsterRaidGameSession
import com.motionarcade.games.monster.MonsterRaidLane
import com.motionarcade.games.monster.MonsterRaidOutcome
import com.motionarcade.games.monster.MonsterRaidPlayerState
import com.motionarcade.games.monster.MonsterRaidSnapshot
import com.motionarcade.games.monster.MonsterRaidStage
import com.motionarcade.wire.snapshot.v1.BoxingAiAttackProto
import com.motionarcade.wire.snapshot.v1.BoxingDodgeDirectionProto
import com.motionarcade.wire.snapshot.v1.BoxingDualStateProto
import com.motionarcade.wire.snapshot.v1.BoxingOutcomeProto
import com.motionarcade.wire.snapshot.v1.BoxingPhaseProto
import com.motionarcade.wire.snapshot.v1.BoxingPlayerProto
import com.motionarcade.wire.snapshot.v1.BoxingSoloStateProto
import com.motionarcade.wire.snapshot.v1.DeterministicPrngProto
import com.motionarcade.wire.snapshot.v1.FishingDualPlayerProto
import com.motionarcade.wire.snapshot.v1.FishingDualStateProto
import com.motionarcade.wire.snapshot.v1.FishingFishProto
import com.motionarcade.wire.snapshot.v1.FishingOutcomeProto
import com.motionarcade.wire.snapshot.v1.FishingPhaseProto
import com.motionarcade.wire.snapshot.v1.FishingSoloPlayerProto
import com.motionarcade.wire.snapshot.v1.FishingSoloStateProto
import com.motionarcade.wire.snapshot.v1.FishingTimelineEpochProto
import com.motionarcade.wire.snapshot.v1.GameIdProto
import com.motionarcade.wire.snapshot.v1.GameModeProto
import com.motionarcade.wire.snapshot.v1.GameSessionSnapshotProto
import com.motionarcade.wire.snapshot.v1.GameStateProto
import com.motionarcade.wire.snapshot.v1.MonsterBossAttackProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidBossPhaseProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidClassProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidEnemyProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidLaneProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidOutcomeProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidPlayerProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidStageProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidStateProto
import com.motionarcade.wire.snapshot.v1.PauseReasonProto
import com.motionarcade.wire.snapshot.v1.PlayerIdProto
import com.motionarcade.wire.snapshot.v1.PlayerStateProto
import com.motionarcade.wire.snapshot.v1.SessionStatusProto
import java.util.LinkedHashMap
import java.util.LinkedHashSet

sealed interface DecodedGameSessionSnapshot {
    data class FishingSolo(val snapshot: FishingSnapshot) : DecodedGameSessionSnapshot
    data class FishingDual(val snapshot: DualFishingSnapshot) : DecodedGameSessionSnapshot
    data class BoxingSolo(val snapshot: BoxingSnapshot) : DecodedGameSessionSnapshot
    data class BoxingDual(val snapshot: BoxingSnapshot) : DecodedGameSessionSnapshot
    data class MonsterSolo(val snapshot: MonsterRaidSnapshot) : DecodedGameSessionSnapshot
    data class MonsterDual(val snapshot: MonsterRaidSnapshot) : DecodedGameSessionSnapshot
}

/** Fail-closed adapter for the versioned public checkpoint contract. */
object GameSessionSnapshotProtoAdapter {
    const val ROOT_SCHEMA_VERSION = 1
    const val MAX_WIRE_BYTES = 32 * 1024

    fun encode(snapshot: FishingSnapshot): ByteArray? = guardedEncode {
        require(snapshot.mode == GameMode.SOLO)
        FishingGameSession.restore(snapshot)
        fishingSoloProto(snapshot).toByteArray()
    }

    fun encode(snapshot: DualFishingSnapshot): ByteArray? = guardedEncode {
        DualFishingGameSession.restore(snapshot)
        fishingDualProto(snapshot).toByteArray()
    }

    fun encode(snapshot: BoxingSnapshot): ByteArray? = guardedEncode {
        BoxingGameSession.restore(snapshot)
        boxingProto(snapshot).toByteArray()
    }

    fun encode(snapshot: MonsterRaidSnapshot): ByteArray? = guardedEncode {
        MonsterRaidGameSession.restore(snapshot)
        monsterProto(snapshot).toByteArray()
    }

    fun decode(bytes: ByteArray): DecodedGameSessionSnapshot? {
        if (bytes.size !in 1..MAX_WIRE_BYTES) return null
        return try {
            val proto = GameSessionSnapshotProto.parseFrom(bytes)
            val decoded = decodeProto(proto) ?: return null
            val canonical = when (decoded) {
                is DecodedGameSessionSnapshot.FishingSolo -> encode(decoded.snapshot)
                is DecodedGameSessionSnapshot.FishingDual -> encode(decoded.snapshot)
                is DecodedGameSessionSnapshot.BoxingSolo -> encode(decoded.snapshot)
                is DecodedGameSessionSnapshot.BoxingDual -> encode(decoded.snapshot)
                is DecodedGameSessionSnapshot.MonsterSolo -> encode(decoded.snapshot)
                is DecodedGameSessionSnapshot.MonsterDual -> encode(decoded.snapshot)
            }
            decoded.takeIf { canonical != null && canonical.contentEquals(bytes) }
        } catch (_: Exception) {
            null
        }
    }

    private inline fun guardedEncode(block: () -> ByteArray): ByteArray? = try {
        block().also { encoded -> require(encoded.size <= MAX_WIRE_BYTES) }
    } catch (_: Exception) {
        null
    }

    private fun root(
        sessionId: String,
        gameId: GameId,
        mode: GameMode,
        simulationTick: Long,
        status: SessionStatus,
        seed: Long,
        prngAlgorithmId: String,
        prngAlgorithmVersion: Int,
        prngState: Long,
        contentRevision: String,
        pauseReason: PauseReason?,
    ) = GameSessionSnapshotProto.newBuilder()
        .setSchemaVersion(ROOT_SCHEMA_VERSION)
        .setSessionId(sessionId)
        .setGameId(gameId.toProto())
        .setMode(mode.toProto())
        .setSimulationTick(simulationTick)
        .setStatus(status.toProto())
        .setSeed(seed)
        .setPrng(
            DeterministicPrngProto.newBuilder()
                .setAlgorithmId(prngAlgorithmId)
                .setAlgorithmVersion(nonNegative(prngAlgorithmVersion))
                .addState(prngState),
        )
        .setContentRevision(contentRevision)
        .also { builder -> pauseReason?.let { builder.pauseReason = it.toProto() } }

    private fun fishingSoloProto(snapshot: FishingSnapshot): GameSessionSnapshotProto {
        require(snapshot.gameId == GameId.FISHING && snapshot.mode == GameMode.SOLO)
        val state = FishingSoloStateProto.newBuilder()
            .setPayloadVersion(nonNegative(snapshot.schemaVersion))
            .setEventTimelineEpoch(snapshot.eventTimelineEpoch)
            .setEventTimelineBaseNs(snapshot.eventTimelineBaseNs)
            .setEventTimelineSimulationBaseTick(snapshot.eventTimelineSimulationBaseTick)
            .setEventTimelineLedgerVersion(nonNegative(snapshot.eventTimelineLedgerVersion))
            .addAllEventTimelineLedger(snapshot.eventTimelineLedger.map { it.toProto() })
            .setCalibrationRevision(nonNegative(snapshot.calibrationRevision))
            .setPhase(snapshot.phase.toProto())
            .setRegionDifficulty(nonNegative(snapshot.regionDifficulty))
        snapshot.fish?.let { state.fish = it.toProto() }
        snapshot.biteAtTick?.let { state.biteAtTick = it }
        snapshot.hookDeadlineTick?.let { state.hookDeadlineTick = it }
        snapshot.recoveryScheduledAtTick?.let { state.recoveryScheduledAtTick = it }
        snapshot.pendingRewardId?.let { state.pendingRewardId = it }

        val player = FishingSoloPlayerProto.newBuilder()
            .setPlayerId(PlayerIdProto.PLAYER_ID_P1)
            .setReelCycles(nonNegative(snapshot.reelCycles))
            .setTension(nonNegative(snapshot.tension))
            .setReelScore(nonNegative(snapshot.reelScore))
            .setTensionScore(nonNegative(snapshot.tensionScore))
            .setRecoveryTokens(nonNegative(snapshot.recoveryTokens))
            .setFailureStreak(nonNegative(snapshot.failureStreak))
            .setScore(nonNegative(snapshot.score))
            .setAcceptedSequenceWatermark(snapshot.acceptedSequenceWatermark)
            .setAcceptedEventTimestampWatermarkNs(snapshot.acceptedEventTimestampWatermarkNs)
            .setLastAppliedSequence(snapshot.lastAppliedSequence)
            .setLastAppliedEventEpoch(snapshot.lastAppliedEventEpoch)
            .setLastAppliedEventTimestampNs(snapshot.lastAppliedEventTimestampNs)
            .setLastStateChangingSequence(snapshot.lastStateChangingSequence)
            .setLastStateChangingEventEpoch(snapshot.lastStateChangingEventEpoch)
            .setLastStateChangingEventTimestampNs(snapshot.lastStateChangingEventTimestampNs)
        snapshot.castEventSequence?.let { player.castEventSequence = it }
        snapshot.castEventEpoch?.let { player.castEventEpoch = it }
        snapshot.castEventTick?.let { player.castEventTick = it }
        snapshot.castEventTimestampNs?.let { player.castEventTimestampNs = it }
        snapshot.castEventTimelineBaseNs?.let { player.castEventTimelineBaseNs = it }
        snapshot.castEventTimelineSimulationBaseTick?.let { player.castEventTimelineSimulationBaseTick = it }
        snapshot.castQuality?.let { player.castQuality = finite(it) }
        snapshot.hookEventSequence?.let { player.hookEventSequence = it }
        snapshot.hookEventEpoch?.let { player.hookEventEpoch = it }
        snapshot.hookEventTick?.let { player.hookEventTick = it }
        snapshot.hookEventTimestampNs?.let { player.hookEventTimestampNs = it }
        snapshot.hookEventTimelineBaseNs?.let { player.hookEventTimelineBaseNs = it }
        snapshot.hookEventTimelineSimulationBaseTick?.let { player.hookEventTimelineSimulationBaseTick = it }
        snapshot.hookQuality?.let { player.hookQuality = finite(it) }
        snapshot.netQuality?.let { player.netQuality = finite(it) }
        snapshot.outcome?.let { player.outcome = it.toProto() }
        snapshot.lastAppliedEventTick?.let { player.lastAppliedEventTick = it }
        snapshot.lastAppliedEventTimelineBaseNs?.let { player.lastAppliedEventTimelineBaseNs = it }
        snapshot.lastAppliedEventTimelineSimulationBaseTick?.let { player.lastAppliedEventTimelineSimulationBaseTick = it }
        snapshot.lastStateChangingEventTick?.let { player.lastStateChangingEventTick = it }
        snapshot.lastStateChangingEventTimelineBaseNs?.let { player.lastStateChangingEventTimelineBaseNs = it }
        snapshot.lastStateChangingEventTimelineSimulationBaseTick?.let {
            player.lastStateChangingEventTimelineSimulationBaseTick = it
        }

        return rootOf(snapshot)
            .setState(GameStateProto.newBuilder().setFishingSolo(state))
            .addPlayers(PlayerStateProto.newBuilder().setFishingSolo(player))
            .addAllCommittedRewardIds(snapshot.committedRewardIds)
            .build()
    }

    private fun fishingDualProto(snapshot: DualFishingSnapshot): GameSessionSnapshotProto {
        require(snapshot.gameId == GameId.FISHING && snapshot.mode == GameMode.DUAL)
        require(snapshot.players.keys == linkedSetOf(PlayerId.P1, PlayerId.P2))
        val state = FishingDualStateProto.newBuilder()
            .setPayloadVersion(nonNegative(snapshot.schemaVersion))
            .setCalibrationRevision(nonNegative(snapshot.calibrationRevision))
            .setRodPlayerId(snapshot.rodPlayerId.toProto())
            .setCatches(nonNegative(snapshot.catches))
            .setCombo(nonNegative(snapshot.combo))
        return rootOf(snapshot)
            .setState(GameStateProto.newBuilder().setFishingDual(state))
            .addAllPlayers(listOf(PlayerId.P1, PlayerId.P2).map { id ->
                PlayerStateProto.newBuilder().setFishingDual(snapshot.players.getValue(id).toProto()).build()
            })
            .build()
    }

    private fun boxingProto(snapshot: BoxingSnapshot): GameSessionSnapshotProto {
        require(snapshot.gameId == GameId.BOXING)
        val builder = rootOf(snapshot)
        if (snapshot.mode == GameMode.SOLO) {
            require(snapshot.players.isEmpty())
            val state = BoxingSoloStateProto.newBuilder()
                .setPayloadVersion(nonNegative(snapshot.schemaVersion))
                .setCalibrationRevision(nonNegative(snapshot.calibrationRevision))
                .setPhase(snapshot.phase.toProto())
                .setOpponentHealth(nonNegative(snapshot.opponentHealth))
                .setRoundTicksRemaining(nonNegative(snapshot.roundTicksRemaining))
                .setAiTelegraphTicksRemaining(nonNegative(snapshot.aiTelegraphTicksRemaining))
                .setAiAttackOrdinal(nonNegative(snapshot.aiAttackOrdinal))
            snapshot.aiTelegraph?.let { state.aiTelegraph = it.toProto() }
            snapshot.outcome?.let { state.outcome = it.toProto() }
            builder.setState(GameStateProto.newBuilder().setBoxingSolo(state))
                .addPlayers(PlayerStateProto.newBuilder().setBoxingSolo(snapshot.soloPlayer().toProto()))
        } else {
            require(snapshot.players.keys == linkedSetOf(PlayerId.P1, PlayerId.P2))
            val state = BoxingDualStateProto.newBuilder()
                .setPayloadVersion(nonNegative(snapshot.schemaVersion))
                .setCalibrationRevision(nonNegative(snapshot.calibrationRevision))
                .setPhase(snapshot.phase.toProto())
                .setRoundTicksRemaining(nonNegative(snapshot.roundTicksRemaining))
            snapshot.outcome?.let { state.outcome = it.toProto() }
            builder.setState(GameStateProto.newBuilder().setBoxingDual(state))
                .addAllPlayers(listOf(PlayerId.P1, PlayerId.P2).map { id ->
                    PlayerStateProto.newBuilder().setBoxingDual(snapshot.players.getValue(id).toProto()).build()
                })
        }
        return builder.build()
    }

    private fun monsterProto(snapshot: MonsterRaidSnapshot): GameSessionSnapshotProto {
        require(snapshot.gameId == GameId.MONSTER)
        val expected = if (snapshot.mode == GameMode.SOLO) linkedSetOf(PlayerId.P1, PlayerId.AI) else linkedSetOf(PlayerId.P1, PlayerId.P2)
        require(snapshot.players.keys == expected)
        val state = MonsterRaidStateProto.newBuilder()
            .setPayloadVersion(nonNegative(snapshot.schemaVersion))
            .setCalibrationRevision(nonNegative(snapshot.calibrationRevision))
            .setStage(snapshot.stage.toProto())
            .setWaveIndex(nonNegative(snapshot.waveIndex))
            .setEnemy(snapshot.enemy.toProto())
            .setEnemyHealth(nonNegative(snapshot.enemyHealth))
            .setBossTelegraphTicksRemaining(nonNegative(snapshot.bossTelegraphTicksRemaining))
            .setBossAttackOrdinal(nonNegative(snapshot.bossAttackOrdinal))
            .setTeamCharge(nonNegative(snapshot.teamCharge))
            .setTeamScore(nonNegative(snapshot.teamScore))
            .setRoundTicksRemaining(nonNegative(snapshot.roundTicksRemaining))
        snapshot.bossPhase?.let { state.bossPhase = it.toProto() }
        snapshot.bossAttack?.let { state.bossAttack = it.toProto() }
        snapshot.pendingUltimatePlayer?.let { state.pendingUltimatePlayer = it.toProto() }
        snapshot.pendingUltimateTimestampNs?.let { state.pendingUltimateTimestampNs = it }
        snapshot.lastHumanCoreActionTick?.let { state.lastHumanCoreActionTick = it }
        snapshot.outcome?.let { state.outcome = it.toProto() }
        val ids = if (snapshot.mode == GameMode.SOLO) listOf(PlayerId.P1, PlayerId.AI) else listOf(PlayerId.P1, PlayerId.P2)
        val root = rootOf(snapshot)
        val gameState = GameStateProto.newBuilder()
        if (snapshot.mode == GameMode.SOLO) gameState.monsterSolo = state.build() else gameState.monsterDual = state.build()
        root.state = gameState.build()
        ids.forEach { id ->
            val playerState = PlayerStateProto.newBuilder()
            if (snapshot.mode == GameMode.SOLO) playerState.monsterSolo = snapshot.players.getValue(id).toProto()
            else playerState.monsterDual = snapshot.players.getValue(id).toProto()
            root.addPlayers(playerState)
        }
        return root.build()
    }

    private fun rootOf(snapshot: FishingSnapshot) = root(snapshot.sessionId, snapshot.gameId, snapshot.mode, snapshot.simulationTick, snapshot.status, snapshot.seed, snapshot.prngAlgorithmId, snapshot.prngAlgorithmVersion, snapshot.prngState, snapshot.contentRevision, snapshot.pauseReason)
    private fun rootOf(snapshot: DualFishingSnapshot) = root(snapshot.sessionId, snapshot.gameId, snapshot.mode, snapshot.simulationTick, snapshot.status, snapshot.seed, snapshot.prngAlgorithmId, snapshot.prngAlgorithmVersion, snapshot.prngState, snapshot.contentRevision, snapshot.pauseReason)
    private fun rootOf(snapshot: BoxingSnapshot) = root(snapshot.sessionId, snapshot.gameId, snapshot.mode, snapshot.simulationTick, snapshot.status, snapshot.seed, snapshot.prngAlgorithmId, snapshot.prngAlgorithmVersion, snapshot.prngState, snapshot.contentRevision, snapshot.pauseReason)
    private fun rootOf(snapshot: MonsterRaidSnapshot) = root(snapshot.sessionId, snapshot.gameId, snapshot.mode, snapshot.simulationTick, snapshot.status, snapshot.seed, snapshot.prngAlgorithmId, snapshot.prngAlgorithmVersion, snapshot.prngState, snapshot.contentRevision, snapshot.pauseReason)

    private fun decodeProto(proto: GameSessionSnapshotProto): DecodedGameSessionSnapshot? {
        require(proto.schemaVersion == ROOT_SCHEMA_VERSION)
        require(proto.hasState() && proto.hasPrng() && proto.prng.stateCount == 1)
        require(proto.sessionId.isNotEmpty())
        val gameId = proto.gameId.toDomain()
        val mode = proto.mode.toDomain()
        val status = proto.status.toDomain()
        val pauseReason = if (proto.hasPauseReason()) proto.pauseReason.toDomain() else null
        require((status == SessionStatus.PAUSED) == (pauseReason != null))
        require(proto.prng.algorithmVersion >= 0)
        require(proto.committedRewardIdsList.size == proto.committedRewardIdsList.toSet().size)
        return when (proto.state.variantCase) {
            GameStateProto.VariantCase.FISHING_SOLO -> {
                require(gameId == GameId.FISHING && mode == GameMode.SOLO)
                DecodedGameSessionSnapshot.FishingSolo(decodeFishingSolo(proto, status, pauseReason))
            }
            GameStateProto.VariantCase.FISHING_DUAL -> {
                require(gameId == GameId.FISHING && mode == GameMode.DUAL && proto.committedRewardIdsCount == 0)
                DecodedGameSessionSnapshot.FishingDual(decodeFishingDual(proto, status, pauseReason))
            }
            GameStateProto.VariantCase.BOXING_SOLO -> {
                require(gameId == GameId.BOXING && mode == GameMode.SOLO && proto.committedRewardIdsCount == 0)
                DecodedGameSessionSnapshot.BoxingSolo(decodeBoxing(proto, status, pauseReason, false))
            }
            GameStateProto.VariantCase.BOXING_DUAL -> {
                require(gameId == GameId.BOXING && mode == GameMode.DUAL && proto.committedRewardIdsCount == 0)
                DecodedGameSessionSnapshot.BoxingDual(decodeBoxing(proto, status, pauseReason, true))
            }
            GameStateProto.VariantCase.MONSTER_SOLO -> {
                require(gameId == GameId.MONSTER && mode == GameMode.SOLO && proto.committedRewardIdsCount == 0)
                DecodedGameSessionSnapshot.MonsterSolo(decodeMonster(proto, status, pauseReason, false))
            }
            GameStateProto.VariantCase.MONSTER_DUAL -> {
                require(gameId == GameId.MONSTER && mode == GameMode.DUAL && proto.committedRewardIdsCount == 0)
                DecodedGameSessionSnapshot.MonsterDual(decodeMonster(proto, status, pauseReason, true))
            }
            else -> null
        }
    }

    private fun decodeFishingSolo(
        root: GameSessionSnapshotProto,
        status: SessionStatus,
        pauseReason: PauseReason?,
    ): FishingSnapshot {
        require(root.playersCount == 1)
        val state = root.state.fishingSolo
        val wrapper = root.getPlayers(0)
        require(wrapper.variantCase == PlayerStateProto.VariantCase.FISHING_SOLO)
        val player = wrapper.fishingSolo
        require(player.playerId == PlayerIdProto.PLAYER_ID_P1)
        require(state.payloadVersion >= 0 && state.eventTimelineLedgerVersion >= 0 && state.calibrationRevision >= 0)
        val rewards = LinkedHashSet(root.committedRewardIdsList)
        val snapshot = FishingSnapshot(
            schemaVersion = state.payloadVersion,
            sessionId = root.sessionId,
            gameId = GameId.FISHING,
            mode = GameMode.SOLO,
            contentRevision = root.contentRevision,
            seed = root.seed,
            prngAlgorithmId = root.prng.algorithmId,
            prngAlgorithmVersion = root.prng.algorithmVersion,
            prngState = root.prng.getState(0),
            eventTimelineEpoch = state.eventTimelineEpoch,
            eventTimelineBaseNs = state.eventTimelineBaseNs,
            eventTimelineSimulationBaseTick = state.eventTimelineSimulationBaseTick,
            eventTimelineLedgerVersion = state.eventTimelineLedgerVersion,
            eventTimelineLedger = state.eventTimelineLedgerList.map {
                FishingTimelineEpoch(it.epoch, it.baseNs, it.simulationBaseTick)
            },
            calibrationRevision = state.calibrationRevision,
            simulationTick = root.simulationTick,
            status = status,
            pauseReason = pauseReason,
            phase = state.phase.toDomain(),
            fish = state.fish.takeIf { state.hasFish() }?.toDomain(),
            castEventSequence = player.castEventSequence.takeIf { player.hasCastEventSequence() },
            castEventEpoch = player.castEventEpoch.takeIf { player.hasCastEventEpoch() },
            castEventTick = player.castEventTick.takeIf { player.hasCastEventTick() },
            castEventTimestampNs = player.castEventTimestampNs.takeIf { player.hasCastEventTimestampNs() },
            castEventTimelineBaseNs = player.castEventTimelineBaseNs.takeIf { player.hasCastEventTimelineBaseNs() },
            castEventTimelineSimulationBaseTick = player.castEventTimelineSimulationBaseTick.takeIf { player.hasCastEventTimelineSimulationBaseTick() },
            castQuality = player.castQuality.takeIf { player.hasCastQuality() }?.also(::finite),
            hookEventSequence = player.hookEventSequence.takeIf { player.hasHookEventSequence() },
            hookEventEpoch = player.hookEventEpoch.takeIf { player.hasHookEventEpoch() },
            hookEventTick = player.hookEventTick.takeIf { player.hasHookEventTick() },
            hookEventTimestampNs = player.hookEventTimestampNs.takeIf { player.hasHookEventTimestampNs() },
            hookEventTimelineBaseNs = player.hookEventTimelineBaseNs.takeIf { player.hasHookEventTimelineBaseNs() },
            hookEventTimelineSimulationBaseTick = player.hookEventTimelineSimulationBaseTick.takeIf { player.hasHookEventTimelineSimulationBaseTick() },
            hookQuality = player.hookQuality.takeIf { player.hasHookQuality() }?.also(::finite),
            biteAtTick = state.biteAtTick.takeIf { state.hasBiteAtTick() },
            hookDeadlineTick = state.hookDeadlineTick.takeIf { state.hasHookDeadlineTick() },
            recoveryScheduledAtTick = state.recoveryScheduledAtTick.takeIf { state.hasRecoveryScheduledAtTick() },
            reelCycles = uint(player.reelCycles),
            tension = uint(player.tension),
            reelScore = uint(player.reelScore),
            tensionScore = uint(player.tensionScore),
            netQuality = player.netQuality.takeIf { player.hasNetQuality() }?.also(::finite),
            recoveryTokens = uint(player.recoveryTokens),
            failureStreak = uint(player.failureStreak),
            regionDifficulty = uint(state.regionDifficulty),
            score = uint(player.score),
            outcome = player.outcome.takeIf { player.hasOutcome() }?.toDomain(),
            acceptedSequenceWatermark = player.acceptedSequenceWatermark,
            acceptedEventTimestampWatermarkNs = player.acceptedEventTimestampWatermarkNs,
            lastAppliedSequence = player.lastAppliedSequence,
            lastAppliedEventEpoch = player.lastAppliedEventEpoch,
            lastAppliedEventTick = player.lastAppliedEventTick.takeIf { player.hasLastAppliedEventTick() },
            lastAppliedEventTimestampNs = player.lastAppliedEventTimestampNs,
            lastAppliedEventTimelineBaseNs = player.lastAppliedEventTimelineBaseNs.takeIf { player.hasLastAppliedEventTimelineBaseNs() },
            lastAppliedEventTimelineSimulationBaseTick = player.lastAppliedEventTimelineSimulationBaseTick.takeIf { player.hasLastAppliedEventTimelineSimulationBaseTick() },
            lastStateChangingSequence = player.lastStateChangingSequence,
            lastStateChangingEventEpoch = player.lastStateChangingEventEpoch,
            lastStateChangingEventTick = player.lastStateChangingEventTick.takeIf { player.hasLastStateChangingEventTick() },
            lastStateChangingEventTimestampNs = player.lastStateChangingEventTimestampNs,
            lastStateChangingEventTimelineBaseNs = player.lastStateChangingEventTimelineBaseNs.takeIf { player.hasLastStateChangingEventTimelineBaseNs() },
            lastStateChangingEventTimelineSimulationBaseTick = player.lastStateChangingEventTimelineSimulationBaseTick.takeIf { player.hasLastStateChangingEventTimelineSimulationBaseTick() },
            pendingRewardId = state.pendingRewardId.takeIf { state.hasPendingRewardId() },
            committedRewardIds = rewards,
        )
        FishingGameSession.restore(snapshot)
        return snapshot
    }

    private fun decodeFishingDual(root: GameSessionSnapshotProto, status: SessionStatus, pauseReason: PauseReason?): DualFishingSnapshot {
        require(root.playersCount == 2)
        val state = root.state.fishingDual
        require(state.payloadVersion >= 0 && state.calibrationRevision >= 0)
        val players = LinkedHashMap<PlayerId, DualFishingPlayerState>()
        root.playersList.forEach { wrapper ->
            require(wrapper.variantCase == PlayerStateProto.VariantCase.FISHING_DUAL)
            val player = wrapper.fishingDual
            val id = player.playerId.toDomain()
            require(id == PlayerId.P1 || id == PlayerId.P2)
            require(players.put(id, player.toDomain(id)) == null)
        }
        require(players.keys.toList() == listOf(PlayerId.P1, PlayerId.P2))
        val snapshot = DualFishingSnapshot(
            schemaVersion = state.payloadVersion,
            sessionId = root.sessionId,
            gameId = GameId.FISHING,
            mode = GameMode.DUAL,
            contentRevision = root.contentRevision,
            seed = root.seed,
            prngAlgorithmId = root.prng.algorithmId,
            prngAlgorithmVersion = root.prng.algorithmVersion,
            prngState = root.prng.getState(0),
            calibrationRevision = state.calibrationRevision,
            simulationTick = root.simulationTick,
            status = status,
            pauseReason = pauseReason,
            rodPlayerId = state.rodPlayerId.toDomain(),
            catches = uint(state.catches),
            combo = uint(state.combo),
            players = players,
        )
        DualFishingGameSession.restore(snapshot)
        return snapshot
    }

    private fun decodeBoxing(root: GameSessionSnapshotProto, status: SessionStatus, pauseReason: PauseReason?, dual: Boolean): BoxingSnapshot {
        val stateSchema: Int
        val calibration: Int
        val phase: BoxingPhase
        val roundTicks: Int
        val outcome: BoxingOutcome?
        val opponentHealth: Int
        val aiTelegraph: BoxingAiAttack?
        val aiTicks: Int
        val aiOrdinal: Int
        if (dual) {
            val state = root.state.boxingDual
            stateSchema = uint(state.payloadVersion)
            calibration = uint(state.calibrationRevision)
            phase = state.phase.toDomain()
            roundTicks = uint(state.roundTicksRemaining)
            outcome = state.outcome.takeIf { state.hasOutcome() }?.toDomain()
            opponentHealth = 0
            aiTelegraph = null
            aiTicks = 0
            aiOrdinal = 0
        } else {
            val state = root.state.boxingSolo
            stateSchema = uint(state.payloadVersion)
            calibration = uint(state.calibrationRevision)
            phase = state.phase.toDomain()
            roundTicks = uint(state.roundTicksRemaining)
            outcome = state.outcome.takeIf { state.hasOutcome() }?.toDomain()
            opponentHealth = uint(state.opponentHealth)
            aiTelegraph = state.aiTelegraph.takeIf { state.hasAiTelegraph() }?.toDomain()
            aiTicks = uint(state.aiTelegraphTicksRemaining)
            aiOrdinal = uint(state.aiAttackOrdinal)
        }
        val expectedIds = if (dual) listOf(PlayerId.P1, PlayerId.P2) else listOf(PlayerId.P1)
        require(root.playersCount == expectedIds.size)
        val players = LinkedHashMap<PlayerId, BoxingPlayerState>()
        root.playersList.forEachIndexed { index, wrapper ->
            require(wrapper.variantCase == if (dual) PlayerStateProto.VariantCase.BOXING_DUAL else PlayerStateProto.VariantCase.BOXING_SOLO)
            val proto = if (dual) wrapper.boxingDual else wrapper.boxingSolo
            val id = proto.playerId.toDomain()
            require(id == expectedIds[index] && players.put(id, proto.toDomain(id)) == null)
        }
        val p1 = players.getValue(PlayerId.P1)
        val snapshot = BoxingSnapshot(
            schemaVersion = stateSchema,
            sessionId = root.sessionId,
            gameId = GameId.BOXING,
            mode = if (dual) GameMode.DUAL else GameMode.SOLO,
            contentRevision = root.contentRevision,
            seed = root.seed,
            prngAlgorithmId = root.prng.algorithmId,
            prngAlgorithmVersion = root.prng.algorithmVersion,
            prngState = root.prng.getState(0),
            calibrationRevision = calibration,
            simulationTick = root.simulationTick,
            status = status,
            pauseReason = pauseReason,
            phase = phase,
            playerHealth = p1.health,
            opponentHealth = if (dual) players.getValue(PlayerId.P2).health else opponentHealth,
            playerStamina = p1.stamina,
            score = p1.score,
            roundTicksRemaining = roundTicks,
            aiTelegraph = aiTelegraph,
            aiTelegraphTicksRemaining = aiTicks,
            aiAttackOrdinal = aiOrdinal,
            guardTicksRemaining = p1.guardTicksRemaining,
            dodgeDirection = p1.dodgeDirection,
            dodgeTicksRemaining = p1.dodgeTicksRemaining,
            attackCooldownTicks = p1.attackCooldownTicks,
            requiresReturnToGuard = p1.requiresReturnToGuard,
            lastPlayerDamage = p1.lastPlayerDamage,
            lastReceivedDamage = p1.lastReceivedDamage,
            blockedAttackCount = p1.blockedAttackCount,
            dodgedAttackCount = p1.dodgedAttackCount,
            ignoredStrikeCount = p1.ignoredStrikeCount,
            acceptedSequenceWatermark = p1.acceptedSequenceWatermark,
            acceptedEventTimestampWatermarkNs = p1.acceptedEventTimestampWatermarkNs,
            lastAppliedSequence = p1.lastAppliedSequence,
            outcome = outcome,
            players = if (dual) players else emptyMap(),
        )
        BoxingGameSession.restore(snapshot)
        return snapshot
    }

    private fun decodeMonster(root: GameSessionSnapshotProto, status: SessionStatus, pauseReason: PauseReason?, dual: Boolean): MonsterRaidSnapshot {
        val state = if (dual) root.state.monsterDual else root.state.monsterSolo
        val expected = if (dual) listOf(PlayerId.P1, PlayerId.P2) else listOf(PlayerId.P1, PlayerId.AI)
        if (state.hasPendingUltimatePlayer()) {
            require(dual)
            require(
                state.pendingUltimatePlayer == PlayerIdProto.PLAYER_ID_P1 ||
                    state.pendingUltimatePlayer == PlayerIdProto.PLAYER_ID_P2,
            )
            require(state.hasPendingUltimateTimestampNs() && state.pendingUltimateTimestampNs >= 0L)
        } else {
            require(!state.hasPendingUltimateTimestampNs())
        }
        require(root.playersCount == 2)
        val players = LinkedHashMap<PlayerId, MonsterRaidPlayerState>()
        root.playersList.forEachIndexed { index, wrapper ->
            require(wrapper.variantCase == if (dual) PlayerStateProto.VariantCase.MONSTER_DUAL else PlayerStateProto.VariantCase.MONSTER_SOLO)
            val proto = if (dual) wrapper.monsterDual else wrapper.monsterSolo
            val id = proto.playerId.toDomain()
            require(id == expected[index] && players.put(id, proto.toDomain(id)) == null)
        }
        val snapshot = MonsterRaidSnapshot(
            schemaVersion = uint(state.payloadVersion),
            sessionId = root.sessionId,
            gameId = GameId.MONSTER,
            mode = if (dual) GameMode.DUAL else GameMode.SOLO,
            contentRevision = root.contentRevision,
            seed = root.seed,
            prngAlgorithmId = root.prng.algorithmId,
            prngAlgorithmVersion = root.prng.algorithmVersion,
            prngState = root.prng.getState(0),
            calibrationRevision = uint(state.calibrationRevision),
            simulationTick = root.simulationTick,
            status = status,
            pauseReason = pauseReason,
            stage = state.stage.toDomain(),
            waveIndex = uint(state.waveIndex),
            enemy = state.enemy.toDomain(),
            enemyHealth = uint(state.enemyHealth),
            bossPhase = state.bossPhase.takeIf { state.hasBossPhase() }?.toDomain(),
            bossAttack = state.bossAttack.takeIf { state.hasBossAttack() }?.toDomain(),
            bossTelegraphTicksRemaining = uint(state.bossTelegraphTicksRemaining),
            bossAttackOrdinal = uint(state.bossAttackOrdinal),
            teamCharge = uint(state.teamCharge),
            teamScore = uint(state.teamScore),
            roundTicksRemaining = uint(state.roundTicksRemaining),
            players = players,
            pendingUltimatePlayer = state.pendingUltimatePlayer.takeIf { state.hasPendingUltimatePlayer() }?.toDomain(),
            pendingUltimateTimestampNs = state.pendingUltimateTimestampNs.takeIf { state.hasPendingUltimateTimestampNs() },
            lastHumanCoreActionTick = state.lastHumanCoreActionTick.takeIf { state.hasLastHumanCoreActionTick() },
            outcome = state.outcome.takeIf { state.hasOutcome() }?.toDomain(),
        )
        MonsterRaidGameSession.restore(snapshot)
        return snapshot
    }

    private fun FishingTimelineEpoch.toProto() = FishingTimelineEpochProto.newBuilder()
        .setEpoch(epoch).setBaseNs(baseNs).setSimulationBaseTick(simulationBaseTick).build()

    private fun DualFishingPlayerState.toProto(): FishingDualPlayerProto {
        val builder = FishingDualPlayerProto.newBuilder()
            .setPlayerId(playerId.toProto()).setPhase(phase.toProto())
            .setReelCycles(nonNegative(reelCycles)).setTension(nonNegative(tension))
            .setRecoveryTokens(nonNegative(recoveryTokens)).setScore(nonNegative(score))
            .setAcceptedSequenceWatermark(acceptedSequenceWatermark)
            .setAcceptedEventTimestampWatermarkNs(acceptedEventTimestampWatermarkNs)
            .setLastAppliedSequence(lastAppliedSequence)
        fish?.let { builder.fish = it.toProto() }
        biteAtTick?.let { builder.biteAtTick = it }
        hookDeadlineTick?.let { builder.hookDeadlineTick = it }
        assistDeadlineTick?.let { builder.assistDeadlineTick = it }
        outcome?.let { builder.outcome = it.toProto() }
        return builder.build()
    }

    private fun FishingDualPlayerProto.toDomain(id: PlayerId) = DualFishingPlayerState(
        playerId = id,
        phase = phase.toDomain(),
        fish = fish.takeIf { hasFish() }?.toDomain(),
        biteAtTick = biteAtTick.takeIf { hasBiteAtTick() },
        hookDeadlineTick = hookDeadlineTick.takeIf { hasHookDeadlineTick() },
        assistDeadlineTick = assistDeadlineTick.takeIf { hasAssistDeadlineTick() },
        reelCycles = uint(reelCycles), tension = uint(tension), recoveryTokens = uint(recoveryTokens), score = uint(score),
        outcome = outcome.takeIf { hasOutcome() }?.toDomain(),
        acceptedSequenceWatermark = acceptedSequenceWatermark,
        acceptedEventTimestampWatermarkNs = acceptedEventTimestampWatermarkNs,
        lastAppliedSequence = lastAppliedSequence,
    )

    private fun BoxingSnapshot.soloPlayer() = BoxingPlayerState(
        playerId = PlayerId.P1, health = playerHealth, stamina = playerStamina, score = score,
        guardTicksRemaining = guardTicksRemaining, dodgeDirection = dodgeDirection,
        dodgeTicksRemaining = dodgeTicksRemaining, attackCooldownTicks = attackCooldownTicks,
        requiresReturnToGuard = requiresReturnToGuard, lastPlayerDamage = lastPlayerDamage,
        lastReceivedDamage = lastReceivedDamage, blockedAttackCount = blockedAttackCount,
        dodgedAttackCount = dodgedAttackCount, ignoredStrikeCount = ignoredStrikeCount,
        acceptedSequenceWatermark = acceptedSequenceWatermark,
        acceptedEventTimestampWatermarkNs = acceptedEventTimestampWatermarkNs,
        lastAppliedSequence = lastAppliedSequence,
    )

    private fun BoxingPlayerState.toProto(): BoxingPlayerProto {
        val builder = BoxingPlayerProto.newBuilder()
            .setPlayerId(playerId.toProto()).setHealth(nonNegative(health)).setStamina(nonNegative(stamina))
            .setScore(nonNegative(score)).setGuardTicksRemaining(nonNegative(guardTicksRemaining))
            .setDodgeTicksRemaining(nonNegative(dodgeTicksRemaining)).setAttackCooldownTicks(nonNegative(attackCooldownTicks))
            .setRequiresReturnToGuard(requiresReturnToGuard).setLastPlayerDamage(nonNegative(lastPlayerDamage))
            .setLastReceivedDamage(nonNegative(lastReceivedDamage)).setBlockedAttackCount(nonNegative(blockedAttackCount))
            .setDodgedAttackCount(nonNegative(dodgedAttackCount)).setIgnoredStrikeCount(nonNegative(ignoredStrikeCount))
            .setAcceptedSequenceWatermark(acceptedSequenceWatermark)
            .setAcceptedEventTimestampWatermarkNs(acceptedEventTimestampWatermarkNs)
            .setLastAppliedSequence(lastAppliedSequence)
        dodgeDirection?.let { builder.dodgeDirection = it.toProto() }
        return builder.build()
    }

    private fun BoxingPlayerProto.toDomain(id: PlayerId) = BoxingPlayerState(
        playerId = id, health = uint(health), stamina = uint(stamina), score = uint(score),
        guardTicksRemaining = uint(guardTicksRemaining),
        dodgeDirection = dodgeDirection.takeIf { hasDodgeDirection() }?.toDomain(),
        dodgeTicksRemaining = uint(dodgeTicksRemaining), attackCooldownTicks = uint(attackCooldownTicks),
        requiresReturnToGuard = requiresReturnToGuard, lastPlayerDamage = uint(lastPlayerDamage),
        lastReceivedDamage = uint(lastReceivedDamage), blockedAttackCount = uint(blockedAttackCount),
        dodgedAttackCount = uint(dodgedAttackCount), ignoredStrikeCount = uint(ignoredStrikeCount),
        acceptedSequenceWatermark = acceptedSequenceWatermark,
        acceptedEventTimestampWatermarkNs = acceptedEventTimestampWatermarkNs,
        lastAppliedSequence = lastAppliedSequence,
    )

    private fun MonsterRaidPlayerState.toProto() = MonsterRaidPlayerProto.newBuilder()
        .setPlayerId(playerId.toProto()).setPlayerClass(playerClass.toProto())
        .setHealth(nonNegative(health)).setStamina(nonNegative(stamina)).setScore(nonNegative(score))
        .setGuardTicksRemaining(nonNegative(guardTicksRemaining)).setAttackCooldownTicks(nonNegative(attackCooldownTicks))
        .setSkillOneCooldownTicks(nonNegative(skillOneCooldownTicks)).setSkillTwoCooldownTicks(nonNegative(skillTwoCooldownTicks))
        .setSkillOneUses(nonNegative(skillOneUses)).setSkillTwoUses(nonNegative(skillTwoUses)).setLane(lane.toProto())
        .setDangerEvades(nonNegative(dangerEvades)).setRequiresRearm(requiresRearm).setDowned(downed)
        .setRevivesPerformed(nonNegative(revivesPerformed)).setReceivedRevives(nonNegative(receivedRevives))
        .setLastDamageDealt(nonNegative(lastDamageDealt)).setLastDamageTaken(nonNegative(lastDamageTaken))
        .setAcceptedSequenceWatermark(acceptedSequenceWatermark)
        .setAcceptedEventTimestampWatermarkNs(acceptedEventTimestampWatermarkNs)
        .setLastAppliedSequence(lastAppliedSequence).build()

    private fun MonsterRaidPlayerProto.toDomain(id: PlayerId) = MonsterRaidPlayerState(
        playerId = id, playerClass = playerClass.toDomain(), health = uint(health), stamina = uint(stamina), score = uint(score),
        guardTicksRemaining = uint(guardTicksRemaining), attackCooldownTicks = uint(attackCooldownTicks),
        skillOneCooldownTicks = uint(skillOneCooldownTicks), skillTwoCooldownTicks = uint(skillTwoCooldownTicks),
        skillOneUses = uint(skillOneUses), skillTwoUses = uint(skillTwoUses), lane = lane.toDomain(),
        dangerEvades = uint(dangerEvades), requiresRearm = requiresRearm, downed = downed,
        revivesPerformed = uint(revivesPerformed), receivedRevives = uint(receivedRevives),
        lastDamageDealt = uint(lastDamageDealt), lastDamageTaken = uint(lastDamageTaken),
        acceptedSequenceWatermark = acceptedSequenceWatermark,
        acceptedEventTimestampWatermarkNs = acceptedEventTimestampWatermarkNs,
        lastAppliedSequence = lastAppliedSequence,
    )

    private fun nonNegative(value: Int): Int = value.also { require(it >= 0) }
    private fun uint(value: Int): Int = value.also { require(it >= 0) }
    private fun finite(value: Float): Float = value.also { require(it.isFinite()) }

    private fun GameId.toProto() = when (this) {
        GameId.FISHING -> GameIdProto.GAME_ID_FISHING
        GameId.BOXING -> GameIdProto.GAME_ID_BOXING
        GameId.MONSTER -> GameIdProto.GAME_ID_MONSTER
    }
    private fun GameIdProto.toDomain() = when (this) {
        GameIdProto.GAME_ID_FISHING -> GameId.FISHING
        GameIdProto.GAME_ID_BOXING -> GameId.BOXING
        GameIdProto.GAME_ID_MONSTER -> GameId.MONSTER
        else -> error("unspecified game")
    }
    private fun GameMode.toProto() = if (this == GameMode.SOLO) GameModeProto.GAME_MODE_SOLO else GameModeProto.GAME_MODE_DUAL
    private fun GameModeProto.toDomain() = when (this) {
        GameModeProto.GAME_MODE_SOLO -> GameMode.SOLO
        GameModeProto.GAME_MODE_DUAL -> GameMode.DUAL
        else -> error("unspecified mode")
    }
    private fun PlayerId.toProto() = when (this) {
        PlayerId.P1 -> PlayerIdProto.PLAYER_ID_P1
        PlayerId.P2 -> PlayerIdProto.PLAYER_ID_P2
        PlayerId.AI -> PlayerIdProto.PLAYER_ID_AI
    }
    private fun PlayerIdProto.toDomain() = when (this) {
        PlayerIdProto.PLAYER_ID_P1 -> PlayerId.P1
        PlayerIdProto.PLAYER_ID_P2 -> PlayerId.P2
        PlayerIdProto.PLAYER_ID_AI -> PlayerId.AI
        else -> error("unspecified player")
    }
    private fun SessionStatus.toProto() = when (this) {
        SessionStatus.COUNTDOWN -> SessionStatusProto.SESSION_STATUS_COUNTDOWN
        SessionStatus.RUNNING -> SessionStatusProto.SESSION_STATUS_RUNNING
        SessionStatus.PAUSED -> SessionStatusProto.SESSION_STATUS_PAUSED
        SessionStatus.COMPLETED -> SessionStatusProto.SESSION_STATUS_COMPLETED
        SessionStatus.ABORTED -> SessionStatusProto.SESSION_STATUS_ABORTED
    }
    private fun SessionStatusProto.toDomain() = when (this) {
        SessionStatusProto.SESSION_STATUS_COUNTDOWN -> SessionStatus.COUNTDOWN
        SessionStatusProto.SESSION_STATUS_RUNNING -> SessionStatus.RUNNING
        SessionStatusProto.SESSION_STATUS_PAUSED -> SessionStatus.PAUSED
        SessionStatusProto.SESSION_STATUS_COMPLETED -> SessionStatus.COMPLETED
        SessionStatusProto.SESSION_STATUS_ABORTED -> SessionStatus.ABORTED
        else -> error("unspecified status")
    }
    private fun PauseReason.toProto() = PauseReasonProto.valueOf("PAUSE_REASON_$name")
    private fun PauseReasonProto.toDomain() = name.removePrefix("PAUSE_REASON_").let { value ->
        PauseReason.entries.firstOrNull { it.name == value } ?: error("unspecified pause reason")
    }

    private fun FishingPhase.toProto() = FishingPhaseProto.valueOf("FISHING_PHASE_$name")
    private fun FishingPhaseProto.toDomain() = enumDomain("FISHING_PHASE_", name, FishingPhase.entries)
    private fun FishingFish.toProto() = FishingFishProto.valueOf("FISHING_FISH_$name")
    private fun FishingFishProto.toDomain() = enumDomain("FISHING_FISH_", name, FishingFish.entries)
    private fun FishingOutcome.toProto() = FishingOutcomeProto.valueOf("FISHING_OUTCOME_$name")
    private fun FishingOutcomeProto.toDomain() = enumDomain("FISHING_OUTCOME_", name, FishingOutcome.entries)
    private fun BoxingPhase.toProto() = BoxingPhaseProto.valueOf("BOXING_PHASE_$name")
    private fun BoxingPhaseProto.toDomain() = enumDomain("BOXING_PHASE_", name, BoxingPhase.entries)
    private fun BoxingAiAttack.toProto() = BoxingAiAttackProto.valueOf("BOXING_AI_ATTACK_$name")
    private fun BoxingAiAttackProto.toDomain() = enumDomain("BOXING_AI_ATTACK_", name, BoxingAiAttack.entries)
    private fun BoxingDodgeDirection.toProto() = BoxingDodgeDirectionProto.valueOf("BOXING_DODGE_DIRECTION_$name")
    private fun BoxingDodgeDirectionProto.toDomain() = enumDomain("BOXING_DODGE_DIRECTION_", name, BoxingDodgeDirection.entries)
    private fun BoxingOutcome.toProto() = BoxingOutcomeProto.valueOf("BOXING_OUTCOME_$name")
    private fun BoxingOutcomeProto.toDomain() = enumDomain("BOXING_OUTCOME_", name, BoxingOutcome.entries)
    private fun MonsterRaidStage.toProto() = MonsterRaidStageProto.valueOf("MONSTER_RAID_STAGE_$name")
    private fun MonsterRaidStageProto.toDomain() = enumDomain("MONSTER_RAID_STAGE_", name, MonsterRaidStage.entries)
    private fun MonsterRaidBossPhase.toProto() = MonsterRaidBossPhaseProto.valueOf("MONSTER_RAID_BOSS_${name}")
    private fun MonsterRaidBossPhaseProto.toDomain() = enumDomain("MONSTER_RAID_BOSS_", name, MonsterRaidBossPhase.entries)
    private fun MonsterBossAttack.toProto() = MonsterBossAttackProto.valueOf("MONSTER_BOSS_ATTACK_$name")
    private fun MonsterBossAttackProto.toDomain() = enumDomain("MONSTER_BOSS_ATTACK_", name, MonsterBossAttack.entries)
    private fun MonsterRaidClass.toProto() = MonsterRaidClassProto.valueOf("MONSTER_RAID_CLASS_$name")
    private fun MonsterRaidClassProto.toDomain() = enumDomain("MONSTER_RAID_CLASS_", name, MonsterRaidClass.entries)
    private fun MonsterRaidLane.toProto() = MonsterRaidLaneProto.valueOf("MONSTER_RAID_LANE_$name")
    private fun MonsterRaidLaneProto.toDomain() = enumDomain("MONSTER_RAID_LANE_", name, MonsterRaidLane.entries)
    private fun MonsterRaidEnemy.toProto() = MonsterRaidEnemyProto.valueOf("MONSTER_RAID_ENEMY_$name")
    private fun MonsterRaidEnemyProto.toDomain() = enumDomain("MONSTER_RAID_ENEMY_", name, MonsterRaidEnemy.entries)
    private fun MonsterRaidOutcome.toProto() = MonsterRaidOutcomeProto.valueOf("MONSTER_RAID_OUTCOME_$name")
    private fun MonsterRaidOutcomeProto.toDomain() = enumDomain("MONSTER_RAID_OUTCOME_", name, MonsterRaidOutcome.entries)

    private fun <T : Enum<T>> enumDomain(prefix: String, wireName: String, values: List<T>): T =
        values.firstOrNull { it.name == wireName.removePrefix(prefix) } ?: error("unspecified enum")
}
