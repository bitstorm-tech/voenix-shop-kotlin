import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PwaUpdateBanner from '@/components/shared/PwaUpdateBanner.vue'
import { usePwaUpdateStore } from '@/stores/shared/pwaUpdate'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

describe('PwaUpdateBanner', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('uses shared update actions for dismissing and applying the pending update', async () => {
    const updateServiceWorker = vi.fn()
    const store = usePwaUpdateStore()
    store.setUpdateAvailable(updateServiceWorker)

    const wrapper = mount(PwaUpdateBanner)
    const buttons = wrapper.findAll('button')

    expect(buttons.map((button) => button.text())).toEqual([
      'pwaUpdate.dismiss',
      'pwaUpdate.updateButton',
    ])

    await buttons[1]!.trigger('click')
    expect(updateServiceWorker).toHaveBeenCalledWith(true)

    await buttons[0]!.trigger('click')
    expect(store.needsRefresh).toBe(false)
  })
})
