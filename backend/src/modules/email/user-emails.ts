import type { Settings } from '@/platform/settings'
import type { EmailDelivery } from './delivery/sweego'
import type { RenderedEmail, UserEmailKind } from './templates/layout'

/**
 * The one failure a caller of `userEmails.send` may expect: the provider did not confirm
 * acceptance. The message is fixed and names no recipient, token, or provider text, so it is safe
 * to log. Anything else a send throws is a bug and must stay distinguishable from this, which is
 * why callers catch this class and nothing wider.
 */
export class EmailDeliveryError extends Error {
  constructor() {
    super('Email provider acceptance was not confirmed')
    this.name = 'EmailDeliveryError'
  }
}

/**
 * Sends a mail of an interactive user action directly, without storing it: it may carry a secret
 * link. Disabled email makes this a no-op; enabled, it makes exactly one provider request.
 */
export function createUserEmails(settings: Settings['email'], delivery: EmailDelivery) {
  return {
    send: async (email: RenderedEmail<UserEmailKind>): Promise<void> => {
      if (!settings.enabled) return
      const result = await delivery.deliver(email, null)
      if (result.kind === 'failed') throw new EmailDeliveryError()
    },
  }
}
