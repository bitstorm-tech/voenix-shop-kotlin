package shop.voenix.article

import java.util.concurrent.CopyOnWriteArrayList
import shop.voenix.operation.OperationResult
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.pricing.PriceInput

/**
 * A [PriceCatalog] that remembers which price ids a read asked for. One entry per call is what
 * proves that a list of many articles resolves its prices in a single batched lookup.
 */
internal class CountingPriceCatalog(private val delegate: PriceCatalog) : PriceCatalog {
    val requestedIds: MutableList<Set<Long>> = CopyOnWriteArrayList()

    override suspend fun prepare(input: PriceInput): OperationResult<CalculatedPrice> =
        delegate.prepare(input)

    override fun storeInTransaction(price: CalculatedPrice): Long =
        delegate.storeInTransaction(price)

    override fun replaceInTransaction(
        id: Long,
        price: CalculatedPrice,
    ): Boolean = delegate.replaceInTransaction(id, price)

    override fun deleteInTransaction(id: Long): Boolean = delegate.deleteInTransaction(id)

    override suspend fun find(ids: Set<Long>): Map<Long, CalculatedPrice> {
        requestedIds += ids
        return delegate.find(ids)
    }
}
