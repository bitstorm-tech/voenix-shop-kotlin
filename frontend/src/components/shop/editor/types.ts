import type { MugDto, MugVariantDto } from '@/stores/shop/mugs'

export interface EditorArticleVariant {
  id: number
  name: string
  outsideColorCode: string
  insideColorCode: string
  isDefault: boolean
  exampleImageFilename?: string
}

export interface EditorPrintArea {
  documentFormatWidthMm: number
  documentFormatHeightMm: number
  aspectRatio: number
}

export interface EditorArticle {
  id: number
  type: 'MUG'
  name: string
  descriptionShort: string
  price: number
  printArea: EditorPrintArea | null
  variants: EditorArticleVariant[]
}

export function toEditorArticle(mug: MugDto): EditorArticle {
  const width = mug.mugDetails?.documentFormatWidthMm
  const height = mug.mugDetails?.documentFormatHeightMm

  return {
    id: mug.id,
    type: 'MUG',
    name: mug.name,
    descriptionShort: mug.descriptionShort,
    price: mug.price,
    printArea:
      width != null && height != null && width > 0 && height > 0
        ? {
            documentFormatWidthMm: width,
            documentFormatHeightMm: height,
            aspectRatio: width / height,
          }
        : null,
    variants: mug.variants.map(toEditorArticleVariant),
  }
}

export function toEditorArticleVariant(variant: MugVariantDto): EditorArticleVariant {
  return {
    id: variant.id,
    name: variant.name,
    outsideColorCode: variant.outsideColorCode,
    insideColorCode: variant.insideColorCode,
    isDefault: variant.isDefault,
    exampleImageFilename: variant.exampleImageFilename,
  }
}
