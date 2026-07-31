package shop.voenix.order

import kotlinx.serialization.Serializable

/**
 * One line of a placed order, exactly as it was ordered.
 *
 * Every value here is a snapshot taken at placement and never resolved again — unlike a cart line,
 * which renders live catalog data. That is the whole point of an order: the customer must still see
 * the name they bought and the price they paid after an admin has renamed the article, changed its
 * price, or deleted it altogether.
 *
 * [imageId] and the prompt reference behind it are the two exceptions the schema keeps as real
 * references, because the reorder flow needs the rows they point at; the prompt is not part of this
 * answer, because a customer has no use for it.
 */
@Serializable
internal data class OrderLineView(
    val orderItemId: Long,
    val articleId: Long,
    val variantId: Long,
    val articleName: String,
    val variantName: String,
    val quantity: Int,
    val price: Int,
    val promptPrice: Int,
    val imageId: Long?,
)
