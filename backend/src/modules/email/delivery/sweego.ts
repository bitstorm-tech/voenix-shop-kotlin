import type { Fetch } from '@/platform/fetch'
import type { Settings } from '@/platform/settings'
import type { RenderedEmail } from '../templates/layout'

/** Hands one mail to the provider. Sweego in a deployment, a recording fake in the tests. */
export interface EmailDelivery {
  deliver(email: RenderedEmail, campaignId: string | null): Promise<DeliveryResult>
}

/** `failed` carries a bounded code such as `PROVIDER_HTTP_503`, never provider text. */
export type DeliveryResult = { kind: 'accepted' } | { kind: 'failed'; code: string }

/** The `fetch` the adapter calls; the global one in a deployment, a stub in the tests. */

const SEND_URL = 'https://api.sweego.io/send'
const TIMEOUT_MILLIS = 30_000

/**
 * The Sweego adapter. A 2xx answer means Sweego accepted the request, not that a mailbox received
 * it. The response body is drained but never parsed, stored, or logged. Redirects are not followed:
 * walking one would replay the message and the API key against a URL this adapter never chose, so
 * a `302` is reported like every other refusal.
 */
export function createSweegoDelivery(settings: Settings['email'], fetch: Fetch): EmailDelivery {
  return {
    deliver: async (email, campaignId) => {
      try {
        const response = await fetch(SEND_URL, {
          method: 'POST',
          headers: { 'Api-Key': settings.apiKey, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            channel: 'email',
            provider: 'sweego',
            recipients: [{ email: email.recipient, name: email.recipientName ?? '' }],
            from: { email: settings.fromEmail, name: settings.fromName },
            subject: email.subject,
            'message-html': email.html,
            'message-txt': email.text,
            'campaign-type': 'transac',
            'campaign-id': campaignId ?? undefined,
          }),
          redirect: 'manual',
          signal: AbortSignal.timeout(TIMEOUT_MILLIS),
        })
        await response.arrayBuffer()
        return response.ok
          ? { kind: 'accepted' }
          : { kind: 'failed', code: `PROVIDER_HTTP_${String(response.status)}` }
      } catch (error) {
        const timedOut = error instanceof DOMException && error.name === 'TimeoutError'
        return { kind: 'failed', code: timedOut ? 'REQUEST_TIMEOUT' : 'PROVIDER_UNAVAILABLE' }
      }
    },
  }
}
