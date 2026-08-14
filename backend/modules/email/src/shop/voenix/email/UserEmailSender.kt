package shop.voenix.email

public fun interface UserEmailSender {
    public suspend fun send(email: UserEmail)
}

public sealed interface UserEmail {
    public val recipient: EmailRecipient

    public data class AccountConfirmation(
        override val recipient: EmailRecipient,
        public val confirmationUrl: EmailActionUrl,
    ) : UserEmail

    public data class ChangeEmailConfirmation(
        override val recipient: EmailRecipient,
        public val confirmationUrl: EmailActionUrl,
    ) : UserEmail

    public data class PasswordReset(
        override val recipient: EmailRecipient,
        public val resetUrl: EmailActionUrl,
    ) : UserEmail

    /**
     * The invitation to a supplier login an administrator created. It carries a set-password link —
     * the recipient never receives a password — and has its own copy, because unlike
     * [PasswordReset] nobody asked for it.
     */
    public data class SupplierInvitation(
        override val recipient: EmailRecipient,
        public val invitationUrl: EmailActionUrl,
    ) : UserEmail

    public data class PasswordChangedNotification(override val recipient: EmailRecipient) :
        UserEmail

    public data class ChangeEmailNotification(
        override val recipient: EmailRecipient,
        public val newEmail: EmailRecipient,
    ) : UserEmail
}

/**
 * The one failure a caller of [UserEmailSender] may expect from the email provider: the send
 * reached the provider step and acceptance was not confirmed.
 *
 * The message is fixed and carries no recipient, token, or provider text, so it is safe to log
 * anywhere. Anything else that escapes a send — a rendering bug, an invalid [EmailActionUrl] — is a
 * programming error, not an external dependency failure, and must stay distinguishable from this
 * exception; that is why callers catch this type and nothing wider.
 *
 * The constructor is public because [UserEmailSender] is a public interface: every implementation,
 * including the fakes other modules use in their tests, must be able to signal the same failure.
 */
public class EmailDeliveryException :
    RuntimeException("Email provider acceptance was not confirmed")
