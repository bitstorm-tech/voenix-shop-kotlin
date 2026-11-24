import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useArticleCategoriesStore, type CategoryDto } from '@/stores/shop/articleCategories'
import { resetApiClientForTests } from '@/lib/api'

const CATEGORIES_RESPONSE: CategoryDto[] = [
  {
    id: 1,
    name: 'Mugs',
    position: 1,
    subcategories: [{ id: 10, name: 'Classic', exampleImageFilename: null, position: 1 }],
  },
]

describe('shop articleCategories store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shares an in-flight promise across concurrent callers instead of returning early', async () => {
    const store = useArticleCategoriesStore()
    let resolveFetch!: (response: Response) => void
    const fetchMock = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolveFetch = resolve
        }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const first = store.fetchCategories()
    const second = store.fetchCategories()
    let secondResolved = false
    void second.then(() => {
      secondResolved = true
    })

    await Promise.resolve()
    expect(secondResolved).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    resolveFetch(
      new Response(JSON.stringify(CATEGORIES_RESPONSE), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    await Promise.all([first, second])

    expect(secondResolved).toBe(true)
    expect(store.categories).toEqual(CATEGORIES_RESPONSE)
  })
})
