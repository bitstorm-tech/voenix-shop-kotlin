import { zValidator } from '@hono/zod-validator'
import { Hono, type Context } from 'hono'
import { z } from 'zod'
import { readUploadedImage } from '@/modules/image'
import { toApiError } from '@/modules/promotion'
import { guestCapable, type Auth } from '@/platform/auth/auth'
import { apiError, jsonBody, respondResult } from '@/platform/http/respond'
import { MAXIMUM_LINE_QUANTITY, type CartOwner } from './repository'
import type { AddItemInput, CartService } from './service'

const identifier = (name: string) =>
  z.int({ error: `${name} is required` }).positive(`${name} must be positive`)

const QUANTITY_RANGE = `Quantity must be between 1 and ${String(MAXIMUM_LINE_QUANTITY)}`
const quantity = z
  .int({ error: 'Quantity is required' })
  .min(1, QUANTITY_RANGE)
  .max(MAXIMUM_LINE_QUANTITY, QUANTITY_RANGE)

/**
 * The body of `POST /api/cart/items`. The image is referenced by the id the pre-upload answered,
 * never uploaded here, so a rejected add never leaves a file behind.
 */
export const addCartItemInput = z.strictObject({
  articleId: identifier('ArticleId'),
  variantId: identifier('VariantId'),
  quantity,
  promptId: identifier('PromptId').nullish(),
  imageId: identifier('ImageId').nullish(),
}) satisfies z.ZodType<AddItemInput>

/** The body of `PATCH /api/cart/items/{itemId}`: the new quantity of one line. */
export const cartQuantityInput = z.strictObject({ quantity })

/** The body of `POST /api/cart/promotion`. The length mirrors the `coupon_code` column. */
export const promotionCodeInput = z.strictObject({
  promotionCode: z
    .string({ error: 'PromotionCode is required' })
    .trim()
    .min(1, { error: 'PromotionCode is required', abort: true })
    .max(64, 'PromotionCode must be at most 64 characters'),
})

/** Only a reorder reports a conflict, and it answers that one itself. */
const CART_RESPONSES = { notFound: 'Cart not found', conflict: null }
const REORDER_RESPONSES = { notFound: 'Order item not found', conflict: null }

/** A path id that is not a number never named a line, so it is the same `404` as an unknown one. */
const pathId = z
  .string()
  .regex(/^\d{1,15}$/)
  .transform(Number)
const itemIdParam = zValidator('param', z.object({ itemId: pathId }), (result, c) =>
  result.success ? undefined : c.json(apiError('Cart item not found'), 404),
)
const orderItemIdParam = zValidator('param', z.object({ orderItemId: pathId }), (result, c) =>
  result.success ? undefined : c.json(apiError(REORDER_RESPONSES.notFound), 404),
)

/** The signed-in customer's id, or `null` for a guest. */
const userIdOf = (c: Context): number | null => {
  const userId = Number(c.var.principal?.userId)
  return Number.isSafeInteger(userId) && userId > 0 ? userId : null
}

/**
 * The eight cart routes under `/api/cart`, guest-capable: every mutation needs a valid CSRF pair,
 * from guests and signed-in customers alike. A mutation calls `getOrCreate` and therefore hands
 * out the guest cookie; the read calls `tryGet` and never creates a guest, because looking at a
 * cart must not turn a visitor into a tracked one.
 */
export function cartRoutes(carts: CartService, guestTokens: Auth['guestTokens']): Hono {
  const mutatingOwner = async (c: Context): Promise<CartOwner> => ({
    guestToken: await guestTokens.getOrCreate(c),
    userId: userIdOf(c),
  })

  const cart = new Hono()
  cart.use(guestCapable)

  cart.get('/', async (c) => {
    // Cart contents are per visitor and change constantly; no cache may keep them.
    c.header('Cache-Control', 'no-store')
    return c.json(
      await carts.cart({ guestToken: await guestTokens.tryGet(c), userId: userIdOf(c) }),
    )
  })

  cart.post('/images', async (c) => {
    // A body that is no multipart form has no `file` part either.
    const upload = await readUploadedImage(await c.req.formData().catch(() => new FormData()))
    return respondResult(
      c,
      await carts.uploadPrintImage(await mutatingOwner(c), upload),
      CART_RESPONSES,
      201,
    )
  })

  cart.post('/items', jsonBody(addCartItemInput), async (c) =>
    respondResult(
      c,
      await carts.addItem(await mutatingOwner(c), c.req.valid('json')),
      CART_RESPONSES,
    ),
  )

  cart.patch('/items/:itemId', itemIdParam, jsonBody(cartQuantityInput), async (c) =>
    respondResult(
      c,
      await carts.updateQuantity(
        await mutatingOwner(c),
        c.req.valid('param').itemId,
        c.req.valid('json').quantity,
      ),
      CART_RESPONSES,
    ),
  )

  cart.delete('/items/:itemId', itemIdParam, async (c) =>
    respondResult(
      c,
      await carts.removeItem(await mutatingOwner(c), c.req.valid('param').itemId),
      CART_RESPONSES,
    ),
  )

  // A cart route although it starts from an order, because what it produces is a cart line.
  cart.post('/order-items/:orderItemId', orderItemIdParam, async (c) => {
    const result = await carts.reorder(await mutatingOwner(c), c.req.valid('param').orderItemId)
    if (result.kind === 'conflict') {
      // The code a frontend branches on to offer a fresh upload.
      return c.json(
        apiError(
          'The image of this order item is no longer available',
          {},
          'ORDER_IMAGE_UNAVAILABLE',
        ),
        409,
      )
    }
    return respondResult(c, result, REORDER_RESPONSES)
  })

  cart.post('/promotion', jsonBody(promotionCodeInput), async (c) => {
    const result = await carts.applyPromotion(
      await mutatingOwner(c),
      c.req.valid('json').promotionCode,
    )
    if (result.kind === 'rejected') {
      const { status, body } = toApiError(result.rejection)
      return c.json(body, status)
    }
    return respondResult(c, result, CART_RESPONSES)
  })

  cart.delete('/promotion', async (c) =>
    respondResult(c, await carts.removePromotion(await mutatingOwner(c)), CART_RESPONSES),
  )

  const routes = new Hono()
  routes.route('/api/cart', cart)
  return routes
}
