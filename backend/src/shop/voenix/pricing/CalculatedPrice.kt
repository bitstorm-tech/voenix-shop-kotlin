package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
data class CalculatedPrice(
    val id: Long?,
    val purchaseVatId: Long,
    val purchaseCalculationMode: PriceCalculationMode,
    val purchaseActiveRow: PurchaseActiveRow,
    val purchasePriceInputCents: Int,
    val purchaseCostInputCents: Int,
    @Serializable(with = BigDecimalJsonNumberSerializer::class) val purchaseCostPercent: BigDecimal,
    val salesVatId: Long,
    val salesCalculationMode: PriceCalculationMode,
    val salesActiveRow: SalesActiveRow,
    val salesMarginInputCents: Int,
    @Serializable(with = BigDecimalJsonNumberSerializer::class) val salesMarginPercent: BigDecimal,
    val salesTotalInputCents: Int,
    val purchaseVat: PriceVat,
    val purchasePrice: PriceAmount,
    val purchaseCost: PriceAmount,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    val calculatedPurchaseCostPercent: BigDecimal,
    val purchaseTotal: PriceAmount,
    val salesVat: PriceVat,
    val salesMargin: PriceAmount,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    val calculatedSalesMarginPercent: BigDecimal,
    val salesTotal: PriceAmount,
)
