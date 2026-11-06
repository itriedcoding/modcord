package io.nebula.mobile.net

import io.nebula.mobile.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP GET used by the update checker and the marketplace client. */
object Http {

    fun getText(url: String): String = getBytes(url).toString(Charsets.UTF_8)

    fun getBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nebula-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.trim()
                    ?.take(200)
                    ?.takeIf { it.isNotEmpty() }
                throw IllegalStateException("HTTP $code" + if (detail != null) ": $detail" else "")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
