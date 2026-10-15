import { sql, type Transaction } from 'kysely'
import type { Tables } from '@/platform/db/database'
import type { ArticleType } from './catalog'
import './schema'

/**
 * The persistence rules every article repository shares: the ordering locks, the dense position
 * sequences, the category reference an article write locks, and the question which picture files no
 * row refers to any more. Everything here runs on the caller's transaction and opens none.
 */

type Trx = Transaction<Tables>

/** The four tables whose `position` is a dense `1..n` sequence with a deferred unique rule. */
type OrderedTable =
  | 'article_categories'
  | 'article_subcategories'
  | 'article_mugs'
  | 'article_spod_articles'

/**
 * Locks the single row of `article_category_ordering`. Every writer of a category position (create,
 * delete compaction, reorder) queues on it first and only then reads the positions it decides from:
 * a preliminary `max(position)` alone would let two creates write the same place.
 */
export async function lockCategoryOrdering(trx: Trx): Promise<void> {
  const anchor = await trx
    .selectFrom('article_category_ordering')
    .select('id')
    .forUpdate()
    .executeTakeFirst()
  if (anchor === undefined) throw new Error('The article category ordering anchor row is missing')
}

/**
 * Locks the category rows `ids` one statement at a time in ascending id order and answers whether
 * every one exists. The row is the anchor of its subcategory positions, and while it is held the
 * category cannot disappear. The ascending order is what keeps every writer of more than one
 * category row (subcategory and article writes, the category reorder and delete) free of deadlocks.
 */
export async function lockCategories(trx: Trx, ids: Iterable<number>): Promise<boolean> {
  for (const id of [...new Set(ids)].sort((a, b) => a - b)) {
    const row = await trx
      .selectFrom('article_categories')
      .select('id')
      .where('id', '=', id)
      .forUpdate()
      .executeTakeFirst()
    if (row === undefined) return false
  }
  return true
}

/** Locks the `article_types` row of `type`, the anchor of that type's article positions. */
export async function lockArticleType(trx: Trx, type: ArticleType): Promise<void> {
  const anchor = await trx
    .selectFrom('article_types')
    .select('article_type')
    .where('article_type', '=', type)
    .forUpdate()
    .executeTakeFirst()
  if (anchor === undefined)
    throw new Error(`The ordering anchor of article type ${type} is missing`)
}

/**
 * The place behind the last one of `table`, or of one category's subcategories. Only meaningful
 * while the caller holds the sequence's ordering lock.
 */
export async function nextPosition(
  trx: Trx,
  table: OrderedTable,
  categoryId?: number,
): Promise<number> {
  const scope = categoryId === undefined ? sql`` : sql` WHERE category_id = ${categoryId}`
  const { rows } = await sql<{ maximum: number | null }>`
    SELECT max(position) AS maximum FROM ${sql.table(table)}${scope}`.execute(trx)
  return (rows[0]?.maximum ?? 0) + 1
}

/** Whether the stored order is `1..n` without a gap. A reorder refuses to repair one silently. */
export const isDense = (rows: readonly { position: number }[]): boolean =>
  rows.every((row, index) => row.position === index + 1)

/**
 * Numbers `ordered` from 1, writes only the rows whose place changed, and answers every row with
 * the place it now has. The duplicates that exist while it runs are allowed because the unique rule
 * on `position` is deferred to COMMIT. The caller holds the sequence's ordering lock.
 */
export async function rewritePositions<T extends { id: number; position: number }>(
  trx: Trx,
  table: OrderedTable,
  ordered: readonly T[],
): Promise<T[]> {
  const rewritten: T[] = []
  for (const [index, row] of ordered.entries()) {
    const position = index + 1
    if (row.position !== position) {
      await sql`UPDATE ${sql.table(table)} SET position = ${position} WHERE id = ${row.id}`.execute(
        trx,
      )
    }
    rewritten.push({ ...row, position })
  }
  return rewritten
}

/**
 * The reorder every ordered table shares: move `sourceId` to the place of `targetId` in `stored`
 * and write the new order. An id that is not in the order answers `notFound`; a stored sequence
 * with a gap answers `conflict` without writing anything. `beforeWrite` runs between the checks and
 * the write (the category reorder locks its rows there). The caller wraps its whole transaction in
 * the `23505` mapping, because a position another writer changed fails only at COMMIT.
 */
export async function moveInSequence<T extends { id: number; position: number }>(
  trx: Trx,
  table: OrderedTable,
  stored: readonly T[],
  sourceId: number,
  targetId: number,
  beforeWrite: () => Promise<void> = () => Promise.resolve(),
): Promise<T[] | 'notFound' | 'conflict'> {
  const sourceIndex = stored.findIndex((row) => row.id === sourceId)
  const targetIndex = stored.findIndex((row) => row.id === targetId)
  if (sourceIndex < 0 || targetIndex < 0) return 'notFound'
  if (!isDense(stored)) return 'conflict'
  await beforeWrite()
  const moved = [...stored]
  moved.splice(targetIndex, 0, ...moved.splice(sourceIndex, 1))
  return rewritePositions(trx, table, moved)
}

/** Moves every article behind `position` one place forward, closing the gap a delete left. */
export async function closePositionGap(
  trx: Trx,
  table: 'article_mugs' | 'article_spod_articles',
  position: number,
): Promise<void> {
  await sql`UPDATE ${sql.table(table)} SET position = position - 1 WHERE position > ${position}`.execute(
    trx,
  )
}

/**
 * Locks the category an article write references and reports the first reference the client got
 * wrong. While the category row is held it cannot disappear and no subcategory can leave it, so the
 * subcategory is looked up inside it: exactly the pair the composite foreign key requires.
 */
export async function lockCategoryReference(
  trx: Trx,
  categoryId: number | null,
  subcategoryId: number | null,
): Promise<'categoryNotFound' | 'subcategoryNotFound' | null> {
  if (categoryId === null) return null
  if (!(await lockCategories(trx, [categoryId]))) return 'categoryNotFound'
  if (subcategoryId === null) return null
  const subcategory = await trx
    .selectFrom('article_subcategories')
    .select('id')
    .where('id', '=', subcategoryId)
    .where('category_id', '=', categoryId)
    .executeTakeFirst()
  return subcategory === undefined ? 'subcategoryNotFound' : null
}

/** The names of the category or subcategory rows `ids`, in one query, for an admin list. */
export async function namesOf(
  trx: Trx,
  table: 'article_categories' | 'article_subcategories',
  ids: Iterable<number>,
): Promise<Map<number, string>> {
  const wanted = [...new Set(ids)]
  if (wanted.length === 0) return new Map()
  const rows = await trx
    .selectFrom(table)
    .select(['id', 'name'])
    .where('id', 'in', wanted)
    .execute()
  return new Map(rows.map((row) => [row.id, row.name]))
}

/**
 * The names among `candidates` that no row refers to through `table.column` any more. Two rows may
 * name one file, so a name one row dropped may still be another's picture; asked inside the writing
 * transaction, the answer is the state its commit publishes.
 */
export async function unreferencedFilenames(
  trx: Trx,
  table:
    | 'article_subcategories'
    | 'article_mug_variants'
    | 'article_spod_images'
    | 'article_spod_articles',
  column: 'example_image_filename' | 'filename' | 'size_chart_image_filename',
  candidates: readonly string[],
): Promise<string[]> {
  const distinct = [...new Set(candidates)]
  if (distinct.length === 0) return []
  const { rows } = await sql<{ filename: string }>`
    SELECT DISTINCT ${sql.ref(column)} AS filename FROM ${sql.table(table)}
    WHERE ${sql.ref(column)} = ANY(${distinct})`.execute(trx)
  const referenced = new Set(rows.map((row) => row.filename))
  return distinct.filter((filename) => !referenced.has(filename))
}
