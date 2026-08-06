import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MugCard from '@/components/shop/MugCard.vue'
import { SwatchButton } from '@/components/ui/swatch-button'
import type { MugDto } from '@/stores/shop/mugs'
import { createMugVariant, createShopMug } from '@/testing/shopCatalog'

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

describe('MugCard', () => {
  it('uses shared swatch buttons for variant selection', async () => {
    const wrapper = mount(MugCard, {
      props: {
        mug,
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
})
