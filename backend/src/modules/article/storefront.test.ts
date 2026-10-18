import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import type { Hono } from 'hono'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import {
  articleTestModule,
  grossPrice,
  insertCategory,
  insertSpodArticle,
  insertSpodDestination,
  insertSubcategory,
  insertSupplier,
  insertVat,
  resetArticles,
} from './test-fixtures'

/** The three anonymous reads share one visibility rule; every row a customer must not see is hidden. */

const database = testDatabase()
let app: Hono
beforeAll(async () => {
  app = testApp((await articleTestModule(database.db, testAuth.guestTokens)).article.routes)
})

let vat: number
let supplier: number
let destination: number
beforeEach(async () => {
  await resetArticles(database.db)
  vat = await insertVat(database.db)
  supplier = await insertSupplier(database.db, 'Supplier')
  destination = await insertSpodDestination(database.db, supplier)
})

const admin = async (method: string, path: string, body: unknown) =>
  app.request(path, {
    method,
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })

const createMug = async (
  name: string,
  categoryId: number,
  subcategoryId: number | null = null,
  discount: number | null = null,
) => {
  const response = await admin('POST', '/api/admin/articles/mugs', {
    name,
    descriptionShort: 'A mug',
    descriptionLong: 'A mug',
    active: true,
    categoryId,
    subcategoryId,
    supplierId: supplier,
    mugDetails: {
      heightMm: 95,
      diameterMm: 82,
      printTemplateWidthMm: 200,
      printTemplateHeightMm: 90,
    },
    mugVariants: [
      {
        name: 'White',
        insideColorCode: '#fff',
        outsideColorCode: '#fff',
        isDefault: true,
        active: true,
      },
      { name: 'Hidden', insideColorCode: '#000', outsideColorCode: '#000', active: false },
    ],
    price: grossPrice(vat, 1990, discount),
  })
  expect(response.status).toBe(201)
  return ((await jsonOf(response)) as { id: number }).id
}

const activateSpod = async (
  spodArticleId: string,
  categoryId: number,
  subcategoryId: number | null = null,
) => {
  const seeded = await insertSpodArticle(database.db, {
    destinationId: destination,
    supplierId: supplier,
    spodArticleId,
    colors: [
      { name: 'Black', sizes: ['M'], listing: ['black.webp'], mockup: 'black-mockup.webp' },
      { name: 'White', sizes: ['M'], listing: ['white.webp'] },
    ],
  })
  // The white colour has nothing for sale: its one variant is inactive.
  await database.db
    .updateTable('article_spod_variants')
    .set({ active: false })
    .where('id', '=', seeded.variantIds[1] ?? 0)
    .execute()
  const response = await admin('PUT', `/api/admin/articles/spod/${String(seeded.articleId)}`, {
    active: true,
    categoryId,
    subcategoryId,
    defaultVariantId: seeded.variantIds[0],
    price: grossPrice(vat, 1990, 20),
  })
  expect(response.status).toBe(200)
  return seeded
}

const read = async (path: string) => {
  const response = await app.request(path)
  expect(response.status).toBe(200)
  return jsonOf(response)
}

describe('the visibility matrix', () => {
  test('hides inactive articles, articles in inactive categories, and in inactive subcategories', async () => {
    const shop = await insertCategory(database.db, 'Shop')
    const closed = await insertCategory(database.db, 'Closed', false)
    const open = await insertSubcategory(database.db, shop, 'Open')
    const hidden = await insertSubcategory(database.db, shop, 'Hidden', false)
    const plain = await createMug('Plain', shop)
    const inOpen = await createMug('In open', shop, open)
    await createMug('In hidden', shop, hidden)
    await createMug('In closed', closed)
    const switchedOff = await createMug('Switched off', shop)
    await database.db
      .updateTable('article_mugs')
      .set({ active: false })
      .where('id', '=', switchedOff)
      .execute()
    const spod = await activateSpod('a-1', shop, open)
    await activateSpod('a-2', shop, hidden)

    expect(((await read('/api/articles/mugs')) as { id: number }[]).map((mug) => mug.id)).toEqual([
      plain,
      inOpen,
    ])
    expect(
      ((await read('/api/articles/spod')) as { id: number }[]).map((article) => article.id),
    ).toEqual([spod.articleId])
    expect(await read('/api/articles/categories')).toEqual([
      {
        id: shop,
        name: 'Shop',
        position: 1,
        subcategories: [{ id: open, name: 'Open', exampleImageFilename: null, position: 1 }],
      },
    ])
  })
})

describe('the storefront documents', () => {
  test('a mug: no supplier or active fields, active variants only, the price in cents', async () => {
    const shop = await insertCategory(database.db, 'Shop')
    const id = await createMug('Classic', shop)
    const [mug] = (await read('/api/articles/mugs')) as Record<string, unknown>[]
    expect(mug).toEqual({
      articleType: 'MUG',
      id,
      position: 1,
      name: 'Classic',
      descriptionShort: 'A mug',
      descriptionLong: 'A mug',
      categoryId: shop,
      subcategoryId: null,
      price: 1990,
      regularPrice: null,
      mugDetails: {
        heightMm: 95,
        diameterMm: 82,
        printTemplateWidthMm: 200,
        printTemplateHeightMm: 90,
        fillingQuantity: null,
        dishwasherSafe: false,
        documentFormatWidthMm: null,
        documentFormatHeightMm: null,
        documentFormatMarginBottomMm: null,
      },
      variants: [
        {
          id: expect.any(Number) as unknown,
          name: 'White',
          insideColorCode: '#fff',
          outsideColorCode: '#fff',
          isDefault: true,
          exampleImageFilename: null,
        },
      ],
    })
  })

  test('a SPOD article: the discounted price, sellable colours only, and no partner ids', async () => {
    const shop = await insertCategory(database.db, 'Shop')
    const { articleId, appearanceIds, variantIds } = await activateSpod('a-1', shop)
    const response = await app.request('/api/articles/spod')
    const text = await response.text()
    expect(JSON.parse(text)).toEqual([
      {
        articleType: 'SPOD',
        id: articleId,
        position: 1,
        name: 'Classic tee',
        descriptionShort: 'A tee',
        descriptionLong: 'A classic heavy cotton tee',
        categoryId: shop,
        subcategoryId: null,
        price: 1592,
        regularPrice: 1990,
        productTypeName: "Men's T-Shirt",
        sizeChartImageFilename: null,
        printFrame: { leftPct: 35.6, topPct: 18.9, widthPct: 28.4, heightPct: 28.4 },
        appearances: [
          {
            id: appearanceIds[0],
            colorName: 'Black',
            colorHex: '#101010',
            mockupImageFilename: 'black-mockup.webp',
            listingImageFilenames: ['black.webp'],
          },
        ],
        variants: [
          {
            id: variantIds[0],
            name: 'Black / M',
            appearanceId: appearanceIds[0],
            size: 'M',
            isDefault: true,
          },
        ],
      },
    ])
    expect(text).not.toContain('MEDIUM_FRONT')
    expect(text).not.toContain('812')
  })

  test('an empty catalog answers empty arrays', async () => {
    expect(await read('/api/articles/mugs')).toEqual([])
    expect(await read('/api/articles/spod')).toEqual([])
    expect(await read('/api/articles/categories')).toEqual([])
  })
})
