import { Hono } from 'hono'
import { z } from 'zod'
import { priceInput } from '@/modules/pricing'
import type { Storefront } from './storefront'

/**
 * What every route file of this module shares: the request fragments both article bodies and all
 * four reorders use, and the three anonymous storefront routes.
 */

/** A required id of a body: `X is required` when absent, `X must be positive` below 1. */
const requiredId = (displayName: string) =>
  z.int({ error: `${displayName} is required` }).positive(`${displayName} must be positive`)

/** An optional reference id: absent or `null` mean none, a submitted one must be positive. */
export const optionalId = (displayName: string) =>
  z.int().positive(`${displayName} must be positive`).nullable().default(null)

/** A text the client may leave out; blank means none, so it is stored as `null`. */
export const optionalText = (displayName: string, maximumLength: number) =>
  z
    .string()
    .trim()
    .max(maximumLength, `${displayName} must be at most ${String(maximumLength)} characters`)
    .nullable()
    .default(null)
    .transform((text) => (text === '' ? null : text))

/** The name a pre-upload answered; blank means no image. Whether it exists is checked while saving. */
export const optionalFilename = z
  .string()
  .trim()
  .nullable()
  .default(null)
  .transform((filename) => (filename === '' ? null : filename))

/** A required text, trimmed. */
export const requiredText = (displayName: string, maximumLength: number) =>
  z
    .string({ error: `${displayName} is required` })
    .trim()
    .min(1, { error: `${displayName} is required`, abort: true })
    .max(maximumLength, `${displayName} must be at most ${String(maximumLength)} characters`)

/** The price an article body embeds; absent or `null` keeps the stored price. */
export const articlePrice = priceInput.nullable().default(null)

/**
 * The body of every reorder route: move `sourceId` to the place currently held by `targetId`.
 * Whether the ids exist is the database's answer (`404`), not a field rule.
 */
export const reorderInput = z
  .strictObject({ sourceId: requiredId('SourceId'), targetId: requiredId('TargetId') })
  .superRefine((input, ctx) => {
    if (input.sourceId === input.targetId) {
      ctx.addIssue({
        code: 'custom',
        path: ['targetId'],
        message: 'TargetId must be different from SourceId',
      })
    }
  })

/**
 * The three anonymous storefront reads. Nothing here takes a parameter or a body, so the only
 * failure is an exception, which the app's `onError` answers.
 */
export function storefrontRoutes(storefront: Storefront): Hono {
  const routes = new Hono()
  routes.get('/api/articles/mugs', async (c) => c.json(await storefront.mugs()))
  routes.get('/api/articles/spod', async (c) => c.json(await storefront.spodArticles()))
  routes.get('/api/articles/categories', async (c) => c.json(await storefront.navigation()))
  return routes
}
