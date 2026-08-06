import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WizardView from '@/views/shop/WizardView.vue'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useEditorStore } from '@/stores/shop/editor'
import { useImageGenerationStore, type GeneratedImage } from '@/stores/shop/imageGeneration'
import { useMugsStore, type MugDto } from '@/stores/shop/mugs'
import type { PromptDto } from '@/stores/shop/prompts'
import { useWizardStore } from '@/stores/shop/wizard'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}))

function makeMug(id: number): MugDto {
  return {
    id,
    position: id,
    name: `Mug ${id}`,
    descriptionShort: 'Short',
    descriptionLong: 'Long',
    categoryId: 1,
    price: 1499,
    mugDetails: {
      documentFormatWidthMm: 200,
      documentFormatHeightMm: 90,
    },
    variants: [
      {
        id: id * 10 + 1,
        name: 'White',
        outsideColorCode: '#ffffff',
        insideColorCode: '#ffffff',
        isDefault: true,
      },
      {
        id: id * 10 + 2,
        name: 'Black',
        outsideColorCode: '#111111',
        insideColorCode: '#111111',
        isDefault: false,
      },
    ],
  }
}

function generatedImage(id: string): GeneratedImage {
  return {
    id,
    blob: new Blob([id], { type: 'image/png' }),
    url: `blob:wizard-${id}`,
    createdAt: 1,
  }
}

function makePrompt(id: number): PromptDto {
  return {
    id,
    position: id,
    title: `Prompt ${id}`,
    category: {
      id: 1,
      name: 'Portrait',
      position: 1,
    },
    subcategory: {
      id: 2,
      name: 'Bold',
      position: 1,
    },
    exampleImageFilename: 'prompt.webp',
    price: {
      salesTotalNet: 12,
      salesTotalGross: 14.28,
      salesTotalTax: 2.28,
      salesVatRatePercent: 19,
    },
  }
}

function stubPromptsFetch(prompts: PromptDto[] = [makePrompt(100001)]) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: vi.fn().mockResolvedValue({ items: prompts }),
  } as unknown as Response)

  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function createRouterForWizard(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/wizard', name: 'wizard', component: WizardView },
      { path: '/editor/:draftId?', name: 'editor', component: { template: '<div />' } },
    ],
  })
}

const defaultStubs = {
  Button: {
    props: ['disabled'],
    template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
  },
  StepIndicator: {
    props: ['steps', 'currentStep'],
    template: '<div data-testid="step-indicator" />',
  },
  WizardNavigation: {
    props: ['currentStep', 'totalSteps', 'steps', 'canProceed', 'isSubmitting', 'isLastStep'],
    emits: ['next', 'back'],
    template: `
      <nav
        data-testid="wizard-navigation"
        :data-current-step="String(currentStep)"
        :data-total-steps="String(totalSteps)"
        :data-can-proceed="canProceed ? 'true' : 'false'"
        :data-is-last-step="isLastStep ? 'true' : 'false'"
      >
        <button data-testid="wizard-back" @click="$emit('back')">back</button>
        <button
          data-testid="wizard-next"
          :disabled="!canProceed || isSubmitting"
          @click="$emit('next')"
        >
          {{ isLastStep ? 'finish' : 'next' }}
        </button>
        <ol data-testid="wizard-step-labels">
          <li v-for="step in steps" :key="step.number">{{ step.label }}</li>
        </ol>
      </nav>
    `,
  },
  SelectMugStep: {
    name: 'SelectMugStep',
    template: '<section data-testid="select-mug-step" />',
  },
  SelectStyleStep: {
    name: 'SelectStyleStep',
    template: '<section data-testid="select-style-step" />',
  },
  UploadImageStep: {
    name: 'UploadImageStep',
    template: '<section data-testid="upload-image-step" />',
  },
  GenerateStep: {
    name: 'GenerateStep',
    template: '<section data-testid="generate-step" />',
  },
}

async function mountWizard(path = '/wizard', stubs: Record<string, unknown> = {}) {
  const router = createRouterForWizard()
  await router.push(path)
  await router.isReady()

  const wrapper = mount(WizardView, {
    global: {
      plugins: [router],
      stubs: {
        ...defaultStubs,
        ...stubs,
      },
    },
  })

  await flushPromises()
  return { router, wrapper }
}

function stepLabels(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('[data-testid="wizard-step-labels"] li').map((item) => item.text())
}

function nextButton(wrapper: ReturnType<typeof mount>) {
  return wrapper.get('[data-testid="wizard-next"]')
}

describe('WizardView', () => {
  let nextUuid = 1
  let nextUrl = 1

  beforeEach(() => {
    setActivePinia(createPinia())
    nextUuid = 1
    nextUrl = 1
    window.scrollTo = vi.fn()

    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => `blob:editor-${nextUrl++}`),
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      configurable: true,
    })
    vi.stubGlobal('crypto', {
      randomUUID: () => `editor-id-${nextUuid++}`,
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('uses the style, product, upload, generate order without an editor step by default', async () => {
    const { wrapper } = await mountWizard()

    expect(stepLabels(wrapper)).toEqual([
      'mugConfigurator.steps.selectStyle.label',
      'mugConfigurator.steps.selectMug.label',
      'mugConfigurator.steps.uploadImage.label',
      'mugConfigurator.steps.generate.label',
    ])
    expect(wrapper.get('[data-testid="wizard-navigation"]').attributes('data-total-steps')).toBe(
      '4',
    )
    expect(wrapper.find('[data-testid="select-style-step"]').exists()).toBe(true)
    expect(stepLabels(wrapper)).not.toContain('mugConfigurator.steps.edit.label')
  })

  it('starts with upload for start=upload and then asks for product context', async () => {
    const { wrapper } = await mountWizard('/wizard?start=upload')
    const wizardStore = useWizardStore()

    expect(stepLabels(wrapper)).toEqual([
      'mugConfigurator.steps.uploadImage.label',
      'mugConfigurator.steps.selectMug.label',
      'mugConfigurator.steps.selectStyle.label',
      'mugConfigurator.steps.generate.label',
    ])
    expect(wrapper.find('[data-testid="upload-image-step"]').exists()).toBe(true)

    wizardStore.uploadedFile = new File(['upload'], 'upload.png', { type: 'image/png' })
    await nextTick()
    await nextButton(wrapper).trigger('click')

    expect(wrapper.find('[data-testid="select-mug-step"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="wizard-navigation"]').attributes('data-current-step')).toBe(
      '2',
    )
  })

  it('blocks wizard content while validating a promptId query', async () => {
    let resolveFetch!: (response: Response) => void
    const fetchPromise = new Promise<Response>((resolve) => {
      resolveFetch = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() => fetchPromise),
    )

    const { wrapper } = await mountWizard('/wizard?promptId=100001')

    expect(wrapper.text()).toContain('mugConfigurator.loadingSelectedStyle')
    expect(wrapper.find('[data-testid="wizard-navigation"]').exists()).toBe(false)

    resolveFetch({
      ok: true,
      json: vi.fn().mockResolvedValue({ items: [makePrompt(100001)] }),
    } as unknown as Response)
    await flushPromises()

    expect(wrapper.find('[data-testid="wizard-navigation"]').exists()).toBe(true)
  })

  it('starts at product selection for a valid promptId query with no selected product', async () => {
    const fetchMock = stubPromptsFetch()

    const { wrapper } = await mountWizard('/wizard?promptId=100001')
    const wizardStore = useWizardStore()

    expect(fetchMock).toHaveBeenCalledWith('/api/prompts')
    expect(wizardStore.selectedPromptId).toBe(100001)
    expect(wrapper.get('[data-testid="wizard-navigation"]').attributes('data-current-step')).toBe(
      '2',
    )
    expect(wrapper.find('[data-testid="select-mug-step"]').exists()).toBe(true)
    expect(stepLabels(wrapper)).toEqual([
      'mugConfigurator.steps.selectStyle.label',
      'mugConfigurator.steps.selectMug.label',
      'mugConfigurator.steps.uploadImage.label',
      'mugConfigurator.steps.generate.label',
    ])
  })

  it('starts at upload for a valid promptId query with an already selected product', async () => {
    stubPromptsFetch()
    const wizardStore = useWizardStore()
    wizardStore.selectMug(1, 11)

    const { wrapper } = await mountWizard('/wizard?promptId=100001')

    expect(wizardStore.selectedPromptId).toBe(100001)
    expect(wrapper.get('[data-testid="wizard-navigation"]').attributes('data-current-step')).toBe(
      '2',
    )
    expect(wrapper.find('[data-testid="upload-image-step"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="select-mug-step"]').exists()).toBe(false)
    expect(stepLabels(wrapper)).toEqual([
      'mugConfigurator.steps.selectStyle.label',
      'mugConfigurator.steps.uploadImage.label',
      'mugConfigurator.steps.generate.label',
    ])
  })

  it.each(['/wizard?promptId=abc', '/wizard?promptId=0', '/wizard?promptId=9007199254740992'])(
    'ignores invalid promptId query values for %s',
    async (path) => {
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)
      const wizardStore = useWizardStore()
      wizardStore.selectPrompt(100001)

      await mountWizard(path)

      expect(wizardStore.selectedPromptId).toBeNull()
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it('clears a stale promptId query after prompt validation and falls back to style selection', async () => {
    stubPromptsFetch([])
    const wizardStore = useWizardStore()
    wizardStore.selectPrompt(100001)

    const { wrapper } = await mountWizard('/wizard?promptId=100001')

    expect(wizardStore.selectedPromptId).toBeNull()
    expect(wrapper.get('[data-testid="wizard-navigation"]').attributes('data-current-step')).toBe(
      '1',
    )
    expect(wrapper.find('[data-testid="select-style-step"]').exists()).toBe(true)
    expect(stepLabels(wrapper)).toEqual([
      'mugConfigurator.steps.selectStyle.label',
      'mugConfigurator.steps.selectMug.label',
      'mugConfigurator.steps.uploadImage.label',
      'mugConfigurator.steps.generate.label',
    ])
  })

  it('skips the product step when product context already exists in the wizard store', async () => {
    const wizardStore = useWizardStore()
    wizardStore.selectMug(1, 11)

    const { wrapper } = await mountWizard()

    expect(stepLabels(wrapper)).toEqual([
      'mugConfigurator.steps.selectStyle.label',
      'mugConfigurator.steps.uploadImage.label',
      'mugConfigurator.steps.generate.label',
    ])
    expect(wrapper.find('[data-testid="select-style-step"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="select-mug-step"]').exists()).toBe(false)
  })

  it('creates an editor draft from generated images and navigates to the editor on finish', async () => {
    const wizardStore = useWizardStore()
    wizardStore.selectMug(1, 11)
    wizardStore.selectPrompt(7)
    wizardStore.uploadedFile = new File(['upload'], 'upload.png', { type: 'image/png' })

    const firstImage = generatedImage('generated-1')
    const secondImage = generatedImage('generated-2')
    const imageGenerationStore = useImageGenerationStore()
    imageGenerationStore.generatedImages = [firstImage, secondImage]
    imageGenerationStore.selectedImageId = secondImage.id

    const { router, wrapper } = await mountWizard()

    await nextButton(wrapper).trigger('click')
    await nextButton(wrapper).trigger('click')
    await nextButton(wrapper).trigger('click')
    await flushPromises()

    const editorStore = useEditorStore()
    const draft = editorStore.drafts[0]

    expect(draft).toMatchObject({
      source: 'wizard',
      articleId: 1,
      variantId: 11,
    })
    expect(draft?.images.map((image) => image.blob)).toEqual([firstImage.blob, secondImage.blob])
    expect(router.currentRoute.value.name).toBe('editor')
    expect(router.currentRoute.value.params.draftId).toBe(draft?.id)
  })

  it('does not treat mug and variant query params as wizard product context', async () => {
    const mugsStore = useMugsStore()
    mugsStore.mugs = [makeMug(1)]
    vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()

    const categoriesStore = useArticleCategoriesStore()
    categoriesStore.allCategories = { MUG: [{ id: 1, name: 'Mugs', position: 1 }] }
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()

    await mountWizard('/wizard?mug=1&variant=11', {
      SelectMugStep: false,
      MugCard: {
        props: ['mug'],
        template: '<article data-testid="mug-card">{{ mug.name }}</article>',
      },
    })

    const wizardStore = useWizardStore()
    expect(wizardStore.selectedMugId).toBeNull()
    expect(wizardStore.selectedVariantId).toBeNull()
  })
})
