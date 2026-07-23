package shop.voenix.production.delivery

/** Typed outcome of the transactional split write for one open production request. */
internal sealed interface ProductionSplitResult {
    /** Every job and delivery exists and the request is marked processed. */
    data object Completed : ProductionSplitResult

    /**
     * A supplier of the order has no enabled destination; nothing was written and the request stays
     * open until an admin enables or creates a destination for [supplierId].
     */
    data class SupplierWithoutDestination(val supplierId: Long) : ProductionSplitResult
}
