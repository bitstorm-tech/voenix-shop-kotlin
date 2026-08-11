package shop.voenix.account

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailDeliveryException
import shop.voenix.email.EmailRecipient
import shop.voenix.email.UserEmail
import shop.voenix.email.UserEmailSender

/**
 * Owns the account mail policy: which mail carries which frontend link, which deliveries are
 * required, and which are best effort. Links are built and percent-encoded here from
 * [AccountSettings.frontendBaseUrl].
 *
 * The two kinds of send handle failure differently, and so do the two kinds of *failure*:
 * - A **required** send answers `false` only for an [EmailDeliveryException] — the email module's
 *   one public signal that an external provider did not accept the message. The service turns that
 *   into a delivery result and the route into `502`. Everything else that can escape a send is a
 *   bug on our side (a rendering failure, a malformed [EmailActionUrl]) and is deliberately *not*
 *   caught here: it travels on to the caller's `databaseOperation` guard, which logs it and answers
 *   with the operation's unexpected-failure result — a plain `500` that reveals no exception text,
 *   recipient, or provider detail. Claiming `502` for our own bug would blame the provider and tell
 *   the customer to retry something that cannot succeed.
 * - A **best-effort** send catches everything, including our own bugs, because the operation it
 *   accompanies — a completed password change, a confirmed address change — has already happened
 *   and must not be undone by a notification.
 *
 * Caller cancellation is never a failure in either case. The required sends get that for free from
 * their narrow catch (a `CancellationException` is not an [EmailDeliveryException]); the
 * best-effort send has to rethrow it explicitly before its broad catch.
 */
internal class AccountMailer(
    private val settings: AccountSettings,
    private val userEmails: UserEmailSender,
) {
    /** Required delivery: returns whether the confirmation mail reached the provider. */
    suspend fun sendAccountConfirmation(userId: Long, email: String, token: String): Boolean =
        try {
            val url = actionUrl("/confirm-email", "userId" to userId.toString(), "token" to token)
            userEmails.send(
                UserEmail.AccountConfirmation(EmailRecipient(email), EmailActionUrl(url))
            )
            true
        } catch (exception: EmailDeliveryException) {
            logger.warn("Account confirmation delivery failed for user {}", userId, exception)
            false
        }

    /**
     * The caller is enumeration-safe and suppresses *any* failure, so this send may throw. Not
     * catching here is what keeps the two branches indistinguishable: a delivery failure and a
     * rendering bug both leave `forgot-password` answering `204`.
     */
    suspend fun sendPasswordReset(email: String, token: String) {
        val url = actionUrl("/reset-password", "email" to email, "token" to token)
        userEmails.send(UserEmail.PasswordReset(EmailRecipient(email), EmailActionUrl(url)))
    }

    /**
     * Required delivery of the confirmation to the new address; on success the notification to the
     * old address goes out best effort.
     */
    suspend fun sendChangeEmail(
        userId: Long,
        oldEmail: String,
        newEmail: String,
        token: String,
    ): Boolean {
        try {
            val url =
                actionUrl(
                    "/confirm-change-email",
                    "userId" to userId.toString(),
                    "newEmail" to newEmail,
                    "token" to token,
                )
            userEmails.send(
                UserEmail.ChangeEmailConfirmation(EmailRecipient(newEmail), EmailActionUrl(url))
            )
        } catch (exception: EmailDeliveryException) {
            logger.warn("Change-email confirmation delivery failed for user {}", userId, exception)
            return false
        }
        sendBestEffort(
            UserEmail.ChangeEmailNotification(EmailRecipient(oldEmail), EmailRecipient(newEmail))
        )
        return true
    }

    suspend fun sendPasswordChangedBestEffort(email: String) {
        sendBestEffort(UserEmail.PasswordChangedNotification(EmailRecipient(email)))
    }

    /**
     * Swallows every failure on purpose — a provider outage as much as a bug of ours. The change
     * this mail announces is already stored, so there is nothing left to fail; only cancellation
     * passes through, because a cancelled request must not be reported as a sent notification.
     */
    private suspend fun sendBestEffort(email: UserEmail) {
        try {
            userEmails.send(email)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Best-effort account notification delivery failed", exception)
        }
    }

    private fun actionUrl(path: String, vararg parameters: Pair<String, String>): String =
        settings.frontendBaseUrl.value +
            path +
            parameters.joinToString(separator = "&", prefix = "?") { (name, value) ->
                "$name=${value.encodeURLParameter()}"
            }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AccountMailer::class.java)
    }
}
