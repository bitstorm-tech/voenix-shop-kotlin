package shop.voenix.email.rendering

import shop.voenix.email.EmailRecipient

internal data class RenderedEmail(
    val recipient: EmailRecipient,
    val recipientName: String?,
    val subject: String,
    val html: String,
    val text: String,
)
