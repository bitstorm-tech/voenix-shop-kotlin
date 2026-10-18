import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProductCard from '@/components/shop/ProductCard.vue'
import { SwatchButton } from '@/components/ui/swatch-button'
import type { MugDto } from '@/stores/shop/catalog'
import {
  createMugVariant,
  createShopMug,
  createShopTee,
  createShopTote,
  createSpodAppearance,
  createSpodVariant,
} from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, values?: Record<string, unknown>) =>
      values ? `${key} ${Object.values(values).join(' ')}` : key,
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
        priceCents: 1499,
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

  it('renders the price from the two cent amounts, discount and all', () => {
    const wrapper = mount(ProductCard, {
      props: {
        article: mug,
        activeVariant: mug.variants[0]!,
        priceCents: 1592,
        regularPriceCents: 1990,
      },
    })

    expect(wrapper.get('[data-testid="product-price-effective"]').text()).toBe('15,92\u00a0€')
    expect(wrapper.get('[data-testid="product-price-regular"]').text()).toContain('19,90\u00a0€')
    expect(wrapper.get('[data-testid="product-price-badge"]').text()).toBe('price.discountBadge 20')
  })

  it('shows one swatch per colour, the sizes as a range, and the colour photo', async () => {
    const black = createSpodAppearance({ id: 1, colorName: 'Black', colorHex: '#101010' })
    const white = createSpodAppearance({ id: 2, colorName: 'White', colorHex: '#ffffff' })
    const tee = createShopTee({
      id: 20,
      appearances: [black, white],
      variants: [
        createSpodVariant({ id: 201, name: 'Black / S', appearanceId: 1, size: 'S' }),
        createSpodVariant({
          id: 202,
          name: 'Black / M',
          appearanceId: 1,
          size: 'M',
          isDefault: false,
        }),
        // The white shirt starts with M so that a swatch keeping the first size would be wrong.
        createSpodVariant({
          id: 204,
          name: 'White / M',
          appearanceId: 2,
          size: 'M',
          isDefault: false,
        }),
        createSpodVariant({
          id: 203,
          name: 'White / S',
          appearanceId: 2,
          size: 'S',
          isDefault: false,
        }),
      ],
    })

    const wrapper = mount(ProductCard, {
      props: {
        article: tee,
        activeVariant: tee.variants[0]!,
        priceCents: 1990,
      },
    })
    const swatches = wrapper.findAllComponents(SwatchButton)

    // Two colours, four variants: the size is not a swatch.
    expect(swatches).toHaveLength(2)
    expect(swatches.map((swatch) => swatch.attributes('aria-label'))).toEqual(['Black', 'White'])
    expect(swatches[0]!.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="product-card-sizes"]').text()).toBe('productCard.sizes S – M')
    // The card starts on the first listing image of the active colour.
    expect(wrapper.get('img').attributes('src')).toBe(
      '/api/images/public/400/articles/spod/listing-images/listing-1-front.webp',
    )

    await swatches[1]!.trigger('click')

    // The selected size S survives the colour switch, even though white starts with M.
    expect(wrapper.emitted('select-variant')).toEqual([[203]])
  })

  it('names no size range for a one-size article', () => {
    const tote = createShopTote({ id: 3 })

    const wrapper = mount(ProductCard, {
      props: { article: tote, activeVariant: tote.variants[0]!, priceCents: 1990 },
    })

    expect(wrapper.findAllComponents(SwatchButton).length).toBeGreaterThan(1)
    expect(wrapper.find('[data-testid="product-card-sizes"]').exists()).toBe(false)
  })

  it('falls back to the colour first variant when it is not offered in the selected size', async () => {
    const tee = createShopTee({
      id: 21,
      appearances: [createSpodAppearance({ id: 1 }), createSpodAppearance({ id: 2 })],
      variants: [
        createSpodVariant({ id: 211, appearanceId: 1, size: 'XL' }),
        createSpodVariant({ id: 212, appearanceId: 2, size: 'M', isDefault: false }),
      ],
    })

    const wrapper = mount(ProductCard, {
      props: {
        article: tee,
        activeVariant: tee.variants[0]!,
        priceCents: 1990,
      },
    })
    const swatches = wrapper.findAllComponents(SwatchButton)

    await swatches[1]!.trigger('click')

    expect(wrapper.emitted('select-variant')).toEqual([[212]])
  })

  it('folds many colours behind a +n button that keeps the selected colour visible', async () => {
    const colors = ['Black', 'Grey', 'Blue', 'Navy', 'Green', 'Red', 'White']
    const tee = createShopTee({
      id: 22,
      appearances: colors.map((colorName, index) =>
        createSpodAppearance({ id: index + 1, colorName, colorHex: `#00000${index}` }),
      ),
      variants: colors.map((colorName, index) =>
        createSpodVariant({
          id: 220 + index,
          name: `${colorName} / M`,
          appearanceId: index + 1,
          isDefault: index === 0,
        }),
      ),
    })

    const wrapper = mount(ProductCard, {
      props: {
        article: tee,
        activeVariant: tee.variants[6]!,
        priceCents: 1990,
      },
    })
    const labels = () =>
      wrapper.findAllComponents(SwatchButton).map((swatch) => swatch.attributes('aria-label'))

    // Five places; the selected white swatch takes the last one instead of hiding behind +2.
    expect(labels()).toEqual(['Black', 'Grey', 'Blue', 'Navy', 'White'])
    const toggle = wrapper.get('[data-testid="product-card-color-toggle"]')
    expect(toggle.text()).toBe('+2')

    await toggle.trigger('click')

    expect(labels()).toEqual(colors)
    expect(toggle.text()).toBe('productCard.fewerColors')
    // Unfolding the colours is not choosing the card.
    expect(wrapper.emitted('click')).toBeUndefined()
  })

  describe('image gallery', () => {
    const LISTING_URL = '/api/images/public/400/articles/spod/listing-images/'

    const tee = createShopTee({
      id: 30,
      appearances: [
        createSpodAppearance({
          id: 1,
          colorName: 'Black',
          listingImageFilenames: ['black-front.webp', 'black-model.webp', 'black-detail.webp'],
        }),
        createSpodAppearance({
          id: 2,
          colorName: 'White',
          listingImageFilenames: ['white-front.webp', 'white-model.webp'],
        }),
        createSpodAppearance({ id: 3, colorName: 'Red', listingImageFilenames: [] }),
      ],
    })
    const variantOf = (appearanceId: number) =>
      tee.variants.find((variant) => variant.appearanceId === appearanceId)!

    function mountCard(props: Partial<InstanceType<typeof ProductCard>['$props']> = {}) {
      return mount(ProductCard, {
        props: { article: tee, activeVariant: variantOf(1), priceCents: 1990, ...props },
      })
    }

    type Card = ReturnType<typeof mountCard>
    const shownImage = (wrapper: Card) => wrapper.get('img').attributes('src')
    const dots = (wrapper: Card) => wrapper.findAll('[data-testid="product-image-gallery-dot"]')
    const next = (wrapper: Card) => wrapper.get('[data-testid="product-image-gallery-next"]')

    it('leafs through every listing image of the active colour', async () => {
      const wrapper = mountCard()

      expect(shownImage(wrapper)).toBe(`${LISTING_URL}black-front.webp`)
      expect(dots(wrapper)).toHaveLength(3)

      await next(wrapper).trigger('click')
      expect(shownImage(wrapper)).toBe(`${LISTING_URL}black-model.webp`)
    })

    it('starts the new colour at its first image', async () => {
      const wrapper = mountCard()
      await next(wrapper).trigger('click')

      await wrapper.setProps({ activeVariant: variantOf(2) })

      expect(shownImage(wrapper)).toBe(`${LISTING_URL}white-front.webp`)
      expect(dots(wrapper)).toHaveLength(2)
    })

    it('shows the placeholder for a colour without listing images', () => {
      const wrapper = mountCard({ activeVariant: variantOf(3) })

      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.find('[data-testid="product-image-gallery"]').exists()).toBe(false)
    })

    it('shows a mug photo alone, without arrows or dots', () => {
      const photoMug = createShopMug({
        variants: [createMugVariant({ exampleImageFilename: 'mug-white.webp' })],
      })

      const wrapper = mountCard({ article: photoMug, activeVariant: photoMug.variants[0]! })

      expect(shownImage(wrapper)).toBe(
        '/api/images/public/400/articles/mugs/variant-example-images/mug-white.webp',
      )
      expect(wrapper.find('[data-testid="product-image-gallery-next"]').exists()).toBe(false)
      expect(dots(wrapper)).toHaveLength(0)
    })

    it('does not choose a button card when the customer leafs', async () => {
      const wrapper = mountCard({ as: 'button' })

      await next(wrapper).trigger('click')
      await wrapper.get('[data-testid="product-image-gallery-previous"]').trigger('click')
      await dots(wrapper)[2]!.trigger('click')
      await dots(wrapper)[2]!.trigger('keydown', { key: 'Enter' })
      await dots(wrapper)[2]!.trigger('keydown', { key: ' ' })

      expect(shownImage(wrapper)).toBe(`${LISTING_URL}black-detail.webp`)
      expect(wrapper.emitted('click')).toBeUndefined()

      // The picture itself still belongs to the card.
      await wrapper.get('img').trigger('click')
      expect(wrapper.emitted('click')).toHaveLength(1)
    })
  })
})
