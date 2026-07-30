package shop.voenix.cart

/**
 * A cart exactly as the database holds it: the id, the promotion reference, and the lines in
 * position order.
 *
 * It is not the response. Everything a customer sees beyond these numbers — names, colors,
 * availability, the promotion behind the id, and every total — is resolved and calculated by the
 * service from current master data, which is why the repository stops here.
 */
internal data class StoredCart(
    val id: Long,
    val promotionId: Long?,
    val lines: List<Line>,
) {
    data class Line(
        val id: Long,
        val articleId: Long,
        val variantId: Long,
        val quantity: Int,
        val priceCents: Int,
        val promptId: Long?,
        val promptPriceCents: Int,
        val printImageId: Long?,
    )
}
