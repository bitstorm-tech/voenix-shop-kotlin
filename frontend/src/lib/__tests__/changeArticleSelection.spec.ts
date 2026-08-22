import { describe, expect, it } from 'vitest'
import { resolveDefaultVariant, resolveDisplayVariant } from '@/lib/changeArticleSelection'
import {
  createMugVariant,
  createShopMug,
  createShopTshirt,
  createTshirtVariant,
} from '@/testing/shopCatalog'

function mugArticle(id: number) {
  return createShopMug({
    id,
    variants: [
      createMugVariant({ id: id * 10 + 1, isDefault: false }),
      createMugVariant({ id: id * 10 + 2, name: 'Black', isDefault: true }),
    ],
  })
}

function shirtArticle(id: number) {
  return createShopTshirt({
    id,
    variants: [
      createTshirtVariant({ id: id * 10 + 1, name: 'Black / M', size: 'M', isDefault: true }),
      createTshirtVariant({ id: id * 10 + 2, name: 'Black / L', size: 'L', isDefault: false }),
      createTshirtVariant({
        id: id * 10 + 3,
        name: 'White / M',
        colorName: 'White',
        colorHex: '#ffffff',
        size: 'M',
        isDefault: false,
      }),
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
    const shirt = shirtArticle(2)

    expect(resolveDisplayVariant(shirt, 2, 23)?.id).toBe(23)
    // The colour and the size of another article do not carry over to this one.
    expect(resolveDisplayVariant(shirt, 9, 23)?.id).toBe(21)
    expect(resolveDisplayVariant(shirt, 2, 999)?.id).toBe(21)
  })
})
