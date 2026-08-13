package shop.voenix.order

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * The placed order: who it belongs to, where it goes, and what it cost.
 *
 * Every address and amount column is a snapshot taken at placement, so a later change to the
 * account, the catalog, or the promotion cannot rewrite what was ordered. The amounts are stored
 * rather than derived because the database is what keeps them consistent (`total = subtotal +
 * shipping - discount`).
 */
internal object Orders : LongIdTable("orders") {
    val cartId = long("cart_id")
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val promotionId = long("promotion_id").nullable()

    /**
     * The order's own bearer credential; see [OrderAccessToken]. It is stored as text and read back
     * through that type, so nothing outside the repository ever handles it as a plain string.
     */
    val accessToken = text("access_token")
    val status = text("status")
    val shippingFirstName = varchar("shipping_first_name", 100)
    val shippingLastName = varchar("shipping_last_name", 100)
    val shippingStreet = varchar("shipping_street", 200)
    val shippingHouseNumber = varchar("shipping_house_number", 20)
    val shippingPostalCode = varchar("shipping_postal_code", 10)
    val shippingCity = varchar("shipping_city", 100)
    val shippingCountry = varchar("shipping_country", 2)
    val billingFirstName = varchar("billing_first_name", 100)
    val billingLastName = varchar("billing_last_name", 100)
    val billingStreet = varchar("billing_street", 200)
    val billingHouseNumber = varchar("billing_house_number", 20)
    val billingPostalCode = varchar("billing_postal_code", 10)
    val billingCity = varchar("billing_city", 100)
    val billingCountry = varchar("billing_country", 2)
    val email = varchar("email", 255)
    val phone = text("phone").nullable()
    val subtotalCents = integer("subtotal_cents")
    val shippingCostCents = integer("shipping_cost_cents")
    val discountCents = integer("discount_cents")
    val totalCents = integer("total_cents")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
