package shop.voenix.order

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer

/**
 * A placed order as everyone reads it: the history list, the single lookup, and the answer a
 * placement gives back.
 *
 * There is deliberately one representation instead of a list projection and a detail model. An
 * order is small and complete — a handful of amounts and its lines — so a list entry that left the
 * lines out would only force a second request per order to show what was bought.
 *
 * Nothing here is calculated on read. [subtotal], [shippingCost], [discountAmount], and [total] are
 * the stored amounts, and the database keeps them one consistent statement (`total = subtotal +
 * shippingCost - discountAmount`); recomputing them from today's prices would answer a question
 * nobody asked.
 *
 * [paymentStatus] is the one value that does *not* come from the order's own tables. It is filled
 * in by the service from [OrderPaymentStatusSource] — in one batch read for the history, and with a
 * provider refresh for the single order — and it is `null` when the order has no payment at all: a
 * free order, or one whose checkout was never started. The repository therefore builds every view
 * without it, and the field defaults to `null` so no read path can forget to say so.
 */
@Serializable
internal data class OrderView(
    val orderId: Long,
    @Serializable(with = InstantIso8601Serializer::class) val createdAt: Instant,
    val status: OrderStatus,
    val paymentStatus: OrderPaymentStatus? = null,
    val subtotal: Int,
    val shippingCost: Int,
    val discountAmount: Int,
    val total: Int,
    val items: List<OrderLineView>,
)

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

/**
 * What has happened to an order so far.
 *
 * The three values are the whole lifecycle: an order is [PENDING] from placement until its payment
 * is confirmed, becomes [PAID] once it is processed, and [CANCELLED] when its payment could never
 * be started. The database CHECK on `orders.status` says exactly the same, and only [CANCELLED]
 * rows fall out of the partial unique index that keeps one live order per cart.
 */
@Serializable
internal enum class OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
}
