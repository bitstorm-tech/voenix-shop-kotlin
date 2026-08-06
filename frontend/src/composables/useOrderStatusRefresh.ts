import { computed, onMounted, onUnmounted, shallowRef, toValue, type MaybeRefOrGetter } from 'vue'
import {
  useCheckoutStore,
  type OrderPaymentStatus,
  type OrderStatusSnapshot,
} from '@/stores/shop/checkout'

/**
 * The waiting ladder, in milliseconds. It is deliberately finite: after the last delay the page
 * stops asking on its own and offers a manual refresh instead. A payment confirmation arrives
 * through Mollie's webhook, so a browser tab that keeps asking forever only produces load — the
 * order detail read is also what repairs a missed webhook, and a handful of reads is enough for
 * that (`docs/migration/payment-post-migration.md`).
 */
const REFRESH_DELAYS_MS = [3000, 6000, 12000, 24000, 48000]

/** A payment that reached one of these will never become `PAID`; waiting for it is pointless. */
const TERMINAL_PAYMENT_STATUSES: readonly OrderPaymentStatus[] = ['FAILED', 'CANCELED', 'EXPIRED']

/**
 * Reads `GET /api/orders/{orderId}` and keeps it fresh with a bounded, backing-off refresh while the
 * order is still waiting for its payment confirmation.
 */
export function useOrderStatusRefresh(orderId: MaybeRefOrGetter<number | null>) {
  const checkoutStore = useCheckoutStore()

  const order = shallowRef<OrderStatusSnapshot | null>(null)
  const isLoading = shallowRef(true)
  const isRefreshing = shallowRef(false)
  const hasFailed = shallowRef(false)
  /** True while another automatic refresh is scheduled. */
  const isWaiting = shallowRef(false)

  let timer: ReturnType<typeof setTimeout> | null = null
  let refreshCount = 0

  const status = computed(() => order.value?.status ?? null)
  const paymentStatus = computed(() => order.value?.paymentStatus ?? null)
  const total = computed(() => order.value?.total ?? 0)

  const isPaid = computed(() => status.value === 'PAID' || paymentStatus.value === 'PAID')
  const isCancelled = computed(() => status.value === 'CANCELLED')
  const hasTerminalPayment = computed(
    () => paymentStatus.value !== null && TERMINAL_PAYMENT_STATUSES.includes(paymentStatus.value),
  )

  /** Nothing this page could wait for is still open. */
  const isSettled = computed(() => isPaid.value || isCancelled.value || hasTerminalPayment.value)

  /** The order is still open and the ladder is used up, so the customer has to ask themselves. */
  const hasStoppedWaiting = computed(
    () => !isLoading.value && !hasFailed.value && !isSettled.value && !isWaiting.value,
  )

  async function refresh(): Promise<void> {
    const id = toValue(orderId)
    if (id === null || isRefreshing.value) {
      isLoading.value = false
      return
    }

    isRefreshing.value = true
    try {
      order.value = await checkoutStore.fetchOrderStatus(id)
      hasFailed.value = false
    } catch {
      hasFailed.value = true
    } finally {
      isRefreshing.value = false
      isLoading.value = false
    }
  }

  /** One manual read, outside the ladder: it neither restarts nor extends the automatic waiting. */
  async function refreshNow(): Promise<void> {
    cancelScheduledRefresh()
    await refresh()
  }

  function scheduleRefresh() {
    const delay = REFRESH_DELAYS_MS[refreshCount]
    if (delay === undefined || toValue(orderId) === null || isSettled.value || hasFailed.value) {
      isWaiting.value = false
      return
    }

    isWaiting.value = true
    timer = setTimeout(() => {
      timer = null
      refreshCount += 1
      void refresh().then(scheduleRefresh)
    }, delay)
  }

  function cancelScheduledRefresh() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    isWaiting.value = false
  }

  onMounted(() => {
    void refresh().then(scheduleRefresh)
  })

  onUnmounted(cancelScheduledRefresh)

  return {
    order,
    status,
    paymentStatus,
    total,
    isLoading,
    isRefreshing,
    hasFailed,
    isPaid,
    isCancelled,
    hasTerminalPayment,
    isSettled,
    isWaiting,
    hasStoppedWaiting,
    refreshNow,
  }
}
