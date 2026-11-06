package io.nebula.mobile.theme

import io.nebula.mobile.core.AddonMeta
import io.nebula.mobile.core.InstalledAddon
import io.nebula.mobile.core.MetaParser

/** Parsed theme: META header plus the custom properties the stylesheet declares. */
data class ParsedTheme(
    val meta: AddonMeta?,
    val rules: List<CssRule>,
    val variables: Map<String, String>,
)

/**
 * Colour palette used to paint the Nebula UI. Derived from a theme's Discord
 * CSS custom properties, so any BetterDiscord theme that overrides those
 * variables produces a usable palette.
 */
data class Palette(
    val background: Int = 0xFF0F1117.toInt(),
    val surface: Int = 0xFF171A23.toInt(),
    val surfaceAlt: Int = 0xFF1F2430.toInt(),
    val accent: Int = 0xFF7C5CFC.toInt(),
    val text: Int = 0xFFEDEFF7.toInt(),
    val textMuted: Int = 0xFF9AA3B2.toInt(),
    val border: Int = 0xFF262B37.toInt(),
    val onAccent: Int = 0xFFFFFFFF.toInt(),
    val isDark: Boolean = true,
    val variables: Map<String, String> = emptyMap(),
) {
    companion object {
        val DEFAULT = Palette()

        private val BACKGROUND_KEYS = listOf(
            "--background-primary", "--bg-base-primary", "--background-primary-alt",
        )
        private val SURFACE_KEYS = listOf(
            "--background-secondary", "--bg-base-secondary", "--background-secondary-alt",
            "--background-base-lowest",
        )
        private val SURFACE_ALT_KEYS = listOf(
            "--background-tertiary", "--bg-base-tertiary", "--background-floating",
            "--background-base-lower",
        )
        private val ACCENT_KEYS = listOf(
            "--brand-experiment", "--brand-500", "--accent", "--primary-500", "--control-brand-foreground",
        )
        private val TEXT_KEYS = listOf(
            "--text-normal", "--text-primary", "--header-primary", "--text-default",
        )
        private val MUTED_KEYS = listOf(
            "--text-muted", "--text-secondary", "--interactive-normal", "--header-secondary",
        )
        private val BORDER_KEYS = listOf(
            "--background-modifier-accent", "--border", "--background-modifier-hover",
            "--background-base-lowest",
        )

        fun fromVariables(variables: Map<String, String>, base: Palette = DEFAULT): Palette {
            val background = pick(variables, BACKGROUND_KEYS) ?: base.background
            val surface = pick(variables, SURFACE_KEYS) ?: base.surface
            val surfaceAlt = pick(variables, SURFACE_ALT_KEYS) ?: base.surfaceAlt
            val accent = pick(variables, ACCENT_KEYS) ?: base.accent
            val text = pick(variables, TEXT_KEYS) ?: base.text
            val muted = pick(variables, MUTED_KEYS) ?: base.textMuted
            val border = pick(variables, BORDER_KEYS) ?: base.border
            return Palette(
                background = background,
                surface = surface,
                surfaceAlt = surfaceAlt,
                accent = accent,
                text = text,
                textMuted = muted,
                border = border,
                onAccent = if (luminance(accent) > 0.55) 0xFF11131A.toInt() else 0xFFFFFFFF.toInt(),
                isDark = luminance(background) < 0.5,
                variables = variables,
            )
        }

        fun pick(variables: Map<String, String>, keys: List<String>): Int? {
            for (key in keys) {
                val raw = variables[key] ?: continue
                Colors.parse(raw)?.let { return it }
            }
            return null
        }

        fun luminance(color: Int): Double {
            val r = (color shr 16 and 0xFF) / 255.0
            val g = (color shr 8 and 0xFF) / 255.0
            val b = (color and 0xFF) / 255.0
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
    }
}

/** CSS colour parsing for the formats themes actually use. */
object Colors {

    private val NAMED = mapOf(
        "transparent" to 0x00000000,
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
    )

    fun parse(value: String): Int? {
        val text = value.trim().lowercase()
        if (text.isEmpty()) return null
        NAMED[text]?.let { return it }
        if (text.startsWith("#")) return parseHex(text.substring(1))
        if (text.startsWith("rgb")) return parseRgb(text)
        if (text.startsWith("hsl")) return parseHsl(text)
        return null
    }

    private fun parseHex(hex: String): Int? {
        val digits = hex.trim()
        if (digits.any { it !in "0123456789abcdef" }) return null
        return when (digits.length) {
            3 -> {
                val r = digits[0].digit() * 17
                val g = digits[1].digit() * 17
                val b = digits[2].digit() * 17
                argb(255, r, g, b)
            }
            4 -> {
                val r = digits[0].digit() * 17
                val g = digits[1].digit() * 17
                val b = digits[2].digit() * 17
                val a = digits[3].digit() * 17
                argb(a, r, g, b)
            }
            6 -> argb(
                255,
                digits.substring(0, 2).toInt(16),
                digits.substring(2, 4).toInt(16),
                digits.substring(4, 6).toInt(16),
            )
            8 -> argb(
                digits.substring(6, 8).toInt(16),
                digits.substring(0, 2).toInt(16),
                digits.substring(2, 4).toInt(16),
                digits.substring(4, 6).toInt(16),
            )
            else -> null
        }
    }

    private fun Char.digit(): Int = this.toString().toInt(16)

    private fun parseRgb(text: String): Int? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val parts = text.substring(open + 1, close).split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = if (parts.size >= 4) (parts[3].toFloatOrNull() ?: 1f).coerceIn(0f, 1f) else 1f
        return argb((a * 255).toInt(), r, g, b)
    }

    private fun parseHsl(text: String): Int? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val parts = text.substring(open + 1, close).split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val h = (parts[0].removeSuffix("deg").toFloatOrNull() ?: return null) / 360f
        val s = (parts[1].removeSuffix("%").toFloatOrNull() ?: return null) / 100f
        val l = (parts[2].removeSuffix("%").toFloatOrNull() ?: return null) / 100f
        val a = if (parts.size >= 4) (parts[3].toFloatOrNull() ?: 1f).coerceIn(0f, 1f) else 1f
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = (hue(p, q, h + 1f / 3f) * 255).toInt().coerceIn(0, 255)
        val g = (hue(p, q, h) * 255).toInt().coerceIn(0, 255)
        val b = (hue(p, q, h - 1f / 3f) * 255).toInt().coerceIn(0, 255)
        return argb((a * 255).toInt(), r, g, b)
    }

    private fun hue(p: Float, q: Float, t: Float): Float {
        var value = t
        if (value < 0f) value += 1f
        if (value > 1f) value -= 1f
        return when {
            value < 1f / 6f -> p + (q - p) * 6 * value
            value < 1f / 2f -> q
            value < 2f / 3f -> p + (q - p) * (2f / 3f - value) * 6
            else -> p
        }
    }

    private fun channel(part: String): Int? {
        val value = part.trim()
        return if (value.endsWith("%")) {
            ((value.removeSuffix("%").toFloatOrNull() ?: return null) / 100f * 255).toInt().coerceIn(0, 255)
        } else {
            (value.toFloatOrNull() ?: return null).toInt().coerceIn(0, 255)
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}

/** Turns installed theme files into palettes. */
class ThemeEngine {

    fun parse(addon: InstalledAddon): ParsedTheme {
        val source = addon.file.readText()
        val meta = addon.meta ?: MetaParser.parse(source)
        val rules = CssParser.parse(MetaParser.stripMeta(source))
        val variables = CssParser.resolveVariables(CssParser.customProperties(rules))
        return ParsedTheme(meta, rules, variables)
    }

    fun paletteFor(addon: InstalledAddon?): Palette {
        if (addon == null) return Palette.DEFAULT
        return try {
            val parsed = parse(addon)
            if (parsed.variables.isEmpty()) Palette.DEFAULT else Palette.fromVariables(parsed.variables)
        } catch (e: Exception) {
            Palette.DEFAULT
        }
    }
}
