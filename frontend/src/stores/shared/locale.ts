import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const STORAGE_KEY = 'voenix-locale'

export const useLocaleStore = defineStore('locale', () => {
  const locale = ref(getStoredLocale())

  function getStoredLocale(): string {
    if (typeof window === 'undefined') return 'de'
    return localStorage.getItem(STORAGE_KEY) || 'de'
  }

  function setLocale(newLocale: string) {
    locale.value = newLocale
    localStorage.setItem(STORAGE_KEY, newLocale)
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
