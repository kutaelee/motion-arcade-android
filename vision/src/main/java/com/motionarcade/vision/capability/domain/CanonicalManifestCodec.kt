package com.motionarcade.vision.capability.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class CanonicalField(
    val name: String,
    value: ByteArray,
) {
    private val immutableValue = value.copyOf()

    fun copyValue(): ByteArray = immutableValue.copyOf()
}

internal object CanonicalManifestCodec {
    fun encode(
        domain: String,
        fields: List<CanonicalField>,
        maximumBytes: Int = Int.MAX_VALUE,
    ): CapabilityDomainResult<ByteArray> {
        if (maximumBytes < 0) return invalid("$/maximumBytes", ">= 0", maximumBytes.toString())
        val domainBytes = when (val encoded = strictUtf8(domain, "$/domain")) {
            is CapabilityDomainResult.Valid -> encoded.value
            is CapabilityDomainResult.Invalid -> return encoded
        }
        if (domainBytes.isEmpty() || domainBytes.any { it == 0.toByte() }) {
            return invalid("$/domain", "nonempty UTF-8 without NUL", domain)
        }
        val fieldSnapshot = fields.map { field ->
            EncodedField(field.name, field.copyValue())
        }
        val duplicateName = fieldSnapshot.groupingBy(EncodedField::name).eachCount().entries
            .firstOrNull { it.value > 1 }
        if (duplicateName != null) {
            return CapabilityDomainResult.Invalid(
                listOf(CapabilityDomainViolation.DuplicateValue("$/fields", duplicateName.key)),
            )
        }

        val encodedNames = ArrayList<ByteArray>(fieldSnapshot.size)
        var size = 0L
        try {
            size = Math.addExact(size, domainBytes.size.toLong())
            size = Math.addExact(size, 1L + UInt.SIZE_BYTES)
            fieldSnapshot.forEachIndexed { index, field ->
                val nameBytes = when (val encoded = strictUtf8(field.name, "$/fields/$index/name")) {
                    is CapabilityDomainResult.Valid -> encoded.value
                    is CapabilityDomainResult.Invalid -> return encoded
                }
                if (nameBytes.isEmpty() || nameBytes.any { it == 0.toByte() }) {
                    return invalid("$/fields/$index/name", "nonempty UTF-8 without NUL", field.name)
                }
                encodedNames += nameBytes
                size = Math.addExact(size, UInt.SIZE_BYTES.toLong())
                size = Math.addExact(size, nameBytes.size.toLong())
                size = Math.addExact(size, ULong.SIZE_BYTES.toLong())
                size = Math.addExact(size, field.value.size.toLong())
            }
        } catch (_: ArithmeticException) {
            return overflow("$/manifest", "encoded-size addition")
        }
        if (size > maximumBytes.toLong() || size > Int.MAX_VALUE.toLong()) {
            return invalid("$/manifest", "encoded size <= $maximumBytes", size.toString())
        }

        val output = ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN)
        output.put(domainBytes)
        output.put(0)
        output.putInt(fieldSnapshot.size)
        fieldSnapshot.forEachIndexed { index, field ->
            val nameBytes = encodedNames[index]
            output.putInt(nameBytes.size)
            output.put(nameBytes)
            output.putLong(field.value.size.toLong())
            output.put(field.value)
        }
        check(!output.hasRemaining())
        return CapabilityDomainResult.Valid(output.array())
    }

    fun strictUtf8(value: String, path: String): CapabilityDomainResult<ByteArray> =
        try {
            val encoder = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = encoder.encode(CharBuffer.wrap(value))
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            CapabilityDomainResult.Valid(bytes)
        } catch (_: Exception) {
            invalid(path, "well-formed UTF-8 input", "malformed UTF-16 string")
        }

    fun uint32(value: UInt): ByteArray =
        ByteBuffer.allocate(UInt.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

    fun uint64(value: ULong): ByteArray =
        ByteArray(ULong.SIZE_BYTES) { index ->
            ((value shr ((ULong.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)) and 0xffuL).toByte()
        }

    fun int64(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    fun oneByte(value: Int, path: String): CapabilityDomainResult<ByteArray> =
        if (value in 0..0xff) {
            CapabilityDomainResult.Valid(byteArrayOf(value.toByte()))
        } else {
            invalid(path, "unsigned byte 0..255", value.toString())
        }

    fun boolean(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    fun sha256(bytes: ByteArray): Sha256Digest =
        Sha256Digest.trusted(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val shared = minOf(left.size, right.size)
        for (index in 0 until shared) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    fun compareUnsignedUtf8(left: String, right: String): CapabilityDomainResult<Int> =
        strictUtf8(left, "$/left").flatMap { leftBytes ->
            strictUtf8(right, "$/right").map { rightBytes -> compareUnsigned(leftBytes, rightBytes) }
        }

    fun frameLengthPrefixed(value: ByteArray): CapabilityDomainResult<ByteArray> {
        val size = try {
            Math.addExact(UInt.SIZE_BYTES, value.size)
        } catch (_: ArithmeticException) {
            return overflow("$/framedValue", "uint32 length prefix + value length")
        }
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(value.size)
        output.put(value)
        return CapabilityDomainResult.Valid(output.array())
    }

    private data class EncodedField(
        val name: String,
        val value: ByteArray,
    )
}

internal fun invalid(
    path: String,
    expected: String,
    actual: String,
): CapabilityDomainResult.Invalid =
    CapabilityDomainResult.Invalid(
        listOf(CapabilityDomainViolation.InvalidValue(path, expected, actual)),
    )

internal fun overflow(path: String, operation: String): CapabilityDomainResult.Invalid =
    CapabilityDomainResult.Invalid(
        listOf(CapabilityDomainViolation.ArithmeticOverflow(path, operation)),
    )
