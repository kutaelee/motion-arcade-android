package com.motionarcade.core.contract

/** A structured, path-addressable contract failure. */
sealed interface ContractViolation {
    val path: String

    data class MissingProperty(
        override val path: String,
        val property: String,
    ) : ContractViolation

    data class UnknownProperty(
        override val path: String,
        val property: String,
    ) : ContractViolation

    data class TypeMismatch(
        override val path: String,
        val expected: String,
        val actual: String,
    ) : ContractViolation

    data class OutOfRange(
        override val path: String,
        val constraint: String,
        val actual: String,
    ) : ContractViolation

    data class InvalidFormat(
        override val path: String,
        val constraint: String,
        val actual: String,
    ) : ContractViolation

    data class UnknownEnumValue(
        override val path: String,
        val enumName: String,
        val actual: String,
    ) : ContractViolation

    data class DuplicateValue(
        override val path: String,
        val actual: String,
    ) : ContractViolation

    data class SensitiveDiagnosticField(
        override val path: String,
        val field: String,
    ) : ContractViolation
}

sealed interface ContractResult<out T> {
    data class Valid<T>(val value: T) : ContractResult<T>

    data class Invalid(val violations: List<ContractViolation>) : ContractResult<Nothing> {
        init {
            require(violations.isNotEmpty()) { "Invalid result requires at least one violation" }
        }
    }
}

internal fun <T> invalid(violation: ContractViolation): ContractResult<T> =
    ContractResult.Invalid(listOf(violation))
