import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'voenix.campaign-home'

/**
 * A visitor who arrives through a campaign landing page (e.g. /royal-dog) should keep that page
 * as their "home" for the rest of the visit: the header logo links there instead of /. The path
 * is kept in sessionStorage so a page reload inside the same tab does not lose it, while a new
 * tab starts fresh on the default shop.
 */
export const useCampaignStore = defineStore('campaign', () => {
  const homePath = ref(readStoredHomePath() ?? '/')

  function rememberLanding(path: string) {
    homePath.value = path
    try {
      sessionStorage.setItem(STORAGE_KEY, path)
    } catch {
      // Storage can be unavailable (private mode, blocked cookies); the in-memory ref still works.
    }
  }

  return { homePath, rememberLanding }
})

function readStoredHomePath(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}
