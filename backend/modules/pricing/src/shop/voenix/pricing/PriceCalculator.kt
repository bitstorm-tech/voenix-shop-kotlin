package shop.voenix.pricing

import java.math.BigDecimal
import java.math.RoundingMode
import shop.voenix.vat.Vat

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
            salesMargin = sales.margin,
            calculatedSalesMarginPercent = sales.marginPercent,
            salesTotal = sales.total,
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
            SalesActiveRow.TOTAL -> {
                val total =
                    fromInput(
                        input.salesTotalInputCents,
                        input.salesCalculationMode,
                        vatPercent,
                    )
                val marginInput =
                    Math.subtractExact(
                        modeAmount(total, input.salesCalculationMode),
                        baseAmount,
                    )
                SalesCalculation(
                    margin = fromInput(marginInput, input.salesCalculationMode, vatPercent),
                    total = total,
                    marginPercent = calculatePercent(marginInput, baseAmount),
                )
            }
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
                .multiply(HUNDRED)
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

    private val HUNDRED = BigDecimal.valueOf(100)
    private const val PERCENT_SHIFT = 2
}
