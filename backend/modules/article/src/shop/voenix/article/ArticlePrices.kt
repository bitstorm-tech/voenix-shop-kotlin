package shop.voenix.article

import shop.voenix.operation.OperationResult
import shop.voenix.operation.asFailure
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.pricing.PriceInput

/** The path a submitted price and its nested field errors are reported under. */
internal const val PRICE_FIELD: String = "price"

/**
 * Validates, resolves, and calculates the submitted price without touching the database.
 *
 * The field errors of the price are reported under the path the client sent them at, so
 * `purchaseVatId` becomes `price.purchaseVatId` and a client never has to guess which of its two
 * nested objects a rejected field belongs to. Every article slice prices its article this way, so
 * the mapping lives here instead of once per slice.
 */
internal suspend fun preparePrice(
    prices: PriceCatalog,
    input: PriceInput?,
): OperationResult<CalculatedPrice?> =
    when (input) {
        null -> OperationResult.Success(null)
        else ->
            when (val prepared = prices.prepare(input)) {
                is OperationResult.Success -> OperationResult.Success(prepared.value)
                is OperationResult.Invalid ->
                    OperationResult.Invalid(
                        prepared.errors.mapKeys { (field, _) -> "$PRICE_FIELD.$field" }
                    )
                else -> prepared.asFailure()
            }
    }
