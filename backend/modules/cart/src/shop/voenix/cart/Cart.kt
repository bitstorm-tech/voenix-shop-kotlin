package shop.voenix.cart

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.article.ArticleType
import shop.voenix.json.BigDecimalJsonNumberSerializer
import shop.voenix.promotion.PromotionCodeResult

/**
 * The one answer every cart operation gives: the complete, recalculated cart.
 *
 * There is deliberately no second, smaller response. A mutation that returned only what it changed
 * would force the browser to recompute totals it cannot compute — shipping thresholds and discount
 * caps are server rules — so every add, update, remove, and promotion change answers with the whole
 * cart, exactly like the read does.
 *
 * The totals are aggregates of [items] and therefore travel with them: [subtotal] is the sum of
 * article and prompt price times quantity, [shippingCost] and [discountAmount] follow the rules of
 * [CartTotals], and [total] is `subtotal + shippingCost - discountAmount`.
 *
 * They are `Long` while a single line price is `Int`, because a line price is one column and a
 * total is up to 99 of them added up: the sum leaves 32 bits long before any single price does
 * (deviation D13). The JSON is unchanged — a number is a number.
 */
@Serializable
internal data class CartView(
    val id: Long?,
    val items: List<CartLine>,
    val subtotal: Long,
    val shippingCost: Long,
    val discountAmount: Long,
    val total: Long,
    val totalItems: Int,
    val appliedPromotion: AppliedPromotion?,
) {
    internal companion object {
        /**
         * What a visitor without a cart sees. `id` is `null` rather than `0`, so a client can tell
         * "no cart yet" from "cart number zero", and every amount is zero rather than absent.
         */
        val EMPTY: CartView =
            CartView(
                id = null,
                items = emptyList(),
                subtotal = 0,
                shippingCost = 0,
                discountAmount = 0,
                total = 0,
                totalItems = 0,
                appliedPromotion = null,
            )
    }
}

/**
 * One line of a rendered cart: what the customer chose, what they were quoted, and what the article
 * catalog currently says about it.
 *
 * [price] and [promptPrice] are snapshots taken when the line was added and never change again; the
 * names and the two color codes are current master data, resolved on every read. A reference the
 * article catalog no longer answers renders with `null` names and `available = false` instead of
 * disappearing: the customer must see the line they put in the cart, and why they cannot buy it.
 *
 * [articleType] is the same kind of live answer and is what a client switches on to render the
 * line: a mug shows its two colour codes, a t-shirt shows the colour and size its [variantName]
 * spells (issue #205). Nothing about it is stored on the cart line — `cart_items` points at the
 * identity registries, and the type is one of the answers `ArticleCatalog` gives for that pair — so
 * it is `null` for exactly the lines whose names are `null`: the ones the catalog no longer
 * resolves.
 */
@Serializable
internal data class CartLine(
    val id: Long,
    val articleId: Long,
    val variantId: Long,
    val articleType: ArticleType?,
    val articleName: String?,
    val variantName: String?,
    val outsideColorCode: String?,
    val insideColorCode: String?,
    val available: Boolean,
    val price: Int,
    val quantity: Int,
    val imageId: Long?,
    val promptId: Long?,
    val promptPrice: Int,
)

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

/**
 * Who a cart operation is for: the user id when the request is signed in, plus the guest session
 * token the browser carries.
 *
 * The two are not symmetrical, and which of them identifies the cart depends on the request: a
 * signed-in request finds and creates its cart by [userId], an anonymous one by [guestToken]. A
 * cart therefore never carries both, and it keeps the identity it was created with for life:
 * nothing moves a cart from the one identity to the other, so a login does not touch a single cart
 * row. `ck_carts_single_owner` and a unique index per half are the database's version of that rule
 * (issue #77, which supersedes deviation 14 of the cart migration; the claim that once moved carts
 * is gone with issue #110).
 *
 * [guestToken] still matters for a signed-in caller, because two things next to the cart keep their
 * own ownership rule: a print image belongs to its token *or* its user, and so does an ordered line
 * a reorder starts from.
 *
 * At least one of the two is always set. A request with neither is not an owner at all, and the
 * route answers it with the empty cart instead of constructing one.
 */
internal data class CartOwner(
    val guestToken: String?,
    val userId: Long?,
)

internal fun PromotionCodeResult.Applicable.toAppliedPromotion(): AppliedPromotion =
    AppliedPromotion(
        id = id,
        name = name,
        promotionCode = couponCode,
        discountType = discount.discountType,
        discountValue = discount.value,
    )
