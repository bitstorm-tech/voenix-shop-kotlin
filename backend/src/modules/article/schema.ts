import type { ColumnType, Generated } from 'kysely'

/**
 * The article tables (V13, V19, V26–V29). Every rule lives in the migrations; these interfaces only
 * type the columns. A `numeric` column answers a string, which the repository parses once.
 */

/** The known article types (V13, `TSHIRT` renamed to `SPOD` by V29): the identity target and the per-type ordering anchor. */
export interface ArticleTypesTable {
  article_type: string
}

/** The single-row ordering anchor of the global category positions (V13). The row is the lock. */
export interface ArticleCategoryOrderingTable {
  id: number
}

/** The registry that mints every article id, whatever its type. It never gains another column. */
export interface ArticleIdentitiesTable {
  id: Generated<number>
  article_type: string
}

/** The registry that mints every variant id; its composite key ties a variant to its article. */
export interface ArticleVariantIdentitiesTable {
  id: Generated<number>
  article_id: number
  article_type: string
}

export interface ArticleCategoriesTable {
  id: Generated<number>
  name: string
  description: string | null
  position: number
  active: boolean
}

export interface ArticleSubcategoriesTable {
  id: Generated<number>
  category_id: number
  name: string
  description: string | null
  example_image_filename: string | null
  position: number
  active: boolean
}

/** A mug: the legacy article row and its details in one table (V13), plus the print ratio (V19). */
export interface ArticleMugsTable {
  id: number
  article_type: Generated<string>
  position: number
  name: string
  description_short: string
  description_long: string
  active: boolean
  category_id: number | null
  subcategory_id: number | null
  supplier_id: number | null
  supplier_article_name: string | null
  supplier_article_number: string | null
  price_id: number | null
  height_mm: number | null
  diameter_mm: number | null
  print_template_width_mm: number | null
  print_template_height_mm: number | null
  filling_quantity: string | null
  dishwasher_safe: boolean | null
  document_format_width_mm: number | null
  document_format_height_mm: number | null
  document_format_margin_bottom_mm: number | null
  print_aspect_ratio: Generated<string>
}

export interface ArticleMugVariantsTable {
  id: number
  article_id: number
  inside_color_code: string
  outside_color_code: string
  name: string
  is_default: boolean
  active: boolean
  example_image_filename: string | null
}

/** A `numeric(5, 2)` frame percentage: read as a string, written as a number, `NULL` until measured. */
type FramePercent = ColumnType<string | null, number | null, number | null>

/** A SPOD article (V29): the synced product plus the shop-owned half. */
export interface ArticleSpodArticlesTable {
  id: number
  article_type: Generated<string>
  position: number
  name: string
  description_short: string
  description_long: string
  active: boolean
  category_id: number | null
  subcategory_id: number | null
  supplier_id: number
  price_id: number | null
  size_chart_image_filename: string | null
  print_frame_left_pct: FramePercent
  print_frame_top_pct: FramePercent
  print_frame_width_pct: FramePercent
  print_frame_height_pct: FramePercent
  spod_destination_id: number
  spod_environment: string
  spod_article_id: string
  spod_synced_at: Date
  spod_missing_since: Date | null
  spod_size_chart_url: string | null
  spod_product_type_id: number
  spod_product_type_name: string
  spod_hotspot: string | null
}

/** One colour of a SPOD article, upserted by the partner's appearance id and never deleted. */
export interface ArticleSpodAppearancesTable {
  id: Generated<number>
  article_id: number
  spod_appearance_id: number
  color_name: string
  color_hex: string
  position: number
}

/** One colour × size of a SPOD article; its id is minted by `article_variant_identities`. */
export interface ArticleSpodVariantsTable {
  id: number
  article_id: number
  appearance_id: number
  size_label: string
  spod_product_type_id: number
  spod_size_id: number
  spod_variant_id: string
  sku: string | null
  is_default: boolean
  active: boolean
}

/** The pictures of one colour: every `LISTING` image the partner lists and at most one `MOCKUP`. */
export interface ArticleSpodImagesTable {
  id: Generated<number>
  appearance_id: number
  role: 'LISTING' | 'MOCKUP'
  position: number
  spod_image_id: string | null
  filename: string
  source_url: string | null
}

declare module '@/platform/db/database' {
  interface Tables {
    article_types: ArticleTypesTable
    article_category_ordering: ArticleCategoryOrderingTable
    article_identities: ArticleIdentitiesTable
    article_variant_identities: ArticleVariantIdentitiesTable
    article_categories: ArticleCategoriesTable
    article_subcategories: ArticleSubcategoriesTable
    article_mugs: ArticleMugsTable
    article_mug_variants: ArticleMugVariantsTable
    article_spod_articles: ArticleSpodArticlesTable
    article_spod_appearances: ArticleSpodAppearancesTable
    article_spod_variants: ArticleSpodVariantsTable
    article_spod_images: ArticleSpodImagesTable
  }
}
