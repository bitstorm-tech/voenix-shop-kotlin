import { invalid, ok, type OperationResult } from '@/platform/operation'
import { deleted, found, written } from '../outcomes'
import type {
  PromptSlot,
  PromptSlotRepository,
  PromptSlotVariant,
  SlotVariantValues,
} from './repository'

/** The validated create body of a variant: its values plus the slot it is created in, once. */
export interface SlotVariantInput extends SlotVariantValues {
  slotId: number
}

export type PromptSlotService = ReturnType<typeof createPromptSlotService>

/**
 * Slots and slot variants. `conflict` is the name another slot or variant already carries in any
 * case (variant names are unique across all slots), or, for a delete, a slot that still has
 * variants or a variant a prompt still uses; the routes word the two apart.
 */
export function createPromptSlotService(repository: PromptSlotRepository) {
  return {
    listSlots: async (): Promise<OperationResult<PromptSlot[]>> => ok(await repository.listSlots()),

    getSlot: async (id: number): Promise<OperationResult<PromptSlot>> =>
      found(await repository.findSlot(id)),

    createSlot: async (name: string): Promise<OperationResult<PromptSlot>> =>
      written(await repository.insertSlot(name)),

    updateSlot: async (id: number, name: string): Promise<OperationResult<PromptSlot>> =>
      written(await repository.updateSlot(id, name)),

    deleteSlot: async (id: number): Promise<OperationResult<undefined>> =>
      deleted(await repository.deleteSlot(id)),

    listVariants: async (): Promise<OperationResult<PromptSlotVariant[]>> =>
      ok(await repository.listVariants()),

    getVariant: async (id: number): Promise<OperationResult<PromptSlotVariant>> =>
      found(await repository.findVariant(id)),

    /** A slot that does not exist is a broken field of the body, not a conflict. */
    createVariant: async ({
      slotId,
      ...values
    }: SlotVariantInput): Promise<OperationResult<PromptSlotVariant>> => {
      const stored = await repository.insertVariant(slotId, values)
      if (stored === 'slotNotFound') return invalid({ slotId: ['Prompt slot does not exist'] })
      return written(stored)
    },

    updateVariant: async (
      id: number,
      values: SlotVariantValues,
    ): Promise<OperationResult<PromptSlotVariant>> =>
      written(await repository.updateVariant(id, values)),

    deleteVariant: async (id: number): Promise<OperationResult<undefined>> =>
      deleted(await repository.deleteVariant(id)),
  }
}
