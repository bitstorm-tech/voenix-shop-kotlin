import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProductCard from '@/components/shop/ProductCard.vue'
import { SwatchButton } from '@/components/ui/swatch-button'
import type { MugDto } from '@/stores/shop/catalog'
import {
  createMugVariant,
  createShopMug,
  createShopTshirt,
  createTshirtVariant,
} from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

const mug: MugDto = createShopMug({
  id: 10,
  name: 'Classic Mug',
  descriptionShort: 'Short',
  descriptionLong: 'Long',
  categoryId: 1,
  variants: [
    createMugVariant({ id: 101, insideColorCode: '#eeeeee' }),
    createMugVariant({
      id: 102,
      name: 'Black',
      outsideColorCode: '#111111',
      insideColorCode: '#444444',
      isDefault: false,
    }),
  ],
})

describe('ProductCard', () => {
  it('uses shared swatch buttons for variant selection', async () => {
    const wrapper = mount(ProductCard, {
      props: {
        article: mug,
        activeVariant: mug.variants[0]!,
        formattedPrice: '14,99 EUR',
      },
    })
    const swatches = wrapper.findAllComponents(SwatchButton)

    expect(swatches).toHaveLength(2)
    expect(swatches[0]!.attributes('aria-pressed')).toBe('true')
    expect(swatches[1]!.attributes('aria-label')).toBe('Black')

    await swatches[1]!.trigger('click')

    expect(wrapper.emitted('select-variant')).toEqual([[102]])
    expect(wrapper.emitted('click')).toBeUndefined()
  })

  it('shows one swatch per shirt colour and the sizes as a hint', async () => {
    const tshirt = createShopTshirt({
      id: 20,
      variants: [
        createTshirtVariant({ id: 201, colorName: 'Black', colorHex: '#101010', size: 'S' }),
        createTshirtVariant({
          id: 202,
          name: 'Black / M',
          colorName: 'Black',
          colorHex: '#101010',
          size: 'M',
          isDefault: false,
        }),
        createTshirtVariant({
          id: 203,
          name: 'White / S',
          colorName: 'White',
          colorHex: '#ffffff',
          size: 'S',
          isDefault: false,
        }),
      ],
    })

    const wrapper = mount(ProductCard, {
      props: {
        article: tshirt,
        activeVariant: tshirt.variants[0]!,
        formattedPrice: '19,90 EUR',
      },
    })
    const swatches = wrapper.findAllComponents(SwatchButton)

    // Two colours, three variants: the size is not a swatch.
    expect(swatches).toHaveLength(2)
    expect(swatches.map((swatch) => swatch.attributes('aria-label'))).toEqual(['Black', 'White'])
    expect(swatches[0]!.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="product-card-sizes"]').text()).toBe('S · M')

    await swatches[1]!.trigger('click')

    expect(wrapper.emitted('select-variant')).toEqual([[203]])
  })
})
