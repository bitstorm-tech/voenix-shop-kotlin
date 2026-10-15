import { isSupportedCountry, parsePhoneNumberFromString } from 'libphonenumber-js'
import { z } from 'zod'
import { createLogger } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import type { OrderAddress, PayableOrder } from '../repository'

/**
 * Where the payment of an order stands, in Mollie's seven words, uppercased. `CANCELED` has one L
 * on purpose: Mollie cancelled a *payment*, while the order status `CANCELLED` says the shop gave up
 * an *order*. Unifying the spelling would make a word silently valid on the wrong side.
 */
export const PAYMENT_STATUSES = [
  'OPEN',
  'PENDING',
  'AUTHORIZED',
  'PAID',
  'FAILED',
  'CANCELED',
  'EXPIRED',
] as const
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number]

/** What Mollie says about one payment, reduced to the four facts this module uses. */
export interface MolliePayment {
  id: string
  status: PaymentStatus
  /** The amount *Mollie* names, compared with the stored one before an order is confirmed. */
  amountCents: number
  /** Present while the payment can still be paid; `create` refuses an answer without one. */
  checkoutUrl: string | null
}

/**
 * The payment provider in the three calls the shop makes of it. Every way a call can fail (a
 * refusal, an unreadable answer, a timeout, an unknown status word) ends in `null` or `false`, with
 * the reason in the log and never anything the provider wrote. There are two implementations: the
 * adapter below and the stub the tests hand in.
 */
export interface MolliePayments {
  /** Creates the payment the customer is sent to; `idempotencyKey` is fresh per attempt. */
  create(order: PayableOrder, idempotencyKey: string): Promise<MolliePayment | null>
  find(molliePaymentId: string): Promise<MolliePayment | null>
  /** Best effort: `false` only says the attempt did not succeed. */
  cancel(molliePaymentId: string): Promise<boolean>
}

/** Mollie's payments endpoint. Not configurable, so no deployment can point it elsewhere. */
export const MOLLIE_PAYMENTS_URL = 'https://api.mollie.com/v2/payments'

/** An ordinary API call: a Mollie request that has not answered in ten seconds has failed. */
const REQUEST_TIMEOUT_MILLIS = 10_000
const CURRENCY = 'EUR'

type Fetch = (url: string, init: RequestInit) => Promise<Response>

/**
 * What Mollie answers, in the fields read here. `id`, `status`, and `amount` are required, so a
 * truncated answer is unusable rather than a payment with an empty id; `_links` is absent on a
 * payment that can no longer be paid. Unknown fields are ignored.
 */
const mollieAnswer = z.object({
  id: z.string(),
  status: z.string(),
  amount: z.object({ currency: z.string(), value: z.string() }),
  _links: z.object({ checkout: z.object({ href: z.string().nullish() }).nullish() }).nullish(),
})

/**
 * The hand-written Mollie adapter on `fetch`. There is no SDK, because the rule that nothing the
 * provider wrote reaches a log is only enforceable here: a refusal is logged by its status number,
 * an unreadable answer and a transport failure by the error's class, an unknown status word not at
 * all. Redirects are never followed, so a `3xx` is a refusal and the bearer key is never replayed
 * against a URL this adapter did not choose.
 */
export function createMolliePayments(
  settings: Settings['mollie'],
  fetch: Fetch = globalThis.fetch,
): MolliePayments {
  const logger = createLogger('payment')
  const headers = { Authorization: `Bearer ${settings.apiKey}` }

  /** Runs one call; a thrown error (transport, timeout, unreadable answer) is logged by class only. */
  const upstream = async <T>(what: string, call: () => Promise<T>): Promise<T | null> => {
    try {
      return await call()
    } catch (error) {
      logger.error(`${what} (${error instanceof Error ? error.name : typeof error})`)
      return null
    }
  }

  /** The payment in a successful answer, or `null` for a refusal or an answer that cannot be used. */
  const readPayment = async (response: Response, what: string): Promise<MolliePayment | null> => {
    if (!response.ok) {
      await response.body?.cancel()
      logger.error(`Mollie refused ${what} with status ${String(response.status)}`)
      return null
    }
    const answer = mollieAnswer.parse(await response.json())
    const status = statusOf(answer.status)
    const amountCents = centsOf(answer.amount)
    if (status === null) logger.error(`Mollie reported an unknown payment status while ${what}`)
    if (amountCents === null) logger.error(`Mollie reported an unusable amount while ${what}`)
    if (status === null || amountCents === null) return null
    return {
      id: answer.id,
      status,
      amountCents,
      checkoutUrl: answer._links?.checkout?.href ?? null,
    }
  }

  return {
    create: async (order, idempotencyKey) =>
      upstream('The Mollie payment could not be created', async () => {
        const response = await fetch(MOLLIE_PAYMENTS_URL, {
          method: 'POST',
          headers: {
            ...headers,
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
          },
          body: JSON.stringify(createRequest(settings, order)),
          redirect: 'manual',
          signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
        })
        const what = `creating the payment of order ${String(order.orderId)}`
        const created = await readPayment(response, what)
        if (created !== null && (created.checkoutUrl ?? '').trim() === '') {
          logger.error(`Mollie answered ${what} without a checkout URL`)
          return null
        }
        return created
      }),

    find: async (molliePaymentId) =>
      upstream('The Mollie payment could not be read', async () => {
        const response = await fetch(
          `${MOLLIE_PAYMENTS_URL}/${encodeURIComponent(molliePaymentId)}`,
          {
            method: 'GET',
            headers,
            redirect: 'manual',
            signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
          },
        )
        const what = `reading payment ${molliePaymentId}`
        const found = await readPayment(response, what)
        if (found !== null && found.id !== molliePaymentId) {
          logger.error(`Mollie answered ${what} with a different payment`)
          return null
        }
        return found
      }),

    cancel: async (molliePaymentId) =>
      (await upstream('The Mollie payment could not be cancelled', async () => {
        const response = await fetch(
          `${MOLLIE_PAYMENTS_URL}/${encodeURIComponent(molliePaymentId)}`,
          {
            method: 'DELETE',
            headers,
            redirect: 'manual',
            signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
          },
        )
        await response.body?.cancel()
        if (!response.ok) {
          logger.warn(
            `Mollie refused to cancel payment ${molliePaymentId} with status ${String(response.status)}`,
          )
        }
        return response.ok
      })) ?? false,
  }
}

/** The one request shape this shop sends; a `null` phone is left out, which Mollie requires. */
function createRequest(settings: Settings['mollie'], order: PayableOrder) {
  const redirectUrl = new URL(settings.redirectUrl)
  redirectUrl.searchParams.append('orderId', String(order.orderId))
  const address = (stored: OrderAddress) => ({
    givenName: stored.firstName,
    familyName: stored.lastName,
    email: order.email,
    phone: e164Phone(order.phone, stored.country) ?? undefined,
    streetAndNumber:
      stored.houseNumber.trim() === '' ? stored.street : `${stored.street} ${stored.houseNumber}`,
    city: stored.city,
    postalCode: stored.postalCode,
    country: stored.country,
  })
  return {
    amount: { currency: CURRENCY, value: amountText(order.totalCents) },
    description: `Order #${String(order.orderId)}`,
    redirectUrl: redirectUrl.toString(),
    webhookUrl: settings.webhookUrl,
    billingAddress: address(order.billingAddress),
    shippingAddress: address(order.shippingAddress),
    metadata: { orderId: order.orderId },
  }
}

/** Integer cents as the exact two-decimal string Mollie requires: `4070` is `"40.70"`. */
export function amountText(cents: number): string {
  return `${String(Math.trunc(cents / 100))}.${String(cents % 100).padStart(2, '0')}`
}

/**
 * Mollie's amount as integer cents, or `null` unless it is EUR with at most two significant
 * decimals and fits the `integer` columns. An amount that cannot be interpreted is not rounded into
 * something plausible: the amount check on `PAID` is the one guard against paying too little.
 */
function centsOf(amount: { currency: string; value: string }): number | null {
  if (amount.currency.trim() !== CURRENCY) return null
  const match = /^(\d+)(?:\.(\d*))?$/.exec(amount.value.trim())
  if (match?.[1] === undefined) return null
  const fraction = (match[2] ?? '').replace(/0+$/, '')
  if (fraction.length > 2) return null
  const cents = Number(match[1]) * 100 + Number(fraction.padEnd(2, '0'))
  return cents <= 2_147_483_647 ? cents : null
}

/** The status word Mollie named, or `null` for one this backend does not know. */
function statusOf(value: string): PaymentStatus | null {
  const upper = value.trim().toUpperCase()
  return PAYMENT_STATUSES.find((status) => status === upper) ?? null
}

/**
 * The customer's phone in E.164, or `null` when it cannot be one: a number with `+` carries its own
 * country, one without is read in the country of the address it belongs to, and anything that
 * does not parse as a valid number is left out, because Mollie rejects the whole payment over one
 * malformed field. Nothing here is logged; a phone number is customer data.
 */
function e164Phone(phone: string | null, country: string): string | null {
  const trimmed = (phone ?? '').trim()
  if (trimmed === '') return null
  const region = country.trim().toUpperCase()
  let parsed
  if (trimmed.startsWith('+')) parsed = parsePhoneNumberFromString(trimmed)
  else if (isSupportedCountry(region)) parsed = parsePhoneNumberFromString(trimmed, region)
  else return null
  return parsed?.isValid() === true ? parsed.number : null
}
