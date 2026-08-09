import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePromptsStore, type PromptDto } from '@/stores/shop/prompts'
import { createShopPrompt } from '@/testing/shopCatalog'
import { resetApiClientForTests } from '@/lib/api'

/** The document of `GET /api/prompts`, verbatim from `docs/dev/backend/prompt-package.md`. */
const PUBLIC_PROMPT_RESPONSE = [
  {
    id: 1,
    position: 1,
    title: 'Watercolor portrait',
    category: { id: 1, name: 'Portraits', position: 1 },
    subcategory: { id: 2, name: 'Adults', position: 2 },
    exampleImageFilename: '6f1b0f34-1111-4222-8333-444455556666.webp',
    llm: 'gpt-image-1',
    price: {
      salesTotalNet: 419,
      salesTotalGross: 499,
      salesTotalTax: 80,
      salesVatRatePercent: 19,
    },
  },
] satisfies PromptDto[]

function makePrompt(
  id: number,
  categoryId: number,
  categoryName: string,
  categoryPosition: number,
  subcategory: { id: number; name: string; position: number } | null = null,
  title = `Prompt ${id}`,
): PromptDto {
  return createShopPrompt({
    id,
    position: id,
    title,
    category: { id: categoryId, name: categoryName, position: categoryPosition },
    subcategory,
  })
}

function stubFetch(body: unknown, init: ResponseInit = {}) {
  const requestedPaths: string[] = []
  // A fresh Response per call: a body may only be read once.
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    requestedPaths.push(String(input))

    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      ...init,
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, requestedPaths }
}

describe('shop prompts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('reads the bare array of the storefront prompt route', async () => {
    const store = usePromptsStore()
    const { fetchMock } = stubFetch(PUBLIC_PROMPT_RESPONSE)

    await store.fetchPrompts()

    expect(fetchMock).toHaveBeenCalledWith('/api/prompts')
    expect(store.prompts).toEqual(PUBLIC_PROMPT_RESPONSE)
    expect(store.getPromptById(1)?.price?.salesVatRatePercent).toBe(19)
    expect(store.error).toBeNull()
  })

  it('keeps a null price, because a prompt without a price row has no zero placeholder', async () => {
    const store = usePromptsStore()
    stubFetch([createShopPrompt({ id: 7, price: null })])

    await store.fetchPrompts()

    expect(store.getPromptById(7)?.price).toBeNull()
  })

  it('asks for a single category with the categoryId parameter', async () => {
    const store = usePromptsStore()
    const { fetchMock } = stubFetch([createShopPrompt({ id: 3 })])

    await store.fetchPrompts(5)

    expect(fetchMock).toHaveBeenCalledWith('/api/prompts?categoryId=5')
    expect(store.prompts.map((prompt) => prompt.id)).toEqual([3])
  })

  it('reloads when the category filter changes and serves the cache when it does not', async () => {
    const store = usePromptsStore()
    const { requestedPaths } = stubFetch([createShopPrompt({ id: 3 })])

    await store.fetchPrompts()
    await store.fetchPrompts()
    await store.fetchPrompts(5)
    await store.fetchPrompts(5)

    expect(requestedPaths).toEqual(['/api/prompts', '/api/prompts?categoryId=5'])
  })

  it('reports the message of a failed read', async () => {
    const store = usePromptsStore()
    stubFetch({ message: 'Internal server error' }, { status: 500 })

    await store.fetchPrompts()

    expect(store.prompts).toEqual([])
    expect(store.error).toBe('Internal server error')
  })

  it('keeps the (position, id) order of the answer with and without a filter', async () => {
    const store = usePromptsStore()
    // The backend orders globally, so a filtered answer is that same order with rows removed.
    const apiPrompts = [
      makePrompt(10, 10, 'Portraits', 1, { id: 11, name: 'Ink', position: 1 }, 'Alpha'),
      makePrompt(20, 10, 'Portraits', 1, null, 'Beta'),
      makePrompt(30, 20, 'Seasonal', 2, null, 'Zed'),
    ]
    stubFetch(apiPrompts)

    await store.fetchPrompts()

    expect(store.prompts.map((prompt) => prompt.id)).toEqual([10, 20, 30])
    expect(store.getPromptsByCategory(null).map((prompt) => prompt.id)).toEqual([10, 20, 30])
    expect(store.getPromptsByCategory(10).map((prompt) => prompt.id)).toEqual([10, 20])
  })

  it('does not re-sort a filtered list by subcategory and title', () => {
    const store = usePromptsStore()
    store.prompts = [
      {
        ...makePrompt(3, 10, 'Portraits', 1, { id: 20, name: 'Oil', position: 2 }, 'Zulu'),
        position: 1,
      },
      {
        ...makePrompt(2, 10, 'Portraits', 1, { id: 10, name: 'Ink', position: 1 }, 'Alpha'),
        position: 2,
      },
      { ...makePrompt(1, 20, 'Seasonal', 2, null, 'Beta'), position: 3 },
    ]
    const sourceOrder = store.prompts.map((prompt) => prompt.id)

    expect(store.getPromptsByCategory(10).map((prompt) => prompt.id)).toEqual([3, 2])
    expect(store.getPromptsByCategoryAndSubcategory(10, 20).map((prompt) => prompt.id)).toEqual([3])
    expect(store.getPromptsByCategoryAndSubcategory(null, null).map((prompt) => prompt.id)).toEqual(
      [3, 2, 1],
    )
    expect(store.prompts.map((prompt) => prompt.id)).toEqual(sourceOrder)
  })

  it('returns prompt category filters in position order', () => {
    const store = usePromptsStore()
    store.prompts = [
      makePrompt(1, 20, 'Seasonal', 2),
      makePrompt(2, 10, 'Portraits', 1),
      makePrompt(3, 20, 'Seasonal', 2),
    ]

    expect(store.categories.map((category) => category.id)).toEqual([10, 20])
  })

  it('groups the subcategories of a category in position order', () => {
    const store = usePromptsStore()
    store.prompts = [
      makePrompt(1, 10, 'Portraits', 1, { id: 20, name: 'Oil', position: 2 }),
      makePrompt(3, 10, 'Portraits', 1),
      makePrompt(2, 10, 'Portraits', 1, { id: 10, name: 'Ink', position: 1 }),
      makePrompt(4, 20, 'Seasonal', 2, { id: 30, name: 'Holiday', position: 1 }),
    ]

    expect(store.getSubcategoriesByCategory(10).map((subcategory) => subcategory.id)).toEqual([
      10, 20,
    ])
    expect(store.getSubcategoriesByCategory(30)).toEqual([])
  })
})
