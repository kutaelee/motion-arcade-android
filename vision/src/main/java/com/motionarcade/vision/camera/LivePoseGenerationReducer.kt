package com.motionarcade.vision.camera

import com.motionarcade.vision.pose.LivePoseInferencePhase
import com.motionarcade.vision.pose.LivePoseInferenceSink
import com.motionarcade.vision.pose.LivePoseInferenceSnapshot

/**
 * Main-thread owner of the pose presentation generation. Camera teardown publishes its own
 * terminal revision before revoking the request token, so native close callbacks are optional and
 * an older generation can never overwrite a newer bind.
 */
internal class LivePoseGenerationReducer {
    private data class Generation(
        val token: CameraSessionRequestGate.Token,
        val sink: LivePoseInferenceSink,
        var lastRevision: Long,
        var callbackCount: Long,
    )

    private var current: Generation? = null

    fun begin(
        token: CameraSessionRequestGate.Token,
        sink: LivePoseInferenceSink,
    ) {
        check(current == null) { "The prior pose generation must terminate before a new bind" }
        current =
            Generation(
                token = token,
                sink = sink,
                lastRevision = 0L,
                callbackCount = 0L,
            )
        deliver(sink, LivePoseInferenceSnapshot.idle(token.generation))
    }

    fun accept(
        token: CameraSessionRequestGate.Token,
        snapshot: LivePoseInferenceSnapshot,
    ) {
        val owned = current ?: return
        if (
            owned.token !== token ||
            snapshot.sessionGeneration != token.generation ||
            snapshot.revision <= owned.lastRevision
        ) {
            return
        }
        owned.lastRevision = snapshot.revision
        owned.callbackCount = snapshot.callbackCount
        if (
            snapshot.phase == LivePoseInferencePhase.FAILED ||
            snapshot.phase == LivePoseInferencePhase.RELEASED
        ) {
            current = null
        }
        deliver(owned.sink, snapshot)
    }

    fun terminateCurrent(phase: LivePoseInferencePhase) {
        val owned = current ?: return
        terminate(owned.token, phase)
    }

    fun terminate(
        token: CameraSessionRequestGate.Token,
        phase: LivePoseInferencePhase,
    ) {
        require(phase == LivePoseInferencePhase.FAILED || phase == LivePoseInferencePhase.RELEASED) {
            "Camera ownership may terminate pose UI only with a terminal phase"
        }
        val owned = current ?: return
        if (owned.token !== token) return
        current = null
        val terminal =
            LivePoseInferenceSnapshot(
                sessionGeneration = token.generation,
                revision = Math.incrementExact(owned.lastRevision),
                phase = phase,
                poseCount = null,
                callbackCount = owned.callbackCount,
                resultTimestampMs = null,
            )
        deliver(owned.sink, terminal)
    }

    private fun deliver(
        sink: LivePoseInferenceSink,
        snapshot: LivePoseInferenceSnapshot,
    ) {
        try {
            sink.onInference(snapshot)
        } catch (_: RuntimeException) {
            // UI delivery cannot retain ownership or admit a superseded generation.
        }
    }
}
