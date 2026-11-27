import { mount, RouterLinkStub } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
  props: {
    prompts?: PromptDto[]
    isLoading?: boolean
    error?: string | null
    shuffle?: boolean
    autoplayIntervalMs?: number
  } = {},
) {
  const {
    prompts = [makePrompt()],
    isLoading = false,
    error = null,
    shuffle,
    autoplayIntervalMs,
  } = props
  return mount(LandingCarousel, {
    props: {
      items: prompts,
      isLoading,
      error,
      i18nKey: 'landing.styles',
      ...(shuffle !== undefined ? { shuffle } : {}),
      ...(autoplayIntervalMs !== undefined ? { autoplayIntervalMs } : {}),
    },
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

function setupScrollableTrack(
  wrapper: ReturnType<typeof mountCarousel>,
  options: { clientWidth?: number; scrollWidth?: number } = {},
) {
  const { clientWidth = 100, scrollWidth = 326 } = options
  const track = wrapper.get('[data-testid="style-carousel-track"]')
  let scrollLeft = 0
  const scrollTo = vi.fn(({ left }: { left?: number; behavior?: ScrollBehavior }) => {
    if (typeof left === 'number') {
      scrollLeft = left
      track.element.dispatchEvent(new Event('scroll'))
    }
  })

  Object.defineProperties(track.element, {
    clientWidth: { configurable: true, value: clientWidth },
    scrollWidth: { configurable: true, value: scrollWidth },
    scrollLeft: {
      configurable: true,
      get: () => scrollLeft,
      set: (value: number) => {
        scrollLeft = value
      },
    },
    scrollTo: { configurable: true, value: scrollTo },
  })

  return { track, scrollTo }
}

describe('LandingCarousel', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

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

  it('shuffles item order on load by default without mutating the source array', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const prompts = [
      makePrompt({ id: 401, title: 'First API Prompt' }),
      makePrompt({ id: 402, title: 'Second API Prompt' }),
      makePrompt({ id: 403, title: 'Third API Prompt' }),
    ]

    const wrapper = mountCarousel({ prompts })

    // Fisher-Yates with Math.random() === 0 rotates [401, 402, 403] -> [402, 403, 401].
    expect(wrapper.findAll('.landing-carousel-card__title').map((title) => title.text())).toEqual([
      'Second API Prompt',
      'Third API Prompt',
      'First API Prompt',
    ])
    expect(prompts.map((prompt) => prompt.id)).toEqual([401, 402, 403])
  })

  it('shuffles items once they finish loading asynchronously and keeps the order across same-id updates', async () => {
    const randomSpy = vi.spyOn(Math, 'random').mockReturnValue(0)
    const wrapper = mountCarousel({ prompts: [], isLoading: true })

    await wrapper.setProps({
      isLoading: false,
      items: [
        makePrompt({ id: 401, title: 'First API Prompt' }),
        makePrompt({ id: 402, title: 'Second API Prompt' }),
        makePrompt({ id: 403, title: 'Third API Prompt' }),
      ],
    })
    await nextTick()

    expect(wrapper.findAll('.landing-carousel-card__title').map((title) => title.text())).toEqual([
      'Second API Prompt',
      'Third API Prompt',
      'First API Prompt',
    ])

    randomSpy.mockReturnValue(0.99)
    await wrapper.setProps({
      items: [
        makePrompt({ id: 401, title: 'First Updated' }),
        makePrompt({ id: 402, title: 'Second Updated' }),
        makePrompt({ id: 403, title: 'Third Updated' }),
      ],
    })
    await nextTick()

    expect(wrapper.findAll('.landing-carousel-card__title').map((title) => title.text())).toEqual([
      'Second Updated',
      'Third Updated',
      'First Updated',
    ])
  })

  it('preserves the provided order when shuffle is disabled', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const wrapper = mountCarousel({
      shuffle: false,
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

  it('auto-advances pages on an interval and wraps back to the first page at the end', async () => {
    vi.useFakeTimers()
    const wrapper = mountCarousel({
      autoplayIntervalMs: 4000,
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({ id: 500 + index, title: `Prompt ${index + 1}` }),
      ),
    })
    const { track, scrollTo } = setupScrollableTrack(wrapper, {
      clientWidth: 100,
      scrollWidth: 250,
    })

    await track.trigger('scroll')
    await nextTick()

    // Offsets are [0, 100, 150] (3 pages)
    vi.advanceTimersByTime(4000)
    await nextTick()
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 100, behavior: 'smooth' })

    vi.advanceTimersByTime(4000)
    await nextTick()
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 150, behavior: 'smooth' })

    vi.advanceTimersByTime(4000)
    await nextTick()
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 0, behavior: 'smooth' })

    wrapper.unmount()
  })

  it('pauses autoplay on hover and resumes when the pointer leaves', async () => {
    vi.useFakeTimers()
    const wrapper = mountCarousel({
      autoplayIntervalMs: 4000,
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({ id: 510 + index, title: `Prompt ${index + 1}` }),
      ),
    })
    const { track, scrollTo } = setupScrollableTrack(wrapper)

    await track.trigger('scroll')
    await nextTick()

    await wrapper.get('.landing-carousel').trigger('mouseenter')
    vi.advanceTimersByTime(12000)
    expect(scrollTo).not.toHaveBeenCalled()

    await wrapper.get('.landing-carousel').trigger('mouseleave')
    vi.advanceTimersByTime(4000)
    expect(scrollTo).toHaveBeenCalledWith({ left: 100, behavior: 'smooth' })

    wrapper.unmount()
  })

  it('pauses autoplay on keyboard focus but resumes after pointer clicks when mouse leaves', async () => {
    vi.useFakeTimers()
    const wrapper = mountCarousel({
      autoplayIntervalMs: 4000,
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({ id: 520 + index, title: `Prompt ${index + 1}` }),
      ),
    })
    const { track, scrollTo } = setupScrollableTrack(wrapper)

    await track.trigger('scroll')
    await nextTick()

    // Keyboard focus pauses autoplay until focus leaves the carousel
    await wrapper.get('.landing-carousel').trigger('focusin')
    vi.advanceTimersByTime(8000)
    expect(scrollTo).not.toHaveBeenCalled()

    await wrapper.get('.landing-carousel').trigger('focusout', { relatedTarget: null })
    vi.advanceTimersByTime(4000)
    expect(scrollTo).toHaveBeenCalledTimes(1)

    // Pointer click on a control resets the timer without permanently locking focus pause
    scrollTo.mockClear()
    await wrapper.get('.landing-carousel').trigger('mouseenter')
    await wrapper.get('.landing-carousel').trigger('pointerdown')
    await wrapper.get('.landing-carousel').trigger('focusin')
    await wrapper.get('.landing-carousel__arrow--next').trigger('click')
    expect(scrollTo).toHaveBeenCalledTimes(1)
    scrollTo.mockClear()

    await wrapper.get('.landing-carousel').trigger('mouseleave')
    vi.advanceTimersByTime(4000)
    expect(scrollTo).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('pauses autoplay while the tab is hidden and resumes when visible again', async () => {
    vi.useFakeTimers()
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const wrapper = mountCarousel({
      autoplayIntervalMs: 4000,
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({ id: 530 + index, title: `Prompt ${index + 1}` }),
      ),
    })
    const { track, scrollTo } = setupScrollableTrack(wrapper)

    await track.trigger('scroll')
    await nextTick()

    hiddenSpy.mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()

    vi.advanceTimersByTime(12000)
    expect(scrollTo).not.toHaveBeenCalled()

    hiddenSpy.mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()

    vi.advanceTimersByTime(4000)
    expect(scrollTo).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('does not autoplay when prefers-reduced-motion is enabled', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    )

    const wrapper = mountCarousel({
      autoplayIntervalMs: 4000,
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({ id: 540 + index, title: `Prompt ${index + 1}` }),
      ),
    })
    const { track, scrollTo } = setupScrollableTrack(wrapper)

    await track.trigger('scroll')
    await nextTick()

    vi.advanceTimersByTime(12000)
    expect(scrollTo).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('resets the autoplay timer after manual dot navigation', async () => {
    vi.useFakeTimers()
    const wrapper = mountCarousel({
      autoplayIntervalMs: 4000,
      prompts: Array.from({ length: 4 }, (_, index) =>
        makePrompt({ id: 550 + index, title: `Prompt ${index + 1}` }),
      ),
    })
    const { track, scrollTo } = setupScrollableTrack(wrapper)

    await track.trigger('scroll')
    await nextTick()

    vi.advanceTimersByTime(3000)
    const secondDot = wrapper.findAll('.landing-carousel__dot')[1]
    if (!secondDot) throw new Error('Expected second carousel dot')
    await secondDot.trigger('click')
    expect(scrollTo).toHaveBeenCalledWith({ left: 100, behavior: 'smooth' })
    scrollTo.mockClear()

    // Only 3s elapsed since manual click: should not have advanced yet
    vi.advanceTimersByTime(3000)
    expect(scrollTo).not.toHaveBeenCalled()

    // Full 4s elapsed since manual click: now advances to next page
    vi.advanceTimersByTime(1000)
    expect(scrollTo).toHaveBeenCalledWith({ left: 200, behavior: 'smooth' })

    wrapper.unmount()
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

    const wrapper = mountCarousel({ prompts: [], isLoading: true })
    expect(observe).not.toHaveBeenCalled()

    await wrapper.setProps({ items: [makePrompt()], isLoading: false })
    await nextTick()

    expect(observe).toHaveBeenCalledWith(
      wrapper.get('[data-testid="style-carousel-track"]').element,
    )

    wrapper.unmount()
    expect(disconnect).toHaveBeenCalled()
  })
})
