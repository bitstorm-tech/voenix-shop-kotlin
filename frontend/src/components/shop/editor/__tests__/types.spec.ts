import { describe, expect, it } from 'vitest'
import { toEditorArticle } from '@/components/shop/editor/types'
import type { MugDetailsDto, MugDto } from '@/stores/shop/mugs'
import { createMugDetails, createShopMug } from '@/testing/shopCatalog'

function makeMug(mugDetails: Partial<MugDetailsDto>): MugDto {
  return createShopMug({
    id: 10,
    name: 'Classic Mug',
    mugDetails: createMugDetails(mugDetails),
    variants: [],
  })
}

describe('editor type adapters', () => {
  it('maps positive mug document dimensions to an editor print area', () => {
    const article = toEditorArticle(
      makeMug({ documentFormatWidthMm: 200, documentFormatHeightMm: 90 }),
    )

    expect(article.printArea).toEqual({
      documentFormatWidthMm: 200,
      documentFormatHeightMm: 90,
      aspectRatio: 200 / 90,
    })
  })

  it('ignores non-positive mug document dimensions', () => {
    expect(
      toEditorArticle(makeMug({ documentFormatWidthMm: 200, documentFormatHeightMm: 0 })).printArea,
    ).toBeNull()
    expect(
      toEditorArticle(makeMug({ documentFormatWidthMm: 0, documentFormatHeightMm: 90 })).printArea,
    ).toBeNull()
  })
})
