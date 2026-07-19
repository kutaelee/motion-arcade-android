package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The full identity of one active route. Epoch and scope are deliberately redacted from logs. */
data class RecoveryAttemptRouteKey(
    val probeBaseScopeId: ProbeBaseScopeId,
    val attemptEpoch: ULong,
    val delegate: ProbeDelegate,
    val role: JournalRouteRole,
) {
    override fun toString(): String = "RecoveryAttemptRouteKey(delegate=$delegate,role=$role)"
}

/** One native route in the finite fallback grammar. */
data class RecoveryAttemptRoute(
    val delegate: ProbeDelegate,
    val role: JournalRouteRole,
)

enum class RecoveryAttemptRejection {
    INVALID_CURRENT_PAYLOAD,
    NEXT_STATE_INVALID,
    INITIAL_FRESH_REQUIRES_EMPTY_BASELINE,
    RECHECK_MODE_STORE_DELETE_REQUIRED,
    ACTIVE_ALREADY_PRESENT,
    NO_ACTIVE,
    ACTIVE_KEY_MISMATCH,
    ACTIVE_STATE_MISMATCH,
    PENDING_STORE_COMMIT_BLOCKS,
    MODE_CONTROL_BLOCKS,
    RETRY_CONTEXT_BLOCKS,
    BIT_ZERO_QUARANTINE_BLOCKS,
    EPOCH_OVERFLOW,
    CAPACITY_EXCEEDED,
    NO_ROUTE_AVAILABLE,
    NO_PROOF_PREFIX,
    PROOF_PREFIX_COMPLETE,
    ROUTE_BLOCKED,
    MANUAL_RETRY_TARGET_NOT_ELIGIBLE,
    ROUTE_PERMIT_PAYLOAD_MISMATCH,
    ROUTE_PERMIT_NOT_ISSUED,
    ROUTE_PERMIT_ROUTE_NOT_ALLOWED,
    ROUTE_PERMIT_ALREADY_CONSUMED,
    CLEAN_CLOSURE_AUTHORITY_REQUIRED,
    CLEAN_CLOSURE_AUTHORITY_MISMATCH,
    CLEAN_CLOSURE_AUTHORITY_REVOKED,
    CLEAN_CLOSURE_AUTHORITY_ALREADY_CONSUMED,
    PRE_NATIVE_DENIAL_AUTHORITY_REQUIRED,
    PRE_NATIVE_DENIAL_AUTHORITY_MISMATCH,
    PRE_NATIVE_DENIAL_AUTHORITY_ALREADY_CONSUMED,
    TERMINAL_PROOF_INVALID,
    TERMINAL_PROOF_MISMATCH,
    ATTEMPT_EPOCH_MISMATCH,
    ATTEMPT_NOT_FOUND,
    NOTHING_TO_RECOVER,
}

/**
 * A process-local continuation derived from one exact canonical payload. It is not a durable
 * receipt and does not authorize native work. The journal owner must first durably commit the
 * payload that produced it, then durably commit the reducer's next active payload before native
 * entry.
 */
class RecoveryRouteContinuation private constructor(
    internal val expectedPayloadSha256: Sha256Digest,
    internal val probeBaseScopeId: ProbeBaseScopeId,
    internal val attemptEpoch: ULong,
    allowedRoutes: List<RecoveryAttemptRoute>,
) {
    private val consumed = AtomicBoolean(false)

    val allowedRoutes: List<RecoveryAttemptRoute> =
        Collections.unmodifiableList(ArrayList(allowedRoutes))

    override fun toString(): String =
        "RecoveryRouteContinuation(allowedRoutes=$allowedRoutes,payload=redacted)"

    internal fun consumeOnce(): Boolean = consumed.compareAndSet(false, true)

    internal companion object {
        fun create(
            expectedPayloadSha256: Sha256Digest,
            probeBaseScopeId: ProbeBaseScopeId,
            attemptEpoch: ULong,
            allowedRoutes: List<RecoveryAttemptRoute>,
        ): RecoveryRouteContinuation =
            RecoveryRouteContinuation(
                expectedPayloadSha256 = expectedPayloadSha256,
                probeBaseScopeId = probeBaseScopeId,
                attemptEpoch = attemptEpoch,
                allowedRoutes = allowedRoutes,
            )
    }
}

sealed interface RecoveryAttemptEffect {
    /** The returned payload is only an intended state until the journal owner commits it. */
    data object PersistJournalBeforeFurtherAction : RecoveryAttemptEffect

    /** Native entry remains forbidden until the active payload is durably committed. */
    data class NativeEntryRequiresCommittedJournal(
        val route: RecoveryAttemptRoute,
    ) : RecoveryAttemptEffect

    /** No metric or rank from the interrupted/older process may be reused. */
    data object DiscardProcessLocalMetrics : RecoveryAttemptEffect

    /** Candidate routes required by the new process must be measured again. */
    data object RerunProcessLocalCandidates : RecoveryAttemptEffect

    data class ContinueWith(
        val continuation: RecoveryRouteContinuation,
    ) : RecoveryAttemptEffect

    data object AttemptWindowComplete : RecoveryAttemptEffect

    data object QuarantineCacheDeletionRequired : RecoveryAttemptEffect

    /** Clean bytes are not write-authorized until the opaque post-seal fence is resolved. */
    data object ResolveCleanPersistenceFenceBeforeCheckedReplace : RecoveryAttemptEffect
}

sealed interface RecoveryAttemptReductionResult {
    /**
     * A pure intended transition. Applied never means that bytes were written or that native work
     * is authorized; the listed effects describe the external ordering still required.
     */
    class Applied(
        val next: RecoveryJournalPayloadV5,
        effects: List<RecoveryAttemptEffect>,
        val cleanPersistenceFence: RecoveryCleanJournalPersistenceFence? = null,
    ) : RecoveryAttemptReductionResult {
        val effects: List<RecoveryAttemptEffect> =
            Collections.unmodifiableList(ArrayList(effects))

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Applied && next == other.next && effects == other.effects &&
                    cleanPersistenceFence === other.cleanPersistenceFence

        override fun hashCode(): Int =
            31 * (31 * next.hashCode() + effects.hashCode()) +
                System.identityHashCode(cleanPersistenceFence)

        override fun toString(): String =
            "Applied(next=$next,effects=${effects.map { it::class.simpleName }})"
    }

    data class Rejected(
        val reason: RecoveryAttemptRejection,
    ) : RecoveryAttemptReductionResult
}

/**
 * Pure RecoveryJournalV5 attempt-lifecycle reducer.
 *
 * This object performs no disk, ModeStore, native, clock, or receipt work. Both the current and
 * intended next payload are checked by [RecoveryJournalV5Codec]. Caller-controlled invalid state
 * is returned as a finite rejection rather than thrown.
 */
object RecoveryJournalAttemptReducer {
    private enum class ContinuationTransitionKind {
        CLEAN_INTERMEDIATE,
        CLEAN_TERMINAL,
    }

    private data class ContinuationBasis(
        val priorActive: ActiveV5,
        val transitionKind: ContinuationTransitionKind,
    )

    private val issuedContinuations =
        Collections.synchronizedMap(
            WeakHashMap<RecoveryRouteContinuation, ContinuationBasis>(),
        )

    fun startInitialFresh(
        current: RecoveryJournalPayloadV5,
        probeBaseScopeId: ProbeBaseScopeId,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            if (!isCanonicalEmptyBaseline(current)) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.INITIAL_FRESH_REQUIRES_EMPTY_BASELINE,
                )
            }
            val attemptEpoch = checkedNextEpoch(current.lastEpoch)
                ?: return@withValidCurrent rejected(RecoveryAttemptRejection.EPOCH_OVERFLOW)
            applyActiveRoute(
                current = current,
                probeBaseScopeId = probeBaseScopeId,
                attemptEpoch = attemptEpoch,
                route = RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.CANDIDATE),
                retryContextId = null,
                lastEpoch = attemptEpoch,
                entries = deleteRetryZeroTerminals(current.entries, probeBaseScopeId),
                prefixEffects = emptyList(),
            )
        }

    /**
     * Explicit recheck needs an exact durable ModeStore record-deletion result. No boolean or
     * caller-constructible receipt is accepted by this pure reducer, so recheck can never create
     * active work here.
     */
    fun requestExplicitRecheck(
        current: RecoveryJournalPayloadV5,
        @Suppress("UNUSED_PARAMETER") probeBaseScopeId: ProbeBaseScopeId,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            rejected(RecoveryAttemptRejection.RECHECK_MODE_STORE_DELETE_REQUIRED)
        }

    fun resumeProofPrefix(
        current: RecoveryJournalPayloadV5,
        probeBaseScopeId: ProbeBaseScopeId,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            blockingReason(current)?.let { return@withValidCurrent rejected(it) }
            if (current.active != null) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ACTIVE_ALREADY_PRESENT)
            }
            val terminals = exactBaseTerminals(current, probeBaseScopeId)
            if (terminals.isEmpty()) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.NO_PROOF_PREFIX)
            }
            val epochs = terminals.map { it.attemptEpoch }.distinct()
            if (epochs.size != 1) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ATTEMPT_EPOCH_MISMATCH)
            }
            val attemptEpoch = epochs[0]
            val route = resumedRoute(terminals)
                ?: return@withValidCurrent rejected(RecoveryAttemptRejection.PROOF_PREFIX_COMPLETE)
            if (isRouteBlocked(current.entries, probeBaseScopeId, route.delegate)) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ROUTE_BLOCKED)
            }
            applyActiveRoute(
                current = current,
                probeBaseScopeId = probeBaseScopeId,
                attemptEpoch = attemptEpoch,
                route = route,
                retryContextId = null,
                lastEpoch = current.lastEpoch,
                entries = current.entries,
                prefixEffects =
                    listOf(
                        RecoveryAttemptEffect.DiscardProcessLocalMetrics,
                        RecoveryAttemptEffect.RerunProcessLocalCandidates,
                    ),
            )
        }

    fun authorizeManualRetry(
        current: RecoveryJournalPayloadV5,
        probeBaseScopeId: ProbeBaseScopeId,
        targetDelegate: ProbeDelegate,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            blockingReason(current)?.let { return@withValidCurrent rejected(it) }
            if (current.active != null) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ACTIVE_ALREADY_PRESENT)
            }
            if (targetDelegate !in NATIVE_DELEGATES) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.MANUAL_RETRY_TARGET_NOT_ELIGIBLE,
                )
            }
            val target = current.entries.singleOrNull {
                it.probeBaseScopeId == probeBaseScopeId &&
                    it.delegate == targetDelegate &&
                    it.state == JournalEntryState.QUARANTINED &&
                    !it.retryUsed &&
                    it.cacheInvalidated &&
                    it.retryContextId == null &&
                    it.terminalEvidence == null
            } ?: return@withValidCurrent rejected(
                RecoveryAttemptRejection.MANUAL_RETRY_TARGET_NOT_ELIGIBLE,
            )
            val attemptEpoch = checkedNextEpoch(current.lastEpoch)
                ?: return@withValidCurrent rejected(RecoveryAttemptRejection.EPOCH_OVERFLOW)
            val originHash = when (val result = RecoveryJournalV5Codec.entrySha256(target)) {
                is CapabilityDomainResult.Valid -> result.value
                is CapabilityDomainResult.Invalid -> {
                    return@withValidCurrent rejected(RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD)
                }
            }
            val context =
                ManualRetryContextV1(
                    probeBaseScopeId = probeBaseScopeId,
                    targetDelegate = targetDelegate,
                    originQuarantineEpoch = target.attemptEpoch,
                    originQuarantineReason = target.reason,
                    originQuarantineEntrySha256 = originHash,
                    attemptEpoch = attemptEpoch,
                )
            val contextId = when (val result = RecoveryJournalV5Codec.manualRetryContextId(context)) {
                is CapabilityDomainResult.Valid -> result.value
                is CapabilityDomainResult.Invalid -> {
                    return@withValidCurrent rejected(RecoveryAttemptRejection.NEXT_STATE_INVALID)
                }
            }
            val marker =
                JournalEntryV5(
                    probeBaseScopeId = probeBaseScopeId,
                    attemptEpoch = attemptEpoch,
                    delegate = targetDelegate,
                    state = JournalEntryState.MANUAL_RETRY_ACTIVE,
                    reason = JournalReason.MANUAL_RETRY_AUTHORIZED,
                    retryUsed = true,
                    cacheInvalidated = true,
                    retryContextId = contextId,
                    terminalEvidence = null,
                )
            val retained = deleteRetryZeroTerminals(current.entries, probeBaseScopeId)
                .map { entry -> if (entry == target) marker else entry }
            val route = firstCanonicalRoute(retained, probeBaseScopeId)
                ?: return@withValidCurrent rejected(RecoveryAttemptRejection.NO_ROUTE_AVAILABLE)
            applyActiveRoute(
                current = current.copy(manualRetryContext = context),
                probeBaseScopeId = probeBaseScopeId,
                attemptEpoch = attemptEpoch,
                route = route,
                retryContextId = contextId,
                lastEpoch = attemptEpoch,
                entries = retained,
                prefixEffects =
                    listOf(
                        RecoveryAttemptEffect.DiscardProcessLocalMetrics,
                        RecoveryAttemptEffect.RerunProcessLocalCandidates,
                    ),
            )
        }

    fun markTeardownPending(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            externalRecoveryBlocker(current)?.let { return@withValidCurrent rejected(it) }
            val active = current.active
                ?: return@withValidCurrent rejected(RecoveryAttemptRejection.NO_ACTIVE)
            if (!active.matches(key)) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ACTIVE_KEY_MISMATCH)
            }
            if (active.state != JournalActiveState.ACTIVE) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ACTIVE_STATE_MISMATCH)
            }
            applyNext(
                current.copy(active = active.copy(state = JournalActiveState.TEARDOWN_PENDING)),
                listOf(RecoveryAttemptEffect.PersistJournalBeforeFurtherAction),
            )
        }

    fun completeCleanIntermediate(
        current: RecoveryJournalPayloadV5,
        @Suppress("UNUSED_PARAMETER") key: RecoveryAttemptRouteKey,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REQUIRED)
        }

    fun completeCleanIntermediate(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        authority: RecoveryCleanClosureAuthority,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            val intended = intendedCleanIntermediate(current, key)
            if (intended !is RecoveryAttemptReductionResult.Applied) {
                return@withValidCurrent intended
            }
            when (
                RecoveryCleanClosureBoundary.consumeAuthority(
                    authority,
                    current,
                    key,
                    com.motionarcade.vision.capability.runtime.RouteAttemptOutcome.MEASURED,
                    null,
                )
            ) {
                RecoveryAuthorityConsumeResult.CONSUMED ->
                    bindCleanPersistenceFence(intended, current, authority)
                RecoveryAuthorityConsumeResult.MISMATCH ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH)
                RecoveryAuthorityConsumeResult.REVOKED ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED)
                RecoveryAuthorityConsumeResult.ALREADY_CONSUMED ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_ALREADY_CONSUMED)
            }
        }

    fun beginPermittedRoute(
        current: RecoveryJournalPayloadV5,
        continuation: RecoveryRouteContinuation,
        route: RecoveryAttemptRoute,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            externalRecoveryBlocker(current)?.let { return@withValidCurrent rejected(it) }
            if (current.entries.any {
                    it.state == JournalEntryState.QUARANTINED && !it.cacheInvalidated
                }
            ) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.BIT_ZERO_QUARANTINE_BLOCKS,
                )
            }
            if (current.active != null) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ACTIVE_ALREADY_PRESENT)
            }
            val digest = payloadSha256(current)
                ?: return@withValidCurrent rejected(RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD)
            val basis = issuedContinuations[continuation]
                ?: return@withValidCurrent rejected(
                    RecoveryAttemptRejection.ROUTE_PERMIT_NOT_ISSUED,
                )
            if (digest != continuation.expectedPayloadSha256) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.ROUTE_PERMIT_PAYLOAD_MISMATCH,
                )
            }
            if (basis.priorActive.probeBaseScopeId != continuation.probeBaseScopeId ||
                basis.priorActive.attemptEpoch != continuation.attemptEpoch
            ) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.ROUTE_PERMIT_PAYLOAD_MISMATCH,
                )
            }
            val canonicalRoutes =
                when (basis.transitionKind) {
                    ContinuationTransitionKind.CLEAN_INTERMEDIATE ->
                        routesAfterCleanIntermediate(current, basis.priorActive)
                    ContinuationTransitionKind.CLEAN_TERMINAL ->
                        routesAfterCleanTerminal(current, basis.priorActive)
                }
            if (continuation.allowedRoutes != canonicalRoutes || route !in canonicalRoutes) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.ROUTE_PERMIT_ROUTE_NOT_ALLOWED,
                )
            }
            val context = current.manualRetryContext
            val contextId = if (context == null) {
                null
            } else {
                if (context.probeBaseScopeId != continuation.probeBaseScopeId ||
                    context.attemptEpoch != continuation.attemptEpoch
                ) {
                    return@withValidCurrent rejected(RecoveryAttemptRejection.RETRY_CONTEXT_BLOCKS)
                }
                when (val result = RecoveryJournalV5Codec.manualRetryContextId(context)) {
                    is CapabilityDomainResult.Valid -> result.value
                    is CapabilityDomainResult.Invalid -> {
                        return@withValidCurrent rejected(
                            RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD,
                        )
                    }
                }
            }
            val intended = applyActiveRoute(
                current = current,
                probeBaseScopeId = continuation.probeBaseScopeId,
                attemptEpoch = continuation.attemptEpoch,
                route = route,
                retryContextId = contextId,
                lastEpoch = current.lastEpoch,
                entries = current.entries,
                prefixEffects = emptyList(),
            )
            if (intended !is RecoveryAttemptReductionResult.Applied) {
                return@withValidCurrent intended
            }
            if (!continuation.consumeOnce()) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.ROUTE_PERMIT_ALREADY_CONSUMED,
                )
            }
            intended
        }

    fun recordCleanTerminal(
        current: RecoveryJournalPayloadV5,
        @Suppress("UNUSED_PARAMETER") key: RecoveryAttemptRouteKey,
        proof: TerminalProofV1,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            val reason =
                if (proof.reason == TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                    RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_REQUIRED
                } else {
                    RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REQUIRED
                }
            rejected(reason)
        }

    fun recordCleanTerminal(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        proof: TerminalProofV1,
        authority: RecoveryCleanClosureAuthority,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            if (proof.reason == TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
                )
            }
            val intended = intendedCleanTerminal(current, key, proof)
            if (intended !is RecoveryAttemptReductionResult.Applied) {
                return@withValidCurrent intended
            }
            when (
                RecoveryCleanClosureBoundary.consumeAuthority(
                    authority,
                    current,
                    key,
                    com.motionarcade.vision.capability.runtime.RouteAttemptOutcome.CLEAN_TERMINAL,
                    proof,
                )
            ) {
                RecoveryAuthorityConsumeResult.CONSUMED ->
                    bindCleanPersistenceFence(intended, current, authority)
                RecoveryAuthorityConsumeResult.MISMATCH ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH)
                RecoveryAuthorityConsumeResult.REVOKED ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED)
                RecoveryAuthorityConsumeResult.ALREADY_CONSUMED ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_ALREADY_CONSUMED)
            }
        }

    fun recordCleanTerminal(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        proof: TerminalProofV1,
        authority: RecoveryPreNativeCreateDenialAuthority,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            if (proof.reason != TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_MISMATCH,
                )
            }
            val intended = intendedCleanTerminal(current, key, proof)
            if (intended !is RecoveryAttemptReductionResult.Applied) {
                return@withValidCurrent intended
            }
            when (
                RecoveryPreNativeCreateDenialBoundary.consumeAuthority(
                    authority,
                    current,
                    key,
                )
            ) {
                RecoveryAuthorityConsumeResult.CONSUMED -> intended
                RecoveryAuthorityConsumeResult.MISMATCH ->
                    rejected(RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_MISMATCH)
                RecoveryAuthorityConsumeResult.REVOKED ->
                    rejected(RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_MISMATCH)
                RecoveryAuthorityConsumeResult.ALREADY_CONSUMED ->
                    rejected(RecoveryAttemptRejection.PRE_NATIVE_DENIAL_AUTHORITY_ALREADY_CONSUMED)
            }
        }

    fun cancelAfterCleanup(
        current: RecoveryJournalPayloadV5,
        @Suppress("UNUSED_PARAMETER") key: RecoveryAttemptRouteKey,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REQUIRED)
        }

    fun cancelAfterCleanup(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        authority: RecoveryCleanClosureAuthority,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            val intended = intendedCancelAfterCleanup(current, key)
            if (intended !is RecoveryAttemptReductionResult.Applied) {
                return@withValidCurrent intended
            }
            when (
                RecoveryCleanClosureBoundary.consumeAuthority(
                    authority,
                    current,
                    key,
                    com.motionarcade.vision.capability.runtime.RouteAttemptOutcome.INCOMPLETE,
                    null,
                )
            ) {
                RecoveryAuthorityConsumeResult.CONSUMED ->
                    bindCleanPersistenceFence(intended, current, authority)
                RecoveryAuthorityConsumeResult.MISMATCH ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH)
                RecoveryAuthorityConsumeResult.REVOKED ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED)
                RecoveryAuthorityConsumeResult.ALREADY_CONSUMED ->
                    rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_ALREADY_CONSUMED)
            }
        }

    fun abandonBetweenRuntimes(
        current: RecoveryJournalPayloadV5,
        probeBaseScopeId: ProbeBaseScopeId,
        attemptEpoch: ULong,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            externalRecoveryBlocker(current)?.let {
                return@withValidCurrent rejected(it)
            }
            if (current.active != null) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.ACTIVE_ALREADY_PRESENT)
            }
            val context = current.manualRetryContext
            if (context != null) {
                if (context.probeBaseScopeId != probeBaseScopeId ||
                    context.attemptEpoch != attemptEpoch
                ) {
                    return@withValidCurrent rejected(RecoveryAttemptRejection.RETRY_CONTEXT_BLOCKS)
                }
                consumeManualAttempt(
                    current = current,
                    active = null,
                    reason = JournalReason.MANUAL_RETRY_ABANDONED,
                )
            } else {
                val retained = current.entries.filterNot {
                    it.probeBaseScopeId == probeBaseScopeId &&
                        it.attemptEpoch == attemptEpoch &&
                        it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT &&
                        !it.retryUsed
                }
                if (retained.size == current.entries.size) {
                    return@withValidCurrent rejected(RecoveryAttemptRejection.ATTEMPT_NOT_FOUND)
                }
                applyNext(
                    current.copy(entries = retained),
                    listOf(RecoveryAttemptEffect.PersistJournalBeforeFurtherAction),
                )
            }
        }

    fun recoverInterrupted(
        current: RecoveryJournalPayloadV5,
    ): RecoveryAttemptReductionResult =
        withValidCurrent(current) {
            if (current.pendingStoreCommit != null) {
                return@withValidCurrent rejected(
                    RecoveryAttemptRejection.PENDING_STORE_COMMIT_BLOCKS,
                )
            }
            if (current.modeControl != ModeControlV5.None) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.MODE_CONTROL_BLOCKS)
            }
            val active = current.active
            if (active?.retryUsed == true || current.manualRetryContext != null) {
                return@withValidCurrent consumeManualAttempt(
                    current = current,
                    active = active,
                    reason = JournalReason.MANUAL_RETRY_INTERRUPTED,
                )
            }
            if (active == null) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.NOTHING_TO_RECOVER)
            }
            if (current.entries.size >= RecoveryJournalV5Codec.MAXIMUM_ENTRIES) {
                return@withValidCurrent rejected(RecoveryAttemptRejection.CAPACITY_EXCEEDED)
            }
            val quarantine =
                JournalEntryV5(
                    probeBaseScopeId = active.probeBaseScopeId,
                    attemptEpoch = active.attemptEpoch,
                    delegate = active.delegate,
                    state = JournalEntryState.QUARANTINED,
                    reason =
                        if (active.state == JournalActiveState.ACTIVE) {
                            JournalReason.RECOVERED_ACTIVE
                        } else {
                            JournalReason.RECOVERED_TEARDOWN_PENDING
                        },
                    retryUsed = false,
                    cacheInvalidated = false,
                    retryContextId = null,
                    terminalEvidence = null,
                )
            applyNext(
                current.copy(
                    active = null,
                    entries = sortEntries(current.entries + quarantine),
                ),
                listOf(
                    RecoveryAttemptEffect.PersistJournalBeforeFurtherAction,
                    RecoveryAttemptEffect.QuarantineCacheDeletionRequired,
                ),
            )
        }

    private fun intendedCleanIntermediate(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
    ): RecoveryAttemptReductionResult {
        externalRecoveryBlocker(current)?.let { return rejected(it) }
        val active = matchingTeardown(current, key)
            ?: return matchingTeardownRejection(current, key)
        val next = current.copy(active = null)
        if (!validatePayload(next)) {
            return rejected(RecoveryAttemptRejection.NEXT_STATE_INVALID)
        }
        val routes = routesAfterCleanIntermediate(current, active)
        val effects = mutableListOf<RecoveryAttemptEffect>(
            RecoveryAttemptEffect.PersistJournalBeforeFurtherAction,
        )
        if (routes.isEmpty()) {
            effects += RecoveryAttemptEffect.AttemptWindowComplete
        } else {
            val continuation =
                continuationEffect(
                    next,
                    active,
                    routes,
                    ContinuationTransitionKind.CLEAN_INTERMEDIATE,
                )
                ?: return rejected(RecoveryAttemptRejection.NEXT_STATE_INVALID)
            effects += continuation
        }
        return applied(next, effects)
    }

    private fun intendedCleanTerminal(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        proof: TerminalProofV1,
    ): RecoveryAttemptReductionResult {
        externalRecoveryBlocker(current)?.let { return rejected(it) }
        if (!validateTerminalProof(proof)) {
            return rejected(RecoveryAttemptRejection.TERMINAL_PROOF_INVALID)
        }
        val active = matchingTeardown(current, key)
            ?: return matchingTeardownRejection(current, key)
        if (proof.delegate != active.delegate || proof.role != active.role) {
            return rejected(RecoveryAttemptRejection.TERMINAL_PROOF_MISMATCH)
        }
        val terminal =
            JournalEntryV5(
                probeBaseScopeId = active.probeBaseScopeId,
                attemptEpoch = active.attemptEpoch,
                delegate = active.delegate,
                state = JournalEntryState.TERMINAL_THIS_ATTEMPT,
                reason = proof.reason.toJournalReason(),
                retryUsed = active.retryUsed,
                cacheInvalidated = true,
                retryContextId = active.retryContextId,
                terminalEvidence = proof,
            )
        val sameKeyIndex = current.entries.indexOfFirst {
            it.probeBaseScopeId == active.probeBaseScopeId && it.delegate == active.delegate
        }
        val entries = ArrayList(current.entries)
        if (sameKeyIndex >= 0) {
            entries[sameKeyIndex] = terminal
        } else {
            if (entries.size >= RecoveryJournalV5Codec.MAXIMUM_ENTRIES) {
                return rejected(RecoveryAttemptRejection.CAPACITY_EXCEEDED)
            }
            entries += terminal
        }
        val next = current.copy(active = null, entries = sortEntries(entries))
        if (!validatePayload(next)) {
            return rejected(RecoveryAttemptRejection.NEXT_STATE_INVALID)
        }
        val routes = routesAfterCleanTerminal(next, active)
        val effects = mutableListOf<RecoveryAttemptEffect>(
            RecoveryAttemptEffect.PersistJournalBeforeFurtherAction,
        )
        if (routes.isEmpty()) {
            effects += RecoveryAttemptEffect.AttemptWindowComplete
        } else {
            val continuation =
                continuationEffect(
                    next,
                    active,
                    routes,
                    ContinuationTransitionKind.CLEAN_TERMINAL,
                )
                ?: return rejected(RecoveryAttemptRejection.NEXT_STATE_INVALID)
            effects += continuation
        }
        return applied(next, effects)
    }

    private fun intendedCancelAfterCleanup(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
    ): RecoveryAttemptReductionResult {
        externalRecoveryBlocker(current)?.let { return rejected(it) }
        val active = matchingTeardown(current, key)
            ?: return matchingTeardownRejection(current, key)
        return if (active.retryUsed) {
            consumeManualAttempt(
                current = current,
                active = active,
                reason = JournalReason.MANUAL_RETRY_ABORTED,
            )
        } else {
            val next =
                current.copy(
                    active = null,
                    entries =
                        current.entries.filterNot {
                            it.probeBaseScopeId == active.probeBaseScopeId &&
                                it.attemptEpoch == active.attemptEpoch &&
                                it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT &&
                                !it.retryUsed
                        },
                )
            applyNext(
                next,
                listOf(RecoveryAttemptEffect.PersistJournalBeforeFurtherAction),
            )
        }
    }

    private fun applyActiveRoute(
        current: RecoveryJournalPayloadV5,
        probeBaseScopeId: ProbeBaseScopeId,
        attemptEpoch: ULong,
        route: RecoveryAttemptRoute,
        retryContextId: Sha256Digest?,
        lastEpoch: ULong,
        entries: List<JournalEntryV5>,
        prefixEffects: List<RecoveryAttemptEffect>,
    ): RecoveryAttemptReductionResult {
        if (route.delegate !in NATIVE_DELEGATES) {
            return rejected(RecoveryAttemptRejection.NO_ROUTE_AVAILABLE)
        }
        if (entries.any { it.state == JournalEntryState.QUARANTINED && !it.cacheInvalidated }) {
            return rejected(RecoveryAttemptRejection.BIT_ZERO_QUARANTINE_BLOCKS)
        }
        val sameKey = entries.any {
            it.probeBaseScopeId == probeBaseScopeId && it.delegate == route.delegate
        }
        if (entries.size + (if (sameKey) 0 else 1) > RecoveryJournalV5Codec.MAXIMUM_ENTRIES) {
            return rejected(RecoveryAttemptRejection.CAPACITY_EXCEEDED)
        }
        val active =
            ActiveV5(
                probeBaseScopeId = probeBaseScopeId,
                attemptEpoch = attemptEpoch,
                delegate = route.delegate,
                role = route.role,
                state = JournalActiveState.ACTIVE,
                retryUsed = retryContextId != null,
                retryContextId = retryContextId,
            )
        val next =
            current.copy(
                lastEpoch = lastEpoch,
                active = active,
                entries = sortEntries(entries),
            )
        return applyNext(
            next,
            prefixEffects +
                listOf(
                    RecoveryAttemptEffect.PersistJournalBeforeFurtherAction,
                    RecoveryAttemptEffect.NativeEntryRequiresCommittedJournal(route),
                ),
        )
    }

    private fun consumeManualAttempt(
        current: RecoveryJournalPayloadV5,
        active: ActiveV5?,
        reason: JournalReason,
    ): RecoveryAttemptReductionResult {
        val context = current.manualRetryContext
            ?: return rejected(RecoveryAttemptRejection.RETRY_CONTEXT_BLOCKS)
        val contextId = when (val result = RecoveryJournalV5Codec.manualRetryContextId(context)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> {
                return rejected(RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD)
            }
        }
        if (active != null &&
            (active.probeBaseScopeId != context.probeBaseScopeId ||
                active.attemptEpoch != context.attemptEpoch ||
                !active.retryUsed ||
                active.retryContextId != contextId)
        ) {
            return rejected(RecoveryAttemptRejection.RETRY_CONTEXT_BLOCKS)
        }
        val converted = ArrayList<JournalEntryV5>()
        current.entries.forEach { entry ->
            val exactAttempt =
                entry.probeBaseScopeId == context.probeBaseScopeId &&
                    entry.attemptEpoch == context.attemptEpoch
            when {
                exactAttempt &&
                    entry.state == JournalEntryState.TERMINAL_THIS_ATTEMPT &&
                    !entry.retryUsed -> Unit
                exactAttempt &&
                    entry.retryUsed &&
                    entry.retryContextId == contextId &&
                    entry.state in LIVE_MANUAL_ENTRY_STATES ->
                    converted += entry.toConsumed(reason, contextId)
                else -> converted += entry
            }
        }
        if (active != null && converted.none {
                it.probeBaseScopeId == active.probeBaseScopeId && it.delegate == active.delegate
            }
        ) {
            if (converted.size >= RecoveryJournalV5Codec.MAXIMUM_ENTRIES) {
                return rejected(RecoveryAttemptRejection.CAPACITY_EXCEEDED)
            }
            converted +=
                JournalEntryV5(
                    probeBaseScopeId = active.probeBaseScopeId,
                    attemptEpoch = active.attemptEpoch,
                    delegate = active.delegate,
                    state = JournalEntryState.RETRY_CONSUMED,
                    reason = reason,
                    retryUsed = true,
                    cacheInvalidated = true,
                    retryContextId = contextId,
                    terminalEvidence = null,
                )
        }
        return applyNext(
            current.copy(
                active = null,
                manualRetryContext = null,
                entries = sortEntries(converted),
            ),
            listOf(RecoveryAttemptEffect.PersistJournalBeforeFurtherAction),
        )
    }

    private fun JournalEntryV5.toConsumed(
        reason: JournalReason,
        contextId: Sha256Digest,
    ): JournalEntryV5 =
        JournalEntryV5(
            probeBaseScopeId = probeBaseScopeId,
            attemptEpoch = attemptEpoch,
            delegate = delegate,
            state = JournalEntryState.RETRY_CONSUMED,
            reason = reason,
            retryUsed = true,
            cacheInvalidated = true,
            retryContextId = contextId,
            terminalEvidence = null,
        )

    private fun routesAfterCleanIntermediate(
        current: RecoveryJournalPayloadV5,
        active: ActiveV5,
    ): List<RecoveryAttemptRoute> =
        when (active.role) {
            JournalRouteRole.CANDIDATE ->
                if (active.delegate == ProbeDelegate.CPU) {
                    if (hasTerminal(current, active, ProbeDelegate.GPU) ||
                        isRouteBlocked(current.entries, active.probeBaseScopeId, ProbeDelegate.GPU)
                    ) {
                        listOf(RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED))
                    } else {
                        listOf(RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE))
                    }
                } else if (hasTerminal(current, active, ProbeDelegate.CPU) ||
                    isRouteBlocked(current.entries, active.probeBaseScopeId, ProbeDelegate.CPU)
                ) {
                    listOf(RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED))
                } else {
                    listOf(
                        RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED),
                        RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED),
                    )
                }
            JournalRouteRole.SELECTED -> emptyList()
            JournalRouteRole.FALLBACK_CANDIDATE ->
                listOf(
                    RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.FALLBACK_SELECTED),
                )
            JournalRouteRole.FALLBACK_SELECTED -> emptyList()
        }

    private fun routesAfterCleanTerminal(
        next: RecoveryJournalPayloadV5,
        priorActive: ActiveV5,
    ): List<RecoveryAttemptRoute> =
        when (priorActive.role) {
            JournalRouteRole.CANDIDATE ->
                if (priorActive.delegate == ProbeDelegate.CPU) {
                    if (hasTerminal(next, priorActive, ProbeDelegate.GPU) ||
                        isRouteBlocked(next.entries, priorActive.probeBaseScopeId, ProbeDelegate.GPU)
                    ) {
                        emptyList()
                    } else {
                        listOf(RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE))
                    }
                } else if (hasTerminal(next, priorActive, ProbeDelegate.CPU) ||
                    isRouteBlocked(next.entries, priorActive.probeBaseScopeId, ProbeDelegate.CPU)
                ) {
                    emptyList()
                } else {
                    listOf(RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED))
                }
            JournalRouteRole.SELECTED ->
                if (priorActive.delegate == ProbeDelegate.GPU &&
                    !hasTerminal(next, priorActive, ProbeDelegate.CPU) &&
                    !isRouteBlocked(
                        next.entries,
                        priorActive.probeBaseScopeId,
                        ProbeDelegate.CPU,
                    )
                ) {
                    listOf(
                        RecoveryAttemptRoute(
                            ProbeDelegate.CPU,
                            JournalRouteRole.FALLBACK_CANDIDATE,
                        ),
                    )
                } else {
                    emptyList()
                }
            JournalRouteRole.FALLBACK_CANDIDATE,
            JournalRouteRole.FALLBACK_SELECTED,
            -> emptyList()
        }

    private fun continuationEffect(
        payload: RecoveryJournalPayloadV5,
        active: ActiveV5,
        routes: List<RecoveryAttemptRoute>,
        transitionKind: ContinuationTransitionKind,
    ): RecoveryAttemptEffect.ContinueWith? {
        val digest = payloadSha256(payload) ?: return null
        val continuation =
            RecoveryRouteContinuation.create(
                expectedPayloadSha256 = digest,
                probeBaseScopeId = active.probeBaseScopeId,
                attemptEpoch = active.attemptEpoch,
                allowedRoutes = routes,
            )
        issuedContinuations[continuation] =
            ContinuationBasis(
                priorActive = active,
                transitionKind = transitionKind,
            )
        return RecoveryAttemptEffect.ContinueWith(
            continuation,
        )
    }

    private fun resumedRoute(terminals: List<JournalEntryV5>): RecoveryAttemptRoute? {
        val proofs = terminals.mapNotNull { it.terminalEvidence }
        if (proofs.size != 1) return null
        val proof = proofs[0]
        return when {
            proof.delegate == ProbeDelegate.CPU && proof.role == JournalRouteRole.CANDIDATE ->
                RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE)
            proof.delegate == ProbeDelegate.GPU && proof.role == JournalRouteRole.CANDIDATE ->
                RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.CANDIDATE)
            proof.delegate == ProbeDelegate.GPU && proof.role == JournalRouteRole.SELECTED ->
                RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.FALLBACK_CANDIDATE)
            else -> null
        }
    }

    private fun firstCanonicalRoute(
        entries: List<JournalEntryV5>,
        probeBaseScopeId: ProbeBaseScopeId,
    ): RecoveryAttemptRoute? =
        when {
            !isRouteBlocked(entries, probeBaseScopeId, ProbeDelegate.CPU) ->
                RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.CANDIDATE)
            !isRouteBlocked(entries, probeBaseScopeId, ProbeDelegate.GPU) ->
                RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE)
            else -> null
        }

    private fun blockingReason(
        current: RecoveryJournalPayloadV5,
    ): RecoveryAttemptRejection? =
        when {
            current.pendingStoreCommit != null ->
                RecoveryAttemptRejection.PENDING_STORE_COMMIT_BLOCKS
            current.modeControl != ModeControlV5.None -> RecoveryAttemptRejection.MODE_CONTROL_BLOCKS
            current.manualRetryContext != null ->
                RecoveryAttemptRejection.RETRY_CONTEXT_BLOCKS
            current.entries.any {
                it.state == JournalEntryState.QUARANTINED && !it.cacheInvalidated
            } -> RecoveryAttemptRejection.BIT_ZERO_QUARANTINE_BLOCKS
            else -> null
        }

    private fun externalRecoveryBlocker(
        current: RecoveryJournalPayloadV5,
    ): RecoveryAttemptRejection? =
        when {
            current.pendingStoreCommit != null ->
                RecoveryAttemptRejection.PENDING_STORE_COMMIT_BLOCKS
            current.modeControl != ModeControlV5.None -> RecoveryAttemptRejection.MODE_CONTROL_BLOCKS
            else -> null
        }

    private fun matchingTeardown(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
    ): ActiveV5? {
        val active = current.active ?: return null
        return active.takeIf { it.matches(key) && it.state == JournalActiveState.TEARDOWN_PENDING }
    }

    private fun matchingTeardownRejection(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
    ): RecoveryAttemptReductionResult.Rejected {
        val active = current.active
            ?: return rejected(RecoveryAttemptRejection.NO_ACTIVE)
        return if (!active.matches(key)) {
            rejected(RecoveryAttemptRejection.ACTIVE_KEY_MISMATCH)
        } else {
            rejected(RecoveryAttemptRejection.ACTIVE_STATE_MISMATCH)
        }
    }

    private fun ActiveV5.matches(key: RecoveryAttemptRouteKey): Boolean =
        probeBaseScopeId == key.probeBaseScopeId &&
            attemptEpoch == key.attemptEpoch &&
            delegate == key.delegate &&
            role == key.role

    private fun hasTerminal(
        payload: RecoveryJournalPayloadV5,
        active: ActiveV5,
        delegate: ProbeDelegate,
    ): Boolean =
        payload.entries.any {
            it.probeBaseScopeId == active.probeBaseScopeId &&
                it.attemptEpoch == active.attemptEpoch &&
                it.delegate == delegate &&
                it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT
        }

    private fun isRouteBlocked(
        entries: List<JournalEntryV5>,
        probeBaseScopeId: ProbeBaseScopeId,
        delegate: ProbeDelegate,
    ): Boolean =
        entries.any {
            it.probeBaseScopeId == probeBaseScopeId &&
                it.delegate == delegate &&
                it.state in BLOCKING_ENTRY_STATES
        }

    private fun exactBaseTerminals(
        current: RecoveryJournalPayloadV5,
        probeBaseScopeId: ProbeBaseScopeId,
    ): List<JournalEntryV5> =
        current.entries.filter {
            it.probeBaseScopeId == probeBaseScopeId &&
                it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT &&
                !it.retryUsed
        }

    private fun deleteRetryZeroTerminals(
        entries: List<JournalEntryV5>,
        probeBaseScopeId: ProbeBaseScopeId,
    ): List<JournalEntryV5> =
        entries.filterNot {
            it.probeBaseScopeId == probeBaseScopeId &&
                it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT &&
                !it.retryUsed
        }

    private fun sortEntries(entries: List<JournalEntryV5>): List<JournalEntryV5> =
        entries.sortedWith { left, right ->
            val scope =
                CanonicalManifestCodec.compareUnsigned(
                    left.probeBaseScopeId.digest.copyBytes(),
                    right.probeBaseScopeId.digest.copyBytes(),
                )
            if (scope != 0) scope else left.delegate.wireValue.compareTo(right.delegate.wireValue)
        }

    private fun payloadSha256(value: RecoveryJournalPayloadV5): Sha256Digest? =
        when (val result = RecoveryJournalV5Codec.encodePayload(value)) {
            is CapabilityDomainResult.Valid -> CanonicalManifestCodec.sha256(result.value)
            is CapabilityDomainResult.Invalid -> null
        }

    private fun validatePayload(value: RecoveryJournalPayloadV5): Boolean =
        RecoveryJournalV5Codec.encodePayload(value) is CapabilityDomainResult.Valid

    private fun validateTerminalProof(value: TerminalProofV1): Boolean =
        RecoveryJournalV5Codec.encodeTerminalProof(value) is CapabilityDomainResult.Valid

    private fun TerminalProofReason.toJournalReason(): JournalReason =
        when (this) {
            TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY ->
                JournalReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY
            TerminalProofReason.DETECT_EXCEPTION_RETURNED -> JournalReason.DETECT_EXCEPTION_RETURNED
            TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN ->
                JournalReason.RESULT_CALLBACK_DEADLINE_CLEAN
        }

    private fun checkedNextEpoch(lastEpoch: ULong): ULong? =
        if (lastEpoch == ULong.MAX_VALUE) null else lastEpoch + 1uL

    private fun isCanonicalEmptyBaseline(value: RecoveryJournalPayloadV5): Boolean =
        value.lastEpoch == 0uL &&
            value.active == null &&
            value.manualRetryContext == null &&
            value.entries.isEmpty() &&
            value.pendingStoreCommit == null &&
            value.modeControl == ModeControlV5.None

    private fun applyNext(
        next: RecoveryJournalPayloadV5,
        effects: List<RecoveryAttemptEffect>,
    ): RecoveryAttemptReductionResult =
        if (validatePayload(next)) {
            applied(next, effects)
        } else {
            rejected(RecoveryAttemptRejection.NEXT_STATE_INVALID)
        }

    private fun bindCleanPersistenceFence(
        intended: RecoveryAttemptReductionResult.Applied,
        current: RecoveryJournalPayloadV5,
        authority: RecoveryCleanClosureAuthority,
    ): RecoveryAttemptReductionResult {
        val fence =
            RecoveryCleanClosureBoundary.bindPersistenceFence(
                authority,
                current,
                intended.next,
            ) ?: return rejected(RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_REVOKED)
        return RecoveryAttemptReductionResult.Applied(
            next = intended.next,
            effects =
                intended.effects +
                    RecoveryAttemptEffect.ResolveCleanPersistenceFenceBeforeCheckedReplace,
            cleanPersistenceFence = fence,
        )
    }

    private inline fun withValidCurrent(
        current: RecoveryJournalPayloadV5,
        block: () -> RecoveryAttemptReductionResult,
    ): RecoveryAttemptReductionResult =
        if (validatePayload(current)) {
            block()
        } else {
            rejected(RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD)
        }

    private fun applied(
        next: RecoveryJournalPayloadV5,
        effects: List<RecoveryAttemptEffect>,
    ): RecoveryAttemptReductionResult.Applied =
        RecoveryAttemptReductionResult.Applied(next, effects)

    private fun rejected(
        reason: RecoveryAttemptRejection,
    ): RecoveryAttemptReductionResult.Rejected =
        RecoveryAttemptReductionResult.Rejected(reason)

    private val NATIVE_DELEGATES = setOf(ProbeDelegate.CPU, ProbeDelegate.GPU)
    private val BLOCKING_ENTRY_STATES =
        setOf(JournalEntryState.QUARANTINED, JournalEntryState.RETRY_CONSUMED)
    private val LIVE_MANUAL_ENTRY_STATES =
        setOf(JournalEntryState.TERMINAL_THIS_ATTEMPT, JournalEntryState.MANUAL_RETRY_ACTIVE)
}
