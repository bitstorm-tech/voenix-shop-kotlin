import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MugOverviewView from '@/views/shop/MugOverviewView.vue'
import { useEditorStore } from '@/stores/shop/editor'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useMugsStore, type MugDto } from '@/stores/shop/mugs'
import { createMugVariant, createShopMug } from '@/testing/shopCatalog'

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

function createRouterForMugs(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/mugs', name: 'mugs', component: MugOverviewView },
      { path: '/editor/:draftId?', name: 'editor', component: { template: '<div />' } },
    ],
  })
}

async function mountMugOverview(router: Router, initialRoute = '/mugs') {
  await router.push(initialRoute)
  await router.isReady()

  const wrapper = mount(MugOverviewView, {
    global: {
      plugins: [router],
      stubs: {
        Button: {
          props: ['disabled'],
          template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
        },
        MugCard: {
          props: ['mug'],
          emits: ['selectVariant'],
          template:
            '<article data-testid="mug-card" :data-mug-id="mug.id">{{ mug.name }}<slot name="action" /></article>',
        },
      },
    },
  })

  await flushPromises()
  return wrapper
}

describe('MugOverviewView', () => {
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
    const mugsStore = useMugsStore()
    mugsStore.mugs = [makeMug()]
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()

    const router = createRouterForMugs()
    const wrapper = await mountMugOverview(router)

    expect(wrapper.html()).not.toContain('/wizard?mug=')
    expect(wrapper.html()).not.toContain('/wizard?variant=')

    await wrapper.get('[data-testid="mug-open-editor"]').trigger('click')
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
    const mugsStore = useMugsStore()
    mugsStore.mugs = [makeMug({ variants: [] })]
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()

    const router = createRouterForMugs()
    const wrapper = await mountMugOverview(router)
    const openButton = wrapper.get('[data-testid="mug-open-editor"]')

    expect(openButton.attributes('disabled')).toBeDefined()
    expect(openButton.text()).toContain('mugOverview.unavailable')
    expect(useEditorStore().drafts).toHaveLength(0)
    expect(router.currentRoute.value.name).toBe('mugs')
  })

  it('filters mugs by category and subcategory query params', async () => {
    const mugsStore = useMugsStore()
    mugsStore.mugs = [
      makeMug({ id: 10, categoryId: 1, subcategoryId: 10 }),
      makeMug({ id: 20, categoryId: 1, subcategoryId: 20 }),
      makeMug({ id: 30, categoryId: 2, subcategoryId: 30 }),
    ]
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.mugCategories = [
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

    const router = createRouterForMugs()
    const wrapper = await mountMugOverview(router, '/mugs?category=1&subcategory=20')

    expect(wrapper.findAll('[data-testid="mug-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('mugOverview.results.filteredSubcategory')
  })

  it('renders position order for All and alphabetical order for category and subcategory filters', async () => {
    const mugsStore = useMugsStore()
    mugsStore.mugs = [
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
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.mugCategories = [
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

    const router = createRouterForMugs()
    const wrapper = await mountMugOverview(router)
    const renderedMugIds = () =>
      wrapper
        .findAll('[data-testid="mug-card"]')
        .map((card) => Number(card.attributes('data-mug-id')))

    expect(renderedMugIds()).toEqual([30, 10, 20, 40])

    await router.push({ name: 'mugs', query: { category: '1' } })
    await flushPromises()
    expect(renderedMugIds()).toEqual([40, 20, 30])

    await router.push({ name: 'mugs', query: { category: '1', subcategory: '11' } })
    await flushPromises()
    expect(renderedMugIds()).toEqual([40, 30])

    await router.push({ name: 'mugs' })
    await flushPromises()
    expect(renderedMugIds()).toEqual([30, 10, 20, 40])
  })

  it('replaces an unavailable category bookmark with All and removes its subcategory', async () => {
    const mugsStore = useMugsStore()
    mugsStore.mugs = [makeMug()]
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()
    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.mugCategories = [{ id: 1, name: 'Mugs', position: 1, subcategories: [] }]
    categoriesStore.hasFetched = true
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()
    const router = createRouterForMugs()
    const replaceSpy = vi.spyOn(router, 'replace')

    await mountMugOverview(router, '/mugs?category=99&subcategory=10&sort=new')
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { sort: 'new' } })
    expect(router.currentRoute.value.fullPath).toBe('/mugs?sort=new')
  })

  it('keeps a valid category while replacing an unavailable subcategory bookmark', async () => {
    const mugsStore = useMugsStore()
    mugsStore.mugs = [makeMug()]
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()
    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.mugCategories = [
      {
        id: 1,
        name: 'Mugs',
        position: 1,
        subcategories: [{ id: 10, name: 'Espresso', position: 1, exampleImageFilename: null }],
      },
    ]
    categoriesStore.hasFetched = true
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()
    const router = createRouterForMugs()
    const replaceSpy = vi.spyOn(router, 'replace')

    await mountMugOverview(router, '/mugs?category=1&subcategory=99')
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { category: '1' } })
    expect(router.currentRoute.value.fullPath).toBe('/mugs?category=1')
  })
})
