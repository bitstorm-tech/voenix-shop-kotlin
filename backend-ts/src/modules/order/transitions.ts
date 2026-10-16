import type { Kysely, Transaction } from 'kysely'
import { requestProduction } from '@/modules/production'
import type { PromotionCodes } from '@/modules/promotion'
import { write, type Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import { lockOrder, setOrderStatus } from './repository'

/** What the paid transition did: the first two are successes, the last two changed nothing. */
export type PaidResult = 'paid' | 'alreadyPaid' | 'cancelled' | 'notFound'

/** What a cancellation did: `paid` is the refusal, a paid order is never taken back. */
export type CancelResult = 'cancelled' | 'alreadyCancelled' | 'paid' | 'notFound'

export type OrderTransitions = ReturnType<typeof createOrderTransitions>

/**
 * The three writes that move an order after its placement. Each takes the order row lock before
 * it reads the status it decides from, so a confirmation, a cancellation, and a payment ending of
 * one order queue up and the order ends in exactly one status with the side effects that status
 * implies. The lock order is always orders, then promotions. `markPaid` and `cancel` run in the
 * caller's transaction; a caller that already holds the order lock simply keeps it.
 */
export function createOrderTransitions({
  db,
  promotionCodes,
}: {
  db: Kysely<Tables>
  promotionCodes: Pick<PromotionCodes, 'redeem' | 'release'>
}) {
  const logger = createLogger('order')

  return {
    /**
     * The paid transition, inside the caller's transaction: lock, redeem the promotion, mark the
     * order `PAID`, request production. Everything commits together or not at all, and a second
     * call finds the order `PAID` and does nothing twice. A redemption the promotion refuses (its
     * limit ran out while the customer paid) does not stop the order from being paid: the money
     * has moved, so the order is `PAID` without a redemption and the reason is logged.
     */
    markPaid: async (trx: Transaction<Tables>, orderId: number): Promise<PaidResult> => {
      const locked = await lockOrder(trx, orderId)
      if (locked === undefined) return 'notFound'
      if (locked.status === 'PAID') return 'alreadyPaid'
      if (locked.status === 'CANCELLED') return 'cancelled'
      if (locked.promotionId !== null) {
        const redemption = await promotionCodes.redeem(
          trx,
          locked.promotionId,
          orderId,
          locked.cartId,
          locked.userId,
        )
        if (redemption.kind !== 'applicable') {
          logger.warn(
            { orderId, reason: redemption.kind },
            'The order was paid without redeeming its promotion',
          )
        }
      }
      await setOrderStatus(trx, orderId, 'PAID')
      await requestProduction(trx, orderId)
      return 'paid'
    },

    /**
     * Gives up an order whose payment could not even be started, inside the caller's transaction:
     * `CANCELLED`, out of the one-live-order-per-cart index, and its promotion reservation released
     * in the same commit. The payment start calls it under the order lock it already holds, so a
     * second start queued on that lock finds the order cancelled instead of paying for it. A
     * `PAID` order is refused, because the money moved and production is requested.
     */
    cancel: async (trx: Transaction<Tables>, orderId: number): Promise<CancelResult> => {
      const locked = await lockOrder(trx, orderId)
      if (locked === undefined) {
        logger.warn({ orderId }, 'The cancellation found no such order')
        return 'notFound'
      }
      if (locked.status === 'PAID') {
        logger.warn({ orderId }, 'The cancellation changed nothing: the order is PAID')
        return 'paid'
      }
      if (locked.status === 'CANCELLED') return 'alreadyCancelled'
      if (locked.promotionId !== null) await promotionCodes.release(trx, locked.cartId)
      await setOrderStatus(trx, orderId, 'CANCELLED')
      return 'cancelled'
    },

    /**
     * A payment ended terminally while the order stays `PENDING` (the customer may pay again):
     * the promotion capacity the cart holds is released. The order lock keeps the release from
     * overtaking a running confirmation whose redemption is about to consume that reservation. An
     * unknown order, one without a promotion, and a reservation already gone are the same no-op.
     */
    paymentEnded: (orderId: number): Promise<void> =>
      write(db, async (trx) => {
        const locked = await lockOrder(trx, orderId)
        if (locked?.promotionId == null) return
        await promotionCodes.release(trx, locked.cartId)
      }),
  }
}
