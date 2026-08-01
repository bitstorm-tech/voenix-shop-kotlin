package shop.voenix.payment

/**
 * What a webhook delivery did, in the seven situations that end differently.
 *
 * Five of them are answered with `200`, and that is the point rather than a shortcut: a webhook
 * answer is a message to *Mollie* about whether to redeliver, not a report to a human. Everything
 * this shop has to sort out afterwards — an unknown payment id, a mismatching amount, a paid
 * cancelled order, a payment overtaken by a newer one — is already handled as far as software can
 * handle it, so asking Mollie to send it again would only repeat the same log line every few
 * minutes. The two statuses that are *not* `200` are exactly the two where a redelivery genuinely
 * helps: [PROVIDER_UNAVAILABLE] and [DATABASE_FAILURE].
 *
 * The log is therefore the other half of this type. Every outcome below that is not routine names
 * its evidence at WARN or ERROR, and the deferred admin anomaly page is what will one day list them
 * without anybody reading a log at all.
 */
internal enum class PaymentConfirmation {
    /** The reported status was stored (or already stood there) and it was not `PAID`. */
    RECORDED,

    /** `PAID`, and the order module applied it — or had already applied an earlier delivery. */
    CONFIRMED,

    /**
     * `PAID`, and the order was deliberately *not* confirmed: either Mollie's amount differs from
     * what this shop asked for (deviation D11), or the order is `CANCELLED` and stays that way
     * (deviation D14). Both mean money moved for something the shop will not produce, both are
     * logged at ERROR with everything a human needs, and both are settled by hand.
     */
    NOT_CONFIRMED,

    /**
     * The status could not be stored because a newer live payment for the same order stands in the
     * index. A dead payment reporting itself paid next to a live one means the customer may have
     * been charged twice. On `PAID` the order is still confirmed — the money is real either way.
     */
    SUPERSEDED,

    /** Mollie named a payment this backend never created (deviation D2 from the legacy `404`). */
    UNKNOWN_PAYMENT,

    /** Mollie could not be reached or said nothing usable; a redelivery may well succeed. */
    PROVIDER_UNAVAILABLE,

    /** The database refused the work; a redelivery may well succeed. */
    DATABASE_FAILURE,
}
