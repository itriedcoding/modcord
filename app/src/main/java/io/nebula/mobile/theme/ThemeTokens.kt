package io.nebula.mobile.theme

/**
 * Bridges a BetterDiscord theme onto the real Discord app.
 *
 * A patched Discord has no DOM, so a stylesheet cannot be "applied" as CSS. What
 * actually repaints Discord's native React Native views is the colour token objects
 * inside its own bundle, whose keys follow the same names as Discord's CSS custom
 * properties (`--background-primary` -> `backgroundPrimary`). This class resolves a
 * theme's variables with the project's real CSS parser and produces that token map,
 * which the injected loader then writes into Discord's modules.
 */
object ThemeTokens {

    /**
     * Extra token names Discord's React Native bundle is known to read for a variable.
     * The generic camelCase conversion always runs; these add the historical names.
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "--background-primary" to listOf("backgroundMobilePrimary"),
        "--background-secondary" to listOf("backgroundMobileSecondary"),
        "--brand-experiment" to listOf("brand"),
        "--brand-500" to listOf("brand", "brandExperiment"),
        "--text-normal" to listOf("textDefault"),
        "--header-primary" to listOf("textPrimary"),
        "--header-secondary" to listOf("textSecondary"),
        "--status-online" to listOf("statusGreen"),
        "--status-idle" to listOf("statusYellow"),
        "--status-dnd" to listOf("statusRed"),
        "--status-offline" to listOf("statusGrey"),
    )

    /** Parses a stylesheet and returns token name -> colour. */
    fun resolve(css: String): Map<String, String> {
        val rules = CssParser.parse(css)
        val variables = CssParser.resolveVariables(CssParser.customProperties(rules))
        return fromVariables(variables)
    }

    fun fromVariables(variables: Map<String, String>): Map<String, String> {
        val tokens = LinkedHashMap<String, String>()
        for ((name, raw) in variables) {
            if (!name.startsWith("--")) continue
            val color = toReactNativeColor(raw) ?: continue
            tokens[camelCase(name.removePrefix("--"))] = color
            ALIASES[name]?.forEach { alias -> tokens[alias] = color }
        }
        return tokens
    }

    /** `--background-primary` -> `backgroundPrimary`, as Discord names its RN tokens. */
    fun camelCase(name: String): String {
        val parts = name.split('-', '_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return name
        val out = StringBuilder(parts.first())
        for (index in 1 until parts.size) {
            val part = parts[index]
            out.append(part.replaceFirstChar { it.uppercase() })
        }
        return out.toString()
    }

    /** Converts a CSS colour to the format React Native accepts. */
    fun toReactNativeColor(raw: String): String? {
        val color = Colors.parse(raw) ?: return null
        val alpha = (color ushr 24) and 0xFF
        val red = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        return if (alpha == 255) {
            String.format("#%02X%02X%02X", red, green, blue)
        } else {
            String.format(java.util.Locale.US, "rgba(%d, %d, %d, %.3f)", red, green, blue, alpha / 255.0)
        }
    }
}
