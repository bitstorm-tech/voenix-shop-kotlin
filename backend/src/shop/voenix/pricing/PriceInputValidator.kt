package shop.voenix.pricing

import java.math.BigDecimal

object PriceInputValidator {
    fun validate(input: PriceInput): Map<String, List<String>> = buildMap {
        if (input.purchaseVatId == null || input.purchaseVatId <= 0) {
            put("purchaseVatId", listOf("Purchase VAT id is required"))
        }
        if (input.salesVatId == null || input.salesVatId <= 0) {
            put("salesVatId", listOf("Sales VAT id is required"))
        }
        if (input.purchasePriceInputCents < 0) {
            put(
                "purchasePriceInputCents",
                listOf("Purchase price input must not be negative"),
            )
        }
        addPurchaseCostError(input)
        addSalesMarginPercentError(input)
        if (input.salesActiveRow == SalesActiveRow.TOTAL && input.salesTotalInputCents < 0) {
            put(
                "salesTotalInputCents",
                listOf("Sales total input must not be negative"),
            )
        }
    }

    private fun MutableMap<String, List<String>>.addPurchaseCostError(input: PriceInput) {
        when (input.purchaseActiveRow) {
            PurchaseActiveRow.COST ->
                if (input.purchaseCostInputCents < 0) {
                    put(
                        "purchaseCostInputCents",
                        listOf("Purchase cost input must not be negative"),
                    )
                }
            PurchaseActiveRow.COST_PERCENT ->
                when {
                    input.purchaseCostPercent < BigDecimal.ZERO ->
                        put(
                            "purchaseCostPercent",
                            listOf("Purchase cost percent must not be negative"),
                        )
                    PricePercentagePolicy.hasTooManyDecimalPlaces(input.purchaseCostPercent) ->
                        put(
                            "purchaseCostPercent",
                            listOf("Purchase cost percent must have at most two decimal places"),
                        )
                    input.purchaseCostPercent > PricePercentagePolicy.maxValue ->
                        put(
                            "purchaseCostPercent",
                            listOf("Purchase cost percent must not exceed 9999.99"),
                        )
                }
        }
    }

    private fun MutableMap<String, List<String>>.addSalesMarginPercentError(input: PriceInput) {
        if (input.salesActiveRow == SalesActiveRow.MARGIN_PERCENT) {
            when {
                PricePercentagePolicy.hasTooManyDecimalPlaces(input.salesMarginPercent) ->
                    put(
                        "salesMarginPercent",
                        listOf("Sales margin percent must have at most two decimal places"),
                    )
                input.salesMarginPercent.abs() > PricePercentagePolicy.maxValue ->
                    put(
                        "salesMarginPercent",
                        listOf("Sales margin percent must be between -9999.99 and 9999.99"),
                    )
            }
        }
    }
}
