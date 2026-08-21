import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProductOverviewView from '@/views/shop/ProductOverviewView.vue'
import { useEditorStore } from '@/stores/shop/editor'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useCatalogStore, type MugDto } from '@/stores/shop/catalog'
import { createMugVariant, createShopMug, createShopTshirt } from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}))

function makeMug(overrides: Partial<MugDto> = {}): MugDto {
  const id = overrides.id ?? 10
  const mug = createShopMug({
    id,
    position: overrides.position ?? id,
    name: 'Classic Mug',
    descriptionShort: 'Short',
    descriptionLong: 'Long',
    categoryId: 1,
    variants: [
      createMugVariant({ id: 101, isDefault: false }),
      createMugVariant({
        id: 102,
        name: 'Black',
        outsideColorCode: '#111111',
        insideColorCode: '#111111',
        isDefault: true,
      }),
    ],
  })

  return { ...mug, ...overrides }
}

function createProductRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/products', name: 'products', component: ProductOverviewView },
      { path: '/editor/:draftId?', name: 'editor', component: { template: '<div />' } },
    ],
  })
}

async function mountProductOverview(router: Router, initialRoute = '/products') {
  await router.push(initialRoute)
  await router.isReady()

  const wrapper = mount(ProductOverviewView, {
    global: {
      plugins: [router],
      stubs: {
        Button: {
          props: ['disabled'],
          template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
        },
        ProductCard: {
          props: ['article'],
          emits: ['selectVariant'],
          template:
            '<article data-testid="product-card" :data-article-id="article.id">{{ article.name }}<slot name="action" /></article>',
        },
      },
    },
  })

  await flushPromises()
  return wrapper
}

describe('ProductOverviewView', () => {
  let nextUuid = 1

  beforeEach(() => {
    setActivePinia(createPinia())
    nextUuid = 1

    vi.stubGlobal('crypto', {
      randomUUID: () => `editor-draft-${nextUuid++}`,
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('creates a product editor draft and navigates to the editor without wizard query links', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug()]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const router = createProductRouter()
    const wrapper = await mountProductOverview(router)

    expect(wrapper.html()).not.toContain('/wizard?mug=')
    expect(wrapper.html()).not.toContain('/wizard?variant=')

    await wrapper.get('[data-testid="product-open-editor"]').trigger('click')
    await flushPromises()

    const editorStore = useEditorStore()
    const draft = editorStore.drafts[0]

    expect(draft).toMatchObject({
      id: 'editor-draft-1',
      source: 'product',
      articleId: 10,
      variantId: 102,
      images: [],
      selectedImageId: null,
    })
    expect(router.currentRoute.value.name).toBe('editor')
    expect(router.currentRoute.value.params.draftId).toBe(draft?.id)
  })

  it('does not open the editor for mugs without variants', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug({ variants: [] })]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const router = createProductRouter()
    const wrapper = await mountProductOverview(router)
    const openButton = wrapper.get('[data-testid="product-open-editor"]')

    expect(openButton.attributes('disabled')).toBeDefined()
    expect(openButton.text()).toContain('mugOverview.unavailable')
    expect(useEditorStore().drafts).toHaveLength(0)
    expect(router.currentRoute.value.name).toBe('products')
  })

  it('shows both article types in one grid and narrows to one with the type query', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      makeMug({ id: 10, position: 1 }),
      createShopTshirt({ id: 20, position: 2, categoryId: 1 }),
    ]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const router = createProductRouter()
    const combined = await mountProductOverview(router)

    expect(
      combined
        .findAll('[data-testid="product-card"]')
        .map((card) => Number(card.attributes('data-article-id'))),
    ).toEqual([10, 20])

    const shirtsOnly = await mountProductOverview(router, '/products?type=TSHIRT')

    expect(
      shirtsOnly
        .findAll('[data-testid="product-card"]')
        .map((card) => Number(card.attributes('data-article-id'))),
    ).toEqual([20])
  })

  it('drops a type query the catalog does not know', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug()]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()
    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.hasFetched = true
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const router = createProductRouter()
    await mountProductOverview(router, '/products?type=CUP')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/products')
  })

  it('filters mugs by category and subcategory query params', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      makeMug({ id: 10, categoryId: 1, subcategoryId: 10 }),
      makeMug({ id: 20, categoryId: 1, subcategoryId: 20 }),
      makeMug({ id: 30, categoryId: 2, subcategoryId: 30 }),
    ]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      {
        id: 1,
        name: 'Mugs',
        position: 1,
        subcategories: [
          { id: 10, name: 'Espresso', position: 1, exampleImageFilename: null },
          { id: 20, name: 'Travel', position: 2, exampleImageFilename: null },
        ],
      },
    ]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const router = createProductRouter()
    const wrapper = await mountProductOverview(router, '/products?category=1&subcategory=20')

    expect(wrapper.findAll('[data-testid="product-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('mugOverview.results.filteredSubcategory')
  })

  it('renders position order for All and alphabetical order for category and subcategory filters', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      makeMug({
        id: 40,
        position: 4,
        name: 'Alpha',
        categoryId: 1,
        subcategoryId: 11,
      }),
      makeMug({
        id: 30,
        position: 1,
        name: 'Zulu',
        categoryId: 1,
        subcategoryId: 11,
      }),
      makeMug({
        id: 20,
        position: 3,
        name: 'Bravo',
        categoryId: 1,
        subcategoryId: 12,
      }),
      makeMug({
        id: 10,
        position: 2,
        name: 'Charlie',
        categoryId: 2,
        subcategoryId: 21,
      }),
    ]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      {
        id: 1,
        name: 'Everyday',
        position: 1,
        subcategories: [
          { id: 11, name: 'Classic', position: 1, exampleImageFilename: null },
          { id: 12, name: 'Modern', position: 2, exampleImageFilename: null },
        ],
      },
      {
        id: 2,
        name: 'Travel',
        position: 2,
        subcategories: [{ id: 21, name: 'Insulated', position: 1, exampleImageFilename: null }],
      },
    ]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const router = createProductRouter()
    const wrapper = await mountProductOverview(router)
    const renderedArticleIds = () =>
      wrapper
        .findAll('[data-testid="product-card"]')
        .map((card) => Number(card.attributes('data-article-id')))

    expect(renderedArticleIds()).toEqual([30, 10, 20, 40])

    await router.push({ name: 'products', query: { category: '1' } })
    await flushPromises()
    expect(renderedArticleIds()).toEqual([40, 20, 30])

    await router.push({ name: 'products', query: { category: '1', subcategory: '11' } })
    await flushPromises()
    expect(renderedArticleIds()).toEqual([40, 30])

    await router.push({ name: 'products' })
    await flushPromises()
    expect(renderedArticleIds()).toEqual([30, 10, 20, 40])
  })

  it('replaces an unavailable category bookmark with All and removes its subcategory', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug()]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()
    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [{ id: 1, name: 'Mugs', position: 1, subcategories: [] }]
    categoriesStore.hasFetched = true
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()
    const router = createProductRouter()
    const replaceSpy = vi.spyOn(router, 'replace')

    await mountProductOverview(router, '/products?category=99&subcategory=10&sort=new')
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { sort: 'new' } })
    expect(router.currentRoute.value.fullPath).toBe('/products?sort=new')
  })

  it('keeps a valid category while replacing an unavailable subcategory bookmark', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug()]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()
    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      {
        id: 1,
        name: 'Mugs',
        position: 1,
        subcategories: [{ id: 10, name: 'Espresso', position: 1, exampleImageFilename: null }],
      },
    ]
    categoriesStore.hasFetched = true
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()
    const router = createProductRouter()
    const replaceSpy = vi.spyOn(router, 'replace')

    await mountProductOverview(router, '/products?category=1&subcategory=99')
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { category: '1' } })
    expect(router.currentRoute.value.fullPath).toBe('/products?category=1')
  })
})
