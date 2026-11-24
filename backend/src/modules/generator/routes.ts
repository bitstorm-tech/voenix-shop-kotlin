import { Hono, type MiddlewareHandler } from 'hono'
import { readUploadedImage, validateImageUpload } from '@/modules/image'
import { guestCapable, requireAdmin, type Auth } from '@/platform/auth/auth'
import { apiError, formBody } from '@/platform/http/respond'
import { assertNever } from '@/platform/operation'
import { ALLOWED_IMAGE_CONTENT_TYPES, type BackgroundRemover } from './image-generator'
import {
  INITIAL_BALANCE,
  magicCoinsOwner,
  tryMagicCoinsOwner,
  type MagicCoins,
} from './magic-coins'
import type { GeneratorService } from './service'
import { readGenerationUpload } from './upload'

/** The storefront reads this code to open its own out-of-coins dialog. */
const INSUFFICIENT_COINS_CODE = 'INSUFFICIENT_MAGIC_COINS'

/**
 * `POST /api/generator/generate` behind the guest-capable protection and then the per-IP rate
 * limit, so a request without a CSRF token spends no slot; `GET /api/magic-coins/balance`; and the
 * admin's `POST /api/admin/images/background-removal`, which reads the `file` part and stores
 * nothing. A generated or cut-out image is answered as its raw bytes in its own content type, no
 * JSON envelope and no `Content-Disposition`, because the frontend reads it as a `Blob`.
 */
export function generatorRoutes({
  generator,
  removeBackground,
  coins,
  guestTokens,
  rateLimit,
}: {
  generator: GeneratorService
  removeBackground: BackgroundRemover
  coins: MagicCoins
  guestTokens: Auth['guestTokens']
  rateLimit: MiddlewareHandler
}): Hono {
  const generation = new Hono()
  generation.use(guestCapable)
  generation.post('/generate', formBody, rateLimit, async (c) => {
    const upload = await readGenerationUpload(await c.req.formData())
    if (upload.kind === 'rejected') {
      return c.json(apiError('Validation failed', { [upload.field]: [upload.message] }), 400)
    }
    const outcome = await generator.generate(await magicCoinsOwner(c, guestTokens), upload)
    switch (outcome.kind) {
      case 'generated':
        return c.body(outcome.image.bytes, 200, { 'Content-Type': outcome.image.contentType })
      case 'insufficientCoins':
        return c.json(apiError('Not enough Magic Coins', {}, INSUFFICIENT_COINS_CODE), 402)
      case 'articleUnavailable':
        return c.json(apiError('Article not found'), 404)
      case 'promptUnavailable':
        return c.json(apiError('Prompt not found'), 404)
      case 'upstreamFailure':
        return c.json(apiError('Generator API error'), 502)
      default:
        return assertNever(outcome)
    }
  })

  // Admin-only and therefore without a rate limit; every call costs fal.ai money all the same.
  const backgroundRemoval = new Hono()
  backgroundRemoval.use(requireAdmin)
  backgroundRemoval.post('/', formBody, async (c) => {
    const upload = await readUploadedImage(await c.req.formData())
    const refuse = (message: string) =>
      c.json(apiError('Validation failed', { file: [message] }), 400)
    if (upload.kind === 'tooLarge') return refuse('Image must be at most 10 MiB')
    if (upload.kind === 'missing' || upload.upload.bytes.length === 0) {
      return refuse('An image file is required')
    }
    const contentType = upload.upload.contentType.trim().toLowerCase()
    if (!ALLOWED_IMAGE_CONTENT_TYPES.has(contentType)) {
      return refuse('Image must be a JPEG, PNG, or WebP file')
    }
    // Copied, because the upload's bytes are typed without the plain `ArrayBuffer` a body needs.
    const bytes = new Uint8Array(upload.upload.bytes)
    const validated = await validateImageUpload(bytes, contentType)
    if ('rejection' in validated) return refuse(validated.rejection)
    const result = await removeBackground({ bytes, contentType: validated.contentType })
    if (result === null) return c.json(apiError('Background removal failed'), 502)
    return c.body(result.bytes, 200, { 'Content-Type': result.contentType })
  })

  const routes = new Hono()
  routes.route('/api/generator', generation)
  routes.route('/api/admin/images/background-removal', backgroundRemoval)
  routes.get('/api/magic-coins/balance', async (c) => {
    c.header('Cache-Control', 'no-store')
    const owner = await tryMagicCoinsOwner(c, guestTokens)
    const balance = owner === null ? INITIAL_BALANCE : await coins.readBalance(owner)
    return c.json({ balance })
  })
  return routes
}
