package shop.voenix.pricing

import shop.voenix.operation.OperationResult

/**
 * The price capability that owning modules consume. Article is the first one: an article and its
 * price must be written in one transaction, so a failing article write can never leave a stray
 * price row behind.
 *
 * The capability is split into one suspending preparation step and three non-suspending write
 * steps, because those two halves need different transaction behavior:
 *
 * - [prepare] validates, resolves both VAT entries, and calculates. It never reads or writes the
 *   `prices` table, so the caller can run it before opening its own transaction and keep the
 *   transaction as short as the writes themselves.
 * - [storeInTransaction], [replaceInTransaction], and [deleteInTransaction] are deliberately not
 *   `suspend`. They run their statement in the Exposed transaction the caller has already opened
 *   and never start a second one. Committing or rolling back therefore always covers the article
 *   and its price together. Calling one of them outside a transaction fails with an
 *   [IllegalStateException] instead of silently writing on its own.
 *
 * Price ownership holds by construction: an id only exists after [storeInTransaction] minted it for
 * the caller, and no consumer contract accepts a price id from a client.
 *
 * Only [prepare] reports expected outcomes as an [OperationResult]. The write operations and [find]
 * let unexpected database failures surface as exceptions, so the consuming module answers them with
 * its own error policy — and so a failed write aborts the surrounding transaction.
 */
public interface PriceCatalog {
    /**
     * Validates [input], resolves the referenced purchase and sales VAT entries in one lookup, and
     * calculates every derived amount. The result has `id = null`; nothing is stored.
     *
     * Returns [OperationResult.Invalid] with field errors for a rejected input, for an unknown
     * `purchaseVatId` or `salesVatId`, and for a negative calculated sales total.
     */
    public suspend fun prepare(input: PriceInput): OperationResult<CalculatedPrice>

    /**
     * Inserts [price] into the caller's open transaction and returns the new price id. Pass a value
     * that [prepare] produced, so only validated and normalized inputs reach the table.
     */
    public fun storeInTransaction(price: CalculatedPrice): Long

    /**
     * Replaces every calculation input of the price [id] inside the caller's open transaction and
     * reports whether that price existed. Updating in place keeps the id stable, so an owner never
     * has to rewrite its own reference.
     */
    public fun replaceInTransaction(
        id: Long,
        price: CalculatedPrice,
    ): Boolean

    /**
     * Deletes the price [id] inside the caller's open transaction and reports whether it existed.
     * The owner deletes its own row first, because the referencing foreign key is restricted.
     */
    public fun deleteInTransaction(id: Long): Boolean

    /**
     * Reads the prices for [ids] and recalculates them with the current VAT values. Unknown ids are
     * absent from the result. One call resolves every price and every referenced VAT entry in one
     * query each, so a list projection never queries per row.
     */
    public suspend fun find(ids: Set<Long>): Map<Long, CalculatedPrice>
}
