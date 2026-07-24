package shop.voenix.account

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object Users : LongIdTable("users") {
    val email = varchar("email", 255)
    val emailConfirmed = bool("email_confirmed")
    val passwordHash = text("password_hash")
    val createdAt = timestampWithTimeZone("created_at")
    val failedLoginCount = integer("failed_login_count")
    val lockedUntil = timestampWithTimeZone("locked_until").nullable()
    val shippingFirstName = varchar("shipping_first_name", 100).nullable()
    val shippingLastName = varchar("shipping_last_name", 100).nullable()
    val shippingStreet = varchar("shipping_street", 200).nullable()
    val shippingHouseNumber = varchar("shipping_house_number", 20).nullable()
    val shippingPostalCode = varchar("shipping_postal_code", 10).nullable()
    val shippingCity = varchar("shipping_city", 100).nullable()
    val shippingCountry = varchar("shipping_country", 2).nullable()
    val shippingPhone = text("shipping_phone").nullable()
    val billingFirstName = varchar("billing_first_name", 100).nullable()
    val billingLastName = varchar("billing_last_name", 100).nullable()
    val billingStreet = varchar("billing_street", 200).nullable()
    val billingHouseNumber = varchar("billing_house_number", 20).nullable()
    val billingPostalCode = varchar("billing_postal_code", 10).nullable()
    val billingCity = varchar("billing_city", 100).nullable()
    val billingCountry = varchar("billing_country", 2).nullable()
    val billingPhone = text("billing_phone").nullable()
    val hasSeparateBillingAddress = bool("has_separate_billing_address")
}
