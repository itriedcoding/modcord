package io.nebula.mobile.ui

import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nebula.mobile.AddonRow
import io.nebula.mobile.NebulaApp
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.MetaParser
import io.nebula.mobile.theme.Palette
import org.json.JSONArray

/** Detail dialogs for plugins and themes, including the native settings screen. */
object AddonDialogs {

    fun showPluginDetails(activity: FragmentActivity, app: NebulaApp, row: AddonRow) {
        val palette = app.palette()
        val container = column(activity, palette)

        container.addView(heading(activity, row.addon.name, palette))
        container.addView(caption(activity, "${row.addon.version} · ${row.addon.author}", palette))
        if (row.addon.description.isNotBlank()) {
            container.addView(body(activity, row.addon.description, palette))
        }
        container.addView(body(activity, "Identifier: ${row.addon.id}", palette))
        row.addon.meta?.website?.let { container.addView(body(activity, "Website: $it", palette)) }
        row.addon.meta?.source?.let { container.addView(body(activity, "Source: $it", palette)) }
        if (row.error != null) {
            container.addView(body(activity, "Runtime error: ${row.error}", palette, error = true))
        }

        val source = try {
            app.store.read(row.addon)
        } catch (e: Exception) {
            ""
        }
        val unavailable = detectUnavailableApis(source)
        if (unavailable.isNotEmpty()) {
            container.addView(
                body(
                    activity,
                    "This plugin uses ${unavailable.joinToString(", ")}; those Discord-renderer APIs " +
                        "are not available in a standalone Android host. It can still run if it guards them.",
                    palette,
                    error = true,
                ),
            )
        }

        val schema = app.state.settingsSchema(row.addon.id)
        if (schema != null && schema != "[]") {
            container.addView(button(activity, "Settings", palette) { showSettings(activity, app, row) })
        }
        container.addView(
            button(activity, if (row.enabled) "Disable" else "Enable", palette) {
                app.plugins.setEnabled(row.addon.id, !row.enabled)
            },
        )
        container.addView(button(activity, "View source", palette) { showSource(activity, app, row) })
        container.addView(button(activity, "Delete", palette) { confirmDelete(activity, app, row) })

        MaterialAlertDialogBuilder(activity)
            .setView(scroll(activity, container))
            .setPositiveButton("Close", null)
            .show()
    }

    fun showThemeDetails(activity: FragmentActivity, app: NebulaApp, row: AddonRow) {
        val palette = app.palette()
        val container = column(activity, palette)
        container.addView(heading(activity, row.addon.name, palette))
        container.addView(caption(activity, "${row.addon.version} · ${row.addon.author}", palette))
        if (row.addon.description.isNotBlank()) {
            container.addView(body(activity, row.addon.description, palette))
        }

        val parsed = try {
            app.paletteForTheme(row.addon.id)
        } catch (e: Exception) {
            Palette.DEFAULT
        }
        val variables = parsed.variables
        container.addView(body(activity, "${variables.size} CSS variables detected", palette))
        if (variables.isNotEmpty()) {
            val preview = variables.entries.take(12).joinToString("\n") { (key, value) -> "$key: $value" }
            container.addView(
                body(activity, preview, palette).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                },
            )
        } else {
            container.addView(
                body(
                    activity,
                    "This theme does not override any Discord CSS variables, so it cannot be applied " +
                        "as a palette. Discord DOM selectors cannot be rendered outside Discord.",
                    palette,
                    error = true,
                ),
            )
        }

        container.addView(
            button(
                activity,
                if (app.activeThemeId() == row.addon.id) "Remove theme" else "Apply theme",
                palette,
            ) {
                app.plugins.setThemeActive(if (app.activeThemeId() == row.addon.id) null else row.addon.id)
            },
        )
        container.addView(button(activity, "View CSS", palette) { showSource(activity, app, row) })
        container.addView(button(activity, "Delete", palette) { confirmDelete(activity, app, row) })

        MaterialAlertDialogBuilder(activity)
            .setView(scroll(activity, container))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showSettings(activity: FragmentActivity, app: NebulaApp, row: AddonRow) {
        val schema = app.state.settingsSchema(row.addon.id) ?: return
        val palette = app.palette()
        val values = app.state.settings(row.addon.id)
        val container = column(activity, palette)
        val editors = ArrayList<Pair<String, () -> Any?>>()

        val array = try {
            JSONArray(schema)
        } catch (e: Exception) {
            JSONArray()
        }
        for (index in 0 until array.length()) {
            val field = array.optJSONObject(index) ?: continue
            val key = field.optString("key").ifBlank { "setting$index" }
            val label = field.optString("label").ifBlank { key }
            val description = field.optString("description")
            val type = field.optString("type", "text")
            val defaultValue = field.opt("default")

            container.addView(caption(activity, label, palette).apply { textSize = 14f })
            if (description.isNotBlank()) {
                container.addView(body(activity, description, palette).apply { textSize = 12f })
            }

            when (type) {
                "boolean" -> {
                    val switch = com.google.android.material.materialswitch.MaterialSwitch(activity).apply {
                        isChecked = if (values.has(key)) values.optBoolean(key) else (defaultValue as? Boolean ?: false)
                    }
                    container.addView(switch)
                    editors.add(key to { switch.isChecked })
                }
                "number" -> {
                    val input = EditText(activity).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setText(
                            if (values.has(key)) values.optString(key)
                            else defaultValue?.toString().orEmpty(),
                        )
                    }
                    container.addView(input)
                    editors.add(key to { input.text.toString().toDoubleOrNull() ?: 0.0 })
                }
                "select" -> {
                    val options = field.optJSONArray("options") ?: JSONArray()
                    val labels = ArrayList<String>()
                    for (i in 0 until options.length()) {
                        labels.add(options.optJSONObject(i)?.optString("label") ?: options.optString(i))
                    }
                    val spinner = android.widget.Spinner(activity).apply {
                        adapter = android.widget.ArrayAdapter(
                            activity,
                            android.R.layout.simple_spinner_dropdown_item,
                            labels,
                        )
                    }
                    container.addView(spinner)
                    editors.add(
                        key to {
                            val option = options.optJSONObject(spinner.selectedItemPosition)
                            option?.opt("value") ?: spinner.selectedItem?.toString()
                        },
                    )
                }
                else -> {
                    val input = EditText(activity).apply {
                        setText(if (values.has(key)) values.optString(key) else defaultValue?.toString().orEmpty())
                    }
                    container.addView(input)
                    editors.add(key to { input.text.toString() })
                }
            }
        }

        if (editors.isEmpty()) {
            container.addView(body(activity, "This plugin registered no settings.", palette))
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("${row.addon.name} settings")
            .setView(scroll(activity, container))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                for ((key, reader) in editors) {
                    app.plugins.writeSetting(row.addon.id, key, reader())
                }
            }
            .show()
    }

    private fun showSource(activity: FragmentActivity, app: NebulaApp, row: AddonRow) {
        val palette = app.palette()
        val text = try {
            app.store.read(row.addon)
        } catch (e: Exception) {
            "Unable to read file: ${e.message}"
        }
        val view = TextView(activity).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(palette.text)
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(activity).apply { addView(view) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(row.addon.file.name)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmDelete(activity: FragmentActivity, app: NebulaApp, row: AddonRow) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete ${row.addon.name}?")
            .setMessage("The file and its stored data will be removed. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                app.deleteAddon(row.addon)
            }
            .show()
    }

    private fun detectUnavailableApis(source: String): List<String> {
        val body = MetaParser.stripMeta(source)
        val found = ArrayList<String>()
        for (api in listOf("Webpack", "DOM", "React", "Hooks", "ContextMenu", "Components")) {
            if (body.contains("BdApi.$api")) found.add(api)
        }
        return found
    }

    private fun column(activity: FragmentActivity, palette: Palette): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            setBackgroundColor(palette.surface)
        }

    private fun heading(activity: FragmentActivity, text: String, palette: Palette): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
            setPadding(0, 0, 0, 8)
        }

    private fun caption(activity: FragmentActivity, text: String, palette: Palette): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(palette.accent)
        }

    private fun body(
        activity: FragmentActivity,
        text: String,
        palette: Palette,
        error: Boolean = false,
    ): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 12, 0, 0)
        setTextColor(
            when {
                error && palette.isDark -> 0xFFFF8A80.toInt()
                error -> 0xFFB3261E.toInt()
                else -> palette.textMuted
            },
        )
    }

    private fun button(
        activity: FragmentActivity,
        label: String,
        palette: Palette,
        onClick: () -> Unit,
    ): MaterialButton = MaterialButton(
        activity,
        null,
        io.nebula.mobile.R.attr.nebulaOutlinedButtonStyle,
    ).apply {
        text = label
        setTextColor(palette.accent)
        strokeColor = android.content.res.ColorStateList.valueOf(palette.border)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 16 }
        gravity = Gravity.CENTER
        setOnClickListener {
            onClick()
        }
    }

    private fun scroll(activity: FragmentActivity, content: LinearLayout): ScrollView =
        ScrollView(activity).apply { addView(content) }
}
