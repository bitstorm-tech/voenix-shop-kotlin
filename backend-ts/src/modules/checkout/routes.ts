import { zValidator } from '@hono/zod-validator'
import { Hono, type Context } from 'hono'
import { z } from 'zod'
import { toApiError } from '@/modules/promotion'
import { guestCapable, type Auth } from '@/platform/auth/auth'
import { apiError, jsonBody } from '@/platform/http/respond'
import { assertNever } from '@/platform/operation'
import type { CheckoutInput, CheckoutResult, CheckoutService } from './service'

const text = (maximum: number) =>
  z
    .string({ error: 'Must not be blank' })
    .trim()
    .min(1, { error: 'Must not be blank', abort: true })
    .max(maximum, `Must be at most ${String(maximum)} characters`)

/** The bounds are the ones the order columns hold. The country is checked for its shape only. */
const addressShape = {
  firstName: text(100),
  lastName: text(100),
  street: text(200),
  houseNumber: text(20),
  postalCode: text(10),
  city: text(100),
  country: z
    .string({ error: 'Must be a two-letter code' })
    .trim()
    .regex(/^[A-Za-z]{2}$/, 'Must be a two-letter code')
    .toUpperCase(),
}

/** Deliberately permissive: an address is rejected only when it cannot be an address at all. */
const EMAIL_PATTERN = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/

/**
 * The Vue store serializes `email` and `phone` on the billing address too. They are declared so
 * the strict schema accepts the request, and ignored: the contact lives on the shipping address.
 */
const ignored = z.unknown().optional()

/**
 * The body of `POST /api/checkout`. The contact fields sit on the shipping address only. A blank
 * `phone` is no phone at all (the store always sends the field), and `billingAddress: null` is the
 * customer saying "same address".
 */
export const checkoutInput = z.strictObject({
  shippingAddress: z.strictObject(
    {
      ...addressShape,
      email: z
        .string({ error: 'Email is required' })
        .trim()
        .min(1, { error: 'Email is required', abort: true })
        .max(255, { error: 'Must be at most 255 characters', abort: true })
        .regex(EMAIL_PATTERN, 'Must be a valid email address'),
      phone: z
        .string()
        .trim()
        .max(50, 'Must be at most 50 characters')
        .nullish()
        .transform((phone) => (phone ? phone : null)),
    },
    { error: 'Shipping address is required' },
  ),
  billingAddress: z
    .strictObject({ ...addressShape, email: ignored, phone: ignored })
    .nullable()
    .default(null),
}) satisfies z.ZodType<CheckoutInput>

const ORDER_NOT_FOUND = 'Order not found'

/** An order id that is not a number names no order, which is the same answer as a foreign one. */
const orderIdParam = zValidator(
  'param',
  z.object({
    orderId: z
      .string()
      .regex(/^\d{1,15}$/)
      .transform(Number),
  }),
  (result, c) => (result.success ? undefined : c.json(apiError(ORDER_NOT_FOUND), 404)),
)

/**
 * The two checkout routes under `/api/checkout`, guest-capable. Both answers carry
 * `Cache-Control: no-store` (set in the handler, so a CSRF rejection or a validation failure is
 * answered without it), and neither hands out a guest cookie: the token is read, never minted.
 */
export function checkoutRoutes(checkouts: CheckoutService, guestTokens: Auth['guestTokens']): Hono {
  const checkout = new Hono()
  checkout.use(guestCapable)

  // A completed checkout is a created order: `201` and the `Location` of the order itself.
  checkout.post('/', jsonBody(checkoutInput), async (c) => {
    c.header('Cache-Control', 'no-store')
    const result = await checkouts.checkout(
      await guestTokens.tryGet(c),
      c.var.principal?.userId ?? null,
      c.req.valid('json'),
    )
    if (result.kind !== 'started') return respondFailure(c, result)
    c.header('Location', `/api/orders/${String(result.orderId)}`)
    return c.json({ orderId: result.orderId, checkoutUrl: result.checkoutUrl }, 201)
  })

  // The retry has no body: everything the payment needs is what the order stored. It creates
  // nothing, so its answer is a plain `200`.
  checkout.post('/orders/:orderId/payment', orderIdParam, async (c) => {
    c.header('Cache-Control', 'no-store')
    const result = await checkouts.startPayment(
      c.req.valid('param').orderId,
      await guestTokens.tryGet(c),
      c.var.principal?.userId ?? null,
    )
    if (result.kind !== 'started') return respondFailure(c, result)
    return c.json({ orderId: result.orderId, checkoutUrl: result.checkoutUrl })
  })

  const routes = new Hono()
  routes.route('/api/checkout', checkout)
  return routes
}

/**
 * The one error table of this module. The promotion reasons are not translated here: the mapping
 * is the promotion module's `toApiError`, so a coupon rejected while it is entered into the cart
 * and the same coupon rejected while the checkout reserves it reach the customer as one answer.
 */
function respondFailure(c: Context, result: Exclude<CheckoutResult, { kind: 'started' }>) {
  switch (result.kind) {
    case 'emptyCart':
      return c.json(apiError('Your cart is empty', {}, 'CART_EMPTY'), 400)
    // The two refusals the customer can fix at the form, in the shape a rejected body has, so a
    // frontend that highlights invalid fields needs no new branch.
    case 'shippingCountryUnavailable':
      return fieldError(c, 'shippingAddress.country', 'We do not ship to this country')
    case 'phoneRequired':
      return fieldError(
        c,
        'shippingAddress.phone',
        'Phone is required for orders containing print-on-demand articles',
      )
    case 'promotionRejected': {
      const { status, body } = toApiError(result.rejection)
      return c.json(body, status)
    }
    case 'itemUnavailable':
      return c.json(
        apiError('An item in your cart is no longer available', {}, 'CART_ITEM_UNAVAILABLE'),
        409,
      )
    case 'totalTooLarge':
      return c.json(apiError('Your cart total is too large', {}, 'CART_TOTAL_TOO_LARGE'), 409)
    case 'paymentNotStarted':
      return c.json(apiError('The payment could not be started', {}, 'PAYMENT_NOT_STARTED'), 502)
    case 'orderNotFound':
      return c.json(apiError(ORDER_NOT_FOUND), 404)
    case 'alreadyPaid':
      return c.json(apiError('This order has already been paid', {}, 'ORDER_ALREADY_PAID'), 409)
    case 'notPayable':
      return c.json(apiError('This order cannot be paid', {}, 'ORDER_NOT_PAYABLE'), 409)
    default:
      return assertNever(result)
  }
}

const fieldError = (c: Context, field: string, message: string) =>
  c.json(apiError('Validation failed', { [field]: [message] }), 400)
