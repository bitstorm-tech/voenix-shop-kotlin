import type { ShopArticleType } from '@/stores/shop/catalog'

/**
 * Each article type uploads its variant photos into its own folder, so the URL of a photo cannot
 * be built from the filename alone - the type names the folder.
 */
const VARIANT_EXAMPLE_IMAGE_FOLDERS: Record<ShopArticleType, string> = {
  MUG: 'articles/mugs/variant-example-images',
  TSHIRT: 'articles/tshirts/variant-example-images',
}

export function variantExampleImageUrl(
  articleType: ShopArticleType,
  filename: string,
  size: number,
): string {
  return `/api/images/public/${size}/${VARIANT_EXAMPLE_IMAGE_FOLDERS[articleType]}/${filename}`
}

/**
 * The size chart of a t-shirt is a plain public image, uploaded by the admin into its own folder.
 * Only shirts have one, so the folder needs no article-type map.
 */
const TSHIRT_SIZE_CHART_FOLDER = 'articles/tshirts/size-charts'

export function sizeChartImageUrl(filename: string, size: number): string {
  return `/api/images/public/${size}/${TSHIRT_SIZE_CHART_FOLDER}/${filename}`
}
