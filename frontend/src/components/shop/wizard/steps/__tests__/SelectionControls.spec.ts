import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SelectArticleStep from '@/components/shop/wizard/steps/SelectArticleStep.vue'
import SelectStyleStep from '@/components/shop/wizard/steps/SelectStyleStep.vue'
import { SegmentedControl } from '@/components/ui/segmented-control'
import { SelectableCard } from '@/components/ui/selectable-card'
import { SwatchButton } from '@/components/ui/swatch-button'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useCatalogStore, type MugDto, type TshirtDto } from '@/stores/shop/catalog'
import { usePromptsStore, type PromptDto } from '@/stores/shop/prompts'
import { useWizardStore } from '@/stores/shop/wizard'
import {
  createMugVariant,
  createShopMug,
  createShopPrompt,
  createShopTshirt,
  createTshirtVariant,
} from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function makePrompt(
  id: number,
  categoryId: number,
  categoryName: string,
  subcategory: { id: number; name: string; position: number } | null = null,
): PromptDto {
  return createShopPrompt({
    id,
    title: `Prompt ${id}`,
    category: { id: categoryId, name: categoryName, position: categoryId },
    subcategory,
    exampleImageFilename: `prompt-${id}.webp`,
    price: {
      salesTotalNet: 1000,
      salesTotalGross: 1190,
      salesTotalTax: 190,
      salesVatRatePercent: 19,
    },
  })
}

function makeMug(id: number, categoryId: number, overrides: Partial<MugDto> = {}): MugDto {
  return createShopMug({
    id,
    name: `Mug ${id}`,
    descriptionShort: 'Short',
    descriptionLong: 'Long',
    categoryId,
    variants: [
      createMugVariant({ id: id * 10 + 1 }),
      createMugVariant({
        id: id * 10 + 2,
        name: 'Black',
        outsideColorCode: '#111111',
        insideColorCode: '#111111',
        isDefault: false,
      }),
    ],
    ...overrides,
  })
}

function makeTshirt(id: number, categoryId: number, overrides: Partial<TshirtDto> = {}): TshirtDto {
  return createShopTshirt({
    id,
    name: `Shirt ${id}`,
    categoryId,
    sizeChartImageFilename: 'size-chart.webp',
    variants: [
      createTshirtVariant({ id: id * 10 + 1, name: 'Black / M', size: 'M', isDefault: true }),
      createTshirtVariant({ id: id * 10 + 2, name: 'Black / L', size: 'L', isDefault: false }),
      createTshirtVariant({
        id: id * 10 + 3,
        name: 'White / L',
        colorName: 'White',
        colorHex: '#ffffff',
        size: 'L',
        isDefault: false,
      }),
    ],
    ...overrides,
  })
}

describe('wizard selection controls', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('filters style prompts with SegmentedControl and selects with SelectableCard', async () => {
    const promptsStore = usePromptsStore()
    promptsStore.prompts = [makePrompt(1, 10, 'Portrait'), makePrompt(2, 20, 'Landscape')]
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()

    const wrapper = mount(SelectStyleStep)
    const wizard = useWizardStore()

    await flushPromises()

    expect(wrapper.findComponent(SegmentedControl).exists()).toBe(true)
    expect(wrapper.findAllComponents(SelectableCard)).toHaveLength(2)

    await wrapper.findAll('.style-pill')[2]!.trigger('click')
    await flushPromises()

    const visibleCards = wrapper.findAllComponents(SelectableCard)
    expect(visibleCards).toHaveLength(1)
    expect(visibleCards[0]!.text()).toContain('Prompt 2')

    await visibleCards[0]!.trigger('click')

    expect(wizard.selectedPromptId).toBe(2)
    expect(wrapper.getComponent(SelectableCard).attributes('data-state')).toBe('selected')
  })

  it('filters style prompts by ordered subcategories under the selected category', async () => {
    const promptsStore = usePromptsStore()
    promptsStore.prompts = [
      makePrompt(1, 10, 'Portrait', { id: 20, name: 'Oil', position: 2 }),
      makePrompt(2, 10, 'Portrait', { id: 10, name: 'Ink', position: 1 }),
      makePrompt(3, 20, 'Landscape', { id: 30, name: 'Wide', position: 1 }),
    ]
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()

    const wrapper = mount(SelectStyleStep)

    await flushPromises()
    await wrapper.findAll('.style-pill')[1]!.trigger('click')
    await flushPromises()

    // The filtered list keeps the order of the answer; it is not re-sorted by subcategory.
    expect(wrapper.findAllComponents(SelectableCard).map((card) => card.get('h3').text())).toEqual([
      'Prompt 1',
      'Prompt 2',
    ])

    const subcategoryPills = wrapper.findAll('.style-subcategory-pill')
    expect(subcategoryPills.map((pill) => pill.text())).toEqual([
      'configurator.steps.selectStyle.allSubcategories',
      'Ink',
      'Oil',
    ])

    await subcategoryPills[1]!.trigger('click')
    await flushPromises()

    const visibleCards = wrapper.findAllComponents(SelectableCard)
    expect(visibleCards).toHaveLength(1)
    expect(visibleCards[0]!.text()).toContain('Prompt 2')
  })

  it('keeps the one global order while category and subcategory filters narrow it', async () => {
    const promptsStore = usePromptsStore()
    // The answer arrives in (position, id) order, and every filtered view of it stays in that
    // order — the storefront never re-sorts.
    promptsStore.prompts = [
      { ...makePrompt(3, 10, 'Portrait', { id: 20, name: 'Oil', position: 2 }), position: 1 },
      { ...makePrompt(1, 10, 'Portrait', { id: 20, name: 'Oil', position: 2 }), position: 2 },
      { ...makePrompt(2, 10, 'Portrait', { id: 10, name: 'Ink', position: 1 }), position: 3 },
      { ...makePrompt(4, 20, 'Landscape', { id: 30, name: 'Wide', position: 1 }), position: 4 },
    ]
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()
    const wrapper = mount(SelectStyleStep)
    const renderedPromptTitles = () =>
      wrapper.findAllComponents(SelectableCard).map((card) => card.get('h3').text())

    await flushPromises()
    expect(renderedPromptTitles()).toEqual(['Prompt 3', 'Prompt 1', 'Prompt 2', 'Prompt 4'])

    await wrapper.findAll('.style-pill')[1]!.trigger('click')
    await flushPromises()
    expect(renderedPromptTitles()).toEqual(['Prompt 3', 'Prompt 1', 'Prompt 2'])

    await wrapper.findAll('.style-subcategory-pill')[2]!.trigger('click')
    await flushPromises()
    expect(renderedPromptTitles()).toEqual(['Prompt 3', 'Prompt 1'])

    await wrapper.findAll('.style-pill')[0]!.trigger('click')
    await flushPromises()
    expect(renderedPromptTitles()).toEqual(['Prompt 3', 'Prompt 1', 'Prompt 2', 'Prompt 4'])
  })

  it('filters articles with SegmentedControl and keeps selection behavior', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug(1, 10), makeMug(2, 20)]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      { id: 10, name: 'Classic', position: 1, subcategories: [] },
      { id: 20, name: 'Travel', position: 2, subcategories: [] },
    ]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const wrapper = mount(SelectArticleStep, {
      global: {
        stubs: {
          ProductCard: {
            props: ['article'],
            emits: ['click', 'select-variant'],
            template:
              '<article class="product-card-stub" role="button" @click="$emit(\'click\')">{{ article.name }}</article>',
          },
        },
      },
    })
    const wizard = useWizardStore()

    await flushPromises()

    expect(wrapper.findComponent(SegmentedControl).exists()).toBe(true)
    expect(wrapper.findAll('.product-card-stub')).toHaveLength(2)

    await wrapper.findAll('.mug-pill')[2]!.trigger('click')
    await flushPromises()

    const visibleCards = wrapper.findAll('.product-card-stub')
    expect(visibleCards).toHaveLength(1)
    expect(visibleCards[0]!.text()).toBe('Mug 2')

    await visibleCards[0]!.trigger('click')

    expect(wizard.selectedArticleId).toBe(2)
    expect(wizard.selectedVariantId).toBe(21)
  })

  it('renders position order for All and alphabetical order for a category filter', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      makeMug(30, 10, { name: 'Alpha', position: 3 }),
      makeMug(20, 10, { name: 'Zulu', position: 1 }),
      makeMug(10, 20, { name: 'Bravo', position: 2 }),
    ]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      { id: 10, name: 'Classic', position: 1, subcategories: [] },
      { id: 20, name: 'Travel', position: 2, subcategories: [] },
    ]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const wrapper = mount(SelectArticleStep, {
      global: {
        stubs: {
          ProductCard: {
            props: ['article'],
            emits: ['click', 'select-variant'],
            template: '<article class="product-card-stub">{{ article.name }}</article>',
          },
        },
      },
    })
    const renderedMugNames = () => wrapper.findAll('.product-card-stub').map((card) => card.text())

    await flushPromises()
    expect(renderedMugNames()).toEqual(['Zulu', 'Bravo', 'Alpha'])

    await wrapper.findAll('.mug-pill')[1]!.trigger('click')
    await flushPromises()
    expect(renderedMugNames()).toEqual(['Alpha', 'Zulu'])

    await wrapper.findAll('.mug-pill')[0]!.trigger('click')
    await flushPromises()
    expect(renderedMugNames()).toEqual(['Zulu', 'Bravo', 'Alpha'])
  })

  it('uses SwatchButton for preselected mug variants', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeMug(1, 10)]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [{ id: 10, name: 'Classic', position: 1, subcategories: [] }]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const wizard = useWizardStore()
    wizard.selectArticle('MUG', 1, 11)

    const wrapper = mount(SelectArticleStep)

    await flushPromises()

    const swatches = wrapper.findAllComponents(SwatchButton)
    expect(swatches).toHaveLength(2)
    expect(swatches[0]!.attributes('data-state')).toBe('selected')

    await swatches[1]!.trigger('click')

    expect(wizard.selectedVariantId).toBe(12)
    expect(wrapper.findAllComponents(SwatchButton)[1]!.attributes('data-state')).toBe('selected')
  })

  it('picks a shirt colour and size and offers its size chart', async () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [makeTshirt(5, 20)]
    vi.spyOn(catalogStore, 'fetchArticles').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [{ id: 20, name: 'Shirts', position: 1, subcategories: [] }]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const wizard = useWizardStore()
    wizard.selectArticle('TSHIRT', 5, 51)

    const wrapper = mount(SelectArticleStep)

    await flushPromises()

    // Two colours out of three variants: the shirt offers black in two sizes.
    const swatches = wrapper.findAllComponents(SwatchButton)
    expect(swatches).toHaveLength(2)
    expect(swatches[0]!.attributes('data-state')).toBe('selected')

    const sizeButtons = wrapper.findAll('[data-testid="wizard-tshirt-sizes"] button')
    expect(sizeButtons.map((button) => button.text())).toEqual(['M', 'L'])

    await sizeButtons[1]!.trigger('click')
    expect(wizard.selectedVariantId).toBe(52)

    // White is only offered in L, and the selected size survives the colour switch.
    await swatches[1]!.trigger('click')
    expect(wizard.selectedVariantId).toBe(53)
    expect(wizard.selectedArticleType).toBe('TSHIRT')

    expect(wrapper.find('[data-testid="wizard-size-chart-trigger"]').exists()).toBe(true)
  })
})
