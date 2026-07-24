package shop.voenix.account

import java.time.Instant

/** The stored user row including credential and lockout state. Never serialized. */
internal data class UserAccount(
    val id: Long,
    val email: String,
    val emailConfirmed: Boolean,
    val passwordHash: String,
    val createdAt: Instant,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
    val roles: Set<String>,
    val shippingAddress: Address?,
    val billingAddress: Address?,
    val hasSeparateBillingAddress: Boolean,
)

internal fun UserAccount.toProfile(): AccountProfile =
    AccountProfile(
        id = id,
        email = email,
        roles = roles.sorted(),
        shippingAddress = shippingAddress,
        billingAddress = billingAddress,
        hasSeparateBillingAddress = hasSeparateBillingAddress,
        createdAt = createdAt.toString(),
    )
