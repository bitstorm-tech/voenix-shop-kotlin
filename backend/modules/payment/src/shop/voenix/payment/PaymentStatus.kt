package shop.voenix.payment

/**
 * Mollie's payment status vocabulary, uppercased — the seven values `ck_payments_status` accepts
 * and the only ones this module ever stores.
 *
 * The names are the provider's, which is why [CANCELED] carries one L while the order module's
 * `CANCELLED` carries two. That is not a typo to fix: the two words describe two different things
 * (Mollie cancelled a payment; the shop cancelled an order), they are written by two different
 * systems, and unifying the spelling would make a status string silently valid on the wrong side.
 *
 * Three of the seven are terminal in the sense the partial unique index cares about — [FAILED],
 * [CANCELED], [EXPIRED] fall out of `ux_payments_live_order`, so an order whose payment ended that
 * way may start a second one. The other four keep the order's payment slot occupied.
 */
internal enum class PaymentStatus {
    OPEN,
    PENDING,
    AUTHORIZED,
    PAID,
    FAILED,
    CANCELED,
    EXPIRED;

    /** Whether a payment in this status still occupies its order's one live payment slot. */
    val isLive: Boolean
        get() = this !in TERMINAL

    companion object {
        private val TERMINAL = setOf(FAILED, CANCELED, EXPIRED)

        /**
         * The status Mollie named, or `null` when it is a word this module does not know.
         *
         * The unparsed value is deliberately *not* part of the answer and never travels into a log
         * line: it is provider output, and the webhook answers `502` so Mollie retries rather than
         * writing a status nothing downstream could interpret.
         */
        fun ofProviderValue(value: String): PaymentStatus? = entries.firstOrNull { status ->
            status.name.equals(value.trim(), ignoreCase = true)
        }
    }
}
