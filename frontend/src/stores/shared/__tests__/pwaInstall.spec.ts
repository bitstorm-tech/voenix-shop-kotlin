import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePwaInstallStore } from '../pwaInstall'

const INSTALL_ACCEPTED_KEY = 'voenix-pwa-install-accepted'

type PwaInstallStore = ReturnType<typeof usePwaInstallStore>

interface TestBeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

const stores: PwaInstallStore[] = []

function createStore() {
  const store = usePwaInstallStore()
  stores.push(store)
  return store
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

function createBeforeInstallPromptEvent(
  outcome: 'accepted' | 'dismissed' = 'accepted',
  prompt: () => Promise<void> = vi.fn().mockResolvedValue(undefined),
): TestBeforeInstallPromptEvent {
  const event = new Event('beforeinstallprompt') as TestBeforeInstallPromptEvent
  event.preventDefault = vi.fn()
  event.prompt = prompt
  event.userChoice = Promise.resolve({ outcome })
  return event
}

function restoreNavigatorProperty(
  property: 'platform' | 'maxTouchPoints',
  descriptor: PropertyDescriptor | undefined,
) {
  if (descriptor) {
    Object.defineProperty(window.navigator, property, descriptor)
    return
  }

  Reflect.deleteProperty(window.navigator, property)
}

describe('pwaInstall store', () => {
  beforeEach(() => {
    stores.length = 0
    setActivePinia(createPinia())
    localStorage.clear()
    mockDisplayMode(false)
  })

  afterEach(() => {
    stores.forEach((store) => store.dispose())
    stores.length = 0
    localStorage.clear()
  })

  it('shows the mobile menu install action in regular browser tabs without a native prompt', () => {
    const store = createStore()

    expect(store.canInstall).toBe(true)
    expect(store.hasNativePrompt).toBe(false)
  })

  it('hides the mobile menu install action in standalone PWA mode', () => {
    mockDisplayMode(true)
    const store = createStore()

    expect(store.canInstall).toBe(false)
  })

  it('stores and consumes the native browser install prompt', async () => {
    const store = createStore()
    const event = createBeforeInstallPromptEvent('accepted')

    store.init()
    window.dispatchEvent(event)

    expect(event.preventDefault).toHaveBeenCalledTimes(1)
    expect(store.hasNativePrompt).toBe(true)

    const result = await store.installApp()

    expect(event.prompt).toHaveBeenCalledTimes(1)
    expect(result).toBe('accepted')
    expect(store.hasNativePrompt).toBe(false)
    expect(store.canInstall).toBe(false)
  })

  it('keeps the install action visible when the native prompt is dismissed', async () => {
    const store = createStore()
    const event = createBeforeInstallPromptEvent('dismissed')

    store.init()
    window.dispatchEvent(event)

    const result = await store.installApp()

    expect(result).toBe('dismissed')
    expect(store.hasNativePrompt).toBe(false)
    expect(store.canInstall).toBe(true)
  })

  it('hides the install action after the appinstalled browser event', () => {
    const store = createStore()

    store.init()
    window.dispatchEvent(new Event('appinstalled'))

    expect(store.canInstall).toBe(false)
  })

  it('persists accepted installs across store instances', async () => {
    const store = createStore()
    const event = createBeforeInstallPromptEvent('accepted')

    store.init()
    window.dispatchEvent(event)
    await store.installApp()
    store.dispose()

    setActivePinia(createPinia())
    const reloadedStore = createStore()

    expect(reloadedStore.canInstall).toBe(false)
  })

  it('allows install again when the browser reports a native prompt after a stale accepted flag', () => {
    localStorage.setItem(INSTALL_ACCEPTED_KEY, 'true')
    const store = createStore()
    const event = createBeforeInstallPromptEvent('dismissed')

    expect(store.canInstall).toBe(false)

    store.init()
    window.dispatchEvent(event)

    expect(store.canInstall).toBe(true)
    expect(store.hasNativePrompt).toBe(true)
    expect(localStorage.getItem(INSTALL_ACCEPTED_KEY)).toBeNull()
  })

  it('returns unavailable and clears a stale prompt when prompting fails', async () => {
    const store = createStore()
    const prompt = vi.fn().mockRejectedValue(new Error('prompt failed'))
    const event = createBeforeInstallPromptEvent('accepted', prompt)

    store.init()
    window.dispatchEvent(event)

    const result = await store.installApp()

    expect(result).toBe('unavailable')
    expect(store.hasNativePrompt).toBe(false)
    expect(store.installing).toBe(false)
  })

  it('prevents repeated prompt calls while an install attempt is in flight', async () => {
    const store = createStore()
    let resolvePrompt!: () => void
    const prompt = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolvePrompt = resolve
        }),
    )
    const event = createBeforeInstallPromptEvent('accepted', prompt)

    store.init()
    window.dispatchEvent(event)

    const firstResult = store.installApp()
    const secondResult = await store.installApp()

    expect(secondResult).toBe('unavailable')
    expect(prompt).toHaveBeenCalledTimes(1)
    expect(store.installing).toBe(true)

    resolvePrompt()

    await expect(firstResult).resolves.toBe('accepted')
    expect(store.installing).toBe(false)
  })

  it('registers install listeners only once per store instance', () => {
    const store = createStore()
    const event = createBeforeInstallPromptEvent('accepted')

    store.init()
    store.init()
    window.dispatchEvent(event)

    expect(event.preventDefault).toHaveBeenCalledTimes(1)
  })

  it('detects iPadOS Safari in desktop browsing mode as iOS', () => {
    const platformDescriptor = Object.getOwnPropertyDescriptor(window.navigator, 'platform')
    const maxTouchPointsDescriptor = Object.getOwnPropertyDescriptor(
      window.navigator,
      'maxTouchPoints',
    )

    try {
      Object.defineProperty(window.navigator, 'platform', {
        configurable: true,
        value: 'MacIntel',
      })
      Object.defineProperty(window.navigator, 'maxTouchPoints', {
        configurable: true,
        value: 2,
      })
      const store = createStore()

      expect(store.isIos).toBe(true)
    } finally {
      restoreNavigatorProperty('platform', platformDescriptor)
      restoreNavigatorProperty('maxTouchPoints', maxTouchPointsDescriptor)
    }
  })
})
