import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  PromptNotFoundError,
  PromptOrderConflictError,
  PromptSaveError,
  useAdminPromptsStore,
  type AdminPromptDetailDto,
  type AdminPromptListItemDto,
  type SaveAdminPromptRequest,
} from '@/stores/admin/prompts'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
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

/** The admin prompt detail of section 1.4: flat ids, `promptText`, `slotVariantIds`, full price. */
const detailPrompt: AdminPromptDetailDto = {
  id: 7,
  position: 1,
  title: 'Portrait prompt',
  promptText: 'Generate a portrait',
  categoryId: 1,
  subcategoryId: null,
  slotVariantIds: [],
  exampleImageFilename: null,
  llm: null,
  active: true,
  archived: false,
  price: priceDto(),
}

/** The admin prompt list row of section 1.4: flat ids *and* display names, small price projection. */
function listPrompt(overrides: Partial<AdminPromptListItemDto> = {}): AdminPromptListItemDto {
  return {
    id: 1,
    position: 1,
    title: 'Prompt 1',
    categoryId: 1,
    categoryName: 'People',
    subcategoryId: null,
    subcategoryName: null,
    exampleImageFilename: null,
    llm: null,
    active: true,
    archived: false,
    price: {
      salesTotalNet: 1000,
      salesTotalGross: 1190,
      salesTotalTax: 190,
      salesVatRatePercent: 19,
    },
    ...overrides,
  }
}

function savePayload(overrides: Partial<SaveAdminPromptRequest> = {}): SaveAdminPromptRequest {
  return {
    title: 'Portrait prompt',
    promptText: 'Generate a portrait',
    categoryId: 1,
    subcategoryId: null,
    slotVariantIds: [],
    exampleImageFilename: null,
    llm: null,
    active: true,
    archived: false,
    price: priceInput(),
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

  it('reads the list as a bare array in display order', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(input).toBe('/api/admin/prompts')
        return jsonResponse([
          listPrompt({ id: 2, position: 2, title: 'Prompt 2' }),
          listPrompt({
            id: 1,
            position: 1,
            subcategoryId: 20,
            subcategoryName: 'Oil',
            exampleImageFilename: '6f1b0f34-1111-4222-8333-444455556666.webp',
            llm: 'gpt-image-1',
          }),
        ])
      }),
    )
    const store = useAdminPromptsStore()

    await store.fetchPrompts()

    expect(store.prompts.map((prompt) => prompt.id)).toEqual([1, 2])
    expect(store.prompts[0]).toEqual({
      id: 1,
      position: 1,
      title: 'Prompt 1',
      categoryId: 1,
      categoryName: 'People',
      subcategoryId: 20,
      subcategoryName: 'Oil',
      exampleImageFilename: '6f1b0f34-1111-4222-8333-444455556666.webp',
      llm: 'gpt-image-1',
      active: true,
      archived: false,
      price: {
        salesTotalNet: 1000,
        salesTotalGross: 1190,
        salesTotalTax: 190,
        salesVatRatePercent: 19,
      },
    })
  })

  it('reads the flat detail with prompt text, slot variants, and the full calculated price', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(input).toBe('/api/admin/prompts/7')
        return jsonResponse({ ...detailPrompt, subcategoryId: 20, slotVariantIds: [9, 12] })
      }),
    )
    const store = useAdminPromptsStore()

    const prompt = await store.fetchPrompt(7)

    expect(prompt.categoryId).toBe(1)
    expect(prompt.subcategoryId).toBe(20)
    expect(prompt.promptText).toBe('Generate a portrait')
    expect(prompt.slotVariantIds).toEqual([9, 12])
    expect(prompt.price?.id).toBe(5)
    expect(prompt).not.toHaveProperty('priceId')
    expect(prompt).not.toHaveProperty('category')
  })

  it('reads a prompt whose price row was never linked', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ ...detailPrompt, price: null })),
    )
    const store = useAdminPromptsStore()

    expect((await store.fetchPrompt(7)).price).toBeNull()
  })

  it('creates and updates with a body that carries neither position nor priceId', async () => {
    const bodies: unknown[] = []
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      bodies.push(JSON.parse(String(init?.body)))
      return input === '/api/admin/prompts'
        ? jsonResponse(detailPrompt, { status: 201 })
        : jsonResponse(detailPrompt)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptsStore()

    await store.createPrompt(savePayload())
    await store.updatePrompt(7, savePayload({ exampleImageFilename: 'new-example.webp' }))

    expect(fetchMock.mock.calls.map(([input, init]) => [input, init?.method])).toEqual([
      ['/api/antiforgery/token', undefined],
      ['/api/admin/prompts', 'POST'],
      ['/api/admin/prompts/7', 'PUT'],
    ])
    for (const body of bodies) {
      expect(body).not.toHaveProperty('position')
      expect(body).not.toHaveProperty('priceId')
    }
    expect(bodies[1]).toEqual({
      title: 'Portrait prompt',
      promptText: 'Generate a portrait',
      categoryId: 1,
      subcategoryId: null,
      slotVariantIds: [],
      exampleImageFilename: 'new-example.webp',
      llm: null,
      active: true,
      archived: false,
      price: priceInput(),
    })
  })

  it('maps a missing prompt detail to a dedicated not-found error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Prompt not found' }, { status: 404 })),
    )
    const store = useAdminPromptsStore()

    await expect(store.fetchPrompt(404)).rejects.toBeInstanceOf(PromptNotFoundError)
  })

  it.each([
    ['price', 'Price is required'],
    ['price.salesVatId', 'Sales VAT does not exist'],
  ])('sends a write rejected on %s to the Price tab', async (field, message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse(
          { message: 'Validation failed', errors: { [field]: [message] } },
          { status: 400 },
        )
      }),
    )
    const store = useAdminPromptsStore()

    await expect(store.updatePrompt(7, savePayload())).rejects.toMatchObject({
      name: 'PromptSaveError',
      message: 'Validation failed',
      section: 'price',
    } satisfies Partial<PromptSaveError>)
  })

  it.each([
    ['categoryId', 'Prompt category does not exist'],
    ['subcategoryId', 'Prompt subcategory does not exist in this prompt category'],
    ['slotVariantIds', 'Prompt slot variant does not exist'],
    ['exampleImageFilename', 'Example image does not exist'],
  ])(
    'keeps a write rejected on %s in the Prompt tab and carries its message',
    async (field, message) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: RequestInfo | URL) => {
          if (input === '/api/antiforgery/token') {
            return jsonResponse({ requestToken: 'token-1' })
          }
          return jsonResponse(
            { message: 'Validation failed', errors: { [field]: [message] } },
            { status: 400 },
          )
        }),
      )
      const store = useAdminPromptsStore()

      const error = await store.createPrompt(savePayload()).catch((thrown: unknown) => thrown)

      expect(error).toBeInstanceOf(PromptSaveError)
      expect((error as PromptSaveError).section).toBe('prompt')
      expect((error as PromptSaveError).fieldError(field)).toBe(message)
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
    staleResponse.resolve(jsonResponse([listPrompt()]))
    await staleRequest
    await Promise.resolve()

    expect(listRequestCount).toBe(2)
    authoritativeResponse.resolve(jsonResponse([listPrompt(), listPrompt({ id: 2, position: 2 })]))
    await refreshRequest

    expect(store.prompts.map((prompt) => prompt.id)).toEqual([1, 2])
  })

  it('pre-uploads an example image and answers the stored file name', async () => {
    const file = new File(['image'], 'example.png', { type: 'image/png' })
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/example-images')
      expect(init?.method).toBe('POST')
      expect(init?.headers).toEqual({ 'X-XSRF-TOKEN': 'token-1' })
      expect(init?.body).toBeInstanceOf(FormData)
      expect((init?.body as FormData).get('file')).toBe(file)
      return jsonResponse(
        { filename: '6f1b0f34-1111-4222-8333-444455556666.webp' },
        { status: 201 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptsStore()

    expect(await store.uploadExampleImage(file)).toBe('6f1b0f34-1111-4222-8333-444455556666.webp')
  })

  it.each([
    'An example image file part is required',
    'Example image must not exceed 10 MiB',
    'Example image could not be read',
  ])('reports the pre-upload rejection "%s" from the file field', async (message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse(
          { message: 'Validation failed', errors: { file: [message] } },
          { status: 400 },
        )
      }),
    )
    const store = useAdminPromptsStore()

    await expect(
      store.uploadExampleImage(new File(['image'], 'example.png', { type: 'image/png' })),
    ).rejects.toThrow(message)
  })

  it('reorders with the shared body and adopts the dense answer without optimistic mutation', async () => {
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
        body: JSON.stringify({ sourceId: 2, targetId: 1 }),
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
      jsonResponse([
        listPrompt({ id: 1, position: 2 }),
        listPrompt({ id: 2, position: 1, title: 'Prompt 2', active: false }),
      ]),
    )
    await request

    expect(store.isReordering).toBe(false)
    expect(store.prompts.map((prompt) => prompt.id)).toEqual([2, 1])
    expect(store.prompts.map((prompt) => prompt.position)).toEqual([1, 2])
    expect(store.prompts[0]?.price).toEqual({
      salesTotalNet: 1000,
      salesTotalGross: 1190,
      salesTotalTax: 190,
      salesVatRatePercent: 19,
    })
  })

  it('maps a lost reorder race to a retryable conflict without changing the collection', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse(
          { message: 'Prompt order changed concurrently, please retry' },
          { status: 409 },
        )
      }),
    )
    const store = useAdminPromptsStore()
    const originalPrompts = [listPrompt(), listPrompt({ id: 2, position: 2 })]
    store.prompts = originalPrompts

    await expect(store.reorderPrompts(2, 1)).rejects.toThrow(
      new PromptOrderConflictError('Prompt order changed concurrently, please retry'),
    )

    expect(store.prompts).toEqual(originalPrompts)
    expect(store.isReordering).toBe(false)
  })

  it('maps an unknown reorder id to a not-found error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }
        return jsonResponse({ message: 'Prompt not found' }, { status: 404 })
      }),
    )
    const store = useAdminPromptsStore()

    await expect(store.reorderPrompts(2, 999)).rejects.toBeInstanceOf(PromptNotFoundError)
    expect(store.isReordering).toBe(false)
  })
})
