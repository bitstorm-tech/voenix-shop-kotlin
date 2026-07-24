package shop.voenix.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 password hashing with a versioned encoding (`v1$<iterations>$<salt>$<hash>`,
 * both parts Base64). Verification reads the iteration count from the encoding, so stored hashes
 * stay valid when the configured work factor changes and a future algorithm change only needs a new
 * version prefix.
 */
internal class PasswordHasher(private val iterations: Int) {
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val hash = deriveKey(password, salt, iterations)
        return listOf(
                VERSION,
                iterations.toString(),
                base64.encodeToString(salt),
                base64.encodeToString(hash),
            )
            .joinToString(SEPARATOR)
    }

    fun verify(password: String, encoded: String): Boolean {
        val parsed = parse(encoded) ?: return false
        return MessageDigest.isEqual(
            parsed.hash,
            deriveKey(password, parsed.salt, parsed.iterations),
        )
    }

    private class ParsedEncoding(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(encoded: String): ParsedEncoding? {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != ENCODED_PART_COUNT || parts.first() != VERSION) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 }
        val salt = decodeBase64(parts[2])
        val hash = decodeBase64(parts.last())?.takeIf { it.size == HASH_BYTES }
        return if (iterations == null || salt == null || hash == null) {
            null
        } else {
            ParsedEncoding(iterations, salt, hash)
        }
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val VERSION = "v1"
        const val SEPARATOR = "$"
        const val ENCODED_PART_COUNT = 4
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32

        val secureRandom = SecureRandom()
        val base64: Base64.Encoder = Base64.getEncoder().withoutPadding()
    }
}
