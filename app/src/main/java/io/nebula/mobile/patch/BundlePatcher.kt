package io.nebula.mobile.patch

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One addon baked into the patched application. */
data class PatchAddon(
    val id: String,
    val name: String,
    val version: String,
    /** `plugin` or `theme`; the loader treats them differently. */
    val kind: String,
    val source: String,
)

/**
 * Locates Discord's React Native bundle inside an APK and injects the Nebula loader.
 *
 * The bundle is the component that actually renders Discord: patching it is what makes
 * this a client modification rather than a separate app. Three outcomes are possible and
 * all three are reported honestly:
 *
 *  * plain UTF-8 JavaScript — injected;
 *  * Hermes bytecode — refused, because bytecode cannot be edited as text;
 *  * no bundle at all — refused with the asset list that was searched.
 */
object BundlePatcher {

    const val LOADER_BEGIN = "/*nebula-loader:begin*/"
    const val LOADER_END = "/*nebula-loader:end*/"

    private val PREFERRED = listOf(
        "assets/index.android.bundle",
        "assets/index.android.bundle.js",
    )

    fun findBundle(names: Collection<String>): String? {
        PREFERRED.firstOrNull { it in names }?.let { return it }
        return names
            .filter { it.startsWith("assets/") && (it.endsWith(".bundle") || it.endsWith(".bundle.js")) }
            .minByOrNull { it.length }
    }

    /** Hermes bytecode starts with the magic `C6 1F BC ...`; it is not JavaScript text. */
    fun isHermes(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xC6.toByte() &&
            bytes[1] == 0x1F.toByte() &&
            bytes[2] == 0xBC.toByte()

    fun isPlainJavaScript(bytes: ByteArray): Boolean {
        if (isHermes(bytes)) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun payloadJson(addons: List<PatchAddon>, nebulaVersion: String): String {
        val built = StringBuilder()
        built.append("{\"nebula\":1,\"version\":").append(json(nebulaVersion))
        built.append(",\"addons\":[")
        addons.forEachIndexed { index, addon ->
            if (index > 0) built.append(',')
            built.append('{')
            built.append("\"id\":").append(json(addon.id)).append(',')
            built.append("\"name\":").append(json(addon.name)).append(',')
            built.append("\"version\":").append(json(addon.version)).append(',')
            built.append("\"kind\":").append(json(addon.kind)).append(',')
            built.append("\"source\":").append(json(addon.source))
            built.append('}')
        }
        built.append("]}")
        return built.toString()
    }

    /** Appends the payload and loader so that a loader failure can never break the bundle. */
    fun inject(bundle: String, loaderJs: String, payload: String): String {
        val out = StringBuilder(bundle.length + loaderJs.length + payload.length + 512)
        out.append(bundle)
        if (!bundle.endsWith("\n")) out.append('\n')
        out.append(LOADER_BEGIN).append('\n')
        out.append(";(function(){try{\n")
        out.append("globalThis.__nebula_payload=").append(payload).append(";\n")
        out.append(loaderJs)
        out.append("\n}catch(e){try{console.error('[Nebula] loader failed',e)}catch(_){}}})();\n")
        out.append(LOADER_END).append('\n')
        return out.toString()
    }

    fun json(value: String): String {
        val out = StringBuilder(value.length + 16)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                else -> if (ch < ' ') {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}
