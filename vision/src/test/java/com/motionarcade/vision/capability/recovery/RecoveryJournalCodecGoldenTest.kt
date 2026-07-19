package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class RecoveryJournalCodecGoldenTest {
    @Test
    fun emptySoloPayloadAndFileMatchIndependentLiteralOracles() {
        val payload = RecoveryJournalPayloadV5.empty(
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(seed = 0),
            mode = RecoveryJournalMode.SOLO,
        )

        val payloadBytes = valid(RecoveryJournalV5Codec.encodePayload(payload))
        val fileBytes = valid(RecoveryJournalV5Codec.encodeFile(payload))

        assertEquals(327, payloadBytes.size)
        assertEquals(
            "31d8b8ae2e9cc907f0150eb31599af4448a5176e21599fa57e7ce25a52719cfc",
            sha256(payloadBytes),
        )
        assertEquals(359, fileBytes.size)
        assertEquals(
            "67365230812f10f6393e4ddd936b6431b50aac6fa59077be2c5e843413a86255",
            sha256(fileBytes),
        )
        assertEquals(
            payload,
            valid(
                RecoveryJournalV5Codec.decodeFile(
                    fileBytes,
                    expectedRecoveryBuildId = payload.recoveryBuildId,
                    expectedMode = payload.mode,
                ),
            ),
        )
    }

    @Test
    fun terminalProofMatchesIndependentLengthHashAndLiteralPrefix() {
        val bytes = valid(RecoveryJournalV5Codec.encodeTerminalProof(RecoveryJournalTestFixtures.proof()))

        assertEquals(160, bytes.size)
        assertEquals(
            "045c85ffc559bc4c571ee66b2a52702e377aaed9e7c6a7b95214672b01e6f82d",
            sha256(bytes),
        )
        assertTrue(bytes.copyOfRange(0, 18).contentEquals("terminal-proof-v1\u0000".toByteArray()))
        assertEquals(RecoveryJournalTestFixtures.proof(), valid(RecoveryJournalV5Codec.decodeTerminalProof(bytes)))
    }

    @Test
    fun quarantineEntryMatchesIndependentLengthAndHashOracle() {
        val entry = RecoveryJournalTestFixtures.quarantineEntry()
        val bytes = valid(RecoveryJournalV5Codec.encodeEntry(entry))

        assertEquals(287, bytes.size)
        assertEquals(
            "5ff62b1c893c26b198b3dc27b0a493b47eb5296fa2b7b90be846f33bed3a1312",
            sha256(bytes),
        )
        assertEquals(entry, valid(RecoveryJournalV5Codec.decodeEntry(bytes)))
    }

    @Test
    fun sourceArraysAndEntryListsAreDefensivelySnapshotted() {
        val source = ByteArray(32) { it.toByte() }
        val digest = valid(com.motionarcade.vision.capability.domain.Sha256Digest.fromBytes(source))
        source.fill(99)
        val mutableEntries = mutableListOf(RecoveryJournalTestFixtures.quarantineEntry())
        val value =
            RecoveryJournalPayloadV5(
                schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
                recoveryBuildId = com.motionarcade.vision.capability.domain.RuntimeArtifactId(digest),
                mode = RecoveryJournalMode.SOLO,
                lastEpoch = 1uL,
                active = null,
                manualRetryContext = null,
                entries = mutableEntries,
                pendingStoreCommit = null,
                modeControl = ModeControlV5.None,
            )
        mutableEntries.clear()

        assertArrayEquals(ByteArray(32) { it.toByte() }, value.recoveryBuildId.digest.copyBytes())
        assertEquals(1, value.entries.size)
        assertNotSame(mutableEntries, value.entries)
        assertEquals(value, valid(RecoveryJournalV5Codec.decodePayload(
            valid(RecoveryJournalV5Codec.encodePayload(value)),
            value.recoveryBuildId,
            value.mode,
        )))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> valid(result: CapabilityDomainResult<T>): T = RecoveryJournalTestFixtures.valid(result)
}
