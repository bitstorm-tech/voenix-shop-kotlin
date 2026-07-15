package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

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
    override fun validate(): ValidationErrors = buildMap {
        if (purchaseVatId == null || purchaseVatId <= 0) {
            put("purchaseVatId", listOf("Purchase VAT id is required"))
        }
        if (salesVatId == null || salesVatId <= 0) {
            put("salesVatId", listOf("Sales VAT id is required"))
        }
        if (purchasePriceInputCents < 0) {
            put(
                "purchasePriceInputCents",
                listOf("Purchase price input must not be negative"),
            )
        }
        addPurchaseCostError()
        addSalesMarginPercentError()
        if (salesActiveRow == SalesActiveRow.TOTAL && salesTotalInputCents < 0) {
            put(
                "salesTotalInputCents",
                listOf("Sales total input must not be negative"),
            )
        }
    }

    private fun MutableMap<String, List<String>>.addPurchaseCostError() {
        when (purchaseActiveRow) {
            PurchaseActiveRow.COST ->
                if (purchaseCostInputCents < 0) {
                    put(
                        "purchaseCostInputCents",
                        listOf("Purchase cost input must not be negative"),
                    )
                }
            PurchaseActiveRow.COST_PERCENT ->
                when {
                    purchaseCostPercent < BigDecimal.ZERO ->
                        put(
                            "purchaseCostPercent",
                            listOf("Purchase cost percent must not be negative"),
                        )
                    PricePercentagePolicy.hasTooManyDecimalPlaces(purchaseCostPercent) ->
                        put(
                            "purchaseCostPercent",
                            listOf("Purchase cost percent must have at most two decimal places"),
                        )
                    purchaseCostPercent > PricePercentagePolicy.maxValue ->
                        put(
                            "purchaseCostPercent",
                            listOf("Purchase cost percent must not exceed 9999.99"),
                        )
                }
        }
    }

    private fun MutableMap<String, List<String>>.addSalesMarginPercentError() {
        if (salesActiveRow == SalesActiveRow.MARGIN_PERCENT) {
            when {
                PricePercentagePolicy.hasTooManyDecimalPlaces(salesMarginPercent) ->
                    put(
                        "salesMarginPercent",
                        listOf("Sales margin percent must have at most two decimal places"),
                    )
                salesMarginPercent.abs() > PricePercentagePolicy.maxValue ->
                    put(
                        "salesMarginPercent",
                        listOf("Sales margin percent must be between -9999.99 and 9999.99"),
                    )
            }
        }
    }
}
