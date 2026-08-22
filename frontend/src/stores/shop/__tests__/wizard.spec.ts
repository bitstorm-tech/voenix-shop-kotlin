import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWizardStore } from '@/stores/shop/wizard'

describe('wizard store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('requires a selected variant before the article selection is complete', () => {
    const wizard = useWizardStore()

    wizard.selectArticle('MUG', 1)

    expect(wizard.hasSelectedArticle).toBe(false)

    wizard.selectVariant(10)

    expect(wizard.hasSelectedArticle).toBe(true)
    expect(wizard.selectedArticleType).toBe('MUG')
    expect(wizard.selectedArticleId).toBe(1)
  })

  it('keeps the article type of a shirt selection and drops everything on a reset', () => {
    const wizard = useWizardStore()

    wizard.selectArticle('TSHIRT', 2, 21)

    expect(wizard.hasSelectedArticle).toBe(true)
    expect(wizard.selectedArticleType).toBe('TSHIRT')
    expect(wizard.selectedVariantId).toBe(21)

    wizard.clearArticleSelection()

    expect(wizard.hasSelectedArticle).toBe(false)
    expect(wizard.selectedArticleType).toBeNull()
    expect(wizard.selectedArticleId).toBeNull()
    expect(wizard.selectedVariantId).toBeNull()
  })
})
