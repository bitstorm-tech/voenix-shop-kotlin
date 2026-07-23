package shop.voenix.production.delivery

/**
 * The typed outcome of one external delivery attempt. [Failed] carries only a bounded
 * [ProductionDeliveryError] — raw exception messages, credentials, hosts, and remote paths can
 * never reach a persisted error column by construction.
 */
internal sealed interface ProductionDeliveryResult {
    /** The remote system confirmed acceptance of the complete file under its final name. */
    data object Accepted : ProductionDeliveryResult

    data class Failed(val error: ProductionDeliveryError) : ProductionDeliveryResult
}

/**
 * The bounded, channel-neutral error vocabulary of external delivery attempts — the names are the
 * safe codes persisted in `production_deliveries.last_error_code`.
 */
internal enum class ProductionDeliveryError {
    /** The remote system could not be reached (resolution, TCP connect, connect timeout). */
    CONNECTION_FAILED,

    /** The server's host key did not match the pinned fingerprint — never delivered to. */
    HOST_KEY_REJECTED,

    /** The remote system rejected the credentials, or authentication never completed. */
    AUTH_FAILED,

    /** The connection was established but the transfer itself failed. */
    TRANSFER_FAILED,
}
