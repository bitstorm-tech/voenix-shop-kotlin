import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import FaqView from '@/views/shop/FaqView.vue'

async function mountFaq(locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { de, en },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/faq', name: 'faq', component: FaqView },
      { path: '/returns', name: 'returns', component: { template: '<div />' } },
      { path: '/shipping', name: 'shipping', component: { template: '<div />' } },
      { path: '/payment', name: 'payment', component: { template: '<div />' } },
    ],
  })
  await router.push('/faq')
  await router.isReady()

  return mount(FaqView, {
    global: {
      plugins: [i18n, router],
    },
  })
}

describe('FaqView', () => {
  it('renders all FAQ items, opens the first item by default, and toggles additional items', async () => {
    const wrapper = await mountFaq('de')

    expect(wrapper.findAll('[data-testid^="faq-item-"]')).toHaveLength(10)
    expect(wrapper.get('[data-testid="faq-item-configurator"]').text()).toContain(
      'Online-Konfigurator',
    )

    const paymentItem = wrapper.get('[data-testid="faq-item-payment"]')
    expect(paymentItem.text()).not.toContain('Mollie B.V.')

    await paymentItem.get('button').trigger('click')
    expect(paymentItem.text()).toContain('Mollie B.V.')
    expect(paymentItem.find('a[href="/payment"]').exists()).toBe(true)
  })

  it('links to internal shop pages and uses write-us@voenix.shop without outdated Stripe or wishlist copy', async () => {
    const wrapper = await mountFaq('de')

    await wrapper.get('[data-testid="faq-item-returns"] button').trigger('click')
    await wrapper.get('[data-testid="faq-item-shippingCountries"] button').trigger('click')
    await wrapper.get('[data-testid="faq-item-payment"] button').trigger('click')

    const text = wrapper.text()
    expect(text).toContain('write-us@voenix.shop')
    expect(text).not.toContain('Stripe')
    expect(text).not.toContain('Wunschliste')
    expect(text).not.toContain('info@voenix.shop')
    expect(wrapper.find('a[href="/returns"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/shipping"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/payment"]').exists()).toBe(true)
  })

  it('renders English copy when locale is en', async () => {
    const wrapper = await mountFaq('en')

    expect(wrapper.text()).toContain('Customer Service & FAQ')
    expect(wrapper.get('[data-testid="faq-cta"]').text()).toContain('Did we answer your questions?')
  })
})
