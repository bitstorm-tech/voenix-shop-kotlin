package shop.voenix.pricing

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceInputValidationTest {
    @Test
    fun `required vats and always-active purchase price are validated`() {
        assertEquals(
            linkedMapOf(
                "purchaseVatId" to listOf("Purchase VAT id is required"),
                "salesVatId" to listOf("Sales VAT id is required"),
                "purchasePriceInputCents" to listOf("Purchase price input must not be negative"),
            ),
            PriceInput(purchasePriceInputCents = -1).validate(),
        )
    }

    @Test
    fun `only the active purchase cost field is validated`() {
        assertEquals(
            mapOf("purchaseCostInputCents" to listOf("Purchase cost input must not be negative")),
            validInput()
                .copy(
                    purchaseCostInputCents = -1,
                    purchaseCostPercent = BigDecimal("-1"),
                )
                .validate(),
        )
        assertEquals(
            mapOf("purchaseCostPercent" to listOf("Purchase cost percent must not be negative")),
            validInput()
                .copy(
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchaseCostInputCents = -1,
                    purchaseCostPercent = BigDecimal("-1"),
                )
                .validate(),
        )
    }

    @Test
    fun `active percentage fields allow two decimal places within four integer digits`() {
        assertEquals(
            emptyMap(),
            validInput()
                .copy(
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchaseCostPercent = BigDecimal("9999.99"),
                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                    salesMarginPercent = BigDecimal("-9999.99"),
                )
                .validate(),
        )
        assertEquals(
            emptyMap(),
            validInput()
                .copy(
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchaseCostPercent = BigDecimal("12.340"),
                )
                .validate(),
        )
    }

    @Test
    fun `active percentage fields reject excess decimal and integer digits`() {
        assertEquals(
            mapOf(
                "purchaseCostPercent" to
                    listOf("Purchase cost percent must have at most two decimal places")
            ),
            validInput()
                .copy(
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchaseCostPercent = BigDecimal("12.345"),
                )
                .validate(),
        )
        assertEquals(
            mapOf("purchaseCostPercent" to listOf("Purchase cost percent must not exceed 9999.99")),
            validInput()
                .copy(
                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                    purchaseCostPercent = BigDecimal("10000.00"),
                )
                .validate(),
        )
        assertEquals(
            mapOf(
                "salesMarginPercent" to
                    listOf("Sales margin percent must have at most two decimal places")
            ),
            validInput()
                .copy(
                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                    salesMarginPercent = BigDecimal("12.345"),
                )
                .validate(),
        )
        assertEquals(
            mapOf(
                "salesMarginPercent" to
                    listOf("Sales margin percent must be between -9999.99 and 9999.99")
            ),
            validInput()
                .copy(
                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                    salesMarginPercent = BigDecimal("-10000.00"),
                )
                .validate(),
        )
    }

    @Test
    fun `inactive percentage fields ignore decimal and integer digit limits`() {
        assertEquals(
            emptyMap(),
            validInput()
                .copy(
                    purchaseCostPercent = BigDecimal("10000.001"),
                    salesMarginPercent = BigDecimal("10000.001"),
                )
                .validate(),
        )
    }

    @Test
    fun `only active sales total is rejected for being negative`() {
        assertEquals(
            mapOf("salesTotalInputCents" to listOf("Sales total input must not be negative")),
            validInput().copy(salesTotalInputCents = -1).validate(),
        )
        assertEquals(
            emptyMap(),
            validInput()
                .copy(
                    salesActiveRow = SalesActiveRow.MARGIN,
                    salesMarginInputCents = -1,
                    salesMarginPercent = BigDecimal("-1"),
                    salesTotalInputCents = -1,
                )
                .validate(),
        )
        assertEquals(
            emptyMap(),
            validInput()
                .copy(
                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                    salesMarginInputCents = -1,
                    salesMarginPercent = BigDecimal("-1"),
                    salesTotalInputCents = -1,
                )
                .validate(),
        )
    }

    private fun validInput(): PriceInput = PriceInput(purchaseVatId = 1, salesVatId = 1)
}
