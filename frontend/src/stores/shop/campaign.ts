import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'voenix.campaign-home'

/**
 * A visitor who enters the shop through a campaign landing page (e.g. /royal-dog) keeps that page
 * as their "home" for the rest of the visit: the header logo links there instead of /. Only the
 * entry page of a visit decides; opening a campaign page later from inside the shop does not
 * change the home. The path is kept in sessionStorage so a page reload inside the same tab does
 * not lose it, while a new tab starts fresh on the default shop.
 */
export const useCampaignStore = defineStore('campaign', () => {
  const homePath = ref(readStoredHomePath() ?? '/')

  /**
   * Called once per page load with the entry page's path when it is a campaign landing page, or
   * `null` otherwise. A reload keeps the stored home; any other entry starts a new visit.
   */
  function startVisit(campaignLandingPath: string | null) {
    if (campaignLandingPath) {
      setHomePath(campaignLandingPath)
    } else if (!isReload()) {
      setHomePath('/')
    }
  }

  function setHomePath(path: string) {
    homePath.value = path
    try {
      if (path === '/') {
        sessionStorage.removeItem(STORAGE_KEY)
      } else {
        sessionStorage.setItem(STORAGE_KEY, path)
      }
    } catch {
      // Storage can be unavailable (private mode, blocked cookies); the in-memory ref still works.
    }
  }

  return { homePath, startVisit }
})

function readStoredHomePath(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function isReload(): boolean {
  const [entry] = performance.getEntriesByType('navigation') as PerformanceNavigationTiming[]
  return entry?.type === 'reload'
}
