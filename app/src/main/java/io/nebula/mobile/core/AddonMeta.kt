package io.nebula.mobile.core

/**
 * Addon metadata as declared in a BetterDiscord style META header.
 *
 * BetterDiscord addons start with a JSDoc comment:
 *
 * ```
 * /**
 *  * @name ExampleAddon
 *  * @author YourName
 *  * @description Does a thing.
 *  * @version 1.0.0
 *  *\/
 * ```
 *
 * The legacy `//META{...}*//` JSON header used by very old addons is also supported.
 */
data class AddonMeta(
    val name: String,
    val author: String = "Unknown",
    val description: String = "",
    val version: String = "0.0.0",
    val website: String? = null,
    val source: String? = null,
    val donate: String? = null,
    val patreon: String? = null,
) {
    val displayAuthor: String
        get() = author.takeIf { it.isNotBlank() } ?: "Unknown"
}

object MetaParser {

    private val legacyRegex = Regex("""//\s*META\s*(\{.*?\})\s*\*/""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Extracts the META header from an addon source file.
     * Returns null when no usable header (i.e. no `@name`) is present.
     */
    fun parse(source: String): AddonMeta? {
        val jsdoc = leadingJsdoc(source)
        if (jsdoc != null) {
            parseJsdoc(jsdoc)?.let { return it }
        }
        return parseLegacy(source)
    }

    fun isPluginSource(source: String): Boolean {
        val body = stripMeta(source)
        return body.contains("module.exports") ||
            Regex("""function\s+start\s*\(""").containsMatchIn(body) ||
            Regex("""\bstart\s*[:=]\s*function""").containsMatchIn(body) ||
            Regex("""\bstart\s*\(\)\s*\{""").containsMatchIn(body)
    }

    fun isThemeSource(source: String): Boolean {
        val body = stripMeta(source)
        if (body.contains("module.exports")) return false
        return body.contains("{") && (body.contains("--") || body.contains(":"))
    }

    /** Removes the leading META header so the remaining body can be inspected. */
    fun stripMeta(source: String): String {
        val start = source.indexOf("/**")
        if (start >= 0 && source.substring(0, start).isBlank()) {
            val end = source.indexOf("*/", start + 3)
            if (end > 0) return source.substring(end + 2)
        }
        return source.replace(legacyRegex, "")
    }

    private fun parseJsdoc(block: List<String>): AddonMeta? {
        val fields = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        for (raw in block) {
            val line = raw.trim().trimStart('*').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("@")) {
                val separator = line.indexOfFirst { it == ' ' || it == '\t' }
                val key: String
                val value: String
                if (separator < 0) {
                    key = line.substring(1)
                    value = ""
                } else {
                    key = line.substring(1, separator)
                    value = line.substring(separator + 1).trim()
                }
                current = key.lowercase()
                fields[current] = StringBuilder(value)
            } else if (current != null) {
                val builder = fields[current]!!
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(line)
            }
        }

        val name = fields["name"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return null

        return AddonMeta(
            name = name,
            author = fields["author"]?.toString()?.trim().orEmpty().ifBlank { "Unknown" },
            description = fields["description"]?.toString()?.trim().orEmpty(),
            version = fields["version"]?.toString()?.trim().orEmpty().ifBlank { "0.0.0" },
            website = fields["website"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            source = fields["source"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            donate = fields["donate"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            patreon = fields["patreon"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseLegacy(source: String): AddonMeta? {
        val match = legacyRegex.find(source) ?: return null
        return try {
            val json = org.json.JSONObject(match.groupValues[1])
            val name = json.optString("name").trim()
            if (name.isEmpty()) return null
            AddonMeta(
                name = name,
                author = json.optString("author").ifBlank { "Unknown" },
                description = json.optString("description"),
                version = json.optString("version").ifBlank { "0.0.0" },
                website = json.optString("website").takeIf { it.isNotBlank() },
                source = json.optString("source").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the lines of the first JSDoc block, if it starts the file. */
    private fun leadingJsdoc(source: String): List<String>? {
        val start = source.indexOf("/**")
        if (start < 0) return null
        if (source.substring(0, start).isNotBlank()) return null
        val end = source.indexOf("*/", start + 3)
        if (end < 0) return null
        return source.substring(start + 3, end).lines()
    }
}
