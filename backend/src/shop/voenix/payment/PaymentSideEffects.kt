package shop.voenix.payment

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import shop.voenix.persistence.order.Orders

object PaymentSideEffects : IntIdTable("payment_side_effects") {
    val order = reference("order_id", Orders)
    val type = varchar("type", length = 32)
    val status = varchar("status", length = 32)
    val attempts = integer("attempts")
    val nextAttemptEpochSeconds = long("next_attempt_epoch_seconds")
    val idempotencyKey = varchar("idempotency_key", length = 160)
    val lastError = text("last_error").nullable()
}
