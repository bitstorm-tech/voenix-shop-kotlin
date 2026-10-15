import { Hono } from 'hono'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { integerParam, jsonBody, respondFailure, respondResult } from '@/platform/http/respond'
import type { VatInput, VatService } from './service'

/**
 * The VAT create and update body. A missing, `null`, or wrongly typed `name` or `percent` answers
 * its required message; a blank description becomes `null`, a present one is trimmed.
 */
export const vatInput = z.strictObject({
  name: z
    .string({ error: 'Name is required' })
    .trim()
    .min(1, 'Name is required')
    .max(255, 'Name must be at most 255 characters'),
  percent: z
    .int({ error: 'Percent is required' })
    .min(0, 'Percent must be between 0 and 100')
    .max(100, 'Percent must be between 0 and 100'),
  description: z
    .string()
    .nullable()
    .default(null)
    .transform((description) => {
      const trimmed = description?.trim()
      return trimmed === undefined || trimmed === '' ? null : trimmed
    }),
  isDefault: z.boolean().default(false),
}) satisfies z.ZodType<VatInput>

const VAT_RESPONSES = { notFound: 'VAT not found', conflict: 'VAT entry already exists' }
const VAT_DELETE_RESPONSES = { notFound: 'VAT not found', conflict: 'VAT is in use' }
const vatId = integerParam('id', 'Invalid VAT id')

export function vatRoutes(vats: VatService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => respondResult(c, await vats.list(), VAT_RESPONSES))
  admin.post('/', jsonBody(vatInput), async (c) => {
    const result = await vats.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, VAT_RESPONSES)
    c.header('Location', `/api/admin/vat/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  admin.get('/:id', vatId, async (c) =>
    respondResult(c, await vats.get(c.req.valid('param').id), VAT_RESPONSES),
  )
  admin.put('/:id', vatId, jsonBody(vatInput), async (c) =>
    respondResult(
      c,
      await vats.update(c.req.valid('param').id, c.req.valid('json')),
      VAT_RESPONSES,
    ),
  )
  admin.delete('/:id', vatId, async (c) => {
    const result = await vats.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, VAT_DELETE_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/vat', admin)
  return routes
}
