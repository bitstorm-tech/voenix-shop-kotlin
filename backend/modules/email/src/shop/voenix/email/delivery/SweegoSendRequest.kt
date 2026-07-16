package shop.voenix.email.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SweegoSendRequest(
    val channel: String = "email",
    val provider: String = "sweego",
    val recipients: List<Recipient>,
    val from: Sender,
    val subject: String,
    @SerialName("message-html") val messageHtml: String,
    @SerialName("message-txt") val messageText: String,
    @SerialName("campaign-type") val campaignType: String = "transac",
    @SerialName("campaign-id") val campaignId: String? = null,
) {
    @Serializable internal data class Recipient(val email: String, val name: String)

    @Serializable internal data class Sender(val email: String, val name: String)
}
