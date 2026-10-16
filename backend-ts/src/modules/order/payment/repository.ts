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
 * A payment that stands still where the shop expected it to move: still under way an hour after it
 * was started (`PAYMENT_UNRESOLVED`, probably a lost webhook), or paid while its order is not
 * (`PAYMENT_PAID_ORDER_UNPAID`: the amount differed, or the order was cancelled).
 */
export interface OrderFinding {
  kind: 'PAYMENT_UNRESOLVED' | 'PAYMENT_PAID_ORDER_UNPAID'
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
     * payment can be either kind, and a live payment is always its order's current one (the index
     * allows one per order), so no "current payment" selection is needed. A pending order whose
     * payment ended is an abandoned checkout, not a finding.
     */
    findings: async (now: Date): Promise<OrderFinding[]> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('payments as p')
          .innerJoin('orders as o', 'o.id', 'p.order_id')
          .select(['p.id', 'p.order_id', 'p.status', 'p.created_at', 'p.updated_at'])
          .where((eb) =>
            eb.or([
              eb.and([
                eb('o.status', '=', 'PENDING'),
                eb('p.status', 'in', UNDER_WAY_STATUSES),
                eb('p.created_at', '<', new Date(now.getTime() - UNRESOLVED_AFTER_MILLIS)),
              ]),
              eb.and([eb('p.status', '=', 'PAID'), eb('o.status', '<>', 'PAID')]),
            ]),
          )
          .orderBy('p.id')
          .execute(),
      )
      return rows.map((row) => ({
        ...(row.status === 'PAID'
          ? { kind: 'PAYMENT_PAID_ORDER_UNPAID', since: row.updated_at }
          : { kind: 'PAYMENT_UNRESOLVED', since: row.created_at }),
        subject: { type: 'PAYMENT', id: row.id },
        orderId: row.order_id,
        attemptCount: null,
        lastErrorCode: null,
      }))
    },

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
 * under way; `false` means the row is final and nothing moved. A write from a live status never
 * conflicts with `ux_payments_live_order`: the row already holds its order's slot or gives it up.
 */
export async function moveStatus(
  trx: Transaction<Tables>,
  paymentId: number,
  status: PaymentStatus,
): Promise<boolean> {
  const result = await trx
    .updateTable('payments')
    .set({ status, updated_at: sql<Date>`now()` })
    .where('id', '=', paymentId)
    .where('status', 'in', UNDER_WAY_STATUSES)
    .executeTakeFirst()
  return result.numUpdatedRows > 0n
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

export async function insertPayment(
  trx: Transaction<Tables>,
  payment: Omit<StoredPayment, 'paymentId'>,
): Promise<void> {
  await trx
    .insertInto('payments')
    .values({
      order_id: payment.orderId,
      mollie_payment_id: payment.molliePaymentId,
      status: payment.status,
      amount_cents: payment.amountCents,
      checkout_url: payment.checkoutUrl,
    })
    .execute()
}
