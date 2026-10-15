import { sql, type Kysely, type Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { lockCategories, lockSequence, moved, rewritePositions } from '../ordering'
import '../schema'

/** A category; `position` is response-only, decided by create, delete, and reorder. */
export interface PromptCategory {
  id: number
  name: string
  position: number
  active: boolean
}

/** A subcategory; `position` counts inside its category. */
export interface PromptSubcategory {
  id: number
  categoryId: number
  name: string
  description: string | null
  position: number
  active: boolean
}

export type CategoryValues = Pick<PromptCategory, 'name' | 'active'>
export type SubcategoryValues = Omit<PromptSubcategory, 'id' | 'position'>

export type PromptCategoryRepository = ReturnType<typeof createPromptCategoryRepository>

const categoryColumns = ['id', 'name', 'position', 'active'] as const
const subcategoryColumns = [
  'id',
  'category_id as categoryId',
  'name',
  'description',
  'position',
  'active',
] as const

const categoryById = (trx: Transaction<Tables>, id: number) =>
  trx.selectFrom('prompt_categories').select(categoryColumns).where('id', '=', id)

const orderedCategories = (trx: Transaction<Tables>) =>
  trx
    .selectFrom('prompt_categories')
    .select(categoryColumns)
    .orderBy('position')
    .orderBy('id')
    .execute()

const subcategoryById = (trx: Transaction<Tables>, id: number) =>
  trx.selectFrom('prompt_subcategories').select(subcategoryColumns).where('id', '=', id)

const subcategoriesOf = (trx: Transaction<Tables>, categoryId: number) =>
  trx
    .selectFrom('prompt_subcategories')
    .select(subcategoryColumns)
    .where('category_id', '=', categoryId)
    .orderBy('position')
    .orderBy('id')
    .execute()

const setCategoryPosition = (trx: Transaction<Tables>) => (id: number, position: number) =>
  trx.updateTable('prompt_categories').set({ position }).where('id', '=', id).execute()

const setSubcategoryPosition = (trx: Transaction<Tables>) => (id: number, position: number) =>
  trx.updateTable('prompt_subcategories').set({ position }).where('id', '=', id).execute()

async function lastSubcategoryPosition(trx: Transaction<Tables>, categoryId: number) {
  const { last } = await trx
    .selectFrom('prompt_subcategories')
    .select(sql<number>`coalesce(max(position), 0)`.as('last'))
    .where('category_id', '=', categoryId)
    .executeTakeFirstOrThrow()
  return last
}

/**
 * A subcategory write has to read the row before it knows which category rows to lock, and the
 * row can move to another category in between. An attempt that finds it moved answers `'retry'`
 * and the next one starts over in a transaction of its own; taking one more lock instead would
 * break the ascending lock order.
 */
async function withCategoryLocks<T>(attempt: () => Promise<T | 'retry'>): Promise<T> {
  for (let attempts = 0; attempts < 3; attempts++) {
    const result = await attempt()
    if (result !== 'retry') return result
  }
  throw new Error('A prompt subcategory kept moving between categories while it was written')
}

/**
 * Categories and subcategories. Category positions are one global sequence under the `CATEGORY`
 * anchor; subcategory positions count per category, and the category row is their anchor. A unique
 * violation of a create or update can only be the name, because the positions they write are
 * decided under the anchor; the reorders map the deferred position rule, checked at `COMMIT`, to
 * the conflict of a lost race.
 */
export function createPromptCategoryRepository(db: Kysely<Tables>) {
  return {
    listCategories: (): Promise<PromptCategory[]> => read(db, orderedCategories),

    findCategory: (id: number) => read(db, (trx) => categoryById(trx, id).executeTakeFirst()),

    insertCategory: (values: CategoryValues) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx): Promise<PromptCategory> => {
          await lockSequence(trx, 'CATEGORY')
          const { last } = await trx
            .selectFrom('prompt_categories')
            .select(sql<number>`coalesce(max(position), 0)`.as('last'))
            .executeTakeFirstOrThrow()
          return trx
            .insertInto('prompt_categories')
            .values({ ...values, position: last + 1 })
            .returning(categoryColumns)
            .executeTakeFirstOrThrow()
        }),
      ),

    /** Replaces name and activation; the position stays, so no anchor is needed. */
    updateCategory: (id: number, values: CategoryValues) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          const row = await trx
            .updateTable('prompt_categories')
            .set(values)
            .where('id', '=', id)
            .returning(categoryColumns)
            .executeTakeFirst()
          return row ?? 'notFound'
        }),
      ),

    /**
     * Deletes a category and closes its gap. Subcategories and prompts restrict the delete, which
     * is the one foreign key that can fail it: `'inUse'`. The compaction rewrites rows in display
     * order, so every category row is locked first, ascending by id.
     */
    deleteCategory: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, () =>
        write(db, async (trx) => {
          await lockSequence(trx, 'CATEGORY')
          const stored = await orderedCategories(trx)
          await lockCategories(
            trx,
            stored.map((category) => category.id),
          )
          const result = await trx
            .deleteFrom('prompt_categories')
            .where('id', '=', id)
            .executeTakeFirst()
          if (result.numDeletedRows === 0n) return 0
          await rewritePositions(
            stored.filter((category) => category.id !== id),
            setCategoryPosition(trx),
          )
          return 1
        }),
      ),

    /** Moves `sourceId` to the place of `targetId` and answers the complete new order. */
    reorderCategories: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          await lockSequence(trx, 'CATEGORY')
          const stored = await orderedCategories(trx)
          const order = moved(stored, sourceId, targetId)
          if (typeof order === 'string') return order
          await lockCategories(
            trx,
            stored.map((category) => category.id),
          )
          return rewritePositions(order, setCategoryPosition(trx))
        }),
      ),

    /** Every subcategory, in its category's display order and then its own. */
    listSubcategories: (): Promise<PromptSubcategory[]> =>
      read(db, (trx) =>
        trx
          .selectFrom('prompt_subcategories as subcategory')
          .innerJoin('prompt_categories as category', 'category.id', 'subcategory.category_id')
          .select([
            'subcategory.id',
            'subcategory.category_id as categoryId',
            'subcategory.name',
            'subcategory.description',
            'subcategory.position',
            'subcategory.active',
          ])
          .orderBy('category.position')
          .orderBy('category.id')
          .orderBy('subcategory.position')
          .orderBy('subcategory.id')
          .execute(),
      ),

    findSubcategory: (id: number) => read(db, (trx) => subcategoryById(trx, id).executeTakeFirst()),

    /** Appends behind the last subcategory of its category; a missing category is a lock that found no row. */
    insertSubcategory: (values: SubcategoryValues) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          if (!(await lockCategories(trx, [values.categoryId]))) return 'categoryNotFound'
          const position = (await lastSubcategoryPosition(trx, values.categoryId)) + 1
          return trx
            .insertInto('prompt_subcategories')
            .values({
              category_id: values.categoryId,
              name: values.name,
              description: values.description,
              active: values.active,
              position,
            })
            .returning(subcategoryColumns)
            .executeTakeFirstOrThrow()
        }),
      ),

    /**
     * Replaces every value, including the category. A move appends in the target category and
     * compacts the source, so both rows are locked. While they are, the only reference the update
     * can still violate is the composite key `prompts(subcategory_id, category_id)`: `'inUse'`
     * means prompts hold the subcategory in the category it wants to leave.
     */
    updateSubcategory: (id: number, values: SubcategoryValues) =>
      executePostgresWrite({ uniqueViolation: 'conflict', foreignKeyViolation: 'inUse' }, () =>
        withCategoryLocks(() =>
          write(
            db,
            async (trx): Promise<PromptSubcategory | 'notFound' | 'categoryNotFound' | 'retry'> => {
              const observed = await subcategoryById(trx, id).executeTakeFirst()
              if (observed === undefined) return 'notFound'
              if (!(await lockCategories(trx, [observed.categoryId, values.categoryId]))) {
                return 'categoryNotFound'
              }
              const locked = await subcategoryById(trx, id).executeTakeFirst()
              if (locked?.categoryId !== observed.categoryId) return 'retry'
              const moving = locked.categoryId !== values.categoryId
              const position = moving
                ? (await lastSubcategoryPosition(trx, values.categoryId)) + 1
                : locked.position
              const row = await trx
                .updateTable('prompt_subcategories')
                .set({
                  category_id: values.categoryId,
                  name: values.name,
                  description: values.description,
                  active: values.active,
                  position,
                })
                .where('id', '=', id)
                .returning(subcategoryColumns)
                .executeTakeFirstOrThrow()
              if (moving) {
                await rewritePositions(
                  await subcategoriesOf(trx, locked.categoryId),
                  setSubcategoryPosition(trx),
                )
              }
              return row
            },
          ),
        ),
      ),

    /** Deletes a subcategory and closes its gap; prompts that use it restrict the delete. */
    deleteSubcategory: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, () =>
        withCategoryLocks(() =>
          write(db, async (trx): Promise<number | 'retry'> => {
            const observed = await subcategoryById(trx, id).executeTakeFirst()
            if (observed === undefined) return 0
            await lockCategories(trx, [observed.categoryId])
            const locked = await subcategoryById(trx, id).executeTakeFirst()
            if (locked?.categoryId !== observed.categoryId) return 'retry'
            await trx.deleteFrom('prompt_subcategories').where('id', '=', id).execute()
            await rewritePositions(
              await subcategoriesOf(trx, locked.categoryId),
              setSubcategoryPosition(trx),
            )
            return 1
          }),
        ),
      ),

    /**
     * Moves `sourceId` to the place of `targetId` inside the source's category and answers that
     * category's new order. A target in another category is as unknown as a missing id.
     */
    reorderSubcategories: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        withCategoryLocks(() =>
          write(
            db,
            async (trx): Promise<PromptSubcategory[] | 'notFound' | 'conflict' | 'retry'> => {
              const observed = await subcategoryById(trx, sourceId).executeTakeFirst()
              if (observed === undefined) return 'notFound'
              await lockCategories(trx, [observed.categoryId])
              const locked = await subcategoryById(trx, sourceId).executeTakeFirst()
              if (locked?.categoryId !== observed.categoryId) return 'retry'
              const order = moved(await subcategoriesOf(trx, locked.categoryId), sourceId, targetId)
              if (typeof order === 'string') return order
              return rewritePositions(order, setSubcategoryPosition(trx))
            },
          ),
        ),
      ),
  }
}
