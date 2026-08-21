import { describe, expect, it } from 'vitest'
import {
  toEditorArticle,
  toEditorArticleVariant,
  toEditorMugArticle,
  toEditorTshirtArticle,
} from '@/components/shop/editor/types'
import type { MugDetailsDto, MugDto } from '@/stores/shop/catalog'
import {
  createMugDetails,
  createShopMug,
  createShopTshirt,
  createTshirtVariant,
} from '@/testing/shopCatalog'

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
    const article = toEditorMugArticle(
      makeMug({ documentFormatWidthMm: 200, documentFormatHeightMm: 90 }),
    )

    expect(article.type).toBe('MUG')
    expect(article.printArea).toEqual({
      aspectRatio: 200 / 90,
      documentFormatWidthMm: 200,
      documentFormatHeightMm: 90,
    })
  })

  it('ignores non-positive mug document dimensions', () => {
    expect(
      toEditorMugArticle(makeMug({ documentFormatWidthMm: 200, documentFormatHeightMm: 0 }))
        .printArea,
    ).toBeNull()
    expect(
      toEditorMugArticle(makeMug({ documentFormatWidthMm: 0, documentFormatHeightMm: 90 }))
        .printArea,
    ).toBeNull()
  })

  it('derives the shirt print area from the print aspect ratio, without millimetres', () => {
    const wide = toEditorTshirtArticle(createShopTshirt({ printAspectRatio: '16:9' }))
    const square = toEditorTshirtArticle(createShopTshirt({ printAspectRatio: '1:1' }))

    expect(wide.printArea).toEqual({
      aspectRatio: 16 / 9,
      documentFormatWidthMm: null,
      documentFormatHeightMm: null,
    })
    expect(square.printArea).toEqual({
      aspectRatio: 1,
      documentFormatWidthMm: null,
      documentFormatHeightMm: null,
    })
  })

  it('carries the print frame and the size chart of a shirt into the editor', () => {
    const article = toEditorTshirtArticle(
      createShopTshirt({
        sizeChartImageFilename: 'chart.webp',
        printFrame: { leftPct: 25, topPct: 20, widthPct: 50, heightPct: 28.125 },
      }),
    )

    expect(article.type).toBe('TSHIRT')
    expect(article.printAspectRatio).toBe('16:9')
    expect(article.sizeChartImageFilename).toBe('chart.webp')
    expect(article.printFrame).toEqual({
      leftPct: 25,
      topPct: 20,
      widthPct: 50,
      heightPct: 28.125,
    })
  })

  it('dispatches on the article type of the storefront article', () => {
    expect(toEditorArticle(createShopMug({ id: 1 })).type).toBe('MUG')
    expect(toEditorArticle(createShopTshirt({ id: 2 })).type).toBe('TSHIRT')
  })

  it('maps a variant of either type and answers null for an unknown id', () => {
    const mug = createShopMug({ id: 1 })
    const tshirt = createShopTshirt({
      id: 2,
      variants: [
        createTshirtVariant({ id: 21, colorName: 'Black', colorHex: '#101010', size: 'M' }),
      ],
    })

    expect(toEditorArticleVariant(mug, mug.variants[0]!.id)).toMatchObject({
      type: 'MUG',
      outsideColorCode: '#ffffff',
    })
    expect(toEditorArticleVariant(tshirt, 21)).toMatchObject({
      type: 'TSHIRT',
      colorName: 'Black',
      colorHex: '#101010',
      size: 'M',
    })
    expect(toEditorArticleVariant(tshirt, 999)).toBeNull()
  })
})
