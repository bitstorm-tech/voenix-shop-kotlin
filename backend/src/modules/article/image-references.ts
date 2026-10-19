import type { Kysely } from 'kysely'
import type { PublicImageReferences } from '@/modules/image'
import { read, type Tables } from '@/platform/db/database'
import './schema'

/** The public folders the module's pictures live in, one per kind (and per writer rule). */
export const FOLDERS = {
  subcategoryExamples: 'articles/subcategory-example-images',
  mugVariantExamples: 'articles/mugs/variant-example-images',
  spodListingImages: 'articles/spod/listing-images',
  spodMockups: 'articles/spod/mockups',
  spodSizeCharts: 'articles/spod/size-charts',
} as const

/**
 * Every picture a row of this module refers to, as a path below the public root, for the image
 * module's orphan list: subcategory and mug-variant example images, SPOD listing images and
 * mockups, and SPOD size charts.
 */
export function createImageReferences(db: Kysely<Tables>): PublicImageReferences {
  return () =>
    read(db, async (trx) => {
      const exampleImages = (table: 'article_subcategories' | 'article_mug_variants') =>
        trx
          .selectFrom(table)
          .select('example_image_filename as filename')
          .where('example_image_filename', 'is not', null)
          .execute()
      const subcategories = await exampleImages('article_subcategories')
      const mugVariants = await exampleImages('article_mug_variants')
      const spodImages = await trx
        .selectFrom('article_spod_images')
        .select(['filename', 'role'])
        .execute()
      const sizeCharts = await trx
        .selectFrom('article_spod_articles')
        .select('size_chart_image_filename as filename')
        .where('size_chart_image_filename', 'is not', null)
        .execute()
      const inFolder = (folder: string, rows: { filename: string | null }[]) =>
        rows.map((row) => `${folder}/${row.filename ?? ''}`)
      return [
        ...inFolder(FOLDERS.subcategoryExamples, subcategories),
        ...inFolder(FOLDERS.mugVariantExamples, mugVariants),
        ...inFolder(
          FOLDERS.spodMockups,
          spodImages.filter((row) => row.role === 'MOCKUP'),
        ),
        ...inFolder(
          FOLDERS.spodListingImages,
          spodImages.filter((row) => row.role === 'LISTING'),
        ),
        ...inFolder(FOLDERS.spodSizeCharts, sizeCharts),
      ]
    })
}
