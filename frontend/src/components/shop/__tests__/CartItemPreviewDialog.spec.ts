import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CartItemPreviewDialog from '@/components/shop/CartItemPreviewDialog.vue'
import type { CartItem } from '@/stores/shop/cart'
import { useMugsStore } from '@/stores/shop/mugs'
import { createCartItem } from '@/testing/cart'
import { createMugVariant, createShopMug } from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function createItem(overrides: Partial<CartItem> = {}): CartItem {
  return createCartItem({
    id: 1,
    variantId: 11,
    articleName: 'Classic Mug',
    variantName: 'White',
    price: 1499,
    insideColorCode: '#eeeeee',
    quantity: 2,
    imageId: 123,
    promptId: null,
    promptPrice: 0,
    ...overrides,
  })
}

const stubs = {
  Button: {
    template: '<button v-bind="$attrs"><slot /></button>',
  },
  Dialog: {
    template: '<div><slot /></div>',
  },
  DialogTrigger: {
    template: '<div><slot /></div>',
  },
  DialogContent: {
    template: '<div><slot /></div>',
  },
  DialogHeader: {
    template: '<div><slot /></div>',
  },
  DialogTitle: {
    template: '<h2><slot /></h2>',
  },
  DialogDescription: {
    template: '<p><slot /></p>',
  },
}

describe('CartItemPreviewDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('enables the preview and uses the saved design image', async () => {
    const mugsStore = useMugsStore()
    mugsStore.mugs = [
      createShopMug({
        id: 10,
        name: 'Classic Mug',
        categoryId: 1,
        variants: [
          createMugVariant({
            id: 11,
            insideColorCode: '#eeeeee',
            exampleImageFilename: 'white-mug.png',
          }),
        ],
      }),
    ]

    const wrapper = mount(CartItemPreviewDialog, {
      props: { item: createItem() },
      global: { stubs },
    })

    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(3)
    await wrapper.get('[data-testid="cart-preview-print-mode"]').trigger('click')

    const imageSources = wrapper.findAll('img').map((img) => img.attributes('src'))
    expect(imageSources).toContain('/api/images/guest/1600/123')
    expect(imageSources).toContain(
      '/api/images/public/200/articles/mugs/variant-example-images/white-mug.png',
    )
    expect(wrapper.text()).toContain('Classic Mug')
    expect(wrapper.text()).toContain('29,98')
  })

  it('disables the preview when no generated design image exists', () => {
    const wrapper = mount(CartItemPreviewDialog, {
      props: { item: createItem({ imageId: null }) },
      global: { stubs },
    })

    expect(wrapper.get('button').attributes()).toHaveProperty('disabled')
  })
})
