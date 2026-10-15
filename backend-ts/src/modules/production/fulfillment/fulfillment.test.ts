import { beforeEach, describe, expect, test } from 'bun:test'
import { readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { Hono } from 'hono'
import { write } from '@/platform/db/database'
import { seedUser, testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'
import { requestProduction } from '../delivery/requests'
import type { ProductionLine } from '../orders'
import {
  ALERT_EMAIL,
  line,
  productionData,
  productionTestKit,
  queuedEmails,
  resetProduction,
  seedOrder,
  seedSpodDestination,
  seedSupplier,
  stubSpodClient,
  WEBHOOK_SECRET,
  writeImage,
} from '../test-fixtures'
import { carrierOfReportedName, SHIPPING_CARRIERS, trackingUrl } from './carrier'

const database = testDatabase()

let kit: Awaited<ReturnType<typeof productionTestKit>>
let app: Hono
let image = ''
let supplierA = 0
let supplierB = 0
let spodSupplier = 0
let adminId = 0
let loginA = 0
let created = 0

/** A partner that accepts everything: designs, `spod-<n>` orders, and their confirmation. */
const happyPartner = stubSpodClient({
  uploadDesign: () => Promise.resolve({ kind: 'answered', value: 'design' }),
  createOrder: () => Promise.resolve({ kind: 'answered', value: `spod-${String(++created)}` }),
  getOrder: () => Promise.resolve({ kind: 'answered', value: 'NEW' }),
  confirmOrder: () => Promise.resolve({ kind: 'answered', value: undefined }),
})

beforeEach(async () => {
  await resetProduction(database.db)
  created = 0
  kit = await productionTestKit(database.db, { client: happyPartner })
  app = testApp(kit.module.routes)
  image = await writeImage(kit.root, 'print.png')
  supplierA = await seedSupplier(database.db, 'Tassenwerk')
  supplierB = await seedSupplier(database.db, 'Becherhaus')
  spodSupplier = await seedSupplier(database.db, 'Spreadconnect')
  await seedSpodDestination(database.db, spodSupplier)
  adminId = await seedUser(database.db, 'admin@voenix.test')
  loginA = await seedUser(database.db, 'a@tassenwerk.test')
  kit.logins.bind(loginA, supplierA)
  kit.catalog.set({ articleId: 1, variantId: 1 }, { supplierId: supplierA })
  kit.catalog.set({ articleId: 2, variantId: 2 }, { supplierId: supplierB })
  kit.catalog.set(
    { articleId: 3, variantId: 3 },
    {
      supplierId: spodSupplier,
      variantName: 'Black / M',
      spodProduct: { productTypeId: 6, appearanceId: 2, sizeId: 3 },
    },
  )
})

/** A paid order, requested and run through one worker scan; answers its order id. */
async function processedOrder(lines: ProductionLine[]) {
  const orderId = await seedOrder(database.db)
  kit.orders.add(productionData(orderId, lines))
  await write(database.db, (trx) => requestProduction(trx, orderId))
  await kit.worker.runOnce()
  return orderId
}

const mug = (overrides: Partial<ProductionLine> = {}) => line({ imagePath: image, ...overrides })
const beaker = () => line({ articleId: 2, variantId: 2, imagePath: image, articleName: 'Becher' })
const shirt = (spodHotspot: string | null = 'MEDIUM_FRONT') =>
  line({ articleId: 3, variantId: 3, imagePath: image, variantName: 'Black / M', spodHotspot })

const jobOf = async (orderId: number, supplierId: number) => {
  const job = await database.db
    .selectFrom('production_jobs as j')
    .innerJoin('production_requests as r', 'r.id', 'j.request_id')
    .select([
      'j.id',
      'j.shipped_by_user_id',
      'j.shipped_by_channel',
      'j.shipping_carrier',
      'j.shipping_carrier_reported',
      'j.tracking_number',
      'j.prepared_at',
    ])
    .where('r.order_id', '=', orderId)
    .where('j.supplier_id', '=', supplierId)
    .executeTakeFirstOrThrow()
  return job
}

const asSupplier = async () => signedInHeaders({ userId: loginA, roles: ['SUPPLIER'] })
const asAdmin = async () => signedInHeaders({ userId: adminId, roles: ['ADMIN'] })

const get = async (path: string, headers: Record<string, string>) => app.request(path, { headers })
const post = async (path: string, headers: Record<string, string>, body: unknown = {}) =>
  app.request(path, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
const webhook = (body: unknown, secret = WEBHOOK_SECRET) =>
  app.request(`/api/production/webhooks/spod/${secret}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })

const mailsOf = async (kind: string) =>
  (await queuedEmails(database.db)).filter((mail) => mail.kind === kind)

describe('the supplier surface', () => {
  test('names the supplier and lists only its own open jobs, without contact data or money', async () => {
    const orderId = await processedOrder([mug({ quantity: 2 }), beaker()])
    const headers = await asSupplier()
    const me = await get('/api/supplier/me', headers)
    expect(me.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(me)).toEqual({ supplierId: supplierA, supplierName: 'Tassenwerk' })

    const open = await get('/api/supplier/production-jobs', headers)
    expect(open.headers.get('Cache-Control')).toBe('no-store')
    const jobs = await jsonOf(open)
    expect(jobs).toEqual([
      {
        jobId: (await jobOf(orderId, supplierA)).id,
        orderId,
        orderDate: '2026-07-16',
        customerFirstName: 'Erika',
        customerLastName: 'Mustermann',
        shippingStreet: 'Heidestraße',
        shippingHouseNumber: '17',
        shippingPostalCode: '51147',
        shippingCity: 'Köln',
        shippingCountry: 'Deutschland',
        items: [
          {
            articleName: 'Zaubertasse',
            variantName: 'Weiß',
            supplierArticleNumber: 'SUP-001',
            quantity: 2,
          },
        ],
        pdfAvailable: true,
        shippedAt: null,
        shippingCarrier: null,
        trackingNumber: null,
      },
    ])
    for (const word of ['email', 'phone', 'price', 'total', 'token']) {
      expect(JSON.stringify(jobs).toLowerCase()).not.toContain(word)
    }
    expect(
      await jsonOf(await get('/api/supplier/production-jobs?status=SHIPPED', headers)),
    ).toEqual([])
    expect(await jsonOf(await get('/api/supplier/production-jobs?status=DONE', headers))).toEqual({
      message: 'Unknown production job status',
      errors: {},
      code: 'INVALID_STATUS',
    })
  })

  test('downloads its own PDF; a foreign or unusable id is simply not found', async () => {
    const orderId = await processedOrder([mug(), beaker()])
    const headers = await asSupplier()
    const own = await jobOf(orderId, supplierA)
    const pdf = await get(`/api/supplier/production-jobs/${String(own.id)}/pdf`, headers)
    expect(pdf.status).toBe(200)
    expect(pdf.headers.get('Content-Type')).toBe('application/pdf')
    expect(pdf.headers.get('Content-Disposition')).toBe(
      `attachment; filename="ORD-${String(orderId)}.pdf"`,
    )
    expect(new Uint8Array(await pdf.arrayBuffer())).toEqual(
      new Uint8Array(
        await readFile(join(kit.root, 'artifacts', String(own.id), `ORD-${String(orderId)}.pdf`)),
      ),
    )
    const foreign = await jobOf(orderId, supplierB)
    for (const jobId of [String(foreign.id), 'abc']) {
      const answer = await get(`/api/supplier/production-jobs/${jobId}/pdf`, headers)
      expect(answer.status).toBe(404)
      expect(await jsonOf(answer)).toEqual({ message: 'Production job not found', errors: {} })
    }
  })

  test('ships its own job once, with exactly one customer mail carrying the tracking link', async () => {
    const orderId = await processedOrder([mug(), beaker()])
    const headers = await asSupplier()
    const job = await jobOf(orderId, supplierA)
    const path = `/api/supplier/production-jobs/${String(job.id)}/ship`
    const shipped = await post(path, headers, { carrier: 'DHL', trackingNumber: '0034 0434' })
    expect(shipped.status).toBe(200)
    expect(await jsonOf(shipped)).toMatchObject({
      jobId: job.id,
      shippingCarrier: 'DHL',
      trackingNumber: '0034 0434',
    })
    expect(await jobOf(orderId, supplierA)).toMatchObject({
      shipped_by_user_id: loginA,
      shipped_by_channel: null,
    })

    const again = await post(path, headers)
    expect(again.status).toBe(409)
    expect(await jsonOf(again)).toMatchObject({ code: 'ALREADY_SHIPPED' })
    const foreign = await jobOf(orderId, supplierB)
    expect(
      (await post(`/api/supplier/production-jobs/${String(foreign.id)}/ship`, headers)).status,
    ).toBe(404)

    const mails = await mailsOf('SHIPPING_NOTIFICATION')
    expect(mails.map((mail) => mail.recipient)).toEqual(['erika@example.com'])
    expect(mails[0]?.text).toContain(
      'https://www.dhl.de/de/privatkunden/pakete-empfangen/verfolgen.html?piececode=0034%200434',
    )
    expect(mails[0]?.text).toContain('1x Zaubertasse (Weiß)')
    const shippedList = await jsonOf(
      await get('/api/supplier/production-jobs?status=SHIPPED', headers),
    )
    expect(shippedList).toMatchObject([{ jobId: job.id, shippingCarrier: 'DHL' }])
  })

  test('two concurrent ships end as one shipment, one conflict, and one mail', async () => {
    const orderId = await processedOrder([mug()])
    const headers = await asSupplier()
    const path = `/api/supplier/production-jobs/${String((await jobOf(orderId, supplierA)).id)}/ship`
    const answers = await Promise.all([post(path, headers), post(path, headers)])
    expect(answers.map((answer) => answer.status).sort()).toEqual([200, 409])
    expect((await mailsOf('SHIPPING_NOTIFICATION')).length).toBe(1)
  })
})

describe('the admin surface', () => {
  test('lists every supplier with the generation state, filters by supplier, and batches the headers', async () => {
    const orderId = await processedOrder([mug(), beaker(), shirt()])
    const headers = await asAdmin()
    const before = kit.orders.calls.fulfillmentOrders
    const all = (await jsonOf(await get('/api/admin/production/jobs', headers))) as Record<
      string,
      unknown
    >[]
    expect(kit.orders.calls.fulfillmentOrders).toBe(before + 1)
    expect(
      all.map((job) => [
        job.supplier,
        job.fulfillmentChannel,
        job.pdfAvailable,
        job.externalReference,
        job.remoteState,
      ]),
    ).toEqual([
      [{ id: supplierA, name: 'Tassenwerk' }, 'SFTP', true, null, null],
      [{ id: supplierB, name: 'Becherhaus' }, 'SFTP', true, null, null],
      [{ id: spodSupplier, name: 'Spreadconnect' }, 'SPOD', false, 'spod-1', 'CONFIRMED'],
    ])
    expect(all[0]).toMatchObject({
      orderId,
      generationAttemptCount: 1,
      lastGenerationErrorCode: null,
    })
    const filtered = await jsonOf(
      await get(`/api/admin/production/jobs?supplierId=${String(supplierB)}`, headers),
    )
    expect(filtered).toMatchObject([{ supplier: { id: supplierB } }])
    const invalid = await get('/api/admin/production/jobs?supplierId=x', headers)
    expect(invalid.status).toBe(400)
    expect(await jsonOf(invalid)).toEqual({
      message: 'Invalid supplier id',
      errors: {},
      code: 'INVALID_SUPPLIER_ID',
    })
    expect((await get('/api/admin/production/jobs?status=NOPE', headers)).status).toBe(400)
  })

  test('the download states of a job: generated, not yet, gone, tampered, and none for SPOD', async () => {
    const orderId = await processedOrder([
      mug({ imagePath: join(kit.root, 'later.png') }),
      beaker(),
      shirt(),
    ])
    const headers = await asAdmin()
    const pdf = (jobId: number) => get(`/api/admin/production/jobs/${String(jobId)}/pdf`, headers)
    const codeOf = async (jobId: number) =>
      ((await jsonOf(await pdf(jobId))) as { code?: string }).code

    const pending = await jobOf(orderId, supplierA)
    expect((await pdf(pending.id)).status).toBe(409)
    expect(await codeOf(pending.id)).toBe('ARTIFACT_NOT_GENERATED')

    const generated = await jobOf(orderId, supplierB)
    expect((await pdf(generated.id)).status).toBe(200)
    const file = join(kit.root, 'artifacts', String(generated.id), `ORD-${String(orderId)}.pdf`)
    await writeFile(file, 'tampered')
    expect(await codeOf(generated.id)).toBe('ARTIFACT_DIGEST_MISMATCH')
    await rm(file)
    expect(await codeOf(generated.id)).toBe('ARTIFACT_MISSING')

    expect((await pdf((await jobOf(orderId, spodSupplier)).id)).status).toBe(404)
  })

  test('ships on a supplier’s behalf through the same path; an unprepared job is not ready', async () => {
    const orderId = await processedOrder([mug({ imagePath: null }), beaker()])
    const headers = await asAdmin()
    const unprepared = await jobOf(orderId, supplierA)
    const refused = await post(`/api/admin/production/jobs/${String(unprepared.id)}/ship`, headers)
    expect(refused.status).toBe(409)
    expect(await jsonOf(refused)).toMatchObject({ code: 'NOT_READY' })

    const job = await jobOf(orderId, supplierB)
    const shipped = await post(`/api/admin/production/jobs/${String(job.id)}/ship`, headers, {
      carrier: 'OTHER',
      trackingNumber: 'X1',
    })
    expect(shipped.status).toBe(200)
    expect(await jsonOf(shipped)).toMatchObject({
      supplier: { id: supplierB },
      shippedByUserId: adminId,
      shippingCarrier: 'OTHER',
    })
    const mail = (await mailsOf('SHIPPING_NOTIFICATION'))[0]
    expect(mail?.text).toContain('Sendungsnummer: X1')
    expect(mail?.text).not.toContain('Sendung verfolgen')
  })
})

describe('the SPOD webhook', () => {
  const shipment = (orderId: string, extra: Record<string, unknown> = {}) => ({
    eventType: 'Shipment.sent',
    shipment: {
      orderId,
      carrier: 'deutsche post',
      trackingCode: 'TRACK-1',
      trackingUrl: 'https://evil.test',
      ...extra,
    },
  })

  // With a Content-Length the platform's body limit only reads the header; a chunked body is
  // buffered by that middleware before any route runs, which is outside this module.
  test('a wrong secret is refused before the body is read', async () => {
    let pulls = 0
    const body = new ReadableStream<Uint8Array>(
      {
        pull: (controller) => {
          pulls++
          controller.enqueue(new TextEncoder().encode('{}'))
          controller.close()
        },
      },
      { highWaterMark: 0 },
    )
    const answer = await app.request('/api/production/webhooks/spod/wrong-secret', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': '2' },
      body,
    })
    expect(answer.status).toBe(403)
    expect(await jsonOf(answer)).toEqual({ message: 'Forbidden', errors: {} })
    expect(pulls).toBe(0)
  })

  test('a reported shipment ships the job once, by channel, and mails the customer once', async () => {
    const orderId = await processedOrder([shirt()])
    for (let delivery = 0; delivery < 2; delivery++) {
      const answer = await webhook(shipment('spod-1'))
      expect(answer.status).toBe(202)
      expect(await answer.text()).toBe('[accepted]')
    }
    expect(await jobOf(orderId, spodSupplier)).toMatchObject({
      shipped_by_user_id: null,
      shipped_by_channel: 'SPOD',
      shipping_carrier: 'DEUTSCHE_POST',
      shipping_carrier_reported: 'deutsche post',
      tracking_number: 'TRACK-1',
    })
    const mails = await mailsOf('SHIPPING_NOTIFICATION')
    expect(mails.length).toBe(1)
    expect(mails[0]?.text).not.toContain('evil.test')
  })

  test('an unprepared job is found by the shop reference, shipped, and marked prepared', async () => {
    const orderId = await processedOrder([shirt(null)])
    const job = await jobOf(orderId, spodSupplier)
    expect(job.prepared_at).toBeNull()
    const human = await post(`/api/admin/production/jobs/${String(job.id)}/ship`, await asAdmin())
    expect(await jsonOf(human)).toMatchObject({ code: 'NOT_READY' })

    const reference = `ORD-${String(orderId)}-JOB-${String(job.id)}`
    const mismatch = `ORD-${String(orderId + 1)}-JOB-${String(job.id)}`
    expect(
      (await webhook({ eventType: 'Shipment.sent', externalOrderReference: mismatch })).status,
    ).toBe(202)
    expect((await jobOf(orderId, spodSupplier)).shipped_by_channel).toBeNull()
    await webhook({ eventType: 'Shipment.sent', shipment: { externalOrderReference: reference } })
    expect(await jobOf(orderId, spodSupplier)).toMatchObject({
      shipped_by_channel: 'SPOD',
      shipping_carrier: 'OTHER',
    })
    expect((await jobOf(orderId, spodSupplier)).prepared_at).not.toBeNull()
  })

  test('remote states alert once per transition; no-ops are accepted all the same', async () => {
    await processedOrder([shirt()])
    const cancelled = { eventType: 'Order.cancelled', order: { orderId: 'spod-1' } }
    await webhook(cancelled)
    await webhook(cancelled)
    expect((await mailsOf('SPOD_OPS_ALERT')).map((mail) => mail.recipient)).toEqual([ALERT_EMAIL])
    await webhook({ eventType: 'Order.needs-action', order: { orderId: 'spod-1' } })
    const alerts = await mailsOf('SPOD_OPS_ALERT')
    expect(alerts.length).toBe(2)
    expect(alerts[0]?.text).toContain('storniert')
    expect(alerts[1]?.text).toContain('Rückmeldung')

    for (const body of [
      'not json',
      { eventType: 'Order.created', order: { orderId: 'spod-1' } },
      shipment('unknown'),
      { eventType: 'Shipment.sent' },
    ]) {
      const answer = await webhook(body)
      expect([answer.status, await answer.text()]).toEqual([202, '[accepted]'])
    }
  })

  test('a deployment without SPOD settings has no webhook route', async () => {
    await database.db.deleteFrom('production_destinations').execute()
    const plain = await productionTestKit(database.db, { spodSettings: null })
    const answer = await testApp(plain.module.routes).request(
      `/api/production/webhooks/spod/${WEBHOOK_SECRET}`,
      { method: 'POST' },
    )
    expect(answer.status).toBe(404)
  })
})

describe('the carrier list', () => {
  test('builds encoded links for every carrier but OTHER and maps reported names', () => {
    expect(SHIPPING_CARRIERS).toEqual([
      'DHL',
      'DPD',
      'GLS',
      'HERMES',
      'UPS',
      'DEUTSCHE_POST',
      'OTHER',
    ])
    expect(trackingUrl('DPD', 'a b&c')).toBe(
      'https://tracking.dpd.de/status/de_DE/parcel/a%20b%26c',
    )
    expect(trackingUrl('HERMES', "x'y")).toBe(
      'https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#x%27y',
    )
    expect(trackingUrl('OTHER', '123')).toBeNull()
    expect(trackingUrl('DHL', ' ')).toBeNull()
    expect(
      ['DHL', 'Deutsche Post', 'deutsche_post', ' ups ', 'FedEx', '', null].map(
        carrierOfReportedName,
      ),
    ).toEqual(['DHL', 'DEUTSCHE_POST', 'DEUTSCHE_POST', 'UPS', 'OTHER', 'OTHER', 'OTHER'])
  })
})
