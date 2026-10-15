import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createPromotionModule } from '@/modules/promotion'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'

const database = testDatabase()
const app = () => testApp(createPromotionModule({ db: database.db }).routes)
const admin = () => signedInHeaders({ roles: ['ADMIN'] })

const send = async (method: string, path: string, body?: string) =>
  app().request(path, {
    method,
    headers: { ...(await admin()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body }),
  })

const winterSale = {
  id: 1,
  name: 'Winter sale',
  couponCode: 'Winter10',
  discount: { discountType: 'PERCENTAGE', discountValue: 10 },
  startsAt: '2026-01-01T00:00:00.000Z',
  endsAt: '2026-03-01T00:00:00.000Z',
  usageLimitTotal: 100,
  usageLimitPerUser: 1,
  isActive: true,
  redemptionCount: 1,
  isLocked: true,
}
const autumnSale = {
  id: 2,
  name: 'Autumn sale',
  couponCode: 'Autumn5',
  discount: { discountType: 'FIXED_AMOUNT', discountValue: 500 },
  startsAt: null,
  endsAt: null,
  usageLimitTotal: null,
  usageLimitPerUser: null,
  isActive: false,
  redemptionCount: 0,
  isLocked: false,
}

beforeAll(async () => {
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
})

beforeEach(async () => {
  await sql`
    DELETE FROM promotion_redemptions;
    DELETE FROM promotions;
    INSERT INTO promotions (id, name, discount_type, discount_value, coupon_code,
        coupon_code_normalized, starts_at, ends_at, usage_limit_total, usage_limit_per_user, is_active)
    VALUES
      (1, 'Winter sale', 'PERCENTAGE', 10.00, 'Winter10', 'WINTER10',
       '2026-01-01T00:00:00Z', '2026-03-01T00:00:00Z', 100, 1, TRUE),
      (2, 'Autumn sale', 'FIXED_AMOUNT', 500.00, 'Autumn5', 'AUTUMN5', NULL, NULL, NULL, NULL, FALSE);
    SELECT setval(pg_get_serial_sequence('promotions', 'id'), 2);
    INSERT INTO promotion_redemptions (promotion_id, user_id, order_id, redeemed_at)
      VALUES (1, NULL, 1, '2026-01-20T10:00:00Z');
  `.execute(database.db)
})

describe('GET /api/admin/promotions', () => {
  test('answers every promotion ordered by name, then id, with its redemption count', async () => {
    const response = await app().request('/api/admin/promotions', { headers: await admin() })
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toEqual([autumnSale, winterSale])
  })
})

describe('POST /api/admin/promotions', () => {
  test('creates a trimmed promotion in UTC and answers 201 with a Location header', async () => {
    const created = await send(
      'POST',
      '/api/admin/promotions',
      JSON.stringify({
        name: ' Summer sale ',
        couponCode: '  Sommer25  ',
        discountType: 'PERCENTAGE',
        discountValue: 25,
        startsAt: '2026-06-01T00:00:00+02:00',
        usageLimitTotal: 100,
        isActive: true,
      }),
    )
    expect(created.status).toBe(201)
    expect(created.headers.get('Location')).toBe('/api/admin/promotions/3')
    expect(await jsonOf(created)).toEqual({
      id: 3,
      name: 'Summer sale',
      couponCode: 'Sommer25',
      discount: { discountType: 'PERCENTAGE', discountValue: 25 },
      startsAt: '2026-05-31T22:00:00.000Z',
      endsAt: null,
      usageLimitTotal: 100,
      usageLimitPerUser: null,
      isActive: true,
      redemptionCount: 0,
      isLocked: false,
    })
  })

  test('a code that differs only in letter case answers 409', async () => {
    const duplicate = await send(
      'POST',
      '/api/admin/promotions',
      '{"name":"Copy","couponCode":" winter10 ","discountType":"PERCENTAGE","discountValue":5}',
    )
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Coupon code is already in use',
      errors: {},
    })
  })
})

describe('GET /api/admin/promotions/:id', () => {
  test('answers the promotion, 404 for an unknown id, and 400 for a malformed one', async () => {
    const found = await app().request('/api/admin/promotions/1', { headers: await admin() })
    expect(found.status).toBe(200)
    expect(await jsonOf(found)).toEqual(winterSale)
    const missing = await app().request('/api/admin/promotions/999', { headers: await admin() })
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Promotion not found', errors: {} })
    const malformed = await app().request('/api/admin/promotions/abc', { headers: await admin() })
    expect(malformed.status).toBe(400)
    expect(await jsonOf(malformed)).toEqual({ message: 'Invalid promotion id', errors: {} })
  })
})

describe('PUT /api/admin/promotions/:id', () => {
  test('replaces every field of an unredeemed promotion and answers 404 for an unknown id', async () => {
    const body =
      '{"name":"Autumn deal","couponCode":"Autumn7","discountType":"PERCENTAGE","discountValue":7.5}'
    const updated = await send('PUT', '/api/admin/promotions/2', body)
    expect(updated.status).toBe(200)
    expect(await jsonOf(updated)).toEqual({
      ...autumnSale,
      name: 'Autumn deal',
      couponCode: 'Autumn7',
      discount: { discountType: 'PERCENTAGE', discountValue: 7.5 },
    })
    expect((await send('PUT', '/api/admin/promotions/999', body)).status).toBe(404)
  })

  test('a redeemed promotion refuses a configuration change but accepts an activation change', async () => {
    const configuration = {
      name: 'Winter sale',
      couponCode: 'Winter10',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      startsAt: '2026-01-01T01:00:00+01:00',
      endsAt: '2026-03-01T00:00:00Z',
      usageLimitTotal: 100,
      usageLimitPerUser: 1,
    }
    const rejected = await send(
      'PUT',
      '/api/admin/promotions/1',
      JSON.stringify({ ...configuration, name: 'Winter sale reloaded', isActive: true }),
    )
    expect(rejected.status).toBe(409)
    expect(await jsonOf(rejected)).toEqual({
      message: 'Coupon code is already in use or the promotion is locked',
      errors: {},
    })

    // The same configuration in another notation, only isActive differs.
    const deactivated = await send(
      'PUT',
      '/api/admin/promotions/1',
      JSON.stringify({ ...configuration, isActive: false }),
    )
    expect(deactivated.status).toBe(200)
    expect(await jsonOf(deactivated)).toEqual({ ...winterSale, isActive: false })
  })

  test('a case-variant code of another promotion answers 409', async () => {
    const conflict = await send(
      'PUT',
      '/api/admin/promotions/2',
      '{"name":"Autumn sale","couponCode":"WINTER10","discountType":"FIXED_AMOUNT","discountValue":500}',
    )
    expect(conflict.status).toBe(409)
  })
})

describe('DELETE /api/admin/promotions/:id', () => {
  test('answers 204, then 404 for the same id, and 409 for a redeemed promotion', async () => {
    const deleted = await send('DELETE', '/api/admin/promotions/2')
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
    expect((await send('DELETE', '/api/admin/promotions/2')).status).toBe(404)
    const inUse = await send('DELETE', '/api/admin/promotions/1')
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({
      message: 'Promotion is still in use and cannot be deleted',
      errors: {},
    })
  })
})
