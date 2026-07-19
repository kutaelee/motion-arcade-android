package com.motionarcade.vision.capability.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCloseFenceRegistryCodecTest {
    @Test
    fun canonicalEmptyRegistryParsesButCarriesNoAuthorityRows() {
        val bytes = registry()

        val decoded = valid(NativeCloseFenceRegistryCodec.decode(bytes))

        assertTrue(decoded.entries.isEmpty())
        assertArrayEquals(bytes, decoded.copyCanonicalBytes())
        assertEquals(
            "49801addf54f19aa8b3027d83d3c0533add42138bfe2dfef5d75c34e9bd1e942",
            CanonicalManifestCodec.sha256(bytes).toLowerHex(),
        )
    }

    @Test
    fun canonicalRowsExposeExactBindingsAndDefensiveBytes() {
        val first = row(basis = 1, api = 26, abi = "arm64-v8a", jni = 2, bundle = 3)
        val second = row(basis = 1, api = 27, abi = "arm64-v8a", jni = 4, bundle = 5)
        val bytes = registry(first, second)

        val decoded = valid(NativeCloseFenceRegistryCodec.decode(bytes))

        assertEquals(2, decoded.entries.size)
        val entry = decoded.entries[0]
        assertEquals(26, entry.osApi)
        assertEquals(NativeCloseAbi.ARM64_V8A, entry.abi)
        assertEquals(0x03, entry.delegateMask)
        assertEquals(hex(1), entry.proofBasisSha256.toLowerHex())
        assertEquals(hex(2), entry.installedJniSetSha256.toLowerHex())
        assertEquals(hex(3), entry.proofBundleSha256.toLowerHex())

        val registryCopy = decoded.copyCanonicalBytes()
        val rowCopy = entry.copyCanonicalBytes()
        assertNotSame(registryCopy, decoded.copyCanonicalBytes())
        assertNotSame(rowCopy, entry.copyCanonicalBytes())
        registryCopy.fill(0)
        rowCopy.fill(0)
        bytes.fill(0)
        assertArrayEquals(registry(first, second), decoded.copyCanonicalBytes())
        assertArrayEquals(first, entry.copyCanonicalBytes())
    }

    @Test
    fun rejectsNoncanonicalOrMalformedRegistryEnvelope() {
        val canonical = registry()
        assertInvalid(canonical + 0)
        assertInvalid(ByteArray(131_073))
        assertInvalid(
            manifest(
                REGISTRY_DOMAIN,
                listOf(
                    "entries" to framedList(),
                    "schema_revision" to REGISTRY_DOMAIN.toByteArray(),
                ),
            ),
        )
        assertInvalid(canonical.copyOf().also { it[0] = 'N'.code.toByte() })
    }

    @Test
    fun rejectsRowHashApiAbiDelegateAndBundleViolations() {
        assertInvalid(registry(row(basis = 0, api = 26, abi = "arm64-v8a", jni = 2, bundle = 3)))
        assertInvalid(registry(row(basis = 1, api = 25, abi = "arm64-v8a", jni = 2, bundle = 3)))
        assertInvalid(registry(row(basis = 1, api = 38, abi = "arm64-v8a", jni = 2, bundle = 3)))
        assertInvalid(registry(row(basis = 1, api = 26, abi = "mips", jni = 2, bundle = 3)))
        assertInvalid(registry(row(basis = 1, api = 26, abi = "arm64-v8a", jni = 0, bundle = 3)))
        assertInvalid(
            registry(
                row(
                    basis = 1,
                    api = 26,
                    abi = "arm64-v8a",
                    jni = 2,
                    bundle = 3,
                    delegateMask = 1,
                ),
            ),
        )
        assertInvalid(registry(row(basis = 1, api = 26, abi = "arm64-v8a", jni = 2, bundle = 0)))
    }

    @Test
    fun rejectsDuplicateAndOutOfOrderRows() {
        val first = row(basis = 1, api = 26, abi = "arm64-v8a", jni = 2, bundle = 3)
        val second = row(basis = 1, api = 27, abi = "arm64-v8a", jni = 4, bundle = 5)

        assertInvalid(registry(first, first))
        assertInvalid(registry(second, first))
    }

    @Test
    fun abiOrderingUsesLengthPrefixedKeyRatherThanDisplayNameOrder() {
        val x86 = row(basis = 1, api = 26, abi = "x86_64", jni = 2, bundle = 3)
        val arm = row(basis = 1, api = 26, abi = "arm64-v8a", jni = 4, bundle = 5)

        val decoded = valid(NativeCloseFenceRegistryCodec.decode(registry(x86, arm)))

        assertEquals(listOf(NativeCloseAbi.X86_64, NativeCloseAbi.ARM64_V8A), decoded.entries.map { it.abi })
        assertInvalid(registry(arm, x86))
    }

    @Test
    fun rejectsTruncatedFramingAndNoncanonicalRowFieldOrder() {
        val validRow = row(basis = 1, api = 26, abi = "arm64-v8a", jni = 2, bundle = 3)
        assertInvalid(registryBytes(framedList(validRow).copyOf(framedList(validRow).size - 1)))
        val reordered = manifest(
            ENTRY_DOMAIN,
            listOf(
                "os_api" to u32(26),
                "proof_basis_sha256" to digest(1),
                "abi" to "arm64-v8a".toByteArray(),
                "installed_jni_set_sha256" to digest(2),
                "delegate_mask" to byteArrayOf(3),
                "proof_bundle_sha256" to digest(3),
            ),
        )
        assertInvalid(registry(reordered))
    }

    private fun row(
        basis: Int,
        api: Int,
        abi: String,
        jni: Int,
        bundle: Int,
        delegateMask: Int = 3,
    ): ByteArray =
        manifest(
            ENTRY_DOMAIN,
            listOf(
                "proof_basis_sha256" to digest(basis),
                "os_api" to u32(api),
                "abi" to abi.toByteArray(StandardCharsets.UTF_8),
                "installed_jni_set_sha256" to digest(jni),
                "delegate_mask" to byteArrayOf(delegateMask.toByte()),
                "proof_bundle_sha256" to digest(bundle),
            ),
        )

    private fun registry(vararg rows: ByteArray): ByteArray = registryBytes(framedList(*rows))

    private fun registryBytes(entries: ByteArray): ByteArray =
        manifest(
            REGISTRY_DOMAIN,
            listOf(
                "schema_revision" to REGISTRY_DOMAIN.toByteArray(StandardCharsets.UTF_8),
                "entries" to entries,
            ),
        )

    private fun framedList(vararg rows: ByteArray): ByteArray {
        val size = UInt.SIZE_BYTES + rows.sumOf { UInt.SIZE_BYTES + it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(rows.size)
            rows.forEach { row -> putInt(row.size).put(row) }
        }.array()
    }

    private fun manifest(domain: String, fields: List<Pair<String, ByteArray>>): ByteArray =
        valid(
            CanonicalManifestCodec.encode(
                domain,
                fields.map { (name, value) -> CanonicalField(name, value) },
            ),
        )

    private fun digest(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    private fun hex(seed: Int): String = "%02x".format(seed).repeat(32)

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(UInt.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun assertInvalid(bytes: ByteArray) {
        assertTrue(
            "expected invalid registry",
            NativeCloseFenceRegistryCodec.decode(bytes) is CapabilityDomainResult.Invalid,
        )
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error(result.toString())
        }

    private companion object {
        const val REGISTRY_DOMAIN = "native-close-fence-registry-v1"
        const val ENTRY_DOMAIN = "native-close-fence-entry-v1"
    }
}
