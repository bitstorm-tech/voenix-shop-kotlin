import {
  isMug,
  type MugDto,
  type MugVariantDto,
  type PrintAspectRatio,
  type PrintFrameDto,
  type ShopArticle,
  type TshirtDto,
  type TshirtVariantDto,
} from '@/stores/shop/catalog'

/** What every variant contributes to the editor, whatever the article is made of. */
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

/** A shirt variant is one colour in one size - the two halves the picker offers separately. */
export interface EditorTshirtVariant extends EditorArticleVariantBase {
  type: 'TSHIRT'
  colorName: string
  colorHex: string
  size: string
}

export type EditorArticleVariant = EditorMugVariant | EditorTshirtVariant

/**
 * The rectangle the design is printed in. `aspectRatio` is the one field the editor really works
 * with; the millimetres exist only where the article is described in millimetres (a mug's document
 * format). A shirt is described by a ratio alone, so its millimetres are `null`.
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
  printArea: EditorPrintArea | null
}

export interface EditorMugArticle extends EditorArticleBase {
  type: 'MUG'
  variants: EditorMugVariant[]
}

export interface EditorTshirtArticle extends EditorArticleBase {
  type: 'TSHIRT'
  /** The ratio as it is written on the wire, for the copy that names the format. */
  printAspectRatio: PrintAspectRatio
  /** Where the design sits on the mockup photo, in percent of the photo (admin-calibrated). */
  printFrame: PrintFrameDto
  sizeChartImageFilename: string | null
  variants: EditorTshirtVariant[]
}

/**
 * The article as the editor sees it: the storefront article reduced to what a canvas needs, as one
 * discriminated union over `type`. The editor never touches the catalog DTOs directly, so a new
 * article type is one adapter plus the branches that really differ.
 */
export type EditorArticle = EditorMugArticle | EditorTshirtArticle

/** The two ratios the backend allows for a shirt design, as the numbers the frame is drawn with. */
export const PRINT_ASPECT_RATIOS: Record<PrintAspectRatio, number> = {
  '16:9': 16 / 9,
  '1:1': 1,
}

export function toEditorMugArticle(mug: MugDto): EditorMugArticle {
  const width = mug.mugDetails.documentFormatWidthMm
  const height = mug.mugDetails.documentFormatHeightMm

  return {
    id: mug.id,
    type: 'MUG',
    name: mug.name,
    descriptionShort: mug.descriptionShort,
    price: mug.price,
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

export function toEditorTshirtArticle(tshirt: TshirtDto): EditorTshirtArticle {
  return {
    id: tshirt.id,
    type: 'TSHIRT',
    name: tshirt.name,
    descriptionShort: tshirt.descriptionShort,
    price: tshirt.price,
    // A shirt always has a print area: its ratio comes from the closed `printAspectRatio` enum,
    // and there are no millimetres to lose.
    printArea: {
      aspectRatio: PRINT_ASPECT_RATIOS[tshirt.printAspectRatio],
      documentFormatWidthMm: null,
      documentFormatHeightMm: null,
    },
    printAspectRatio: tshirt.printAspectRatio,
    printFrame: tshirt.printFrame,
    sizeChartImageFilename: tshirt.sizeChartImageFilename,
    variants: tshirt.variants.map(toEditorTshirtVariant),
  }
}

export function toEditorArticle(article: ShopArticle): EditorArticle {
  return isMug(article) ? toEditorMugArticle(article) : toEditorTshirtArticle(article)
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

export function toEditorTshirtVariant(variant: TshirtVariantDto): EditorTshirtVariant {
  return {
    id: variant.id,
    type: 'TSHIRT',
    name: variant.name,
    colorName: variant.colorName,
    colorHex: variant.colorHex,
    size: variant.size,
    isDefault: variant.isDefault,
    exampleImageFilename: variant.exampleImageFilename,
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
    : toEditorTshirtVariant(variant as TshirtVariantDto)
}
