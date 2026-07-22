package com.motionarcade.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.vision.camera.CameraFrameMetadataSink
import com.motionarcade.vision.camera.CameraLensSelection
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.camera.FrontCameraPreviewSurface
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfig
import com.motionarcade.vision.motion.DualPlayerCombatMotionConfigs
import com.motionarcade.vision.motion.DualPlayerCombatProfile
import com.motionarcade.vision.motion.SoloCombatMotionFrame
import com.motionarcade.vision.motion.SoloCombatMotionFrameSink
import com.motionarcade.vision.motion.supportsSoloCombat
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import kotlinx.coroutines.delay

internal class SoloCombatCameraPresentation(
    val status: FrontCameraPreviewStatus,
    val inference: LivePoseInferenceSnapshot,
    val motionConfig: DualPlayerCombatMotionConfig,
    private val admissionGate: SoloCombatGameplayAdmissionGate,
    private val motionDispatcher: SoloCombatMotionDispatcher,
) {
    val gameplayInputAllowed: Boolean get() = admissionGate.isGameplayInputAllowed()

    fun currentSafetyPauseReason(): PauseReason = admissionGate.currentSafetyPauseReason()

    fun <T> runWhenGameplayAllowed(action: () -> T): T? = admissionGate.runWhenAllowed(action)

    fun registerSafetyStop(onSafetyStop: (PauseReason) -> Unit): () -> Unit =
        admissionGate.registerSafetyStop(onSafetyStop)

    fun registerCombatMotionConsumer(consumer: SoloCombatMotionConsumer): () -> Unit {
        val unregisterConsumer = motionDispatcher.register(consumer)
        val unregisterSafety = admissionGate.registerSafetyStop {
            consumer.onMotionContinuityFence(motionDispatcher.latestTimestampNs())
        }
        return {
            unregisterSafety()
            unregisterConsumer()
        }
    }
}

@Composable
internal fun SoloCombatCameraRoute(
    profile: DualPlayerCombatProfile,
    lensSelection: CameraLensSelection,
    lensBindEpoch: Int,
    calibrationRevision: Int,
    onLensSelectionRequested: (CameraLensSelection) -> Unit,
    onPermissionMissing: () -> Unit,
    content: @Composable (SoloCombatCameraPresentation) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surface = remember(context) { FrontCameraPreviewSurface(context) }
    val config = remember(profile, calibrationRevision) {
        soloCombatMotionConfig(profile, calibrationRevision)
    }
    val gate = remember(config) { SoloCombatGameplayAdmissionGate(configAvailable = true) }
    val dispatcher = remember(gate) { SoloCombatMotionDispatcher() }
    var status by remember { mutableStateOf(FrontCameraPreviewStatus.IDLE) }
    var inference by remember(gate) { mutableStateOf(LivePoseInferenceSnapshot.idle()) }
    var autoRecovery by remember(surface, lifecycleOwner, config, lensSelection, lensBindEpoch) {
        mutableStateOf(LivePoseAutoRecoveryState())
    }
    var bindEpoch by remember(lifecycleOwner) {
        mutableIntStateOf(
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) 1 else 0,
        )
    }

    DisposableEffect(lifecycleOwner, gate) {
        val observer = DualPlayerCameraLifecycleRebindObserver(
            initiallyResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            onResumeRebind = { bindEpoch = Math.incrementExact(bindEpoch) },
            onInactive = {
                gate.invalidateForRebind()
                autoRecovery = LivePoseAutoRecoveryState()
                inference = LivePoseInferenceSnapshot.idle()
                status = FrontCameraPreviewStatus.IDLE
            },
        )
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(surface, lifecycleOwner, config, gate, bindEpoch, lensSelection, lensBindEpoch) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }
        gate.invalidateForRebind()
        inference = LivePoseInferenceSnapshot.idle()
        status = FrontCameraPreviewStatus.IDLE
        surface.bind(
            lifecycleOwner = lifecycleOwner,
            lensSelection = lensSelection,
            metadataSink = CameraFrameMetadataSink.NONE,
            inferenceSink = LivePoseInferenceSink { next ->
                if (gate.onInference(next)) inference = next
            },
            soloCombatMotionConfig = config,
            soloCombatMotionSink = SoloCombatMotionFrameSink { frame ->
                if (!frame.matches(config)) {
                    if (gate.onSoloCombatMotion(frame.sessionGeneration, frame.revision, false)) {
                        dispatcher.onMotionContinuityFence(frame.sourceTimestampNs)
                    }
                    return@SoloCombatMotionFrameSink
                }
                if (!gate.onSoloCombatMotion(frame.sessionGeneration, frame.revision, frame.usableForSolo)) {
                    return@SoloCombatMotionFrameSink
                }
                if (!frame.usableForSolo) {
                    dispatcher.onMotionContinuityFence(frame.sourceTimestampNs)
                    return@SoloCombatMotionFrameSink
                }
                gate.runWhenAllowed(frame.sessionGeneration) {
                    dispatcher.onSafeMotionFrame(frame)
                }
            },
            onSoloCombatBindingStarted = { generation ->
                gate.onBindingStarted(generation)
                inference = LivePoseInferenceSnapshot.idle(generation)
            },
            onSoloCombatCallbackError = { generation ->
                if (gate.onCallbackError(generation)) {
                    dispatcher.onMotionContinuityFence(dispatcher.latestTimestampNs())
                }
            },
            onStatus = { next ->
                gate.onPreviewStatus(next)
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

    val presentation = SoloCombatCameraPresentation(
        status = status,
        inference = inference,
        motionConfig = config,
        admissionGate = gate,
        motionDispatcher = dispatcher,
    )
    PoseRecognitionFailureToast(
        status = status,
        inference = inference,
        expectedPlayers = 1,
        automaticRecoveryScheduled = autoRecovery.isRecovering(inference),
    )

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "One-player ${soloCombatProfileLabel(profile)} camera preview"
                },
            factory = { surface },
        )
        content(presentation)
        CameraLensControl(
            lensSelection = lensSelection,
            onLensSelectionRequested = onLensSelectionRequested,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
    }
}

internal class SoloCombatMotionDispatcher {
    private val lock = Any()
    private val consumers = linkedMapOf<Long, SoloCombatMotionConsumer>()
    private var nextId = 0L
    private var latestTimestampNs = 0L

    fun register(consumer: SoloCombatMotionConsumer): () -> Unit {
        val id = synchronized(lock) {
            nextId += 1L
            nextId.also { consumers[it] = consumer }
        }
        return { synchronized(lock) { consumers.remove(id) } }
    }

    fun latestTimestampNs(): Long = synchronized(lock) { latestTimestampNs }

    fun onSafeMotionFrame(frame: SoloCombatMotionFrame) {
        val targets = synchronized(lock) {
            latestTimestampNs = maxOf(latestTimestampNs, frame.sourceTimestampNs)
            consumers.values.toList()
        }
        targets.forEach { consumer -> runCatching { consumer.onSafeMotionFrame(frame) } }
    }

    fun onMotionContinuityFence(timestampNs: Long) {
        val targets = synchronized(lock) {
            latestTimestampNs = maxOf(latestTimestampNs, timestampNs)
            consumers.values.toList()
        }
        targets.forEach { consumer -> runCatching { consumer.onMotionContinuityFence(latestTimestampNs()) } }
    }
}

@Composable
internal fun SoloCombatSafetyCard(presentation: SoloCombatCameraPresentation) {
    val safe = presentation.gameplayInputAllowed
    val poseCount = presentation.inference.poseCount
    val profileLabel = soloCombatProfileLabel(presentation.motionConfig.profile)
    val statusText = when {
        presentation.status == FrontCameraPreviewStatus.FRONT_CAMERA_UNAVAILABLE ->
            "Front camera unavailable. Rear camera requires a visible and safe QA setup."
        presentation.status == FrontCameraPreviewStatus.BACK_CAMERA_UNAVAILABLE ->
            "Rear camera unavailable. Switch back to the front camera."
        presentation.status == FrontCameraPreviewStatus.BIND_FAILED ->
            "Selected camera failed to bind. Gameplay remains closed."
        presentation.status != FrontCameraPreviewStatus.ACTIVE -> "Waiting for the selected camera."
        poseCount == null -> "Waiting for the first current pose result."
        poseCount == 0 -> "Step into view. Exactly one player is required."
        poseCount > 1 -> "Safety pause: a second person is visible. Keep exactly one player in frame."
        !safe -> "Safety hold: keep one player visible while current motion evidence is checked."
        else -> soloCombatActiveStatusText(presentation.motionConfig.profile)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "One-player $profileLabel camera ${if (safe) "ready" else "safety hold"}"
            },
        colors = CardDefaults.cardColors(
            containerColor = if (safe) Color(0xD61B4437) else Color(0xE638263F),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (safe) "1P camera ready" else "1P camera safety hold",
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
                text = "Candidate gesture thresholds require human device QA; paused input never scores.",
                color = Color(0xFFD6D2E1),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun SoloCombatMotionFrame.matches(config: DualPlayerCombatMotionConfig): Boolean =
    profile == config.profile &&
        configId == config.configId &&
        calibrationRevision == config.calibrationRevision

internal fun soloCombatMotionConfig(
    profile: DualPlayerCombatProfile,
    calibrationRevision: Int = 0,
): DualPlayerCombatMotionConfig {
    require(profile.supportsSoloCombat) { "Solo combat supports only BOXING or MONSTER" }
    return when (profile) {
        DualPlayerCombatProfile.BOXING -> DualPlayerCombatMotionConfigs.boxing(calibrationRevision)
        DualPlayerCombatProfile.MONSTER -> DualPlayerCombatMotionConfigs.monster(calibrationRevision)
        DualPlayerCombatProfile.FISHING -> error("Fishing cannot use solo combat")
    }
}

internal fun soloCombatProfileLabel(profile: DualPlayerCombatProfile): String = when (profile) {
    DualPlayerCombatProfile.BOXING -> "boxing"
    DualPlayerCombatProfile.MONSTER -> "monster raid"
    DualPlayerCombatProfile.FISHING -> error("Fishing cannot use solo combat")
}

internal fun soloCombatActiveStatusText(profile: DualPlayerCombatProfile): String = when (profile) {
    DualPlayerCombatProfile.BOXING ->
        "One-player boxing motion input is active. The deterministic AI opponent follows a fixed attack cadence."
    DualPlayerCombatProfile.MONSTER ->
        "One-player motion input is active. Your AI partner assists but does not replace core actions."
    DualPlayerCombatProfile.FISHING -> error("Fishing cannot use solo combat presentation")
}
