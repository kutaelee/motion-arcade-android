package com.motionarcade.app.boxing

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motionarcade.app.DualPlayerCameraPresentation
import com.motionarcade.app.DualPlayerCombatInputController
import com.motionarcade.app.SafeResumeControl
import com.motionarcade.app.DualPlayerTrackingSafetyCard
import com.motionarcade.app.R
import com.motionarcade.app.toGameSafetyPauseReason
import com.motionarcade.core.contract.GameMode
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.boxing.BoxingAiAttack
import com.motionarcade.games.boxing.BoxingGameSession
import com.motionarcade.games.boxing.BoxingOutcome
import com.motionarcade.games.boxing.BoxingSnapshot
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** Two-player camera-gated Boxing rules slice. Touch remains an explicit QA input fallback. */
@Composable
internal fun BoxingGameScreen(
    camera: DualPlayerCameraPresentation,
    onBack: () -> Unit,
) {
    val config = camera.motionConfig
    if (config == null || config.profile != DualPlayerCombatProfile.BOXING) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF130E1A)) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                Text("Boxing motion configuration unavailable.", color = Color.White)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }
    val boxingConfig = requireNotNull(config)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val combatFx = ImageBitmap.imageResource(R.drawable.boxing_combat_fx_v1)
    var roundSerial by rememberSaveable { mutableIntStateOf(0) }
    var playerOneSelection by rememberSaveable(roundSerial) { mutableIntStateOf(NO_BOXER_SELECTED) }
    var playerTwoSelection by rememberSaveable(roundSerial) { mutableIntStateOf(NO_BOXER_SELECTED) }
    var matchupConfirmed by rememberSaveable(roundSerial) { mutableStateOf(false) }
    if (!matchupConfirmed) {
        BoxingCharacterSelectionPresentation(
            playerOne = playerOneSelection,
            playerTwo = playerTwoSelection,
            onSelectPlayerOne = { playerOneSelection = it },
            onSelectPlayerTwo = { playerTwoSelection = it },
            onContinue = {
                if (canConfirmBoxingMatchup(playerOneSelection, playerTwoSelection)) {
                    matchupConfirmed = true
                }
            },
            onBack = onBack,
        )
        return
    }
    val checkpointRestoreRejected = remember { AtomicBoolean(false) }
    val gameSaver = remember(boxingConfig.calibrationRevision) {
        Saver<BoxingGameSession, ByteArray>(
            save = { game ->
                runCatching {
                    BoxingCheckpointCodec.encode(game.checkpointForAppBackground())
                }.getOrNull()
            },
            restore = { encoded ->
                val restored = BoxingCheckpointCodec.decodeCompatible(
                    encoded = encoded,
                    calibrationRevision = boxingConfig.calibrationRevision,
                    mode = GameMode.DUAL,
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
            sessionId = "boxing-dual-$roundSerial",
            seed = 0x4d4f54494f4eL + roundSerial,
            calibrationRevision = boxingConfig.calibrationRevision,
            mode = GameMode.DUAL,
        ).also { session -> session.pause(camera.toGameSafetyPauseReason()) }
    }
    var snapshot by remember(game) { mutableStateOf(game.snapshot) }
    var playerOneVisualAction by remember(game) { mutableStateOf(BoxingVisualAction.NEUTRAL) }
    var playerTwoVisualAction by remember(game) { mutableStateOf(BoxingVisualAction.NEUTRAL) }
    LaunchedEffect(game) {
        if (checkpointRestoreRejected.compareAndSet(true, false)) {
            Toast.makeText(
                context,
                "복싱 규칙이 업데이트되어 저장된 라운드 대신 새 라운드를 시작합니다.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(game, boxingConfig.calibrationRevision) {
        if (boxingConfig.calibrationRevision > game.snapshot.calibrationRevision) {
            snapshot = game.recalibrate(boxingConfig.calibrationRevision)
            playerOneVisualAction = BoxingVisualAction.NEUTRAL
            playerTwoVisualAction = BoxingVisualAction.NEUTRAL
        }
    }
    val motionConfig = boxingConfig
    var motionRearmed by remember(game, motionConfig) { mutableStateOf(false) }
    val motionInput = remember(game, motionConfig) {
        DualPlayerCombatInputController(
            sessionId = snapshot.sessionId,
            config = motionConfig,
            acceptedSequenceWatermarks = snapshot.players.mapValues { (_, player) ->
                player.acceptedSequenceWatermark
            },
            acceptedTimestampWatermarksNs = snapshot.players.mapValues { (_, player) ->
                player.acceptedEventTimestampWatermarkNs
            },
            requireNeutralRearm = true,
            onRearmStateChanged = { motionRearmed = it },
        ) { event ->
            snapshot = when (val result = game.accept(event)) {
                is com.motionarcade.games.boxing.BoxingInputResult.Queued -> result.snapshot.also {
                    val visual = boxingVisualActionFor(event.type)
                    when (event.playerId) {
                        PlayerId.P1 -> playerOneVisualAction = visual
                        PlayerId.P2 -> playerTwoVisualAction = visual
                        PlayerId.AI -> Unit
                    }
                }
                is com.motionarcade.games.boxing.BoxingInputResult.Ignored -> result.snapshot
                is com.motionarcade.games.boxing.BoxingInputResult.Rejected -> result.snapshot
            }
        }
    }

    LaunchedEffect(playerOneVisualAction, playerTwoVisualAction) {
        if (
            playerOneVisualAction != BoxingVisualAction.NEUTRAL ||
            playerTwoVisualAction != BoxingVisualAction.NEUTRAL
        ) {
            delay(BOXING_VISUAL_FEEDBACK_MS)
            playerOneVisualAction = BoxingVisualAction.NEUTRAL
            playerTwoVisualAction = BoxingVisualAction.NEUTRAL
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
                val next = game.advanceTicks(1)
                val previousP1 = previous.players[PlayerId.P1]
                val previousP2 = previous.players[PlayerId.P2]
                val nextP1 = next.players[PlayerId.P1]
                val nextP2 = next.players[PlayerId.P2]
                if (previousP1 != null && nextP1 != null) {
                    playerOneVisualAction = defensiveVisualTransition(previousP1, nextP1)
                        ?: playerOneVisualAction
                }
                if (previousP2 != null && nextP2 != null) {
                    playerTwoVisualAction = defensiveVisualTransition(previousP2, nextP2)
                        ?: playerTwoVisualAction
                }
                snapshot = next
            }
        }
    }

    fun submit(playerId: PlayerId, type: MotionType) {
        camera.runWhenGameplayAllowed {
            motionInput.submitTouch(playerId, type, SystemClock.elapsedRealtimeNanos())
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xE6130E1A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(top = 56.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("게임 선택으로")
                }
                Text(
                    text = "BOXING · P1 VS P2",
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            BoxingArenaPresentation(
                playerOneSelection = playerOneSelection,
                playerTwoSelection = playerTwoSelection,
                playerOneAction = playerOneVisualAction,
                playerTwoAction = playerTwoVisualAction,
                combatFx = combatFx,
            )
            Text(
                text = "서로를 향해 치지 말고 각자 카메라 방향으로 섀도 복싱하세요.",
                color = Color(0xFFD8CDDF),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "레인 · P1 왼쪽 / P2 오른쪽 · ${if (camera.gameplayInputAllowed) "추적 준비" else "안전 정지"}",
                color = if (camera.gameplayInputAllowed) Color(0xFF8FF0C8) else Color(0xFFFFD166),
                style = MaterialTheme.typography.labelMedium,
            )

            if (!camera.gameplayInputAllowed) DualPlayerTrackingSafetyCard(camera)
            BoxingStatusCard(snapshot)

            if (snapshot.status == SessionStatus.RUNNING) {
                BoxingControls(
                    snapshot = snapshot,
                    onAction = ::submit,
                    onPause = {
                        snapshot = if (snapshot.paused) {
                            camera.runWhenGameplayAllowed { game.resume() }
                            game.snapshot
                        } else {
                            game.pause(com.motionarcade.core.contract.PauseReason.USER)
                        }
                    },
                )
            } else if (snapshot.phase == com.motionarcade.games.boxing.BoxingPhase.RESULT) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1D34)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = dualOutcomeLabel(snapshot.outcome),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            modifier = Modifier.padding(top = 8.dp),
                            text = "점수 ${snapshot.score} · 가드 ${snapshot.blockedAttackCount} · 회피 ${snapshot.dodgedAttackCount}",
                            color = Color(0xFFD8CDDF),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (snapshot.players.isNotEmpty()) {
                            val p1 = requireNotNull(snapshot.players[PlayerId.P1])
                            val p2 = requireNotNull(snapshot.players[PlayerId.P2])
                            Text(
                                modifier = Modifier.padding(top = 4.dp),
                                text = "P1 score ${p1.score} · P2 score ${p2.score}",
                                color = Color(0xFFFFD166),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Button(
                            modifier = Modifier.padding(top = 16.dp),
                            onClick = { roundSerial += 1 },
                        ) {
                            Text("새 1라운드")
                        }
                    }
                }
            } else {
                if (snapshot.pauseReason == com.motionarcade.core.contract.PauseReason.USER) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = camera.gameplayInputAllowed,
                        onClick = {
                            camera.runWhenGameplayAllowed { game.resume() }
                            snapshot = game.snapshot
                        },
                    ) { Text("라운드 재개") }
                } else {
                    SafeResumeControl(
                        recoveryKey = "${snapshot.sessionId}:${snapshot.calibrationRevision}",
                        ready = camera.gameplayInputAllowed && motionRearmed,
                        idleLabel = "라운드 재개",
                        onResume = {
                            camera.runWhenGameplayAllowed { game.resume() }
                            snapshot = game.snapshot
                        },
                    )
                }
            }

            Text(
                text = "P1/P2 버튼은 각 플레이어 모션 이벤트와 같은 경로를 사용하는 QA 대체 입력입니다.",
                color = Color(0xFFB8AABD),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal const val BOXING_ART_FRAME_COUNT = 4
internal const val BOXING_ART_COLUMNS = 2
internal const val BOXING_ART_FRAME_WIDTH_PX = 540
internal const val BOXING_ART_FRAME_HEIGHT_PX = 960

internal enum class BoxingVisualAction {
    NEUTRAL,
    ATTACK,
    GUARD,
    DODGE_LEFT,
    DODGE_RIGHT,
    HIT,
}

internal data class BoxingFxCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class BoxerVisualTransform(
    val translationXFraction: Float,
    val translationYFraction: Float,
    val rotationDegrees: Float,
    val scale: Float,
)

internal fun boxingVisualActionFor(type: MotionType): BoxingVisualAction = when (type) {
    MotionType.PUNCH_JAB,
    MotionType.PUNCH_HOOK,
    -> BoxingVisualAction.ATTACK
    MotionType.BOXING_GUARD -> BoxingVisualAction.GUARD
    MotionType.DODGE_LEFT -> BoxingVisualAction.DODGE_LEFT
    MotionType.DODGE_RIGHT -> BoxingVisualAction.DODGE_RIGHT
    else -> BoxingVisualAction.NEUTRAL
}

internal fun boxingFxCrop(action: BoxingVisualAction): BoxingFxCrop? = when (action) {
    BoxingVisualAction.NEUTRAL -> null
    BoxingVisualAction.ATTACK -> BoxingFxCrop(0, 0, 700, 420)
    BoxingVisualAction.GUARD -> BoxingFxCrop(0, 280, 700, 520)
    BoxingVisualAction.DODGE_LEFT,
    BoxingVisualAction.DODGE_RIGHT,
    -> BoxingFxCrop(500, 260, 600, 500)
    BoxingVisualAction.HIT -> BoxingFxCrop(980, 250, 556, 520)
}

internal fun boxerVisualTransform(
    action: BoxingVisualAction,
    isLeft: Boolean,
): BoxerVisualTransform {
    val towardCenter = if (isLeft) 0.12f else -0.12f
    return when (action) {
        BoxingVisualAction.NEUTRAL -> BoxerVisualTransform(0f, 0f, 0f, 1f)
        BoxingVisualAction.ATTACK -> BoxerVisualTransform(towardCenter, -0.015f, if (isLeft) 4f else -4f, 1.05f)
        BoxingVisualAction.GUARD -> BoxerVisualTransform(0f, 0.025f, 0f, 0.96f)
        BoxingVisualAction.DODGE_LEFT -> BoxerVisualTransform(-0.09f, 0.015f, -7f, 0.98f)
        BoxingVisualAction.DODGE_RIGHT -> BoxerVisualTransform(0.09f, 0.015f, 7f, 0.98f)
        BoxingVisualAction.HIT -> BoxerVisualTransform(-towardCenter * 0.55f, 0.02f, if (isLeft) -6f else 6f, 0.94f)
    }
}

internal fun defensiveVisualTransition(
    previous: com.motionarcade.games.boxing.BoxingPlayerState,
    current: com.motionarcade.games.boxing.BoxingPlayerState,
): BoxingVisualAction? = when {
    current.dodgedAttackCount > previous.dodgedAttackCount ->
        when (current.dodgeDirection ?: previous.dodgeDirection) {
            com.motionarcade.games.boxing.BoxingDodgeDirection.LEFT -> BoxingVisualAction.DODGE_LEFT
            com.motionarcade.games.boxing.BoxingDodgeDirection.RIGHT -> BoxingVisualAction.DODGE_RIGHT
            null -> null
        }
    current.blockedAttackCount > previous.blockedAttackCount -> BoxingVisualAction.GUARD
    current.health < previous.health -> BoxingVisualAction.HIT
    else -> null
}

@Composable
internal fun BoxingArenaPresentation(
    playerOneSelection: Int,
    playerTwoSelection: Int,
    playerOneAction: BoxingVisualAction,
    playerTwoAction: BoxingVisualAction,
    combatFx: ImageBitmap,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF080B1F)),
    ) {
        Image(
            painter = painterResource(R.drawable.boxing_ring_v1),
            contentDescription = "복싱 경기장",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            drawLine(
                color = Color(0x99FFFFFF),
                start = Offset(centerX, size.height * 0.08f),
                end = Offset(centerX, size.height * 0.92f),
                strokeWidth = 2.dp.toPx(),
            )
        }
        BoxerSprite(
            selection = playerOneSelection,
            label = "P1",
            action = playerOneAction,
            isLeft = true,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        BoxerSprite(
            selection = playerTwoSelection,
            label = "P2",
            action = playerTwoAction,
            isLeft = false,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        BoxingActionFx(combatFx, playerOneAction, isLeft = true)
        BoxingActionFx(combatFx, playerTwoAction, isLeft = false)
        Text(
            text = "${boxingCharacterName(playerOneSelection)}  VS  ${boxingCharacterName(playerTwoSelection)}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(Color(0xB30A0B18), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BoxerSprite(
    selection: Int,
    label: String,
    action: BoxingVisualAction,
    isLeft: Boolean,
    modifier: Modifier,
) {
    val transform = boxerVisualTransform(action, isLeft)
    Image(
        painter = painterResource(boxingCharacterDrawable(selection)),
        contentDescription = "$label ${boxingCharacterName(selection)} ${action.name.lowercase()}",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth(0.67f)
            .aspectRatio(1f)
            .graphicsLayer {
                translationX = size.width * transform.translationXFraction
                translationY = size.height * transform.translationYFraction
                rotationZ = transform.rotationDegrees
                scaleX = transform.scale * if (isLeft) 1f else -1f
                scaleY = transform.scale
            },
    )
}

@Composable
private fun BoxingActionFx(
    image: ImageBitmap,
    action: BoxingVisualAction,
    isLeft: Boolean,
) {
    val crop = boxingFxCrop(action) ?: return
    Canvas(Modifier.fillMaxSize()) {
        val effectSize = (size.width * 0.38f).toInt().coerceAtLeast(1)
        val centerX = size.width * if (isLeft) 0.43f else 0.57f
        val centerY = size.height * 0.48f
        drawImage(
            image = image,
            srcOffset = IntOffset(crop.x, crop.y),
            srcSize = IntSize(crop.width, crop.height),
            dstOffset = IntOffset(
                (centerX - effectSize / 2f).toInt(),
                (centerY - effectSize / 2f).toInt(),
            ),
            dstSize = IntSize(effectSize, effectSize),
            alpha = 0.9f,
        )
    }
}

private const val BOXING_VISUAL_FEEDBACK_MS = 260L

@Composable
internal fun BoxingStatusCard(snapshot: BoxingSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF211728)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (snapshot.players.isNotEmpty()) {
                DualBoxingPlayerStatus(
                    label = "P1",
                    player = requireNotNull(snapshot.players[PlayerId.P1]),
                    accent = Color(0xFF4DD3A4),
                )
                DualBoxingPlayerStatus(
                    label = "P2",
                    player = requireNotNull(snapshot.players[PlayerId.P2]),
                    accent = Color(0xFF25C7D9),
                )
                Text(
                    text = "남은 시간 ${snapshot.roundTicksRemaining / 10}.${snapshot.roundTicksRemaining % 10}s · P1 ${snapshot.players.getValue(PlayerId.P1).score} : ${snapshot.players.getValue(PlayerId.P2).score} P2",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                HealthBar("상대", snapshot.opponentHealth, Color(0xFFFF5C7A))
                HealthBar("나", snapshot.playerHealth, Color(0xFF4DD3A4))
                HealthBar("스태미나", snapshot.playerStamina, Color(0xFF58B4FF))
                Text(
                    text = "남은 시간 ${snapshot.roundTicksRemaining / 10}.${snapshot.roundTicksRemaining % 10}s · 점수 ${snapshot.score}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                val telegraph = snapshot.aiTelegraph?.let(::telegraphLabel) ?: "AI가 거리를 재는 중"
                Text(
                    modifier = Modifier.semantics { contentDescription = "AI 예고: $telegraph" },
                    text = "AI 예고: $telegraph",
                    color = if (snapshot.aiTelegraph == null) Color(0xFFD8CDDF) else Color(0xFFFFD166),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DualBoxingPlayerStatus(
    label: String,
    player: com.motionarcade.games.boxing.BoxingPlayerState,
    accent: Color,
) {
    Text(
        text = "$label · score ${player.score} · guard ${player.blockedAttackCount} · dodge ${player.dodgedAttackCount}",
        color = accent,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
    HealthBar("$label health", player.health, accent)
    HealthBar("$label stamina", player.stamina, Color(0xFF58B4FF))
    if (player.requiresReturnToGuard) {
        Text(
            text = "$label must return to guard before the next strike.",
            color = Color(0xFFD8CDDF),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HealthBar(label: String, value: Int, color: Color) {
    Column {
        Text(
            text = "$label $value / 100",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics { contentDescription = "$label $value 퍼센트" },
            progress = { value.coerceIn(0, 100) / 100f },
            color = color,
            trackColor = Color(0xFF44364F),
        )
    }
}

@Composable
internal fun BoxingControls(
    snapshot: BoxingSnapshot,
    onAction: (PlayerId, MotionType) -> Unit,
    onPause: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val players = if (snapshot.mode == GameMode.DUAL) {
            listOf(PlayerId.P1 to Color(0xFF4DD3A4), PlayerId.P2 to Color(0xFF25C7D9))
        } else {
            listOf(PlayerId.P1 to Color(0xFF4DD3A4))
        }
        players.forEach { (player, accent) ->
            Text(
                if (snapshot.mode == GameMode.DUAL) "${player.name} QA 입력" else "QA 대체 입력",
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BoxingActionButton("가드", Modifier.weight(1f), Color(0xFF5964F2)) {
                    onAction(player, MotionType.BOXING_GUARD)
                }
                BoxingActionButton("←회피", Modifier.weight(1f), Color(0xFF20A39E)) {
                    onAction(player, MotionType.DODGE_LEFT)
                }
                BoxingActionButton("회피→", Modifier.weight(1f), Color(0xFF20A39E)) {
                    onAction(player, MotionType.DODGE_RIGHT)
                }
                BoxingActionButton("잽", Modifier.weight(1f), Color(0xFFE45F7A)) {
                    onAction(player, MotionType.PUNCH_JAB)
                }
                BoxingActionButton("훅", Modifier.weight(1f), Color(0xFFFF8A4C)) {
                    onAction(player, MotionType.PUNCH_HOOK)
                }
            }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onPause,
        ) {
            Text(if (snapshot.paused) "재개" else "일시정지")
        }
    }
}

@Composable
private fun BoxingActionButton(
    label: String,
    modifier: Modifier,
    color: Color,
    onClick: () -> Unit,
) {
    Button(
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        onClick = onClick,
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

private fun telegraphLabel(attack: BoxingAiAttack): String = when (attack) {
    BoxingAiAttack.STRAIGHT -> "스트레이트 — 왼쪽 회피 또는 가드"
    BoxingAiAttack.HOOK -> "훅 — 오른쪽 회피 또는 가드"
    BoxingAiAttack.BODY -> "바디 — 가드"
}

internal fun outcomeLabel(outcome: BoxingOutcome?): String = when (outcome) {
    BoxingOutcome.WIN -> "라운드 승리"
    BoxingOutcome.LOSS -> "라운드 패배"
    BoxingOutcome.DRAW, null -> "라운드 무승부"
}

internal fun dualOutcomeLabel(outcome: BoxingOutcome?): String = when (outcome) {
    BoxingOutcome.WIN -> "P1 승리"
    BoxingOutcome.LOSS -> "P2 승리"
    BoxingOutcome.DRAW, null -> "무승부"
}
