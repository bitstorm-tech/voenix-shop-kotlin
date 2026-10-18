import { Hono } from 'hono'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { integerParam, jsonBody, respondFailure, respondResult } from '@/platform/http/respond'
import { articlePrice, optionalId, reorderInput } from '../routes'
import type { SpodArticleInput, SpodArticleService } from './service'

/**
 * The update body: what the shop decides about a synced article. A field the sync owns (`name`,
 * `printFrame`, …) is an unknown property here, like every unknown key of this backend. Two rules
 * are facts of the body and report on `active`; the four that need the stored row belong to the
 * write path.
 */
export const spodArticleInput = z
  .strictObject({
    active: z.boolean().default(false),
    categoryId: optionalId('CategoryId'),
    subcategoryId: optionalId('SubcategoryId'),
    defaultVariantId: optionalId('DefaultVariantId'),
    price: articlePrice,
  })
  .superRefine((input, ctx) => {
    const add = (field: string, message: string) => {
      ctx.addIssue({ code: 'custom', path: [field], message })
    }
    if (input.subcategoryId !== null && input.categoryId === null) {
      add('subcategoryId', 'SubcategoryId requires CategoryId')
    }
    if (input.active) {
      if (input.defaultVariantId === null) {
        add('active', 'An active article requires an active default variant')
      }
      if (input.categoryId === null) add('active', 'An active article requires a category')
    }
  }) satisfies z.ZodType<SpodArticleInput>

const SPOD_RESPONSES = { notFound: 'Article not found', conflict: null }
const ORDER_RESPONSES = {
  notFound: 'Article not found',
  conflict: 'Article order changed concurrently, please retry',
}
const articleId = integerParam('id', 'Invalid article id')

/**
 * The five admin SPOD article routes. There is no create and no upload: a sync run creates an
 * article and every picture comes from the partner (SPOD sync decision 1). Only the reorder can
 * answer `409`.
 */
export function spodArticleRoutes(articles: SpodArticleService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => c.json(await articles.list()))
  admin.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(c, await articles.reorder(sourceId, targetId), ORDER_RESPONSES)
  })
  admin.get('/:id', articleId, async (c) =>
    respondResult(c, await articles.get(c.req.valid('param').id), SPOD_RESPONSES),
  )
  admin.put('/:id', articleId, jsonBody(spodArticleInput), async (c) =>
    respondResult(
      c,
      await articles.update(c.req.valid('param').id, c.req.valid('json')),
      SPOD_RESPONSES,
    ),
  )
  admin.delete('/:id', articleId, async (c) => {
    const result = await articles.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, SPOD_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/articles/spod', admin)
  return routes
}
