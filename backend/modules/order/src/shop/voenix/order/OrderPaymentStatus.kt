package shop.voenix.order

import kotlinx.serialization.Serializable

/**
 * Where the payment of an order stands, in the seven words the payment provider uses.
 *
 * The vocabulary is Mollie's, uppercased, and it is declared *here* rather than in the payment
 * module for the same reason [OrderPaymentGateway] is: an order answer carries this value on the
 * wire, so every consumer of an order — the cart re-exports this module — would otherwise have to
 * compile against the Mollie integration. The payment module implements [OrderPaymentStatusSource]
 * with it and stores nothing else.
 *
 * [CANCELED] carries one L while [OrderStatus.CANCELLED] carries two, and that is deliberate rather
 * than a typo to fix: the two words describe two different things — Mollie cancelled a *payment*,
 * the shop cancelled an *order* — they are written by two different systems, and unifying the
 * spelling would make a status string silently valid on the wrong side.
 *
 * Nothing here says which of the seven is terminal. That question belongs to the payment module,
 * where the partial unique index over live payments decides it; an order only displays the word.
 */
@Serializable
public enum class OrderPaymentStatus {
    OPEN,
    PENDING,
    AUTHORIZED,
    PAID,
    FAILED,
    CANCELED,
    EXPIRED,
}
