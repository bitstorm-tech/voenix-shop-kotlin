package shop.voenix.email.delivery

import shop.voenix.email.rendering.RenderedEmail

internal fun interface EmailDelivery {
    suspend fun deliver(
        email: RenderedEmail,
        campaignId: String?,
    ): EmailDeliveryResult
}

internal sealed interface EmailDeliveryResult {
    data object Accepted : EmailDeliveryResult

    data class Failed(val code: String) : EmailDeliveryResult {
        init {
            require(code.matches(Regex("[A-Z0-9_]{1,64}"))) { "Invalid safe delivery error code" }
        }
    }
}
