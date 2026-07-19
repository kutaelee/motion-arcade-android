package com.motionarcade.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import com.motionarcade.app.fishing.FishingGameViewModel
import com.motionarcade.app.fishing.FishingGameRuntime
import com.motionarcade.app.fishing.FishingGameRuntimeTarget
import com.motionarcade.app.fishing.FishingHud
import com.motionarcade.app.fishing.FishingRodSelectionPresentation
import com.motionarcade.app.fishing.NO_FISHING_ROD_SELECTED
import com.motionarcade.app.fishing.DualFishingGameScreen
import com.motionarcade.app.fishing.isFishingRodSelectionValid
import com.motionarcade.app.fishing.toHudModel
import com.motionarcade.app.fishing.toHudProjection
import com.motionarcade.app.boxing.BoxingGameScreen
import com.motionarcade.app.boxing.SoloBoxingGameScreen
import com.motionarcade.app.monster.MonsterRaidGameScreen
import com.motionarcade.app.ui.MotionArcadeTheme
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.camera.FrontCameraPreviewSurface
import com.motionarcade.vision.camera.CameraLensSelection
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.motion.FishingMotionFrameSink
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotionArcadeTheme {
                MotionArcadeEntryScreen(onRestartRuntime = ::recreate)
            }
        }
    }
}

@Composable
private fun MotionArcadeEntryScreen(onRestartRuntime: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionPreferences = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(
            CAMERA_PERMISSION_PREFERENCES,
            android.content.Context.MODE_PRIVATE,
        )
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionInFlightAction by remember {
        mutableStateOf(CameraPermissionInFlightAction.NONE)
    }
    var permissionDenied by remember {
        mutableStateOf(
            !permissionGranted &&
                permissionPreferences.getBoolean(CAMERA_PERMISSION_DENIED, false),
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionInFlightAction = CameraPermissionInFlightAction.NONE
        permissionGranted = granted
        permissionDenied = !granted
        permissionPreferences.edit().putBoolean(CAMERA_PERMISSION_DENIED, !granted).apply()
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionInFlightAction = CameraPermissionInFlightAction.NONE
        permissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
            permissionDenied = false
            permissionPreferences.edit().putBoolean(CAMERA_PERMISSION_DENIED, false).apply()
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionInFlightAction = CameraPermissionInFlightAction.NONE
                permissionGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (permissionGranted) {
                    permissionDenied = false
                    permissionPreferences.edit().putBoolean(CAMERA_PERMISSION_DENIED, false).apply()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (permissionGranted) {
        CameraPreviewScreen(
            onPermissionMissing = { permissionGranted = false },
            onRestartRuntime = onRestartRuntime,
        )
    } else {
        val shouldShowRationale =
            (context as? Activity)?.shouldShowRequestPermissionRationale(
                Manifest.permission.CAMERA,
            ) == true
        val recoveryRoute = cameraPermissionRecoveryRoute(
            permissionDenied = permissionDenied,
            shouldShowRationale = shouldShowRationale,
        )
        CameraPermissionScreen(
            inFlightAction = permissionInFlightAction,
            permissionDenied = permissionDenied,
            recoveryRoute = recoveryRoute,
            onRequestPermission = {
                if (permissionInFlightAction == CameraPermissionInFlightAction.NONE) {
                    permissionInFlightAction =
                        CameraPermissionInFlightAction.REQUEST_SYSTEM_PERMISSION
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onOpenSettings = {
                if (permissionInFlightAction == CameraPermissionInFlightAction.NONE) {
                    permissionInFlightAction = CameraPermissionInFlightAction.OPEN_APP_SETTINGS
                    settingsLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun CameraPermissionScreen(
    inFlightAction: CameraPermissionInFlightAction,
    permissionDenied: Boolean,
    recoveryRoute: CameraPermissionRecoveryRoute,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val actionEnabled = inFlightAction == CameraPermissionInFlightAction.NONE
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = stringResource(
                    if (recoveryRoute == CameraPermissionRecoveryRoute.OPEN_APP_SETTINGS) {
                        R.string.camera_permission_settings_body
                    } else {
                        R.string.camera_permission_body
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                modifier = Modifier.padding(top = 24.dp),
                enabled = actionEnabled,
                onClick = {
                    when (recoveryRoute) {
                        CameraPermissionRecoveryRoute.REQUEST_SYSTEM_PERMISSION ->
                            onRequestPermission()
                        CameraPermissionRecoveryRoute.OPEN_APP_SETTINGS -> onOpenSettings()
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        if (
                            inFlightAction ==
                            CameraPermissionInFlightAction.OPEN_APP_SETTINGS
                        ) {
                            R.string.camera_permission_opening_settings
                        } else if (
                            inFlightAction ==
                            CameraPermissionInFlightAction.REQUEST_SYSTEM_PERMISSION
                        ) {
                            R.string.camera_permission_requesting
                        } else if (recoveryRoute == CameraPermissionRecoveryRoute.OPEN_APP_SETTINGS) {
                            R.string.camera_permission_open_settings
                        } else if (permissionDenied) {
                            R.string.camera_permission_retry
                        } else {
                            R.string.camera_permission_action
                        },
                    ),
                )
            }
            if (
                permissionDenied &&
                recoveryRoute == CameraPermissionRecoveryRoute.REQUEST_SYSTEM_PERMISSION
            ) {
                Button(
                    modifier = Modifier.padding(top = 12.dp),
                    enabled = actionEnabled,
                    onClick = onOpenSettings,
                ) {
                    Text(
                        text = stringResource(
                            if (
                                inFlightAction ==
                                CameraPermissionInFlightAction.OPEN_APP_SETTINGS
                            ) {
                                R.string.camera_permission_opening_settings
                            } else {
                                R.string.camera_permission_open_settings
                            },
                        ),
                    )
                }
            }
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(R.string.camera_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val CAMERA_PERMISSION_PREFERENCES = "camera-permission-recovery-v1"
private const val CAMERA_PERMISSION_DENIED = "denied"

private enum class ArcadePlayMode {
    FISHING,
    FISHING_DUAL,
    BOXING_SOLO,
    BOXING_DUAL,
    MONSTER_SOLO,
    MONSTER_DUAL,
}

private fun arcadePlayMode(
    game: ArcadeGameChoice,
    playerCount: ArcadePlayerCount,
): ArcadePlayMode = when (game to playerCount) {
    ArcadeGameChoice.FISHING to ArcadePlayerCount.SOLO -> ArcadePlayMode.FISHING
    ArcadeGameChoice.FISHING to ArcadePlayerCount.DUAL -> ArcadePlayMode.FISHING_DUAL
    ArcadeGameChoice.BOXING to ArcadePlayerCount.SOLO -> ArcadePlayMode.BOXING_SOLO
    ArcadeGameChoice.BOXING to ArcadePlayerCount.DUAL -> ArcadePlayMode.BOXING_DUAL
    ArcadeGameChoice.MONSTER to ArcadePlayerCount.SOLO -> ArcadePlayMode.MONSTER_SOLO
    ArcadeGameChoice.MONSTER to ArcadePlayerCount.DUAL -> ArcadePlayMode.MONSTER_DUAL
    else -> error("Unsupported arcade game/player-count combination")
}

@Composable
private fun CameraPreviewScreen(
    onPermissionMissing: () -> Unit,
    onRestartRuntime: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedGame by rememberSaveable { mutableStateOf<ArcadePlayMode?>(null) }
    var selectedFishingRod by rememberSaveable { mutableIntStateOf(NO_FISHING_ROD_SELECTED) }
    var fishingRodConfirmed by rememberSaveable { mutableStateOf(false) }
    var lensSelection by rememberSaveable { mutableStateOf(CameraLensSelection.FRONT) }
    var rearConfirmationPending by rememberSaveable { mutableStateOf(false) }
    var lensBindEpoch by rememberSaveable { mutableIntStateOf(0) }
    var cameraCalibrationEpoch by rememberSaveable { mutableIntStateOf(0) }
    fun returnToGameSelection() {
        selectedGame = null
        fishingRodConfirmed = false
        selectedFishingRod = NO_FISHING_ROD_SELECTED
    }
    fun completeLensRequest(next: CameraLensSelection?) {
        rearConfirmationPending = false
        if (next == null) return
        if (next != lensSelection) {
            lensSelection = next
            cameraCalibrationEpoch = Math.incrementExact(cameraCalibrationEpoch)
        }
        lensBindEpoch = Math.incrementExact(lensBindEpoch)
    }
    fun requestLens(next: CameraLensSelection) {
        if (next == CameraLensSelection.BACK) {
            rearConfirmationPending = true
        } else {
            completeLensRequest(next)
        }
    }
    if (rearConfirmationPending) {
        AlertDialog(
            onDismissRequest = { completeLensRequest(null) },
            title = { Text("후면 카메라 실험 모드") },
            text = {
                Text("화면을 계속 볼 수 있는 거치·미러링 또는 감독자가 있고, 주변 안전 공간이 확보된 QA 환경에서만 사용하세요. 전환 즉시 게임 입력이 정지됩니다.")
            },
            confirmButton = {
                TextButton(onClick = {
                    completeLensRequest(CameraLensSelection.BACK)
                }) { Text("안전 환경 확인") }
            },
            dismissButton = {
                TextButton(onClick = { completeLensRequest(null) }) { Text("전면 유지") }
            },
        )
    }
    if (selectedGame == null) {
        ArcadeModeSelection(
            onModeSelected = { game, players ->
                selectedGame = arcadePlayMode(game, players)
                fishingRodConfirmed = false
                selectedFishingRod = NO_FISHING_ROD_SELECTED
            },
        )
        return
    }
    if (selectedGame == ArcadePlayMode.FISHING_DUAL) {
        DualPlayerCameraRoute(
            profile = com.motionarcade.vision.motion.DualPlayerCombatProfile.FISHING,
            lensSelection = lensSelection,
            lensBindEpoch = lensBindEpoch,
            calibrationRevisionOffset = cameraCalibrationEpoch,
            onLensSelectionRequested = ::requestLens,
            onPermissionMissing = onPermissionMissing,
        ) { camera ->
            DualFishingGameScreen(
                camera = camera,
                onBack = ::returnToGameSelection,
            )
        }
        return
    }
    if (selectedGame == ArcadePlayMode.BOXING_SOLO) {
        SoloCombatCameraRoute(
            profile = com.motionarcade.vision.motion.DualPlayerCombatProfile.BOXING,
            lensSelection = lensSelection,
            lensBindEpoch = lensBindEpoch,
            calibrationRevision = cameraCalibrationEpoch,
            onLensSelectionRequested = ::requestLens,
            onPermissionMissing = onPermissionMissing,
        ) { camera ->
            SoloBoxingGameScreen(
                camera = camera,
                onBack = ::returnToGameSelection,
            )
        }
        return
    }
    if (selectedGame == ArcadePlayMode.BOXING_DUAL) {
        DualPlayerCameraRoute(
            profile = com.motionarcade.vision.motion.DualPlayerCombatProfile.BOXING,
            lensSelection = lensSelection,
            lensBindEpoch = lensBindEpoch,
            calibrationRevisionOffset = cameraCalibrationEpoch,
            onLensSelectionRequested = ::requestLens,
            onPermissionMissing = onPermissionMissing,
        ) { camera ->
            BoxingGameScreen(
                camera = camera,
                onBack = ::returnToGameSelection,
            )
        }
        return
    }
    if (selectedGame == ArcadePlayMode.MONSTER_DUAL) {
        DualPlayerCameraRoute(
            profile = com.motionarcade.vision.motion.DualPlayerCombatProfile.MONSTER,
            lensSelection = lensSelection,
            lensBindEpoch = lensBindEpoch,
            calibrationRevisionOffset = cameraCalibrationEpoch,
            onLensSelectionRequested = ::requestLens,
            onPermissionMissing = onPermissionMissing,
        ) { camera ->
            MonsterRaidGameScreen(
                camera = camera,
                onBack = ::returnToGameSelection,
            )
        }
        return
    }
    if (selectedGame == ArcadePlayMode.MONSTER_SOLO) {
        SoloCombatCameraRoute(
            profile = com.motionarcade.vision.motion.DualPlayerCombatProfile.MONSTER,
            lensSelection = lensSelection,
            lensBindEpoch = lensBindEpoch,
            calibrationRevision = cameraCalibrationEpoch,
            onLensSelectionRequested = ::requestLens,
            onPermissionMissing = onPermissionMissing,
        ) { camera ->
            MonsterRaidGameScreen(
                camera = camera,
                onBack = ::returnToGameSelection,
            )
        }
        return
    }
    if (!fishingRodConfirmed) {
        FishingRodSelectionPresentation(
            selectedRod = selectedFishingRod,
            onSelectRod = { selectedFishingRod = it },
            onContinue = {
                if (isFishingRodSelectionValid(selectedFishingRod)) {
                    fishingRodConfirmed = true
                }
            },
            onBack = ::returnToGameSelection,
        )
        return
    }
    val fishingMotionConfigResult = remember(context) {
        runCatching {
            context.assets.open(FishingMotionConfigLoader.ASSET_PATH).use(
                FishingMotionConfigLoader::load,
            )
        }
    }
    val fishingMotionConfig = fishingMotionConfigResult.getOrNull()
    if (fishingMotionConfig == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF071723))
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.fishing_motion_config_failed),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val activeFishingMotionConfig = remember(fishingMotionConfig, cameraCalibrationEpoch) {
        if (cameraCalibrationEpoch == 0) {
            fishingMotionConfig
        } else {
            fishingMotionConfig.withCalibrationRevision(
                Math.addExact(fishingMotionConfig.calibrationRevision, cameraCalibrationEpoch),
            )
        }
    }
    val fishingViewModelFactory = remember(activeFishingMotionConfig) {
        viewModelFactory {
            initializer {
                FishingGameViewModel(createSavedStateHandle(), activeFishingMotionConfig)
            }
        }
    }
    val fishingViewModel: FishingGameViewModel = viewModel(factory = fishingViewModelFactory)
    val fishingRuntimeTarget = remember(fishingViewModel) {
        FishingViewModelRuntimeTarget(fishingViewModel)
    }
    val fishingRuntime = remember(fishingRuntimeTarget) {
        FishingGameRuntime(fishingRuntimeTarget)
    }
    val fishingState by fishingViewModel.uiState.collectAsStateWithLifecycle()
    val fishingHudModel = fishingState.toHudProjection().toHudModel()
    val previewDescription = stringResource(R.string.camera_preview_description)
    val surface = remember(context) { FrontCameraPreviewSurface(context) }
    var status by remember { mutableStateOf(FrontCameraPreviewStatus.IDLE) }
    var inference by remember { mutableStateOf(LivePoseInferenceSnapshot.idle()) }
    var bindRequest by remember { mutableIntStateOf(0) }
    var cameraRebindPolicy by remember { mutableStateOf(FishingCameraRebindPolicyState()) }
    var boundGameIdentity by remember {
        mutableStateOf(
            fishingState.snapshot.sessionId to fishingState.snapshot.eventTimelineEpoch,
        )
    }

    LaunchedEffect(
        surface,
        lifecycleOwner,
        bindRequest,
        lensSelection,
        lensBindEpoch,
        activeFishingMotionConfig,
    ) {
        val bindingSessionId = fishingState.snapshot.sessionId
        val bindingTimelineEpoch = fishingState.snapshot.eventTimelineEpoch
        fishingRuntime.onMotionBindingClosed()
        if (fishingState.snapshot.calibrationRevision < activeFishingMotionConfig.calibrationRevision) {
            fishingRuntime.onCameraRecalibrated(activeFishingMotionConfig)
        }
        surface.bind(
            lifecycleOwner = lifecycleOwner,
            lensSelection = lensSelection,
            metadataSink = com.motionarcade.vision.camera.CameraFrameMetadataSink.NONE,
            inferenceSink = LivePoseInferenceSink { inference = it },
            fishingMotionConfig = activeFishingMotionConfig,
            fishingMotionSink = FishingMotionFrameSink { frame ->
                fishingRuntime.onMotionFrame(frame)
            },
            onMotionBindingStarted = { generation ->
                fishingRuntime.onMotionBindingStarted(
                    sessionId = bindingSessionId,
                    eventTimelineEpoch = bindingTimelineEpoch,
                    sessionGeneration = generation,
                )
            },
            onStatus = { next ->
                if (cameraStatusRequiresPermission(next)) {
                    onPermissionMissing()
                } else {
                    status = next
                }
            },
        )
    }
    DisposableEffect(fishingRuntime) {
        onDispose { fishingRuntime.close() }
    }
    DisposableEffect(surface) {
        onDispose { surface.release() }
    }
    LaunchedEffect(
        fishingState.snapshot.sessionId,
        fishingState.snapshot.eventTimelineEpoch,
    ) {
        val nextIdentity =
            fishingState.snapshot.sessionId to fishingState.snapshot.eventTimelineEpoch
        if (nextIdentity != boundGameIdentity) {
            boundGameIdentity = nextIdentity
            bindRequest += 1
        }
    }
    DisposableEffect(lifecycleOwner, fishingViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    fishingRuntime.onBackground()
                }
                Lifecycle.Event.ON_START -> {
                    fishingRuntime.onForeground()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    cameraRebindPolicy = reduceFishingCameraRebindPolicy(
                        cameraRebindPolicy,
                        FishingCameraRebindEvent.PAUSE,
                    ).state
                }
                Lifecycle.Event.ON_RESUME -> {
                    val decision = reduceFishingCameraRebindPolicy(
                        cameraRebindPolicy,
                        FishingCameraRebindEvent.RESUME,
                    )
                    cameraRebindPolicy = decision.state
                    if (decision.requestBind) {
                        status = FrontCameraPreviewStatus.STARTING
                        bindRequest += 1
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status, inference.revision, inference.phase, inference.poseCount) {
        fishingRuntime.onCameraState(
            active = status == FrontCameraPreviewStatus.ACTIVE,
            poseCount = if (inference.phase == LivePoseInferencePhase.ACTIVE) {
                inference.poseCount
            } else {
                null
            },
        )
    }

    PoseRecognitionFailureToast(
        status = status,
        inference = inference,
        expectedPlayers = 1,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071723)),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = previewDescription
                },
            factory = { surface },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB3000000),
                            Color.Transparent,
                            Color(0xD9000000),
                        ),
                    ),
                ),
        )

        FishingHud(
            model = fishingHudModel,
            onAction = { fishingRuntime.onPrimaryAction() },
            onPause = { fishingRuntime.onPauseToggle() },
            modifier = Modifier.padding(top = 96.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraLensControl(
                lensSelection = lensSelection,
                onLensSelectionRequested = ::requestLens,
            )
            OutlinedButton(onClick = ::returnToGameSelection) {
                Text(text = "게임 선택")
            }
        }
        if (fishingState.runtimeFailed) {
            Button(
                modifier = Modifier
                    .align(Alignment.Center)
                    .safeDrawingPadding(),
                onClick = onRestartRuntime,
            ) {
                Text(text = stringResource(R.string.fishing_runtime_restart))
            }
        }
        if (previewCanRetry(status, inference, cameraRebindPolicy.manualRetryAttempts)) {
            Button(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 12.dp),
                onClick = {
                    val decision = reduceFishingCameraRebindPolicy(
                        cameraRebindPolicy,
                        FishingCameraRebindEvent.MANUAL_RETRY,
                    )
                    cameraRebindPolicy = decision.state
                    if (decision.requestBind) {
                        status = FrontCameraPreviewStatus.STARTING
                        bindRequest += 1
                    }
                },
            ) {
                Text(text = stringResource(R.string.fishing_camera_retry))
            }
        }
    }
}

@Composable
private fun PreviewStatusPill(
    modifier: Modifier = Modifier,
    status: FrontCameraPreviewStatus,
) {
    val active = status == FrontCameraPreviewStatus.ACTIVE
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xCC071723))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (active) Color(0xFF35D7A4) else Color(0xFFFFC857)),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(previewStatusLabel(status)),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun PoseStatusPill(
    modifier: Modifier = Modifier,
    inference: LivePoseInferenceSnapshot,
) {
    val active = inference.phase == LivePoseInferencePhase.ACTIVE
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xCC071723))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (active) Color(0xFF55C7FF) else Color(0xFFFFC857)),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = poseInferenceLabel(inference),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun PreviewInstructionCard(
    modifier: Modifier,
    status: FrontCameraPreviewStatus,
    inference: LivePoseInferenceSnapshot,
    rebindAttempts: Int,
    onRetry: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.camera_preview_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF12263A),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.camera_preview_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF27445A),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = poseInferenceDetail(inference),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5C6F7F),
            )
            if (previewCanRetry(status, inference, rebindAttempts)) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    onClick = onRetry,
                ) {
                    Text(text = stringResource(R.string.camera_preview_retry))
                }
            }
        }
    }
}

@Composable
private fun poseInferenceLabel(inference: LivePoseInferenceSnapshot): String =
    when (inference.phase) {
        LivePoseInferencePhase.IDLE -> stringResource(R.string.pose_inference_idle)
        LivePoseInferencePhase.INITIALIZING -> stringResource(R.string.pose_inference_initializing)
        LivePoseInferencePhase.WAITING_FOR_RESULT ->
            stringResource(R.string.pose_inference_waiting)
        LivePoseInferencePhase.ACTIVE ->
            stringResource(R.string.pose_inference_active, requireNotNull(inference.poseCount))
        LivePoseInferencePhase.FAILED -> stringResource(R.string.pose_inference_failed)
        LivePoseInferencePhase.RELEASED -> stringResource(R.string.pose_inference_released)
    }

@Composable
private fun poseInferenceDetail(inference: LivePoseInferenceSnapshot): String =
    if (inference.phase == LivePoseInferencePhase.ACTIVE) {
        stringResource(
            R.string.pose_inference_detail_active,
            requireNotNull(inference.poseCount),
            inference.callbackCount,
        )
    } else {
        stringResource(R.string.camera_preview_scope_note)
    }

internal fun cameraStatusRequiresPermission(status: FrontCameraPreviewStatus): Boolean =
    status == FrontCameraPreviewStatus.PERMISSION_MISSING

internal fun cameraStatusCanRetry(status: FrontCameraPreviewStatus): Boolean =
    status == FrontCameraPreviewStatus.BIND_FAILED ||
        status == FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE

internal fun previewCanRetry(
    status: FrontCameraPreviewStatus,
    inference: LivePoseInferenceSnapshot,
    rebindAttempts: Int,
): Boolean =
    rebindAttempts in 0 until MAX_PREVIEW_REBIND_ATTEMPTS &&
        (
            cameraStatusCanRetry(status) ||
                inference.phase == LivePoseInferencePhase.FAILED
        )

internal enum class FishingCameraRebindEvent {
    PAUSE,
    RESUME,
    MANUAL_RETRY,
}

internal data class FishingCameraRebindPolicyState(
    val pausedSinceLastResume: Boolean = false,
    val manualRetryAttempts: Int = 0,
)

internal data class FishingCameraRebindDecision(
    val state: FishingCameraRebindPolicyState,
    val requestBind: Boolean,
)

/**
 * Keeps lifecycle rebinds separate from the initial Compose bind.
 *
 * Lifecycle observers may receive an initial catch-up RESUME when they are added. Only a RESUME
 * paired with a preceding PAUSE opens a new camera epoch and requests a fresh generation. Game
 * background/foreground remains independently owned by STOP/START.
 */
internal fun reduceFishingCameraRebindPolicy(
    state: FishingCameraRebindPolicyState,
    event: FishingCameraRebindEvent,
): FishingCameraRebindDecision = when (event) {
    FishingCameraRebindEvent.PAUSE -> FishingCameraRebindDecision(
        state = state.copy(pausedSinceLastResume = true),
        requestBind = false,
    )
    FishingCameraRebindEvent.RESUME -> if (state.pausedSinceLastResume) {
        FishingCameraRebindDecision(
            state = FishingCameraRebindPolicyState(),
            requestBind = true,
        )
    } else {
        FishingCameraRebindDecision(state = state, requestBind = false)
    }
    FishingCameraRebindEvent.MANUAL_RETRY -> if (
        !state.pausedSinceLastResume &&
        state.manualRetryAttempts < MAX_PREVIEW_REBIND_ATTEMPTS
    ) {
        FishingCameraRebindDecision(
            state = state.copy(manualRetryAttempts = state.manualRetryAttempts + 1),
            requestBind = true,
        )
    } else {
        FishingCameraRebindDecision(state = state, requestBind = false)
    }
}

private fun previewStatusLabel(status: FrontCameraPreviewStatus): Int = when (status) {
    FrontCameraPreviewStatus.IDLE,
    FrontCameraPreviewStatus.STARTING,
    -> R.string.camera_preview_starting
    FrontCameraPreviewStatus.ACTIVE -> R.string.camera_preview_active
    FrontCameraPreviewStatus.PERMISSION_MISSING -> R.string.camera_preview_permission_missing
    FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE -> R.string.camera_preview_front_unavailable
    FrontCameraPreviewStatus.BACK_CAMERA_UNAVAILABLE -> R.string.camera_preview_back_unavailable
    FrontCameraPreviewStatus.BIND_FAILED -> R.string.camera_preview_failed
    FrontCameraPreviewStatus.RELEASED -> R.string.camera_preview_released
}

private const val MAX_PREVIEW_REBIND_ATTEMPTS = 1

internal class FishingViewModelRuntimeTarget(
    private val viewModel: FishingGameViewModel,
) : FishingGameRuntimeTarget {
    override fun onTick(nowNs: Long) = viewModel.onTick(nowNs)

    override fun onPrimaryAction(nowNs: Long) = viewModel.onPrimaryAction(nowNs)

    override fun onMotionFrame(
        frame: com.motionarcade.vision.motion.FishingMotionFrame,
        observedAtNs: Long,
    ) = viewModel.onMotionFrame(frame, observedAtNs)

    override fun onPauseToggle(nowNs: Long) = viewModel.onPauseToggle(nowNs)

    override fun onBackground() = viewModel.onBackground()

    override fun onForeground(nowNs: Long) = viewModel.onForeground(nowNs)

    override fun onCameraState(active: Boolean, poseCount: Int?, observedAtNs: Long) =
        viewModel.onCameraState(active, poseCount, observedAtNs)

    override fun onMotionBindingStarted(
        sessionId: String,
        eventTimelineEpoch: Long,
        sessionGeneration: Long,
    ) {
        viewModel.onMotionBindingStarted(sessionId, eventTimelineEpoch, sessionGeneration)
    }

    override fun onMotionBindingClosed() = viewModel.onMotionBindingClosed()

    override fun onCameraRecalibrated(config: com.motionarcade.vision.motion.FishingMotionConfig) =
        viewModel.onCameraRecalibrated(config)

    override fun onRuntimeQueueOverflow() = viewModel.onRuntimeQueueOverflow()

    override fun onRuntimeWorkerFailure() = viewModel.onRuntimeWorkerFailure()
}
