import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/**
 * The shop's own order lifecycle — `CANCELLED` with **two** L, and there is no `SHIPPED`: the
 * backend knows exactly these three values and writes them uppercase
 * (`docs/dev/backend/order-package.md`).
 */
export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED'

/**
 * Mollie's vocabulary, uppercased — `CANCELED` with **one** L. The one-L payment word and the two-L
 * order word are different facts written by two different systems and must stay two words
 * (`docs/migration/payment-post-migration.md`).
 */
export type OrderPaymentStatus =
  | 'OPEN'
  | 'PENDING'
  | 'AUTHORIZED'
  | 'PAID'
  | 'FAILED'
  | 'CANCELED'
  | 'EXPIRED'

/** One ordered line. It is priced as it was paid, not as the catalog prices it today. */
export interface OrderItem {
  orderItemId: number
  articleId: number
  variantId: number
  articleName: string
  variantName: string
  quantity: number
  /** The article price of one unit, in integer cents. */
  price: number
  /** The prompt surcharge of one unit, in integer cents. */
  promptPrice: number
  /** The print image of that line, or `null` when none was stored. */
  imageId: number | null
}

/**
 * What `GET /api/orders` and `GET /api/orders/{orderId}` both answer — the list entry carries its
 * lines, so no second request per order is needed.
 *
 * All four amounts are integer cents and the backend guarantees
 * `total = subtotal + shippingCost - discountAmount`, so the discount is read, never inferred.
 * `paymentStatus` is `null` when the order has no payment at all: a free order, or a checkout that
 * was never started (`docs/dev/backend/order-package.md`).
 */
export interface Order {
  orderId: number
  createdAt: string
  status: OrderStatus
  paymentStatus: OrderPaymentStatus | null
  subtotal: number
  shippingCost: number
  discountAmount: number
  total: number
  items: OrderItem[]
}

/** The part of an order answer the confirmation page reads while it waits for the payment. */
export interface OrderStatusSnapshot {
  orderId: number
  status: OrderStatus
  paymentStatus: OrderPaymentStatus | null
  total: number
}

export const useOrdersStore = defineStore('orders', () => {
  const orders = ref<Order[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)

  /**
   * The whole history, newest first, as a bare JSON array. The route is guest-capable: a visitor
   * without an account sees the orders they placed under their guest cookie. It answers the
   * complete history — pagination is a deliberate open decision, not a missing feature
   * (`docs/migration/order-post-migration.md`).
   */
  async function fetchOrders() {
    isLoading.value = true
    error.value = null

    try {
      orders.value = await fetchJson<Order[]>('/api/orders')
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load orders'
    } finally {
      isLoading.value = false
    }
  }

  /**
   * One order in the same representation as a list entry. An unknown id and a foreign id are both a
   * `404` with the shared error body — there is no `403`, so a caller cannot tell the two apart.
   */
  async function fetchOrder(orderId: number): Promise<Order> {
    return fetchJson<Order>(`/api/orders/${orderId}`)
  }

  function $reset() {
    orders.value = []
    isLoading.value = false
    error.value = null
  }

  return {
    orders,
    isLoading,
    error,
    fetchOrders,
    fetchOrder,
    $reset,
  }
})
