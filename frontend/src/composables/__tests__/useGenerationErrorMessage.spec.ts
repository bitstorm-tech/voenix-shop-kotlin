import { beforeEach, describe, expect, it } from 'vitest'
import { defineComponent, type ComputedRef } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import en from '@/i18n/locales/en.json'
import { useGenerationErrorMessage } from '@/composables/useGenerationErrorMessage'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'

function mountMessage(): ComputedRef<string> {
  let message!: ComputedRef<string>
  const Host = defineComponent({
    setup() {
      message = useGenerationErrorMessage()
      return () => null
    },
  })

  mount(Host, {
    global: {
      plugins: [createI18n({ legacy: false, locale: 'en', messages: { en } })],
    },
  })

  return message
}

describe('useGenerationErrorMessage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('names the request-size bound of a 413', () => {
    const message = mountMessage()
    useImageGenerationStore().errorStatus = 413

    expect(message.value).toBe(en.mugConfigurator.steps.generate.imageTooLarge)
  })

  // The generator's own bounds arrive as a `400` with the field error on `image`, well below the
  // application-wide `413`. Without this branch a 10-30 MB upload showed the generic message.
  it.each([
    ['Image files may carry at most 10 MiB each and 20 MiB per request'],
    ['Image must be a JPEG, PNG, or WebP file'],
  ])('names the generator refusal behind a 400 on the image part (%s)', (backendMessage) => {
    const message = mountMessage()
    const store = useImageGenerationStore()
    store.errorStatus = 400
    store.errorFieldErrors = { image: [backendMessage] }

    expect(message.value).toBe(en.mugConfigurator.steps.generate.imageRejected)
  })

  it('leaves a 400 on another part with the generic message', () => {
    const message = mountMessage()
    const store = useImageGenerationStore()
    store.errorStatus = 400
    store.errorFieldErrors = { promptId: ['A numeric prompt id is required'] }

    expect(message.value).toBe(en.mugConfigurator.steps.generate.errorMessage)
  })

  it('keeps the waiting copy of a 429', () => {
    const message = mountMessage()
    const store = useImageGenerationStore()
    store.errorStatus = 429
    store.errorRetryAfterSeconds = 30

    expect(message.value).toContain('30 seconds')
  })
})
