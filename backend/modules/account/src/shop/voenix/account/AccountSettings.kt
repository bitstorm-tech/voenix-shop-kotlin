package shop.voenix.account

import io.ktor.server.config.ApplicationConfig
import java.net.URI

/**
 * [frontendBaseUrl] is the base of every mailed account link (confirmation, password reset, e-mail
 * change). It is required at startup and must use HTTPS outside local environments.
 * [pbkdf2Iterations] configures the password-hash work factor so tests can run fast without
 * weakening the production default.
 */
public class AccountSettings(
    frontendBaseUrl: String,
    public val pbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
) {
    public val frontendBaseUrl: String = frontendBaseUrl.trim().trimEnd('/')

    init {
        val uri = runCatching {
            URI(this.frontendBaseUrl)
        }
            .getOrElse { throw IllegalArgumentException("Account frontend base URL is invalid") }
        require(uri.isAbsolute && uri.scheme.lowercase() in ALLOWED_SCHEMES) {
            "Account frontend base URL must be an absolute HTTP(S) URL"
        }
        val host = uri.host
        require(!host.isNullOrBlank()) { "Account frontend base URL must contain a host" }
        require(uri.scheme.lowercase() == "https" || host.lowercase() in LOCAL_HOSTS) {
            "Account frontend base URL must use HTTPS outside local environments"
        }
        require(pbkdf2Iterations >= MINIMUM_PBKDF2_ITERATIONS) {
            "Account PBKDF2 iteration count must be at least 1"
        }
    }

    public companion object {
        public fun from(config: ApplicationConfig): AccountSettings =
            AccountSettings(
                frontendBaseUrl =
                    config
                        .propertyOrNull("Account.FrontendBaseUrl")
                        ?.getString()
                        ?.takeIf(String::isNotBlank)
                        ?: error("Missing required configuration value: Account.FrontendBaseUrl"),
                pbkdf2Iterations =
                    config.propertyOrNull("Account.Pbkdf2Iterations")?.getString()?.toInt()
                        ?: DEFAULT_PBKDF2_ITERATIONS,
            )

        private val ALLOWED_SCHEMES = setOf("http", "https")
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
        private const val DEFAULT_PBKDF2_ITERATIONS = 600_000
        private const val MINIMUM_PBKDF2_ITERATIONS = 1
    }
}
