import { timingSafeEqual } from 'node:crypto'
import { Hono } from 'hono'
import { apiError } from '@/platform/http/respond'
import type { PaymentService } from './service'

/**
 * `POST /api/payments/webhook/{secret}`, called by Mollie and nobody else. Mollie has no session
 * and sends no CSRF token, so the route installs no protection; the secret path segment takes its
 * place and is compared, in constant time, before the body is read, Mollie is called, or the
 * database is touched. Only `id` is read from the form body. The answer is always empty: `200`
 * for everything a redelivery would not change, `502` when Mollie said nothing usable, and the
 * application's `500` for a database failure, both of which Mollie repairs by redelivering.
 */
export function paymentRoutes(payments: Pick<PaymentService, 'confirm'>, webhookSecret: string) {
  const routes = new Hono()
  routes.post('/api/payments/webhook/:secret', async (c) => {
    if (!secretMatches(c.req.param('secret'), webhookSecret)) {
      return c.json(apiError('Forbidden'), 403)
    }
    const id = (await c.req.parseBody()).id
    if (typeof id !== 'string' || id.trim() === '') {
      return c.json(apiError('Missing payment id'), 400)
    }
    const outcome = await payments.confirm(id.trim())
    return c.body(null, outcome === 'providerUnavailable' ? 502 : 200)
  })
  return routes
}

/** The length of the configured secret is not the secret, so comparing it first leaks nothing. */
function secretMatches(candidate: string, expected: string): boolean {
  const actual = Buffer.from(candidate, 'utf8')
  const wanted = Buffer.from(expected, 'utf8')
  return actual.length === wanted.length && timingSafeEqual(actual, wanted)
}
