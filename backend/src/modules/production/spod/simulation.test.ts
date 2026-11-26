import { beforeEach, expect, test } from 'bun:test'
import type { SpodAccess } from '@/modules/spod'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'
import {
  productionTestKit,
  resetProduction,
  seedOrder,
  seedSpodDestination,
  seedSupplier,
  stubWebhookClient,
} from '../test-fixtures'

const database = testDatabase()
const PATH = '/api/admin/production/spod-simulation'

beforeEach(async () => {
  await resetProduction(database.db)
})

/** A SPOD job of `supplierId` whose remote order `spodOrderId` exists at the partner. */
async function seedSpodJob(supplierId: number, spodOrderId: string | null) {
  const orderId = await seedOrder(database.db)
  const { id: requestId } = await database.db
    .insertInto('production_requests')
    .values({ order_id: orderId })
    .returning('id')
    .executeTakeFirstOrThrow()
  const { id } = await database.db
    .insertInto('production_jobs')
    .values({
      request_id: requestId,
      supplier_id: supplierId,
      fulfillment_channel: 'SPOD',
      file_name: 'job.pdf',
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  await database.db
    .insertInto('production_spod_orders')
    .values({
      production_job_id: id,
      external_reference: spodOrderId,
      create_state: spodOrderId === null ? 'PENDING' : 'CREATED',
    })
    .execute()
  return id
}

test('lists only created orders of staging destinations and simulates on staging only', async () => {
  const calls: { access: SpodAccess; orderId: string; event: string }[] = []
  const kit = await productionTestKit(database.db, {
    webhookClient: stubWebhookClient({
      simulate: (access, orderId, event) => {
        calls.push({ access, orderId, event })
        return Promise.resolve({ kind: 'answered', value: undefined })
      },
    }),
  })
  const app = testApp(kit.module.routes)
  const headers = { ...(await signedInHeaders()), 'Content-Type': 'application/json' }
  const simulate = (jobId: number, event: string) =>
    app.request(`${PATH}/${String(jobId)}`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ event }),
    })

  const staging = await seedSupplier(database.db, 'Staging')
  const destinationId = await seedSpodDestination(database.db, staging)
  const created = await seedSpodJob(staging, '454000808')
  const pending = await seedSpodJob(staging, null)
  const production = await seedSupplier(database.db, 'Production')
  const productionDestination = await seedSpodDestination(database.db, production)
  await database.db
    .updateTable('production_destination_spod')
    .set({ environment: 'PRODUCTION' })
    .where('id', '=', productionDestination)
    .execute()
  const live = await seedSpodJob(production, '999')

  const listed = await app.request(PATH, { headers })
  expect(listed.status).toBe(200)
  expect(await jsonOf(listed)).toEqual([
    expect.objectContaining({ jobId: created, spodOrderId: '454000808', remoteState: null }),
  ])

  expect((await simulate(created, 'shipment-sent')).status).toBe(202)
  expect(calls).toEqual([
    {
      access: {
        destinationId,
        environment: 'STAGING',
        accessToken: 'spod-token',
        timeoutSeconds: 5,
      },
      orderId: '454000808',
      event: 'shipment-sent',
    },
  ])

  expect((await simulate(live, 'order-cancelled')).status).toBe(404)
  expect((await simulate(pending, 'order-cancelled')).status).toBe(404)
  expect((await simulate(created, 'order-needs-action')).status).toBe(400)
  expect(calls).toHaveLength(1)
})
