package shop.voenix.pricing

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.vat.Vat
import shop.voenix.vat.VatRepository

class PriceService(
    private val repository: PriceRepository,
    private val vatRepository: VatRepository,
) : PriceOperations {
    override suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice> =
        withValidInput(input) { normalized -> calculateWithCurrentVats(null, normalized) }

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
        withUnexpectedFailureHandling("Error while building the default price") {
            val vat =
                vatRepository.list().let { vats ->
                    vats.firstOrNull(Vat::isDefault) ?: vats.minByOrNull(Vat::id)
                } ?: return@withUnexpectedFailureHandling OperationResult.Invalid(emptyMap())
            val input =
                PriceInput(purchaseVatId = vat.id, salesVatId = vat.id).normalizeInactiveFields()
            OperationResult.Success(PriceCalculator.calculate(null, input, vat, vat))
        }

    override suspend fun get(id: Long): OperationResult<CalculatedPrice> =
        withUnexpectedFailureHandling("Error while reading price $id") {
            val input =
                repository.find(id) ?: return@withUnexpectedFailureHandling OperationResult.NotFound
            val vats = findVats(input)
            calculatedResult(
                id,
                input,
                checkNotNull(vats.purchaseVat),
                checkNotNull(vats.salesVat),
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
        return withUnexpectedFailureHandling("Error while processing price") { block(normalized) }
    }

    private suspend fun calculateWithCurrentVats(
        id: Long?,
        input: PriceInput,
    ): OperationResult<CalculatedPrice> {
        val vats = findVats(input)
        val vatErrors = buildMap {
            if (vats.purchaseVat == null) {
                put("purchaseVatId", listOf("Purchase VAT not found"))
            }
            if (vats.salesVat == null) {
                put("salesVatId", listOf("Sales VAT not found"))
            }
        }
        if (vatErrors.isNotEmpty()) return OperationResult.Invalid(vatErrors)
        return calculatedResult(
            id,
            input,
            checkNotNull(vats.purchaseVat),
            checkNotNull(vats.salesVat),
        )
    }

    private suspend fun findVats(input: PriceInput): VatLookup {
        val purchaseVatId = checkNotNull(input.purchaseVatId)
        val salesVatId = checkNotNull(input.salesVatId)
        val vats = vatRepository.find(setOf(purchaseVatId, salesVatId))
        return VatLookup(vats[purchaseVatId], vats[salesVatId])
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
                    PricePercentagePolicy.zero
                },
            salesMarginInputCents =
                if (salesActiveRow == SalesActiveRow.MARGIN) salesMarginInputCents else 0,
            salesMarginPercent =
                if (salesActiveRow == SalesActiveRow.MARGIN_PERCENT) {
                    PricePercentagePolicy.normalize(salesMarginPercent)
                } else {
                    PricePercentagePolicy.zero
                },
            salesTotalInputCents =
                if (salesActiveRow == SalesActiveRow.TOTAL) salesTotalInputCents else 0,
        )

    private suspend fun <T> withUnexpectedFailureHandling(
        message: String,
        block: suspend () -> OperationResult<T>,
    ): OperationResult<T> = runCatching {
        block()
    }
        .getOrElse { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            logger.error(message, failure)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PriceService::class.java)
    }

    private data class VatLookup(
        val purchaseVat: Vat?,
        val salesVat: Vat?,
    )
}
