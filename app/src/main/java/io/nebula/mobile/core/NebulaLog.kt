package io.nebula.mobile.core

import java.util.concurrent.CopyOnWriteArrayList

/** Small in-process log buffer shared by the plugin runtime, theme engine and UI. */
object NebulaLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val scope: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 1000
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    /** Optional platform sink (logcat) installed by the application class. */
    @Volatile
    var sink: ((Entry) -> Unit)? = null

    fun debug(scope: String, message: String) = add(Level.DEBUG, scope, message)
    fun info(scope: String, message: String) = add(Level.INFO, scope, message)
    fun warn(scope: String, message: String) = add(Level.WARN, scope, message)
    fun error(scope: String, message: String) = add(Level.ERROR, scope, message)

    fun add(level: Level, scope: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, scope, message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        sink?.invoke(entry)
        listeners.forEach { listener ->
            try {
                listener(entry)
            } catch (e: Exception) {
                // A broken listener must never take down the logger.
            }
        }
    }

    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    fun addListener(listener: (Entry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Entry) -> Unit) {
        listeners.remove(listener)
    }
}
