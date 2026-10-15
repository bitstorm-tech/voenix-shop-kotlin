import { describe, expect, it } from 'vitest'
import {
  toEditorArticle,
  toEditorArticleVariant,
  toEditorMugArticle,
  toEditorSpodArticle,
} from '@/components/shop/editor/types'
import type { MugDetailsDto, MugDto } from '@/stores/shop/catalog'
import { createMugDetails, createShopMug, createShopTee } from '@/testing/shopCatalog'

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

  it('gives a SPOD article a square print area without millimetres', () => {
    expect(toEditorSpodArticle(createShopTee()).printArea).toEqual({
      aspectRatio: 1,
      documentFormatWidthMm: null,
      documentFormatHeightMm: null,
    })
  })

  it('carries the print frame, the product type, and the size chart into the editor', () => {
    const tee = createShopTee({
      sizeChartImageFilename: 'chart.webp',
      printFrame: { leftPct: 25, topPct: 20, widthPct: 50, heightPct: 28.125 },
    })
    const article = toEditorSpodArticle(tee)

    expect(article.type).toBe('SPOD')
    expect(article.productTypeName).toBe("Men's Tee")
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
    expect(toEditorArticle(createShopTee({ id: 2 })).type).toBe('SPOD')
  })

  it('maps a variant of either type and answers null for an unknown id', () => {
    const mug = createShopMug({ id: 1 })
    const tee = createShopTee({ id: 2 })

    expect(toEditorArticleVariant(mug, mug.variants[0]!.id)).toMatchObject({
      type: 'MUG',
      outsideColorCode: '#ffffff',
    })
    // Variant 25 is white (appearance 202) in M: the colour, its first listing image, and its
    // mockup come along.
    expect(toEditorArticleVariant(tee, 25)).toMatchObject({
      type: 'SPOD',
      appearanceId: 202,
      colorName: 'White',
      colorHex: '#ffffff',
      size: 'M',
      exampleImageFilename: 'listing-202-front.webp',
      mockupImageFilename: 'mockup-202.webp',
    })
    expect(toEditorArticleVariant(tee, 999)).toBeNull()
  })
})
