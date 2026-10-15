import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import type { Hono } from 'hono'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import {
  articleTestModule,
  grossPrice,
  insertCategory,
  insertSubcategory,
  insertSupplier,
  insertVat,
  pngBytes,
  resetArticles,
} from '../test-fixtures'

const database = testDatabase()
let app: Hono
beforeAll(async () => {
  app = testApp((await articleTestModule(database.db, testAuth.guestTokens)).article.routes)
})

const MUGS = '/api/admin/articles/mugs'
let vat: number
let category: number
let supplier: number
beforeEach(async () => {
  await resetArticles(database.db)
  vat = await insertVat(database.db)
  category = await insertCategory(database.db, 'Mugs')
  supplier = await insertSupplier(database.db, 'Porcelain Ltd')
})

const send = async (method: string, path: string, body?: unknown) =>
  app.request(path, {
    method,
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })

const details = {
  heightMm: 95,
  diameterMm: 82,
  printTemplateWidthMm: 200,
  printTemplateHeightMm: 90,
  dishwasherSafe: true,
}
const mugBody = (overrides: Record<string, unknown> = {}) => ({
  name: 'Classic mug',
  descriptionShort: 'A mug',
  descriptionLong: 'A classic white mug',
  active: true,
  categoryId: category,
  supplierId: supplier,
  supplierArticleNumber: '4711',
  mugDetails: details,
  mugVariants: [
    {
      name: 'White',
      insideColorCode: '#ffffff',
      outsideColorCode: '#eeeeee',
      isDefault: true,
      active: true,
    },
  ],
  price: grossPrice(vat, 1490),
  ...overrides,
})

const createMug = async (overrides: Record<string, unknown> = {}) => {
  const created = await send('POST', MUGS, mugBody(overrides))
  expect(created.status).toBe(201)
  return (await jsonOf(created)) as { id: number; mugVariants: { id: number }[] }
}

describe('POST /api/admin/articles/mugs', () => {
  test('creates the mug with its variants and price, answers 201 with a Location header', async () => {
    const created = await send('POST', MUGS, mugBody())
    expect(created.status).toBe(201)
    const mug = (await jsonOf(created)) as Record<string, unknown> & {
      id: number
      mugVariants: { id: number }[]
      price: { id: number; salesTotal: { gross: number } }
    }
    expect(created.headers.get('Location')).toBe(`${MUGS}/${String(mug.id)}`)
    expect(mug).toMatchObject({
      position: 1,
      name: 'Classic mug',
      printAspectRatio: '16:9',
      mugDetails: { ...details, fillingQuantity: null, documentFormatWidthMm: null },
      supplierArticleName: null,
    })
    expect(mug.mugVariants).toHaveLength(1)
    expect(mug.price.salesTotal.gross).toBe(1490)
    expect(Object.keys(mug)).not.toContain('priceId')
    expect(Object.keys(mug)).not.toContain('articleType')
  })

  test('reports the references and the price rule the write decides as field errors', async () => {
    const errorOf = async (overrides: Record<string, unknown>) =>
      jsonOf(await send('POST', MUGS, mugBody(overrides)))
    expect(await errorOf({ categoryId: 999 })).toEqual({
      message: 'Validation failed',
      errors: { categoryId: ['Article category does not exist'] },
    })
    const other = await insertCategory(database.db, 'Other')
    const foreign = await insertSubcategory(database.db, other, 'Foreign')
    expect(await errorOf({ subcategoryId: foreign })).toEqual({
      message: 'Validation failed',
      errors: { subcategoryId: ['Article subcategory does not exist in this article category'] },
    })
    expect(await errorOf({ supplierId: 999 })).toEqual({
      message: 'Validation failed',
      errors: { supplierId: ['Supplier does not exist'] },
    })
    expect(await errorOf({ price: null })).toEqual({
      message: 'Validation failed',
      errors: { price: ['An active article requires a price'] },
    })
    expect(await errorOf({ price: grossPrice(999, 100) })).toEqual({
      message: 'Validation failed',
      errors: {
        'price.purchaseVatId': ['Purchase VAT not found'],
        'price.salesVatId': ['Sales VAT not found'],
      },
    })
  })
})

describe('GET /api/admin/articles/mugs', () => {
  test('answers the overview rows with the names of what a mug references', async () => {
    const subcategory = await insertSubcategory(database.db, category, 'Classic')
    const mug = await createMug({ subcategoryId: subcategory })
    const draft = await createMug({
      active: false,
      categoryId: null,
      supplierId: null,
      price: null,
      mugVariants: [],
    })
    expect(await jsonOf(await send('GET', MUGS))).toEqual([
      {
        id: mug.id,
        position: 1,
        name: 'Classic mug',
        active: true,
        categoryId: category,
        categoryName: 'Mugs',
        subcategoryId: subcategory,
        subcategoryName: 'Classic',
        supplierId: supplier,
        supplierName: 'Porcelain Ltd',
        variantCount: 1,
        exampleImageFilename: null,
      },
      {
        id: draft.id,
        position: 2,
        name: 'Classic mug',
        active: false,
        categoryId: null,
        categoryName: null,
        subcategoryId: null,
        subcategoryName: null,
        supplierId: null,
        supplierName: null,
        variantCount: 0,
        exampleImageFilename: null,
      },
    ])
  })
})

describe('GET, PUT, DELETE /api/admin/articles/mugs/:id', () => {
  test('reads one mug; unknown is 404, malformed 400', async () => {
    const mug = await createMug()
    const read = await send('GET', `${MUGS}/${String(mug.id)}`)
    expect(read.status).toBe(200)
    expect(await jsonOf(read)).toMatchObject({ id: mug.id, price: { salesTotal: { gross: 1490 } } })
    expect(await jsonOf(await send('GET', `${MUGS}/999`))).toEqual({
      message: 'Article not found',
      errors: {},
    })
    expect(await jsonOf(await send('GET', `${MUGS}/x`))).toEqual({
      message: 'Invalid article id',
      errors: {},
    })
  })

  test('updates in place: an omitted price keeps the row, a variant of another article is refused', async () => {
    const mug = await createMug()
    const other = await createMug()
    const updated = await send(
      'PUT',
      `${MUGS}/${String(mug.id)}`,
      mugBody({ name: 'Renamed', price: null, printAspectRatio: '1:1' }),
    )
    expect(updated.status).toBe(200)
    expect(await jsonOf(updated)).toMatchObject({
      name: 'Renamed',
      printAspectRatio: '1:1',
      price: { salesTotal: { gross: 1490 } },
    })
    const foreign = await send(
      'PUT',
      `${MUGS}/${String(mug.id)}`,
      mugBody({
        mugVariants: [
          {
            id: other.mugVariants[0]?.id,
            name: 'White',
            insideColorCode: '#fff',
            outsideColorCode: '#fff',
            isDefault: true,
            active: true,
          },
        ],
      }),
    )
    expect(await jsonOf(foreign)).toEqual({
      message: 'Validation failed',
      errors: { mugVariants: ['One or more variants do not belong to this article'] },
    })
    expect((await send('PUT', `${MUGS}/999`, mugBody())).status).toBe(404)
  })

  test('DELETE answers 204 and then 404', async () => {
    const mug = await createMug()
    const deleted = await send('DELETE', `${MUGS}/${String(mug.id)}`)
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
    expect((await send('DELETE', `${MUGS}/${String(mug.id)}`)).status).toBe(404)
  })
})

describe('PUT /api/admin/articles/mugs/order', () => {
  test('answers the complete new order as list rows; an unknown id is 404', async () => {
    const first = await createMug()
    const second = await createMug()
    const moved = await send('PUT', `${MUGS}/order`, { sourceId: second.id, targetId: first.id })
    expect(moved.status).toBe(200)
    expect(
      ((await jsonOf(moved)) as { id: number; position: number; supplierName: string }[]).map(
        (row) => [row.id, row.position, row.supplierName],
      ),
    ).toEqual([
      [second.id, 1, 'Porcelain Ltd'],
      [first.id, 2, 'Porcelain Ltd'],
    ])
    expect(
      await jsonOf(await send('PUT', `${MUGS}/order`, { sourceId: 999, targetId: first.id })),
    ).toEqual({
      message: 'Article not found',
      errors: {},
    })
  })

  test('a gapped sequence answers the retryable 409', async () => {
    const first = await createMug()
    const second = await createMug()
    await database.db
      .updateTable('article_mugs')
      .set({ position: 7 })
      .where('id', '=', second.id)
      .execute()
    const refused = await send('PUT', `${MUGS}/order`, { sourceId: second.id, targetId: first.id })
    expect(refused.status).toBe(409)
    expect(await jsonOf(refused)).toEqual({
      message: 'Article order changed concurrently, please retry',
      errors: {},
    })
  })
})

describe('POST /api/admin/articles/mugs/variant-example-images', () => {
  test('stores the upload and answers 201 with its name; a missing part is a file error', async () => {
    const form = new FormData()
    form.append('file', new Blob([await pngBytes()], { type: 'image/png' }), 'variant.png')
    const stored = await app.request(`${MUGS}/variant-example-images`, {
      method: 'POST',
      headers: await signedInHeaders(),
      body: form,
    })
    expect(stored.status).toBe(201)
    expect(((await jsonOf(stored)) as { filename: string }).filename).toMatch(/\.webp$/)
    const missing = await app.request(`${MUGS}/variant-example-images`, {
      method: 'POST',
      headers: await signedInHeaders(),
      body: new FormData(),
    })
    expect(await jsonOf(missing)).toEqual({
      message: 'Validation failed',
      errors: { file: ['An example image file part is required'] },
    })
  })
})
