import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import { stubMollie } from '@/test-fixtures'
import { count, testDatabase } from '@test-support/database'
import { guestCookie, jsonOf, testApp } from '@test-support/http'
import {
  orderModuleFor,
  placedOrder,
  placeInput,
  reserve,
  resetOrders,
  seedCart,
  seedPromotion,
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

  test('a refusal cancels the order under the lock a concurrent start waits on: no payment for a cancelled order', async () => {
    const { module, order, calls, mollie } = await placed()
    // Mollie refuses the first start and would accept the second. The first holds the order lock
    // through the refusal and the cancellation, so the second finds the order CANCELLED and starts
    // nothing; were the cancellation a transaction of its own, the second would create a live
    // payment and the first would then cancel the order the customer is about to pay.
    const create = mollie.create.bind(mollie)
    mollie.create = (payable, key) =>
      calls.create === 0 ? create(payable, key).then(() => null) : create(payable, key)

    const urls = await Promise.all([
      module.orders.startPayment(order),
      module.orders.startPayment(order),
    ])

    expect(urls).toEqual([null, null])
    expect(calls.create).toBe(1)
    expect(await orderStatus()).toBe('CANCELLED')
    expect(await count(database.db, 'payments')).toBe(0)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
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

  test('PAID and the paid transition commit together: a failed transition leaves the payment under way', async () => {
    const { module, order, report, app } = await placed()
    await module.orders.startPayment(order)
    report('tr_1_1', { status: 'PAID' })
    await sql`CREATE FUNCTION refuse_request() RETURNS trigger LANGUAGE plpgsql AS
      $$ BEGIN RAISE EXCEPTION 'production refused'; END $$`.execute(database.db)
    await sql`CREATE TRIGGER refuse_request BEFORE INSERT ON production_requests
      FOR EACH ROW EXECUTE FUNCTION refuse_request()`.execute(database.db)
    try {
      expect((await webhook(app, 'tr_1_1')).status).toBe(500)
    } finally {
      await sql`DROP TRIGGER refuse_request ON production_requests`.execute(database.db)
      await sql`DROP FUNCTION refuse_request()`.execute(database.db)
    }
    // Neither the payment nor the order moved, so the redelivery is not skipped as already PAID.
    expect((await payments())[0]?.status).toBe('OPEN')
    expect(await orderStatus()).toBe('PENDING')
    expect(await count(database.db, 'promotion_redemptions')).toBe(0)

    expect((await webhook(app, 'tr_1_1')).status).toBe(200)
    expect((await payments())[0]?.status).toBe('PAID')
    expect(await orderStatus()).toBe('PAID')
    expect(await count(database.db, 'production_requests')).toBe(1)
  })

  test('a stale answer never takes a paid payment back: OPEN after PAID leaves PAID', async () => {
    const { module, order, report, app } = await placed()
    await module.orders.startPayment(order)
    report('tr_1_1', { status: 'PAID' })
    expect((await webhook(app, 'tr_1_1')).status).toBe(200)

    report('tr_1_1', { status: 'OPEN' })
    expect((await webhook(app, 'tr_1_1')).status).toBe(200)

    expect((await payments())[0]?.status).toBe('PAID')
    expect(await orderStatus()).toBe('PAID')
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

describe('orders.reconcilePayments', () => {
  const NOW = new Date('2026-10-16T12:00:00.000Z')
  const minutesAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000)

  /**
   * `ages.length` orders with one started payment each (`tr_1_1`, `tr_2_2`, ...), the first with
   * the reserved coupon of `placed`, payment `n` created `ages[n - 1]` minutes before `NOW`.
   * `asked` lists the Mollie ids in the order the stub was asked about them.
   */
  async function started(ages: number[]) {
    const first = await placed()
    const orders = [first.order]
    while (orders.length < ages.length) {
      const cartId = await seedCart(database.db)
      orders.push(
        placedOrder(
          await write(database.db, (trx) => first.module.orders.place(trx, placeInput(cartId))),
        ),
      )
    }
    for (const [index, order] of orders.entries()) {
      await first.module.orders.startPayment(order)
      await database.db
        .updateTable('payments')
        .set({ created_at: minutesAgo(ages[index] ?? 0) })
        .where('order_id', '=', order.orderId)
        .execute()
    }
    const asked: string[] = []
    const find = first.mollie.find.bind(first.mollie)
    first.mollie.find = (id) => {
      asked.push(id)
      return find(id)
    }
    return { ...first, asked }
  }

  const statuses = async () =>
    database.db
      .selectFrom('orders')
      .innerJoin('payments', 'payments.order_id', 'orders.id')
      .select(['orders.status as order', 'payments.status as payment'])
      .orderBy('orders.id')
      .execute()

  test('asks Mollie once about every payment under way for over 15 minutes, in id order', async () => {
    const { module, asked } = await started([16, 15, 600, 120, 14])
    // Payment 3 is authorized and older than payment 1; payment 4 has ended.
    await database.db
      .updateTable('payments')
      .set({ status: 'AUTHORIZED' })
      .where('id', '=', 3)
      .execute()
    await database.db
      .updateTable('payments')
      .set({ status: 'EXPIRED' })
      .where('id', '=', 4)
      .execute()

    await module.orders.reconcilePayments(NOW)
    expect(asked).toEqual(['tr_1_1', 'tr_3_3'])

    await module.orders.reconcilePayments(NOW)
    expect(asked).toEqual(['tr_1_1', 'tr_3_3', 'tr_1_1', 'tr_3_3'])
  })

  test('a paid answer pays the order the way the webhook does', async () => {
    const { module, report } = await started([30])
    report('tr_1_1', { status: 'PAID' })

    await module.orders.reconcilePayments(NOW)

    expect(await statuses()).toEqual([{ order: 'PAID', payment: 'PAID' }])
    expect(await count(database.db, 'production_requests')).toBe(1)
    expect(await count(database.db, 'promotion_redemptions')).toBe(1)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
  })

  test.each(['EXPIRED', 'FAILED', 'CANCELED'] as const)(
    '%s ends the payment, releases the reservation, and leaves the order pending',
    async (status) => {
      const { module, report } = await started([30])
      report('tr_1_1', { status })

      await module.orders.reconcilePayments(NOW)

      expect(await statuses()).toEqual([{ order: 'PENDING', payment: status }])
      expect(await count(database.db, 'promotion_reservations')).toBe(0)
      expect(await count(database.db, 'production_requests')).toBe(0)
    },
  )

  test('a payment Mollie cannot answer about does not stop the others', async () => {
    const { module, report, asked } = await started([30, 30, 30])
    report('tr_1_1', { status: 'PAID' })
    report('tr_3_3', { status: 'EXPIRED' })
    await database.db
      .updateTable('payments')
      .set({ mollie_payment_id: 'tr_gone' })
      .where('id', '=', 2)
      .execute()

    await module.orders.reconcilePayments(NOW)

    expect(asked).toEqual(['tr_1_1', 'tr_gone', 'tr_3_3'])
    expect(await statuses()).toEqual([
      { order: 'PAID', payment: 'PAID' },
      { order: 'PENDING', payment: 'OPEN' },
      { order: 'PENDING', payment: 'EXPIRED' },
    ])
  })

  test('a paid transition that fails does not stop the others', async () => {
    const { module, report } = await started([30, 30])
    report('tr_1_1', { status: 'PAID' })
    report('tr_2_2', { status: 'EXPIRED' })
    await sql`CREATE FUNCTION refuse_request() RETURNS trigger LANGUAGE plpgsql AS
      $$ BEGIN RAISE EXCEPTION 'production refused'; END $$`.execute(database.db)
    await sql`CREATE TRIGGER refuse_request BEFORE INSERT ON production_requests
      FOR EACH ROW EXECUTE FUNCTION refuse_request()`.execute(database.db)
    try {
      await module.orders.reconcilePayments(NOW)
    } finally {
      await sql`DROP TRIGGER refuse_request ON production_requests`.execute(database.db)
      await sql`DROP FUNCTION refuse_request()`.execute(database.db)
    }

    expect(await statuses()).toEqual([
      { order: 'PENDING', payment: 'OPEN' },
      { order: 'PENDING', payment: 'EXPIRED' },
    ])
  })
})
