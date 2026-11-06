package io.nebula.mobile.market

import io.nebula.mobile.core.AddonStore
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.Hashes
import io.nebula.mobile.net.Http
import org.json.JSONObject

data class MarketplaceEntry(
    val id: String,
    val name: String,
    val type: AddonType,
    val version: String,
    val author: String,
    val description: String,
    val url: String,
    val sha256: String,
)

/**
 * Plugin marketplace backed by the catalogue committed to this repository.
 *
 * `marketplace/index.json` is served from GitHub's raw host and lists every addon with its
 * SHA-256. An entry is only installed when the downloaded bytes match that checksum, so a
 * tampered or truncated download is refused with an explanation instead of being written
 * into the addon store.
 */
object Marketplace {

    const val RAW_BASE = "https://raw.githubusercontent.com/itriedcoding/modcord/main/"
    const val INDEX_URL = RAW_BASE + "marketplace/index.json"

    fun parseIndex(json: String, baseUrl: String = RAW_BASE): List<MarketplaceEntry> {
        val root = JSONObject(json)
        val array = root.optJSONArray("addons")
            ?: throw IllegalStateException("Marketplace index is missing its addons array")
        val entries = ArrayList<MarketplaceEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val path = item.optString("path").trim()
            val url = item.optString("url").trim().ifBlank { if (path.isBlank()) "" else baseUrl + path }
            val sha = item.optString("sha256").trim().lowercase()
            if (name.isBlank() || url.isBlank()) continue
            require(Regex("^[0-9a-f]{64}$").matches(sha)) { "Marketplace entry \"$name\" has no valid sha256" }
            entries.add(
                MarketplaceEntry(
                    id = AddonStore.slug(name),
                    name = name,
                    type = if (item.optString("type") == AddonType.THEME.dirName) AddonType.THEME else AddonType.PLUGIN,
                    version = item.optString("version", "0.0.0"),
                    author = item.optString("author", "Unknown"),
                    description = item.optString("description"),
                    url = url,
                    sha256 = sha,
                ),
            )
        }
        return entries
    }

    fun verify(entry: MarketplaceEntry, bytes: ByteArray): Boolean =
        Hashes.sha256(bytes).equals(entry.sha256, ignoreCase = true)

    fun fetchIndex(url: String = INDEX_URL): List<MarketplaceEntry> = parseIndex(Http.getText(url))

    fun downloadSource(entry: MarketplaceEntry): String {
        val bytes = Http.getBytes(entry.url)
        if (!verify(entry, bytes)) {
            throw IllegalStateException("${entry.name} failed its SHA-256 check; nothing was installed")
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
