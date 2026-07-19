package com.motionarcade.core.contract

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * Temporary, precision-preserving JSON-object boundary for snapshot v1.
 *
 * This is deliberately not a JSON parser and is not a release persistence contract.
 * It provides deterministic UTF-8 bytes without converting integers through Double.
 */
class TemporaryCanonicalJsonObject private constructor(
    internal val root: TemporaryJsonValue.ObjectValue,
) {
    fun canonicalUtf8(): ByteArray =
        TemporaryJsonWriter.write(root).toByteArray(StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is TemporaryCanonicalJsonObject && root == other.root

    override fun hashCode(): Int = root.hashCode()

    override fun toString(): String = TemporaryJsonWriter.write(root)

    companion object {
        const val FORMAT_ID: String = "TEMPORARY_CANONICAL_JSON_V1"

        fun fromMap(
            value: Map<*, *>,
            path: String = "$",
        ): ContractResult<TemporaryCanonicalJsonObject> {
            val stack = IdentityHashMap<Any, Unit>()
            return when (val converted = TemporaryJsonConverter.convertObject(value, path, stack)) {
                is ContractResult.Valid -> ContractResult.Valid(TemporaryCanonicalJsonObject(converted.value))
                is ContractResult.Invalid -> converted
            }
        }
    }
}

internal sealed interface TemporaryJsonValue {
    data object NullValue : TemporaryJsonValue

    data class BooleanValue(val value: Boolean) : TemporaryJsonValue

    data class StringValue(val value: String) : TemporaryJsonValue

    data class IntegerValue(val value: BigInteger) : TemporaryJsonValue

    data class DecimalValue(val value: BigDecimal) : TemporaryJsonValue

    data class ArrayValue(val values: List<TemporaryJsonValue>) : TemporaryJsonValue

    data class ObjectValue(val fields: Map<String, TemporaryJsonValue>) : TemporaryJsonValue
}

private object TemporaryJsonConverter {
    fun convertObject(
        value: Map<*, *>,
        path: String,
        stack: IdentityHashMap<Any, Unit>,
    ): ContractResult<TemporaryJsonValue.ObjectValue> {
        if (stack.put(value, Unit) != null) {
            return invalid(ContractViolation.InvalidFormat(path, "acyclic JSON object", "cycle"))
        }
        val converted = TreeMap<String, TemporaryJsonValue>()
        try {
            value.forEach { (rawKey, rawValue) ->
                if (rawKey !is String) {
                    return invalid(
                        ContractViolation.TypeMismatch(
                            path = path,
                            expected = "string property name",
                            actual = rawKey?.javaClass?.simpleName ?: "null",
                        ),
                    )
                }
                when (val item = convert(rawValue, "$path/${escapePointer(rawKey)}", stack)) {
                    is ContractResult.Valid -> converted[rawKey] = item.value
                    is ContractResult.Invalid -> return item
                }
            }
        } finally {
            stack.remove(value)
        }
        return ContractResult.Valid(
            TemporaryJsonValue.ObjectValue(Collections.unmodifiableMap(converted)),
        )
    }

    private fun convert(
        value: Any?,
        path: String,
        stack: IdentityHashMap<Any, Unit>,
    ): ContractResult<TemporaryJsonValue> = when (value) {
        null -> ContractResult.Valid(TemporaryJsonValue.NullValue)
        is Boolean -> ContractResult.Valid(TemporaryJsonValue.BooleanValue(value))
        is String -> ContractResult.Valid(TemporaryJsonValue.StringValue(value))
        is Byte -> ContractResult.Valid(TemporaryJsonValue.IntegerValue(BigInteger.valueOf(value.toLong())))
        is Short -> ContractResult.Valid(TemporaryJsonValue.IntegerValue(BigInteger.valueOf(value.toLong())))
        is Int -> ContractResult.Valid(TemporaryJsonValue.IntegerValue(BigInteger.valueOf(value.toLong())))
        is Long -> ContractResult.Valid(TemporaryJsonValue.IntegerValue(BigInteger.valueOf(value)))
        is BigInteger -> ContractResult.Valid(TemporaryJsonValue.IntegerValue(value))
        is BigDecimal -> ContractResult.Valid(TemporaryJsonValue.DecimalValue(normalizeDecimal(value)))
        is Float -> floating(value.toDouble(), path)
        is Double -> floating(value, path)
        is List<*> -> convertArray(value, path, stack)
        is Map<*, *> -> convertObject(value, path, stack)
        else -> invalid(
            ContractViolation.TypeMismatch(
                path = path,
                expected = "JSON null, boolean, string, number, array, or object",
                actual = value.javaClass.simpleName,
            ),
        )
    }

    private fun floating(value: Double, path: String): ContractResult<TemporaryJsonValue> =
        if (!value.isFinite()) {
            invalid(ContractViolation.OutOfRange(path, "finite JSON number", value.toString()))
        } else {
            ContractResult.Valid(TemporaryJsonValue.DecimalValue(normalizeDecimal(BigDecimal.valueOf(value))))
        }

    private fun convertArray(
        value: List<*>,
        path: String,
        stack: IdentityHashMap<Any, Unit>,
    ): ContractResult<TemporaryJsonValue.ArrayValue> {
        if (stack.put(value, Unit) != null) {
            return invalid(ContractViolation.InvalidFormat(path, "acyclic JSON array", "cycle"))
        }
        val converted = ArrayList<TemporaryJsonValue>(value.size)
        try {
            value.forEachIndexed { index, item ->
                when (val result = convert(item, "$path/$index", stack)) {
                    is ContractResult.Valid -> converted += result.value
                    is ContractResult.Invalid -> return result
                }
            }
        } finally {
            stack.remove(value)
        }
        return ContractResult.Valid(
            TemporaryJsonValue.ArrayValue(Collections.unmodifiableList(converted)),
        )
    }

    private fun normalizeDecimal(value: BigDecimal): BigDecimal =
        if (value.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else value.stripTrailingZeros()

    private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")
}

private object TemporaryJsonWriter {
    fun write(value: TemporaryJsonValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: TemporaryJsonValue) {
        when (value) {
            TemporaryJsonValue.NullValue -> append("null")
            is TemporaryJsonValue.BooleanValue -> append(value.value)
            is TemporaryJsonValue.StringValue -> appendString(value.value)
            is TemporaryJsonValue.IntegerValue -> append(value.value.toString())
            is TemporaryJsonValue.DecimalValue -> append(value.value.toPlainString())
            is TemporaryJsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is TemporaryJsonValue.ObjectValue -> {
                append('{')
                value.fields.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendString(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20 || character.isSurrogate()) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
