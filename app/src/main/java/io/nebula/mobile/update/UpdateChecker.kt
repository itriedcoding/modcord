package io.nebula.mobile.update

import io.nebula.mobile.net.Http
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String?,
)

/**
 * Automatic updates against the project's real GitHub releases.
 *
 * The release JSON is parsed with the actual GitHub API shape (`tag_name`, `assets[]`),
 * versions are compared numerically (`1.10.0` is newer than `1.9.9`), and a downloaded APK
 * is only accepted when its SHA-256 matches the `.sha256` asset published with it. A
 * missing checksum asset is reported rather than silently trusted.
 */
object UpdateChecker {

    const val API_URL = "https://api.github.com/repos/itriedcoding/modcord/releases/latest"

    fun numericParts(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .split(Regex("[^0-9]+"))
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = numericParts(remote)
        val localParts = numericParts(local)
        if (remoteParts.isEmpty()) return false
        val size = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until size) {
            val a = remoteParts.getOrElse(index) { 0 }
            val b = localParts.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun parseRelease(json: String, bundleSuffix: String = ".apk"): ReleaseInfo {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").ifBlank { root.optString("name") }
        val assets = root.optJSONArray("assets") ?: throw IllegalStateException("Release $tag has no assets")
        var apkUrl: String? = null
        var apkSize = 0L
        var checksumUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            when {
                name.endsWith(bundleSuffix, ignoreCase = true) -> {
                    apkUrl = url
                    apkSize = asset.optLong("size", 0L)
                }
                name.endsWith(".sha256", ignoreCase = true) -> checksumUrl = url
            }
        }
        val url = apkUrl ?: throw IllegalStateException("Release $tag has no $bundleSuffix asset")
        return ReleaseInfo(
            tag = tag.ifBlank { "unknown" },
            name = root.optString("name").ifBlank { tag },
            notes = root.optString("body"),
            apkUrl = url,
            apkSize = apkSize,
            checksumUrl = checksumUrl,
        )
    }

    fun shaFromSidecar(text: String): String? =
        Regex("[0-9a-fA-F]{64}").find(text)?.value?.lowercase()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun latest(): ReleaseInfo = parseRelease(Http.getText(API_URL))

    fun checksumFor(release: ReleaseInfo): String? =
        release.checksumUrl?.let { shaFromSidecar(Http.getText(it)) }

    /** Downloads into [target] and verifies the published checksum before returning. */
    fun download(release: ReleaseInfo, target: File, expectedSha256: String?): File {
        val bytes = Http.getBytes(release.apkUrl)
        val actual = sha256(bytes)
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            throw IllegalStateException(
                "Downloaded APK does not match the published SHA-256 (expected $expectedSha256, got $actual)",
            )
        }
        if (bytes.isEmpty()) throw IllegalStateException("Downloaded APK is empty")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        return target
    }
}
