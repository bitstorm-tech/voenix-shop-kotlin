import { defineStore } from 'pinia'
import { computed, shallowRef, watchEffect } from 'vue'

export type Theme = 'light' | 'dark' | 'system'

// Keep in sync with the FOUC script in index.html
const STORAGE_KEY = 'voenix-theme'

export const useThemeStore = defineStore('theme', () => {
  const theme = shallowRef<Theme>(getInitialTheme())
  const systemPrefersDark = shallowRef(getSystemPrefersDark())

  if (typeof window !== 'undefined') {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (event) => {
      systemPrefersDark.value = event.matches
    })
  }

  const resolvedTheme = computed<'light' | 'dark'>(() => {
    if (theme.value === 'system') return systemPrefersDark.value ? 'dark' : 'light'
    return theme.value
  })

  watchEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme.value === 'dark')
  })

  function setTheme(value: Theme) {
    if (value === theme.value) return
    theme.value = value
    localStorage.setItem(STORAGE_KEY, value)
  }

  return { theme, resolvedTheme, setTheme }
})

function getInitialTheme(): Theme {
  if (typeof window === 'undefined') return 'system'
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'light' || stored === 'dark' || stored === 'system') return stored
  return 'system'
}

function getSystemPrefersDark(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}
