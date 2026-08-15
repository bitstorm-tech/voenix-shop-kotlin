package shop.voenix.pricing

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.vat.Vat
import shop.voenix.vat.VatReader

internal class PriceService(
    private val repository: PriceRepository,
    private val vats: VatReader,
) : PriceOperations, PriceCatalog {
    override suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice> =
        prepare(input)

    override suspend fun prepare(input: PriceInput): OperationResult<CalculatedPrice> =
        withValidInput(input) { normalized -> calculateWithCurrentVats(null, normalized) }

    override fun storeInTransaction(price: CalculatedPrice): Long =
        repository.insertInCurrentTransaction(price.toPriceInput())

    override fun replaceInTransaction(
        id: Long,
        price: CalculatedPrice,
    ): Boolean = repository.updateInCurrentTransaction(id, price.toPriceInput()) > 0

    override fun deleteInTransaction(id: Long): Boolean =
        repository.deleteInCurrentTransaction(id) > 0

    override suspend fun find(ids: Set<Long>): Map<Long, CalculatedPrice> {
        val stored = repository.find(ids)
        if (stored.isEmpty()) return emptyMap()
        val vatsById =
            vats.find(
                stored.values.flatMapTo(mutableSetOf()) { input ->
                    listOf(checkNotNull(input.purchaseVatId), checkNotNull(input.salesVatId))
                }
            )
        return stored.mapValues { (id, input) ->
            PriceCalculator.calculate(
                id,
                input,
                checkNotNull(vatsById[input.purchaseVatId]),
                checkNotNull(vatsById[input.salesVatId]),
            )
        }
    }

    override suspend fun create(input: PriceInput): OperationResult<CalculatedPrice> =
        withValidInput(input) { normalized ->
            when (val calculated = calculateWithCurrentVats(null, normalized)) {
                is OperationResult.Success -> {
                    val id = repository.insert(normalized)
                    OperationResult.Success(calculated.value.copy(id = id))
                }
                else -> calculated
            }
        }

    override suspend fun default(): OperationResult<CalculatedPrice> =
        logger.databaseOperation(
            "Error while building the default price",
            OperationResult.UnexpectedFailure,
        ) {
            val vat =
                vats.list().let { availableVats ->
                    availableVats.firstOrNull(Vat::isDefault) ?: availableVats.minByOrNull(Vat::id)
                } ?: return@databaseOperation OperationResult.Invalid(emptyMap())
            val input =
                PriceInput(purchaseVatId = vat.id, salesVatId = vat.id).normalizeInactiveFields()
            OperationResult.Success(PriceCalculator.calculate(null, input, vat, vat))
        }

    override suspend fun get(id: Long): OperationResult<CalculatedPrice> =
        logger.databaseOperation(
            "Error while reading price $id",
            OperationResult.UnexpectedFailure,
        ) {
            val input = repository.find(id) ?: return@databaseOperation OperationResult.NotFound
            val purchaseVatId = checkNotNull(input.purchaseVatId)
            val salesVatId = checkNotNull(input.salesVatId)
            val vatsById = vats.find(setOf(purchaseVatId, salesVatId))
            calculatedResult(
                id,
                input,
                checkNotNull(vatsById[purchaseVatId]),
                checkNotNull(vatsById[salesVatId]),
            )
        }

    override suspend fun update(
        id: Long,
        input: PriceInput,
    ): OperationResult<CalculatedPrice> =
        withValidInput(input) { normalized ->
            if (!repository.exists(id)) return@withValidInput OperationResult.NotFound
            when (val calculated = calculateWithCurrentVats(id, normalized)) {
                is OperationResult.Success ->
                    if (repository.update(id, normalized) == 0) {
                        OperationResult.NotFound
                    } else {
                        calculated
                    }
                else -> calculated
            }
        }

    private suspend fun withValidInput(
        input: PriceInput,
        block: suspend (PriceInput) -> OperationResult<CalculatedPrice>,
    ): OperationResult<CalculatedPrice> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        val normalized = input.normalizeInactiveFields()
        return logger.databaseOperation(
            "Error while processing price",
            OperationResult.UnexpectedFailure,
        ) {
            block(normalized)
        }
    }

    private suspend fun calculateWithCurrentVats(
        id: Long?,
        input: PriceInput,
    ): OperationResult<CalculatedPrice> {
        val purchaseVatId = checkNotNull(input.purchaseVatId)
        val salesVatId = checkNotNull(input.salesVatId)
        val vatsById = vats.find(setOf(purchaseVatId, salesVatId))
        val purchaseVat = vatsById[purchaseVatId]
        val salesVat = vatsById[salesVatId]
        val vatErrors = buildMap {
            if (purchaseVat == null) {
                put("purchaseVatId", listOf("Purchase VAT not found"))
            }
            if (salesVat == null) {
                put("salesVatId", listOf("Sales VAT not found"))
            }
        }
        if (vatErrors.isNotEmpty()) return OperationResult.Invalid(vatErrors)
        return calculatedResult(
            id,
            input,
            checkNotNull(purchaseVat),
            checkNotNull(salesVat),
        )
    }

    private fun calculatedResult(
        id: Long?,
        input: PriceInput,
        purchaseVat: Vat,
        salesVat: Vat,
    ): OperationResult<CalculatedPrice> {
        val price = PriceCalculator.calculate(id, input, purchaseVat, salesVat)
        if (price.salesTotal.net >= 0 && price.salesTotal.gross >= 0) {
            return OperationResult.Success(price)
        }
        val field =
            when (input.salesActiveRow) {
                SalesActiveRow.MARGIN -> "salesMarginInputCents"
                SalesActiveRow.MARGIN_PERCENT -> "salesMarginPercent"
                SalesActiveRow.TOTAL -> "salesTotalInputCents"
            }
        return OperationResult.Invalid(mapOf(field to listOf("Sales total must not be negative")))
    }

    private fun PriceInput.normalizeInactiveFields(): PriceInput =
        copy(
            purchaseCostInputCents =
                if (purchaseActiveRow == PurchaseActiveRow.COST) purchaseCostInputCents else 0,
            purchaseCostPercent =
                if (purchaseActiveRow == PurchaseActiveRow.COST_PERCENT) {
                    PricePercentagePolicy.normalize(purchaseCostPercent)
                } else {
                    PricePercentagePolicy.ZERO
                },
            salesMarginInputCents =
                if (salesActiveRow == SalesActiveRow.MARGIN) salesMarginInputCents else 0,
            salesMarginPercent =
                if (salesActiveRow == SalesActiveRow.MARGIN_PERCENT) {
                    PricePercentagePolicy.normalize(salesMarginPercent)
                } else {
                    PricePercentagePolicy.ZERO
                },
            salesTotalInputCents =
                if (salesActiveRow == SalesActiveRow.TOTAL) salesTotalInputCents else 0,
        )

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PriceService::class.java)
    }
}

internal interface PriceOperations {
    suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice>

    suspend fun create(input: PriceInput): OperationResult<CalculatedPrice>

    suspend fun default(): OperationResult<CalculatedPrice>

    suspend fun get(id: Long): OperationResult<CalculatedPrice>

    suspend fun update(
        id: Long,
        input: PriceInput,
    ): OperationResult<CalculatedPrice>
}
