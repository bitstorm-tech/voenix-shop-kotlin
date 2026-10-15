import { Hono } from 'hono'
import { z } from 'zod'
import { integerParam, jsonBody, respondFailure, respondResult } from '@/platform/http/respond'
import { optionalText, requiredName } from '../fields'
import type { SlotVariantValues } from './repository'
import type { PromptSlotService, SlotVariantInput } from './service'

/** The create and update body of a slot: a slot carries nothing but its name. */
export const slotInput = z.strictObject({ name: requiredName(255) })

const variantValues = {
  name: requiredName(255),
  prompt: z
    .string({ error: 'Prompt is required' })
    .trim()
    .min(1, 'Prompt is required')
    .max(10_000, 'Prompt must be at most 10000 characters'),
  description: optionalText('Description', 1000),
  llm: optionalText('LLM', 255),
}

/** The update body of a variant. It has no `slotId`: a variant never changes its slot. */
export const slotVariantUpdate = z.strictObject(
  variantValues,
) satisfies z.ZodType<SlotVariantValues>

/** The create body of a variant: the update's fields plus the slot it is created in. */
export const slotVariantInput = z.strictObject({
  slotId: z.int({ error: 'Slot id is required' }).positive('Slot id must be positive'),
  ...variantValues,
}) satisfies z.ZodType<SlotVariantInput>

const SLOT_PATH = '/api/admin/prompts/slots'
const SLOT_RESPONSES = {
  notFound: 'Prompt slot not found',
  conflict: 'Prompt slot name already exists',
}
const SLOT_IN_USE = {
  ...SLOT_RESPONSES,
  conflict: 'Prompt slot is used by slot variants and cannot be deleted',
}
const slotId = integerParam('id', 'Invalid prompt slot id')

const VARIANT_PATH = '/api/admin/prompts/slot-variants'
const VARIANT_RESPONSES = {
  notFound: 'Prompt slot variant not found',
  conflict: 'Prompt slot variant name already exists',
}
const VARIANT_IN_USE = {
  ...VARIANT_RESPONSES,
  conflict: 'Prompt slot variant is used by prompts and cannot be deleted',
}
const variantId = integerParam('id', 'Invalid prompt slot variant id')

/** `/api/admin/prompts/slots`, mounted below the module's admin protection. */
export function slotRoutes(slots: PromptSlotService): Hono {
  const routes = new Hono()
  routes.get('/', async (c) => respondResult(c, await slots.listSlots(), SLOT_RESPONSES))
  routes.post('/', jsonBody(slotInput), async (c) => {
    const result = await slots.createSlot(c.req.valid('json').name)
    if (result.kind !== 'ok') return respondFailure(c, result, SLOT_RESPONSES)
    c.header('Location', `${SLOT_PATH}/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  routes.get('/:id', slotId, async (c) =>
    respondResult(c, await slots.getSlot(c.req.valid('param').id), SLOT_RESPONSES),
  )
  routes.put('/:id', slotId, jsonBody(slotInput), async (c) =>
    respondResult(
      c,
      await slots.updateSlot(c.req.valid('param').id, c.req.valid('json').name),
      SLOT_RESPONSES,
    ),
  )
  routes.delete('/:id', slotId, async (c) => {
    const result = await slots.deleteSlot(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, SLOT_IN_USE)
    return c.body(null, 204)
  })
  return routes
}

/** `/api/admin/prompts/slot-variants`, mounted below the module's admin protection. */
export function slotVariantRoutes(slots: PromptSlotService): Hono {
  const routes = new Hono()
  routes.get('/', async (c) => respondResult(c, await slots.listVariants(), VARIANT_RESPONSES))
  routes.post('/', jsonBody(slotVariantInput), async (c) => {
    const result = await slots.createVariant(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, VARIANT_RESPONSES)
    c.header('Location', `${VARIANT_PATH}/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  routes.get('/:id', variantId, async (c) =>
    respondResult(c, await slots.getVariant(c.req.valid('param').id), VARIANT_RESPONSES),
  )
  routes.put('/:id', variantId, jsonBody(slotVariantUpdate), async (c) =>
    respondResult(
      c,
      await slots.updateVariant(c.req.valid('param').id, c.req.valid('json')),
      VARIANT_RESPONSES,
    ),
  )
  routes.delete('/:id', variantId, async (c) => {
    const result = await slots.deleteVariant(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, VARIANT_IN_USE)
    return c.body(null, 204)
  })
  return routes
}
