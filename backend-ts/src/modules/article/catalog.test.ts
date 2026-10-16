import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import type { Hono } from 'hono'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import { catalogKey, type ArticleCatalog, type CatalogVariant } from './index'
import {
  articleTestModule,
  grossPrice,
  insertCategory,
  insertSpodArticle,
  insertSpodDestination,
  insertSupplier,
  insertVat,
  resetArticles,
} from './test-fixtures'

const database = testDatabase()
let app: Hono
let catalog: ArticleCatalog
beforeAll(async () => {
  const { article } = await articleTestModule(database.db, testAuth.guestTokens)
  app = testApp(article.routes)
  catalog = article.articleCatalog
})

let vat: number
let category: number
let supplier: number
beforeEach(async () => {
  await resetArticles(database.db)
  vat = await insertVat(database.db)
  category = await insertCategory(database.db, 'Mugs')
  supplier = await insertSupplier(database.db, 'Porcelain Ltd')
})

interface CreatedMug {
  id: number
  mugVariants: { id: number }[]
}

const createMug = async (overrides: Record<string, unknown> = {}): Promise<CreatedMug> => {
  const response = await app.request('/api/admin/articles/mugs', {
    method: 'POST',
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    body: JSON.stringify({
      name: 'Classic mug',
      descriptionShort: 'A mug',
      descriptionLong: 'A mug',
      active: true,
      categoryId: category,
      supplierId: supplier,
      supplierArticleNumber: '4711',
      mugDetails: {
        heightMm: 95,
        diameterMm: 82,
        printTemplateWidthMm: 200,
        printTemplateHeightMm: 90,
        documentFormatWidthMm: 210,
        documentFormatHeightMm: 100,
        documentFormatMarginBottomMm: 5,
      },
      mugVariants: [
        {
          name: 'Red',
          insideColorCode: '#ff0000',
          outsideColorCode: '#00ff00',
          isDefault: true,
          active: true,
        },
      ],
      price: grossPrice(vat, 1490),
      ...overrides,
    }),
  })
  expect(response.status).toBe(201)
  return (await jsonOf(response)) as CreatedMug
}

const variantOf = (mug: CreatedMug) => ({
  articleId: mug.id,
  variantId: mug.mugVariants[0]?.id ?? 0,
})

const mugVariant = (overrides: Partial<CatalogVariant>): CatalogVariant => ({
  articleType: 'MUG',
  articleName: 'Classic mug',
  variantName: 'Red',
  purchasable: true,
  grossSalesPriceCents: 1490,
  supplierId: supplier,
  supplierArticleNumber: '4711',
  printTemplateWidthMm: 200,
  printTemplateHeightMm: 90,
  documentFormatWidthMm: 210,
  documentFormatHeightMm: 100,
  documentFormatMarginBottomMm: 5,
  outsideColorCode: '#00ff00',
  insideColorCode: '#ff0000',
  spodProduct: null,
  spodHotspot: null,
  ...overrides,
})

describe('articleCatalog.find', () => {
  test('answers each known pair whole; unknown and cross-article pairs are absent', async () => {
    const purchasable = await createMug()
    const inactive = await createMug({ active: false })
    const draft = await createMug({
      active: false,
      price: null,
      mugDetails: null,
      supplierArticleNumber: null,
    })
    const discounted = await createMug({ price: grossPrice(vat, 1990, 20) })
    const found = await catalog.find([
      variantOf(purchasable),
      variantOf(inactive),
      variantOf(draft),
      variantOf(discounted),
      { articleId: purchasable.id, variantId: 999_999 },
      { articleId: inactive.id, variantId: variantOf(purchasable).variantId },
    ])
    expect(found.size).toBe(4)
    expect(found.get(catalogKey(variantOf(purchasable)))).toEqual(mugVariant({}))
    expect(found.get(catalogKey(variantOf(inactive)))).toEqual(mugVariant({ purchasable: false }))
    expect(found.get(catalogKey(variantOf(draft)))).toEqual(
      mugVariant({
        purchasable: false,
        grossSalesPriceCents: null,
        supplierArticleNumber: null,
        printTemplateWidthMm: null,
        printTemplateHeightMm: null,
        documentFormatWidthMm: null,
        documentFormatHeightMm: null,
        documentFormatMarginBottomMm: null,
      }),
    )
    expect(found.get(catalogKey(variantOf(discounted)))?.grossSalesPriceCents).toBe(1592)
    expect((await catalog.find([])).size).toBe(0)
  })

  test('a SPOD variant answers its product triple and hotspot, and no mug data', async () => {
    const destination = await insertSpodDestination(database.db, supplier)
    const { articleId, variantIds } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
    })
    const reference = { articleId, variantId: variantIds[1] ?? 0 }
    const unmeasured = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      spodArticleId: 'a-2',
      framed: false,
    })
    const found = await catalog.find([
      reference,
      { articleId: unmeasured.articleId, variantId: unmeasured.variantIds[0] ?? 0 },
    ])
    expect(found.get(catalogKey(reference))).toEqual({
      articleType: 'SPOD',
      articleName: 'Classic tee',
      variantName: 'Black / L',
      purchasable: false,
      grossSalesPriceCents: null,
      supplierId: supplier,
      supplierArticleNumber: null,
      printTemplateWidthMm: null,
      printTemplateHeightMm: null,
      documentFormatWidthMm: null,
      documentFormatHeightMm: null,
      documentFormatMarginBottomMm: null,
      outsideColorCode: null,
      insideColorCode: null,
      spodProduct: { productTypeId: 812, appearanceId: 5, sizeId: 2 },
      spodHotspot: 'MEDIUM_FRONT',
    })
    expect(
      found.get(
        catalogKey({ articleId: unmeasured.articleId, variantId: unmeasured.variantIds[0] ?? 0 }),
      )?.spodHotspot,
    ).toBeNull()
  })
})

describe('articleCatalog.printFormats', () => {
  test('a mug answers its ratio, a SPOD article 1:1, an unknown id nothing', async () => {
    const wide = await createMug()
    const square = await createMug({ printAspectRatio: '1:1' })
    const destination = await insertSpodDestination(database.db, supplier)
    const { articleId } = await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
    })
    const formats = await catalog.printFormats([wide.id, square.id, articleId, 999_999])
    expect([...formats.entries()].sort(([a], [b]) => a - b)).toEqual([
      [wide.id, '16:9'],
      [square.id, '1:1'],
      [articleId, '1:1'],
    ])
    expect((await catalog.printFormats([])).size).toBe(0)
  })
})
