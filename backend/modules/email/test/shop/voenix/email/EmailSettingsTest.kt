package shop.voenix.email

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class EmailSettingsTest {
    @Test
    fun `defaults are disabled safe and poll every five minutes`() {
        val settings = EmailSettings.from(MapApplicationConfig())

        assertFalse(settings.enabled)
        assertEquals(5, settings.pollIntervalMinutes)
        assertContains(settings.toString(), "[REDACTED]")
    }

    @Test
    fun `enabled settings require credentials without exposing the key`() {
        val missingKey =
            assertFailsWith<IllegalArgumentException> {
                EmailSettings(enabled = true, fromEmail = "mail@voenix.shop")
            }
        assertFalse(missingKey.message.orEmpty().contains("secret-key"))

        val settings =
            EmailSettings(
                enabled = true,
                apiKey = "secret-key",
                fromEmail = "mail@voenix.shop",
            )
        assertFalse(settings.toString().contains("secret-key"))
    }

    @Test
    fun `send url defaults to sweego and is never read from configuration`() {
        val configWithUrl = MapApplicationConfig("Email.SendUrl" to "http://localhost:1/send")

        assertEquals("https://api.sweego.io/send", EmailSettings.from(configWithUrl).sendUrl)
        assertEquals(
            "http://localhost:8089/send",
            EmailSettings(sendUrl = "http://localhost:8089/send").sendUrl,
        )
        assertFailsWith<IllegalArgumentException> { EmailSettings(sendUrl = "not-a-url") }
    }

    @Test
    fun `poll interval is bounded`() {
        assertFailsWith<IllegalArgumentException> { EmailSettings(pollIntervalMinutes = 0) }
        assertFailsWith<IllegalArgumentException> { EmailSettings(pollIntervalMinutes = 1_441) }
        assertEquals(1, EmailSettings(pollIntervalMinutes = 1).pollIntervalMinutes)
        assertEquals(1_440, EmailSettings(pollIntervalMinutes = 1_440).pollIntervalMinutes)
    }
}
