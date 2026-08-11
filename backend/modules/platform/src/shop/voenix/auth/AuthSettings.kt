package shop.voenix.auth

import io.ktor.server.config.ApplicationConfig

public data class AuthSettings(public val sessionSecret: String) {
    init {
        require(sessionSecret.toByteArray(Charsets.UTF_8).size >= MINIMUM_SESSION_SECRET_BYTES) {
            "The auth session secret must contain at least 32 UTF-8 bytes"
        }
    }

    public companion object {
        public fun from(config: ApplicationConfig): AuthSettings =
            AuthSettings(
                sessionSecret =
                    config
                        .propertyOrNull("auth.sessionSecret")
                        ?.getString()
                        ?.takeIf(String::isNotBlank)
                        ?: error("Missing required configuration value: auth.sessionSecret")
            )

        private const val MINIMUM_SESSION_SECRET_BYTES = 32
    }
}
