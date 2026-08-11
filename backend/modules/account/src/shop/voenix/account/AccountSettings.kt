package shop.voenix.account

import io.ktor.server.config.ApplicationConfig
import shop.voenix.http.FrontendBaseUrl

/**
 * [frontendBaseUrl] is the base of every mailed account link (confirmation, password reset, e-mail
 * change). It is the application-wide `frontend.baseUrl` — the same value the order confirmation
 * links to — and is handed in rather than read here, so there is exactly one place that decides
 * what this deployment's frontend is called (issue #110). [pbkdf2Iterations] configures the
 * password-hash work factor so tests can run fast without weakening the production default.
 */
public class AccountSettings(
    public val frontendBaseUrl: FrontendBaseUrl,
    public val pbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
) {
    init {
        require(pbkdf2Iterations >= MINIMUM_PBKDF2_ITERATIONS) {
            "Account PBKDF2 iteration count must be at least 1"
        }
    }

    public companion object {
        public fun from(
            config: ApplicationConfig,
            frontendBaseUrl: FrontendBaseUrl,
        ): AccountSettings =
            AccountSettings(
                frontendBaseUrl = frontendBaseUrl,
                pbkdf2Iterations =
                    config.propertyOrNull("account.pbkdf2Iterations")?.getString()?.toInt()
                        ?: DEFAULT_PBKDF2_ITERATIONS,
            )

        private const val DEFAULT_PBKDF2_ITERATIONS = 600_000
        private const val MINIMUM_PBKDF2_ITERATIONS = 1
    }
}
