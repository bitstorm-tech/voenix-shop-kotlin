package shop.voenix.account

import io.ktor.http.Url
import shop.voenix.email.UserEmail
import shop.voenix.email.UserEmailSender

/**
 * Records every sent account mail so tests can drive confirmation and reset flows by extracting the
 * mailed link — never by reading tokens from the database.
 *
 * [failure] makes the next send throw, and *which* exception it throws is the point: an
 * `EmailDeliveryException` is the email module's one public signal that the provider did not accept
 * the message, while any other exception stands for a bug on our side — a rendering failure, a
 * malformed link. The account module answers the two differently, so a test that wants a `502` must
 * throw the first and a test that wants the internal path must throw the second.
 */
internal class RecordingUserEmailSender : UserEmailSender {
    val sent = mutableListOf<UserEmail>()
    var failure: (() -> Throwable)? = null

    override suspend fun send(email: UserEmail) {
        failure?.let { throw it() }
        sent += email
    }

    fun lastConfirmationUrl(): String =
        sent.filterIsInstance<UserEmail.AccountConfirmation>().last().confirmationUrl.value

    fun lastResetUrl(): String =
        sent.filterIsInstance<UserEmail.PasswordReset>().last().resetUrl.value

    fun lastInvitationUrl(): String =
        sent.filterIsInstance<UserEmail.SupplierInvitation>().last().invitationUrl.value

    fun lastChangeEmailUrl(): String =
        sent.filterIsInstance<UserEmail.ChangeEmailConfirmation>().last().confirmationUrl.value
}

/** Reads a decoded query parameter from a mailed link. */
internal fun queryParameter(url: String, name: String): String =
    checkNotNull(Url(url).parameters[name]) { "No parameter $name in $url" }
