package shop.voenix.account.api

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The administrator's request for a new supplier login: which supplier, and which address gets the
 * invitation. There is no password field — the invited person sets one through the mailed link.
 *
 * Only the *shape* of [supplierId] is checked here. Whether that supplier exists is decided by the
 * foreign key of the insert, because a preliminary lookup could not answer it without a race.
 */
@Serializable
internal data class CreateSupplierLoginInput(
    val supplierId: Long? = null,
    val email: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        AccountFieldRules.emailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        when {
            supplierId == null -> put("supplierId", listOf("Supplier id is required"))
            supplierId <= 0 -> put("supplierId", listOf("Supplier id must be positive"))
        }
    }
}
