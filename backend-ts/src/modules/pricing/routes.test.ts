import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createPricingModule } from '@/modules/pricing'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'

const database = testDatabase()
const app = () => testApp(createPricingModule({ db: database.db }).routes)
const admin = () => signedInHeaders({ roles: ['ADMIN'] })

const send = async (method: string, path: string, body?: string) =>
  app().request(path, {
    method,
    headers: { ...(await admin()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body }),
  })

const STANDARD = { id: 1, name: 'Standard', percent: 19, description: null, isDefault: true }

beforeEach(async () => {
  await sql`TRUNCATE prices, value_added_taxes RESTART IDENTITY CASCADE`.execute(database.db)
  await sql`INSERT INTO value_added_taxes (name, percent, description, is_default)
    VALUES ('Standard', 19, NULL, TRUE)`.execute(database.db)
})

describe('POST /api/admin/prices/calculate', () => {
  test('answers the calculated, discounted price without storing it', async () => {
    const response = await send(
      'POST',
      '/api/admin/prices/calculate',
      JSON.stringify({
        purchaseVatId: 1,
        purchasePriceInputCents: 1_000,
        salesVatId: 1,
        salesTotalInputCents: 1_990,
        discountType: 'PERCENTAGE',
        discountValue: 20,
      }),
    )
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toMatchObject({
      id: null,
      purchaseCalculationMode: 'NET',
      purchaseVat: STANDARD,
      discount: { discountType: 'PERCENTAGE', discountValue: 20 },
      regularSalesTotal: { net: 1_672, tax: 318, gross: 1_990 },
      salesDiscount: { net: 334, tax: 64, gross: 398 },
      salesTotal: { net: 1_338, tax: 254, gross: 1_592 },
    })
    const { rows } = await sql<{
      count: number
    }>`SELECT count(*)::int AS count FROM prices`.execute(database.db)
    expect(rows[0]?.count).toBe(0)
  })

  test('an unknown VAT entry is a field error', async () => {
    const response = await send(
      'POST',
      '/api/admin/prices/calculate',
      '{"purchaseVatId":9,"salesVatId":1}',
    )
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { purchaseVatId: ['Purchase VAT not found'] },
    })
  })
})

describe('GET /api/admin/prices/default', () => {
  test('answers an unstored price on the default VAT, and 400 without any VAT', async () => {
    const response = await send('GET', '/api/admin/prices/default')
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toMatchObject({
      id: null,
      purchaseVat: STANDARD,
      discount: null,
    })
    await sql`DELETE FROM value_added_taxes`.execute(database.db)
    const none = await send('GET', '/api/admin/prices/default')
    expect(none.status).toBe(400)
    expect(await jsonOf(none)).toEqual({ message: 'No VAT is configured', errors: {} })
  })
})

describe('/api/admin/vat', () => {
  test('GET answers the list ordered by name', async () => {
    await send('POST', '/api/admin/vat', '{"name":"Reduced","percent":7}')
    const response = await send('GET', '/api/admin/vat')
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toEqual([
      { id: 2, name: 'Reduced', percent: 7, description: null, isDefault: false },
      STANDARD,
    ])
  })

  test('POST creates a normalized entry with a Location header; a duplicate name is 409', async () => {
    const created = await send(
      'POST',
      '/api/admin/vat',
      '{"name":" Reduced ","percent":7,"description":" Food ","isDefault":true}',
    )
    expect(created.status).toBe(201)
    expect(created.headers.get('Location')).toBe('/api/admin/vat/2')
    expect(await jsonOf(created)).toEqual({
      id: 2,
      name: 'Reduced',
      percent: 7,
      description: 'Food',
      isDefault: true,
    })
    const duplicate = await send('POST', '/api/admin/vat', '{"name":"Standard","percent":20}')
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({ message: 'VAT entry already exists', errors: {} })
  })

  test('GET by id answers the entry and 404 for an unknown id', async () => {
    const found = await send('GET', '/api/admin/vat/1')
    expect(found.status).toBe(200)
    expect(await jsonOf(found)).toEqual(STANDARD)
    const missing = await send('GET', '/api/admin/vat/999')
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'VAT not found', errors: {} })
  })

  test('PUT updates the entry and answers 404 for an unknown id', async () => {
    const body = '{"name":"Standard rate","percent":20,"isDefault":true}'
    const updated = await send('PUT', '/api/admin/vat/1', body)
    expect(updated.status).toBe(200)
    expect(await jsonOf(updated)).toEqual({ ...STANDARD, name: 'Standard rate', percent: 20 })
    expect((await send('PUT', '/api/admin/vat/999', body)).status).toBe(404)
  })

  test('DELETE answers 204, then 404, and 409 while a price references the entry', async () => {
    await sql`INSERT INTO value_added_taxes (name, percent, description, is_default)
      VALUES ('Reduced', 7, NULL, FALSE)`.execute(database.db)
    await sql`INSERT INTO prices (purchase_vat_id, purchase_calculation_mode, purchase_active_row,
        purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent, sales_vat_id,
        sales_calculation_mode, sales_active_row, sales_margin_input_cents, sales_margin_percent,
        sales_total_input_cents)
      VALUES (1, 'NET', 'COST', 0, 0, 0, 1, 'GROSS', 'TOTAL', 0, 0, 0)`.execute(database.db)
    const inUse = await send('DELETE', '/api/admin/vat/1')
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({ message: 'VAT is in use', errors: {} })
    const deleted = await send('DELETE', '/api/admin/vat/2')
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
    expect((await send('DELETE', '/api/admin/vat/2')).status).toBe(404)
  })
})
