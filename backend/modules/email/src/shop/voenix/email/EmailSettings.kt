package shop.voenix.email

import io.ktor.server.config.ApplicationConfig

public class EmailSettings(
    public val enabled: Boolean = false,
    public val pollIntervalMinutes: Int = DEFAULT_POLL_INTERVAL_MINUTES,
    apiKey: String = "",
    fromEmail: String = "",
    fromName: String = DEFAULT_FROM_NAME,
) {
    internal val apiKey: String = apiKey.trim()
    internal val sender: EmailRecipient? = fromEmail.takeIf { enabled }?.let(EmailRecipient::invoke)
    internal val fromName: String = fromName.trim()

    init {
        require(pollIntervalMinutes in MIN_POLL_INTERVAL_MINUTES..MAX_POLL_INTERVAL_MINUTES) {
            "Email poll interval must be between 1 and 1440 minutes"
        }
        require(this.fromName.isNotBlank() && this.fromName.length <= MAX_DISPLAY_VALUE_LENGTH) {
            "Email sender name must contain between 1 and 255 characters"
        }
        require(this.fromName.none { it.isISOControl() }) {
            "Email sender name must not contain control characters"
        }
        if (enabled) {
            require(this.apiKey.isNotBlank()) { "Email API key is required when email is enabled" }
            requireNotNull(sender) { "Email sender address is required when email is enabled" }
        }
    }

    override fun toString(): String =
        "EmailSettings(enabled=$enabled, pollIntervalMinutes=$pollIntervalMinutes, credentials=[REDACTED])"

    public companion object {
        public fun from(config: ApplicationConfig): EmailSettings =
            EmailSettings(
                enabled = config.value("Enabled", "false").toBooleanStrict(),
                pollIntervalMinutes =
                    config
                        .value("PollIntervalMinutes", DEFAULT_POLL_INTERVAL_MINUTES.toString())
                        .toInt(),
                apiKey = config.value("ApiKey", ""),
                fromEmail = config.value("FromEmail", ""),
                fromName = config.value("FromName", DEFAULT_FROM_NAME),
            )

        private fun ApplicationConfig.value(name: String, default: String): String =
            propertyOrNull("Email.$name")?.getString() ?: default

        private const val DEFAULT_FROM_NAME = "Voenix Shop"
        private const val DEFAULT_POLL_INTERVAL_MINUTES = 5
        private const val MIN_POLL_INTERVAL_MINUTES = 1
        private const val MAX_POLL_INTERVAL_MINUTES = 1_440
        private const val MAX_DISPLAY_VALUE_LENGTH = 255
    }
}
