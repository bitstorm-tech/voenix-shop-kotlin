import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import HeaderCategoryMenuPanel from '@/components/shop/HeaderCategoryMenuPanel.vue'
import HeaderSubcategoryMenuCard from '@/components/shop/HeaderSubcategoryMenuCard.vue'
import type { CategoryDto } from '@/stores/shop/articleCategories'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, string | number>) => {
      if (key === 'header.allCategory') {
        return `All ${params?.category}`
      }

      if (key === 'header.subcategoryImageAlt') {
        return `Preview for ${params?.subcategory}`
      }

      return params ? `${key}:${Object.values(params).join(':')}` : key
    },
  }),
}))

const menuLinkStub = {
  props: ['asChild'],
  template: '<slot />',
}

function makeCategory(overrides: Partial<CategoryDto> = {}): CategoryDto {
  return {
    id: 7,
    name: 'Tassen',
    position: 1,
    subcategories: [
      { id: 11, name: 'Espresso', position: 1, exampleImageFilename: 'espresso.webp' },
      { id: 12, name: 'Thermobehälter', position: 2, exampleImageFilename: null },
    ],
    ...overrides,
  }
}

function mountPanel(category = makeCategory()) {
  return mount(HeaderCategoryMenuPanel, {
    props: { category },
    global: {
      stubs: {
        NavigationMenuLink: menuLinkStub,
        RouterLink: RouterLinkStub,
      },
    },
  })
}

describe('HeaderCategoryMenuPanel', () => {
  it('renders image-led subcategory links with category and subcategory query params', () => {
    const wrapper = mountPanel()
    const links = wrapper.findAllComponents(RouterLinkStub)
    const cards = wrapper.findAllComponents(HeaderSubcategoryMenuCard)

    expect(wrapper.findAll('[data-testid="super-menu-subcategory"]')).toHaveLength(2)
    expect(cards).toHaveLength(2)
    expect(cards.map((card) => card.props('title'))).toEqual(['Espresso', 'Thermobehälter'])
    expect(cards[0]?.props('imageSrc')).toBe(
      '/api/images/public/400/articles/subcategory-example-images/espresso.webp',
    )
    expect(cards[1]?.props('imageSrc')).toBeNull()
    expect(wrapper.find('.super-menu-panel__feature').exists()).toBe(false)
    expect(wrapper.findAll('img')).toHaveLength(1)
    expect(links[0]?.props('to')).toEqual({
      name: 'mugs',
      query: { category: '7', subcategory: '11' },
    })
    expect(links[1]?.props('to')).toEqual({
      name: 'mugs',
      query: { category: '7', subcategory: '12' },
    })
    expect(wrapper.text()).toContain('Espresso')
    expect(wrapper.text()).toContain('Thermobehälter')
    expect(wrapper.get('img').attributes('alt')).toBe('Preview for Espresso')
    expect(wrapper.text()).toContain('No image')
  })

  it('falls back to a single category link when no subcategories exist', () => {
    const wrapper = mountPanel(
      makeCategory({
        subcategories: [],
      }),
    )

    expect(wrapper.findAll('[data-testid="super-menu-subcategory"]')).toHaveLength(0)
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(1)
    expect(wrapper.text()).toContain('All Tassen')
  })

  it('uses the visible missing-image fallback when filenames are absent', () => {
    const wrapper = mountPanel(
      makeCategory({
        id: 8,
        name: 'Thermobehälter',
        subcategories: [
          { id: 21, name: 'Thermobecher', position: 1, exampleImageFilename: null },
          { id: 22, name: 'Thermoflasche', position: 2, exampleImageFilename: null },
        ],
      }),
    )

    const cards = wrapper.findAllComponents(HeaderSubcategoryMenuCard)

    expect(wrapper.classes()).not.toContain('super-menu-panel--thermal')
    expect(cards).toHaveLength(2)
    expect(cards.map((card) => card.props('imageSrc'))).toEqual([null, null])
    expect(wrapper.findAll('img')).toHaveLength(0)
    expect(wrapper.text()).toContain('No image')
  })
})
