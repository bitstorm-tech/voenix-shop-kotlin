import type { Kysely, Transaction } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import { apiError, type ApiError } from '@/platform/http/respond'
import {
  countRedemptions,
  countReservations,
  holdReservation,
  insertRedemption,
  lockPromotion,
  normalizedCouponCode,
  promotionByCode,
  promotionsByIds,
  releaseReservation,
  type Discount,
  type StoredPromotion,
} from './repository'

/** What a consumer needs to apply a promotion; no admin bookkeeping. */
export interface ApplicablePromotion {
  id: number
  name: string
  couponCode: string
  discount: Discount
}

/** Why a code cannot be applied, reserved, or redeemed. */
export type PromotionRejection =
  /** No promotion carries this code, or the promotion no longer exists. */
  | { kind: 'invalidCode' }
  /** The promotion is switched off. */
  | { kind: 'inactive' }
  /** The activity window has not begun. */
  | { kind: 'notStarted' }
  /** The activity window has ended. */
  | { kind: 'expired' }
  /** The promotion limits usage per user, and the caller is a guest. */
  | { kind: 'loginRequired' }
  /** The total usage limit is used up. */
  | { kind: 'totalExhausted' }
  /** This customer has used up their personal allowance. */
  | { kind: 'perUserExhausted' }

/** The answer of `validate`, `reserve`, and `redeem`. */
export type PromotionCodeResult =
  | { kind: 'applicable'; promotion: ApplicablePromotion }
  | PromotionRejection

/**
 * The status and the stable `code` every consumer answers a rejected coupon with: the cart when the
 * code is entered, the checkout when it is reserved. The message is for a human, the code is for
 * the client.
 */
export function toApiError(rejection: PromotionRejection): {
  status: 400 | 403 | 409
  body: ApiError
} {
  switch (rejection.kind) {
    case 'invalidCode':
      return rejected(400, 'Promotion code is invalid', 'PROMOTION_INVALID_CODE')
    case 'inactive':
      return rejected(400, 'Promotion code is not active', 'PROMOTION_INACTIVE')
    case 'notStarted':
      return rejected(400, 'Promotion code is not valid yet', 'PROMOTION_NOT_STARTED')
    case 'expired':
      return rejected(400, 'Promotion code has expired', 'PROMOTION_EXPIRED')
    case 'loginRequired':
      return rejected(
        403,
        'Promotion code requires a signed-in customer',
        'PROMOTION_LOGIN_REQUIRED',
      )
    case 'totalExhausted':
      return rejected(
        409,
        'Promotion code has reached its usage limit',
        'PROMOTION_TOTAL_EXHAUSTED',
      )
    case 'perUserExhausted':
      return rejected(
        409,
        'Promotion code has reached your usage limit',
        'PROMOTION_PER_USER_EXHAUSTED',
      )
  }
}

function rejected(status: 400 | 403 | 409, message: string, code: string) {
  return { status, body: apiError(message, {}, code) }
}

export type PromotionCodes = ReturnType<typeof createPromotionCodes>

/**
 * The coupon-code capability Cart, Checkout, and Order consume; the only place the code rules live.
 * `validate` answers whether a code may be entered, `reserve` holds its capacity while the checkout
 * runs, and `redeem` turns that hold into a recorded redemption, or `release` gives it back. A cart
 * holds at most one reservation, which is why the lifecycle is keyed on the cart id. Database
 * failures are exceptions; the consumer answers them with its own error policy.
 */
export function createPromotionCodes(db: Kysely<Tables>) {
  return {
    /**
     * Resolves a customer-entered code (trimmed, case-insensitive) for the cart `cartId` and the
     * optional signed-in `userId`. The cart's own reservation is not counted, so the cart holding
     * the last unit may re-apply its code while every other cart is told it is exhausted. Advisory:
     * only `reserve` and, decisively, `redeem` decide.
     */
    validate: (code: string, cartId: number, userId: number | null): Promise<PromotionCodeResult> =>
      read(db, async (trx) => {
        const promotion = await promotionByCode(trx, normalizedCouponCode(code))
        if (promotion === undefined) return { kind: 'invalidCode' }
        return (
          availabilityFailure(promotion, new Date()) ??
          (await usageFailure(trx, promotion, userId, cartId)) ??
          applicable(promotion)
        )
      }),

    /**
     * Holds the capacity of `promotionId` for `cartId` in the caller's transaction (checkout TX1),
     * under the promotion row lock, re-checking everything `validate` checks. Re-reserving the same
     * cart overwrites its row instead of taking a second unit. The hold has no expiry: it ends with
     * `redeem` or `release`.
     */
    reserve: async (
      trx: Transaction<Tables>,
      promotionId: number,
      cartId: number,
      userId: number | null,
    ): Promise<PromotionCodeResult> => {
      const promotion = await lockPromotion(trx, promotionId)
      if (promotion === undefined) return { kind: 'invalidCode' }
      const failure =
        availabilityFailure(promotion, new Date()) ??
        (await usageFailure(trx, promotion, userId, cartId))
      if (failure !== undefined) return failure
      await holdReservation(trx, promotionId, cartId, userId)
      return applicable(promotion)
    },

    /**
     * Records the redemption of `promotionId` by `orderId` in the caller's transaction (the paid
     * transition), under the promotion row lock, and consumes the reservation of `cartId` in the
     * same breath. Only the usage limits are re-checked: a promotion that expires while the
     * customer pays is still redeemed. The unique `order_id` makes a second redemption of the same
     * order fail with a `23505` that aborts the caller's transaction.
     */
    redeem: async (
      trx: Transaction<Tables>,
      promotionId: number,
      orderId: number,
      cartId: number,
      userId: number | null,
    ): Promise<PromotionCodeResult> => {
      const promotion = await lockPromotion(trx, promotionId)
      if (promotion === undefined) return { kind: 'invalidCode' }
      const failure = await usageFailure(trx, promotion, userId, cartId)
      if (failure !== undefined) return failure
      await insertRedemption(trx, promotionId, orderId, userId)
      await releaseReservation(trx, cartId)
      return applicable(promotion)
    },

    /**
     * Gives the reservation of `cartId` back in the caller's transaction, so an order's cancel or
     * payment-ended write and the release commit together (a cart that removes its own coupon
     * opens a transaction of its own). Idempotent; no promotion lock, because giving capacity back
     * cannot overshoot a limit.
     */
    release: (trx: Transaction<Tables>, cartId: number): Promise<void> =>
      releaseReservation(trx, cartId),

    /**
     * The promotions with these ids as they are configured now, keyed by id; unknown ids are absent
     * and no availability rule applies. An empty list touches no database.
     */
    find: async (ids: Iterable<number>): Promise<Map<number, ApplicablePromotion>> => {
      const wanted = [...ids]
      if (wanted.length === 0) return new Map()
      const promotions = await read(db, (trx) => promotionsByIds(trx, wanted))
      return new Map(promotions.map((promotion) => [promotion.id, applicable(promotion).promotion]))
    },
  }
}

function applicable(promotion: StoredPromotion) {
  const { id, name, couponCode, discount } = promotion
  return { kind: 'applicable', promotion: { id, name, couponCode, discount } } as const
}

/**
 * Switched off or outside the activity window at `now`; both boundaries belong to the window.
 * `validate` and `reserve` apply it, `redeem` deliberately does not.
 */
function availabilityFailure(
  promotion: StoredPromotion,
  now: Date,
): PromotionRejection | undefined {
  if (!promotion.isActive) return { kind: 'inactive' }
  if (promotion.startsAt !== null && now < promotion.startsAt) return { kind: 'notStarted' }
  if (promotion.endsAt !== null && now > promotion.endsAt) return { kind: 'expired' }
  return undefined
}

/**
 * The usage limits, shared by all three checks so they cannot drift apart. Usage is the recorded
 * redemptions plus the reservations of every cart except `cartId`, the caller's own hold. The order
 * is behaviour: a guest facing a per-user limit learns that a login is required even when the total
 * limit is already exhausted. A count is only read when a limit depends on it.
 */
async function usageFailure(
  trx: Transaction<Tables>,
  promotion: StoredPromotion,
  userId: number | null,
  cartId: number,
): Promise<PromotionRejection | undefined> {
  const usage = async (byUser?: number) =>
    (await countRedemptions(trx, promotion.id, byUser)) +
    (await countReservations(trx, promotion.id, cartId, byUser))

  const { usageLimitTotal, usageLimitPerUser } = promotion
  if (usageLimitPerUser !== null && userId === null) return { kind: 'loginRequired' }
  if (usageLimitTotal !== null && (await usage()) >= usageLimitTotal) {
    return { kind: 'totalExhausted' }
  }
  if (usageLimitPerUser !== null && userId !== null && (await usage(userId)) >= usageLimitPerUser) {
    return { kind: 'perUserExhausted' }
  }
  return undefined
}
