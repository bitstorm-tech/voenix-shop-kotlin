package shop.voenix.email

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class EmailValueTest {
    @Test
    fun `recipient is trimmed and rejects unsafe shapes`() {
        assertEquals("customer@example.com", EmailRecipient(" customer@example.com ").value)
        listOf("", "a", "a@@example.com", "@example.com", "a@", "a @example.com").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { EmailRecipient(value) }
        }
    }

    @Test
    fun `action URL accepts local http but rejects credentials and unsafe schemes`() {
        val url = EmailActionUrl("http://localhost:5173/confirm?token=secret")

        assertFalse(url.toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> { EmailActionUrl("mailto:user@example.com") }
        assertFailsWith<IllegalArgumentException> {
            EmailActionUrl("https://user:pass@example.com/x")
        }
        assertFailsWith<IllegalArgumentException> { EmailActionUrl("/relative") }
    }
}
