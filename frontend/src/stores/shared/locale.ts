import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const STORAGE_KEY = 'voenix-locale'

export const useLocaleStore = defineStore('locale', () => {
  const locale = ref(getStoredLocale())

  function getStoredLocale(): string {
    if (typeof window === 'undefined') return 'de'
    try {
      return localStorage.getItem(STORAGE_KEY) || 'de'
    } catch {
      return 'de'
    }
  }

  function setLocale(newLocale: string) {
    locale.value = newLocale
    try {
      localStorage.setItem(STORAGE_KEY, newLocale)
    } catch {
      // Ignore storage access errors in restricted browsing contexts.
    }
  }

  return { locale, setLocale }
})

export function useLocaleSync() {
  const store = useLocaleStore()
  const { locale: i18nLocale } = useI18n()

  watch(
    () => store.locale,
    (newLocale) => {
      i18nLocale.value = newLocale
    },
    { immediate: true },
  )

  return store
}
