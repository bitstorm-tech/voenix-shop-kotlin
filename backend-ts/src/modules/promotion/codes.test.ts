import { afterAll, beforeAll, beforeEach, describe, expect, setSystemTime, test } from 'bun:test'
import { sql } from 'kysely'
import { createPromotionModule, toApiError, type PromotionCodeResult } from '@/modules/promotion'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'

const database = testDatabase()
const codes = () => createPromotionModule({ db: database.db }).promotionCodes

/** Inside the window of every seeded promotion except the expired ones. */
const NOW = '2026-02-01T12:00:00Z'
const USER = 42
const OTHER_USER = 43
const FIRST_CART = 101
const SECOND_CART = 102
/** Orders 1 to 20 exist, each with its own checked-out cart of the same id. */
const ORDER_COUNT = 20

beforeAll(async () => {
  await sql`
    INSERT INTO users (id, email, password_hash) VALUES
      (42, 'customer-42@example.com', 'hash'), (43, 'customer-43@example.com', 'hash');
    INSERT INTO carts (id, guest_session_token, status) VALUES
      (101, 'cart-101', 'ACTIVE'), (102, 'cart-102', 'ACTIVE');
  `.execute(database.db)
  for (let id = 1; id <= ORDER_COUNT; id++) {
    await sql`INSERT INTO carts (id, guest_session_token, status)
      VALUES (${id}, ${`guest-${String(id)}`}, 'CHECKED_OUT')`.execute(database.db)
    await sql`INSERT INTO orders (id, cart_id, guest_session_token, access_token, status,
        shipping_first_name, shipping_last_name, shipping_street, shipping_house_number,
        shipping_postal_code, shipping_city, shipping_country,
        billing_first_name, billing_last_name, billing_street, billing_house_number,
        billing_postal_code, billing_city, billing_country, email,
        subtotal_cents, shipping_cost_cents, discount_cents, total_cents)
      VALUES (${id}, ${id}, ${`guest-${String(id)}`}, ${`access-token-${String(id)}`.padEnd(43, 'x')},
        'PAID', 'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE',
        'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE', 'customer@example.com',
        1000, 490, 0, 1490)`.execute(database.db)
  }
  setSystemTime(new Date(NOW))
})
afterAll(() => {
  setSystemTime()
})

beforeEach(async () => {
  await sql`
    DELETE FROM promotion_reservations;
    DELETE FROM promotion_redemptions;
    DELETE FROM promotions;
    INSERT INTO promotions (id, name, discount_type, discount_value, coupon_code,
        coupon_code_normalized, starts_at, ends_at, usage_limit_total, usage_limit_per_user, is_active)
    VALUES
      (1, 'Winter sale', 'PERCENTAGE', 10.00, 'Winter10', 'WINTER10',
       '2026-01-01T00:00:00Z', '2026-03-01T00:00:00Z', NULL, NULL, TRUE),
      (2, 'Switched off sale', 'FIXED_AMOUNT', 500.00, 'Off5', 'OFF5', NULL, NULL, NULL, NULL, FALSE),
      (3, 'Future sale', 'PERCENTAGE', 5.00, 'Soon5', 'SOON5', '2026-06-01T00:00:00Z', NULL, NULL, NULL, TRUE),
      (4, 'Past sale', 'PERCENTAGE', 5.00, 'Past5', 'PAST5', NULL, '2026-01-15T00:00:00Z', NULL, NULL, TRUE),
      (5, 'Public sale', 'PERCENTAGE', 30.00, 'Public1', 'PUBLIC1', NULL, NULL, 1, NULL, TRUE),
      (6, 'Personal sale', 'PERCENTAGE', 25.00, 'Personal1', 'PERSONAL1', NULL, NULL, NULL, 1, TRUE),
      (7, 'Members sale', 'PERCENTAGE', 20.00, 'Both1', 'BOTH1', NULL, NULL, 1, 1, TRUE),
      (8, 'Open public sale', 'PERCENTAGE', 15.00, 'Open2', 'OPEN2', NULL, NULL, 2, NULL, TRUE),
      (9, 'Last unit sale', 'PERCENTAGE', 50.00, 'Last1', 'LAST1', NULL, NULL, 1, NULL, TRUE),
      (10, 'Unlimited sale', 'PERCENTAGE', 12.50, 'Free10', 'FREE10', NULL, NULL, NULL, NULL, TRUE);
    INSERT INTO promotion_redemptions (promotion_id, user_id, order_id, redeemed_at) VALUES
      (5, NULL, 1, '2026-01-20T10:00:00Z'),
      (6, 42, 2, '2026-01-20T10:00:00Z'),
      (7, 42, 3, '2026-01-20T10:00:00Z');
  `.execute(database.db)
})

const reserve = (promotionId: number, cartId: number, userId: number | null = null) =>
  write(database.db, (trx) => codes().reserve(trx, promotionId, cartId, userId))

const redeem = (
  promotionId: number,
  orderId: number,
  cartId: number,
  userId: number | null = null,
) => write(database.db, (trx) => codes().redeem(trx, promotionId, orderId, cartId, userId))

/** The error a promise rejects with, or `undefined` when it resolves. */
const failureOf = (promise: Promise<unknown>) =>
  promise.then(
    () => undefined,
    (error: unknown) => error,
  )

const kindOf = (result: PromotionCodeResult) => result.kind

const columnOf = async (
  column: 'cart_id' | 'order_id' | 'user_id',
  table: 'promotion_reservations' | 'promotion_redemptions',
  promotionId: number,
) => {
  const { rows } = await sql<Record<string, number | null>>`
    SELECT ${sql.ref(column)} AS value FROM ${sql.table(table)}
    WHERE promotion_id = ${promotionId} ORDER BY id`.execute(database.db)
  return rows.map((row) => row.value)
}
const reservedCarts = (promotionId: number) =>
  columnOf('cart_id', 'promotion_reservations', promotionId)
const redeemedOrders = (promotionId: number) =>
  columnOf('order_id', 'promotion_redemptions', promotionId)

describe('validate', () => {
  test('resolves a code with trimming and case-insensitive matching', async () => {
    expect(await codes().validate('  wInTeR10  ', FIRST_CART, null)).toEqual({
      kind: 'applicable',
      promotion: {
        id: 1,
        name: 'Winter sale',
        couponCode: 'Winter10',
        discount: { discountType: 'PERCENTAGE', discountValue: 10 },
      },
    })
  })

  test('reports every reason a code cannot be applied, the login hint first', async () => {
    const validate = async (code: string, userId: number | null = null) =>
      kindOf(await codes().validate(code, FIRST_CART, userId))
    expect(await validate('Nothing10')).toBe('invalidCode')
    expect(await validate('Off5')).toBe('inactive')
    expect(await validate('Soon5')).toBe('notStarted')
    expect(await validate('Past5')).toBe('expired')
    expect(await validate('Public1')).toBe('totalExhausted')
    expect(await validate('Public1', USER)).toBe('totalExhausted')
    expect(await validate('Personal1', USER)).toBe('perUserExhausted')
    expect(await validate('Personal1', OTHER_USER)).toBe('applicable')
    // The total limit of Both1 is used up, yet a guest learns that logging in may still help.
    expect(await validate('Both1')).toBe('loginRequired')
    expect(await validate('Both1', USER)).toBe('totalExhausted')
  })

  test('the activity window includes both of its boundaries', async () => {
    const at = async (now: string) => {
      setSystemTime(new Date(now))
      return kindOf(await codes().validate('Winter10', FIRST_CART, null))
    }
    try {
      expect(await at('2026-01-01T00:00:00Z')).toBe('applicable')
      expect(await at('2026-03-01T00:00:00Z')).toBe('applicable')
      expect(await at('2025-12-31T23:59:59Z')).toBe('notStarted')
      expect(await at('2026-03-01T00:00:01Z')).toBe('expired')
    } finally {
      setSystemTime(new Date(NOW))
    }
  })

  test('counts the reservations of other carts and excludes the caller cart', async () => {
    expect(kindOf(await reserve(9, FIRST_CART))).toBe('applicable')
    expect(kindOf(await codes().validate('Last1', SECOND_CART, null))).toBe('totalExhausted')
    expect(kindOf(await codes().validate('Last1', FIRST_CART, null))).toBe('applicable')

    expect(kindOf(await reserve(6, FIRST_CART, OTHER_USER))).toBe('applicable')
    expect(kindOf(await codes().validate('Personal1', SECOND_CART, OTHER_USER))).toBe(
      'perUserExhausted',
    )
    expect(kindOf(await codes().validate('Personal1', FIRST_CART, OTHER_USER))).toBe('applicable')
  })
})

describe('reserve', () => {
  test('holds capacity for its cart and never counts the same cart twice', async () => {
    expect(kindOf(await reserve(9, FIRST_CART))).toBe('applicable')
    expect(kindOf(await reserve(9, FIRST_CART))).toBe('applicable')
    expect(await reservedCarts(9)).toEqual([FIRST_CART])
    expect(kindOf(await reserve(9, SECOND_CART))).toBe('totalExhausted')
  })

  test('refuses an unknown, switched-off, expired, or guest-limited promotion and holds nothing', async () => {
    expect(kindOf(await reserve(404, FIRST_CART))).toBe('invalidCode')
    expect(kindOf(await reserve(2, FIRST_CART))).toBe('inactive')
    expect(kindOf(await reserve(4, FIRST_CART))).toBe('expired')
    expect(kindOf(await reserve(6, FIRST_CART))).toBe('loginRequired')
    const { rows } = await sql`SELECT id FROM promotion_reservations`.execute(database.db)
    expect(rows).toEqual([])
  })

  test('the per-user limit counts the reservations of the same customer only', async () => {
    await sql`DELETE FROM promotion_redemptions WHERE promotion_id = 6`.execute(database.db)
    expect(kindOf(await reserve(6, FIRST_CART, USER))).toBe('applicable')
    expect(kindOf(await reserve(6, SECOND_CART, USER))).toBe('perUserExhausted')
    expect(kindOf(await reserve(6, SECOND_CART, OTHER_USER))).toBe('applicable')
    expect(await reservedCarts(6)).toEqual([FIRST_CART, SECOND_CART])
  })

  test('two concurrent reserves of the last unit by different carts hold it once', async () => {
    const results = await Promise.all([reserve(9, FIRST_CART), reserve(9, SECOND_CART)])
    expect(results.map(kindOf).sort()).toEqual(['applicable', 'totalExhausted'])
    expect(await reservedCarts(9)).toHaveLength(1)
  })

  test('rolls back with the caller transaction', async () => {
    const failed = write(database.db, async (trx) => {
      await codes().reserve(trx, 9, FIRST_CART, null)
      throw new Error('checkout failed')
    })
    expect(await failureOf(failed)).toMatchObject({ message: 'checkout failed' })
    expect(await reservedCarts(9)).toEqual([])
  })
})

describe('redeem', () => {
  test('records the promotion, the optional user, and the order until the total limit is used up', async () => {
    expect(await redeem(8, 4, 4, USER)).toEqual({
      kind: 'applicable',
      promotion: {
        id: 8,
        name: 'Open public sale',
        couponCode: 'Open2',
        discount: { discountType: 'PERCENTAGE', discountValue: 15 },
      },
    })
    // A guest may redeem a promotion that carries only a total limit.
    expect(kindOf(await redeem(8, 5, 5))).toBe('applicable')
    expect(await columnOf('user_id', 'promotion_redemptions', 8)).toEqual([USER, null])
    expect(await redeemedOrders(8)).toEqual([4, 5])
    expect(kindOf(await redeem(8, 6, 6))).toBe('totalExhausted')
    expect(kindOf(await redeem(8, 7, 7, OTHER_USER))).toBe('totalExhausted')
    expect(await redeemedOrders(8)).toEqual([4, 5])
  })

  test('re-checks the limits, refuses an unknown promotion, and ignores the window', async () => {
    expect(kindOf(await redeem(5, 8, 8))).toBe('totalExhausted')
    expect(kindOf(await redeem(6, 9, 9, USER))).toBe('perUserExhausted')
    expect(kindOf(await redeem(7, 10, 10))).toBe('loginRequired')
    expect(kindOf(await redeem(404, 11, 11))).toBe('invalidCode')
    // A promotion that expires while the customer pays is still redeemed.
    expect(kindOf(await redeem(4, 12, 12))).toBe('applicable')
  })

  test('consumes the reservation of its own cart and counts the reservations of others', async () => {
    expect(kindOf(await reserve(9, FIRST_CART))).toBe('applicable')
    expect(kindOf(await redeem(9, 13, 13))).toBe('totalExhausted')

    expect(kindOf(await reserve(10, 13, USER))).toBe('applicable')
    expect(kindOf(await redeem(10, 13, 13, USER))).toBe('applicable')
    expect(await reservedCarts(10)).toEqual([])
    expect(await redeemedOrders(10)).toEqual([13])
  })

  test('a rolled-back caller transaction leaves the reservation and no redemption', async () => {
    expect(kindOf(await reserve(10, 14, USER))).toBe('applicable')
    const failed = write(database.db, async (trx) => {
      await codes().redeem(trx, 10, 14, 14, USER)
      throw new Error('payment transition failed')
    })
    expect(await failureOf(failed)).toMatchObject({ message: 'payment transition failed' })
    expect(await redeemedOrders(10)).toEqual([])
    expect(await reservedCarts(10)).toEqual([14])
  })

  test('an order redeems at most once: the unique order id rejects the second redemption', async () => {
    expect(kindOf(await redeem(10, 15, 15, USER))).toBe('applicable')
    expect(await failureOf(redeem(10, 15, 15, USER))).toMatchObject({ code: '23505' })
    expect(await redeemedOrders(10)).toEqual([15])
  })

  test('two concurrent redeems of the last unit produce exactly one redemption', async () => {
    const results = await Promise.all([redeem(9, 16, 16, USER), redeem(9, 17, 17, OTHER_USER)])
    expect(results.map(kindOf).sort()).toEqual(['applicable', 'totalExhausted'])
    expect(await redeemedOrders(9)).toHaveLength(1)
  })
})

describe('release', () => {
  test('frees the capacity for another cart and is idempotent', async () => {
    expect(kindOf(await reserve(9, FIRST_CART))).toBe('applicable')
    await write(database.db, (trx) => codes().release(trx, FIRST_CART))
    await write(database.db, (trx) => codes().release(trx, FIRST_CART))
    await write(database.db, (trx) => codes().release(trx, SECOND_CART))
    expect(await reservedCarts(9)).toEqual([])
    expect(kindOf(await reserve(9, SECOND_CART))).toBe('applicable')
  })
})

describe('find', () => {
  test('answers the current master data of known ids, unknown ones absent, without availability rules', async () => {
    const found = await codes().find([1, 2, 404])
    expect([...found.entries()]).toEqual([
      [
        1,
        {
          id: 1,
          name: 'Winter sale',
          couponCode: 'Winter10',
          discount: { discountType: 'PERCENTAGE', discountValue: 10 },
        },
      ],
      [
        2,
        {
          id: 2,
          name: 'Switched off sale',
          couponCode: 'Off5',
          discount: { discountType: 'FIXED_AMOUNT', discountValue: 500 },
        },
      ],
    ])
    expect((await codes().find([])).size).toBe(0)
  })
})

describe('toApiError', () => {
  test('maps every rejection to the status and the stable code the cart and checkout answer', () => {
    const mapped = (
      [
        'invalidCode',
        'inactive',
        'notStarted',
        'expired',
        'loginRequired',
        'totalExhausted',
        'perUserExhausted',
      ] as const
    ).map((kind) => {
      const { status, body } = toApiError({ kind })
      return [status, body.code, body.message]
    })
    expect(mapped).toEqual([
      [400, 'PROMOTION_INVALID_CODE', 'Promotion code is invalid'],
      [400, 'PROMOTION_INACTIVE', 'Promotion code is not active'],
      [400, 'PROMOTION_NOT_STARTED', 'Promotion code is not valid yet'],
      [400, 'PROMOTION_EXPIRED', 'Promotion code has expired'],
      [403, 'PROMOTION_LOGIN_REQUIRED', 'Promotion code requires a signed-in customer'],
      [409, 'PROMOTION_TOTAL_EXHAUSTED', 'Promotion code has reached its usage limit'],
      [409, 'PROMOTION_PER_USER_EXHAUSTED', 'Promotion code has reached your usage limit'],
    ])
    expect(toApiError({ kind: 'expired' }).body.errors).toEqual({})
  })
})
