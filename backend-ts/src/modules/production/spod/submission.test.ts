import { beforeEach, describe, expect, test } from 'bun:test'
import type { SpodOrderRequest, SpodResult } from '@/modules/spod'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
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
  writeImage,
} from '../test-fixtures'
import type { SubmissionClient } from './submitter'

const database = testDatabase()

const PRODUCT = { productTypeId: 6, appearanceId: 2, sizeId: 3 }
const SHIRT = { articleId: 10, variantId: 100 }
const failed = (error: 'REFUSED' | 'PROVIDER_UNAVAILABLE', ambiguous: boolean) =>
  ({ kind: 'failed', error, ambiguous }) as const

/**
 * The partner as the submission sees it: every call recorded, every answer scripted. An unscripted
 * call answers the happy path (design `d1`, `d2`, …, order `spod-1`, state `NEW`).
 */
function recordingClient() {
  const calls: string[] = []
  const orders: SpodOrderRequest[] = []
  const script = {
    create: [] as SpodResult<string>[],
    state: [] as SpodResult<string>[],
  }
  let designs = 0
  const client: SubmissionClient = {
    uploadDesign: (_access, fileName) => {
      calls.push(`upload ${fileName}`)
      return Promise.resolve({ kind: 'answered', value: `d${String(++designs)}` })
    },
    createOrder: (_access, request) => {
      calls.push('create')
      orders.push(request)
      return Promise.resolve(script.create.shift() ?? { kind: 'answered', value: 'spod-1' })
    },
    getOrder: (_access, orderId) => {
      calls.push(`get ${orderId}`)
      return Promise.resolve(script.state.shift() ?? { kind: 'answered', value: 'NEW' })
    },
    confirmOrder: (_access, orderId) => {
      calls.push(`confirm ${orderId}`)
      return Promise.resolve({ kind: 'answered', value: undefined })
    },
  }
  return { client, calls, orders, script }
}

let partner: ReturnType<typeof recordingClient>
let kit: Awaited<ReturnType<typeof productionTestKit>>
let supplierId = 0
let image = ''

beforeEach(async () => {
  await resetProduction(database.db)
  partner = recordingClient()
  kit = await productionTestKit(database.db, { client: partner.client })
  image = await writeImage(kit.root, 'print.webp', 'webp')
  supplierId = await seedSupplier(database.db, 'Spreadconnect')
  await seedSpodDestination(database.db, supplierId)
  kit.catalog.set(SHIRT, { supplierId, variantName: 'Black / M', spodProduct: PRODUCT })
})

const shirt = (overrides: Partial<ProductionLine> = {}) =>
  line({
    ...SHIRT,
    articleName: 'T-Shirt',
    variantName: 'Black / M',
    imagePath: image,
    spodHotspot: 'MEDIUM_FRONT',
    supplierArticleNumber: null,
    ...overrides,
  })

/** A paid order requested for production; answers its order id. */
async function order(lines: ProductionLine[], phone: string | null = '+49 30 123456') {
  const orderId = await seedOrder(database.db)
  kit.orders.add({ ...productionData(orderId, lines), customerPhone: phone })
  await write(database.db, (trx) => requestProduction(trx, orderId))
  return orderId
}

const spodState = () =>
  database.db
    .selectFrom('production_spod_orders as o')
    .innerJoin('production_jobs as j', 'j.id', 'o.production_job_id')
    .select([
      'j.id as jobId',
      'o.external_reference as externalReference',
      'o.create_state as createState',
      'o.create_ambiguous_count as ambiguous',
      'o.last_error_code as code',
      'o.remote_state as remoteState',
      'j.prepared_at as preparedAt',
    ])
    .orderBy('j.id')
    .execute()

const alerts = async () =>
  (await queuedEmails(database.db)).filter((mail) => mail.kind === 'SPOD_OPS_ALERT')

describe('the SPOD submission', () => {
  test('uploads the design, creates the order NEW with the snapshotted hotspot, and confirms it', async () => {
    const orderId = await order([shirt({ quantity: 2 })])
    await kit.worker.runOnce()
    const [state] = await spodState()
    const reference = `ORD-${String(orderId)}-JOB-${String(state?.jobId)}`
    expect(partner.calls).toEqual([
      `upload ${reference}-1.png`,
      'create',
      'get spod-1',
      'confirm spod-1',
    ])
    expect(partner.orders).toEqual([
      {
        externalOrderReference: reference,
        email: 'erika@example.com',
        phone: '+49 30 123456',
        shipping: {
          address: {
            firstName: 'Erika',
            lastName: 'Mustermann',
            street: 'Heidestraße 17',
            city: 'Köln',
            country: 'Deutschland',
            zipCode: '51147',
          },
        },
        oneTimeItems: [
          {
            productTypeId: 6,
            quantityItems: [{ quantity: 2, sizeId: 3, appearanceId: 2 }],
            configurations: [{ image: { designId: 'd1' }, view: 'FRONT', hotspot: 'MEDIUM_FRONT' }],
            customerPricePerItem: { amount: 19.99, currency: 'EUR' },
          },
        ],
      },
    ])
    expect(state).toMatchObject({
      externalReference: 'spod-1',
      createState: 'CREATED',
      code: null,
      remoteState: 'CONFIRMED',
    })
    expect(state?.preparedAt).not.toBeNull()

    await kit.worker.runOnce()
    expect(partner.calls.length).toBe(4)
    expect(await alerts()).toEqual([])
  })

  test('one image shared by two lines is one design; two hotspots are two order entries', async () => {
    kit.catalog.set(
      { articleId: 10, variantId: 101 },
      {
        supplierId,
        variantName: 'Black / L',
        spodProduct: { ...PRODUCT, sizeId: 4 },
      },
    )
    await order([
      shirt(),
      shirt({ variantId: 101, variantName: 'Black / L', spodHotspot: 'CHEST_LEFT' }),
    ])
    await kit.worker.runOnce()
    expect(partner.calls.filter((call) => call.startsWith('upload')).length).toBe(1)
    expect(partner.orders[0]?.oneTimeItems.map((item) => item.configurations[0]?.hotspot)).toEqual([
      'MEDIUM_FRONT',
      'CHEST_LEFT',
    ])
  })

  test('one product at two prices is two order entries, each priced per piece in euros', async () => {
    kit.catalog.set(
      { articleId: 10, variantId: 101 },
      { supplierId, variantName: 'Black / L', spodProduct: { ...PRODUCT, sizeId: 4 } },
    )
    await order([
      shirt({ quantity: 2, unitPriceCents: 2490 }),
      shirt({ variantId: 101, variantName: 'Black / L', unitPriceCents: 2490 }),
      shirt({ variantId: 101, variantName: 'Black / L', unitPriceCents: 2990 }),
    ])
    await kit.worker.runOnce()
    expect(
      partner.orders[0]?.oneTimeItems.map((item) => ({
        price: item.customerPricePerItem,
        quantities: item.quantityItems.map((quantity) => quantity.quantity),
      })),
    ).toEqual([
      { price: { amount: 24.9, currency: 'EUR' }, quantities: [2, 1] },
      { price: { amount: 29.9, currency: 'EUR' }, quantities: [1] },
    ])
  })

  test('a line without a snapshotted hotspot is held before any partner call, with one alert', async () => {
    const orderId = await order([shirt({ spodHotspot: null })])
    await kit.worker.runOnce()
    await kit.worker.runOnce()
    expect(partner.calls).toEqual([])
    expect(await spodState()).toMatchObject([{ code: 'PLACEMENT_UNAVAILABLE', preparedAt: null }])
    const mails = await alerts()
    expect(mails.map((mail) => mail.recipient)).toEqual([ALERT_EMAIL])
    expect(mails[0]?.text).toContain(`ORD-${String(orderId)}`)
    expect(mails[0]?.text).toContain('Stammdaten korrigieren')
  })

  test('a crash after the id was persisted re-enters at the confirmation without a second order', async () => {
    await order([shirt()])
    partner.script.state.push(failed('PROVIDER_UNAVAILABLE', true), {
      kind: 'answered',
      value: 'CONFIRMED',
    })
    await kit.worker.runOnce()
    expect(await spodState()).toMatchObject([
      { externalReference: 'spod-1', createState: 'CREATED', code: 'PROVIDER_UNAVAILABLE' },
    ])
    await kit.worker.runOnce()
    expect(partner.calls.slice(1)).toEqual(['create', 'get spod-1', 'get spod-1'])
    expect((await spodState())[0]?.preparedAt).not.toBeNull()
  })

  test('the first ambiguous creation re-creates, the second quarantines the job with one alert', async () => {
    await order([shirt()])
    partner.script.create.push(
      failed('PROVIDER_UNAVAILABLE', true),
      failed('PROVIDER_UNAVAILABLE', true),
    )
    await kit.worker.runOnce()
    expect(await spodState()).toMatchObject([{ createState: 'PENDING', ambiguous: 1 }])
    await kit.worker.runOnce()
    expect(await spodState()).toMatchObject([{ createState: 'OUTCOME_UNKNOWN', ambiguous: 2 }])
    await kit.worker.runOnce()
    expect(partner.calls.filter((call) => call.startsWith('upload')).length).toBe(1)
    expect(partner.calls.filter((call) => call === 'create').length).toBe(2)
    expect((await alerts()).map((mail) => mail.subject.startsWith('Aktion nötig'))).toEqual([true])
    expect((await alerts())[0]?.text).toContain('Zweimal in Folge')
  })

  test('a refused creation holds the job with one alert and is never re-sent with another placement', async () => {
    await order([shirt()])
    partner.script.create.push(failed('REFUSED', false), failed('REFUSED', false))
    await kit.worker.runOnce()
    await kit.worker.runOnce()
    expect(partner.calls.filter((call) => call === 'create').length).toBe(2)
    expect(
      partner.orders.map((request) => request.oneTimeItems[0]?.configurations[0]?.hotspot),
    ).toEqual(['MEDIUM_FRONT', 'MEDIUM_FRONT'])
    expect(await spodState()).toMatchObject([
      { code: 'ORDER_CREATE_REJECTED', createState: 'PENDING' },
    ])
    expect((await alerts()).length).toBe(1)
  })

  test('master data problems are bounded codes before any upload; only the permanent ones alert', async () => {
    await order([shirt()], null)
    kit.catalog.set({ articleId: 11, variantId: 110 }, { supplierId, variantName: 'Red / S' })
    await order([shirt({ articleId: 11, variantId: 110, variantName: 'Red / S' })])
    await order([shirt({ imagePath: null })])
    await kit.worker.runOnce()
    expect((await spodState()).map((state) => state.code)).toEqual([
      'PHONE_MISSING',
      'ITEM_WITHOUT_SPOD_PRODUCT',
      'PRINT_IMAGE_MISSING',
    ])
    expect(partner.calls).toEqual([])
    expect((await alerts()).length).toBe(2)
  })

  test('a variant renamed or moved to another supplier after the split refuses the job', async () => {
    await order([shirt(), shirt({ variantId: 101, variantName: 'Black / L' })])
    kit.catalog.set(
      { articleId: 10, variantId: 101 },
      {
        supplierId,
        variantName: 'Black / L',
        spodProduct: PRODUCT,
      },
    )
    kit.catalog.set(SHIRT, { supplierId, variantName: 'Schwarz / M', spodProduct: PRODUCT })
    // The job snapshots the name the order line carries; today's catalog name is another one.
    await kit.worker.runOnce()
    expect((await spodState())[0]?.code).toBe('SPOD_MAPPING_CHANGED')

    const other = await seedSupplier(database.db, 'Andere')
    kit.catalog.set(SHIRT, { supplierId, variantName: 'Black / M', spodProduct: PRODUCT })
    kit.catalog.set(
      { articleId: 10, variantId: 101 },
      {
        supplierId: other,
        variantName: 'Black / L',
        spodProduct: PRODUCT,
      },
    )
    await kit.worker.runOnce()
    expect((await spodState())[0]?.code).toBe('ITEM_SET_CHANGED')
    expect(partner.calls).toEqual([])
  })

  test('a cancelled order is no longer scanned while a needs-action one still is', async () => {
    await order([shirt({ spodHotspot: null })])
    await order([shirt({ spodHotspot: null })])
    await kit.worker.runOnce()
    const [cancelled, flagged] = await spodState()
    await database.db
      .updateTable('production_spod_orders')
      .set({ remote_state: 'CANCELLED' })
      .where('production_job_id', '=', cancelled?.jobId ?? 0)
      .execute()
    await database.db
      .updateTable('production_spod_orders')
      .set({ remote_state: 'NEEDS_ACTION' })
      .where('production_job_id', '=', flagged?.jobId ?? 0)
      .execute()
    const attempts = () =>
      database.db
        .selectFrom('production_spod_orders')
        .select('attempt_count')
        .orderBy('production_job_id')
        .execute()
    await kit.worker.runOnce()
    expect((await attempts()).map((row) => row.attempt_count)).toEqual([1, 2])
  })
})
