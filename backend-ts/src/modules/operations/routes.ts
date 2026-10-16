import { zValidator } from '@hono/zod-validator'
import { Hono, type Context } from 'hono'
import { createMiddleware } from 'hono/factory'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { apiError, jsonBody, respondResult, validationFailed } from '@/platform/http/respond'
import type { FindingRepository } from './repository'
import type { OperationsService } from './service'

const ORDER_NOT_FOUND = 'Order not found'
const JOB_NOT_FOUND = 'Production job not found'

/** A findings or order answer names orders and their state; no cache may keep it. */
const noStore = createMiddleware(async (c, next) => {
  c.header('Cache-Control', 'no-store')
  await next()
})

/**
 * The order id of a timeline path. An id that is not a number never named an order, so it answers
 * the same `404` an unknown one does, as the order routes do.
 */
const orderIdParam = zValidator(
  'param',
  z.object({
    orderId: z
      .string()
      .regex(/^\d{1,15}$/)
      .transform(Number),
  }),
  (result, c) => (result.success ? undefined : c.json(apiError(ORDER_NOT_FOUND), 404)),
)

/** A job id that is not a number answers like an unknown one, as the fulfillment routes do. */
const jobIdParam = zValidator(
  'param',
  z.object({
    jobId: z
      .string()
      .regex(/^\d{1,15}$/)
      .transform(Number),
  }),
  (result, c) => (result.success ? undefined : c.json(apiError(JOB_NOT_FOUND), 404)),
)

/**
 * The release of a quarantined SPOD job: the decision the admin took in the partner's backoffice,
 * the partner's order id for `CREATED`, and the job's reference typed by hand (compared by the
 * production module, which knows the job's order).
 */
const releaseInput = z.discriminatedUnion('decision', [
  z.strictObject({ decision: z.literal('NOT_CREATED'), confirmation: z.string() }),
  z.strictObject({
    decision: z.literal('CREATED'),
    externalReference: z
      .string()
      .min(1, 'ExternalReference must not be empty')
      .max(128, 'ExternalReference must be at most 128 characters')
      .refine(
        (reference) => !/\p{Cc}/u.test(reference),
        'ExternalReference must not contain control characters',
      ),
    confirmation: z.string(),
  }),
])

/** The admin who triggered an action; `requireAdmin` guarantees one. */
function adminUserId(c: Context): number {
  const userId = c.var.principal?.userId
  if (userId === undefined) throw new Error('An admin action has no user id to record')
  return userId
}

/** The e-mail filter of the order list; an empty value is no filter. */
const orderListQuery = z.object({
  email: z
    .string()
    .trim()
    .optional()
    .transform((email) => (email === undefined || email === '' ? null : email)),
})

/**
 * The admin operations routes: the findings list (the open findings, or with `?resolved=true` the
 * ones resolved within the last seven days, which carry `resolvedAt` as well; any other value of
 * `resolved` is the open list), the order list with its e-mail filter, one order's timeline, and
 * the two admin actions (the payment recheck and the SPOD job release). The order routes share
 * `/api/admin/orders` with the order module's production PDF routes, the release shares
 * `/api/admin/production/jobs` with production's job routes. `requireAdmin` checks the CSRF pair
 * of every `POST`.
 */
export function operationsRoutes(findings: FindingRepository, service: OperationsService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin, noStore)
  admin.get('/findings', async (c) => {
    const resolved = c.req.query('resolved') === 'true'
    const rows = await findings.list(resolved)
    return c.json(
      rows.map((row) => ({
        kind: row.kind,
        subject: { type: row.subject_type, id: row.subject_id },
        orderId: row.order_id,
        since: row.since,
        attemptCount: row.attempt_count,
        lastErrorCode: row.last_error_code,
        firstSeenAt: row.first_seen_at,
        alertedAt: row.alerted_at,
        ...(resolved ? { resolvedAt: row.resolved_at } : {}),
      })),
    )
  })

  const orders = new Hono()
  orders.use(requireAdmin, noStore)
  orders.get(
    '/',
    zValidator('query', orderListQuery, (result, c) =>
      result.success ? undefined : c.json(validationFailed(result.error), 400),
    ),
    async (c) => c.json(await service.orders(c.req.valid('query').email)),
  )
  orders.get('/:orderId/timeline', orderIdParam, async (c) =>
    respondResult(c, await service.timeline(c.req.valid('param').orderId), {
      notFound: ORDER_NOT_FOUND,
      conflict: null,
    }),
  )
  orders.post('/:orderId/payment/recheck', orderIdParam, async (c) =>
    respondResult(c, await service.recheckPayment(c.req.valid('param').orderId, adminUserId(c)), {
      notFound: ORDER_NOT_FOUND,
      conflict: 'The order has no payment',
    }),
  )

  const jobs = new Hono()
  jobs.use(requireAdmin, noStore)
  jobs.post('/:jobId/spod/release', jobIdParam, jsonBody(releaseInput), async (c) =>
    respondResult(
      c,
      await service.releaseSpodJob(c.req.valid('param').jobId, c.req.valid('json'), adminUserId(c)),
      {
        notFound: JOB_NOT_FOUND,
        conflict: 'The job is not quarantined or the external reference belongs to another job',
      },
    ),
  )

  const routes = new Hono()
  routes.route('/api/admin/operations', admin)
  routes.route('/api/admin/orders', orders)
  routes.route('/api/admin/production/jobs', jobs)
  return routes
}
