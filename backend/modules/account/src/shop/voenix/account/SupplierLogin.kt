package shop.voenix.account

import java.time.Instant

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
