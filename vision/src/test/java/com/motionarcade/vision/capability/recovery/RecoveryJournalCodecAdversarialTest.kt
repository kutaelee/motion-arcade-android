package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeDelegate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class RecoveryJournalCodecAdversarialTest {
    @Test
    fun unknownMissingDuplicateAndOutOfOrderTopLevelFieldsAreRejected() {
        val canonical = emptyFields()
        val unknown = canonical.toMutableList().also { it[8] = "unknown_control" to it[8].second }
        val missing = canonical.dropLast(1)
        val duplicate = canonical.toMutableList().also { it[8] = "pending_store_commit" to it[8].second }
        val reordered = canonical.toMutableList().also { fields ->
            val swap = fields[7]
            fields[7] = fields[8]
            fields[8] = swap
        }

        listOf(unknown, missing, duplicate, reordered).forEach { fields ->
            assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, fields)))
        }
    }

    @Test
    fun correctChecksumCannotBlessTrailingUnknownOrMalformedValues() {
        val canonicalPayload = oracleManifest(PAYLOAD_DOMAIN, emptyFields())
        assertInvalidFile(file(canonicalPayload + byteArrayOf(0)))

        val unknownActive = emptyFields().toMutableList().also { it[4] = "active" to byteArrayOf(2) }
        assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, unknownActive)))

        val malformedMode = emptyFields().toMutableList().also { it[2] = "mode" to byteArrayOf(0, 0) }
        assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, malformedMode)))

        val unknownMode = emptyFields().toMutableList().also { it[2] = "mode" to byteArrayOf(2) }
        assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, unknownMode)))
    }

    @Test
    fun checksumMutationAppendedByteAndOversizeFileFailClosed() {
        val canonical = valid(
            RecoveryJournalV5Codec.encodeFile(
                RecoveryJournalPayloadV5.empty(
                    RecoveryJournalTestFixtures.runtime(),
                    RecoveryJournalMode.SOLO,
                ),
            ),
        )
        val payloadMutation = canonical.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        val checksumMutation = canonical.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertInvalidFile(payloadMutation)
        assertInvalidFile(checksumMutation)
        assertInvalidFile(canonical + byteArrayOf(0))
        assertInvalidFile(ByteArray(RecoveryJournalV5Codec.MAXIMUM_FILE_BYTES + 1))
    }

    @Test
    fun everyPayloadAndChecksumByteIsCoveredByMutationDetection() {
        val canonical = valid(
            RecoveryJournalV5Codec.encodeFile(
                RecoveryJournalPayloadV5.empty(
                    RecoveryJournalTestFixtures.runtime(),
                    RecoveryJournalMode.SOLO,
                ),
            ),
        )

        canonical.indices.forEach { index ->
            val mutated = canonical.copyOf()
            mutated[index] = (mutated[index].toInt() xor 1).toByte()
            assertInvalidFile(mutated)
        }
    }

    @Test
    fun recomputedChecksumCannotBlessInvalidMutationOfAnyTopLevelField() {
        val canonical = emptyFields()
        val mutations =
            canonical.indices.map { fieldIndex ->
                canonical.toMutableList().also { fields ->
                    val (name, value) = fields[fieldIndex]
                    fields[fieldIndex] = name to invalidTopLevelValue(fieldIndex, value)
                }
            }
        val unknownDomain = oracleManifest("recovery-journal-payload-v6", canonical)

        mutations.forEach { fields -> assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, fields))) }
        assertInvalidFile(file(unknownDomain))
    }

    @Test
    fun buildAndModeOwnerMismatchAreRejectedEvenForCanonicalBytes() {
        val value = RecoveryJournalPayloadV5.empty(RecoveryJournalTestFixtures.runtime(), RecoveryJournalMode.SOLO)
        val file = valid(RecoveryJournalV5Codec.encodeFile(value))

        assertTrue(
            RecoveryJournalV5Codec.decodeFile(
                file,
                RecoveryJournalTestFixtures.runtime(seed = 1),
                RecoveryJournalMode.SOLO,
            ) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            RecoveryJournalV5Codec.decodeFile(
                file,
                value.recoveryBuildId,
                RecoveryJournalMode.DUAL,
            ) is CapabilityDomainResult.Invalid,
        )
    }

    @Test
    fun terminalProofUnknownDuplicateAndOutOfOrderFieldsAreRejected() {
        val canonical = terminalProofFields()
        val unknown = canonical.toMutableList().also { it[5] = "unknown_mask" to it[5].second }
        val duplicate = canonical.toMutableList().also { it[5] = "closure" to it[5].second }
        val reordered = canonical.toMutableList().also { fields ->
            val swap = fields[0]
            fields[0] = fields[1]
            fields[1] = swap
        }

        listOf(unknown, duplicate, reordered).forEach { fields ->
            assertTrue(
                RecoveryJournalV5Codec.decodeTerminalProof(
                    oracleManifest(TERMINAL_PROOF_DOMAIN, fields),
                ) is CapabilityDomainResult.Invalid,
            )
        }
    }

    @Test
    fun impossibleTerminalOrdinalAndUnknownEntryReasonAreRejected() {
        val invalidOrdinal = terminalProofFields().toMutableList().also {
            it[0] = "trace_ordinal" to uint32(5u)
        }
        assertTrue(
            RecoveryJournalV5Codec.decodeTerminalProof(
                oracleManifest(TERMINAL_PROOF_DOMAIN, invalidOrdinal),
            ) is CapabilityDomainResult.Invalid,
        )

        val entry = valid(RecoveryJournalV5Codec.encodeEntry(RecoveryJournalTestFixtures.quarantineEntry()))
        val reasonValueOffset = findFieldValueOffset(entry, "reason")
        val unknownReason = entry.copyOf().also { it[reasonValueOffset] = 26 }
        assertTrue(RecoveryJournalV5Codec.decodeEntry(unknownReason) is CapabilityDomainResult.Invalid)
    }

    @Test
    fun truncatedLengthsAndUnionTrailingBytesAreRejectedBeforeAllocation() {
        val truncated = oracleManifest(PAYLOAD_DOMAIN, emptyFields()).copyOf(40)
        assertInvalidFile(file(truncated))

        val activeTrailing = emptyFields().toMutableList().also {
            it[4] = "active" to byteArrayOf(0, 0)
        }
        assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, activeTrailing)))

        val hugeLength = oracleManifest(PAYLOAD_DOMAIN, emptyFields()).copyOf()
        val schemaValueLengthOffset = PAYLOAD_DOMAIN.toByteArray().size + 1 + 4 + 4 + "schema_revision".toByteArray().size
        repeat(8) { index -> hugeLength[schemaValueLengthOffset + index] = 0xff.toByte() }
        assertInvalidFile(file(hugeLength))
    }

    @Test
    fun everyActiveAndManualContextFieldRejectsInvalidRecomputedChecksumMutation() {
        val value = RecoveryJournalTestFixtures.manualRetryPayload(activeDelegate = ProbeDelegate.CPU)
        val top = parseOracleManifest(valid(RecoveryJournalV5Codec.encodePayload(value))).second

        val activeFields = parseOracleManifest(top[4].second.copyOfRange(1, top[4].second.size)).second
        val invalidActiveValues =
            listOf(
                ByteArray(31),
                ByteArray(8),
                byteArrayOf(0),
                byteArrayOf(4),
                byteArrayOf(2),
                byteArrayOf(2),
                byteArrayOf(2),
            )
        assertNestedMutationsRejected(top, 4, ACTIVE_DOMAIN, activeFields, invalidActiveValues)

        val contextFields = parseOracleManifest(top[5].second.copyOfRange(1, top[5].second.size)).second
        val invalidContextValues =
            listOf(
                ByteArray(31),
                byteArrayOf(0),
                ByteArray(8),
                byteArrayOf(0),
                ByteArray(32),
                ByteArray(8),
            )
        assertNestedMutationsRejected(top, 5, MANUAL_CONTEXT_DOMAIN, contextFields, invalidContextValues)
    }

    @Test
    fun everyEntryAndTerminalProofFieldRejectsInvalidRecomputedChecksumMutation() {
        val quarantine = RecoveryJournalTestFixtures.quarantineEntry()
        val quarantinePayload = payloadWithEntries(listOf(quarantine), 1uL)
        val top = parseOracleManifest(valid(RecoveryJournalV5Codec.encodePayload(quarantinePayload))).second
        val entry = extractSingleEntry(top[6].second)
        val entryFields = parseOracleManifest(entry).second
        val invalidEntryValues =
            listOf(
                ByteArray(31),
                ByteArray(8),
                byteArrayOf(0),
                byteArrayOf(4),
                byteArrayOf(26),
                byteArrayOf(2),
                byteArrayOf(2),
                byteArrayOf(2),
                byteArrayOf(2),
            )
        entryFields.indices.forEach { index ->
            val mutatedFields = entryFields.toMutableList().also { it[index] = it[index].first to invalidEntryValues[index] }
            val mutatedEntry = oracleManifest(ENTRY_DOMAIN, mutatedFields)
            val mutatedTop = top.toMutableList().also { it[6] = it[6].first to oneEntryList(mutatedEntry) }
            assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, mutatedTop)))
        }

        val terminal = RecoveryJournalTestFixtures.terminalEntry()
        val terminalTop = parseOracleManifest(
            valid(RecoveryJournalV5Codec.encodePayload(payloadWithEntries(listOf(terminal), 1uL))),
        ).second
        val terminalEntryFields = parseOracleManifest(extractSingleEntry(terminalTop[6].second)).second
        val evidence = terminalEntryFields[8].second
        val proofLength = readUInt32(evidence, 1)
        val proof = evidence.copyOfRange(5, 5 + proofLength)
        val proofFields = parseOracleManifest(proof).second
        val invalidProofValues =
            listOf(
                uint32(5u),
                byteArrayOf(0),
                byteArrayOf(4),
                byteArrayOf(3),
                byteArrayOf(2),
                byteArrayOf(0xfe.toByte()),
            )
        proofFields.indices.forEach { index ->
            val mutatedProofFields = proofFields.toMutableList().also { it[index] = it[index].first to invalidProofValues[index] }
            val mutatedProof = oracleManifest(TERMINAL_PROOF_DOMAIN, mutatedProofFields)
            val mutatedEvidence = byteArrayOf(1) + uint32(mutatedProof.size.toUInt()) + mutatedProof
            val mutatedEntryFields = terminalEntryFields.toMutableList().also { it[8] = it[8].first to mutatedEvidence }
            val mutatedEntry = oracleManifest(ENTRY_DOMAIN, mutatedEntryFields)
            val mutatedTop = terminalTop.toMutableList().also { it[6] = it[6].first to oneEntryList(mutatedEntry) }
            assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, mutatedTop)))
        }
    }

    @Test
    fun everyPendingFieldRejectsInvalidRecomputedChecksumMutation() {
        val pending =
            PendingStoreCommitV5(
                probeBaseScopeId = RecoveryJournalTestFixtures.scope(),
                attemptEpoch = 1uL,
                capabilityResultId = RecoveryJournalTestFixtures.result(),
                selectedDelegate = ProbeDelegate.NONE,
                retryUsed = false,
                retryContextId = null,
                expectedOldFileState = ExpectedOldFileState.ABSENT,
                expectedOldFileLength = 0uL,
                expectedOldFileSha256 = null,
                intendedNewFileLength = 1uL,
                intendedNewFileSha256 = RecoveryJournalTestFixtures.digest(96),
                intendedNewRecordSha256 = RecoveryJournalTestFixtures.digest(128),
            )
        val value = payloadWithEntries(emptyList(), 1uL).copy(pendingStoreCommit = pending)
        val top = parseOracleManifest(valid(RecoveryJournalV5Codec.encodePayload(value))).second
        val pendingFields = parseOracleManifest(top[7].second.copyOfRange(1, top[7].second.size)).second
        val invalidPendingValues =
            listOf(
                ByteArray(31),
                ByteArray(8),
                ByteArray(31),
                byteArrayOf(3),
                byteArrayOf(2),
                byteArrayOf(2),
                byteArrayOf(2),
                ByteArray(7),
                byteArrayOf(2),
                ByteArray(8),
                ByteArray(31),
                ByteArray(31),
            )
        assertNestedMutationsRejected(top, 7, PENDING_DOMAIN, pendingFields, invalidPendingValues)
    }

    private fun assertInvalidFile(bytes: ByteArray) {
        assertTrue(
            RecoveryJournalV5Codec.decodeFile(
                bytes,
                RecoveryJournalTestFixtures.runtime(),
                RecoveryJournalMode.SOLO,
            ) is CapabilityDomainResult.Invalid,
        )
    }

    private fun assertNestedMutationsRejected(
        topFields: List<Pair<String, ByteArray>>,
        topIndex: Int,
        nestedDomain: String,
        nestedFields: List<Pair<String, ByteArray>>,
        invalidValues: List<ByteArray>,
    ) {
        assertTrue(nestedFields.size == invalidValues.size)
        nestedFields.indices.forEach { index ->
            val mutatedNested = nestedFields.toMutableList().also { it[index] = it[index].first to invalidValues[index] }
            val union = byteArrayOf(1) + oracleManifest(nestedDomain, mutatedNested)
            val mutatedTop = topFields.toMutableList().also { it[topIndex] = it[topIndex].first to union }
            assertInvalidFile(file(oracleManifest(PAYLOAD_DOMAIN, mutatedTop)))
        }
    }

    private fun payloadWithEntries(entries: List<JournalEntryV5>, lastEpoch: ULong): RecoveryJournalPayloadV5 =
        RecoveryJournalPayloadV5(
            schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
            mode = RecoveryJournalMode.SOLO,
            lastEpoch = lastEpoch,
            active = null,
            manualRetryContext = null,
            entries = entries,
            pendingStoreCommit = null,
            modeControl = ModeControlV5.None,
        )

    private fun extractSingleEntry(entriesValue: ByteArray): ByteArray {
        assertTrue(entriesValue.copyOfRange(0, 4).contentEquals(uint32(1u)))
        val length = readUInt32(entriesValue, 4)
        assertTrue(entriesValue.size == 8 + length)
        return entriesValue.copyOfRange(8, 8 + length)
    }

    private fun oneEntryList(entry: ByteArray): ByteArray = uint32(1u) + uint32(entry.size.toUInt()) + entry

    private fun emptyFields(): List<Pair<String, ByteArray>> =
        listOf(
            "schema_revision" to PAYLOAD_DOMAIN.toByteArray(),
            "recovery_build_id" to ByteArray(32) { it.toByte() },
            "mode" to byteArrayOf(0),
            "last_epoch" to ByteArray(8),
            "active" to byteArrayOf(0),
            "manual_retry_context" to byteArrayOf(0),
            "entries" to ByteArray(4),
            "pending_store_commit" to byteArrayOf(0),
            "mode_control" to byteArrayOf(0),
        )

    private fun terminalProofFields(): List<Pair<String, ByteArray>> =
        listOf(
            "trace_ordinal" to uint32(0u),
            "delegate" to byteArrayOf(1),
            "role" to byteArrayOf(0),
            "reason" to byteArrayOf(0),
            "closure" to byteArrayOf(0),
            "resolved_owner_mask" to byteArrayOf(0xff.toByte()),
        )

    private fun invalidTopLevelValue(fieldIndex: Int, value: ByteArray): ByteArray =
        when (fieldIndex) {
            0 -> "recovery-journal-payload-v6".toByteArray()
            1 -> value.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            2 -> byteArrayOf(1)
            3 -> ByteArray(7)
            4 -> byteArrayOf(2)
            5 -> byteArrayOf(2)
            6 -> uint32(9u)
            7 -> byteArrayOf(2)
            8 -> byteArrayOf(4)
            else -> error("Unexpected top-level field index: $fieldIndex")
        }

    private fun oracleManifest(domain: String, fields: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(domain.toByteArray(Charsets.UTF_8))
            data.writeByte(0)
            data.writeInt(fields.size)
            fields.forEach { (name, value) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                data.writeInt(nameBytes.size)
                data.write(nameBytes)
                data.writeLong(value.size.toLong())
                data.write(value)
            }
        }
        return output.toByteArray()
    }

    private fun parseOracleManifest(bytes: ByteArray): Pair<String, List<Pair<String, ByteArray>>> {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val domainBytes = ByteArrayOutputStream()
            while (true) {
                val value = data.readUnsignedByte()
                if (value == 0) break
                domainBytes.write(value)
            }
            val fieldCount = data.readInt()
            val fields = ArrayList<Pair<String, ByteArray>>(fieldCount)
            repeat(fieldCount) {
                val nameLength = data.readInt()
                val name = ByteArray(nameLength).also(data::readFully).toString(Charsets.UTF_8)
                val valueLength = data.readLong()
                assertTrue(valueLength in 0..Int.MAX_VALUE.toLong())
                val value = ByteArray(valueLength.toInt()).also(data::readFully)
                fields += name to value
            }
            assertTrue(data.read() == -1)
            return domainBytes.toByteArray().toString(Charsets.UTF_8) to fields
        }
    }

    private fun file(payload: ByteArray): ByteArray = payload + MessageDigest.getInstance("SHA-256").digest(payload)

    private fun uint32(value: UInt): ByteArray =
        byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun findFieldValueOffset(manifest: ByteArray, name: String): Int {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        for (index in 0..manifest.size - nameBytes.size) {
            if (manifest.copyOfRange(index, index + nameBytes.size).contentEquals(nameBytes)) {
                return index + nameBytes.size + 8
            }
        }
        error("Field not found: $name")
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T = RecoveryJournalTestFixtures.valid(result)

    private companion object {
        const val PAYLOAD_DOMAIN = "recovery-journal-payload-v5"
        const val ACTIVE_DOMAIN = "journal-active-v5"
        const val MANUAL_CONTEXT_DOMAIN = "manual-retry-context-v1"
        const val ENTRY_DOMAIN = "journal-entry-v5"
        const val TERMINAL_PROOF_DOMAIN = "terminal-proof-v1"
        const val PENDING_DOMAIN = "pending-store-commit-v5"
    }
}
