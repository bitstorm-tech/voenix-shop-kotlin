import { randomUUID } from 'node:crypto'
import type { Kysely } from 'kysely'
import { write, type Tables } from '@/platform/db/database'
import { createLogger, errorClass } from '@/platform/logging'
import { lockOrder, type PayableOrder } from '../repository'
import type { OrderTransitions } from '../transitions'
import type { MolliePayment, MolliePayments, PaymentStatus } from './mollie'
import {
  insertPayment,
  isLive,
  isUnderWay,
  livePayment,
  moveStatus,
  type PaymentRepository,
  type StoredPayment,
} from './repository'

/**
 * What one webhook delivery did. Every outcome but `providerUnavailable` answers Mollie `200`:
 * a redelivery would change nothing about it, and what a human has to settle is in the log.
 */
export type PaymentConfirmation =
  /** The reported status is stored (or already was, or the row is final) and it is not `PAID`. */
  | 'recorded'
  /** `PAID`, and the order is paid now or was already. */
  | 'confirmed'
  /** `PAID`, but Mollie's amount differs from the stored one, or the order is `CANCELLED`. */
  | 'notConfirmed'
  /** A payment Mollie knows and this backend never created. */
  | 'unknownPayment'
  /** Mollie could not be reached or said nothing usable; a redelivery may succeed. */
  | 'providerUnavailable'

export type PaymentService = ReturnType<typeof createPaymentService>

/** A payment under way for longer than this is asked about by the reconciliation. */
const RECONCILE_AFTER_MILLIS = 15 * 60_000

/**
 * The payment half of the order module: starting a payment, the webhook, and the `paymentStatus`
 * an order answer carries. It never decides an order status itself; it asks the order transitions.
 */
export function createPaymentService({
  db,
  repository,
  mollie,
  transitions,
}: {
  db: Kysely<Tables>
  repository: PaymentRepository
  mollie: MolliePayments
  transitions: OrderTransitions
}) {
  const logger = createLogger('payment')

  /**
   * Hands a paid payment to the order and records `PAID` on the payment in the same transaction,
   * so a paid transition that fails (the redemption, the production request) leaves the payment
   * under way and the next webhook or refresh confirms the order; a payment stored as `PAID` is
   * never asked about again. The order lock comes first, then the payment row. `true` when the
   * order is paid now or was already.
   */
  const confirmOrder = async (stored: StoredPayment): Promise<boolean> => {
    const result = await write(db, async (trx) => {
      const paid = await transitions.markPaid(trx, stored.orderId)
      await moveStatus(trx, stored.paymentId, 'PAID')
      return paid
    })
    if (result === 'paid' || result === 'alreadyPaid') return true
    logger.error(
      { paymentId: stored.paymentId, orderId: stored.orderId, amountCents: stored.amountCents },
      result === 'cancelled'
        ? 'A payment was paid for a CANCELLED order: the amount has to be refunded by hand'
        : 'A payment was paid for an order that does not exist',
    )
    return false
  }

  /**
   * What the webhook and the detail refresh both do with a status Mollie reported. A status the
   * row already has, or a row that is final (`PAID` or ended), is written over by nothing: a
   * delayed `OPEN` arriving after `PAID` was recorded leaves `PAID`. A terminal status releases the
   * reservation, on a redelivery again: the release is idempotent, and the redelivery is the only
   * retry a release lost to a failure has.
   */
  const applyReported = async (
    stored: StoredPayment,
    reported: MolliePayment,
  ): Promise<PaymentConfirmation> => {
    if (reported.status === 'PAID') {
      if (reported.amountCents === stored.amountCents) {
        return (await confirmOrder(stored)) ? 'confirmed' : 'notConfirmed'
      }
      logger.error(
        {
          paymentId: stored.paymentId,
          orderId: stored.orderId,
          reportedCents: reported.amountCents,
          storedCents: stored.amountCents,
        },
        'Mollie reports a payment as paid with another amount than the shop asked for: ' +
          'the order is not confirmed and needs a human',
      )
      await write(db, (trx) => moveStatus(trx, stored.paymentId, 'PAID'))
      return 'notConfirmed'
    }
    const recorded =
      stored.status === reported.status ||
      (await write(db, (trx) => moveStatus(trx, stored.paymentId, reported.status)))
    if (recorded && !isLive(reported.status)) await transitions.paymentEnded(stored.orderId)
    return 'recorded'
  }

  /**
   * The status of one order's payment, asked of Mollie while the payment is still under way: the
   * fallback for a webhook that never arrived, which may confirm the order through the same path
   * the webhook takes. A provider that cannot answer degrades to the stored status.
   */
  const refreshed = async (orderId: number): Promise<PaymentStatus | null> => {
    const stored = (await repository.currentPayments([orderId])).get(orderId)
    if (stored === undefined) return null
    if (!isUnderWay(stored.status)) return stored.status
    const reported = await mollie.find(stored.molliePaymentId)
    if (reported === null) {
      logger.warn(
        { paymentId: stored.paymentId, orderId },
        'Mollie said nothing usable: the order is answered with its stored payment status',
      )
      return stored.status
    }
    await applyReported(stored, reported)
    return reported.status
  }

  /** Closes a provider payment nobody will be sent to; a refusal is worth a line. */
  const cancelUnused = async (molliePaymentId: string, orderId: number) => {
    if (!(await mollie.cancel(molliePaymentId))) {
      logger.warn(
        { molliePaymentId, orderId },
        'An unused payment could not be cancelled at Mollie: it may stay open',
      )
    }
  }

  return {
    /**
     * Starts the payment of `order`, or answers the one it already has, and returns the URL the
     * customer is sent to, or `null` when no payment was started. One transaction holds the order
     * row lock around "live payment? → Mollie create → insert", so concurrent starts of one order
     * queue on the lock and the second answers the first one's stored URL without a provider
     * call. A provider that refuses cancels the order (and releases its reservation) under that
     * same lock, so a start queued behind the refusal finds the order cancelled instead of paying
     * for it; a database failure after Mollie created the payment cancels that payment before the
     * error travels on. The lock is held during the provider call, which the adapter bounds at
     * eight seconds.
     */
    startPayment: async (order: PayableOrder): Promise<string | null> => {
      if (order.totalCents <= 0) throw new Error('A payment amount must be greater than zero')
      const attempt: { created: MolliePayment | null } = { created: null }
      let outcome: { kind: 'started'; checkoutUrl: string } | { kind: 'refused' | 'notPending' }
      try {
        outcome = await write(db, async (trx) => {
          const locked = await lockOrder(trx, order.orderId)
          if (locked?.status !== 'PENDING') return { kind: 'notPending' }
          const live = await livePayment(trx, order.orderId)
          if (live !== undefined) return { kind: 'started', checkoutUrl: live.checkoutUrl }
          const created = await mollie.create(order, randomUUID())
          if (created?.checkoutUrl == null) {
            await transitions.cancel(trx, order.orderId)
            return { kind: 'refused' }
          }
          attempt.created = created
          await insertPayment(trx, {
            orderId: order.orderId,
            molliePaymentId: created.id,
            status: created.status,
            amountCents: order.totalCents,
            checkoutUrl: created.checkoutUrl,
          })
          return { kind: 'started', checkoutUrl: created.checkoutUrl }
        })
      } catch (error) {
        if (attempt.created !== null) await cancelUnused(attempt.created.id, order.orderId)
        throw error
      }
      switch (outcome.kind) {
        case 'started':
          return outcome.checkoutUrl
        case 'notPending':
          return null
        case 'refused':
          logger.warn(
            { orderId: order.orderId },
            'Mollie started no payment: the order is cancelled and no checkout URL exists',
          )
          return null
      }
    },

    /**
     * Applies what Mollie currently says about one payment. The status is fetched first and the
     * body is never trusted, so a forged `status=PAID` changes nothing, and an id Mollie does not
     * know is a `providerUnavailable` rather than a probe of which ids this shop has.
     */
    confirm: async (molliePaymentId: string): Promise<PaymentConfirmation> => {
      const reported = await mollie.find(molliePaymentId)
      if (reported === null) return 'providerUnavailable'
      const stored = await repository.paymentByMollieId(molliePaymentId)
      if (stored === undefined) {
        logger.warn('A webhook named a Mollie payment this backend never created')
        return 'unknownPayment'
      }
      return applyReported(stored, reported)
    },

    /** The stored status of every order that has a payment, in one read, never a provider call. */
    stored: async (orderIds: Iterable<number>): Promise<Map<number, PaymentStatus>> => {
      const payments = await repository.currentPayments(orderIds)
      return new Map([...payments].map(([orderId, payment]) => [orderId, payment.status]))
    },

    refreshed,

    /**
     * The operations worker's first step: every payment under way for more than fifteen minutes,
     * oldest first, goes through `refreshed` once, so a lost webhook is repaired without the
     * customer opening their order. A provider that cannot answer is `refreshed`'s warning; any
     * other failure is logged by class and the next payment is asked all the same.
     */
    reconcile: async (now: Date): Promise<void> => {
      const orderIds = await repository.underWayBefore(
        new Date(now.getTime() - RECONCILE_AFTER_MILLIS),
      )
      for (const orderId of orderIds) {
        try {
          await refreshed(orderId)
        } catch (error) {
          logger.error(
            { orderId, errorClass: errorClass(error) },
            'The reconciliation of a payment failed: the next scan asks again',
          )
        }
      }
    },
  }
}
