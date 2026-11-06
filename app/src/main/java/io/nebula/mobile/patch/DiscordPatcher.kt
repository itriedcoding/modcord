package io.nebula.mobile.patch

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class PatchRequest(
    val apk: File,
    val outputDirectory: File,
    val addons: List<PatchAddon>,
    val loaderJs: String,
    val identity: ApkIdentity,
    val nebulaVersion: String,
    val minSdk: Int = 24,
)

data class PatchReport(
    val output: File,
    val sha256: String,
    val sizeBytes: Long,
    val bundleEntry: String,
    val bundleBytesBefore: Int,
    val bundleBytesAfter: Int,
    val addonCount: Int,
    val rewrite: RewriteReport,
    val signature: SignatureReport,
    val signerSubject: String,
)

data class PatchProbe(
    val bundleEntry: String?,
    val hermes: Boolean,
    val bundleSize: Long,
    val assetEntries: List<String>,
)

/**
 * Produces a modified build of the real Discord application.
 *
 * The work is genuinely destructive-and-rebuild: the React Native bundle that renders
 * Discord is replaced with a version carrying the Nebula loader and the chosen addons,
 * the container is rewritten with Android's alignment rules, and the result is signed
 * and then verified. Anything that cannot be done correctly is refused with a reason
 * rather than emitted as a broken APK.
 */
object DiscordPatcher {

    private const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024

    fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        if (name == "META-INF/MANIFEST.MF") return true
        return listOf(".SF", ".RSA", ".DSA", ".EC").any { name.endsWith(it) }
    }

    fun probe(apk: File): PatchProbe {
        if (!apk.exists()) throw PatchException("APK not found: ${apk.absolutePath}")
        ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val entry = BundlePatcher.findBundle(names)
                ?: return PatchProbe(null, false, 0L, names.filter { it.startsWith("assets/") }.take(25))
            val zipEntry = zip.getEntry(entry)
            val head = zip.getInputStream(zipEntry).use { input ->
                val buffer = ByteArray(16)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
            return PatchProbe(
                bundleEntry = entry,
                hermes = BundlePatcher.isHermes(head),
                bundleSize = zipEntry.size,
                assetEntries = names.filter { it.startsWith("assets/") }.take(25),
            )
        }
    }

    fun patch(request: PatchRequest, log: (String) -> Unit = {}): PatchReport {
        val apk = request.apk
        if (!apk.exists()) throw PatchException("APK not found: ${apk.absolutePath}")
        if (apk.length() < 1024) throw PatchException("${apk.name} is too small to be an APK")
        request.outputDirectory.mkdirs()

        // The rewrite stages each entry and then signs a second copy, so a patch needs
        // roughly three times the APK size. Discord installs are large; failing here with
        // a number beats failing halfway through with a truncated file.
        val needed = apk.length() * 3
        val usable = request.outputDirectory.usableSpace
        if (usable > 0 && usable < needed) {
            throw PatchException(
                "Not enough free space to patch: about ${needed / (1024 * 1024)} MB needed, " +
                    "${usable / (1024 * 1024)} MB available in ${request.outputDirectory.absolutePath}",
            )
        }

        val bundleEntry: String
        val bundleBytes: ByteArray
        val names: List<String>
        ZipFile(apk).use { zip ->
            names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            bundleEntry = BundlePatcher.findBundle(names)
                ?: throw PatchException(
                    "This APK contains no React Native bundle, so there is nothing to modify. " +
                        "Looked for assets/index.android.bundle. First assets found: " +
                        names.filter { it.startsWith("assets/") }.take(8).joinToString(", ").ifEmpty { "(none)" },
                )
            val zipEntry = zip.getEntry(bundleEntry)
            if (zipEntry.size > MAX_BUNDLE_BYTES) {
                throw PatchException("Bundle is unexpectedly large (${zipEntry.size} bytes); refusing to patch")
            }
            bundleBytes = zip.getInputStream(zipEntry).use { it.readBytes() }
        }
        log("Bundle: $bundleEntry (${bundleBytes.size} bytes, ${names.size} entries)")

        if (BundlePatcher.isHermes(bundleBytes)) {
            throw PatchException(
                "This build ships Hermes bytecode ($bundleEntry starts with C6 1F BC), not JavaScript text. " +
                    "Bytecode cannot be edited as text, and faking a patch would produce an app that crashes. " +
                    "Patching requires a build that ships a plain JavaScript bundle.",
            )
        }
        if (!BundlePatcher.isPlainJavaScript(bundleBytes)) {
            throw PatchException(
                "$bundleEntry is neither Hermes bytecode nor UTF-8 JavaScript; refusing to inject into an unknown format.",
            )
        }

        val original = bundleBytes.toString(Charsets.UTF_8)
        val payload = BundlePatcher.payloadJson(request.addons, request.nebulaVersion)
        val patched = BundlePatcher.inject(original, request.loaderJs, payload)
        if (!patched.contains(BundlePatcher.LOADER_END)) {
            throw PatchException("Loader injection produced an unexpected bundle; aborting")
        }
        log("Injected ${patched.length - original.length} bytes of loader and ${request.addons.size} addon(s)")

        val rewritten = File(request.outputDirectory, "rewrite-${System.nanoTime()}.apk")
        val output = File(request.outputDirectory, outputName(apk))
        val report: RewriteReport
        try {
            report = ZipFile(apk).use { zip ->
                ApkRewriter(zip).rewrite(
                    target = rewritten,
                    replacements = mapOf(bundleEntry to patched.toByteArray(Charsets.UTF_8)),
                    drop = { name -> isSignatureFile(name) },
                )
            }
            log("Rebuilt container: ${report.entryCount} entries, ${report.alignedEntries} aligned, ${report.droppedEntries.size} old signature file(s) dropped")

            if (output.exists() && !output.delete()) throw PatchException("Cannot replace ${output.name}")
            ApkSigning.sign(rewritten, output, request.identity, request.minSdk)
            log("Signed ${output.name} (${output.length()} bytes)")
        } finally {
            rewritten.delete()
        }

        val signature = ApkSigning.verify(output)
        if (!signature.verified) {
            throw PatchException(
                "Signing produced an APK that does not verify: " +
                    signature.problems.joinToString("; ").ifEmpty { "no signer accepted" },
            )
        }

        return PatchReport(
            output = output,
            sha256 = sha256(output),
            sizeBytes = output.length(),
            bundleEntry = bundleEntry,
            bundleBytesBefore = bundleBytes.size,
            bundleBytesAfter = patched.toByteArray(Charsets.UTF_8).size,
            addonCount = request.addons.size,
            rewrite = report,
            signature = signature,
            signerSubject = request.identity.certificates.first().subjectX500Principal.name,
        )
    }

    fun outputName(apk: File): String {
        val base = apk.nameWithoutExtension.ifBlank { "discord" }
        return "nebula-$base-patched.apk"
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
