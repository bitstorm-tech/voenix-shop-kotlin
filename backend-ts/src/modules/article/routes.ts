import { Hono, type Context } from 'hono'
import { z } from 'zod'
import { readUploadedImage, uploadRejection, type ExampleImages } from '@/modules/image'
import { priceInput } from '@/modules/pricing'
import { respondResult, type FailureResponses } from '@/platform/http/respond'
import type { Storefront } from './storefront'

/**
 * What every route file of this module shares: the request fragments both article bodies and all
 * four reorders use, the example-image pre-upload, and the three anonymous storefront routes.
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
 * `POST …/example-images`: stores the `file` part of a multipart body in the slice's folder and
 * answers `201 { filename }`, the name a following create or update submits. A missing part and an
 * oversized one are `400` on the `file` field, like everything the storage rejects. Install it
 * behind `formBody`, which refuses a body that is no multipart form.
 */
export function exampleImageUpload(store: ExampleImages['store'], responses: FailureResponses) {
  return async (c: Context): Promise<Response> => {
    const upload = await readUploadedImage(await c.req.formData())
    const result =
      upload.kind === 'missing'
        ? uploadRejection('An example image file part is required')
        : upload.kind === 'tooLarge'
          ? uploadRejection('Example image must not exceed 10 MiB')
          : await store(upload.upload)
    return respondResult(c, result, responses, 201)
  }
}

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
