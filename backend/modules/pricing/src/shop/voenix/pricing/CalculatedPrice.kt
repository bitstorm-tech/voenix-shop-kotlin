package shop.voenix.pricing

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import shop.voenix.json.BigDecimalJsonNumberSerializer
import shop.voenix.vat.Vat

/**
 * One price with its stored calculation inputs and every value derived from them.
 *
 * `id` is `null` while a price has not been stored yet: [PriceCatalog.prepare] calculates without
 * touching the database, and only [PriceCatalog.storeInTransaction] mints an id. The derived
 * amounts are recalculated on every read, so a later VAT change is reflected immediately.
 *
 * Naming rule for the sales side: an unqualified name is the **effective** value, what the customer
 * actually pays, and a `regular*` name is the value **before the discount**. Without a discount the
 * two are equal and [salesDiscount] is all zeros, so a consumer that only ever reads [salesTotal]
 * charges the discounted price by construction.
 */
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
    public val regularSalesMargin: PriceAmount,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val calculatedRegularSalesMarginPercent: BigDecimal,
    public val regularSalesTotal: PriceAmount,
    public val discount: PriceDiscount?,
    public val salesDiscount: PriceAmount,
    public val salesMargin: PriceAmount,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val calculatedSalesMarginPercent: BigDecimal,
    public val salesTotal: PriceAmount,
)

@Serializable
public data class PriceAmount(
    public val net: Int,
    public val tax: Int,
    public val gross: Int,
)

/**
 * The reduction configured on a price: a percentage of the regular gross sales total, or a fixed
 * number of cents. It is not a coupon: it has no activity window, no name, and no limit, and it is
 * rejected instead of capped when it is larger than the price it reduces.
 */
@Serializable
public data class PriceDiscount(
    public val discountType: PriceDiscountType,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val discountValue: BigDecimal,
)

@Serializable
public enum class PriceDiscountType {
    PERCENTAGE,
    FIXED_AMOUNT,
}

@Serializable
public enum class PriceCalculationMode {
    NET,
    GROSS,
}

@Serializable
public enum class PurchaseActiveRow {
    COST,
    COST_PERCENT,
}

@Serializable
public enum class SalesActiveRow {
    MARGIN,
    MARGIN_PERCENT,
    TOTAL,
}
