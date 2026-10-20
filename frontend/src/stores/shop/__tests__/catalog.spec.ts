import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  useCatalogStore,
  variantPhotoFilename,
  variantPhotoFilenames,
  type MugDto,
  type SpodArticleDto,
} from '@/stores/shop/catalog'
import {
  createMugVariant,
  createShopMug as makeMug,
  createShopTee as makeTee,
  createSpodAppearance,
} from '@/testing/shopCatalog'
import { resetApiClientForTests } from '@/lib/api'

/** The document of `GET /api/articles/mugs`, verbatim from `docs/dev/backend/packages/article-package.md`. */
const PUBLIC_MUG_RESPONSE = [
  {
    articleType: 'MUG',
    id: 12,
    position: 1,
    name: 'Classic mug',
    descriptionShort: 'A mug',
    descriptionLong: 'A classic white mug',
    categoryId: 7,
    subcategoryId: 42,
    price: 1490,
    regularPrice: null,
    mugDetails: {
      heightMm: 95,
      diameterMm: 82,
      printTemplateWidthMm: 200,
      printTemplateHeightMm: 90,
      fillingQuantity: '325ml',
      dishwasherSafe: true,
      documentFormatWidthMm: 200,
      documentFormatHeightMm: 90,
      documentFormatMarginBottomMm: null,
    },
    variants: [
      {
        id: 34,
        name: 'White',
        insideColorCode: '#ffffff',
        outsideColorCode: '#ffffff',
        isDefault: true,
        exampleImageFilename: '0f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d.webp',
      },
    ],
  },
] satisfies MugDto[]

/** The document of `GET /api/articles/spod`, from the same guide. */
const PUBLIC_SPOD_RESPONSE = [
  {
    articleType: 'SPOD',
    id: 31,
    position: 1,
    name: 'Classic tee',
    descriptionShort: 'A tee',
    descriptionLong: 'A classic heavy cotton tee',
    categoryId: 8,
    subcategoryId: 51,
    price: 1592,
    regularPrice: 1990,
    productTypeName: "Men's Tee",
    sizeChartImageFilename: null,
    sizeMeasurements: [],
    printFrame: { leftPct: 35.6, topPct: 18.9, widthPct: 28.4, heightPct: 28.4 },
    appearances: [
      {
        id: 3,
        colorName: 'Black',
        colorHex: '#101010',
        mockupImageFilename: '7a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d.webp',
        listingImageFilenames: ['8b1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d.webp'],
      },
    ],
    variants: [{ id: 88, name: 'Black / M', appearanceId: 3, size: 'M', isDefault: true }],
  },
] satisfies SpodArticleDto[]

/** Answers each catalog route with its own body, so a merged read can be told apart. */
function omitArticleType<T extends { articleType: string }>(row: T): Omit<T, 'articleType'> {
  const copy: Partial<T> = { ...row }
  delete copy.articleType
  return copy as Omit<T, 'articleType'>
}

function stubCatalogFetch(
  bodies: Record<string, unknown> = {
    '/api/articles/mugs': PUBLIC_MUG_RESPONSE,
    '/api/articles/spod': PUBLIC_SPOD_RESPONSE,
  },
  init: ResponseInit = {},
) {
  // A fresh Response per call: a body may only be read once.
  const fetchMock = vi.fn(
    async (input: string) =>
      new Response(JSON.stringify(bodies[input] ?? []), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        ...init,
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('shop catalog store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('reads both storefront routes in parallel and merges them into one union list', async () => {
    const store = useCatalogStore()
    const fetchMock = stubCatalogFetch()

    await store.fetchArticles()

    expect(fetchMock).toHaveBeenCalledWith('/api/articles/mugs')
    expect(fetchMock).toHaveBeenCalledWith('/api/articles/spod')
    // Both requests are in flight before either answer is awaited.
    expect(fetchMock.mock.invocationCallOrder[1]! - fetchMock.mock.invocationCallOrder[0]!).toBe(1)
    expect(store.articles).toEqual([...PUBLIC_MUG_RESPONSE, ...PUBLIC_SPOD_RESPONSE])
    expect(store.mugs).toEqual(PUBLIC_MUG_RESPONSE)
    expect(store.spodArticles).toEqual(PUBLIC_SPOD_RESPONSE)
    expect(store.error).toBeNull()
  })

  it('stamps the discriminator from the route a row came from', async () => {
    const store = useCatalogStore()
    const mugWithoutType = omitArticleType(PUBLIC_MUG_RESPONSE[0]!)
    const spodWithoutType = omitArticleType(PUBLIC_SPOD_RESPONSE[0]!)
    stubCatalogFetch({
      '/api/articles/mugs': [mugWithoutType],
      '/api/articles/spod': [spodWithoutType],
    })

    await store.fetchArticles()

    expect(store.articles.map((article) => article.articleType)).toEqual(['MUG', 'SPOD'])
  })

  it('answers an article lookup by id', async () => {
    const store = useCatalogStore()
    stubCatalogFetch()

    await store.fetchArticles()

    expect(store.getArticleById(12)).toEqual(PUBLIC_MUG_RESPONSE[0])
    expect(store.getArticleById(31)).toEqual(PUBLIC_SPOD_RESPONSE[0])
    expect(store.getArticleById(999)).toBeUndefined()
  })

  it('keeps a zero price, because it is a real price and not a missing one', async () => {
    const store = useCatalogStore()
    stubCatalogFetch({ '/api/articles/mugs': [makeMug({ id: 5, price: 0 })] })

    await store.fetchArticles()

    expect(store.getArticleById(5)?.price).toBe(0)
    expect(store.getDisplayArticles(null).map((article) => article.id)).toEqual([5])
  })

  it('fails the whole read when one of the two routes fails', async () => {
    const store = useCatalogStore()
    const fetchMock = vi.fn(async (input: string) =>
      input === '/api/articles/spod'
        ? new Response(JSON.stringify({ message: 'Internal server error' }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' },
          })
        : new Response(JSON.stringify(PUBLIC_MUG_RESPONSE), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await store.fetchArticles()

    expect(store.articles).toEqual([])
    expect(store.error).toBe('Internal server error')
  })

  it('narrows the display list by article type when one is asked for', () => {
    const store = useCatalogStore()
    store.articles = [
      makeMug({ id: 10, position: 1, categoryId: 7 }),
      makeTee({ id: 20, position: 2, categoryId: 7 }),
      makeTee({ id: 30, position: 3, categoryId: 8 }),
    ]

    expect(store.getDisplayArticles(null).map((article) => article.id)).toEqual([10, 20, 30])
    expect(store.getDisplayArticles(null, null, 'MUG').map((article) => article.id)).toEqual([10])
    expect(store.getDisplayArticles(null, null, 'SPOD').map((article) => article.id)).toEqual([
      20, 30,
    ])
    expect(store.getDisplayArticles(7, null, 'SPOD').map((article) => article.id)).toEqual([20])
  })

  it('orders an unfiltered display list by position and then ID without reordering the source', () => {
    const store = useCatalogStore()
    store.articles = [
      makeMug({ id: 30, position: 2 }),
      makeMug({ id: 20, position: 1 }),
      makeMug({ id: 10, position: 1 }),
    ]
    const sourceOrder = store.articles.map((article) => article.id)

    const displayedArticles = store.getDisplayArticles(null)

    expect(displayedArticles.map((article) => article.id)).toEqual([10, 20, 30])
    expect(displayedArticles).not.toBe(store.articles)
    expect(store.articles.map((article) => article.id)).toEqual(sourceOrder)
  })

  it('filters by category before ordering by name and then ID, ignoring position', () => {
    const store = useCatalogStore()
    store.articles = [
      makeMug({ id: 30, position: 1, name: 'Zulu', categoryId: 10 }),
      makeMug({ id: 20, position: 4, name: 'Alpha', categoryId: 10 }),
      makeMug({ id: 10, position: 3, name: 'Alpha', categoryId: 10 }),
      makeMug({ id: 40, position: 2, name: 'Bravo', categoryId: 20 }),
    ]
    const sourceOrder = store.articles.map((article) => article.id)

    const displayedArticles = store.getDisplayArticles(10)

    expect(displayedArticles.map((article) => article.id)).toEqual([10, 20, 30])
    expect(store.articles.map((article) => article.id)).toEqual(sourceOrder)
  })

  it('filters by subcategory before ordering by name and then ID, even without a category filter', () => {
    const store = useCatalogStore()
    store.articles = [
      makeMug({ id: 30, position: 1, name: 'Zulu', categoryId: 10, subcategoryId: 100 }),
      makeMug({ id: 20, position: 4, name: 'Alpha', categoryId: 10, subcategoryId: 100 }),
      makeTee({ id: 10, position: 3, name: 'Alpha', categoryId: 10, subcategoryId: 100 }),
      makeMug({ id: 40, position: 2, name: 'Bravo', categoryId: 10, subcategoryId: 200 }),
    ]
    const sourceOrder = store.articles.map((article) => article.id)

    expect(store.getDisplayArticles(null, 100).map((article) => article.id)).toEqual([10, 20, 30])
    expect(store.getDisplayArticles(10, 100).map((article) => article.id)).toEqual([10, 20, 30])
    expect(store.getDisplayArticles(20, 100)).toEqual([])
    expect(store.articles.map((article) => article.id)).toEqual(sourceOrder)
  })

  it('restores position order after filter switching and places a later article at its saved rank', () => {
    const store = useCatalogStore()
    store.articles = [
      makeMug({ id: 30, position: 3, name: 'Alpha', categoryId: 10, subcategoryId: 100 }),
      makeMug({ id: 10, position: 1, name: 'Zulu', categoryId: 10, subcategoryId: 100 }),
    ]

    expect(store.getDisplayArticles(null).map((article) => article.id)).toEqual([10, 30])
    expect(store.getDisplayArticles(10).map((article) => article.id)).toEqual([30, 10])
    expect(store.getDisplayArticles(null, 100).map((article) => article.id)).toEqual([30, 10])
    expect(store.getDisplayArticles(null).map((article) => article.id)).toEqual([10, 30])

    store.articles = [
      ...store.articles,
      makeTee({ id: 20, position: 2, name: 'Bravo', categoryId: 20, subcategoryId: 200 }),
    ]

    expect(store.getDisplayArticles(null).map((article) => article.id)).toEqual([10, 20, 30])
    expect(store.articles.map((article) => article.id)).toEqual([30, 10, 20])
  })

  it('upserts an article without duplicating the id it already holds', () => {
    const store = useCatalogStore()

    store.upsertArticle(makeMug({ id: 7, name: 'First' }))
    store.upsertArticle(makeMug({ id: 7, name: 'Second' }))

    expect(store.articles).toHaveLength(1)
    expect(store.getArticleById(7)?.name).toBe('Second')
  })
})

describe('variantPhotoFilename', () => {
  it('answers the own photo of a mug variant and the first listing image of a SPOD colour', () => {
    const mug = makeMug({ variants: [createMugVariant({ exampleImageFilename: 'mug-red.webp' })] })
    const tee = makeTee({
      appearances: [
        createSpodAppearance({ id: 201 }),
        createSpodAppearance({ id: 202, listingImageFilenames: [] }),
      ],
    })

    expect(variantPhotoFilename(mug, mug.variants[0]!)).toBe('mug-red.webp')
    expect(variantPhotoFilename(tee, tee.variants.find((v) => v.appearanceId === 201)!)).toBe(
      'listing-201-front.webp',
    )
    expect(variantPhotoFilename(tee, tee.variants.find((v) => v.appearanceId === 202)!)).toBeNull()
  })
})

describe('variantPhotoFilenames', () => {
  it('answers the own photo of a mug variant, or nothing when it has none', () => {
    const mug = makeMug({
      variants: [
        createMugVariant({ id: 1, exampleImageFilename: 'mug-red.webp' }),
        createMugVariant({ id: 2, exampleImageFilename: null }),
      ],
    })

    expect(variantPhotoFilenames(mug, mug.variants[0]!)).toEqual(['mug-red.webp'])
    expect(variantPhotoFilenames(mug, mug.variants[1]!)).toEqual([])
  })

  it('answers every listing image of the SPOD variant colour in order, and only those', () => {
    const tee = makeTee({
      appearances: [
        createSpodAppearance({
          id: 201,
          listingImageFilenames: ['black-front.webp', 'black-model.webp', 'black-detail.webp'],
        }),
        createSpodAppearance({ id: 202, listingImageFilenames: ['white-front.webp'] }),
      ],
    })

    expect(variantPhotoFilenames(tee, tee.variants.find((v) => v.appearanceId === 201)!)).toEqual([
      'black-front.webp',
      'black-model.webp',
      'black-detail.webp',
    ])
    expect(variantPhotoFilenames(tee, tee.variants.find((v) => v.appearanceId === 202)!)).toEqual([
      'white-front.webp',
    ])
  })
})
