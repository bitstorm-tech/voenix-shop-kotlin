import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminArticleSpodVariantGroups from '../AdminArticleSpodVariantGroups.vue'
import type { AdminSpodAppearanceDto, AdminSpodVariantDto } from '@/stores/admin/spodArticles'

const appearances: AdminSpodAppearanceDto[] = [
  {
    id: 3,
    colorName: 'Black',
    colorHex: '#000000',
    position: 1,
    mockupImageFilename: null,
    listingImageFilenames: ['black-1.webp', 'black-2.webp'],
  },
  {
    id: 4,
    colorName: 'White',
    colorHex: '#ffffff',
    position: 2,
    mockupImageFilename: null,
    listingImageFilenames: [],
  },
]

function variant(overrides: Partial<AdminSpodVariantDto>): AdminSpodVariantDto {
  return {
    id: 1,
    name: 'Black / M',
    appearanceId: 3,
    sizeLabel: 'M',
    spodProductTypeId: 812,
    spodAppearanceId: 3,
    spodSizeId: 5,
    spodVariantId: 'v-1',
    sku: null,
    isDefault: false,
    active: true,
    ...overrides,
  }
}

describe('AdminArticleSpodVariantGroups', () => {
  it('shows one row per colour with the sizes in garment order, not in sync order', () => {
    const wrapper = mount(AdminArticleSpodVariantGroups, {
      props: {
        appearances,
        variants: [
          variant({ id: 1, sizeLabel: '3XL' }),
          variant({ id: 2, sizeLabel: 'L' }),
          variant({ id: 3, sizeLabel: 'S', isDefault: true }),
          variant({ id: 4, sizeLabel: 'XL', active: false }),
          variant({ id: 5, appearanceId: 4, sizeLabel: 'M' }),
        ],
      },
    })

    const black = wrapper.get('[data-testid="spod-colour-Black"]')
    const chips = black.findAll('[data-testid^="spod-size-"]')
    expect(chips.map((chip) => chip.text())).toEqual(['S', 'L', 'XL', '3XL'])
    expect(black.get('[data-testid="spod-size-3"]').classes()).toContain('border-primary')
    expect(black.get('[data-testid="spod-size-4"]').classes()).toContain('line-through')
    expect(black.text()).toContain('3 of 4 active')
    // The thumbnail of a colour is its first listing image.
    expect(black.get('img').attributes('src')).toContain(
      'articles/spod/listing-images/black-1.webp',
    )

    expect(wrapper.get('[data-testid="spod-colour-White"]').text()).toContain('1 of 1 active')
    expect(wrapper.findAll('[data-testid^="spod-colour-"]')).toHaveLength(2)
  })

  it('leaves out a colour without variants', () => {
    const wrapper = mount(AdminArticleSpodVariantGroups, {
      props: { appearances, variants: [variant({ id: 1 })] },
    })

    expect(wrapper.findAll('[data-testid^="spod-colour-"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="spod-colour-White"]').exists()).toBe(false)
  })

  it('keeps the partner identifiers behind one toggle', async () => {
    const wrapper = mount(AdminArticleSpodVariantGroups, {
      props: {
        appearances,
        variants: [variant({ id: 1, sku: 'SKU-1', spodVariantId: 'v-812-3-5' })],
      },
    })

    expect(wrapper.find('[data-testid="spod-variant-1"]').exists()).toBe(false)
    await wrapper.get('[data-testid="technical-ids"]').trigger('click')
    const row = wrapper.get('[data-testid="spod-variant-1"]')
    expect(row.text()).toContain('v-812-3-5')
    expect(row.text()).toContain('SKU-1')
    expect(row.text()).toContain('812 / 3 / 5')
  })
})
