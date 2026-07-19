package com.motionarcade.vision.capability.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalManifestCodecTest {
    @Test
    fun encodingUsesUtf8ByteLengthsAndExactBigEndianFraming() {
        val encoded = valid(
            CanonicalManifestCodec.encode(
                domain = "d",
                fields = listOf(CanonicalField("é", byteArrayOf(0x7f))),
            ),
        )

        assertArrayEquals(
            hex("64000000000100000002c3a900000000000000017f"),
            encoded,
        )
    }

    @Test
    fun fieldOrderIsCallerSpecifiedAndMutationChangesHash() {
        val first = valid(
            CanonicalManifestCodec.encode(
                "ordered-v1",
                listOf(CanonicalField("a", byteArrayOf(1)), CanonicalField("b", byteArrayOf(2))),
            ),
        )
        val reordered = valid(
            CanonicalManifestCodec.encode(
                "ordered-v1",
                listOf(CanonicalField("b", byteArrayOf(2)), CanonicalField("a", byteArrayOf(1))),
            ),
        )
        val mutated = first.copyOf().also { it[it.lastIndex] = 3 }

        assertNotEquals(CanonicalManifestCodec.sha256(first), CanonicalManifestCodec.sha256(reordered))
        assertNotEquals(CanonicalManifestCodec.sha256(first), CanonicalManifestCodec.sha256(mutated))
    }

    @Test
    fun duplicateFieldMalformedUnicodeNulAndSizeOverflowFailClosed() {
        assertTrue(
            CanonicalManifestCodec.encode(
                "duplicate-v1",
                listOf(CanonicalField("a", byteArrayOf()), CanonicalField("a", byteArrayOf())),
            ) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            CanonicalManifestCodec.encode("bad\uD800", emptyList()) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            CanonicalManifestCodec.encode("bad\u0000domain", emptyList()) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            CanonicalManifestCodec.encode(
                "cap-v1",
                listOf(CanonicalField("a", byteArrayOf(1))),
                maximumBytes = 1,
            ) is CapabilityDomainResult.Invalid,
        )
    }

    @Test
    fun integerAndUnsignedOrderingHelpersAreExact() {
        assertArrayEquals(hex("ffffffff"), CanonicalManifestCodec.uint32(UInt.MAX_VALUE))
        assertArrayEquals(hex("ffffffffffffffff"), CanonicalManifestCodec.uint64(ULong.MAX_VALUE))
        assertTrue(CanonicalManifestCodec.compareUnsigned(byteArrayOf(0x7f), byteArrayOf(0x80.toByte())) < 0)
        assertEquals(0, valid(CanonicalManifestCodec.compareUnsignedUtf8("같음", "같음")))
    }

    @Test
    fun fieldValueAndFieldListAreSnapshottedBeforeEncoding() {
        val mutableValue = byteArrayOf(1, 2, 3)
        val field = CanonicalField("a", mutableValue)
        mutableValue.fill(9)
        val mutableFields = mutableListOf(field)

        val encoded = valid(CanonicalManifestCodec.encode("snapshot-v1", mutableFields))
        mutableFields.clear()

        assertArrayEquals(
            hex("736e617073686f742d7631000000000100000001610000000000000003010203"),
            encoded,
        )
    }

    @Test
    fun maximumBytesAcceptsExactSizeAndRejectsOneAdditionalEncodedByte() {
        val fieldsAtLimit = listOf(CanonicalField("value", byteArrayOf(1, 2, 3)))
        val unconstrained = valid(CanonicalManifestCodec.encode("bounded-v1", fieldsAtLimit))

        assertArrayEquals(
            unconstrained,
            valid(
                CanonicalManifestCodec.encode(
                    domain = "bounded-v1",
                    fields = fieldsAtLimit,
                    maximumBytes = unconstrained.size,
                ),
            ),
        )
        assertTrue(
            CanonicalManifestCodec.encode(
                domain = "bounded-v1",
                fields = listOf(CanonicalField("value", byteArrayOf(1, 2, 3, 4))),
                maximumBytes = unconstrained.size,
            ) is CapabilityDomainResult.Invalid,
        )
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }
}
