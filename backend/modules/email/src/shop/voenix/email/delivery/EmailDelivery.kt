package shop.voenix.email.delivery

import shop.voenix.email.rendering.RenderedEmail

internal fun interface EmailDelivery {
    suspend fun deliver(
        email: RenderedEmail,
        campaignId: String?,
    ): EmailDeliveryResult
}
