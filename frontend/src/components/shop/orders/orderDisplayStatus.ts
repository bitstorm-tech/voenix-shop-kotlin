import type { OrderPaymentStatus, OrderStatus } from '@/stores/shop/orders'

/**
 * The one status a customer sees. On the wire an order still carries two values — the shop's
 * order status and Mollie's payment status — but the shop shows a single word: the order status
 * decides, and only while the order is still `PENDING` does the payment status refine it, because
 * a dead payment is the one thing a customer can act on.
 */
export type OrderDisplayStatus =
  | 'PENDING'
  | 'PAID'
  | 'CANCELLED'
  | 'PAYMENT_FAILED'
  | 'PAYMENT_EXPIRED'
  | 'PAYMENT_CANCELED'

export function orderDisplayStatus(
  status: OrderStatus,
  paymentStatus: OrderPaymentStatus | null,
): OrderDisplayStatus {
  if (status !== 'PENDING') {
    return status
  }

  // The provider already confirmed the payment; the order row flips on the next confirmation
  // pass, so waiting customers should not read "in progress" as "not paid yet".
  if (paymentStatus === 'PAID') {
    return 'PAID'
  }

  if (paymentStatus === 'FAILED') {
    return 'PAYMENT_FAILED'
  }

  if (paymentStatus === 'EXPIRED') {
    return 'PAYMENT_EXPIRED'
  }

  if (paymentStatus === 'CANCELED') {
    return 'PAYMENT_CANCELED'
  }

  // OPEN, PENDING, AUTHORIZED, and "no payment at all" are all still "in progress" to a customer.
  return 'PENDING'
}
