package shop.voenix.production.delivery

internal sealed interface ProductionDestinationWriteResult {
    data class Stored(val destination: StoredProductionDestination) :
        ProductionDestinationWriteResult

    data object NotFound : ProductionDestinationWriteResult

    data object SupplierNotFound : ProductionDestinationWriteResult
}
