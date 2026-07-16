package shop.voenix.email

internal data class RenderedEmail(
    val recipient: EmailRecipient,
    val recipientName: String?,
    val subject: String,
    val html: String,
    val text: String,
)
