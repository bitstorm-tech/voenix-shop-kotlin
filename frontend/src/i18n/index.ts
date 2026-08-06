import { createI18n } from 'vue-i18n'
import de from './locales/de.json'
import en from './locales/en.json'

const STORAGE_KEY = 'voenix-locale'

function getStoredLocale(): string {
  if (typeof window === 'undefined') return 'de'
  return localStorage.getItem(STORAGE_KEY) || 'de'
}

const i18n = createI18n({
  legacy: false,
  locale: getStoredLocale(),
  fallbackLocale: 'de',
  messages: {
    de,
    en,
  },
})

export default i18n
