import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import UploadImageStep from '@/components/shop/wizard/steps/UploadImageStep.vue'
import { FileInput } from '@/components/ui/file-input'
import { useWizardStore } from '@/stores/shop/wizard'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function mountUploadImageStepWithPreview() {
  const wizard = useWizardStore()
  wizard.uploadedFile = new File(['old'], 'old.png', { type: 'image/png' })
  wizard.previewUrl = 'blob:old'

  const wrapper = mount(UploadImageStep, {
    global: {
      stubs: {
        ImageCropDialog: {
          props: ['open'],
          template: '<div data-testid="crop-dialog" :data-open="String(open)" />',
        },
      },
    },
  })

  return { wizard, wrapper }
}

describe('UploadImageStep', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => 'blob:new'),
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      configurable: true,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses FileInput for replacing an uploaded image', async () => {
    const { wizard, wrapper } = mountUploadImageStepWithPreview()
    const file = new File(['new'], 'new.png', { type: 'image/png' })
    const input = wrapper.get('[data-testid="wizard-replacement-image-input"]')

    expect(wrapper.findComponent(FileInput).exists()).toBe(true)
    expect(wrapper.findComponent(FileInput).props()).toMatchObject({
      inputTestId: 'wizard-replacement-image-input',
      resetOnSelect: true,
      variant: 'outline',
    })

    Object.defineProperty(input.element, 'files', {
      value: [file],
      configurable: true,
    })

    await input.trigger('change')

    expect(wizard.uploadedFile).toBe(file)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:old')
    expect(wrapper.get('[data-testid="crop-dialog"]').attributes('data-open')).toBe('true')
  })
})
