package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.json.BigDecimalJsonNumberSerializer
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * Everything a price is calculated from. It is the only price payload another module submits: a
 * consumer such as Article embeds it in its own request and hands it to [PriceCatalog.prepare].
 * Inactive rows are ignored during validation and replaced with zero afterwards, so the same input
 * always produces the same stored row.
 */
@Serializable
public data class PriceInput(
    public val purchaseVatId: Long? = null,
    public val purchaseCalculationMode: PriceCalculationMode = PriceCalculationMode.NET,
    public val purchaseActiveRow: PurchaseActiveRow = PurchaseActiveRow.COST,
    public val purchasePriceInputCents: Int = 0,
    public val purchaseCostInputCents: Int = 0,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val purchaseCostPercent: BigDecimal = BigDecimal.ZERO,
    public val salesVatId: Long? = null,
    public val salesCalculationMode: PriceCalculationMode = PriceCalculationMode.GROSS,
    public val salesActiveRow: SalesActiveRow = SalesActiveRow.TOTAL,
    public val salesMarginInputCents: Int = 0,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val salesMarginPercent: BigDecimal = BigDecimal.ZERO,
    public val salesTotalInputCents: Int = 0,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (purchaseVatId == null || purchaseVatId <= 0) {
            add("purchaseVatId", "Purchase VAT id is required")
        }
        if (salesVatId == null || salesVatId <= 0) {
            add("salesVatId", "Sales VAT id is required")
        }
        if (purchasePriceInputCents < 0) {
            add("purchasePriceInputCents", "Purchase price input must not be negative")
        }
        addPurchaseCostError()
        addSalesMarginPercentError()
        if (salesActiveRow == SalesActiveRow.TOTAL && salesTotalInputCents < 0) {
            add("salesTotalInputCents", "Sales total input must not be negative")
        }
    }

    private fun ValidationErrorsBuilder.addPurchaseCostError() {
        when (purchaseActiveRow) {
            PurchaseActiveRow.COST ->
                if (purchaseCostInputCents < 0) {
                    add("purchaseCostInputCents", "Purchase cost input must not be negative")
                }
            PurchaseActiveRow.COST_PERCENT ->
                when {
                    purchaseCostPercent < BigDecimal.ZERO ->
                        add("purchaseCostPercent", "Purchase cost percent must not be negative")
                    PricePercentagePolicy.hasTooManyDecimalPlaces(purchaseCostPercent) ->
                        add(
                            "purchaseCostPercent",
                            "Purchase cost percent must have at most two decimal places",
                        )
                    purchaseCostPercent > PricePercentagePolicy.MAX_VALUE ->
                        add("purchaseCostPercent", "Purchase cost percent must not exceed 9999.99")
                }
        }
    }

    private fun ValidationErrorsBuilder.addSalesMarginPercentError() {
        if (salesActiveRow == SalesActiveRow.MARGIN_PERCENT) {
            when {
                PricePercentagePolicy.hasTooManyDecimalPlaces(salesMarginPercent) ->
                    add(
                        "salesMarginPercent",
                        "Sales margin percent must have at most two decimal places",
                    )
                salesMarginPercent.abs() > PricePercentagePolicy.MAX_VALUE ->
                    add(
                        "salesMarginPercent",
                        "Sales margin percent must be between -9999.99 and 9999.99",
                    )
            }
        }
    }
}

/**
 * The stored half of a calculated price. The `prices` table keeps only calculation inputs, so
 * writing a [CalculatedPrice] means writing exactly the validated and normalized [PriceInput] it
 * was calculated from. This is why persistence has one column mapping instead of two.
 */
internal fun CalculatedPrice.toPriceInput(): PriceInput =
    PriceInput(
        purchaseVatId = purchaseVatId,
        purchaseCalculationMode = purchaseCalculationMode,
        purchaseActiveRow = purchaseActiveRow,
        purchasePriceInputCents = purchasePriceInputCents,
        purchaseCostInputCents = purchaseCostInputCents,
        purchaseCostPercent = purchaseCostPercent,
        salesVatId = salesVatId,
        salesCalculationMode = salesCalculationMode,
        salesActiveRow = salesActiveRow,
        salesMarginInputCents = salesMarginInputCents,
        salesMarginPercent = salesMarginPercent,
        salesTotalInputCents = salesTotalInputCents,
    )
