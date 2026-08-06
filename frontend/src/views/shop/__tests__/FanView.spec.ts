import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FanView from '@/views/shop/FanView.vue'
import { FileInput } from '@/components/ui/file-input'

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

describe('FanView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => 'blob:fan-upload'),
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

  it('uses FileInput for the fan image picker flow', async () => {
    const wrapper = mount(FanView)
    const file = new File(['image'], 'fan.png', { type: 'image/png' })
    const input = wrapper.get('[data-testid="fan-upload-input"]')

    expect(wrapper.findComponent(FileInput).exists()).toBe(true)

    Object.defineProperty(input.element, 'files', {
      value: [file],
      configurable: true,
    })

    await input.trigger('change')
    await nextTick()

    expect(URL.createObjectURL).toHaveBeenCalledWith(file)
    expect(wrapper.text()).toContain('fanConfigurator.chooseAnother')
    expect(wrapper.findComponent(FileInput).exists()).toBe(true)
  })
})
