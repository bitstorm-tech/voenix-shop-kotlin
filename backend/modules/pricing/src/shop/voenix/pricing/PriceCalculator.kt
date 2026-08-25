package shop.voenix.pricing

import java.math.BigDecimal
import java.math.RoundingMode
import shop.voenix.vat.Vat

/**
 * The object is one function over Detekt's limit. Every function here is one step of the single
 * calculation, and the twelfth exists so that the discount and the `TOTAL` row derive a margin the
 * same way instead of twice. Splitting the object would separate steps that only make sense
 * together.
 */
@Suppress("TooManyFunctions")
internal object PriceCalculator {
    fun calculate(
        id: Long?,
        input: PriceInput,
        purchaseVat: Vat,
        salesVat: Vat,
    ): CalculatedPrice {
        val purchase = calculatePurchase(input, purchaseVat.percent)
        val salesBaseAmount = modeAmount(purchase.total, input.salesCalculationMode)
        val sales = calculateSales(input, salesVat.percent, salesBaseAmount)
        val discount =
            input.discountType?.let { type ->
                PriceDiscount(
                    discountType = enumValueOf(type),
                    discountValue = checkNotNull(input.discountValue),
                )
            }
        val effectiveSales =
            applyDiscount(sales, discount, input, salesVat.percent, salesBaseAmount)

        return CalculatedPrice(
            id = id,
            purchaseVatId = checkNotNull(input.purchaseVatId),
            purchaseCalculationMode = input.purchaseCalculationMode,
            purchaseActiveRow = input.purchaseActiveRow,
            purchasePriceInputCents = input.purchasePriceInputCents,
            purchaseCostInputCents = input.purchaseCostInputCents,
            purchaseCostPercent = input.purchaseCostPercent,
            salesVatId = checkNotNull(input.salesVatId),
            salesCalculationMode = input.salesCalculationMode,
            salesActiveRow = input.salesActiveRow,
            salesMarginInputCents = input.salesMarginInputCents,
            salesMarginPercent = input.salesMarginPercent,
            salesTotalInputCents = input.salesTotalInputCents,
            purchaseVat = purchaseVat,
            purchasePrice = purchase.price,
            purchaseCost = purchase.cost,
            calculatedPurchaseCostPercent = purchase.costPercent,
            purchaseTotal = purchase.total,
            salesVat = salesVat,
            regularSalesMargin = sales.margin,
            calculatedRegularSalesMarginPercent = sales.marginPercent,
            regularSalesTotal = sales.total,
            discount = discount,
            salesDiscount = subtract(sales.total, effectiveSales.total),
            salesMargin = effectiveSales.margin,
            calculatedSalesMarginPercent = effectiveSales.marginPercent,
            salesTotal = effectiveSales.total,
        )
    }

    /**
     * Reduces the regular sales total by the discount. The saving is taken from the gross amount,
     * and the effective net and tax are derived from the effective gross with the same arithmetic
     * as every other amount, so `net + tax == gross` still holds exactly. The effective margin is
     * derived from the effective total the same way [SalesActiveRow.TOTAL] derives the regular one.
     *
     * Without a discount the regular calculation is returned unchanged, which is what makes
     * `salesTotal == regularSalesTotal` and `salesMargin == regularSalesMargin` exact.
     *
     * The saving is capped at the regular gross, so the effective total is never negative. The cap
     * matters for a stored fixed amount: [PriceService] rejects one that is larger than the price
     * it reduces on write, but a later VAT change can shrink the regular gross below it, and a read
     * must still answer a price the shop can charge — `0` in that case.
     */
    private fun applyDiscount(
        sales: SalesCalculation,
        discount: PriceDiscount?,
        input: PriceInput,
        vatPercent: Int,
        baseAmount: Int,
    ): SalesCalculation {
        if (discount == null) return sales
        val saving =
            when (discount.discountType) {
                PriceDiscountType.PERCENTAGE ->
                    roundToCents(
                        sales.total.gross.toBigDecimal() *
                            discount.discountValue.movePointLeft(PERCENT_SHIFT)
                    )
                PriceDiscountType.FIXED_AMOUNT ->
                    discount.discountValue.min(sales.total.gross.toBigDecimal()).intValueExact()
            }
        return salesFromTotal(
            fromInput(sales.total.gross - saving, PriceCalculationMode.GROSS, vatPercent),
            input.salesCalculationMode,
            vatPercent,
            baseAmount,
        )
    }

    /**
     * Derives margin and margin percent from a known sales total, the way [SalesActiveRow.TOTAL]
     * does: the margin is what is left of the total above the purchase [baseAmount].
     */
    private fun salesFromTotal(
        total: PriceAmount,
        mode: PriceCalculationMode,
        vatPercent: Int,
        baseAmount: Int,
    ): SalesCalculation {
        val marginInput = Math.subtractExact(modeAmount(total, mode), baseAmount)
        return SalesCalculation(
            margin = fromInput(marginInput, mode, vatPercent),
            total = total,
            marginPercent = calculatePercent(marginInput, baseAmount),
        )
    }

    private fun calculatePurchase(input: PriceInput, vatPercent: Int): PurchaseCalculation {
        val purchasePrice =
            fromInput(
                input.purchasePriceInputCents,
                input.purchaseCalculationMode,
                vatPercent,
            )
        val purchaseCost =
            when (input.purchaseActiveRow) {
                PurchaseActiveRow.COST ->
                    fromInput(
                        input.purchaseCostInputCents,
                        input.purchaseCalculationMode,
                        vatPercent,
                    )
                PurchaseActiveRow.COST_PERCENT ->
                    fromInput(
                        roundToCents(
                            modeAmount(purchasePrice, input.purchaseCalculationMode)
                                .toBigDecimal() *
                                input.purchaseCostPercent.movePointLeft(PERCENT_SHIFT)
                        ),
                        input.purchaseCalculationMode,
                        vatPercent,
                    )
            }
        val calculatedPurchaseCostPercent =
            when (input.purchaseActiveRow) {
                PurchaseActiveRow.COST_PERCENT -> roundPercent(input.purchaseCostPercent)
                PurchaseActiveRow.COST ->
                    calculatePercent(
                        modeAmount(purchaseCost, input.purchaseCalculationMode),
                        modeAmount(purchasePrice, input.purchaseCalculationMode),
                    )
            }
        val purchaseTotal = add(purchasePrice, purchaseCost)
        return PurchaseCalculation(
            price = purchasePrice,
            cost = purchaseCost,
            costPercent = calculatedPurchaseCostPercent,
            total = purchaseTotal,
        )
    }

    private fun calculateSales(
        input: PriceInput,
        vatPercent: Int,
        baseAmount: Int,
    ): SalesCalculation =
        when (input.salesActiveRow) {
            SalesActiveRow.MARGIN -> {
                val margin =
                    fromInput(
                        input.salesMarginInputCents,
                        input.salesCalculationMode,
                        vatPercent,
                    )
                val totalInput =
                    Math.addExact(
                        baseAmount,
                        modeAmount(margin, input.salesCalculationMode),
                    )
                SalesCalculation(
                    margin = margin,
                    total = fromInput(totalInput, input.salesCalculationMode, vatPercent),
                    marginPercent =
                        calculatePercent(
                            modeAmount(margin, input.salesCalculationMode),
                            baseAmount,
                        ),
                )
            }
            SalesActiveRow.MARGIN_PERCENT -> {
                val marginInput =
                    roundToCents(
                        baseAmount.toBigDecimal() *
                            input.salesMarginPercent.movePointLeft(PERCENT_SHIFT)
                    )
                SalesCalculation(
                    margin = fromInput(marginInput, input.salesCalculationMode, vatPercent),
                    total =
                        fromInput(
                            Math.addExact(baseAmount, marginInput),
                            input.salesCalculationMode,
                            vatPercent,
                        ),
                    marginPercent = roundPercent(input.salesMarginPercent),
                )
            }
            SalesActiveRow.TOTAL ->
                salesFromTotal(
                    fromInput(
                        input.salesTotalInputCents,
                        input.salesCalculationMode,
                        vatPercent,
                    ),
                    input.salesCalculationMode,
                    vatPercent,
                    baseAmount,
                )
        }

    private fun fromInput(
        inputCents: Int,
        mode: PriceCalculationMode,
        vatPercent: Int,
    ): PriceAmount =
        when (mode) {
            PriceCalculationMode.NET -> {
                val tax =
                    roundToCents(
                        inputCents.toBigDecimal() *
                            vatPercent.toBigDecimal().movePointLeft(PERCENT_SHIFT)
                    )
                PriceAmount(
                    net = inputCents,
                    tax = tax,
                    gross = Math.addExact(inputCents, tax),
                )
            }
            PriceCalculationMode.GROSS -> {
                val gross = inputCents
                val divisor =
                    BigDecimal.ONE + vatPercent.toBigDecimal().movePointLeft(PERCENT_SHIFT)
                val net =
                    gross.toBigDecimal().divide(divisor, 0, RoundingMode.HALF_UP).intValueExact()
                PriceAmount(net = net, tax = Math.subtractExact(gross, net), gross = gross)
            }
        }

    private fun add(left: PriceAmount, right: PriceAmount): PriceAmount =
        PriceAmount(
            net = Math.addExact(left.net, right.net),
            tax = Math.addExact(left.tax, right.tax),
            gross = Math.addExact(left.gross, right.gross),
        )

    private fun subtract(left: PriceAmount, right: PriceAmount): PriceAmount =
        PriceAmount(
            net = Math.subtractExact(left.net, right.net),
            tax = Math.subtractExact(left.tax, right.tax),
            gross = Math.subtractExact(left.gross, right.gross),
        )

    private fun modeAmount(amount: PriceAmount, mode: PriceCalculationMode): Int =
        when (mode) {
            PriceCalculationMode.NET -> amount.net
            PriceCalculationMode.GROSS -> amount.gross
        }

    private fun calculatePercent(amount: Int, baseAmount: Int): BigDecimal =
        if (baseAmount == 0) {
            BigDecimal.ZERO
        } else {
            amount
                .toBigDecimal()
                .multiply(PricePercentagePolicy.HUNDRED)
                .divide(
                    baseAmount.toBigDecimal(),
                    PricePercentagePolicy.SCALE,
                    RoundingMode.HALF_UP,
                )
        }

    private fun roundPercent(value: BigDecimal): BigDecimal =
        value.setScale(PricePercentagePolicy.SCALE, RoundingMode.HALF_UP)

    private fun roundToCents(value: BigDecimal): Int =
        value.setScale(0, RoundingMode.HALF_UP).intValueExact()

    private const val PERCENT_SHIFT = 2

    private data class SalesCalculation(
        val margin: PriceAmount,
        val total: PriceAmount,
        val marginPercent: BigDecimal,
    )

    private data class PurchaseCalculation(
        val price: PriceAmount,
        val cost: PriceAmount,
        val costPercent: BigDecimal,
        val total: PriceAmount,
    )
}
