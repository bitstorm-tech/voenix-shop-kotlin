import { sql, type Kysely, type Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { conflict, invalid, notFound, ok, type OperationResult } from '@/platform/operation'
import { lockSequence } from '../ordering'
import '../schema'

/** A slot; `variantCount` lets the admin list warn before a delete that would be refused. */
export interface PromptSlot {
  id: number
  name: string
  position: number
  variantCount: number
}

/** A slot variant, flat: `slotId` to write back, `slotName` to display. */
export interface PromptSlotVariant {
  id: number
  slotId: number
  slotName: string
  name: string
  prompt: string
  description: string | null
  llm: string | null
  /** How many prompts use the variant, which is when its delete is refused. */
  assignedPromptCount: number
}

/** Every value a variant may change; its slot is not one of them. */
export type SlotVariantValues = Pick<PromptSlotVariant, 'name' | 'prompt' | 'description' | 'llm'>

/** The validated create body of a variant: its values plus the slot it is created in, once. */
export interface SlotVariantInput extends SlotVariantValues {
  slotId: number
}

export type PromptSlotRepository = ReturnType<typeof createPromptSlotRepository>

function selectSlots(trx: Transaction<Tables>) {
  return trx
    .selectFrom('prompt_slots as slot')
    .select(['slot.id', 'slot.name', 'slot.position'])
    .select((eb) =>
      eb
        .selectFrom('prompt_slot_variants as variant')
        .select(sql<number>`count(*)::int`.as('count'))
        .whereRef('variant.slot_id', '=', 'slot.id')
        .$asScalar()
        .$notNull()
        .as('variantCount'),
    )
}

function selectVariants(trx: Transaction<Tables>) {
  return trx
    .selectFrom('prompt_slot_variants as variant')
    .innerJoin('prompt_slots as slot', 'slot.id', 'variant.slot_id')
    .select([
      'variant.id',
      'variant.slot_id as slotId',
      'slot.name as slotName',
      'variant.name',
      'variant.prompt',
      'variant.description',
      'variant.llm',
    ])
    .select((eb) =>
      eb
        .selectFrom('prompt_slot_variant_mappings as mapping')
        .select(sql<number>`count(*)::int`.as('count'))
        .whereRef('mapping.slot_variant_id', '=', 'variant.id')
        .$asScalar()
        .$notNull()
        .as('assignedPromptCount'),
    )
}

const slotById = (trx: Transaction<Tables>, id: number) =>
  selectSlots(trx).where('slot.id', '=', id)

const variantById = (trx: Transaction<Tables>, id: number) =>
  selectVariants(trx).where('variant.id', '=', id)

/**
 * Slots and their variants. A unique violation can only be the name: the position rule is deferred
 * to `COMMIT` and unreachable under the `SLOT` anchor. A foreign-key violation has exactly one
 * possible cause per statement: the missing slot of a variant insert, the variants of a slot
 * delete, the prompts of a variant delete. `conflict` is therefore the name another slot or variant
 * already carries in any case (variant names are unique across all slots), or, for a delete, a
 * slot that still has variants or a variant a prompt still uses; the routes word the two apart.
 */
export function createPromptSlotRepository(db: Kysely<Tables>) {
  return {
    listSlots: (): Promise<PromptSlot[]> =>
      read(db, (trx) => selectSlots(trx).orderBy('slot.position').orderBy('slot.id').execute()),

    getSlot: async (id: number): Promise<OperationResult<PromptSlot>> => {
      const slot = await read(db, (trx) => slotById(trx, id).executeTakeFirst())
      return slot === undefined ? notFound : ok(slot)
    },

    /**
     * Appends behind the highest position. Gaps a delete left stay empty: nothing reads the number,
     * only the order it produces.
     */
    createSlot: (name: string): Promise<OperationResult<PromptSlot>> =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          await lockSequence(trx, 'SLOT')
          const { last } = await trx
            .selectFrom('prompt_slots')
            .select(sql<number>`coalesce(max(position), 0)`.as('last'))
            .executeTakeFirstOrThrow()
          const { id } = await trx
            .insertInto('prompt_slots')
            .values({ name, position: last + 1 })
            .returning('id')
            .executeTakeFirstOrThrow()
          return ok({ id, name, position: last + 1, variantCount: 0 })
        }),
      ),

    updateSlot: (id: number, name: string): Promise<OperationResult<PromptSlot>> =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          const result = await trx
            .updateTable('prompt_slots')
            .set({ name })
            .where('id', '=', id)
            .executeTakeFirst()
          return result.numUpdatedRows === 0n
            ? notFound
            : ok(await slotById(trx, id).executeTakeFirstOrThrow())
        }),
      ),

    /** A conflict while variants reference the slot. */
    deleteSlot: (id: number): Promise<OperationResult<undefined>> =>
      executePostgresWrite({ foreignKeyViolation: conflict }, async () => {
        const result = await db.deleteFrom('prompt_slots').where('id', '=', id).executeTakeFirst()
        return result.numDeletedRows === 0n ? notFound : ok(undefined)
      }),

    /** Every variant, in its slot's display order and then by name. */
    listVariants: (): Promise<PromptSlotVariant[]> =>
      read(db, (trx) =>
        selectVariants(trx)
          .orderBy('slot.position')
          .orderBy('slot.id')
          .orderBy('variant.name')
          .orderBy('variant.id')
          .execute(),
      ),

    getVariant: async (id: number): Promise<OperationResult<PromptSlotVariant>> => {
      const variant = await read(db, (trx) => variantById(trx, id).executeTakeFirst())
      return variant === undefined ? notFound : ok(variant)
    },

    /** A slot that does not exist is a broken field of the body, not a conflict. */
    createVariant: ({
      slotId,
      ...values
    }: SlotVariantInput): Promise<OperationResult<PromptSlotVariant>> =>
      executePostgresWrite(
        {
          uniqueViolation: conflict,
          foreignKeyViolation: invalid({ slotId: ['Prompt slot does not exist'] }),
        },
        () =>
          write(db, async (trx) => {
            const { id } = await trx
              .insertInto('prompt_slot_variants')
              .values({ slot_id: slotId, ...values })
              .returning('id')
              .executeTakeFirstOrThrow()
            return ok(await variantById(trx, id).executeTakeFirstOrThrow())
          }),
      ),

    updateVariant: (
      id: number,
      values: SlotVariantValues,
    ): Promise<OperationResult<PromptSlotVariant>> =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          const result = await trx
            .updateTable('prompt_slot_variants')
            .set(values)
            .where('id', '=', id)
            .executeTakeFirst()
          return result.numUpdatedRows === 0n
            ? notFound
            : ok(await variantById(trx, id).executeTakeFirstOrThrow())
        }),
      ),

    /** A conflict while a prompt is composed of the variant. */
    deleteVariant: (id: number): Promise<OperationResult<undefined>> =>
      executePostgresWrite({ foreignKeyViolation: conflict }, async () => {
        const result = await db
          .deleteFrom('prompt_slot_variants')
          .where('id', '=', id)
          .executeTakeFirst()
        return result.numDeletedRows === 0n ? notFound : ok(undefined)
      }),
  }
}
