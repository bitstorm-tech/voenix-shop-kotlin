import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import TshirtArticleEditView from '../TshirtArticleEditView.vue'
import type {
  AdminTshirtArticleDto,
  SaveAdminTshirtArticleRequest,
} from '@/stores/admin/tshirtArticles'
import type { AdminPriceDto, AdminPriceInputDto, PriceVatDto } from '@/stores/admin/prices'
import { InvalidArticleRequestError } from '@/stores/admin/articles'

const mocks = vi.hoisted(() => {
  return {
    toast: vi.fn(),
    fetchArticle: vi.fn(),
    createArticle: vi.fn(),
    updateArticle: vi.fn(),
    deleteArticle: vi.fn(),
    uploadVariantExampleImage: vi.fn(),
    uploadSizeChartImage: vi.fn(),
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
      createArticle: mocks.createArticle,
      updateArticle: mocks.updateArticle,
      deleteArticle: mocks.deleteArticle,
      uploadVariantExampleImage: mocks.uploadVariantExampleImage,
      uploadSizeChartImage: mocks.uploadSizeChartImage,
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
    salesMargin: { net: 0, tax: 0, gross: 0 },
    calculatedSalesMarginPercent: 0,
    salesTotal: { net: 1000, tax: 190, gross: 1190 },
    ...overrides,
  }
}

const tshirtArticle: AdminTshirtArticleDto = {
  id: 10,
  position: 1,
  name: 'Classic Shirt',
  descriptionShort: 'Short',
  descriptionLong: 'Long',
  active: false,
  categoryId: null,
  subcategoryId: null,
  supplierId: null,
  printAspectRatio: '16:9',
  sizeChartImageFilename: 'chart.webp',
  printFrame: { leftPct: 20, topPct: 15, widthPct: 60, heightPct: 30 },
  tshirtVariants: [
    {
      id: 1,
      name: 'Black / M',
      colorName: 'Black',
      colorHex: '#000000',
      sizeLabel: 'M',
      spodProductTypeId: 812,
      spodAppearanceId: 3,
      spodSizeId: 5,
      isDefault: true,
      active: true,
      exampleImageFilename: 'black-m.webp',
    },
  ],
  price: null,
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
        path: '/admin/articles/tshirts/new',
        name: 'admin-tshirt-article-new',
        component: TshirtArticleEditView,
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

type Wrapper = Awaited<ReturnType<typeof mountEditView>>['wrapper']

async function openTab(wrapper: Wrapper, label: string) {
  const tab = wrapper.findAll('button').find((button) => button.text() === label)
  expect(tab).toBeDefined()
  await tab!.trigger('mousedown', { button: 0 })
  await tab!.trigger('click')
  await flushPromises()
}

async function fillRequiredGeneral(wrapper: Wrapper) {
  await wrapper.find('#article-name').setValue('New Shirt')
  await wrapper.find('#article-description-short').setValue('Short')
  await wrapper.find('#article-description-long').setValue('Long')
}

async function generateMatrix(
  wrapper: Wrapper,
  colors: string,
  sizes: string,
  productType = '812',
) {
  await openTab(wrapper, 'Variants')
  await wrapper.find('[data-testid="tshirt-matrix-colors"]').setValue(colors)
  await wrapper.find('[data-testid="tshirt-matrix-sizes"]').setValue(sizes)
  await wrapper.find('[data-testid="tshirt-matrix-product-type"]').setValue(productType)
  await wrapper.find('[data-testid="tshirt-matrix-generate"]').trigger('click')
  await flushPromises()
}

describe('TshirtArticleEditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    mocks.fetchArticle.mockReset()
    mocks.createArticle.mockReset()
    mocks.updateArticle.mockReset()
    mocks.deleteArticle.mockReset()
    mocks.uploadSizeChartImage.mockReset()
    mocks.fetchDefaultPrice.mockReset()
    mocks.calculatePrice.mockReset()
    mocks.fetchVatAll.mockReset()
    mocks.vats = [standardVat]
    mocks.fetchDefaultPrice.mockResolvedValue(priceDto())
    mocks.calculatePrice.mockImplementation(async (payload: AdminPriceInputDto) =>
      priceDto({ ...payload, purchaseVat: standardVat, salesVat: standardVat }),
    )
  })

  it('loads a shirt from the t-shirt route and shows its name in the heading', async () => {
    mocks.fetchArticle.mockResolvedValue(tshirtArticle)

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')

    expect(mocks.fetchArticle).toHaveBeenCalledWith(10)
    expect(wrapper.find('h1').text()).toBe('Edit T-Shirt (Classic Shirt)')
  })

  it('fills the print tab from the loaded frame and shows the stored size chart', async () => {
    mocks.fetchArticle.mockResolvedValue(tshirtArticle)

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    await openTab(wrapper, 'Print')

    expect(
      (wrapper.get('[data-testid="print-frame-left"]').element as HTMLInputElement).value,
    ).toBe('20')
    expect(
      (wrapper.get('[data-testid="print-frame-width"]').element as HTMLInputElement).value,
    ).toBe('60')
    expect(wrapper.get('[data-testid="size-chart-preview"]').attributes('src')).toBe(
      '/api/images/public/400/articles/tshirts/size-charts/chart.webp',
    )
  })

  it('generates the variant matrix and submits it with the frame and the ratio', async () => {
    mocks.createArticle.mockResolvedValue({ ...tshirtArticle, id: 20, name: 'New Shirt' })

    const { wrapper } = await mountEditView('/admin/articles/tshirts/new')
    await fillRequiredGeneral(wrapper)
    await generateMatrix(wrapper, 'Black #000000\nWhite #ffffff', 'S, M, L, XL, XXL')

    for (const index of Array.from({ length: 10 }, (_, position) => position)) {
      await wrapper.find(`[data-testid="tshirt-variant-appearance-${index}"]`).setValue('3')
      await wrapper.find(`[data-testid="tshirt-variant-size-${index}"]`).setValue(String(index + 1))
    }

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).toHaveBeenCalledOnce()
    const payload = mocks.createArticle.mock.calls[0]![0] as SaveAdminTshirtArticleRequest
    expect(payload.tshirtVariants).toHaveLength(10)
    expect(new Set(payload.tshirtVariants.map((variant) => variant.spodProductTypeId))).toEqual(
      new Set([812]),
    )
    expect(payload.tshirtVariants.filter((variant) => variant.isDefault)).toHaveLength(1)
    expect(payload.printAspectRatio).toBe('1:1')
    expect(payload.printFrame).toEqual({
      leftPct: 30,
      topPct: 25,
      widthPct: 40,
      heightPct: 40,
    })
  })

  it('refuses to save while a generated row has no SPOD appearance and size id', async () => {
    const { wrapper } = await mountEditView('/admin/articles/tshirts/new')
    await fillRequiredGeneral(wrapper)
    await generateMatrix(wrapper, 'Black #000000', 'S')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.createArticle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Every variant needs a SPOD appearance id and a SPOD size id.')
  })

  it('shows a rejected write on the input that caused it and opens that tab', async () => {
    mocks.fetchArticle.mockResolvedValue(tshirtArticle)
    mocks.updateArticle.mockRejectedValue(
      new InvalidArticleRequestError('Validation failed', {
        'printFrame.widthPct': ['LeftPct plus WidthPct must be at most 100'],
        'tshirtVariants[0].colorHex': ['ColorHex must be a six-digit hex color such as #1a2b3c'],
      }),
    )

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateArticle.mock.calls[0]![0]).toBe(10)
    expect(wrapper.text()).toContain('LeftPct plus WidthPct must be at most 100')
    await openTab(wrapper, 'Variants')
    expect(wrapper.find('[data-testid="tshirt-variant-error"]').text()).toContain(
      'ColorHex must be a six-digit hex color',
    )
  })

  it('deletes through the t-shirt route', async () => {
    mocks.fetchArticle.mockResolvedValue(tshirtArticle)
    mocks.deleteArticle.mockResolvedValue(undefined)

    const { wrapper } = await mountEditView('/admin/articles/tshirts/10/edit')
    const deleteButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Delete Article')
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
