package shop.voenix.pricing

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

internal object Prices : LongIdTable("prices") {
    val purchaseVatId = long("purchase_vat_id")
    val purchaseCalculationMode =
        enumerationByName<PriceCalculationMode>("purchase_calculation_mode", MODE_LENGTH)
    val purchaseActiveRow =
        enumerationByName<PurchaseActiveRow>("purchase_active_row", ACTIVE_ROW_LENGTH)
    val purchasePriceInputCents = integer("purchase_price_input_cents")
    val purchaseCostInputCents = integer("purchase_cost_input_cents")
    val purchaseCostPercent =
        decimal(
            "purchase_cost_percent",
            PricePercentagePolicy.PRECISION,
            PricePercentagePolicy.SCALE,
        )
    val salesVatId = long("sales_vat_id")
    val salesCalculationMode =
        enumerationByName<PriceCalculationMode>("sales_calculation_mode", MODE_LENGTH)
    val salesActiveRow = enumerationByName<SalesActiveRow>("sales_active_row", ACTIVE_ROW_LENGTH)
    val salesMarginInputCents = integer("sales_margin_input_cents")
    val salesMarginPercent =
        decimal(
            "sales_margin_percent",
            PricePercentagePolicy.PRECISION,
            PricePercentagePolicy.SCALE,
        )
    val salesTotalInputCents = integer("sales_total_input_cents")

    private const val MODE_LENGTH = 5
    private const val ACTIVE_ROW_LENGTH = 20
}
