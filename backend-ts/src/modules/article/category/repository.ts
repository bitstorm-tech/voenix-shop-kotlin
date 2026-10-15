import type { Kysely, Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import {
  lockCategories,
  lockCategoryOrdering,
  moveInSequence,
  nextPosition,
  rewritePositions,
  unreferencedFilenames,
} from '../persistence'
import type { ArticleCategoryInput, ArticleSubcategoryInput } from './service'
import '../schema'

type Trx = Transaction<Tables>

/** The admin representation of a category. `position` is decided by the module, never submitted. */
export interface ArticleCategory {
  id: number
  name: string
  description: string | null
  position: number
  active: boolean
}

/** The admin representation of a subcategory; `position` counts inside its category. */
export interface ArticleSubcategory {
  id: number
  categoryId: number
  name: string
  description: string | null
  exampleImageFilename: string | null
  position: number
  active: boolean
}

const categoryColumns = ['id', 'name', 'description', 'position', 'active'] as const
const subcategoryColumns = [
  'id',
  'category_id as categoryId',
  'name',
  'description',
  'example_image_filename as exampleImageFilename',
  'position',
  'active',
] as const

/** How many times a subcategory write starts over after its subcategory moved category under it. */
const MAXIMUM_LOCK_ATTEMPTS = 3

export type CategoryRepository = ReturnType<typeof createCategoryRepository>

/**
 * Categories and subcategories. Where the `23505` mapping sits tells the two unique rules apart: the
 * case-insensitive name index is checked while a statement runs, so create and update map it
 * *inside* their transaction; the position rule is deferred to COMMIT, so only a reorder, which wraps
 * its whole transaction, maps it. A create's commit-time `23505` stays an unexpected failure.
 *
 * Every category write that decides positions takes the category ordering anchor and then the
 * category rows in ascending id order, the order the subcategory and article writes use too.
 */
export function createCategoryRepository(db: Kysely<Tables>) {
  /**
   * Runs a subcategory write in a transaction of its own and starts over when it answers `null`:
   * the subcategory moved to another category between its read and its category lock. Rolling back
   * instead of taking one more lock keeps the ascending lock order intact.
   */
  const withCategoryLocks = async <T>(operation: (trx: Trx) => Promise<T | null>): Promise<T> => {
    for (let attempt = 0; attempt < MAXIMUM_LOCK_ATTEMPTS; attempt++) {
      const result = await write(db, operation)
      if (result !== null) return result
    }
    throw new Error('An article subcategory kept moving between categories while it was written')
  }

  return {
    listCategories: () => read(db, orderedCategories),

    findCategory: (id: number) =>
      read(db, (trx) =>
        trx
          .selectFrom('article_categories')
          .select(categoryColumns)
          .where('id', '=', id)
          .executeTakeFirst(),
      ),

    /** Appends a category behind the last one, under the ordering anchor. */
    insertCategory: (input: ArticleCategoryInput) =>
      write(db, async (trx) => {
        await lockCategoryOrdering(trx)
        const position = await nextPosition(trx, 'article_categories')
        return executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
          trx
            .insertInto('article_categories')
            .values({ ...input, position })
            .returning(categoryColumns)
            .executeTakeFirstOrThrow(),
        )
      }),

    /** Replaces name, description, and activation; the position stays, so no lock is needed. */
    updateCategory: (id: number, input: ArticleCategoryInput) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, async () => {
        const updated = await write(db, (trx) =>
          trx
            .updateTable('article_categories')
            .set(input)
            .where('id', '=', id)
            .returning(categoryColumns)
            .executeTakeFirst(),
        )
        return updated ?? 'notFound'
      }),

    /**
     * Deletes a category and closes its gap. A category subcategories or articles reference fails
     * the delete statement with `23001`, which leaves the transaction before the compaction runs.
     */
    deleteCategory: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, () =>
        write(db, async (trx) => {
          await lockCategoryOrdering(trx)
          await lockEveryCategory(trx, await categoryIds(trx))
          const deleted = await trx
            .deleteFrom('article_categories')
            .where('id', '=', id)
            .executeTakeFirst()
          if (deleted.numDeletedRows === 0n) return 'notFound'
          await rewritePositions(trx, 'article_categories', await orderedCategories(trx))
          return 'deleted'
        }),
      ),

    /**
     * Moves one category and answers the complete new order. The rows are locked in id order after
     * the read and before the rewrite: what the transaction decides from is the order it read, and
     * a position a writer outside the anchor changed meanwhile fails the deferred rule at COMMIT.
     */
    reorderCategories: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          await lockCategoryOrdering(trx)
          const stored = await orderedCategories(trx)
          return moveInSequence(trx, 'article_categories', stored, sourceId, targetId, () =>
            lockEveryCategory(
              trx,
              stored.map((category) => category.id),
            ),
          )
        }),
      ),

    /** Every subcategory, by its category's display order and then by its own. */
    listSubcategories: () =>
      read(db, (trx) =>
        trx
          .selectFrom('article_subcategories as s')
          .innerJoin('article_categories as c', 'c.id', 's.category_id')
          .select([
            's.id',
            's.category_id as categoryId',
            's.name',
            's.description',
            's.example_image_filename as exampleImageFilename',
            's.position',
            's.active',
          ])
          .orderBy('c.position')
          .orderBy('c.id')
          .orderBy('s.position')
          .orderBy('s.id')
          .execute(),
      ),

    findSubcategory: (id: number) => read(db, (trx) => findSubcategory(trx, id)),

    /** Appends a subcategory behind the last one of its category, under that category's lock. */
    insertSubcategory: (input: ArticleSubcategoryInput) =>
      write(db, async (trx) => {
        if (!(await lockCategories(trx, [input.categoryId]))) return 'categoryNotFound'
        const position = await nextPosition(trx, 'article_subcategories', input.categoryId)
        return executePostgresWrite({ uniqueViolation: 'conflict' }, async () => ({
          subcategory: await trx
            .insertInto('article_subcategories')
            .values(subcategoryRow(input, position))
            .returning(subcategoryColumns)
            .executeTakeFirstOrThrow(),
          obsoleteFilename: null,
        }))
      }),

    /**
     * Replaces every value, the category included. A category change appends in the new category
     * and compacts the one it left; an article that uses the subcategory references it together
     * with the category, so its composite foreign key refuses the move (`23503`, unambiguous because
     * the target category is locked).
     */
    updateSubcategory: (id: number, input: ArticleSubcategoryInput) =>
      withCategoryLocks(async (trx) => {
        const observed = await findSubcategory(trx, id)
        if (observed === undefined) return 'notFound'
        if (!(await lockCategories(trx, [observed.categoryId, input.categoryId]))) {
          return 'categoryNotFound'
        }
        const locked = await findSubcategory(trx, id)
        if (locked?.categoryId !== observed.categoryId) return null
        const moving = locked.categoryId !== input.categoryId
        const position = moving
          ? await nextPosition(trx, 'article_subcategories', input.categoryId)
          : locked.position
        return executePostgresWrite(
          { uniqueViolation: 'conflict', foreignKeyViolation: 'inUse' },
          async () => {
            const subcategory = await trx
              .updateTable('article_subcategories')
              .set(subcategoryRow(input, position))
              .where('id', '=', id)
              .returning(subcategoryColumns)
              .executeTakeFirstOrThrow()
            if (moving) {
              await rewritePositions(
                trx,
                'article_subcategories',
                await categorySubcategories(trx, locked.categoryId),
              )
            }
            const previous = locked.exampleImageFilename
            const [obsoleteFilename = null] =
              previous === null || previous === subcategory.exampleImageFilename
                ? []
                : await unreferencedFilenames(
                    trx,
                    'article_subcategories',
                    'example_image_filename',
                    [previous],
                  )
            return { subcategory, obsoleteFilename }
          },
        )
      }),

    /**
     * Deletes a subcategory and closes its gap. One an article uses fails the delete statement with
     * `23001`. Answers its example image when no other subcategory still names it.
     */
    deleteSubcategory: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, () =>
        withCategoryLocks(async (trx) => {
          const observed = await findSubcategory(trx, id)
          if (observed === undefined) return 'notFound'
          await lockEveryCategory(trx, [observed.categoryId])
          const locked = await findSubcategory(trx, id)
          if (locked?.categoryId !== observed.categoryId) return null
          await trx.deleteFrom('article_subcategories').where('id', '=', id).execute()
          await rewritePositions(
            trx,
            'article_subcategories',
            await categorySubcategories(trx, locked.categoryId),
          )
          const filename = locked.exampleImageFilename
          const [obsoleteFilename = null] =
            filename === null
              ? []
              : await unreferencedFilenames(
                  trx,
                  'article_subcategories',
                  'example_image_filename',
                  [filename],
                )
          return { obsoleteFilename }
        }),
      ),

    /**
     * Moves one subcategory to the place of a sibling and answers its category's new order. A target
     * from another category is outside that order, so it is as unknown as a missing id.
     */
    reorderSubcategories: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        withCategoryLocks(async (trx) => {
          const observed = await findSubcategory(trx, sourceId)
          if (observed === undefined) return 'notFound'
          await lockEveryCategory(trx, [observed.categoryId])
          const locked = await findSubcategory(trx, sourceId)
          if (locked?.categoryId !== observed.categoryId) return null
          return moveInSequence(
            trx,
            'article_subcategories',
            await categorySubcategories(trx, locked.categoryId),
            sourceId,
            targetId,
          )
        }),
      ),
  }
}

/** Locks rows that must exist: they were read under a lock that keeps them from disappearing. */
async function lockEveryCategory(trx: Trx, ids: readonly number[]): Promise<void> {
  if (!(await lockCategories(trx, ids))) {
    throw new Error('An article category disappeared while its writer held the ordering lock')
  }
}

async function categoryIds(trx: Trx): Promise<number[]> {
  const rows = await trx.selectFrom('article_categories').select('id').execute()
  return rows.map((row) => row.id)
}

const orderedCategories = (trx: Trx): Promise<ArticleCategory[]> =>
  trx
    .selectFrom('article_categories')
    .select(categoryColumns)
    .orderBy('position')
    .orderBy('id')
    .execute()

const findSubcategory = (trx: Trx, id: number): Promise<ArticleSubcategory | undefined> =>
  trx
    .selectFrom('article_subcategories')
    .select(subcategoryColumns)
    .where('id', '=', id)
    .executeTakeFirst()

const categorySubcategories = (trx: Trx, categoryId: number): Promise<ArticleSubcategory[]> =>
  trx
    .selectFrom('article_subcategories')
    .select(subcategoryColumns)
    .where('category_id', '=', categoryId)
    .orderBy('position')
    .orderBy('id')
    .execute()

const subcategoryRow = (input: ArticleSubcategoryInput, position: number) => ({
  category_id: input.categoryId,
  name: input.name,
  description: input.description,
  example_image_filename: input.exampleImageFilename,
  active: input.active,
  position,
})
