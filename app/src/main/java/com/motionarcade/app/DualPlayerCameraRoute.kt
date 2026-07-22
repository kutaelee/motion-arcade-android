package com.motionarcade.app

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.vision.camera.CameraFrameMetadataSink
import com.motionarcade.vision.camera.CameraLensSelection
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.camera.FrontCameraPreviewSurface
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrame
import com.motionarcade.vision.motion.DualPlayerCombatMotionFrameSink
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.pose.DualPlayerRoleSetupPolicy
import com.motionarcade.vision.pose.DualPlayerTrackingSink
import com.motionarcade.vision.pose.DualPlayerTrackingSummary
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.tracking.LoadedPlayerTrackerConfig
import com.motionarcade.vision.tracking.PlayerTrackerConfigAssets
import com.motionarcade.vision.tracking.PlayerTrackerConfigJson
import com.motionarcade.vision.tracking.PlayerTrackerConfigLoadResult
import kotlinx.coroutines.delay

/**
 * Camera host shared by the two-player game routes. The app layer gets aggregate role state only;
 * landmarks, detector IDs, and timestamps remain inside :vision.
 */
internal class DualPlayerCameraPresentation(
    val status: FrontCameraPreviewStatus,
    val tracking: DualPlayerTrackingSummary?,
    val configAvailable: Boolean,
    val motionConfig: DualPlayerCombatMotionConfig?,
    val requestRearm: () -> Boolean,
    private val admissionGate: DualPlayerGameplayAdmissionGate,
    private val combatMotionDispatcher: DualPlayerCombatMotionDispatcher,
) {
    /** Presentation may lag; game mutations must use [runWhenGameplayAllowed]. */
    val gameplayInputAllowed: Boolean get() = admissionGate.isGameplayInputAllowed()

    fun currentSafetyPauseReason(): PauseReason = admissionGate.currentSafetyPauseReason()

    fun <T> runWhenGameplayAllowed(action: () -> T): T? = admissionGate.runWhenAllowed(action)

    fun registerSafetyStop(onSafetyStop: (PauseReason) -> Unit): () -> Unit =
        admissionGate.registerSafetyStop(onSafetyStop)

    /** Registers a coordinate-free two-player input consumer plus a safety-boundary fence. */
    fun registerCombatMotionConsumer(consumer: DualPlayerCombatMotionConsumer): () -> Unit {
        val unregisterConsumer = combatMotionDispatcher.register(consumer)
        val unregisterSafety = admissionGate.registerSafetyStop {
            consumer.onMotionContinuityFence(combatMotionDispatcher.latestTimestampNs())
        }
        return {
            unregisterSafety()
            unregisterConsumer()
        }
    }
}

internal fun DualPlayerCameraPresentation.toGameSafetyPauseReason(): PauseReason = currentSafetyPauseReason()

@Composable
internal fun DualPlayerCameraRoute(
    profile: DualPlayerCombatProfile,
    lensSelection: CameraLensSelection,
    lensBindEpoch: Int,
    calibrationRevisionOffset: Int,
    onLensSelectionRequested: (CameraLensSelection) -> Unit,
    onPermissionMissing: () -> Unit,
    content: @Composable (DualPlayerCameraPresentation) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configState = remember(context) { loadDualPlayerTrackerConfig(context) }
    val surface = remember(context) { FrontCameraPreviewSurface(context) }
    var status by remember { mutableStateOf(FrontCameraPreviewStatus.IDLE) }
    var tracking by remember { mutableStateOf<DualPlayerTrackingSummary?>(null) }
    val loadedConfig = (configState as? DualPlayerTrackerConfigState.Ready)?.config
    val combatMotionConfig = remember(profile, context, calibrationRevisionOffset) {
        when (profile) {
            DualPlayerCombatProfile.FISHING -> runCatching {
                context.assets.open(FishingMotionConfigLoader.ASSET_PATH).use(
                    FishingMotionConfigLoader::load,
                )
            }.getOrNull()?.let { config ->
                val active = if (calibrationRevisionOffset == 0) config else {
                    config.withCalibrationRevision(
                        Math.addExact(config.calibrationRevision, calibrationRevisionOffset),
                    )
                }
                DualPlayerCombatMotionConfigs.fishing(active)
            }
            DualPlayerCombatProfile.BOXING ->
                DualPlayerCombatMotionConfigs.boxing(calibrationRevisionOffset)
            DualPlayerCombatProfile.MONSTER ->
                DualPlayerCombatMotionConfigs.monster(calibrationRevisionOffset)
        }
    }
    val admissionGate = remember(loadedConfig, combatMotionConfig) {
        DualPlayerGameplayAdmissionGate(
            configAvailable = loadedConfig != null && combatMotionConfig != null,
        )
    }
    val combatMotionDispatcher = remember(admissionGate, combatMotionConfig) {
        DualPlayerCombatMotionDispatcher()
    }
    var inference by remember(admissionGate) { mutableStateOf(LivePoseInferenceSnapshot.idle()) }
    var autoRecovery by remember(
        surface,
        lifecycleOwner,
        loadedConfig,
        combatMotionConfig,
        lensSelection,
        lensBindEpoch,
    ) {
        mutableStateOf(LivePoseAutoRecoveryState())
    }
    var bindEpoch by remember(lifecycleOwner) {
        mutableIntStateOf(
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) 1 else 0,
        )
    }

    DisposableEffect(lifecycleOwner, admissionGate) {
        val observer = DualPlayerCameraLifecycleRebindObserver(
            initiallyResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            onResumeRebind = { bindEpoch = Math.incrementExact(bindEpoch) },
            onInactive = {
                admissionGate.invalidateForRebind()
                autoRecovery = LivePoseAutoRecoveryState()
                tracking = null
                inference = LivePoseInferenceSnapshot.idle()
                status = FrontCameraPreviewStatus.IDLE
            },
        )
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        surface,
        lifecycleOwner,
        loadedConfig,
        combatMotionConfig,
        admissionGate,
        bindEpoch,
        lensSelection,
        lensBindEpoch,
    ) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }
        // Close before issuing the next bind, so a stale presentation can never bridge rebind.
        admissionGate.invalidateForRebind()
        tracking = null
        inference = LivePoseInferenceSnapshot.idle()
        status = FrontCameraPreviewStatus.IDLE
        if (loadedConfig == null || combatMotionConfig == null) {
            admissionGate.setConfigAvailable(false)
            return@LaunchedEffect
        }
        admissionGate.setConfigAvailable(true)
        surface.bind(
            lifecycleOwner = lifecycleOwner,
            lensSelection = lensSelection,
            metadataSink = CameraFrameMetadataSink.NONE,
            inferenceSink = LivePoseInferenceSink { next ->
                if (admissionGate.onInference(next)) inference = next
            },
            dualPlayerTrackingConfig = loadedConfig,
            roleSetupPolicy = DualPlayerRoleSetupPolicy.P1_ANALYSIS_LEFT_P2_ANALYSIS_RIGHT,
            calibrationRevision = combatMotionConfig.calibrationRevision.toLong(),
            dualPlayerTrackingSink = DualPlayerTrackingSink { next ->
                if (
                    admissionGate.onTracking(
                        generation = next.sessionGeneration,
                        revision = next.revision,
                        pauseRequired = next.pauseRequired,
                        reason = next.pauseReason,
                    )
                ) {
                    tracking = next
                }
            },
            dualPlayerCombatMotionConfig = combatMotionConfig,
            dualPlayerCombatMotionSink = DualPlayerCombatMotionFrameSink { frame ->
                if (!frame.matches(combatMotionConfig)) {
                    if (
                        admissionGate.onCombatMotion(
                            generation = frame.sessionGeneration,
                            revision = frame.revision,
                            usableForDual = false,
                        )
                    ) {
                        combatMotionDispatcher.onMotionContinuityFence(frame.sourceTimestampNs)
                    }
                    return@DualPlayerCombatMotionFrameSink
                }
                if (
                    !admissionGate.onCombatMotion(
                        generation = frame.sessionGeneration,
                        revision = frame.revision,
                        usableForDual = frame.usableForDual,
                    )
                ) {
                    return@DualPlayerCombatMotionFrameSink
                }
                if (!frame.usableForDual) {
                    combatMotionDispatcher.onMotionContinuityFence(frame.sourceTimestampNs)
                    return@DualPlayerCombatMotionFrameSink
                }
                admissionGate.runWhenAllowed(frame.sessionGeneration) {
                    combatMotionDispatcher.onSafeMotionFrame(frame)
                }
            },
            onDualPlayerTrackingBindingStarted = { generation ->
                admissionGate.onBindingStarted(generation)
                tracking = null
                inference = LivePoseInferenceSnapshot.idle(generation)
            },
            onStatus = { next ->
                admissionGate.onPreviewStatus(next)
                status = next
                if (cameraStatusRequiresPermission(next)) onPermissionMissing()
            },
        )
    }
    LaunchedEffect(
        inference.sessionGeneration,
        inference.phase,
    ) {
        val failedSnapshot = inference
        val decision = decideLivePoseAutoRecovery(
            state = autoRecovery,
            inference = failedSnapshot,
            lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        autoRecovery = decision.state
        if (decision.requestRebind) {
            delay(LIVE_POSE_AUTO_RECOVERY_DELAY_MILLIS)
            if (canCompleteLivePoseAutoRecovery(
                    lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(
                        Lifecycle.State.RESUMED,
                    ),
                    inference = inference,
                    failedGeneration = failedSnapshot.sessionGeneration,
                )
            ) {
                status = FrontCameraPreviewStatus.STARTING
                bindEpoch = Math.incrementExact(bindEpoch)
            }
        }
    }
    DisposableEffect(surface) {
        onDispose { surface.release() }
    }

    val presentation =
        DualPlayerCameraPresentation(
            status = status,
            tracking = tracking,
            configAvailable = loadedConfig != null && combatMotionConfig != null,
            motionConfig = combatMotionConfig,
            admissionGate = admissionGate,
            combatMotionDispatcher = combatMotionDispatcher,
            requestRearm = {
                val snapshot = tracking
                snapshot != null &&
                    snapshot.sessionGeneration == inference.sessionGeneration &&
                    surface.requestDualPlayerRearm(
                        expectedGeneration = snapshot.sessionGeneration,
                        expectedRearmChallengeId = snapshot.rearmChallengeId,
                    )
            },
        )

    PoseRecognitionFailureToast(
        status = status,
        inference = inference,
        expectedPlayers = 2,
        automaticRecoveryScheduled = autoRecovery.isRecovering(inference),
    )
    DualTrackingFailureToast(tracking?.pauseReason)

    Box(modifier = Modifier.fillMaxSize()) {
        if (loadedConfig != null && combatMotionConfig != null) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Two-player camera preview" },
                factory = { surface },
            )
        }
        content(presentation)
        CameraLensControl(
            lensSelection = lensSelection,
            onLensSelectionRequested = onLensSelectionRequested,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
    }
}

/** Converts lifecycle resume edges into distinct bind epochs and closes state on every exit. */
internal class DualPlayerCameraLifecycleRebindObserver(
    initiallyResumed: Boolean,
    private val onResumeRebind: () -> Unit,
    private val onInactive: () -> Unit,
) : LifecycleEventObserver {
    private var skipCatchUpResume = initiallyResumed

    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                if (skipCatchUpResume) {
                    skipCatchUpResume = false
                } else {
                    onResumeRebind()
                }
            }
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            -> onInactive()
            else -> Unit
        }
    }
}

/** Main-thread delivery fan-out. It carries no pose geometry and retains no frame history. */
internal class DualPlayerCombatMotionDispatcher {
    private val lock = Any()
    private val consumers = linkedMapOf<Long, DualPlayerCombatMotionConsumer>()
    private var nextId = 0L
    private var latestTimestampNs = 0L

    fun register(consumer: DualPlayerCombatMotionConsumer): () -> Unit {
        val id = synchronized(lock) {
            nextId += 1L
            nextId.also { consumers[it] = consumer }
        }
        return { synchronized(lock) { consumers.remove(id) } }
    }

    fun latestTimestampNs(): Long = synchronized(lock) { latestTimestampNs }

    fun onSafeMotionFrame(frame: DualPlayerCombatMotionFrame) {
        val targets = synchronized(lock) {
            latestTimestampNs = maxOf(latestTimestampNs, frame.sourceTimestampNs)
            consumers.values.toList()
        }
        targets.forEach { consumer ->
            try {
                consumer.onSafeMotionFrame(frame)
            } catch (_: RuntimeException) {
                // A presentation failure cannot reopen the authoritative camera gate.
            }
        }
    }

    fun onMotionContinuityFence(timestampNs: Long) {
        val targets = synchronized(lock) {
            latestTimestampNs = maxOf(latestTimestampNs, timestampNs)
            consumers.values.toList()
        }
        targets.forEach { consumer ->
            try {
                consumer.onMotionContinuityFence(latestTimestampNs())
            } catch (_: RuntimeException) {
                // Fence attempts are fail-closed; the admission gate remains closed.
            }
        }
    }
}

private fun DualPlayerCombatMotionFrame.matches(config: DualPlayerCombatMotionConfig): Boolean =
    profile == config.profile &&
        configId == config.configId &&
        calibrationRevision == config.calibrationRevision

private sealed interface DualPlayerTrackerConfigState {
    data class Ready(val config: LoadedPlayerTrackerConfig) : DualPlayerTrackerConfigState

    data object Unavailable : DualPlayerTrackerConfigState
}

private fun loadDualPlayerTrackerConfig(context: Context): DualPlayerTrackerConfigState =
    try {
        val source =
            context.assets.open(PlayerTrackerConfigAssets.DUAL_PLAYER_CANDIDATE_V1).use { input ->
                input.readBytes()
            }
        when (val parsed = PlayerTrackerConfigJson.load(source)) {
            is PlayerTrackerConfigLoadResult.Success -> DualPlayerTrackerConfigState.Ready(parsed.loaded)
            is PlayerTrackerConfigLoadResult.Failure -> DualPlayerTrackerConfigState.Unavailable
        }
    } catch (_: Exception) {
        DualPlayerTrackerConfigState.Unavailable
    }

@Composable
internal fun DualPlayerTrackingSafetyCard(presentation: DualPlayerCameraPresentation) {
    val tracking = presentation.tracking
    val safe = presentation.gameplayInputAllowed
    var rearmFeedback by remember(tracking?.sessionGeneration, tracking?.rearmChallengeId) {
        mutableStateOf<String?>(null)
    }
    val statusText =
        when {
            !presentation.configAvailable -> "Tracking configuration unavailable. Gameplay remains closed."
            presentation.status == FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE ->
                "Front camera unavailable. Choose the rear camera only in a verified visible and safe setup."
            presentation.status == FrontCameraPreviewStatus.BACK_CAMERA_UNAVAILABLE ->
                "Rear camera unavailable. Switch back to the front camera."
            presentation.status == FrontCameraPreviewStatus.BIND_FAILED ->
                "Selected camera failed to bind. Gameplay remains closed."
            presentation.status != FrontCameraPreviewStatus.ACTIVE ->
                "Waiting for the selected camera to become active."
            tracking == null -> "Waiting for two players. P1 is the analysis-left role; P2 is analysis-right."
            tracking.pauseRequired -> "Safety pause: ${tracking.pauseReason}. Keep both players neutral, then re-arm."
            !safe -> "Safety hold: camera evidence is not current. Wait for both roles to re-arm."
            else -> "P1 and P2 roles are active."
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (safe) Color(0xD61B4437) else Color(0xE638263F),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = if (safe) "2P camera tracking ready" else "2P camera setup / safety hold",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                modifier = Modifier.padding(top = 5.dp),
                text = statusText,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.padding(top = 5.dp),
                text = "Candidate tracking thresholds: human QA required; no score is accepted while paused.",
                color = Color(0xFFD6D2E1),
                style = MaterialTheme.typography.labelSmall,
            )
            if (tracking?.pauseRequired == true) {
                Button(
                    modifier = Modifier.padding(top = 10.dp),
                    onClick = {
                        rearmFeedback = if (presentation.requestRearm()) {
                            "Re-arm request accepted. Keep both players neutral until tracking resumes."
                        } else {
                            "Re-arm request was not accepted. Wait for the current safety prompt, then try again."
                        }
                    },
                ) {
                    Text("Set / re-arm P1 + P2")
                }
            }
            rearmFeedback?.let { feedback ->
                Text(
                    modifier = Modifier.padding(top = 6.dp),
                    text = feedback,
                    color = Color(0xFFFFD166),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
