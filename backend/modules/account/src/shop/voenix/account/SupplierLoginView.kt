package shop.voenix.account

import kotlinx.serialization.Serializable

/**
 * The JSON representation of a supplier login. It deliberately carries no credential or lockout
 * state: an administrator manages *who* may sign in for a supplier, not how that login is doing.
 */
@Serializable
internal data class SupplierLoginView(
    val userId: Long,
    val email: String,
    val supplierId: Long,
    val createdAt: String,
)
