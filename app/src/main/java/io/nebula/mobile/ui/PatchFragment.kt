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
import io.nebula.mobile.patch.ApkIdentity
import io.nebula.mobile.patch.ApkSigning
import io.nebula.mobile.patch.DiscordPatcher
import io.nebula.mobile.patch.PatchAddon
import io.nebula.mobile.patch.PatchException
import io.nebula.mobile.patch.PatchReport
import io.nebula.mobile.patch.PatchRequest
import java.io.File
import java.util.Locale

/**
 * Patches a real Discord APK: injects the Nebula loader and the enabled plugins into
 * Discord's own React Native bundle, rebuilds the container, signs it, then offers the
 * result to the system installer.
 */
class PatchFragment : Fragment(R.layout.fragment_patch) {

    private lateinit var app: NebulaApp
    private var selected: File? = null
    private var patched: PatchReport? = null
    private var busy = false

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyIntoCache(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Nebula.get()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(R.id.txtPatchStatus)
        val addons = view.findViewById<TextView>(R.id.txtPatchAddons)
        val pick = view.findViewById<MaterialButton>(R.id.btnPickApk)
        val run = view.findViewById<MaterialButton>(R.id.btnPatch)
        val install = view.findViewById<MaterialButton>(R.id.btnInstallPatched)
        val share = view.findViewById<MaterialButton>(R.id.btnSharePatched)

        install.visibility = View.GONE
        share.visibility = View.GONE
        addons.text = addonSummary()
        status.text = "No APK selected yet."

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
            busy = true
            run.isEnabled = false
            status.text = "Reading ${apkFile.name}…"
            val context = requireContext().applicationContext
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
                            addons = enabledPlugins(),
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
                activity?.runOnUiThread {
                    busy = false
                    run.isEnabled = true
                    if (finished != null) {
                        patched = finished
                        status.text = describe(finished)
                        install.visibility = View.VISIBLE
                        share.visibility = View.VISIBLE
                    } else {
                        status.text = "Patch refused:\n${failure ?: "unknown error"}"
                    }
                }
            }.start()
        }

        install.setOnClickListener { installPatched() }
        share.setOnClickListener { sharePatched() }
    }

    /* ------------------------------------------------------------------ */

    private fun enabledPlugins(): List<PatchAddon> =
        app.store.list(AddonType.PLUGIN)
            .filter { addon -> app.state.isEnabled(AddonType.PLUGIN, addon.id) }
            .mapNotNull { addon ->
                try {
                    PatchAddon(
                        id = addon.id,
                        name = addon.name,
                        version = addon.version,
                        kind = "plugin",
                        source = app.store.read(addon),
                    )
                } catch (e: Exception) {
                    null
                }
            }

    private fun addonSummary(): String {
        val plugins = enabledPlugins()
        val head =
            if (plugins.isEmpty()) {
                "No plugins are enabled, so the patched app will only carry the Nebula loader. " +
                    "Enable plugins on the Plugins tab to embed them."
            } else {
                "Will embed ${plugins.size} plugin(s): " + plugins.joinToString(", ") { it.name }
            }
        return head + "\n\nCSS themes cannot be embedded into Discord: its Android UI is native " +
            "React Native, not a DOM, so there are no CSS-selectable elements. The loader reports " +
            "this instead of pretending a theme applied."
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
                "Selected ${target.name} (${humanSize(target)})."
            view?.findViewById<MaterialButton>(R.id.btnInstallPatched)?.visibility = View.GONE
            view?.findViewById<MaterialButton>(R.id.btnSharePatched)?.visibility = View.GONE
        } catch (e: Exception) {
            toast("Could not read that APK: ${e.message}")
        }
    }

    private fun describe(report: PatchReport): String = buildString {
        appendLine("Patched and signed: ${report.output.name}")
        appendLine("Size ${humanSize(report.output)} · SHA-256 ${report.sha256.take(16)}…")
        appendLine("Bundle ${report.bundleEntry}")
        appendLine("  ${report.bundleBytesBefore} → ${report.bundleBytesAfter} bytes")
        appendLine("Addons embedded: ${report.addonCount}")
        appendLine("Entries rewritten: ${report.rewrite.entryCount} (${report.rewrite.droppedEntries.size} old signature file(s) dropped)")
        appendLine(
            "Signature verified: v1=${report.signature.schemeV1} " +
                "v2=${report.signature.schemeV2} v3=${report.signature.schemeV3}",
        )
        appendLine("Signer: ${report.signerSubject}")
        append("Saved to ${report.output.absolutePath}")
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

    private fun humanSize(file: File): String = when {
        file.length() >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", file.length() / 1048576.0)
        else -> "${file.length() / 1024} KB"
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
