package com.motionarcade.app

import com.motionarcade.core.contract.PauseReason
import com.motionarcade.vision.camera.FrontCameraPreviewStatus
import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot

/**
 * Generation- and observation-fenced admission for a one-person combat session.
 *
 * A pose callback and its derived combat frame must describe the same accepted camera result.
 * Any camera or callback safety gap discards both halves of that proof, so returning to ACTIVE
 * cannot revive an earlier frame.
 */
internal class SoloCombatGameplayAdmissionGate(
    private var configAvailable: Boolean,
) {
    private val lock = Any()
    private val safetyStops = linkedMapOf<Long, (PauseReason) -> Unit>()
    private var nextSafetyStopId = 0L

    private var expectedGeneration: Long? = null
    private var previewStatus = FrontCameraPreviewStatus.IDLE
    private var inferenceSafeObservationRevision: Long? = null
    private var combatMotionSafeRevision: Long? = null
    private var hardSafetyStopArmed = false
    private var inputAllowed = false

    fun setConfigAvailable(available: Boolean) = synchronized(lock) {
        configAvailable = available
        if (!available) discardObservationProofLocked()
        recomputeLocked()
    }

    fun invalidateForRebind() = synchronized(lock) {
        expectedGeneration = null
        previewStatus = FrontCameraPreviewStatus.IDLE
        discardObservationProofLocked()
        recomputeLocked()
    }

    fun onBindingStarted(generation: Long) = synchronized(lock) {
        require(generation > 0L) { "Camera generation must be positive" }
        expectedGeneration = generation
        previewStatus = FrontCameraPreviewStatus.STARTING
        discardObservationProofLocked()
        recomputeLocked()
    }

    fun onPreviewStatus(status: FrontCameraPreviewStatus) = synchronized(lock) {
        previewStatus = status
        if (status != FrontCameraPreviewStatus.ACTIVE) discardObservationProofLocked()
        recomputeLocked()
    }

    /** Returns false for a stale generation; stale deliveries never alter current evidence. */
    fun onInference(snapshot: LivePoseInferenceSnapshot): Boolean = synchronized(lock) {
        if (snapshot.sessionGeneration != expectedGeneration) return false
        val usable =
            snapshot.phase == LivePoseInferencePhase.ACTIVE && snapshot.poseCount == SOLO_POSE_COUNT
        if (usable) {
            inferenceSafeObservationRevision = snapshot.callbackCount.takeIf { it > 0L }
            if (inferenceSafeObservationRevision == null) combatMotionSafeRevision = null
        } else {
            discardObservationProofLocked()
        }
        recomputeLocked()
        true
    }

    /**
     * Records only coordinate-free solo combat usability. The caller must supply the exact raw
     * callback revision from which the frame was derived.
     */
    fun onSoloCombatMotion(
        generation: Long,
        revision: Long,
        usableForSolo: Boolean,
    ): Boolean = synchronized(lock) {
        if (generation != expectedGeneration || revision <= 0L) return false
        if (usableForSolo) {
            combatMotionSafeRevision = revision
        } else {
            discardObservationProofLocked()
        }
        recomputeLocked()
        true
    }

    /** A current-generation processing callback failure is a hard gap even with ACTIVE preview. */
    fun onCallbackError(generation: Long): Boolean = synchronized(lock) {
        if (generation != expectedGeneration) return false
        discardObservationProofLocked()
        recomputeLocked()
        true
    }

    fun isGameplayInputAllowed(): Boolean = synchronized(lock) { inputAllowed }

    fun currentSafetyPauseReason(): PauseReason = PauseReason.POSE_LOST

    fun <T> runWhenAllowed(action: () -> T): T? = synchronized(lock) {
        if (!inputAllowed) null else action()
    }

    fun <T> runWhenAllowed(
        generation: Long,
        action: () -> T,
    ): T? = synchronized(lock) {
        if (!inputAllowed || generation != expectedGeneration) null else action()
    }

    fun registerSafetyStop(onSafetyStop: (PauseReason) -> Unit): () -> Unit {
        val id = synchronized(lock) {
            nextSafetyStopId += 1L
            nextSafetyStopId.also { safetyStops[it] = onSafetyStop }
        }
        return { synchronized(lock) { safetyStops.remove(id) } }
    }

    private fun discardObservationProofLocked() {
        inferenceSafeObservationRevision = null
        combatMotionSafeRevision = null
    }

    private fun recomputeLocked() {
        val safetyEvidenceValid =
            configAvailable &&
                expectedGeneration != null &&
                previewStatus == FrontCameraPreviewStatus.ACTIVE &&
                inferenceSafeObservationRevision != null &&
                combatMotionSafeRevision != null
        val coherent =
            safetyEvidenceValid && inferenceSafeObservationRevision == combatMotionSafeRevision
        val hardSafetyLost = hardSafetyStopArmed && !safetyEvidenceValid
        inputAllowed = coherent
        if (hardSafetyLost) {
            hardSafetyStopArmed = false
            safetyStops.values.toList().forEach { callback ->
                try {
                    callback(PauseReason.POSE_LOST)
                } catch (_: RuntimeException) {
                    // Keep the gate closed and continue notifying other registered sessions.
                }
            }
        }
        if (coherent) hardSafetyStopArmed = true
    }

    private companion object {
        const val SOLO_POSE_COUNT = 1
    }
}
