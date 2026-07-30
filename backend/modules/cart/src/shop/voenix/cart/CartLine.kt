package shop.voenix.cart

import kotlinx.serialization.Serializable

/**
 * One line of a rendered cart: what the customer chose, what they were quoted, and what the article
 * catalog currently says about it.
 *
 * [price] and [promptPrice] are snapshots taken when the line was added and never change again; the
 * names and the two color codes are current master data, resolved on every read. A reference the
 * article catalog no longer answers renders with `null` names and `available = false` instead of
 * disappearing: the customer must see the line they put in the cart, and why they cannot buy it.
 */
@Serializable
internal data class CartLine(
    val id: Long,
    val articleId: Long,
    val variantId: Long,
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
