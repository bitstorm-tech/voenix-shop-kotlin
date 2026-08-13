package shop.voenix.production.fulfillment

/**
 * What the guarded ship update did: it closed the job, or it touched nothing — and in that case,
 * which of the three reasons the re-read inside the same transaction found.
 *
 * It is the repository's answer, one level below [ShipResult]: no view, no HTTP meaning, just the
 * state of the row.
 */
internal enum class ShipWriteResult {
    SHIPPED,
    NOT_FOUND,
    ALREADY_SHIPPED,
    NOT_READY,
}
