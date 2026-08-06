import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CartItemPreviewDialog from '@/components/shop/CartItemPreviewDialog.vue'
import type { CartItem } from '@/stores/shop/cart'
import { useMugsStore } from '@/stores/shop/mugs'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function createItem(overrides: Partial<CartItem> = {}): CartItem {
  return {
    id: 1,
    articleId: 10,
    variantId: 11,
    articleName: 'Classic Mug',
    variantName: 'White',
    price: 1499,
    originalPrice: 1499,
    quantity: 2,
    outsideColorCode: '#ffffff',
    insideColorCode: '#eeeeee',
    generatedEditedImageId: 123,
    promptId: null,
    promptPrice: 0,
    promptOriginalPrice: 0,
    customData: '{}',
    ...overrides,
  }
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
      {
        id: 10,
        position: 1,
        name: 'Classic Mug',
        descriptionShort: '',
        descriptionLong: '',
        categoryId: 1,
        price: 1499,
        variants: [
          {
            id: 11,
            name: 'White',
            outsideColorCode: '#ffffff',
            insideColorCode: '#eeeeee',
            isDefault: true,
            exampleImageFilename: 'white-mug.png',
          },
        ],
      },
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
      props: { item: createItem({ generatedEditedImageId: null }) },
      global: { stubs },
    })

    expect(wrapper.get('button').attributes()).toHaveProperty('disabled')
  })
})
