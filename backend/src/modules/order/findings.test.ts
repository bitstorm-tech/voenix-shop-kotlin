import { beforeEach, describe, expect, test } from 'bun:test'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import type { PaymentStatus } from './payment/mollie'
import type { OrderStatus } from './schema'
import { orderModuleFor, placeInput, resetOrders, seedCart } from './test-fixtures'

const database = testDatabase()

const NOW = new Date('2026-10-16T12:00:00.000Z')
const minutesAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000)

/** A placed order moved to `status`; every order gets a cart of its own. */
async function seedOrder(status: OrderStatus): Promise<number> {
  const module = await orderModuleFor(database.db)
  const cartId = await seedCart(database.db)
  const result = await write(database.db, (trx) => module.orders.place(trx, placeInput(cartId)))
  if (result.kind !== 'placed') throw new Error('The test order was not placed')
  const { orderId } = result.order
  await database.db.updateTable('orders').set({ status }).where('id', '=', orderId).execute()
  return orderId
}

let molliePaymentIds = 0

async function seedPayment(
  orderId: number,
  status: PaymentStatus,
  createdAt: Date,
  updatedAt: Date = createdAt,
): Promise<number> {
  const { id } = await database.db
    .insertInto('payments')
    .values({
      order_id: orderId,
      mollie_payment_id: `tr_${String(++molliePaymentIds)}`,
      status,
      amount_cents: 4470,
      checkout_url: 'https://mollie.test/checkout',
      created_at: createdAt,
      updated_at: updatedAt,
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  return id
}

beforeEach(() => resetOrders(database.db))

describe('orders.findings', () => {
  test('reports a payment under way for more than an hour on a pending order, and nothing younger', async () => {
    const open = await seedOrder('PENDING')
    const openPayment = await seedPayment(open, 'OPEN', minutesAgo(61))
    const authorized = await seedOrder('PENDING')
    const authorizedPayment = await seedPayment(authorized, 'AUTHORIZED', minutesAgo(600))
    // Below and exactly at the threshold.
    await seedPayment(await seedOrder('PENDING'), 'PENDING', minutesAgo(59))
    await seedPayment(await seedOrder('PENDING'), 'OPEN', minutesAgo(60))
    // An old payment under way on a cancelled order is no finding of this kind.
    await seedPayment(await seedOrder('CANCELLED'), 'OPEN', minutesAgo(120))
    // An abandoned checkout: the only payment ended.
    await seedPayment(await seedOrder('PENDING'), 'EXPIRED', minutesAgo(120))
    // The current payment is the young live one, not the old ended one.
    const retried = await seedOrder('PENDING')
    await seedPayment(retried, 'CANCELED', minutesAgo(300))
    await seedPayment(retried, 'OPEN', minutesAgo(5))
    // A paid order with its paid payment.
    await seedPayment(await seedOrder('PAID'), 'PAID', minutesAgo(300))

    const { orders } = await orderModuleFor(database.db)
    expect(await orders.findings(NOW)).toEqual([
      {
        kind: 'PAYMENT_UNRESOLVED',
        subject: { type: 'PAYMENT', id: openPayment },
        orderId: open,
        since: minutesAgo(61),
        attemptCount: null,
        lastErrorCode: null,
      },
      {
        kind: 'PAYMENT_UNRESOLVED',
        subject: { type: 'PAYMENT', id: authorizedPayment },
        orderId: authorized,
        since: minutesAgo(600),
        attemptCount: null,
        lastErrorCode: null,
      },
    ])
  })

  test('reports a paid payment whose order is not paid, at once and since its last update', async () => {
    const pending = await seedOrder('PENDING')
    const mismatch = await seedPayment(pending, 'PAID', minutesAgo(10), minutesAgo(1))
    const cancelled = await seedOrder('CANCELLED')
    const onCancelled = await seedPayment(cancelled, 'PAID', minutesAgo(30), minutesAgo(20))
    await seedPayment(await seedOrder('PAID'), 'PAID', minutesAgo(30))

    const { orders } = await orderModuleFor(database.db)
    expect(await orders.findings(NOW)).toEqual([
      {
        kind: 'PAYMENT_PAID_ORDER_UNPAID',
        subject: { type: 'PAYMENT', id: mismatch },
        orderId: pending,
        since: minutesAgo(1),
        attemptCount: null,
        lastErrorCode: null,
      },
      {
        kind: 'PAYMENT_PAID_ORDER_UNPAID',
        subject: { type: 'PAYMENT', id: onCancelled },
        orderId: cancelled,
        since: minutesAgo(20),
        attemptCount: null,
        lastErrorCode: null,
      },
    ])
  })
})
