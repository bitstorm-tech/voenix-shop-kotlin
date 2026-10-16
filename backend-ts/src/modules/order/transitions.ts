import type { Transaction } from 'kysely'
import { requestProduction } from '@/modules/production'
import type { PromotionCodes } from '@/modules/promotion'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import { lockOrder, recordEvent, setOrderStatus, type PaidBy } from './repository'

/** What the paid transition did: the first two are successes, the last two changed nothing. */
export type PaidResult = 'paid' | 'alreadyPaid' | 'cancelled' | 'notFound'

/** What a cancellation did: `paid` is the refusal, a paid order is never taken back. */
export type CancelResult = 'cancelled' | 'alreadyCancelled' | 'paid' | 'notFound'

export type OrderTransitions = ReturnType<typeof createOrderTransitions>

/**
 * The three writes that move an order after its placement. Each takes the order row lock before
 * it reads the status it decides from, so a confirmation, a cancellation, and a payment ending of
 * one order queue up and the order ends in exactly one status with the side effects that status
 * implies. The lock order is always orders, then promotions. All three run in the caller's
 * transaction; a caller that already holds the order lock simply keeps it.
 */
export function createOrderTransitions({
  promotionCodes,
}: {
  promotionCodes: Pick<PromotionCodes, 'redeem' | 'release'>
}) {
  const logger = createLogger('order')

  return {
    /**
     * The paid transition, inside the caller's transaction: lock, redeem the promotion, mark the
     * order `PAID`, record `ORDER_PAID` with `by` in its history, request production. Everything
     * commits together or not at all, and a second call finds the order `PAID` and does nothing
     * twice, an event included. A redemption the promotion refuses (its
     * limit ran out while the customer paid) does not stop the order from being paid: the money
     * has moved, so the order is `PAID` without a redemption and the reason is logged.
     */
    markPaid: async (
      trx: Transaction<Tables>,
      orderId: number,
      by: PaidBy,
    ): Promise<PaidResult> => {
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
      await recordEvent(trx, orderId, { kind: 'ORDER_PAID', ...by })
      await requestProduction(trx, orderId)
      return 'paid'
    },

    /**
     * Gives up an order whose payment could not even be started, inside the caller's transaction:
     * `CANCELLED`, out of the one-live-order-per-cart index, its promotion reservation released and
     * `ORDER_CANCELLED` with cause `PAYMENT_START_REFUSED` recorded in the same commit. The payment start calls it under
     * the order lock it already holds, so a second start queued on that lock finds the order
     * cancelled instead of paying for it. A `PAID` order is refused, because the money moved and
     * production is requested. Only a cancellation that changed the status is recorded.
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
      await recordEvent(trx, orderId, {
        kind: 'ORDER_CANCELLED',
        actorType: 'SYSTEM',
        cause: 'PAYMENT_START_REFUSED',
      })
      return 'cancelled'
    },

    /**
     * A payment ended terminally while the order stays `PENDING` (the customer may pay again):
     * the promotion capacity the cart holds is released, inside the caller's transaction, which
     * also moves the payment, so the release commits with the terminal status or not at all. The
     * order lock keeps the release from overtaking a running confirmation whose redemption is
     * about to consume that reservation. An unknown order, one without a promotion, and a
     * reservation already gone are the same no-op.
     */
    paymentEnded: async (trx: Transaction<Tables>, orderId: number): Promise<void> => {
      const locked = await lockOrder(trx, orderId)
      if (locked?.promotionId == null) return
      await promotionCodes.release(trx, locked.cartId)
    },
  }
}
