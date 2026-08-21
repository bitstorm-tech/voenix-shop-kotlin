import { describe, expect, it } from 'vitest'
import {
  toEditorMugArticle,
  toEditorTshirtArticle,
  type EditorArticle,
} from '@/components/shop/editor/types'
import {
  decideArticleChangeSelection,
  hasDifferentPrintAspectRatio,
  resolveDefaultVariant,
  resolveDisplayVariant,
} from '@/lib/changeArticleSelection'
import {
  createMugDetails,
  createMugVariant,
  createShopMug,
  createShopTshirt,
  createTshirtVariant,
} from '@/testing/shopCatalog'

function mugArticle(id: number, widthMm = 200, heightMm = 90): EditorArticle {
  return toEditorMugArticle(
    createShopMug({
      id,
      mugDetails: createMugDetails({
        documentFormatWidthMm: widthMm,
        documentFormatHeightMm: heightMm,
      }),
      variants: [
        createMugVariant({ id: id * 10 + 1, isDefault: false }),
        createMugVariant({ id: id * 10 + 2, name: 'Black', isDefault: true }),
      ],
    }),
  )
}

function shirtArticle(id: number, printAspectRatio: '16:9' | '1:1' = '16:9'): EditorArticle {
  return toEditorTshirtArticle(
    createShopTshirt({
      id,
      printAspectRatio,
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
    }),
  )
}

describe('variant resolution', () => {
  it('prefers the default variant and falls back to the first one', () => {
    expect(resolveDefaultVariant(mugArticle(1))?.id).toBe(12)
    expect(
      resolveDefaultVariant(
        toEditorMugArticle(
          createShopMug({
            id: 3,
            variants: [
              createMugVariant({ id: 31, isDefault: false }),
              createMugVariant({ id: 32, isDefault: false }),
            ],
          }),
        ),
      )?.id,
    ).toBe(31)
    expect(resolveDefaultVariant(toEditorMugArticle(createShopMug({ id: 4, variants: [] })))).toBe(
      null,
    )
  })

  it('shows the selected variant only for the selected article', () => {
    const shirt = shirtArticle(2)

    expect(resolveDisplayVariant(shirt, 2, 23)?.id).toBe(23)
    // The colour and the size of another article do not carry over to this one.
    expect(resolveDisplayVariant(shirt, 9, 23)?.id).toBe(21)
    expect(resolveDisplayVariant(shirt, 2, 999)?.id).toBe(21)
  })
})

describe('print aspect ratio comparison', () => {
  it('treats equal ratios of different article types as the same frame', () => {
    // A 16:9 shirt and a mug whose document format is 16:9 print into the same rectangle.
    expect(hasDifferentPrintAspectRatio(mugArticle(1, 160, 90), shirtArticle(2, '16:9'))).toBe(
      false,
    )
    expect(hasDifferentPrintAspectRatio(mugArticle(1, 200, 90), shirtArticle(2, '16:9'))).toBe(true)
    expect(hasDifferentPrintAspectRatio(shirtArticle(2, '16:9'), shirtArticle(3, '1:1'))).toBe(true)
  })

  it('counts a missing print area as a different ratio', () => {
    const withoutPrintArea = toEditorMugArticle(
      createShopMug({
        id: 5,
        mugDetails: createMugDetails({ documentFormatWidthMm: null, documentFormatHeightMm: null }),
      }),
    )

    expect(hasDifferentPrintAspectRatio(withoutPrintArea, mugArticle(1))).toBe(true)
    expect(hasDifferentPrintAspectRatio(null, mugArticle(1))).toBe(true)
  })
})

describe('decideArticleChangeSelection', () => {
  it('resets the crop transforms when the article change changes the print ratio', () => {
    const decision = decideArticleChangeSelection({
      currentArticle: mugArticle(1, 200, 90),
      currentArticleId: 1,
      currentVariantId: 12,
      nextArticle: shirtArticle(2, '1:1'),
    })

    expect(decision).toEqual({
      selectedArticleId: 2,
      selectedVariantId: 21,
      isArticleChange: true,
      isVariantOnlyChange: false,
      shouldResetCropTransforms: true,
    })
  })

  it('keeps the crop transforms when another article prints in the same ratio', () => {
    const decision = decideArticleChangeSelection({
      currentArticle: mugArticle(1, 160, 90),
      currentArticleId: 1,
      currentVariantId: 12,
      nextArticle: shirtArticle(2, '16:9'),
    })

    expect(decision.isArticleChange).toBe(true)
    expect(decision.shouldResetCropTransforms).toBe(false)
  })

  it('keeps the crop transforms for a colour or size change of the same shirt', () => {
    const shirt = shirtArticle(2)

    const sizeChange = decideArticleChangeSelection({
      currentArticle: shirt,
      currentArticleId: 2,
      currentVariantId: 21,
      nextArticle: shirt,
      requestedVariantId: 22,
    })

    expect(sizeChange).toEqual({
      selectedArticleId: 2,
      selectedVariantId: 22,
      isArticleChange: false,
      isVariantOnlyChange: true,
      shouldResetCropTransforms: false,
    })

    const colorChange = decideArticleChangeSelection({
      currentArticle: shirt,
      currentArticleId: 2,
      currentVariantId: 21,
      nextArticle: shirt,
      requestedVariantId: 23,
    })

    expect(colorChange.isVariantOnlyChange).toBe(true)
    expect(colorChange.shouldResetCropTransforms).toBe(false)
  })

  it('keeps the current variant when the same article is re-selected without a request', () => {
    const shirt = shirtArticle(2)

    const decision = decideArticleChangeSelection({
      currentArticle: shirt,
      currentArticleId: 2,
      currentVariantId: 22,
      nextArticle: shirt,
    })

    expect(decision.selectedVariantId).toBe(22)
    expect(decision.isVariantOnlyChange).toBe(false)
  })
})
