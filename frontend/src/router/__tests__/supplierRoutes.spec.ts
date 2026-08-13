import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { supplierRoutes } from '../supplier'

const authStore = vi.hoisted(() => ({
  isAuthenticated: false,
  isAdmin: false,
  isSupplier: false,
  roles: [] as string[],
  hasRole(role: string) {
    return this.roles.includes(role)
  },
}))

vi.mock('@/stores/shared/auth', () => ({
  useAuthStore: () => authStore,
}))

import { supplierGuard } from '../guards'

function createRouterWithNotFound() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      ...supplierRoutes,
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: { template: '<div />' },
      },
    ],
  })
}

function getSupplierChildRoutes(): RouteRecordRaw[] {
  return supplierRoutes.find((route) => route.path === '/supplier')?.children ?? []
}

describe('supplierRoutes', () => {
  it('protects the whole area with the supplier guard', () => {
    const area = supplierRoutes.find((route) => route.path === '/supplier')

    expect(area?.beforeEnter).toBe(supplierGuard)
    expect(area?.meta).toMatchObject({ requiresAuth: true, requiresSupplier: true })
  })

  it('registers the job list and redirects the bare area path to it', () => {
    const router = createRouterWithNotFound()

    const jobs = router.resolve('/supplier/jobs')
    expect(jobs.name).toBe('supplier-jobs')
    expect(jobs.meta.title).toBe('Production Jobs')

    const record = getSupplierChildRoutes().find((route) => route.path === '')
    expect(record?.redirect).toEqual({ name: 'supplier-jobs' })
    expect(record?.name).toBeUndefined()
  })

  it('resolves an unknown supplier URL through the normal not-found route', () => {
    const router = createRouterWithNotFound()

    const resolvedRoute = router.resolve('/supplier/invoices')

    expect(resolvedRoute.name).toBe('not-found')
    expect(resolvedRoute.matched.some((route) => route.path === '/supplier')).toBe(false)
  })
})

describe('supplierGuard', () => {
  const next = vi.fn()

  beforeEach(() => {
    authStore.isAuthenticated = false
    authStore.isAdmin = false
    authStore.isSupplier = false
    authStore.roles = []
    next.mockReset()
  })

  it('sends an unauthenticated visitor to the login page and remembers where they wanted to go', () => {
    supplierGuard({ fullPath: '/supplier/jobs?status=SHIPPED' } as never, {} as never, next)

    expect(next).toHaveBeenCalledWith({
      path: '/login',
      query: { redirect: '/supplier/jobs?status=SHIPPED' },
    })
  })

  it('turns an admin without a supplier login away from the area', () => {
    authStore.isAuthenticated = true
    authStore.isAdmin = true
    authStore.roles = ['ADMIN']

    supplierGuard({ fullPath: '/supplier/jobs' } as never, {} as never, next)

    expect(next).toHaveBeenCalledWith({ path: '/', query: { error: 'unauthorized' } })
  })

  it('lets a supplier login through', () => {
    authStore.isAuthenticated = true
    authStore.isSupplier = true
    authStore.roles = ['SUPPLIER']

    supplierGuard({ fullPath: '/supplier/jobs' } as never, {} as never, next)

    expect(next).toHaveBeenCalledWith()
  })
})
