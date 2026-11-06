package io.nebula.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nebula.mobile.Nebula
import io.nebula.mobile.NebulaApp
import io.nebula.mobile.R
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.MetaParser
import io.nebula.mobile.theme.Palette
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var app: NebulaApp
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var pager: ViewPager2

    private val paletteListener: (Palette) -> Unit = { runOnUiThread { recreate() } }

    private val pickAddonFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val source = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (source.isNullOrBlank()) {
                toast("That file is empty")
                return@registerForActivityResult
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')
            install(source, name, null)
        } catch (e: Exception) {
            toast("Could not read file: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Nebula.get()
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        pager = findViewById(R.id.pager)
        pager.adapter = TabsPagerAdapter(this)
        TabLayoutMediator(tabs, pager) { tab, position -> tab.text = TITLES[position] }.attach()

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showImportMenu(it) }
        applyPalette()
        app.addPaletteListener(paletteListener)
    }

    override fun onDestroy() {
        app.removePaletteListener(paletteListener)
        super.onDestroy()
    }

    private fun applyPalette() {
        val palette = app.palette()
        window.statusBarColor = palette.surface
        window.navigationBarColor = palette.surface
        findViewById<View>(R.id.root).setBackgroundColor(palette.background)
        toolbar.setBackgroundColor(palette.surface)
        toolbar.setTitleTextColor(palette.text)
        tabs.setBackgroundColor(palette.surface)
        tabs.setTabTextColors(palette.textMuted, palette.accent)
        tabs.setSelectedTabIndicatorColor(palette.accent)
        pager.setBackgroundColor(palette.background)
    }

    private fun showImportMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Plugin file (.plugin.js)")
        menu.menu.add(0, 2, 1, "Theme file (.theme.css)")
        menu.menu.add(0, 3, 2, "From URL")
        menu.menu.add(0, 4, 3, "Paste source")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> pickAddonFile.launch(arrayOf("*/*"))
                2 -> pickAddonFile.launch(arrayOf("*/*"))
                3 -> showUrlDialog()
                4 -> showPasteDialog()
            }
            true
        }
        menu.show()
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/MyPlugin.plugin.js"
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Import from URL")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) downloadAndInstall(url)
            }
            .show()
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "Paste a .plugin.js or .theme.css file"
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 8
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(260),
                ),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Paste addon source")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ ->
                val source = input.text.toString()
                if (source.isBlank()) {
                    toast("Nothing to install")
                } else {
                    install(source, null, null)
                }
            }
            .show()
    }

    private fun downloadAndInstall(url: String) {
        toast("Downloading…")
        Thread {
            var result: String? = null
            var error: String? = null
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Nebula-Android")
                }
                if (connection.responseCode == 200) {
                    result = connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    error = "Server returned HTTP ${connection.responseCode}"
                }
                connection.disconnect()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                val source = result
                if (source.isNullOrBlank()) {
                    toast("Download failed: ${error ?: "empty response"}")
                } else {
                    install(source, url.substringAfterLast('/'), null)
                }
            }
        }.start()
    }

    private fun install(source: String, nameHint: String?, forcedType: AddonType?) {
        val type = forcedType ?: guessType(source, nameHint)
        try {
            val addon = app.importAddon(type, source, nameHint)
            if (type == AddonType.PLUGIN) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Plugin installed")
                    .setMessage("${addon.name} ${addon.version} was installed.\n\nEnable it now?")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Enable") { _, _ ->
                        app.plugins.setEnabled(addon.id, true) { result ->
                            runOnUiThread {
                                result.exceptionOrNull()?.let {
                                    toast("${addon.name} failed: ${it.message}")
                                }
                            }
                        }
                    }
                    .show()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Theme installed")
                    .setMessage("${addon.name} ${addon.version} was installed.\n\nApply it now?")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Apply") { _, _ ->
                        app.plugins.setThemeActive(addon.id)
                    }
                    .show()
            }
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Import failed")
                .setMessage(e.message ?: "Unknown error")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun guessType(source: String, nameHint: String?): AddonType {
        val name = nameHint?.lowercase().orEmpty()
        if (name.endsWith(".theme.css") || name.endsWith(".css")) return AddonType.THEME
        if (name.endsWith(".plugin.js") || name.endsWith(".js")) return AddonType.PLUGIN
        if (MetaParser.isPluginSource(source)) return AddonType.PLUGIN
        return if (MetaParser.isThemeSource(source)) AddonType.THEME else AddonType.PLUGIN
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val TITLES = arrayOf("Plugins", "Themes", "Console", "About")
    }
}

class TabsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> PluginsFragment()
        1 -> ThemesFragment()
        2 -> ConsoleFragment()
        else -> AboutFragment()
    }
}

