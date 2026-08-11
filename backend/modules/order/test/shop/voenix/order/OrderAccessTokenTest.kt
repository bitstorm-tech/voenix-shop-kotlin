package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three promises of [OrderAccessToken]: it is unguessable, it never prints itself, and a string
 * that is not shaped like one is simply not a token.
 *
 * The redaction test is the one that matters most. The token is a bearer credential for a whole
 * order, and the moment it reaches a log line — through a data-class `toString`, an exception
 * message, or a stray trace — the link in the customer's mail is compromised for everyone with
 * access to that log.
 */
internal class OrderAccessTokenTest {
    @Test
    fun `a generated token is 43 URL-safe characters and never repeats`() {
        val tokens = List(100) { OrderAccessToken.generate() }

        tokens.forEach { token ->
            assertEquals(43, token.value.length, "32 bytes are 43 unpadded Base64 characters")
            assertTrue(
                token.value.all { character -> character.isLetterOrDigit() || character in "-_" },
                "A token has to survive a URL path unescaped: ${token.value.length} characters",
            )
        }
        assertEquals(
            tokens.size,
            tokens.mapTo(mutableSetOf(), OrderAccessToken::value).size,
            "Two generated tokens must never be equal",
        )
    }

    @Test
    fun `toString redacts the token, and never quotes it in any form`() {
        val token = OrderAccessToken.generate()

        assertEquals("OrderAccessToken([REDACTED])", token.toString())
        assertTrue(
            !token.toString().contains(token.value),
            "The value must not appear in the redacted form",
        )
        // The mail ticket puts the token into a data class, whose generated toString calls this
        // one.
        assertTrue(
            !listOf(token).toString().contains(token.value),
            "A container's toString must not leak it either",
        )
    }

    @Test
    fun `a string that is not shaped like a token is not one`() {
        val valid = OrderAccessToken.generate().value

        assertEquals(valid, assertNotNull(OrderAccessToken(valid)).value)
        assertEquals(OrderAccessToken(valid), OrderAccessToken(valid))
        assertNotEquals(OrderAccessToken(valid), OrderAccessToken.generate())

        listOf(
                "",
                " ",
                valid.dropLast(1),
                valid + "A",
                // Base64 with padding, and the two characters the URL-safe alphabet replaced.
                valid.dropLast(1) + "=",
                valid.dropLast(1) + "+",
                valid.dropLast(1) + "/",
                valid.dropLast(1) + " ",
                "../../etc/passwd",
                "a".repeat(43) + "\n",
            )
            .forEach { garbage ->
                assertNull(OrderAccessToken(garbage), "'$garbage' must not become a token")
            }
    }
}
