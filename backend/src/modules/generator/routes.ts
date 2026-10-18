import { Hono, type MiddlewareHandler } from 'hono'
import { guestCapable, type Auth } from '@/platform/auth/auth'
import { apiError, formBody } from '@/platform/http/respond'
import { assertNever } from '@/platform/operation'
import { magicCoinsOwner, type MagicCoins } from './magic-coins'
import type { GeneratorService } from './service'
import { readGenerationUpload } from './upload'

/** The storefront reads this code to open its own out-of-coins dialog. */
const INSUFFICIENT_COINS_CODE = 'INSUFFICIENT_MAGIC_COINS'

/**
 * `POST /api/generator/generate` behind the guest-capable protection and then the per-IP rate
 * limit, so a request without a CSRF token spends no slot; and `GET /api/magic-coins/balance`. A
 * generated image is answered as its raw bytes in its own content type, no JSON envelope and no
 * `Content-Disposition`, because the frontend reads it as a `Blob`.
 */
export function generatorRoutes({
  generator,
  coins,
  guestTokens,
  rateLimit,
}: {
  generator: GeneratorService
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

  const routes = new Hono()
  routes.route('/api/generator', generation)
  routes.get('/api/magic-coins/balance', async (c) => {
    c.header('Cache-Control', 'no-store')
    return c.json({ balance: await coins.balance(await magicCoinsOwner(c, guestTokens)) })
  })
  return routes
}
