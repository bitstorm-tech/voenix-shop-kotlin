import { zValidator } from '@hono/zod-validator'
import { Hono, type Context } from 'hono'
import { createMiddleware } from 'hono/factory'
import { z } from 'zod'
import { requireAdmin, requireSupplier, type SupplierAccounts } from '@/platform/auth/auth'
import { apiError, jsonBody } from '@/platform/http/respond'
import { carrierOf, SHIPPING_CARRIERS } from './carrier'
import type { Shipment } from './repository'
import type { ArtifactResult, FulfillmentService, ShipResult } from './service'
import { parseSpodWebhookEvent, secretMatches } from './webhook'

/**
 * The ship body: what the reporter optionally knows about the package. Both fields are optional and
 * independent, blank is absent. There is no `trackingUrl`: the shop builds every link in its mails
 * from its own carrier list, so a supplier login can never put a link into a mail the customer
 * trusts.
 */
export const shipInput = z
  .strictObject({
    carrier: z
      .string()
      .trim()
      .nullish()
      .refine(
        (name) => name === undefined || name === null || name === '' || carrierOf(name) !== null,
        `Carrier must be one of: ${SHIPPING_CARRIERS.join(', ')}`,
      )
      .transform((name) => carrierOf(name ?? null)),
    trackingNumber: z
      .string()
      .trim()
      .max(128, { error: 'TrackingNumber must be at most 128 characters', abort: true })
      .refine(
        (number) => !/\p{Cc}/u.test(number),
        'TrackingNumber must not contain control characters',
      )
      .nullish()
      .transform((number) => (number === undefined || number === '' ? null : number)),
  })
  .transform((body): Shipment => ({ ...body, reportedCarrierName: null }))

const JOB_NOT_FOUND = 'Production job not found'

/** A job id that is not a number answers like an unknown one: the id space is not the caller's. */
const jobId = zValidator(
  'param',
  z.object({
    jobId: z
      .string()
      .regex(/^\d{1,15}$/)
      .transform(Number),
  }),
  (result, c) => (result.success ? undefined : c.json(apiError(JOB_NOT_FOUND), 404)),
)

/** A job answer names a customer and an address; no cache may keep it. */
const noStore = createMiddleware(async (c, next) => {
  c.header('Cache-Control', 'no-store')
  await next()
})

/**
 * The supplier surface (scoped to the supplier the protection resolved on this very request), the
 * admin surface across every supplier, and, in a deployment with SPOD settings, the partner's
 * webhook outside every auth subtree with the secret in its path as its whole authentication.
 */
export function fulfillmentRoutes(
  fulfillment: FulfillmentService,
  accounts: SupplierAccounts,
  webhookSecret: string | null,
): Hono {
  const supplier = new Hono()
  supplier.use(requireSupplier(accounts), noStore)
  supplier.get('/me', async (c) => c.json(await fulfillment.identity(c.var.supplierId)))
  supplier.get('/production-jobs', async (c) => {
    const status = statusOf(c)
    if (status === null) return invalidStatus(c)
    return c.json(await fulfillment.supplierJobs(c.var.supplierId, status))
  })
  supplier.get('/production-jobs/:jobId/pdf', jobId, async (c) =>
    artifactAnswer(c, await fulfillment.artifact(c.req.valid('param').jobId, c.var.supplierId)),
  )
  supplier.post('/production-jobs/:jobId/ship', jobId, jsonBody(shipInput), async (c) =>
    shipAnswer(
      c,
      await fulfillment.shipAsSupplier(
        c.req.valid('param').jobId,
        c.var.supplierId,
        actorUserId(c),
        c.req.valid('json'),
      ),
    ),
  )

  const admin = new Hono()
  admin.use(requireAdmin, noStore)
  admin.get('/', async (c) => {
    const status = statusOf(c)
    if (status === null) return invalidStatus(c)
    // Absent means every supplier; present but unusable is a 400, never an unfiltered page.
    const requested = c.req.query('supplierId')
    const supplierId = requested === undefined ? null : Number(requested)
    if (supplierId !== null && (!/^\d{1,15}$/.test(requested ?? '') || supplierId <= 0)) {
      return c.json(apiError('Invalid supplier id', {}, 'INVALID_SUPPLIER_ID'), 400)
    }
    return c.json(await fulfillment.adminJobs(status, supplierId))
  })
  admin.get('/:jobId/pdf', jobId, async (c) =>
    artifactAnswer(c, await fulfillment.artifact(c.req.valid('param').jobId, null)),
  )
  // Ship on a supplier's behalf: the same write, with the administrator recorded as the actor.
  admin.post('/:jobId/ship', jobId, jsonBody(shipInput), async (c) =>
    shipAnswer(
      c,
      await fulfillment.shipAsAdmin(
        c.req.valid('param').jobId,
        actorUserId(c),
        c.req.valid('json'),
      ),
    ),
  )

  const routes = new Hono()
  routes.route('/api/supplier', supplier)
  routes.route('/api/admin/production/jobs', admin)
  if (webhookSecret !== null) {
    // The partner demands 202 `[accepted]` and redelivers anything else, so every processed event
    // and every no-op answers it. The secret is compared before the body is read; only a database
    // failure, the one thing a redelivery fixes, becomes a 500 by throwing.
    routes.post('/api/production/webhooks/spod/:secret', async (c) => {
      if (!secretMatches(c.req.param('secret'), webhookSecret)) {
        return c.json(apiError('Forbidden'), 403)
      }
      const event = parseSpodWebhookEvent(await c.req.text())
      if (event !== null) await fulfillment.handleWebhook(event)
      return c.body('[accepted]', 202, { 'Content-Type': 'application/json' })
    })
  }
  return routes
}

/** The requested list, `OPEN` when absent, `null` for a name this API does not know. */
function statusOf(c: Context): 'OPEN' | 'SHIPPED' | null {
  const requested = c.req.query('status') ?? 'OPEN'
  return requested === 'OPEN' || requested === 'SHIPPED' ? requested : null
}

const invalidStatus = (c: Context) =>
  c.json(apiError('Unknown production job status', {}, 'INVALID_STATUS'), 400)

/**
 * The signed-in user a ship is recorded as. Both subtrees are protected, so a call without a
 * usable user id is a wiring bug and fails as a 500 rather than a shipment by nobody.
 */
function actorUserId(c: Context): number {
  const userId = c.var.principal?.userId
  if (userId === undefined) throw new Error('A protected write has no user id to record')
  return userId
}

function artifactAnswer(c: Context, result: ArtifactResult): Response {
  switch (result.kind) {
    case 'loaded':
      return c.body(result.bytes, 200, {
        'Content-Type': 'application/pdf',
        'Content-Disposition': `attachment; filename="${result.fileName}"`,
      })
    case 'notFound':
      return c.json(apiError(JOB_NOT_FOUND), 404)
    case 'notGenerated':
      return c.json(
        apiError(
          'The production document for this job has not been generated yet',
          {},
          'ARTIFACT_NOT_GENERATED',
        ),
        409,
      )
    case 'missing':
    case 'digestMismatch':
      return c.json(
        apiError(
          'The production document for this job is not available',
          {},
          result.kind === 'missing' ? 'ARTIFACT_MISSING' : 'ARTIFACT_DIGEST_MISMATCH',
        ),
        409,
      )
  }
}

function shipAnswer<V>(c: Context, result: ShipResult<V>): Response {
  switch (result.kind) {
    case 'shipped':
      return c.json(result.job)
    case 'notFound':
      return c.json(apiError(JOB_NOT_FOUND), 404)
    case 'alreadyShipped':
      return c.json(
        apiError('This production job has already been shipped', {}, 'ALREADY_SHIPPED'),
        409,
      )
    case 'notReady':
      return c.json(
        apiError(
          'This production job cannot be shipped before it has been prepared',
          {},
          'NOT_READY',
        ),
        409,
      )
  }
}
