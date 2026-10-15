import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CartLineItem from '@/components/shop/CartLineItem.vue'
import { useCartStore, type CartItem } from '@/stores/shop/cart'
import { useCatalogStore } from '@/stores/shop/catalog'
import { createCartItem } from '@/testing/cart'
import { createShopMug, createShopTee, createSpodAppearance } from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

const stubs = {
  Badge: { template: '<span v-bind="$attrs"><slot /></span>' },
  Button: {
    props: ['disabled'],
    template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
  },
  Card: { template: '<li><slot /></li>' },
}

function mountLine(item: CartItem) {
  return mount(CartLineItem, { props: { item }, global: { stubs } })
}

describe('CartLineItem', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // The store is instantiated by the cart page, not by the line; instantiating it here keeps the
    // line's own lookups honest.
    useCartStore()
  })

  it('falls back to the two colour codes of a mug line', () => {
    const wrapper = mountLine(
      createCartItem({
        articleType: 'MUG',
        imageId: null,
        outsideColorCode: '#ffffff',
        insideColorCode: '#ff0000',
      }),
    )

    const fallback = wrapper.get('[data-testid="cart-line-mug-fallback"]')
    expect(fallback.attributes('style')).toContain('background-color: rgb(255, 255, 255)')
    expect(wrapper.find('[data-testid="cart-line-spod-fallback"]').exists()).toBe(false)
  })

  it('shows the first listing image of a SPOD line colour and its size through the variant name', () => {
    const catalogStore = useCatalogStore()
    // Variant 425 is white (appearance 4202) in M.
    catalogStore.articles = [createShopTee({ id: 42 })]

    const wrapper = mountLine(
      createCartItem({
        articleId: 42,
        variantId: 425,
        articleType: 'SPOD',
        articleName: 'Shirt',
        variantName: 'White / M',
        outsideColorCode: null,
        insideColorCode: null,
      }),
    )

    expect(wrapper.get('[data-testid="cart-line-variant-image"]').attributes('src')).toBe(
      '/api/images/public/400/articles/spod/listing-images/listing-4202-front.webp',
    )
    expect(wrapper.text()).toContain('White / M')
  })

  it('tints the package icon with the catalog colour while the colour has no listing image', () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      createShopTee({
        id: 42,
        appearances: [
          createSpodAppearance({ id: 4201, colorHex: '#101010', listingImageFilenames: [] }),
        ],
      }),
      createShopMug({ id: 43 }),
    ]

    const wrapper = mountLine(
      createCartItem({
        articleId: 42,
        variantId: 421,
        articleType: 'SPOD',
        variantName: 'Black / M',
        outsideColorCode: null,
        insideColorCode: null,
      }),
    )

    const fallback = wrapper.get('[data-testid="cart-line-spod-fallback"]')
    expect(fallback.attributes('fill')).toBe('#101010')
    expect(wrapper.find('[data-testid="cart-line-mug-fallback"]').exists()).toBe(false)
  })

  it('keeps the package icon untinted when the catalog no longer answers for the line', () => {
    const wrapper = mountLine(
      createCartItem({
        articleId: 999,
        articleType: 'SPOD',
        outsideColorCode: null,
        insideColorCode: null,
      }),
    )

    expect(wrapper.get('[data-testid="cart-line-spod-fallback"]').attributes('fill')).toBe('none')
  })
})
