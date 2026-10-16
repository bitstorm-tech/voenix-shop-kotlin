import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createPromotionRepository, type PromotionInput } from '@/modules/promotion/repository'
import { testDatabase } from '@test-support/database'

const database = testDatabase()
const repository = () => createPromotionRepository(database.db)

const input = (name: string, couponCode: string): PromotionInput => ({
  name,
  couponCode,
  discountType: 'PERCENTAGE',
  discountValue: 10,
  startsAt: null,
  endsAt: null,
  usageLimitTotal: null,
  usageLimitPerUser: null,
  isActive: true,
})

const storedCodes = async () =>
  (
    await sql<{ code: string }>`SELECT coupon_code AS code FROM promotions ORDER BY id`.execute(
      database.db,
    )
  ).rows.map((row) => row.code)

beforeEach(async () => {
  await sql`TRUNCATE promotions RESTART IDENTITY CASCADE`.execute(database.db)
})

describe('the case-insensitive unique coupon code', () => {
  test('concurrent case-variant creates leave one row and one conflict', async () => {
    const promotions = repository()
    const results = await Promise.all([
      promotions.create(input('Spring sale', 'Spring10')),
      promotions.create(input('Spring copy', 'SPRING10')),
    ])
    expect(results.map((result) => result.kind).sort()).toEqual(['conflict', 'ok'])
    expect(await storedCodes()).toHaveLength(1)
  })

  test('concurrent case-variant updates leave one code and one conflict', async () => {
    const promotions = repository()
    await promotions.create(input('First', 'First1'))
    await promotions.create(input('Second', 'Second1'))
    const results = await Promise.all([
      promotions.update(1, input('First', 'Shared1')),
      promotions.update(2, input('Second', 'shared1')),
    ])
    expect(results.map((result) => result.kind).sort()).toEqual(['conflict', 'ok'])
    expect((await storedCodes()).filter((code) => code.toUpperCase() === 'SHARED1')).toHaveLength(1)
  })
})

describe('the lock of a redeemed promotion', () => {
  test('an admin update racing a redemption waits for the row lock, sees the redemption, and conflicts', async () => {
    const promotions = repository()
    await promotions.create(input('Open sale', 'Open2'))
    await sql`
      INSERT INTO carts (id, guest_session_token, status) VALUES (1, 'guest-1', 'CHECKED_OUT');
      INSERT INTO orders (id, cart_id, guest_session_token, access_token, status,
          shipping_first_name, shipping_last_name, shipping_street, shipping_house_number,
          shipping_postal_code, shipping_city, shipping_country,
          billing_first_name, billing_last_name, billing_street, billing_house_number,
          billing_postal_code, billing_city, billing_country, email,
          subtotal_cents, shipping_cost_cents, discount_cents, total_cents)
        VALUES (1, 1, 'guest-1', 'access-token-1xxxxxxxxxxxxxxxxxxxxxxxxxxxxx', 'PAID',
          'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE',
          'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE', 'customer@example.com',
          1000, 490, 0, 1490);
    `.execute(database.db)

    let updateWasBlocked = false
    const { racing } = await database.db.transaction().execute(async (redeemer) => {
      await sql`SELECT id FROM promotions WHERE id = 1 FOR UPDATE`.execute(redeemer)
      const racing = promotions.update(1, input('Renamed sale', 'Renamed2'))
      // Only once the update waits for the lock does the redemption commit inside the window a
      // guard without the lock would miss; the poll gives up after five seconds.
      for (let attempt = 0; attempt < 100 && !updateWasBlocked; attempt++) {
        const { rows } = await sql<{ waiting: number }>`
          SELECT count(*) AS waiting FROM pg_stat_activity
          WHERE datname = current_database() AND wait_event_type = 'Lock'`.execute(database.db)
        updateWasBlocked = (rows[0]?.waiting ?? 0) > 0
        if (!updateWasBlocked) await Bun.sleep(50)
      }
      await sql`INSERT INTO promotion_redemptions (promotion_id, user_id, order_id, redeemed_at)
        VALUES (1, NULL, 1, CURRENT_TIMESTAMP)`.execute(redeemer)
      // Wrapped, so the transaction commits (and frees the lock) before the update is awaited.
      return { racing }
    })

    expect(updateWasBlocked).toBe(true)
    expect(await racing).toEqual({ kind: 'conflict' })
    const stored = await promotions.get(1)
    expect(stored.kind === 'ok' && stored.value.name).toBe('Open sale')
  })
})
