package shop.voenix.generator

import io.ktor.server.config.ApplicationConfig

/**
 * What the generator needs to know about the image provider: whether it talks to it at all, and the
 * key it authenticates with.
 *
 * The rule the legacy options validator enforced is kept: a deployment that is not in dummy mode
 * must carry an API key, and the application fails at startup without one. That is deliberate — the
 * alternative, defaulting to dummy mode, would silently serve back the uploaded image instead of a
 * generated one and nobody would notice until a customer complained.
 *
 * [apiUrl] is a constructor override only and never a configuration key (the
 * `EmailSettings.sendUrl` precedent): deployments always call fal.ai, while adapter tests point the
 * client at a local stub server, so no configuration mistake can make the quality gate spend real
 * money.
 */
public class GeneratorSettings(
    public val dummyMode: Boolean = false,
    apiKey: String = "",
    apiUrl: String = FAL_EDIT_URL,
) {
    internal val apiKey: String = apiKey.trim()
    internal val apiUrl: String = apiUrl.trim()

    init {
        require(this.apiUrl.startsWith("http://") || this.apiUrl.startsWith("https://")) {
            "Generator API URL must be an absolute HTTP(S) URL"
        }
        if (!dummyMode) {
            require(this.apiKey.isNotBlank()) {
                "Generator API key is required unless dummy mode is enabled"
            }
        }
    }

    /** Never renders the key: settings are logged at startup, and a log is not a secret store. */
    override fun toString(): String =
        "GeneratorSettings(dummyMode=$dummyMode, apiUrl=$apiUrl, credentials=[REDACTED])"

    public companion object {
        public fun from(config: ApplicationConfig): GeneratorSettings =
            GeneratorSettings(
                dummyMode = config.value("DummyMode", "false").toBooleanStrict(),
                apiKey = config.value("ApiKey", ""),
            )

        private fun ApplicationConfig.value(
            name: String,
            default: String,
        ): String = propertyOrNull("Generator.$name")?.getString() ?: default

        private const val FAL_EDIT_URL = "https://fal.run/fal-ai/nano-banana-2/edit"
    }
}
