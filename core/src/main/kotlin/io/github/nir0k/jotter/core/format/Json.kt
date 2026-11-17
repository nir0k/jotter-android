package io.github.nir0k.jotter.core.format

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The desktop writes library files with Qt's JSON writer, and every client writes them back byte
// for byte, so this file reproduces Qt's reading and writing rules rather than generic JSON.

/** Number literals Qt accepts: no `+`, no leading zero, `.5` and `1.` allowed. */
private val NumberLiteral = Regex("-?((0|[1-9][0-9]*)(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?")

/** The largest integer a double holds exactly; Qt writes integral doubles up to it as integers. */
private const val MaxExactInteger = 9007199254740992.0

/**
 * Parses a library file whose top level is an object, as the desktop's `parseJsonObject` does.
 *
 * @param data The file's contents in UTF-8; a byte order mark is skipped.
 * @param path The file, for errors.
 * @return The object, with numbers kept as their literals.
 * @throws FormatException Malformed for invalid JSON or another top-level type.
 */
fun parseJsonObject(data: ByteArray, path: String): JsonObject {
    val text = String(data, Charsets.UTF_8).removePrefix("﻿")
    val element = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw FormatException(ErrorCode.Malformed, path, e.message.orEmpty(), e)
    } catch (e: IllegalArgumentException) {
        throw FormatException(ErrorCode.Malformed, path, e.message.orEmpty(), e)
    }
    if (element !is JsonObject) {
        throw FormatException(ErrorCode.Malformed, path, "the top level is not an object")
    }
    checkLiterals(element, path)
    return element
}

/**
 * Rejects unquoted literals that Qt would not read as a number, `true`, `false` or `null`,
 * including numbers too large for a double.
 */
private fun checkLiterals(element: JsonElement, path: String) {
    when (element) {
        is JsonObject -> element.values.forEach { checkLiterals(it, path) }
        is JsonArray -> element.forEach { checkLiterals(it, path) }
        is JsonPrimitive -> {
            val content = element.content
            val valid = element.isString || element is JsonNull || content == "true" ||
                content == "false" ||
                (NumberLiteral.matches(content) && content.toDouble().isFinite())
            if (!valid) throw FormatException(ErrorCode.Malformed, path, "invalid value $content")
        }
    }
}

/**
 * Writes an object as the desktop's `toJsonText` does: Qt's indented form with two spaces, keys
 * in UTF-16 order, empty arrays and objects on two lines and a final newline.
 *
 * @param value The object.
 * @return The file's contents in UTF-8.
 */
fun toJsonText(value: JsonObject): ByteArray {
    val out = StringBuilder()
    writeValue(out, value, 0)
    out.append('\n')
    return out.toString().toByteArray(Charsets.UTF_8)
}

/** Appends a value at a nesting level; containers close at the level's indentation. */
private fun writeValue(out: StringBuilder, value: JsonElement, level: Int) {
    when (value) {
        is JsonObject -> {
            out.append("{\n")
            val keys = value.keys.sorted()
            keys.forEachIndexed { i, key ->
                indent(out, level + 1)
                writeString(out, key)
                out.append(": ")
                writeValue(out, value.getValue(key), level + 1)
                out.append(if (i < keys.size - 1) ",\n" else "\n")
            }
            indent(out, level)
            out.append('}')
        }
        is JsonArray -> {
            out.append("[\n")
            value.forEachIndexed { i, item ->
                indent(out, level + 1)
                writeValue(out, item, level + 1)
                out.append(if (i < value.size - 1) ",\n" else "\n")
            }
            indent(out, level)
            out.append(']')
        }
        is JsonPrimitive -> when {
            value.isString -> writeString(out, value.content)
            value is JsonNull || value.content == "true" || value.content == "false" ->
                out.append(value.content)
            else -> out.append(formatNumber(value.content))
        }
    }
}

private fun indent(out: StringBuilder, level: Int) {
    repeat(level * 2) { out.append(' ') }
}

/**
 * Appends a quoted string as Qt escapes it: quotes, backslashes and control characters, and
 * unpaired surrogates as `\uXXXX`; everything else, `/` and DEL included, stays as it is.
 */
private fun writeString(out: StringBuilder, text: String) {
    out.append('"')
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> appendEscape(out, c)
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                out.append(c).append(text[i + 1])
                i++
            }
            c.isSurrogate() -> appendEscape(out, c)
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
}

/** Appends a character as `\uXXXX` with lower-case hexadecimal digits. */
private fun appendEscape(out: StringBuilder, c: Char) {
    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
}

/**
 * Writes a number literal as Qt writes the value it reads from it. An integer literal that fits
 * in 64 bits stays an integer; anything else is a double, written as an integer when it is
 * integral and exactly representable, otherwise with the fewest digits that read back to the same
 * double, in the shorter of the decimal and the exponent forms (the decimal one on a tie).
 *
 * @param literal A number literal, as read from a file or made from a Kotlin number.
 * @return The literal Qt writes; `null` for a value that is not finite.
 */
internal fun formatNumber(literal: String): String {
    if (literal.all { it.isDigit() || it == '-' }) literal.toLongOrNull()?.let { return it.toString() }
    val value = literal.toDouble()
    if (!value.isFinite()) return "null"
    if (value == Math.rint(value) && abs(value) <= MaxExactInteger) return value.toLong().toString()

    val exact = BigDecimal(value)
    val shortest = (1..17).asSequence()
        .map { exact.round(MathContext(it, RoundingMode.HALF_EVEN)) }
        .first { it.toDouble() == value }
        .stripTrailingZeros()
    val digits = shortest.unscaledValue().abs().toString()
    val exponent = digits.length - 1 - shortest.scale()
    val point = exponent + 1
    val decimal = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
    val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
    val scientific = mantissa + "e" + (if (exponent < 0) "-" else "+") +
        abs(exponent).toString().padStart(2, '0')
    val sign = if (value < 0) "-" else ""
    return sign + if (decimal.length <= scientific.length) decimal else scientific
}

/** Returns the string of a string value, or an empty string for any other value, as Qt's `toString()`. */
internal fun JsonElement?.qtString(default: String = ""): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

/** Tells whether a value is a string. */
internal val JsonElement?.isString: Boolean
    get() = (this as? JsonPrimitive)?.isString == true

/** Tells whether a value is a number, which Qt 6 reports as a double for integers too. */
internal val JsonElement?.isNumber: Boolean
    get() = this is JsonPrimitive && this !is JsonNull && !isString &&
        content != "true" && content != "false"

/** Tells whether a value is `true` or `false`. */
internal val JsonElement?.isBoolean: Boolean
    get() = this is JsonPrimitive && !isString && (content == "true" || content == "false")

/** Returns a number value, or no value for any other value. */
internal fun JsonElement?.qtDouble(): Double? = if (isNumber) (this as JsonPrimitive).content.toDouble() else null

/** Returns an integral number that fits in an `Int`, or [default], as Qt's `toInt()`. */
internal fun JsonElement?.qtInt(default: Int): Int {
    val number = qtDouble() ?: return default
    return if (number == Math.rint(number) && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
        number.toInt()
    } else {
        default
    }
}

/** Returns a boolean value, or [default] for any other value, as Qt's `toBool()`. */
internal fun JsonElement?.qtBoolean(default: Boolean): Boolean =
    if (isBoolean) (this as JsonPrimitive).content == "true" else default

/** Returns an array value, or an empty array, as Qt's `toArray()`. */
internal fun JsonElement?.qtArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

/** Returns an object value, or an empty object, as Qt's `toObject()`. */
internal fun JsonElement?.qtObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

/** Tells whether a value is `null`; a missing field is not. */
internal val JsonElement?.isNull: Boolean
    get() = this is JsonNull

/** Returns a string, or `null` for no value. */
internal fun jsonString(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

/**
 * Returns the fields of an object whose keys are not in [known], so that a file written by a newer
 * Jotter keeps fields this build does not understand when it is rewritten.
 */
internal fun unknownFields(value: JsonObject, known: Set<String>): JsonObject =
    JsonObject(value.filterKeys { it !in known })
