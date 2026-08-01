package shop.voenix.payment

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The rules a deployment's Mollie configuration has to satisfy, all of them enforced in the
 * constructor.
 *
 * That placement is the point: the application reads every module's settings before Flyway runs, so
 * a shop that cannot take payments never gets as far as migrating a database, let alone as far as
 * accepting an order it could never collect money for.
 */
internal class MollieSettingsTest {
    @Test
    fun `a deployment without an api key does not start`() {
        listOf("", "   ").forEach { key ->
            val failure = assertFailsWith<IllegalArgumentException> { settings(apiKey = key) }
            assertContains(failure.message.orEmpty(), "Mollie API key is required")
        }
    }

    @Test
    fun `a redirect URL that is not absolute does not start`() {
        listOf("", "/checkout/success", "voenix.test/success", "ftp://voenix.test").forEach { url ->
            val failure = assertFailsWith<IllegalArgumentException> { settings(redirectUrl = url) }
            assertContains(failure.message.orEmpty(), "redirect URL")
        }
    }

    /**
     * The webhook address carries the secret that authorizes the call, so plaintext would hand both
     * to anyone on the path. `http://` is refused even though it is an absolute URL.
     */
    @Test
    fun `a webhook URL that is not HTTPS does not start`() {
        listOf("", "http://voenix.test/hook", "/api/payments/webhook").forEach { url ->
            val failure = assertFailsWith<IllegalArgumentException> { settings(webhookUrl = url) }
            assertContains(failure.message.orEmpty(), "webhook URL")
        }
    }

    @Test
    fun `a missing or guessable webhook secret does not start`() {
        listOf("", "   ", "short").forEach { secret ->
            val failure =
                assertFailsWith<IllegalArgumentException> { settings(webhookSecret = secret) }
            assertContains(failure.message.orEmpty(), "webhook secret")
        }
    }

    @Test
    fun `an api URL that is not absolute does not start`() {
        assertFailsWith<IllegalArgumentException> { settings(apiUrl = "api.mollie.com") }
    }

    /** Both credentials are secrets, and a log is not a secret store. */
    @Test
    fun `neither credential is rendered`() {
        val rendered = settings().toString()

        assertFalse(rendered.contains("test_mollie_key"))
        assertFalse(rendered.contains("settings-test-webhook-secret"))
        assertContains(rendered, "[REDACTED]")
        assertContains(rendered, "https://api.mollie.com/v2/payments")
    }

    @Test
    fun `values are read from the Mollie block and trimmed`() {
        val settings =
            MollieSettings.from(
                MapApplicationConfig().apply {
                    put("Mollie.ApiKey", " test_mollie_key ")
                    put("Mollie.RedirectUrl", " https://voenix.test/checkout/success ")
                    put("Mollie.WebhookUrl", " https://voenix.test/api/payments/webhook/s ")
                    put("Mollie.WebhookSecret", " settings-test-webhook-secret ")
                }
            )

        assertEquals("test_mollie_key", settings.apiKey)
        assertEquals("https://voenix.test/checkout/success", settings.redirectUrl)
        assertEquals("https://voenix.test/api/payments/webhook/s", settings.webhookUrl)
        assertEquals("settings-test-webhook-secret", settings.webhookSecret)
        assertEquals("https://api.mollie.com/v2/payments", settings.apiUrl)
    }

    private fun settings(
        apiKey: String = "test_mollie_key",
        redirectUrl: String = "https://voenix.test/checkout/success",
        webhookUrl: String = "https://voenix.test/api/payments/webhook/secret",
        webhookSecret: String = "settings-test-webhook-secret",
        apiUrl: String = "https://api.mollie.com/v2/payments",
    ) = MollieSettings(apiKey, redirectUrl, webhookUrl, webhookSecret, apiUrl)
}
