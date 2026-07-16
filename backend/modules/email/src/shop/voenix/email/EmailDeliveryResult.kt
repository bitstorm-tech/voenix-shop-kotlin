package shop.voenix.email

import java.time.Duration

internal sealed interface EmailDeliveryResult {
    data object Accepted : EmailDeliveryResult

    data class Failed(
        val code: String,
        val safeMessage: String,
        val retryAfter: Duration? = null,
    ) : EmailDeliveryResult {
        init {
            require(code.matches(Regex("[A-Z0-9_]{1,64}"))) { "Invalid safe delivery error code" }
            require(
                safeMessage.length <= MAX_SAFE_MESSAGE_LENGTH &&
                    safeMessage.none { it.isISOControl() }
            ) {
                "Invalid safe delivery error message"
            }
        }

        private companion object {
            const val MAX_SAFE_MESSAGE_LENGTH = 512
        }
    }
}
