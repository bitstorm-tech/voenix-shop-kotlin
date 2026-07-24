package shop.voenix.account

import kotlinx.serialization.Serializable

/** The one profile representation returned by both `GET me` and `PUT profile`. */
@Serializable
internal data class AccountProfile(
    val id: Long,
    val email: String,
    val roles: List<String>,
    val shippingAddress: Address?,
    val billingAddress: Address?,
    val hasSeparateBillingAddress: Boolean,
    val createdAt: String,
)
