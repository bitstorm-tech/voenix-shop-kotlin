import {
  EmailDeliveryError,
  renderAccountConfirmation,
  renderChangeEmailConfirmation,
  renderChangeEmailNotification,
  renderPasswordChanged,
  renderPasswordReset,
  renderSupplierInvitation,
  type EmailModule,
  type RenderedEmail,
  type UserEmailKind,
} from '@/modules/email'
import { createLogger } from '@/platform/logging'

const log = createLogger('account')

export type AccountMails = ReturnType<typeof createAccountMails>

/**
 * The account mail policy: which mail carries which frontend link, and which delivery may fail.
 *
 * A **required** send answers `false` only for an `EmailDeliveryError`, the email module's one
 * signal that the provider did not accept the mail; the route turns that into `502`. Anything
 * else a send throws is a bug of ours and propagates to the app's `500`, because a `502` would
 * blame the provider and promise a retry that cannot succeed. A **best-effort** send swallows
 * every failure: the change it announces is already stored and must not be undone by a mail.
 */
export function createAccountMails({
  userEmails,
  frontendBaseUrl,
}: {
  userEmails: EmailModule['userEmails']
  frontendBaseUrl: string
}) {
  const link = (path: string, parameters: Record<string, string>) =>
    `${frontendBaseUrl}${path}?${Object.entries(parameters)
      .map(([name, value]) => `${name}=${encodeURIComponent(value)}`)
      .join('&')}`

  const sendRequired = async (email: RenderedEmail<UserEmailKind>, userId: number) => {
    try {
      await userEmails.send(email)
      return true
    } catch (error) {
      if (!(error instanceof EmailDeliveryError)) throw error
      log.warn({ userId, kind: email.kind }, 'Required account mail was not delivered')
      return false
    }
  }

  const sendBestEffort = async (email: RenderedEmail<UserEmailKind>) => {
    try {
      await userEmails.send(email)
    } catch (error) {
      log.warn({ err: error, kind: email.kind }, 'Best-effort account notification failed')
    }
  }

  return {
    /** Required: answers whether the confirmation mail reached the provider. */
    sendAccountConfirmation: (userId: number, email: string, token: string) =>
      sendRequired(
        renderAccountConfirmation({
          recipient: email,
          confirmationUrl: link('/confirm-email', { userId: String(userId), token }),
        }),
        userId,
      ),

    /**
     * Throws on any failure: its caller, `forgot-password`, is enumeration-safe and suppresses
     * every failure alike, so a delivery failure and a bug of ours both still answer `204`.
     */
    sendPasswordReset: (email: string, token: string) =>
      userEmails.send(
        renderPasswordReset({
          recipient: email,
          resetUrl: link('/reset-password', { email, token }),
        }),
      ),

    /**
     * Required: the invitation of a new supplier login. Its link is a reset link on the
     * set-password page, because setting a first password and resetting one are one operation.
     */
    sendSupplierInvitation: (userId: number, email: string, token: string) =>
      sendRequired(
        renderSupplierInvitation({
          recipient: email,
          invitationUrl: link('/set-password', { email, token }),
        }),
        userId,
      ),

    /**
     * Required delivery of the confirmation to the new address; only when it went out, the
     * notice to the old address follows best effort.
     */
    sendChangeEmail: async (userId: number, oldEmail: string, newEmail: string, token: string) => {
      const sent = await sendRequired(
        renderChangeEmailConfirmation({
          recipient: newEmail,
          confirmationUrl: link('/confirm-change-email', {
            userId: String(userId),
            newEmail,
            token,
          }),
        }),
        userId,
      )
      if (sent)
        await sendBestEffort(renderChangeEmailNotification({ recipient: oldEmail, newEmail }))
      return sent
    },

    sendPasswordChanged: (email: string) =>
      sendBestEffort(renderPasswordChanged({ recipient: email })),
  }
}
