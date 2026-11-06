package io.nebula.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nebula.mobile.BuildConfig
import io.nebula.mobile.Nebula
import io.nebula.mobile.NebulaApp
import io.nebula.mobile.R
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.BackupManager
import io.nebula.mobile.core.CrashReporter
import io.nebula.mobile.core.Fonts
import io.nebula.mobile.core.NebulaLog
import io.nebula.mobile.market.Marketplace
import io.nebula.mobile.market.MarketplaceEntry
import io.nebula.mobile.notify.Notifications
import io.nebula.mobile.theme.CustomTheme
import io.nebula.mobile.update.ReleaseInfo
import io.nebula.mobile.update.UpdateChecker
import java.io.File
import java.util.Locale

/**
 * Settings screen. Every control here writes through to real storage or performs a real
 * action; nothing is a preview or a remembered-in-memory toggle.
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var app: NebulaApp
    private lateinit var container: LinearLayout
    private var catalog: List<MarketplaceEntry> = emptyList()

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = BackupManager.export(app)
            if (writeTo(uri, text)) {
                toast("Backup written (${text.length / 1024} KB)")
            } else {
                toast("Could not write that file")
            }
        }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            toast("That file is empty")
            return@registerForActivityResult
        }
        val result = try {
            BackupManager.restore(app, text)
        } catch (e: Exception) {
            toast("Restore failed: ${e.message}")
            return@registerForActivityResult
        }
        val skipped = if (result.addonsSkipped.isEmpty()) "" else ", ${result.addonsSkipped.size} skipped"
        toast("Restored ${result.addonsInstalled} addon(s)$skipped")
        rebuild()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            toast(if (granted) "Notifications allowed" else "Notifications are still blocked by Android")
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = Nebula.get()
        container = view.findViewById(R.id.settingsContainer)
        view.setBackgroundColor(app.palette().background)
        rebuild()
        Fonts.apply(view, Fonts.typefaceOf(app.settings.fontFamily))
        if (app.settings.autoCheckUpdates) checkForUpdates(silent = true)
    }

    private fun rebuild() {
        if (!isAdded) return
        container.removeAllViews()
        buildAppearance()
        buildNotifications()
        buildUpdates()
        buildThemeBuilder()
        buildBackup()
        buildMarketplace()
        buildDiagnostics()
        Motion.reveal(container, app.settings.animations)
    }

    /* -------------------------------------------------------------- */
    /* Appearance                                                     */
    /* -------------------------------------------------------------- */

    private fun buildAppearance() {
        val column = section("Appearance")
        val families: List<Pair<String?, String>> =
            listOf(null to "System default") + Fonts.FAMILIES.map { entry -> entry.first as String? to entry.second }
        val current = families.firstOrNull { it.first == app.settings.fontFamily }?.second
            ?: app.settings.fontFamily
            ?: "System default"
        addValueRow(column, "Font", current) { showFontPicker(families) }
        addSwitch(column, "Custom animations", app.settings.animations) { enabled ->
            app.settings.animations = enabled
            if (enabled) Motion.reveal(container, true)
        }
    }

    private fun showFontPicker(families: List<Pair<String?, String>>) {
        val labels = families.map { it.second }.toTypedArray()
        val checked = families.indexOfFirst { it.first == app.settings.fontFamily }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Font family")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                app.settings.fontFamily = families[which].first
                dialog.dismiss()
                rebuild()
                Fonts.apply(requireView(), Fonts.typefaceOf(app.settings.fontFamily))
                toast("Font set to ${labels[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* -------------------------------------------------------------- */
    /* Notifications                                                  */
    /* -------------------------------------------------------------- */

    private fun buildNotifications() {
        val column = section("Notifications")
        addSwitch(column, "Notify when a patch finishes", app.settings.notifyOnPatch) { enabled ->
            app.settings.notifyOnPatch = enabled
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !Notifications.hasPermission(requireContext())
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Notifications.ensureChannel(requireContext(), app.settings.notificationImportance)
        }
        val labels = arrayOf("Min (silent)", "Low", "Default", "High (heads-up)")
        addValueRow(column, "Importance", labels[app.settings.notificationImportance - 1]) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Notification importance")
                .setSingleChoiceItems(labels, app.settings.notificationImportance - 1) { dialog, which ->
                    app.settings.notificationImportance = which + 1
                    Notifications.ensureChannel(requireContext(), which + 1)
                    dialog.dismiss()
                    rebuild()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        if (!Notifications.hasPermission(requireContext())) {
            addInfo(column, "Android is blocking notifications for Nebula, so patch results cannot be posted.")
        }
    }

    /* -------------------------------------------------------------- */
    /* Updates                                                        */
    /* -------------------------------------------------------------- */

    private fun buildUpdates() {
        val column = section("Updates")
        addValueRow(column, "Installed version", BuildConfig.VERSION_NAME) {
            toast("Nebula ${BuildConfig.VERSION_NAME}")
        }
        addSwitch(column, "Check GitHub releases on launch", app.settings.autoCheckUpdates) {
            app.settings.autoCheckUpdates = it
        }
        addButton(column, "Check for updates now") { checkForUpdates(silent = false) }
    }

    private fun checkForUpdates(silent: Boolean) {
        val context = requireContext()
        app.settings.lastUpdateCheck = System.currentTimeMillis()
        if (!silent) toast("Checking GitHub releases…")
        Thread {
            val outcome = runCatching { UpdateChecker.latest() }
            val release = outcome.getOrNull()
            val error = outcome.exceptionOrNull()
            val checksum = if (release == null) null else runCatching { UpdateChecker.checksumFor(release) }.getOrNull()
            view?.post {
                if (!isAdded) return@post
                if (release == null) {
                    if (silent) {
                        NebulaLog.warn("update", "Automatic update check failed: ${error?.message}")
                    } else {
                        toast("Update check failed: ${error?.message ?: "unknown error"}")
                    }
                    return@post
                }
                if (!UpdateChecker.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                    if (!silent) toast("You are on the latest release (${release.tag})")
                    return@post
                }
                if (silent && app.settings.lastNotifiedUpdate == release.tag) return@post
                app.settings.lastNotifiedUpdate = release.tag
                showUpdateDialog(context, release, checksum)
            }
        }.start()
    }

    private fun showUpdateDialog(context: Context, release: ReleaseInfo, checksum: String?) {
        val size = String.format(Locale.US, "%.1f MB", release.apkSize / 1048576.0)
        val checksumNote = if (checksum == null) {
            "This release has no .sha256 asset, so the download cannot be verified."
        } else {
            "The download is verified against the published SHA-256."
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Nebula ${release.tag} is available")
            .setMessage("${release.notes.ifBlank { "No release notes." }.take(1200)}\n\nAPK: $size\n$checksumNote")
            .setNegativeButton("Later", null)
            .setPositiveButton("Download") { _, _ -> downloadUpdate(context, release, checksum) }
            .show()
    }

    private fun downloadUpdate(context: Context, release: ReleaseInfo, checksum: String?) {
        toast("Downloading ${release.tag}…")
        Thread {
            val name = "nebula-" + release.tag.removePrefix("v") + ".apk"
            val target = File(context.filesDir, "updates/$name")
            val outcome = runCatching { UpdateChecker.download(release, target, checksum) }
            view?.post {
                if (!isAdded) return@post
                val file = outcome.getOrNull()
                if (file == null) {
                    toast("Download failed: ${outcome.exceptionOrNull()?.message}")
                } else {
                    installDownloaded(context, file)
                }
            }
        }.start()
    }

    private fun installDownloaded(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.patched", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            toast("Opening the installer for ${file.name}")
        } catch (e: Exception) {
            toast("Could not hand the APK to the installer: ${e.message}")
        }
    }

    /* -------------------------------------------------------------- */
    /* Custom theme                                                   */
    /* -------------------------------------------------------------- */

    private fun buildThemeBuilder() {
        val column = section("Custom theme")
        addInfo(column, "Builds a real .theme.css from three colours. It is installed like any imported theme and applied immediately.")
        addButton(column, "Create theme…") { showThemeDialog() }
    }

    private fun showThemeDialog() {
        val context = requireContext()
        val name = field(context, "Theme name", "My Theme")
        val accent = field(context, "Accent", "#A855F7")
        val background = field(context, "Background", "#0B0716")
        val text = field(context, "Text", "#ECE6FF")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(accent)
            addView(background)
            addView(text)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Create a theme")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                try {
                    val css = CustomTheme.build(
                        name.text.toString(),
                        accent.text.toString(),
                        background.text.toString(),
                        text.text.toString(),
                    )
                    val addon = app.importAddon(AddonType.THEME, css, "custom-theme")
                    app.plugins.setThemeActive(addon.id)
                    toast("Created and applied ${addon.name}")
                    rebuild()
                } catch (e: Exception) {
                    toast(e.message ?: "Could not create the theme")
                }
            }
            .show()
    }

    /* -------------------------------------------------------------- */
    /* Backup                                                         */
    /* -------------------------------------------------------------- */

    private fun buildBackup() {
        val column = section("Backup and restore")
        addInfo(column, "Exports settings, enabled state and the full source of every installed addon into one JSON file.")
        addButton(column, "Export backup…") {
            createBackup.launch("nebula-backup-${System.currentTimeMillis()}.json")
        }
        addButton(column, "Restore from backup…") {
            openBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    /* -------------------------------------------------------------- */
    /* Marketplace                                                    */
    /* -------------------------------------------------------------- */

    private fun buildMarketplace() {
        val column = section("Plugin marketplace")
        addInfo(
            column,
            if (catalog.isEmpty()) {
                "Fetches the catalogue published with Nebula. Every download is checked against its SHA-256."
            } else {
                "${catalog.size} addon(s) in the catalogue."
            },
        )
        addButton(column, if (catalog.isEmpty()) "Load catalogue" else "Reload catalogue") { loadCatalog() }
        for (entry in catalog) {
            addValueRow(column, "${entry.name} ${entry.version}", if (entry.type == AddonType.THEME) "theme" else "plugin") {
                installFromMarketplace(entry)
            }
        }
    }

    private fun loadCatalog() {
        val context = requireContext()
        toast("Loading catalogue…")
        Thread {
            val outcome = runCatching { Marketplace.fetchIndex() }
            view?.post {
                if (!isAdded) return@post
                val entries = outcome.getOrNull()
                if (entries == null) {
                    toast("Catalogue failed: ${outcome.exceptionOrNull()?.message}")
                    return@post
                }
                catalog = entries
                rebuild()
                toast("Loaded ${entries.size} addon(s) from ${Marketplace.INDEX_URL.substringAfterLast('/')}")
            }
        }.start()
    }

    private fun installFromMarketplace(entry: MarketplaceEntry) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle("Install ${entry.name}?")
            .setMessage(
                "${entry.version} by ${entry.author}\n\n${entry.description}\n\n" +
                    "The download is verified against its published SHA-256 before install.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ ->
                toast("Downloading ${entry.name}…")
                Thread {
                    val outcome = runCatching {
                        app.importAddon(entry.type, Marketplace.downloadSource(entry), entry.id)
                    }
                    view?.post {
                        if (!isAdded) return@post
                        val addon = outcome.getOrNull()
                        if (addon == null) {
                            toast("Install failed: ${outcome.exceptionOrNull()?.message}")
                        } else {
                            toast("Installed ${addon.name} ${addon.version}")
                        }
                        rebuild()
                    }
                }.start()
            }
            .show()
    }

    /* -------------------------------------------------------------- */
    /* Diagnostics                                                    */
    /* -------------------------------------------------------------- */

    private fun buildDiagnostics() {
        val column = section("Diagnostics")
        val crashes = CrashReporter.reports()
        addValueRow(column, "Crash reports", crashes.size.toString()) {
            val report = CrashReporter.latestText()
            if (report == null) {
                toast("No crash reports recorded")
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Latest crash report")
                    .setMessage(report.take(4000))
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Clear all") { _, _ ->
                        toast("Deleted ${CrashReporter.clear()} report(s)")
                        rebuild()
                    }
                    .show()
            }
        }
        val patches = app.patchHistory.read()
        addValueRow(column, "Patch history", patches.size.toString()) {
            if (patches.isEmpty()) {
                toast("No patched builds recorded yet")
            } else {
                val summary = patches.take(5).joinToString("\n\n") { record ->
                    "${record.file}\n${record.sizeBytes / 1048576} MB · Discord ${record.discordVersionCode} · " +
                        "${record.addonCount} addon(s) · ${record.themeTokens} token(s)"
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Recent patches")
                    .setMessage(summary)
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    /* -------------------------------------------------------------- */
    /* Small view helpers                                             */
    /* -------------------------------------------------------------- */

    private fun section(title: String): LinearLayout {
        val header = TextView(requireContext()).apply {
            text = title.uppercase(Locale.US)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(app.palette().textMuted)
            setPadding(0, dp(20), 0, dp(4))
        }
        container.addView(header)
        val column = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        container.addView(column)
        return column
    }

    private fun addSwitch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = SwitchMaterial(requireContext()).apply {
            text = label
            isChecked = checked
            setTextColor(app.palette().text)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        parent.addView(row)
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val button = MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        parent.addView(button, params)
    }

    private fun addValueRow(parent: LinearLayout, label: String, value: String, onClick: () -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val name = TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTextColor(app.palette().text)
        }
        val current = TextView(requireContext()).apply {
            text = value
            textSize = 14f
            gravity = Gravity.END
            setTextColor(app.palette().textMuted)
        }
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(current)
        parent.addView(row)
    }

    private fun addInfo(parent: LinearLayout, text: String) {
        val view = TextView(requireContext()).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(app.palette().textMuted)
            setPadding(0, dp(6), 0, dp(6))
        }
        parent.addView(view)
    }

    private fun field(context: Context, hint: String, initial: String): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(initial)
            setSingleLine()
        }

    private fun writeTo(uri: Uri, text: String): Boolean = try {
        requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } != null
    } catch (e: Exception) {
        NebulaLog.error("backup", "Unable to write backup: ${e.message}")
        false
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
