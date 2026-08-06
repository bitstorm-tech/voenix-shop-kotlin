import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  PromptCreateConflictError,
  PromptNotFoundError,
  PromptOrderConflictError,
  PromptSaveError,
  useAdminPromptsStore,
  type AdminPromptDetailDto,
  type AdminPromptListItemDto,
} from '@/stores/admin/prompts'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const basePrompt: AdminPromptDetailDto = {
  id: 7,
  position: 1,
  title: 'Portrait prompt',
  category: { id: 1, name: 'People', position: 1 },
  subcategory: undefined,
  exampleImageFilename: undefined,
  llm: null,
  price: priceDto(),
  active: true,
  archived: false,
  promptText: 'Generate a portrait',
  slotVariantIds: [],
}

function priceInput(): AdminPriceInputDto {
  return {
    purchaseVatId: 1,
    purchaseCalculationMode: 'NET',
    purchaseActiveRow: 'COST',
    purchasePriceInputCents: 0,
    purchaseCostInputCents: 0,
    purchaseCostPercent: 0,
    salesVatId: 1,
    salesCalculationMode: 'GROSS',
    salesActiveRow: 'TOTAL',
    salesMarginInputCents: 0,
    salesMarginPercent: 0,
    salesTotalInputCents: 1190,
  }
}

function priceDto(): AdminPriceDto {
  return {
    id: 5,
    ...priceInput(),
    purchaseVat: { id: 1, name: 'Standard', percent: 19 },
    purchasePrice: { net: 0, tax: 0, gross: 0 },
    purchaseCost: { net: 0, tax: 0, gross: 0 },
    calculatedPurchaseCostPercent: 0,
    purchaseTotal: { net: 0, tax: 0, gross: 0 },
    salesVat: { id: 1, name: 'Standard', percent: 19 },
    salesMargin: { net: 1000, tax: 190, gross: 1190 },
    calculatedSalesMarginPercent: 0,
    salesTotal: { net: 1000, tax: 190, gross: 1190 },
  }
}

function listPrompt(overrides: Partial<AdminPromptListItemDto> = {}): AdminPromptListItemDto {
  return {
    id: 1,
    position: 1,
    title: 'Prompt 1',
    category: { id: 1, name: 'People', position: 1 },
    active: true,
    archived: false,
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

describe('admin prompts store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('syncs prompt list category and subcategory after update', async () => {
    const updatedPrompt: AdminPromptDetailDto = {
      ...basePrompt,
      category: { id: 2, name: 'Painting', position: 2 },
      subcategory: { id: 20, name: 'Oil', position: 1 },
      exampleImageFilename: 'new-example.webp',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/7')
      expect(init?.method).toBe('PUT')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(
        JSON.stringify({
          title: 'Portrait prompt',
          promptText: 'Generate a portrait',
          llm: null,
          exampleImageFilename: 'new-example.webp',
          active: true,
          archived: false,
          categoryId: 2,
          subcategoryId: 20,
          slotVariantIds: [],
          price: priceInput(),
        }),
      )
      return jsonResponse(updatedPrompt)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptsStore()
    store.prompts = [
      {
        id: basePrompt.id,
        position: basePrompt.position,
        title: basePrompt.title,
        category: basePrompt.category,
        subcategory: basePrompt.subcategory,
        exampleImageFilename: basePrompt.exampleImageFilename,
        llm: basePrompt.llm,
        price: {
          salesTotalNet: 1000,
          salesTotalGross: 1190,
          salesTotalTax: 190,
          salesVatRatePercent: 19,
        },
        active: basePrompt.active,
        archived: basePrompt.archived,
      },
    ]

    await store.updatePrompt(7, {
      title: 'Portrait prompt',
      promptText: 'Generate a portrait',
      llm: null,
      exampleImageFilename: 'new-example.webp',
      active: true,
      archived: false,
      categoryId: 2,
      subcategoryId: 20,
      slotVariantIds: [],
      price: priceInput(),
    })

    expect(store.prompts).toEqual([
      {
        id: 7,
        position: 1,
        title: 'Portrait prompt',
        category: { id: 2, name: 'Painting', position: 2 },
        subcategory: { id: 20, name: 'Oil', position: 1 },
        exampleImageFilename: 'new-example.webp',
        llm: null,
        price: {
          salesTotalNet: 1000,
          salesTotalGross: 1190,
          salesTotalTax: 190,
          salesVatRatePercent: 19,
        },
        active: true,
        archived: false,
      },
    ])
  })

  it('creates a complete prompt with antiforgery without accepting a position', async () => {
    const payload = {
      title: 'New prompt',
      promptText: 'First line\nSecond line',
      llm: null,
      exampleImageFilename: null,
      active: false,
      archived: true,
      categoryId: 1,
      subcategoryId: null,
      slotVariantIds: [],
      price: { ...priceInput(), salesTotalInputCents: 0 },
    }
    const createdPrompt = {
      ...basePrompt,
      id: 8,
      position: 4,
      title: payload.title,
      promptText: payload.promptText,
      active: payload.active,
      archived: payload.archived,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts')
      expect(init).toEqual({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'token-1',
        },
        body: JSON.stringify(payload),
      })
      return jsonResponse(createdPrompt, { status: 201 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptsStore()

    const result = await store.createPrompt(payload)

    expect(result).toEqual(createdPrompt)
  })

  it('maps create ordering conflicts to a retryable error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse({ detail: 'Prompt order changed.' }, { status: 409 })
      }),
    )
    const store = useAdminPromptsStore()

    await expect(
      store.createPrompt({
        title: 'Prompt',
        promptText: 'text',
        active: true,
        archived: false,
        categoryId: 1,
        slotVariantIds: [],
        price: priceInput(),
      }),
    ).rejects.toBeInstanceOf(PromptCreateConflictError)
  })

  it('maps a missing prompt detail to a dedicated not-found error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ detail: 'Prompt not found' }, { status: 404 })),
    )
    const store = useAdminPromptsStore()

    await expect(store.fetchPrompt(404)).rejects.toBeInstanceOf(PromptNotFoundError)
  })

  it('maps structured Price save failures to the Price editor section', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse(
          { detail: 'Sales total input must not be negative', code: 'invalid_price_request' },
          { status: 400 },
        )
      }),
    )
    const store = useAdminPromptsStore()

    await expect(
      store.updatePrompt(7, {
        title: 'Prompt',
        promptText: 'text',
        active: true,
        archived: false,
        categoryId: 1,
        slotVariantIds: [],
        price: priceInput(),
      }),
    ).rejects.toMatchObject({
      name: 'PromptSaveError',
      section: 'price',
    } satisfies Partial<PromptSaveError>)
  })

  it.each(['Price', 'Price.SalesTotalInputCents'])(
    'maps ASP.NET validation key %s to the Price editor section',
    async (validationKey) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: RequestInfo | URL) => {
          if (input === '/api/antiforgery/token') {
            return jsonResponse({ requestToken: 'token-1' })
          }
          return jsonResponse(
            { title: 'Validation failed', errors: { [validationKey]: ['Invalid Price'] } },
            { status: 400 },
          )
        }),
      )
      const store = useAdminPromptsStore()

      await expect(
        store.updatePrompt(7, {
          title: 'Prompt',
          promptText: 'text',
          active: true,
          archived: false,
          categoryId: 1,
          slotVariantIds: [],
          price: priceInput(),
        }),
      ).rejects.toMatchObject({ section: 'price' })
    },
  )

  it('forces an authoritative refresh after an overlapping stale list request', async () => {
    const staleResponse = deferred<Response>()
    const authoritativeResponse = deferred<Response>()
    let listRequestCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        listRequestCount += 1
        return listRequestCount === 1 ? staleResponse.promise : authoritativeResponse.promise
      }),
    )
    const store = useAdminPromptsStore()

    const staleRequest = store.fetchPrompts()
    const refreshRequest = store.refreshPrompts()
    staleResponse.resolve(jsonResponse({ items: [listPrompt()] }))
    await staleRequest
    await Promise.resolve()

    expect(listRequestCount).toBe(2)
    authoritativeResponse.resolve(
      jsonResponse({ items: [listPrompt(), listPrompt({ id: 2, position: 2 })] }),
    )
    await refreshRequest

    expect(store.prompts.map((prompt) => prompt.id)).toEqual([1, 2])
  })

  it('uploads prompt example images with antiforgery', async () => {
    const file = new File(['image'], 'example.png', { type: 'image/png' })
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/example-images')
      expect(init?.method).toBe('POST')
      expect(init?.headers).toEqual({
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBeInstanceOf(FormData)
      expect((init?.body as FormData).get('file')).toBe(file)
      return jsonResponse({ filename: 'uploaded-example.webp' })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptsStore()

    const filename = await store.uploadExampleImage(file)

    expect(filename).toBe('uploaded-example.webp')
  })

  it('reorders prompts without optimistic mutation and adopts the authoritative response', async () => {
    const orderResponse = deferred<Response>()
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }
      expect(input).toBe('/api/admin/prompts/order')
      expect(init).toEqual({
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'token-1',
        },
        body: JSON.stringify({ sourcePromptId: 2, targetPromptId: 1 }),
      })
      return orderResponse.promise
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptsStore()
    store.prompts = [listPrompt(), listPrompt({ id: 2, position: 2, title: 'Prompt 2' })]

    const request = store.reorderPrompts(2, 1)
    await Promise.resolve()

    expect(store.isReordering).toBe(true)
    expect(store.prompts.map((prompt) => prompt.id)).toEqual([1, 2])

    orderResponse.resolve(
      jsonResponse({
        items: [
          listPrompt({ id: 1, position: 2 }),
          listPrompt({ id: 2, position: 1, title: 'Prompt 2', active: false }),
        ],
      }),
    )
    await request

    expect(store.isReordering).toBe(false)
    expect(store.prompts.map((prompt) => prompt.id)).toEqual([2, 1])
    expect(store.prompts.map((prompt) => prompt.position)).toEqual([1, 2])
  })

  it('maps ordering conflicts without changing the current collection', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse({ detail: 'Prompt order is stale.' }, { status: 409 })
      }),
    )
    const store = useAdminPromptsStore()
    const originalPrompts = [listPrompt(), listPrompt({ id: 2, position: 2 })]
    store.prompts = originalPrompts

    await expect(store.reorderPrompts(2, 1)).rejects.toBeInstanceOf(PromptOrderConflictError)

    expect(store.prompts).toEqual(originalPrompts)
    expect(store.isReordering).toBe(false)
  })
})
