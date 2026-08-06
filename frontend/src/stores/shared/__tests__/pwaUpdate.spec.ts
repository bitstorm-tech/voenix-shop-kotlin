import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePwaUpdateStore } from '../pwaUpdate'

describe('pwaUpdate store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('applies the waiting service worker update', () => {
    const updateSW = vi.fn()
    const store = usePwaUpdateStore()

    store.setUpdateAvailable(updateSW)
    store.applyUpdate()

    expect(store.needsRefresh).toBe(true)
    expect(updateSW).toHaveBeenCalledWith(true)
  })

  it('shows a dismissed update again after the reminder delay', () => {
    const store = usePwaUpdateStore()

    store.setUpdateAvailable(vi.fn())
    store.dismissUpdate()

    expect(store.needsRefresh).toBe(false)

    vi.advanceTimersByTime(30 * 60 * 1000)

    expect(store.needsRefresh).toBe(true)
  })

  it('shows a dismissed update again when the app returns to focus', () => {
    const store = usePwaUpdateStore()

    store.setUpdateAvailable(vi.fn())
    store.dismissUpdate()
    store.showDismissedUpdate()

    expect(store.needsRefresh).toBe(true)
  })
})
