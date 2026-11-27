import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import type { Hono } from 'hono'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import { catalogKey, type ArticleCatalog, type SpodStock } from '../index'
import {
  articleTestModule,
  grossPrice,
  insertCategory,
  insertSpodArticle,
  insertSpodDestination,
  insertSupplier,
  insertVat,
  resetArticles,
} from '../test-fixtures'

/** The partner's stock decides what the shop shows and sells; `NULL` (never read) hides nothing. */

const database = testDatabase()
let app: Hono
let catalog: ArticleCatalog
let stock: SpodStock
beforeAll(async () => {
  const { article } = await articleTestModule(database.db, testAuth.guestTokens)
  app = testApp(article.routes)
  catalog = article.articleCatalog
  stock = article.spodStock
})

let vat: number
let supplier: number
let destination: number
let category: number
beforeEach(async () => {
  await resetArticles(database.db)
  vat = await insertVat(database.db)
  supplier = await insertSupplier(database.db, 'Supplier')
  destination = await insertSpodDestination(database.db, supplier)
  category = await insertCategory(database.db, 'Shop')
})

/** An active article with Black / M (the default) and Black / L, SKUs `<prefix>-M` and `<prefix>-L`. */
const activeArticle = async (
  spodArticleId: string,
  destinationId = destination,
  supplierId = supplier,
) => {
  const seeded = await insertSpodArticle(database.db, {
    destinationId,
    supplierId,
    spodArticleId,
    colors: [{ name: 'Black', sizes: ['M', 'L'], listing: ['b.webp'], mockup: 'b-mockup.webp' }],
  })
  for (const [index, size] of ['M', 'L'].entries()) {
    await database.db
      .updateTable('article_spod_variants')
      .set({ sku: `${spodArticleId}-${size}` })
      .where('id', '=', seeded.variantIds[index] ?? 0)
      .execute()
  }
  const response = await app.request(`/api/admin/articles/spod/${String(seeded.articleId)}`, {
    method: 'PUT',
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    body: JSON.stringify({
      active: true,
      categoryId: category,
      subcategoryId: null,
      defaultVariantId: seeded.variantIds[0],
      price: grossPrice(vat, 1990, null),
    }),
  })
  expect(response.status).toBe(200)
  return seeded
}

const stored = async () =>
  (
    await database.db
      .selectFrom('article_spod_variants')
      .select(['sku', 'spod_stock'])
      .orderBy('sku')
      .execute()
  ).map((row) => [row.sku, row.spod_stock])

const shop = async () =>
  (await jsonOf(await app.request('/api/articles/spod'))) as {
    id: number
    variants: { id: number; isDefault: boolean }[]
  }[]

describe('spodStock.record', () => {
  test('a complete listing stores 0 for a SKU it lacks, only on its own destination', async () => {
    await activeArticle('a')
    const other = await insertSupplier(database.db, 'Other')
    await activeArticle('b', await insertSpodDestination(database.db, other), other)
    expect(await stock.record(destination, 'PRODUCTION', new Map([['a-M', 7]]), true)).toBe(2)
    expect(await stored()).toEqual([
      ['a-L', 0],
      ['a-M', 7],
      ['b-L', null],
      ['b-M', null],
    ])
    // The same listing again changes nothing; another environment's listing touches nothing.
    expect(await stock.record(destination, 'PRODUCTION', new Map([['a-M', 7]]), true)).toBe(0)
    expect(await stock.record(destination, 'STAGING', new Map(), true)).toBe(0)
  })

  test('a partial listing touches only the SKUs it names', async () => {
    await activeArticle('a')
    await stock.record(
      destination,
      'PRODUCTION',
      new Map([
        ['a-M', 3],
        ['a-L', 4],
      ]),
      true,
    )
    expect(await stock.record(destination, 'PRODUCTION', new Map([['a-L', 0]]), false)).toBe(1)
    expect(await stored()).toEqual([
      ['a-L', 0],
      ['a-M', 3],
    ])
  })

  test('keysOf answers where to read the SPOD variants with a SKU', async () => {
    const { variantIds } = await activeArticle('a')
    expect(await stock.keysOf([...variantIds, 999_999])).toEqual(
      variantIds.map((variantId, index) => ({
        variantId,
        sku: index === 0 ? 'a-M' : 'a-L',
        destinationId: destination,
        environment: 'PRODUCTION',
      })),
    )
  })
})

describe('a sold-out variant', () => {
  test('is neither shown nor sold, and a sold-out default hands its role on', async () => {
    const { articleId, variantIds } = await activeArticle('a')
    const [m = 0, l = 0] = variantIds
    await stock.record(destination, 'PRODUCTION', new Map([['a-L', 5]]), true)
    expect(await shop()).toMatchObject([{ id: articleId, variants: [{ id: l, isDefault: true }] }])
    const found = await catalog.find([
      { articleId, variantId: m },
      { articleId, variantId: l },
    ])
    expect(found.get(catalogKey({ articleId, variantId: m }))?.purchasable).toBe(false)
    expect(found.get(catalogKey({ articleId, variantId: l }))?.purchasable).toBe(true)
  })

  test('an article with nothing in stock disappears, menu included, and comes back restocked', async () => {
    const { articleId } = await activeArticle('a')
    await stock.record(destination, 'PRODUCTION', new Map(), true)
    expect(await shop()).toEqual([])
    expect(await jsonOf(await app.request('/api/articles/categories'))).toEqual([])
    await stock.record(destination, 'PRODUCTION', new Map([['a-M', 1]]), false)
    expect(await shop()).toMatchObject([{ id: articleId, variants: [{ isDefault: true }] }])
  })
})
