import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import TshirtArticleEditView from '../TshirtArticleEditView.vue'
import type {
  AdminArticleTshirtVariantDto,
  AdminTshirtArticleDto,
  SaveAdminTshirtArticleRequest,
} from '@/stores/admin/tshirtArticles'
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

vi.mock('@/stores/admin/tshirtArticles', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/tshirtArticles')>()

  return {
    ...actual,
    useAdminTshirtArticlesStore: () => ({
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

function variant(
  overrides: Partial<AdminArticleTshirtVariantDto> = {},
): AdminArticleTshirtVariantDto {
  return {
    id: 1,
    name: 'Black / M',
    colorName: 'Black',
    colorHex: '#000000',
    sizeLabel: 'M',
    spodProductTypeId: 812,
    spodAppearanceId: 3,
    spodSizeId: 5,
    spodVariantId: 'v-812-3-5',
    sku: 'SKU-1',
    isDefault: true,
    active: true,
    exampleImageFilename: 'black-m.webp',
    ...overrides,
  }
}

function tshirtArticle(overrides: Partial<AdminTshirtArticleDto> = {}): AdminTshirtArticleDto {
  return {
    id: 10,
    position: 1,
    name: 'Classic Shirt',
    descriptionShort: 'Short',
    descriptionLong: 'Long',
    active: false,
    categoryId: null,
    subcategoryId: null,
    supplierId: 4,
    printAspectRatio: '16:9',
    sizeChartImageFilename: 'chart.webp',
    printFrame: { leftPct: 20, topPct: 15, widthPct: 60, heightPct: 30 },
    tshirtVariants: [
      variant(),
      variant({
        id: 2,
        name: 'White / L',
        colorName: 'White',
        colorHex: '#ffffff',
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
        path: '/admin/articles/tshirts',
        name: 'admin-tshirt-articles',
        component: { template: '<div>Article list</div>' },
      },
      {
        path: '/admin/articles/tshirts/:id/edit',
        name: 'admin-tshirt-article-edit',
        component: TshirtArticleEditView,
      },
    ],
  })
}

async function mountEditView(path: string) {
  const router = createArticleRouter()
  await router.push(path)
  await router.isReady()

  const wrapper = mount(TshirtArticleEditView, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
  await flushPromises()

  return { wrapper, router }
}

describe('TshirtArticleEditView', () => {
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
    mocks.fetchArticle.mockResolvedValue(tshirtArticle())
  })

  it('loads a shirt from the t-shirt route and shows its name in the heading', async () => {
    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')

    expect(mocks.fetchArticle).toHaveBeenCalledWith(10)
    expect(wrapper.find('h1').text()).toBe('Edit T-Shirt (Classic Shirt)')
  })

  it('sums the shirt up in the header: the active switch, the category, the price, the sync', async () => {
    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')

    const summary = wrapper.get('[data-testid="editor-summary"]')
    expect(summary.find('#article-active').exists()).toBe(true)
    expect(wrapper.get('[data-testid="active-badge"]').text()).toBe('Not in shop')
    expect(summary.text()).toContain('Category')
    expect(summary.text()).toContain('11,90 EUR')
    expect(summary.text()).toContain('Aug 20, 2026')
    expect(wrapper.find('[data-testid="spod-missing-badge"]').exists()).toBe(false)

    await wrapper.get('#article-active').trigger('click')
    expect(wrapper.get('[data-testid="active-badge"]').text()).toBe('Visible in shop')

    // The jump list names every section of the page, in reading order.
    const navLabels = wrapper
      .get('[data-testid="form-section-nav"]')
      .findAll('a')
      .map((link) => link.text())
    expect(navLabels).toEqual(['Spreadconnect', 'General', 'Print', 'Price', 'Variants'])
  })

  it('shows the synced half read-only: the identity on top, the variants grouped by colour', async () => {
    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')

    const spodSection = wrapper.get('[data-testid="spod-section"]')
    expect(spodSection.text()).toContain('Read-only')
    const spodIdentity = wrapper.get('[data-testid="spod-identity"]')
    expect(spodIdentity.text()).toContain('Classic Shirt')
    expect(spodIdentity.text()).toContain('A-77')
    expect(spodIdentity.text()).toContain('STAGING')
    expect(wrapper.get('[data-testid="spod-synced-at"]').text()).toContain('Aug 20, 2026')

    // Every colour is a row and every size a chip on it, the inactive one struck through — so the
    // whole garment is visible at once, without a toggle for the sizes the partner dropped.
    const blackRow = wrapper.get('[data-testid="spod-colour-Black"]')
    expect(blackRow.text()).toContain('Black')
    expect(blackRow.get('[data-testid="spod-size-1"]').text()).toBe('M')
    expect(blackRow.text()).toContain('1 of 1 active')
    const whiteRow = wrapper.get('[data-testid="spod-colour-White"]')
    expect(whiteRow.get('[data-testid="spod-size-2"]').classes()).toContain('line-through')
    expect(whiteRow.text()).toContain('Inactive')

    // The partner's identifiers are behind one toggle and then show for every colour in the same
    // columns — the same row an operator has to find again in the backoffice.
    expect(wrapper.find('[data-testid="spod-variant-1"]').exists()).toBe(false)
    await wrapper.get('[data-testid="technical-ids"]').trigger('click')
    await flushPromises()
    const activeRow = wrapper.get('[data-testid="spod-variant-1"]')
    expect(activeRow.text()).toContain('v-812-3-5')
    expect(activeRow.text()).toContain('SKU-1')
    expect(activeRow.text()).toContain('812 / 3 / 5')
    const inactiveRow = wrapper.get('[data-testid="spod-variant-2"]')
    expect(inactiveRow.text()).toContain('L')
    expect(inactiveRow.text()).toContain('v-812-4-6')
    expect(inactiveRow.text()).toContain('812 / 4 / 6')
    expect(inactiveRow.text()).toContain('Inactive')

    expect(wrapper.find('#article-name').exists()).toBe(false)
    expect(wrapper.find('[data-testid="size-chart-upload"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="size-chart-preview"]').attributes('src')).toBe(
      '/api/images/public/400/articles/tshirts/size-charts/chart.webp',
    )
  })

  it('offers only the active variants as the default one', async () => {
    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')

    await wrapper.get('[data-testid="default-variant-select"]').trigger('keydown', { key: 'Enter' })
    await flushPromises()

    const options = [...document.body.querySelectorAll('[role="option"]')].map((option) =>
      option.textContent?.trim(),
    )
    expect(options).toEqual(['No default variant', 'Black / M'])
  })

  it('submits the shop-owned half and nothing else', async () => {
    mocks.updateArticle.mockResolvedValue(tshirtArticle())

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).toHaveBeenCalledOnce()
    expect(mocks.updateArticle.mock.calls[0]![0]).toBe(10)
    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminTshirtArticleRequest
    expect(payload).toEqual({
      active: false,
      categoryId: null,
      subcategoryId: null,
      printAspectRatio: '16:9',
      printFrame: { leftPct: 20, topPct: 15, widthPct: 60, heightPct: 30 },
      defaultVariantId: 1,
    })
  })

  it('drops a default variant the last run deactivated instead of submitting it back', async () => {
    mocks.updateArticle.mockResolvedValue(tshirtArticle())
    mocks.fetchArticle.mockResolvedValue(
      // The partner deactivated every colour, the stored default among them.
      tshirtArticle({
        tshirtVariants: [
          variant({ active: false }),
          variant({ id: 2, isDefault: false, active: false }),
        ],
      }),
    )

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const payload = mocks.updateArticle.mock.calls[0]![1] as SaveAdminTshirtArticleRequest
    expect(payload.defaultVariantId).toBeNull()
  })

  it('refuses to activate a shirt the partner no longer lists', async () => {
    mocks.fetchArticle.mockResolvedValue(
      tshirtArticle({
        categoryId: 3,
        sync: {
          spodArticleId: 'A-77',
          environment: 'STAGING',
          syncedAt: '2026-08-20T08:30:00Z',
          missingSince: '2026-08-21T08:30:00Z',
        },
      }),
    )

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    expect(wrapper.get('[data-testid="missing-alert"]').text()).toContain(
      'no longer lists this article',
    )
    expect(wrapper.get('[data-testid="spod-missing-badge"]').text()).toBe(
      'Missing at Spreadconnect',
    )

    await wrapper.get('#article-active').trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(
      'An article that is missing at Spreadconnect cannot be activated.',
    )
  })

  it('shows a rejected write on the input that caused it', async () => {
    mocks.updateArticle.mockRejectedValue(
      new InvalidArticleRequestError('Validation failed', {
        'printFrame.widthPct': ['LeftPct plus WidthPct must be at most 100'],
      }),
    )

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('LeftPct plus WidthPct must be at most 100')
  })

  it('deletes through the t-shirt route', async () => {
    mocks.deleteArticle.mockResolvedValue(undefined)

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
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
