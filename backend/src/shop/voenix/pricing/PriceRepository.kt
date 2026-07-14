package shop.voenix.pricing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.vat.ValueAddedTaxes

class PriceRepository(private val database: Database) {
    internal suspend fun find(id: Long): StoredPrice? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                val input =
                    Prices.selectAll().where { Prices.id eq id }.singleOrNull()?.toPriceInput()
                        ?: return@suspendTransaction null
                val vats = findVatsInTransaction(input)
                StoredPrice(
                    input = input,
                    purchaseVat = checkNotNull(vats.purchaseVat),
                    salesVat = checkNotNull(vats.salesVat),
                )
            }
        }

    internal suspend fun exists(id: Long): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                Prices.selectAll().where { Prices.id eq id }.limit(1).any()
            }
        }

    internal suspend fun findVats(input: PriceInput): VatLookup =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findVatsInTransaction(input)
            }
        }

    internal suspend fun preferredVat(): PriceVat? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                ValueAddedTaxes.selectAll()
                    .orderBy(
                        ValueAddedTaxes.isDefault to SortOrder.DESC,
                        ValueAddedTaxes.id to SortOrder.ASC,
                    )
                    .limit(1)
                    .singleOrNull()
                    ?.toPriceVat()
            }
        }

    internal suspend fun insert(input: PriceInput): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                Prices.insertAndGetId { statement -> statement.copyFrom(input) }.value
            }
        }

    internal suspend fun update(
        id: Long,
        input: PriceInput,
    ): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                Prices.update({ Prices.id eq id }) { statement -> statement.copyFrom(input) }
            }
        }

    private fun findVatsInTransaction(input: PriceInput): VatLookup {
        val purchaseVatId = checkNotNull(input.purchaseVatId)
        val salesVatId = checkNotNull(input.salesVatId)
        val vats =
            ValueAddedTaxes.selectAll()
                .where { ValueAddedTaxes.id inList setOf(purchaseVatId, salesVatId) }
                .associate { row -> row[ValueAddedTaxes.id].value to row.toPriceVat() }
        return VatLookup(vats[purchaseVatId], vats[salesVatId])
    }

    private fun ResultRow.toPriceInput(): PriceInput =
        PriceInput(
            purchaseVatId = this[Prices.purchaseVatId],
            purchaseCalculationMode = this[Prices.purchaseCalculationMode],
            purchaseActiveRow = this[Prices.purchaseActiveRow],
            purchasePriceInputCents = this[Prices.purchasePriceInputCents],
            purchaseCostInputCents = this[Prices.purchaseCostInputCents],
            purchaseCostPercent = this[Prices.purchaseCostPercent],
            salesVatId = this[Prices.salesVatId],
            salesCalculationMode = this[Prices.salesCalculationMode],
            salesActiveRow = this[Prices.salesActiveRow],
            salesMarginInputCents = this[Prices.salesMarginInputCents],
            salesMarginPercent = this[Prices.salesMarginPercent],
            salesTotalInputCents = this[Prices.salesTotalInputCents],
        )

    private fun ResultRow.toPriceVat(): PriceVat =
        PriceVat(
            id = this[ValueAddedTaxes.id].value,
            name = this[ValueAddedTaxes.name],
            percent = this[ValueAddedTaxes.percent],
        )

    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.copyFrom(
        input: PriceInput
    ) {
        this[Prices.purchaseVatId] = checkNotNull(input.purchaseVatId)
        this[Prices.purchaseCalculationMode] = input.purchaseCalculationMode
        this[Prices.purchaseActiveRow] = input.purchaseActiveRow
        this[Prices.purchasePriceInputCents] = input.purchasePriceInputCents
        this[Prices.purchaseCostInputCents] = input.purchaseCostInputCents
        this[Prices.purchaseCostPercent] = input.purchaseCostPercent
        this[Prices.salesVatId] = checkNotNull(input.salesVatId)
        this[Prices.salesCalculationMode] = input.salesCalculationMode
        this[Prices.salesActiveRow] = input.salesActiveRow
        this[Prices.salesMarginInputCents] = input.salesMarginInputCents
        this[Prices.salesMarginPercent] = input.salesMarginPercent
        this[Prices.salesTotalInputCents] = input.salesTotalInputCents
    }

    internal data class StoredPrice(
        val input: PriceInput,
        val purchaseVat: PriceVat,
        val salesVat: PriceVat,
    )

    internal data class VatLookup(
        val purchaseVat: PriceVat?,
        val salesVat: PriceVat?,
    )
}
