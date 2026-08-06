import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useMugsStore, type MugDto } from '@/stores/shop/mugs'

function makeMug(overrides: Partial<MugDto> = {}): MugDto {
  const id = overrides.id ?? 1

  return {
    id,
    position: overrides.position ?? id,
    name: overrides.name ?? `Mug ${id}`,
    descriptionShort: 'Short description',
    descriptionLong: 'Long description',
    categoryId: overrides.categoryId ?? 10,
    subcategoryId: overrides.subcategoryId ?? null,
    price: 1499,
    mugDetails: {
      documentFormatWidthMm: 200,
      documentFormatHeightMm: 90,
    },
    variants: [
      {
        id: id * 10 + 1,
        name: 'White',
        outsideColorCode: '#ffffff',
        insideColorCode: '#ffffff',
        isDefault: true,
      },
    ],
    ...overrides,
  }
}

describe('shop mugs store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('consumes Article Display Position without changing the public Mug Article shape or ID lookup', async () => {
    const store = useMugsStore()
    const apiMug = makeMug({ id: 42, position: 7, price: 2399 })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: vi.fn().mockResolvedValue({ items: [apiMug] }),
      }),
    )

    await store.fetchMugs()

    expect(store.mugs).toEqual([apiMug])
    expect(store.getMugById(42)).toEqual(apiMug)
    expect(store.getMugById(42)).toMatchObject({
      position: 7,
      price: 2399,
      mugDetails: apiMug.mugDetails,
      variants: apiMug.variants,
    })
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
