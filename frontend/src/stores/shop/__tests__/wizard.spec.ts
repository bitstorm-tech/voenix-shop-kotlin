import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWizardStore } from '@/stores/shop/wizard'

describe('wizard store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('requires a selected variant before the mug selection is complete', () => {
    const wizard = useWizardStore()

    wizard.selectMug(1)

    expect(wizard.hasSelectedMug).toBe(false)

    wizard.selectVariant(10)

    expect(wizard.hasSelectedMug).toBe(true)
  })
})
