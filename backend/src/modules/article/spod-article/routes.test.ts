import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import type { Hono } from 'hono'
import type { ExampleImages } from '@/modules/image'
import { createLogger } from '@/platform/logging'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import { pngBytes } from '@test-support/values'
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

const database = testDatabase()
let app: Hono
let publicRoot: string
let folder: (name: string) => ExampleImages
beforeAll(async () => {
  const fixture = await articleTestModule(database.db, testAuth.guestTokens)
  app = testApp(fixture.article.routes)
  publicRoot = fixture.publicRoot
  folder = (name) => fixture.images.exampleImages(name, createLogger('article-test'))
})

const SPOD = '/api/admin/articles/spod'
let vat: number
let category: number
let supplier: number
let destination: number
beforeEach(async () => {
  await resetArticles(database.db)
  vat = await insertVat(database.db)
  category = await insertCategory(database.db, 'Shirts')
  supplier = await insertSupplier(database.db, 'Shirt supplier')
  destination = await insertSpodDestination(database.db, supplier)
})

const send = async (method: string, path: string, body?: unknown) =>
  app.request(path, {
    method,
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })

const store = async (name: string) => {
  const stored = await folder(name).store({ bytes: await pngBytes(), contentType: 'image/png' })
  if (stored.kind !== 'ok') throw new Error('The picture was not stored')
  return stored.value.filename
}

describe('GET /api/admin/articles/spod', () => {
  test('lists the rows with the sync state and the picture of the default colour', async () => {
    const { articleId } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      colors: [{ name: 'Black', sizes: ['M', 'L'], listing: ['first.webp', 'second.webp'] }],
    })
    expect(await jsonOf(await send('GET', SPOD))).toEqual([
      {
        id: articleId,
        position: 1,
        name: 'Classic tee',
        active: false,
        categoryId: null,
        categoryName: null,
        subcategoryId: null,
        subcategoryName: null,
        supplierId: supplier,
        supplierName: 'Shirt supplier',
        variantCount: 2,
        exampleImageFilename: 'first.webp',
        syncedAt: '2026-08-24T09:15:00.000Z',
        missingAtSpreadconnect: false,
      },
    ])
  })
})

describe('GET and PUT /api/admin/articles/spod/:id', () => {
  test('reads the article with colours, variants, frame, and sync block; 404 and 400 otherwise', async () => {
    const { articleId, appearanceIds, variantIds } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      colors: [{ name: 'Black', sizes: ['M'], listing: ['first.webp'], mockup: 'mockup.webp' }],
    })
    expect(await jsonOf(await send('GET', `${SPOD}/${String(articleId)}`))).toEqual({
      id: articleId,
      position: 1,
      name: 'Classic tee',
      descriptionShort: 'A tee',
      descriptionLong: 'A classic heavy cotton tee',
      active: false,
      categoryId: null,
      subcategoryId: null,
      supplierId: supplier,
      productTypeName: "Men's T-Shirt",
      sizeChartImageFilename: null,
      sizeMeasurements: [],
      printFrame: { leftPct: 35.6, topPct: 18.9, widthPct: 28.4, heightPct: 28.4 },
      hotspot: 'MEDIUM_FRONT',
      appearances: [
        {
          id: appearanceIds[0],
          colorName: 'Black',
          colorHex: '#101010',
          position: 1,
          mockupImageFilename: 'mockup.webp',
          listingImageFilenames: ['first.webp'],
        },
      ],
      spodVariants: [
        {
          id: variantIds[0],
          name: 'Black / M',
          appearanceId: appearanceIds[0],
          sizeLabel: 'M',
          spodProductTypeId: 812,
          spodAppearanceId: 5,
          spodSizeId: 1,
          spodVariantId: `v-${String(variantIds[0])}`,
          sku: null,
          isDefault: true,
          active: true,
        },
      ],
      price: null,
      sync: {
        spodArticleId: 'a-1',
        environment: 'PRODUCTION',
        syncedAt: '2026-08-24T09:15:00.000Z',
        missingSince: null,
      },
    })
    expect(await jsonOf(await send('GET', `${SPOD}/999`))).toEqual({
      message: 'Article not found',
      errors: {},
    })
    expect(await jsonOf(await send('GET', `${SPOD}/x`))).toEqual({
      message: 'Invalid article id',
      errors: {},
    })
  })

  test('writes the shop-owned half: activation, category, default variant, price', async () => {
    const { articleId, variantIds } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
    })
    const updated = await send('PUT', `${SPOD}/${String(articleId)}`, {
      active: true,
      categoryId: category,
      defaultVariantId: variantIds[1],
      price: grossPrice(vat, 1990),
    })
    expect(updated.status).toBe(200)
    const article = (await jsonOf(updated)) as {
      active: boolean
      name: string
      spodVariants: { isDefault: boolean }[]
      price: { id: number; salesTotal: { gross: number } }
      printFrame: unknown
    }
    expect(article.active).toBe(true)
    expect(article.name).toBe('Classic tee')
    expect(article.spodVariants.map((variant) => variant.isDefault)).toEqual([false, true])
    expect(article.price.salesTotal.gross).toBe(1990)
    expect(article.printFrame).toEqual({
      leftPct: 35.6,
      topPct: 18.9,
      widthPct: 28.4,
      heightPct: 28.4,
    })
    const kept = await send('PUT', `${SPOD}/${String(articleId)}`, {
      active: true,
      categoryId: category,
      defaultVariantId: variantIds[0],
    })
    expect(((await jsonOf(kept)) as { price: { id: number } }).price.id).toBe(article.price.id)
    expect((await send('PUT', `${SPOD}/999`, {})).status).toBe(404)
  })

  test('refuses the four activations the stored row decides', async () => {
    const framed = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
    })
    const unframed = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      spodArticleId: 'a-2',
      framed: false,
    })
    const errorsOf = async (id: number, body: unknown) =>
      ((await jsonOf(await send('PUT', `${SPOD}/${String(id)}`, body))) as { errors: unknown })
        .errors
    const activate = (variantId: number | undefined) => ({
      active: true,
      categoryId: category,
      defaultVariantId: variantId,
      price: grossPrice(vat, 1990),
    })
    expect(
      await errorsOf(framed.articleId, { ...activate(framed.variantIds[0]), price: null }),
    ).toEqual({
      price: ['An active article requires a price'],
    })
    expect(await errorsOf(framed.articleId, activate(unframed.variantIds[0]))).toEqual({
      defaultVariantId: ['The default variant is not an active variant of this article'],
    })
    expect(await errorsOf(unframed.articleId, activate(unframed.variantIds[0]))).toEqual({
      active: ['An article without a measured print frame cannot be activated'],
    })
    await database.db
      .updateTable('article_spod_articles')
      .set({ spod_missing_since: new Date() })
      .where('id', '=', framed.articleId)
      .execute()
    expect(await errorsOf(framed.articleId, activate(framed.variantIds[0]))).toEqual({
      active: ['An article that is missing at Spreadconnect cannot be activated'],
    })
    expect(await errorsOf(framed.articleId, { categoryId: 999 })).toEqual({
      categoryId: ['Article category does not exist'],
    })
  })
})

describe('the size measurements of GET /api/admin/articles/spod/:id', () => {
  test('cover every size of the variants, labelled by the variant with the lowest id', async () => {
    const { articleId, variantIds } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      colors: [
        { name: 'Black', sizes: ['M', 'L'] },
        { name: 'White', sizes: ['Medium'] },
      ],
      sizeMeasurements: [
        { sizeId: 1, name: 'A', valueMm: 720.5 },
        { sizeId: 2, name: 'B', valueMm: 520 },
        { sizeId: 2, name: 'A', valueMm: 740 },
        { sizeId: 9, name: 'A', valueMm: 999 },
      ],
    })
    // An inactive variant's size is still the admin's to see.
    await database.db
      .updateTable('article_spod_variants')
      .set({ active: false })
      .where('id', '=', variantIds[1] ?? 0)
      .execute()
    const article = (await jsonOf(await send('GET', `${SPOD}/${String(articleId)}`))) as {
      sizeMeasurements: { size: string; measurements: unknown[] }[]
    }
    expect(article.sizeMeasurements.toSorted((a, b) => a.size.localeCompare(b.size))).toEqual([
      {
        size: 'L',
        measurements: [
          { name: 'A', valueMm: 740 },
          { name: 'B', valueMm: 520 },
        ],
      },
      { size: 'M', measurements: [{ name: 'A', valueMm: 720.5 }] },
    ])
  })
})

describe('DELETE /api/admin/articles/spod/:id', () => {
  test('removes the article with its colours, variants, measurements, price, and all three kinds of file', async () => {
    const listing = await store('articles/spod/listing-images')
    const mockup = await store('articles/spod/mockups')
    const sizeChart = await store('articles/spod/size-charts')
    const { articleId, variantIds } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      sizeChart,
      colors: [{ name: 'Black', sizes: ['M'], listing: [listing], mockup }],
      sizeMeasurements: [{ sizeId: 1, name: 'A', valueMm: 700 }],
    })
    await send('PUT', `${SPOD}/${String(articleId)}`, {
      defaultVariantId: variantIds[0],
      price: grossPrice(vat, 1990),
    })
    const deleted = await send('DELETE', `${SPOD}/${String(articleId)}`)
    expect(deleted.status).toBe(204)
    for (const [name, file] of [
      ['listing-images', listing],
      ['mockups', mockup],
      ['size-charts', sizeChart],
    ] as const) {
      expect(existsSync(join(publicRoot, 'articles/spod', name, file))).toBe(false)
    }
    expect(await database.db.selectFrom('prices').select('id').execute()).toEqual([])
    expect(
      await database.db.selectFrom('article_spod_size_measurements').selectAll().execute(),
    ).toEqual([])
    expect((await send('DELETE', `${SPOD}/${String(articleId)}`)).status).toBe(404)
  })
})

describe('PUT /api/admin/articles/spod/order', () => {
  test('answers the new order, 404 for an unknown id, and 409 for a gapped sequence', async () => {
    const first = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
    })
    const second = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      spodArticleId: 'a-2',
    })
    const moved = await send('PUT', `${SPOD}/order`, {
      sourceId: second.articleId,
      targetId: first.articleId,
    })
    expect(((await jsonOf(moved)) as { id: number }[]).map((row) => row.id)).toEqual([
      second.articleId,
      first.articleId,
    ])
    expect(
      (await send('PUT', `${SPOD}/order`, { sourceId: 999, targetId: first.articleId })).status,
    ).toBe(404)
    await database.db
      .updateTable('article_spod_articles')
      .set({ position: 9 })
      .where('id', '=', first.articleId)
      .execute()
    const refused = await send('PUT', `${SPOD}/order`, {
      sourceId: first.articleId,
      targetId: second.articleId,
    })
    expect(await jsonOf(refused)).toEqual({
      message: 'Article order changed concurrently, please retry',
      errors: {},
    })
  })

  test('there is no create route and the old t-shirt paths are gone', async () => {
    expect((await send('POST', SPOD, {})).status).toBe(404)
    expect((await send('GET', '/api/admin/articles/tshirts')).status).toBe(404)
  })
})
