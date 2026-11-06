package io.nebula.mobile.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporting without a third-party service.
 *
 * Firebase Crashlytics needs a Firebase project and a `google-services.json` generated in
 * that project, so it cannot be wired up from here. What this does instead is real: it
 * installs an uncaught-exception handler that writes the full stack trace to the app's own
 * storage, then delegates to the previous handler so Android still shows its crash dialog.
 * The Console tab and Settings screen read these reports back.
 */
object CrashReporter {

    private const val MAX_REPORTS = 20

    @Volatile
    private var directory: File? = null

    fun install(directory: File) {
        this.directory = directory
        directory.mkdirs()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(thread, error)
            } catch (ignored: Throwable) {
                // Reporting must never mask the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
        NebulaLog.info("crash", "Crash handler installed at ${directory.absolutePath}")
    }

    fun write(thread: Thread, error: Throwable): File? {
        val dir = directory ?: return null
        dir.mkdirs()
        val stamp = System.currentTimeMillis()
        val target = File(dir, "crash-$stamp.txt")
        return try {
            target.writeText(format(thread.name, error, stamp))
            prune(dir)
            NebulaLog.error("crash", "Crash report saved: ${target.name}")
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Pure formatter, so the report layout is unit-testable without a device. */
    fun format(threadName: String, error: Throwable, whenMillis: Long): String {
        val writer = StringWriter()
        PrintWriter(writer).use { out ->
            out.println("Nebula crash report")
            out.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(whenMillis)))
            out.println("thread: $threadName")
            out.println("exception: ${error.javaClass.name}: ${error.message ?: "(no message)"}")
            out.println()
            error.printStackTrace(out)
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 5) {
                out.println()
                out.println("caused by: ${cause.javaClass.name}: ${cause.message ?: "(no message)"}")
                cause.printStackTrace(out)
                cause = cause.cause
                depth++
            }
        }
        return writer.toString()
    }

    fun reports(): List<File> {
        val dir = directory ?: return emptyList()
        return (dir.listFiles { file -> file.isFile && file.name.startsWith("crash-") } ?: emptyArray())
            .sortedByDescending { it.name }
    }

    fun latestText(): String? = reports().firstOrNull()?.let { file ->
        try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun clear(): Int {
        val reports = reports()
        var removed = 0
        for (report in reports) if (report.delete()) removed++
        return removed
    }

    private fun prune(dir: File) {
        val all = (dir.listFiles { file -> file.isFile && file.name.startsWith("crash-") } ?: return)
            .sortedByDescending { it.name }
        for (stale in all.drop(MAX_REPORTS)) stale.delete()
    }
}
