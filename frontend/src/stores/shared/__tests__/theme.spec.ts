import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useThemeStore } from '../theme'

const STORAGE_KEY = 'voenix-theme'

type MediaQueryChangeListener = (event: MediaQueryListEvent) => void

let prefersDark = false
let changeListeners: MediaQueryChangeListener[] = []

function mockMatchMedia() {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn((query: string) => ({
      matches: query === '(prefers-color-scheme: dark)' && prefersDark,
      media: query,
      addEventListener: vi.fn((_event: string, listener: MediaQueryChangeListener) => {
        changeListeners.push(listener)
      }),
      removeEventListener: vi.fn(),
    })),
  })
}

function emitSystemPreferenceChange(matches: boolean) {
  prefersDark = matches
  for (const listener of changeListeners) {
    listener({ matches } as MediaQueryListEvent)
  }
}

describe('theme store', () => {
  beforeEach(() => {
    prefersDark = false
    changeListeners = []
    localStorage.clear()
    document.documentElement.classList.remove('dark')
    mockMatchMedia()
    setActivePinia(createPinia())
  })

  it('defaults to system mode when nothing is stored', () => {
    const store = useThemeStore()

    expect(store.theme).toBe('system')
    expect(store.resolvedTheme).toBe('light')
  })

  it('resolves system mode to dark when the system prefers dark', async () => {
    prefersDark = true

    const store = useThemeStore()
    await nextTick()

    expect(store.theme).toBe('system')
    expect(store.resolvedTheme).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('restores an explicit stored theme', () => {
    localStorage.setItem(STORAGE_KEY, 'dark')

    const store = useThemeStore()

    expect(store.theme).toBe('dark')
    expect(store.resolvedTheme).toBe('dark')
  })

  it('persists the selected theme and updates the dark class', async () => {
    const store = useThemeStore()

    store.setTheme('dark')
    await nextTick()

    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)

    store.setTheme('light')
    await nextTick()

    expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('follows system preference changes while in system mode', async () => {
    const store = useThemeStore()
    await nextTick()

    expect(store.resolvedTheme).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)

    emitSystemPreferenceChange(true)
    await nextTick()

    expect(store.resolvedTheme).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('ignores system preference changes in an explicit mode', async () => {
    localStorage.setItem(STORAGE_KEY, 'light')

    const store = useThemeStore()
    await nextTick()

    emitSystemPreferenceChange(true)
    await nextTick()

    expect(store.resolvedTheme).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})
