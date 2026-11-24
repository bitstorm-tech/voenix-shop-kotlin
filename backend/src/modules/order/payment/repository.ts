import { sql, type Kysely, type Transaction } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import type { PaymentStatus } from './mollie'
import '../schema'

/** A payment row: what a decision here is made from. `amountCents` is what the shop asked for. */
export interface StoredPayment {
  paymentId: number
  orderId: number
  molliePaymentId: string
  status: PaymentStatus
  amountCents: number
  checkoutUrl: string
}

/**
 * The three statuses `ux_payments_live_order` leaves out: a payment that failed, was cancelled, or
 * expired frees its order's one live slot, so the order may start another payment.
 */
const TERMINAL_STATUSES: readonly PaymentStatus[] = ['FAILED', 'CANCELED', 'EXPIRED']

/** Whether a payment in this status still occupies its order's one live payment slot. */
export const isLive = (status: PaymentStatus): boolean => !TERMINAL_STATUSES.includes(status)

/**
 * The statuses a payment can still move from: the customer has not paid and the payment has not
 * ended. A detail read asks Mollie only about these, and only these are ever written over: `PAID`
 * and the three terminal statuses are final, so a delayed answer about an earlier state cannot
 * take a paid payment back (and a dead payment can never re-enter the live index).
 */
const UNDER_WAY_STATUSES: readonly PaymentStatus[] = ['OPEN', 'PENDING', 'AUTHORIZED']

export const isUnderWay = (status: PaymentStatus): boolean => UNDER_WAY_STATUSES.includes(status)

const columns = [
  'id as paymentId',
  'order_id as orderId',
  'mollie_payment_id as molliePaymentId',
  'status',
  'amount_cents as amountCents',
  'checkout_url as checkoutUrl',
] as const

/**
 * A payment that needs operator attention: still under way an hour after it was started
 * (`PAYMENT_UNRESOLVED`, probably a lost webhook), paid while its order is not
 * (`PAYMENT_PAID_ORDER_UNPAID`: the amount differed, or the order was cancelled), or subject to a
 * Mollie chargeback (`PAYMENT_CHARGEBACK`).
 */
export interface OrderFinding {
  kind: 'PAYMENT_UNRESOLVED' | 'PAYMENT_PAID_ORDER_UNPAID' | 'PAYMENT_CHARGEBACK'
  subject: { type: 'PAYMENT'; id: number }
  orderId: number
  since: Date
  attemptCount: null
  lastErrorCode: null
}

/** A payment under way for longer than this is unresolved. */
const UNRESOLVED_AFTER_MILLIS = 60 * 60 * 1000

export type PaymentRepository = ReturnType<typeof createPaymentRepository>

export function createPaymentRepository(db: Kysely<Tables>) {
  return {
    /**
     * The payment that *is* each order's payment, for every order in `orderIds` that has one, in
     * one statement: the live payment if there is one (the index allows at most one), otherwise the
     * last attempt, so an order whose only payment expired still answers `EXPIRED`.
     */
    currentPayments: async (orderIds: Iterable<number>): Promise<Map<number, StoredPayment>> => {
      const wanted = [...orderIds]
      if (wanted.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('payments')
          .select(columns)
          .where('order_id', 'in', wanted)
          .orderBy('id')
          .execute(),
      )
      const current = new Map<number, StoredPayment>()
      for (const payment of rows) {
        const kept = current.get(payment.orderId)
        if (kept === undefined || !isLive(kept.status)) current.set(payment.orderId, payment)
      }
      return current
    },

    /**
     * The order module's findings for the operations worker, oldest payment first. Only a live
     * payment can match the rules, and a live payment is always its order's current one (the index
     * allows one per order), so no "current payment" selection is needed. A pending order whose
     * payment ended is an abandoned checkout, not a finding.
     */
    findings: async (now: Date): Promise<OrderFinding[]> => {
      const unresolvedBefore = new Date(now.getTime() - UNRESOLVED_AFTER_MILLIS)
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('payments as p')
          .innerJoin('orders as o', 'o.id', 'p.order_id')
          .select([
            'p.id',
            'p.order_id',
            'p.status',
            'p.charged_back_amount_cents',
            'p.created_at',
            'p.updated_at',
            'o.status as order_status',
          ])
          .where((eb) =>
            eb.or([
              eb.and([
                eb('o.status', '=', 'PENDING'),
                eb('p.status', 'in', UNDER_WAY_STATUSES),
                eb('p.created_at', '<', unresolvedBefore),
              ]),
              eb.and([eb('p.status', '=', 'PAID'), eb('o.status', '<>', 'PAID')]),
              eb('p.charged_back_amount_cents', '>', 0),
            ]),
          )
          .orderBy('p.id')
          .execute(),
      )
      const findings: OrderFinding[] = []
      for (const row of rows) {
        if (
          row.order_status === 'PENDING' &&
          isUnderWay(row.status) &&
          row.created_at.getTime() < unresolvedBefore.getTime()
        ) {
          findings.push({
            kind: 'PAYMENT_UNRESOLVED',
            subject: { type: 'PAYMENT', id: row.id },
            orderId: row.order_id,
            since: row.created_at,
            attemptCount: null,
            lastErrorCode: null,
          })
        }
        if (row.status === 'PAID' && row.order_status !== 'PAID') {
          findings.push({
            kind: 'PAYMENT_PAID_ORDER_UNPAID',
            subject: { type: 'PAYMENT', id: row.id },
            orderId: row.order_id,
            since: row.updated_at,
            attemptCount: null,
            lastErrorCode: null,
          })
        }
        if (row.charged_back_amount_cents > 0) {
          findings.push({
            kind: 'PAYMENT_CHARGEBACK',
            subject: { type: 'PAYMENT', id: row.id },
            orderId: row.order_id,
            since: row.updated_at,
            attemptCount: null,
            lastErrorCode: null,
          })
        }
      }
      return findings
    },

    /**
     * The orders whose payment is under way and was started before `createdBefore`, oldest payment
     * first: the payments the reconciliation asks Mollie about. A payment under way is live, and a
     * live payment is always its order's current one.
     */
    underWayBefore: async (createdBefore: Date): Promise<number[]> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('payments')
          .select('order_id')
          .where('status', 'in', UNDER_WAY_STATUSES)
          .where('created_at', '<', createdBefore)
          .orderBy('id')
          .execute(),
      )
      return rows.map((row) => row.order_id)
    },

    /** The status of one order, `undefined` for an unknown id. */
    orderStatus: async (orderId: number) =>
      (
        await read(db, (trx) =>
          trx.selectFrom('orders').select('status').where('id', '=', orderId).executeTakeFirst(),
        )
      )?.status,

    /** The payment Mollie is talking about, or `undefined` when this backend never created it. */
    paymentByMollieId: (molliePaymentId: string) =>
      read(db, (trx) =>
        trx
          .selectFrom('payments')
          .select(columns)
          .where('mollie_payment_id', '=', molliePaymentId)
          .executeTakeFirst(),
      ),
  }
}

/**
 * Writes the status Mollie reported, inside the caller's transaction and only while the payment is
 * under way and not already in `status`: the row is read `FOR UPDATE` first, so of two concurrent
 * reports of one change only the first moves it. Answers the locked previous status, or `null`
 * when nothing moved. A write from a live status never conflicts with `ux_payments_live_order`: the
 * row already holds its order's slot or gives it up.
 */
export async function moveStatus(
  trx: Transaction<Tables>,
  paymentId: number,
  status: PaymentStatus,
): Promise<PaymentStatus | null> {
  const locked = await trx
    .selectFrom('payments')
    .select('status')
    .where('id', '=', paymentId)
    .forUpdate()
    .executeTakeFirstOrThrow()
  if (locked.status === status || !isUnderWay(locked.status)) return null
  await trx
    .updateTable('payments')
    .set({ status, updated_at: sql<Date>`now()` })
    .where('id', '=', paymentId)
    .execute()
  return locked.status
}

export interface PaymentAdjustmentDeltas {
  refundDeltaCents: number
  totalRefundedCents: number
  chargebackDeltaCents: number
  totalChargedBackCents: number
}

/**
 * Records increased cumulative refunded or charged-back amounts on a `PAID` payment under a
 * `FOR UPDATE` row lock, answering the positive deltas actually applied. Repeated or concurrent
 * webhooks carrying the same totals apply zero deltas and therefore produce no duplicate events.
 */
export async function recordPaymentAdjustments(
  trx: Transaction<Tables>,
  paymentId: number,
  reported: { refundedAmountCents: number; chargedBackAmountCents: number },
): Promise<PaymentAdjustmentDeltas> {
  const locked = await trx
    .selectFrom('payments')
    .select(['status', 'refunded_amount_cents', 'charged_back_amount_cents'])
    .where('id', '=', paymentId)
    .forUpdate()
    .executeTakeFirstOrThrow()
  if (locked.status !== 'PAID') {
    return {
      refundDeltaCents: 0,
      totalRefundedCents: locked.refunded_amount_cents,
      chargebackDeltaCents: 0,
      totalChargedBackCents: locked.charged_back_amount_cents,
    }
  }
  const refundDeltaCents = Math.max(0, reported.refundedAmountCents - locked.refunded_amount_cents)
  const chargebackDeltaCents = Math.max(
    0,
    reported.chargedBackAmountCents - locked.charged_back_amount_cents,
  )
  const totalRefundedCents = locked.refunded_amount_cents + refundDeltaCents
  const totalChargedBackCents = locked.charged_back_amount_cents + chargebackDeltaCents
  if (refundDeltaCents > 0 || chargebackDeltaCents > 0) {
    await trx
      .updateTable('payments')
      .set({
        refunded_amount_cents: totalRefundedCents,
        charged_back_amount_cents: totalChargedBackCents,
        updated_at: sql<Date>`now()`,
      })
      .where('id', '=', paymentId)
      .execute()
  }
  return {
    refundDeltaCents,
    totalRefundedCents,
    chargebackDeltaCents,
    totalChargedBackCents,
  }
}

/** The order's live payment, read inside the caller's transaction (which holds the order lock). */
export function livePayment(trx: Transaction<Tables>, orderId: number) {
  return trx
    .selectFrom('payments')
    .select(columns)
    .where('order_id', '=', orderId)
    .where('status', 'not in', TERMINAL_STATUSES)
    .executeTakeFirst()
}

/** Stores a payment Mollie created and answers its id. */
export async function insertPayment(
  trx: Transaction<Tables>,
  payment: Omit<StoredPayment, 'paymentId'>,
): Promise<number> {
  const { id } = await trx
    .insertInto('payments')
    .values({
      order_id: payment.orderId,
      mollie_payment_id: payment.molliePaymentId,
      status: payment.status,
      amount_cents: payment.amountCents,
      checkout_url: payment.checkoutUrl,
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  return id
}
