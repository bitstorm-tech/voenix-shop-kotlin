import { ref } from 'vue'
import { defineStore } from 'pinia'

export type OrderStatus = 'pending' | 'paid' | 'shipped' | 'cancelled'
export type PaymentStatus =
  | 'open'
  | 'pending'
  | 'authorized'
  | 'paid'
  | 'failed'
  | 'canceled'
  | 'expired'

export interface OrderItem {
  orderItemId: number
  articleId: number
  variantId: number
  articleName: string
  variantName: string
  quantity: number
  priceAtTime: number
  promptPriceAtTime: number
  generatedEditedImageId: number | null
  customData: string
}

export interface Order {
  orderId: number
  createdAt: string
  status: OrderStatus
  paymentStatus: PaymentStatus | null
  totalAmountInCents: number
  shippingCostInCents: number
  items: OrderItem[]
}

interface OrderItemApiResponse {
  orderItemId: number
  articleId: number
  variantId: number
  articleName: string
  variantName: string
  quantity: number
  priceAtTime: number
  promptPriceAtTime: number
  generatedEditedImageId: number | null
  customData: string
}

interface OrderApiResponse {
  orderId: number
  createdAt: string
  status: string
  paymentStatus: string | null
  totalAmountInCents: number
  shippingCostInCents: number
  items: OrderItemApiResponse[]
}

function normalizeStatus<T extends string>(value: string): T {
  return value.toLowerCase() as T
}

function mapOrder(order: OrderApiResponse): Order {
  return {
    ...order,
    status: normalizeStatus<OrderStatus>(order.status),
    paymentStatus: order.paymentStatus ? normalizeStatus<PaymentStatus>(order.paymentStatus) : null,
  }
}

export const useOrdersStore = defineStore('orders', () => {
  const orders = ref<Order[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function fetchOrders() {
    isLoading.value = true
    error.value = null

    try {
      const response = await fetch('/api/checkout/orders')

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}))
        throw new Error(errorData.detail || errorData.message || `HTTP ${response.status}`)
      }

      const data: OrderApiResponse[] = await response.json()
      orders.value = data.map(mapOrder)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load orders'
    } finally {
      isLoading.value = false
    }
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
    $reset,
  }
})
