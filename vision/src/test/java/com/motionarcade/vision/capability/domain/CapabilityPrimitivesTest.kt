package com.motionarcade.vision.capability.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPrimitivesTest {
    @Test
    fun digestDefensivelyCopiesInputAndOutputAndUsesContentEquality() {
        val source = ByteArray(32) { it.toByte() }
        val first = valid(Sha256Digest.fromBytes(source))
        val same = valid(Sha256Digest.fromBytes(source.copyOf()))

        source[0] = 99
        val extracted = first.copyBytes()
        extracted[1] = 99

        assertEquals(same, first)
        assertEquals(same.hashCode(), first.hashCode())
        assertEquals(0, first.copyBytes()[0].toInt())
        assertEquals(1, first.copyBytes()[1].toInt())
        assertArrayEquals(ByteArray(32) { it.toByte() }, first.copyBytes())
    }

    @Test
    fun digestRejectsWrongLengthAndNonLowercaseHex() {
        assertTrue(Sha256Digest.fromBytes(ByteArray(31)) is CapabilityDomainResult.Invalid)
        assertTrue(Sha256Digest.fromLowerHex("A".repeat(64)) is CapabilityDomainResult.Invalid)
        assertTrue(Sha256Digest.fromLowerHex("0".repeat(63)) is CapabilityDomainResult.Invalid)
    }

    @Test
    fun digestRoundTripsExactLowercaseHex() {
        val hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val digest = valid(Sha256Digest.fromLowerHex(hex))
        assertEquals(hex, digest.toLowerHex())
        assertNotEquals(digest, valid(Sha256Digest.fromBytes(ByteArray(32))))
        assertEquals("ff".repeat(32), valid(Sha256Digest.fromBytes(ByteArray(32) { 0xff.toByte() })).toLowerHex())
    }

    @Test
    fun delegateAndRunningModeWireValuesAreExplicit() {
        assertEquals(listOf(0, 1, 2), ProbeDelegate.entries.map(ProbeDelegate::wireValue))
        assertEquals(listOf(0), ProbeRunningMode.entries.map(ProbeRunningMode::wireValue))
    }

    @Test
    fun invalidResultDefensivelyCopiesViolations() {
        val source = mutableListOf<CapabilityDomainViolation>(
            CapabilityDomainViolation.InvalidValue("$/a", "expected", "actual"),
        )
        val invalid = CapabilityDomainResult.Invalid(source)

        source.clear()

        assertEquals(1, invalid.violations.size)
        assertEquals("$/a", invalid.violations.single().path)
        var mutationRejected = false
        try {
            @Suppress("UNCHECKED_CAST")
            (invalid.violations as MutableList<CapabilityDomainViolation>).clear()
        } catch (_: UnsupportedOperationException) {
            mutationRejected = true
        }
        assertTrue(mutationRejected)
        assertEquals(1, invalid.violations.size)
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }
}
