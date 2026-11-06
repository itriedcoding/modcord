package io.nebula.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import io.nebula.mobile.BuildConfig
import io.nebula.mobile.Nebula
import io.nebula.mobile.NebulaApp
import io.nebula.mobile.R
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.MetaParser
import io.nebula.mobile.core.PatchRecord
import io.nebula.mobile.patch.ApkIdentity
import io.nebula.mobile.patch.ApkSigning
import io.nebula.mobile.patch.ApkSource
import io.nebula.mobile.patch.DiscordLocator
import io.nebula.mobile.patch.DiscordPatcher
import io.nebula.mobile.patch.InstalledDiscord
import io.nebula.mobile.patch.PatchAddon
import io.nebula.mobile.patch.PatchException
import io.nebula.mobile.patch.PatchReport
import io.nebula.mobile.patch.PatchRequest
import io.nebula.mobile.notify.Notifications
import io.nebula.mobile.theme.ThemeTokens
import java.io.File
import java.util.Locale

/**
 * Patches the Discord installed on this phone: injects the Nebula loader, the enabled
 * plugins and the enabled themes' colour tokens into Discord's own React Native bundle,
 * rebuilds the container, signs it and offers the result to the installer.
 */
class PatchFragment : Fragment(R.layout.fragment_patch) {

    private lateinit var app: NebulaApp
    private var selected: File? = null
    private var patched: PatchReport? = null
    private var busy = false
    private var detected: InstalledDiscord? = null
    private var detectedView: TextView? = null
    private var useInstalledButton: MaterialButton? = null
    private var getDiscordButton: MaterialButton? = null
    private var historyView: TextView? = null

    /** APIs that genuinely cannot exist inside Discord's bundle. */
    private val unsupportedApis = listOf(
        "BdApi.DOM",
        "BdApi.React",
        "BdApi.Hooks",
        "BdApi.Components",
        "BdApi.ContextMenu",
    )

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyIntoCache(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Nebula.get()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the store after installing Discord should pick it up by itself.
        if (selected == null) detectInstalled()
        renderHistory()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(R.id.txtPatchStatus)
        val addons = view.findViewById<TextView>(R.id.txtPatchAddons)
        val pick = view.findViewById<MaterialButton>(R.id.btnPickApk)
        val run = view.findViewById<MaterialButton>(R.id.btnPatch)
        val install = view.findViewById<MaterialButton>(R.id.btnInstallPatched)
        val share = view.findViewById<MaterialButton>(R.id.btnSharePatched)

        historyView = view.findViewById(R.id.txtPatchHistory)
        install.visibility = View.GONE
        share.visibility = View.GONE
        addons.text = addonSummary()
        status.text = "Looking for an installed Discord…"

        detectedView = view.findViewById(R.id.txtDiscordDetected)
        useInstalledButton = view.findViewById<MaterialButton>(R.id.btnUseInstalled).also { button ->
            button.visibility = View.GONE
            button.setOnClickListener {
                if (busy) return@setOnClickListener
                detected?.let { found -> prepareInstalled(found) }
            }
        }
        getDiscordButton = view.findViewById<MaterialButton>(R.id.btnGetDiscord).also { button ->
            button.visibility = View.GONE
            button.setOnClickListener { openDiscordInstallPage() }
        }
        renderHistory()
        detectInstalled()

        pick.setOnClickListener {
            if (busy) return@setOnClickListener
            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }

        run.setOnClickListener {
            val apkFile = selected
            if (busy) return@setOnClickListener
            if (apkFile == null) {
                toast("Pick the Discord APK first")
                return@setOnClickListener
            }
            val addonsToEmbed = patchAddons()
            busy = true
            run.isEnabled = false
            status.text = "Reading ${apkFile.name}…"
            val context = requireContext().applicationContext
            val sourceVersion = detected?.versionCode ?: 0L
            Thread {
                var result: PatchReport? = null
                var error: String? = null
                try {
                    val loader = NebulaApp.readAsset(context, "patcher/nebula-payload.js")
                    val outputDirectory = File(
                        context.getExternalFilesDir(null) ?: context.filesDir,
                        "patched",
                    )
                    result = DiscordPatcher.patch(
                        PatchRequest(
                            apk = apkFile,
                            outputDirectory = outputDirectory,
                            addons = addonsToEmbed,
                            loaderJs = loader,
                            identity = identity(context),
                            nebulaVersion = BuildConfig.VERSION_NAME,
                        ),
                    ) { line ->
                        activity?.runOnUiThread { status.text = line }
                    }
                } catch (e: PatchException) {
                    error = e.message
                } catch (e: Exception) {
                    error = "${e.javaClass.simpleName}: ${e.message}"
                }
                val finished = result
                val failure = error
                if (finished != null) {
                    app.patchHistory.add(
                        PatchRecord(
                            file = finished.output.name,
                            sha256 = finished.sha256,
                            sizeBytes = finished.sizeBytes,
                            discordVersionCode = sourceVersion,
                            addonCount = finished.addonCount,
                            themeTokens = addonsToEmbed.filter { it.kind == "theme" }.sumOf { it.tokens.size },
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    busy = false
                    run.isEnabled = true
                    if (finished != null) {
                        patched = finished
                        status.text = describe(finished, addonsToEmbed)
                        install.visibility = View.VISIBLE
                        share.visibility = View.VISIBLE
                        Notifications.patchComplete(
                            requireContext(),
                            finished.output,
                            finished.sizeBytes,
                            app.settings,
                        )
                    } else {
                        status.text = "Patch refused:\n${failure ?: "unknown error"}"
                    }
                    renderHistory()
                }
            }.start()
        }

        install.setOnClickListener { installPatched() }
        share.setOnClickListener { sharePatched() }
    }

    /* ------------------------------------------------------------------ */

    /** Looks for Discord through PackageManager, then prepares its APK automatically. */
    private fun detectInstalled() {
        val context = requireContext().applicationContext
        Thread {
            val found = try {
                DiscordLocator.find(context)
            } catch (e: Exception) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val first = found.firstOrNull()
                detected = first
                if (first == null) {
                    detectedView?.text =
                        "Discord is not installed on this device (looked for " +
                            DiscordLocator.KNOWN_PACKAGES.joinToString(", ") +
                            ").\nInstall it from the official store and this tab picks it up " +
                            "automatically, or choose an APK file below."
                    useInstalledButton?.visibility = View.GONE
                    getDiscordButton?.visibility = View.VISIBLE
                    view?.findViewById<TextView>(R.id.txtPatchStatus)?.text = "No APK source yet."
                } else {
                    getDiscordButton?.visibility = View.GONE
                    useInstalledButton?.visibility = View.VISIBLE
                    useInstalledButton?.text = "Re-read installed Discord ${first.versionName}"
                    val last = app.patchHistory.latest()
                    val changed = last != null && last.discordVersionCode > 0 &&
                        last.discordVersionCode != first.versionCode
                    detectedView?.text = buildString {
                        append("Detected ${first.summary()}\n${first.apkPath}")
                        if (changed) {
                            append(
                                "\n\nDiscord changed since your last patch " +
                                    "(${last!!.discordVersionCode} → ${first.versionCode}). " +
                                    "Patch again so your addons keep working.",
                            )
                        }
                    }
                    if (selected == null) prepareInstalled(first)
                }
            }
        }.start()
    }

    /** Copies the installed base APK and makes it the patch source. */
    private fun prepareInstalled(discord: InstalledDiscord) {
        val context = requireContext().applicationContext
        val status = view?.findViewById<TextView>(R.id.txtPatchStatus) ?: return
        busy = true
        status.text = "Reading installed ${discord.label}…"
        Thread {
            var problem: String? = null
            var copied: File? = null
            try {
                val target = File(
                    context.cacheDir,
                    "installed-${discord.packageName}-${discord.versionCode}.apk",
                )
                copied = ApkSource.copy(discord.apkPath, target)
            } catch (e: Exception) {
                problem = e.message
            }
            val file = copied
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                busy = false
                if (file == null) {
                    selected = null
                    status.text =
                        "Could not use the installed Discord APK: ${problem ?: "unknown error"}\n" +
                            "Choose an APK file instead."
                    return@runOnUiThread
                }
                selected = file
                patched = null
                view?.findViewById<MaterialButton>(R.id.btnInstallPatched)?.visibility = View.GONE
                view?.findViewById<MaterialButton>(R.id.btnSharePatched)?.visibility = View.GONE
                status.text = buildString {
                    appendLine("Source: installed ${discord.label} ${discord.versionName} · ${humanSizeOf(file.length())}")
                    if (discord.isSplitInstall) {
                        appendLine(
                            "Note: Discord is installed as ${discord.splitPaths.size + 1} APKs. " +
                                "Only the base APK can be patched; if its bundle or native libraries " +
                                "sit in a split, patching refuses with that reason.",
                        )
                    }
                    append("Ready to patch.")
                }
            }
        }.start()
    }

    /** Enabled plugins plus enabled themes, with their CSS already resolved to tokens. */
    private fun patchAddons(): List<PatchAddon> {
        val result = ArrayList<PatchAddon>()
        for (addon in app.store.list(AddonType.PLUGIN)) {
            if (!app.state.isEnabled(AddonType.PLUGIN, addon.id)) continue
            val source = try {
                app.store.read(addon)
            } catch (e: Exception) {
                continue
            }
            result.add(PatchAddon(addon.id, addon.name, addon.version, "plugin", source))
        }
        for (addon in app.store.list(AddonType.THEME)) {
            if (!app.state.isEnabled(AddonType.THEME, addon.id)) continue
            val css = try {
                app.store.read(addon)
            } catch (e: Exception) {
                continue
            }
            val tokens = ThemeTokens.resolve(MetaParser.stripMeta(css))
            result.add(PatchAddon(addon.id, addon.name, addon.version, "theme", css, tokens))
        }
        return result
    }

    private fun addonSummary(): String {
        val addons = patchAddons()
        val plugins = addons.filter { it.kind == "plugin" }
        val themes = addons.filter { it.kind == "theme" }
        val tokens = themes.sumOf { it.tokens.size }
        val warnings = plugins
            .flatMap { plugin ->
                unsupportedApis
                    .filter { api -> plugin.source.contains(api) }
                    .map { api -> "${plugin.name} calls $api" }
            }
            .distinct()
        return buildString {
            if (addons.isEmpty()) {
                appendLine("No addons are enabled. The patched app would carry only the Nebula loader.")
            } else {
                appendLine("Embedding ${plugins.size} plugin(s) and ${themes.size} theme(s).")
                if (themes.isNotEmpty()) {
                    appendLine(
                        "Theme colour tokens: $tokens. They are written into Discord's React Native " +
                            "token objects, which is what repaints native views.",
                    )
                }
            }
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("These calls have no equivalent inside Discord and throw at runtime:")
                warnings.forEach { appendLine("· $it") }
            }
        }.trim()
    }

    private fun renderHistory() {
        val view = historyView ?: return
        val records = try {
            app.patchHistory.read()
        } catch (e: Exception) {
            emptyList<PatchRecord>()
        }
        val latest = records.firstOrNull()
        view.text = if (latest == null) {
            "No patched builds yet."
        } else {
            buildString {
                appendLine("Last patch: ${latest.file}")
                appendLine(
                    "${humanSizeOf(latest.sizeBytes)} · sha ${latest.sha256.take(12)}… · " +
                        "${latest.addonCount} addon(s) · ${latest.themeTokens} colour token(s)",
                )
                append("${records.size} build(s) recorded")
            }
        }
    }

    private fun describe(report: PatchReport, addons: List<PatchAddon>): String = buildString {
        val tokens = addons.filter { it.kind == "theme" }.sumOf { it.tokens.size }
        appendLine("Patched and signed: ${report.output.name}")
        appendLine("Size ${humanSizeOf(report.sizeBytes)} · SHA-256 ${report.sha256.take(16)}…")
        appendLine("Bundle ${report.bundleEntry} (${report.bundleBytesBefore} → ${report.bundleBytesAfter} bytes)")
        appendLine("Addons embedded: ${report.addonCount} · colour tokens: $tokens")
        appendLine(
            "Entries rewritten: ${report.rewrite.entryCount} " +
                "(${report.rewrite.droppedEntries.size} old signature file(s) dropped)",
        )
        appendLine(
            "Signature verified: v1=${report.signature.schemeV1} " +
                "v2=${report.signature.schemeV2} v3=${report.signature.schemeV3}",
        )
        appendLine("Signer: ${report.signerSubject}")
        append("Saved to ${report.output.absolutePath}")
    }

    /**
     * Opens the official store listing. Nebula never downloads Discord's APK on its own:
     * only Discord can distribute Discord, and an APK from a mirror would be unverifiable.
     */
    private fun openDiscordInstallPage() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.discord"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.discord"),
        )
        try {
            startActivity(market)
        } catch (e: Exception) {
            try {
                startActivity(web)
            } catch (also: Exception) {
                toast("No store app available. Install Discord, then reopen this tab.")
            }
        }
    }

    private fun identity(context: Context): ApkIdentity {
        val keystore = File(context.cacheDir, "nebula-patch-signing.jks")
        if (!keystore.exists() || keystore.length() == 0L) {
            context.assets.open("patcher/signing.jks").use { input ->
                keystore.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return ApkSigning.identityFromKeystore(
            keystore,
            "nebula".toCharArray(),
            "nebula",
            "nebula".toCharArray(),
        )
    }

    private fun copyIntoCache(uri: Uri) {
        val context = requireContext()
        try {
            var displayName = "discord.apk"
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                    }
                }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(context.cacheDir, "incoming-$safeName")
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("this file cannot be opened")
            stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            if (target.length() < 1024) throw IllegalStateException("that file is too small to be an APK")

            selected = target
            patched = null
            view?.findViewById<TextView>(R.id.txtPatchStatus)?.text =
                "Selected ${target.name} (${humanSizeOf(target.length())})."
            view?.findViewById<MaterialButton>(R.id.btnInstallPatched)?.visibility = View.GONE
            view?.findViewById<MaterialButton>(R.id.btnSharePatched)?.visibility = View.GONE
        } catch (e: Exception) {
            toast("Could not read that APK: ${e.message}")
        }
    }

    private fun installPatched() {
        val report = patched ?: return
        val context = requireContext()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.patched", report.output)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Could not open the installer (${e.message}). The APK is at ${report.output.absolutePath}")
        }
    }

    private fun sharePatched() {
        val report = patched ?: return
        val context = requireContext()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.patched", report.output)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share patched APK"))
        } catch (e: Exception) {
            toast("Could not share the APK: ${e.message}")
        }
    }

    private fun humanSizeOf(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> "${bytes / 1024} KB"
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
