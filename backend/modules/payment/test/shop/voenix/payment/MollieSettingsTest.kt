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

    /**
     * The route answers on `/api/payments/webhook/<secret>` and nowhere else, so a webhook URL
     * ending in anything else describes a deployment whose every real callback gets a `403` — and
     * nobody would notice, because Mollie retries a `403` in silence instead of complaining.
     */
    @Test
    fun `a webhook URL that does not end in the secret does not start`() {
        listOf(
                "https://voenix.test/api/payments/webhook",
                "https://voenix.test/api/payments/webhook/",
                "https://voenix.test/api/payments/webhook/other-webhook-secret",
                "https://voenix.test/api/payments/webhook/settings-test-webhook-secret/x",
                "https://voenix.test/api/payments/webhook/settings-test-webhook-secre",
            )
            .forEach { url ->
                val failure =
                    assertFailsWith<IllegalArgumentException> { settings(webhookUrl = url) }
                assertContains(failure.message.orEmpty(), "webhook URL must end in the webhook")
            }
    }

    /** A secret that has to travel percent-encoded in the address is still that address's end. */
    @Test
    fun `a percent-encoded secret segment is the secret`() {
        val settings =
            settings(
                webhookUrl = "https://voenix.test/api/payments/webhook/secret%20with%20spaces",
                webhookSecret = "secret with spaces",
            )

        assertEquals("secret with spaces", settings.webhookSecret)
    }

    /**
     * Both credentials are secrets, and a log is not a secret store. The webhook URL is the harder
     * half: the secret *is* its last segment, so the whole path has to go.
     */
    @Test
    fun `neither credential is rendered, and the webhook URL loses its path with them`() {
        val rendered = settings().toString()

        assertFalse(rendered.contains("test_mollie_key"))
        assertFalse(
            rendered.contains(SECRET),
            "the webhook URL carries the secret in its path: $rendered",
        )
        assertFalse(rendered.contains("/api/payments"))
        assertContains(rendered, "[REDACTED]")
        assertContains(rendered, "webhookUrl=https://voenix.test [path redacted]")
        assertContains(rendered, "https://api.mollie.com/v2/payments")
    }

    @Test
    fun `values are read from the Mollie block and trimmed`() {
        val settings =
            MollieSettings.from(
                MapApplicationConfig().apply {
                    put("Mollie.ApiKey", " test_mollie_key ")
                    put("Mollie.RedirectUrl", " https://voenix.test/checkout/success ")
                    put("Mollie.WebhookUrl", " https://voenix.test/api/payments/webhook/$SECRET ")
                    put("Mollie.WebhookSecret", " $SECRET ")
                }
            )

        assertEquals("test_mollie_key", settings.apiKey)
        assertEquals("https://voenix.test/checkout/success", settings.redirectUrl)
        assertEquals("https://voenix.test/api/payments/webhook/$SECRET", settings.webhookUrl)
        assertEquals(SECRET, settings.webhookSecret)
        assertEquals("https://api.mollie.com/v2/payments", settings.apiUrl)
    }

    private fun settings(
        apiKey: String = "test_mollie_key",
        redirectUrl: String = "https://voenix.test/checkout/success",
        webhookUrl: String = "https://voenix.test/api/payments/webhook/$SECRET",
        webhookSecret: String = SECRET,
        apiUrl: String = "https://api.mollie.com/v2/payments",
    ) = MollieSettings(apiKey, redirectUrl, webhookUrl, webhookSecret, apiUrl)

    private companion object {
        /**
         * The fixture's secret is what the fixture's webhook URL ends in, exactly as a deployment
         * has to configure it — otherwise `toString` could leak it and no test would notice.
         */
        const val SECRET = "settings-test-webhook-secret"
    }
}
