import { randomUUID } from 'node:crypto'
import type { Kysely, Transaction } from 'kysely'
import type { PromotionCodes, PromotionRejection } from '@/modules/promotion'
import { write, type Tables } from '@/platform/db/database'
import { createLogger, errorDetails } from '@/platform/logging'
import { conflict, notFound, ok } from '@/platform/operation'
import { lockOrder, recordEvent, type PayableOrder, type PaymentCause } from '../repository'
import type { OrderTransitions } from '../transitions'
import type { MolliePayment, MolliePayments, PaymentStatus } from './mollie'
import {
  insertPayment,
  isLive,
  isUnderWay,
  livePayment,
  moveStatus,
  recordPaymentAdjustments,
  type PaymentRepository,
  type StoredPayment,
} from './repository'

/** What starting (or reusing) a payment ends in. */
export type StartPaymentResult =
  | { kind: 'started'; checkoutUrl: string }
  | { kind: 'promotionRejected'; rejection: PromotionRejection }
  | { kind: 'notStarted' }

/** Controls whether a Mollie refusal cancels the pending order (`true` on initial checkout, `false` on retry). */
export interface StartPaymentOptions {
  cancelOnFailure?: boolean
}

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
  promotionCodes,
}: {
  db: Kysely<Tables>
  repository: PaymentRepository
  mollie: MolliePayments
  transitions: OrderTransitions
  promotionCodes: Pick<PromotionCodes, 'hasReservation' | 'reserve' | 'release'>
}) {
  const logger = createLogger('payment')

  /**
   * Records newly reported cumulative refund or chargeback deltas on a `PAID` payment inside the
   * caller's transaction, writing `PAYMENT_REFUNDED` / `PAYMENT_CHARGEBACK` idempotently. Answers
   * the deltas so the caller can log an immediate error after commit when a chargeback arrives.
   */
  const recordAdjustments = async (
    trx: Transaction<Tables>,
    stored: StoredPayment,
    reported: MolliePayment,
    cause: PaymentCause,
    actorId: number | null,
  ) => {
    const deltas = await recordPaymentAdjustments(trx, stored.paymentId, reported)
    if (deltas.refundDeltaCents > 0) {
      await recordEvent(trx, stored.orderId, {
        kind: 'PAYMENT_REFUNDED',
        actorType: 'SYSTEM',
        cause,
        actorId,
        details: {
          paymentId: stored.paymentId,
          amountCents: deltas.refundDeltaCents,
          totalRefundedCents: deltas.totalRefundedCents,
        },
      })
    }
    if (deltas.chargebackDeltaCents > 0) {
      await recordEvent(trx, stored.orderId, {
        kind: 'PAYMENT_CHARGEBACK',
        actorType: 'SYSTEM',
        cause,
        actorId,
        details: {
          paymentId: stored.paymentId,
          amountCents: deltas.chargebackDeltaCents,
          totalChargedBackCents: deltas.totalChargedBackCents,
        },
      })
    }
    return deltas
  }

  const logAdjustments = (
    stored: StoredPayment,
    deltas: {
      refundDeltaCents: number
      totalRefundedCents: number
      chargebackDeltaCents: number
      totalChargedBackCents: number
    },
  ) => {
    if (deltas.refundDeltaCents > 0) {
      logger.info(
        {
          paymentId: stored.paymentId,
          orderId: stored.orderId,
          amountCents: deltas.refundDeltaCents,
          totalRefundedCents: deltas.totalRefundedCents,
        },
        'Mollie reported a payment refund',
      )
    }
    if (deltas.chargebackDeltaCents > 0) {
      logger.error(
        {
          paymentId: stored.paymentId,
          orderId: stored.orderId,
          amountCents: deltas.chargebackDeltaCents,
          totalChargedBackCents: deltas.totalChargedBackCents,
        },
        'Mollie reported a payment chargeback: immediate operator attention required',
      )
    }
  }

  /**
   * Hands a paid payment to the order and records `PAID` on the payment in the same transaction,
   * so a paid transition that fails (the redemption, the production request) leaves the payment
   * under way and the next webhook or refresh confirms the order; a payment stored as `PAID` is
   * never asked about again by refresh, while webhooks still check it for new refunds and
   * chargebacks. The order lock comes first, then the payment row. `true` when the order is paid
   * now or was already.
   */
  const confirmOrder = async (
    stored: StoredPayment,
    reported: MolliePayment,
    cause: PaymentCause,
    actorId: number | null,
  ): Promise<boolean> => {
    const { paid, deltas } = await write(db, async (trx) => {
      const paid = await transitions.markPaid(trx, stored.orderId, { actorType: 'SYSTEM', cause })
      await movePayment(trx, stored, 'PAID', cause, actorId)
      const deltas = await recordAdjustments(trx, stored, reported, cause, actorId)
      return { paid, deltas }
    })
    logAdjustments(stored, deltas)
    if (paid === 'paid' || paid === 'alreadyPaid') return true
    logger.error(
      { paymentId: stored.paymentId, orderId: stored.orderId, amountCents: stored.amountCents },
      paid === 'cancelled'
        ? 'A payment was paid for a CANCELLED order: the amount has to be refunded by hand'
        : 'A payment was paid for an order that does not exist',
    )
    return false
  }

  /**
   * Writes the reported status over a payment under way and, only when that really changed the
   * row, records `PAYMENT_STATUS_CHANGED` from the locked previous status with `cause` (and the
   * admin of an `ADMIN_RECHECK` as `actorId`) in the same transaction; `false` when the row was
   * final or already had the status and nothing moved.
   */
  const movePayment = async (
    trx: Transaction<Tables>,
    stored: StoredPayment,
    to: PaymentStatus,
    cause: PaymentCause,
    actorId: number | null,
  ): Promise<boolean> => {
    const from = await moveStatus(trx, stored.paymentId, to)
    if (from === null) return false
    await recordEvent(trx, stored.orderId, {
      kind: 'PAYMENT_STATUS_CHANGED',
      actorType: 'SYSTEM',
      cause,
      actorId,
      details: { paymentId: stored.paymentId, from, to },
    })
    logger.info(
      { orderId: stored.orderId, paymentId: stored.paymentId, from, to, cause },
      'Payment status changed',
    )
    return true
  }

  /**
   * What the webhook, the detail refresh, and the reconciliation do with a status Mollie reported;
   * `cause` names which of them asked. A status the row already has, or a row that is final
   * (`PAID` or ended), is written over by nothing and recorded by nothing: a delayed `OPEN`
   * arriving after `PAID` was recorded leaves `PAID`. A terminal status is one transaction that
   * locks orders, then the payment, then the promotion (the order of `confirmOrder`): the payment
   * ends and the reservation is released together, so a failed release leaves the payment under
   * way for the next webhook, refresh, or reconciliation. A redelivery of a terminal status
   * releases again; the release is idempotent.
   */
  const applyReported = async (
    stored: StoredPayment,
    reported: MolliePayment,
    cause: PaymentCause,
    actorId: number | null,
  ): Promise<PaymentConfirmation> => {
    if (reported.status === 'PAID') {
      if (reported.amountCents === stored.amountCents) {
        return (await confirmOrder(stored, reported, cause, actorId)) ? 'confirmed' : 'notConfirmed'
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
      const deltas = await write(db, async (trx) => {
        await movePayment(trx, stored, 'PAID', cause, actorId)
        return recordAdjustments(trx, stored, reported, cause, actorId)
      })
      logAdjustments(stored, deltas)
      return 'notConfirmed'
    }
    if (isLive(reported.status)) {
      if (stored.status !== reported.status) {
        await write(db, (trx) => movePayment(trx, stored, reported.status, cause, actorId))
      }
      return 'recorded'
    }
    await write(db, async (trx) => {
      await lockOrder(trx, stored.orderId)
      const moved = await movePayment(trx, stored, reported.status, cause, actorId)
      if (moved || stored.status === reported.status) {
        await transitions.paymentEnded(trx, stored.orderId)
      }
    })
    return 'recorded'
  }

  /**
   * The status of one order's payment, asked of Mollie while the payment is still under way: the
   * fallback for a webhook that never arrived, which may confirm the order through the same path
   * the webhook takes; `cause` is recorded on what it changed, with `actorId` the admin of an
   * `ADMIN_RECHECK`. A provider that cannot answer degrades to the stored status.
   */
  const refreshed = async (
    orderId: number,
    cause: PaymentCause,
    actorId: number | null = null,
  ): Promise<PaymentStatus | null> => {
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
    await applyReported(stored, reported, cause, actorId)
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
     * Starts the payment of `order`, or answers the one it already has. One transaction holds the
     * order row lock around "live payment? → ensure promotion reservation → Mollie create →
     * insert", so concurrent starts of one order queue on the lock and the second answers the
     * first one's stored URL without a provider call.
     *
     * When the order carries a promotion without an active reservation (typically a retry after a
     * terminal payment released the initial hold), the promotion is reserved again under the order
     * lock before Mollie is called; a rejected reservation stops immediately with
     * `promotionRejected` and starts no payment.
     *
     * On a provider refusal: with `cancelOnFailure: true` (initial checkout, the default), the
     * order is cancelled and its reservation released under the same lock so a queued start finds
     * it cancelled and the still-active cart can be submitted again. With `cancelOnFailure: false`
     * (payment retry, whose cart is already checked out), the order stays `PENDING` and any
     * reservation acquired in this attempt is released again so a transient Mollie failure does
     * not strand the customer or leak coupon capacity. A database failure after Mollie created the
     * payment cancels that payment before the error travels on. The lock is held during the
     * provider call, which the adapter bounds at eight seconds.
     */
    startPayment: async (
      order: PayableOrder,
      { cancelOnFailure = true }: StartPaymentOptions = {},
    ): Promise<StartPaymentResult> => {
      if (order.totalCents <= 0) throw new Error('A payment amount must be greater than zero')
      const attempt: { created: MolliePayment | null } = { created: null }
      let outcome:
        | { kind: 'started'; checkoutUrl: string }
        | { kind: 'promotionRejected'; rejection: PromotionRejection }
        | { kind: 'refused' | 'notPending' }
      try {
        outcome = await write(db, async (trx) => {
          const locked = await lockOrder(trx, order.orderId)
          if (locked?.status !== 'PENDING') return { kind: 'notPending' }
          const live = await livePayment(trx, order.orderId)
          if (live !== undefined) return { kind: 'started', checkoutUrl: live.checkoutUrl }
          let reservedNow = false
          if (locked.promotionId !== null) {
            const alreadyReserved = await promotionCodes.hasReservation(
              trx,
              locked.promotionId,
              locked.cartId,
            )
            if (!alreadyReserved) {
              const reservation = await promotionCodes.reserve(
                trx,
                locked.promotionId,
                locked.cartId,
                locked.userId,
              )
              if (reservation.kind !== 'applicable') {
                return { kind: 'promotionRejected', rejection: reservation }
              }
              reservedNow = true
            }
          }
          const created = await mollie.create(order, randomUUID())
          if (created?.checkoutUrl == null) {
            if (cancelOnFailure) {
              await transitions.cancel(trx, order.orderId)
            } else if (reservedNow) {
              await promotionCodes.release(trx, locked.cartId)
            }
            return { kind: 'refused' }
          }
          attempt.created = created
          const paymentId = await insertPayment(trx, {
            orderId: order.orderId,
            molliePaymentId: created.id,
            status: created.status,
            amountCents: order.totalCents,
            checkoutUrl: created.checkoutUrl,
          })
          await recordEvent(trx, order.orderId, {
            kind: 'PAYMENT_STARTED',
            actorType: 'CUSTOMER',
            details: { paymentId },
          })
          return { kind: 'started', checkoutUrl: created.checkoutUrl }
        })
      } catch (error) {
        if (attempt.created !== null) await cancelUnused(attempt.created.id, order.orderId)
        throw error
      }
      switch (outcome.kind) {
        case 'started':
          return outcome
        case 'promotionRejected':
          return outcome
        case 'notPending':
          return { kind: 'notStarted' }
        case 'refused':
          logger.warn(
            { orderId: order.orderId, cancelled: cancelOnFailure },
            cancelOnFailure
              ? 'Mollie started no payment: the order is cancelled and no checkout URL exists'
              : 'Mollie started no payment: the order remains PENDING and no checkout URL exists',
          )
          return { kind: 'notStarted' }
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
      return applyReported(stored, reported, 'WEBHOOK', null)
    },

    /** The stored status of every order that has a payment, in one read, never a provider call. */
    stored: async (orderIds: Iterable<number>): Promise<Map<number, PaymentStatus>> => {
      const payments = await repository.currentPayments(orderIds)
      return new Map([...payments].map(([orderId, payment]) => [orderId, payment.status]))
    },

    refreshed,

    /**
     * An admin's "ask Mollie now" on one order: `refreshed` with cause `ADMIN_RECHECK` and the
     * admin's user id, then the stored order status and payment status read again, so the answer
     * is what is stored, also when a concurrent webhook got there first (`PAID` on both after a
     * paid answer). An unknown order is `notFound`, an order without a payment a `conflict`.
     */
    recheck: async (orderId: number, adminUserId: number) => {
      await refreshed(orderId, 'ADMIN_RECHECK', adminUserId)
      const status = await repository.orderStatus(orderId)
      if (status === undefined) return notFound
      const payment = (await repository.currentPayments([orderId])).get(orderId)
      if (payment === undefined) return conflict
      return ok({ status, paymentStatus: payment.status })
    },

    /**
     * The operations worker's first step: every payment under way for more than fifteen minutes,
     * oldest first, goes through `refreshed` once, so a lost webhook is repaired without the
     * customer opening their order. A provider that cannot answer is `refreshed`'s warning; any
     * other failure is logged by class and the next payment is asked all the same. Once `signal`
     * is aborted it returns between payments.
     */
    reconcile: async (now: Date, signal?: AbortSignal): Promise<void> => {
      const orderIds = await repository.underWayBefore(
        new Date(now.getTime() - RECONCILE_AFTER_MILLIS),
      )
      for (const orderId of orderIds) {
        if (signal?.aborted === true) return
        try {
          await refreshed(orderId, 'RECONCILIATION')
        } catch (error) {
          logger.error(
            { orderId, error: errorDetails(error) },
            'The reconciliation of a payment failed: the next scan asks again',
          )
        }
      }
    },
  }
}
