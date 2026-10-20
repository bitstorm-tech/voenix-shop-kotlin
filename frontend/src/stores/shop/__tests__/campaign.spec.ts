import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCampaignStore } from '../campaign'

const STORAGE_KEY = 'voenix.campaign-home'

function stubNavigationType(type: NavigationTimingType) {
  vi.spyOn(performance, 'getEntriesByType').mockReturnValue([
    { type } as PerformanceNavigationTiming,
  ])
}

describe('campaign store', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('makes the campaign landing page the home when the visit starts there', () => {
    stubNavigationType('navigate')

    useCampaignStore().startVisit('/royal-dog')

    expect(useCampaignStore().homePath).toBe('/royal-dog')
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe('/royal-dog')
  })

  it('drops an earlier campaign home when a new visit starts on a regular page', () => {
    sessionStorage.setItem(STORAGE_KEY, '/royal-dog')
    stubNavigationType('navigate')

    useCampaignStore().startVisit(null)

    expect(useCampaignStore().homePath).toBe('/')
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('keeps the campaign home when the visitor reloads a regular page', () => {
    sessionStorage.setItem(STORAGE_KEY, '/royal-dog')
    stubNavigationType('reload')

    useCampaignStore().startVisit(null)

    expect(useCampaignStore().homePath).toBe('/royal-dog')
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe('/royal-dog')
  })
})
