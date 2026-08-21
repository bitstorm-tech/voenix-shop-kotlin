import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteRecordRaw } from 'vue-router'
import { shopRoutes } from '../shop'

/** Vitest runs with `frontend/` as its root, so the sources are one known directory down. */
const SRC_DIRECTORY = join(process.cwd(), 'src')

function collectSourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return collectSourceFiles(path)
    return /\.(ts|vue)$/.test(entry.name) ? [path] : []
  })
}

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

  it('serves the one product listing at /products and nowhere else', () => {
    const route = findShopChildRoute('products')

    expect(route.name).toBe('products')
    expect(route.beforeEnter).toBeUndefined()
    expect(getShopChildRoutes().some((child) => child.path === 'mugs')).toBe(false)
  })

  /**
   * The rename is only done when the last link is gone: `/mugs` has no redirect, so a leftover
   * link would be a dead end. Nothing is in production yet, which is why no redirect is owed.
   */
  it('leaves no /mugs link or route name anywhere in the sources', () => {
    const forbiddenPatterns = [/['"]\/mugs(['"?])/, /name: ['"]mugs['"]/, /path: ['"]mugs['"]/]

    const offenders = collectSourceFiles(SRC_DIRECTORY).filter((file) => {
      const content = readFileSync(file, 'utf8')
      return forbiddenPatterns.some((pattern) => pattern.test(content))
    })

    expect(offenders).toEqual([])
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

  it('still sends the same anonymous visitor from the order history to the login page', async () => {
    const { default: router } = await import('@/router')

    await router.push('/orders')

    expect(router.currentRoute.value.path).toBe('/login')
  })
})
