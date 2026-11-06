package io.nebula.mobile.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One built patched APK. */
data class PatchRecord(
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Version code of the Discord build this patch was produced from. */
    val discordVersionCode: Long,
    val addonCount: Int,
    val themeTokens: Int,
    val createdAt: Long,
)

/** Small JSON-backed log of built patches, newest first. */
class PatchHistory(private val file: File) {

    private val lock = Any()

    fun read(): List<PatchRecord> = synchronized(lock) {
        val array = try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
        val records = ArrayList<PatchRecord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            records.add(
                PatchRecord(
                    file = item.optString("file"),
                    sha256 = item.optString("sha256"),
                    sizeBytes = item.optLong("size"),
                    discordVersionCode = item.optLong("discordVersionCode"),
                    addonCount = item.optInt("addons"),
                    themeTokens = item.optInt("themeTokens"),
                    createdAt = item.optLong("createdAt"),
                ),
            )
        }
        records
    }

    fun add(record: PatchRecord, limit: Int = 20): List<PatchRecord> = synchronized(lock) {
        val next = ArrayList<PatchRecord>()
        next.add(record)
        for (existing in read()) {
            if (existing.file == record.file && existing.sha256 == record.sha256) continue
            if (next.size >= limit) break
            next.add(existing)
        }
        val array = JSONArray()
        for (item in next) {
            array.put(
                JSONObject()
                    .put("file", item.file)
                    .put("sha256", item.sha256)
                    .put("size", item.sizeBytes)
                    .put("discordVersionCode", item.discordVersionCode)
                    .put("addons", item.addonCount)
                    .put("themeTokens", item.themeTokens)
                    .put("createdAt", item.createdAt),
            )
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            NebulaLog.error("history", "Unable to persist patch history: ${e.message}")
        }
        next
    }

    fun latest(): PatchRecord? = read().firstOrNull()

    fun clear() = synchronized(lock) {
        file.delete()
    }
}
