import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import AboutView from '@/views/shop/AboutView.vue'

async function mountAbout(locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { de, en },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/about', name: 'about', component: AboutView },
      { path: '/products', name: 'products', component: { template: '<div />' } },
    ],
  })
  await router.push('/about')
  await router.isReady()

  return mount(AboutView, {
    global: {
      plugins: [i18n, router],
    },
  })
}

describe('AboutView', () => {
  it('renders the transferred Bavaria story, highlights, and CTAs in German', async () => {
    const wrapper = await mountAbout('de')

    expect(wrapper.get('h1').text()).toBe('Aus Bayern. Mit Liebe.')
    const storyText = wrapper.get('[data-testid="about-story"]').text()
    expect(storyText).toContain('Holzkirchen bei München')
    expect(storyText).toContain('voenix.shop – Moments made magical.')
    expect(wrapper.findAll('[data-testid="about-highlights"] article')).toHaveLength(3)
    expect(wrapper.find('a[href="/products"]').exists()).toBe(true)
    expect(wrapper.find('a[href="mailto:write-us@voenix.shop"]').exists()).toBe(true)
  })

  it('renders localized English copy when locale is en', async () => {
    const wrapper = await mountAbout('en')

    expect(wrapper.get('h1').text()).toBe('From Bavaria. With Love.')
    expect(wrapper.get('[data-testid="about-cta"]').text()).toContain('Browse products')
  })
})
