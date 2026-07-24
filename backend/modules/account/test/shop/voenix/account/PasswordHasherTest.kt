package shop.voenix.account

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class PasswordHasherTest {
    private val hasher = PasswordHasher(iterations = 1_000)

    @Test
    fun `hash and verify roundtrip accepts the right password and rejects a wrong one`() {
        val encoded = hasher.hash("correct horse battery")

        assertTrue(hasher.verify("correct horse battery", encoded))
        assertFalse(hasher.verify("wrong horse battery", encoded))
        assertFalse(hasher.verify("", encoded))
    }

    @Test
    fun `the same password hashes to different encodings because of the random salt`() {
        assertNotEquals(hasher.hash("secret-password"), hasher.hash("secret-password"))
    }

    @Test
    fun `the iteration count is read from the encoding not from the configuration`() {
        val encoded = hasher.hash("secret-password")
        assertContains(encoded, "\$1000\$")

        val differentlyConfigured = PasswordHasher(iterations = 7)
        assertTrue(differentlyConfigured.verify("secret-password", encoded))
    }

    @Test
    fun `tampered encodings are rejected`() {
        val encoded = hasher.hash("secret-password")
        val index = encoded.length - 10
        val tampered =
            encoded.substring(0, index) +
                (if (encoded[index] == 'A') 'B' else 'A') +
                encoded.substring(index + 1)

        assertFalse(hasher.verify("secret-password", tampered))
    }

    @Test
    fun `unknown versions and malformed encodings are rejected`() {
        val encoded = hasher.hash("secret-password")

        assertFalse(hasher.verify("secret-password", encoded.replaceFirst("v1", "v2")))
        assertFalse(hasher.verify("secret-password", ""))
        assertFalse(hasher.verify("secret-password", "v1"))
        assertFalse(hasher.verify("secret-password", "v1\$abc\$def\$ghi"))
        assertFalse(hasher.verify("secret-password", "v1\$0\$AAAA\$AAAA"))
        assertFalse(hasher.verify("secret-password", "v1\$1000\$not-base64!\$AAAA"))
        assertFalse(hasher.verify("secret-password", "v1\$1000\$AAAA\$AAAA"))
    }
}
