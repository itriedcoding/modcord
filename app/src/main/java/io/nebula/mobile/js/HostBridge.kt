package io.nebula.mobile.js

/** Result of an asynchronous network request made through `BdApi.Net`. */
data class FetchResult(
    val ok: Boolean,
    val status: Int,
    val body: String,
)

/**
 * The platform surface addons are allowed to touch. Implemented for Android in
 * [io.nebula.mobile.android.AndroidBridge] and by a recording double in JVM tests.
 */
interface HostBridge {

    fun log(level: String, scope: String, message: String)

    fun dataLoad(pluginId: String, name: String): String
    fun dataSave(pluginId: String, name: String, json: String): Boolean
    fun dataDelete(pluginId: String, name: String): Boolean

    fun toast(message: String)
    fun notice(title: String, body: String)
    fun confirm(
        title: String,
        body: String,
        confirmLabel: String,
        cancelLabel: String,
        onResult: (Boolean) -> Unit,
    )

    fun themeVariables(): String
    fun setAccent(hex: String?): Boolean

    fun listPlugins(): String
    fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean
    fun listThemes(): String
    fun setThemeEnabled(themeId: String, enabled: Boolean): Boolean

    fun registerSettings(pluginId: String, schemaJson: String): Boolean
    fun loadSettings(pluginId: String): String
    fun saveSetting(pluginId: String, key: String, jsonValue: String): Boolean

    fun fetch(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
        callback: (FetchResult) -> Unit,
    ): Int

    fun cancelFetch(id: Int)

    /** Called whenever a plugin starts or stops (or fails), so the UI stays in sync. */
    fun onPluginRuntimeChanged(pluginId: String, running: Boolean, error: String?)
}
