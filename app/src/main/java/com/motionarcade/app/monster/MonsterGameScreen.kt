package com.motionarcade.app.monster

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motionarcade.app.DualPlayerCameraPresentation
import com.motionarcade.app.DualPlayerCombatInputController
import com.motionarcade.app.DualPlayerTrackingSafetyCard
import com.motionarcade.app.toGameSafetyPauseReason
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.monster.MonsterBossAttack
import com.motionarcade.games.monster.MonsterGameSession
import com.motionarcade.games.monster.MonsterInputResult
import com.motionarcade.games.monster.MonsterOutcome
import com.motionarcade.games.monster.MonsterPhase
import com.motionarcade.games.monster.MonsterSnapshot
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import kotlinx.coroutines.delay

/** Two-player camera-gated boss rules slice. Touch remains an explicit QA input fallback. */
@Composable
internal fun MonsterGameScreen(
    camera: DualPlayerCameraPresentation,
    onBack: () -> Unit,
) {
    var encounterSerial by remember { mutableIntStateOf(0) }
    val game = remember(encounterSerial) {
        MonsterGameSession.start(
            sessionId = "monster-dual-$encounterSerial",
            seed = 0x4d4f4e53544552L + encounterSerial,
            calibrationRevision = 0,
            mode = GameMode.DUAL,
        ).also { session -> session.pause(camera.toGameSafetyPauseReason()) }
    }
    var snapshot by remember(game) { mutableStateOf(game.snapshot) }
    val motionConfig = remember(game) { DualPlayerCombatMotionConfigs.monster(snapshot.calibrationRevision) }
    val motionInput = remember(game, motionConfig) {
        DualPlayerCombatInputController(
            sessionId = snapshot.sessionId,
            config = motionConfig,
        ) { event ->
            snapshot = when (val result = game.accept(event)) {
                is MonsterInputResult.Queued -> result.snapshot
                is MonsterInputResult.Ignored -> result.snapshot
                is MonsterInputResult.Rejected -> result.snapshot
            }
        }
    }

    // The presentation is refreshed for every tracking frame; the underlying gate is stable.
    // Do not key simulation effects to presentation identity or a healthy camera can starve ticks.
    DisposableEffect(game, motionInput) {
        val unregisterMotion = camera.registerCombatMotionConsumer(motionInput)
        val unregister = camera.registerSafetyStop { reason ->
            snapshot = game.pause(reason)
        }
        onDispose {
            unregister()
            unregisterMotion()
        }
    }

    LaunchedEffect(game, snapshot.status) {
        while (game.snapshot.status == SessionStatus.RUNNING) {
            delay(MonsterGameSession.FIXED_STEP_NS / 1_000_000L)
            camera.runWhenGameplayAllowed {
                snapshot = game.advanceTicks(1)
            }
        }
    }

    fun submit(type: MotionType) {
        camera.runWhenGameplayAllowed {
            motionInput.submitTouch(PlayerId.P1, type, SystemClock.elapsedRealtimeNanos())
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xE60D1823),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) { Text("Back to Fishing") }
                Text(
                    text = "MONSTER · 2P CAMERA BOSS",
                    color = Color(0xFF80E4FF),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "2P camera motion candidate",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Role-bound P1/P2 pose signals now drive Block, Team Ultimate, Jab, and Hook through the same semantic event gate as touch fallback.",
                color = Color(0xFFC7D6E5),
                style = MaterialTheme.typography.bodyLarge,
            )

            DualPlayerTrackingSafetyCard(camera)
            MonsterStatusCard(snapshot)

            when {
                snapshot.status == SessionStatus.RUNNING -> {
                    MonsterControls(
                        snapshot = snapshot,
                        onAction = ::submit,
                        onPause = { snapshot = game.pause(PauseReason.USER) },
                    )
                }

                snapshot.phase == MonsterPhase.RESULT -> {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF193040))) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = outcomeLabel(snapshot.outcome),
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = "Score ${snapshot.score} · blocks ${snapshot.blockedAttackCount} · barrier counters ${snapshot.counteredBarrierCount}",
                                color = Color(0xFFC7D6E5),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            if (snapshot.players.isNotEmpty()) {
                                val p1 = requireNotNull(snapshot.players[PlayerId.P1])
                                val p2 = requireNotNull(snapshot.players[PlayerId.P2])
                                Text(
                                    modifier = Modifier.padding(top = 4.dp),
                                    text = "P1 score ${p1.score} · P2 score ${p2.score}",
                                    color = Color(0xFF80E4FF),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Button(
                                modifier = Modifier.padding(top = 16.dp),
                                onClick = { encounterSerial += 1 },
                            ) { Text("New encounter") }
                        }
                    }
                }

                else -> Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = camera.gameplayInputAllowed,
                    onClick = {
                        camera.runWhenGameplayAllowed { game.resume() }
                        snapshot = game.snapshot
                    },
                ) { Text("Resume encounter") }
            }

            Text(
                text = "Camera motion routing is implemented with candidate thresholds. Human device QA is still required; buttons remain a P1 touch fallback.",
                color = Color(0xFF9FB5C7),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonsterStatusCard(snapshot: MonsterSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF132838))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (snapshot.players.isNotEmpty()) {
                DualMonsterPlayerStatus(
                    label = "P1",
                    player = requireNotNull(snapshot.players[PlayerId.P1]),
                    accent = Color(0xFF4DD3A4),
                )
                DualMonsterPlayerStatus(
                    label = "P2",
                    player = requireNotNull(snapshot.players[PlayerId.P2]),
                    accent = Color(0xFF80E4FF),
                )
                Text(
                    text = "Compatibility bars below mirror P1 only; boss health and partner charge are shared.",
                    color = Color(0xFF9FB5C7),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            MonsterBar("Boss", snapshot.bossHealth, MonsterGameSession.BOSS_MAX_HEALTH, Color(0xFFFF6978))
            MonsterBar("Player", snapshot.playerHealth, MonsterGameSession.MAX_HEALTH, Color(0xFF4DD3A4))
            MonsterBar("Stamina", snapshot.playerStamina, MonsterGameSession.MAX_STAMINA, Color(0xFF58B4FF))
            MonsterBar("Partner charge", snapshot.partnerCharge, MonsterGameSession.MAX_PARTNER_CHARGE, Color(0xFFFFD166))
            Text(
                text = "Round ${snapshot.roundTicksRemaining / 10}.${snapshot.roundTicksRemaining % 10}s · score ${snapshot.score}",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val telegraph = snapshot.bossTelegraph?.let(::telegraphLabel) ?: "Boss is repositioning"
            Text(
                modifier = Modifier.semantics { contentDescription = "Boss telegraph: $telegraph" },
                text = "Boss telegraph: $telegraph",
                color = if (snapshot.bossTelegraph == null) Color(0xFFC7D6E5) else Color(0xFF80E4FF),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (snapshot.requiresRearm) "Use Block to re-arm the next strike." else "Strike, Block, or hold Team Ultimate for a charged Barrier counter.",
                color = Color(0xFFC7D6E5),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (snapshot.players.isNotEmpty()) {
                val waiting = snapshot.pendingTeamUltimatePlayer
                Text(
                    text = if (waiting == null) {
                        "Team Ultimate needs P1 and P2 within 600ms when charge is full."
                    } else {
                        "$waiting is armed for Team Ultimate; waiting for the other player within 600ms."
                    },
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DualMonsterPlayerStatus(
    label: String,
    player: com.motionarcade.games.monster.MonsterPlayerState,
    accent: Color,
) {
    Text(
        text = "$label · score ${player.score} · blocks ${player.blockedAttackCount}",
        color = accent,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
    MonsterBar("$label health", player.health, MonsterGameSession.MAX_HEALTH, accent)
    MonsterBar("$label stamina", player.stamina, MonsterGameSession.MAX_STAMINA, Color(0xFF58B4FF))
    if (player.requiresRearm) {
        Text(
            text = "$label must Block to re-arm the next strike.",
            color = Color(0xFFC7D6E5),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MonsterBar(label: String, value: Int, max: Int, color: Color) {
    Column {
        Text("$label $value / $max", color = Color.White, style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics { contentDescription = "$label $value of $max" },
            progress = { value.coerceIn(0, max) / max.toFloat() },
            color = color,
            trackColor = Color(0xFF314958),
        )
    }
}

@Composable
private fun MonsterControls(
    snapshot: MonsterSnapshot,
    onAction: (MotionType) -> Unit,
    onPause: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            MonsterActionButton("Block", Modifier.weight(1f), Color(0xFF5964F2)) {
                onAction(MotionType.MONSTER_BLOCK)
            }
            Spacer(Modifier.width(10.dp))
            MonsterActionButton("Team Ultimate", Modifier.weight(1f), Color(0xFFFFA62B)) {
                onAction(MotionType.TEAM_ULTIMATE)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            MonsterActionButton("Jab", Modifier.weight(1f), Color(0xFFE45F7A)) {
                onAction(MotionType.PUNCH_JAB)
            }
            Spacer(Modifier.width(10.dp))
            MonsterActionButton("Hook", Modifier.weight(1f), Color(0xFFFF7F51)) {
                onAction(MotionType.PUNCH_HOOK)
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onPause) {
            Text(if (snapshot.paused) "Resume" else "Pause")
        }
    }
}

@Composable
private fun MonsterActionButton(label: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Button(
        modifier = modifier.height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        onClick = onClick,
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

private fun telegraphLabel(attack: MonsterBossAttack): String = when (attack) {
    MonsterBossAttack.CLAW -> "Claw incoming — Block"
    MonsterBossAttack.ORB -> "Orb incoming — Block"
    MonsterBossAttack.BARRIER -> "Barrier incoming — charged Team Ultimate"
}

private fun outcomeLabel(outcome: MonsterOutcome?): String = when (outcome) {
    MonsterOutcome.VICTORY -> "Boss defeated"
    MonsterOutcome.DEFEAT -> "Encounter lost"
    MonsterOutcome.TIMEOUT, null -> "Time expired"
}
