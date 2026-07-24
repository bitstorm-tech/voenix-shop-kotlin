package shop.voenix.account

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailRecipient
import shop.voenix.email.UserEmail
import shop.voenix.email.UserEmailSender

/**
 * Owns the account mail policy: which mail carries which frontend link, which deliveries are
 * required, and which are best effort. Required sends report failure as `false` so the service can
 * answer 502; best-effort sends only log. Links are built and percent-encoded here from
 * [AccountSettings.frontendBaseUrl].
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
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Account confirmation delivery failed for user {}", userId, exception)
            false
        }

    /** The caller is enumeration-safe and suppresses any failure, so this send may throw. */
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
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
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
        settings.frontendBaseUrl +
            path +
            parameters.joinToString(separator = "&", prefix = "?") { (name, value) ->
                "$name=${value.encodeURLParameter()}"
            }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AccountMailer::class.java)
    }
}
