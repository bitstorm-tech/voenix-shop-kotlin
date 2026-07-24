package shop.voenix.account

import io.ktor.http.Url
import shop.voenix.email.UserEmail
import shop.voenix.email.UserEmailSender

/**
 * Records every sent account mail so tests can drive confirmation and reset flows by extracting the
 * mailed link — never by reading tokens from the database. Setting [failure] simulates a failing
 * delivery provider.
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

    fun lastChangeEmailUrl(): String =
        sent.filterIsInstance<UserEmail.ChangeEmailConfirmation>().last().confirmationUrl.value
}

/** Reads a decoded query parameter from a mailed link. */
internal fun queryParameter(url: String, name: String): String =
    checkNotNull(Url(url).parameters[name]) { "No parameter $name in $url" }
