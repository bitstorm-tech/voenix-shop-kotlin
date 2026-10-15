import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminArticleTshirtVariantGroups from '../AdminArticleTshirtVariantGroups.vue'
import type { AdminArticleTshirtVariantDto } from '@/stores/admin/tshirtArticles'

function variant(overrides: Partial<AdminArticleTshirtVariantDto>): AdminArticleTshirtVariantDto {
  return {
    id: 1,
    name: 'Black / M',
    colorName: 'Black',
    colorHex: '#000000',
    sizeLabel: 'M',
    spodProductTypeId: 812,
    spodAppearanceId: 3,
    spodSizeId: 5,
    spodVariantId: 'v-1',
    sku: null,
    isDefault: false,
    active: true,
    exampleImageFilename: null,
    ...overrides,
  }
}

describe('AdminArticleTshirtVariantGroups', () => {
  it('shows one row per colour with the sizes in garment order, not in sync order', () => {
    const wrapper = mount(AdminArticleTshirtVariantGroups, {
      props: {
        variants: [
          variant({ id: 1, sizeLabel: '3XL' }),
          variant({ id: 2, sizeLabel: 'L' }),
          variant({ id: 3, sizeLabel: 'S', isDefault: true }),
          variant({ id: 4, sizeLabel: 'XL', active: false }),
          variant({ id: 5, colorName: 'White', colorHex: '#ffffff', sizeLabel: 'M' }),
        ],
      },
    })

    const black = wrapper.get('[data-testid="spod-colour-Black"]')
    const chips = black.findAll('[data-testid^="spod-size-"]')
    expect(chips.map((chip) => chip.text())).toEqual(['S', 'L', 'XL', '3XL'])
    expect(black.get('[data-testid="spod-size-3"]').classes()).toContain('border-primary')
    expect(black.get('[data-testid="spod-size-4"]').classes()).toContain('line-through')
    expect(black.text()).toContain('3 of 4 active')

    expect(wrapper.get('[data-testid="spod-colour-White"]').text()).toContain('1 of 1 active')
    expect(wrapper.findAll('[data-testid^="spod-colour-"]')).toHaveLength(2)
  })

  it('keeps the partner identifiers behind one toggle', async () => {
    const wrapper = mount(AdminArticleTshirtVariantGroups, {
      props: { variants: [variant({ id: 1, sku: 'SKU-1', spodVariantId: 'v-812-3-5' })] },
    })

    expect(wrapper.find('[data-testid="spod-variant-1"]').exists()).toBe(false)
    await wrapper.get('[data-testid="technical-ids"]').trigger('click')
    const row = wrapper.get('[data-testid="spod-variant-1"]')
    expect(row.text()).toContain('v-812-3-5')
    expect(row.text()).toContain('SKU-1')
    expect(row.text()).toContain('812 / 3 / 5')
  })
})
