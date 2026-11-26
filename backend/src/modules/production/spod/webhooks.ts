import type { SpodAccess, SpodClient, SpodError, SpodSubscription } from '@/modules/spod'

/** The events this shop acts on (SPOD fulfillment guide, "The webhook"). */
export const SPOD_WEBHOOK_EVENTS = ['Shipment.sent', 'Order.cancelled', 'Order.needs-action']

/** The path the partner calls; the configured secret is its last segment. */
export const SPOD_WEBHOOK_PATH = '/api/production/webhooks/spod/'

export type WebhookClient = Pick<SpodClient, 'subscriptions' | 'subscribe' | 'unsubscribe'>

/** `WRONG_URL`: subscribed, but to a URL other than this deployment's, e.g. an old secret. */
export type WebhookState = 'ACTIVE' | 'MISSING' | 'WRONG_URL'

export interface WebhookStatus {
  eventType: string
  state: WebhookState
  /** The hosts of this event's subscriptions that miss this deployment; never their paths. */
  otherHosts: string[]
}

export type WebhookResult =
  | { kind: 'answered'; value: WebhookStatus[] }
  | { kind: 'failed'; error: SpodError }
  /** This deployment's own address is not HTTPS (local development): nothing to subscribe. */
  | { kind: 'notPublic' }

export type SpodWebhooks = ReturnType<typeof createSpodWebhooks>

/**
 * The partner's subscriptions of one account against this deployment's webhook URL. The URL holds
 * the secret, so it is compared here and leaves this file only towards the partner.
 */
export function createSpodWebhooks(client: WebhookClient, webhookUrl: string) {
  const statusOf = (subscriptions: SpodSubscription[]): WebhookStatus[] =>
    SPOD_WEBHOOK_EVENTS.map((eventType) => {
      const subscribed = subscriptions.filter((item) => item.eventType === eventType)
      const others = subscribed.filter((item) => item.url !== webhookUrl)
      const state: WebhookState =
        subscribed.length > others.length ? 'ACTIVE' : others.length > 0 ? 'WRONG_URL' : 'MISSING'
      return { eventType, state, otherHosts: [...new Set(others.map((item) => hostOf(item.url)))] }
    })

  const status = async (access: SpodAccess): Promise<WebhookResult> => {
    const listed = await client.subscriptions(access)
    if (listed.kind === 'failed') return { kind: 'failed', error: listed.error }
    return { kind: 'answered', value: statusOf(listed.value) }
  }

  return {
    status,

    /**
     * Subscribes every missing event and removes this shop's stale subscriptions (an old secret or
     * host); a receiver outside this shop's webhook path stays. Each step is idempotent, so a run
     * that failed half-way is simply repeated.
     */
    register: async (access: SpodAccess): Promise<WebhookResult> => {
      if (!webhookUrl.startsWith('https://')) return { kind: 'notPublic' }
      const listed = await client.subscriptions(access)
      if (listed.kind === 'failed') return { kind: 'failed', error: listed.error }
      for (const eventType of SPOD_WEBHOOK_EVENTS) {
        const subscribed = listed.value.filter((item) => item.eventType === eventType)
        const stale = subscribed.filter(
          (item) => item.url !== webhookUrl && isShopWebhook(item.url),
        )
        for (const item of stale) {
          const removed = await client.unsubscribe(access, item.id)
          if (removed.kind === 'failed') return { kind: 'failed', error: removed.error }
        }
        if (!subscribed.some((item) => item.url === webhookUrl)) {
          const created = await client.subscribe(access, eventType, webhookUrl)
          if (created.kind === 'failed') return { kind: 'failed', error: created.error }
        }
      }
      return status(access)
    },
  }
}

const parsed = (url: string | null): URL | null => {
  try {
    return url === null ? null : new URL(url)
  } catch {
    return null
  }
}

const hostOf = (url: string | null) => parsed(url)?.host ?? '(no valid URL)'

const isShopWebhook = (url: string | null) =>
  parsed(url)?.pathname.startsWith(SPOD_WEBHOOK_PATH) ?? false
