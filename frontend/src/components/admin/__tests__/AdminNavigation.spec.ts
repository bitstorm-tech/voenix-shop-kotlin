import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import AdminNavigation from '../AdminNavigation.vue'
import { adminNavigationItems } from '../adminNavigation'

function createAdminNavigationRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin', component: { template: '<div />' } },
      { path: '/admin/prompts', component: { template: '<div />' } },
      { path: '/admin/prompts/new', component: { template: '<div />' } },
      { path: '/admin/prompts/categories', component: { template: '<div />' } },
      { path: '/admin/prompts/slots', component: { template: '<div />' } },
      { path: '/admin/prompts/:id/edit', component: { template: '<div />' } },
      { path: '/admin/articles', component: { template: '<div />' } },
      { path: '/admin/articles/categories', component: { template: '<div />' } },
      { path: '/admin/articles/categories/new', component: { template: '<div />' } },
      { path: '/admin/articles/categories/:id', component: { template: '<div />' } },
      { path: '/admin/suppliers', component: { template: '<div />' } },
      { path: '/admin/logistics', component: { template: '<div />' } },
      { path: '/admin/vat', component: { template: '<div />' } },
      { path: '/admin/vat/new', component: { template: '<div />' } },
      { path: '/admin/vat/:id', component: { template: '<div />' } },
      { path: '/admin/promotions', component: { template: '<div />' } },
      { path: '/admin/coupons', redirect: '/admin/promotions' },
      { path: '/admin/orders', component: { template: '<div />' } },
      { path: '/admin/issues', component: { template: '<div />' } },
    ],
  })
}

async function mountNavigation(path = '/admin') {
  const router = createAdminNavigationRouter()

  await router.push(path)
  await router.isReady()

  const wrapper = mount(AdminNavigation, {
    props: { items: adminNavigationItems },
    global: {
      plugins: [router],
    },
  })

  return { wrapper, router }
}

function findNavGroup(wrapper: VueWrapper, title: string) {
  return wrapper
    .findAll('[data-nav-group]')
    .find((group) => group.find('[data-nav-group-heading]').text() === title)
}

function getActiveLinkTexts(wrapper: VueWrapper) {
  return wrapper
    .findAll('[data-nav-link]')
    .filter((link) => link.attributes('aria-current') === 'page')
    .map((link) => link.text())
}

describe('AdminNavigation', () => {
  it('renders overview followed by the Prompts group and child links', async () => {
    const { wrapper } = await mountNavigation()

    const topEntries = wrapper.findAll(
      '[data-nav-link]:not([data-nav-child-link]), [data-nav-group-heading]',
    )
    const promptsGroup = findNavGroup(wrapper, 'Prompts')

    expect(topEntries[0]?.text()).toContain('Overview')
    expect(topEntries[1]?.text()).toContain('Prompts')
    expect(promptsGroup?.attributes('role')).toBe('group')
    expect(promptsGroup?.attributes('aria-labelledby')).toBe(
      promptsGroup?.find('[data-nav-group-heading]').attributes('id'),
    )
    expect(promptsGroup?.findAll('[data-nav-child-link]').map((link) => link.text())).toEqual([
      'All Prompts',
      'Categories',
      'Slots',
    ])
  })

  it('renders child links indented and without icons', async () => {
    const { wrapper } = await mountNavigation()

    const childLinkContainers = wrapper.findAll('[data-nav-children]')

    expect(childLinkContainers.every((container) => container.classes().includes('pl-11'))).toBe(
      true,
    )
    expect(
      wrapper.findAll('[data-nav-child-link]').every((link) => !link.find('svg').exists()),
    ).toBe(true)
  })

  it('renders unique group heading ids across multiple navigation instances', async () => {
    const router = createAdminNavigationRouter()

    await router.push('/admin')
    await router.isReady()

    const wrapper = mount(
      {
        components: { AdminNavigation },
        setup() {
          return { items: adminNavigationItems }
        },
        template: `
          <div>
            <AdminNavigation :items="items" />
            <AdminNavigation :items="items" />
          </div>
        `,
      },
      {
        global: {
          plugins: [router],
        },
      },
    )

    const headingIds = wrapper
      .findAll('[data-nav-group-heading]')
      .map((heading) => heading.attributes('id'))

    expect(new Set(headingIds).size).toBe(headingIds.length)
  })

  it('renders the Articles group as a visual heading with All Articles and Categories links', async () => {
    const { wrapper } = await mountNavigation()

    const articlesGroup = findNavGroup(wrapper, 'Articles')
    const articleLinks = articlesGroup?.findAll('[data-nav-child-link]') ?? []

    expect(articlesGroup?.attributes('role')).toBe('group')
    expect(articlesGroup?.attributes('aria-labelledby')).toBe(
      articlesGroup?.find('[data-nav-group-heading]').attributes('id'),
    )
    expect(articleLinks.map((link) => link.text())).toEqual(['All Articles', 'Categories'])
    expect(articleLinks.map((link) => link.attributes('href'))).toEqual([
      '/admin/articles',
      '/admin/articles/categories',
    ])
  })

  it('renders the Masterdata group as a labeled section with operational child links', async () => {
    const { wrapper } = await mountNavigation()

    const masterdataGroup = findNavGroup(wrapper, 'Masterdata')
    const masterdataLinks = masterdataGroup?.findAll('[data-nav-child-link]') ?? []

    expect(masterdataGroup?.attributes('role')).toBe('group')
    expect(masterdataGroup?.attributes('aria-labelledby')).toBe(
      masterdataGroup?.find('[data-nav-group-heading]').attributes('id'),
    )
    expect(masterdataLinks.map((link) => link.text())).toEqual([
      'Suppliers',
      'Logistics',
      'VAT',
      'Promotions',
    ])
    expect(masterdataLinks.map((link) => link.attributes('href'))).toEqual([
      '/admin/suppliers',
      '/admin/logistics',
      '/admin/vat',
      '/admin/promotions',
    ])
  })

  it('marks the Promotions destination as the active page', async () => {
    const { wrapper } = await mountNavigation('/admin/promotions')

    expect(wrapper.find('[aria-current="page"]').text()).toBe('Promotions')
    expect(wrapper.text()).toContain('Masterdata')
  })

  it('renders Orders and Issues as top-level operational links', async () => {
    const { wrapper } = await mountNavigation()

    const topLevelLinks = wrapper.findAll('[data-nav-link]:not([data-nav-child-link])')
    const operationalLinks = topLevelLinks.filter((link) =>
      ['Orders', 'Issues'].includes(link.attributes('data-nav-title') ?? ''),
    )

    expect(operationalLinks.map((link) => link.text())).toEqual(['Orders', 'Issues'])
    expect(operationalLinks.map((link) => link.attributes('href'))).toEqual([
      '/admin/orders',
      '/admin/issues',
    ])
    expect(wrapper.text()).not.toContain('Completed Orders')
  })

  it('does not render removed Admin destinations', async () => {
    const { wrapper } = await mountNavigation()

    const navLinkHrefs = wrapper.findAll('[data-nav-link]').map((link) => link.attributes('href'))

    expect(navLinkHrefs).not.toContain('/admin/customers')
    expect(navLinkHrefs).not.toContain('/admin/users')
    expect(navLinkHrefs).not.toContain('/admin/settings')
    expect(navLinkHrefs).not.toContain('/admin/prompt-tester')
    expect(navLinkHrefs).not.toContain('/admin/editor')
    expect(wrapper.text()).not.toContain('Customers')
    expect(wrapper.text()).not.toContain('Users')
    expect(wrapper.text()).not.toContain('Settings')
    expect(wrapper.text()).not.toContain('Prompt Tester')
    expect(wrapper.text()).not.toContain('Editor')
  })

  it.each([
    ['/admin/orders', 'Orders'],
    ['/admin/issues', 'Issues'],
  ])(
    'marks only the matching top-level operational link active for %s',
    async (path, activeTitle) => {
      const { wrapper } = await mountNavigation(path)

      expect(getActiveLinkTexts(wrapper)).toEqual([activeTitle])
      expect(wrapper.findAll('[data-nav-group-heading][data-active="true"]')).toHaveLength(0)
    },
  )

  it('emits navigate when a link is selected so mobile drawers can close', async () => {
    const { wrapper } = await mountNavigation()

    await wrapper.find('[data-nav-child-link]').trigger('click')

    expect(wrapper.emitted('navigate')).toHaveLength(1)
  })

  it('keeps All Prompts active for prompt edit routes only', async () => {
    const { wrapper } = await mountNavigation('/admin/prompts/42/edit')

    expect(getActiveLinkTexts(wrapper)).toEqual(['All Prompts'])
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Prompts"]').attributes('data-active'),
    ).toBe('true')
  })

  it('keeps All Prompts active for the prompt create route', async () => {
    const { wrapper } = await mountNavigation('/admin/prompts/new')

    expect(getActiveLinkTexts(wrapper)).toEqual(['All Prompts'])
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Prompts"]').attributes('data-active'),
    ).toBe('true')
  })

  it.each([
    ['/admin/prompts/categories', 'Categories'],
    ['/admin/prompts/slots', 'Slots'],
  ])('does not mark All Prompts active for %s', async (path, activeTitle) => {
    const { wrapper } = await mountNavigation(path)

    expect(getActiveLinkTexts(wrapper)).toEqual([activeTitle])
  })

  it('does not render retired prompt slot links', async () => {
    const { wrapper } = await mountNavigation()
    const navLinkHrefs = wrapper.findAll('[data-nav-link]').map((link) => link.attributes('href'))

    expect(navLinkHrefs).toContain('/admin/prompts/slots')
    expect(navLinkHrefs).not.toContain('/admin/prompts/slot-types')
    expect(navLinkHrefs).not.toContain('/admin/prompts/slot-variants')
    expect(wrapper.text()).not.toContain('Slot Types')
    expect(wrapper.text()).not.toContain('Slot Variants')
  })

  it('marks All Articles active only for the article list route', async () => {
    const { wrapper } = await mountNavigation('/admin/articles')

    expect(getActiveLinkTexts(wrapper)).toEqual(['All Articles'])
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Articles"]').attributes('data-active'),
    ).toBe('true')
  })

  it.each([
    '/admin/articles/categories',
    '/admin/articles/categories/new',
    '/admin/articles/categories/42',
  ])('marks only the Article Categories child active for %s', async (path) => {
    const { wrapper } = await mountNavigation(path)

    expect(getActiveLinkTexts(wrapper)).toEqual(['Categories'])
    expect(wrapper.find('a[href="/admin/articles"]').attributes('aria-current')).toBeUndefined()
    expect(wrapper.find('a[href="/admin/articles/categories"]').attributes('aria-current')).toBe(
      'page',
    )
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Articles"]').attributes('data-active'),
    ).toBe('true')
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Prompts"]').attributes('data-active'),
    ).toBeUndefined()
  })

  it.each([
    ['/admin/suppliers', 'Suppliers'],
    ['/admin/logistics', 'Logistics'],
    ['/admin/vat', 'VAT'],
    ['/admin/vat/new', 'VAT'],
    ['/admin/vat/42', 'VAT'],
    ['/admin/promotions', 'Promotions'],
    ['/admin/coupons', 'Promotions'],
  ])('marks only the active Masterdata child for %s', async (path, activeTitle) => {
    const { wrapper } = await mountNavigation(path)

    expect(getActiveLinkTexts(wrapper)).toEqual([activeTitle])
    expect(
      wrapper
        .find('[data-nav-group-heading][data-nav-title="Masterdata"]')
        .attributes('data-active'),
    ).toBe('true')
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Prompts"]').attributes('data-active'),
    ).toBeUndefined()
    expect(
      wrapper.find('[data-nav-group-heading][data-nav-title="Articles"]').attributes('data-active'),
    ).toBeUndefined()
  })
})
