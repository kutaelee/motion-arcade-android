package com.motionarcade.app.fishing

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingPhase
import com.motionarcade.games.fishing.FishingSnapshot
import com.motionarcade.vision.motion.FishingMotionConfig
import com.motionarcade.vision.motion.FishingMotionFrame
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class FishingRecoveryStage {
    NONE,
    WAITING_FOR_CAMERA,
    AWAITING_CONFIRMATION,
    COUNTDOWN,
}

internal data class FishingGameUiState(
    val snapshot: FishingSnapshot,
    val cameraActive: Boolean,
    val detectedPoseCount: Int?,
    val lastInputDetail: String?,
    val recoveryStage: FishingRecoveryStage = FishingRecoveryStage.NONE,
    val recoveryCountdownSeconds: Int? = null,
    val runtimeFailed: Boolean = false,
)

internal class FishingGameViewModel(
    private val savedStateHandle: SavedStateHandle,
    private var motionConfig: FishingMotionConfig,
) : ViewModel() {
    private data class InitialController(
        val controller: FishingGameController,
        val recoveryStage: FishingRecoveryStage,
        val detail: String?,
    )

    private val checkpointWriter = FishingCheckpointMainWriter(savedStateHandle, CHECKPOINT)
    private val initial = loadInitialController()
    private var controller = initial.controller
    private var recoveryCountdownStartedNs: Long? = null
    private var lastMotionGeneration: Long? = null
    private var lastMotionRevision: Long? = null
    private var lastMotionSourceTimestampNs: Long? = null
    private var expectedMotionGeneration: Long? = null
    private var motionAdmissionOpen = false
    private var motionFencePending = true
    private var motionTrackingEstablished = false
    private var latestMotionUsable = false
    private var lastUsableMotionObservedNs: Long? = null
    private var poseLossStartedSourceNs: Long? = null
    private var poseLossStartedObservedNs: Long? = null
    private var poseLossCandidateCancelled = false
    private val mutableUiState = MutableStateFlow(
        controller.toUiState(
            recoveryStage = initial.recoveryStage,
            lastInputDetail = initial.detail,
        ),
    )

    val uiState: StateFlow<FishingGameUiState> = mutableUiState.asStateFlow()

    override fun onCleared() {
        check(
            checkpointWriter.flushAndCloseOnMain() !=
                FishingCheckpointCloseResult.MAIN_THREAD_REQUIRED,
        ) { "FishingGameViewModel must be cleared on Main to preserve its latest checkpoint" }
        super.onCleared()
    }

    init {
        savedStateHandle[MOTION_CONFIG_ID] = motionConfig.configId
        savedStateHandle[MOTION_CONFIG_REVISION] = motionConfig.calibrationRevision
        if (!persistCheckpoint()) {
            mutableUiState.value = mutableUiState.value.copy(
                lastInputDetail = CHECKPOINT_SAVE_FAILED,
            )
        }
    }

    fun onTick(nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        beginPoseLossForStaleHeartbeat(nowNs)
        if (applyPoseLossClock(nowNs)) return
        if (mutableUiState.value.recoveryStage != FishingRecoveryStage.NONE) {
            updateRecoveryReadiness(nowNs)
            if (mutableUiState.value.recoveryStage == FishingRecoveryStage.COUNTDOWN) {
                updateRecoveryCountdown(nowNs)
            }
            return
        }
        val before = controller.snapshot
        val next = runCatching { controller.advanceTo(nowNs) }.getOrNull() ?: return
        val reachedCheckpoint =
            before.phase != next.phase ||
                before.status != next.status ||
                next.phase == FishingPhase.RESULT
        publish(snapshot = next, persist = reachedCheckpoint)
    }

    fun onPrimaryAction(nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        if (mutableUiState.value.recoveryStage != FishingRecoveryStage.NONE) return
        val current = controller.snapshot
        if (current.phase == FishingPhase.RESULT) {
            controller = newController(originNs = nowNs)
            resetMotionAdmissionForNewSession()
            publish(snapshot = controller.snapshot, lastInputDetail = null, persist = true)
            return
        }
        if (current.status == SessionStatus.PAUSED) return
        val type = current.suggestedTouchType() ?: return
        val result = runCatching { controller.submitTouch(type, nowNs) }.getOrNull() ?: return
        publish(
            snapshot = result.snapshot,
            lastInputDetail = when (result) {
                is FishingControllerInputResult.Accepted -> "accepted_${result.event.type.name}"
                is FishingControllerInputResult.Ignored -> result.detail
            },
        )
    }

    fun onMotionFrame(
        frame: FishingMotionFrame,
        observedAtNs: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        require(observedAtNs >= 0L)
        if (!motionAdmissionOpen || frame.sessionGeneration != expectedMotionGeneration) return
        val previousGeneration = lastMotionGeneration
        val previousRevision = lastMotionRevision
        if (
            previousGeneration != null &&
            (frame.sessionGeneration < previousGeneration ||
                (frame.sessionGeneration == previousGeneration &&
                    previousRevision != null && frame.revision <= previousRevision))
        ) {
            return
        }
        if (
            previousGeneration == null ||
            frame.sessionGeneration != previousGeneration ||
            previousRevision == null ||
            previousRevision == Long.MAX_VALUE ||
            frame.revision != previousRevision + 1L
        ) {
            motionFencePending = true
        }
        val previousSourceTimestampNs = lastMotionSourceTimestampNs
        if (
            previousSourceTimestampNs != null &&
            frame.sourceTimestampNs <= previousSourceTimestampNs
        ) {
            motionFencePending = true
        } else {
            lastMotionSourceTimestampNs = frame.sourceTimestampNs
        }
        lastMotionGeneration = frame.sessionGeneration
        lastMotionRevision = frame.revision
        val frameUsable =
            frame.usableForSolo &&
                frame.configId == motionConfig.configId &&
                frame.calibrationRevision == motionConfig.calibrationRevision
        latestMotionUsable = frameUsable
        if (frameUsable) lastUsableMotionObservedNs = observedAtNs
        val observedLossDuration = poseLossStartedObservedNs?.let { startedNs ->
            if (observedAtNs >= startedNs) observedAtNs - startedNs else 0L
        }
        if (observedLossDuration != null && observedLossDuration > POSE_LOST_PAUSE_NS) {
            pauseForPoseLoss()
            return
        }
        if (mutableUiState.value.recoveryStage != FishingRecoveryStage.NONE) {
            motionFencePending = true
            updateRecoveryReadiness(observedAtNs)
            return
        }
        if (!frameUsable) {
            if (motionTrackingEstablished) {
                beginPoseLoss(frame.sourceTimestampNs, observedAtNs)
                val sourceElapsed = poseLossStartedSourceNs?.let { start ->
                    if (frame.sourceTimestampNs >= start) frame.sourceTimestampNs - start else 0L
                } ?: 0L
                if (sourceElapsed > POSE_LOST_PAUSE_NS) {
                    pauseForPoseLoss()
                    return
                }
                if (
                    sourceElapsed >= POSE_LOSS_CANCEL_NS &&
                    !poseLossCandidateCancelled
                ) {
                    poseLossCandidateCancelled = true
                    motionFencePending = true
                }
            }
            applyPendingMotionFence(frame.sourceTimestampNs)
            return
        }
        motionTrackingEstablished = true
        val sourceLossDuration = poseLossStartedSourceNs?.let { start ->
            if (frame.sourceTimestampNs >= start) frame.sourceTimestampNs - start else 0L
        }
        if (sourceLossDuration != null && sourceLossDuration > POSE_LOST_PAUSE_NS) {
            pauseForPoseLoss()
            return
        }
        if (
            sourceLossDuration != null &&
            sourceLossDuration >= POSE_LOSS_CANCEL_NS &&
            !poseLossCandidateCancelled
        ) {
            poseLossCandidateCancelled = true
        }
        if (poseLossCandidateCancelled) motionFencePending = true
        clearPoseLossClock()
        if (motionFencePending) {
            applyPendingMotionFence(frame.sourceTimestampNs)
            return
        }
        val before = controller.snapshot
        if (before.paused || before.phase == FishingPhase.RESULT) {
            motionFencePending = true
            return
        }
        val results = runCatching { controller.submitMotionFrame(frame.samples) }.getOrNull() ?: return
        if (results.isEmpty()) return
        val accepted = results.filterIsInstance<FishingControllerInputResult.Accepted>()
        val detail = accepted.lastOrNull()?.let { "accepted_${it.event.type.name}" }
            ?: (results.lastOrNull() as? FishingControllerInputResult.Ignored)?.detail
        val next = controller.snapshot
        publish(
            snapshot = next,
            lastInputDetail = detail,
            persist = accepted.isNotEmpty() || before.phase != next.phase,
        )
    }

    fun onPauseToggle(nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        if (mutableUiState.value.recoveryStage != FishingRecoveryStage.NONE) {
            updateRecoveryReadiness(nowNs)
        }
        when (mutableUiState.value.recoveryStage) {
            FishingRecoveryStage.WAITING_FOR_CAMERA,
            FishingRecoveryStage.COUNTDOWN,
            -> return
            FishingRecoveryStage.AWAITING_CONFIRMATION -> {
                recoveryCountdownStartedNs = nowNs
                setRecovery(FishingRecoveryStage.COUNTDOWN, RECOVERY_COUNTDOWN_SECONDS)
                return
            }
            FishingRecoveryStage.NONE -> Unit
        }

        val current = controller.snapshot
        if (current.status == SessionStatus.PAUSED) {
            if (current.pauseReason == PauseReason.USER && controller.resume(nowNs)) {
                closeMotionAdmissionForRebind()
                clearPoseLossClock()
                publish(snapshot = controller.snapshot, persist = true)
            }
        } else {
            motionFencePending = true
            clearPoseLossClock()
            publish(snapshot = controller.pause(PauseReason.USER), persist = true)
        }
    }

    fun onBackground() {
        val current = controller.snapshot
        if (current.phase == FishingPhase.RESULT || current.pauseReason == PauseReason.USER) return
        val paused = if (current.status == SessionStatus.PAUSED) {
            current
        } else {
            controller.pause(PauseReason.APP_BACKGROUND)
        }
        recoveryCountdownStartedNs = null
        motionFencePending = true
        latestMotionUsable = false
        lastUsableMotionObservedNs = null
        clearPoseLossClock()
        setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
        publish(snapshot = paused, persist = true)
    }

    fun onForeground(nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        require(nowNs >= 0L)
        val current = controller.snapshot
        if (
            current.status == SessionStatus.PAUSED &&
            current.pauseReason == PauseReason.APP_BACKGROUND &&
            mutableUiState.value.recoveryStage == FishingRecoveryStage.NONE
        ) {
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
        }
    }

    fun onCameraState(
        active: Boolean,
        poseCount: Int?,
        observedAtNs: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        require(observedAtNs >= 0L)
        mutableUiState.value = mutableUiState.value.copy(
            cameraActive = active,
            detectedPoseCount = poseCount,
        )
        if (
            mutableUiState.value.recoveryStage == FishingRecoveryStage.NONE &&
            motionTrackingEstablished &&
            (!active || poseCount != 1)
        ) {
            beginPoseLoss(sourceTimestampNs = null, observedAtNs = observedAtNs)
        }
        if (!active || poseCount != 1) latestMotionUsable = false
        updateRecoveryReadiness(observedAtNs)
    }

    fun onMotionBindingStarted(
        sessionId: String,
        eventTimelineEpoch: Long,
        sessionGeneration: Long,
    ): Boolean {
        if (sessionGeneration <= 0L) return false
        val current = controller.snapshot
        if (
            current.sessionId != sessionId ||
            current.eventTimelineEpoch != eventTimelineEpoch
        ) {
            return false
        }
        expectedMotionGeneration = sessionGeneration
        motionAdmissionOpen = true
        lastMotionGeneration = null
        lastMotionRevision = null
        lastMotionSourceTimestampNs = null
        latestMotionUsable = false
        lastUsableMotionObservedNs = null
        motionFencePending = true
        if (mutableUiState.value.runtimeFailed) {
            mutableUiState.value = mutableUiState.value.copy(
                runtimeFailed = false,
                lastInputDetail = RUNTIME_RESTARTED_DETAIL,
            )
        }
        return true
    }

    fun onMotionBindingClosed() {
        closeMotionAdmissionForRebind()
    }

    fun onCameraRecalibrated(config: FishingMotionConfig) {
        require(config.configId == motionConfig.configId)
        // Bind effects may restart before Compose observes the first migration. Duplicate or stale
        // revisions are already fenced by the active config and must be harmless to the worker.
        if (config.calibrationRevision <= motionConfig.calibrationRevision) return
        closeMotionAdmissionForRebind()
        clearPoseLossClock()
        motionConfig = config
        savedStateHandle[MOTION_CONFIG_REVISION] = config.calibrationRevision
        recoveryCountdownStartedNs = null
        val paused = controller.recalibrate(config)
        setRecovery(
            if (paused.phase == FishingPhase.RESULT) {
                FishingRecoveryStage.NONE
            } else {
                FishingRecoveryStage.WAITING_FOR_CAMERA
            },
            null,
        )
        publish(snapshot = paused, lastInputDetail = CAMERA_RECALIBRATED_DETAIL, persist = true)
    }

    fun onRuntimeQueueOverflow() {
        pauseForRuntimeFailure(RUNTIME_QUEUE_OVERFLOW_DETAIL, terminal = false)
    }

    fun onRuntimeWorkerFailure() {
        pauseForRuntimeFailure(RUNTIME_WORKER_FAILURE_DETAIL, terminal = true)
    }

    private fun pauseForRuntimeFailure(detail: String, terminal: Boolean) {
        val current = controller.snapshot
        if (current.phase == FishingPhase.RESULT) {
            if (terminal) {
                mutableUiState.value = mutableUiState.value.copy(
                    runtimeFailed = true,
                    lastInputDetail = detail,
                )
            }
            return
        }
        val paused = if (current.paused) {
            current
        } else {
            controller.pause(PauseReason.EVENT_QUEUE_OVERFLOW)
        }
        recoveryCountdownStartedNs = null
        motionFencePending = true
        latestMotionUsable = false
        lastUsableMotionObservedNs = null
        if (paused.pauseReason != PauseReason.USER) {
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
        }
        publish(
            snapshot = paused,
            lastInputDetail = detail,
            persist = true,
        )
        if (terminal) {
            mutableUiState.value = mutableUiState.value.copy(runtimeFailed = true)
        }
    }

    private fun updateRecoveryReadiness(nowNs: Long) {
        val currentStage = mutableUiState.value.recoveryStage
        if (currentStage == FishingRecoveryStage.NONE) return
        val state = mutableUiState.value
        val recoveryReady =
            state.cameraActive &&
                state.detectedPoseCount == 1 &&
                motionFrameIsFresh(nowNs)
        if (recoveryReady && currentStage == FishingRecoveryStage.WAITING_FOR_CAMERA) {
            setRecovery(FishingRecoveryStage.AWAITING_CONFIRMATION, null)
        } else if (!recoveryReady && currentStage != FishingRecoveryStage.WAITING_FOR_CAMERA) {
            recoveryCountdownStartedNs = null
            motionFencePending = true
            latestMotionUsable = false
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
        }
    }

    private fun motionFrameIsFresh(nowNs: Long): Boolean {
        if (!motionAdmissionOpen || !latestMotionUsable) return false
        val lastObservedNs = lastUsableMotionObservedNs ?: return false
        if (nowNs < lastObservedNs) return false
        return nowNs - lastObservedNs < POSE_LOSS_CANCEL_NS
    }

    private fun beginPoseLoss(sourceTimestampNs: Long?, observedAtNs: Long) {
        if (poseLossStartedObservedNs == null) poseLossStartedObservedNs = observedAtNs
        if (sourceTimestampNs != null && poseLossStartedSourceNs == null) {
            poseLossStartedSourceNs = sourceTimestampNs
        }
    }

    private fun beginPoseLossForStaleHeartbeat(nowNs: Long) {
        if (
            mutableUiState.value.recoveryStage != FishingRecoveryStage.NONE ||
            !motionTrackingEstablished ||
            !motionAdmissionOpen ||
            !latestMotionUsable ||
            poseLossStartedObservedNs != null
        ) {
            return
        }
        val lastObservedNs = lastUsableMotionObservedNs ?: return
        if (nowNs < lastObservedNs || nowNs - lastObservedNs < POSE_LOSS_CANCEL_NS) return
        // No observation at all is also a continuity loss. Anchor it to the last known-good
        // heartbeat so exact 400 ms cancellation and strict >1.2 s pause remain identical to an
        // explicit unusable frame.
        beginPoseLoss(sourceTimestampNs = null, observedAtNs = lastObservedNs)
        latestMotionUsable = false
    }

    private fun applyPoseLossClock(nowNs: Long): Boolean {
        if (
            mutableUiState.value.recoveryStage != FishingRecoveryStage.NONE ||
            !motionTrackingEstablished
        ) {
            return false
        }
        val startedNs = poseLossStartedObservedNs ?: return false
        if (nowNs < startedNs) {
            clearPoseLossClock()
            motionFencePending = true
            return false
        }
        val elapsedNs = nowNs - startedNs
        if (elapsedNs > POSE_LOST_PAUSE_NS) {
            pauseForPoseLoss()
            return true
        }
        if (elapsedNs >= POSE_LOSS_CANCEL_NS && !poseLossCandidateCancelled) {
            poseLossCandidateCancelled = true
            motionFencePending = true
            applyPendingMotionFence(lastMotionSourceTimestampNs ?: nowNs)
        }
        return false
    }

    private fun applyPendingMotionFence(timestampNs: Long) {
        if (motionFencePending && controller.fenceMotionContinuity(timestampNs)) {
            motionFencePending = false
        }
    }

    private fun clearPoseLossClock() {
        poseLossStartedSourceNs = null
        poseLossStartedObservedNs = null
        poseLossCandidateCancelled = false
    }

    private fun pauseForPoseLoss() {
        val current = controller.snapshot
        if (!current.paused && current.phase != FishingPhase.RESULT) {
            val paused = controller.pause(PauseReason.POSE_LOST)
            recoveryCountdownStartedNs = null
            latestMotionUsable = false
            motionFencePending = true
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
            publish(snapshot = paused, lastInputDetail = POSE_LOST_DETAIL, persist = true)
        }
        clearPoseLossClock()
    }

    private fun resetMotionAdmissionForNewSession() {
        closeMotionAdmissionForRebind()
        motionTrackingEstablished = false
        clearPoseLossClock()
    }

    private fun closeMotionAdmissionForRebind() {
        motionAdmissionOpen = false
        expectedMotionGeneration = null
        lastMotionGeneration = null
        lastMotionRevision = null
        lastMotionSourceTimestampNs = null
        latestMotionUsable = false
        lastUsableMotionObservedNs = null
        motionFencePending = true
    }

    private fun updateRecoveryCountdown(nowNs: Long) {
        if (mutableUiState.value.recoveryStage != FishingRecoveryStage.COUNTDOWN) return
        val startedNs = recoveryCountdownStartedNs ?: run {
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
            return
        }
        if (nowNs < startedNs) {
            recoveryCountdownStartedNs = null
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
            return
        }
        val elapsedNs = nowNs - startedNs
        if (elapsedNs < RECOVERY_COUNTDOWN_NS) {
            val remainingNs = RECOVERY_COUNTDOWN_NS - elapsedNs
            val seconds = ((remainingNs + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND).toInt()
            setRecovery(FishingRecoveryStage.COUNTDOWN, seconds)
            return
        }
        val cameraReady =
            mutableUiState.value.cameraActive &&
                mutableUiState.value.detectedPoseCount == 1 &&
                motionFrameIsFresh(nowNs)
        if (cameraReady && controller.resume(nowNs)) {
            recoveryCountdownStartedNs = null
            closeMotionAdmissionForRebind()
            clearPoseLossClock()
            setRecovery(FishingRecoveryStage.NONE, null)
            publish(snapshot = controller.snapshot, persist = true)
        } else {
            recoveryCountdownStartedNs = null
            setRecovery(FishingRecoveryStage.WAITING_FOR_CAMERA, null)
        }
    }

    private fun loadInitialController(): InitialController {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val encoded: ByteArray? = savedStateHandle[CHECKPOINT]
        val savedConfigId: String? = savedStateHandle[MOTION_CONFIG_ID]
        val savedConfigRevision: Int? = savedStateHandle[MOTION_CONFIG_REVISION]
        if (encoded != null) {
            val checkpoint = FishingCheckpointCodec.decode(encoded)
            if (
                checkpoint != null &&
                (savedConfigId != motionConfig.configId ||
                    savedConfigRevision != motionConfig.calibrationRevision ||
                    checkpoint.calibrationRevision != motionConfig.calibrationRevision)
            ) {
                quarantineRejectedCheckpoint(encoded)
                return InitialController(
                    controller = newController(originNs = nowNs),
                    recoveryStage = FishingRecoveryStage.NONE,
                    detail = CHECKPOINT_CONFIG_REJECTED,
                )
            }
            val restored = checkpoint?.let { safeCheckpoint ->
                runCatching {
                    FishingGameController.restore(
                        checkpoint = safeCheckpoint,
                        currentClockNs = nowNs,
                        gestureDefinitions = motionConfig.gestureDefinitions,
                    )
                }.getOrNull()
            }
            if (restored != null) {
                val stage = if (restored.snapshot.phase == FishingPhase.RESULT) {
                    FishingRecoveryStage.NONE
                } else {
                    FishingRecoveryStage.WAITING_FOR_CAMERA
                }
                return InitialController(restored, stage, CHECKPOINT_RESTORED)
            }
            quarantineRejectedCheckpoint(encoded)
        }
        return InitialController(
            controller = newController(originNs = nowNs),
            recoveryStage = FishingRecoveryStage.NONE,
            detail = if (encoded == null) null else CHECKPOINT_REJECTED,
        )
    }

    private fun quarantineRejectedCheckpoint(encoded: ByteArray) {
        if (encoded.size <= FishingCheckpointCodec.MAX_ENCODED_BYTES) {
            savedStateHandle[REJECTED_CHECKPOINT] = encoded.copyOf()
        }
        savedStateHandle.remove<ByteArray>(CHECKPOINT)
    }

    private fun newController(originNs: Long): FishingGameController {
        val sessionId = "fishing-${UUID.randomUUID()}"
        val seed = originNs
        return FishingGameController.start(
            sessionId = sessionId,
            seed = seed,
            calibrationRevision = motionConfig.calibrationRevision,
            originNs = originNs,
            gestureDefinitions = motionConfig.gestureDefinitions,
        )
    }

    private fun FishingGameController.toUiState(
        recoveryStage: FishingRecoveryStage,
        lastInputDetail: String?,
    ): FishingGameUiState = FishingGameUiState(
        snapshot = snapshot,
        cameraActive = false,
        detectedPoseCount = null,
        lastInputDetail = lastInputDetail,
        recoveryStage = recoveryStage,
    )

    private fun setRecovery(stage: FishingRecoveryStage, countdownSeconds: Int?) {
        mutableUiState.value = mutableUiState.value.copy(
            recoveryStage = stage,
            recoveryCountdownSeconds = countdownSeconds,
        )
    }

    private fun publish(
        snapshot: FishingSnapshot,
        lastInputDetail: String? = mutableUiState.value.lastInputDetail,
        persist: Boolean = false,
    ) {
        val current = mutableUiState.value
        if (
            !snapshot.hasSameRenderStateAs(current.snapshot) ||
            lastInputDetail != current.lastInputDetail
        ) {
            mutableUiState.value = current.copy(
                snapshot = snapshot,
                lastInputDetail = lastInputDetail,
            )
        }
        if (persist && !persistCheckpoint()) {
            mutableUiState.value = mutableUiState.value.copy(
                lastInputDetail = CHECKPOINT_SAVE_FAILED,
            )
        }
    }

    private fun persistCheckpoint(): Boolean {
        val encoded = runCatching {
            FishingCheckpointCodec.encode(controller.checkpoint())
        }.getOrNull() ?: return false
        return checkpointWriter.publish(encoded).accepted
    }

    private fun FishingSnapshot.hasSameRenderStateAs(other: FishingSnapshot): Boolean =
        sessionId == other.sessionId &&
            eventTimelineEpoch == other.eventTimelineEpoch &&
            status == other.status &&
            pauseReason == other.pauseReason &&
            phase == other.phase &&
            fish == other.fish &&
            reelCycles == other.reelCycles &&
            tension == other.tension &&
            score == other.score &&
            outcome == other.outcome

    private companion object {
        const val CHECKPOINT = "fishing.checkpoint.v1"
        const val REJECTED_CHECKPOINT = "fishing.checkpoint.rejected.v1"
        const val MOTION_CONFIG_ID = "fishing.motion-config-id.v1"
        const val MOTION_CONFIG_REVISION = "fishing.motion-config-revision.v1"
        const val RECOVERY_COUNTDOWN_SECONDS = 3
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val RECOVERY_COUNTDOWN_NS = RECOVERY_COUNTDOWN_SECONDS * NANOS_PER_SECOND
        const val POSE_LOSS_CANCEL_NS = 400_000_000L
        const val POSE_LOST_PAUSE_NS = 1_200_000_000L
        const val CHECKPOINT_RESTORED = "checkpoint_restored_waiting_for_rearm"
        const val CHECKPOINT_REJECTED = "checkpoint_rejected_new_session"
        const val CHECKPOINT_CONFIG_REJECTED = "checkpoint_config_changed_new_session"
        const val CHECKPOINT_SAVE_FAILED = "checkpoint_save_failed"
        const val POSE_LOST_DETAIL = "pose_lost_paused_waiting_for_rearm"
        const val RUNTIME_QUEUE_OVERFLOW_DETAIL = "runtime_queue_overflow_paused"
        const val RUNTIME_WORKER_FAILURE_DETAIL = "runtime_worker_failure_paused"
        const val RUNTIME_RESTARTED_DETAIL = "runtime_restarted_camera_recovery_required"
        const val CAMERA_RECALIBRATED_DETAIL = "camera_recalibrated_waiting_for_rearm"
    }
}
