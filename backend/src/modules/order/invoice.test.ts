import { beforeAll, describe, expect, test } from 'bun:test'
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import { guestCookie, jsonOf, testApp } from '@test-support/http'
import { vatBreakdown } from './invoice'
import { orderModuleFor, placeInput, resetOrders, seedCart } from './test-fixtures'

const line = (vatPercent: number, priceCents: number) => ({
  articleName: 'Tasse',
  variantName: 'Weiß',
  quantity: 1,
  priceCents,
  promptPriceCents: 0,
  vatPercent,
})

describe('vatBreakdown', () => {
  test('one rate takes the whole total, shipping and discount included', () => {
    expect(
      vatBreakdown({
        lines: [{ ...line(19, 1490), quantity: 2, promptPriceCents: 500 }],
        shippingCostCents: 490,
        discountCents: 100,
      }),
    ).toEqual([{ percent: 19, grossCents: 4370, netCents: 3672, taxCents: 698 }])
  })

  test('two rates share shipping and discount by their goods, the last one the remainder', () => {
    const breakdown = vatBreakdown({
      lines: [line(7, 600), line(19, 1000), line(7, 400)],
      shippingCostCents: 490,
      discountCents: 101,
    })
    expect(breakdown).toEqual([
      { percent: 19, grossCents: 1194, netCents: 1003, taxCents: 191 },
      { percent: 7, grossCents: 1195, netCents: 1117, taxCents: 78 },
    ])
    expect(breakdown.reduce((sum, rate) => sum + rate.grossCents, 0)).toBe(2000 + 490 - 101)
  })
})

describe('GET /api/orders/{orderId}/invoice', () => {
  const database = testDatabase()
  let module: Awaited<ReturnType<typeof orderModuleFor>>
  const asGuest = async (token = 'guest-a') => ({ Cookie: await guestCookie(token) })

  beforeAll(async () => {
    await resetOrders(database.db)
    module = await orderModuleFor(database.db)
    for (const discountCents of [100, 0]) {
      const cartId = await seedCart(database.db)
      await write(database.db, (trx) =>
        module.orders.place(trx, placeInput(cartId, { discountCents })),
      )
    }
    await write(database.db, (trx) => module.orders.confirmFree(trx, 1))
  })

  test('downloads the invoice of an own paid order as a PDF attachment', async () => {
    const response = await testApp(module.routes).request('/api/orders/1/invoice', {
      headers: await asGuest(),
    })
    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Type')).toBe('application/pdf')
    expect(response.headers.get('Content-Disposition')).toBe(
      'attachment; filename="Rechnung-Voenix-Shop-1.pdf"',
    )
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    const document = await getDocument({ data: new Uint8Array(await response.arrayBuffer()) })
      .promise
    const content = await (await document.getPage(1)).getTextContent()
    const text = content.items
      .map((item) => ('str' in item ? item.str : ''))
      .join('|')
      .replace(/\s/gu, ' ')
    for (const expected of [
      'Rechnung',
      'Rechnungsnummer',
      'Tasse Klassik – Weiß/Blau',
      '19 %',
      '19,90 €',
      '-1,00 €',
      '43,70 €',
      '36,72 €',
      '6,98 €',
      'USt-IdNr. DE455554144',
    ]) {
      expect(text).toContain(expected)
    }
  })

  test('an unpaid, a foreign, an unknown, and a malformed order are the same 404', async () => {
    for (const [path, headers] of [
      ['/api/orders/2/invoice', await asGuest()],
      ['/api/orders/1/invoice', await asGuest('guest-b')],
      ['/api/orders/1/invoice', {}],
      ['/api/orders/99/invoice', await asGuest()],
      ['/api/orders/abc/invoice', await asGuest()],
    ] as const) {
      const response = await testApp(module.routes).request(path, { headers })
      expect(response.status).toBe(404)
      expect(await jsonOf(response)).toEqual({ message: 'Order not found', errors: {} })
    }
  })
})
