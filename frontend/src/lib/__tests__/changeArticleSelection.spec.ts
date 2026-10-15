import { describe, expect, it } from 'vitest'
import { resolveDefaultVariant, resolveDisplayVariant } from '@/lib/changeArticleSelection'
import { createMugVariant, createShopMug, createShopTee } from '@/testing/shopCatalog'

function mugArticle(id: number) {
  return createShopMug({
    id,
    variants: [
      createMugVariant({ id: id * 10 + 1, isDefault: false }),
      createMugVariant({ id: id * 10 + 2, name: 'Black', isDefault: true }),
    ],
  })
}

describe('variant resolution', () => {
  it('prefers the default variant and falls back to the first one', () => {
    expect(resolveDefaultVariant(mugArticle(1))?.id).toBe(12)
    expect(
      resolveDefaultVariant(
        createShopMug({
          id: 3,
          variants: [
            createMugVariant({ id: 31, isDefault: false }),
            createMugVariant({ id: 32, isDefault: false }),
          ],
        }),
      )?.id,
    ).toBe(31)
    expect(resolveDefaultVariant(createShopMug({ id: 4, variants: [] }))).toBe(null)
  })

  it('shows the selected variant only for the selected article', () => {
    // The tee's default variant is 21; 23 is black in L.
    const shirt = createShopTee({ id: 2 })

    expect(resolveDisplayVariant(shirt, 2, 23)?.id).toBe(23)
    // The colour and the size of another article do not carry over to this one.
    expect(resolveDisplayVariant(shirt, 9, 23)?.id).toBe(21)
    expect(resolveDisplayVariant(shirt, 2, 999)?.id).toBe(21)
  })
})
