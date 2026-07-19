package com.motionarcade.vision.capability.recovery

import com.motionarcade.core.contract.GameMode
import com.google.mediapipe.framework.image.MPImage
import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.runtime.CallbackCleanupToken
import com.motionarcade.vision.capability.runtime.CallbackOutputCleanupEvidence
import com.motionarcade.vision.capability.runtime.CallbackResolution
import com.motionarcade.vision.capability.runtime.ProbeFailureReason
import com.motionarcade.vision.capability.runtime.ProbeCloseClaim
import com.motionarcade.vision.capability.runtime.ProbeCloseCompletion
import com.motionarcade.vision.capability.runtime.ProbeCloseCompletionKind
import com.motionarcade.vision.capability.runtime.ProbeCloseCompletionRecord
import com.motionarcade.vision.capability.runtime.ProbeStateMachine
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import com.motionarcade.vision.capability.runtime.RuntimeCloseExecution
import com.motionarcade.vision.capability.runtime.RuntimeCloseResult
import com.motionarcade.vision.capability.runtime.RuntimeCreateRequest
import com.motionarcade.vision.capability.runtime.FreshPoseRuntimeFactory
import com.motionarcade.vision.capability.runtime.NativeCreateAuthorizationRequest
import com.motionarcade.vision.capability.runtime.NativeCreateAuthorizer
import com.motionarcade.vision.capability.runtime.OpenRuntimeCommand
import com.motionarcade.vision.capability.runtime.ProbeClock
import com.motionarcade.vision.capability.runtime.RuntimeCallbackPort
import com.motionarcade.vision.capability.runtime.RuntimeNativeCreateGate
import com.motionarcade.vision.capability.runtime.RuntimeOpenExecution
import com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary
import com.motionarcade.vision.capability.runtime.RuntimeResultCallback
import com.motionarcade.vision.capability.runtime.RuntimeRouteKind
import com.motionarcade.vision.capability.runtime.RuntimeSubmitAuthorization
import com.motionarcade.vision.capability.runtime.RuntimeSubmitExecution
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opaque owner for one exact runtime-attempt closure. */
sealed interface RecoveryClosureSession

/** Opaque open whose durable ACTIVE and thermal gate both preceded native create. */
sealed interface RecoveryThermallyGuardedRuntimeOpen

/**
 * One-shot journal authority minted only after every process-local owner and safety boundary has
 * produced exact clean evidence. It intentionally exposes no copyable fields.
 */
sealed interface RecoveryCleanClosureAuthority

/**
 * Separate pre-native authority. No production issuer exists until the options-proto verifier can
 * prove rejection before RuntimeOwnerBoundary entry; post-native clean authority cannot substitute.
 */
sealed interface RecoveryPreNativeCreateDenialAuthority

enum class RecoveryRuntimeCloseAdmission {
    SEALED_PENDING_FINALIZATION,
    RETRY_REQUIRED,
    RECOVERY_REQUIRED,
}

internal enum class RecoveryAuthorityConsumeResult {
    CONSUMED,
    MISMATCH,
    REVOKED,
    ALREADY_CONSUMED,
}

/** JVM-private nest host for every post-native recovery authority implementation. */
object RecoveryCleanClosureBoundary {

private data class RecoveryCommittedAttemptBinding(
    val activePayloadSha256: Sha256Digest,
    val teardownPayloadSha256: Sha256Digest,
    val lastEpoch: ULong,
    val retryUsed: Boolean,
    val retryContextId: Sha256Digest?,
    val durableActive: RecoveryDurableActiveMaterial,
)

private class IssuedRecoveryClosureSession(
    val issuerIdentity: Any,
    val openExecution: RuntimeOpenExecution,
    val request: RuntimeCreateRequest,
    val key: RecoveryAttemptRouteKey,
    val committedAttempt: RecoveryCommittedAttemptBinding,
    val machine: ProbeStateMachine,
    val runtimeClaim: RuntimeOwnerBoundary.RecoveryClosureBeginToken,
    val resourceGeneration: Long,
    val thermalMonitor: ProcessThermalSafetyMonitor,
    val thermalMeasurement: RecoveryThermalMeasurement,
    val renderOwner: RecoveryRenderOwner,
    val cameraPipelineOwner: RecoveryCameraPipelineOwner,
    val postSealPoisonSink: RecoveryDurablePostSealPoisonSink,
) : RecoveryClosureSession {
    var committedTeardownBound = false
    var nativeCloseStarted = false
    var closeAdmissionInFlight = false
    var closeClaim: ProbeCloseClaim? = null
    var closeExecution: RuntimeCloseExecution? = null
    var closeCompletion: ProbeCloseCompletion? = null
    var thermalCutoff: RecoveryThermalCutoff? = null
    var renderClosure: RecoveryRenderClosure? = null
    var cameraPipelineClosure: RecoveryCameraPipelineClosure? = null
    var failed = false
    var completed = false
    var committedTeardownPayload: RecoveryJournalPayloadV5? = null
    var boundCleanPayload: RecoveryJournalPayloadV5? = null
    val postSealPoisonLatched = AtomicBoolean(false)
    var postSealPoisonDurablyCommittedPayload: RecoveryJournalPayloadV5? = null
}

private class IssuedRecoveryThermallyGuardedRuntimeOpen private constructor(
    val issuerIdentity: Any,
    val openExecution: RuntimeOpenExecution,
    val durableActive: RecoveryDurableActiveMaterial,
    val thermalMonitor: ProcessThermalSafetyMonitor,
    val postSealPoisonSink: RecoveryDurablePostSealPoisonSink,
) : RecoveryThermallyGuardedRuntimeOpen {
    val consumed = AtomicBoolean(false)

    companion object {
        fun issue(
            issuerIdentity: Any,
            openExecution: RuntimeOpenExecution,
            durableActive: RecoveryDurableActiveMaterial,
            thermalMonitor: ProcessThermalSafetyMonitor,
            postSealPoisonSink: RecoveryDurablePostSealPoisonSink,
        ): IssuedRecoveryThermallyGuardedRuntimeOpen =
            IssuedRecoveryThermallyGuardedRuntimeOpen(
                issuerIdentity,
                openExecution,
                durableActive,
                thermalMonitor,
                postSealPoisonSink,
            )
    }
}

/**
 * Exact lifecycle coordinator for source/analyzer/input/buffer/native/callback/render/thermal
 * ownership. A session can be opened only from an owner-issued RuntimeOpenExecution whose complete
 * attempt and route match the durable recovery key.
 */
    private val issuerIdentity = Any()
    private val nextPersistenceFenceGeneration = AtomicLong(1L)

    private class IssuedRecoveryCleanJournalPersistenceFence private constructor(
        val issuerIdentity: Any,
        val fenceGeneration: Long,
        val session: IssuedRecoveryClosureSession,
        val expectedOldPayloadSha256: Sha256Digest,
        val knownTeardownPayload: RecoveryJournalPayloadV5,
        val cleanPayload: RecoveryJournalPayloadV5,
    ) : RecoveryCleanJournalPersistenceFence {
        val consumed = AtomicBoolean(false)

        companion object {
            fun issue(
                issuerIdentity: Any,
                fenceGeneration: Long,
                session: IssuedRecoveryClosureSession,
                expectedOldPayloadSha256: Sha256Digest,
                knownTeardownPayload: RecoveryJournalPayloadV5,
                cleanPayload: RecoveryJournalPayloadV5,
            ): IssuedRecoveryCleanJournalPersistenceFence =
                IssuedRecoveryCleanJournalPersistenceFence(
                    issuerIdentity,
                    fenceGeneration,
                    session,
                    expectedOldPayloadSha256,
                    knownTeardownPayload,
                    cleanPayload,
                )
        }
    }

    private class IssuedRecoveryCleanClosureAuthority private constructor(
        val issuerIdentity: Any,
        val session: IssuedRecoveryClosureSession,
        val request: RuntimeCreateRequest,
        val key: RecoveryAttemptRouteKey,
        val committedAttempt: RecoveryCommittedAttemptBinding,
        val machine: ProbeStateMachine,
        val claim: ProbeCloseClaim,
        val execution: RuntimeCloseExecution,
        val resourceGeneration: Long,
        val outcome: RouteAttemptOutcome,
        val terminalProof: TerminalProofV1?,
    ) : RecoveryCleanClosureAuthority {
        val consumed = AtomicBoolean(false)
        var persistenceFence: RecoveryCleanJournalPersistenceFence? = null

        companion object {
            fun issue(
                issuerIdentity: Any,
                session: IssuedRecoveryClosureSession,
                request: RuntimeCreateRequest,
                key: RecoveryAttemptRouteKey,
                committedAttempt: RecoveryCommittedAttemptBinding,
                machine: ProbeStateMachine,
                claim: ProbeCloseClaim,
                execution: RuntimeCloseExecution,
                resourceGeneration: Long,
                outcome: RouteAttemptOutcome,
                terminalProof: TerminalProofV1?,
            ): IssuedRecoveryCleanClosureAuthority =
                IssuedRecoveryCleanClosureAuthority(
                    issuerIdentity,
                    session,
                    request,
                    key,
                    committedAttempt,
                    machine,
                    claim,
                    execution,
                    resourceGeneration,
                    outcome,
                    terminalProof,
                )
        }
    }

    /**
     * Consumes durable ACTIVE first, then invokes the process thermal gate at RuntimeOwnerBoundary's
     * final pre-create edge. A raw RuntimeOpenExecution can no longer enter clean closure.
     */
    fun openThermallyGuardedRuntime(
        command: OpenRuntimeCommand,
        authorizationRequest: NativeCreateAuthorizationRequest,
        authorizer: NativeCreateAuthorizer,
        callbacks: RuntimeCallbackPort,
        runtimeFactory: FreshPoseRuntimeFactory,
        clock: ProbeClock,
        committedActiveReceipt: RecoveryDurableActiveJournalReceipt,
        thermalMonitor: ProcessThermalSafetyMonitor,
        postSealPoisonSink: RecoveryDurablePostSealPoisonSink,
    ): RecoveryThermallyGuardedRuntimeOpen? {
        if (!RecoveryPostSealPoisonPersistenceBoundary.isGenuineSink(postSealPoisonSink)) {
            return null
        }
        val durableActive =
            RecoveryDurableJournalReceiptBoundary.consumeActive(committedActiveReceipt)
                ?: return null
        val committedActive = durableActive.payload
        val active = committedActive.active ?: return null
        if (active.state != JournalActiveState.ACTIVE) return null
        val key = active.toRouteKey()
        val activeDigest = payloadSha256(committedActive) ?: return null
        if (activeDigest != durableActive.payloadSha256 ||
            !requestMatchesRecoveryKey(command.request, key) ||
            command.request.attempt.runtimeArtifactId != committedActive.recoveryBuildId ||
            command.request.attempt.mode != committedActive.mode.toGameMode()
        ) {
            return null
        }
        val openExecution =
            RuntimeOwnerBoundary.openWithPreCreateGate(
                command,
                authorizationRequest,
                authorizer,
                callbacks,
                runtimeFactory,
                clock,
                RuntimeNativeCreateGate {
                    ProcessThermalSafetyBoundary.isCurrentSafeForNativeCreate(thermalMonitor)
                },
            )
        if (RuntimeOwnerBoundary.stateMachine(openExecution) == null) return null
        return IssuedRecoveryThermallyGuardedRuntimeOpen.issue(
            issuerIdentity,
            openExecution,
            durableActive,
            thermalMonitor,
            postSealPoisonSink,
        )
    }

    fun begin(
        guardedOpen: RecoveryThermallyGuardedRuntimeOpen,
        renderOwner: RecoveryRenderOwner,
        cameraPipelineOwner: RecoveryCameraPipelineOwner,
    ): RecoveryClosureSession? {
        val guarded =
            (guardedOpen as? IssuedRecoveryThermallyGuardedRuntimeOpen)?.takeIf {
                it.javaClass == IssuedRecoveryThermallyGuardedRuntimeOpen::class.java &&
                    it.issuerIdentity === issuerIdentity && it.consumed.compareAndSet(false, true)
            } ?: return null
        val openExecution = guarded.openExecution
        val durableActive = guarded.durableActive
        val thermalMonitor = guarded.thermalMonitor
        val postSealPoisonSink = guarded.postSealPoisonSink
        val committedActive = durableActive.payload
        val request = RuntimeOwnerBoundary.createRequestForGenuineOpen(openExecution) ?: return null
        val machine = RuntimeOwnerBoundary.stateMachine(openExecution) ?: return null
        val resourceGeneration =
            RuntimeOwnerBoundary.ownershipGenerationForGenuineOpen(openExecution)
        val active = committedActive.active ?: return null
        if (active.state != JournalActiveState.ACTIVE) return null
        val key = active.toRouteKey()
        val activeDigest = payloadSha256(committedActive) ?: return null
        if (activeDigest != durableActive.payloadSha256) return null
        val teardownPayload =
            committedActive.copy(active = active.copy(state = JournalActiveState.TEARDOWN_PENDING))
        val teardownDigest = payloadSha256(teardownPayload) ?: return null
        if (resourceGeneration <= 0L ||
            !requestMatchesRecoveryKey(request, key) ||
            request.attempt.runtimeArtifactId != committedActive.recoveryBuildId ||
            request.attempt.mode != committedActive.mode.toGameMode()
        ) {
            return null
        }
        val runtimeClaim =
            RuntimeOwnerBoundary.beginRecoveryClosureClaim(openExecution) ?: return null
        if (!RecoveryCameraPipelineBoundary.claimForResourceGeneration(
                cameraPipelineOwner,
                resourceGeneration,
            )
        ) {
            RuntimeOwnerBoundary.rollbackRecoveryClosureClaim(runtimeClaim, openExecution)
            return null
        }
        if (!RecoveryRenderOwnerBoundary.claimForResourceGeneration(
                renderOwner,
                resourceGeneration,
            )
        ) {
            RecoveryCameraPipelineBoundary.releaseUncommittedClaim(
                cameraPipelineOwner,
                resourceGeneration,
            )
            RuntimeOwnerBoundary.rollbackRecoveryClosureClaim(runtimeClaim, openExecution)
            return null
        }
        val thermalMeasurement =
            ProcessThermalSafetyBoundary.beginMeasurement(thermalMonitor, resourceGeneration)
                ?: run {
                    RecoveryRenderOwnerBoundary.releaseUncommittedClaim(
                        renderOwner,
                        resourceGeneration,
                    )
                    RecoveryCameraPipelineBoundary.releaseUncommittedClaim(
                        cameraPipelineOwner,
                        resourceGeneration,
                    )
                    RuntimeOwnerBoundary.rollbackRecoveryClosureClaim(runtimeClaim, openExecution)
                    return null
                }
        val session = IssuedRecoveryClosureSession(
            issuerIdentity = issuerIdentity,
            openExecution = openExecution,
            request = request,
            key = key,
            committedAttempt =
                RecoveryCommittedAttemptBinding(
                    activePayloadSha256 = activeDigest,
                    teardownPayloadSha256 = teardownDigest,
                    lastEpoch = committedActive.lastEpoch,
                    retryUsed = active.retryUsed,
                    retryContextId = active.retryContextId,
                    durableActive = durableActive,
                ),
            machine = machine,
            runtimeClaim = runtimeClaim,
            resourceGeneration = resourceGeneration,
            thermalMonitor = thermalMonitor,
            thermalMeasurement = thermalMeasurement,
            renderOwner = renderOwner,
            cameraPipelineOwner = cameraPipelineOwner,
            postSealPoisonSink = postSealPoisonSink,
        )
        if (!RuntimeOwnerBoundary.commitRecoveryClosureClaim(
                runtimeClaim,
                openExecution,
                session,
            )
        ) {
            ProcessThermalSafetyBoundary.abortMeasurement(
                thermalMonitor,
                thermalMeasurement,
            )
            RecoveryRenderOwnerBoundary.releaseUncommittedClaim(
                renderOwner,
                resourceGeneration,
            )
            RecoveryCameraPipelineBoundary.releaseUncommittedClaim(
                cameraPipelineOwner,
                resourceGeneration,
            )
            RuntimeOwnerBoundary.rollbackRecoveryClosureClaim(runtimeClaim, openExecution)
            return null
        }
        return session
    }

    /**
     * Consumes only a source token minted at the owned analyzer entry. The camera boundary always
     * receives the token, including after session completion, so it can close the exact late proxy
     * and sticky-poison prior clean evidence.
     */
    fun ownFrame(
        session: RecoveryClosureSession,
        source: RecoveryCameraAnalyzerSource,
    ): RecoveryCameraFrameLease? {
        val issued = genuineSession(session) ?: return null
        val frame =
            RecoveryCameraPipelineBoundary.ownFrame(
                issued.cameraPipelineOwner,
                issued.resourceGeneration,
                source,
            )
        if (frame == null) synchronized(issued) { issued.failed = true }
        return frame
    }

    /** Builds the submitted MPImage from the exact buffer already owned by the frame graph. */
    fun buildSubmittedInput(
        session: RecoveryClosureSession,
        frame: RecoveryCameraFrameLease,
    ): MPImage? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.cameraPipelineClosure != null
            ) {
                return null
            }
        }
        return RecoveryCameraPipelineBoundary.buildSubmittedInput(
            issued.cameraPipelineOwner,
            frame,
            issued.resourceGeneration,
        )
    }

    /** The only recovery submit path; the graph's exact MPImage crosses RuntimeOwnerBoundary. */
    fun submitFrame(
        session: RecoveryClosureSession,
        frame: RecoveryCameraFrameLease,
        authorization: RuntimeSubmitAuthorization,
    ): RuntimeSubmitExecution? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.cameraPipelineClosure != null
            ) {
                return null
            }
        }
        val execution =
            RecoveryCameraPipelineBoundary.submitFrame(
                issued.cameraPipelineOwner,
                frame,
                issued.resourceGeneration,
                issued.machine,
                authorization,
                issued.openExecution,
            )
        if (execution == null) synchronized(issued) { issued.failed = true }
        return execution
    }

    /** Closes submitted input and zeros the exact full buffer immediately after submit returns. */
    fun completeReturnedSubmission(
        session: RecoveryClosureSession,
        frame: RecoveryCameraFrameLease,
    ): Boolean {
        val issued = genuineSession(session) ?: return false
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.cameraPipelineClosure != null
            ) {
                return false
            }
        }
        val completed =
            RecoveryCameraPipelineBoundary.completeReturnedSubmission(
                issued.cameraPipelineOwner,
                frame,
                issued.resourceGeneration,
            )
        if (!completed) synchronized(issued) { issued.failed = true }
        return completed
    }

    /**
     * Closes the concrete callback MPImage first, then and only then supplies CLOSED to the exact
     * state-machine cleanup token. The categorical enum can no longer authorize cleanup alone.
     */
    fun completeCallbackOutput(
        session: RecoveryClosureSession,
        frame: RecoveryCameraFrameLease,
        cleanupToken: CallbackCleanupToken,
        callback: RuntimeResultCallback,
        callbackOutput: MPImage,
    ): CallbackResolution? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.cameraPipelineClosure != null
            ) {
                return null
            }
        }
        val callbackExecution =
            RuntimeOwnerBoundary.claimCallbackOutput(
                issued.openExecution,
                callback,
                callbackOutput,
            )
        if (!RecoveryCameraPipelineBoundary.closeCallbackOutput(
                issued.cameraPipelineOwner,
                frame,
                callbackExecution,
                callbackOutput,
                issued.openExecution,
                issued.resourceGeneration,
            )
        ) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        val resolution =
            try {
                issued.machine.completeCallbackOutput(
                    cleanupToken,
                    CallbackOutputCleanupEvidence.CLOSED,
                )
            } catch (_: Throwable) {
                null
            }
        if (resolution !is CallbackResolution.Completed) {
            RecoveryCameraPipelineBoundary.closeCompletedFrame(
                issued.cameraPipelineOwner,
                frame,
                issued.resourceGeneration,
            )
            synchronized(issued) { issued.failed = true }
        }
        return resolution
    }

    fun closeCompletedFrame(
        session: RecoveryClosureSession,
        frame: RecoveryCameraFrameLease,
    ): Boolean {
        val issued = genuineSession(session) ?: return false
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.cameraPipelineClosure != null
            ) {
                return false
            }
        }
        return RecoveryCameraPipelineBoundary.closeCompletedFrame(
            issued.cameraPipelineOwner,
            frame,
            issued.resourceGeneration,
        )
    }

    /** Closes a graph without callback only after the exact runtime has a no-callback outcome. */
    fun closeFrameWithoutCallback(
        session: RecoveryClosureSession,
        frame: RecoveryCameraFrameLease,
    ): Boolean {
        val issued = genuineSession(session) ?: return false
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.cameraPipelineClosure != null ||
                issued.machine.pendingCloseOutcome() !in
                setOf(RouteAttemptOutcome.CLEAN_TERMINAL, RouteAttemptOutcome.INCOMPLETE)
            ) {
                return false
            }
        }
        return RecoveryCameraPipelineBoundary.closeFrameWithoutCallback(
            issued.cameraPipelineOwner,
            frame,
            issued.resourceGeneration,
        )
    }

    /** Routes a concrete callback arriving after seal to an absorbing poisoned owner. */
    fun closeLateCallbackOutput(
        session: RecoveryClosureSession,
        callbackOutput: MPImage,
    ): Boolean {
        val issued = genuineSession(session) ?: return false
        val persistenceInputs = synchronized(issued) {
            issued.postSealPoisonLatched.set(true)
            issued.failed = true
            if (issued.committedTeardownBound && issued.machine.callbackGateIsSealed()) {
                Pair(issued.committedTeardownPayload, issued.boundCleanPayload)
            } else {
                null
            }
        }
        val closed =
            RecoveryCameraPipelineBoundary.closeLateCallbackOutput(
                issued.cameraPipelineOwner,
                callbackOutput,
                issued.resourceGeneration,
            )
        val knownTeardown = persistenceInputs?.first ?: return false
        val committedPoison =
            RecoveryPostSealPoisonPersistenceBoundary.persistPoison(
                issued.postSealPoisonSink,
                knownTeardown,
                persistenceInputs.second,
            )
        if (committedPoison != null) {
            synchronized(issued) {
                issued.postSealPoisonDurablyCommittedPayload = committedPoison
            }
        }
        return closed && committedPoison != null
    }

    fun closeCameraPipeline(
        session: RecoveryClosureSession,
    ): RecoveryCameraPipelineClosure? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            issued.cameraPipelineClosure?.let { return it }
            if (issued.failed || issued.completed || issued.nativeCloseStarted) {
                return null
            }
        }
        val outcome = issued.machine.pendingCloseOutcome() ?: return null
        val completedCallbacks = try {
            Math.addExact(
                issued.machine.successfulWarmupCallbacks,
                issued.machine.measurementCompletions,
            )
        } catch (_: ArithmeticException) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        val closure =
            RecoveryCameraPipelineBoundary.closeForResourceGeneration(
                issued.cameraPipelineOwner,
                issued.resourceGeneration,
                outcome,
                completedCallbacks,
            ) ?: run {
                synchronized(issued) { issued.failed = true }
                return null
            }
        synchronized(issued) {
            if (issued.cameraPipelineClosure != null) return null
            issued.cameraPipelineClosure = closure
            return closure
        }
    }

    /** Binds the exact durably committed ACTIVE -> TEARDOWN_PENDING mutation before native close. */
    fun bindCommittedTeardown(
        session: RecoveryClosureSession,
        committedTeardownReceipt: RecoveryDurableTeardownJournalReceipt,
    ): Boolean {
        val issued = genuineSession(session) ?: return false
        val durableTeardown =
            RecoveryDurableJournalReceiptBoundary.consumeTeardown(
                committedTeardownReceipt,
                issued.committedAttempt.durableActive,
                issued.committedAttempt.teardownPayloadSha256,
            ) ?: return false
        val committedTeardown = durableTeardown.payload
        val digest = payloadSha256(committedTeardown) ?: return false
        val active = committedTeardown.active ?: return false
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.nativeCloseStarted ||
                issued.committedTeardownBound ||
                digest != issued.committedAttempt.teardownPayloadSha256 ||
                committedTeardown.lastEpoch != issued.committedAttempt.lastEpoch ||
                active.state != JournalActiveState.TEARDOWN_PENDING ||
                !active.matches(issued.key) ||
                active.retryUsed != issued.committedAttempt.retryUsed ||
                active.retryContextId != issued.committedAttempt.retryContextId ||
                committedTeardown.recoveryBuildId != issued.request.attempt.runtimeArtifactId ||
                committedTeardown.mode.toGameMode() != issued.request.attempt.mode
            ) {
                return false
            }
            issued.committedTeardownBound = true
            issued.committedTeardownPayload = committedTeardown
            return true
        }
    }

    /**
     * Freezes resource acquisition before asking the exact state machine for its one close claim.
     * Runtime close cannot begin until the exact frame graph and CameraX pipeline are closed.
     */
    fun beginRuntimeClose(session: RecoveryClosureSession): ProbeCloseClaim? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            if (!externalOwnersClosedLocked(issued) ||
                !issued.committedTeardownBound ||
                issued.nativeCloseStarted ||
                issued.closeClaim != null ||
                issued.failed ||
                issued.completed
            ) {
                return null
            }
            issued.nativeCloseStarted = true
        }
        val claim = issued.machine.beginClose()
        synchronized(issued) {
            if (claim == null) {
                issued.nativeCloseStarted = false
                return null
            }
            issued.closeClaim = claim
            return claim
        }
    }

    fun executeRuntimeClose(
        session: RecoveryClosureSession,
        claim: ProbeCloseClaim,
    ): RuntimeCloseExecution? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.closeClaim !== claim ||
                issued.closeCompletion != null || issued.closeAdmissionInFlight
            ) {
                return null
            }
        }
        return RuntimeOwnerBoundary.close(claim, issued.openExecution)
    }

    fun admitRuntimeClose(
        session: RecoveryClosureSession,
        claim: ProbeCloseClaim,
        execution: RuntimeCloseExecution,
    ): RecoveryRuntimeCloseAdmission? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            if (issued.failed || issued.completed || issued.closeClaim !== claim ||
                issued.closeCompletion != null || issued.closeAdmissionInFlight ||
                !RuntimeOwnerBoundary.isGenuineCloseExecutionForOpen(
                    execution,
                    issued.openExecution,
                    issued.machine,
                    claim,
                )
            ) {
                return null
            }
            issued.closeAdmissionInFlight = true
        }
        val completion = issued.machine.completeClose(claim, execution)
        if (completion == null) {
            synchronized(issued) { issued.closeAdmissionInFlight = false }
            return null
        }
        return when (execution.result) {
            is RuntimeCloseResult.Deferred -> {
                val record = issued.machine.consumeCloseCompletion(completion, claim)
                synchronized(issued) { issued.closeAdmissionInFlight = false }
                if (record?.kind == ProbeCloseCompletionKind.RETRY_REQUIRED) {
                    RecoveryRuntimeCloseAdmission.RETRY_REQUIRED
                } else {
                    synchronized(issued) { issued.failed = true }
                    RecoveryRuntimeCloseAdmission.RECOVERY_REQUIRED
                }
            }

            is RuntimeCloseResult.Failed -> {
                issued.machine.consumeCloseCompletion(completion, claim)
                synchronized(issued) {
                    issued.closeAdmissionInFlight = false
                    issued.failed = true
                }
                RecoveryRuntimeCloseAdmission.RECOVERY_REQUIRED
            }

            is RuntimeCloseResult.Clean -> synchronized(issued) {
                issued.closeAdmissionInFlight = false
                issued.closeExecution = execution
                issued.closeCompletion = completion
                RecoveryRuntimeCloseAdmission.SEALED_PENDING_FINALIZATION
            }
        }
    }

    fun endThermalMeasurement(session: RecoveryClosureSession): RecoveryThermalCutoff? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            issued.thermalCutoff?.let { return it }
            if (issued.failed || issued.completed) return null
        }
        val cutoff =
            ProcessThermalSafetyBoundary.endMeasurement(
                issued.thermalMonitor,
                issued.thermalMeasurement,
            ) ?: return null
        synchronized(issued) {
            if (issued.thermalCutoff != null) return null
            issued.thermalCutoff = cutoff
            return cutoff
        }
    }

    fun closeRenderOwner(session: RecoveryClosureSession): RecoveryRenderClosure? {
        val issued = genuineSession(session) ?: return null
        synchronized(issued) {
            issued.renderClosure?.let { return it }
            if (issued.failed || issued.completed) return null
        }
        val closure =
            RecoveryRenderOwnerBoundary.closeForResourceGeneration(
                issued.renderOwner,
                issued.resourceGeneration,
            ) ?: return null
        synchronized(issued) {
            if (issued.renderClosure != null) return null
            issued.renderClosure = closure
            return closure
        }
    }

    /**
     * Final mint point. It consumes render, thermal-current/cutoff, and the state-machine close
     * completion exactly once; any unavailable, stale, foreign, replayed, or unsafe evidence keeps
     * durable TEARDOWN_PENDING intact.
     */
    fun mintCleanClosureAuthority(
        session: RecoveryClosureSession,
    ): RecoveryCleanClosureAuthority? {
        val issued = genuineSession(session) ?: return null
        val claim: ProbeCloseClaim
        val execution: RuntimeCloseExecution
        val completion: ProbeCloseCompletion
        val cutoff: RecoveryThermalCutoff
        val renderClosure: RecoveryRenderClosure
        val cameraPipelineClosure: RecoveryCameraPipelineClosure
        synchronized(issued) {
            if (issued.failed || issued.completed || !externalOwnersClosedLocked(issued) ||
                !issued.committedTeardownBound || !issued.nativeCloseStarted ||
                issued.closeAdmissionInFlight
            ) {
                return null
            }
            claim = issued.closeClaim ?: return null
            execution = issued.closeExecution ?: return null
            completion = issued.closeCompletion ?: return null
            cutoff = issued.thermalCutoff ?: return null
            renderClosure = issued.renderClosure ?: return null
            cameraPipelineClosure = issued.cameraPipelineClosure ?: return null
        }
        if (!RuntimeOwnerBoundary.isGenuineCloseExecutionForOpen(
                execution,
                issued.openExecution,
                issued.machine,
                claim,
            ) ||
            !issued.machine.callbackGateIsSealed() ||
            issued.machine.callbackAdmissionIsOpen()
        ) {
            return null
        }
        val closeRecord = issued.machine.consumeCloseCompletion(completion, claim) ?: return null
        if (closeRecord.kind != ProbeCloseCompletionKind.SEALED ||
            closeRecord.outcome == RouteAttemptOutcome.RESOURCE_UNCERTAIN
        ) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        val exactTerminalProof = terminalProofFor(issued, closeRecord)
        if (closeRecord.outcome == RouteAttemptOutcome.CLEAN_TERMINAL &&
            exactTerminalProof == null
        ) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        // Consume the native/callback seal first. A rejected close must not burn otherwise valid
        // render or thermal receipts. Any later thermal failure is an absorbing process failure,
        // so retaining a retry path after that point would be incorrect.
        if (!RecoveryCameraPipelineBoundary.consumeClosed(
                issued.cameraPipelineOwner,
                cameraPipelineClosure,
                issued.resourceGeneration,
            )
        ) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        if (!RecoveryRenderOwnerBoundary.consumeClosed(
                issued.renderOwner,
                renderClosure,
                issued.resourceGeneration,
            )
        ) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        if (!ProcessThermalSafetyBoundary.consumeCurrentSafeCutoff(
                issued.thermalMonitor,
                issued.thermalMeasurement,
                cutoff,
                issued.resourceGeneration,
            )
        ) {
            synchronized(issued) { issued.failed = true }
            return null
        }
        synchronized(issued) {
            if (issued.completed || issued.failed) return null
            issued.completed = true
            return IssuedRecoveryCleanClosureAuthority.issue(
                issuerIdentity = issuerIdentity,
                session = issued,
                request = issued.request,
                key = issued.key,
                committedAttempt = issued.committedAttempt,
                machine = issued.machine,
                claim = claim,
                execution = execution,
                resourceGeneration = issued.resourceGeneration,
                outcome = closeRecord.outcome,
                terminalProof = exactTerminalProof,
            )
        }
    }

    internal fun consumeAuthority(
        authority: RecoveryCleanClosureAuthority,
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        expectedOutcome: RouteAttemptOutcome,
        expectedTerminalProof: TerminalProofV1?,
    ): RecoveryAuthorityConsumeResult {
        val issued = authority as? IssuedRecoveryCleanClosureAuthority
            ?: return RecoveryAuthorityConsumeResult.MISMATCH
        val currentDigest = payloadSha256(current)
            ?: return RecoveryAuthorityConsumeResult.MISMATCH
        val active = current.active ?: return RecoveryAuthorityConsumeResult.MISMATCH
        if (issued.javaClass != IssuedRecoveryCleanClosureAuthority::class.java ||
            issued.issuerIdentity !== issuerIdentity ||
            issued.key != key ||
            issued.outcome != expectedOutcome ||
            issued.terminalProof != expectedTerminalProof ||
            issued.resourceGeneration <= 0L ||
            issued.session.resourceGeneration != issued.resourceGeneration ||
            issued.session.request != issued.request ||
            issued.session.key != issued.key ||
            issued.session.committedAttempt != issued.committedAttempt ||
            issued.session.machine !== issued.machine ||
            issued.session.closeClaim !== issued.claim ||
            issued.session.closeExecution !== issued.execution ||
            !issued.session.completed ||
            !issued.session.committedTeardownBound ||
            currentDigest != issued.committedAttempt.teardownPayloadSha256 ||
            current.lastEpoch != issued.committedAttempt.lastEpoch ||
            active.retryUsed != issued.committedAttempt.retryUsed ||
            active.retryContextId != issued.committedAttempt.retryContextId ||
            issued.request.attempt.runtimeArtifactId != current.recoveryBuildId ||
            issued.request.attempt.mode != current.mode.toGameMode() ||
            !requestMatchesRecoveryKey(issued.request, key) ||
            !RuntimeOwnerBoundary.isGenuineCloseExecutionForOpen(
                issued.execution,
                issued.session.openExecution,
                issued.machine,
                issued.claim,
            )
        ) {
            return RecoveryAuthorityConsumeResult.MISMATCH
        }
        if (!RecoveryCameraPipelineBoundary.isStillClean(
                issued.session.cameraPipelineOwner,
                issued.resourceGeneration,
            )
        ) {
            return RecoveryAuthorityConsumeResult.REVOKED
        }
        return when (
            ProcessThermalSafetyBoundary.consumeAuthorityIfCurrentSafe(
                issued.session.thermalMonitor,
                issued.consumed,
            )
        ) {
            ProcessThermalSafetyBoundary.LiveAuthorityConsumeResult.CONSUMED ->
                RecoveryAuthorityConsumeResult.CONSUMED
            ProcessThermalSafetyBoundary.LiveAuthorityConsumeResult.UNSAFE ->
                RecoveryAuthorityConsumeResult.REVOKED
            ProcessThermalSafetyBoundary.LiveAuthorityConsumeResult.ALREADY_CONSUMED ->
                RecoveryAuthorityConsumeResult.ALREADY_CONSUMED
        }
    }

    internal fun bindPersistenceFence(
        authority: RecoveryCleanClosureAuthority,
        current: RecoveryJournalPayloadV5,
        cleanPayload: RecoveryJournalPayloadV5,
    ): RecoveryCleanJournalPersistenceFence? {
        val issued = authority as? IssuedRecoveryCleanClosureAuthority ?: return null
        if (!issued.consumed.get() || issued.issuerIdentity !== issuerIdentity ||
            issued.session.committedAttempt.teardownPayloadSha256 != payloadSha256(current) ||
            cleanPayload.modeControl != ModeControlV5.None
        ) {
            return null
        }
        synchronized(issued.session) {
            issued.persistenceFence?.let { return null }
            if (issued.session.committedTeardownPayload != current ||
                issued.session.boundCleanPayload != null
            ) {
                return null
            }
            val fenceGeneration = allocatePersistenceFenceGeneration() ?: return null
            issued.session.boundCleanPayload = cleanPayload
            val fence = IssuedRecoveryCleanJournalPersistenceFence.issue(
                issuerIdentity = issuerIdentity,
                fenceGeneration = fenceGeneration,
                session = issued.session,
                expectedOldPayloadSha256 = issued.committedAttempt.teardownPayloadSha256,
                knownTeardownPayload = current,
                cleanPayload = cleanPayload,
            )
            issued.persistenceFence = fence
            return fence
        }
    }

    /** Validates and consumes only a fence issued by this exact closure boundary. */
    internal fun resolvePersistenceFence(
        fence: RecoveryCleanJournalPersistenceFence,
    ): RecoveryJournalPersistenceDecision? {
        val issued = fence as? IssuedRecoveryCleanJournalPersistenceFence ?: return null
        if (issued.javaClass != IssuedRecoveryCleanJournalPersistenceFence::class.java ||
            issued.issuerIdentity !== issuerIdentity || issued.fenceGeneration <= 0L
        ) {
            return null
        }
        synchronized(issued.session) {
            if (issued.consumed.get() ||
                issued.session.committedTeardownPayload != issued.knownTeardownPayload ||
                issued.session.boundCleanPayload != issued.cleanPayload
            ) {
                return null
            }
            val committedPoison = issued.session.postSealPoisonDurablyCommittedPayload
            val decision = if (issued.session.postSealPoisonLatched.get()) {
                committedPoison ?: return null
                val expectedTeardownPoison =
                    issued.knownTeardownPayload.copy(modeControl = postSealModePoison())
                val expectedCleanPoison =
                    issued.cleanPayload.copy(modeControl = postSealModePoison())
                if (committedPoison != expectedTeardownPoison &&
                    committedPoison != expectedCleanPoison
                ) {
                    return null
                }
                val committedDigest = payloadSha256(committedPoison) ?: return null
                RecoveryJournalPersistenceDecision(
                    expectedOldPayloadSha256 = committedDigest,
                    payload = committedPoison,
                    alreadyDurablyCommitted = true,
                )
            } else {
                RecoveryJournalPersistenceDecision(
                    expectedOldPayloadSha256 = issued.expectedOldPayloadSha256,
                    payload = issued.cleanPayload,
                    alreadyDurablyCommitted = false,
                )
            }
            if (!issued.consumed.compareAndSet(false, true)) return null
            return decision
        }
    }

    private fun allocatePersistenceFenceGeneration(): Long? {
        while (true) {
            val current = nextPersistenceFenceGeneration.get()
            if (current <= 0L || current == Long.MAX_VALUE) return null
            if (nextPersistenceFenceGeneration.compareAndSet(current, current + 1L)) {
                return current
            }
        }
    }

    private fun postSealModePoison(): ModeControlV5.PostSealModePoison =
        ModeControlV5.PostSealModePoison(
            reason = PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
            cacheInvalidated = false,
        )

    private fun genuineSession(candidate: RecoveryClosureSession): IssuedRecoveryClosureSession? {
        val issued = candidate as? IssuedRecoveryClosureSession ?: return null
        return if (issued.javaClass == IssuedRecoveryClosureSession::class.java &&
            issued.issuerIdentity === issuerIdentity &&
            issued.resourceGeneration > 0L &&
            RuntimeOwnerBoundary.ownershipGenerationForGenuineOpen(issued.openExecution) ==
                issued.resourceGeneration &&
            RuntimeOwnerBoundary.isCommittedRecoveryClosureClaim(
                issued.runtimeClaim,
                issued.openExecution,
                issued,
            ) &&
            RuntimeOwnerBoundary.stateMachine(issued.openExecution) === issued.machine &&
            RuntimeOwnerBoundary.createRequestForGenuineOpen(issued.openExecution) == issued.request &&
            requestMatchesRecoveryKey(issued.request, issued.key)
        ) {
            issued
        } else {
            null
        }
    }

    private fun externalOwnersClosedLocked(session: IssuedRecoveryClosureSession): Boolean =
        session.cameraPipelineClosure != null

    private fun terminalProofFor(
        session: IssuedRecoveryClosureSession,
        closeRecord: ProbeCloseCompletionRecord,
    ): TerminalProofV1? {
        if (closeRecord.outcome != RouteAttemptOutcome.CLEAN_TERMINAL) return null
        val reason =
            when (closeRecord.failureReason) {
                ProbeFailureReason.RESULT_DEADLINE_EXPIRED ->
                    TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN
                ProbeFailureReason.SUBMISSION_FAILED -> TerminalProofReason.DETECT_EXCEPTION_RETURNED
                else -> return null
            }
        val traceOrdinal =
            when (session.key.role) {
                JournalRouteRole.CANDIDATE ->
                    if (session.key.delegate ==
                        com.motionarcade.vision.capability.domain.ProbeDelegate.CPU
                    ) {
                        0u
                    } else {
                        1u
                    }
                JournalRouteRole.SELECTED -> 2u
                JournalRouteRole.FALLBACK_CANDIDATE -> 3u
                JournalRouteRole.FALLBACK_SELECTED -> 4u
            }
        return TerminalProofV1(
            traceOrdinal = traceOrdinal,
            delegate = session.key.delegate,
            role = session.key.role,
            reason = reason,
            closure = TerminalClosure.POST_CREATE_ALL_OWNERS_RETURNED_CLEAN,
            resolvedOwnerMask = 0xff,
        )
    }

    private fun payloadSha256(value: RecoveryJournalPayloadV5): Sha256Digest? =
        when (val encoded = RecoveryJournalV5Codec.encodePayload(value)) {
            is CapabilityDomainResult.Valid -> CanonicalManifestCodec.sha256(encoded.value)
            is CapabilityDomainResult.Invalid -> null
        }

    private fun ActiveV5.toRouteKey(): RecoveryAttemptRouteKey =
        RecoveryAttemptRouteKey(
            probeBaseScopeId = probeBaseScopeId,
            attemptEpoch = attemptEpoch,
            delegate = delegate,
            role = role,
        )

    private fun ActiveV5.matches(key: RecoveryAttemptRouteKey): Boolean =
        probeBaseScopeId == key.probeBaseScopeId &&
            attemptEpoch == key.attemptEpoch &&
            delegate == key.delegate &&
            role == key.role

    private fun requestMatchesRecoveryKey(
        request: RuntimeCreateRequest,
        key: RecoveryAttemptRouteKey,
    ): Boolean {
        val expectedRole =
            when (request.route.kind) {
                RuntimeRouteKind.CPU_CANDIDATE,
                RuntimeRouteKind.GPU_CANDIDATE,
                -> JournalRouteRole.CANDIDATE

                RuntimeRouteKind.SELECTED_CPU,
                RuntimeRouteKind.SELECTED_GPU,
                -> JournalRouteRole.SELECTED

                RuntimeRouteKind.FALLBACK_CPU_CANDIDATE -> JournalRouteRole.FALLBACK_CANDIDATE
                RuntimeRouteKind.FALLBACK_CPU_SELECTED -> JournalRouteRole.FALLBACK_SELECTED
            }
        return request.attempt.probeBaseScopeId == key.probeBaseScopeId &&
            request.attempt.attemptEpoch.value == key.attemptEpoch &&
            request.route.delegate == key.delegate &&
            expectedRole == key.role
    }
}

/** No mint API by design; the future options verifier must be implemented before this can pass. */
object RecoveryPreNativeCreateDenialBoundary {
    /*
     * Reserved shape for the future options-proto verifier. Its JVM-private nest membership makes
     * same-package constructor and consumed-latch replay unavailable to ordinary callers.
     */
    private class IssuedRecoveryPreNativeCreateDenialAuthority private constructor(
        val issuerIdentity: Any,
        val denialGeneration: Long,
        val recoveryBuildId: RuntimeArtifactId,
        val mode: RecoveryJournalMode,
        val key: RecoveryAttemptRouteKey,
    ) : RecoveryPreNativeCreateDenialAuthority {
        val consumed = AtomicBoolean(false)
    }

    private val issuerIdentity = Any()

    internal fun consumeAuthority(
        authority: RecoveryPreNativeCreateDenialAuthority,
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
    ): RecoveryAuthorityConsumeResult {
        val issued = authority as? IssuedRecoveryPreNativeCreateDenialAuthority
            ?: return RecoveryAuthorityConsumeResult.MISMATCH
        if (issued.javaClass != IssuedRecoveryPreNativeCreateDenialAuthority::class.java ||
            issued.issuerIdentity !== issuerIdentity ||
            issued.denialGeneration <= 0L ||
            issued.recoveryBuildId != current.recoveryBuildId ||
            issued.mode != current.mode ||
            issued.key != key
        ) {
            return RecoveryAuthorityConsumeResult.MISMATCH
        }
        return if (issued.consumed.compareAndSet(false, true)) {
            RecoveryAuthorityConsumeResult.CONSUMED
        } else {
            RecoveryAuthorityConsumeResult.ALREADY_CONSUMED
        }
    }
}

private fun RecoveryJournalMode.toGameMode(): GameMode =
    when (this) {
        RecoveryJournalMode.SOLO -> GameMode.SOLO
        RecoveryJournalMode.DUAL -> GameMode.DUAL
    }
