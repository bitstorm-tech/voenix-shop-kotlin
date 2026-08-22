import { mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import GenerateStep from '@/components/shop/wizard/steps/GenerateStep.vue'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import { useImageGenerationStore, type GeneratedImage } from '@/stores/shop/imageGeneration'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'
import { useWizardStore } from '@/stores/shop/wizard'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, unknown> | number) => {
      if (typeof params === 'number') return `${key}:${params}`
      if (params?.coins) return `${key}:${params.coins}`
      if (params?.number) return `${key}:${params.number}`
      return key
    },
  }),
}))

function generatedImage(id: string): GeneratedImage {
  return {
    id,
    blob: new Blob([id], { type: 'image/png' }),
    url: `blob:${id}`,
    createdAt: 1,
  }
}

function mountGenerateStep(imageCount = 3) {
  const wizard = useWizardStore()
  const imageGeneration = useImageGenerationStore()
  const magicCoins = useMagicCoinsStore()

  wizard.selectPrompt(7)
  wizard.uploadedFile = new File(['fixture'], 'fixture.png', { type: 'image/png' })
  magicCoins.balance = 10
  magicCoins.isLoading = false
  magicCoins.error = null

  const images = Array.from({ length: imageCount }, (_, index) =>
    generatedImage(`generated-${index + 1}`),
  )
  imageGeneration.generatedImages = images
  imageGeneration.selectedImageId = images.at(-1)?.id ?? null

  return mount(GenerateStep, {
    global: {
      stubs: {
        Button: {
          props: ['asChild'],
          template: '<button v-bind="$attrs"><slot /></button>',
        },
        Dialog: {
          props: ['open'],
          emits: ['update:open'],
          template:
            '<div data-testid="dialog-root" :data-open="String(open)"><slot v-if="open" /></div>',
        },
        DialogContent: {
          template: '<div role="dialog"><slot /></div>',
        },
        DialogDescription: {
          template: '<p><slot /></p>',
        },
        DialogTitle: {
          template: '<h3><slot /></h3>',
        },
        RouterLink: RouterLinkStub,
      },
    },
  })
}

/**
 * Mounts the step in its refused-generation state. No uploaded file is set, so the `onMounted`
 * auto-generation returns early and the error panel is the rendered branch.
 */
function mountRefusedGeneration(refusal: {
  message: string
  code?: string | null
  status?: number | null
  retryAfterSeconds?: number | null
}) {
  const wizard = useWizardStore()
  const imageGeneration = useImageGenerationStore()
  const magicCoins = useMagicCoinsStore()

  wizard.selectPrompt(7)
  magicCoins.balance = 10
  magicCoins.isLoading = false
  magicCoins.error = null

  imageGeneration.error = refusal.message
  imageGeneration.errorCode = refusal.code ?? null
  imageGeneration.errorStatus = refusal.status ?? null
  imageGeneration.errorRetryAfterSeconds = refusal.retryAfterSeconds ?? null

  return mount(GenerateStep, {
    global: {
      stubs: {
        Button: {
          props: ['asChild'],
          template: '<button v-bind="$attrs"><slot /></button>',
        },
        RouterLink: RouterLinkStub,
      },
    },
  })
}

describe('GenerateStep', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('puts the regenerate action above the generated result layout', () => {
    const wrapper = mountGenerateStep()

    const toolbar = wrapper.get('.generate-result-toolbar')
    const layout = wrapper.get('.generate-result-layout')

    expect(toolbar.text()).toContain('configurator.steps.generate.generateAnother')
    expect(
      toolbar.element.compareDocumentPosition(layout.element) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('keeps the mobile DOM order as action, image preview, then variants', () => {
    const wrapper = mountGenerateStep()

    const layout = wrapper.get('.generate-result-layout')
    const preview = wrapper.get('.generate-preview-shell')
    const rail = wrapper.get('.generate-variants-rail')

    expect(layout.classes()).toContain('generate-result-layout--with-variants')
    expect(
      preview.element.compareDocumentPosition(rail.element) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('renders the variants rail only when multiple generated images exist', () => {
    const singleImage = mountGenerateStep(1)

    expect(singleImage.find('.generate-variants-rail').exists()).toBe(false)
    expect(singleImage.get('.generate-result-layout').classes()).not.toContain(
      'generate-result-layout--with-variants',
    )

    const multipleImages = mountGenerateStep(2)

    expect(multipleImages.find('.generate-variants-rail').exists()).toBe(true)
    expect(multipleImages.get('.generate-result-layout').classes()).toContain(
      'generate-result-layout--with-variants',
    )
  })

  it('keeps the history label and passes the desktop rail class to the gallery root', () => {
    const wrapper = mountGenerateStep()

    expect(wrapper.get('.generate-variants-rail p').text()).toBe(
      'configurator.steps.generate.historyLabel',
    )
    expect(wrapper.get('.generate-variants-gallery').classes()).toContain('vg-gallery')
  })

  it('keeps newest-first thumbnails and updates the main image when selecting one', async () => {
    const wrapper = mountGenerateStep(3)
    const imageGeneration = useImageGenerationStore()
    const thumbs = wrapper.findAll('.generate-variants-gallery button')

    expect(wrapper.findAllComponents(ThumbnailButton)).toHaveLength(3)
    expect(thumbs.map((thumb) => thumb.find('img').attributes('src'))).toEqual([
      'blob:generated-3',
      'blob:generated-2',
      'blob:generated-1',
    ])
    expect(thumbs[0]!.attributes('data-state')).toBe('selected')
    expect(wrapper.get('.generate-preview-image').attributes('src')).toBe('blob:generated-3')

    await thumbs[1]!.trigger('click')

    expect(imageGeneration.selectedImageId).toBe('generated-2')
    expect(wrapper.get('.generate-preview-image').attributes('src')).toBe('blob:generated-2')
    expect(wrapper.findAll('.generate-variants-gallery button')[1]!.attributes('data-state')).toBe(
      'selected',
    )
  })

  it('shows a rate-limit message with the Retry-After wait for a 429 refusal', () => {
    const minutes = mountRefusedGeneration({
      message: 'Too many requests',
      status: 429,
      retryAfterSeconds: 3150,
    })

    expect(minutes.get('p').text()).toBe('configurator.steps.generate.rateLimitedMinutes:53')

    const seconds = mountRefusedGeneration({
      message: 'Too many requests',
      status: 429,
      retryAfterSeconds: 45,
    })

    expect(seconds.get('p').text()).toBe('configurator.steps.generate.rateLimitedSeconds:45')

    const withoutHeader = mountRefusedGeneration({
      message: 'Too many requests',
      status: 429,
    })

    expect(withoutHeader.get('p').text()).toBe('configurator.steps.generate.rateLimited')
  })

  it('shows a too-large message for a 413 refusal', () => {
    const wrapper = mountRefusedGeneration({
      message: 'Request body too large',
      status: 413,
    })

    expect(wrapper.get('p').text()).toBe('configurator.steps.generate.imageTooLarge')
  })

  it('keeps the generic message for a refusal without a recognized status', () => {
    const wrapper = mountRefusedGeneration({
      message: 'Validation failed',
      status: 400,
    })

    expect(wrapper.get('p').text()).toBe('configurator.steps.generate.errorMessage')
  })

  it('keeps the insufficient Magic Coins panel for the coin code branch', () => {
    const wrapper = mountRefusedGeneration({
      message: 'Not enough Magic Coins',
      code: 'INSUFFICIENT_MAGIC_COINS',
      status: 402,
    })

    expect(wrapper.get('p').text()).toBe('configurator.steps.generate.insufficientMagicCoins')
  })

  it('opens the lightbox when the main preview is clicked', async () => {
    const wrapper = mountGenerateStep()

    expect(wrapper.get('[data-testid="dialog-root"]').attributes('data-open')).toBe('false')

    await wrapper.get('.generate-preview').trigger('click')

    expect(wrapper.get('[data-testid="dialog-root"]').attributes('data-open')).toBe('true')
    expect(wrapper.get('[role="dialog"] img').attributes('src')).toBe('blob:generated-3')
  })
})
