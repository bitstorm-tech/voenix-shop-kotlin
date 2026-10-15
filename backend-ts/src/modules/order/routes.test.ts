import { beforeAll, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import type { ProductionPdfResult } from '@/modules/production'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'
import {
  guestCookie,
  orderModuleFor,
  placeInput,
  resetOrders,
  seedCart,
  seedUser,
} from './test-fixtures'

const database = testDatabase()
let module: Awaited<ReturnType<typeof orderModuleFor>>
let userId: number
let accessToken: string

beforeAll(async () => {
  await resetOrders(database.db)
  module = await orderModuleFor(database.db)
  userId = await seedUser(database.db, 'kunde@example.com')
  const place = async (cartId: number, input: Parameters<typeof placeInput>[1]) =>
    write(database.db, (trx) => module.orders.place(trx, placeInput(cartId, input)))
  // 1 and 2 belong to the guest, 3 to the account (placed with that browser's guest cookie too).
  await place(await seedCart(database.db), {})
  await place(await seedCart(database.db), { discountCents: 100 })
  await place(await seedCart(database.db, { userId }), { userId })
  await sql`UPDATE orders SET created_at = '2026-07-30T09:12:44.123Z' WHERE id = 1`.execute(
    database.db,
  )
  accessToken = (
    await database.db
      .selectFrom('orders')
      .select('access_token')
      .where('id', '=', 1)
      .executeTakeFirstOrThrow()
  ).access_token
})

const app = () => testApp(module.routes)
const asGuest = async (token = 'guest-a') => ({ Cookie: await guestCookie(token) })
const asCustomer = () => signedInHeaders({ userId: String(userId), roles: ['CUSTOMER'] })

const ORDER_ONE = {
  orderId: 1,
  createdAt: '2026-07-30T09:12:44.123Z',
  status: 'PENDING',
  paymentStatus: null,
  subtotal: 3980,
  shippingCost: 490,
  discountAmount: 0,
  total: 4470,
  items: [
    {
      orderItemId: 1,
      articleId: 1,
      variantId: 1,
      articleType: 'MUG',
      articleName: 'Tasse Klassik',
      variantName: 'Weiß/Blau',
      quantity: 2,
      price: 1490,
      promptPrice: 500,
      imageId: null,
    },
  ],
}

describe('GET /api/orders', () => {
  test("answers the caller's own orders, newest first, uncached", async () => {
    const response = await app().request('/api/orders', { headers: await asGuest() })
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(response.headers.getSetCookie()).toEqual([])
    const orders = (await jsonOf(response)) as { orderId: number }[]
    expect(orders.map((order) => order.orderId)).toEqual([2, 1])
    expect(orders[1]).toEqual(ORDER_ONE)

    const account = (await jsonOf(
      await app().request('/api/orders', { headers: await asCustomer() }),
    )) as { orderId: number }[]
    expect(account.map((order) => order.orderId)).toEqual([3])
    expect(await jsonOf(await app().request('/api/orders'))).toEqual([])
  })
})

describe('GET /api/orders/{orderId}', () => {
  test('answers an own order and 404 for a foreign, an unknown, or a malformed id', async () => {
    const own = await app().request('/api/orders/1', { headers: await asGuest() })
    expect(own.status).toBe(200)
    expect(own.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(own)).toEqual(ORDER_ONE)

    for (const [path, headers] of [
      ['/api/orders/3', await asGuest()],
      ['/api/orders/1', await asGuest('guest-b')],
      ['/api/orders/1', await asCustomer()],
      ['/api/orders/99', await asGuest()],
      ['/api/orders/abc', await asGuest()],
    ] as const) {
      const response = await app().request(path, { headers })
      expect(response.status).toBe(404)
      expect(response.headers.get('Cache-Control')).toBe('no-store')
      expect(await jsonOf(response)).toEqual({ message: 'Order not found', errors: {} })
    }
  })
})

describe('GET /api/order-lookup/{token}', () => {
  test('answers the order behind a token without any identity, and never the token itself', async () => {
    const response = await app().request(`/api/order-lookup/${accessToken}`)
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    const body = await response.text()
    expect(JSON.parse(body)).toEqual(ORDER_ONE)
    expect(body).not.toContain(accessToken)
  })

  test('a malformed, an unknown, and a missing token are the same 404', async () => {
    for (const path of [
      '/api/order-lookup/short',
      `/api/order-lookup/${'A'.repeat(43)}`,
      `/api/order-lookup/${accessToken}x`,
      '/api/order-lookup',
    ]) {
      const response = await app().request(path)
      expect(response.status).toBe(404)
      expect(response.headers.get('Cache-Control')).toBe('no-store')
      expect(await jsonOf(response)).toEqual({ message: 'Order not found', errors: {} })
    }
  })
})

describe('the admin production-PDF routes', () => {
  const pdf = {
    supplierId: 3,
    fileName: 'ORD-1.pdf',
    mediaType: 'application/pdf' as const,
    bytes: new TextEncoder().encode('%PDF-1.7'),
    sha256: 'irrelevant',
  }
  const adminApp = (result: ProductionPdfResult) => {
    const requested: number[] = []
    const generator = {
      generate: (orderId: number) => {
        requested.push(orderId)
        return Promise.resolve(result)
      },
    }
    return { app: testApp(module.adminPdfRoutes(generator)), requested }
  }
  const admin = () => signedInHeaders({ roles: ['ADMIN'] })

  test('lists the documents and downloads one as an attachment', async () => {
    const { app: pdfApp, requested } = adminApp({ kind: 'generated', documents: [pdf] })
    const list = await pdfApp.request('/api/admin/orders/1/production-pdfs', {
      headers: await admin(),
    })
    expect(list.status).toBe(200)
    expect(list.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(list)).toEqual([{ supplierId: 3, fileName: 'ORD-1.pdf' }])

    const download = await pdfApp.request('/api/admin/orders/1/production-pdfs/3', {
      headers: await admin(),
    })
    expect(download.status).toBe(200)
    expect(download.headers.get('Content-Type')).toBe('application/pdf')
    expect(download.headers.get('Content-Disposition')).toBe('attachment; filename="ORD-1.pdf"')
    expect(await download.text()).toBe('%PDF-1.7')

    const otherSupplier = await pdfApp.request('/api/admin/orders/1/production-pdfs/4', {
      headers: await admin(),
    })
    expect(otherSupplier.status).toBe(404)
    expect(requested).toEqual([1, 1, 1])
    const malformed = await pdfApp.request('/api/admin/orders/x/production-pdfs', {
      headers: await admin(),
    })
    expect(malformed.status).toBe(404)
    expect(requested).toHaveLength(3)
  })

  test('answers the generator failures with their status and code', async () => {
    const failures: [ProductionPdfResult, number, string | undefined][] = [
      [{ kind: 'orderNotFound' }, 404, undefined],
      [{ kind: 'failed', error: 'MISSING_IMAGE' }, 409, 'PRODUCTION_PDF_MISSING_IMAGE'],
      [{ kind: 'failed', error: 'UNREADABLE_IMAGE' }, 409, 'PRODUCTION_PDF_UNREADABLE_IMAGE'],
      [{ kind: 'failed', error: 'INVALID_SOURCE' }, 409, 'PRODUCTION_PDF_INVALID_SOURCE'],
      [{ kind: 'failed', error: 'RENDER_FAILURE' }, 500, 'PRODUCTION_PDF_RENDER_FAILURE'],
    ]
    for (const [result, status, code] of failures) {
      for (const path of [
        '/api/admin/orders/1/production-pdfs',
        '/api/admin/orders/1/production-pdfs/3',
      ]) {
        const response = await adminApp(result).app.request(path, { headers: await admin() })
        expect(response.status).toBe(status)
        expect(((await jsonOf(response)) as { code?: string }).code).toBe(code)
      }
    }
  })
})
