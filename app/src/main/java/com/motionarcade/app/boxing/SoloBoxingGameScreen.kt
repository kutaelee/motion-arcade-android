package com.motionarcade.app.boxing

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motionarcade.app.R
import com.motionarcade.app.SharedScoreComboHud
import com.motionarcade.app.SoloCombatCameraPresentation
import com.motionarcade.app.SoloCombatInputController
import com.motionarcade.app.SafeResumeControl
import com.motionarcade.app.SoloCombatSafetyCard
import com.motionarcade.app.sharedHudFrameForTick
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.boxing.BoxingGameSession
import com.motionarcade.games.boxing.BoxingInputResult
import com.motionarcade.games.boxing.BoxingPhase
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** One-person camera-gated boxing round against the deterministic game AI. */
@Composable
internal fun SoloBoxingGameScreen(
    camera: SoloCombatCameraPresentation,
    onBack: () -> Unit,
) {
    require(camera.motionConfig.profile == com.motionarcade.vision.motion.DualPlayerCombatProfile.BOXING)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val combatFx = ImageBitmap.imageResource(R.drawable.boxing_combat_fx_v1)
    var roundSerial by rememberSaveable { mutableIntStateOf(0) }
    var playerSelection by rememberSaveable(roundSerial) { mutableIntStateOf(NO_BOXER_SELECTED) }
    var aiSelection by rememberSaveable(roundSerial) { mutableIntStateOf(NO_BOXER_SELECTED) }
    var matchupConfirmed by rememberSaveable(roundSerial) { mutableStateOf(false) }

    if (!matchupConfirmed) {
        BoxingCharacterSelectionPresentation(
            playerOne = playerSelection,
            playerTwo = aiSelection,
            onSelectPlayerOne = { playerSelection = it },
            onSelectPlayerTwo = { aiSelection = it },
            onContinue = {
                if (canConfirmBoxingMatchup(playerSelection, aiSelection)) matchupConfirmed = true
            },
            onBack = onBack,
            playerOneLabel = "YOU",
            playerTwoLabel = "AI",
        )
        return
    }

    val checkpointRestoreRejected = remember { AtomicBoolean(false) }
    val gameSaver = remember(camera.motionConfig.calibrationRevision) {
        Saver<BoxingGameSession, ByteArray>(
            save = { game ->
                runCatching {
                    BoxingCheckpointCodec.encode(game.checkpointForAppBackground())
                }.getOrNull()
            },
            restore = { encoded ->
                val restored = BoxingCheckpointCodec.decodeCompatible(
                    encoded = encoded,
                    calibrationRevision = camera.motionConfig.calibrationRevision,
                    mode = GameMode.SOLO,
                )?.let(BoxingGameSession::restore)
                if (restored == null) checkpointRestoreRejected.set(true)
                restored
            },
        )
    }
    val game = rememberSaveable(
        roundSerial,
        saver = gameSaver,
    ) {
        BoxingGameSession.start(
            sessionId = "boxing-solo-$roundSerial",
            seed = SOLO_BOXING_SEED + roundSerial,
            calibrationRevision = camera.motionConfig.calibrationRevision,
            mode = GameMode.SOLO,
        ).also { session -> session.pause(camera.currentSafetyPauseReason()) }
    }
    var snapshot by remember(game) { mutableStateOf(game.snapshot) }
    var combatArt by remember(game) { mutableStateOf(BoxingCombatArtState()) }
    LaunchedEffect(game) {
        if (checkpointRestoreRejected.compareAndSet(true, false)) {
            Toast.makeText(
                context,
                "복싱 규칙이 업데이트되어 저장된 라운드 대신 새 라운드를 시작합니다.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(game, camera.motionConfig.calibrationRevision) {
        if (camera.motionConfig.calibrationRevision > game.snapshot.calibrationRevision) {
            snapshot = game.recalibrate(camera.motionConfig.calibrationRevision)
            combatArt = BoxingCombatArtState()
        }
    }
    var motionRearmed by remember(game, camera.motionConfig) { mutableStateOf(false) }
    val motionInput = remember(game, camera.motionConfig) {
        SoloCombatInputController(
            sessionId = snapshot.sessionId,
            config = camera.motionConfig,
            acceptedSequenceWatermarks = mapOf(PlayerId.P1 to snapshot.acceptedSequenceWatermark),
            acceptedTimestampWatermarksNs = mapOf(
                PlayerId.P1 to snapshot.acceptedEventTimestampWatermarkNs,
            ),
            requireNeutralRearm = true,
            onRearmStateChanged = { motionRearmed = it },
        ) { event ->
            snapshot = when (val result = game.accept(event)) {
                is BoxingInputResult.Queued -> result.snapshot.also {
                    if (event.type == MotionType.PUNCH_JAB || event.type == MotionType.PUNCH_HOOK) {
                        combatArt = boxingArtForAcceptedPlayerPunch()
                    }
                }
                is BoxingInputResult.Ignored -> result.snapshot
                is BoxingInputResult.Rejected -> result.snapshot
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
            if (event == Lifecycle.Event.ON_STOP) snapshot = game.checkpointForAppBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(game, snapshot.status) {
        while (game.snapshot.status == SessionStatus.RUNNING) {
            delay(BoxingGameSession.FIXED_STEP_NS / 1_000_000L)
            camera.runWhenGameplayAllowed {
                val previous = snapshot
                snapshot = game.advanceTicks(1)
                combatArt = boxingArtForAdvance(previous, snapshot, combatArt)
            }
        }
    }

    fun submit(type: MotionType) {
        camera.runWhenGameplayAllowed {
            motionInput.submitTouch(PlayerId.P1, type, SystemClock.elapsedRealtimeNanos())
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF130E1A)) {
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
                OutlinedButton(onClick = onBack) { Text("게임 선택") }
                Text(
                    "BOXING · 1P + AI",
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            val playerAction = soloPlayerVisualAction(snapshot, combatArt)
            val aiAction = soloAiVisualAction(snapshot, combatArt)
            BoxingArenaPresentation(
                playerOneSelection = playerSelection,
                playerTwoSelection = aiSelection,
                playerOneAction = playerAction,
                playerTwoAction = aiAction,
                combatFx = combatFx,
            )
            SharedScoreComboHud(
                primaryText = "SCORE ${snapshot.score}",
                secondaryText = "DEFENSE ${snapshot.blockedAttackCount + snapshot.dodgedAttackCount}",
                frame = sharedHudFrameForTick(snapshot.simulationTick),
            )
            Text(
                "한 사람의 가드·회피·잽·훅을 인식해 결정적 AI 상대와 1라운드를 진행합니다.",
                color = Color(0xFFD8CDDF),
                style = MaterialTheme.typography.bodyLarge,
            )
            SoloCombatSafetyCard(camera)
            BoxingStatusCard(snapshot)

            when {
                snapshot.status == SessionStatus.RUNNING -> BoxingControls(
                    snapshot = snapshot,
                    onAction = { _, motion -> submit(motion) },
                    onPause = { snapshot = game.pause(PauseReason.USER) },
                )
                snapshot.phase == BoxingPhase.RESULT -> Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1D34)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            outcomeLabel(snapshot.outcome),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "점수 ${snapshot.score} · 가드 ${snapshot.blockedAttackCount} · 회피 ${snapshot.dodgedAttackCount}",
                            modifier = Modifier.padding(top = 8.dp),
                            color = Color(0xFFD8CDDF),
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            modifier = Modifier.padding(top = 16.dp),
                            onClick = { roundSerial += 1 },
                        ) { Text("다음 1인 라운드") }
                    }
                }
                snapshot.pauseReason == com.motionarcade.core.contract.PauseReason.USER -> Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = camera.gameplayInputAllowed,
                    onClick = {
                        camera.runWhenGameplayAllowed {
                            game.resume()
                            snapshot = game.snapshot
                        }
                    },
                ) { Text("1인 라운드 재개") }
                else -> SafeResumeControl(
                    recoveryKey = "${snapshot.sessionId}:${snapshot.calibrationRevision}",
                    ready = camera.gameplayInputAllowed && motionRearmed,
                    idleLabel = "1인 라운드 시작 / 재개",
                    onResume = {
                        camera.runWhenGameplayAllowed {
                            game.resume()
                            snapshot = game.snapshot
                        }
                    },
                )
            }
        }
    }
}

private const val SOLO_BOXING_SEED = 0x534F4C4F424F58L
