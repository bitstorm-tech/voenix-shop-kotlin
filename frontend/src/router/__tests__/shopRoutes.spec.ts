import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteRecordRaw } from 'vue-router'
import { shopRoutes } from '../shop'

function getShopChildRoutes(): RouteRecordRaw[] {
  return shopRoutes.find((route) => route.path === '/')?.children ?? []
}

function findShopChildRoute(path: string): RouteRecordRaw {
  const route = getShopChildRoutes().find((child) => child.path === path)
  if (!route) {
    throw new Error(`Shop route not found: ${path}`)
  }
  return route
}

describe('shopRoutes', () => {
  it('registers the permanent order link as a child of the shop layout', () => {
    const route = findShopChildRoute('order/:token')

    expect(route.name).toBe('order-link')
  })

  it('marks the royal dog page as a campaign landing page', () => {
    expect(findShopChildRoute('royal-dog').meta?.campaignLanding).toBe(true)
  })

  it('leaves the permanent order link guard-free while the account pages stay guarded', () => {
    // The link is the credential. A guard would send a mail recipient without an account to /login.
    expect(findShopChildRoute('order/:token').beforeEnter).toBeUndefined()
    expect(findShopChildRoute('orders').beforeEnter).toBeDefined()
    expect(findShopChildRoute('profile').beforeEnter).toBeDefined()
  })
})

describe('shop router navigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    // The session restore behind `authReadyPromise`: this visitor is not signed in.
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ message: 'Unauthorized' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )
  })

  it('opens the order link for an anonymous visitor without any redirect', async () => {
    const { default: router } = await import('@/router')

    await router.push('/order/Tok3n-with_chars')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('order-link')
    expect(router.currentRoute.value.fullPath).toBe('/order/Tok3n-with_chars')
    expect(router.currentRoute.value.params.token).toBe('Tok3n-with_chars')
  })

  it('remembers a campaign landing page as the session home for the header logo', async () => {
    const { default: router } = await import('@/router')
    const { useCampaignStore } = await import('@/stores/shop/campaign')

    const campaignStore = useCampaignStore()
    expect(campaignStore.homePath).toBe('/')

    await router.push('/royal-dog')

    expect(campaignStore.homePath).toBe('/royal-dog')
    expect(sessionStorage.getItem('voenix.campaign-home')).toBe('/royal-dog')
  })

  it('still sends the same anonymous visitor from the order history to the login page', async () => {
    const { default: router } = await import('@/router')

    await router.push('/orders')

    expect(router.currentRoute.value.path).toBe('/login')
  })
})
