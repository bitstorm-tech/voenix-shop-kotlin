import { Hono } from 'hono'
import type { Kysely } from 'kysely'
import { z } from 'zod'
import { SPOD_SIMULATED_EVENTS, type SpodSimulatedEvent } from '@/modules/spod'
import { requireAdmin } from '@/platform/auth/auth'
import { read, type Tables } from '@/platform/db/database'
import { apiError, integerParam, jsonBody } from '@/platform/http/respond'
import type { SecretBox } from '@/platform/secret-box'
import type { WebhookClient } from './webhooks'
import '../schema'

/** How many of the newest staging orders the page lists. */
const LIST_LIMIT = 100

/**
 * The SPOD orders that exist at the partner and belong to a supplier whose enabled SPOD destination
 * is `STAGING`. The order row does not record its installation; the supplier's current destination
 * stands in for it, and a call can only ever reach the staging installation.
 */
const stagingOrders = (db: Kysely<Tables>) =>
  db
    .selectFrom('production_spod_orders as o')
    .innerJoin('production_jobs as j', 'j.id', 'o.production_job_id')
    .innerJoin('production_requests as r', 'r.id', 'j.request_id')
    .innerJoin('production_destinations as d', (join) =>
      join.onRef('d.supplier_id', '=', 'j.supplier_id').on('d.enabled', '=', true),
    )
    .innerJoin('production_destination_spod as s', 's.id', 'd.id')
    .where('s.environment', '=', 'STAGING')
    .where('o.external_reference', 'is not', null)

/**
 * The admin's simulation of the partner's webhook events for staging orders (SPOD fulfillment,
 * "Simulating events on staging"). The partner sends the event to the subscribed webhook like a
 * real one; nothing here writes a row.
 */
export function spodSimulationRoutes(
  db: Kysely<Tables>,
  secrets: SecretBox,
  client: WebhookClient,
) {
  const admin = new Hono()
  admin.use(requireAdmin)

  admin.get('/', async (c) =>
    c.json(
      await read(db, (trx) =>
        stagingOrders(trx)
          .select([
            'j.id as jobId',
            'r.order_id as orderId',
            'o.external_reference as spodOrderId',
            'o.remote_state as remoteState',
            'o.confirmed_at as confirmedAt',
            'j.shipped_at as shippedAt',
            'j.created_at as createdAt',
          ])
          .orderBy('j.id', 'desc')
          .limit(LIST_LIMIT)
          .execute(),
      ),
    ),
  )

  admin.post(
    '/:jobId',
    integerParam('jobId', 'Invalid production job id'),
    jsonBody(z.strictObject({ event: z.enum(SPOD_SIMULATED_EVENTS) })),
    async (c) => {
      const row = await read(db, (trx) =>
        stagingOrders(trx)
          .select(['o.external_reference', 'd.id', 's.access_token', 's.timeout_seconds'])
          .where('j.id', '=', c.req.valid('param').jobId)
          .executeTakeFirst(),
      )
      if (row?.external_reference == null) {
        return c.json(apiError('No SPOD order of a staging destination has this job id'), 404)
      }
      const event: SpodSimulatedEvent = c.req.valid('json').event
      const result = await client.simulate(
        {
          destinationId: row.id,
          environment: 'STAGING',
          accessToken: secrets.open(row.access_token),
          timeoutSeconds: row.timeout_seconds,
        },
        row.external_reference,
        event,
      )
      if (result.kind === 'failed') {
        return c.json(apiError('Spreadconnect did not answer the call', {}, result.error), 502)
      }
      return c.body(null, 202)
    },
  )

  const routes = new Hono()
  routes.route('/api/admin/production/spod-simulation', admin)
  return routes
}
