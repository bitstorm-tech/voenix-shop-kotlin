package shop.voenix.account

import java.time.Instant
import kotlinx.serialization.Serializable

/** A stored user row that carries a supplier link, reduced to what the admin surface shows. */
internal data class SupplierLogin(
    val userId: Long,
    val email: String,
    val supplierId: Long,
    val createdAt: Instant,
)

internal fun SupplierLogin.toView(): SupplierLoginView =
    SupplierLoginView(
        userId = userId,
        email = email,
        supplierId = supplierId,
        createdAt = createdAt.toString(),
    )

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
