import { Hono } from 'hono'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { integerParam, jsonBody, respondFailure, respondResult } from '@/platform/http/respond'
import type { PromotionInput, PromotionRepository } from './repository'

const MAXIMUM_FIXED_AMOUNT_CENTS = 9_999_999_999

const requiredText = (displayName: string, maximumLength: number) =>
  z
    .string({ error: `${displayName} is required` })
    .trim()
    .min(1, `${displayName} is required`)
    .max(maximumLength, `${displayName} must be at most ${String(maximumLength)} characters`)

const timestamp = (displayName: string) =>
  z.iso
    .datetime({ offset: true, error: `${displayName} must be an ISO-8601 timestamp` })
    .transform((text) => new Date(text))
    .nullable()
    .default(null)

const usageLimit = (displayName: string) =>
  z
    .int32({ error: `${displayName} must be an integer` })
    .positive(`${displayName} must be positive`)
    .nullable()
    .default(null)

/**
 * Runs a cross-field rule only when the body is an object and the fields it reads passed their own
 * rules. A root issue (a `null`, an array, a number) has an empty path and stops every such rule.
 */
const whenValid =
  (...fields: string[]) =>
  (payload: z.core.ParsePayload) =>
    !payload.issues.some((issue) => {
      const field = issue.path?.[0]
      return field === undefined || fields.some((name) => name === field)
    })

/**
 * The create and update body, flat (the response nests the discount). Every field but `isActive`
 * may be omitted or `null`; a missing `isActive` is `false`, so a forgotten switch leaves the
 * promotion off. Field names and messages are those of the Kotlin `PromotionInput.validate()`.
 */
export const promotionInput = z
  .strictObject({
    name: requiredText('Name', 255),
    couponCode: requiredText('CouponCode', 64),
    discountType: z.string({ error: 'DiscountType is required' }).pipe(
      z.enum(['PERCENTAGE', 'FIXED_AMOUNT'], {
        error: 'DiscountType must be PERCENTAGE or FIXED_AMOUNT',
      }),
    ),
    discountValue: z
      .number({ error: 'DiscountValue is required' })
      .positive('DiscountValue must be positive'),
    startsAt: timestamp('StartsAt'),
    endsAt: timestamp('EndsAt'),
    usageLimitTotal: usageLimit('UsageLimitTotal'),
    usageLimitPerUser: usageLimit('UsageLimitPerUser'),
    isActive: z.boolean({ error: 'IsActive must be a boolean' }).default(false),
  })
  .superRefine(
    ({ discountType, discountValue }, ctx) => {
      const message = discountValueFailure(discountType, discountValue)
      if (message !== undefined) ctx.addIssue({ code: 'custom', path: ['discountValue'], message })
    },
    { when: whenValid('discountType', 'discountValue') },
  )
  .superRefine(
    ({ startsAt, endsAt }, ctx) => {
      if (startsAt === null || endsAt === null || startsAt <= endsAt) return
      for (const field of ['startsAt', 'endsAt'])
        ctx.addIssue({
          code: 'custom',
          path: [field],
          message: 'StartsAt must not be after EndsAt',
        })
    },
    { when: whenValid('startsAt', 'endsAt') },
  ) satisfies z.ZodType<PromotionInput>

/**
 * The value stays exactly what the `numeric(12,2)` column stores: a percentage has at most two
 * decimals, a fixed amount is whole cents within the column's capacity.
 */
function discountValueFailure(
  discountType: PromotionInput['discountType'],
  value: number,
): string | undefined {
  if (discountType === 'PERCENTAGE') {
    if (value > 100) return 'DiscountValue must be at most 100 for percentage promotions'
    if (Number(value.toFixed(2)) !== value)
      return 'DiscountValue must have at most 2 decimal places for percentage promotions'
    return undefined
  }
  if (!Number.isInteger(value))
    return 'DiscountValue must be whole cents for fixed amount promotions'
  if (value > MAXIMUM_FIXED_AMOUNT_CENTS)
    return `DiscountValue must be at most ${String(MAXIMUM_FIXED_AMOUNT_CENTS)} for fixed amount promotions`
  return undefined
}

const PROMOTION_RESPONSES = {
  notFound: 'Promotion not found',
  conflict: 'Coupon code is already in use',
}
const UPDATE_RESPONSES = {
  ...PROMOTION_RESPONSES,
  conflict: 'Coupon code is already in use or the promotion is locked',
}
const DELETE_RESPONSES = {
  ...PROMOTION_RESPONSES,
  conflict: 'Promotion is still in use and cannot be deleted',
}
const promotionId = integerParam('id', 'Invalid promotion id')

export function promotionRoutes(promotions: PromotionRepository): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => c.json(await promotions.list()))
  admin.post('/', jsonBody(promotionInput), async (c) => {
    const result = await promotions.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, PROMOTION_RESPONSES)
    c.header('Location', `/api/admin/promotions/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  admin.get('/:id', promotionId, async (c) =>
    respondResult(c, await promotions.get(c.req.valid('param').id), PROMOTION_RESPONSES),
  )
  admin.put('/:id', promotionId, jsonBody(promotionInput), async (c) =>
    respondResult(
      c,
      await promotions.update(c.req.valid('param').id, c.req.valid('json')),
      UPDATE_RESPONSES,
    ),
  )
  admin.delete('/:id', promotionId, async (c) => {
    const result = await promotions.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, DELETE_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/promotions', admin)
  return routes
}
