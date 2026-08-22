import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CartLineItem from '@/components/shop/CartLineItem.vue'
import { useCartStore, type CartItem } from '@/stores/shop/cart'
import { useCatalogStore } from '@/stores/shop/catalog'
import { createCartItem } from '@/testing/cart'
import { createShopMug, createShopTshirt, createTshirtVariant } from '@/testing/shopCatalog'

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
    expect(wrapper.find('[data-testid="cart-line-shirt-fallback"]').exists()).toBe(false)
  })

  it('shows the variant mockup of a shirt line and its size through the variant name', () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      createShopTshirt({
        id: 42,
        variants: [
          createTshirtVariant({ id: 84, name: 'Black / M', exampleImageFilename: 'black.png' }),
        ],
      }),
    ]

    const wrapper = mountLine(
      createCartItem({
        articleId: 42,
        variantId: 84,
        articleType: 'TSHIRT',
        articleName: 'Shirt',
        variantName: 'Black / M',
        outsideColorCode: null,
        insideColorCode: null,
      }),
    )

    expect(wrapper.get('[data-testid="cart-line-variant-image"]').attributes('src')).toBe(
      '/api/images/public/400/articles/tshirts/variant-example-images/black.png',
    )
    expect(wrapper.text()).toContain('Black / M')
  })

  it('tints the shirt silhouette with the catalog colour while no mockup exists', () => {
    const catalogStore = useCatalogStore()
    catalogStore.articles = [
      createShopTshirt({
        id: 42,
        variants: [createTshirtVariant({ id: 84, colorHex: '#101010' })],
      }),
      createShopMug({ id: 43 }),
    ]

    const wrapper = mountLine(
      createCartItem({
        articleId: 42,
        variantId: 84,
        articleType: 'TSHIRT',
        variantName: 'Black / M',
        outsideColorCode: null,
        insideColorCode: null,
      }),
    )

    const fallback = wrapper.get('[data-testid="cart-line-shirt-fallback"]')
    expect(fallback.attributes('fill')).toBe('#101010')
    expect(wrapper.find('[data-testid="cart-line-mug-fallback"]').exists()).toBe(false)
  })

  it('keeps the shirt silhouette untinted when the catalog no longer answers for the line', () => {
    const wrapper = mountLine(
      createCartItem({
        articleId: 999,
        articleType: 'TSHIRT',
        outsideColorCode: null,
        insideColorCode: null,
      }),
    )

    expect(wrapper.get('[data-testid="cart-line-shirt-fallback"]').attributes('fill')).toBe('none')
  })
})
