package shop.voenix.pricing

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import shop.voenix.vat.Vat

internal class PriceCalculatorTest {
    @Test
    fun `net purchase price derives tax and gross`() {
        val result =
            PriceCalculator.calculate(
                id = null,
                input = priceInput(purchasePriceInputCents = 1_000),
                purchaseVat = standardVat,
                salesVat = standardVat,
            )

        assertEquals(PriceAmount(net = 1_000, tax = 190, gross = 1_190), result.purchasePrice)
    }

    @Test
    fun `gross purchase price derives net and tax`() {
        val result =
            calculate(
                priceInput(
                    purchaseCalculationMode = PriceCalculationMode.GROSS,
                    purchasePriceInputCents = 1_190,
                )
            )

        assertEquals(PriceAmount(net = 1_000, tax = 190, gross = 1_190), result.purchasePrice)
    }

    @Test
    fun `fixed purchase cost derives percent and component-wise total`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    purchaseCostInputCents = 250,
                )
            )

        assertEquals(PriceAmount(net = 250, tax = 48, gross = 298), result.purchaseCost)
        assertDecimal("25", result.calculatedPurchaseCostPercent)
        assertEquals(PriceAmount(net = 1_250, tax = 238, gross = 1_488), result.purchaseTotal)
    }

    @Test
    fun `purchase cost percent derives fixed cost`() {
        val result =
            calculate(
                priceInput(
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchasePriceInputCents = 1_000,
                    purchaseCostPercent = BigDecimal("25"),
                )
            )

        assertEquals(PriceAmount(net = 250, tax = 48, gross = 298), result.purchaseCost)
        assertEquals(PriceAmount(net = 1_250, tax = 238, gross = 1_488), result.purchaseTotal)
    }

    @Test
    fun `gross purchase cost modes use gross inputs`() {
        val fixedCost =
            calculate(
                priceInput(
                    purchaseCalculationMode = PriceCalculationMode.GROSS,
                    purchasePriceInputCents = 1_190,
                    purchaseCostInputCents = 119,
                )
            )
        val percentageCost =
            calculate(
                priceInput(
                    purchaseCalculationMode = PriceCalculationMode.GROSS,
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchasePriceInputCents = 1_190,
                    purchaseCostPercent = BigDecimal("10"),
                )
            )

        listOf(fixedCost, percentageCost).forEach { result ->
            assertEquals(PriceAmount(net = 100, tax = 19, gross = 119), result.purchaseCost)
            assertDecimal("10", result.calculatedPurchaseCostPercent)
            assertEquals(PriceAmount(net = 1_100, tax = 209, gross = 1_309), result.purchaseTotal)
        }
    }

    @Test
    fun `fixed sales margin derives total and percent`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesCalculationMode = PriceCalculationMode.NET,
                    salesActiveRow = SalesActiveRow.MARGIN,
                    salesMarginInputCents = 500,
                )
            )

        assertEquals(PriceAmount(net = 500, tax = 95, gross = 595), result.salesMargin)
        assertDecimal("50", result.calculatedSalesMarginPercent)
        assertEquals(PriceAmount(net = 1_500, tax = 285, gross = 1_785), result.salesTotal)
    }

    @Test
    fun `sales margin percent derives margin and total`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesCalculationMode = PriceCalculationMode.NET,
                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                    salesMarginPercent = BigDecimal("50"),
                )
            )

        assertEquals(PriceAmount(net = 500, tax = 95, gross = 595), result.salesMargin)
        assertEquals(PriceAmount(net = 1_500, tax = 285, gross = 1_785), result.salesTotal)
    }

    @Test
    fun `gross sales margin modes use the gross purchase total`() {
        val fixedMargin =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesActiveRow = SalesActiveRow.MARGIN,
                    salesMarginInputCents = 119,
                )
            )
        val percentageMargin =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                    salesMarginPercent = BigDecimal("10"),
                )
            )

        listOf(fixedMargin, percentageMargin).forEach { result ->
            assertEquals(PriceAmount(net = 100, tax = 19, gross = 119), result.salesMargin)
            assertDecimal("10", result.calculatedSalesMarginPercent)
            assertEquals(PriceAmount(net = 1_100, tax = 209, gross = 1_309), result.salesTotal)
        }
    }

    @Test
    fun `net sales total derives its margin from the net purchase total`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesCalculationMode = PriceCalculationMode.NET,
                    salesTotalInputCents = 1_500,
                )
            )

        assertEquals(PriceAmount(net = 500, tax = 95, gross = 595), result.salesMargin)
        assertDecimal("50", result.calculatedSalesMarginPercent)
        assertEquals(PriceAmount(net = 1_500, tax = 285, gross = 1_785), result.salesTotal)
    }

    @Test
    fun `sales total below purchase total derives a negative margin`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesActiveRow = SalesActiveRow.TOTAL,
                    salesTotalInputCents = 1_000,
                )
            )

        assertEquals(PriceAmount(net = -160, tax = -30, gross = -190), result.salesMargin)
        assertDecimal("-15.97", result.calculatedSalesMarginPercent)
        assertEquals(PriceAmount(net = 840, tax = 160, gross = 1_000), result.salesTotal)
    }

    @Test
    fun `negative sales margin is accepted when total stays non-negative`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesCalculationMode = PriceCalculationMode.NET,
                    salesActiveRow = SalesActiveRow.MARGIN,
                    salesMarginInputCents = -100,
                )
            )

        assertEquals(PriceAmount(net = -100, tax = -19, gross = -119), result.salesMargin)
        assertEquals(PriceAmount(net = 900, tax = 171, gross = 1_071), result.salesTotal)
    }

    @Test
    fun `zero percentage bases return zero`() {
        val result = calculate(priceInput(salesTotalInputCents = 1_000))

        assertDecimal("0", result.calculatedPurchaseCostPercent)
        assertDecimal("0", result.calculatedSalesMarginPercent)
    }

    @Test
    fun `cent and percent midpoint rounding is away from zero`() {
        val halfVat = vat(id = 2, name = "Half", percent = 50)
        val centResult =
            PriceCalculator.calculate(
                id = null,
                input =
                    priceInput(purchasePriceInputCents = 1)
                        .copy(
                            purchaseVatId = halfVat.id,
                            salesVatId = halfVat.id,
                        ),
                purchaseVat = halfVat,
                salesVat = halfVat,
            )
        assertEquals(PriceAmount(net = 1, tax = 1, gross = 2), centResult.purchasePrice)

        val zeroVat = vat(id = 3, name = "Zero", percent = 0)
        val percentResult =
            PriceCalculator.calculate(
                id = null,
                input =
                    priceInput(
                            purchasePriceInputCents = 32,
                            purchaseCostInputCents = 1,
                            salesCalculationMode = PriceCalculationMode.NET,
                            salesTotalInputCents = 31,
                        )
                        .copy(purchaseVatId = zeroVat.id, salesVatId = zeroVat.id),
                purchaseVat = zeroVat,
                salesVat = zeroVat,
            )
        assertDecimal("3.13", percentResult.calculatedPurchaseCostPercent)
        assertDecimal("-6.06", percentResult.calculatedSalesMarginPercent)

        val negativeCentResult =
            PriceCalculator.calculate(
                id = null,
                input =
                    priceInput(
                            purchasePriceInputCents = 1,
                            salesCalculationMode = PriceCalculationMode.NET,
                            salesActiveRow = SalesActiveRow.MARGIN,
                            salesMarginInputCents = -1,
                        )
                        .copy(purchaseVatId = halfVat.id, salesVatId = halfVat.id),
                purchaseVat = halfVat,
                salesVat = halfVat,
            )
        assertEquals(PriceAmount(net = -1, tax = -1, gross = -2), negativeCentResult.salesMargin)

        val negativePercentResult =
            PriceCalculator.calculate(
                id = null,
                input =
                    priceInput(
                            purchasePriceInputCents = 32,
                            salesCalculationMode = PriceCalculationMode.NET,
                            salesActiveRow = SalesActiveRow.MARGIN,
                            salesMarginInputCents = -1,
                        )
                        .copy(purchaseVatId = zeroVat.id, salesVatId = zeroVat.id),
                purchaseVat = zeroVat,
                salesVat = zeroVat,
            )
        assertDecimal("-3.13", negativePercentResult.calculatedSalesMarginPercent)
    }

    @Test
    fun `without a discount the effective values are the regular ones`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesTotalInputCents = 1_990,
                )
            )

        assertNull(result.discount)
        assertEquals(PriceAmount(net = 0, tax = 0, gross = 0), result.salesDiscount)
        assertEquals(result.regularSalesTotal, result.salesTotal)
        assertEquals(result.regularSalesMargin, result.salesMargin)
        assertEquals(
            result.calculatedRegularSalesMarginPercent,
            result.calculatedSalesMarginPercent,
        )
    }

    @Test
    fun `percentage discount reduces a gross sales total and its margin`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesTotalInputCents = 1_990,
                    discountType = "PERCENTAGE",
                    discountValue = BigDecimal("20"),
                )
            )

        assertEquals(
            PriceDiscount(PriceDiscountType.PERCENTAGE, BigDecimal("20")),
            result.discount,
        )
        assertEquals(PriceAmount(net = 1_672, tax = 318, gross = 1_990), result.regularSalesTotal)
        assertEquals(PriceAmount(net = 1_338, tax = 254, gross = 1_592), result.salesTotal)
        assertEquals(PriceAmount(net = 334, tax = 64, gross = 398), result.salesDiscount)
        assertEquals(PriceAmount(net = 672, tax = 128, gross = 800), result.regularSalesMargin)
        assertEquals(PriceAmount(net = 338, tax = 64, gross = 402), result.salesMargin)
        assertDecimal("67.23", result.calculatedRegularSalesMarginPercent)
        assertDecimal("33.78", result.calculatedSalesMarginPercent)
        assertComponentwiseIdentity(result)
    }

    @Test
    fun `fixed amount discount reduces a net sales total`() {
        val result =
            calculate(
                priceInput(
                    purchasePriceInputCents = 1_000,
                    salesCalculationMode = PriceCalculationMode.NET,
                    salesTotalInputCents = 1_500,
                    discountType = "FIXED_AMOUNT",
                    discountValue = BigDecimal("285.00"),
                )
            )

        assertEquals(PriceAmount(net = 1_500, tax = 285, gross = 1_785), result.regularSalesTotal)
        assertEquals(PriceAmount(net = 1_261, tax = 239, gross = 1_500), result.salesTotal)
        assertEquals(PriceAmount(net = 239, tax = 46, gross = 285), result.salesDiscount)
        assertEquals(PriceAmount(net = 261, tax = 50, gross = 311), result.salesMargin)
        assertComponentwiseIdentity(result)
    }

    @Test
    fun `a half cent saving rounds away from zero`() {
        val result =
            calculate(
                priceInput(
                    salesTotalInputCents = 1_000,
                    discountType = "PERCENTAGE",
                    discountValue = BigDecimal("0.05"),
                )
            )

        assertEquals(1, result.salesDiscount.gross)
        assertEquals(999, result.salesTotal.gross)
    }

    @Test
    fun `a hundred percent discount makes the price free`() {
        val result =
            calculate(
                priceInput(
                    salesTotalInputCents = 1_990,
                    discountType = "PERCENTAGE",
                    discountValue = BigDecimal("100"),
                )
            )

        assertEquals(PriceAmount(net = 1_672, tax = 318, gross = 1_990), result.regularSalesTotal)
        assertEquals(PriceAmount(net = 0, tax = 0, gross = 0), result.salesTotal)
        assertComponentwiseIdentity(result)
    }

    @Test
    fun `a fixed amount larger than the gross is capped at it`() {
        val result =
            calculate(
                priceInput(
                    salesTotalInputCents = 1_990,
                    discountType = "FIXED_AMOUNT",
                    discountValue = BigDecimal("2147483648"),
                )
            )

        assertEquals(PriceAmount(net = 1_672, tax = 318, gross = 1_990), result.regularSalesTotal)
        assertEquals(PriceAmount(net = 0, tax = 0, gross = 0), result.salesTotal)
        assertEquals(result.regularSalesTotal, result.salesDiscount)
        assertComponentwiseIdentity(result)
    }

    @Test
    fun `checked cent arithmetic never silently wraps`() {
        assertFailsWith<ArithmeticException> {
            PriceCalculator.calculate(
                id = null,
                input = priceInput(purchasePriceInputCents = Int.MAX_VALUE),
                purchaseVat = vat(id = 1, name = "Full", percent = 100),
                salesVat = standardVat,
            )
        }
    }

    private fun calculate(input: PriceInput): CalculatedPrice =
        PriceCalculator.calculate(null, input, standardVat, standardVat)

    private fun priceInput(
        purchaseCalculationMode: PriceCalculationMode = PriceCalculationMode.NET,
        purchaseActiveRow: PurchaseActiveRow = PurchaseActiveRow.COST,
        purchasePriceInputCents: Int = 0,
        purchaseCostInputCents: Int = 0,
        purchaseCostPercent: BigDecimal = BigDecimal.ZERO,
        salesCalculationMode: PriceCalculationMode = PriceCalculationMode.GROSS,
        salesActiveRow: SalesActiveRow = SalesActiveRow.TOTAL,
        salesMarginInputCents: Int = 0,
        salesMarginPercent: BigDecimal = BigDecimal.ZERO,
        salesTotalInputCents: Int = 0,
        discountType: String? = null,
        discountValue: BigDecimal? = null,
    ): PriceInput =
        PriceInput(
            purchaseVatId = standardVat.id,
            purchaseCalculationMode = purchaseCalculationMode,
            purchaseActiveRow = purchaseActiveRow,
            purchasePriceInputCents = purchasePriceInputCents,
            purchaseCostInputCents = purchaseCostInputCents,
            purchaseCostPercent = purchaseCostPercent,
            salesVatId = standardVat.id,
            salesCalculationMode = salesCalculationMode,
            salesActiveRow = salesActiveRow,
            salesMarginInputCents = salesMarginInputCents,
            salesMarginPercent = salesMarginPercent,
            salesTotalInputCents = salesTotalInputCents,
            discountType = discountType,
            discountValue = discountValue,
        )

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    /** The saving and the effective total add up to the regular total in every component. */
    private fun assertComponentwiseIdentity(price: CalculatedPrice) {
        assertEquals(
            price.regularSalesTotal,
            PriceAmount(
                net = price.salesDiscount.net + price.salesTotal.net,
                tax = price.salesDiscount.tax + price.salesTotal.tax,
                gross = price.salesDiscount.gross + price.salesTotal.gross,
            ),
        )
    }

    private fun vat(
        id: Long,
        name: String,
        percent: Int,
    ): Vat = Vat(id = id, name = name, percent = percent, description = null, isDefault = false)

    private companion object {
        val standardVat =
            Vat(id = 1, name = "Standard", percent = 19, description = null, isDefault = true)
    }
}
