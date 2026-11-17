package io.github.nir0k.jotter.core.highlight

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a run of code is drawn.
 *
 * @param color The colour as `0xRRGGBB`, or null for the text colour of the note.
 */
data class TokenStyle(val color: Int?, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean) {
    /** Tells whether the run looks like plain code. */
    val isPlain: Boolean get() = color == null && !bold && !italic && !underline && !strike
}

/**
 * A theme of KDE SyntaxHighlighting, read from its bundled file: Breeze Light, Breeze Dark or
 * Monokai, as the desktop shows code in them.
 */
class CodeTheme private constructor(val name: String, private val json: JsonObject) {
    private val textStyles = json["text-styles"]?.jsonObject ?: JsonObject(emptyMap())
    private val customStyles = json["custom-styles"]?.jsonObject ?: JsonObject(emptyMap())

    /** The colour of plain code, which is drawn in the note's own text colour instead. */
    private val normalColor: Int? = color(textStyles["Normal"]?.jsonObject, "text-color")

    private val styles = HashMap<ItemData, TokenStyle>()

    /** Returns how an attribute of a definition is drawn: the theme's style for the language's own attribute, its colours, then the default style's. */
    fun styleOf(item: ItemData): TokenStyle = synchronized(styles) {
        styles.getOrPut(item) {
            val base = textStyles[item.style.name]?.jsonObject
            val custom = customStyles[item.definition]?.jsonObject?.get(item.name)?.jsonObject
            fun flag(key: String, own: Boolean?) = custom?.get(key)?.jsonPrimitive?.booleanOrNull ?: own ?: base?.get(key)?.jsonPrimitive?.booleanOrNull ?: false
            val color = color(custom, "text-color") ?: item.color ?: color(base, "text-color")
            TokenStyle(
                color = color?.takeIf { it != normalColor },
                bold = flag("bold", item.bold),
                italic = flag("italic", item.italic),
                underline = flag("underline", item.underline),
                strike = flag("strike-through", item.strike),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is CodeTheme && other.name == name
    override fun hashCode(): Int = name.hashCode()

    companion object {
        private val themes = HashMap<String, CodeTheme>()

        /**
         * Returns a bundled theme.
         *
         * @param name `breeze-light`, `breeze-dark` or `monokai`; another name gives Breeze Light.
         */
        fun named(name: String): CodeTheme = synchronized(themes) {
            themes.getOrPut(name) {
                val file = if (name in setOf("breeze-light", "breeze-dark", "monokai")) name else "breeze-light"
                val text = CodeTheme::class.java.getResourceAsStream("/syntax/themes/$file.theme")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
                CodeTheme(file, Json.parseToJsonElement(text).jsonObject)
            }
        }

        /** Reads a `#rrggbb` colour of a style. */
        private fun color(style: JsonObject?, key: String): Int? =
            style?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("#") && it.length == 7 }?.substring(1)?.toIntOrNull(16)
    }
}
