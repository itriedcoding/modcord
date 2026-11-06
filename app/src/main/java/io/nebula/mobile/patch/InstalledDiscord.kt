package io.nebula.mobile.patch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException

/** A Discord installation discovered on this device. */
data class InstalledDiscord(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    /** Absolute path of the installed base APK, e.g. /data/app/.../base.apk */
    val apkPath: String,
    val splitPaths: List<String>,
) {
    val isSplitInstall: Boolean get() = splitPaths.isNotEmpty()

    fun summary(): String = buildString {
        append("$label $versionName ($versionCode)")
        append(" · ")
        append(if (isSplitInstall) "${splitPaths.size + 1} APK splits" else "single APK")
    }
}

/**
 * Finds Discord through the platform package manager.
 *
 * On Android 11+ a package is invisible to [PackageManager] unless the app declares it
 * in `<queries>`, which is why the manifest lists these package names explicitly.
 */
object DiscordLocator {

    /** Stable, then the known Discord forks that ship the same bundle layout. */
    val KNOWN_PACKAGES = listOf("com.discord", "com.discord.alpha", "com.discord.beta")

    @Suppress("DEPRECATION")
    fun find(context: Context): List<InstalledDiscord> {
        val manager = context.packageManager
        val found = ArrayList<InstalledDiscord>()
        for (packageName in KNOWN_PACKAGES) {
            val info: PackageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    manager.getPackageInfo(packageName, 0)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            val application = info.applicationInfo ?: continue
            val label = try {
                manager.getApplicationLabel(application).toString()
            } catch (e: Exception) {
                packageName
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            found.add(
                InstalledDiscord(
                    packageName = packageName,
                    label = label,
                    versionName = info.versionName ?: "unknown",
                    versionCode = versionCode,
                    apkPath = application.sourceDir,
                    splitPaths = application.splitSourceDirs?.toList().orEmpty(),
                ),
            )
        }
        return found
    }
}

/**
 * Copies an installed APK where the patcher can work on it.
 *
 * Reading another app's APK is allowed when the APK file is world readable, which it
 * normally is, but some Android builds and some devices refuse. When that happens this
 * reports the exact path and reason instead of failing vaguely.
 */
object ApkSource {

    fun copy(sourcePath: String, target: File): File {
        val source = File(sourcePath)
        if (!source.exists()) {
            throw PatchException(
                "The installed APK is not visible at $sourcePath. " +
                    "Pick the APK file instead and choose it from storage.",
            )
        }
        if (!source.canRead()) {
            throw PatchException(
                "Android does not allow this app to read $sourcePath (permission denied). " +
                    "Pick the APK file instead and choose it from storage.",
            )
        }
        target.parentFile?.mkdirs()
        val length = try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length()
        } catch (e: SecurityException) {
            throw PatchException("Reading $sourcePath was refused by Android: ${e.message}", e)
        } catch (e: IOException) {
            throw PatchException("Reading $sourcePath failed: ${e.message}", e)
        }
        if (length < 1024) {
            throw PatchException("The copied APK is only $length bytes, which cannot be a real APK")
        }
        return target
    }
}
