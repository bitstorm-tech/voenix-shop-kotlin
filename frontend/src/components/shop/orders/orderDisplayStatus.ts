import type { OrderPaymentStatus, OrderShippingStatus, OrderStatus } from '@/stores/shop/orders'

/**
 * The one status a customer sees. On the wire an order carries its order status, Mollie's payment
 * status, and its shipping progress — but the shop shows a single word: once paid, shipping
 * progress refines it to `PARTIALLY_SHIPPED` or `SHIPPED`; while still `PENDING`, a dead payment
 * surfaces because it is the one thing a customer can act on.
 */
export type OrderDisplayStatus =
  | 'PENDING'
  | 'PAID'
  | 'PARTIALLY_SHIPPED'
  | 'SHIPPED'
  | 'CANCELLED'
  | 'PAYMENT_FAILED'
  | 'PAYMENT_EXPIRED'
  | 'PAYMENT_CANCELED'

function paidDisplayStatus(shippingStatus: OrderShippingStatus | null): OrderDisplayStatus {
  if (shippingStatus === 'SHIPPED') return 'SHIPPED'
  if (shippingStatus === 'PARTIALLY_SHIPPED') return 'PARTIALLY_SHIPPED'
  return 'PAID'
}

export function orderDisplayStatus(
  status: OrderStatus,
  paymentStatus: OrderPaymentStatus | null,
  shippingStatus: OrderShippingStatus | null = null,
): OrderDisplayStatus {
  if (status === 'PAID') {
    return paidDisplayStatus(shippingStatus)
  }

  if (status !== 'PENDING') {
    return status
  }

  // The provider already confirmed the payment; the order row flips on the next confirmation
  // pass, so waiting customers should not read "in progress" as "not paid yet".
  if (paymentStatus === 'PAID') {
    return paidDisplayStatus(shippingStatus)
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
