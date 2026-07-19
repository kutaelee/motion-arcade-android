package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CanonicalField
import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CapabilityDomainViolation
import com.motionarcade.vision.capability.domain.CapabilityResultId
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.security.MessageDigest

/** Strict canonical codec for the pure RecoveryJournalV5 byte and value domains. */
object RecoveryJournalV5Codec {
    const val MAXIMUM_FILE_BYTES: Int = 16_384
    const val CHECKSUM_BYTES: Int = Sha256Digest.BYTE_COUNT
    const val MAXIMUM_PAYLOAD_BYTES: Int = MAXIMUM_FILE_BYTES - CHECKSUM_BYTES
    const val MAXIMUM_ENTRIES: Int = 8
    const val MAXIMUM_MODE_STORE_FILE_BYTES: ULong = 1_048_576uL

    fun encodePayload(value: RecoveryJournalPayloadV5): CapabilityDomainResult<ByteArray> =
        compute {
            validatePayload(value)
            encodePayloadUnchecked(value)
        }

    fun encodeFile(value: RecoveryJournalPayloadV5): CapabilityDomainResult<ByteArray> =
        compute {
            validatePayload(value)
            val payload = encodePayloadUnchecked(value)
            concatenate(
                parts = listOf(payload, CanonicalManifestCodec.sha256(payload).copyBytes()),
                maximumBytes = MAXIMUM_FILE_BYTES,
                path = "$/file",
            )
        }

    fun decodePayload(
        bytes: ByteArray,
        expectedRecoveryBuildId: RuntimeArtifactId,
        expectedMode: RecoveryJournalMode,
    ): CapabilityDomainResult<RecoveryJournalPayloadV5> =
        compute {
            if (bytes.size > MAXIMUM_PAYLOAD_BYTES) {
                reject("$/payload", "at most $MAXIMUM_PAYLOAD_BYTES bytes", "${bytes.size} bytes")
            }
            val snapshot = bytes.copyOf()
            val value = decodePayloadUnchecked(snapshot)
            validateExpectedOwner(value, expectedRecoveryBuildId, expectedMode)
            validatePayload(value)
            val canonical = encodePayloadUnchecked(value)
            if (!snapshot.contentEquals(canonical)) {
                reject("$/payload", "exact canonical payload", "noncanonical bytes")
            }
            value
        }

    fun decodeFile(
        bytes: ByteArray,
        expectedRecoveryBuildId: RuntimeArtifactId,
        expectedMode: RecoveryJournalMode,
    ): CapabilityDomainResult<RecoveryJournalPayloadV5> =
        compute {
            if (bytes.size > MAXIMUM_FILE_BYTES) {
                reject("$/file", "at most $MAXIMUM_FILE_BYTES bytes", "${bytes.size} bytes")
            }
            val snapshot = bytes.copyOf()
            if (snapshot.size <= CHECKSUM_BYTES) {
                reject("$/file", "canonical payload plus 32-byte checksum", "${snapshot.size} bytes")
            }
            val payloadSize = snapshot.size - CHECKSUM_BYTES
            val payload = snapshot.copyOfRange(0, payloadSize)
            val checksum = snapshot.copyOfRange(payloadSize, snapshot.size)
            val computed = CanonicalManifestCodec.sha256(payload).copyBytes()
            if (!MessageDigest.isEqual(checksum, computed)) {
                reject("$/file/checksum", "raw SHA-256 of canonical payload", "mismatch")
            }
            val value = decodePayloadUnchecked(payload)
            validateExpectedOwner(value, expectedRecoveryBuildId, expectedMode)
            validatePayload(value)
            val canonical = encodePayloadUnchecked(value)
            if (!payload.contentEquals(canonical)) {
                reject("$/payload", "exact canonical payload", "noncanonical bytes")
            }
            value
        }

    fun encodeTerminalProof(value: TerminalProofV1): CapabilityDomainResult<ByteArray> =
        compute {
            validateTerminalProof(value)
            encodeTerminalProofUnchecked(value)
        }

    fun decodeTerminalProof(bytes: ByteArray): CapabilityDomainResult<TerminalProofV1> =
        compute {
            val snapshot = boundedSnapshot(bytes, MAXIMUM_PAYLOAD_BYTES, "$/terminalProof")
            val value = decodeTerminalProofUnchecked(snapshot)
            validateTerminalProof(value)
            if (!snapshot.contentEquals(encodeTerminalProofUnchecked(value))) {
                reject("$/terminalProof", "exact canonical TerminalProofV1", "noncanonical bytes")
            }
            value
        }

    fun encodeEntry(value: JournalEntryV5): CapabilityDomainResult<ByteArray> =
        compute {
            validateEntry(value)
            encodeEntryUnchecked(value)
        }

    fun decodeEntry(bytes: ByteArray): CapabilityDomainResult<JournalEntryV5> =
        compute {
            val snapshot = boundedSnapshot(bytes, MAXIMUM_PAYLOAD_BYTES, "$/entry")
            val value = decodeEntryUnchecked(snapshot)
            validateEntry(value)
            if (!snapshot.contentEquals(encodeEntryUnchecked(value))) {
                reject("$/entry", "exact canonical EntryV5", "noncanonical bytes")
            }
            value
        }

    fun encodeManualRetryContext(value: ManualRetryContextV1): CapabilityDomainResult<ByteArray> =
        compute {
            validateManualRetryContext(value)
            encodeManualRetryContextUnchecked(value)
        }

    fun manualRetryContextId(value: ManualRetryContextV1): CapabilityDomainResult<Sha256Digest> =
        encodeManualRetryContext(value).mapValid(CanonicalManifestCodec::sha256)

    fun entrySha256(value: JournalEntryV5): CapabilityDomainResult<Sha256Digest> =
        encodeEntry(value).mapValid(CanonicalManifestCodec::sha256)

    private fun encodePayloadUnchecked(value: RecoveryJournalPayloadV5): ByteArray =
        manifest(
            domain = PAYLOAD_DOMAIN,
            fields =
                listOf(
                    utf8Field("schema_revision", value.schemaRevision),
                    CanonicalField("recovery_build_id", value.recoveryBuildId.digest.copyBytes()),
                    CanonicalField("mode", oneByte(value.mode.wireValue, "$/mode")),
                    CanonicalField("last_epoch", CanonicalManifestCodec.uint64(value.lastEpoch)),
                    CanonicalField("active", encodeActiveUnion(value.active)),
                    CanonicalField("manual_retry_context", encodeManualRetryContextUnion(value.manualRetryContext)),
                    CanonicalField("entries", encodeEntries(value.entries)),
                    CanonicalField("pending_store_commit", encodePendingUnion(value.pendingStoreCommit)),
                    CanonicalField("mode_control", encodeModeControl(value.modeControl)),
                ),
            maximumBytes = MAXIMUM_PAYLOAD_BYTES,
        )

    private fun encodeActiveUnion(value: ActiveV5?): ByteArray =
        if (value == null) {
            byteArrayOf(UNION_ABSENT)
        } else {
            byteArrayOf(UNION_PRESENT) +
                manifest(
                    domain = ACTIVE_DOMAIN,
                    fields =
                        listOf(
                            CanonicalField("probe_base_scope_id", value.probeBaseScopeId.digest.copyBytes()),
                            CanonicalField("attempt_epoch", CanonicalManifestCodec.uint64(value.attemptEpoch)),
                            CanonicalField("delegate", oneByte(value.delegate.wireValue, "$/active/delegate")),
                            CanonicalField("role", oneByte(value.role.wireValue, "$/active/role")),
                            CanonicalField("state", oneByte(value.state.wireValue, "$/active/state")),
                            CanonicalField("retry_used", CanonicalManifestCodec.boolean(value.retryUsed)),
                            CanonicalField("retry_context_id", encodeDigestUnion(value.retryContextId)),
                        ),
                )
        }

    private fun encodeManualRetryContextUnion(value: ManualRetryContextV1?): ByteArray =
        if (value == null) {
            byteArrayOf(UNION_ABSENT)
        } else {
            byteArrayOf(UNION_PRESENT) + encodeManualRetryContextUnchecked(value)
        }

    private fun encodeManualRetryContextUnchecked(value: ManualRetryContextV1): ByteArray =
        manifest(
            domain = MANUAL_CONTEXT_DOMAIN,
            fields =
                listOf(
                    CanonicalField("probe_base_scope_id", value.probeBaseScopeId.digest.copyBytes()),
                    CanonicalField("target_delegate", oneByte(value.targetDelegate.wireValue, "$/manualRetryContext/targetDelegate")),
                    CanonicalField("origin_quarantine_epoch", CanonicalManifestCodec.uint64(value.originQuarantineEpoch)),
                    CanonicalField(
                        "origin_quarantine_reason",
                        oneByte(value.originQuarantineReason.wireValue, "$/manualRetryContext/originQuarantineReason"),
                    ),
                    CanonicalField(
                        "origin_quarantine_entry_sha256",
                        value.originQuarantineEntrySha256.copyBytes(),
                    ),
                    CanonicalField("attempt_epoch", CanonicalManifestCodec.uint64(value.attemptEpoch)),
                ),
        )

    private fun encodeEntries(values: List<JournalEntryV5>): ByteArray {
        val parts = ArrayList<ByteArray>(1 + values.size * 2)
        parts += CanonicalManifestCodec.uint32(values.size.toUInt())
        values.forEach { entry ->
            val encoded = encodeEntryUnchecked(entry)
            parts += CanonicalManifestCodec.uint32(encoded.size.toUInt())
            parts += encoded
        }
        return concatenate(parts, MAXIMUM_PAYLOAD_BYTES, "$/entries")
    }

    private fun encodeEntryUnchecked(value: JournalEntryV5): ByteArray =
        manifest(
            domain = ENTRY_DOMAIN,
            fields =
                listOf(
                    CanonicalField("probe_base_scope_id", value.probeBaseScopeId.digest.copyBytes()),
                    CanonicalField("attempt_epoch", CanonicalManifestCodec.uint64(value.attemptEpoch)),
                    CanonicalField("delegate", oneByte(value.delegate.wireValue, "$/entry/delegate")),
                    CanonicalField("state", oneByte(value.state.wireValue, "$/entry/state")),
                    CanonicalField("reason", oneByte(value.reason.wireValue, "$/entry/reason")),
                    CanonicalField("retry_used", CanonicalManifestCodec.boolean(value.retryUsed)),
                    CanonicalField("cache_invalidated", CanonicalManifestCodec.boolean(value.cacheInvalidated)),
                    CanonicalField("retry_context_id", encodeDigestUnion(value.retryContextId)),
                    CanonicalField("terminal_evidence", encodeTerminalEvidenceUnion(value.terminalEvidence)),
                ),
        )

    private fun encodeTerminalEvidenceUnion(value: TerminalProofV1?): ByteArray =
        if (value == null) {
            byteArrayOf(UNION_ABSENT)
        } else {
            val proof = encodeTerminalProofUnchecked(value)
            concatenate(
                listOf(
                    byteArrayOf(UNION_PRESENT),
                    CanonicalManifestCodec.uint32(proof.size.toUInt()),
                    proof,
                ),
                MAXIMUM_PAYLOAD_BYTES,
                "$/entry/terminalEvidence",
            )
        }

    private fun encodeTerminalProofUnchecked(value: TerminalProofV1): ByteArray =
        manifest(
            domain = TERMINAL_PROOF_DOMAIN,
            fields =
                listOf(
                    CanonicalField("trace_ordinal", CanonicalManifestCodec.uint32(value.traceOrdinal)),
                    CanonicalField("delegate", oneByte(value.delegate.wireValue, "$/terminalProof/delegate")),
                    CanonicalField("role", oneByte(value.role.wireValue, "$/terminalProof/role")),
                    CanonicalField("reason", oneByte(value.reason.wireValue, "$/terminalProof/reason")),
                    CanonicalField("closure", oneByte(value.closure.wireValue, "$/terminalProof/closure")),
                    CanonicalField("resolved_owner_mask", oneByte(value.resolvedOwnerMask, "$/terminalProof/resolvedOwnerMask")),
                ),
        )

    private fun encodePendingUnion(value: PendingStoreCommitV5?): ByteArray =
        if (value == null) {
            byteArrayOf(UNION_ABSENT)
        } else {
            byteArrayOf(UNION_PRESENT) +
                manifest(
                    domain = PENDING_DOMAIN,
                    fields =
                        listOf(
                            CanonicalField("probe_base_scope_id", value.probeBaseScopeId.digest.copyBytes()),
                            CanonicalField("attempt_epoch", CanonicalManifestCodec.uint64(value.attemptEpoch)),
                            CanonicalField("capability_result_id", value.capabilityResultId.digest.copyBytes()),
                            CanonicalField(
                                "selected_delegate",
                                oneByte(value.selectedDelegate.wireValue, "$/pendingStoreCommit/selectedDelegate"),
                            ),
                            CanonicalField("retry_used", CanonicalManifestCodec.boolean(value.retryUsed)),
                            CanonicalField("retry_context_id", encodeDigestUnion(value.retryContextId)),
                            CanonicalField(
                                "expected_old_file_state",
                                oneByte(value.expectedOldFileState.wireValue, "$/pendingStoreCommit/expectedOldFileState"),
                            ),
                            CanonicalField(
                                "expected_old_file_length",
                                CanonicalManifestCodec.uint64(value.expectedOldFileLength),
                            ),
                            CanonicalField("expected_old_file_sha256", encodeDigestUnion(value.expectedOldFileSha256)),
                            CanonicalField(
                                "intended_new_file_length",
                                CanonicalManifestCodec.uint64(value.intendedNewFileLength),
                            ),
                            CanonicalField("intended_new_file_sha256", value.intendedNewFileSha256.copyBytes()),
                            CanonicalField("intended_new_record_sha256", value.intendedNewRecordSha256.copyBytes()),
                        ),
                )
        }

    private fun encodeModeControl(value: ModeControlV5): ByteArray =
        when (value) {
            ModeControlV5.None -> byteArrayOf(MODE_CONTROL_NONE)
            is ModeControlV5.ResetRequested ->
                byteArrayOf(MODE_CONTROL_RESET_REQUESTED) + CanonicalManifestCodec.uint64(value.controlEpoch)
            is ModeControlV5.ResetRunning ->
                byteArrayOf(MODE_CONTROL_RESET_RUNNING) + CanonicalManifestCodec.uint64(value.controlEpoch)
            is ModeControlV5.PostSealModePoison ->
                byteArrayOf(
                    MODE_CONTROL_POST_SEAL_POISON,
                    value.reason.wireValue.toByte(),
                    if (value.cacheInvalidated) 1 else 0,
                )
        }

    private fun encodeDigestUnion(value: Sha256Digest?): ByteArray =
        value?.let { byteArrayOf(UNION_PRESENT) + it.copyBytes() } ?: byteArrayOf(UNION_ABSENT)

    private fun decodePayloadUnchecked(bytes: ByteArray): RecoveryJournalPayloadV5 {
        val fields = parseManifest(bytes, PAYLOAD_DOMAIN, PAYLOAD_FIELDS, "$/payload")
        return RecoveryJournalPayloadV5(
            schemaRevision = exactUtf8(fields[0], RecoveryJournalPayloadV5.SCHEMA_REVISION, "$/schema_revision"),
            recoveryBuildId = RuntimeArtifactId(digest(fields[1], "$/recovery_build_id")),
            mode = enumValue(fields[2], RecoveryJournalMode.entries, "$/mode"),
            lastEpoch = uint64(fields[3], "$/last_epoch"),
            active = decodeActiveUnion(fields[4]),
            manualRetryContext = decodeManualRetryContextUnion(fields[5]),
            entries = decodeEntries(fields[6]),
            pendingStoreCommit = decodePendingUnion(fields[7]),
            modeControl = decodeModeControl(fields[8]),
        )
    }

    private fun decodeActiveUnion(bytes: ByteArray): ActiveV5? {
        val union = UnionCursor(bytes, "$/active")
        return when (union.tag()) {
            UNION_ABSENT -> {
                union.requireEnd()
                null
            }
            UNION_PRESENT -> {
                val fields = parseManifest(union.remainder(), ACTIVE_DOMAIN, ACTIVE_FIELDS, "$/active/value")
                ActiveV5(
                    probeBaseScopeId = ProbeBaseScopeId(digest(fields[0], "$/active/probe_base_scope_id")),
                    attemptEpoch = uint64(fields[1], "$/active/attempt_epoch"),
                    delegate = enumValue(fields[2], ProbeDelegate.entries, "$/active/delegate"),
                    role = enumValue(fields[3], JournalRouteRole.entries, "$/active/role"),
                    state = enumValue(fields[4], JournalActiveState.entries, "$/active/state"),
                    retryUsed = boolean(fields[5], "$/active/retry_used"),
                    retryContextId = decodeDigestUnion(fields[6], "$/active/retry_context_id"),
                )
            }
            else -> reject("$/active/tag", "0 or 1", union.firstTag.toString())
        }
    }

    private fun decodeManualRetryContextUnion(bytes: ByteArray): ManualRetryContextV1? {
        val union = UnionCursor(bytes, "$/manual_retry_context")
        return when (union.tag()) {
            UNION_ABSENT -> {
                union.requireEnd()
                null
            }
            UNION_PRESENT -> decodeManualRetryContextUnchecked(union.remainder())
            else -> reject("$/manual_retry_context/tag", "0 or 1", union.firstTag.toString())
        }
    }

    private fun decodeManualRetryContextUnchecked(bytes: ByteArray): ManualRetryContextV1 {
        val fields = parseManifest(bytes, MANUAL_CONTEXT_DOMAIN, MANUAL_CONTEXT_FIELDS, "$/manual_retry_context/value")
        return ManualRetryContextV1(
            probeBaseScopeId = ProbeBaseScopeId(digest(fields[0], "$/manual_retry_context/probe_base_scope_id")),
            targetDelegate = enumValue(fields[1], ProbeDelegate.entries, "$/manual_retry_context/target_delegate"),
            originQuarantineEpoch = uint64(fields[2], "$/manual_retry_context/origin_quarantine_epoch"),
            originQuarantineReason = enumValue(fields[3], JournalReason.entries, "$/manual_retry_context/origin_quarantine_reason"),
            originQuarantineEntrySha256 = digest(fields[4], "$/manual_retry_context/origin_quarantine_entry_sha256"),
            attemptEpoch = uint64(fields[5], "$/manual_retry_context/attempt_epoch"),
        )
    }

    private fun decodeEntries(bytes: ByteArray): List<JournalEntryV5> {
        val cursor = Cursor(bytes, "$/entries")
        val count = cursor.readUInt32("count")
        if (count > MAXIMUM_ENTRIES.toUInt()) {
            reject("$/entries/count", "0..$MAXIMUM_ENTRIES", count.toString())
        }
        val entries = ArrayList<JournalEntryV5>(count.toInt())
        repeat(count.toInt()) { index ->
            val length = cursor.readUInt32("$index/length")
            entries += decodeEntryUnchecked(cursor.readBytes(length, "$index/value"))
        }
        cursor.requireEnd()
        return entries
    }

    private fun decodeEntryUnchecked(bytes: ByteArray): JournalEntryV5 {
        val fields = parseManifest(bytes, ENTRY_DOMAIN, ENTRY_FIELDS, "$/entry")
        return JournalEntryV5(
            probeBaseScopeId = ProbeBaseScopeId(digest(fields[0], "$/entry/probe_base_scope_id")),
            attemptEpoch = uint64(fields[1], "$/entry/attempt_epoch"),
            delegate = enumValue(fields[2], ProbeDelegate.entries, "$/entry/delegate"),
            state = enumValue(fields[3], JournalEntryState.entries, "$/entry/state"),
            reason = enumValue(fields[4], JournalReason.entries, "$/entry/reason"),
            retryUsed = boolean(fields[5], "$/entry/retry_used"),
            cacheInvalidated = boolean(fields[6], "$/entry/cache_invalidated"),
            retryContextId = decodeDigestUnion(fields[7], "$/entry/retry_context_id"),
            terminalEvidence = decodeTerminalEvidenceUnion(fields[8]),
        )
    }

    private fun decodeTerminalEvidenceUnion(bytes: ByteArray): TerminalProofV1? {
        val cursor = Cursor(bytes, "$/entry/terminal_evidence")
        return when (val tag = cursor.readByte("tag")) {
            UNION_ABSENT.toInt() -> {
                cursor.requireEnd()
                null
            }
            UNION_PRESENT.toInt() -> {
                val length = cursor.readUInt32("proof_length")
                val proof = decodeTerminalProofUnchecked(cursor.readBytes(length, "proof"))
                cursor.requireEnd()
                proof
            }
            else -> reject("$/entry/terminal_evidence/tag", "0 or 1", tag.toString())
        }
    }

    private fun decodeTerminalProofUnchecked(bytes: ByteArray): TerminalProofV1 {
        val fields = parseManifest(bytes, TERMINAL_PROOF_DOMAIN, TERMINAL_PROOF_FIELDS, "$/terminalProof")
        return TerminalProofV1(
            traceOrdinal = uint32(fields[0], "$/terminalProof/trace_ordinal"),
            delegate = enumValue(fields[1], ProbeDelegate.entries, "$/terminalProof/delegate"),
            role = enumValue(fields[2], JournalRouteRole.entries, "$/terminalProof/role"),
            reason = enumValue(fields[3], TerminalProofReason.entries, "$/terminalProof/reason"),
            closure = enumValue(fields[4], TerminalClosure.entries, "$/terminalProof/closure"),
            resolvedOwnerMask = singleByte(fields[5], "$/terminalProof/resolved_owner_mask"),
        )
    }

    private fun decodePendingUnion(bytes: ByteArray): PendingStoreCommitV5? {
        val union = UnionCursor(bytes, "$/pending_store_commit")
        return when (union.tag()) {
            UNION_ABSENT -> {
                union.requireEnd()
                null
            }
            UNION_PRESENT -> {
                val fields = parseManifest(union.remainder(), PENDING_DOMAIN, PENDING_FIELDS, "$/pending_store_commit/value")
                PendingStoreCommitV5(
                    probeBaseScopeId = ProbeBaseScopeId(digest(fields[0], "$/pending_store_commit/probe_base_scope_id")),
                    attemptEpoch = uint64(fields[1], "$/pending_store_commit/attempt_epoch"),
                    capabilityResultId = CapabilityResultId(digest(fields[2], "$/pending_store_commit/capability_result_id")),
                    selectedDelegate = enumValue(fields[3], ProbeDelegate.entries, "$/pending_store_commit/selected_delegate"),
                    retryUsed = boolean(fields[4], "$/pending_store_commit/retry_used"),
                    retryContextId = decodeDigestUnion(fields[5], "$/pending_store_commit/retry_context_id"),
                    expectedOldFileState = enumValue(fields[6], ExpectedOldFileState.entries, "$/pending_store_commit/expected_old_file_state"),
                    expectedOldFileLength = uint64(fields[7], "$/pending_store_commit/expected_old_file_length"),
                    expectedOldFileSha256 = decodeDigestUnion(fields[8], "$/pending_store_commit/expected_old_file_sha256"),
                    intendedNewFileLength = uint64(fields[9], "$/pending_store_commit/intended_new_file_length"),
                    intendedNewFileSha256 = digest(fields[10], "$/pending_store_commit/intended_new_file_sha256"),
                    intendedNewRecordSha256 = digest(fields[11], "$/pending_store_commit/intended_new_record_sha256"),
                )
            }
            else -> reject("$/pending_store_commit/tag", "0 or 1", union.firstTag.toString())
        }
    }

    private fun decodeModeControl(bytes: ByteArray): ModeControlV5 {
        val cursor = Cursor(bytes, "$/mode_control")
        val result =
            when (val tag = cursor.readByte("tag")) {
                MODE_CONTROL_NONE.toInt() -> ModeControlV5.None
                MODE_CONTROL_RESET_REQUESTED.toInt() ->
                    ModeControlV5.ResetRequested(cursor.readULong("control_epoch"))
                MODE_CONTROL_RESET_RUNNING.toInt() ->
                    ModeControlV5.ResetRunning(cursor.readULong("control_epoch"))
                MODE_CONTROL_POST_SEAL_POISON.toInt() ->
                    ModeControlV5.PostSealModePoison(
                        reason = enumValue(cursor.readByte("reason"), PostSealModePoisonReason.entries, "$/mode_control/reason"),
                        cacheInvalidated = cursor.readBoolean("cache_invalidated"),
                    )
                else -> reject("$/mode_control/tag", "0, 1, 2, or 3", tag.toString())
            }
        cursor.requireEnd()
        return result
    }

    private fun decodeDigestUnion(bytes: ByteArray, path: String): Sha256Digest? {
        val cursor = Cursor(bytes, path)
        return when (val tag = cursor.readByte("tag")) {
            UNION_ABSENT.toInt() -> {
                cursor.requireEnd()
                null
            }
            UNION_PRESENT.toInt() -> {
                val value = digest(cursor.readBytes(Sha256Digest.BYTE_COUNT.toUInt(), "digest"), "$path/digest")
                cursor.requireEnd()
                value
            }
            else -> reject("$path/tag", "0 or 1", tag.toString())
        }
    }

    private fun validatePayload(value: RecoveryJournalPayloadV5) {
        exact(value.schemaRevision, RecoveryJournalPayloadV5.SCHEMA_REVISION, "$/schema_revision")
        if (value.lastEpoch == 0uL &&
            (value.active != null ||
                value.manualRetryContext != null ||
                value.entries.isNotEmpty() ||
                value.pendingStoreCommit != null ||
                value.modeControl != ModeControlV5.None)
        ) {
            reject("$/last_epoch", "zero only for the empty baseline", "zero with nonempty state")
        }
        value.active?.let {
            validateActive(it)
            epochAtMostLast(it.attemptEpoch, value.lastEpoch, "$/active/attempt_epoch")
        }
        value.manualRetryContext?.let {
            validateManualRetryContext(it)
            epochAtMostLast(it.originQuarantineEpoch, value.lastEpoch, "$/manual_retry_context/origin_quarantine_epoch")
            epochAtMostLast(it.attemptEpoch, value.lastEpoch, "$/manual_retry_context/attempt_epoch")
        }
        if (value.entries.size > MAXIMUM_ENTRIES) {
            reject("$/entries", "at most $MAXIMUM_ENTRIES entries", value.entries.size.toString())
        }
        value.entries.forEachIndexed { index, entry ->
            validateEntry(entry)
            epochAtMostLast(entry.attemptEpoch, value.lastEpoch, "$/entries/$index/attempt_epoch")
        }
        validateEntryOrdering(value.entries)
        validateTerminalEpochs(value.entries)
        validateTerminalProofPrefixes(value.entries)
        value.pendingStoreCommit?.let {
            validatePending(it)
            epochAtMostLast(it.attemptEpoch, value.lastEpoch, "$/pending_store_commit/attempt_epoch")
        }
        validateModeControl(value)
        validateActiveAndCapacity(value)
        validateActiveProofContinuation(value)
        validateRetryContextRelations(value)
        validatePendingRelations(value)
    }

    private fun validateActive(value: ActiveV5) {
        nonzero(value.attemptEpoch, "$/active/attempt_epoch")
        requireNativeDelegate(value.delegate, "$/active/delegate")
        validateRoleDelegate(value.role, value.delegate, "$/active/role")
        if (value.retryUsed != (value.retryContextId != null)) {
            reject("$/active/retry_context_id", "PRESENT iff retry_used is one", "inconsistent union")
        }
    }

    private fun validateManualRetryContext(value: ManualRetryContextV1) {
        requireNativeDelegate(value.targetDelegate, "$/manual_retry_context/target_delegate")
        nonzero(value.originQuarantineEpoch, "$/manual_retry_context/origin_quarantine_epoch")
        nonzero(value.attemptEpoch, "$/manual_retry_context/attempt_epoch")
        if (value.attemptEpoch <= value.originQuarantineEpoch) {
            reject(
                "$/manual_retry_context/attempt_epoch",
                "greater than origin_quarantine_epoch",
                "invalid epoch ordering",
            )
        }
        if (value.originQuarantineReason !in QUARANTINE_REASONS) {
            reject(
                "$/manual_retry_context/origin_quarantine_reason",
                "one exact QUARANTINED reason",
                value.originQuarantineReason.name,
            )
        }
        val eligibleOrigin =
            JournalEntryV5(
                probeBaseScopeId = value.probeBaseScopeId,
                attemptEpoch = value.originQuarantineEpoch,
                delegate = value.targetDelegate,
                state = JournalEntryState.QUARANTINED,
                reason = value.originQuarantineReason,
                retryUsed = false,
                cacheInvalidated = true,
                retryContextId = null,
                terminalEvidence = null,
            )
        val expectedOriginHash = CanonicalManifestCodec.sha256(encodeEntryUnchecked(eligibleOrigin))
        if (value.originQuarantineEntrySha256 != expectedOriginHash) {
            reject(
                "$/manual_retry_context/origin_quarantine_entry_sha256",
                "hash of the exact eligible cache-invalidated quarantine entry",
                "mismatch",
            )
        }
    }

    private fun validateEntry(value: JournalEntryV5) {
        nonzero(value.attemptEpoch, "$/entry/attempt_epoch")
        requireNativeDelegate(value.delegate, "$/entry/delegate")
        when (value.state) {
            JournalEntryState.TERMINAL_THIS_ATTEMPT -> {
                if (value.reason !in TERMINAL_REASONS) {
                    reject("$/entry/reason", "terminal reason 0..2", value.reason.name)
                }
                if (!value.cacheInvalidated) {
                    reject("$/entry/cache_invalidated", "true for terminal entry", "false")
                }
                if (value.retryUsed != (value.retryContextId != null)) {
                    reject("$/entry/retry_context_id", "PRESENT iff retry_used is one", "inconsistent union")
                }
                val proof = value.terminalEvidence
                    ?: reject("$/entry/terminal_evidence", "PRESENT for terminal entry", "ABSENT")
                validateTerminalProof(proof)
                if (proof.delegate != value.delegate) {
                    reject("$/entry/terminal_evidence/delegate", value.delegate.name, proof.delegate.name)
                }
                if (proof.reason.wireValue != value.reason.wireValue) {
                    reject("$/entry/terminal_evidence/reason", value.reason.name, proof.reason.name)
                }
            }
            JournalEntryState.QUARANTINED -> {
                if (value.reason !in QUARANTINE_REASONS) {
                    reject("$/entry/reason", "QUARANTINED reason 3..20", value.reason.name)
                }
                requireBitsAndAbsence(value, retryUsed = false, proofAbsent = true)
            }
            JournalEntryState.MANUAL_RETRY_ACTIVE -> {
                if (value.reason != JournalReason.MANUAL_RETRY_AUTHORIZED) {
                    reject("$/entry/reason", JournalReason.MANUAL_RETRY_AUTHORIZED.name, value.reason.name)
                }
                if (!value.retryUsed || !value.cacheInvalidated || value.retryContextId == null) {
                    reject("$/entry", "retry/cache/context tuple 1/1/PRESENT", "invalid manual retry tuple")
                }
                if (value.terminalEvidence != null) {
                    reject("$/entry/terminal_evidence", "ABSENT for manual marker", "PRESENT")
                }
            }
            JournalEntryState.RETRY_CONSUMED -> {
                if (value.reason !in CONSUMED_REASONS) {
                    reject("$/entry/reason", "RETRY_CONSUMED reason 21..24", value.reason.name)
                }
                if (!value.retryUsed || !value.cacheInvalidated || value.retryContextId == null) {
                    reject("$/entry", "retry/cache/context tuple 1/1/PRESENT", "invalid consumed tuple")
                }
                if (value.terminalEvidence != null) {
                    reject("$/entry/terminal_evidence", "ABSENT for consumed entry", "PRESENT")
                }
            }
        }
    }

    private fun requireBitsAndAbsence(
        value: JournalEntryV5,
        retryUsed: Boolean,
        proofAbsent: Boolean,
    ) {
        if (value.retryUsed != retryUsed || value.retryContextId != null) {
            reject("$/entry", "retry/context tuple 0/ABSENT", "invalid quarantine tuple")
        }
        if (proofAbsent && value.terminalEvidence != null) {
            reject("$/entry/terminal_evidence", "ABSENT", "PRESENT")
        }
    }

    private fun validateTerminalProof(value: TerminalProofV1) {
        requireNativeDelegate(value.delegate, "$/terminalProof/delegate")
        validateRoleDelegate(value.role, value.delegate, "$/terminalProof/role")
        val expectedOrdinal =
            when (value.role) {
                JournalRouteRole.CANDIDATE -> if (value.delegate == ProbeDelegate.CPU) 0u else 1u
                JournalRouteRole.SELECTED -> 2u
                JournalRouteRole.FALLBACK_CANDIDATE -> 3u
                JournalRouteRole.FALLBACK_SELECTED -> 4u
            }
        if (value.traceOrdinal != expectedOrdinal) {
            reject("$/terminalProof/trace_ordinal", expectedOrdinal.toString(), value.traceOrdinal.toString())
        }
        val expectedClosure =
            when (value.reason) {
                TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY ->
                    TerminalClosure.PRE_CREATE_NO_NATIVE_ENTRY
                TerminalProofReason.DETECT_EXCEPTION_RETURNED,
                TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN,
                -> TerminalClosure.POST_CREATE_ALL_OWNERS_RETURNED_CLEAN
            }
        if (value.closure != expectedClosure) {
            reject("$/terminalProof/closure", expectedClosure.name, value.closure.name)
        }
        if (value.resolvedOwnerMask != RESOLVED_OWNER_MASK) {
            reject("$/terminalProof/resolved_owner_mask", "0xff", "0x${value.resolvedOwnerMask.toString(16)}")
        }
    }

    private fun validatePending(value: PendingStoreCommitV5) {
        nonzero(value.attemptEpoch, "$/pending_store_commit/attempt_epoch")
        if (value.retryUsed != (value.retryContextId != null)) {
            reject("$/pending_store_commit/retry_context_id", "PRESENT iff retry_used is one", "inconsistent union")
        }
        when (value.expectedOldFileState) {
            ExpectedOldFileState.ABSENT -> {
                if (value.expectedOldFileLength != 0uL || value.expectedOldFileSha256 != null) {
                    reject("$/pending_store_commit/expected_old_file", "ABSENT/0/ABSENT", "inconsistent tuple")
                }
            }
            ExpectedOldFileState.HASH_PRESENT -> {
                if (value.expectedOldFileLength == 0uL || value.expectedOldFileSha256 == null) {
                    reject("$/pending_store_commit/expected_old_file", "HASH_PRESENT/positive/PRESENT", "inconsistent tuple")
                }
                if (value.expectedOldFileLength > MAXIMUM_MODE_STORE_FILE_BYTES) {
                    reject(
                        "$/pending_store_commit/expected_old_file_length",
                        "at most $MAXIMUM_MODE_STORE_FILE_BYTES",
                        value.expectedOldFileLength.toString(),
                    )
                }
            }
        }
        if (value.intendedNewFileLength == 0uL || value.intendedNewFileLength > MAXIMUM_MODE_STORE_FILE_BYTES) {
            reject(
                "$/pending_store_commit/intended_new_file_length",
                "1..$MAXIMUM_MODE_STORE_FILE_BYTES",
                value.intendedNewFileLength.toString(),
            )
        }
    }

    private fun validateModeControl(value: RecoveryJournalPayloadV5) {
        when (val control = value.modeControl) {
            ModeControlV5.None -> Unit
            is ModeControlV5.ResetRequested -> {
                validateControlEpoch(control.controlEpoch, value.lastEpoch, "$/mode_control/control_epoch")
                if (value.active != null || value.pendingStoreCommit != null) {
                    reject("$/mode_control", "reset without active or pending", "conflicting state")
                }
            }
            is ModeControlV5.ResetRunning -> {
                validateControlEpoch(control.controlEpoch, value.lastEpoch, "$/mode_control/control_epoch")
                if (value.active != null || value.pendingStoreCommit != null) {
                    reject("$/mode_control", "reset without active or pending", "conflicting state")
                }
            }
            is ModeControlV5.PostSealModePoison -> Unit
        }
    }

    private fun validateControlEpoch(controlEpoch: ULong, lastEpoch: ULong, path: String) {
        nonzero(controlEpoch, path)
        if (controlEpoch != lastEpoch) {
            reject(path, "equal to last_epoch", "mismatch")
        }
    }

    private fun validateEntryOrdering(values: List<JournalEntryV5>) {
        values.zipWithNext().forEachIndexed { index, pair ->
            if (compareEntryKeys(pair.first, pair.second) >= 0) {
                reject("$/entries/${index + 1}", "strict unsigned scope/delegate order", "duplicate or out of order")
            }
        }
    }

    private fun validateTerminalEpochs(values: List<JournalEntryV5>) {
        values.filter { it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT }
            .groupBy { it.probeBaseScopeId }
            .forEach { (_, entries) ->
                if (entries.map { it.attemptEpoch }.distinct().size > 1) {
                    reject("$/entries", "one terminal epoch per exact base", "multiple terminal epochs")
                }
            }
    }

    private fun validateTerminalProofPrefixes(values: List<JournalEntryV5>) {
        values.filter { it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT }
            .groupBy { it.probeBaseScopeId to it.attemptEpoch }
            .forEach { (_, entries) ->
                val proofs = entries.map { checkNotNull(it.terminalEvidence) }
                if (proofs.map { it.traceOrdinal }.distinct().size != proofs.size) {
                    reject("$/entries", "unique terminal trace ordinals per exact attempt", "duplicate ordinal")
                }
                val orderedProofEvents =
                    proofs.associate { proof -> proof.traceOrdinal.toInt() to terminalEvent(proof) }
                val maximumOrdinal = orderedProofEvents.keys.max()
                val compatiblePrefixes =
                    LEGAL_FALLBACK_TRACES.mapNotNull { trace ->
                        if (maximumOrdinal >= trace.size) return@mapNotNull null
                        val prefix = trace.take(maximumOrdinal + 1)
                        val safeEvents =
                            prefix.mapIndexedNotNull { ordinal, event ->
                                if (event in SAFE_TERMINAL_EVENTS) ordinal to event else null
                            }.toMap()
                        prefix.takeIf { safeEvents == orderedProofEvents }
                    }.distinct()
                if (compatiblePrefixes.isEmpty()) {
                    reject("$/entries", "terminal proofs matching one exact finite fallback prefix", "impossible proof set")
                }
                if (compatiblePrefixes.size != 1) {
                    reject("$/entries", "one unique finite fallback prefix", "ambiguous proof continuation")
                }
            }
    }

    private fun terminalEvent(proof: TerminalProofV1): Int =
        when (proof.role) {
            JournalRouteRole.CANDIDATE -> if (proof.delegate == ProbeDelegate.CPU) 1 else 4
            JournalRouteRole.SELECTED -> if (proof.delegate == ProbeDelegate.CPU) 8 else 9
            JournalRouteRole.FALLBACK_CANDIDATE -> 11
            JournalRouteRole.FALLBACK_SELECTED -> 13
        }

    private fun validateActiveProofContinuation(value: RecoveryJournalPayloadV5) {
        val active = value.active ?: return
        val proofs =
            value.entries.filter {
                it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT &&
                    it.probeBaseScopeId == active.probeBaseScopeId &&
                    it.attemptEpoch == active.attemptEpoch
            }.map { checkNotNull(it.terminalEvidence) }

        val routeOrdinal = routeOrdinal(active.delegate, active.role)
        val proofEvents = proofs.associate { it.traceOrdinal.toInt() to terminalEvent(it) }
        val lostCpuCandidateRerun =
            proofEvents == mapOf(1 to 4) &&
                active.delegate == ProbeDelegate.CPU &&
                active.role == JournalRouteRole.CANDIDATE
        if (!lostCpuCandidateRerun && proofEvents.keys.any { it >= routeOrdinal }) {
            reject("$/active", "native route after every durable terminal proof slot", "route overlaps proof prefix")
        }
        val retainedSkips =
            value.entries.filter {
                it.probeBaseScopeId == active.probeBaseScopeId &&
                    it.state in setOf(JournalEntryState.QUARANTINED, JournalEntryState.RETRY_CONSUMED) &&
                    candidateOrdinal(it.delegate) < routeOrdinal
            }.map { it.delegate }.toSet()
        val compatible =
            lostCpuCandidateRerun || LEGAL_FALLBACK_TRACES.any { trace ->
                if (routeOrdinal >= trace.size || trace[routeOrdinal] !in activeRouteEvents(active.delegate, active.role)) {
                    return@any false
                }
                val prefixBeforeActive = trace.take(routeOrdinal)
                val safeEvents =
                    prefixBeforeActive.mapIndexedNotNull { ordinal, event ->
                        if (event in SAFE_TERMINAL_EVENTS) ordinal to event else null
                    }.toMap()
                val skipped =
                    prefixBeforeActive.mapNotNull { event ->
                        when (event) {
                            2 -> ProbeDelegate.CPU
                            5 -> ProbeDelegate.GPU
                            else -> null
                        }
                    }.toSet()
                safeEvents == proofEvents && skipped == retainedSkips
            }
        if (!compatible) {
            reject("$/active", "active route compatible with the exact finite proof prefix", "impossible continuation")
        }
    }

    private fun routeOrdinal(delegate: ProbeDelegate, role: JournalRouteRole): Int =
        when (role) {
            JournalRouteRole.CANDIDATE -> if (delegate == ProbeDelegate.CPU) 0 else 1
            JournalRouteRole.SELECTED -> 2
            JournalRouteRole.FALLBACK_CANDIDATE -> 3
            JournalRouteRole.FALLBACK_SELECTED -> 4
        }

    private fun candidateOrdinal(delegate: ProbeDelegate): Int =
        when (delegate) {
            ProbeDelegate.CPU -> 0
            ProbeDelegate.GPU -> 1
            ProbeDelegate.NONE -> Int.MAX_VALUE
        }

    private fun activeRouteEvents(delegate: ProbeDelegate, role: JournalRouteRole): Set<Int> =
        when (role) {
            JournalRouteRole.CANDIDATE -> if (delegate == ProbeDelegate.CPU) setOf(0, 1) else setOf(3, 4)
            JournalRouteRole.SELECTED -> if (delegate == ProbeDelegate.CPU) setOf(6, 8) else setOf(7, 9)
            JournalRouteRole.FALLBACK_CANDIDATE -> setOf(10, 11)
            JournalRouteRole.FALLBACK_SELECTED -> setOf(12, 13)
        }

    private fun validateActiveAndCapacity(value: RecoveryJournalPayloadV5) {
        val active = value.active
        if (active != null && value.pendingStoreCommit != null) {
            reject("$/pending_store_commit", "ABSENT while active", "PRESENT")
        }
        if (active != null &&
            value.entries.any {
                it.state == JournalEntryState.QUARANTINED && !it.cacheInvalidated
            }
        ) {
            reject("$/active", "no native work before every quarantine cache deletion commits", "bit-0 quarantine remains")
        }
        val sameKeyEntry =
            if (active == null) {
                null
            } else {
                value.entries.singleOrNull {
                    it.probeBaseScopeId == active.probeBaseScopeId && it.delegate == active.delegate
                }
            }
        if (sameKeyEntry != null) {
            checkNotNull(active)
            val legal =
                sameKeyEntry.state == JournalEntryState.MANUAL_RETRY_ACTIVE &&
                    sameKeyEntry.attemptEpoch == active.attemptEpoch &&
                    sameKeyEntry.retryUsed &&
                    sameKeyEntry.cacheInvalidated &&
                    sameKeyEntry.retryContextId == active.retryContextId
            if (!legal) {
                reject("$/active", "only exact MANUAL_RETRY_ACTIVE same-key pair", "conflicting entry")
            }
        }
        if (active != null) {
            val sameBaseTerminal = value.entries.filter {
                it.probeBaseScopeId == active.probeBaseScopeId &&
                    it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT
            }
            if (sameBaseTerminal.any { it.attemptEpoch != active.attemptEpoch }) {
                reject("$/active/attempt_epoch", "same epoch as exact-base terminal prefix", "different epoch")
            }
        }
        val reservation = value.entries.size + if (active != null && sameKeyEntry == null) 1 else 0
        if (reservation > MAXIMUM_ENTRIES) {
            reject("$/entries", "entry count plus distinct active key <= $MAXIMUM_ENTRIES", reservation.toString())
        }
    }

    private fun validateRetryContextRelations(value: RecoveryJournalPayloadV5) {
        val context = value.manualRetryContext
        if (context == null) {
            if (value.active?.retryUsed == true) {
                reject("$/active/retry_used", "zero without ManualRetryContextV1", "one")
            }
            value.entries.forEachIndexed { index, entry ->
                if (entry.state == JournalEntryState.MANUAL_RETRY_ACTIVE ||
                    (entry.state == JournalEntryState.TERMINAL_THIS_ATTEMPT && entry.retryUsed)
                ) {
                    reject("$/entries/$index", "no live retry-1 terminal/manual state without context", entry.state.name)
                }
            }
            if (value.pendingStoreCommit?.retryUsed == true) {
                reject("$/pending_store_commit/retry_used", "zero without ManualRetryContextV1", "one")
            }
            return
        }

        val contextId = CanonicalManifestCodec.sha256(encodeManualRetryContextUnchecked(context))
        value.active?.let { active ->
            val legal =
                active.retryUsed &&
                    active.probeBaseScopeId == context.probeBaseScopeId &&
                    active.attemptEpoch == context.attemptEpoch &&
                    active.retryContextId == contextId
            if (!legal) {
                reject("$/active", "exact same-base/same-epoch retry context", "mismatch")
            }
        }

        value.entries.forEachIndexed { index, entry ->
            val liveRetryEntry =
                entry.state == JournalEntryState.MANUAL_RETRY_ACTIVE ||
                    (entry.state == JournalEntryState.TERMINAL_THIS_ATTEMPT && entry.retryUsed)
            if (liveRetryEntry) {
                val legal =
                    entry.retryUsed &&
                        entry.probeBaseScopeId == context.probeBaseScopeId &&
                        entry.attemptEpoch == context.attemptEpoch &&
                        entry.retryContextId == contextId
                if (!legal) {
                    reject("$/entries/$index", "exact live retry context", "mismatch")
                }
            }
            if (entry.state == JournalEntryState.MANUAL_RETRY_ACTIVE &&
                entry.delegate != context.targetDelegate
            ) {
                reject("$/entries/$index", "only the target delegate may carry the manual marker", entry.delegate.name)
            }
            if (entry.probeBaseScopeId == context.probeBaseScopeId &&
                entry.state == JournalEntryState.TERMINAL_THIS_ATTEMPT
            ) {
                val legal =
                    entry.attemptEpoch == context.attemptEpoch &&
                        entry.retryUsed &&
                        entry.retryContextId == contextId
                if (!legal) {
                    reject("$/entries/$index", "same-epoch retry-1 terminal in manual attempt", "stale or context mismatch")
                }
            }
            if (entry.probeBaseScopeId == context.probeBaseScopeId &&
                entry.attemptEpoch == context.attemptEpoch &&
                entry.state !in setOf(JournalEntryState.TERMINAL_THIS_ATTEMPT, JournalEntryState.MANUAL_RETRY_ACTIVE)
            ) {
                reject("$/entries/$index", "only terminal/manual entries in live retry epoch", entry.state.name)
            }
        }

        val target = value.entries.singleOrNull {
            it.probeBaseScopeId == context.probeBaseScopeId &&
                it.delegate == context.targetDelegate &&
                it.attemptEpoch == context.attemptEpoch
        }
        val targetLegal =
            target != null &&
                target.state in setOf(JournalEntryState.MANUAL_RETRY_ACTIVE, JournalEntryState.TERMINAL_THIS_ATTEMPT) &&
                target.retryUsed &&
                target.retryContextId == contextId
        if (!targetLegal) {
            reject("$/manual_retry_context", "exact target manual marker or terminal proof", "orphan context")
        }
    }

    private fun validatePendingRelations(value: RecoveryJournalPayloadV5) {
        val pending = value.pendingStoreCommit ?: return
        val context = value.manualRetryContext
        val expectedContextId = context?.let { CanonicalManifestCodec.sha256(encodeManualRetryContextUnchecked(it)) }
        if (pending.retryUsed) {
            val legal =
                context != null &&
                    pending.probeBaseScopeId == context.probeBaseScopeId &&
                    pending.attemptEpoch == context.attemptEpoch &&
                    pending.retryContextId == expectedContextId
            if (!legal) {
                reject("$/pending_store_commit", "exact same-base/same-epoch retry context", "mismatch")
            }
        } else if (context != null || pending.retryContextId != null) {
            reject("$/pending_store_commit", "retry zero with ABSENT context", "context present")
        }
        value.entries.forEachIndexed { index, entry ->
            if (entry.probeBaseScopeId == pending.probeBaseScopeId &&
                entry.state in setOf(JournalEntryState.TERMINAL_THIS_ATTEMPT, JournalEntryState.MANUAL_RETRY_ACTIVE)
            ) {
                if (entry.attemptEpoch != pending.attemptEpoch ||
                    entry.retryUsed != pending.retryUsed ||
                    entry.retryContextId != pending.retryContextId
                ) {
                    reject("$/entries/$index", "same-epoch pending retry provenance", "mismatch")
                }
            }
        }
    }

    private fun validateExpectedOwner(
        value: RecoveryJournalPayloadV5,
        expectedRecoveryBuildId: RuntimeArtifactId,
        expectedMode: RecoveryJournalMode,
    ) {
        if (value.recoveryBuildId != expectedRecoveryBuildId) {
            reject("$/recovery_build_id", "exact directory RuntimeArtifactId", "mismatch")
        }
        if (value.mode != expectedMode) {
            reject("$/mode", expectedMode.name, value.mode.name)
        }
    }

    private fun validateRoleDelegate(role: JournalRouteRole, delegate: ProbeDelegate, path: String) {
        if (delegate == ProbeDelegate.GPU &&
            role in setOf(JournalRouteRole.FALLBACK_CANDIDATE, JournalRouteRole.FALLBACK_SELECTED)
        ) {
            reject(path, "GPU cannot use a fallback role", role.name)
        }
    }

    private fun requireNativeDelegate(delegate: ProbeDelegate, path: String) {
        if (delegate !in setOf(ProbeDelegate.CPU, ProbeDelegate.GPU)) {
            reject(path, "CPU or GPU", delegate.name)
        }
    }

    private fun epochAtMostLast(epoch: ULong, last: ULong, path: String) {
        if (epoch > last) reject(path, "at most last_epoch", "out of range")
    }

    private fun nonzero(value: ULong, path: String) {
        if (value == 0uL) reject(path, "nonzero uint64", "invalid epoch")
    }

    private fun compareEntryKeys(left: JournalEntryV5, right: JournalEntryV5): Int {
        val scope = CanonicalManifestCodec.compareUnsigned(
            left.probeBaseScopeId.digest.copyBytes(),
            right.probeBaseScopeId.digest.copyBytes(),
        )
        return if (scope != 0) scope else left.delegate.wireValue.compareTo(right.delegate.wireValue)
    }

    private fun parseManifest(
        bytes: ByteArray,
        expectedDomain: String,
        expectedFields: List<String>,
        path: String,
    ): List<ByteArray> {
        val cursor = Cursor(bytes, path)
        val expectedDomainBytes = expectedDomain.toByteArray(Charsets.UTF_8)
        val actualDomain = cursor.readBytes(expectedDomainBytes.size.toUInt(), "domain")
        if (!actualDomain.contentEquals(expectedDomainBytes)) {
            reject("$path/domain", expectedDomain, "unknown domain")
        }
        if (cursor.readByte("domain_terminator") != 0) {
            reject("$path/domain", "NUL terminator", "missing")
        }
        val count = cursor.readUInt32("field_count")
        if (count != expectedFields.size.toUInt()) {
            reject("$path/field_count", expectedFields.size.toString(), count.toString())
        }
        val values = ArrayList<ByteArray>(expectedFields.size)
        expectedFields.forEachIndexed { index, expectedName ->
            val nameLength = cursor.readUInt32("fields/$index/name_length")
            val actualName = cursor.readBytes(nameLength, "fields/$index/name")
            val expectedNameBytes = expectedName.toByteArray(Charsets.UTF_8)
            if (!actualName.contentEquals(expectedNameBytes)) {
                reject(
                    "$path/fields/$index/name",
                    expectedName,
                    "unknown, duplicate, missing, or out-of-order field",
                )
            }
            val valueLength = cursor.readULong("fields/$index/value_length")
            values += cursor.readBytes(valueLength, "fields/$index/value")
        }
        cursor.requireEnd()
        return values
    }

    private fun <E> enumValue(bytes: ByteArray, values: List<E>, path: String): E where E : Enum<E> {
        val wire = singleByte(bytes, path)
        return enumValue(wire, values, path)
    }

    private fun <E> enumValue(wire: Int, values: List<E>, path: String): E where E : Enum<E> =
        values.firstOrNull { enumWireValue(it) == wire }
            ?: reject(path, "known sealed enum byte", wire.toString())

    private fun enumWireValue(value: Enum<*>): Int =
        when (value) {
            is RecoveryJournalMode -> value.wireValue
            is ProbeDelegate -> value.wireValue
            is JournalRouteRole -> value.wireValue
            is JournalActiveState -> value.wireValue
            is JournalEntryState -> value.wireValue
            is JournalReason -> value.wireValue
            is TerminalProofReason -> value.wireValue
            is TerminalClosure -> value.wireValue
            is ExpectedOldFileState -> value.wireValue
            is PostSealModePoisonReason -> value.wireValue
            else -> reject("$/enum", "known recovery enum type", value.javaClass.name)
        }

    private fun exactUtf8(bytes: ByteArray, expected: String, path: String): String {
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        if (!bytes.contentEquals(expectedBytes)) reject(path, expected, "different bytes")
        return expected
    }

    private fun uint32(bytes: ByteArray, path: String): UInt {
        if (bytes.size != UInt.SIZE_BYTES) reject(path, "exactly 4 bytes", "${bytes.size} bytes")
        return bytes.fold(0u) { value, byte -> (value shl Byte.SIZE_BITS) or (byte.toUInt() and 0xffu) }
    }

    private fun uint64(bytes: ByteArray, path: String): ULong {
        if (bytes.size != ULong.SIZE_BYTES) reject(path, "exactly 8 bytes", "${bytes.size} bytes")
        return bytes.fold(0uL) { value, byte -> (value shl Byte.SIZE_BITS) or (byte.toULong() and 0xffuL) }
    }

    private fun singleByte(bytes: ByteArray, path: String): Int {
        if (bytes.size != 1) reject(path, "exactly one byte", "${bytes.size} bytes")
        return bytes[0].toInt() and 0xff
    }

    private fun boolean(bytes: ByteArray, path: String): Boolean =
        when (val value = singleByte(bytes, path)) {
            0 -> false
            1 -> true
            else -> reject(path, "boolean byte 0 or 1", value.toString())
        }

    private fun digest(bytes: ByteArray, path: String): Sha256Digest =
        when (val result = Sha256Digest.fromBytes(bytes)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> reject(path, "exactly 32 bytes", "${bytes.size} bytes")
        }

    private fun manifest(
        domain: String,
        fields: List<CanonicalField>,
        maximumBytes: Int = MAXIMUM_PAYLOAD_BYTES,
    ): ByteArray =
        when (val result = CanonicalManifestCodec.encode(domain, fields, maximumBytes)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> throw CodecAbort(result.violations)
        }

    private fun utf8Field(name: String, value: String): CanonicalField =
        when (val result = CanonicalManifestCodec.strictUtf8(value, "$/fields/$name")) {
            is CapabilityDomainResult.Valid -> CanonicalField(name, result.value)
            is CapabilityDomainResult.Invalid -> throw CodecAbort(result.violations)
        }

    private fun oneByte(value: Int, path: String): ByteArray =
        when (val result = CanonicalManifestCodec.oneByte(value, path)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> throw CodecAbort(result.violations)
        }

    private fun boundedSnapshot(bytes: ByteArray, maximumBytes: Int, path: String): ByteArray {
        if (bytes.size > maximumBytes) reject(path, "at most $maximumBytes bytes", "${bytes.size} bytes")
        return bytes.copyOf()
    }

    private fun concatenate(parts: List<ByteArray>, maximumBytes: Int, path: String): ByteArray {
        var size = 0L
        try {
            parts.forEach { size = Math.addExact(size, it.size.toLong()) }
        } catch (_: ArithmeticException) {
            reject(path, "checked encoded length", "overflow")
        }
        if (size > maximumBytes || size > Int.MAX_VALUE) {
            reject(path, "encoded size <= $maximumBytes", size.toString())
        }
        val result = ByteArray(size.toInt())
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, destinationOffset = offset)
            offset += part.size
        }
        return result
    }

    private fun exact(actual: String, expected: String, path: String) {
        if (actual != expected) reject(path, expected, actual)
    }

    private fun reject(path: String, expected: String, actual: String): Nothing =
        throw CodecAbort(listOf(CapabilityDomainViolation.InvalidValue(path, expected, actual)))

    private inline fun <T> compute(block: () -> T): CapabilityDomainResult<T> =
        try {
            CapabilityDomainResult.Valid(block())
        } catch (failure: CodecAbort) {
            CapabilityDomainResult.Invalid(failure.violations)
        }

    private fun <T, R> CapabilityDomainResult<T>.mapValid(transform: (T) -> R): CapabilityDomainResult<R> =
        when (this) {
            is CapabilityDomainResult.Valid -> CapabilityDomainResult.Valid(transform(value))
            is CapabilityDomainResult.Invalid -> this
        }

    private class CodecAbort(
        val violations: List<CapabilityDomainViolation>,
    ) : RuntimeException(null, null, false, false)

    private class Cursor(
        private val bytes: ByteArray,
        private val path: String,
    ) {
        private var position: Int = 0

        fun readByte(name: String): Int {
            requireAvailable(1uL, name)
            return bytes[position++].toInt() and 0xff
        }

        fun readBoolean(name: String): Boolean =
            when (val value = readByte(name)) {
                0 -> false
                1 -> true
                else -> reject("$path/$name", "boolean byte 0 or 1", value.toString())
            }

        fun readUInt32(name: String): UInt {
            requireAvailable(UInt.SIZE_BYTES.toULong(), name)
            var value = 0u
            repeat(UInt.SIZE_BYTES) {
                value = (value shl Byte.SIZE_BITS) or (bytes[position++].toUInt() and 0xffu)
            }
            return value
        }

        fun readULong(name: String): ULong {
            requireAvailable(ULong.SIZE_BYTES.toULong(), name)
            var value = 0uL
            repeat(ULong.SIZE_BYTES) {
                value = (value shl Byte.SIZE_BITS) or (bytes[position++].toULong() and 0xffuL)
            }
            return value
        }

        fun readBytes(length: UInt, name: String): ByteArray = readBytes(length.toULong(), name)

        fun readBytes(length: ULong, name: String): ByteArray {
            requireAvailable(length, name)
            val count = length.toInt()
            val result = bytes.copyOfRange(position, position + count)
            position += count
            return result
        }

        fun requireEnd() {
            if (position != bytes.size) {
                reject(path, "no trailing bytes", "${bytes.size - position} trailing bytes")
            }
        }

        private fun requireAvailable(length: ULong, name: String) {
            val remaining = bytes.size - position
            if (length > remaining.toULong()) {
                reject("$path/$name", "length within remaining $remaining bytes", length.toString())
            }
        }
    }

    private class UnionCursor(bytes: ByteArray, private val path: String) {
        private val cursor = Cursor(bytes, path)
        private val snapshot = bytes.copyOf()
        val firstTag: Int

        init {
            firstTag = cursor.readByte("tag")
        }

        fun tag(): Byte = firstTag.toByte()

        fun remainder(): ByteArray = snapshot.copyOfRange(1, snapshot.size)

        fun requireEnd() {
            if (snapshot.size != 1) reject(path, "one-byte ABSENT union", "${snapshot.size} bytes")
        }
    }

    private const val PAYLOAD_DOMAIN = "recovery-journal-payload-v5"
    private const val ACTIVE_DOMAIN = "journal-active-v5"
    private const val MANUAL_CONTEXT_DOMAIN = "manual-retry-context-v1"
    private const val ENTRY_DOMAIN = "journal-entry-v5"
    private const val TERMINAL_PROOF_DOMAIN = "terminal-proof-v1"
    private const val PENDING_DOMAIN = "pending-store-commit-v5"

    private val PAYLOAD_FIELDS =
        listOf(
            "schema_revision",
            "recovery_build_id",
            "mode",
            "last_epoch",
            "active",
            "manual_retry_context",
            "entries",
            "pending_store_commit",
            "mode_control",
        )
    private val ACTIVE_FIELDS =
        listOf(
            "probe_base_scope_id",
            "attempt_epoch",
            "delegate",
            "role",
            "state",
            "retry_used",
            "retry_context_id",
        )
    private val MANUAL_CONTEXT_FIELDS =
        listOf(
            "probe_base_scope_id",
            "target_delegate",
            "origin_quarantine_epoch",
            "origin_quarantine_reason",
            "origin_quarantine_entry_sha256",
            "attempt_epoch",
        )
    private val ENTRY_FIELDS =
        listOf(
            "probe_base_scope_id",
            "attempt_epoch",
            "delegate",
            "state",
            "reason",
            "retry_used",
            "cache_invalidated",
            "retry_context_id",
            "terminal_evidence",
        )
    private val TERMINAL_PROOF_FIELDS =
        listOf(
            "trace_ordinal",
            "delegate",
            "role",
            "reason",
            "closure",
            "resolved_owner_mask",
        )
    private val PENDING_FIELDS =
        listOf(
            "probe_base_scope_id",
            "attempt_epoch",
            "capability_result_id",
            "selected_delegate",
            "retry_used",
            "retry_context_id",
            "expected_old_file_state",
            "expected_old_file_length",
            "expected_old_file_sha256",
            "intended_new_file_length",
            "intended_new_file_sha256",
            "intended_new_record_sha256",
        )

    private val TERMINAL_REASONS = JournalReason.entries.filter { it.wireValue in 0..2 }.toSet()
    private val QUARANTINE_REASONS = JournalReason.entries.filter { it.wireValue in 3..20 }.toSet()
    private val CONSUMED_REASONS = JournalReason.entries.filter { it.wireValue in 21..24 }.toSet()
    private val SAFE_TERMINAL_EVENTS = setOf(1, 4, 8, 9, 11, 13)
    private val LEGAL_FALLBACK_TRACES =
        listOf(
            listOf(15),
            listOf(0, 3, 6),
            listOf(0, 4, 6),
            listOf(0, 5, 6),
            listOf(0, 3, 7),
            listOf(1, 3, 7),
            listOf(2, 3, 7),
            listOf(0, 3, 9, 10, 12),
            listOf(1, 4, 14),
            listOf(1, 3, 9, 14),
            listOf(0, 4, 8, 14),
            listOf(0, 3, 9, 11, 14),
            listOf(0, 3, 9, 10, 13, 14),
        )

    private const val UNION_ABSENT: Byte = 0
    private const val UNION_PRESENT: Byte = 1
    private const val MODE_CONTROL_NONE: Byte = 0
    private const val MODE_CONTROL_RESET_REQUESTED: Byte = 1
    private const val MODE_CONTROL_RESET_RUNNING: Byte = 2
    private const val MODE_CONTROL_POST_SEAL_POISON: Byte = 3
    private const val RESOLVED_OWNER_MASK: Int = 0xff
}
