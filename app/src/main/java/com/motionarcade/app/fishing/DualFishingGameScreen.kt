package com.motionarcade.app.fishing

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motionarcade.app.DualPlayerCameraPresentation
import com.motionarcade.app.DualPlayerCombatInputController
import com.motionarcade.app.SafeResumeControl
import com.motionarcade.app.DualPlayerTrackingSafetyCard
import com.motionarcade.app.toGameSafetyPauseReason
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.DualFishingGameSession
import com.motionarcade.games.fishing.DualFishingInputResult
import com.motionarcade.games.fishing.DualFishingSnapshot
import com.motionarcade.games.fishing.FishingFish
import com.motionarcade.games.fishing.FishingOutcome
import com.motionarcade.games.fishing.FishingPhase
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import kotlinx.coroutines.delay

/** Playable two-player cooperative catch. Touch buttons remain a QA fallback for the same event path. */
@Composable
internal fun DualFishingGameScreen(
    camera: DualPlayerCameraPresentation,
    onBack: () -> Unit,
) {
    val motionConfig = camera.motionConfig
    if (motionConfig == null || motionConfig.profile != DualPlayerCombatProfile.FISHING) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xEE071723)) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("2인 협동 낚시 모션 설정을 불러오지 못했습니다.", color = Color.White)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var raceSerial by rememberSaveable { mutableIntStateOf(0) }
    val gameSaver = remember(motionConfig.calibrationRevision) {
        Saver<DualFishingGameSession, ByteArray>(
            save = { session ->
                runCatching {
                    DualFishingCheckpointCodec.encode(session.checkpointForAppBackground())
                }.getOrNull()
            },
            restore = { encoded ->
                DualFishingCheckpointCodec.decode(encoded)
                    ?.takeIf { checkpoint ->
                        checkpoint.calibrationRevision == motionConfig.calibrationRevision
                    }
                    ?.let(DualFishingGameSession::restore)
            },
        )
    }
    val game = rememberSaveable(raceSerial, saver = gameSaver) {
        DualFishingGameSession.start(
            sessionId = "fishing-dual-$raceSerial",
            seed = 0x46495348494e47L + raceSerial,
            calibrationRevision = motionConfig.calibrationRevision,
        ).also { session -> session.pause(camera.toGameSafetyPauseReason()) }
    }
    var snapshot by remember(game) { mutableStateOf(game.snapshot) }
    LaunchedEffect(game, motionConfig.calibrationRevision) {
        if (motionConfig.calibrationRevision > game.snapshot.calibrationRevision) {
            snapshot = game.recalibrate(motionConfig.calibrationRevision)
        }
    }
    var motionRearmed by remember(game, motionConfig) { mutableStateOf(false) }
    val motionInput = remember(game, motionConfig) {
        val restoredPlayers = game.snapshot.players
        DualPlayerCombatInputController(
            sessionId = snapshot.sessionId,
            config = motionConfig,
            acceptedSequenceWatermarks = restoredPlayers.mapValues { (_, player) ->
                player.acceptedSequenceWatermark
            },
            acceptedTimestampWatermarksNs = restoredPlayers.mapValues { (_, player) ->
                player.acceptedEventTimestampWatermarkNs
            },
            requireNeutralRearm = true,
            onRearmStateChanged = { motionRearmed = it },
        ) { event ->
            if (event.type != MotionType.FISH_READY) {
                snapshot = when (val result = game.accept(event)) {
                    is DualFishingInputResult.Queued -> result.snapshot
                    is DualFishingInputResult.Ignored -> result.snapshot
                    is DualFishingInputResult.Rejected -> result.snapshot
                }
            }
        }
    }

    DisposableEffect(game, motionInput) {
        val unregisterMotion = camera.registerCombatMotionConsumer(motionInput)
        val unregisterSafety = camera.registerSafetyStop { reason -> snapshot = game.pause(reason) }
        onDispose {
            unregisterSafety()
            unregisterMotion()
        }
    }

    DisposableEffect(lifecycleOwner, game) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                snapshot = game.checkpointForAppBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(game, snapshot.status) {
        var lastTickClockNs = SystemClock.elapsedRealtimeNanos()
        var accumulatorNs = 0L
        while (game.snapshot.status == SessionStatus.RUNNING) {
            delay(TICK_POLL_MS)
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val elapsedNs = (nowNs - lastTickClockNs).coerceAtLeast(0L).coerceAtMost(MAX_ACCUMULATOR_NS)
            lastTickClockNs = nowNs
            accumulatorNs = (accumulatorNs + elapsedNs).coerceAtMost(MAX_ACCUMULATOR_NS)
            val ticks = (accumulatorNs / DualFishingGameSession.FIXED_STEP_NS)
                .toInt()
                .coerceAtMost(DualFishingGameSession.MAX_CATCH_UP_TICKS)
            if (ticks == 0) continue
            val advanced = camera.runWhenGameplayAllowed { game.advanceTicks(ticks) }
            if (advanced == null) {
                accumulatorNs = 0L
            } else {
                snapshot = advanced
                accumulatorNs -= ticks * DualFishingGameSession.FIXED_STEP_NS
            }
        }
    }

    fun submit(playerId: PlayerId, type: MotionType) {
        camera.runWhenGameplayAllowed {
            motionInput.submitTouch(playerId, type, SystemClock.elapsedRealtimeNanos())
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xB8072434)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xA6072434))
                .safeDrawingPadding()
                .padding(top = 56.dp)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) { Text("게임 선택") }
                Text(
                    "FISHING · 2P CO-OP",
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "낚싯대: 캐스팅·챔질·릴  |  보조: 장력·뜰채",
                color = Color(0xFFD8EEF2),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!camera.gameplayInputAllowed) DualPlayerTrackingSafetyCard(camera)
            DualFishingCoopCard(snapshot, ::submit)

            when {
                snapshot.status == SessionStatus.COMPLETED -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { snapshot = game.startNextCatch(switchRoles = false) },
                    ) { Text("같은 역할로 다음 포획") }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { snapshot = game.startNextCatch(switchRoles = true) },
                    ) { Text("역할 교대") }
                }
                snapshot.pauseReason == PauseReason.USER -> Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = camera.gameplayInputAllowed,
                    onClick = {
                        camera.runWhenGameplayAllowed { game.resume() }
                        snapshot = game.snapshot
                    },
                ) { Text("협동 포획 계속") }
                snapshot.paused -> SafeResumeControl(
                    recoveryKey = "${snapshot.sessionId}:${snapshot.calibrationRevision}",
                    ready = camera.gameplayInputAllowed && motionRearmed,
                    idleLabel = "안전 확인 후 협동 시작",
                    onResume = {
                        camera.runWhenGameplayAllowed { game.resume() }
                        snapshot = game.snapshot
                    },
                )
                else -> OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { snapshot = game.pause(PauseReason.USER) },
                ) { Text("Pause") }
            }

            Text(
                "Camera motion and the P1/P2 QA buttons below enter the same semantic event queue.",
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFA9C9D0),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DualFishingCoopCard(
    snapshot: DualFishingSnapshot,
    onAction: (PlayerId, MotionType) -> Unit,
) {
    val shared = snapshot.players.getValue(snapshot.rodPlayerId)
    val supportId = if (snapshot.rodPlayerId == PlayerId.P1) PlayerId.P2 else PlayerId.P1
    val action = shared.phase.suggestedDualAction()
    val actor = when (shared.phase) {
        FishingPhase.TENSION, FishingPhase.NETTING -> supportId
        FishingPhase.READY, FishingPhase.HOOK_WINDOW, FishingPhase.REELING -> snapshot.rodPlayerId
        FishingPhase.BITE_WAIT, FishingPhase.RESULT -> null
    }
    val progress = when (shared.phase) {
        FishingPhase.READY -> 0f
        FishingPhase.BITE_WAIT -> .15f
        FishingPhase.HOOK_WINDOW -> .25f
        FishingPhase.REELING, FishingPhase.TENSION -> (.25f + shared.reelCycles * .12f).coerceAtMost(.85f)
        FishingPhase.NETTING -> .92f
        FishingPhase.RESULT -> 1f
    }
    val model = FishingHudModel(
        phaseLabel = phaseLabel(shared.phase),
        instruction = actor?.let { "${it.name} 차례 · ${actionLabel(shared.phase, shared.outcome)}" }
            ?: if (shared.phase == FishingPhase.RESULT) "포획 결과" else "입질을 기다리세요",
        scoreLabel = "P1 ${snapshot.players.getValue(PlayerId.P1).score} · P2 ${snapshot.players.getValue(PlayerId.P2).score} · TEAM ${snapshot.teamScore}",
        progressLabel = "포획 ${(progress * 100).toInt()}%",
        progressDescription = "공유 포획 진행도 ${(progress * 100).toInt()}%",
        progress = progress,
        tensionLabel = "장력 ${shared.tension}%",
        tensionDescription = "공유 낚싯줄 장력 ${shared.tension}%",
        tension = shared.tension / DualFishingGameSession.MAX_TENSION.toFloat(),
        trackingStatusLabel = "P1 + P2",
        actionLabel = actionLabel(shared.phase, shared.outcome),
        actionEnabled = actor != null && snapshot.status == SessionStatus.RUNNING,
        pauseLabel = "일시정지",
        pauseEnabled = true,
        artFrame = when (shared.phase) {
            FishingPhase.READY -> 0
            FishingPhase.BITE_WAIT, FishingPhase.HOOK_WINDOW -> 1
            FishingPhase.REELING, FishingPhase.TENSION -> 2
            FishingPhase.NETTING, FishingPhase.RESULT -> 3
        },
        showCatchMeasurement = shared.phase == FishingPhase.NETTING || shared.phase == FishingPhase.RESULT,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE12384A)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("낚싯대 ${snapshot.rodPlayerId.name}", color = Color(0xFF4DD3A4), fontWeight = FontWeight.Black)
                Text("장력·뜰채 ${supportId.name}", color = Color(0xFF58B4FF), fontWeight = FontWeight.Black)
            }
            FishingPlayfield(
                model = model,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .border(2.dp, Color(0x6658D7E8), RoundedCornerShape(20.dp)),
            )
            Text(model.instruction, color = Color.White, fontWeight = FontWeight.Bold)
            Text(fishLabel(shared.fish), color = Color(0xFFFFD166), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { shared.tension / DualFishingGameSession.MAX_TENSION.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFF7B68),
                trackColor = Color(0xFF315767),
            )
            Text(
                "P1 ${snapshot.players.getValue(PlayerId.P1).score} · P2 ${snapshot.players.getValue(PlayerId.P2).score} · TEAM ${snapshot.teamScore} · COMBO ${snapshot.combo}",
                color = Color(0xFFA9C9D0),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PlayerId.P1, PlayerId.P2).forEach { playerId ->
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = actor == playerId && action != null && snapshot.status == SessionStatus.RUNNING,
                        onClick = { action?.let { onAction(playerId, it) } },
                    ) { Text("${playerId.name} · ${if (actor == playerId) actionLabel(shared.phase, shared.outcome) else "대기"}") }
                }
            }
        }
    }
}

private fun FishingPhase.suggestedDualAction(): MotionType? = when (this) {
    FishingPhase.READY -> MotionType.FISH_CAST
    FishingPhase.BITE_WAIT -> null
    FishingPhase.HOOK_WINDOW -> MotionType.FISH_HOOK
    FishingPhase.REELING -> MotionType.FISH_REEL_CYCLE
    FishingPhase.TENSION -> MotionType.FISH_TENSION_LEFT
    FishingPhase.NETTING -> MotionType.FISH_NET
    FishingPhase.RESULT -> null
}

private fun phaseLabel(phase: FishingPhase): String = when (phase) {
    FishingPhase.READY -> "Ready to cast"
    FishingPhase.BITE_WAIT -> "Waiting for bite"
    FishingPhase.HOOK_WINDOW -> "Bite! Hook now"
    FishingPhase.REELING -> "Reel steadily"
    FishingPhase.TENSION -> "Lean to release tension"
    FishingPhase.NETTING -> "Net the fish"
    FishingPhase.RESULT -> "Lane complete"
}

private fun actionLabel(phase: FishingPhase, outcome: FishingOutcome?): String = when (phase) {
    FishingPhase.READY -> "CAST"
    FishingPhase.BITE_WAIT -> "WAIT"
    FishingPhase.HOOK_WINDOW -> "HOOK"
    FishingPhase.REELING -> "REEL"
    FishingPhase.TENSION -> "RELIEVE"
    FishingPhase.NETTING -> "NET"
    FishingPhase.RESULT -> if (outcome == FishingOutcome.CAUGHT) "CAUGHT" else "ESCAPED"
}

private fun fishLabel(fish: FishingFish?): String = when (fish) {
    FishingFish.SUNFIN -> "Sunfin"
    FishingFish.MOON_CARP -> "Moon Carp · rare"
    null -> "No fish selected"
}

private const val TICK_POLL_MS = 8L
private const val MAX_ACCUMULATOR_NS =
    DualFishingGameSession.FIXED_STEP_NS * DualFishingGameSession.MAX_CATCH_UP_TICKS
