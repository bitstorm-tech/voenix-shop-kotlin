package shop.voenix.http

import io.ktor.server.config.ApplicationConfig
import java.net.URI

/**
 * Where the frontend of this deployment lives, as every mailed link is built from it.
 *
 * It is one setting for the whole application rather than one per module, because there is one
 * frontend: the account mails link into it (confirmation, password reset, e-mail change) and so
 * does the order confirmation, and a deployment that has to name its own address twice will
 * eventually name it differently in the two places (issue #110).
 *
 * [value] is normalized — trimmed, without a trailing slash — so a caller can always append a path
 * beginning with `/` and never has to think about a double slash. The rules are the ones the
 * account module used to carry: an absolute HTTP(S) URL with a host, and HTTPS everywhere except
 * the local hosts a development machine uses. A mailed link is a link the customer clicks outside
 * the shop, and shipping one over plain HTTP is how a session or a token ends up in the open.
 *
 * The value is required at startup. It is read together with every other setting *before* Flyway
 * runs, so a deployment that forgot the key fails without having touched the database.
 */
public class FrontendBaseUrl(rawValue: String) {
    public val value: String = rawValue.trim().trimEnd('/')

    init {
        val uri = runCatching {
            URI(value)
        }
            .getOrElse { throw IllegalArgumentException("Frontend base URL is invalid") }
        require(uri.isAbsolute && uri.scheme.lowercase() in ALLOWED_SCHEMES) {
            "Frontend base URL must be an absolute HTTP(S) URL"
        }
        val host = uri.host
        require(!host.isNullOrBlank()) { "Frontend base URL must contain a host" }
        require(uri.scheme.lowercase() == "https" || host.lowercase() in LOCAL_HOSTS) {
            "Frontend base URL must use HTTPS outside local environments"
        }
    }

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is FrontendBaseUrl && value == other.value

    override fun hashCode(): Int = value.hashCode()

    public companion object {
        public fun from(config: ApplicationConfig): FrontendBaseUrl =
            FrontendBaseUrl(
                config.propertyOrNull("frontend.baseUrl")?.getString()?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: frontend.baseUrl")
            )

        private val ALLOWED_SCHEMES = setOf("http", "https")
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
