import { describe, expect, test } from 'bun:test'
import { ADDRESS, MOLLIE_SETTINGS } from '../test-fixtures'
import type { PayableOrder } from '../repository'
import { amountText, createMolliePayments, MOLLIE_PAYMENTS_URL } from './mollie'

const order: PayableOrder = {
  orderId: 42,
  cartId: 7,
  totalCents: 4070,
  email: 'customer@example.com',
  phone: '0176 23123456',
  shippingAddress: ADDRESS,
  billingAddress: {
    firstName: 'Max',
    lastName: 'Mustermann',
    street: 'Musterstraße',
    houseNumber: '1',
    postalCode: '10115',
    city: 'Berlin',
    country: 'DE',
  },
}

/** The adapter on a stub that records every request and answers with `respond`, or fails with it. */
function mollie(respond: () => Response | Error) {
  const requests: { url: string; init: RequestInit }[] = []
  const fetch = (url: string, init: RequestInit) => {
    requests.push({ url, init })
    const answer = respond()
    return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer)
  }
  return { payments: createMolliePayments(MOLLIE_SETTINGS, fetch), requests }
}

const answer = (body: Record<string, unknown>, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const payment = (overrides: Record<string, unknown> = {}) =>
  answer({
    resource: 'payment',
    id: 'tr_created',
    status: 'open',
    amount: { currency: 'EUR', value: '40.70' },
    _links: { checkout: { href: 'https://www.mollie.com/checkout/tr_created' } },
    ...overrides,
  })

describe('create', () => {
  test('sends the whole request contract and reads the created payment', async () => {
    const { payments, requests } = mollie(() => payment())

    expect(await payments.create(order, 'key-4711')).toEqual({
      id: 'tr_created',
      status: 'OPEN',
      amountCents: 4070,
      checkoutUrl: 'https://www.mollie.com/checkout/tr_created',
    })

    const [request] = requests
    expect(request?.url).toBe(MOLLIE_PAYMENTS_URL)
    expect(request?.init.method).toBe('POST')
    expect(request?.init.redirect).toBe('manual')
    expect(request?.init.headers).toEqual({
      Authorization: 'Bearer test_mollie_key',
      'Content-Type': 'application/json',
      'Idempotency-Key': 'key-4711',
    })
    expect(JSON.parse(request?.init.body as string)).toEqual({
      amount: { currency: 'EUR', value: '40.70' },
      description: 'Order #42',
      redirectUrl: 'https://voenix.test/order-confirmation?orderId=42',
      webhookUrl: MOLLIE_SETTINGS.webhookUrl,
      billingAddress: {
        givenName: 'Max',
        familyName: 'Mustermann',
        email: 'customer@example.com',
        phone: '+4917623123456',
        streetAndNumber: 'Musterstraße 1',
        city: 'Berlin',
        postalCode: '10115',
        country: 'DE',
      },
      shippingAddress: {
        givenName: 'Erika',
        familyName: 'Musterfrau',
        email: 'customer@example.com',
        phone: '+4917623123456',
        streetAndNumber: 'Lieferweg 5',
        city: 'Hamburg',
        postalCode: '20095',
        country: 'DE',
      },
      metadata: { orderId: 42 },
    })
  })

  test('leaves out a phone that cannot be E.164 and formats every amount exactly', async () => {
    const { payments, requests } = mollie(() => payment())
    await payments.create({ ...order, phone: 'not a number' }, 'key')
    const body = JSON.parse(requests[0]?.init.body as string) as {
      billingAddress: Record<string, unknown>
    }
    expect('phone' in body.billingAddress).toBe(false)
    expect([1, 5, 100, 1005, 4070, 123456].map(amountText)).toEqual([
      '0.01',
      '0.05',
      '1.00',
      '10.05',
      '40.70',
      '1234.56',
    ])
  })

  test('a refusal, a redirect, a transport failure, or an answer without a checkout URL is null', async () => {
    for (const respond of [
      () => answer({ title: 'Unprocessable Entity' }, 422),
      () => new Response(null, { status: 302, headers: { Location: 'https://elsewhere.test' } }),
      () => new TypeError('fetch failed'),
      () => payment({ _links: {} }),
      () => payment({ _links: { checkout: { href: '  ' } } }),
      () => answer({ id: 'tr_created' }),
      () => new Response('<html>', { status: 200 }),
    ]) {
      expect(await mollie(respond).payments.create(order, 'key')).toBeNull()
    }
  })
})

describe('find', () => {
  test('maps every Mollie status word and refuses what it cannot act on', async () => {
    const find = (overrides: Record<string, unknown>) =>
      mollie(() => payment({ _links: undefined, ...overrides })).payments.find('tr_created')
    const words = ['open', 'pending', 'authorized', 'paid', 'failed', 'canceled', 'expired']
    const statuses = await Promise.all(
      words.map(async (status) => (await find({ status }))?.status),
    )
    expect(statuses).toEqual([
      'OPEN',
      'PENDING',
      'AUTHORIZED',
      'PAID',
      'FAILED',
      'CANCELED',
      'EXPIRED',
    ])
    expect(await find({ status: 'paid' })).toEqual({
      id: 'tr_created',
      status: 'PAID',
      amountCents: 4070,
      checkoutUrl: null,
    })
    expect((await find({ amount: { currency: 'EUR', value: '40.7' } }))?.amountCents).toBe(4070)
    expect(await find({ status: 'refunded-ish' })).toBeNull()
    expect(await find({ amount: { currency: 'USD', value: '40.70' } })).toBeNull()
    expect(await find({ amount: { currency: 'EUR', value: '40.701' } })).toBeNull()
    expect(await find({ id: 'tr_other' })).toBeNull()
  })

  test('asks for the payment by its id', async () => {
    const { payments, requests } = mollie(() => payment())
    await payments.find('tr_created')
    expect(requests[0]?.url).toBe(`${MOLLIE_PAYMENTS_URL}/tr_created`)
    expect(requests[0]?.init.method).toBe('GET')
  })
})

describe('cancel', () => {
  test('answers whether Mollie cancelled the payment', async () => {
    const cancelled = mollie(() => payment({ status: 'canceled' }))
    expect(await cancelled.payments.cancel('tr_created')).toBe(true)
    expect(cancelled.requests[0]?.init.method).toBe('DELETE')
    expect(await mollie(() => answer({}, 422)).payments.cancel('tr_created')).toBe(false)
    expect(await mollie(() => new TypeError('down')).payments.cancel('tr_created')).toBe(false)
  })
})
