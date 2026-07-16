package shop.voenix.email.delivery

internal sealed interface EmailDeliveryResult {
    data object Accepted : EmailDeliveryResult

    data class Failed(val code: String) : EmailDeliveryResult {
        init {
            require(code.matches(Regex("[A-Z0-9_]{1,64}"))) { "Invalid safe delivery error code" }
        }
    }
}
