package shop.voenix.email

import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.outbox.EmailJobRepository
import shop.voenix.email.rendering.UserEmailRenderer

internal class EmailService(
    private val settings: EmailSettings,
    private val renderer: UserEmailRenderer,
    private val delivery: EmailDelivery,
    private val repository: EmailJobRepository,
) : UserEmailSender, EmailOutbox {
    override suspend fun send(email: UserEmail) {
        if (!settings.enabled) return
        val rendered = renderer.render(email)
        when (delivery.deliver(rendered, campaignId = null)) {
            EmailDeliveryResult.Accepted -> Unit
            is EmailDeliveryResult.Failed -> throw EmailDeliveryException()
        }
    }

    override suspend fun enqueue(reference: QueuedEmailReference): Long =
        repository.enqueueInCurrentTransaction(reference)
}
