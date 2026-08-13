package shop.voenix.order

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Everything an order stored about itself, read back in one go.
 *
 * This is the *inside* view of an order, and it exists for the two consumers that are not the
 * customer: the production source and the confirmation mail. Both read the same row, both read it
 * again on every attempt, and neither may see anything but what was stored — a paid order's PDF and
 * its confirmation mail must still show the address, the names, and the prices of the day it was
 * placed, however the account, the catalog, or the promotion has changed since.
 *
 * One type answers both because they overlap almost completely and the row is read once either way.
 * The two things that are *not* stored are equally deliberate: the supplier of a line is resolved
 * live at production time (decision 7, so a missing assignment stays repairable), and the file
 * behind [Line.printImageFilename] is fetched from the image module by name.
 */
internal data class StoredOrder(
    val orderId: Long,
    /**
     * The order's access token, read back with the row so the confirmation mail can build its link
     * without a second query. It is an [OrderAccessToken] rather than a string, which is what keeps
     * this data class's own `toString` from printing a bearer credential.
     */
    val accessToken: OrderAccessToken,
    val createdAt: OffsetDateTime,
    val email: String,
    val shippingAddress: Address,
    val billingAddress: Address,
    val subtotalCents: Int,
    val shippingCostCents: Int,
    val discountCents: Int,
    val totalCents: Int,
    val lines: List<Line>,
) {
    /**
     * The customer-facing order date.
     *
     * The instant is stored, the date is presented, and the shop's customers and producers are in
     * Germany — so the calendar day of an order placed at 00:30 Berlin time is that Berlin day, not
     * the UTC one before it. Production and the confirmation mail both take the date from here, so
     * the two can never name different days for the same order.
     */
    val orderDate: LocalDate
        get() = berlinOrderDate(createdAt)

    /** One snapshotted address; shipping and billing are stored separately and read the same. */
    data class Address(
        val firstName: String,
        val lastName: String,
        val street: String,
        val houseNumber: String,
        val postalCode: String,
        val city: String,
        val country: String,
    )

    /** One ordered line, in the position the customer put it in. */
    data class Line(
        val articleId: Long,
        val variantId: Long,
        val articleName: String,
        val variantName: String,
        val supplierArticleNumber: String?,
        val quantity: Int,
        val priceCents: Int,
        val promptPriceCents: Int,
        val printImageFilename: String?,
        val printTemplateWidthMm: Int?,
        val printTemplateHeightMm: Int?,
        val documentFormatWidthMm: Int?,
        val documentFormatHeightMm: Int?,
        val documentFormatMarginBottomMm: Int?,
    )
}

/**
 * The one conversion from a stored creation instant to the customer-facing order date.
 *
 * Every surface that names the day of an order goes through here — the production PDF, the
 * confirmation mail, and the fulfillment header a supplier reads — so none of them can name a
 * different day than the others.
 */
internal fun berlinOrderDate(createdAt: OffsetDateTime): LocalDate =
    createdAt.atZoneSameInstant(BERLIN).toLocalDate()

private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
