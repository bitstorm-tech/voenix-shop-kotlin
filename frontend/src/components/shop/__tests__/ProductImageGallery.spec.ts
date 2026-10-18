import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import ProductImageGallery from '@/components/shop/ProductImageGallery.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, values?: Record<string, unknown>) =>
      values ? `${key} ${Object.values(values).join(' ')}` : key,
  }),
}))

const IMAGES = ['front.webp', 'back.webp', 'model.webp']

function mountGallery(images: string[] = IMAGES) {
  return mount(ProductImageGallery, { props: { images, alt: 'Classic Tee' } })
}

type Gallery = ReturnType<typeof mountGallery>

const shownImage = (wrapper: Gallery) => wrapper.get('img').attributes('src')
const previous = (wrapper: Gallery) => wrapper.get('[data-testid="product-image-gallery-previous"]')
const next = (wrapper: Gallery) => wrapper.get('[data-testid="product-image-gallery-next"]')
const dots = (wrapper: Gallery) => wrapper.findAll('[data-testid="product-image-gallery-dot"]')

describe('ProductImageGallery', () => {
  it('shows a single image without arrows or dots', () => {
    const wrapper = mountGallery(['front.webp'])

    expect(shownImage(wrapper)).toBe('front.webp')
    expect(wrapper.get('img').attributes('alt')).toBe('Classic Tee')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('shows no controls for an empty list', () => {
    expect(mountGallery([]).findAll('button')).toHaveLength(0)
  })

  it('starts on the first image and labels its controls', () => {
    const wrapper = mountGallery()

    expect(shownImage(wrapper)).toBe('front.webp')
    expect(previous(wrapper).attributes('aria-label')).toBe('productCard.previousImage')
    expect(next(wrapper).attributes('aria-label')).toBe('productCard.nextImage')
    expect(dots(wrapper).map((dot) => dot.attributes('aria-label'))).toEqual([
      'productCard.imageOf 1 3',
      'productCard.imageOf 2 3',
      'productCard.imageOf 3 3',
    ])
    expect(dots(wrapper).map((dot) => dot.attributes('aria-current'))).toEqual([
      'true',
      undefined,
      undefined,
    ])
  })

  it('leafs with the arrows and the dots and wraps around at both ends', async () => {
    const wrapper = mountGallery()

    await next(wrapper).trigger('click')
    expect(shownImage(wrapper)).toBe('back.webp')
    expect(dots(wrapper)[1]!.attributes('aria-current')).toBe('true')

    await dots(wrapper)[2]!.trigger('click')
    expect(shownImage(wrapper)).toBe('model.webp')

    await next(wrapper).trigger('click')
    expect(shownImage(wrapper)).toBe('front.webp')

    await previous(wrapper).trigger('click')
    expect(shownImage(wrapper)).toBe('model.webp')

    await previous(wrapper).trigger('click')
    expect(shownImage(wrapper)).toBe('back.webp')
  })

  it('leafs with the arrow keys while focus is inside', async () => {
    const wrapper = mountGallery()

    await next(wrapper).trigger('keydown', { key: 'ArrowRight' })
    expect(shownImage(wrapper)).toBe('back.webp')

    await dots(wrapper)[0]!.trigger('keydown', { key: 'ArrowLeft' })
    await dots(wrapper)[0]!.trigger('keydown', { key: 'ArrowLeft' })
    expect(shownImage(wrapper)).toBe('model.webp')
  })

  it('leafs on a sideways swipe but not on a small movement', async () => {
    const wrapper = mountGallery()
    const surface = wrapper.get('[data-testid="product-image-gallery"]').element

    // The test DOM has no PointerEvent; a MouseEvent of the same name carries `clientX` alike.
    async function swipe(fromX: number, toX: number) {
      surface.dispatchEvent(new MouseEvent('pointerdown', { clientX: fromX, bubbles: true }))
      surface.dispatchEvent(new MouseEvent('pointerup', { clientX: toX, bubbles: true }))
      await nextTick()
    }

    await swipe(200, 190)
    expect(shownImage(wrapper)).toBe('front.webp')

    await swipe(200, 100)
    expect(shownImage(wrapper)).toBe('back.webp')

    await swipe(100, 200)
    expect(shownImage(wrapper)).toBe('front.webp')
  })

  it('starts again at the first image when the images change', async () => {
    const wrapper = mountGallery()
    await next(wrapper).trigger('click')

    await wrapper.setProps({ images: ['white-front.webp', 'white-back.webp'] })

    expect(shownImage(wrapper)).toBe('white-front.webp')
    expect(dots(wrapper)).toHaveLength(2)
  })

  it('keeps the current image when the same images arrive in a new list', async () => {
    const wrapper = mountGallery()
    await next(wrapper).trigger('click')

    await wrapper.setProps({ images: [...IMAGES] })

    expect(shownImage(wrapper)).toBe('back.webp')
  })

  it('keeps the click that ends a swipe away from a surrounding card, but not a plain click', async () => {
    const onCardClick = vi.fn()
    const Card = defineComponent(
      () => () =>
        h('div', { onClick: onCardClick }, [
          h(ProductImageGallery, { images: IMAGES, alt: 'Classic Tee' }),
        ]),
    )
    const wrapper = mount(Card)
    const surface = wrapper.get('[data-testid="product-image-gallery"]').element

    surface.dispatchEvent(new MouseEvent('pointerdown', { clientX: 200, bubbles: true }))
    surface.dispatchEvent(new MouseEvent('pointerup', { clientX: 100, bubbles: true }))
    await wrapper.get('img').trigger('click')

    expect(wrapper.get('img').attributes('src')).toBe('back.webp')
    expect(onCardClick).not.toHaveBeenCalled()

    await wrapper.get('img').trigger('click')
    expect(onCardClick).toHaveBeenCalledOnce()
  })

  it('keeps clicks and keys of its controls away from a surrounding card', async () => {
    const onCardClick = vi.fn()
    const onCardKeydown = vi.fn()
    const Card = defineComponent(
      () => () =>
        h('div', { onClick: onCardClick, onKeydown: onCardKeydown }, [
          h(ProductImageGallery, { images: IMAGES, alt: 'Classic Tee' }),
        ]),
    )
    const wrapper = mount(Card)

    await wrapper.get('[data-testid="product-image-gallery-next"]').trigger('click')
    await wrapper.findAll('[data-testid="product-image-gallery-dot"]')[2]!.trigger('click')
    await wrapper.get('[data-testid="product-image-gallery-next"]').trigger('keydown', {
      key: 'Enter',
    })

    expect(wrapper.get('img').attributes('src')).toBe('model.webp')
    expect(onCardClick).not.toHaveBeenCalled()
    expect(onCardKeydown).not.toHaveBeenCalled()
  })
})
