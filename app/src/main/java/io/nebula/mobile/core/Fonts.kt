package io.nebula.mobile.core

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Custom fonts, implemented with the platform's real font families.
 *
 * Android exposes named families from its own `fonts.xml` (`sans-serif`, `serif`,
 * `monospace`, `sans-serif-condensed`, ...). [Typeface.create] resolves those names
 * against the real system fonts, so switching families is an actual change in rendering,
 * not a preview or a pretend setting.
 */
object Fonts {

    /** Family name -> label shown in the picker. */
    val FAMILIES: List<Pair<String, String>> = listOf(
        "sans-serif" to "Roboto (default sans)",
        "serif" to "Serif",
        "monospace" to "Monospace",
        "sans-serif-condensed" to "Condensed sans",
        "sans-serif-light" to "Light",
        "sans-serif-medium" to "Medium",
        "casual" to "Casual",
        "cursive" to "Cursive",
    )

    fun typefaceOf(family: String?): Typeface {
        if (family.isNullOrBlank()) return Typeface.DEFAULT
        return try {
            Typeface.create(family, Typeface.NORMAL)
        } catch (e: Exception) {
            NebulaLog.warn("fonts", "Unknown system font family: $family")
            Typeface.DEFAULT
        }
    }

    /** Walks a view tree and applies [typeface], leaving monospace log views untouched. */
    fun apply(root: View, typeface: Typeface) = applyTo(root, typeface)

    private fun applyTo(view: View, typeface: Typeface) {
        if (view is TextView && view.typeface !== Typeface.MONOSPACE) {
            view.typeface = typeface
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTo(view.getChildAt(index), typeface)
        }
    }
}
