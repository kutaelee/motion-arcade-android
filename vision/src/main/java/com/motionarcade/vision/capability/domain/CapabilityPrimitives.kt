package com.motionarcade.vision.capability.domain

import java.security.MessageDigest
import java.util.Collections

sealed interface CapabilityDomainViolation {
    val path: String

    data class InvalidValue(
        override val path: String,
        val expected: String,
        val actual: String,
    ) : CapabilityDomainViolation

    data class DuplicateValue(
        override val path: String,
        val actual: String,
    ) : CapabilityDomainViolation

    data class ArithmeticOverflow(
        override val path: String,
        val operation: String,
    ) : CapabilityDomainViolation
}

sealed interface CapabilityDomainResult<out T> {
    data class Valid<T>(val value: T) : CapabilityDomainResult<T>

    class Invalid(violations: List<CapabilityDomainViolation>) : CapabilityDomainResult<Nothing> {
        val violations: List<CapabilityDomainViolation> =
            Collections.unmodifiableList(ArrayList(violations))

        init {
            require(this.violations.isNotEmpty()) { "Invalid result requires at least one violation" }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is Invalid && violations == other.violations)

        override fun hashCode(): Int = violations.hashCode()

        override fun toString(): String = "Invalid(violations=$violations)"
    }
}

internal inline fun <T, R> CapabilityDomainResult<T>.map(
    transform: (T) -> R,
): CapabilityDomainResult<R> =
    when (this) {
        is CapabilityDomainResult.Valid -> CapabilityDomainResult.Valid(transform(value))
        is CapabilityDomainResult.Invalid -> this
    }

internal inline fun <T, R> CapabilityDomainResult<T>.flatMap(
    transform: (T) -> CapabilityDomainResult<R>,
): CapabilityDomainResult<R> =
    when (this) {
        is CapabilityDomainResult.Valid -> transform(value)
        is CapabilityDomainResult.Invalid -> this
    }

/** Immutable raw SHA-256 value with defensive construction and extraction. */
class Sha256Digest private constructor(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()

    fun toLowerHex(): String =
        buildString(BYTE_COUNT * 2) {
            value.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Sha256Digest && MessageDigest.isEqual(value, other.value))

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = toLowerHex()

    companion object {
        const val BYTE_COUNT: Int = 32

        fun fromBytes(bytes: ByteArray): CapabilityDomainResult<Sha256Digest> =
            if (bytes.size == BYTE_COUNT) {
                CapabilityDomainResult.Valid(Sha256Digest(bytes))
            } else {
                CapabilityDomainResult.Invalid(
                    listOf(
                        CapabilityDomainViolation.InvalidValue(
                            path = "$/sha256",
                            expected = "exactly 32 bytes",
                            actual = "${bytes.size} bytes",
                        ),
                    ),
                )
            }

        fun fromLowerHex(value: String): CapabilityDomainResult<Sha256Digest> {
            if (!LOWER_HEX.matches(value)) {
                return CapabilityDomainResult.Invalid(
                    listOf(
                        CapabilityDomainViolation.InvalidValue(
                            path = "$/sha256",
                            expected = "64 lowercase hexadecimal characters",
                            actual = value,
                        ),
                    ),
                )
            }
            val bytes = ByteArray(BYTE_COUNT) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
            }
            return CapabilityDomainResult.Valid(Sha256Digest(bytes))
        }

        internal fun trusted(bytes: ByteArray): Sha256Digest {
            check(bytes.size == BYTE_COUNT)
            return Sha256Digest(bytes)
        }

        private val LOWER_HEX = Regex("^[0-9a-f]{64}$")
        private const val HEX = "0123456789abcdef"
    }
}

@JvmInline
value class RuntimeArtifactId(val digest: Sha256Digest)

@JvmInline
value class WorkloadBuildManifestSha256(val digest: Sha256Digest)

@JvmInline
value class RuntimeBuildId(val digest: Sha256Digest)

@JvmInline
value class ProbeBaseScopeId(val digest: Sha256Digest)

@JvmInline
value class CapabilityResultId(val digest: Sha256Digest)

enum class ProbeDelegate(val wireValue: Int) {
    NONE(0),
    CPU(1),
    GPU(2),
}

enum class ProbeRunningMode(val wireValue: Int) {
    LIVE_STREAM(0),
}

enum class RequiredDigestProvenance {
    OS_BUILD,
    CAMERA2_ID,
}

private const val REQUIRED_OS_BUILD_DOMAIN = "os-build-v1"
private const val REQUIRED_CAMERA2_ID_DOMAIN = "camera2-id-v1"
private const val REQUIRED_BUILD_UNKNOWN_VALUE = "unknown"

private fun isCanonicalRequiredDigestAbsence(
    provenance: RequiredDigestProvenance,
    value: String?,
): Boolean =
    when (provenance) {
        RequiredDigestProvenance.OS_BUILD ->
            value.isNullOrEmpty() || value == REQUIRED_BUILD_UNKNOWN_VALUE
        RequiredDigestProvenance.CAMERA2_ID -> value.isNullOrEmpty()
    }

private fun canonicalPresentRequiredDigest(
    provenance: RequiredDigestProvenance,
    value: String,
): CapabilityDomainResult<Sha256Digest> {
    val domain: String
    val path: String
    when (provenance) {
        RequiredDigestProvenance.OS_BUILD -> {
            domain = REQUIRED_OS_BUILD_DOMAIN
            path = "$/osBuildFingerprint"
            if (value.isEmpty() || value == REQUIRED_BUILD_UNKNOWN_VALUE) {
                return invalid(path, "non-ABSENT OS build source", "domain-specific ABSENT sentinel")
            }
        }
        RequiredDigestProvenance.CAMERA2_ID -> {
            domain = REQUIRED_CAMERA2_ID_DOMAIN
            path = "$/publicCamera2Id"
            if (value.isEmpty()) {
                return invalid(path, "non-ABSENT Camera2 ID source", "domain-specific ABSENT sentinel")
            }
        }
    }
    val valueBytes = when (val result = CanonicalManifestCodec.strictUtf8(value, path)) {
        is CapabilityDomainResult.Valid -> result.value
        is CapabilityDomainResult.Invalid -> return result
    }
    return CanonicalManifestCodec.encode(
        domain = domain,
        fields = listOf(CanonicalField("value", valueBytes)),
    ).map(CanonicalManifestCodec::sha256)
}

private fun requireCanonicalPresentRequiredDigest(
    provenance: RequiredDigestProvenance,
    value: String,
): Sha256Digest =
    when (val result = canonicalPresentRequiredDigest(provenance, value)) {
        is CapabilityDomainResult.Valid -> result.value
        is CapabilityDomainResult.Invalid ->
            throw IllegalArgumentException("Required digest source is not canonical PRESENT input")
    }

/**
 * Required tagged digest whose PRESENT construction is restricted to the canonical
 * domain-specific builders. The raw source string is never retained by this value.
 */
sealed interface RequiredDigestToken {
    val provenance: RequiredDigestProvenance
    val digestOrNull: Sha256Digest?

    companion object {
        internal fun osBuild(fingerprint: String?): CapabilityDomainResult<RequiredDigestToken> =
            if (isCanonicalRequiredDigestAbsence(RequiredDigestProvenance.OS_BUILD, fingerprint)) {
                CapabilityDomainResult.Valid(AbsentRequiredDigestToken.osBuild(fingerprint))
            } else {
                PresentRequiredDigestToken.osBuild(requireNotNull(fingerprint))
            }

        internal fun camera2Id(publicCameraId: String?): CapabilityDomainResult<RequiredDigestToken> =
            if (isCanonicalRequiredDigestAbsence(RequiredDigestProvenance.CAMERA2_ID, publicCameraId)) {
                CapabilityDomainResult.Valid(AbsentRequiredDigestToken.camera2Id(publicCameraId))
            } else {
                PresentRequiredDigestToken.camera2Id(requireNotNull(publicCameraId))
            }
    }

    private class AbsentRequiredDigestToken private constructor(
        override val provenance: RequiredDigestProvenance,
        value: String?,
    ) : RequiredDigestToken {
        init {
            require(isCanonicalRequiredDigestAbsence(provenance, value))
        }

        override val digestOrNull: Sha256Digest? = null

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is RequiredDigestToken &&
                    other.digestOrNull == null &&
                    provenance == other.provenance)

        override fun hashCode(): Int = provenance.hashCode()

        override fun toString(): String = "Absent(provenance=$provenance)"

        companion object {
            internal fun osBuild(value: String?): RequiredDigestToken =
                AbsentRequiredDigestToken(
                    provenance = RequiredDigestProvenance.OS_BUILD,
                    value = value,
                )

            internal fun camera2Id(value: String?): RequiredDigestToken =
                AbsentRequiredDigestToken(
                    provenance = RequiredDigestProvenance.CAMERA2_ID,
                    value = value,
                )
        }
    }

    private class PresentRequiredDigestToken private constructor(
        override val provenance: RequiredDigestProvenance,
        value: String,
    ) : RequiredDigestToken {
        override val digestOrNull: Sha256Digest =
            requireCanonicalPresentRequiredDigest(provenance, value)

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is RequiredDigestToken &&
                    provenance == other.provenance &&
                    digestOrNull == other.digestOrNull)

        override fun hashCode(): Int = 31 * provenance.hashCode() + digestOrNull.hashCode()

        override fun toString(): String = "Present(provenance=$provenance)"

        companion object {
            internal fun osBuild(value: String): CapabilityDomainResult<RequiredDigestToken> =
                canonical(
                    value = value,
                    provenance = RequiredDigestProvenance.OS_BUILD,
                )

            internal fun camera2Id(value: String): CapabilityDomainResult<RequiredDigestToken> =
                canonical(
                    value = value,
                    provenance = RequiredDigestProvenance.CAMERA2_ID,
                )

            private fun canonical(
                value: String,
                provenance: RequiredDigestProvenance,
            ): CapabilityDomainResult<RequiredDigestToken> {
                return when (val result = canonicalPresentRequiredDigest(provenance, value)) {
                    is CapabilityDomainResult.Valid ->
                        CapabilityDomainResult.Valid(
                            PresentRequiredDigestToken(
                                provenance = provenance,
                                value = value,
                            ),
                        )
                    is CapabilityDomainResult.Invalid -> result
                }
            }
        }
    }
}
