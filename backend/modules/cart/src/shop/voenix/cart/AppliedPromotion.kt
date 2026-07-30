package shop.voenix.cart

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.json.BigDecimalJsonNumberSerializer
import shop.voenix.promotion.PromotionCodeResult

/**
 * The promotion currently applied to a cart, as the cart renders it.
 *
 * The discount is deliberately *flat* — a `discountType` string next to a `discountValue` number —
 * instead of the nested sealed [shop.voenix.promotion.Discount] the promotion module models
 * internally. A cart response is read by a browser, and a discriminated union costs every consumer
 * a branch to learn two numbers. The promotion module stays the authority on what the pair means;
 * the cart only shows it.
 */
@Serializable
internal data class AppliedPromotion(
    val id: Long,
    val name: String,
    val promotionCode: String,
    val discountType: String,
    @Serializable(with = BigDecimalJsonNumberSerializer::class) val discountValue: BigDecimal,
)

internal fun PromotionCodeResult.Applicable.toAppliedPromotion(): AppliedPromotion =
    AppliedPromotion(
        id = id,
        name = name,
        promotionCode = couponCode,
        discountType = discount.discountType,
        discountValue = discount.value,
    )
