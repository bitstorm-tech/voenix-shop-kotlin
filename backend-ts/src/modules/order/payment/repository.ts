import { sql, type Kysely, type Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
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

const columns = [
  'id as paymentId',
  'order_id as orderId',
  'mollie_payment_id as molliePaymentId',
  'status',
  'amount_cents as amountCents',
  'checkout_url as checkoutUrl',
] as const

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

    /** The payment Mollie is talking about, or `undefined` when this backend never created it. */
    paymentByMollieId: (molliePaymentId: string) =>
      read(db, (trx) =>
        trx
          .selectFrom('payments')
          .select(columns)
          .where('mollie_payment_id', '=', molliePaymentId)
          .executeTakeFirst(),
      ),

    /**
     * Writes the status Mollie reported, or answers `'superseded'` when the live index refused it:
     * a dead payment reporting itself alive while another live payment stands for its order.
     */
    updateStatus: (paymentId: number, status: PaymentStatus) =>
      executePostgresWrite({ uniqueViolation: 'superseded' }, async () => {
        await write(db, (trx) =>
          trx
            .updateTable('payments')
            .set({ status, updated_at: sql<Date>`now()` })
            .where('id', '=', paymentId)
            .execute(),
        )
        return 'applied' as const
      }),
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
