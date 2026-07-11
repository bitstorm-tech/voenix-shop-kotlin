package shop.voenix.auth

import io.ktor.server.config.ApplicationConfig
import shop.voenix.config.AppSettingsSecrets

data class AuthSettings(
    val sessionSecret: String,
) {
    init {
        require(sessionSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "The auth session secret must contain at least 32 UTF-8 bytes"
        }
    }

    companion object {
        fun from(config: ApplicationConfig): AuthSettings =
            AppSettingsSecrets.section(config, "Auth").let { secrets ->
                AuthSettings(
                    sessionSecret =
                        secrets.entries
                            .firstOrNull { (key, _) -> key.equals("SessionSecret", ignoreCase = true) }
                            ?.value
                            ?: config
                                .propertyOrNull("Auth.SessionSecret")
                                ?.getString()
                                ?.takeIf(String::isNotBlank)
                            ?: error("Missing required configuration value: Auth.SessionSecret"),
                )
            }
    }
}
