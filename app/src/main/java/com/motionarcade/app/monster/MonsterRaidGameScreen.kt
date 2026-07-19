package com.motionarcade.app.monster

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motionarcade.app.R
import com.motionarcade.app.DualPlayerCameraPresentation
import com.motionarcade.app.DualPlayerCombatInputController
import com.motionarcade.app.DualPlayerTrackingSafetyCard
import com.motionarcade.app.SoloCombatCameraPresentation
import com.motionarcade.app.SoloCombatInputController
import com.motionarcade.app.SafeResumeControl
import com.motionarcade.app.SoloCombatSafetyCard
import com.motionarcade.app.toGameSafetyPauseReason
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.monster.MonsterRaidBossPhase
import com.motionarcade.games.monster.MonsterRaidClass
import com.motionarcade.games.monster.MonsterRaidEnemy
import com.motionarcade.games.monster.MonsterRaidGameSession
import com.motionarcade.games.monster.MonsterRaidInputResult
import com.motionarcade.games.monster.MonsterRaidOutcome
import com.motionarcade.games.monster.MonsterRaidPlayerState
import com.motionarcade.games.monster.MonsterRaidSnapshot
import com.motionarcade.games.monster.MonsterRaidStage
import com.motionarcade.games.monster.MonsterRaidSkill
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import kotlinx.coroutines.delay

@Composable
internal fun MonsterRaidGameScreen(
    camera: DualPlayerCameraPresentation,
    onBack: () -> Unit,
) = MonsterRaidGameScreen(dualCamera = camera, soloCamera = null, onBack = onBack)

@Composable
internal fun MonsterRaidGameScreen(
    camera: SoloCombatCameraPresentation,
    onBack: () -> Unit,
) = MonsterRaidGameScreen(dualCamera = null, soloCamera = camera, onBack = onBack)

/** Playable Slice 6 raid: 1P+AI or 2P, waves, roles, revive, and synchronized ultimate. */
@Composable
private fun MonsterRaidGameScreen(
    dualCamera: DualPlayerCameraPresentation?,
    soloCamera: SoloCombatCameraPresentation?,
    onBack: () -> Unit,
) {
    require((dualCamera == null) != (soloCamera == null))
    val mode = if (soloCamera != null) GameMode.SOLO else GameMode.DUAL
    val config = dualCamera?.motionConfig ?: soloCamera?.motionConfig
    if (config == null || config.profile != DualPlayerCombatProfile.MONSTER) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0D1823)) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                Text("Monster motion configuration unavailable.", color = Color.White)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val arenaArt = ImageBitmap.imageResource(R.drawable.monster_arena_v1)
    val vanguardArt = ImageBitmap.imageResource(R.drawable.monster_vanguard_actions_v2)
    val rangerArt = ImageBitmap.imageResource(R.drawable.monster_ranger_mage_actions_v2)
    val commonEnemyArt = ImageBitmap.imageResource(R.drawable.monster_common_family_v1)
    val bossArt = ImageBitmap.imageResource(R.drawable.monster_boss_phases_v1)
    val actionFxArt = ImageBitmap.imageResource(R.drawable.monster_action_fx_v1)
    var encounterSerial by rememberSaveable { mutableIntStateOf(0) }
    val gameSaver = remember(config.calibrationRevision, mode) {
        Saver<MonsterRaidGameSession, ByteArray>(
            save = { game ->
                runCatching {
                    MonsterRaidCheckpointCodec.encode(game.checkpointForAppBackground())
                }.getOrNull()
            },
            restore = { encoded ->
                MonsterRaidCheckpointCodec.decodeCompatible(
                    encoded = encoded,
                    calibrationRevision = config.calibrationRevision,
                    mode = mode,
                )
                    ?.let(MonsterRaidGameSession::restore)
            },
        )
    }
    val game = rememberSaveable(encounterSerial, mode, saver = gameSaver) {
        MonsterRaidGameSession.start(
            sessionId = "monster-raid-${mode.name.lowercase()}-$encounterSerial",
            seed = 0x52414944424f5353L + encounterSerial,
            calibrationRevision = config.calibrationRevision,
            mode = mode,
        ).also {
            it.pause(
                dualCamera?.toGameSafetyPauseReason()
                    ?: requireNotNull(soloCamera).currentSafetyPauseReason(),
            )
        }
    }
    var snapshot by remember(game) { mutableStateOf(game.snapshot) }
    var p1Visual by remember(game) { mutableStateOf(MonsterHeroVisual()) }
    var p2Visual by remember(game) { mutableStateOf(MonsterHeroVisual()) }
    fun recordVisual(playerId: PlayerId, type: MotionType) {
        val playerClass = game.snapshot.players[playerId]?.playerClass ?: return
        val action = monsterHeroAction(playerClass, type)
        if (playerId == PlayerId.P1) {
            p1Visual = MonsterHeroVisual(action, p1Visual.serial + 1)
        } else if (playerId == PlayerId.P2) {
            p2Visual = MonsterHeroVisual(action, p2Visual.serial + 1)
        }
    }
    LaunchedEffect(p1Visual) {
        if (p1Visual.action != MonsterHeroAction.IDLE) {
            val pending = p1Visual
            delay(HERO_ACTION_VISIBLE_MS)
            if (p1Visual == pending) p1Visual = MonsterHeroVisual(serial = pending.serial)
        }
    }
    LaunchedEffect(p2Visual) {
        if (p2Visual.action != MonsterHeroAction.IDLE) {
            val pending = p2Visual
            delay(HERO_ACTION_VISIBLE_MS)
            if (p2Visual == pending) p2Visual = MonsterHeroVisual(serial = pending.serial)
        }
    }
    LaunchedEffect(game, config.calibrationRevision) {
        if (config.calibrationRevision > game.snapshot.calibrationRevision) {
            snapshot = game.recalibrate(config.calibrationRevision)
        }
    }
    var motionRearmed by remember(game, config, mode) { mutableStateOf(false) }
    val dualMotionInput = remember(game, config, mode) {
        if (mode != GameMode.DUAL) return@remember null
        val players = game.snapshot.players
        DualPlayerCombatInputController(
            sessionId = game.snapshot.sessionId,
            config = config,
            acceptedSequenceWatermarks = players
                .filterKeys { it != PlayerId.AI }
                .mapValues { it.value.acceptedSequenceWatermark },
            acceptedTimestampWatermarksNs = players
                .filterKeys { it != PlayerId.AI }
                .mapValues { it.value.acceptedEventTimestampWatermarkNs },
            requireNeutralRearm = true,
            onRearmStateChanged = { motionRearmed = it },
        ) { event ->
            snapshot = when (val result = game.accept(event)) {
                is MonsterRaidInputResult.Queued -> result.snapshot.also {
                    recordVisual(event.playerId, event.type)
                }
                is MonsterRaidInputResult.Ignored -> result.snapshot
                is MonsterRaidInputResult.Rejected -> result.snapshot
            }
        }
    }
    val soloMotionInput = remember(game, config, mode) {
        if (mode != GameMode.SOLO) return@remember null
        val player = game.snapshot.players.getValue(PlayerId.P1)
        SoloCombatInputController(
            sessionId = game.snapshot.sessionId,
            config = config,
            acceptedSequenceWatermarks = mapOf(PlayerId.P1 to player.acceptedSequenceWatermark),
            acceptedTimestampWatermarksNs = mapOf(
                PlayerId.P1 to player.acceptedEventTimestampWatermarkNs,
            ),
            requireNeutralRearm = true,
            onRearmStateChanged = { motionRearmed = it },
        ) { event ->
            snapshot = when (val result = game.accept(event)) {
                is MonsterRaidInputResult.Queued -> result.snapshot.also {
                    recordVisual(event.playerId, event.type)
                }
                is MonsterRaidInputResult.Ignored -> result.snapshot
                is MonsterRaidInputResult.Rejected -> result.snapshot
            }
        }
    }

    DisposableEffect(game, dualMotionInput, soloMotionInput) {
        val unregisterMotion = when {
            dualMotionInput != null -> requireNotNull(dualCamera)
                .registerCombatMotionConsumer(dualMotionInput)
            else -> requireNotNull(soloCamera)
                .registerCombatMotionConsumer(requireNotNull(soloMotionInput))
        }
        val unregisterSafety = (dualCamera?.registerSafetyStop { reason -> snapshot = game.pause(reason) }
            ?: requireNotNull(soloCamera).registerSafetyStop { reason -> snapshot = game.pause(reason) })
        onDispose {
            unregisterSafety()
            unregisterMotion()
        }
    }
    DisposableEffect(lifecycleOwner, game) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) snapshot = game.checkpointForAppBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(game, snapshot.status) {
        var lastNs = SystemClock.elapsedRealtimeNanos()
        var accumulatorNs = 0L
        while (game.snapshot.status == SessionStatus.RUNNING) {
            delay(TICK_POLL_MS)
            val nowNs = SystemClock.elapsedRealtimeNanos()
            accumulatorNs = (
                accumulatorNs + (nowNs - lastNs).coerceAtLeast(0).coerceAtMost(MAX_ACCUMULATOR_NS)
                ).coerceAtMost(MAX_ACCUMULATOR_NS)
            lastNs = nowNs
            val ticks = (accumulatorNs / MonsterRaidGameSession.FIXED_STEP_NS)
                .toInt()
                .coerceAtMost(MonsterRaidGameSession.MAX_CATCH_UP_TICKS)
            if (ticks == 0) continue
            val advanced = dualCamera?.runWhenGameplayAllowed { game.advanceTicks(ticks) }
                ?: soloCamera?.runWhenGameplayAllowed { game.advanceTicks(ticks) }
            if (advanced == null) {
                accumulatorNs = 0
            } else {
                snapshot = advanced
                accumulatorNs -= ticks * MonsterRaidGameSession.FIXED_STEP_NS
            }
        }
    }

    fun submit(playerId: PlayerId, type: MotionType) {
        val action = {
            val timestamp = SystemClock.elapsedRealtimeNanos()
            dualMotionInput?.submitTouch(playerId, type, timestamp)
                ?: soloMotionInput?.submitTouch(playerId, type, timestamp)
        }
        if (dualCamera != null) dualCamera.runWhenGameplayAllowed(action)
        else requireNotNull(soloCamera).runWhenGameplayAllowed(action)
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0D1823)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE60D1823))
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Text(
                    "MONSTER RAID / ${if (mode == GameMode.SOLO) "1P + AI" else "2P"}",
                    color = Color(0xFF80E4FF),
                    fontWeight = FontWeight.Black,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stageLabel(snapshot), color = Color.White, fontWeight = FontWeight.Black)
                    RaidBar("ENEMY", snapshot.enemyHealth, enemyMax(snapshot), Color(0xFFFF6978))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${snapshot.teamScore} PTS · ${snapshot.roundTicksRemaining / 10}s",
                        color = Color(0xFFC7D6E5),
                        fontWeight = FontWeight.Bold,
                    )
                    RaidBar(
                        "TEAM",
                        snapshot.teamCharge,
                        MonsterRaidGameSession.MAX_TEAM_CHARGE,
                        Color(0xFFFFD166),
                    )
                }
            }
            MonsterArenaPresentation(
                snapshot = snapshot,
                arena = arenaArt,
                vanguard = vanguardArt,
                ranger = rangerArt,
                commonEnemies = commonEnemyArt,
                bosses = bossArt,
                actionFx = actionFxArt,
                p1Visual = p1Visual.action,
                p2Visual = p2Visual.action,
            )
            snapshot.bossAttack?.let { attack ->
                Text(
                    "TELEGRAPH: ${attack.name} / ${snapshot.bossTelegraphTicksRemaining}",
                    color = Color(0xFFFFD166),
                    fontWeight = FontWeight.Bold,
                )
            }
            snapshot.pendingUltimatePlayer?.let { readyPlayer ->
                val counterpart = if (readyPlayer == PlayerId.P1) PlayerId.P2 else PlayerId.P1
                Text(
                    "$readyPlayer ready — $counterpart must trigger TEAM ULT within 600 ms",
                    color = Color(0xFFFFD166),
                    fontWeight = FontWeight.Black,
                )
            }

            if (dualCamera != null) DualPlayerTrackingSafetyCard(dualCamera)
            else SoloCombatSafetyCard(requireNotNull(soloCamera))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val teammateId = if (mode == GameMode.SOLO) PlayerId.AI else PlayerId.P2
                RaidPlayerCard(
                    modifier = Modifier.weight(1f),
                    player = snapshot.players.getValue(PlayerId.P1),
                    enabled = snapshot.status == SessionStatus.RUNNING,
                    teammateDown = snapshot.players.getValue(teammateId).downed,
                    interactive = true,
                    supportsTeamUltimate = mode == GameMode.DUAL,
                    onAction = { submit(PlayerId.P1, it) },
                )
                RaidPlayerCard(
                    modifier = Modifier.weight(1f),
                    player = snapshot.players.getValue(teammateId),
                    enabled = snapshot.status == SessionStatus.RUNNING && mode == GameMode.DUAL,
                    teammateDown = snapshot.players.getValue(PlayerId.P1).downed,
                    interactive = mode == GameMode.DUAL,
                    supportsTeamUltimate = mode == GameMode.DUAL,
                    onAction = { submit(teammateId, it) },
                )
            }

            when {
                snapshot.stage == MonsterRaidStage.RESULT -> {
                    Text(
                        outcomeLabel(snapshot.outcome),
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { encounterSerial += 1 },
                    ) { Text("New raid") }
                }
                snapshot.pauseReason == PauseReason.USER -> Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dualCamera?.gameplayInputAllowed
                        ?: requireNotNull(soloCamera).gameplayInputAllowed,
                    onClick = {
                        if (dualCamera != null) dualCamera.runWhenGameplayAllowed { game.resume() }
                        else requireNotNull(soloCamera).runWhenGameplayAllowed { game.resume() }
                        snapshot = game.snapshot
                    },
                ) { Text("Resume raid") }
                snapshot.paused -> SafeResumeControl(
                    recoveryKey = "${snapshot.sessionId}:${snapshot.calibrationRevision}",
                    ready = motionRearmed && (
                        dualCamera?.gameplayInputAllowed
                            ?: requireNotNull(soloCamera).gameplayInputAllowed
                    ),
                    idleLabel = "Start / resume safe raid",
                    onResume = {
                        if (dualCamera != null) dualCamera.runWhenGameplayAllowed { game.resume() }
                        else requireNotNull(soloCamera).runWhenGameplayAllowed { game.resume() }
                        snapshot = game.snapshot
                    },
                )
                else -> OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { snapshot = game.pause(PauseReason.USER) },
                ) { Text("Pause") }
            }
        }
    }
}

@Composable
private fun RaidPlayerCard(
    modifier: Modifier,
    player: MonsterRaidPlayerState,
    enabled: Boolean,
    teammateDown: Boolean,
    interactive: Boolean,
    supportsTeamUltimate: Boolean,
    onAction: (MotionType) -> Unit,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF193040))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "${player.playerId} / ${player.playerClass}",
                color = if (player.playerId == PlayerId.P1) Color(0xFF4DD3A4) else Color(0xFF80E4FF),
                fontWeight = FontWeight.Black,
            )
            RaidBar("HP", player.health, MonsterRaidGameSession.MAX_HEALTH, Color(0xFF4DD3A4))
            RaidBar("SP", player.stamina, MonsterRaidGameSession.MAX_STAMINA, Color(0xFF58B4FF))
            Text(
                "Score ${player.score} / lane ${player.lane} / evades ${player.dangerEvades}",
                color = Color.White,
            )
            if (player.downed) Text("DOWN / teammate must REVIVE", color = Color(0xFFFF6978))
            if (!interactive) {
                Text(
                    "AI assists after your core action; it cannot start or complete the raid for you.",
                    color = Color(0xFFC7D6E5),
                )
            } else {
                RaidActionRow(
                    RaidActionSpec("JAB", enabled && !player.downed, MotionType.PUNCH_JAB),
                    RaidActionSpec("STRONG", enabled && !player.downed, MotionType.PUNCH_HOOK),
                    onAction = onAction,
                )
                RaidActionRow(
                    RaidActionSpec(
                        skillLabel(player, first = true),
                        enabled && !player.downed && player.skillOneCooldownTicks == 0,
                        MotionType.MONSTER_SKILL_ONE,
                    ),
                    RaidActionSpec(
                        skillLabel(player, first = false),
                        enabled && !player.downed && player.skillTwoCooldownTicks == 0,
                        MotionType.MONSTER_SKILL_TWO,
                    ),
                    onAction = onAction,
                )
                RaidActionRow(
                    RaidActionSpec("GUARD", enabled && !player.downed, MotionType.MONSTER_BLOCK),
                    RaidActionSpec("MAGIC / raised V", enabled && !player.downed, MotionType.MONSTER_MAGIC_CHARGE),
                    onAction = onAction,
                )
                RaidActionRow(
                    RaidActionSpec("LEFT", enabled && !player.downed, MotionType.DODGE_LEFT),
                    RaidActionSpec("RIGHT", enabled && !player.downed, MotionType.DODGE_RIGHT),
                    onAction = onAction,
                )
                RaidActionRow(
                    RaidActionSpec("REVIVE 1.5s", enabled && !player.downed && teammateDown, MotionType.MONSTER_REVIVE),
                    RaidActionSpec("TEAM ULT", enabled && !player.downed && supportsTeamUltimate, MotionType.TEAM_ULTIMATE),
                    onAction = onAction,
                )
                if (!supportsTeamUltimate) {
                    Text(
                        "Team ultimate needs two human players; the AI cannot supply the second trigger.",
                        color = Color(0xFFFFD166),
                    )
                }
            }
        }
    }
}

private data class RaidActionSpec(val label: String, val enabled: Boolean, val type: MotionType)

@Composable
private fun RaidActionRow(
    first: RaidActionSpec,
    second: RaidActionSpec,
    onAction: (MotionType) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(first, second).forEach { spec ->
            Button(
                modifier = Modifier.weight(1f),
                enabled = spec.enabled,
                onClick = { onAction(spec.type) },
            ) { Text(spec.label, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun RaidBar(label: String, value: Int, max: Int, color: Color) {
    Column {
        Text("$label $value / $max", color = Color.White)
        LinearProgressIndicator(
            progress = { value.coerceIn(0, max) / max.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = Color(0xFF314958),
        )
    }
}

private fun stageLabel(snapshot: MonsterRaidSnapshot): String = when (snapshot.stage) {
    MonsterRaidStage.WAVE -> "WAVE ${snapshot.waveIndex + 1} / 3"
    MonsterRaidStage.ELITE -> "ELITE"
    MonsterRaidStage.BOSS -> "BOSS ${bossPhaseLabel(snapshot.bossPhase)}"
    MonsterRaidStage.RESULT -> "RESULT"
}

private fun bossPhaseLabel(phase: MonsterRaidBossPhase?): String = when (phase) {
    MonsterRaidBossPhase.PHASE_1 -> "PHASE 1"
    MonsterRaidBossPhase.PHASE_2 -> "PHASE 2 / ROLE SPLIT"
    MonsterRaidBossPhase.PHASE_3 -> "PHASE 3 / SYNC ULT"
    null -> ""
}

private fun enemyLabel(enemy: MonsterRaidEnemy): String = enemy.name.replace('_', ' ')
private fun enemyMax(snapshot: MonsterRaidSnapshot): Int = when (snapshot.stage) {
    MonsterRaidStage.WAVE -> MonsterRaidGameSession.WAVE_HEALTH
    MonsterRaidStage.ELITE -> MonsterRaidGameSession.ELITE_HEALTH
    MonsterRaidStage.BOSS, MonsterRaidStage.RESULT -> MonsterRaidGameSession.BOSS_HEALTH
}
private fun outcomeLabel(outcome: MonsterRaidOutcome?): String = when (outcome) {
    MonsterRaidOutcome.VICTORY -> "RAID CLEAR"
    MonsterRaidOutcome.DEFEAT -> "TEAM DOWN"
    MonsterRaidOutcome.TIMEOUT, null -> "TIME OUT"
}

private fun skillLabel(player: MonsterRaidPlayerState, first: Boolean): String {
    val skill = MonsterRaidGameSession.skillFor(player.playerClass, first)
    val cooldown = if (first) player.skillOneCooldownTicks else player.skillTwoCooldownTicks
    val label = when (skill) {
        MonsterRaidSkill.RUNE_SLAM -> "RUNE SLAM / left arm"
        MonsterRaidSkill.AEGIS_PULSE -> "AEGIS PULSE / right arm"
        MonsterRaidSkill.STAR_VOLLEY -> "STAR VOLLEY / left arm"
        MonsterRaidSkill.WEAKPOINT_MARK -> "WEAKPOINT / right arm"
    }
    return if (cooldown == 0) label else "$label (${cooldown / 10 + 1}s)"
}

@Composable
private fun MonsterArenaPresentation(
    snapshot: MonsterRaidSnapshot,
    arena: ImageBitmap,
    vanguard: ImageBitmap,
    ranger: ImageBitmap,
    commonEnemies: ImageBitmap,
    bosses: ImageBitmap,
    actionFx: ImageBitmap,
    p1Visual: MonsterHeroAction,
    p2Visual: MonsterHeroAction,
) {
    val enemyCrop = monsterEnemyCrop(snapshot.stage, snapshot.enemy, snapshot.bossPhase)
    val fxCrop = monsterFxCrop(snapshot)
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        val width = size.width.toInt()
        val height = size.height.toInt()
        drawImage(
            image = arena,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(arena.width, arena.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
        )
        val frame = (snapshot.simulationTick % MONSTER_HERO_FRAME_COUNT).toInt()
        val vanguardCrop = monsterHeroCrop(vanguard.width, vanguard.height, p1Visual, frame)
        val rangerCrop = monsterHeroCrop(ranger.width, ranger.height, p2Visual, frame)
        drawImage(
            image = vanguard,
            srcOffset = IntOffset(vanguardCrop.x, vanguardCrop.y),
            srcSize = IntSize(vanguardCrop.width, vanguardCrop.height),
            dstOffset = IntOffset((width * 0.03f).toInt(), (height * 0.30f).toInt()),
            dstSize = IntSize((width * 0.24f).toInt(), (height * 0.66f).toInt()),
        )
        drawImage(
            image = ranger,
            srcOffset = IntOffset(rangerCrop.x, rangerCrop.y),
            srcSize = IntSize(rangerCrop.width, rangerCrop.height),
            dstOffset = IntOffset((width * 0.21f).toInt(), (height * 0.34f).toInt()),
            dstSize = IntSize((width * 0.18f).toInt(), (height * 0.61f).toInt()),
        )
        val enemyImage = if (snapshot.stage == MonsterRaidStage.BOSS) bosses else commonEnemies
        drawImage(
            image = enemyImage,
            srcOffset = IntOffset(enemyCrop.x, enemyCrop.y),
            srcSize = IntSize(enemyCrop.width, enemyCrop.height),
            dstOffset = IntOffset((width * 0.56f).toInt(), (height * 0.27f).toInt()),
            dstSize = IntSize((width * 0.40f).toInt(), (height * 0.69f).toInt()),
        )
        fxCrop?.let { crop ->
            drawImage(
                image = actionFx,
                srcOffset = IntOffset(crop.x, crop.y),
                srcSize = IntSize(crop.width, crop.height),
                dstOffset = IntOffset((width * 0.36f).toInt(), (height * 0.29f).toInt()),
                dstSize = IntSize((width * 0.30f).toInt(), (height * 0.45f).toInt()),
            )
        }
    }
}

internal data class MonsterArtCrop(val x: Int, val y: Int, val width: Int, val height: Int)

internal enum class MonsterHeroAction { IDLE, PRIMARY, DEFENSE_OR_CHARGE, REVIVE }

private data class MonsterHeroVisual(
    val action: MonsterHeroAction = MonsterHeroAction.IDLE,
    val serial: Int = 0,
)

internal fun monsterHeroAction(playerClass: MonsterRaidClass, type: MotionType): MonsterHeroAction = when {
    type == MotionType.MONSTER_REVIVE -> MonsterHeroAction.REVIVE
    playerClass == MonsterRaidClass.VANGUARD && type == MotionType.MONSTER_BLOCK ->
        MonsterHeroAction.DEFENSE_OR_CHARGE
    playerClass == MonsterRaidClass.RANGER && type == MotionType.MONSTER_MAGIC_CHARGE ->
        MonsterHeroAction.DEFENSE_OR_CHARGE
    type in setOf(
        MotionType.PUNCH_JAB,
        MotionType.PUNCH_HOOK,
        MotionType.MONSTER_SKILL_ONE,
        MotionType.MONSTER_SKILL_TWO,
        MotionType.TEAM_ULTIMATE,
    ) -> MonsterHeroAction.PRIMARY
    else -> MonsterHeroAction.IDLE
}

internal fun monsterHeroCrop(
    imageWidth: Int,
    imageHeight: Int,
    action: MonsterHeroAction,
    frame: Int,
): MonsterArtCrop {
    require(imageWidth >= MONSTER_HERO_FRAME_COUNT && imageHeight >= MONSTER_HERO_FRAME_COUNT)
    require(frame in 0 until MONSTER_HERO_FRAME_COUNT)
    val cellWidth = imageWidth / MONSTER_HERO_FRAME_COUNT
    val cellHeight = imageHeight / MONSTER_HERO_FRAME_COUNT
    return MonsterArtCrop(
        x = frame * cellWidth,
        y = action.ordinal * cellHeight,
        width = cellWidth,
        height = cellHeight,
    )
}

internal fun monsterEnemyCrop(
    stage: MonsterRaidStage,
    enemy: MonsterRaidEnemy,
    bossPhase: MonsterRaidBossPhase?,
): MonsterArtCrop {
    if (stage == MonsterRaidStage.BOSS) {
        val phase = when (bossPhase) {
            MonsterRaidBossPhase.PHASE_2 -> 1
            MonsterRaidBossPhase.PHASE_3 -> 2
            MonsterRaidBossPhase.PHASE_1, null -> 0
        }
        return MonsterArtCrop(phase * 512, 256, 512, 512)
    }
    val member = when (enemy) {
        MonsterRaidEnemy.MOSS_CRAWLER -> 1
        MonsterRaidEnemy.SKY_WISP -> 2
        MonsterRaidEnemy.STONE_TUSK -> 0
        MonsterRaidEnemy.IRON_WARDEN, MonsterRaidEnemy.TEMPEST_TITAN -> 3
    }
    return MonsterArtCrop(member * 384, 256, 384, 512)
}

internal fun monsterFxCrop(snapshot: MonsterRaidSnapshot): MonsterArtCrop? = when {
    snapshot.players.values.any { it.downed } -> MonsterArtCrop(512, 683, 512, 341)
    snapshot.pendingUltimatePlayer != null ||
        snapshot.teamCharge == MonsterRaidGameSession.MAX_TEAM_CHARGE ->
        MonsterArtCrop(1024, 683, 512, 341)
    snapshot.players.values.any { it.guardTicksRemaining > 0 } -> MonsterArtCrop(0, 341, 512, 341)
    snapshot.players.values.any { it.lastDamageDealt > 0 } -> MonsterArtCrop(0, 0, 512, 341)
    snapshot.bossAttack != null -> MonsterArtCrop(1024, 683, 512, 341)
    else -> null
}

private const val TICK_POLL_MS = 16L
private const val HERO_ACTION_VISIBLE_MS = 650L
private const val MONSTER_HERO_FRAME_COUNT = 4
private const val MAX_ACCUMULATOR_NS =
    MonsterRaidGameSession.FIXED_STEP_NS * MonsterRaidGameSession.MAX_CATCH_UP_TICKS
