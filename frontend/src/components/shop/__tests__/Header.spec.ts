import { mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Header from '@/components/shop/Header.vue'
import { MAGIC_COINS_ROUTE } from '@/lib/magicCoins'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
    locale: { value: 'de' },
  }),
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')

  return {
    ...actual,
    useRoute: () => ({
      path: '/',
      query: {},
    }),
    useRouter: () => ({ push: vi.fn() }),
  }
})

const passthroughStub = {
  template: '<div><slot /></div>',
}

const navigationMenuContentStub = {
  inheritAttrs: false,
  template: '<div data-testid="navigation-menu-content" :class="$attrs.class"><slot /></div>',
}

function seedArticleCategories() {
  const categoriesStore = useArticleCategoriesStore()
  categoriesStore.categories = [
    {
      id: 1,
      name: 'Tassen',
      position: 1,
      subcategories: [{ id: 11, name: 'Espresso', position: 1, exampleImageFilename: null }],
    },
    {
      id: 2,
      name: 'Thermobehälter',
      position: 2,
      subcategories: [{ id: 21, name: 'Thermobecher', position: 1, exampleImageFilename: null }],
    },
  ]
  categoriesStore.hasFetched = true
}

function mountHeader(options: { realMobileMenu?: boolean } = {}) {
  seedArticleCategories()

  return mount(Header, {
    global: {
      stubs: {
        CartButton: passthroughStub,
        HeaderCategoryMenuPanel: {
          props: ['category'],
          template: '<div data-testid="header-category-menu-panel">{{ category.name }}</div>',
        },
        LanguageDropdown: passthroughStub,
        MagicCoinsBadge: passthroughStub,
        MobileMenu: options.realMobileMenu ? false : passthroughStub,
        NavigationMenuContent: navigationMenuContentStub,
        NavigationMenuIndicator: passthroughStub,
        NavigationMenuItem: {
          template: '<li><slot /></li>',
        },
        NavigationMenuList: {
          template: '<ul><slot /></ul>',
        },
        NavigationMenu: {
          template: '<nav><slot /></nav>',
        },
        NavigationMenuTrigger: {
          props: ['variant'],
          template:
            '<button type="button" :data-variant="variant" :class="$attrs.class"><slot /></button>',
        },
        NavigationMenuViewport: passthroughStub,
        RouterLink: RouterLinkStub,
        ThemeToggle: passthroughStub,
        UserMenu: passthroughStub,
      },
    },
  })
}

describe('Header', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockReturnValue({
        matches: false,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    })
    setActivePinia(createPinia())
  })

  it('hides closed super menu contents immediately while switching categories', () => {
    const wrapper = mountHeader()

    const contentClassNames = wrapper
      .findAll('[data-testid="navigation-menu-content"]')
      .map((content) => content.attributes('class') ?? '')

    expect(contentClassNames).toHaveLength(2)
    for (const className of contentClassNames) {
      expect(className).toContain('data-[state=closed]:hidden')
    }
  })

  it('uses plain desktop category triggers', () => {
    const wrapper = mountHeader()

    const triggers = wrapper.findAll('button[data-variant="plain"]')

    expect(triggers).toHaveLength(2)
    for (const trigger of triggers) {
      const className = trigger.attributes('class') ?? ''

      expect(className).toContain('h-full')
      expect(className).toContain('px-4')
      expect(className).toContain('select-none')
    }
  })

  it('lets the desktop super menu escape the center navigation without scrollbars', () => {
    const wrapper = mountHeader()

    const desktopNavigation = wrapper
      .findAll('.md\\:flex')
      .find((element) => element.text().includes('Tassen'))
    const className = desktopNavigation?.attributes('class') ?? ''

    expect(className).toContain('overflow-visible')
    expect(className).not.toContain('overflow-x-auto')
  })

  it('links the shared Magic Coins badge from the desktop header', () => {
    const wrapper = mountHeader()

    const magicCoinsLink = wrapper
      .findAllComponents(RouterLinkStub)
      .find((link) => link.props('to') === MAGIC_COINS_ROUTE)

    expect(magicCoinsLink).toBeTruthy()
    expect(magicCoinsLink?.attributes('aria-label')).toBe('magicCoins.badgeLabel')
  })

  it('renders the public taxonomy dataset in the real mobile menu in merchandising order', async () => {
    const wrapper = mountHeader({ realMobileMenu: true })
    const openMenu = wrapper.get('button[aria-label="Open menu"]')

    await openMenu.trigger('click')

    const categoryButtons = Array.from(
      document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] nav button'),
    ).filter((button) => ['Tassen', 'Thermobehälter'].includes(button.textContent?.trim() ?? ''))

    expect(categoryButtons.map((button) => button.textContent?.trim())).toEqual([
      'Tassen',
      'Thermobehälter',
    ])
    expect(document.body.textContent).toContain('Espresso')
    expect(document.body.textContent).toContain('Thermobecher')
  })
})
