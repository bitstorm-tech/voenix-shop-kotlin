import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrderStatusRefresh } from '@/composables/useOrderStatusRefresh'
import { useCheckoutStore, type OrderStatusSnapshot } from '@/stores/shop/checkout'

/** The delays the composable walks through, so a test can drive it to its end. */
const REFRESH_DELAYS_MS = [3000, 6000, 12000, 24000, 48000]

function mountRefresh(orderId: number | null = 7) {
  const state: { refresh?: ReturnType<typeof useOrderStatusRefresh> } = {}
  const wrapper = mount(
    defineComponent({
      setup() {
        state.refresh = useOrderStatusRefresh(() => orderId)
        return () => h('div')
      },
    }),
  )

  if (!state.refresh) {
    throw new Error('The composable did not run')
  }

  return { wrapper, refresh: state.refresh }
}

async function advanceTo(delayIndex: number) {
  const delay = REFRESH_DELAYS_MS[delayIndex]
  if (delay === undefined) {
    throw new Error(`No delay ${delayIndex}`)
  }

  await vi.advanceTimersByTimeAsync(delay)
  await flushPromises()
}

function snapshot(overrides: Partial<OrderStatusSnapshot> = {}): OrderStatusSnapshot {
  return { orderId: 7, status: 'PENDING', paymentStatus: 'OPEN', total: 4070, ...overrides }
}

describe('useOrderStatusRefresh', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('stops refreshing after a bounded number of attempts and offers a manual read', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockResolvedValue(snapshot())
    const { wrapper, refresh } = mountRefresh()
    await flushPromises()

    expect(fetchOrderStatus).toHaveBeenCalledTimes(1)
    expect(refresh.isWaiting.value).toBe(true)

    for (let index = 0; index < REFRESH_DELAYS_MS.length; index += 1) {
      await advanceTo(index)
    }

    expect(fetchOrderStatus).toHaveBeenCalledTimes(1 + REFRESH_DELAYS_MS.length)
    expect(refresh.isWaiting.value).toBe(false)
    expect(refresh.hasStoppedWaiting.value).toBe(true)

    // Nothing is scheduled any more, however long the tab stays open.
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(fetchOrderStatus).toHaveBeenCalledTimes(1 + REFRESH_DELAYS_MS.length)

    await refresh.refreshNow()
    expect(fetchOrderStatus).toHaveBeenCalledTimes(2 + REFRESH_DELAYS_MS.length)
    wrapper.unmount()
  })

  it('backs off between the refreshes instead of asking every three seconds', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockResolvedValue(snapshot())
    const { wrapper } = mountRefresh()
    await flushPromises()

    await advanceTo(0)
    expect(fetchOrderStatus).toHaveBeenCalledTimes(2)

    // A fixed three-second interval would have asked twice more by now.
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(fetchOrderStatus).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(fetchOrderStatus).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('stops as soon as the payment is confirmed', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockResolvedValueOnce(snapshot())
      .mockResolvedValue(snapshot({ status: 'PAID', paymentStatus: 'PAID' }))
    const { wrapper, refresh } = mountRefresh()
    await flushPromises()

    await advanceTo(0)

    expect(refresh.isPaid.value).toBe(true)
    expect(refresh.isWaiting.value).toBe(false)
    expect(refresh.hasStoppedWaiting.value).toBe(false)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetchOrderStatus).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('treats a free order without any payment as confirmed', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(
      snapshot({ status: 'PAID', paymentStatus: null, total: 0 }),
    )
    const { wrapper, refresh } = mountRefresh()
    await flushPromises()

    expect(refresh.isPaid.value).toBe(true)
    expect(refresh.isWaiting.value).toBe(false)
    wrapper.unmount()
  })

  it.each(['FAILED', 'CANCELED', 'EXPIRED'] as const)(
    'stops waiting for a payment that ended %s',
    async (paymentStatus) => {
      const checkoutStore = useCheckoutStore()
      vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(snapshot({ paymentStatus }))
      const { wrapper, refresh } = mountRefresh()
      await flushPromises()

      expect(refresh.hasTerminalPayment.value).toBe(true)
      expect(refresh.isPaid.value).toBe(false)
      expect(refresh.isWaiting.value).toBe(false)
      wrapper.unmount()
    },
  )

  it('stops on a failed read and keeps the manual refresh', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockRejectedValue(new Error('HTTP error 404'))
    const { wrapper, refresh } = mountRefresh()
    await flushPromises()

    expect(refresh.hasFailed.value).toBe(true)
    expect(refresh.isWaiting.value).toBe(false)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetchOrderStatus).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('cancels the scheduled refresh when the page is left', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockResolvedValue(snapshot())
    const { wrapper } = mountRefresh()
    await flushPromises()

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60_000)

    expect(fetchOrderStatus).toHaveBeenCalledTimes(1)
  })

  it('reads nothing without an order id', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi.spyOn(checkoutStore, 'fetchOrderStatus')
    const { wrapper, refresh } = mountRefresh(null)
    await flushPromises()

    expect(fetchOrderStatus).not.toHaveBeenCalled()
    expect(refresh.isLoading.value).toBe(false)
    wrapper.unmount()
  })
})
