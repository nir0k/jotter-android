package io.github.nir0k.jotter.core.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks the Qt-compatible JSON writer on cases the fixtures do not hold. Every expected text was
 * produced by Qt 6's `QJsonDocument` on the desktop.
 */
class JsonTest {
    private fun written(value: JsonObject) = String(toJsonText(value), Charsets.UTF_8)

    /** Numbers take Qt's shortest form: integers up to 2^53, then the shorter of decimal and exponent. */
    @Test
    fun numbers() {
        val cases = mapOf(
            "0.1" to "0.1", "-0.0" to "0", "100.0" to "100", "1e21" to "1e+21", "1e-7" to "1e-07",
            "0.0001" to "1e-04", "0.00015" to "0.00015", "0.001" to "0.001", "123456789.123" to "123456789.123",
            "9007199254740993.0" to "9007199254740992", "1e15" to "1000000000000000", "1e16" to "1e+16",
            "9.1e15" to "9.1e+15", "12345678901234567.0" to "12345678901234568", "1.2345678901234e16" to "12345678901234000",
            "1152921504606846976.0" to "1152921504606847000", "5e-324" to "5e-324",
            "1.7976931348623157e308" to "1.7976931348623157e+308", "0.30000000000000004" to "0.30000000000000004",
            "123e-20" to "1.23e-18", "-1e-5" to "-1e-05", "9223372036854775807" to "9223372036854775807",
            "12345678901234567890" to "12345678901234567000", "9223372036854775808" to "9223372036854776000",
            "1." to "1", ".5" to "0.5", "-.5" to "-0.5", "0.1e1" to "1", "-0" to "0",
        )
        for ((literal, expected) in cases) assertEquals(literal, expected, formatNumber(literal))
        assertEquals("null", formatNumber(Double.NaN.toString()))
    }

    /** Strings escape quotes, backslashes, control characters and unpaired surrogates only. */
    @Test
    fun strings() {
        val controls = (0 until 0x20).map { it.toChar() }.joinToString("")
        val value = JsonObject(
            mapOf(
                "ctl" to JsonPrimitive(controls),
                "misc" to JsonPrimitive("q\" b\\ s/ del\u007f nbsp\u00a0 emoji\uD83D\uDE00 ё"),
                "lone" to JsonPrimitive("\uD800"),
            ),
        )
        assertEquals(
            "{\n" +
                "  \"ctl\": \"\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000b\\f\\r" +
                "\\u000e\\u000f\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001a" +
                "\\u001b\\u001c\\u001d\\u001e\\u001f\",\n" +
                "  \"lone\": \"\\ud800\",\n" +
                "  \"misc\": \"q\\\" b\\\\ s/ del\u007f nbsp\u00a0 emoji\uD83D\uDE00 ё\"\n" +
                "}\n",
            written(value),
        )
    }

    /** Keys are sorted by UTF-16 code units; empty containers take two lines. */
    @Test
    fun layout() {
        val keys = listOf("b", "a", "B", "_", "Z", "é", "я", "ж", "\uD83D\uDE00", "\uFFFF", "aa", "a b")
        val value = JsonObject(
            mapOf(
                "keys" to JsonObject(keys.associateWith { JsonPrimitive(1) }),
                "nested" to JsonArray(listOf(JsonArray(emptyList()), JsonObject(mapOf("x" to JsonArray(listOf(JsonPrimitive(1))))))),
                "empty" to JsonObject(emptyMap()),
            ),
        )
        val sorted = listOf("B", "Z", "_", "a", "a b", "aa", "b", "é", "ж", "я", "\uD83D\uDE00", "\uFFFF")
        assertEquals(
            "{\n  \"empty\": {\n  },\n  \"keys\": {\n" +
                sorted.joinToString(",\n", postfix = "\n") { "    \"$it\": 1" } +
                "  },\n  \"nested\": [\n    [\n    ],\n    {\n      \"x\": [\n        1\n      ]\n    }\n  ]\n}\n",
            written(value),
        )
    }

    /** Literals Qt refuses make a file malformed, while Qt's lenient forms read. */
    @Test
    fun literals() {
        for (text in listOf("{\"a\":01}", "{\"a\":+1}", "{\"a\":1e}", "{\"a\":NaN}", "{\"a\":1E400}", "{\"a\":1,}", "[1]")) {
            val error = assertThrows(text, FormatException::class.java) { parseJsonObject(text.toByteArray(), "f") }
            assertEquals(ErrorCode.Malformed, error.code)
        }
        assertEquals("{\n  \"a\": 1\n}\n", written(parseJsonObject("\uFEFF{\"a\":1.0}".toByteArray(), "f")))
    }
}
