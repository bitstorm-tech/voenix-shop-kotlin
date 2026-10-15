import { beforeEach, describe, expect, test } from 'bun:test'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import { jsonOf, testApp } from '@test-support/http'
import {
  count,
  guestCookie,
  orderModuleFor,
  placeInput,
  reserve,
  resetOrders,
  seedCart,
  seedPromotion,
  stubMollie,
  WEBHOOK_SECRET,
} from '../test-fixtures'

const database = testDatabase()

/** The module on a fresh Mollie stub, with one placed order of 44.70 € and a reserved coupon. */
async function placed() {
  const stub = stubMollie()
  const module = await orderModuleFor(database.db, stub.mollie)
  const cartId = await seedCart(database.db)
  const promotionId = await seedPromotion(database.db)
  await reserve(database.db, promotionId, cartId)
  const result = await write(database.db, (trx) =>
    module.orders.place(trx, placeInput(cartId, { promotionId, discountCents: 398 })),
  )
  if (result.kind !== 'placed') throw new Error('The test order was not placed')
  return { ...stub, module, order: result.order, app: testApp(module.routes) }
}

const payments = () =>
  database.db
    .selectFrom('payments')
    .select(['order_id', 'mollie_payment_id', 'status', 'amount_cents', 'checkout_url'])
    .orderBy('id')
    .execute()

const orderStatus = async () =>
  (await database.db.selectFrom('orders').select('status').executeTakeFirstOrThrow()).status

const webhook = (app: ReturnType<typeof testApp>, id: string | null, secret = WEBHOOK_SECRET) =>
  app.request(`/api/payments/webhook/${secret}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: id === null ? '' : new URLSearchParams({ id }).toString(),
  })

beforeEach(() => resetOrders(database.db))

describe('orders.startPayment', () => {
  test('creates and stores one payment, and a repeat answers it without a provider call', async () => {
    const { module, order, calls } = await placed()
    const url = await module.orders.startPayment(order)
    expect(url).toBe('https://mollie.test/checkout/tr_1_1')
    expect(await module.orders.startPayment(order)).toBe(url)
    expect(calls.create).toBe(1)
    expect(await payments()).toEqual([
      {
        order_id: 1,
        mollie_payment_id: 'tr_1_1',
        status: 'OPEN',
        amount_cents: 4072,
        checkout_url: 'https://mollie.test/checkout/tr_1_1',
      },
    ])
  })

  test('concurrent starts of one order queue on the order lock: one payment, one URL', async () => {
    const { module, order, calls } = await placed()
    const urls = await Promise.all([
      module.orders.startPayment(order),
      module.orders.startPayment(order),
      module.orders.startPayment(order),
    ])
    expect(new Set(urls).size).toBe(1)
    expect(calls.create).toBe(1)
    expect(calls.cancel).toEqual([])
    expect(await count(database.db, 'payments')).toBe(1)
  })

  test('a terminal payment frees the slot for a second one', async () => {
    const { module, order, report, app } = await placed()
    await module.orders.startPayment(order)
    report('tr_1_1', { status: 'EXPIRED' })
    expect((await webhook(app, 'tr_1_1')).status).toBe(200)
    expect(await module.orders.startPayment(order)).toBe('https://mollie.test/checkout/tr_1_2')
    expect((await payments()).map((payment) => payment.status)).toEqual(['EXPIRED', 'OPEN'])
  })

  test('a provider refusal cancels the order and releases its reservation', async () => {
    const { module, order, state } = await placed()
    state.refuse = true
    expect(await module.orders.startPayment(order)).toBeNull()
    expect(await orderStatus()).toBe('CANCELLED')
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
    expect(await count(database.db, 'payments')).toBe(0)
  })

  test('an order that is no longer pending gets no payment', async () => {
    const { module, order, calls } = await placed()
    await write(database.db, (trx) => module.orders.confirmFree(trx, order.orderId))
    expect(await module.orders.startPayment(order)).toBeNull()
    expect(calls.create).toBe(0)
  })
})

describe('POST /api/payments/webhook/{secret}', () => {
  test('a paid payment pays the order once, however often Mollie delivers it', async () => {
    const { module, order, report, app } = await placed()
    await module.orders.startPayment(order)
    report('tr_1_1', { status: 'PAID' })

    const deliveries = await Promise.all([webhook(app, 'tr_1_1'), webhook(app, 'tr_1_1')])
    const again = await webhook(app, 'tr_1_1')

    expect(deliveries.map((response) => response.status)).toEqual([200, 200])
    expect(again.status).toBe(200)
    expect(await again.text()).toBe('')
    expect(await orderStatus()).toBe('PAID')
    expect((await payments())[0]?.status).toBe('PAID')
    expect(await count(database.db, 'promotion_redemptions')).toBe(1)
    expect(await count(database.db, 'production_requests')).toBe(1)
  })

  test('a paid amount other than the one asked for does not pay the order', async () => {
    const { module, order, report, app } = await placed()
    await module.orders.startPayment(order)
    report('tr_1_1', { status: 'PAID', amountCents: 100 })
    expect((await webhook(app, 'tr_1_1')).status).toBe(200)
    expect(await orderStatus()).toBe('PENDING')
    expect(await count(database.db, 'production_requests')).toBe(0)
  })

  test('a failed payment keeps the order pending and releases the reservation', async () => {
    const { module, order, report, app } = await placed()
    await module.orders.startPayment(order)
    report('tr_1_1', { status: 'FAILED' })
    expect((await webhook(app, 'tr_1_1')).status).toBe(200)
    expect(await orderStatus()).toBe('PENDING')
    expect((await payments())[0]?.status).toBe('FAILED')
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
  })

  test('the documented refusals: wrong secret, missing id, provider silent, unknown payment', async () => {
    const { app, calls, report, module, order } = await placed()
    const forbidden = await webhook(app, 'tr_1_1', 'wrong-secret-0123456789')
    expect(forbidden.status).toBe(403)
    expect(await jsonOf(forbidden)).toEqual({ message: 'Forbidden', errors: {} })
    expect(calls.find).toBe(0)

    const missing = await webhook(app, null)
    expect(missing.status).toBe(400)
    expect(await jsonOf(missing)).toEqual({ message: 'Missing payment id', errors: {} })

    expect((await webhook(app, 'tr_unknown_to_mollie')).status).toBe(502)

    await module.orders.startPayment(order)
    await database.db.deleteFrom('payments').execute()
    report('tr_1_1', { status: 'PAID' })
    expect((await webhook(app, 'tr_1_1')).status).toBe(200)
    expect(await orderStatus()).toBe('PENDING')
  })
})

describe('the payment status of an order answer', () => {
  test('the detail read refreshes a running payment and repairs a missed webhook', async () => {
    const { module, order, report, app, calls } = await placed()
    await module.orders.startPayment(order)
    const headers = { Cookie: await guestCookie('guest-a') }

    const history = await app.request('/api/orders', { headers })
    expect(((await jsonOf(history)) as { paymentStatus: string }[])[0]?.paymentStatus).toBe('OPEN')

    report('tr_1_1', { status: 'PAID' })
    expect(calls.find).toBe(0)
    const listed = await app.request('/api/orders', { headers })
    expect(((await jsonOf(listed)) as { paymentStatus: string }[])[0]?.paymentStatus).toBe('OPEN')
    expect(calls.find).toBe(0)

    const detail = (await jsonOf(await app.request('/api/orders/1', { headers }))) as {
      status: string
      paymentStatus: string
    }
    expect(detail).toMatchObject({ status: 'PAID', paymentStatus: 'PAID' })
    expect(calls.find).toBe(1)
    expect(await count(database.db, 'production_requests')).toBe(1)

    await app.request('/api/orders/1', { headers })
    expect(calls.find).toBe(1)
  })

  test('a provider that cannot answer leaves the stored status', async () => {
    const { module, order, app, calls } = await placed()
    await module.orders.startPayment(order)
    await database.db.updateTable('payments').set({ mollie_payment_id: 'tr_gone' }).execute()
    const detail = (await jsonOf(
      await app.request('/api/orders/1', { headers: { Cookie: await guestCookie('guest-a') } }),
    )) as { status: string; paymentStatus: string }
    expect(detail).toMatchObject({ status: 'PENDING', paymentStatus: 'OPEN' })
    expect(calls.find).toBe(1)
  })
})
