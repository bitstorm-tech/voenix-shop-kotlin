package shop.voenix.cart

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The body of `POST /api/cart/promotion`: the coupon code a customer typed.
 *
 * The length limit mirrors the `coupon_code` column, so a code that could not possibly exist is
 * refused before the promotion module is asked about it.
 */
@Serializable
internal data class PromotionCodeInput(val promotionCode: String? = null) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        when {
            promotionCode.isNullOrBlank() ->
                put("promotionCode", listOf("PromotionCode is required"))
            promotionCode.trim().length > MAXIMUM_PROMOTION_CODE_LENGTH ->
                put(
                    "promotionCode",
                    listOf(
                        "PromotionCode must be at most $MAXIMUM_PROMOTION_CODE_LENGTH characters"
                    ),
                )
        }
    }

    internal companion object {
        const val MAXIMUM_PROMOTION_CODE_LENGTH: Int = 64
    }
}
