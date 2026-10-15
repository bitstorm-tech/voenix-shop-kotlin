import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import SpodArticleEditView from '../SpodArticleEditView.vue'
import type {
  AdminSpodArticleDto,
  AdminSpodVariantDto,
  SaveAdminSpodArticleRequest,
} from '@/stores/admin/spodArticles'
import type { AdminPriceDto, AdminPriceInputDto, PriceVatDto } from '@/stores/admin/prices'
import { InvalidArticleRequestError } from '@/stores/admin/articles'

const mocks = vi.hoisted(() => {
  return {
    toast: vi.fn(),
    fetchArticle: vi.fn(),
    updateArticle: vi.fn(),
    deleteArticle: vi.fn(),
    fetchDefaultPrice: vi.fn(),
    calculatePrice: vi.fn(),
    fetchVatAll: vi.fn(),
    vats: [] as PriceVatDto[],
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/spodArticles', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/spodArticles')>()

  return {
    ...actual,
    useAdminSpodArticlesStore: () => ({
      fetchArticle: mocks.fetchArticle,
      updateArticle: mocks.updateArticle,
      deleteArticle: mocks.deleteArticle,
    }),
  }
})

vi.mock('@/stores/admin/prices', () => ({
  fetchDefaultPrice: mocks.fetchDefaultPrice,
  calculatePrice: mocks.calculatePrice,
}))

vi.mock('@/stores/admin/vat', () => ({
  useAdminVatStore: () => ({ vats: mocks.vats, fetchAll: mocks.fetchVatAll }),
}))

vi.mock('@/stores/admin/articleCategories', () => ({
  useAdminArticleCategoriesStore: () => ({ categories: [], fetchCategories: vi.fn() }),
}))

vi.mock('@/stores/admin/articleSubcategories', () => ({
  useAdminArticleSubcategoriesStore: () => ({ subcategories: [], fetchSubcategories: vi.fn() }),
}))

vi.mock('@/stores/admin/suppliers', () => ({
  useAdminSuppliersStore: () => ({ suppliers: [], fetchSuppliers: vi.fn() }),
}))

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
    regularSalesMargin: { net: 0, tax: 0, gross: 0 },
    calculatedRegularSalesMarginPercent: 0,
    regularSalesTotal: { net: 1000, tax: 190, gross: 1190 },
    discount: null,
    salesDiscount: { net: 0, tax: 0, gross: 0 },
    salesMargin: { net: 0, tax: 0, gross: 0 },
    calculatedSalesMarginPercent: 0,
    salesTotal: { net: 1000, tax: 190, gross: 1190 },
    ...overrides,
  }
}

function variant(overrides: Partial<AdminSpodVariantDto> = {}): AdminSpodVariantDto {
  return {
    id: 1,
    name: 'Black / M',
    appearanceId: 3,
    sizeLabel: 'M',
    spodProductTypeId: 812,
    spodAppearanceId: 3,
    spodSizeId: 5,
    spodVariantId: 'v-812-3-5',
    sku: 'SKU-1',
    isDefault: true,
    active: true,
    ...overrides,
  }
}

function spodArticle(overrides: Partial<AdminSpodArticleDto> = {}): AdminSpodArticleDto {
  return {
    id: 10,
    position: 1,
    name: 'Classic tee',
    descriptionShort: 'Short',
    descriptionLong: 'Long',
    active: false,
    categoryId: null,
    subcategoryId: null,
    supplierId: 4,
    productTypeName: "Men's T-Shirt",
    sizeChartImageFilename: 'chart.webp',
    printFrame: { leftPct: 20, topPct: 15, widthPct: 60, heightPct: 30 },
    hotspot: 'front',
    appearances: [
      {
        id: 3,
        colorName: 'Black',
        colorHex: '#000000',
        position: 1,
        mockupImageFilename: 'black-mockup.webp',
        listingImageFilenames: ['black-1.webp', 'black-2.webp'],
      },
      {
        id: 4,
        colorName: 'White',
        colorHex: '#ffffff',
        position: 2,
        mockupImageFilename: 'white-mockup.webp',
        listingImageFilenames: [],
      },
    ],
    spodVariants: [
      variant(),
      variant({
        id: 2,
        name: 'White / L',
        appearanceId: 4,
        sizeLabel: 'L',
        spodAppearanceId: 4,
        spodSizeId: 6,
        spodVariantId: 'v-812-4-6',
        sku: null,
        isDefault: false,
        active: false,
      }),
    ],
    price: null,
    sync: {
      spodArticleId: 'A-77',
      environment: 'STAGING',
      syncedAt: '2026-08-20T08:30:00Z',
      missingSince: null,
    },
    ...overrides,
  }
}

function createArticleRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles/spod',
        name: 'admin-spod-articles',
        component: { template: '<div>Article list</div>' },
      },
      {
        path: '/admin/articles/spod/:id/edit',
        name: 'admin-spod-article-edit',
        component: SpodArticleEditView,
      },
    ],
  })
}

async function mountEditView(path: string) {
  const router = createArticleRouter()
  await router.push(path)
  await router.isReady()

  const wrapper = mount(SpodArticleEditView, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
  await flushPromises()

  return { wrapper, router }
}

describe('SpodArticleEditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    mocks.fetchArticle.mockReset()
    mocks.updateArticle.mockReset()
    mocks.deleteArticle.mockReset()
    mocks.fetchDefaultPrice.mockReset()
    mocks.calculatePrice.mockReset()
    mocks.fetchVatAll.mockReset()
    mocks.vats = [standardVat]
    mocks.fetchDefaultPrice.mockResolvedValue(priceDto())
    mocks.calculatePrice.mockImplementation(async (payload: AdminPriceInputDto) =>
      priceDto({ ...payload, purchaseVat: standardVat, salesVat: standardVat }),
    )
    mocks.fetchArticle.mockResolvedValue(spodArticle())
  })

  it('loads an article from the SPOD route and shows its name and product type', async () => {
    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')

    expect(mocks.fetchArticle).toHaveBeenCalledWith(10)
    expect(wrapper.find('h1').text()).toBe('Edit SPOD article (Classic tee)')
    const identity = wrapper.get('[data-testid="article-identity"]').text()
    expect(identity).toContain('Classic tee')
    expect(identity).toContain("Men's T-Shirt")
  })

  it('shows the synced half read-only, with the inactive variants collapsed', async () => {
    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')

    const spodSection = wrapper.get('[data-testid="spod-section"]')
    expect(spodSection.text()).toContain('Read-only')
    const spodIdentity = wrapper.get('[data-testid="spod-identity"]')
    expect(spodIdentity.text()).toContain('Classic tee')
    expect(spodIdentity.text()).toContain('A-77')
    expect(spodIdentity.text()).toContain('STAGING')
    expect(wrapper.get('[data-testid="spod-synced-at"]').text()).toContain('Aug 20, 2026')

    // The active variant is listed with the partner's own names for the row.
    const activeRow = wrapper.get('[data-testid="spod-variant-1"]')
    expect(activeRow.text()).toContain('Black')
    expect(activeRow.text()).toContain('v-812-3-5')
    expect(activeRow.text()).toContain('SKU-1')
    expect(activeRow.text()).toContain('812 / 3 / 5')
    expect(activeRow.text()).toContain('Default')
    expect(activeRow.get('img').attributes('src')).toContain(
      'articles/spod/listing-images/black-1.webp',
    )

    // The inactive one is behind the toggle and, once open, shows the same columns as the active
    // one — the same row an operator has to find again in the backoffice.
    expect(wrapper.find('[data-testid="spod-variant-2"]').exists()).toBe(false)
    await wrapper.get('[data-testid="inactive-variants"]').trigger('click')
    await flushPromises()
    const inactiveRow = wrapper.get('[data-testid="spod-variant-2"]')
    expect(inactiveRow.text()).toContain('White')
    expect(inactiveRow.text()).toContain('L')
    expect(inactiveRow.text()).toContain('v-812-4-6')
    expect(inactiveRow.text()).toContain('812 / 4 / 6')
    expect(inactiveRow.text()).toContain('Inactive')

    expect(wrapper.find('#article-name').exists()).toBe(false)
    expect(wrapper.find('[data-testid="size-chart-upload"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="size-chart-preview"]').attributes('src')).toBe(
      '/api/images/public/400/articles/spod/size-charts/chart.webp',
    )
  })

  it('offers only the active variants as the default one', async () => {
    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')

    await wrapper.get('[data-testid="default-variant-select"]').trigger('keydown', { key: 'Enter' })
    await flushPromises()

    const options = [...document.body.querySelectorAll('[role="option"]')].map((option) =>
      option.textContent?.trim(),
    )
    expect(options).toEqual(['No default variant', 'Black / M'])
  })

  it('draws the measured frame read-only on the mockup of the default colour', async () => {
    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')

    const print = wrapper.get('[data-testid="print-section"]')
    expect(print.text()).toContain('Read-only')
    expect(print.text()).toContain('front')
    expect(print.get('[data-testid="print-frame-mockup"]').attributes('src')).toBe(
      '/api/images/public/1000/articles/spod/mockups/black-mockup.webp',
    )
    const style = print.get('[data-testid="print-frame-rectangle"]').attributes('style')
    expect(style).toContain('left: 20%')
    expect(style).toContain('top: 15%')
    expect(style).toContain('width: 60%')
    expect(style).toContain('height: 30%')
    expect(print.find('input').exists()).toBe(false)
    expect(print.find('[data-testid="print-frame-missing"]').exists()).toBe(false)
  })

  it('draws the frame on a blank square when the default colour has no mockup', async () => {
    const article = spodArticle()
    article.appearances[0]!.mockupImageFilename = null
    mocks.fetchArticle.mockResolvedValue(article)

    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')

    expect(wrapper.find('[data-testid="print-frame-mockup"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="print-frame-rectangle"]').exists()).toBe(true)
  })

  it('submits the shop-owned half and nothing else', async () => {
    mocks.updateArticle.mockResolvedValue(spodArticle())

    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).toHaveBeenCalledOnce()
    expect(mocks.updateArticle.mock.calls[0]![0]).toBe(10)
    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminSpodArticleRequest
    expect(payload).toEqual({
      active: false,
      categoryId: null,
      subcategoryId: null,
      defaultVariantId: 1,
    })
  })

  it('drops a default variant the last run deactivated instead of submitting it back', async () => {
    mocks.updateArticle.mockResolvedValue(spodArticle())
    mocks.fetchArticle.mockResolvedValue(
      // The partner deactivated every colour, the stored default among them.
      spodArticle({
        spodVariants: [
          variant({ active: false }),
          variant({ id: 2, isDefault: false, active: false }),
        ],
      }),
    )

    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminSpodArticleRequest
    expect(payload.defaultVariantId).toBeNull()
  })

  it('refuses to activate an article the partner no longer lists', async () => {
    mocks.fetchArticle.mockResolvedValue(
      spodArticle({
        categoryId: 3,
        sync: {
          spodArticleId: 'A-77',
          environment: 'STAGING',
          syncedAt: '2026-08-20T08:30:00Z',
          missingSince: '2026-08-21T08:30:00Z',
        },
      }),
    )

    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')
    expect(wrapper.get('[data-testid="missing-alert"]').text()).toContain(
      'no longer lists this article',
    )

    await wrapper.get('#article-active').trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(
      'An article that is missing at Spreadconnect cannot be activated.',
    )
  })

  // The backend owns the "no frame, no activation" rule, so the form lets the admin try and shows
  // the refusal on the Active field.
  it('explains an unmeasured frame and shows the refused activation on the Active field', async () => {
    mocks.updateArticle.mockRejectedValue(
      new InvalidArticleRequestError('Validation failed', {
        active: ['An article without a measured print frame cannot be activated'],
      }),
    )
    mocks.fetchArticle.mockResolvedValue(
      spodArticle({ categoryId: 3, printFrame: null, hotspot: null, price: priceDto() }),
    )

    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')
    expect(wrapper.get('[data-testid="print-frame-missing"]').text()).toContain(
      'The last sync could not measure the print frame; see the sync report. The article cannot be activated.',
    )
    expect(wrapper.find('[data-testid="print-frame-rectangle"]').exists()).toBe(false)

    const activeCheckbox = wrapper.get('#article-active')
    expect(activeCheckbox.attributes('disabled')).toBeUndefined()
    await activeCheckbox.trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).toHaveBeenCalledOnce()
    expect((mocks.updateArticle.mock.calls[0]![1] as SaveAdminSpodArticleRequest).active).toBe(true)
    const activeField = activeCheckbox.element.parentElement!
    expect(activeField.textContent).toContain(
      'An article without a measured print frame cannot be activated',
    )
  })

  it('deletes through the SPOD route', async () => {
    mocks.deleteArticle.mockResolvedValue(undefined)

    const { wrapper } = await mountEditView('/admin/articles/spod/10/edit')
    const deleteButton = wrapper.findAll('button').find((button) => button.text() === 'Delete')
    await deleteButton!.trigger('click')
    await flushPromises()
    const confirmButton = document.body.querySelector(
      '[data-testid="confirm-delete-article"]',
    ) as HTMLElement | null
    confirmButton?.click()
    await flushPromises()

    expect(mocks.deleteArticle).toHaveBeenCalledWith(10)
  })
})
