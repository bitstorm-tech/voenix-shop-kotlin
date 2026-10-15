import type { Kysely, Selectable, Transaction } from 'kysely'
import type { CalculatedPrice, PriceCatalog } from '@/modules/pricing'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { conflict, notFound, ok } from '@/platform/operation'
import { printAspectRatioOf, type PrintAspectRatio } from '../catalog'
import {
  closePositionGap,
  lockArticleType,
  lockCategoryReference,
  moveInSequence,
  namesOf,
  nextPosition,
  unreferencedFilenames,
} from '../persistence'
import { writePrice } from '../price'
import type { ArticleMugsTable } from '../schema'
import type { MugArticleInput, MugVariantInput } from './service'

type Trx = Transaction<Tables>

/**
 * The physical description of a mug, one value in both directions: all nine fields or none (the
 * table declares them all-or-none).
 */
export interface MugDetails {
  heightMm: number
  diameterMm: number
  printTemplateWidthMm: number
  printTemplateHeightMm: number
  fillingQuantity: string | null
  dishwasherSafe: boolean
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
  documentFormatMarginBottomMm: number | null
}

/** One stored variant; variants come back with the default first, then by name, then by id. */
export interface MugVariant {
  id: number
  name: string
  insideColorCode: string
  outsideColorCode: string
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

/** The admin representation of a mug: no `priceId`, no `articleType`, the price embedded. */
export interface MugArticle {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
  supplierArticleName: string | null
  supplierArticleNumber: string | null
  printAspectRatio: PrintAspectRatio
  mugDetails: MugDetails | null
  mugVariants: MugVariant[]
  price: CalculatedPrice | null
}

/**
 * One row of the admin list: what a mug references spelled out, without descriptions, details,
 * variants, or price. The picture is the default variant's, or the oldest variant's that has one.
 */
export interface MugArticleListItem {
  id: number
  position: number
  name: string
  active: boolean
  categoryId: number | null
  categoryName: string | null
  subcategoryId: number | null
  subcategoryName: string | null
  supplierId: number | null
  supplierName: string | null
  variantCount: number
  exampleImageFilename: string | null
}

/** A stored mug with its price reference next to it; the service resolves the price. */
export interface StoredMug {
  article: MugArticle
  priceId: number | null
}

export type MugRepository = ReturnType<typeof createMugRepository>

/**
 * Reads and writes mugs, their variants, and the price row a mug owns. Three locks order every
 * write, always in this order: the `MUG` type anchor (every write that decides a position), the
 * referenced category row, then the mug row (update, delete). Under the category lock the supplier
 * is the only reference an article statement can still fail on, which is what makes `23503`
 * unambiguous there. The price is written inside the transaction but outside that mapping: a VAT
 * deleted since `prepare` raises `23503` too and must stay an unexpected failure.
 */
export function createMugRepository(
  db: Kysely<Tables>,
  prices: Pick<PriceCatalog, 'storeInTransaction' | 'replaceInTransaction' | 'deleteInTransaction'>,
) {
  return {
    find: (id: number) => read(db, (trx) => findMug(trx, id)),

    list: () => read(db, listMugs),

    /** Appends a mug behind the last one: price, identity, mug, variant identities, variants. */
    insert: (input: MugArticleInput, price: CalculatedPrice | null) =>
      write(db, async (trx) => {
        await lockArticleType(trx, 'MUG')
        const failure = await lockCategoryReference(trx, input.categoryId, input.subcategoryId)
        if (failure !== null) return failure
        if (input.active && price === null) return 'priceRequired'
        const position = await nextPosition(trx, 'article_mugs')
        const priceId = price === null ? null : await prices.storeInTransaction(trx, price)
        return executePostgresWrite({ foreignKeyViolation: 'supplierNotFound' }, async () => {
          const { id } = await trx
            .insertInto('article_identities')
            .values({ article_type: 'MUG' })
            .returning('id')
            .executeTakeFirstOrThrow()
          await trx
            .insertInto('article_mugs')
            .values({ id, position, ...mugRow(input), price_id: priceId })
            .execute()
          for (const variant of input.mugVariants) await insertVariant(trx, id, variant)
          return { mug: await requireMug(trx, id), obsoleteFilenames: [] }
        })
      }),

    /**
     * Replaces every stored value except the position; an omitted price keeps the stored row and
     * a submitted one is written over it. The variant array is a diff (see `applyVariants`).
     */
    update: (id: number, input: MugArticleInput, price: CalculatedPrice | null) =>
      write(db, async (trx) => {
        if ((await findMug(trx, id)) === undefined) return 'notFound'
        const failure = await lockCategoryReference(trx, input.categoryId, input.subcategoryId)
        if (failure !== null) return failure
        const stored = await findMug(trx, id, true)
        if (stored === undefined) return 'notFound'
        const storedVariants = new Map(
          stored.article.mugVariants.map((variant) => [variant.id, variant]),
        )
        if (
          input.mugVariants.some(
            (variant) => variant.id !== null && !storedVariants.has(variant.id),
          )
        ) {
          return 'unknownVariant'
        }
        if (input.active && price === null && stored.priceId === null) return 'priceRequired'
        const priceId = await writePrice(trx, prices, stored.priceId, price)
        return executePostgresWrite({ foreignKeyViolation: 'supplierNotFound' }, async () => {
          await trx
            .updateTable('article_mugs')
            .set({ ...mugRow(input), price_id: priceId })
            .where('id', '=', id)
            .execute()
          const obsoleteFilenames = await applyVariants(trx, id, input.mugVariants, storedVariants)
          return { mug: await requireMug(trx, id), obsoleteFilenames }
        })
      }),

    /**
     * Deletes the identity row (mug, variant identities, and variants cascade), then the price row
     * the mug referenced with `ON DELETE RESTRICT`, then closes the gap. Answers the example images
     * no remaining variant names.
     */
    delete: (id: number) =>
      write(db, async (trx) => {
        await lockArticleType(trx, 'MUG')
        const stored = await findMug(trx, id, true)
        if (stored === undefined) return notFound
        await trx.deleteFrom('article_identities').where('id', '=', id).execute()
        if (stored.priceId !== null) await prices.deleteInTransaction(trx, stored.priceId)
        await closePositionGap(trx, 'article_mugs', stored.article.position)
        return ok(
          await unreferencedFilenames(
            trx,
            'article_mug_variants',
            'example_image_filename',
            stored.article.mugVariants.flatMap((variant) => variant.exampleImageFilename ?? []),
          ),
        )
      }),

    /** Moves one mug to the place of another under the `MUG` anchor; see `moveInSequence`. */
    reorder: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          await lockArticleType(trx, 'MUG')
          return moveInSequence(trx, 'article_mugs', await listMugs(trx), sourceId, targetId)
        }),
      ),
  }
}

/** The details of a stored mug row, or `null` when it has none (`height_mm` stands for all). */
export function mugDetailsOf(row: Selectable<ArticleMugsTable>): MugDetails | null {
  if (
    row.height_mm === null ||
    row.diameter_mm === null ||
    row.print_template_width_mm === null ||
    row.print_template_height_mm === null ||
    row.dishwasher_safe === null
  ) {
    return null
  }
  return {
    heightMm: row.height_mm,
    diameterMm: row.diameter_mm,
    printTemplateWidthMm: row.print_template_width_mm,
    printTemplateHeightMm: row.print_template_height_mm,
    fillingQuantity: row.filling_quantity,
    dishwasherSafe: row.dishwasher_safe,
    documentFormatWidthMm: row.document_format_width_mm,
    documentFormatHeightMm: row.document_format_height_mm,
    documentFormatMarginBottomMm: row.document_format_margin_bottom_mm,
  }
}

/** Every column a mug write sets from its input. */
function mugRow(input: MugArticleInput) {
  const details = input.mugDetails
  return {
    name: input.name,
    description_short: input.descriptionShort,
    description_long: input.descriptionLong,
    active: input.active,
    category_id: input.categoryId,
    subcategory_id: input.subcategoryId,
    supplier_id: input.supplierId,
    supplier_article_name: input.supplierArticleName,
    supplier_article_number: input.supplierArticleNumber,
    print_aspect_ratio: input.printAspectRatio,
    height_mm: details?.heightMm ?? null,
    diameter_mm: details?.diameterMm ?? null,
    print_template_width_mm: details?.printTemplateWidthMm ?? null,
    print_template_height_mm: details?.printTemplateHeightMm ?? null,
    filling_quantity: details?.fillingQuantity ?? null,
    dishwasher_safe: details?.dishwasherSafe ?? null,
    document_format_width_mm: details?.documentFormatWidthMm ?? null,
    document_format_height_mm: details?.documentFormatHeightMm ?? null,
    document_format_margin_bottom_mm: details?.documentFormatMarginBottomMm ?? null,
  }
}

const variantRow = (variant: MugVariantInput) => ({
  name: variant.name,
  inside_color_code: variant.insideColorCode,
  outside_color_code: variant.outsideColorCode,
  is_default: variant.isDefault,
  active: variant.active,
  example_image_filename: variant.exampleImageFilename,
})

async function insertVariant(trx: Trx, articleId: number, variant: MugVariantInput) {
  const { id } = await trx
    .insertInto('article_variant_identities')
    .values({ article_id: articleId, article_type: 'MUG' })
    .returning('id')
    .executeTakeFirstOrThrow()
  await trx
    .insertInto('article_mug_variants')
    .values({ id, article_id: articleId, ...variantRow(variant) })
    .execute()
}

/**
 * Applies the submitted variant array: removals first, then every remaining variant loses its
 * default flag, then the submitted entries are written, so the partial unique index on the default
 * holds at every step (a default swap would otherwise collide halfway). Answers the example images
 * no variant row names once every statement ran.
 */
async function applyVariants(
  trx: Trx,
  articleId: number,
  submitted: readonly MugVariantInput[],
  stored: Map<number, MugVariant>,
): Promise<string[]> {
  const keptIds = new Set(submitted.flatMap((variant) => variant.id ?? []))
  const removed = [...stored.values()].filter((variant) => !keptIds.has(variant.id))
  if (removed.length > 0) {
    await trx
      .deleteFrom('article_variant_identities')
      .where(
        'id',
        'in',
        removed.map((variant) => variant.id),
      )
      .execute()
  }
  if (keptIds.size > 0) {
    await trx
      .updateTable('article_mug_variants')
      .set({ is_default: false })
      .where('article_id', '=', articleId)
      .execute()
  }
  for (const variant of submitted) {
    if (variant.id === null) {
      await insertVariant(trx, articleId, variant)
    } else {
      await trx
        .updateTable('article_mug_variants')
        .set(variantRow(variant))
        .where('id', '=', variant.id)
        .execute()
    }
  }
  const replaced = submitted.flatMap((variant) => {
    const previous =
      variant.id === null ? null : (stored.get(variant.id)?.exampleImageFilename ?? null)
    return previous !== null && previous !== variant.exampleImageFilename ? [previous] : []
  })
  return unreferencedFilenames(trx, 'article_mug_variants', 'example_image_filename', [
    ...removed.flatMap((variant) => variant.exampleImageFilename ?? []),
    ...replaced,
  ])
}

/**
 * The mugs in display order with the labels their references need, in four queries whatever the
 * catalog holds; the supplier name is filled in by the service from one batched lookup.
 */
async function listMugs(trx: Trx): Promise<MugArticleListItem[]> {
  const mugs = await trx
    .selectFrom('article_mugs')
    .select(['id', 'position', 'name', 'active', 'category_id', 'subcategory_id', 'supplier_id'])
    .orderBy('position')
    .orderBy('id')
    .execute()
  if (mugs.length === 0) return []
  // The default first and then the oldest, so the first row with an image is the list's picture.
  const variants = await trx
    .selectFrom('article_mug_variants')
    .select(['article_id', 'example_image_filename'])
    .where(
      'article_id',
      'in',
      mugs.map((mug) => mug.id),
    )
    .orderBy('is_default', 'desc')
    .orderBy('id')
    .execute()
  const categoryNames = await namesOf(
    trx,
    'article_categories',
    mugs.flatMap((mug) => mug.category_id ?? []),
  )
  const subcategoryNames = await namesOf(
    trx,
    'article_subcategories',
    mugs.flatMap((mug) => mug.subcategory_id ?? []),
  )
  return mugs.map((mug) => {
    const own = variants.filter((variant) => variant.article_id === mug.id)
    return {
      id: mug.id,
      position: mug.position,
      name: mug.name,
      active: mug.active,
      categoryId: mug.category_id,
      categoryName: mug.category_id === null ? null : (categoryNames.get(mug.category_id) ?? null),
      subcategoryId: mug.subcategory_id,
      subcategoryName:
        mug.subcategory_id === null ? null : (subcategoryNames.get(mug.subcategory_id) ?? null),
      supplierId: mug.supplier_id,
      supplierName: null,
      variantCount: own.length,
      exampleImageFilename:
        own.find((variant) => variant.example_image_filename !== null)?.example_image_filename ??
        null,
    }
  })
}

/** The mug `id` in full, its row locked for this transaction when `forUpdate` is set. */
async function findMug(trx: Trx, id: number, forUpdate = false): Promise<StoredMug | undefined> {
  const query = trx.selectFrom('article_mugs').selectAll().where('id', '=', id)
  const row = await (forUpdate ? query.forUpdate() : query).executeTakeFirst()
  if (row === undefined) return undefined
  const variants = await trx
    .selectFrom('article_mug_variants')
    .selectAll()
    .where('article_id', '=', id)
    .orderBy('is_default', 'desc')
    .orderBy('name')
    .orderBy('id')
    .execute()
  return {
    priceId: row.price_id,
    article: {
      id: row.id,
      position: row.position,
      name: row.name,
      descriptionShort: row.description_short,
      descriptionLong: row.description_long,
      active: row.active,
      categoryId: row.category_id,
      subcategoryId: row.subcategory_id,
      supplierId: row.supplier_id,
      supplierArticleName: row.supplier_article_name,
      supplierArticleNumber: row.supplier_article_number,
      printAspectRatio: printAspectRatioOf(row.print_aspect_ratio),
      mugDetails: mugDetailsOf(row),
      mugVariants: variants.map((variant) => ({
        id: variant.id,
        name: variant.name,
        insideColorCode: variant.inside_color_code,
        outsideColorCode: variant.outside_color_code,
        isDefault: variant.is_default,
        active: variant.active,
        exampleImageFilename: variant.example_image_filename,
      })),
      price: null,
    },
  }
}

async function requireMug(trx: Trx, id: number): Promise<StoredMug> {
  const stored = await findMug(trx, id)
  if (stored === undefined) throw new Error(`Mug ${String(id)} vanished inside its write`)
  return stored
}
