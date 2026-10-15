import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import { count, testDatabase } from '@test-support/database'
import { rejectionOf } from '@test-support/values'
import {
  orderModuleFor,
  placeInput,
  reserve,
  resetOrders,
  seedCart,
  seedPromotion,
} from './test-fixtures'

const database = testDatabase()

/** A placed order with a reserved coupon, as checkout TX1 leaves it. */
async function placedWithCoupon(usageLimitTotal: number | null = null) {
  const module = await orderModuleFor(database.db)
  const cartId = await seedCart(database.db)
  const promotionId = await seedPromotion(database.db, usageLimitTotal)
  await reserve(database.db, promotionId, cartId)
  await write(database.db, (trx) =>
    module.orders.place(trx, placeInput(cartId, { promotionId, discountCents: 398 })),
  )
  return module.orders
}

const statusOf = async (orderId: number) =>
  (
    await database.db
      .selectFrom('orders')
      .select('status')
      .where('id', '=', orderId)
      .executeTakeFirstOrThrow()
  ).status

beforeEach(() => resetOrders(database.db))

describe('the paid transition (orders.confirmFree, the webhook)', () => {
  test('pays, redeems the coupon, and requests production in one commit, exactly once', async () => {
    const orders = await placedWithCoupon()

    expect(await write(database.db, (trx) => orders.confirmFree(trx, 1))).toBe('paid')
    expect(await write(database.db, (trx) => orders.confirmFree(trx, 1))).toBe('alreadyPaid')

    expect(await statusOf(1)).toBe('PAID')
    expect(await count(database.db, 'promotion_redemptions')).toBe(1)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
    expect(await count(database.db, 'production_requests')).toBe(1)
    expect(await count(database.db, 'email_jobs')).toBe(1) // the placement's, no second mail
  })

  test('two concurrent confirmations queue on the order lock and pay once', async () => {
    const orders = await placedWithCoupon()
    const results = await Promise.all([
      write(database.db, (trx) => orders.confirmFree(trx, 1)),
      write(database.db, (trx) => orders.confirmFree(trx, 1)),
    ])
    expect(results.sort()).toEqual(['alreadyPaid', 'paid'])
    expect(await count(database.db, 'promotion_redemptions')).toBe(1)
    expect(await count(database.db, 'production_requests')).toBe(1)
  })

  test('a production request that fails rolls the payment and the redemption back', async () => {
    const orders = await placedWithCoupon()
    await sql`CREATE FUNCTION refuse_request() RETURNS trigger LANGUAGE plpgsql AS
      $$ BEGIN RAISE EXCEPTION 'production refused'; END $$`.execute(database.db)
    await sql`CREATE TRIGGER refuse_request BEFORE INSERT ON production_requests
      FOR EACH ROW EXECUTE FUNCTION refuse_request()`.execute(database.db)
    try {
      expect(await rejectionOf(write(database.db, (trx) => orders.confirmFree(trx, 1)))).toBe(
        'production refused',
      )
    } finally {
      await sql`DROP TRIGGER refuse_request ON production_requests`.execute(database.db)
      await sql`DROP FUNCTION refuse_request()`.execute(database.db)
    }
    expect(await statusOf(1)).toBe('PENDING')
    expect(await count(database.db, 'promotion_redemptions')).toBe(0)
    expect(await count(database.db, 'promotion_reservations')).toBe(1)
  })

  test('an exhausted coupon still pays the order, without a redemption', async () => {
    const { orders } = await orderModuleFor(database.db)
    const promotionId = await seedPromotion(database.db, 1)
    for (const guestToken of ['guest-a', 'guest-b']) {
      const cartId = await seedCart(database.db, { guestToken })
      await write(database.db, (trx) =>
        orders.place(trx, placeInput(cartId, { guestToken, promotionId, discountCents: 398 })),
      )
    }

    expect(await write(database.db, (trx) => orders.confirmFree(trx, 2))).toBe('paid')
    expect(await write(database.db, (trx) => orders.confirmFree(trx, 1))).toBe('paid')

    expect(await statusOf(1)).toBe('PAID')
    const redemptions = await database.db
      .selectFrom('promotion_redemptions')
      .select('order_id')
      .execute()
    expect(redemptions).toEqual([{ order_id: 2 }])
    expect(await count(database.db, 'production_requests')).toBe(2)
  })

  test('a cancelled order is never paid, and an unknown one is reported', async () => {
    const orders = await placedWithCoupon()
    await orders.cancel(1)
    expect(await write(database.db, (trx) => orders.confirmFree(trx, 1))).toBe('cancelled')
    expect(await write(database.db, (trx) => orders.confirmFree(trx, 99))).toBe('notFound')
    expect(await statusOf(1)).toBe('CANCELLED')
    expect(await count(database.db, 'production_requests')).toBe(0)
  })
})

describe('orders.cancel', () => {
  test('cancels a pending order and releases its reservation in the same commit', async () => {
    const orders = await placedWithCoupon()
    expect(await orders.cancel(1)).toBe('cancelled')
    expect(await orders.cancel(1)).toBe('alreadyCancelled')
    expect(await orders.cancel(99)).toBe('notFound')
    expect(await statusOf(1)).toBe('CANCELLED')
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
  })

  test('refuses a paid order', async () => {
    const orders = await placedWithCoupon()
    await write(database.db, (trx) => orders.confirmFree(trx, 1))
    expect(await orders.cancel(1)).toBe('paid')
    expect(await statusOf(1)).toBe('PAID')
  })
})
