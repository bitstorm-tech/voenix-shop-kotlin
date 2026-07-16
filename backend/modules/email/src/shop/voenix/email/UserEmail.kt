package shop.voenix.email

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

    public data class PasswordChangedNotification(override val recipient: EmailRecipient) :
        UserEmail

    public data class ChangeEmailNotification(
        override val recipient: EmailRecipient,
        public val newEmail: EmailRecipient,
    ) : UserEmail
}
