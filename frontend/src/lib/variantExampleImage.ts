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
