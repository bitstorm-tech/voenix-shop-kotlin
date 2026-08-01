package shop.voenix.payment

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * One attempt at collecting the money for one order.
 *
 * The table has a local `id` and points at the order rather than the other way round (deviation
 * D8): an order outlives its payments, and a retry after a failed payment is a *second* row for the
 * same order, not a rewritten one. Which of those rows is the order's current payment is decided by
 * the partial unique index `ux_payments_live_order`, not by any column here.
 *
 * [amountCents] is what this shop asked Mollie for, stored so the webhook can compare it with what
 * Mollie says was actually paid (deviation D11). [checkoutUrl] is stored because the repeated
 * `start` of a double-clicked checkout answers it instead of creating a second payment.
 */
internal object Payments : LongIdTable("payments") {
    val orderId = long("order_id")
    val molliePaymentId = varchar("mollie_payment_id", 64)
    val status = text("status")
    val amountCents = integer("amount_cents")
    val checkoutUrl = text("checkout_url")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
