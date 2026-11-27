import { zValidator } from '@hono/zod-validator'
import { Hono, type Context, type MiddlewareHandler } from 'hono'
import { createMiddleware } from 'hono/factory'
import { z } from 'zod'
import { readUploadedImage, validateImageUpload } from '@/modules/image'
import { guestCapable, requireAdmin, requireAuthenticated, type Auth } from '@/platform/auth/auth'
import { apiError, formBody, jsonBody, validationFailed } from '@/platform/http/respond'
import { assertNever } from '@/platform/operation'
import { ALLOWED_IMAGE_CONTENT_TYPES, type BackgroundRemover } from './image-generator'
import type { MagicCoinRequestsService } from './magic-coin-requests'
import { magicCoinsOwner } from './magic-coins'
import type { GeneratorService } from './service'
import { readGenerationUpload } from './upload'

/** The storefront reads this code to open its out-of-coins dialog. */
const INSUFFICIENT_COINS_CODE = 'INSUFFICIENT_MAGIC_COINS'
const REQUEST_BLOCKED_CODE = 'MAGIC_COINS_REQUEST_BLOCKED'
const REQUEST_ALREADY_PENDING_CODE = 'MAGIC_COIN_REQUEST_ALREADY_PENDING'
const COINS_AVAILABLE_CODE = 'MAGIC_COINS_AVAILABLE'

const REQUEST_NOT_FOUND = 'Magic Coin request not found'
const BLOCK_NOT_FOUND = 'Blocked account not found'

const noStore = createMiddleware(async (c, next) => {
  c.header('Cache-Control', 'no-store')
  await next()
})

const id = z
  .string()
  .regex(/^\d{1,15}$/)
  .transform(Number)

const idParam = <Name extends string>(name: Name, notFoundMessage: string) =>
  zValidator('param', z.object({ [name]: id } as Record<Name, typeof id>), (result, c) =>
    result.success ? undefined : c.json(apiError(notFoundMessage), 404),
  )

const coinAmountField = z
  .number({ error: 'Amount must be a whole number' })
  .int('Amount must be a whole number')
  .min(1, 'Amount must be at least 1')
  .max(1000, 'Amount must be at most 1000')

const approveInput = z.strictObject({
  amount: coinAmountField,
})

const rejectInput = z.strictObject({
  blockUser: z.boolean({ error: 'blockUser must be a boolean' }),
})

const manualGrantInput = z.strictObject({
  email: z
    .string({ error: 'Email is required' })
    .trim()
    .min(1, 'Email is required')
    .max(255, 'Invalid email format')
    .refine((value) => !/[\s\p{Cc}]/u.test(value) && /^[^@]+@[^@]+$/.test(value), {
      message: 'Invalid email format',
    }),
  amount: coinAmountField,
})

const requestListQuery = z.object({
  status: z.enum(['PENDING', 'DECIDED']).default('PENDING'),
})

function authenticatedUserId(c: Context): number {
  const userId = c.var.principal?.userId
  if (userId === undefined) throw new Error('An authenticated action has no user id')
  return userId
}

/**
 * Generator and Magic Coins HTTP routes:
 *
 * - `POST /api/generator/generate` behind guest-capable CSRF protection and per-IP/global rate limit.
 * - `GET /api/magic-coins/request-status` and `POST /api/magic-coins/requests` for signed-in users
 *   whose coins are exhausted (balances themselves are deliberately no longer exposed).
 * - `POST /api/admin/images/background-removal` for the admin's image editor.
 * - `/api/admin/magic-coins/*` (session-only `requireAdmin`, `Cache-Control: no-store`) for reviewing
 *   refill requests, granting coins manually, and managing blocked accounts.
 */
export function generatorRoutes({
  generator,
  removeBackground,
  requests,
  guestTokens,
  rateLimit,
  requestRateLimit,
}: {
  generator: GeneratorService
  removeBackground: BackgroundRemover
  requests: MagicCoinRequestsService
  guestTokens: Auth['guestTokens']
  rateLimit: MiddlewareHandler
  requestRateLimit: MiddlewareHandler
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

  const customerMagicCoins = new Hono()
  customerMagicCoins.get('/request-status', requireAuthenticated, noStore, async (c) => {
    const status = await requests.requestStatus(authenticatedUserId(c))
    return c.json({ status })
  })
  customerMagicCoins.post(
    '/requests',
    requireAuthenticated,
    noStore,
    requestRateLimit,
    async (c) => {
      const outcome = await requests.submitRequest(authenticatedUserId(c))
      switch (outcome.kind) {
        case 'created':
          return c.json(outcome.request, 201)
        case 'blocked':
          return c.json(
            apiError(
              'Magic Coin requests are not available for this account',
              {},
              REQUEST_BLOCKED_CODE,
            ),
            403,
          )
        case 'alreadyPending':
          return c.json(
            apiError('A Magic Coin request is already pending', {}, REQUEST_ALREADY_PENDING_CODE),
            409,
          )
        case 'coinsAvailable':
          return c.json(
            apiError('You still have Magic Coins available', {}, COINS_AVAILABLE_CODE),
            409,
          )
        default:
          return assertNever(outcome)
      }
    },
  )

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

  // Session-only admin routes: carries customer e-mails and grants paid AI generation budget.
  const adminMagicCoins = new Hono()
  adminMagicCoins.use(requireAdmin, noStore)
  adminMagicCoins.get(
    '/requests',
    zValidator('query', requestListQuery, (result, c) =>
      result.success ? undefined : c.json(validationFailed(result.error), 400),
    ),
    async (c) => c.json(await requests.listRequests(c.req.valid('query').status)),
  )
  adminMagicCoins.post(
    '/requests/:id/approve',
    idParam('id', REQUEST_NOT_FOUND),
    jsonBody(approveInput),
    async (c) => {
      const outcome = await requests.approveRequest(
        c.req.valid('param').id,
        c.req.valid('json').amount,
        authenticatedUserId(c),
      )
      switch (outcome.kind) {
        case 'approved':
          return c.json({
            id: outcome.id,
            status: 'APPROVED' as const,
            grantedCoins: outcome.grantedCoins,
            newBalance: outcome.newBalance,
            decidedAt: outcome.decidedAt,
          })
        case 'notFound':
          return c.json(apiError(REQUEST_NOT_FOUND), 404)
        case 'alreadyDecided':
          return c.json(apiError('Magic Coin request has already been decided'), 409)
        default:
          return assertNever(outcome)
      }
    },
  )
  adminMagicCoins.post(
    '/requests/:id/reject',
    idParam('id', REQUEST_NOT_FOUND),
    jsonBody(rejectInput),
    async (c) => {
      const outcome = await requests.rejectRequest(
        c.req.valid('param').id,
        c.req.valid('json').blockUser,
        authenticatedUserId(c),
      )
      switch (outcome.kind) {
        case 'rejected':
          return c.json({
            id: outcome.id,
            status: 'REJECTED' as const,
            blockedUser: outcome.blockedUser,
            decidedAt: outcome.decidedAt,
          })
        case 'notFound':
          return c.json(apiError(REQUEST_NOT_FOUND), 404)
        case 'alreadyDecided':
          return c.json(apiError('Magic Coin request has already been decided'), 409)
        default:
          return assertNever(outcome)
      }
    },
  )
  adminMagicCoins.post('/grants', jsonBody(manualGrantInput), async (c) => {
    const { email, amount } = c.req.valid('json')
    const outcome = await requests.grantManually(email, amount, authenticatedUserId(c))
    switch (outcome.kind) {
      case 'granted':
        return c.json({
          userId: outcome.userId,
          userEmail: outcome.userEmail,
          grantedCoins: outcome.grantedCoins,
          newBalance: outcome.newBalance,
        })
      case 'userNotFound':
        return c.json(apiError('User not found'), 404)
      default:
        return assertNever(outcome)
    }
  })
  adminMagicCoins.get('/blocks', async (c) => c.json(await requests.listBlocks()))
  adminMagicCoins.delete('/blocks/:userId', idParam('userId', BLOCK_NOT_FOUND), async (c) => {
    const removed = await requests.unblockUser(c.req.valid('param').userId)
    return removed ? c.body(null, 204) : c.json(apiError(BLOCK_NOT_FOUND), 404)
  })

  const routes = new Hono()
  routes.route('/api/generator', generation)
  routes.route('/api/magic-coins', customerMagicCoins)
  routes.route('/api/admin/images/background-removal', backgroundRemoval)
  routes.route('/api/admin/magic-coins', adminMagicCoins)
  return routes
}
