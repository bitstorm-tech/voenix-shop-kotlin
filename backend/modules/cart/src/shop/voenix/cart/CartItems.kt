package shop.voenix.cart

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object CartItems : LongIdTable("cart_items") {
    val cartId = long("cart_id")
    val articleId = long("article_id")
    val variantId = long("variant_id")
    val quantity = integer("quantity")
    val priceCents = integer("price_cents")
    val promptId = long("prompt_id").nullable()
    val promptPriceCents = integer("prompt_price_cents")
    val printImageId = long("print_image_id").nullable()
    val position = integer("position")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

/** The largest quantity one cart line may carry; the database CHECK says the same. */
internal const val MAXIMUM_LINE_QUANTITY: Int = 99
