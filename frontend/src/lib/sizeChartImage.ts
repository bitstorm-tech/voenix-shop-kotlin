/**
 * The size chart of a t-shirt is a plain public image, uploaded by the admin into its own folder.
 * Only shirts have one, so the folder needs no article-type map.
 */
const TSHIRT_SIZE_CHART_FOLDER = 'articles/tshirts/size-charts'

export function sizeChartImageUrl(filename: string, size: number): string {
  return `/api/images/public/${size}/${TSHIRT_SIZE_CHART_FOLDER}/${filename}`
}
