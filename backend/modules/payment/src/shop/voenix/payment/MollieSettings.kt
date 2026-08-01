package shop.voenix.payment

import io.ktor.server.config.ApplicationConfig
import java.net.URI

/**
 * Everything this backend needs to know about Mollie: the key it authenticates with, where a
 * customer comes back to, and the address Mollie calls back on.
 *
 * Every value is validated in the constructor, which is what makes a misconfigured deployment fail
 * *before* Flyway touches the database — the same rule the auth and e-mail settings follow, and one
 * that matters more here than anywhere else: a shop that starts without a working payment
 * integration takes orders it can never collect money for.
 *
 * There is deliberately **no** dummy mode (decision 2026-08-01, D16). A payment provider that
 * silently answers "paid" without money moving is the one stub whose accidental activation in
 * production nobody would notice until the bank statement. Local development uses a Mollie test key
 * and a tunnel, exactly as the legacy application did.
 *
 * [webhookSecret] is not configuration in the ordinary sense but a credential: it is the whole
 * reason an unknown payment id may be answered with `200` (D2/D3), because only Mollie — and
 * whoever holds this secret — can reach the route at all. It therefore has to be long enough to be
 * worth guessing at, it has to be the last segment of [webhookUrl] — the route answers nowhere else
 * — and [toString] redacts it next to the API key, which is why the webhook URL is rendered without
 * its path (deviation D25).
 *
 * [apiUrl] is a constructor override only and never a configuration key (the `GeneratorSettings`
 * precedent): deployments always call Mollie, while adapter tests point the client at a local stub,
 * so no configuration mistake can make the quality gate move real money.
 */
public class MollieSettings(
    apiKey: String = "",
    redirectUrl: String = "",
    webhookUrl: String = "",
    webhookSecret: String = "",
    apiUrl: String = MOLLIE_PAYMENTS_URL,
) {
    internal val apiKey: String = apiKey.trim()
    internal val redirectUrl: String = redirectUrl.trim()
    internal val webhookUrl: String = webhookUrl.trim()
    internal val webhookSecret: String = webhookSecret.trim()
    internal val apiUrl: String = apiUrl.trim()

    init {
        require(this.apiKey.isNotBlank()) { "Mollie API key is required" }
        require(this.apiUrl.isAbsoluteHttpUrl()) {
            "Mollie API URL must be an absolute HTTP(S) URL"
        }
        require(this.redirectUrl.isAbsoluteHttpUrl()) {
            "Mollie redirect URL must be an absolute HTTP(S) URL"
        }
        // Mollie posts the payment id to this address, and the secret that authorizes the call is
        // part of the address itself. Plaintext would hand both to anyone on the path.
        require(this.webhookUrl.startsWith(HTTPS_PREFIX)) {
            "Mollie webhook URL must be an absolute HTTPS URL"
        }
        require(this.webhookSecret.length >= MINIMUM_SECRET_LENGTH) {
            "Mollie webhook secret must be at least $MINIMUM_SECRET_LENGTH characters"
        }
        // The route only answers on `/api/payments/webhook/<secret>`, so a webhook URL whose last
        // segment is something else describes a deployment in which every real callback is
        // answered `403` — silently, because Mollie retries rather than complains. That is a
        // configuration mistake worth refusing to start over.
        require(this.webhookUrl.lastPathSegment() == this.webhookSecret) {
            "Mollie webhook URL must end in the webhook secret"
        }
    }

    /**
     * Renders no credential: should settings ever be logged, a log is not a secret store.
     *
     * The webhook URL loses its path, not just its query. The secret *is* the last path segment, so
     * rendering the address in full would print the credential that the same line claims to redact.
     */
    override fun toString(): String =
        "MollieSettings(apiUrl=$apiUrl, redirectUrl=$redirectUrl, " +
            "webhookUrl=${webhookUrl.origin()} [path redacted], credentials=[REDACTED])"

    public companion object {
        public fun from(config: ApplicationConfig): MollieSettings =
            MollieSettings(
                apiKey = config.value("ApiKey"),
                redirectUrl = config.value("RedirectUrl"),
                webhookUrl = config.value("WebhookUrl"),
                webhookSecret = config.value("WebhookSecret"),
            )

        private fun ApplicationConfig.value(name: String): String =
            propertyOrNull("Mollie.$name")?.getString().orEmpty()

        private fun String.isAbsoluteHttpUrl(): Boolean =
            startsWith("http://") || startsWith(HTTPS_PREFIX)

        /**
         * The last path segment of this URL, decoded, or `null` when it is no URL at all.
         *
         * [URI.getPath] is the decoded path, which is what makes the comparison work for a secret
         * that has to travel percent-encoded in the configured address: the route matches on the
         * decoded segment too.
         */
        private fun String.lastPathSegment(): String? = runCatching {
            URI(this).path.orEmpty().substringAfterLast('/')
        }
            .getOrNull()

        /** Scheme and host of this URL and nothing else, or a redaction when it is no URL. */
        private fun String.origin(): String =
            runCatching { URI(this) }
                .getOrNull()
                ?.takeIf { uri -> uri.scheme != null && uri.host != null }
                ?.let { uri -> "${uri.scheme}://${uri.host}" } ?: "[REDACTED]"

        private const val HTTPS_PREFIX = "https://"
        private const val MOLLIE_PAYMENTS_URL = "https://api.mollie.com/v2/payments"

        /**
         * Short enough not to be a nuisance, long enough that the webhook path is not brute-forced
         * in an afternoon. A generated UUID clears it comfortably.
         */
        private const val MINIMUM_SECRET_LENGTH = 16
    }
}
