package io.nebula.mobile.patch

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** The key material a patched app is signed with. */
data class ApkIdentity(
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
)

data class SignatureReport(
    val verified: Boolean,
    val schemeV1: Boolean,
    val schemeV2: Boolean,
    val schemeV3: Boolean,
    val signerCount: Int,
    val problems: List<String>,
)

/**
 * Real APK signatures, produced with the same `apksig` implementation Android Studio and
 * `apksigner` use. A patched app that is not correctly signed is not installable, so this
 * is verified again after signing instead of being assumed to have worked.
 */
object ApkSigning {

    fun sign(input: File, output: File, identity: ApkIdentity, minSdk: Int) {
        if (identity.certificates.isEmpty()) {
            throw PatchException("Signing identity has no certificate")
        }
        val config = ApkSigner.SignerConfig.Builder(
            "nebula",
            identity.privateKey,
            identity.certificates,
        ).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            // Keep the alignment the rewriter produced. Without this apksig re-pads stored
            // entries of its own accord and moves page-aligned native libraries off 4096.
            .setAlignmentPreserved(true)
            .setLibraryPageAlignmentBytes(4096)
            .setMinSdkVersion(minSdk)
            .setCreatedBy("Nebula Android patcher")
            .build()
            .sign()
    }

    /**
     * Verifies every signature scheme the APK carries. The checked platform version is
     * deliberately the oldest Android release: at 24 and above apksig skips the v1 (JAR)
     * scheme, which would report a present v1 signature as absent.
     */
    fun verify(apk: File, checkedPlatformVersion: Int = OLDEST_CHECKED_PLATFORM): SignatureReport {
        val result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(checkedPlatformVersion)
            .build()
            .verify()
        val problems = ArrayList<String>()
        result.errors.forEach { problems.add(it.toString()) }
        return SignatureReport(
            verified = result.isVerified,
            schemeV1 = result.isVerifiedUsingV1Scheme,
            schemeV2 = result.isVerifiedUsingV2Scheme,
            schemeV3 = result.isVerifiedUsingV3Scheme,
            signerCount = result.signerCertificates?.size ?: 0,
            problems = problems,
        )
    }

    const val OLDEST_CHECKED_PLATFORM = 21

    /** Loads a key pair from a JKS/PKCS12 keystore. */
    fun identityFromKeystore(
        keystore: File,
        storePassword: CharArray,
        alias: String,
        keyPassword: CharArray,
    ): ApkIdentity {
        if (!keystore.exists()) throw PatchException("Keystore not found: ${keystore.absolutePath}")
        val store = KeyStore.getInstance(keystore.extension?.lowercase()?.let { if (it == "p12" || it == "pfx") "PKCS12" else "JKS" } ?: "JKS")
        keystore.inputStream().use { store.load(it, storePassword) }
        val key = store.getKey(alias, keyPassword) as? PrivateKey
            ?: throw PatchException("No private key '$alias' in ${keystore.name}")
        val chain = store.getCertificateChain(alias)
            ?.filterIsInstance<X509Certificate>()
            .orEmpty()
        if (chain.isEmpty()) throw PatchException("No certificate chain for '$alias' in ${keystore.name}")
        return ApkIdentity(key, chain)
    }
}
