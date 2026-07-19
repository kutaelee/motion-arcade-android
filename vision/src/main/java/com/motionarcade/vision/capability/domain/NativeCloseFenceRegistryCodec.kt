package com.motionarcade.vision.capability.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

enum class NativeCloseAbi(val wireName: String) {
    ARM64_V8A("arm64-v8a"),
    ARMEABI_V7A("armeabi-v7a"),
    X86("x86"),
    X86_64("x86_64"),
    ;

    companion object {
        internal fun fromWireName(value: String): NativeCloseAbi? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Immutable, parsed registry row. This is data only: parsing a row never grants native-create
 * authority. The exact canonical bytes are retained so a later issuer can bind the complete row
 * instead of a lossy projection of its hashes.
 */
sealed interface NativeCloseFenceEntryV1 {
    val proofBasisSha256: Sha256Digest
    val osApi: Int
    val abi: NativeCloseAbi
    val installedJniSetSha256: Sha256Digest
    val delegateMask: Int
    val proofBundleSha256: Sha256Digest

    fun copyCanonicalBytes(): ByteArray
}

private class ParsedNativeCloseFenceEntryV1(
    override val proofBasisSha256: Sha256Digest,
    override val osApi: Int,
    override val abi: NativeCloseAbi,
    override val installedJniSetSha256: Sha256Digest,
    override val delegateMask: Int,
    override val proofBundleSha256: Sha256Digest,
    canonicalBytes: ByteArray,
) : NativeCloseFenceEntryV1 {
    private val canonicalValue = canonicalBytes.copyOf()

    override fun copyCanonicalBytes(): ByteArray = canonicalValue.copyOf()
}

/** Parsed registry inventory. An empty inventory is canonical but authorizes no native call. */
sealed interface NativeCloseFenceRegistryV1 {
    val entries: List<NativeCloseFenceEntryV1>

    fun copyCanonicalBytes(): ByteArray
}

private class ParsedNativeCloseFenceRegistryV1(
    entries: List<NativeCloseFenceEntryV1>,
    canonicalBytes: ByteArray,
) : NativeCloseFenceRegistryV1 {
    override val entries: List<NativeCloseFenceEntryV1> =
        Collections.unmodifiableList(ArrayList(entries))
    private val canonicalValue = canonicalBytes.copyOf()

    override fun copyCanonicalBytes(): ByteArray = canonicalValue.copyOf()
}

/**
 * Fail-closed decoder for the runtime-visible NativeCloseFenceRegistryV1 asset.
 *
 * This decoder validates only the registry grammar and row ordering. It deliberately does not
 * mint a proof snapshot or treat bundle hashes as proof; workload, installed APK DEX/JNI, API,
 * bundle-at-build, and route bindings remain separate authorization gates.
 */
object NativeCloseFenceRegistryCodec {
    fun decode(bytes: ByteArray): CapabilityDomainResult<NativeCloseFenceRegistryV1> {
        if (bytes.size > MAX_REGISTRY_BYTES) {
            return rejected("$/registry", "at most $MAX_REGISTRY_BYTES bytes", bytes.size)
        }
        val snapshot = bytes.copyOf()
        val registry = try {
            parseRecord(
                bytes = snapshot,
                domain = REGISTRY_DOMAIN,
                fieldNames = REGISTRY_FIELDS,
            )
        } catch (failure: RegistryDecodeFailure) {
            return rejected(failure.path, failure.expected, failure.actual)
        }
        if (!registry.values[0].contentEquals(REGISTRY_DOMAIN_BYTES)) {
            return rejected(
                "$/schema_revision",
                REGISTRY_DOMAIN,
                printable(registry.values[0]),
            )
        }

        val framedRows = try {
            parseFramedRows(registry.values[1])
        } catch (failure: RegistryDecodeFailure) {
            return rejected(failure.path, failure.expected, failure.actual)
        }
        val entries = ArrayList<NativeCloseFenceEntryV1>(framedRows.size)
        val keys = ArrayList<ByteArray>(framedRows.size)
        framedRows.forEachIndexed { index, rowBytes ->
            val row = try {
                parseEntry(rowBytes, index)
            } catch (failure: RegistryDecodeFailure) {
                return rejected(failure.path, failure.expected, failure.actual)
            }
            val key = rowKey(row)
            if (keys.isNotEmpty() && CanonicalManifestCodec.compareUnsigned(keys.last(), key) >= 0) {
                return rejected(
                    "$/entries/$index",
                    "strict unsigned canonical row order without duplicates",
                    "out of order or duplicate",
                )
            }
            entries += row
            keys += key
        }
        return CapabilityDomainResult.Valid(ParsedNativeCloseFenceRegistryV1(entries, snapshot))
    }

    private fun parseEntry(bytes: ByteArray, index: Int): NativeCloseFenceEntryV1 {
        val path = "$/entries/$index"
        if (bytes.size !in MIN_ENTRY_BYTES..MAX_ENTRY_BYTES) {
            fail(path, "$MIN_ENTRY_BYTES..$MAX_ENTRY_BYTES bytes", bytes.size)
        }
        val record = parseRecord(bytes, ENTRY_DOMAIN, ENTRY_FIELDS, path)
        val basis = digest(record.values[0], "$path/proof_basis_sha256", nonzero = true)
        val api = uint32(record.values[1], "$path/os_api")
        if (api !in MIN_API..MAX_API) {
            fail("$path/os_api", "$MIN_API..$MAX_API", api)
        }
        val abiText = strictUtf8(record.values[2], "$path/abi")
        val abi = NativeCloseAbi.fromWireName(abiText)
            ?: fail("$path/abi", "registered ABI", abiText)
        val jni = digest(record.values[3], "$path/installed_jni_set_sha256", nonzero = true)
        val delegate = record.values[4]
        if (delegate.size != 1 || (delegate[0].toInt() and 0xff) != REQUIRED_DELEGATE_MASK) {
            fail(
                "$path/delegate_mask",
                "exact byte 0x03",
                delegate.joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        }
        val bundle = digest(record.values[5], "$path/proof_bundle_sha256", nonzero = true)
        return ParsedNativeCloseFenceEntryV1(
            proofBasisSha256 = basis,
            osApi = api,
            abi = abi,
            installedJniSetSha256 = jni,
            delegateMask = REQUIRED_DELEGATE_MASK,
            proofBundleSha256 = bundle,
            canonicalBytes = bytes,
        )
    }

    private fun parseFramedRows(bytes: ByteArray): List<ByteArray> {
        val cursor = Cursor(bytes, "$/entries")
        val count = cursor.uint32("$/entries/count")
        if (count > MAX_ENTRY_COUNT) {
            fail("$/entries/count", "0..$MAX_ENTRY_COUNT", count)
        }
        val result = ArrayList<ByteArray>(count)
        repeat(count) { index ->
            val length = cursor.uint32("$/entries/$index/length")
            if (length !in MIN_ENTRY_BYTES..MAX_ENTRY_BYTES) {
                fail(
                    "$/entries/$index/length",
                    "$MIN_ENTRY_BYTES..$MAX_ENTRY_BYTES",
                    length,
                )
            }
            result += cursor.bytes(length, "$/entries/$index/value")
        }
        cursor.requireEnd()
        return result
    }

    private fun parseRecord(
        bytes: ByteArray,
        domain: String,
        fieldNames: List<String>,
        path: String = "$",
    ): ParsedRecord {
        val cursor = Cursor(bytes, path)
        val expectedDomain = domain.toByteArray(StandardCharsets.UTF_8)
        val actualDomain = cursor.bytes(expectedDomain.size, "$path/domain")
        if (!actualDomain.contentEquals(expectedDomain)) {
            fail("$path/domain", domain, printable(actualDomain))
        }
        val separator = cursor.byte("$path/domain_separator")
        if (separator != 0) fail("$path/domain_separator", "NUL", separator)
        val count = cursor.uint32("$path/field_count")
        if (count != fieldNames.size) {
            fail("$path/field_count", fieldNames.size.toString(), count)
        }
        val values = ArrayList<ByteArray>(count)
        repeat(count) { index ->
            val expectedName = fieldNames[index]
            val nameLength = cursor.uint32("$path/fields/$index/name_length")
            val nameBytes = cursor.bytes(nameLength, "$path/fields/$index/name")
            val name = strictUtf8(nameBytes, "$path/fields/$index/name")
            if (name != expectedName) {
                fail("$path/fields/$index/name", expectedName, name)
            }
            val valueLength = cursor.uint64Length("$path/fields/$index/value_length")
            values += cursor.bytes(valueLength, "$path/fields/$index/value")
        }
        cursor.requireEnd()

        val canonical = when (
            val result = CanonicalManifestCodec.encode(
                domain = domain,
                fields = fieldNames.zip(values).map { (name, value) -> CanonicalField(name, value) },
                maximumBytes = bytes.size,
            )
        ) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid ->
                fail(path, "canonical manifest", result.toString())
        }
        if (!canonical.contentEquals(bytes)) {
            fail(path, "byte-identical canonical manifest", "noncanonical encoding")
        }
        return ParsedRecord(values)
    }

    private fun digest(value: ByteArray, path: String, nonzero: Boolean): Sha256Digest {
        if (value.size != Sha256Digest.BYTE_COUNT) {
            fail(path, "exactly ${Sha256Digest.BYTE_COUNT} bytes", value.size)
        }
        if (nonzero && value.all { it == 0.toByte() }) {
            fail(path, "nonzero SHA-256", "all zero")
        }
        return Sha256Digest.trusted(value)
    }

    private fun uint32(value: ByteArray, path: String): Int {
        if (value.size != UInt.SIZE_BYTES) fail(path, "exactly 4 bytes", value.size)
        val unsigned = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        if (unsigned > Int.MAX_VALUE.toLong()) fail(path, "0..${Int.MAX_VALUE}", unsigned)
        return unsigned.toInt()
    }

    private fun rowKey(row: NativeCloseFenceEntryV1): ByteArray {
        val abi = row.abi.wireName.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(
            Sha256Digest.BYTE_COUNT + UInt.SIZE_BYTES + UInt.SIZE_BYTES + abi.size +
                Sha256Digest.BYTE_COUNT,
        ).order(ByteOrder.BIG_ENDIAN)
            .put(row.proofBasisSha256.copyBytes())
            .putInt(row.osApi)
            .putInt(abi.size)
            .put(abi)
            .put(row.installedJniSetSha256.copyBytes())
            .array()
    }

    private fun strictUtf8(bytes: ByteArray, path: String): String =
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            fail(path, "well-formed UTF-8", "malformed bytes")
        }

    private fun printable(bytes: ByteArray): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .let(CharBuffer::toString)
        } catch (_: Exception) {
            "${bytes.size} non-UTF-8 bytes"
        }

    private fun rejected(path: String, expected: String, actual: Any): CapabilityDomainResult.Invalid =
        CapabilityDomainResult.Invalid(
            listOf(CapabilityDomainViolation.InvalidValue(path, expected, actual.toString())),
        )

    private fun fail(path: String, expected: String, actual: Any): Nothing =
        throw RegistryDecodeFailure(path, expected, actual.toString())

    private class Cursor(
        private val bytes: ByteArray,
        private val path: String,
    ) {
        private var position = 0

        fun byte(valuePath: String): Int = bytes(1, valuePath)[0].toInt() and 0xff

        fun uint32(valuePath: String): Int {
            val raw = bytes(UInt.SIZE_BYTES, valuePath)
            val value = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            if (value > Int.MAX_VALUE.toLong()) fail(valuePath, "0..${Int.MAX_VALUE}", value)
            return value.toInt()
        }

        fun uint64Length(valuePath: String): Int {
            val raw = bytes(ULong.SIZE_BYTES, valuePath)
            val signed = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).long
            if (signed < 0L || signed > Int.MAX_VALUE.toLong()) {
                fail(valuePath, "0..${Int.MAX_VALUE}", "unsigned value outside runtime range")
            }
            return signed.toInt()
        }

        fun bytes(length: Int, valuePath: String): ByteArray {
            if (length < 0 || length > bytes.size - position) {
                fail(valuePath, "$length readable bytes", bytes.size - position)
            }
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun requireEnd() {
            if (position != bytes.size) {
                fail(path, "exact end of input", "${bytes.size - position} trailing bytes")
            }
        }
    }

    private data class ParsedRecord(val values: List<ByteArray>)

    private class RegistryDecodeFailure(
        val path: String,
        val expected: String,
        val actual: String,
    ) : IllegalArgumentException()

    private const val REGISTRY_DOMAIN = "native-close-fence-registry-v1"
    private const val ENTRY_DOMAIN = "native-close-fence-entry-v1"
    private val REGISTRY_DOMAIN_BYTES = REGISTRY_DOMAIN.toByteArray(StandardCharsets.UTF_8)
    private val REGISTRY_FIELDS = listOf("schema_revision", "entries")
    private val ENTRY_FIELDS = listOf(
        "proof_basis_sha256",
        "os_api",
        "abi",
        "installed_jni_set_sha256",
        "delegate_mask",
        "proof_bundle_sha256",
    )
    private const val MAX_REGISTRY_BYTES = 131_072
    private const val MAX_ENTRY_COUNT = 512
    private const val MIN_ENTRY_BYTES = 128
    private const val MAX_ENTRY_BYTES = 1_024
    private const val MIN_API = 26
    private const val MAX_API = 37
    private const val REQUIRED_DELEGATE_MASK = 0x03
}
