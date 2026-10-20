import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProductVariantPanel from '@/components/shop/editor/ProductVariantPanel.vue'
import {
  toEditorArticleVariant,
  toEditorMugArticle,
  toEditorSpodArticle,
} from '@/components/shop/editor/types'
import {
  createMugVariant,
  createShopMug,
  createShopTee,
  createShopTote,
} from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key, locale: { value: 'de' } }),
}))

// Black (appearance 201) in S/M/L as variants 21-23, white (appearance 202) as variants 24-26.
const shopTee = createShopTee({ id: 2, sizeChartImageFilename: 'size-chart.png' })
const shopMug = createShopMug({
  id: 1,
  variants: [
    createMugVariant({ id: 11, name: 'White' }),
    createMugVariant({ id: 12, name: 'Black', outsideColorCode: '#000000', isDefault: false }),
  ],
})

function mountPanel(shop: typeof shopTee | typeof shopMug, variantId: number) {
  const article = 'mugDetails' in shop ? toEditorMugArticle(shop) : toEditorSpodArticle(shop)
  return mount(ProductVariantPanel, {
    props: { article, variant: toEditorArticleVariant(shop, variantId)! },
  })
}

const teeMeasurements = [
  { size: 'S', measurements: [{ name: 'A', valueMm: 700 }] },
  { size: 'M', measurements: [{ name: 'A', valueMm: 720 }] },
]

describe('ProductVariantPanel', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('switches a SPOD colour and keeps the selected size', async () => {
    const wrapper = mountPanel(shopTee, 22)
    const swatches = wrapper.get('[data-testid="editor-variant-colors"]').findAll('button')

    expect(swatches.map((swatch) => swatch.attributes('aria-label'))).toEqual(['Black', 'White'])
    expect(swatches[0]!.attributes('aria-pressed')).toBe('true')

    await swatches[1]!.trigger('click')

    expect(wrapper.emitted('select')).toEqual([[25]])
  })

  it('offers the sizes of the selected colour and the size chart', async () => {
    const wrapper = mountPanel(shopTee, 24)
    const sizes = wrapper.get('[data-testid="editor-variant-sizes"]').findAll('button')

    expect(sizes.map((button) => button.text())).toEqual(['S', 'M', 'L'])
    expect(sizes[0]!.attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="editor-size-chart-trigger"]').exists()).toBe(true)

    await sizes[0]!.trigger('click')
    await sizes[2]!.trigger('click')

    expect(wrapper.emitted('select')).toEqual([[26]])
  })

  it('opens the size chart with the picture and the measurements, the selected size highlighted', async () => {
    const wrapper = mountPanel(
      createShopTee({
        id: 2,
        sizeChartImageFilename: 'size-chart.png',
        sizeMeasurements: teeMeasurements,
      }),
      22,
    )

    await wrapper.get('[data-testid="editor-size-chart-trigger"]').trigger('click')
    await flushPromises()

    expect(document.body.querySelector('[data-testid="size-chart-image"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="size-chart-table"]')).not.toBeNull()
    expect(document.body.querySelector('[aria-current="true"]')?.textContent).toContain('M')
    expect(document.body.textContent).toContain('editor.productVariant.sizeChartHint')
  })

  it('offers the size chart with measurements but no picture', () => {
    const wrapper = mountPanel(createShopTee({ id: 2, sizeMeasurements: teeMeasurements }), 22)

    expect(wrapper.find('[data-testid="editor-size-chart-trigger"]').exists()).toBe(true)
  })

  it('offers the picture without measurements, saying there are none', async () => {
    const wrapper = mountPanel(shopTee, 22)

    await wrapper.get('[data-testid="editor-size-chart-trigger"]').trigger('click')
    await flushPromises()

    expect(document.body.querySelector('[data-testid="size-chart-image"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="size-chart-table"]')).toBeNull()
    expect(document.body.textContent).toContain('editor.productVariant.sizeChartNoMeasurementsHint')
  })

  it('offers no size chart without a picture or measurements', () => {
    const wrapper = mountPanel(createShopTee({ id: 2 }), 22)

    expect(wrapper.find('[data-testid="editor-variant-sizes"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-size-chart-trigger"]').exists()).toBe(false)
  })

  it('offers no size choice for an article in one size', () => {
    const wrapper = mountPanel(createShopTote({ id: 3 }), 31)

    expect(wrapper.findAll('[data-testid="editor-variant-colors"] button')).toHaveLength(8)
    expect(wrapper.find('[data-testid="editor-variant-sizes"]').exists()).toBe(false)
  })

  it('offers a mug swatch per variant', async () => {
    const wrapper = mountPanel(shopMug, 11)
    const swatches = wrapper.get('[data-testid="editor-variant-colors"]').findAll('button')

    expect(swatches.map((swatch) => swatch.attributes('aria-label'))).toEqual(['White', 'Black'])
    expect(wrapper.find('[data-testid="editor-variant-sizes"]').exists()).toBe(false)

    await swatches[1]!.trigger('click')

    expect(wrapper.emitted('select')).toEqual([[12]])
  })
})
