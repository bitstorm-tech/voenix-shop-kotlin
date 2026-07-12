package shop.voenix.auth

import io.ktor.server.config.ApplicationConfig

data class AuthSettings(val sessionSecret: String) {
    init {
        require(sessionSecret.toByteArray(Charsets.UTF_8).size >= MINIMUM_SESSION_SECRET_BYTES) {
            "The auth session secret must contain at least 32 UTF-8 bytes"
        }
    }

    companion object {
        fun from(config: ApplicationConfig): AuthSettings =
            AuthSettings(
                sessionSecret =
                    config
                        .propertyOrNull("Auth.SessionSecret")
                        ?.getString()
                        ?.takeIf(String::isNotBlank)
                        ?: error("Missing required configuration value: Auth.SessionSecret")
            )

        private const val MINIMUM_SESSION_SECRET_BYTES = 32
    }
}
