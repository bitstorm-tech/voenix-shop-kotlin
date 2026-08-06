import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const registerSWMock = vi.hoisted(() => vi.fn())

vi.mock('virtual:pwa-register', () => ({
  registerSW: registerSWMock,
}))

interface RegisterSwOptions {
  onNeedRefresh(): void
}

function mockDisplayMode(isStandalone: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn((query: string) => ({
      matches: query === '(display-mode: standalone)' && isStandalone,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  })

  Object.defineProperty(window.navigator, 'standalone', {
    configurable: true,
    value: false,
  })
}

describe('initPwa', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
    registerSWMock.mockReset()
    localStorage.clear()
    setActivePinia(createPinia())
    mockDisplayMode(false)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('activates waiting updates silently in regular browser tabs', async () => {
    const updateSW = vi.fn()
    let options: RegisterSwOptions | undefined

    registerSWMock.mockImplementation((receivedOptions: RegisterSwOptions) => {
      options = receivedOptions
      return updateSW
    })

    const { initPwa } = await import('../pwa')
    const { usePwaUpdateStore } = await import('../stores/shared/pwaUpdate')

    initPwa()
    options?.onNeedRefresh()

    expect(updateSW).toHaveBeenCalledWith(false)
    expect(usePwaUpdateStore().needsRefresh).toBe(false)
  })

  it('shows the update prompt in standalone PWA mode', async () => {
    const updateSW = vi.fn()
    let options: RegisterSwOptions | undefined

    mockDisplayMode(true)
    registerSWMock.mockImplementation((receivedOptions: RegisterSwOptions) => {
      options = receivedOptions
      return updateSW
    })

    const { initPwa } = await import('../pwa')
    const { usePwaUpdateStore } = await import('../stores/shared/pwaUpdate')

    initPwa()
    options?.onNeedRefresh()

    expect(updateSW).not.toHaveBeenCalled()
    expect(usePwaUpdateStore().needsRefresh).toBe(true)
  })
})
