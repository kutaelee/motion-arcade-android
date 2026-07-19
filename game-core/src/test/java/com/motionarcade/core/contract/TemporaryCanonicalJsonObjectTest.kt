package com.motionarcade.core.contract

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryCanonicalJsonObjectTest {
    @Test
    fun writerSortsKeysRecursivelyAndPreservesExactIntegers() {
        val input = linkedMapOf<String, Any?>(
            "z" to listOf(BigInteger("9007199254740993"), null),
            "a" to linkedMapOf("b" to BigDecimal("1.2300"), "a" to "text"),
        )

        val value = (TemporaryCanonicalJsonObject.fromMap(input) as ContractResult.Valid).value

        assertEquals(
            "{\"a\":{\"a\":\"text\",\"b\":1.23},\"z\":[9007199254740993,null]}",
            value.canonicalUtf8().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun nonFiniteNumberIsRejectedWithPointerPath() {
        val result = TemporaryCanonicalJsonObject.fromMap(mapOf("nested" to mapOf("x" to Double.NaN)))

        assertTrue(result is ContractResult.Invalid)
        val violation = (result as ContractResult.Invalid).violations.single()
        assertTrue(violation is ContractViolation.OutOfRange)
        assertEquals("$/nested/x", violation.path)
    }

    @Test
    fun cyclicHostObjectIsRejectedInsteadOfRecursing() {
        val cyclic = mutableMapOf<String, Any?>()
        cyclic["self"] = cyclic

        val result = TemporaryCanonicalJsonObject.fromMap(cyclic)

        assertTrue(result is ContractResult.Invalid)
        assertTrue((result as ContractResult.Invalid).violations.single() is ContractViolation.InvalidFormat)
    }

    @Test
    fun unpairedSurrogateIsEscapedWithoutUtf8Replacement() {
        val value = (
            TemporaryCanonicalJsonObject.fromMap(mapOf("text" to "\uD800"))
                as ContractResult.Valid
            ).value

        assertEquals("{\"text\":\"\\ud800\"}", value.canonicalUtf8().toString(Charsets.UTF_8))
    }
}
