package io.nebula.mobile.theme

/**
 * Builds a real BetterDiscord theme file from three colours.
 *
 * The output is ordinary `.theme.css` text with a META header, so it goes through the same
 * [CssParser] and [ThemeTokens] path as an imported theme: no special-casing, and the
 * generated file stays editable after it is installed.
 */
object CustomTheme {

    private val HEX = Regex("^#?([0-9a-fA-F]{6})$")

    fun normalize(value: String, label: String): String {
        val match = HEX.matchEntire(value.trim())
            ?: throw IllegalArgumentException(
                "The $label colour must be a 6-digit hex value such as #A855F7 (got \"$value\").",
            )
        return "#" + match.groupValues[1].uppercase()
    }

    fun build(name: String, accent: String, background: String, text: String): String {
        val cleanName = name.trim().ifBlank { "My Theme" }
        val accentHex = normalize(accent, "accent")
        val backgroundHex = normalize(background, "background")
        val textHex = normalize(text, "text")
        val secondary = mix(backgroundHex, "#FFFFFF", 0.06)
        val tertiary = mix(backgroundHex, "#FFFFFF", 0.10)
        val muted = mix(textHex, backgroundHex, 0.45)
        return """
            /**
             * @name $cleanName
             * @author You
             * @description Created in Nebula's theme builder.
             * @version 1.0.0
             */

            :root {
                --background-primary: $backgroundHex;
                --background-secondary: $secondary;
                --background-tertiary: $tertiary;
                --background-floating: $tertiary;
                --brand-experiment: $accentHex;
                --brand-500: $accentHex;
                --text-normal: $textHex;
                --text-muted: $muted;
                --header-primary: $textHex;
                --header-secondary: $muted;
            }
        """.trimIndent() + "\n"
    }

    /** Linear interpolation between two `#RRGGBB` colours. */
    fun mix(from: String, to: String, weight: Double): String {
        val a = channels(from)
        val b = channels(to)
        val w = weight.coerceIn(0.0, 1.0)
        return hex(
            (a[0] * (1 - w) + b[0] * w).toInt(),
            (a[1] * (1 - w) + b[1] * w).toInt(),
            (a[2] * (1 - w) + b[2] * w).toInt(),
        )
    }

    fun hex(red: Int, green: Int, blue: Int): String =
        String.format("#%02X%02X%02X", red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))

    private fun channels(hex: String): IntArray {
        val value = hex.removePrefix("#")
        require(value.length == 6) { "Not a #RRGGBB colour: $hex" }
        return intArrayOf(
            value.substring(0, 2).toInt(16),
            value.substring(2, 4).toInt(16),
            value.substring(4, 6).toInt(16),
        )
    }
}
