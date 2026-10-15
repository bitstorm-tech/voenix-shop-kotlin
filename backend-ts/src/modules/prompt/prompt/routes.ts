import { Hono } from 'hono'
import { z } from 'zod'
import { priceInput } from '@/modules/pricing'
import { readUploadedImage, uploadRejection } from '@/modules/image'
import {
  apiError,
  formBody,
  integerParam,
  jsonBody,
  respondFailure,
  respondResult,
} from '@/platform/http/respond'
import { optionalText, reorderInput } from '../fields'
import type { PromptInput, PromptService } from './service'

/**
 * The create and update body. Neither `position` nor `priceId` exists: the module decides the
 * position, and a price id is only minted while a prompt is written. `promptText` is checked but
 * never trimmed; `slotVariantIds` may be empty and loses its duplicates.
 */
export const promptInput = z.strictObject({
  title: z
    .string({ error: 'Title is required' })
    .trim()
    .min(1, 'Title is required')
    .max(255, 'Title must be at most 255 characters'),
  promptText: z
    .string({ error: 'PromptText is required' })
    .refine((text) => text.trim() !== '', 'PromptText is required'),
  categoryId: z.int({ error: 'CategoryId is required' }).positive('CategoryId must be positive'),
  subcategoryId: z
    .int({ error: 'SubcategoryId must be an integer' })
    .positive('SubcategoryId must be positive')
    .nullable()
    .default(null),
  slotVariantIds: z
    .array(z.int(), { error: 'SlotVariantIds is required' })
    .refine((ids) => ids.every((id) => id > 0), 'SlotVariantIds must be positive')
    .transform((ids) => [...new Set(ids)]),
  exampleImageFilename: optionalText('ExampleImageFilename', 255),
  llm: optionalText('Llm', 255),
  active: z.boolean({ error: 'Active must be a boolean' }).default(false),
  archived: z.boolean({ error: 'Archived must be a boolean' }).default(false),
  price: z
    .unknown()
    .refine((price) => price !== undefined && price !== null, {
      error: 'Price is required',
      abort: true,
    })
    .pipe(priceInput),
}) satisfies z.ZodType<PromptInput>

const PROMPT_PATH = '/api/admin/prompts'
// Only the reorder answers a conflict: a prompt has no unique name and is never deleted.
const PROMPT_RESPONSES = {
  notFound: 'Prompt not found',
  conflict: 'Prompt order changed concurrently, please retry',
}
const promptId = integerParam('id', 'Invalid prompt id')

/** `/api/admin/prompts` itself, mounted below the module's admin protection. There is no delete. */
export function promptAdminRoutes(prompts: PromptService): Hono {
  const routes = new Hono()
  routes.get('/', async (c) => respondResult(c, await prompts.list(), PROMPT_RESPONSES))
  routes.post('/', jsonBody(promptInput), async (c) => {
    const result = await prompts.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, PROMPT_RESPONSES)
    c.header('Location', `${PROMPT_PATH}/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  routes.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(c, await prompts.reorder(sourceId, targetId), PROMPT_RESPONSES)
  })
  routes.post('/example-images', formBody, async (c) => {
    const upload = await readUploadedImage(await c.req.formData())
    switch (upload.kind) {
      case 'missing':
        return respondResult(
          c,
          uploadRejection('An example image file part is required'),
          PROMPT_RESPONSES,
        )
      case 'tooLarge':
        return respondResult(
          c,
          uploadRejection('Example image must not exceed 10 MiB'),
          PROMPT_RESPONSES,
        )
      case 'received':
        return respondResult(
          c,
          await prompts.storeExampleImage(upload.upload),
          PROMPT_RESPONSES,
          201,
        )
    }
  })
  routes.get('/:id', promptId, async (c) =>
    respondResult(c, await prompts.get(c.req.valid('param').id), PROMPT_RESPONSES),
  )
  routes.put('/:id', promptId, jsonBody(promptInput), async (c) =>
    respondResult(
      c,
      await prompts.update(c.req.valid('param').id, c.req.valid('json')),
      PROMPT_RESPONSES,
    ),
  )
  return routes
}

/**
 * `GET /api/prompts`, anonymous. An absent or blank `categoryId` means no filter; a value that is
 * not an integer answers `400`; an integer no category has answers `[]`.
 */
export function publicPromptRoutes(prompts: PromptService): Hono {
  const routes = new Hono()
  routes.get('/api/prompts', async (c) => {
    const requested = c.req.query('categoryId')
    let categoryId: number | null = null
    if (requested !== undefined && requested.trim() !== '') {
      categoryId = /^[+-]?\d+$/.test(requested) ? Number(requested) : Number.NaN
      if (!Number.isSafeInteger(categoryId)) {
        return c.json(apiError('Invalid prompt category id'), 400)
      }
    }
    return c.json(await prompts.listPublic(categoryId))
  })
  return routes
}
