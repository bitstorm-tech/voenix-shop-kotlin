import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { usePromptsStore } from '@/stores/shop/prompts'
import { createShopPrompt } from '@/testing/shopCatalog'
import HomeView from '@/views/shop/HomeView.vue'

async function mountHome(locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { de, en },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: HomeView },
      { path: '/products', name: 'products', component: { template: '<div />' } },
      { path: '/wizard', name: 'wizard', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(HomeView, {
    global: {
      plugins: [i18n, router],
    },
  })
  await flushPromises()
  return wrapper
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = vi.fn()
        unobserve = vi.fn()
        disconnect = vi.fn()
      },
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('renders the subcategory carousel inside the hero section with eager-loaded images', async () => {
    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      {
        id: 1,
        name: 'Tassen',
        position: 1,
        subcategories: [
          {
            id: 11,
            name: 'Emaille 330ML',
            position: 1,
            exampleImageFilename: 'enamel.webp',
          },
        ],
      },
      {
        id: 2,
        name: 'Thermobehälter',
        position: 2,
        subcategories: [
          {
            id: 21,
            name: 'Thermoflasche',
            position: 1,
            exampleImageFilename: 'bottle.webp',
          },
        ],
      },
    ]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const promptsStore = usePromptsStore()
    promptsStore.prompts = [
      createShopPrompt({ id: 5, title: 'Aquarell', exampleImageFilename: 'aquarell.webp' }),
    ]
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()

    const wrapper = await mountHome('de')
    const sections = wrapper.findAll('section')
    const heroSection = sections[0]
    if (!heroSection) throw new Error('Expected hero section')

    expect(heroSection.find('#products').exists()).toBe(true)
    expect(heroSection.text()).toContain('Emaille 330ML')
    expect(heroSection.text()).toContain('Thermoflasche')
    expect(heroSection.text()).not.toContain('Auf deiner Tasse')
    expect(heroSection.text()).not.toContain('Sortiment')

    const subcategoryLink = heroSection.get('a[aria-label="Emaille 330ML ansehen"]')
    expect(subcategoryLink.attributes('href')).toBe('/products?category=1&subcategory=11')
    expect(subcategoryLink.get('img').attributes('loading')).toBe('eager')

    const stylesSection = wrapper.get('#styles')
    expect(stylesSection.get('img').attributes('loading')).toBe('lazy')
  })

  it('places the trust strip at the bottom of the how-it-works section above styles', async () => {
    const categoriesStore = useArticleCategoriesStore()
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const promptsStore = usePromptsStore()
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()

    const wrapper = await mountHome('de')
    const sections = wrapper.findAll('section')

    expect(sections[1]?.attributes('id')).toBe('how')
    expect(sections[1]?.text()).toContain('Made in Germany')
    expect(sections[1]?.text()).toContain('DHL-Versand')
    expect(sections[1]?.text()).toContain('Sichere Zahlung')
    expect(sections[2]?.attributes('id')).toBe('styles')
    expect(sections[3]?.text()).toContain('Jetzt ausprobieren')
  })

  it('randomizes both landing carousels on load without mutating store order', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.categories = [
      {
        id: 1,
        name: 'Tassen',
        position: 1,
        subcategories: [
          { id: 11, name: 'Keramik 330ML', position: 1, exampleImageFilename: 'ceramic.webp' },
          { id: 12, name: 'Emaille 330ML', position: 2, exampleImageFilename: 'enamel.webp' },
        ],
      },
      {
        id: 2,
        name: 'Thermobehälter',
        position: 2,
        subcategories: [
          { id: 21, name: 'Thermoflasche', position: 1, exampleImageFilename: 'bottle.webp' },
        ],
      },
    ]
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    const promptsStore = usePromptsStore()
    promptsStore.prompts = [
      createShopPrompt({ id: 1, title: 'Aquarell', exampleImageFilename: 'aquarell.webp' }),
      createShopPrompt({ id: 2, title: 'Ölgemälde', exampleImageFilename: 'oil.webp' }),
      createShopPrompt({ id: 3, title: 'Pop Art', exampleImageFilename: 'pop.webp' }),
    ]
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()

    const wrapper = await mountHome('de')

    const productTitles = wrapper
      .get('#products')
      .findAll('.landing-carousel-card__title')
      .map((title) => title.text())
    const styleTitles = wrapper
      .get('#styles')
      .findAll('.landing-carousel-card__title')
      .map((title) => title.text())

    expect(productTitles).toEqual(['Emaille 330ML', 'Thermoflasche', 'Keramik 330ML'])
    expect(styleTitles).toEqual(['Ölgemälde', 'Pop Art', 'Aquarell'])

    // Store arrays keep their original API order for navigation and other views
    expect(categoriesStore.categories[0]?.subcategories.map((sub) => sub.name)).toEqual([
      'Keramik 330ML',
      'Emaille 330ML',
    ])
    expect(promptsStore.prompts.map((prompt) => prompt.title)).toEqual([
      'Aquarell',
      'Ölgemälde',
      'Pop Art',
    ])
  })
})
