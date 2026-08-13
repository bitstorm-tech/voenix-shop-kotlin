package shop.voenix.account.api

import shop.voenix.account.SupplierLoginView
import shop.voenix.validation.ValidationErrors

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
