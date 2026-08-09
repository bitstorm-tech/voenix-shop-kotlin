import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ArticleEditView from '../ArticleEditView.vue'
import type { AdminArticleDto, SaveAdminArticleRequest } from '@/stores/admin/articles'
import type { AdminPriceDto, AdminPriceInputDto, PriceVatDto } from '@/stores/admin/prices'

const mocks = vi.hoisted(() => {
  class ArticleNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleNotFoundError'
    }
  }

  class InvalidArticleRequestError extends Error {
    readonly fieldErrors: Record<string, string[]>

    constructor(message: string, fieldErrors: Record<string, string[]> = {}) {
      super(message)
      this.name = 'InvalidArticleRequestError'
      this.fieldErrors = fieldErrors
    }

    fieldError(field: string): string | null {
      return this.fieldErrors[field]?.[0] ?? null
    }
  }

  return {
    toast: vi.fn(),
    fetchArticle: vi.fn(),
    createArticle: vi.fn(),
    updateArticle: vi.fn(),
    deleteArticle: vi.fn(),
    uploadVariantExampleImage: vi.fn(),
    fetchDefaultPrice: vi.fn(),
    calculatePrice: vi.fn(),
    fetchVatAll: vi.fn(),
    vats: [] as PriceVatDto[],
    articleCategories: [] as Array<{
      id: number
      name: string
      description: string | null
      position: number
      active: boolean
    }>,
    articleSubcategories: [] as Array<{
      id: number
      categoryId: number
      name: string
      description: string | null
      exampleImageFilename: string | null
      position: number
      active: boolean
    }>,
    ArticleNotFoundError,
    InvalidArticleRequestError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/articles', () => ({
  useAdminArticlesStore: () => ({
    fetchArticle: mocks.fetchArticle,
    createArticle: mocks.createArticle,
    updateArticle: mocks.updateArticle,
    deleteArticle: mocks.deleteArticle,
    uploadVariantExampleImage: mocks.uploadVariantExampleImage,
  }),
  ArticleNotFoundError: mocks.ArticleNotFoundError,
  InvalidArticleRequestError: mocks.InvalidArticleRequestError,
}))

vi.mock('@/stores/admin/prices', () => ({
  fetchDefaultPrice: mocks.fetchDefaultPrice,
  calculatePrice: mocks.calculatePrice,
}))

vi.mock('@/stores/admin/vat', () => ({
  useAdminVatStore: () => ({
    vats: mocks.vats,
    fetchAll: mocks.fetchVatAll,
  }),
}))

vi.mock('@/stores/admin/articleCategories', () => ({
  useAdminArticleCategoriesStore: () => ({
    categories: mocks.articleCategories,
    fetchCategories: vi.fn(),
  }),
}))

vi.mock('@/stores/admin/articleSubcategories', () => ({
  useAdminArticleSubcategoriesStore: () => ({
    subcategories: mocks.articleSubcategories,
    fetchSubcategories: vi.fn(),
  }),
}))

vi.mock('@/stores/admin/suppliers', () => ({
  useAdminSuppliersStore: () => ({
    suppliers: [],
    fetchSuppliers: vi.fn(),
  }),
}))

const EXAMPLE_IMAGE_FILENAME = '11111111-2222-3333-4444-555555555555.png'
const standardVat: PriceVatDto = { id: 1, name: 'Standard', percent: 19 }

function priceDto(overrides: Partial<AdminPriceDto> = {}): AdminPriceDto {
  return {
    id: null,
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
    purchaseVat: standardVat,
    purchasePrice: { net: 0, tax: 0, gross: 0 },
    purchaseCost: { net: 0, tax: 0, gross: 0 },
    calculatedPurchaseCostPercent: 0,
    purchaseTotal: { net: 0, tax: 0, gross: 0 },
    salesVat: standardVat,
    salesMargin: { net: 0, tax: 0, gross: 0 },
    calculatedSalesMarginPercent: 0,
    salesTotal: { net: 1000, tax: 190, gross: 1190 },
    ...overrides,
  }
}

const defaultPrice = priceDto()

const mugArticle: AdminArticleDto = {
  id: 10,
  position: 1,
  name: 'Classic Mug',
  descriptionShort: 'Short',
  descriptionLong: 'Long',
  active: false,
  categoryId: null,
  subcategoryId: null,
  supplierId: null,
  supplierArticleName: null,
  supplierArticleNumber: null,
  mugDetails: null,
  mugVariants: [
    {
      id: 1,
      name: 'White',
      insideColorCode: '#ffffff',
      outsideColorCode: '#ffffff',
      isDefault: true,
      active: true,
      exampleImageFilename: EXAMPLE_IMAGE_FILENAME,
    },
    {
      id: 2,
      name: 'Black',
      insideColorCode: '#000000',
      outsideColorCode: '#000000',
      isDefault: false,
      active: true,
      exampleImageFilename: null,
    },
  ],
  price: null,
}

const completeMugDetails = {
  heightMm: 95,
  diameterMm: 82,
  printTemplateWidthMm: 200,
  printTemplateHeightMm: 90,
  fillingQuantity: '300 ml',
  dishwasherSafe: true,
  documentFormatWidthMm: null,
  documentFormatHeightMm: null,
  documentFormatMarginBottomMm: null,
}

const mugCategory = {
  id: 1,
  name: 'Mugs',
  description: null,
  position: 1,
  active: true,
}

function createArticleRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles',
        name: 'admin-articles',
        component: { template: '<div>Article list</div>' },
      },
      {
        path: '/admin/articles/new',
        name: 'admin-article-new',
        component: ArticleEditView,
      },
      {
        path: '/admin/articles/:id',
        name: 'admin-article-edit',
        component: ArticleEditView,
      },
    ],
  })
}

async function mountArticleEditView(path: string) {
  const router = createArticleRouter()

  await router.push(path)
  await router.isReady()

  const wrapper = mount(ArticleEditView, {
    attachTo: document.body,
    global: {
      plugins: [router],
    },
  })

  await flushPromises()

  return { wrapper, router }
}

async function openVariantsTab(
  wrapper: Awaited<ReturnType<typeof mountArticleEditView>>['wrapper'],
) {
  const variantsTab = wrapper.findAll('button').find((button) => button.text() === 'Variants')
  expect(variantsTab).toBeDefined()
  await variantsTab!.trigger('mousedown', { button: 0 })
  await variantsTab!.trigger('click')
  await flushPromises()
}

async function openPriceTab(wrapper: Awaited<ReturnType<typeof mountArticleEditView>>['wrapper']) {
  const priceTab = wrapper.findAll('button').find((button) => button.text() === 'Price Calculation')
  expect(priceTab).toBeDefined()
  await priceTab!.trigger('mousedown', { button: 0 })
  await priceTab!.trigger('click')
  await flushPromises()
}

async function fillRequiredGeneral(
  wrapper: Awaited<ReturnType<typeof mountArticleEditView>>['wrapper'],
) {
  await wrapper.find('#article-name').setValue('New Mug')
  await wrapper.find('#article-description-short').setValue('Short')
  await wrapper.find('#article-description-long').setValue('Long')
}

describe('ArticleEditView', () => {
  beforeEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    mocks.fetchArticle.mockReset()
    mocks.createArticle.mockReset()
    mocks.updateArticle.mockReset()
    mocks.deleteArticle.mockReset()
    mocks.uploadVariantExampleImage.mockReset()
    mocks.fetchDefaultPrice.mockReset()
    mocks.calculatePrice.mockReset()
    mocks.fetchVatAll.mockReset()
    mocks.vats = [standardVat]
    mocks.articleCategories = []
    mocks.articleSubcategories = []
    mocks.fetchDefaultPrice.mockResolvedValue(defaultPrice)
    mocks.calculatePrice.mockImplementation(async (payload: AdminPriceInputDto) =>
      priceDto({
        ...payload,
        purchaseVat: standardVat,
        salesVat: standardVat,
      }),
    )
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the loaded article name in the edit heading', async () => {
    mocks.fetchArticle.mockResolvedValue(mugArticle)

    const { wrapper } = await mountArticleEditView('/admin/articles/10')

    expect(wrapper.find('h1').text()).toBe('Edit Article (Classic Mug)')
  })

  it('carries the list filter query on every navigation back to the article list', async () => {
    mocks.fetchArticle.mockResolvedValue(mugArticle)
    mocks.updateArticle.mockResolvedValue(mugArticle)

    const { wrapper, router } = await mountArticleEditView(
      '/admin/articles/10?status=inactive&name=mug',
    )

    const backLinks = wrapper
      .findAll('a')
      .filter((link) => ['Back to Articles', 'Cancel'].includes(link.text()))
    expect(backLinks).toHaveLength(2)
    for (const link of backLinks) {
      expect(link.attributes('href')).toBe('/admin/articles?status=inactive&name=mug')
    }

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-articles')
    expect(router.currentRoute.value.query).toEqual({ status: 'inactive', name: 'mug' })
  })

  it('keeps an inactive category structure selectable and labels it in article selectors', async () => {
    const inactiveCategory = {
      id: 1,
      name: 'Staged Mugs',
      description: null,
      position: 1,
      active: false,
    }
    mocks.articleCategories = [inactiveCategory]
    mocks.articleSubcategories = [
      {
        id: 10,
        categoryId: inactiveCategory.id,
        name: 'Staged Espresso',
        description: null,
        exampleImageFilename: null,
        position: 1,
        active: false,
      },
    ]
    mocks.fetchArticle.mockResolvedValue({
      ...mugArticle,
      categoryId: 1,
      subcategoryId: 10,
    })

    const { wrapper } = await mountArticleEditView('/admin/articles/10')

    await wrapper.get('#article-category').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Staged Mugs (Inactive)')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    await wrapper.get('#article-subcategory').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Staged Espresso (Inactive)')
  })

  it('renders a thumbnail for variants with an example image and an em dash otherwise', async () => {
    mocks.fetchArticle.mockResolvedValue(mugArticle)

    const { wrapper } = await mountArticleEditView('/admin/articles/10')
    await openVariantsTab(wrapper)

    const thumbnails = wrapper.findAll('[data-testid="variant-example-image-thumbnail"]')
    expect(thumbnails).toHaveLength(1)
    expect(thumbnails[0]!.attributes('src')).toBe(
      `/api/images/public/200/articles/mugs/variant-example-images/${EXAMPLE_IMAGE_FILENAME}`,
    )

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[1]!.text()).toContain('—')
  })

  it('includes exampleImageFilename per variant in the save payload', async () => {
    mocks.fetchArticle.mockResolvedValue(mugArticle)
    mocks.updateArticle.mockResolvedValue(mugArticle)

    const { wrapper } = await mountArticleEditView('/admin/articles/10')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).toHaveBeenCalledOnce()
    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminArticleRequest
    expect(payload.mugVariants).toEqual([
      {
        id: 1,
        name: 'White',
        insideColorCode: '#ffffff',
        outsideColorCode: '#ffffff',
        isDefault: true,
        active: true,
        exampleImageFilename: EXAMPLE_IMAGE_FILENAME,
      },
      {
        id: 2,
        name: 'Black',
        insideColorCode: '#000000',
        outsideColorCode: '#000000',
        isDefault: false,
        active: true,
        exampleImageFilename: null,
      },
    ])
  })

  it('omits price when creating an article with untouched pricing', async () => {
    mocks.createArticle.mockResolvedValue({ ...mugArticle, id: 20, name: 'New Mug' })

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await fillRequiredGeneral(wrapper)

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).toHaveBeenCalledOnce()
    const payload = mocks.createArticle.mock.calls[0]![0] as SaveAdminArticleRequest
    expect(payload.price).toBeUndefined()
  })

  it('includes the current price payload when saving an existing priced article', async () => {
    const currentPrice = priceDto({
      id: 5,
      purchasePriceInputCents: 700,
      salesTotalInputCents: 1490,
      salesTotal: { net: 1252, tax: 238, gross: 1490 },
    })
    const pricedArticle = { ...mugArticle, price: currentPrice }
    mocks.fetchArticle.mockResolvedValue(pricedArticle)
    mocks.updateArticle.mockResolvedValue(pricedArticle)

    const { wrapper } = await mountArticleEditView('/admin/articles/10')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).toHaveBeenCalledOnce()
    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminArticleRequest
    expect(payload.price).toEqual({
      purchaseVatId: 1,
      purchaseCalculationMode: 'NET',
      purchaseActiveRow: 'COST',
      purchasePriceInputCents: 700,
      purchaseCostInputCents: 0,
      purchaseCostPercent: 0,
      salesVatId: 1,
      salesCalculationMode: 'GROSS',
      salesActiveRow: 'TOTAL',
      salesMarginInputCents: 0,
      salesMarginPercent: 0,
      salesTotalInputCents: 1490,
    })
  })

  it('includes price when creating a new article after a pricing input changes', async () => {
    mocks.createArticle.mockResolvedValue({ ...mugArticle, id: 20, name: 'New Mug' })

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await fillRequiredGeneral(wrapper)
    await openPriceTab(wrapper)
    const purchasePriceInput = wrapper.find('[data-testid="price-purchase-price-net"]')
    await purchasePriceInput.setValue('12,34')
    await purchasePriceInput.trigger('blur')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).toHaveBeenCalledOnce()
    const payload = mocks.createArticle.mock.calls[0]![0] as SaveAdminArticleRequest
    expect(payload.price?.purchasePriceInputCents).toBe(1234)
  })

  it('commits a focused price input before saving', async () => {
    mocks.createArticle.mockResolvedValue({ ...mugArticle, id: 20, name: 'New Mug' })

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await fillRequiredGeneral(wrapper)
    await openPriceTab(wrapper)
    const purchasePriceInput = wrapper.find('[data-testid="price-purchase-price-net"]')
    await purchasePriceInput.setValue('12,34')
    await purchasePriceInput.trigger('keydown.enter')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).toHaveBeenCalledOnce()
    const payload = mocks.createArticle.mock.calls[0]![0] as SaveAdminArticleRequest
    expect(payload.price?.purchasePriceInputCents).toBe(1234)
  })

  it('calculates a changed price only after the input loses focus', async () => {
    vi.useFakeTimers()

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await openPriceTab(wrapper)
    mocks.calculatePrice.mockClear()

    const purchasePriceInput = wrapper.find('[data-testid="price-purchase-price-net"]')
    await purchasePriceInput.setValue('12,34')
    await vi.runAllTimersAsync()
    await flushPromises()

    expect(mocks.calculatePrice).not.toHaveBeenCalled()

    await purchasePriceInput.trigger('blur')
    await vi.runAllTimersAsync()
    await flushPromises()

    expect(mocks.calculatePrice).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ purchasePriceInputCents: 1234 }),
    )
  })

  it('blocks save when a changed price calculation fails', async () => {
    vi.useFakeTimers()
    mocks.calculatePrice.mockRejectedValueOnce(new Error('Sales total must not be negative'))
    mocks.createArticle.mockResolvedValue({ ...mugArticle, id: 20, name: 'New Mug' })

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await fillRequiredGeneral(wrapper)
    await openPriceTab(wrapper)
    const salesTotalInput = wrapper.find('[data-testid="price-sales-total-gross"]')
    await salesTotalInput.setValue('-1,00')
    await salesTotalInput.trigger('blur')
    await vi.runAllTimersAsync()
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Sales total must not be negative')
  })

  it('clears invalid price field errors when recalculation controls rewrite that field', async () => {
    vi.useFakeTimers()

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await openPriceTab(wrapper)
    mocks.calculatePrice.mockClear()

    const purchasePriceInput = wrapper.find('[data-testid="price-purchase-price-net"]')
    await purchasePriceInput.setValue('invalid')
    await purchasePriceInput.trigger('blur')

    expect(wrapper.text()).toContain('Purchase price must be a valid decimal number.')

    const purchaseGrossModeButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Brutto')
    expect(purchaseGrossModeButton).toBeDefined()
    await purchaseGrossModeButton!.trigger('click')
    await vi.runAllTimersAsync()
    await flushPromises()

    expect(wrapper.text()).not.toContain('Purchase price must be a valid decimal number.')
    expect(mocks.calculatePrice).toHaveBeenCalledWith(
      expect.objectContaining({ purchaseCalculationMode: 'GROSS' }),
    )
  })

  it('allows saving a new article without price when no VAT is configured', async () => {
    mocks.vats = []
    mocks.fetchDefaultPrice.mockRejectedValueOnce(new Error('No VAT is configured'))
    mocks.createArticle.mockResolvedValue({ ...mugArticle, id: 20, name: 'New Mug' })

    const { wrapper } = await mountArticleEditView('/admin/articles/new')
    await fillRequiredGeneral(wrapper)

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).toHaveBeenCalledOnce()
    const payload = mocks.createArticle.mock.calls[0]![0] as SaveAdminArticleRequest
    expect(payload.price).toBeUndefined()
  })

  it('does not keep price setup loading after navigating from a pending default to a priced article', async () => {
    const currentPrice = priceDto({
      id: 5,
      purchasePriceInputCents: 700,
      salesTotalInputCents: 1490,
      salesTotal: { net: 1252, tax: 238, gross: 1490 },
    })
    const pricedArticle = { ...mugArticle, price: currentPrice }
    mocks.fetchDefaultPrice.mockReturnValueOnce(new Promise<AdminPriceDto>(() => {}))
    mocks.fetchArticle.mockResolvedValue(pricedArticle)

    const { wrapper, router } = await mountArticleEditView('/admin/articles/new')
    await openPriceTab(wrapper)
    expect(wrapper.text()).toContain('Preisvorlage wird geladen...')

    await router.push('/admin/articles/10')
    await flushPromises()
    await openPriceTab(wrapper)

    expect(wrapper.text()).not.toContain('Preisvorlage wird geladen...')
    expect(wrapper.find('[data-testid="price-sales-total-gross"]').exists()).toBe(true)
  })

  it('refuses to save an active mug without a category', async () => {
    mocks.articleCategories = [mugCategory]
    mocks.fetchArticle.mockResolvedValue({
      ...mugArticle,
      active: true,
      mugDetails: completeMugDetails,
    })

    const { wrapper } = await mountArticleEditView('/admin/articles/10')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('An active article requires a category.')
  })

  it('refuses to save an active mug without a price', async () => {
    mocks.articleCategories = [mugCategory]
    mocks.fetchArticle.mockResolvedValue({
      ...mugArticle,
      active: true,
      categoryId: mugCategory.id,
      mugDetails: completeMugDetails,
      price: null,
    })

    const { wrapper } = await mountArticleEditView('/admin/articles/10')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('An active article requires a price.')
  })

  it('saves an active mug that has a category, a price, and complete details', async () => {
    const pricedArticle = {
      ...mugArticle,
      active: true,
      categoryId: mugCategory.id,
      mugDetails: completeMugDetails,
      price: priceDto({ id: 5 }),
    }
    mocks.articleCategories = [mugCategory]
    mocks.fetchArticle.mockResolvedValue(pricedArticle)
    mocks.updateArticle.mockResolvedValue(pricedArticle)

    const { wrapper } = await mountArticleEditView('/admin/articles/10')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).toHaveBeenCalledOnce()
    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminArticleRequest
    expect(payload.categoryId).toBe(mugCategory.id)
    expect(payload.mugDetails).toMatchObject({ heightMm: 95, diameterMm: 82 })
  })

  it('shows a rejected reference on the field the backend named', async () => {
    mocks.fetchArticle.mockResolvedValue(mugArticle)
    mocks.updateArticle.mockRejectedValue(
      new mocks.InvalidArticleRequestError('Validation failed', {
        supplierId: ['Supplier does not exist'],
      }),
    )

    const { wrapper } = await mountArticleEditView('/admin/articles/10')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Supplier does not exist')
  })

  it('shows a rejected variant example image on the variant it indexes', async () => {
    mocks.fetchArticle.mockResolvedValue(mugArticle)
    mocks.updateArticle.mockRejectedValue(
      new mocks.InvalidArticleRequestError('Validation failed', {
        'mugVariants[0].exampleImageFilename': ['Example image does not exist'],
      }),
    )

    const { wrapper } = await mountArticleEditView('/admin/articles/10')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const variantErrors = wrapper.findAll('[data-testid="variant-field-error"]')
    expect(variantErrors).toHaveLength(1)
    expect(variantErrors[0]!.text()).toBe('Example image does not exist')
  })
})
