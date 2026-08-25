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
 *
 * The discount is the pair [discountType] and [discountValue]: both absent means no discount. Like
 * `PromotionInput`, the type stays a `String` here so that an unknown value becomes a field error
 * instead of failing deserialization.
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
    public val discountType: String? = null,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    public val discountValue: BigDecimal? = null,
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
        addDiscountErrors()
    }

    private fun ValidationErrorsBuilder.addDiscountErrors() {
        if (discountType == null) {
            if (discountValue != null) {
                add("discountType", "Discount type is required")
            }
            return
        }
        if (discountValue == null) {
            add("discountValue", "Discount value is required")
            return
        }
        val type = PriceDiscountType.entries.firstOrNull { it.name == discountType }
        if (type == null) {
            add("discountType", "Discount type must be PERCENTAGE or FIXED_AMOUNT")
            return
        }
        when {
            discountValue <= BigDecimal.ZERO ->
                add("discountValue", "Discount value must be positive")
            type == PriceDiscountType.PERCENTAGE && discountValue > PricePercentagePolicy.HUNDRED ->
                add(
                    "discountValue",
                    "Discount value must be at most 100 for a percentage discount",
                )
            type == PriceDiscountType.PERCENTAGE &&
                PricePercentagePolicy.hasTooManyDecimalPlaces(discountValue) ->
                add("discountValue", "Discount value must have at most two decimal places")
            type == PriceDiscountType.FIXED_AMOUNT &&
                discountValue.stripTrailingZeros().scale() > 0 ->
                add(
                    "discountValue",
                    "Discount value must be whole cents for a fixed amount discount",
                )
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
        discountType = discount?.discountType?.name,
        discountValue = discount?.discountValue,
    )
