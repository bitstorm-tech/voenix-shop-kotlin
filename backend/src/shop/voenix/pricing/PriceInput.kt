package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.http.RequestValidationInput

@Serializable
data class PriceInput(
    val purchaseVatId: Long? = null,
    val purchaseCalculationMode: PriceCalculationMode = PriceCalculationMode.NET,
    val purchaseActiveRow: PurchaseActiveRow = PurchaseActiveRow.COST,
    val purchasePriceInputCents: Int = 0,
    val purchaseCostInputCents: Int = 0,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    val purchaseCostPercent: BigDecimal = BigDecimal.ZERO,
    val salesVatId: Long? = null,
    val salesCalculationMode: PriceCalculationMode = PriceCalculationMode.GROSS,
    val salesActiveRow: SalesActiveRow = SalesActiveRow.TOTAL,
    val salesMarginInputCents: Int = 0,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    val salesMarginPercent: BigDecimal = BigDecimal.ZERO,
    val salesTotalInputCents: Int = 0,
) : RequestValidationInput {
    override fun validationErrors(): Map<String, List<String>> = PriceInputValidator.validate(this)
}
