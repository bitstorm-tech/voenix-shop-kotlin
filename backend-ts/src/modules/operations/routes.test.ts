import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { CSRF_HEADER } from '@/platform/auth/cookies'
import { composedApplication, type ComposedApplication } from '@/test-fixtures'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders } from '@test-support/http'
import { createFindingRepository, type Finding } from './repository'

/**
 * The admin operations routes through the composed application, so the two order routes are
 * checked next to the order module's production PDF routes on the same `/api/admin/orders` path.
 */

let composed: ComposedApplication | undefined
// Registered before `testDatabase()` so the pool closes before the database is dropped.
afterAll(async () => {
  await composed?.stop()
})
const database = testDatabase()
beforeAll(async () => {
  composed = await composedApplication(database.settings)
})

const FINDINGS = '/api/admin/operations/findings'
const ORDERS = '/api/admin/orders'

const SINCE = new Date('2026-10-16T10:00:00.000Z')

const finding = (kind: Finding['kind'], id: number, orderId = 42): Finding =>
  kind === 'DELIVERY_STALLED'
    ? {
        kind,
        subject: { type: 'DELIVERY', id },
        orderId,
        since: SINCE,
        attemptCount: 3,
        lastErrorCode: 'SFTP_CONNECT_FAILED',
      }
    : {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id },
        orderId: 43,
        since: SINCE,
        attemptCount: null,
        lastErrorCode: null,
      }

const app = () => {
  if (composed === undefined) throw new Error('The application exists only inside a test')
  return composed.app
}
const get = async (path: string, roles = ['ADMIN']) =>
  app().request(path, { headers: await signedInHeaders({ roles }) })

beforeEach(async () => {
  await sql`TRUNCATE operations_findings, operations_last_scan, orders, carts, payments, production_requests,
    production_destinations, suppliers, email_jobs RESTART IDENTITY CASCADE`.execute(database.db)
})

/** An order placed at `createdAt` by `email`, with one line; its access token is `token-{n}`. */
async function seedOrder(email: string, createdAt: Date): Promise<number> {
  const token = `token-${email}-${String(createdAt.getTime())}`
  const { rows } = await sql<{ id: number }>`WITH cart AS (
      INSERT INTO carts (guest_session_token, status) VALUES (${token}, 'CHECKED_OUT') RETURNING id
    )
    INSERT INTO orders (cart_id, guest_session_token, access_token, status,
      shipping_first_name, shipping_last_name, shipping_street, shipping_house_number,
      shipping_postal_code, shipping_city, shipping_country, billing_first_name, billing_last_name,
      billing_street, billing_house_number, billing_postal_code, billing_city, billing_country,
      email, phone, subtotal_cents, shipping_cost_cents, discount_cents, total_cents, created_at,
      updated_at)
    SELECT id, ${token}, ${token}, 'PAID', 'Erika', 'Muster', 'Weg', '5', '20095', 'Hamburg', 'DE',
      'Hans', 'Muster', 'Platz', '1', '10115', 'Berlin', 'DE', ${email}, '+49 30 1', 3580, 490, 0,
      4070, ${createdAt}, ${createdAt} FROM cart
    RETURNING id`.execute(database.db)
  const orderId = rows[0]?.id ?? 0
  await database.db
    .insertInto('order_items')
    .values({
      order_id: orderId,
      position: 1,
      article_id: 1,
      variant_id: 1,
      article_type: 'MUG',
      article_name: 'Tasse',
      variant_name: 'Weiß',
      quantity: 2,
      price_cents: 1790,
      prompt_price_cents: 0,
    })
    .execute()
  return orderId
}

const AT = new Date('2026-10-16T09:00:00.000Z')
const LATER = new Date('2026-10-16T09:05:00.000Z')

/**
 * One paid order with a row in every section: a payment, two history events, a processed request
 * with a generated SFTP job (one delivery) and a quarantined SPOD job, a confirmation mail, and an
 * open and a resolved finding.
 */
async function seedTimeline() {
  const orderId = await seedOrder('kunde@example.test', AT)
  const { db } = database
  await db
    .insertInto('payments')
    .values({
      order_id: orderId,
      mollie_payment_id: 'tr_1',
      status: 'PAID',
      amount_cents: 4070,
      checkout_url: 'https://mollie.test/checkout/tr_1',
      created_at: AT,
      updated_at: LATER,
    })
    .execute()
  await db
    .insertInto('order_events')
    .values([
      { order_id: orderId, occurred_at: AT, kind: 'ORDER_PLACED', actor_type: 'CUSTOMER' },
      {
        order_id: orderId,
        occurred_at: LATER,
        kind: 'PAYMENT_STATUS_CHANGED',
        actor_type: 'SYSTEM',
        cause: 'WEBHOOK',
        details: JSON.stringify({ paymentId: 1, from: 'OPEN', to: 'PAID' }),
      },
    ])
    .execute()
  const [printer, partner] = await db
    .insertInto('suppliers')
    .values([{ name: 'Druckerei' }, { name: 'Spreadconnect' }])
    .returning('id')
    .execute()
  const supplierId = printer?.id ?? 0
  const { id: destinationId } = await db
    .insertInto('production_destinations')
    .values({ supplier_id: supplierId, channel: 'SFTP', label: 'Hotfolder' })
    .returning('id')
    .executeTakeFirstOrThrow()
  const { id: requestId } = await db
    .insertInto('production_requests')
    .values({ order_id: orderId, attempt_count: 1, created_at: LATER, processed_at: LATER })
    .returning('id')
    .executeTakeFirstOrThrow()
  const [sftpJob, spodJob] = await db
    .insertInto('production_jobs')
    .values([
      {
        request_id: requestId,
        supplier_id: supplierId,
        fulfillment_channel: 'SFTP',
        file_name: 'ORD-1.pdf',
        content_sha256: 'digest',
        generation_attempt_count: 1,
        generated_at: LATER,
      },
      {
        request_id: requestId,
        supplier_id: partner?.id ?? 0,
        fulfillment_channel: 'SPOD',
        file_name: 'ORD-1.pdf',
      },
    ])
    .returning('id')
    .execute()
  await db
    .insertInto('production_deliveries')
    .values({
      production_job_id: sftpJob?.id ?? 0,
      destination_id: destinationId,
      attempt_count: 3,
      last_error_code: 'SFTP_CONNECT_FAILED',
    })
    .execute()
  await db
    .insertInto('production_spod_orders')
    .values({
      production_job_id: spodJob?.id ?? 0,
      create_state: 'OUTCOME_UNKNOWN',
      create_ambiguous_count: 2,
      attempt_count: 2,
      last_error_code: 'SPOD_TIMEOUT',
    })
    .execute()
  await db
    .insertInto('email_jobs')
    .values({
      payload: JSON.stringify({ kind: 'ORDER_CONFIRMATION', recipient: 'kunde@example.test' }),
      order_id: orderId,
      created_at: AT,
    })
    .execute()
  const findings = createFindingRepository(db)
  await findings.record([finding('DELIVERY_STALLED', 1, orderId)])
  await db.updateTable('operations_findings').set({ resolved_at: LATER }).execute()
  await findings.record([finding('DELIVERY_STALLED', 1, orderId)])
  return orderId
}

describe('GET /api/admin/operations/findings', () => {
  test('is for admins only and never cached', async () => {
    expect((await app().request(FINDINGS)).status).toBe(401)
    const customer = await get(FINDINGS, ['USER'])
    expect(customer.status).toBe(403)
    const admin = await get(FINDINGS)
    expect(admin.status).toBe(200)
    expect(admin.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(admin)).toEqual([])
  })

  test('lists the open findings, newest first seen first', async () => {
    const repository = createFindingRepository(database.db)
    await repository.record([finding('DELIVERY_STALLED', 2)])
    await repository.record([finding('DELIVERY_STALLED', 2), finding('SHIPMENT_OVERDUE', 3)])

    expect(await jsonOf(await get(FINDINGS))).toEqual([
      {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id: 3 },
        orderId: 43,
        since: SINCE.toISOString(),
        attemptCount: null,
        lastErrorCode: null,
        firstSeenAt: expect.any(String) as unknown,
        alertedAt: null,
      },
      {
        kind: 'DELIVERY_STALLED',
        subject: { type: 'DELIVERY', id: 2 },
        orderId: 42,
        since: SINCE.toISOString(),
        attemptCount: 3,
        lastErrorCode: 'SFTP_CONNECT_FAILED',
        firstSeenAt: expect.any(String) as unknown,
        alertedAt: null,
      },
    ])
  })

  test('with resolved=true lists the findings resolved within seven days, with resolvedAt', async () => {
    const repository = createFindingRepository(database.db)
    await repository.record([finding('DELIVERY_STALLED', 2), finding('SHIPMENT_OVERDUE', 3)])
    await repository.record([finding('SHIPMENT_OVERDUE', 3)])
    await repository.record([finding('DELIVERY_STALLED', 4)])
    // Resolved eight days ago: out of the list.
    await database.db
      .updateTable('operations_findings')
      .set({ resolved_at: sql<Date>`now() - interval '8 days'` })
      .where('subject_id', '=', 2)
      .execute()

    expect(await jsonOf(await get(`${FINDINGS}?resolved=true`))).toEqual([
      {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id: 3 },
        orderId: 43,
        since: SINCE.toISOString(),
        attemptCount: null,
        lastErrorCode: null,
        firstSeenAt: expect.any(String) as unknown,
        alertedAt: null,
        resolvedAt: expect.any(String) as unknown,
      },
    ])
    expect(await jsonOf(await get(FINDINGS))).toMatchObject([
      { subject: { type: 'DELIVERY', id: 4 } },
    ])
  })
})

describe('GET /api/admin/operations/last-scan', () => {
  const LAST_SCAN = '/api/admin/operations/last-scan'

  test('is for admins only, never cached, and null before the first scan', async () => {
    expect((await app().request(LAST_SCAN)).status).toBe(401)
    expect((await get(LAST_SCAN, ['USER'])).status).toBe(403)
    const admin = await get(LAST_SCAN)
    expect(admin.status).toBe(200)
    expect(admin.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(admin)).toEqual({ lastScanAt: null })
  })

  test('answers when the last scan recorded its findings', async () => {
    const repository = createFindingRepository(database.db)
    await repository.record([])
    const recorded = await repository.lastScanAt()

    expect(await jsonOf(await get(LAST_SCAN))).toEqual({ lastScanAt: recorded?.toISOString() })
  })
})

describe('GET /api/admin/orders/{orderId}/timeline', () => {
  test('answers every section of one order, never its access token', async () => {
    const orderId = await seedTimeline()
    const response = await get(`${ORDERS}/${String(orderId)}/timeline`)
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    const body = await jsonOf(response)
    expect(JSON.stringify(body)).not.toContain('token-')
    expect(body).toEqual({
      order: {
        orderId,
        createdAt: AT.toISOString(),
        status: 'PAID',
        paymentStatus: 'PAID',
        subtotal: 3580,
        shippingCost: 490,
        discountAmount: 0,
        total: 4070,
        items: [
          {
            orderItemId: 1,
            articleId: 1,
            variantId: 1,
            articleType: 'MUG',
            articleName: 'Tasse',
            variantName: 'Weiß',
            quantity: 2,
            price: 1790,
            promptPrice: 0,
            imageId: null,
          },
        ],
        email: 'kunde@example.test',
        phone: '+49 30 1',
        shippingAddress: {
          firstName: 'Erika',
          lastName: 'Muster',
          street: 'Weg',
          houseNumber: '5',
          postalCode: '20095',
          city: 'Hamburg',
          country: 'DE',
        },
        billingAddress: {
          firstName: 'Hans',
          lastName: 'Muster',
          street: 'Platz',
          houseNumber: '1',
          postalCode: '10115',
          city: 'Berlin',
          country: 'DE',
        },
        cartId: 1,
        promotionId: null,
        updatedAt: AT.toISOString(),
      },
      payments: [
        {
          paymentId: 1,
          molliePaymentId: 'tr_1',
          status: 'PAID',
          amount: 4070,
          createdAt: AT.toISOString(),
          updatedAt: LATER.toISOString(),
        },
      ],
      production: {
        request: {
          id: 1,
          attemptCount: 1,
          lastErrorCode: null,
          createdAt: LATER.toISOString(),
          processedAt: LATER.toISOString(),
        },
        jobs: [
          {
            jobId: 1,
            supplierId: 1,
            fulfillmentChannel: 'SFTP',
            generationAttemptCount: 1,
            lastGenerationErrorCode: null,
            generatedAt: LATER.toISOString(),
            preparedAt: null,
            shippedAt: null,
            shippedByUserId: null,
            shippedByChannel: null,
            shippingCarrier: null,
            trackingNumber: null,
            deliveries: [
              {
                deliveryId: 1,
                destinationId: 1,
                attemptCount: 3,
                lastErrorCode: 'SFTP_CONNECT_FAILED',
                deliveredAt: null,
              },
            ],
            spod: null,
          },
          {
            jobId: 2,
            supplierId: 2,
            fulfillmentChannel: 'SPOD',
            generationAttemptCount: 0,
            lastGenerationErrorCode: null,
            generatedAt: null,
            preparedAt: null,
            shippedAt: null,
            shippedByUserId: null,
            shippedByChannel: null,
            shippingCarrier: null,
            trackingNumber: null,
            deliveries: [],
            spod: {
              externalReference: null,
              createState: 'OUTCOME_UNKNOWN',
              createAmbiguousCount: 2,
              attemptCount: 2,
              lastErrorCode: 'SPOD_TIMEOUT',
              confirmedAt: null,
              remoteState: null,
            },
          },
        ],
      },
      emails: [
        {
          emailJobId: 1,
          kind: 'ORDER_CONFIRMATION',
          recipient: 'kunde@example.test',
          attemptCount: 0,
          lastErrorCode: null,
          createdAt: AT.toISOString(),
          sentAt: null,
        },
      ],
      events: [
        {
          id: 1,
          occurredAt: AT.toISOString(),
          kind: 'ORDER_PLACED',
          actorType: 'CUSTOMER',
          actorId: null,
          cause: null,
          details: null,
        },
        {
          id: 2,
          occurredAt: LATER.toISOString(),
          kind: 'PAYMENT_STATUS_CHANGED',
          actorType: 'SYSTEM',
          actorId: null,
          cause: 'WEBHOOK',
          details: { paymentId: 1, from: 'OPEN', to: 'PAID' },
        },
      ],
      findings: [
        {
          kind: 'DELIVERY_STALLED',
          subject: { type: 'DELIVERY', id: 1 },
          since: SINCE.toISOString(),
          attemptCount: 3,
          lastErrorCode: 'SFTP_CONNECT_FAILED',
          firstSeenAt: expect.any(String) as unknown,
          alertedAt: null,
        },
      ],
    })
  })

  test('an order without payment or production answers empty sections', async () => {
    const orderId = await seedOrder('kunde@example.test', AT)
    expect(await jsonOf(await get(`${ORDERS}/${String(orderId)}/timeline`))).toMatchObject({
      order: { orderId, paymentStatus: null },
      payments: [],
      production: { request: null, jobs: [] },
      emails: [],
      events: [],
      findings: [],
    })
  })

  test('a missing or non-numeric id is the same 404, never cached; customers are refused', async () => {
    for (const id of ['99', 'abc', '1e3']) {
      const response = await get(`${ORDERS}/${id}/timeline`)
      expect(response.status).toBe(404)
      expect(response.headers.get('Cache-Control')).toBe('no-store')
      expect(await jsonOf(response)).toMatchObject({ message: 'Order not found' })
    }
    expect((await get(`${ORDERS}/1/timeline`, ['USER'])).status).toBe(403)
  })
})

describe('GET /api/admin/orders', () => {
  test('lists the newest orders with their open findings, filtered by exact e-mail in any case', async () => {
    const first = await seedOrder('kunde@example.test', AT)
    const second = await seedOrder('andere@example.test', LATER)
    await createFindingRepository(database.db).record([
      finding('DELIVERY_STALLED', 1, first),
      finding('DELIVERY_STALLED', 2, first),
    ])

    const all = await get(ORDERS)
    expect(all.status).toBe(200)
    expect(all.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(all)).toEqual([
      {
        orderId: second,
        createdAt: LATER.toISOString(),
        status: 'PAID',
        paymentStatus: null,
        total: 4070,
        email: 'andere@example.test',
        openFindings: 0,
      },
      {
        orderId: first,
        createdAt: AT.toISOString(),
        status: 'PAID',
        paymentStatus: null,
        total: 4070,
        email: 'kunde@example.test',
        openFindings: 2,
      },
    ])
    expect(await jsonOf(await get(`${ORDERS}?email=%20KUNDE@Example.test`))).toMatchObject([
      { orderId: first },
    ])
    expect(await jsonOf(await get(`${ORDERS}?email=kunde@example`))).toEqual([])
    expect(await jsonOf(await get(`${ORDERS}?email=`))).toHaveLength(2)
    expect((await get(ORDERS, ['USER'])).status).toBe(403)
  })

  test('answers at most the newest 50 orders', async () => {
    for (let index = 0; index < 51; index += 1) {
      await seedOrder('kunde@example.test', new Date(AT.getTime() + index * 1000))
    }
    const list = (await jsonOf(await get(ORDERS))) as { orderId: number }[]
    expect(list).toHaveLength(50)
    expect(list[0]?.orderId).toBe(51)
    expect(list.at(-1)?.orderId).toBe(2)
  })
})

/** A `POST` of `body` as JSON; `csrf: false` drops the CSRF header a browser would send. */
const post = async (
  path: string,
  body?: unknown,
  { roles = ['ADMIN'], csrf = true }: { roles?: string[]; csrf?: boolean } = {},
) => {
  const { [CSRF_HEADER]: token, ...headers } = await signedInHeaders({ roles })
  return app().request(path, {
    method: 'POST',
    headers: {
      ...headers,
      ...(csrf && token !== undefined ? { [CSRF_HEADER]: token } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

const events = () =>
  database.db
    .selectFrom('order_events')
    .select(['kind', 'actor_type', 'actor_id', 'cause', 'details'])
    .where('kind', 'in', ['PAYMENT_STATUS_CHANGED', 'SPOD_JOB_RELEASED'])
    .where('actor_id', 'is not', null)
    .orderBy('id')
    .execute()

describe('POST /api/admin/orders/{orderId}/payment/recheck', () => {
  const recheck = (orderId: string, options?: Parameters<typeof post>[2]) =>
    post(`${ORDERS}/${orderId}/payment/recheck`, undefined, options)

  test('answers the order and payment status, never cached', async () => {
    const orderId = await seedTimeline()
    const response = await recheck(String(orderId))
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(response)).toEqual({ status: 'PAID', paymentStatus: 'PAID' })
  })

  test('an order without a payment is a 409, a missing or non-numeric id a 404', async () => {
    const orderId = await seedOrder('kunde@example.test', AT)
    const conflict = await recheck(String(orderId))
    expect(conflict.status).toBe(409)
    expect(conflict.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(conflict)).toEqual({ message: 'The order has no payment', errors: {} })
    for (const id of ['99', 'abc']) {
      const response = await recheck(id)
      expect(response.status).toBe(404)
      expect(await jsonOf(response)).toMatchObject({ message: 'Order not found' })
    }
  })

  test('is for admins only and needs the CSRF token', async () => {
    const orderId = String(await seedTimeline())
    expect(
      (await app().request(`${ORDERS}/${orderId}/payment/recheck`, { method: 'POST' })).status,
    ).toBe(401)
    expect((await recheck(orderId, { roles: ['USER'] })).status).toBe(403)
    const forged = await recheck(orderId, { csrf: false })
    expect(forged.status).toBe(400)
    expect(await jsonOf(forged)).toMatchObject({ message: 'Invalid CSRF token' })
  })
})

describe('POST /api/admin/production/jobs/{jobId}/spod/release', () => {
  const RELEASE = '/api/admin/production/jobs/2/spod/release'
  const CONFIRMATION = 'ORD-1-JOB-2'

  test('adopts the partner order of the quarantined job and records the admin', async () => {
    await seedTimeline()
    const response = await post(RELEASE, {
      decision: 'CREATED',
      externalReference: 'spod-42',
      confirmation: CONFIRMATION,
    })
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(response)).toMatchObject({
      jobId: 2,
      orderId: 1,
      fulfillmentChannel: 'SPOD',
      externalReference: 'spod-42',
      createState: 'CREATED',
    })
    expect(await events()).toEqual([
      {
        kind: 'SPOD_JOB_RELEASED',
        actor_type: 'ADMIN',
        actor_id: 11,
        cause: null,
        details: { jobId: 2, decision: 'CREATED' },
      },
    ])

    const again = await post(RELEASE, { decision: 'NOT_CREATED', confirmation: CONFIRMATION })
    expect(again.status).toBe(409)
    expect(await jsonOf(again)).toEqual({
      message: 'The job is not quarantined or the external reference belongs to another job',
      errors: {},
    })
  })

  test('NOT_CREATED starts the creation over; an SFTP job is never quarantined', async () => {
    await seedTimeline()
    const response = await post(RELEASE, { decision: 'NOT_CREATED', confirmation: CONFIRMATION })
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toMatchObject({
      createState: 'PENDING',
      externalReference: null,
    })
    const sftp = await post('/api/admin/production/jobs/1/spod/release', {
      decision: 'NOT_CREATED',
      confirmation: 'ORD-1-JOB-1',
    })
    expect(sftp.status).toBe(409)
  })

  test('a wrong confirmation or an invalid body is a 400, an unknown job a 404', async () => {
    await seedTimeline()
    const wrong = await post(RELEASE, { decision: 'NOT_CREATED', confirmation: 'ORD-1-JOB-3' })
    expect(wrong.status).toBe(400)
    expect(wrong.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(wrong)).toEqual({
      message: 'Validation failed',
      errors: { confirmation: ['Confirmation must be the job reference'] },
    })
    for (const body of [
      { decision: 'CREATED', confirmation: CONFIRMATION },
      { decision: 'CREATED', externalReference: '', confirmation: CONFIRMATION },
      { decision: 'CREATED', externalReference: 'x'.repeat(129), confirmation: CONFIRMATION },
      { decision: 'CREATED', externalReference: 'spod\n42', confirmation: CONFIRMATION },
      { decision: 'MAYBE', confirmation: CONFIRMATION },
    ]) {
      const response = await post(RELEASE, body)
      expect(response.status).toBe(400)
      expect(await jsonOf(response)).toMatchObject({ message: 'Validation failed' })
    }
    for (const id of ['99', 'abc']) {
      const response = await post(`/api/admin/production/jobs/${id}/spod/release`, {
        decision: 'NOT_CREATED',
        confirmation: CONFIRMATION,
      })
      expect(response.status).toBe(404)
      expect(await jsonOf(response)).toMatchObject({ message: 'Production job not found' })
    }
    const state = await database.db
      .selectFrom('production_spod_orders')
      .select('create_state')
      .executeTakeFirstOrThrow()
    expect(state.create_state).toBe('OUTCOME_UNKNOWN')
    expect(await events()).toEqual([])
  })

  test('is for admins only and needs the CSRF token', async () => {
    await seedTimeline()
    const body = { decision: 'NOT_CREATED', confirmation: CONFIRMATION }
    expect(
      (
        await app().request(RELEASE, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        })
      ).status,
    ).toBe(401)
    expect((await post(RELEASE, body, { roles: ['USER'] })).status).toBe(403)
    const forged = await post(RELEASE, body, { csrf: false })
    expect(forged.status).toBe(400)
    expect(await jsonOf(forged)).toMatchObject({ message: 'Invalid CSRF token' })
  })
})
