import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { composedApplication, type ComposedApplication, WEBHOOK_SECRET } from '@/test-fixtures'
import { count, seedUser, testDatabase } from '@test-support/database'
import { guestCookie, guestHeaders, jsonOf, setCookies, signedInHeaders } from '@test-support/http'
import {
  checkoutBody,
  GHOST,
  resetCheckouts,
  seedCart,
  seedPromotion,
  seedSpodArticle,
  SHIRT,
  TOTAL_CENTS,
} from './test-fixtures'

/**
 * The checkout journeys through the whole composed application on real PostgreSQL, with Mollie
 * stubbed: the checkout itself owns no table, so every assertion is about a row another module
 * wrote because the checkout called it, in the order the two transactions prescribe.
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
beforeEach(async () => {
  await resetCheckouts(database.db)
  composed?.mollie.reset()
})

const app = () => {
  if (composed === undefined) throw new Error('The application exists only inside a test')
  return composed
}

/** A guest browser that fetched a CSRF token and carries the guest cookie of `token`. */
const guest = async (token: string) => {
  const headers = await guestHeaders()
  return { ...headers, Cookie: `${headers.Cookie ?? ''}; ${await guestCookie(token)}` }
}
/** Customer `userId`, signed in. */
const customer = (userId: number) => signedInHeaders({ userId: userId, roles: ['CUSTOMER'] })

const checkout = (headers: Record<string, string>, body: unknown = checkoutBody()) =>
  app().app.request('/api/checkout', {
    method: 'POST',
    headers: { ...headers, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
const retry = (headers: Record<string, string>, orderId: number | string) =>
  app().app.request(`/api/checkout/orders/${String(orderId)}/payment`, {
    method: 'POST',
    headers,
  })
const webhook = (id: string) =>
  app().app.request(`/api/payments/webhook/${WEBHOOK_SECRET}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ id }).toString(),
  })

interface CheckoutAnswer {
  orderId: number
  checkoutUrl: string | null
}
const answerOf = async (response: Response) => (await jsonOf(response)) as CheckoutAnswer
const codeOf = async (response: Response) => ((await jsonOf(response)) as { code?: string }).code

const order = (orderId: number) =>
  database.db
    .selectFrom('orders')
    .select([
      'status',
      'user_id',
      'guest_session_token',
      'promotion_id',
      'phone',
      'discount_cents',
      'total_cents',
    ])
    .where('id', '=', orderId)
    .executeTakeFirstOrThrow()
const cartStatus = async (cartId: number) =>
  (
    await database.db
      .selectFrom('carts')
      .select('status')
      .where('id', '=', cartId)
      .executeTakeFirstOrThrow()
  ).status
const molliePaymentId = async (orderId: number) =>
  (
    await database.db
      .selectFrom('payments')
      .select('mollie_payment_id')
      .where('order_id', '=', orderId)
      .orderBy('id')
      .executeTakeFirstOrThrow()
  ).mollie_payment_id

describe('POST /api/checkout', () => {
  test('a guest checkout places the order, starts the payment, and closes the cart', async () => {
    const headers = await guest('guest-a')
    const cartId = await seedCart(database.db, { guestToken: 'guest-a' })

    const response = await checkout(headers)

    expect(response.status).toBe(201)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(setCookies(response, 'voenix.guest')).toEqual([])
    const answer = await answerOf(response)
    expect(answer.checkoutUrl).toBe(`https://mollie.test/checkout/tr_${String(answer.orderId)}_1`)
    expect(response.headers.get('Location')).toBe(`/api/orders/${String(answer.orderId)}`)
    expect(await order(answer.orderId)).toEqual({
      status: 'PENDING',
      user_id: null,
      guest_session_token: 'guest-a',
      promotion_id: null,
      phone: null,
      discount_cents: 0,
      total_cents: TOTAL_CENTS,
    })
    expect(await count(database.db, 'payments')).toBe(1)
    expect(await count(database.db, 'email_jobs')).toBe(1)
    expect(await cartStatus(cartId)).toBe('CHECKED_OUT')
  })

  test('a signed-in checkout with a coupon reserves it and stores the user and the discount', async () => {
    const userId = await seedUser(database.db, 'erika@example.com')
    const promotionId = await seedPromotion(database.db, 'SAVE10')
    const cartId = await seedCart(database.db, { userId }, { promotionId })

    const response = await checkout(await customer(userId))

    expect(response.status).toBe(201)
    const { orderId } = await answerOf(response)
    expect(await order(orderId)).toMatchObject({
      status: 'PENDING',
      user_id: userId,
      guest_session_token: null,
      promotion_id: promotionId,
      discount_cents: 149,
      total_cents: TOTAL_CENTS - 149,
    })
    expect(await count(database.db, 'promotion_reservations')).toBe(1)
    expect(await cartStatus(cartId)).toBe('CHECKED_OUT')
  })

  test('a free order is paid at once, redeems the coupon, and never reaches the provider', async () => {
    const promotionId = await seedPromotion(database.db, 'FREEBIE', { percentage: 100 })
    const cartId = await seedCart(database.db, { guestToken: 'guest-a' }, { promotionId })

    const response = await checkout(await guest('guest-a'))

    expect(response.status).toBe(201)
    const answer = await answerOf(response)
    expect(answer.checkoutUrl).toBeNull()
    expect(await order(answer.orderId)).toMatchObject({ status: 'PAID', total_cents: 0 })
    expect(await count(database.db, 'promotion_redemptions')).toBe(1)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
    expect(await count(database.db, 'production_requests')).toBe(1)
    expect(await count(database.db, 'payments')).toBe(0)
    expect(await cartStatus(cartId)).toBe('CHECKED_OUT')
    expect(app().mollie.calls.create).toBe(0)
  })

  test('a refused provider cancels the order, frees the coupon, and keeps the cart active', async () => {
    app().mollie.state.refuse = true
    const promotionId = await seedPromotion(database.db, 'REFUSED10')
    const cartId = await seedCart(database.db, { guestToken: 'guest-a' }, { promotionId })

    const response = await checkout(await guest('guest-a'))

    expect(response.status).toBe(502)
    const body = await response.text()
    expect(body).toContain('"code":"PAYMENT_NOT_STARTED"')
    expect(body.toLowerCase()).not.toContain('cancel')
    const placed = await database.db
      .selectFrom('orders')
      .select('id')
      .where('cart_id', '=', cartId)
      .executeTakeFirstOrThrow()
    expect(await order(placed.id)).toMatchObject({ status: 'CANCELLED' })
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
    expect(await count(database.db, 'payments')).toBe(0)
    expect(await cartStatus(cartId)).toBe('ACTIVE')
  })

  test('no identity and no cart are the same empty-cart answer', async () => {
    expect((await checkout(await guestHeaders())).status).toBe(400)
    const response = await checkout(await guest('guest-without-cart'))
    expect(response.status).toBe(400)
    expect(await codeOf(response)).toBe('CART_EMPTY')
  })

  test('an unshippable country is a field error that writes nothing', async () => {
    const cartId = await seedCart(database.db, { guestToken: 'guest-a' })
    await sql`DELETE FROM countries WHERE country_code = 'DE'`.execute(database.db)
    try {
      const response = await checkout(await guest('guest-a'))
      expect(response.status).toBe(400)
      expect(await jsonOf(response)).toEqual({
        message: 'Validation failed',
        errors: { 'shippingAddress.country': ['We do not ship to this country'] },
      })
    } finally {
      await sql`INSERT INTO countries (name, country_code) VALUES ('Germany', 'DE')`.execute(
        database.db,
      )
    }
    expect(await count(database.db, 'orders')).toBe(0)
    expect(await cartStatus(cartId)).toBe('ACTIVE')
  })

  test('a SPOD line needs a phone number, which the order then stores trimmed', async () => {
    await seedSpodArticle(database.db)
    await seedCart(database.db, { guestToken: 'guest-a' }, { line: SHIRT })
    const headers = await guest('guest-a')

    const refused = await checkout(headers, checkoutBody({ phone: '  ' }))
    expect(refused.status).toBe(400)
    expect(await jsonOf(refused)).toEqual({
      message: 'Validation failed',
      errors: {
        'shippingAddress.phone': [
          'Phone is required for orders containing print-on-demand articles',
        ],
      },
    })
    expect(await count(database.db, 'orders')).toBe(0)

    const placed = await checkout(headers, checkoutBody({ phone: ' +49 89 1234 ' }))
    expect(placed.status).toBe(201)
    expect(await order((await answerOf(placed)).orderId)).toMatchObject({ phone: '+49 89 1234' })
  })

  test('a coupon that expired between the cart and the checkout is refused with the promotion answer', async () => {
    const promotionId = await seedPromotion(database.db, 'SUMMER25', {
      endsAt: new Date(Date.now() - 86_400_000),
    })
    const cartId = await seedCart(database.db, { guestToken: 'guest-a' }, { promotionId })

    const response = await checkout(await guest('guest-a'))

    expect(response.status).toBe(400)
    expect(await codeOf(response)).toBe('PROMOTION_EXPIRED')
    expect(await count(database.db, 'orders')).toBe(0)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
    expect(await cartStatus(cartId)).toBe('ACTIVE')
  })

  test('a line the catalog cannot resolve refuses the checkout and gives the coupon back', async () => {
    const promotionId = await seedPromotion(database.db, 'LASTUNIT', { usageLimitTotal: 1 })
    await seedCart(database.db, { guestToken: 'giver' }, { line: GHOST, promotionId })

    const refused = await checkout(await guest('giver'))
    expect(refused.status).toBe(409)
    expect(await codeOf(refused)).toBe('CART_ITEM_UNAVAILABLE')
    expect(await count(database.db, 'orders')).toBe(0)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)

    // The freed unit really is available to another cart.
    await seedCart(database.db, { guestToken: 'taker' }, { promotionId })
    expect((await checkout(await guest('taker'))).status).toBe(201)
    expect(await count(database.db, 'promotion_reservations')).toBe(1)
  })

  test('two overlapping submissions of one cart end as one order and one payment', async () => {
    const headers = await guest('guest-a')
    const cartId = await seedCart(database.db, { guestToken: 'guest-a' })

    const [first, second] = await Promise.all([checkout(headers), checkout(headers)])

    expect(first.status).toBe(201)
    expect(second.status).toBe(201)
    expect(await first.text()).toBe(await second.text())
    expect(await count(database.db, 'orders')).toBe(1)
    expect(await count(database.db, 'payments')).toBe(1)
    expect(app().mollie.calls.create).toBe(1)
    expect(await cartStatus(cartId)).toBe('CHECKED_OUT')
  })

  test('two carts racing the last unit of a coupon produce one order and one conflict', async () => {
    const promotionId = await seedPromotion(database.db, 'LASTONE', { usageLimitTotal: 1 })
    await seedCart(database.db, { guestToken: 'one' }, { promotionId })
    await seedCart(database.db, { guestToken: 'other' }, { promotionId })

    const answers = await Promise.all([
      checkout(await guest('one')),
      checkout(await guest('other')),
    ])

    expect(answers.map((answer) => answer.status).sort()).toEqual([201, 409])
    const loser = answers.find((answer) => answer.status === 409)
    expect(loser === undefined ? undefined : await codeOf(loser)).toBe('PROMOTION_TOTAL_EXHAUSTED')
    expect(await count(database.db, 'orders')).toBe(1)
    expect(await count(database.db, 'promotion_reservations')).toBe(1)
  })
})

describe('POST /api/checkout/orders/{orderId}/payment', () => {
  /** A guest checkout of `token`; answers its headers and the order. */
  const placedOrder = async (token = 'guest-a', promotionId: number | null = null) => {
    const headers = await guest(token)
    await seedCart(database.db, { guestToken: token }, { promotionId })
    const response = await checkout(headers)
    expect(response.status).toBe(201)
    const body = await response.text()
    return { headers, body, ...(JSON.parse(body) as CheckoutAnswer) }
  }

  test('retrying a live payment answers the same URL without a provider call', async () => {
    const placed = await placedOrder()

    const response = await retry(placed.headers, placed.orderId)

    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(response.headers.get('Location')).toBeNull()
    expect(await response.text()).toBe(placed.body)
    expect(app().mollie.calls.create).toBe(1)
    expect(await count(database.db, 'payments')).toBe(1)
  })

  test('a terminal payment frees the coupon, and the retry starts a second payment', async () => {
    const promotionId = await seedPromotion(database.db, 'RETRY10')
    const placed = await placedOrder('guest-a', promotionId)
    expect(await count(database.db, 'promotion_reservations')).toBe(1)
    const paymentId = await molliePaymentId(placed.orderId)
    app().mollie.report(paymentId, { status: 'EXPIRED' })
    expect((await webhook(paymentId)).status).toBe(200)
    expect(await order(placed.orderId)).toMatchObject({ status: 'PENDING' })
    expect(await count(database.db, 'promotion_reservations')).toBe(0)

    const response = await retry(placed.headers, placed.orderId)

    expect(response.status).toBe(200)
    expect((await answerOf(response)).checkoutUrl).not.toBe(placed.checkoutUrl)
    expect(await count(database.db, 'payments')).toBe(2)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
  })

  test('two simultaneous retries of one order leave one live payment', async () => {
    const placed = await placedOrder()
    const paymentId = await molliePaymentId(placed.orderId)
    app().mollie.report(paymentId, { status: 'EXPIRED' })
    expect((await webhook(paymentId)).status).toBe(200)

    const answers = await Promise.all([
      retry(placed.headers, placed.orderId),
      retry(placed.headers, placed.orderId),
    ])

    expect(answers.map((answer) => answer.status)).toEqual([200, 200])
    expect(await answers[0].text()).toBe(await answers[1].text())
    expect(await count(database.db, 'payments')).toBe(2)
    expect(await order(placed.orderId)).toMatchObject({ status: 'PENDING' })
  })

  test('a foreign, an unknown, and a malformed order id are not found and never reach the provider', async () => {
    const placed = await placedOrder('owner')
    const stranger = await guest('stranger')

    for (const orderId of [placed.orderId, 999_999, 'abc']) {
      const response = await retry(stranger, orderId)
      expect(response.status).toBe(404)
      expect(await jsonOf(response)).toEqual({ message: 'Order not found', errors: {} })
    }
    expect(app().mollie.calls.create).toBe(1)
  })

  test('a paid, a cancelled, and a free order cannot be paid again', async () => {
    // A free order is PAID the moment it is placed, so its retry is the "already paid" answer.
    const paid = await placedOrder('paying')
    app().mollie.report(await molliePaymentId(paid.orderId), { status: 'PAID' })
    expect((await webhook(await molliePaymentId(paid.orderId))).status).toBe(200)
    const alreadyPaid = await retry(paid.headers, paid.orderId)
    expect(alreadyPaid.status).toBe(409)
    expect(await codeOf(alreadyPaid)).toBe('ORDER_ALREADY_PAID')

    app().mollie.state.refuse = true
    const cancelledHeaders = await guest('refused')
    const cancelledCart = await seedCart(database.db, { guestToken: 'refused' })
    expect((await checkout(cancelledHeaders)).status).toBe(502)
    app().mollie.state.refuse = false
    const cancelled = await database.db
      .selectFrom('orders')
      .select('id')
      .where('cart_id', '=', cancelledCart)
      .executeTakeFirstOrThrow()
    const notPayable = await retry(cancelledHeaders, cancelled.id)
    expect(notPayable.status).toBe(409)
    expect(await codeOf(notPayable)).toBe('ORDER_NOT_PAYABLE')

    const promotionId = await seedPromotion(database.db, 'FREEBIE', { percentage: 100 })
    const free = await placedOrder('free', promotionId)
    const freeRetry = await retry(free.headers, free.orderId)
    expect(freeRetry.status).toBe(409)
    expect(await codeOf(freeRetry)).toBe('ORDER_ALREADY_PAID')
  })
})
