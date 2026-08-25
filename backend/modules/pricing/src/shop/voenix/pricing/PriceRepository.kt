package shop.voenix.pricing

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write

/**
 * Price persistence. The standalone admin operations open their own short transaction; the
 * `...InCurrentTransaction` writes join the transaction their caller has already opened, which is
 * what lets an owning module write itself and its price atomically. Both paths share one column
 * mapping, so a stored price cannot depend on which caller wrote it.
 */
internal class PriceRepository(private val database: Database) {
    suspend fun find(id: Long): PriceInput? = database.read {
        Prices.selectAll().where { Prices.id eq id }.singleOrNull()?.toPriceInput()
    }

    suspend fun find(ids: Set<Long>): Map<Long, PriceInput> {
        if (ids.isEmpty()) return emptyMap()
        return database.read {
            Prices.selectAll()
                .where { Prices.id inList ids }
                .associate { row -> row[Prices.id].value to row.toPriceInput() }
        }
    }

    suspend fun exists(id: Long): Boolean = database.read {
        Prices.selectAll().where { Prices.id eq id }.limit(1).any()
    }

    suspend fun insert(input: PriceInput): Long = database.write {
        insertInCurrentTransaction(input)
    }

    suspend fun update(
        id: Long,
        input: PriceInput,
    ): Int = database.write { updateInCurrentTransaction(id, input) }

    fun insertInCurrentTransaction(input: PriceInput): Long {
        requireCurrentTransaction()
        return Prices.insertAndGetId { statement -> statement.copyFrom(input) }.value
    }

    fun updateInCurrentTransaction(
        id: Long,
        input: PriceInput,
    ): Int {
        requireCurrentTransaction()
        return Prices.update({ Prices.id eq id }) { statement -> statement.copyFrom(input) }
    }

    fun deleteInCurrentTransaction(id: Long): Int {
        requireCurrentTransaction()
        return Prices.deleteWhere { Prices.id eq id }
    }

    private fun requireCurrentTransaction() {
        checkNotNull(TransactionManager.currentOrNull()) {
            "PriceCatalog write operations must be called inside an Exposed transaction"
        }
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
            discountType = this[Prices.discountType],
            discountValue = this[Prices.discountValue],
        )

    private fun UpdateBuilder<*>.copyFrom(input: PriceInput) {
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
        this[Prices.discountType] = input.discountType
        this[Prices.discountValue] = input.discountValue
    }
}

internal object Prices : LongIdTable("prices") {
    val purchaseVatId = long("purchase_vat_id")
    val purchaseCalculationMode =
        enumerationByName<PriceCalculationMode>("purchase_calculation_mode", MODE_LENGTH)
    val purchaseActiveRow =
        enumerationByName<PurchaseActiveRow>("purchase_active_row", ACTIVE_ROW_LENGTH)
    val purchasePriceInputCents = integer("purchase_price_input_cents")
    val purchaseCostInputCents = integer("purchase_cost_input_cents")
    val purchaseCostPercent =
        decimal(
            "purchase_cost_percent",
            PricePercentagePolicy.PRECISION,
            PricePercentagePolicy.SCALE,
        )
    val salesVatId = long("sales_vat_id")
    val salesCalculationMode =
        enumerationByName<PriceCalculationMode>("sales_calculation_mode", MODE_LENGTH)
    val salesActiveRow = enumerationByName<SalesActiveRow>("sales_active_row", ACTIVE_ROW_LENGTH)
    val salesMarginInputCents = integer("sales_margin_input_cents")
    val salesMarginPercent =
        decimal(
            "sales_margin_percent",
            PricePercentagePolicy.PRECISION,
            PricePercentagePolicy.SCALE,
        )
    val salesTotalInputCents = integer("sales_total_input_cents")

    // The discount type stays a plain string, exactly as PriceInput carries it; the database check
    // constraint is the authority on the two allowed values.
    val discountType = text("discount_type").nullable()
    val discountValue = decimal("discount_value", DISCOUNT_PRECISION, DISCOUNT_SCALE).nullable()

    private const val MODE_LENGTH = 5
    private const val ACTIVE_ROW_LENGTH = 20
    private const val DISCOUNT_PRECISION = 12
    private const val DISCOUNT_SCALE = 2
}
