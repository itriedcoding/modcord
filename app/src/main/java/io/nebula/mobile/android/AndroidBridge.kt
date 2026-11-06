package io.nebula.mobile.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nebula.mobile.NebulaApp
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.NebulaLog
import io.nebula.mobile.js.FetchResult
import io.nebula.mobile.js.HostBridge
import io.nebula.mobile.theme.Colors
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Android implementation of everything addons are allowed to do. */
class AndroidBridge(
    private val context: Context,
    private val app: NebulaApp,
) : HostBridge {

    private val main = Handler(Looper.getMainLooper())
    private val fetchSeq = AtomicInteger(0)
    private val fetches = ConcurrentHashMap<Int, Thread>()

    override fun log(level: String, scope: String, message: String) {
        when (level.lowercase(Locale.ROOT)) {
            "error" -> NebulaLog.error(scope, message)
            "warn" -> NebulaLog.warn(scope, message)
            "debug" -> NebulaLog.debug(scope, message)
            else -> NebulaLog.info(scope, message)
        }
    }

    override fun dataLoad(pluginId: String, name: String): String = app.store.readData(pluginId, name)

    override fun dataSave(pluginId: String, name: String, json: String): Boolean =
        app.store.writeData(pluginId, name, json)

    override fun dataDelete(pluginId: String, name: String): Boolean =
        app.store.deleteData(pluginId, name)

    override fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    override fun notice(title: String, body: String) {
        main.post {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    override fun confirm(
        title: String,
        body: String,
        confirmLabel: String,
        cancelLabel: String,
        onResult: (Boolean) -> Unit,
    ) {
        main.post {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton(confirmLabel) { _, _ -> onResult(true) }
                .setNegativeButton(cancelLabel) { _, _ -> onResult(false) }
                .setOnCancelListener { onResult(false) }
                .show()
        }
    }

    override fun themeVariables(): String {
        val palette = app.palette()
        val json = JSONObject()
        for ((key, value) in palette.variables) json.put(key, value)
        json.put("--nebula-background", Colors.toHex(palette.background))
        json.put("--nebula-surface", Colors.toHex(palette.surface))
        json.put("--nebula-accent", Colors.toHex(palette.accent))
        json.put("--nebula-text", Colors.toHex(palette.text))
        json.put("--nebula-text-muted", Colors.toHex(palette.textMuted))
        return json.toString()
    }

    override fun setAccent(hex: String?): Boolean =
        app.setAccentOverride(hex?.takeIf { it.isNotBlank() })

    override fun listPlugins(): String = addonJson(AddonType.PLUGIN)

    override fun listThemes(): String = addonJson(AddonType.THEME)

    private fun addonJson(type: AddonType): String {
        val array = JSONArray()
        for (row in app.plugins.rows(type)) {
            array.put(
                JSONObject().apply {
                    put("id", row.addon.id)
                    put("name", row.addon.name)
                    put("version", row.addon.version)
                    put("author", row.addon.author)
                    put("enabled", row.enabled)
                    put("running", row.running)
                },
            )
        }
        return array.toString()
    }

    override fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        val addon = app.store.find(AddonType.PLUGIN, pluginId) ?: return false
        app.plugins.setEnabled(addon.id, enabled)
        return true
    }

    override fun setThemeEnabled(themeId: String, enabled: Boolean): Boolean {
        val addon = app.store.find(AddonType.THEME, themeId) ?: return false
        app.plugins.setThemeActive(if (enabled) addon.id else null)
        return true
    }

    override fun registerSettings(pluginId: String, schemaJson: String): Boolean {
        app.state.putSettingsSchema(pluginId, schemaJson)
        app.notifyAddonsChanged()
        return true
    }

    override fun loadSettings(pluginId: String): String = app.state.settings(pluginId).toString()

    override fun saveSetting(pluginId: String, key: String, jsonValue: String): Boolean {
        return try {
            val value: Any? = when {
                jsonValue == "null" -> null
                jsonValue == "true" -> true
                jsonValue == "false" -> false
                jsonValue.startsWith("{") || jsonValue.startsWith("[") -> jsonValue
                else -> jsonValue.toDoubleOrNull() ?: jsonValue.trim('"')
            }
            app.state.putSetting(pluginId, key, value)
            app.notifyAddonsChanged()
            true
        } catch (e: Exception) {
            NebulaLog.warn("settings", "Unable to save $key for $pluginId: ${e.message}")
            false
        }
    }

    override fun fetch(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
        callback: (FetchResult) -> Unit,
    ): Int {
        val id = fetchSeq.incrementAndGet()
        val thread = Thread {
            var result = FetchResult(false, 0, "")
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.ifBlank { "GET" }.uppercase(Locale.ROOT)
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Nebula-Android")
                    val headers = JSONObject(headersJson.ifBlank { "{}" })
                    for (key in headers.keys()) setRequestProperty(key, headers.optString(key))
                    if (!body.isNullOrEmpty() && requestMethod != "GET" && requestMethod != "HEAD") {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                result = FetchResult(status in 200..299, status, text)
            } catch (e: Exception) {
                result = FetchResult(false, 0, e.message ?: e.javaClass.simpleName)
            } finally {
                try {
                    connection?.disconnect()
                } catch (ignored: Exception) {
                    // Nothing to clean up.
                }
                fetches.remove(id)
                callback(result)
            }
        }.apply {
            isDaemon = true
            name = "nebula-net-$id"
        }
        fetches[id] = thread
        thread.start()
        return id
    }

    override fun cancelFetch(id: Int) {
        fetches.remove(id)?.interrupt()
    }

    override fun onPluginRuntimeChanged(pluginId: String, running: Boolean, error: String?) {
        app.onRuntimeChanged(pluginId, running, error)
    }
}
