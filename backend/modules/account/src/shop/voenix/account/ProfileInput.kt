package shop.voenix.account

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * `PUT profile` replaces the whole profile: every shipping field takes the sent value, and when
 * [hasSeparateBillingAddress] is false the stored billing address is cleared.
 */
@Serializable
internal data class ProfileInput(
    val shippingAddress: Address? = null,
    val hasSeparateBillingAddress: Boolean = false,
    val billingAddress: Address? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (shippingAddress == null) {
            put("shippingAddress", listOf("Shipping address is required"))
        } else {
            putAll(shippingAddress.validate("shippingAddress"))
        }
        billingAddress?.let { putAll(it.validate("billingAddress")) }
    }
}
