package io.nebula.mobile.core

import java.security.MessageDigest

/** SHA-256 helpers shared by update verification and the marketplace. */
object Hashes {

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
