import { sql, type Kysely, type Transaction } from 'kysely'
import type { CalculatedPrice, PriceCatalog } from '@/modules/pricing'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { lockCategories, lockSequence, moved, rewritePositions } from '../ordering'
import type { PromptInput } from './service'
import '../schema'

/** The stored admin representation of a prompt; the service adds the calculated price. */
export interface StoredPrompt {
  id: number
  position: number
  title: string
  /** Verbatim: only the composed text trims. */
  promptText: string
  categoryId: number
  subcategoryId: number | null
  /** Deduplicated and ascending, whatever order a request sent. */
  slotVariantIds: number[]
  exampleImageFilename: string | null
  llm: string | null
  active: boolean
  archived: boolean
  priceId: number | null
}

/** One stored row of the admin list, with the display names of both category levels. */
export interface StoredListRow {
  id: number
  position: number
  title: string
  categoryId: number
  categoryName: string
  subcategoryId: number | null
  subcategoryName: string | null
  exampleImageFilename: string | null
  llm: string | null
  active: boolean
  archived: boolean
  priceId: number | null
}

/** A category level as the storefront sees it. */
export interface CategoryReference {
  id: number
  name: string
  position: number
}

/** One stored row of the storefront list. There is no prompt text: the query never selects it. */
export interface StoredPublicRow {
  id: number
  position: number
  title: string
  category: CategoryReference
  subcategory: CategoryReference | null
  exampleImageFilename: string | null
  llm: string | null
  priceId: number | null
}

/** A stored write, and the example image no prompt names any more once it ran. */
export interface PromptWrite {
  prompt: StoredPrompt
  obsoleteExampleImage: string | null
}

export type PromptValues = Omit<PromptInput, 'price'>
export type PromptRepository = ReturnType<typeof createPromptRepository>

const promptColumns = [
  'id',
  'position',
  'title',
  'prompt_text as promptText',
  'category_id as categoryId',
  'subcategory_id as subcategoryId',
  'example_image_filename as exampleImageFilename',
  'llm',
  'active',
  'archived',
  'price_id as priceId',
] as const

async function findPrompt(trx: Transaction<Tables>, id: number): Promise<StoredPrompt | undefined> {
  const row = await trx
    .selectFrom('prompts')
    .select(promptColumns)
    .where('id', '=', id)
    .executeTakeFirst()
  if (row === undefined) return undefined
  const mappings = await trx
    .selectFrom('prompt_slot_variant_mappings')
    .select('slot_variant_id')
    .where('prompt_id', '=', id)
    .orderBy('slot_variant_id')
    .execute()
  const { priceId, ...prompt } = row
  return { ...prompt, slotVariantIds: mappings.map((mapping) => mapping.slot_variant_id), priceId }
}

/** Every prompt in `(position, id)` order, in one query with both category names. */
const listRows = (trx: Transaction<Tables>): Promise<StoredListRow[]> =>
  trx
    .selectFrom('prompts as prompt')
    .innerJoin('prompt_categories as category', 'category.id', 'prompt.category_id')
    .leftJoin('prompt_subcategories as subcategory', 'subcategory.id', 'prompt.subcategory_id')
    .select([
      'prompt.id',
      'prompt.position',
      'prompt.title',
      'prompt.category_id as categoryId',
      'category.name as categoryName',
      'prompt.subcategory_id as subcategoryId',
      'subcategory.name as subcategoryName',
      'prompt.example_image_filename as exampleImageFilename',
      'prompt.llm',
      'prompt.active',
      'prompt.archived',
      'prompt.price_id as priceId',
    ])
    .orderBy('prompt.position')
    .orderBy('prompt.id')
    .execute()

/**
 * Whether the subcategory belongs to the category. Asked while the category row is locked: every
 * writer that deletes a subcategory or moves one in or out of a category locks that row first, so
 * the answer holds until this transaction ends.
 */
async function isSubcategoryOf(
  trx: Transaction<Tables>,
  subcategoryId: number,
  categoryId: number,
): Promise<boolean> {
  const row = await trx
    .selectFrom('prompt_subcategories')
    .select('id')
    .where('id', '=', subcategoryId)
    .where('category_id', '=', categoryId)
    .executeTakeFirst()
  return row !== undefined
}

/** The checks under the category lock that turn the two category references into field errors. */
async function lockReferences(trx: Transaction<Tables>, values: PromptValues) {
  if (!(await lockCategories(trx, [values.categoryId]))) return 'categoryNotFound'
  if (
    values.subcategoryId !== null &&
    !(await isSubcategoryOf(trx, values.subcategoryId, values.categoryId))
  ) {
    return 'subcategoryNotFound'
  }
  return undefined
}

function promptRow(values: PromptValues) {
  return {
    title: values.title,
    prompt_text: values.promptText,
    category_id: values.categoryId,
    subcategory_id: values.subcategoryId,
    example_image_filename: values.exampleImageFilename,
    llm: values.llm,
    active: values.active,
    archived: values.archived,
  }
}

async function insertMappings(
  trx: Transaction<Tables>,
  promptId: number,
  slotVariantIds: number[],
) {
  if (slotVariantIds.length === 0) return
  await trx
    .insertInto('prompt_slot_variant_mappings')
    .values(
      slotVariantIds.map((slotVariantId) => ({
        prompt_id: promptId,
        slot_variant_id: slotVariantId,
      })),
    )
    .execute()
}

/** Every visible prompt in `(position, id)` order, optionally of one category. */
async function publicRows(
  trx: Transaction<Tables>,
  categoryId: number | null,
): Promise<StoredPublicRow[]> {
  let query = trx
    .selectFrom('prompts as prompt')
    .innerJoin('prompt_categories as category', 'category.id', 'prompt.category_id')
    .leftJoin('prompt_subcategories as subcategory', 'subcategory.id', 'prompt.subcategory_id')
    .select([
      'prompt.id',
      'prompt.position',
      'prompt.title',
      'category.id as categoryId',
      'category.name as categoryName',
      'category.position as categoryPosition',
      'subcategory.id as subcategoryId',
      'subcategory.name as subcategoryName',
      'subcategory.position as subcategoryPosition',
      'prompt.example_image_filename as exampleImageFilename',
      'prompt.llm',
      'prompt.price_id as priceId',
    ])
    .where('prompt.active', '=', true)
    .where('prompt.archived', '=', false)
    .where('category.active', '=', true)
    .where((eb) =>
      eb.or([eb('prompt.subcategory_id', 'is', null), eb('subcategory.active', '=', true)]),
    )
    .orderBy('prompt.position')
    .orderBy('prompt.id')
  if (categoryId !== null) query = query.where('prompt.category_id', '=', categoryId)
  const rows = await query.execute()
  return rows.map((row) => ({
    id: row.id,
    position: row.position,
    title: row.title,
    category: { id: row.categoryId, name: row.categoryName, position: row.categoryPosition },
    subcategory:
      row.subcategoryId === null || row.subcategoryName === null || row.subcategoryPosition === null
        ? null
        : { id: row.subcategoryId, name: row.subcategoryName, position: row.subcategoryPosition },
    exampleImageFilename: row.exampleImageFilename,
    llm: row.llm,
    priceId: row.priceId,
  }))
}

/**
 * Prompts, the slot variants they are composed of, and the price row each owns. The price joins
 * the prompt's transaction through `priceCatalog`, so the two commit or roll back together.
 *
 * Lock order, the module's deadlock rule: the `PROMPT` anchor (only the writes that decide a
 * position), then the category row, then prompt rows. With the category row held and the price id
 * minted in the same transaction, the one reference a write can still violate is a slot variant:
 * a foreign-key violation means `'slotVariantNotFound'`. A unique violation is mapped only for the
 * reorder, where it is the deferred position rule failing at `COMMIT`.
 */
export function createPromptRepository(db: Kysely<Tables>, priceCatalog: PriceCatalog) {
  return {
    list: () => read(db, listRows),

    find: (id: number) => read(db, (trx) => findPrompt(trx, id)),

    /** Appends behind the last prompt: the price first, then the row, then its mappings. */
    insert: (values: PromptValues, price: CalculatedPrice) =>
      executePostgresWrite({ foreignKeyViolation: 'slotVariantNotFound' }, () =>
        write(db, async (trx) => {
          await lockSequence(trx, 'PROMPT')
          const rejected = await lockReferences(trx, values)
          if (rejected !== undefined) return rejected
          const { last } = await trx
            .selectFrom('prompts')
            .select(sql<number>`coalesce(max(position), 0)`.as('last'))
            .executeTakeFirstOrThrow()
          const priceId = await priceCatalog.storeInTransaction(trx, price)
          const { id } = await trx
            .insertInto('prompts')
            .values({ ...promptRow(values), position: last + 1, price_id: priceId })
            .returning('id')
            .executeTakeFirstOrThrow()
          await insertMappings(trx, id, values.slotVariantIds)
          const prompt = await findPrompt(trx, id)
          if (prompt === undefined)
            throw new Error(`The prompt ${String(id)} just written is missing`)
          return { prompt, obsoleteExampleImage: null } satisfies PromptWrite
        }),
      ),

    /**
     * Replaces every value but the position, including the whole mapping set and the inputs of the
     * price row, which keeps its id. A prompt whose `price_id` is empty gets a price linked here.
     */
    update: (id: number, values: PromptValues, price: CalculatedPrice) =>
      executePostgresWrite({ foreignKeyViolation: 'slotVariantNotFound' }, () =>
        write(db, async (trx) => {
          const exists = await trx
            .selectFrom('prompts')
            .select('id')
            .where('id', '=', id)
            .executeTakeFirst()
          if (exists === undefined) return 'notFound'
          const rejected = await lockReferences(trx, values)
          if (rejected !== undefined) return rejected
          const locked = await trx
            .selectFrom('prompts')
            .select(['price_id', 'example_image_filename'])
            .where('id', '=', id)
            .forUpdate()
            .executeTakeFirstOrThrow()
          let priceId = locked.price_id
          if (priceId === null) {
            priceId = await priceCatalog.storeInTransaction(trx, price)
          } else if (!(await priceCatalog.replaceInTransaction(trx, priceId, price))) {
            throw new Error(`The price ${String(priceId)} of prompt ${String(id)} is missing`)
          }
          await trx
            .updateTable('prompts')
            .set({ ...promptRow(values), price_id: priceId })
            .where('id', '=', id)
            .execute()
          await trx.deleteFrom('prompt_slot_variant_mappings').where('prompt_id', '=', id).execute()
          await insertMappings(trx, id, values.slotVariantIds)
          const prompt = await findPrompt(trx, id)
          if (prompt === undefined)
            throw new Error(`The prompt ${String(id)} just written is missing`)
          return {
            prompt,
            obsoleteExampleImage: await unreferenced(trx, locked.example_image_filename),
          } satisfies PromptWrite
        }),
      ),

    /**
     * Moves `sourceId` to the place of `targetId` and answers the complete new order. The prompt
     * rows are locked ascending by id *after* the order is read: a position another writer changed
     * in between is what the deferred unique rule catches at `COMMIT`.
     */
    reorder: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          await lockSequence(trx, 'PROMPT')
          const stored = await listRows(trx)
          const order = moved(stored, sourceId, targetId)
          if (typeof order === 'string') return order
          for (const id of stored.map((row) => row.id).sort((a, b) => a - b)) {
            await trx.selectFrom('prompts').select('id').where('id', '=', id).forUpdate().execute()
          }
          return rewritePositions(order, (id, position) =>
            trx.updateTable('prompts').set({ position }).where('id', '=', id).execute(),
          )
        }),
      ),

    /** The storefront read; `prompt_text` is never selected. */
    listPublic: (categoryId: number | null) => read(db, (trx) => publicRows(trx, categoryId)),

    /**
     * The stored text of a usable prompt (active, not archived) and the texts of its slot variants
     * ordered `(slot.position, slot.id, variant.name, variant.id)`; `undefined` when there is no
     * usable prompt with that id. The category flags are not read at all.
     */
    composition: async (promptId: number) => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('prompts as prompt')
          .leftJoin('prompt_slot_variant_mappings as mapping', 'mapping.prompt_id', 'prompt.id')
          .leftJoin('prompt_slot_variants as variant', 'variant.id', 'mapping.slot_variant_id')
          .leftJoin('prompt_slots as slot', 'slot.id', 'variant.slot_id')
          .select(['prompt.prompt_text as promptText', 'variant.prompt as variantText'])
          .where('prompt.id', '=', promptId)
          .where('prompt.active', '=', true)
          .where('prompt.archived', '=', false)
          .orderBy('slot.position')
          .orderBy('slot.id')
          .orderBy('variant.name')
          .orderBy('variant.id')
          .execute(),
      )
      const first = rows[0]
      if (first === undefined) return undefined
      return {
        promptText: first.promptText,
        variantTexts: rows.flatMap((row) => (row.variantText === null ? [] : [row.variantText])),
      }
    },

    /** The price id of every usable prompt among `promptIds` that has one, keyed by prompt id. */
    priceIds: async (promptIds: number[]): Promise<Map<number, number>> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('prompts')
          .select(['id', 'price_id'])
          .where('id', 'in', promptIds)
          .where('active', '=', true)
          .where('archived', '=', false)
          .where('price_id', 'is not', null)
          .execute(),
      )
      return new Map(
        rows.flatMap((row) => (row.price_id === null ? [] : [[row.id, row.price_id] as const])),
      )
    },
  }
}

/**
 * `filename` when no prompt names it any more, asked after the write's statements ran and inside
 * its transaction: the state the commit publishes. Nothing makes a name exclusive, so another
 * prompt may still show the same file.
 */
async function unreferenced(trx: Transaction<Tables>, filename: string | null) {
  if (filename === null) return null
  const user = await trx
    .selectFrom('prompts')
    .select('id')
    .where('example_image_filename', '=', filename)
    .limit(1)
    .executeTakeFirst()
  return user === undefined ? filename : null
}
