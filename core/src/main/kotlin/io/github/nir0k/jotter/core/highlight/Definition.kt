package io.github.nir0k.jotter.core.highlight

import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

// Syntax definitions of KDE SyntaxHighlighting: the XML files the desktop's highlighter reads,
// bundled with the application and interpreted here the way its engine does, so code blocks are
// highlighted as on the desktop. Only the elements and attributes the bundled definitions use
// are read.

/** The default styles of KDE SyntaxHighlighting, in the order of their `defStyleNum` names. */
enum class DefaultStyle {
    Normal, Keyword, Function, Variable, ControlFlow, Operator, BuiltIn, Extension, Preprocessor, Attribute, Char,
    SpecialChar, String, VerbatimString, SpecialString, Import, DataType, DecVal, BaseN, Float, Constant, Comment,
    Documentation, Annotation, CommentVar, RegionMarker, Information, Warning, Alert, Others, Error,
}

/**
 * An attribute of a definition (`itemData`): a default style and what the definition sets on it.
 *
 * @param color The definition's own colour as `0xRRGGBB`, or null.
 */
class ItemData(
    val definition: String,
    val name: String,
    val style: DefaultStyle,
    val color: Int?,
    val bold: Boolean?,
    val italic: Boolean?,
    val underline: Boolean?,
    val strike: Boolean?,
)

/** A context switch: how many contexts to leave, then the context to enter, if any. */
class ContextSwitch(val pops: Int, val target: String?, val language: String?) {
    /** Tells whether the switch stays in the context. */
    val isStay: Boolean get() = pops == 0 && target == null && language == null

    companion object {
        /** Parses a switch: `#stay`, `#pop`, `#pop#pop!Context`, `Context`, `Context##Language` or `##Language`. */
        fun parse(text: String?): ContextSwitch {
            var rest = text?.trim().orEmpty()
            if (rest.isEmpty() || rest == "#stay") return ContextSwitch(0, null, null)
            var pops = 0
            while (rest.startsWith("#pop")) {
                pops++
                rest = rest.removePrefix("#pop")
            }
            rest = rest.removePrefix("!")
            if (rest.isEmpty()) return ContextSwitch(pops, null, null)
            val split = rest.indexOf("##")
            return if (split >= 0) ContextSwitch(pops, rest.substring(0, split).ifEmpty { null }, rest.substring(split + 2))
            else ContextSwitch(pops, rest, null)
        }
    }
}

/** A rule of a context. */
class Rule(
    val type: String,
    val attribute: String?,
    val context: ContextSwitch,
    val lookAhead: Boolean,
    val firstNonSpace: Boolean,
    val column: Int,
    val dynamic: Boolean,
    val insensitive: Boolean,
    val minimal: Boolean,
    val string: String,
    val char0: Char,
    val char1: Char,
    val weakDelimiters: String,
    val additionalDelimiters: String,
    /** The definition the rule belongs to, whose attributes, keyword lists and delimiters it uses. */
    val definition: Definition,
) {
    /** The compiled expression of a `RegExpr` rule that is not dynamic; null when it does not compile. */
    var pattern: java.util.regex.Pattern? = null

    /** Whether [pattern] has been compiled. */
    var compiled = false

    /** The delimiters of words for this rule. */
    val delimiters: String by lazy {
        var chars = definition.delimiters
        for (c in weakDelimiters) chars = chars.replace(c.toString(), "")
        chars + additionalDelimiters
    }
}

/**
 * A context of a definition.
 *
 * @param rules The rules in their order, included rules in place once resolved.
 */
class Context(
    val definition: Definition,
    val name: String,
    var attribute: String?,
    val lineEnd: ContextSwitch,
    val lineEmpty: ContextSwitch,
    val fallthrough: ContextSwitch?,
    val dynamic: Boolean,
    val rules: MutableList<Rule>,
    /** The `IncludeRules` of the context, with their positions among its rules, resolved when first used. */
    val includes: List<Triple<Int, String, Boolean>>,
) {
    /** Whether the included rules are in [rules] yet. */
    var resolved = false
}

/**
 * A syntax definition.
 *
 * @param name The language's name, such as `C++`.
 */
class Definition(val name: String) {
    /** The contexts by name, the first being the initial one. */
    val contexts = LinkedHashMap<String, Context>()

    /** The attributes by name. */
    val itemData = HashMap<String, ItemData>()

    /** The keyword lists by name, before includes are resolved. */
    val lists = HashMap<String, Pair<MutableSet<String>, List<String>>>()

    /** Whether keywords match case. */
    var caseSensitive = true

    /** The characters that end words. */
    var delimiters = DefaultDelimiters

    /** The context a line starts in when nothing else is open. */
    val initial: Context get() = contexts.values.first()

    companion object {
        /** The delimiters of words of KDE SyntaxHighlighting. */
        const val DefaultDelimiters = "\t !%&()*+,-./:;<=>?[\\]^{|}~"
    }
}

private val EntityDeclaration = Regex("<!ENTITY\\s+(\\S+)\\s+(?:\"([^\"]*)\"|'([^']*)')\\s*>")
private val Doctype = Regex("<!DOCTYPE[^\\[>]*(\\[.*?])?\\s*>", RegexOption.DOT_MATCHES_ALL)

/**
 * Reads a definition's XML. The entities its document type declares are replaced first, as the
 * XML parsers of Android and the JVM treat them differently; nothing outside the file is read.
 */
internal fun readDefinition(input: InputStream): Definition {
    var text = input.readBytes().toString(Charsets.UTF_8)
    val doctype = Doctype.find(text)
    if (doctype != null) {
        val entities = LinkedHashMap<String, String>()
        EntityDeclaration.findAll(doctype.value).forEach { entities[it.groupValues[1]] = it.groupValues[2] + it.groupValues[3] }
        // Entities may use other entities; two passes resolve the chains the definitions have.
        repeat(2) { for (key in entities.keys.toList()) {
            var value = entities.getValue(key)
            for ((other, replacement) in entities) if (other != key) value = value.replace("&$other;", replacement)
            entities[key] = value
        } }
        text = text.removeRange(doctype.range)
        // A declared value is written as it would stand in the document; only its quotes are escaped,
        // as it goes into attribute values of either quote.
        for ((key, value) in entities) text = text.replace("&$key;", value.replace("\"", "&quot;").replace("'", "&apos;"))
    }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
    val root = document.documentElement
    val definition = Definition(root.getAttribute("name"))
    val highlighting = root.child("highlighting") ?: return definition
    root.child("general")?.child("keywords")?.let { keywords ->
        val case = keywords.getAttribute("casesensitive")
        if (case.isNotEmpty()) definition.caseSensitive = case != "0" && !case.equals("false", true)
        var delimiters = Definition.DefaultDelimiters
        for (c in keywords.getAttribute("weakDeliminator")) delimiters = delimiters.replace(c.toString(), "")
        definition.delimiters = delimiters + keywords.getAttribute("additionalDeliminator")
    }
    for (list in highlighting.children("list")) {
        val items = HashSet<String>()
        val includes = ArrayList<String>()
        for (entry in list.children()) {
            val value = entry.textContent.trim()
            when (entry.tagName) {
                "item" -> items += value
                "include" -> includes += value
            }
        }
        definition.lists[list.getAttribute("name")] = items to includes
    }
    highlighting.child("itemDatas")?.children("itemData")?.forEach { item ->
        fun flag(name: String) = item.getAttribute(name).takeIf { it.isNotEmpty() }?.let { it == "1" || it.equals("true", true) }
        val style = item.getAttribute("defStyleNum").removePrefix("ds")
        definition.itemData[item.getAttribute("name")] = ItemData(
            definition.name,
            item.getAttribute("name"),
            DefaultStyle.entries.firstOrNull { it.name == style } ?: DefaultStyle.Normal,
            item.getAttribute("color").takeIf { it.startsWith("#") && it.length == 7 }?.substring(1)?.toIntOrNull(16),
            flag("bold"), flag("italic"), flag("underline"), flag("strikeOut"),
        )
    }
    highlighting.child("contexts")?.children("context")?.forEach { element ->
        val rules = ArrayList<Rule>()
        val includes = ArrayList<Triple<Int, String, Boolean>>()
        for (rule in element.children()) {
            if (rule.tagName == "IncludeRules") {
                includes += Triple(rules.size, rule.getAttribute("context"), rule.getAttribute("includeAttrib").let { it == "true" || it == "1" })
                continue
            }
            rules += readRule(rule, definition)
        }
        val fallthroughContext = element.getAttribute("fallthroughContext")
        val fallthrough = fallthroughContext.takeIf { it.isNotEmpty() && element.getAttribute("fallthrough") != "false" }
            ?.let(ContextSwitch::parse)?.takeIf { !it.isStay }
        val name = element.getAttribute("name")
        definition.contexts[name] = Context(
            definition, name, element.getAttribute("attribute").ifEmpty { null },
            ContextSwitch.parse(element.getAttribute("lineEndContext")),
            ContextSwitch.parse(element.getAttribute("lineEmptyContext")),
            fallthrough,
            element.getAttribute("dynamic").let { it == "true" || it == "1" },
            rules, includes,
        )
    }
    return definition
}

/** Reads one rule element. */
private fun readRule(element: Element, definition: Definition): Rule {
    fun flag(name: String) = element.getAttribute(name).let { it == "true" || it == "1" }
    val char0 = element.getAttribute("char").let { if (it.isEmpty()) '\u0000' else it[0] }
    val char1 = element.getAttribute("char1").let { if (it.isEmpty()) '\u0000' else it[0] }
    return Rule(
        type = element.tagName,
        attribute = element.getAttribute("attribute").ifEmpty { null },
        context = ContextSwitch.parse(element.getAttribute("context")),
        lookAhead = flag("lookAhead"),
        firstNonSpace = flag("firstNonSpace"),
        column = element.getAttribute("column").toIntOrNull() ?: -1,
        dynamic = flag("dynamic"),
        insensitive = flag("insensitive"),
        minimal = flag("minimal"),
        string = element.getAttribute("String"),
        char0 = if (element.tagName == "LineContinue" && char0 == '\u0000') '\\' else char0,
        char1 = char1,
        weakDelimiters = element.getAttribute("weakDeliminator"),
        additionalDelimiters = element.getAttribute("additionalDeliminator"),
        definition = definition,
    )
}

/** Returns the first child element with a name. */
private fun Element.child(name: String): Element? = children(name).firstOrNull()

/** Returns the child elements, all or those with a name. */
private fun Element.children(name: String? = null): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && (name == null || (node as Element).tagName == name)) out += node as Element
    }
    return out
}
