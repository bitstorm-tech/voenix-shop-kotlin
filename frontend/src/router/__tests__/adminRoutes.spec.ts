import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { adminNavigationItems, getAdminNavigationLinks } from '@/components/admin/adminNavigation'
import { adminRoutes } from '../admin'

function getAdminChildRoutes(): RouteRecordRaw[] {
  return adminRoutes.find((route) => route.path === '/admin')?.children ?? []
}

function createRouterWithNotFound() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      ...adminRoutes,
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: { template: '<div />' },
      },
    ],
  })
}

describe('adminRoutes', () => {
  it('registers remaining top-level operational destinations and keeps Orders as a single page', () => {
    const children = getAdminChildRoutes()

    expect(children).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'orders', name: 'admin-orders' }),
        expect.objectContaining({ path: 'issues', name: 'admin-issues' }),
      ]),
    )
    expect(children.map((route) => route.path)).not.toContain('orders/completed')
  })

  it('does not register removed Admin child routes', () => {
    const children = getAdminChildRoutes()
    const childPaths = children.map((route) => route.path)
    const childNames = children.map((route) => route.name)

    expect(childPaths).not.toEqual(
      expect.arrayContaining(['customers', 'users', 'settings', 'prompt-tester', 'editor']),
    )
    expect(childNames).not.toEqual(
      expect.arrayContaining([
        'admin-customers',
        'admin-users',
        'admin-settings',
        'admin-prompt-tester',
        'admin-editor',
      ]),
    )
  })

  it.each([
    '/admin/customers',
    '/admin/users',
    '/admin/settings',
    '/admin/prompt-tester',
    '/admin/editor',
  ])('resolves removed Admin URL %s through the normal not-found route', (path) => {
    const router = createRouterWithNotFound()

    const resolvedRoute = router.resolve(path)

    expect(resolvedRoute.name).toBe('not-found')
    expect(resolvedRoute.matched.some((route) => route.path === '/admin')).toBe(false)
  })

  it('keeps Admin navigation links backed by registered Admin routes', () => {
    const router = createRouterWithNotFound()

    for (const link of getAdminNavigationLinks(adminNavigationItems)) {
      const resolvedRoute = router.resolve(link.to)

      expect(resolvedRoute.name).not.toBe('not-found')
      expect(resolvedRoute.matched.some((route) => route.path === '/admin')).toBe(true)
    }
  })

  it('registers Promotions and redirects the retired Coupons URL', () => {
    const router = createRouterWithNotFound()
    const couponsRecord = getAdminChildRoutes().find((route) => route.path === 'coupons')

    expect(router.resolve('/admin/promotions').name).toBe('admin-promotions')
    expect(router.resolve('/admin/promotions').meta.title).toBe('Promotions')
    expect(couponsRecord?.redirect).toEqual({ name: 'admin-promotions' })
    expect(couponsRecord?.name).toBeUndefined()
  })

  it.each(['vat/new', 'vat/:id'])(
    'redirects the retired VAT editor route %s to the VAT list',
    (path) => {
      const record = getAdminChildRoutes().find((route) => route.path === path)

      expect(record?.redirect).toEqual({ name: 'admin-vat' })
      expect(record?.name).toBeUndefined()
    },
  )

  it('registers the supplier list route and redirects retired editor routes', () => {
    const router = createRouterWithNotFound()

    expect(router.resolve('/admin/suppliers').name).toBe('admin-suppliers')

    for (const path of ['suppliers/new', 'suppliers/:id']) {
      const record = getAdminChildRoutes().find((route) => route.path === path)

      expect(record?.redirect).toEqual({ name: 'admin-suppliers' })
      expect(record?.name).toBeUndefined()
    }
  })

  it('registers Logistics as a real page', () => {
    const router = createRouterWithNotFound()

    const resolvedRoute = router.resolve('/admin/logistics')

    expect(resolvedRoute.name).toBe('admin-logistics')
    expect(resolvedRoute.meta.title).toBe('Logistics')
  })

  it('registers the article category list route and redirects retired editor routes', () => {
    const router = createRouterWithNotFound()

    expect(router.resolve('/admin/articles/categories').name).toBe('admin-article-categories')

    for (const path of ['articles/categories/new', 'articles/categories/:id']) {
      const record = getAdminChildRoutes().find((route) => route.path === path)

      expect(record?.redirect).toEqual({ name: 'admin-article-categories' })
      expect(record?.name).toBeUndefined()
    }
  })

  it('registers one article list route per type', () => {
    const router = createRouterWithNotFound()

    expect(router.resolve('/admin/articles/mugs').name).toBe('admin-mug-articles')
    expect(router.resolve('/admin/articles/mugs').meta.title).toBe('Mugs')
    expect(router.resolve('/admin/articles/tshirts').name).toBe('admin-tshirt-articles')
    expect(router.resolve('/admin/articles/tshirts').meta.title).toBe('T-Shirts')
  })

  // A shirt is created by a sync run against the Spreadconnect backoffice (ADR 0003), so only the
  // mug has a create page.
  it('registers a create route for the mug only', () => {
    const paths = getAdminChildRoutes().map((route) => route.path)

    expect(paths).toContain('articles/mugs/new')
    expect(paths).not.toContain('articles/tshirts/new')
  })

  it('resolves the retired merged article list URL through the normal not-found route', () => {
    const router = createRouterWithNotFound()

    const resolvedRoute = router.resolve('/admin/articles')

    expect(resolvedRoute.name).toBe('not-found')
  })

  it('registers the route-level Prompt editor', () => {
    const router = createRouterWithNotFound()
    const record = getAdminChildRoutes().find((route) => route.path === 'prompts/:id/edit')

    expect(record?.redirect).toBeUndefined()
    expect(record?.name).toBe('admin-prompt-edit')
    expect(record?.component).toBeTypeOf('function')
    expect(router.resolve('/admin/prompts/42/edit').name).toBe('admin-prompt-edit')
    expect(router.resolve('/admin/prompts/42/edit').meta.title).toBe('Edit Prompt')
  })

  it('registers the guarded route-level Prompt create screen', () => {
    const router = createRouterWithNotFound()
    const record = getAdminChildRoutes().find((route) => route.path === 'prompts/new')

    expect(record?.redirect).toBeUndefined()
    expect(record?.name).toBe('admin-prompt-new')
    expect(record?.component).toBeTypeOf('function')
    expect(router.resolve('/admin/prompts/new').name).toBe('admin-prompt-new')
    expect(router.resolve('/admin/prompts/new').meta.title).toBe('New Prompt')
  })

  it('registers the consolidated prompt slots route', () => {
    const router = createRouterWithNotFound()

    const resolvedRoute = router.resolve('/admin/prompts/slots')

    expect(resolvedRoute.name).toBe('admin-prompt-slots')
    expect(resolvedRoute.meta.title).toBe('Prompt Slots')
  })

  it.each(['/admin/prompts/slot-types', '/admin/prompts/slot-variants'])(
    'resolves retired prompt slot URL %s through the normal not-found route',
    (path) => {
      const router = createRouterWithNotFound()

      const resolvedRoute = router.resolve(path)

      expect(resolvedRoute.name).toBe('not-found')
      expect(resolvedRoute.matched.some((route) => route.path === '/admin')).toBe(false)
    },
  )
})
