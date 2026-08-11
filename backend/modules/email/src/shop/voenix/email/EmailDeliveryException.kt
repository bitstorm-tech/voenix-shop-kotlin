package shop.voenix.email

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
