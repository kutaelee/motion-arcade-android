package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityResultId
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest

internal object RecoveryJournalTestFixtures {
    fun digest(seed: Int): Sha256Digest =
        valid(Sha256Digest.fromBytes(ByteArray(Sha256Digest.BYTE_COUNT) { index -> (seed + index).toByte() }))

    fun runtime(seed: Int = 0): RuntimeArtifactId = RuntimeArtifactId(digest(seed))

    fun scope(seed: Int = 32): ProbeBaseScopeId = ProbeBaseScopeId(digest(seed))

    fun result(seed: Int = 96): CapabilityResultId = CapabilityResultId(digest(seed))

    fun proof(
        delegate: ProbeDelegate = ProbeDelegate.CPU,
        role: JournalRouteRole = JournalRouteRole.CANDIDATE,
        reason: TerminalProofReason = TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY,
    ): TerminalProofV1 =
        TerminalProofV1(
            traceOrdinal =
                when (role) {
                    JournalRouteRole.CANDIDATE -> if (delegate == ProbeDelegate.CPU) 0u else 1u
                    JournalRouteRole.SELECTED -> 2u
                    JournalRouteRole.FALLBACK_CANDIDATE -> 3u
                    JournalRouteRole.FALLBACK_SELECTED -> 4u
                },
            delegate = delegate,
            role = role,
            reason = reason,
            closure =
                if (reason == TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                    TerminalClosure.PRE_CREATE_NO_NATIVE_ENTRY
                } else {
                    TerminalClosure.POST_CREATE_ALL_OWNERS_RETURNED_CLEAN
                },
            resolvedOwnerMask = 0xff,
        )

    fun terminalEntry(
        scope: ProbeBaseScopeId = scope(),
        epoch: ULong = 1uL,
        delegate: ProbeDelegate = ProbeDelegate.CPU,
        role: JournalRouteRole = JournalRouteRole.CANDIDATE,
        reason: JournalReason = JournalReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY,
        retryUsed: Boolean = false,
        retryContextId: Sha256Digest? = null,
    ): JournalEntryV5 =
        JournalEntryV5(
            probeBaseScopeId = scope,
            attemptEpoch = epoch,
            delegate = delegate,
            state = JournalEntryState.TERMINAL_THIS_ATTEMPT,
            reason = reason,
            retryUsed = retryUsed,
            cacheInvalidated = true,
            retryContextId = retryContextId,
            terminalEvidence =
                proof(
                    delegate = delegate,
                    role = role,
                    reason = TerminalProofReason.entries.single { it.wireValue == reason.wireValue },
                ),
        )

    fun quarantineEntry(
        scope: ProbeBaseScopeId = scope(),
        epoch: ULong = 1uL,
        delegate: ProbeDelegate = ProbeDelegate.CPU,
        reason: JournalReason = JournalReason.RECOVERED_ACTIVE,
        cacheInvalidated: Boolean = true,
    ): JournalEntryV5 =
        JournalEntryV5(
            probeBaseScopeId = scope,
            attemptEpoch = epoch,
            delegate = delegate,
            state = JournalEntryState.QUARANTINED,
            reason = reason,
            retryUsed = false,
            cacheInvalidated = cacheInvalidated,
            retryContextId = null,
            terminalEvidence = null,
        )

    fun consumedEntry(
        scope: ProbeBaseScopeId = scope(),
        epoch: ULong = 2uL,
        delegate: ProbeDelegate = ProbeDelegate.CPU,
        contextId: Sha256Digest = digest(160),
    ): JournalEntryV5 =
        JournalEntryV5(
            probeBaseScopeId = scope,
            attemptEpoch = epoch,
            delegate = delegate,
            state = JournalEntryState.RETRY_CONSUMED,
            reason = JournalReason.MANUAL_RETRY_INTERRUPTED,
            retryUsed = true,
            cacheInvalidated = true,
            retryContextId = contextId,
            terminalEvidence = null,
        )

    fun manualRetryPayload(
        activeDelegate: ProbeDelegate? = null,
        pending: Boolean = false,
    ): RecoveryJournalPayloadV5 {
        val base = scope()
        val origin = quarantineEntry(scope = base, epoch = 1uL)
        val context =
            ManualRetryContextV1(
                probeBaseScopeId = base,
                targetDelegate = ProbeDelegate.CPU,
                originQuarantineEpoch = 1uL,
                originQuarantineReason = origin.reason,
                originQuarantineEntrySha256 = valid(RecoveryJournalV5Codec.entrySha256(origin)),
                attemptEpoch = 2uL,
            )
        val contextId = valid(RecoveryJournalV5Codec.manualRetryContextId(context))
        val marker =
            JournalEntryV5(
                probeBaseScopeId = base,
                attemptEpoch = 2uL,
                delegate = ProbeDelegate.CPU,
                state = JournalEntryState.MANUAL_RETRY_ACTIVE,
                reason = JournalReason.MANUAL_RETRY_AUTHORIZED,
                retryUsed = true,
                cacheInvalidated = true,
                retryContextId = contextId,
                terminalEvidence = null,
            )
        val active =
            activeDelegate?.let { delegate ->
                ActiveV5(
                    probeBaseScopeId = base,
                    attemptEpoch = 2uL,
                    delegate = delegate,
                    role = JournalRouteRole.CANDIDATE,
                    state = JournalActiveState.ACTIVE,
                    retryUsed = true,
                    retryContextId = contextId,
                )
            }
        val pendingCommit =
            if (pending) {
                PendingStoreCommitV5(
                    probeBaseScopeId = base,
                    attemptEpoch = 2uL,
                    capabilityResultId = result(),
                    selectedDelegate = ProbeDelegate.CPU,
                    retryUsed = true,
                    retryContextId = contextId,
                    expectedOldFileState = ExpectedOldFileState.ABSENT,
                    expectedOldFileLength = 0uL,
                    expectedOldFileSha256 = null,
                    intendedNewFileLength = 512uL,
                    intendedNewFileSha256 = digest(128),
                    intendedNewRecordSha256 = digest(192),
                )
            } else {
                null
            }
        return RecoveryJournalPayloadV5(
            schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
            recoveryBuildId = runtime(),
            mode = RecoveryJournalMode.SOLO,
            lastEpoch = 2uL,
            active = active,
            manualRetryContext = context,
            entries = listOf(marker),
            pendingStoreCommit = pendingCommit,
            modeControl = ModeControlV5.None,
        )
    }

    fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }
}
