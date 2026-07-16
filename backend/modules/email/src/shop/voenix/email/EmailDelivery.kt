package shop.voenix.email

internal fun interface EmailDelivery {
    suspend fun deliver(
        email: RenderedEmail,
        campaignId: String?,
    ): EmailDeliveryResult
}
