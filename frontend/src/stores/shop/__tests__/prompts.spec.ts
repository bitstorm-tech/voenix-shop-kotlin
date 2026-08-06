import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePromptsStore, type PromptDto } from '@/stores/shop/prompts'

function makePrompt(
  id: number,
  categoryId: number,
  categoryName: string,
  position: number,
  subcategory?: { id: number; name: string; position: number },
  title = `Prompt ${id}`,
): PromptDto {
  return {
    id,
    position: id,
    title,
    category: {
      id: categoryId,
      name: categoryName,
      position,
    },
    subcategory,
  }
}

describe('shop prompts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('returns prompt helpers in display order independent of public API order', async () => {
    const store = usePromptsStore()
    const apiPrompts = [
      makePrompt(30, 20, 'Seasonal', 2, undefined, 'Zed'),
      makePrompt(10, 10, 'Portraits', 1, { id: 11, name: 'Ink', position: 1 }, 'Alpha'),
      makePrompt(20, 10, 'Portraits', 1, undefined, 'Beta'),
    ]
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: vi.fn().mockResolvedValue({ items: apiPrompts }),
      }),
    )

    await store.fetchPrompts()

    expect(store.prompts.map((prompt) => prompt.id)).toEqual([30, 10, 20])
    expect(store.sortedPrompts.map((prompt) => prompt.id)).toEqual([10, 20, 30])
    expect(store.getPromptsByCategory(null).map((prompt) => prompt.id)).toEqual([10, 20, 30])
    expect(store.getPromptsByCategory(10).map((prompt) => prompt.id)).toEqual([10, 20])
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

  it('returns prompt filters in display order', () => {
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
    expect(store.getPromptsByCategoryAndSubcategory(10, null).map((prompt) => prompt.id)).toEqual([
      2, 1, 3,
    ])
    expect(store.getPromptsByCategoryAndSubcategory(10, 20).map((prompt) => prompt.id)).toEqual([1])
  })

  it('switches between global and category ordering without mutating shared prompts', () => {
    const store = usePromptsStore()
    store.prompts = [
      {
        ...makePrompt(3, 10, 'Portraits', 1, { id: 20, name: 'Oil', position: 2 }, 'Alpha'),
        position: 1,
      },
      {
        ...makePrompt(2, 10, 'Portraits', 1, { id: 10, name: 'Ink', position: 1 }, 'Zulu'),
        position: 3,
      },
      {
        ...makePrompt(1, 10, 'Portraits', 1, { id: 20, name: 'Oil', position: 2 }, 'Beta'),
        position: 2,
      },
    ]
    const sourceOrder = store.prompts.map((prompt) => prompt.id)

    expect(store.getPromptsByCategory(null).map((prompt) => prompt.id)).toEqual([3, 1, 2])
    expect(store.getPromptsByCategory(10).map((prompt) => prompt.id)).toEqual([2, 3, 1])
    expect(store.getPromptsByCategoryAndSubcategory(10, 20).map((prompt) => prompt.id)).toEqual([
      3, 1,
    ])
    expect(store.getPromptsByCategory(null).map((prompt) => prompt.id)).toEqual([3, 1, 2])
    expect(store.prompts.map((prompt) => prompt.id)).toEqual(sourceOrder)
  })

  it('breaks global position ties by prompt id', () => {
    const store = usePromptsStore()
    store.prompts = [
      { ...makePrompt(3, 10, 'Portraits', 1), position: 1 },
      { ...makePrompt(1, 10, 'Portraits', 1), position: 1 },
      { ...makePrompt(2, 10, 'Portraits', 1), position: 2 },
    ]

    expect(store.getPromptsByCategory(null).map((prompt) => prompt.id)).toEqual([1, 3, 2])
  })
})
