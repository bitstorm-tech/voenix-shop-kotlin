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
