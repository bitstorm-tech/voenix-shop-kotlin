import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useMugsStore, type MugDto } from '@/stores/shop/mugs'
import { createShopMug as makeMug } from '@/testing/shopCatalog'
import { resetApiClientForTests } from '@/lib/api'

/** The document of `GET /api/articles/mugs`, verbatim from `docs/dev/backend/article-package.md`. */
const PUBLIC_MUG_RESPONSE = [
  {
    id: 12,
    position: 1,
    name: 'Classic mug',
    descriptionShort: 'A mug',
    descriptionLong: 'A classic white mug',
    categoryId: 7,
    subcategoryId: 42,
    price: 1490,
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

function stubFetch(body: unknown, init: ResponseInit = {}) {
  // A fresh Response per call: a body may only be read once.
  const fetchMock = vi.fn(
    async () =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        ...init,
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('shop mugs store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('reads the bare array of the storefront mug route', async () => {
    const store = useMugsStore()
    const fetchMock = stubFetch(PUBLIC_MUG_RESPONSE)

    await store.fetchMugs()

    expect(fetchMock).toHaveBeenCalledWith('/api/articles/mugs')
    expect(store.mugs).toEqual(PUBLIC_MUG_RESPONSE)
    expect(store.getMugById(12)).toEqual(PUBLIC_MUG_RESPONSE[0])
    expect(store.error).toBeNull()
  })

  it('keeps a zero price, because it is a real price and not a missing one', async () => {
    const store = useMugsStore()
    stubFetch([makeMug({ id: 5, price: 0 })])

    await store.fetchMugs()

    expect(store.getMugById(5)?.price).toBe(0)
    expect(store.getDisplayMugs(null).map((mug) => mug.id)).toEqual([5])
  })

  it('reports the message of a failed read', async () => {
    const store = useMugsStore()
    stubFetch({ message: 'Internal server error' }, { status: 500 })

    await store.fetchMugs()

    expect(store.mugs).toEqual([])
    expect(store.error).toBe('Internal server error')
  })

  it('orders an unfiltered display list by position and then ID without reordering source mugs', () => {
    const store = useMugsStore()
    store.mugs = [
      makeMug({ id: 30, position: 2 }),
      makeMug({ id: 20, position: 1 }),
      makeMug({ id: 10, position: 1 }),
    ]
    const sourceOrder = store.mugs.map((mug) => mug.id)

    const displayedMugs = store.getDisplayMugs(null)

    expect(displayedMugs.map((mug) => mug.id)).toEqual([10, 20, 30])
    expect(displayedMugs).not.toBe(store.mugs)
    expect(store.mugs.map((mug) => mug.id)).toEqual(sourceOrder)
  })

  it('filters by category before ordering by name and then ID, ignoring position', () => {
    const store = useMugsStore()
    store.mugs = [
      makeMug({ id: 30, position: 1, name: 'Zulu', categoryId: 10 }),
      makeMug({ id: 20, position: 4, name: 'Alpha', categoryId: 10 }),
      makeMug({ id: 10, position: 3, name: 'Alpha', categoryId: 10 }),
      makeMug({ id: 40, position: 2, name: 'Bravo', categoryId: 20 }),
    ]
    const sourceOrder = store.mugs.map((mug) => mug.id)

    const displayedMugs = store.getDisplayMugs(10)

    expect(displayedMugs.map((mug) => mug.id)).toEqual([10, 20, 30])
    expect(store.mugs.map((mug) => mug.id)).toEqual(sourceOrder)
  })

  it('filters by subcategory before ordering by name and then ID, even without a category filter', () => {
    const store = useMugsStore()
    store.mugs = [
      makeMug({
        id: 30,
        position: 1,
        name: 'Zulu',
        categoryId: 10,
        subcategoryId: 100,
      }),
      makeMug({
        id: 20,
        position: 4,
        name: 'Alpha',
        categoryId: 10,
        subcategoryId: 100,
      }),
      makeMug({
        id: 10,
        position: 3,
        name: 'Alpha',
        categoryId: 10,
        subcategoryId: 100,
      }),
      makeMug({
        id: 40,
        position: 2,
        name: 'Bravo',
        categoryId: 10,
        subcategoryId: 200,
      }),
    ]
    const sourceOrder = store.mugs.map((mug) => mug.id)

    const displayedMugs = store.getDisplayMugs(null, 100)

    expect(displayedMugs.map((mug) => mug.id)).toEqual([10, 20, 30])
    expect(store.getDisplayMugs(10, 100).map((mug) => mug.id)).toEqual([10, 20, 30])
    expect(store.getDisplayMugs(20, 100)).toEqual([])
    expect(store.mugs.map((mug) => mug.id)).toEqual(sourceOrder)
  })

  it('restores position order after filter switching and places a later-active Article at its saved rank', () => {
    const store = useMugsStore()
    store.mugs = [
      makeMug({ id: 30, position: 3, name: 'Alpha', categoryId: 10, subcategoryId: 100 }),
      makeMug({ id: 10, position: 1, name: 'Zulu', categoryId: 10, subcategoryId: 100 }),
    ]

    expect(store.getDisplayMugs(null).map((mug) => mug.id)).toEqual([10, 30])
    expect(store.getDisplayMugs(10).map((mug) => mug.id)).toEqual([30, 10])
    expect(store.getDisplayMugs(null, 100).map((mug) => mug.id)).toEqual([30, 10])
    expect(store.getDisplayMugs(null).map((mug) => mug.id)).toEqual([10, 30])

    store.mugs = [
      ...store.mugs,
      makeMug({ id: 20, position: 2, name: 'Bravo', categoryId: 20, subcategoryId: 200 }),
    ]

    expect(store.getDisplayMugs(null).map((mug) => mug.id)).toEqual([10, 20, 30])
    expect(store.mugs.map((mug) => mug.id)).toEqual([30, 10, 20])
  })
})
