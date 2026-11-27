import { mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import ShopLayout from '../ShopLayout.vue'

const route = { name: 'wizard', meta: {} as Record<string, unknown> }

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return { ...actual, useRoute: () => route }
})

vi.mock('@/stores/shop/cart', () => ({
  useCartStore: () => ({ fetchCart: vi.fn() }),
}))

function mountLayout(meta: Record<string, unknown>) {
  route.meta = meta
  setActivePinia(createPinia())
  return mount(ShopLayout, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub,
        RouterView: true,
        ShopHeader: true,
        ShopFooter: { template: '<footer data-testid="footer" />' },
      },
    },
  })
}

describe('ShopLayout', () => {
  it('shows the legal bar instead of the footer on footerless pages', () => {
    const wrapper = mountLayout({ hideFooter: true })

    expect(wrapper.find('[data-testid="footer"]').exists()).toBe(false)
    const links = wrapper.findAllComponents(RouterLinkStub)
    expect(links.map((link) => link.props('to'))).toEqual([
      { name: 'imprint' },
      { name: 'privacy' },
      { name: 'terms' },
    ])
  })

  it('shows the footer and no legal bar on regular pages', () => {
    const wrapper = mountLayout({})

    expect(wrapper.find('[data-testid="footer"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="legal-bar"]').exists()).toBe(false)
  })
})
