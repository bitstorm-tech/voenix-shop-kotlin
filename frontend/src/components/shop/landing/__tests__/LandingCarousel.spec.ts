import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { h, nextTick } from 'vue'
import LandingCarousel from '@/components/shop/landing/LandingCarousel.vue'
import LandingCarouselCard from '@/components/shop/landing/LandingCarouselCard.vue'
import type { PromptDto } from '@/stores/shop/prompts'
import { createShopPrompt } from '@/testing/shopCatalog'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function makePrompt(overrides: Partial<PromptDto> = {}): PromptDto {
  return createShopPrompt({
    id: 101,
    title: 'Neon Portrait',
    category: { id: 1, name: 'Portrait', position: 1 },
    subcategory: { id: 2, name: 'Bold', position: 1 },
    exampleImageFilename: 'neon-portrait.webp',
    price: {
      salesTotalNet: 1200,
      salesTotalGross: 1428,
      salesTotalTax: 228,
      regularSalesTotalGross: null,
      salesVatRatePercent: 19,
    },
    ...overrides,
  })
}

function mountCarousel(
  props: { prompts?: PromptDto[]; isLoading?: boolean; error?: string | null } = {},
) {
  const { prompts = [makePrompt()], isLoading = false, error = null } = props
  return mount(LandingCarousel, {
    props: { items: prompts, isLoading, error, i18nKey: 'landing.styles' },
    slots: {
      // The landing page's own wiring of a style card, so the tests read what a shopper sees.
      // The slot's item is typed by the carousel's `{ id: number }` bound; these items are prompts.
      item: ({ item }: { item: { id: number } }) => {
        const prompt = item as PromptDto
        return h(LandingCarouselCard, {
          to: { name: 'wizard', query: { promptId: String(prompt.id) } },
          title: prompt.title,
          meta: [prompt.category?.name, prompt.subcategory?.name].filter(Boolean).join(' / '),
          imageUrl: prompt.exampleImageFilename
            ? `/api/images/public/400/prompt-example-images/${prompt.exampleImageFilename}`
            : undefined,
          'aria-label': prompt.title,
          noImageLabel: 'landing.styles.noImage',
        })
      },
    },
    global: {
      stubs: {
        RouterLink: RouterLinkStub,
      },
    },
  })
}

describe('LandingCarousel', () => {
  it('renders real prompt titles and category metadata without landing-only dummy copy or prices', () => {
    const wrapper = mountCarousel({
      prompts: [
        makePrompt({
          id: 201,
          title: 'Ink Wash Memory',
          category: { id: 3, name: 'Art', position: 2 },
          subcategory: { id: 4, name: 'Ink', position: 1 },
        }),
      ],
    })

    expect(wrapper.text()).toContain('Ink Wash Memory')
    expect(wrapper.text()).toContain('Art / Ink')
    expect(wrapper.text()).not.toContain('Aquarell')
    expect(wrapper.text()).not.toContain('14.28')
  })

  it('links a prompt card to the wizard with only the prompt id', () => {
    const wrapper = mountCarousel({
      prompts: [makePrompt({ id: 302, title: 'Gallery Oil' })],
    })

    const link = wrapper.getComponent(RouterLinkStub)
    expect(link.props('to')).toEqual({
      name: 'wizard',
      query: {
        promptId: '302',
      },
    })
  })

  it('renders prompt cards in the order provided by the public prompt listing', () => {
    const wrapper = mountCarousel({
      prompts: [
        makePrompt({ id: 401, title: 'First API Prompt' }),
        makePrompt({ id: 402, title: 'Second API Prompt' }),
        makePrompt({ id: 403, title: 'Third API Prompt' }),
      ],
    })

    expect(wrapper.findAll('.landing-carousel-card__title').map((title) => title.text())).toEqual([
      'First API Prompt',
      'Second API Prompt',
      'Third API Prompt',
    ])
  })

  it('renders loading skeleton cards while prompts are loading', () => {
    const wrapper = mountCarousel({
      prompts: [],
      isLoading: true,
    })

    expect(wrapper.findAll('[data-testid="style-skeleton"]')).toHaveLength(4)
    expect(wrapper.text()).toContain('landing.styles.loading')
  })

  it('emits retry from the compact error state', async () => {
    const wrapper = mountCarousel({
      prompts: [],
      error: 'Network failed',
    })

    expect(wrapper.text()).toContain('landing.styles.error')
    await wrapper.get('.landing-carousel__retry').trigger('click')

    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('shows the no-image fallback when a prompt has no example image', () => {
    const wrapper = mountCarousel({
      prompts: [
        makePrompt({
          exampleImageFilename: null,
        }),
      ],
    })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('landing.styles.noImage')
  })

  it('replaces a broken prompt image with the fallback state', async () => {
    const wrapper = mountCarousel()

    await wrapper.get('img').trigger('error')
    await nextTick()

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('landing.styles.noImage')
  })

  it('targets the clamped end scroll position for the final pagination dot', async () => {
    const wrapper = mountCarousel({
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({
          id: 400 + index,
          title: `Prompt ${index + 1}`,
        }),
      ),
    })
    const track = wrapper.get('[data-testid="style-carousel-track"]')
    const scrollTo = vi.fn()

    Object.defineProperties(track.element, {
      clientWidth: { configurable: true, value: 100 },
      scrollWidth: { configurable: true, value: 326 },
      scrollLeft: { configurable: true, writable: true, value: 0 },
      scrollTo: { configurable: true, value: scrollTo },
    })

    await track.trigger('scroll')
    await nextTick()

    const dots = wrapper.findAll('.landing-carousel__dot')
    expect(dots).toHaveLength(4)

    const finalDot = dots[3]
    if (!finalDot) throw new Error('Expected final carousel dot')

    await finalDot.trigger('click')

    expect(scrollTo).toHaveBeenCalledWith({
      left: 226,
      behavior: 'smooth',
    })

    Object.defineProperty(track.element, 'scrollLeft', {
      configurable: true,
      writable: true,
      value: 226,
    })
    await track.trigger('scroll')
    await nextTick()

    const activeFinalDot = wrapper.findAll('.landing-carousel__dot')[3]
    if (!activeFinalDot) throw new Error('Expected final carousel dot')

    expect(activeFinalDot.attributes('aria-current')).toBe('page')
  })

  it('attaches ResizeObserver once the track mounts after loading completes', async () => {
    const observe = vi.fn()
    const disconnect = vi.fn()
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = observe
        unobserve = vi.fn()
        disconnect = disconnect
      },
    )

    try {
      const wrapper = mountCarousel({ prompts: [], isLoading: true })
      expect(observe).not.toHaveBeenCalled()

      await wrapper.setProps({ items: [makePrompt()], isLoading: false })
      await nextTick()

      expect(observe).toHaveBeenCalledWith(
        wrapper.get('[data-testid="style-carousel-track"]').element,
      )

      wrapper.unmount()
      expect(disconnect).toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
