package shop.voenix.auth

import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

internal object SessionCookieEncryption {
    fun transformer(
        sessionSecret: String,
        purpose: String,
    ): SessionTransportTransformerEncrypt {
        val encryptionKey = digest("$purpose:encryption:$sessionSecret").copyOf(16)
        val signingKey = digest("$purpose:signing:$sessionSecret")
        return SessionTransportTransformerEncrypt(
            encryptionKeySpec = SecretKeySpec(encryptionKey, "AES"),
            signKeySpec = SecretKeySpec(signingKey, "HmacSHA256"),
        )
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
