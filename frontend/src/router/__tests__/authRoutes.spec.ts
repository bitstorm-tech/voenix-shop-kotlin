import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { authRoutes } from '../auth'
import { guestGuard } from '../guards'

function createRouterWithNotFound() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      ...authRoutes,
      { path: '/:pathMatch(.*)*', name: 'not-found', component: { template: '<div />' } },
    ],
  })
}

/** Every auth page is the single child of its own layout record. */
function pageRecordOf(path: string): RouteRecordRaw | undefined {
  return authRoutes.find((route) => route.path === path)?.children?.[0]
}

describe('authRoutes', () => {
  it.each([
    ['/login', 'login'],
    ['/forgot-password', 'forgot-password'],
    ['/reset-password', 'reset-password'],
    ['/set-password', 'set-password'],
    ['/register', 'register'],
    ['/confirm-email', 'confirm-email'],
    ['/confirm-change-email', 'confirm-change-email'],
  ])('registers %s', (path, name) => {
    expect(createRouterWithNotFound().resolve(path).name).toBe(name)
  })

  it('gives the supplier invitation its own guest-only page', () => {
    const router = createRouterWithNotFound()
    const record = pageRecordOf('/set-password')

    expect(record?.name).toBe('set-password')
    expect(record?.beforeEnter).toBe(guestGuard)
    expect(router.resolve('/set-password').meta.title).toBe('Set Password')
  })
})
