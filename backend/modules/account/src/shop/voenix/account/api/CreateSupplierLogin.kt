package shop.voenix.account.api

import kotlinx.serialization.Serializable
import shop.voenix.account.SupplierLoginView
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

/**
 * The outcomes of creating a supplier login. The three failure causes stay separate because the
 * administrator has to react differently to each: pick another address, pick another supplier, or
 * simply wait — the login of a [InvitationDeliveryFailed] already exists.
 */
internal sealed interface CreateSupplierLoginResult {
    data class Created(val login: SupplierLoginView) : CreateSupplierLoginResult

    /** Some user — supplier login, customer, or admin — already uses this address. */
    data object EmailTaken : CreateSupplierLoginResult

    data object UnknownSupplier : CreateSupplierLoginResult

    /**
     * The login and its invitation token are stored, but the provider did not accept the mail. The
     * row survives on purpose: a second `POST` would answer `409` for the taken address instead of
     * duplicating the user, and the invited person recovers through "Passwort vergessen", which
     * replaces the stored reset token with a freshly mailed one.
     */
    data object InvitationDeliveryFailed : CreateSupplierLoginResult

    data class Invalid(val errors: ValidationErrors) : CreateSupplierLoginResult

    data object UnexpectedFailure : CreateSupplierLoginResult
}
