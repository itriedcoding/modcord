package io.nebula.mobile.js

import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import com.quickjs.QuickJSScriptException
import io.nebula.mobile.core.AddonMeta
import io.nebula.mobile.core.NebulaLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The JavaScript files that make up the runtime. Loaded from the APK assets and
 * conformance-tested by `tools/js-runtime-test.mjs`.
 */
data class BdApiAssets(
    val prelude: String,
    val bdApi: String,
    val bootstrap: String,
    val teardown: String,
)

/**
 * Runs BetterDiscord plugins on QuickJS.
 *
 * Every plugin gets its own QuickJS runtime and context, so one plugin cannot see
 * another's globals. All JavaScript executes on one dedicated thread; the host
 * only ever exchanges strings, numbers and booleans with the engine, and wakes
 * the engine up for timers and for results of UI/network callbacks.
 */
class PluginHost(
    private val host: HostBridge,
    private val assets: BdApiAssets,
) {

    private class Session(
        val pluginId: String,
        val pluginName: String,
        val runtime: QuickJS,
        val context: JSContext,
    ) {
        val timers = HashMap<Int, ScheduledFuture<*>?>()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    private val jsThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nebula-js").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nebula-timers").apply { isDaemon = true }
    }

    @Volatile
    private var disposed = false

    fun isRunning(pluginId: String): Boolean = sessions.containsKey(pluginId)

    fun start(
        pluginId: String,
        pluginName: String,
        meta: AddonMeta?,
        source: String,
    ): CompletableFuture<Result<Unit>> {
        val future = CompletableFuture<Result<Unit>>()
        try {
            jsThread.execute {
                val result = try {
                    startInternal(pluginId, pluginName, meta, source)
                    Result.success(Unit)
                } catch (t: Throwable) {
                    Result.failure(RuntimeException(describe(t), t))
                }
                future.complete(result)
            }
        } catch (e: Exception) {
            future.complete(Result.failure(e))
        }
        return future
    }

    fun stop(pluginId: String): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        try {
            jsThread.execute {
                stopInternal(pluginId)
                future.complete(Unit)
            }
        } catch (e: Exception) {
            future.complete(Unit)
        }
        return future
    }

    /** Tells a running plugin its settings changed (native settings screen). */
    fun notifySettingsChanged(pluginId: String) {
        postToJs {
            val session = sessions[pluginId] ?: return@postToJs
            runCatching {
                session.context.executeScript("__nebulaSettingsChanged()", "settings.js")
            }.onFailure {
                NebulaLog.warn("runtime", "[${session.pluginName}] settings callback failed: ${describe(it)}")
            }
        }
    }

    fun dispose() {
        disposed = true
        sessions.keys.toList().forEach { id -> stopInternal(id, notify = false) }
        scheduler.shutdownNow()
        jsThread.shutdownNow()
    }

    /* ---------------------------------------------------------------------- */
    /* Lifecycle                                                              */
    /* ---------------------------------------------------------------------- */

    private fun startInternal(
        pluginId: String,
        pluginName: String,
        meta: AddonMeta?,
        source: String,
    ) {
        stopInternal(pluginId, notify = false)

        val runtime = QuickJS.createRuntime()
        val context = runtime.createContext()
        val session = Session(pluginId, pluginName, runtime, context)
        sessions[pluginId] = session

        try {
            context.set("__nPluginId", pluginId)
            context.set("__nPluginName", pluginName)
            context.set("__nMetaJson", metaJson(meta, pluginName))
            registerHostFunctions(session)

            context.executeScript(assets.prelude, "prelude.js")
            context.executeScript(assets.bdApi, "bdapi.js")
            context.executeScript(source, "$pluginId.plugin.js")
            context.executeScript(assets.bootstrap, "bootstrap.js")

            NebulaLog.info("runtime", "Started $pluginName")
            host.onPluginRuntimeChanged(pluginId, true, null)
        } catch (t: Throwable) {
            sessions.remove(pluginId)
            cancelTimers(session)
            closeQuietly(session)
            val message = describe(t)
            NebulaLog.error("runtime", "Failed to start $pluginName: $message")
            host.onPluginRuntimeChanged(pluginId, false, message)
            throw RuntimeException(message, t)
        }
    }

    private fun stopInternal(pluginId: String, notify: Boolean = true) {
        val session = sessions.remove(pluginId) ?: return
        try {
            session.context.executeScript(assets.teardown, "teardown.js")
            NebulaLog.info("runtime", "Stopped ${session.pluginName}")
        } catch (t: Throwable) {
            NebulaLog.warn("runtime", "${session.pluginName} stop() failed: ${describe(t)}")
        } finally {
            cancelTimers(session)
            closeQuietly(session)
            if (notify) host.onPluginRuntimeChanged(pluginId, false, null)
        }
    }

    private fun closeQuietly(session: Session) {
        runCatching { session.context.close() }
        runCatching { session.runtime.close() }
    }

    /* ---------------------------------------------------------------------- */
    /* Host functions exposed to JavaScript                                   */
    /* ---------------------------------------------------------------------- */

    private fun registerHostFunctions(session: Session) {
        val context = session.context

        fun value(name: String, body: (JSArray) -> Any?) {
            context.registerJavaMethod(JavaCallback { _, args ->
                try {
                    body(args)
                } catch (t: Throwable) {
                    NebulaLog.error("runtime", "[${session.pluginName}] $name failed: ${describe(t)}")
                    null
                }
            }, name)
        }

        fun action(name: String, body: (JSArray) -> Unit) {
            context.registerJavaMethod(JavaVoidCallback { _, args ->
                try {
                    body(args)
                } catch (t: Throwable) {
                    NebulaLog.error("runtime", "[${session.pluginName}] $name failed: ${describe(t)}")
                }
            }, name)
        }

        value("__nLog") { args ->
            host.log(args.string(0, "log"), session.pluginName, args.string(1))
            null
        }
        value("__nDataLoad") { args -> host.dataLoad(session.pluginId, args.string(0, "data")) }
        value("__nDataSave") { args ->
            host.dataSave(session.pluginId, args.string(0, "data"), args.string(1, "{}"))
        }
        value("__nDataDelete") { args -> host.dataDelete(session.pluginId, args.string(0, "data")) }

        action("__nToast") { args -> host.toast(args.string(0)) }
        action("__nNotice") { args -> host.notice(args.string(0, "Nebula"), args.string(1)) }
        action("__nConfirm") { args ->
            val callbackId = args.integer(0)
            host.confirm(
                args.string(1, "Nebula"),
                args.string(2),
                args.string(3, "OK"),
                args.string(4, "Cancel"),
            ) { confirmed ->
                postToJs { invokeCallback(session, callbackId, listOf(confirmed)) }
            }
        }

        value("__nThemeVariables") { host.themeVariables() }
        value("__nSetAccent") { args ->
            val hex = args.string(0)
            host.setAccent(hex.ifBlank { null })
        }

        value("__nListPlugins") { host.listPlugins() }
        value("__nSetPluginEnabled") { args -> host.setPluginEnabled(args.string(0), args.bool(1)) }
        value("__nListThemes") { host.listThemes() }
        value("__nSetThemeEnabled") { args -> host.setThemeEnabled(args.string(0), args.bool(1)) }

        value("__nRegisterSettings") { args ->
            host.registerSettings(session.pluginId, args.string(0, "[]"))
        }
        value("__nLoadSettings") { host.loadSettings(session.pluginId) }
        value("__nSaveSetting") { args ->
            host.saveSetting(session.pluginId, args.string(0), args.string(1, "null"))
        }

        value("__nFetch") { args ->
            val callbackId = args.integer(0)
            val requestId = host.fetch(
                args.string(1),
                args.string(2, "GET"),
                args.string(3, "{}"),
                if (args.length() > 4) args.string(4) else null,
            ) { result ->
                postToJs { invokeCallback(session, callbackId, listOf(result.ok, result.status, result.body)) }
            }
            requestId
        }
        value("__nCancelFetch") { args ->
            host.cancelFetch(args.integer(0))
            null
        }

        value("__nScheduleTimer") { args ->
            scheduleTimer(session, args.integer(0), args.double(1).toLong(), args.bool(2))
            null
        }
        value("__nCancelTimer") { args ->
            cancelTimer(session, args.integer(0))
            null
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Timers and callbacks                                                   */
    /* ---------------------------------------------------------------------- */

    private fun scheduleTimer(session: Session, timerId: Int, delayMs: Long, repeat: Boolean) {
        val delay = delayMs.coerceAtLeast(0L)
        val task = Runnable {
            postToJs {
                if (!sessions.containsKey(session.pluginId)) return@postToJs
                if (!repeat) session.timers.remove(timerId)
                try {
                    session.context.executeScript("__nebulaTimers.tick($timerId)", "timer.js")
                } catch (t: Throwable) {
                    NebulaLog.error("runtime", "[${session.pluginName}] timer failed: ${describe(t)}")
                }
            }
        }
        val future = if (repeat) {
            scheduler.scheduleAtFixedRate(task, delay, delay.coerceAtLeast(16L), TimeUnit.MILLISECONDS)
        } else {
            scheduler.schedule(task, delay, TimeUnit.MILLISECONDS)
        }
        session.timers[timerId] = future
    }

    private fun cancelTimer(session: Session, timerId: Int) {
        session.timers.remove(timerId)?.cancel(false)
    }

    private fun cancelTimers(session: Session) {
        session.timers.values.forEach { it?.cancel(false) }
        session.timers.clear()
    }

    private fun invokeCallback(session: Session, callbackId: Int, args: List<Any?>) {
        if (!sessions.containsKey(session.pluginId)) return
        val json = JSONArray(args).toString()
        val script = "__nebulaCallbacks.invoke($callbackId, ${JSONObject.quote(json)})"
        try {
            session.context.executeScript(script, "callback.js")
        } catch (t: Throwable) {
            NebulaLog.error("runtime", "[${session.pluginName}] callback failed: ${describe(t)}")
        }
    }

    private fun postToJs(block: () -> Unit) {
        if (disposed) return
        try {
            jsThread.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    NebulaLog.error("runtime", "JavaScript task failed: ${describe(t)}")
                }
            }
        } catch (e: Exception) {
            NebulaLog.warn("runtime", "Cannot schedule JavaScript task: ${e.message}")
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Helpers                                                                */
    /* ---------------------------------------------------------------------- */

    private fun metaJson(meta: AddonMeta?, pluginName: String): String = JSONObject().apply {
        put("name", meta?.name ?: pluginName)
        put("author", meta?.displayAuthor ?: "Unknown")
        put("description", meta?.description ?: "")
        put("version", meta?.version ?: "0.0.0")
        put("website", meta?.website ?: "")
        put("source", meta?.source ?: "")
        put("invite", "")
    }.toString()

    private fun JSArray.string(index: Int, default: String = ""): String {
        if (index >= length()) return default
        return try {
            getString(index) ?: default
        } catch (e: Exception) {
            default
        }
    }

    private fun JSArray.integer(index: Int, default: Int = 0): Int {
        if (index >= length()) return default
        return try {
            getInteger(index)
        } catch (e: Exception) {
            default
        }
    }

    private fun JSArray.double(index: Int, default: Double = 0.0): Double {
        if (index >= length()) return default
        return try {
            getDouble(index)
        } catch (e: Exception) {
            default
        }
    }

    private fun JSArray.bool(index: Int, default: Boolean = false): Boolean {
        if (index >= length()) return default
        return try {
            getBoolean(index)
        } catch (e: Exception) {
            default
        }
    }

    companion object {
        fun describe(t: Throwable): String {
            if (t is QuickJSScriptException) {
                return t.message?.takeIf { it.isNotBlank() } ?: "JavaScript error"
            }
            val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            val cause = t.cause?.message?.takeIf { it.isNotBlank() && it != message }
            return if (cause != null) "$message ($cause)" else message
        }
    }
}
