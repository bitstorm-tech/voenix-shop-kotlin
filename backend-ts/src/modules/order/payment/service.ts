import { randomUUID } from 'node:crypto'
import type { Kysely } from 'kysely'
import { write, type Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import { lockOrder, type PayableOrder } from '../repository'
import type { OrderTransitions } from '../transitions'
import type { MolliePayment, MolliePayments, PaymentStatus } from './mollie'
import {
  insertPayment,
  isLive,
  livePayment,
  type PaymentRepository,
  type StoredPayment,
} from './repository'

/**
 * What one webhook delivery did. Every outcome but `providerUnavailable` answers Mollie `200`:
 * a redelivery would change nothing about it, and what a human has to settle is in the log.
 */
export type PaymentConfirmation =
  /** The reported status is stored (or already was) and it is not `PAID`. */
  | 'recorded'
  /** `PAID`, and the order is paid now or was already. */
  | 'confirmed'
  /** `PAID`, but Mollie's amount differs from the stored one, or the order is `CANCELLED`. */
  | 'notConfirmed'
  /** The status write was refused by the live index: the customer may have been charged twice. */
  | 'superseded'
  /** A payment Mollie knows and this backend never created. */
  | 'unknownPayment'
  /** Mollie could not be reached or said nothing usable; a redelivery may succeed. */
  | 'providerUnavailable'

/** The statuses a detail read asks Mollie about: the payment is under way and a webhook may be missed. */
const REFRESHABLE: readonly PaymentStatus[] = ['OPEN', 'PENDING', 'AUTHORIZED']

export type PaymentService = ReturnType<typeof createPaymentService>

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

  /** Writes the reported status when it moved; a terminal status releases the reservation. */
  const applyStatus = async (stored: StoredPayment, reported: PaymentStatus): Promise<boolean> => {
    let applied = stored.status === reported
    if (!applied) {
      applied = (await repository.updateStatus(stored.paymentId, reported)) === 'applied'
      if (!applied) {
        logger.error(
          { paymentId: stored.paymentId, orderId: stored.orderId, status: reported },
          'A payment reports a status while another live payment stands for its order: ' +
            'the customer may have been charged twice',
        )
      }
    }
    // A redelivered terminal status releases again: the release is idempotent, and the
    // redelivery is the only retry a release lost to a failure has.
    if (applied && !isLive(reported)) await transitions.paymentEnded(stored.orderId)
    return applied
  }

  /** Hands a paid payment to the order; `true` when the order is paid now or was already. */
  const confirmOrder = async (stored: StoredPayment): Promise<boolean> => {
    const result = await write(db, (trx) => transitions.markPaid(trx, stored.orderId))
    if (result === 'paid' || result === 'alreadyPaid') return true
    logger.error(
      { paymentId: stored.paymentId, orderId: stored.orderId, amountCents: stored.amountCents },
      result === 'cancelled'
        ? 'A payment was paid for a CANCELLED order: the amount has to be refunded by hand'
        : 'A payment was paid for an order that does not exist',
    )
    return false
  }

  /** What the webhook and the detail refresh both do with a status Mollie reported. */
  const applyReported = async (
    stored: StoredPayment,
    reported: MolliePayment,
  ): Promise<PaymentConfirmation> => {
    const superseded = !(await applyStatus(stored, reported.status))
    let outcome: PaymentConfirmation
    if (reported.status !== 'PAID') {
      outcome = 'recorded'
    } else if (reported.amountCents !== stored.amountCents) {
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
      outcome = 'notConfirmed'
    } else {
      outcome = (await confirmOrder(stored)) ? 'confirmed' : 'notConfirmed'
    }
    return superseded ? 'superseded' : outcome
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
     * call. A provider that refuses cancels the order (and releases its reservation); a database
     * failure after Mollie created the payment cancels that payment before the error travels on.
     * The lock is held during the provider call, which the adapter bounds at ten seconds.
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
          if (created?.checkoutUrl == null) return { kind: 'refused' }
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
          await transitions.cancel(order.orderId)
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

    /**
     * The status of one order's payment, asked of Mollie while the payment is still under way: the
     * fallback for a webhook that never arrived, which may confirm the order through the same path
     * the webhook takes. A provider that cannot answer degrades to the stored status, and a write
     * the live index refused answers what the row says, never the reported status.
     */
    refreshed: async (orderId: number): Promise<PaymentStatus | null> => {
      const stored = (await repository.currentPayments([orderId])).get(orderId)
      if (stored === undefined) return null
      if (!REFRESHABLE.includes(stored.status)) return stored.status
      const reported = await mollie.find(stored.molliePaymentId)
      if (reported === null) {
        logger.warn(
          { paymentId: stored.paymentId, orderId },
          'Mollie said nothing usable: the order is answered with its stored payment status',
        )
        return stored.status
      }
      return (await applyReported(stored, reported)) === 'superseded'
        ? stored.status
        : reported.status
    },
  }
}
