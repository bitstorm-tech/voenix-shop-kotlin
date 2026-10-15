import {
  appearanceOf,
  isMug,
  type MugDto,
  type MugVariantDto,
  type PrintFrameDto,
  type ShopArticle,
  type SpodArticleDto,
  type SpodVariantDto,
} from '@/stores/shop/catalog'

/**
 * What every variant contributes to the editor, whatever the article is made of.
 * `exampleImageFilename` is the catalog photo of the variant: a mug variant's own example image, or
 * the first listing image of a SPOD variant's colour.
 */
interface EditorArticleVariantBase {
  id: number
  name: string
  isDefault: boolean
  exampleImageFilename: string | null
}

/** A mug variant is two colours: the outside and the inside of the cup. */
export interface EditorMugVariant extends EditorArticleVariantBase {
  type: 'MUG'
  outsideColorCode: string
  insideColorCode: string
}

/** A SPOD variant is one colour in one size - the two halves the picker offers separately. */
export interface EditorSpodVariant extends EditorArticleVariantBase {
  type: 'SPOD'
  appearanceId: number
  colorName: string
  colorHex: string
  size: string
  /** The plain product photo of the colour the print frame is laid over; `null` when SPOD has none. */
  mockupImageFilename: string | null
}

export type EditorArticleVariant = EditorMugVariant | EditorSpodVariant

/**
 * The rectangle the design is printed in. `aspectRatio` is the one field the editor really works
 * with; the millimetres exist only where the article is described in millimetres (a mug's document
 * format). A SPOD article is described by a ratio alone, so its millimetres are `null`.
 */
export interface EditorPrintArea {
  aspectRatio: number
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
}

interface EditorArticleBase {
  id: number
  name: string
  descriptionShort: string
  price: number
  /** The price before the discount, in integer cents; `null` when the article has no price discount. */
  regularPrice: number | null
  printArea: EditorPrintArea | null
}

export interface EditorMugArticle extends EditorArticleBase {
  type: 'MUG'
  variants: EditorMugVariant[]
}

export interface EditorSpodArticle extends EditorArticleBase {
  type: 'SPOD'
  /** What kind of product it is ("Men's Tee", "Tote Bag"), as SPOD names it. */
  productTypeName: string
  /** Where the design sits on the mockup photo, in percent of the photo (measured by the sync). */
  printFrame: PrintFrameDto
  sizeChartImageFilename: string | null
  variants: EditorSpodVariant[]
}

/**
 * The article as the editor sees it: the storefront article reduced to what a canvas needs, as one
 * discriminated union over `type`. The editor never touches the catalog DTOs directly, so a new
 * article type is one adapter plus the branches that really differ.
 */
export type EditorArticle = EditorMugArticle | EditorSpodArticle

export function toEditorMugArticle(mug: MugDto): EditorMugArticle {
  const width = mug.mugDetails.documentFormatWidthMm
  const height = mug.mugDetails.documentFormatHeightMm

  return {
    id: mug.id,
    type: 'MUG',
    name: mug.name,
    descriptionShort: mug.descriptionShort,
    price: mug.price,
    regularPrice: mug.regularPrice,
    printArea:
      width != null && height != null && width > 0 && height > 0
        ? {
            aspectRatio: width / height,
            documentFormatWidthMm: width,
            documentFormatHeightMm: height,
          }
        : null,
    variants: mug.variants.map(toEditorMugVariant),
  }
}

export function toEditorSpodArticle(article: SpodArticleDto): EditorSpodArticle {
  return {
    id: article.id,
    type: 'SPOD',
    name: article.name,
    descriptionShort: article.descriptionShort,
    price: article.price,
    regularPrice: article.regularPrice,
    // A SPOD design is always square, and there are no millimetres to lose.
    printArea: { aspectRatio: 1, documentFormatWidthMm: null, documentFormatHeightMm: null },
    productTypeName: article.productTypeName,
    printFrame: article.printFrame,
    sizeChartImageFilename: article.sizeChartImageFilename,
    variants: article.variants.map((variant) => toEditorSpodVariant(article, variant)),
  }
}

export function toEditorArticle(article: ShopArticle): EditorArticle {
  return isMug(article) ? toEditorMugArticle(article) : toEditorSpodArticle(article)
}

export function toEditorMugVariant(variant: MugVariantDto): EditorMugVariant {
  return {
    id: variant.id,
    type: 'MUG',
    name: variant.name,
    outsideColorCode: variant.outsideColorCode,
    insideColorCode: variant.insideColorCode,
    isDefault: variant.isDefault,
    exampleImageFilename: variant.exampleImageFilename,
  }
}

export function toEditorSpodVariant(
  article: SpodArticleDto,
  variant: SpodVariantDto,
): EditorSpodVariant {
  const appearance = appearanceOf(article, variant)

  return {
    id: variant.id,
    type: 'SPOD',
    name: variant.name,
    appearanceId: variant.appearanceId,
    colorName: appearance.colorName,
    colorHex: appearance.colorHex,
    size: variant.size,
    isDefault: variant.isDefault,
    exampleImageFilename: appearance.listingImageFilenames[0] ?? null,
    mockupImageFilename: appearance.mockupImageFilename,
  }
}

/**
 * The variant of an article by id, adapted for the editor. It answers `null` for an id the article
 * does not offer, so a stale draft shows the invalid-context state instead of an empty canvas.
 */
export function toEditorArticleVariant(
  article: ShopArticle,
  variantId: number,
): EditorArticleVariant | null {
  const variant = article.variants.find((item) => item.id === variantId)
  if (!variant) {
    return null
  }

  // The variant comes out of this article's own list, so the article's type names it.
  return isMug(article)
    ? toEditorMugVariant(variant as MugVariantDto)
    : toEditorSpodVariant(article, variant as SpodVariantDto)
}
