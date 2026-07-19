package com.motionarcade.app

import com.motionarcade.core.contract.PauseReason
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot
import com.motionarcade.vision.tracking.IdentityPauseReason

/**
 * Synchronous, generation-fenced admission for the two-player simulation.
 *
 * Compose state is deliberately not the authority here: state delivery and recomposition can lag
 * a camera safety edge. Calls which advance or mutate a game run under this gate's lock, and a
 * close edge pauses registered games before another guarded action can enter.
 */
internal class DualPlayerGameplayAdmissionGate(
    private var configAvailable: Boolean,
) {
    private val lock = Any()
    private val safetyStops = linkedMapOf<Long, (PauseReason) -> Unit>()
    private var nextSafetyStopId = 0L

    private var expectedGeneration: Long? = null
    private var previewStatus = FrontCameraPreviewStatus.IDLE
    private var inferenceReady = false
    // LivePoseInferenceSnapshot.revision is a UI-snapshot clock. callbackCount advances exactly
    // once per accepted raw result and therefore shares the tracker/combat observation clock.
    private var inferenceSafeObservationRevision: Long? = null
    private var trackingSafeRevision: Long? = null
    private var trackingPauseReason = IdentityPauseReason.REARM_REQUIRED
    private var combatMotionSafeRevision: Long? = null
    // This is deliberately independent of [inputAllowed]. A healthy-but-incoherent revision
    // closes input temporarily; if safety becomes invalid while that soft fence is closed, the
    // running game still needs exactly one hard pause callback. It is re-armed only by a fully
    // coherent open, so staggered tracker/combat recovery cannot emit another pause callback.
    private var hardSafetyStopArmed = false
    private var inputAllowed = false
    private var pauseReason = PauseReason.POSE_LOST

    fun setConfigAvailable(available: Boolean) = synchronized(lock) {
        configAvailable = available
        if (!available) pauseReason = PauseReason.POSE_LOST
        recomputeLocked()
    }

    /** Closes the previous generation before a bind request can begin. */
    fun invalidateForRebind() = synchronized(lock) {
        expectedGeneration = null
        previewStatus = FrontCameraPreviewStatus.IDLE
        inferenceReady = false
        inferenceSafeObservationRevision = null
        trackingSafeRevision = null
        trackingPauseReason = IdentityPauseReason.REARM_REQUIRED
        combatMotionSafeRevision = null
        pauseReason = PauseReason.POSE_LOST
        recomputeLocked()
    }

    /** Called synchronously by the camera controller once its new generation is reserved. */
    fun onBindingStarted(generation: Long) = synchronized(lock) {
        require(generation > 0L) { "Camera generation must be positive" }
        expectedGeneration = generation
        previewStatus = FrontCameraPreviewStatus.STARTING
        inferenceReady = false
        inferenceSafeObservationRevision = null
        trackingSafeRevision = null
        trackingPauseReason = IdentityPauseReason.REARM_REQUIRED
        combatMotionSafeRevision = null
        pauseReason = PauseReason.POSE_LOST
        recomputeLocked()
    }

    fun onPreviewStatus(status: FrontCameraPreviewStatus) = synchronized(lock) {
        previewStatus = status
        if (status != FrontCameraPreviewStatus.ACTIVE) pauseReason = PauseReason.POSE_LOST
        recomputeLocked()
    }

    /** Returns false for a stale delivery, which the caller must not surface to Compose. */
    fun onInference(snapshot: LivePoseInferenceSnapshot): Boolean = synchronized(lock) {
        if (snapshot.sessionGeneration != expectedGeneration) return false
        inferenceReady =
            snapshot.phase == LivePoseInferencePhase.ACTIVE && snapshot.poseCount == 2
        inferenceSafeObservationRevision = if (inferenceReady) snapshot.callbackCount else null
        if (!inferenceReady) pauseReason = PauseReason.POSE_LOST
        recomputeLocked()
        true
    }

    /** Returns false for a stale delivery, which the caller must not surface to Compose. */
    fun onTracking(
        generation: Long,
        revision: Long,
        pauseRequired: Boolean,
        reason: IdentityPauseReason,
    ): Boolean = synchronized(lock) {
        if (generation != expectedGeneration) return false
        if (revision <= 0L) return false
        if (pauseRequired != (reason != IdentityPauseReason.NONE)) return false
        trackingPauseReason = reason
        trackingSafeRevision = if (!pauseRequired) revision else null
        if (pauseRequired) pauseReason = reason.toGamePauseReason()
        recomputeLocked()
        true
    }

    /**
     * The role summary and derived gesture frame are delivered independently. Do not let a safe
     * prior frame keep gameplay open after the current frame has become unusable.
     */
    fun onCombatMotion(
        generation: Long,
        revision: Long,
        usableForDual: Boolean,
    ): Boolean = synchronized(lock) {
        if (generation != expectedGeneration) return false
        if (revision <= 0L) return false
        combatMotionSafeRevision = if (usableForDual) revision else null
        if (!usableForDual) pauseReason = PauseReason.POSE_LOST
        recomputeLocked()
        true
    }

    fun isGameplayInputAllowed(): Boolean = synchronized(lock) { inputAllowed }

    fun currentSafetyPauseReason(): PauseReason = synchronized(lock) { pauseReason }

    /** Runs [action] only while the lock still proves current generation, inference, and roles. */
    fun <T> runWhenAllowed(action: () -> T): T? = synchronized(lock) {
        if (!inputAllowed) null else action()
    }

    /** Binds a camera-derived action to the exact generation that produced it. */
    fun <T> runWhenAllowed(
        generation: Long,
        action: () -> T,
    ): T? = synchronized(lock) {
        if (!inputAllowed || generation != expectedGeneration) null else action()
    }

    /**
     * Game sessions register once. A loss of safety evidence invokes [onSafetyStop] under the
     * same lock that excludes input/tick execution, eliminating the UI-recomposition timing
     * window. A safe-but-not-yet-coherent revision only blocks the current action; it must not
     * pause a healthy game while its companion tracker/combat deliveries catch up.
     */
    fun registerSafetyStop(onSafetyStop: (PauseReason) -> Unit): () -> Unit {
        val id = synchronized(lock) {
            nextSafetyStopId += 1L
            nextSafetyStopId.also { safetyStops[it] = onSafetyStop }
        }
        return { synchronized(lock) { safetyStops.remove(id) } }
    }

    private fun recomputeLocked() {
        val safetyEvidenceValid =
            configAvailable &&
                expectedGeneration != null &&
                previewStatus == FrontCameraPreviewStatus.ACTIVE &&
                inferenceReady &&
                trackingPauseReason == IdentityPauseReason.NONE &&
                trackingSafeRevision != null &&
                combatMotionSafeRevision != null
        val coherentRevision = inferenceSafeObservationRevision
            ?.takeIf { revision ->
                trackingSafeRevision == revision && combatMotionSafeRevision == revision
            }
        val nextAllowed = safetyEvidenceValid && coherentRevision != null
        val hardSafetyLost = hardSafetyStopArmed && !safetyEvidenceValid
        // Close before invoking a product callback. A broken callback must not reopen the game.
        inputAllowed = nextAllowed
        if (hardSafetyLost) {
            hardSafetyStopArmed = false
            val reason = pauseReason
            safetyStops.values.toList().forEach { callback ->
                try {
                    callback(reason)
                } catch (_: RuntimeException) {
                    // The gate remains closed; another registered game can still receive the stop.
                }
            }
        }
        if (nextAllowed) hardSafetyStopArmed = true
    }

    private fun IdentityPauseReason.toGamePauseReason(): PauseReason =
        when (this) {
            IdentityPauseReason.CROSSING_HYSTERESIS -> PauseReason.PLAYER_LANE_CROSS
            IdentityPauseReason.PLAYER_OVERLAP -> PauseReason.PLAYER_OVERLAP
            else -> PauseReason.POSE_LOST
        }
}
