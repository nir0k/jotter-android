package io.github.nir0k.jotter.editor

/**
 * A language a code block can be highlighted in.
 *
 * @param key What the note file stores: the lower-case name of the KDE SyntaxHighlighting
 *   definition the desktop highlights it with.
 * @param name The name shown; languages are known by their own names in every interface language.
 */
data class CodeLanguage(val key: String, val name: String)

/**
 * The languages the language picker offers: the desktop's list of commonly used languages, in
 * its order, each highlighted by the bundled KDE SyntaxHighlighting definition the desktop
 * uses.
 */
val CodeLanguages: List<CodeLanguage> = listOf(
    CodeLanguage("bash", "Bash"),
    CodeLanguage("ms-dos batch", "Batch"),
    CodeLanguage("c", "C"),
    CodeLanguage("c#", "C#"),
    CodeLanguage("c++", "C++"),
    CodeLanguage("clojure", "Clojure"),
    CodeLanguage("cmake", "CMake"),
    CodeLanguage("css", "CSS"),
    CodeLanguage("csv", "CSV"),
    CodeLanguage("dart", "Dart"),
    CodeLanguage("diff", "Diff"),
    CodeLanguage("dockerfile", "Dockerfile"),
    CodeLanguage("elixir", "Elixir"),
    CodeLanguage("erlang", "Erlang"),
    CodeLanguage("go", "Go"),
    CodeLanguage("graphql", "GraphQL"),
    CodeLanguage("haskell", "Haskell"),
    CodeLanguage("html", "HTML"),
    CodeLanguage("java", "Java"),
    CodeLanguage("javascript", "JavaScript"),
    CodeLanguage("javascript react (jsx)", "JSX"),
    CodeLanguage("json", "JSON"),
    CodeLanguage("julia", "Julia"),
    CodeLanguage("kotlin", "Kotlin"),
    CodeLanguage("lua", "Lua"),
    CodeLanguage("makefile", "Makefile"),
    CodeLanguage("markdown", "Markdown"),
    CodeLanguage("nginx configuration", "nginx"),
    CodeLanguage("objective-c", "Objective-C"),
    CodeLanguage("perl", "Perl"),
    CodeLanguage("php/php", "PHP"),
    CodeLanguage("powershell", "PowerShell"),
    CodeLanguage("protobuf", "Protobuf"),
    CodeLanguage("python", "Python"),
    CodeLanguage("qml", "QML"),
    CodeLanguage("r script", "R"),
    CodeLanguage("ruby", "Ruby"),
    CodeLanguage("rust", "Rust"),
    CodeLanguage("scala", "Scala"),
    CodeLanguage("sql", "SQL"),
    CodeLanguage("swift", "Swift"),
    CodeLanguage("systemd unit", "systemd unit"),
    CodeLanguage("toml", "TOML"),
    CodeLanguage("typescript", "TypeScript"),
    CodeLanguage("typescript react (tsx)", "TSX"),
    CodeLanguage("vue", "Vue"),
    CodeLanguage("xml", "XML"),
    CodeLanguage("yaml", "YAML"),
    CodeLanguage("zig", "Zig"),
    CodeLanguage("zsh", "Zsh"),
)

/**
 * The file extensions a language typed after ``` is also found by, as KDE SyntaxHighlighting's
 * definitions name them, for the offered languages.
 */
val CodeExtensions: Map<String, String> = mapOf(
    "sh" to "bash", "bat" to "ms-dos batch", "cmd" to "ms-dos batch", "h" to "c", "cs" to "c#",
    "cpp" to "c++", "cc" to "c++", "cxx" to "c++", "hpp" to "c++", "clj" to "clojure", "cljs" to "clojure",
    "patch" to "diff", "ex" to "elixir", "exs" to "elixir", "erl" to "erlang", "gql" to "graphql",
    "hs" to "haskell", "htm" to "html", "js" to "javascript", "mjs" to "javascript", "jsx" to "javascript react (jsx)",
    "jl" to "julia", "kt" to "kotlin", "kts" to "kotlin", "mk" to "makefile", "md" to "markdown",
    "m" to "objective-c", "pl" to "perl", "pm" to "perl", "ps1" to "powershell", "proto" to "protobuf",
    "py" to "python", "r" to "r script", "rb" to "ruby", "rs" to "rust", "sc" to "scala",
    "service" to "systemd unit", "ts" to "typescript", "tsx" to "typescript react (tsx)", "yml" to "yaml",
    "php" to "php/php", "dart" to "dart", "go" to "go", "lua" to "lua", "swift" to "swift", "zig" to "zig", "vue" to "vue",
)

/**
 * Returns the stored language of a language typed after ``` or named by a Markdown code fence: a
 * known key, the name a language is shown with, or a file extension; otherwise what was typed, in
 * lower case.
 */
fun typedLanguage(typed: String): String {
    val key = typed.lowercase()
    if (CodeLanguages.any { it.key == key }) return key
    return CodeLanguages.firstOrNull { it.name.lowercase() == key }?.key ?: CodeExtensions[key] ?: key
}
