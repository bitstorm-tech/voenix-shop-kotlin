import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'
import AdminLayout from '../AdminLayout.vue'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'

const AdminNavigationStub = {
  props: ['items'],
  template: `
    <nav data-admin-navigation>
      <template v-for="item in items" :key="item.type === 'link' ? item.to : item.title">
        <a v-if="item.type === 'link'" :href="item.to">{{ item.title }}</a>
        <template v-else>
          <a v-for="child in item.children" :key="child.to" :href="child.to">
            {{ child.title }}
          </a>
        </template>
      </template>
    </nav>
  `,
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: { template: '<div />' },
  },
  {
    path: '/admin',
    component: { template: '<div />' },
    meta: { title: 'Overview' },
  },
  {
    path: '/admin/prompts',
    component: { template: '<div />' },
    meta: { title: 'All Prompts' },
  },
  {
    path: '/admin/prompts/:id/edit',
    component: { template: '<div />' },
    meta: { title: 'Edit Prompt' },
  },
]

async function mountLayout(path: string, locale: 'de' | 'en' = 'en') {
  const pinia = createPinia()
  const i18n = createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { de, en } })
  setActivePinia(pinia)

  const router = createRouter({
    history: createMemoryHistory(),
    routes,
  })

  await router.push(path)
  await router.isReady()

  const wrapper = mount(AdminLayout, {
    global: {
      plugins: [pinia, router, i18n],
      stubs: {
        AdminNavigation: AdminNavigationStub,
        Sheet: { template: '<div><slot /></div>' },
        SheetClose: { template: '<div><slot /></div>' },
        SheetContent: { template: '<div><slot /></div>' },
        SheetDescription: { template: '<div><slot /></div>' },
        SheetHeader: { template: '<div><slot /></div>' },
        SheetTitle: { template: '<div><slot /></div>' },
        SheetTrigger: { template: '<div><slot /></div>' },
      },
    },
  })

  return { wrapper, router }
}

describe('AdminLayout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('does not render a duplicate admin shell header', async () => {
    const { wrapper } = await mountLayout('/admin/prompts')

    expect(wrapper.find('header').exists()).toBe(false)
    expect(wrapper.find('[data-admin-header-title]').exists()).toBe(false)
  })

  it('keeps the desktop admin menu sticky', async () => {
    const { wrapper } = await mountLayout('/admin/prompts')

    const desktopMenu = wrapper.find('aside')

    expect(desktopMenu.classes()).toContain('lg:sticky')
    expect(desktopMenu.classes()).toContain('lg:top-0')
  })

  it('keeps mobile navigation accessible without the removed header', async () => {
    const { wrapper } = await mountLayout('/admin/prompts/42/edit')

    expect(wrapper.text()).toContain('Open navigation')
    expect(wrapper.findAll('[data-admin-navigation]')).toHaveLength(2)
  })

  it('localizes mobile navigation and account actions in German', async () => {
    const { wrapper } = await mountLayout('/admin', 'de')

    expect(wrapper.text()).toContain('Navigation öffnen')
    expect(wrapper.text()).toContain('Zum Shop')
    expect(wrapper.text()).toContain('Abmelden')
  })

  it('keeps removed destinations out of desktop and mobile navigation', async () => {
    const { wrapper } = await mountLayout('/admin')

    const navigationInstances = wrapper.findAll('[data-admin-navigation]')

    expect(navigationInstances).toHaveLength(2)
    navigationInstances.forEach((navigation) => {
      const links = navigation.findAll('a')
      const hrefs = links.map((link) => link.attributes('href'))

      expect(hrefs).not.toContain('/admin/customers')
      expect(hrefs).not.toContain('/admin/users')
      expect(hrefs).not.toContain('/admin/settings')
      expect(hrefs).not.toContain('/admin/prompt-tester')
      expect(hrefs).not.toContain('/admin/editor')
      expect(navigation.text()).not.toContain('Customers')
      expect(navigation.text()).not.toContain('Users')
      expect(navigation.text()).not.toContain('Settings')
      expect(navigation.text()).not.toContain('Prompt Tester')
      expect(navigation.text()).not.toContain('Editor')
    })
  })
})
