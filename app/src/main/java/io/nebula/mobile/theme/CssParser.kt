package io.nebula.mobile.theme

/** A single CSS rule: one or more selectors plus their declarations. */
data class CssRule(
    val selectors: List<String>,
    val declarations: Map<String, String>,
)

/**
 * A small but real CSS parser, sufficient for BetterDiscord themes:
 * comments, quoted strings, `@media`/`@supports` blocks, custom properties
 * and `var()` resolution with fallbacks.
 */
object CssParser {

    fun parse(css: String): List<CssRule> {
        val rules = ArrayList<CssRule>()
        parseRange(stripComments(css), rules)
        return rules
    }

    /** All custom properties declared anywhere in the stylesheet, in source order. */
    fun customProperties(rules: List<CssRule>): Map<String, String> {
        val props = LinkedHashMap<String, String>()
        for (rule in rules) {
            for ((key, value) in rule.declarations) {
                if (key.startsWith("--")) props[key] = value.trim()
            }
        }
        return props
    }

    /**
     * Resolves `var(--name)` / `var(--name, fallback)` references repeatedly until
     * the values are stable (or the iteration budget is exhausted).
     */
    fun resolveVariables(props: Map<String, String>): Map<String, String> {
        val resolved = LinkedHashMap(props)
        repeat(24) {
            var changed = false
            for (key in resolved.keys.toList()) {
                val value = resolved[key] ?: continue
                val next = substitute(value, resolved)
                if (next != value) {
                    resolved[key] = next
                    changed = true
                }
            }
            if (!changed) return resolved
        }
        return resolved
    }

    private fun substitute(input: String, vars: Map<String, String>): String {
        if (!input.contains("var(")) return input
        val out = StringBuilder()
        var index = 0
        while (index < input.length) {
            val start = input.indexOf("var(", index)
            if (start < 0) {
                out.append(input.substring(index))
                break
            }
            out.append(input, index, start)
            var depth = 1
            var cursor = start + 4
            while (cursor < input.length && depth > 0) {
                when (input[cursor]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0) break
                cursor++
            }
            if (cursor >= input.length) {
                out.append(input.substring(start))
                break
            }
            val inner = input.substring(start + 4, cursor)
            val comma = topLevelComma(inner)
            val name = (if (comma >= 0) inner.substring(0, comma) else inner).trim()
            val fallback = if (comma >= 0) inner.substring(comma + 1).trim() else null
            val value = vars[name]?.let { substitute(it, vars) }
                ?: fallback?.let { substitute(it, vars) }
                ?: ""
            out.append(value)
            index = cursor + 1
        }
        return out.toString()
    }

    private fun topLevelComma(value: String): Int {
        var depth = 0
        for (i in value.indices) {
            when (value[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun stripComments(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val c = css[i]
            if (c == '/' && i + 1 < css.length && css[i + 1] == '*') {
                val end = css.indexOf("*/", i + 2)
                if (end < 0) break
                i = end + 2
                continue
            }
            if (c == '"' || c == '\'') {
                val quote = c
                out.append(c)
                i++
                while (i < css.length) {
                    out.append(css[i])
                    if (css[i] == quote && css[i - 1] != '\\') {
                        i++
                        break
                    }
                    i++
                }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun parseRange(css: String, out: MutableList<CssRule>) {
        var index = 0
        var preludeStart = 0
        while (index < css.length) {
            if (css[index] != '{') {
                index++
                continue
            }
            val prelude = css.substring(preludeStart, index).trim()
            val end = findBlockEnd(css, index)
            if (end < 0) return
            val body = css.substring(index + 1, end)
            if (prelude.startsWith("@")) {
                val nested = prelude.startsWith("@media") ||
                    prelude.startsWith("@supports") ||
                    prelude.startsWith("@layer") ||
                    prelude.startsWith("@container")
                if (nested) parseRange(body, out)
            } else {
                val selectors = prelude.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (selectors.isNotEmpty()) {
                    out.add(CssRule(selectors, parseDeclarations(body)))
                }
            }
            index = end + 1
            preludeStart = index
        }
    }

    private fun findBlockEnd(css: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < css.length) {
            when (css[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"', '\'' -> {
                    val quote = css[i]
                    i++
                    while (i < css.length) {
                        if (css[i] == quote && css[i - 1] != '\\') break
                        i++
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun parseDeclarations(body: String): Map<String, String> {
        val declarations = LinkedHashMap<String, String>()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (i in body.indices) {
            val c = body[i]
            if (quote != null) {
                current.append(c)
                if (c == quote && (i == 0 || body[i - 1] != '\\')) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> {
                    quote = c
                    current.append(c)
                }
                '(', '[' -> {
                    depth++
                    current.append(c)
                }
                ')', ']' -> {
                    depth--
                    current.append(c)
                }
                ';' -> if (depth == 0) {
                    parts.add(current.toString())
                    current.setLength(0)
                } else {
                    current.append(c)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())

        for (part in parts) {
            val colon = topLevelColon(part)
            if (colon <= 0) continue
            val property = part.substring(0, colon).trim()
            val value = part.substring(colon + 1).trim()
            if (property.isEmpty() || value.isEmpty()) continue
            declarations[property] = value
        }
        return declarations
    }

    private fun topLevelColon(value: String): Int {
        var depth = 0
        var inQuote: Char? = null
        for (i in value.indices) {
            val c = value[i]
            if (inQuote != null) {
                if (c == inQuote && (i == 0 || value[i - 1] != '\\')) inQuote = null
                continue
            }
            when (c) {
                '"', '\'' -> inQuote = c
                '(' -> depth++
                ')' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }
}
