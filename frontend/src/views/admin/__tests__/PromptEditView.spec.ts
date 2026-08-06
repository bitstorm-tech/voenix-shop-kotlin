import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'
import { resetApiClientForTests } from '@/lib/api'
import { useToast } from '@/composables/useToast'
import AdminPromptForm from '@/components/admin/prompts/AdminPromptForm.vue'
import AdminPriceEditor from '@/components/admin/pricing/AdminPriceEditor.vue'
import type { AdminPromptDetailDto } from '@/stores/admin/prompts'
import type { AdminPriceDto } from '@/stores/admin/prices'
import PromptEditView from '../PromptEditView.vue'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

function priceDto(): AdminPriceDto {
  const vat = { id: 1, name: 'Standard VAT', percent: 19 }
  return {
    id: 5,
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
    purchaseVat: vat,
    purchasePrice: { net: 0, tax: 0, gross: 0 },
    purchaseCost: { net: 0, tax: 0, gross: 0 },
    calculatedPurchaseCostPercent: 0,
    purchaseTotal: { net: 0, tax: 0, gross: 0 },
    salesVat: vat,
    salesMargin: { net: 1000, tax: 190, gross: 1190 },
    calculatedSalesMarginPercent: 0,
    salesTotal: { net: 1000, tax: 190, gross: 1190 },
  }
}

const prompt: AdminPromptDetailDto = {
  id: 7,
  position: 3,
  title: 'Portrait Prompt',
  promptText: 'First line\nSecond line',
  category: { id: 1, name: 'People', position: 1 },
  subcategory: { id: 10, name: 'Portraits', position: 1 },
  exampleImageFilename: 'example.webp',
  llm: 'gpt-image-1',
  price: priceDto(),
  active: true,
  archived: false,
  slotVariantIds: [20, 21],
}

function createFetchMock() {
  let updateCount = 0
  const updateBodies: unknown[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === '/api/antiforgery/token') {
      return jsonResponse({ requestToken: 'request-token' })
    }
    if (input === '/api/admin/prompts/7' && init?.method === 'PUT') {
      updateCount += 1
      const body = JSON.parse(String(init.body))
      updateBodies.push(body)
      if (updateCount === 1) {
        return jsonResponse({ message: 'Save exploded' }, { status: 500 })
      }
      return jsonResponse({ ...prompt, title: body.title, promptText: body.promptText })
    }
    if (input === '/api/admin/prompts/7') {
      return jsonResponse(prompt)
    }
    if (input === '/api/admin/prompts/categories') {
      return jsonResponse({
        items: [
          { id: 1, name: 'People', position: 1, active: true },
          { id: 2, name: 'Staging', position: 2, active: false },
        ],
      })
    }
    if (input === '/api/admin/prompts/subcategories') {
      return jsonResponse({
        items: [
          {
            id: 10,
            promptCategory: { id: 1, name: 'People', position: 1, active: true },
            name: 'Portraits',
            description: null,
            position: 1,
            active: false,
          },
        ],
      })
    }
    if (input === '/api/admin/prompts/slot-types') {
      return jsonResponse({ items: [{ id: 2, name: 'Subject', position: 1, variantCount: 2 }] })
    }
    if (input === '/api/admin/prompts/slot-variants') {
      return jsonResponse({
        items: [
          {
            id: 20,
            slotType: { id: 2, name: 'Subject', position: 1 },
            name: 'Person',
            prompt: 'person',
            description: null,
            llm: null,
            assignedPromptCount: 1,
          },
          {
            id: 21,
            slotType: { id: 2, name: 'Subject', position: 1 },
            name: 'Pet',
            prompt: 'pet',
            description: null,
            llm: null,
            assignedPromptCount: 1,
          },
        ],
      })
    }
    if (input === '/api/admin/vat') {
      return jsonResponse([
        { id: 1, name: 'Standard VAT', percent: 19, description: null, isDefault: true },
      ])
    }
    throw new Error(`Unexpected request: ${String(input)} ${init?.method ?? 'GET'}`)
  })

  return { fetchMock, updateBodies }
}

function createNewPromptFetchMock() {
  let createCount = 0
  const createBodies: Record<string, unknown>[] = []
  const defaultPrice: AdminPriceDto = {
    ...priceDto(),
    id: null,
    salesTotalInputCents: 0,
    salesMargin: { net: 0, tax: 0, gross: 0 },
    salesTotal: { net: 0, tax: 0, gross: 0 },
  }
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === '/api/antiforgery/token') {
      return jsonResponse({ requestToken: 'request-token' })
    }
    if (input === '/api/admin/prompts' && init?.method === 'POST') {
      createCount += 1
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      createBodies.push(body)
      if (createCount === 1) {
        return jsonResponse({ message: 'Prompt order changed.' }, { status: 409 })
      }
      return jsonResponse(
        {
          ...prompt,
          id: 8,
          position: 4,
          title: body.title,
          promptText: body.promptText,
          category: { id: 1, name: 'People', position: 1 },
          subcategory: null,
          exampleImageFilename: null,
          llm: null,
          active: body.active,
          archived: body.archived,
          slotVariantIds: [],
          price: { ...defaultPrice, id: 6 },
        },
        { status: 201 },
      )
    }
    if (input === '/api/admin/prompts') {
      return jsonResponse({ items: [] })
    }
    if (input === '/api/admin/prompts/7') {
      return jsonResponse(prompt)
    }
    if (input === '/api/admin/prices/default') {
      return jsonResponse(defaultPrice)
    }
    if (input === '/api/admin/prices/calculate' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as { salesTotalInputCents: number }
      return jsonResponse({ ...defaultPrice, salesTotalInputCents: body.salesTotalInputCents })
    }
    if (input === '/api/admin/prompts/categories') {
      return jsonResponse({ items: [{ id: 1, name: 'People', position: 1, active: true }] })
    }
    if (input === '/api/admin/prompts/subcategories') {
      return jsonResponse({ items: [] })
    }
    if (input === '/api/admin/prompts/slot-types') {
      return jsonResponse({ items: [] })
    }
    if (input === '/api/admin/prompts/slot-variants') {
      return jsonResponse({ items: [] })
    }
    if (input === '/api/admin/vat') {
      return jsonResponse([
        { id: 1, name: 'Standard VAT', percent: 19, description: null, isDefault: true },
      ])
    }
    throw new Error(`Unexpected request: ${String(input)} ${init?.method ?? 'GET'}`)
  })

  return { fetchMock, createBodies }
}

async function openTab(wrapper: ReturnType<typeof mount>, label: string) {
  const tab = wrapper.findAll('[role="tab"]').find((candidate) => candidate.text() === label)
  expect(tab).toBeDefined()
  await tab!.trigger('mousedown', { button: 0 })
  await tab!.trigger('click')
  await flushPromises()
}

async function mountRoutedEditor(
  fetchMock: ReturnType<typeof vi.fn>,
  initialPath = '/admin/prompts/7/edit',
) {
  vi.stubGlobal('fetch', fetchMock)
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/prompts',
        name: 'admin-prompts',
        component: { template: '<div>All Prompts</div>' },
      },
      {
        path: '/admin/prompts/new',
        name: 'admin-prompt-new',
        component: PromptEditView,
      },
      {
        path: '/admin/prompts/:id/edit',
        name: 'admin-prompt-edit',
        component: PromptEditView,
      },
    ],
  })
  await router.push(initialPath)
  await router.isReady()

  const wrapper = mount(
    { components: { RouterView }, template: '<RouterView />' },
    {
      attachTo: document.body,
      global: {
        plugins: [pinia, router, createI18n({ legacy: false, locale: 'en', messages: { en } })],
      },
    },
  )
  await flushPromises()
  return { router, wrapper }
}

describe('PromptEditView', () => {
  beforeEach(() => {
    resetApiClientForTests()
    const pinia = createPinia()
    setActivePinia(pinia)
    useToast().toasts.value.splice(0)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('hydrates both tabs, retains entered values after failure, and returns after atomic save', async () => {
    const { fetchMock, updateBodies } = createFetchMock()
    const { router, wrapper } = await mountRoutedEditor(fetchMock)

    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('Portrait Prompt')
    expect((wrapper.get('#prompt-text').element as HTMLTextAreaElement).value).toBe(
      'First line\nSecond line',
    )
    expect(wrapper.text()).toContain('Portraits (Inactive)')

    await openTab(wrapper, 'Price')
    expect(
      (wrapper.get('[data-testid="price-sales-total-gross"]').element as HTMLInputElement).value,
    ).toBe('11,90')

    await openTab(wrapper, 'Prompt')
    await wrapper.get('#prompt-title').setValue('Changed Prompt')
    await wrapper.get('#prompt-text').setValue('Changed first line\nChanged second line')

    const saveButton = wrapper.findAll('button').find((button) => button.text() === 'Save Prompt')
    expect(saveButton).toBeDefined()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-prompt-edit')
    expect(wrapper.text()).toContain('Save exploded')
    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('Changed Prompt')

    await saveButton!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-prompts')
    expect(updateBodies).toHaveLength(2)
    expect(updateBodies[1]).toEqual(
      expect.objectContaining({
        title: 'Changed Prompt',
        promptText: 'Changed first line\nChanged second line',
        categoryId: 1,
        subcategoryId: 10,
        slotVariantIds: [20, 21],
        price: expect.objectContaining({
          purchaseVatId: 1,
          salesVatId: 1,
          salesTotalInputCents: 1190,
        }),
      }),
    )
    expect(useToast().toasts.value.at(-1)).toEqual(
      expect.objectContaining({
        title: 'Prompt saved',
        description: 'Prompt and Price were saved successfully.',
        variant: 'success',
      }),
    )
  })

  it('creates from deliberate defaults, preserves state after conflict, and reloads the list', async () => {
    const { fetchMock, createBodies } = createNewPromptFetchMock()
    const { router, wrapper } = await mountRoutedEditor(fetchMock, '/admin/prompts/new')

    expect(wrapper.text()).toContain('New Prompt')
    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#prompt-text').element as HTMLTextAreaElement).value).toBe('')
    expect((wrapper.get('#prompt-active').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('#prompt-archived').element as HTMLInputElement).checked).toBe(false)
    await openTab(wrapper, 'Price')
    expect(
      (wrapper.get('[data-testid="price-sales-total-gross"]').element as HTMLInputElement).value,
    ).toBe('0,00')

    await openTab(wrapper, 'Prompt')
    await wrapper.get('#prompt-title').setValue('Created Prompt')
    await wrapper.get('#prompt-text').setValue('First line\nSecond line')
    const promptForm = wrapper.findComponent(AdminPromptForm)
    promptForm.vm.$emit('categoryIdChange', 1)
    promptForm.vm.$emit('activeChange', false)
    promptForm.vm.$emit('archivedChange', true)
    await flushPromises()

    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Create Prompt')
    expect(createButton).toBeDefined()
    await createButton!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-prompt-new')
    expect(wrapper.text()).toContain('Your entries are intact')
    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('Created Prompt')

    await createButton!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-prompts')
    expect(createBodies).toHaveLength(2)
    expect(createBodies[1]).toEqual(
      expect.objectContaining({
        title: 'Created Prompt',
        promptText: 'First line\nSecond line',
        categoryId: 1,
        subcategoryId: null,
        active: false,
        archived: true,
        slotVariantIds: [],
        price: expect.objectContaining({
          purchaseVatId: 1,
          salesVatId: 1,
          salesTotalInputCents: 0,
        }),
      }),
    )
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) => input === '/api/admin/prompts' && init === undefined,
      ),
    ).toBe(true)
    expect(useToast().toasts.value.at(-1)).toEqual(
      expect.objectContaining({
        title: 'Prompt created',
        description: 'Prompt and Price were created successfully.',
        variant: 'success',
      }),
    )
  })

  it('remounts the editor when navigating from create to edit mode', async () => {
    const { fetchMock } = createNewPromptFetchMock()
    const confirm = vi.fn(() => true)
    vi.stubGlobal('confirm', confirm)
    const { router, wrapper } = await mountRoutedEditor(fetchMock, '/admin/prompts/new')
    await wrapper.get('#prompt-title').setValue('Unsaved create title')

    await router.push('/admin/prompts/7/edit')
    await flushPromises()

    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('Portrait Prompt')
    expect(wrapper.text()).toContain('Edit Prompt (Portrait Prompt)')
    expect(confirm).toHaveBeenCalledOnce()
  })

  it('protects dirty state when navigating between edit IDs of the same route', async () => {
    const base = createFetchMock()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prompts/8' && !init) {
        return Promise.resolve(jsonResponse({ ...prompt, id: 8, title: 'Second Prompt' }))
      }
      return base.fetchMock(input, init)
    })
    const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('confirm', confirm)
    const { router, wrapper } = await mountRoutedEditor(fetchMock)
    await wrapper.get('#prompt-title').setValue('Unsaved title')

    await router.push('/admin/prompts/8/edit')
    expect(router.currentRoute.value.params.id).toBe('7')
    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('Unsaved title')

    await router.push('/admin/prompts/8/edit')
    await flushPromises()
    expect(router.currentRoute.value.params.id).toBe('8')
    expect((wrapper.get('#prompt-title').element as HTMLInputElement).value).toBe('Second Prompt')
    expect(confirm).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('allows clean navigation but protects Prompt changes, image selections, Cancel, and unload', async () => {
    const { fetchMock } = createFetchMock()
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    const { router, wrapper } = await mountRoutedEditor(fetchMock)

    const cleanUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(cleanUnload)
    expect(cleanUnload.defaultPrevented).toBe(false)

    await router.push('/admin/prompts')
    expect(router.currentRoute.value.name).toBe('admin-prompts')
    expect(confirm).not.toHaveBeenCalled()

    await router.push('/admin/prompts/7/edit')
    await flushPromises()
    await wrapper.get('#prompt-title').setValue('Dirty Prompt')

    await router.push('/admin/prompts')
    expect(router.currentRoute.value.name).toBe('admin-prompt-edit')
    expect(confirm).toHaveBeenCalledTimes(1)

    const dirtyUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirtyUnload)
    expect(dirtyUnload.defaultPrevented).toBe(true)

    const cancel = wrapper.findAll('button').find((button) => button.text() === 'Cancel')
    expect(cancel).toBeDefined()
    await cancel!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('admin-prompt-edit')
    expect(confirm).toHaveBeenCalledTimes(2)

    wrapper.findComponent(AdminPromptForm).vm.$emit('exampleImageSelection')
    await flushPromises()
    await router.push('/admin/prompts')
    expect(confirm).toHaveBeenCalledTimes(3)

    wrapper.unmount()
    const unmountedUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unmountedUnload)
    expect(unmountedUnload.defaultPrevented).toBe(false)
  })

  it('protects an image selection before upload without another dirty field', async () => {
    const { fetchMock } = createFetchMock()
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    const { router, wrapper } = await mountRoutedEditor(fetchMock)

    wrapper.findComponent(AdminPromptForm).vm.$emit('exampleImageSelection')
    await flushPromises()
    await router.push('/admin/prompts')

    expect(router.currentRoute.value.name).toBe('admin-prompt-edit')
    expect(confirm).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('protects Price changes and activates the tab with the first validation error', async () => {
    vi.useFakeTimers()
    const { fetchMock } = createNewPromptFetchMock()
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    const { router, wrapper } = await mountRoutedEditor(fetchMock, '/admin/prompts/new')

    await openTab(wrapper, 'Price')
    wrapper.findComponent(AdminPriceEditor).vm.$emit('salesTotalChange', '2,00')
    await flushPromises()
    await router.push('/admin/prompts')
    expect(router.currentRoute.value.name).toBe('admin-prompt-new')
    expect(confirm).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()

    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Create Prompt')
    await createButton!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('Prompt')

    await wrapper.get('#prompt-title').setValue('Valid title')
    await wrapper.get('#prompt-text').setValue('Valid Prompt text')
    wrapper.findComponent(AdminPromptForm).vm.$emit('categoryIdChange', 1)
    await openTab(wrapper, 'Price')
    wrapper.findComponent(AdminPriceEditor).vm.$emit('salesTotalChange', 'invalid')
    await openTab(wrapper, 'Prompt')
    await createButton!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('Price')

    wrapper.unmount()
  })

  it('clears dirty protection before successful navigation and prevents duplicate submits', async () => {
    const base = createFetchMock()
    let resolveUpdate!: (response: Response) => void
    const updateResponse = new Promise<Response>((resolve) => {
      resolveUpdate = resolve
    })
    let updateCount = 0
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prompts/7' && init?.method === 'PUT') {
        updateCount += 1
        return updateResponse
      }
      return base.fetchMock(input, init)
    })
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    const { router, wrapper } = await mountRoutedEditor(fetchMock)

    await wrapper.get('#prompt-title').setValue('Saved once')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(updateCount).toBe(1)

    resolveUpdate(jsonResponse({ ...prompt, title: 'Saved once' }))
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('admin-prompts')
    expect(confirm).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('copies the Prompt text together with the selected slot prompts and reports failures', async () => {
    const { fetchMock } = createFetchMock()
    const writeText = vi.fn(async () => {})
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const { wrapper } = await mountRoutedEditor(fetchMock)

    const copyButton = wrapper.get('[data-testid="prompt-editor-copy-full-prompt"]')
    await copyButton.trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('First line\nSecond line\n\nperson\n\npet')
    expect(useToast().toasts.value.at(-1)?.title).toBe('Prompt copied')

    wrapper.findComponent(AdminPromptForm).vm.$emit('slotVariantIdsChange', [21])
    await flushPromises()
    writeText.mockRejectedValueOnce(new Error('denied'))
    await copyButton.trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith('First line\nSecond line\n\npet')
    expect(useToast().toasts.value.at(-1)?.title).toBe('Copy failed')

    wrapper.unmount()
  })

  it('shows a safe not-found result for a missing or invalid edit target', async () => {
    const base = createFetchMock()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prompts/404') {
        return Promise.resolve(jsonResponse({ message: 'Prompt not found' }, { status: 404 }))
      }
      return base.fetchMock(input, init)
    })
    const { router, wrapper } = await mountRoutedEditor(fetchMock, '/admin/prompts/404/edit')

    expect(wrapper.text()).toContain('Prompt not found')
    expect(wrapper.find('#prompt-title').exists()).toBe(false)
    const back = wrapper.findAll('button').find((button) => button.text() === 'All Prompts')
    await back!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('admin-prompts')

    await router.push('/admin/prompts/not-a-number/edit')
    await flushPromises()
    expect(wrapper.text()).toContain('Invalid Prompt ID')
    expect(wrapper.text()).toContain('All Prompts')
    wrapper.unmount()
  })

  it('keeps the editor recoverable and blocks save when reference or default Price data fails', async () => {
    const base = createNewPromptFetchMock()
    let taxonomyRequestCount = 0
    let slotsFailed = true
    let defaultPriceFailed = true
    const taxonomyRetry = deferred<Response>()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prompts/categories') {
        taxonomyRequestCount += 1
        if (taxonomyRequestCount === 1) {
          return Promise.resolve(jsonResponse({ message: 'Taxonomy unavailable' }, { status: 503 }))
        }
        if (taxonomyRequestCount === 2) {
          return taxonomyRetry.promise
        }
      }
      if (input === '/api/admin/prompts/slot-types' && slotsFailed) {
        slotsFailed = false
        return Promise.resolve(jsonResponse({ message: 'Slots unavailable' }, { status: 503 }))
      }
      if (input === '/api/admin/prices/default' && defaultPriceFailed) {
        defaultPriceFailed = false
        return Promise.resolve(
          jsonResponse({ message: 'Default Price unavailable' }, { status: 503 }),
        )
      }
      return base.fetchMock(input, init)
    })
    const { wrapper } = await mountRoutedEditor(fetchMock, '/admin/prompts/new')

    expect(wrapper.find('#prompt-title').exists()).toBe(true)
    expect(wrapper.text()).toContain('Prompt taxonomy is unavailable')
    expect(wrapper.text()).toContain('Prompt Slot references are unavailable')
    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Create Prompt')
    expect(createButton!.attributes('disabled')).toBeDefined()

    const retryButtons = wrapper.findAll('button').filter((button) => button.text() === 'Try again')
    await retryButtons[0]!.trigger('click')
    await flushPromises()
    expect(createButton!.attributes('disabled')).toBeDefined()
    taxonomyRetry.resolve(
      jsonResponse({ items: [{ id: 1, name: 'People', position: 1, active: true }] }),
    )
    await retryButtons[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Prompt taxonomy is unavailable')
    expect(wrapper.text()).not.toContain('Prompt Slot references are unavailable')

    await openTab(wrapper, 'Price')
    expect(wrapper.text()).toContain('Default Price unavailable')
    expect(createButton!.attributes('disabled')).toBeDefined()
    const priceRetry = wrapper.findAll('button').find((button) => button.text() === 'Try again')
    await priceRetry!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Default Price unavailable')
    wrapper.unmount()
  })

  it('blocks saving until the current debounced Price calculation succeeds', async () => {
    vi.useFakeTimers()
    const base = createFetchMock()
    const calculation = deferred<Response>()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prices/calculate' && init?.method === 'POST') {
        return calculation.promise
      }
      return base.fetchMock(input, init)
    })
    const { wrapper } = await mountRoutedEditor(fetchMock)
    await openTab(wrapper, 'Price')
    wrapper.findComponent(AdminPriceEditor).vm.$emit('salesTotalChange', '12,00')
    await flushPromises()

    const saveButton = wrapper.findAll('button').find((button) => button.text() === 'Save Prompt')
    expect(saveButton!.attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) => input === '/api/admin/prompts/7' && init?.method === 'PUT',
      ),
    ).toBe(false)

    await vi.advanceTimersByTimeAsync(350)
    calculation.resolve(jsonResponse({ ...priceDto(), salesTotalInputCents: 1200 }))
    await flushPromises()
    expect(saveButton!.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('uses structured save-error metadata to select the affected tab', async () => {
    const base = createFetchMock()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prompts/7' && init?.method === 'PUT') {
        return Promise.resolve(
          jsonResponse(
            {
              message: 'Sales total input must not be negative',
              code: 'invalid_price_request',
            },
            { status: 400 },
          ),
        )
      }
      return base.fetchMock(input, init)
    })
    const { wrapper } = await mountRoutedEditor(fetchMock)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('Price')
    expect(wrapper.text()).toContain('Sales total input must not be negative')
    wrapper.unmount()
  })

  it('recovers independently from Prompt detail and Price calculation failures', async () => {
    const editBase = createFetchMock()
    let detailFailed = true
    const detailFetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prompts/7' && !init && detailFailed) {
        detailFailed = false
        return Promise.resolve(
          jsonResponse({ message: 'Prompt detail unavailable' }, { status: 503 }),
        )
      }
      return editBase.fetchMock(input, init)
    })
    const detailEditor = await mountRoutedEditor(detailFetch)

    expect(detailEditor.wrapper.text()).toContain('Prompt could not be loaded')
    expect(detailEditor.wrapper.find('#prompt-title').exists()).toBe(false)
    const detailRetry = detailEditor.wrapper
      .findAll('button')
      .find((button) => button.text() === 'Try again')
    await detailRetry!.trigger('click')
    await flushPromises()
    expect(detailEditor.wrapper.find('#prompt-title').exists()).toBe(true)
    detailEditor.wrapper.unmount()

    resetApiClientForTests()
    const createBase = createNewPromptFetchMock()
    let calculationFailed = true
    const calculationFetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/admin/prices/calculate' && init?.method === 'POST') {
        if (calculationFailed) {
          calculationFailed = false
          return Promise.resolve(
            jsonResponse({ message: 'Price calculation unavailable' }, { status: 503 }),
          )
        }
        return Promise.resolve(jsonResponse({ ...priceDto(), id: null }))
      }
      return createBase.fetchMock(input, init)
    })
    const priceEditor = await mountRoutedEditor(calculationFetch, '/admin/prompts/new')
    await openTab(priceEditor.wrapper, 'Price')
    vi.useFakeTimers()
    priceEditor.wrapper.findComponent(AdminPriceEditor).vm.$emit('salesTotalChange', '2,00')
    await openTab(priceEditor.wrapper, 'Prompt')
    await vi.advanceTimersByTimeAsync(351)
    await flushPromises()

    expect(priceEditor.wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('Price')
    expect(priceEditor.wrapper.text()).toContain('Price calculation unavailable')
    const calculateRetry = priceEditor.wrapper
      .findAll('button')
      .find((button) => button.text() === 'Try again')
    await calculateRetry!.trigger('click')
    await flushPromises()
    expect(priceEditor.wrapper.text()).not.toContain('Price calculation unavailable')
    priceEditor.wrapper.unmount()
  })
})
