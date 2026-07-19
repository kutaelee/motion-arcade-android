package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityResultId
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.Collections

/** Exact physical journal mode. SOLO and DUAL are separate integrity domains. */
enum class RecoveryJournalMode(val wireValue: Int) {
    SOLO(0),
    DUAL(1),
}

enum class JournalRouteRole(val wireValue: Int) {
    CANDIDATE(0),
    SELECTED(1),
    FALLBACK_CANDIDATE(2),
    FALLBACK_SELECTED(3),
}

enum class JournalActiveState(val wireValue: Int) {
    ACTIVE(0),
    TEARDOWN_PENDING(1),
}

data class ActiveV5(
    val probeBaseScopeId: ProbeBaseScopeId,
    val attemptEpoch: ULong,
    val delegate: ProbeDelegate,
    val role: JournalRouteRole,
    val state: JournalActiveState,
    val retryUsed: Boolean,
    val retryContextId: Sha256Digest?,
) {
    override fun toString(): String =
        "ActiveV5(delegate=$delegate,role=$role,state=$state,retryUsed=$retryUsed)"
}

enum class JournalEntryState(val wireValue: Int) {
    TERMINAL_THIS_ATTEMPT(0),
    QUARANTINED(1),
    MANUAL_RETRY_ACTIVE(2),
    RETRY_CONSUMED(3),
}

enum class JournalReason(val wireValue: Int) {
    OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY(0),
    DETECT_EXCEPTION_RETURNED(1),
    RESULT_CALLBACK_DEADLINE_CLEAN(2),
    RECOVERED_ACTIVE(3),
    RECOVERED_TEARDOWN_PENDING(4),
    CREATE_UNCERTAIN(5),
    CREATE_TIMEOUT(6),
    SUBMISSION_TIMEOUT(7),
    PROCESS_ABORT(8),
    IMAGE_PROXY_CLOSE(9),
    SUBMITTED_INPUT_CLOSE(10),
    BUFFER_ZERO(11),
    BUFFER_RELEASE(12),
    ANALYZER_DETACH(13),
    CALLBACK_OUTPUT_DISPOSAL(14),
    CALLBACK_OUTPUT_UNDELIVERED(15),
    LANDMARKER_CLOSE(16),
    JOURNAL_POST_CREATE(17),
    THERMAL_SAFETY_MONITOR_FAILED(18),
    FRAME_METRICS_REMOVE_OR_DRAIN(19),
    CALLBACK_BARRIER_UNPROVEN(20),
    MANUAL_RETRY_ABANDONED(21),
    MANUAL_RETRY_ABORTED(22),
    MANUAL_RETRY_INTERRUPTED(23),
    MANUAL_RETRY_SAVE_NOT_COMMITTED(24),
    MANUAL_RETRY_AUTHORIZED(25),
}

enum class TerminalProofReason(val wireValue: Int) {
    OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY(0),
    DETECT_EXCEPTION_RETURNED(1),
    RESULT_CALLBACK_DEADLINE_CLEAN(2),
}

enum class TerminalClosure(val wireValue: Int) {
    PRE_CREATE_NO_NATIVE_ENTRY(0),
    POST_CREATE_ALL_OWNERS_RETURNED_CLEAN(1),
}

data class TerminalProofV1(
    val traceOrdinal: UInt,
    val delegate: ProbeDelegate,
    val role: JournalRouteRole,
    val reason: TerminalProofReason,
    val closure: TerminalClosure,
    val resolvedOwnerMask: Int,
)

data class JournalEntryV5(
    val probeBaseScopeId: ProbeBaseScopeId,
    val attemptEpoch: ULong,
    val delegate: ProbeDelegate,
    val state: JournalEntryState,
    val reason: JournalReason,
    val retryUsed: Boolean,
    val cacheInvalidated: Boolean,
    val retryContextId: Sha256Digest?,
    val terminalEvidence: TerminalProofV1?,
) {
    override fun toString(): String =
        "JournalEntryV5(delegate=$delegate,state=$state,reason=$reason,retryUsed=$retryUsed)"
}

data class ManualRetryContextV1(
    val probeBaseScopeId: ProbeBaseScopeId,
    val targetDelegate: ProbeDelegate,
    val originQuarantineEpoch: ULong,
    val originQuarantineReason: JournalReason,
    val originQuarantineEntrySha256: Sha256Digest,
    val attemptEpoch: ULong,
) {
    override fun toString(): String =
        "ManualRetryContextV1(targetDelegate=$targetDelegate,originReason=$originQuarantineReason)"
}

enum class ExpectedOldFileState(val wireValue: Int) {
    ABSENT(0),
    HASH_PRESENT(1),
}

data class PendingStoreCommitV5(
    val probeBaseScopeId: ProbeBaseScopeId,
    val attemptEpoch: ULong,
    val capabilityResultId: CapabilityResultId,
    val selectedDelegate: ProbeDelegate,
    val retryUsed: Boolean,
    val retryContextId: Sha256Digest?,
    val expectedOldFileState: ExpectedOldFileState,
    val expectedOldFileLength: ULong,
    val expectedOldFileSha256: Sha256Digest?,
    val intendedNewFileLength: ULong,
    val intendedNewFileSha256: Sha256Digest,
    val intendedNewRecordSha256: Sha256Digest,
) {
    override fun toString(): String =
        "PendingStoreCommitV5(selectedDelegate=$selectedDelegate,retryUsed=$retryUsed,oldState=$expectedOldFileState)"
}

enum class PostSealModePoisonReason(val wireValue: Int) {
    RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL(0),
}

sealed interface ModeControlV5 {
    data object None : ModeControlV5

    data class ResetRequested(val controlEpoch: ULong) : ModeControlV5 {
        override fun toString(): String = "ResetRequested(controlEpoch=redacted)"
    }

    data class ResetRunning(val controlEpoch: ULong) : ModeControlV5 {
        override fun toString(): String = "ResetRunning(controlEpoch=redacted)"
    }

    data class PostSealModePoison(
        val reason: PostSealModePoisonReason,
        val cacheInvalidated: Boolean,
    ) : ModeControlV5
}

/**
 * Immutable representation of the exact nine-field recovery-journal-payload-v5
 * manifest. The entry list is defensively copied and exposed read-only.
 */
class RecoveryJournalPayloadV5(
    val schemaRevision: String,
    val recoveryBuildId: RuntimeArtifactId,
    val mode: RecoveryJournalMode,
    val lastEpoch: ULong,
    val active: ActiveV5?,
    val manualRetryContext: ManualRetryContextV1?,
    entries: List<JournalEntryV5>,
    val pendingStoreCommit: PendingStoreCommitV5?,
    val modeControl: ModeControlV5,
) {
    val entries: List<JournalEntryV5> = Collections.unmodifiableList(ArrayList(entries))

    fun copy(
        schemaRevision: String = this.schemaRevision,
        recoveryBuildId: RuntimeArtifactId = this.recoveryBuildId,
        mode: RecoveryJournalMode = this.mode,
        lastEpoch: ULong = this.lastEpoch,
        active: ActiveV5? = this.active,
        manualRetryContext: ManualRetryContextV1? = this.manualRetryContext,
        entries: List<JournalEntryV5> = this.entries,
        pendingStoreCommit: PendingStoreCommitV5? = this.pendingStoreCommit,
        modeControl: ModeControlV5 = this.modeControl,
    ): RecoveryJournalPayloadV5 =
        RecoveryJournalPayloadV5(
            schemaRevision = schemaRevision,
            recoveryBuildId = recoveryBuildId,
            mode = mode,
            lastEpoch = lastEpoch,
            active = active,
            manualRetryContext = manualRetryContext,
            entries = entries,
            pendingStoreCommit = pendingStoreCommit,
            modeControl = modeControl,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RecoveryJournalPayloadV5 &&
            schemaRevision == other.schemaRevision &&
            recoveryBuildId == other.recoveryBuildId &&
            mode == other.mode &&
            lastEpoch == other.lastEpoch &&
            active == other.active &&
            manualRetryContext == other.manualRetryContext &&
            entries == other.entries &&
            pendingStoreCommit == other.pendingStoreCommit &&
            modeControl == other.modeControl

    override fun hashCode(): Int {
        var result = schemaRevision.hashCode()
        result = 31 * result + recoveryBuildId.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + lastEpoch.hashCode()
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + (manualRetryContext?.hashCode() ?: 0)
        result = 31 * result + entries.hashCode()
        result = 31 * result + (pendingStoreCommit?.hashCode() ?: 0)
        return 31 * result + modeControl.hashCode()
    }

    override fun toString(): String =
        "RecoveryJournalPayloadV5(mode=$mode,entries=${entries.size},active=${active != null})"

    companion object {
        const val SCHEMA_REVISION: String = "recovery-journal-payload-v5"

        fun empty(
            recoveryBuildId: RuntimeArtifactId,
            mode: RecoveryJournalMode,
        ): RecoveryJournalPayloadV5 =
            RecoveryJournalPayloadV5(
                schemaRevision = SCHEMA_REVISION,
                recoveryBuildId = recoveryBuildId,
                mode = mode,
                lastEpoch = 0uL,
                active = null,
                manualRetryContext = null,
                entries = emptyList(),
                pendingStoreCommit = null,
                modeControl = ModeControlV5.None,
            )
    }
}
