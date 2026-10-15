import type { ShopArticleType } from '@/stores/shop/catalog'

/**
 * Each article type keeps the photo of a variant in its own folder, so the URL of a photo cannot
 * be built from the filename alone - the type names the folder. A mug variant has its own example
 * image; a SPOD variant shows the listing images of its colour.
 */
const VARIANT_EXAMPLE_IMAGE_FOLDERS: Record<ShopArticleType, string> = {
  MUG: 'articles/mugs/variant-example-images',
  SPOD: 'articles/spod/listing-images',
}

export function variantExampleImageUrl(
  articleType: ShopArticleType,
  filename: string,
  size: number,
): string {
  return `/api/images/public/${size}/${VARIANT_EXAMPLE_IMAGE_FOLDERS[articleType]}/${filename}`
}

/** The plain product photo of a SPOD colour that the editor lays the print frame over. */
export function spodMockupImageUrl(filename: string, size: number): string {
  return `/api/images/public/${size}/articles/spod/mockups/${filename}`
}

/** The size chart of a SPOD article. Only SPOD articles have one, so it needs no type map. */
export function sizeChartImageUrl(filename: string, size: number): string {
  return `/api/images/public/${size}/articles/spod/size-charts/${filename}`
}
