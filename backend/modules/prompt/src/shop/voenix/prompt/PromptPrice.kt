package shop.voenix.prompt

import kotlinx.serialization.Serializable
import shop.voenix.pricing.CalculatedPrice

/**
 * What a list row shows of a prompt's price: the sales total split into its three amounts, plus the
 * VAT rate they were calculated with.
 *
 * This is the small projection `pricing-post-migration.md` asks the prompt module to keep. It stays
 * `internal` and it is not the pricing module's business: the full [CalculatedPrice] carries
 * thirteen calculation inputs and seven derived amounts, none of which an overview table — or, in a
 * later slice, the storefront — has any use for.
 *
 * The amounts are integer cents, and [salesVatRatePercent] is the whole-number percentage of the
 * VAT entry the price refers to.
 */
@Serializable
internal data class PromptPrice(
    val salesTotalNet: Int,
    val salesTotalGross: Int,
    val salesTotalTax: Int,
    val salesVatRatePercent: Int,
) {
    companion object {
        /**
         * The projection of a calculated price, recalculated from the current VAT on every read.
         */
        fun of(price: CalculatedPrice): PromptPrice =
            PromptPrice(
                salesTotalNet = price.salesTotal.net,
                salesTotalGross = price.salesTotal.gross,
                salesTotalTax = price.salesTotal.tax,
                salesVatRatePercent = price.salesVat.percent,
            )
    }
}
