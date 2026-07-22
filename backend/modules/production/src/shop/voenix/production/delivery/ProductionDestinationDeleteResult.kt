package shop.voenix.production.delivery

internal sealed interface ProductionDestinationDeleteResult {
    data object Deleted : ProductionDestinationDeleteResult

    data object NotFound : ProductionDestinationDeleteResult

    data object InUse : ProductionDestinationDeleteResult
}
