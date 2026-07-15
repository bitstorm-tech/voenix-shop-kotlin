package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.vat.Vat

@Serializable
public data class CalculatedPrice(
    public val id: Long?,
    public val purchaseVatId: Long,
    public val purchaseCalculationMode: PriceCalculationMode,
    public val purchaseActiveRow: PurchaseActiveRow,
    public val purchasePriceInputCents: Int,
    public val purchaseCostInputCents: Int,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val purchaseCostPercent: BigDecimal,
    public val salesVatId: Long,
    public val salesCalculationMode: PriceCalculationMode,
    public val salesActiveRow: SalesActiveRow,
    public val salesMarginInputCents: Int,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val salesMarginPercent: BigDecimal,
    public val salesTotalInputCents: Int,
    public val purchaseVat: Vat,
    public val purchasePrice: PriceAmount,
    public val purchaseCost: PriceAmount,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val calculatedPurchaseCostPercent: BigDecimal,
    public val purchaseTotal: PriceAmount,
    public val salesVat: Vat,
    public val salesMargin: PriceAmount,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val calculatedSalesMarginPercent: BigDecimal,
    public val salesTotal: PriceAmount,
)
