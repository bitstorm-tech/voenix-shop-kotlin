package shop.voenix.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 12_000
    private const val KEY_LENGTH_BITS = 256
    private const val FORMAT = "pbkdf2_sha256"
    private val secureRandom = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val hash = pbkdf2(password, salt, ITERATIONS)

        return listOf(
            FORMAT,
            ITERATIONS.toString(),
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hash),
        ).joinToString("$")
    }

    fun verify(
        password: String,
        storedHash: String,
    ): Boolean {
        val parts = storedHash.split("$")
        if (parts.size != 4 || parts[0] != FORMAT) {
            return false
        }

        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        val actual = pbkdf2(password, salt, iterations)

        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).encoded
    }
}
