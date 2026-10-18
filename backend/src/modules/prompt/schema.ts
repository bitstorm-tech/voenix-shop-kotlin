import type { Generated } from 'kysely'

/**
 * The tables of `V14__create_prompts.sql`. Every rule lives in the migration: names are unique by
 * `LOWER(name)` (variant names across all slots, subcategory names per category), positions are
 * positive and unique with the unique rule `DEFERRABLE INITIALLY DEFERRED`, and every reference
 * restricts the delete of the row it points at.
 */

/** Holds no data: its three rows are the locks of the global position sequences. */
export interface PromptOrderingTable {
  sequence: 'SLOT' | 'CATEGORY' | 'PROMPT'
}

/** The slots, in an order that is unique but gapped: a delete leaves its position behind. */
export interface PromptSlotsTable {
  id: Generated<number>
  name: string
  position: number
}

export interface PromptSlotVariantsTable {
  id: Generated<number>
  slot_id: number
  name: string
  prompt: string
  description: string | null
  llm: string | null
}

/** Category positions are global and dense. */
export interface PromptCategoriesTable {
  id: Generated<number>
  name: string
  position: number
  active: boolean
}

/** Subcategory positions are dense per category; `(id, category_id)` is what `prompts` references. */
export interface PromptSubcategoriesTable {
  id: Generated<number>
  category_id: number
  name: string
  description: string | null
  position: number
  active: boolean
}

/**
 * The prompts. `(subcategory_id, category_id)` references a subcategory together with its
 * category; `price_id` is nullable, unique, and points at a row of the pricing module.
 */
export interface PromptsTable {
  id: Generated<number>
  position: number
  title: string
  prompt_text: string
  category_id: number
  subcategory_id: number | null
  example_image_filename: string | null
  price_id: number | null
  llm: string | null
  active: boolean
  archived: boolean
}

/** Which slot variants a prompt is composed of; the rows go with their prompt. */
export interface PromptSlotVariantMappingsTable {
  prompt_id: number
  slot_variant_id: number
}

declare module '@/platform/db/database' {
  interface Tables {
    prompt_ordering: PromptOrderingTable
    prompt_slots: PromptSlotsTable
    prompt_slot_variants: PromptSlotVariantsTable
    prompt_categories: PromptCategoriesTable
    prompt_subcategories: PromptSubcategoriesTable
    prompts: PromptsTable
    prompt_slot_variant_mappings: PromptSlotVariantMappingsTable
  }
}
