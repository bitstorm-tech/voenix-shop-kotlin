import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import PaymentView from '@/views/shop/PaymentView.vue'

function mountPayment(locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { de, en },
  })
  return mount(PaymentView, {
    global: {
      plugins: [i18n],
    },
  })
}

describe('PaymentView', () => {
  it('renders all six active Mollie payment methods and Mollie B.V. provider details', () => {
    const wrapper = mountPayment('de')

    for (const id of ['visa', 'mastercard', 'apple-pay', 'google-pay', 'paypal', 'klarna']) {
      expect(wrapper.find(`[data-testid="payment-method-${id}"]`).exists()).toBe(true)
    }

    const providerText = wrapper.get('[data-testid="payment-provider-card"]').text()
    expect(providerText).toContain('Mollie B.V.')
    expect(providerText).toContain('Keizersgracht 126')
    expect(providerText).toContain('30204462')
    expect(providerText).toContain('NL815839091B01')
    expect(wrapper.text()).not.toContain('Stripe')

    const externalLinks = wrapper.get('[data-testid="payment-notes-card"]').findAll('a')
    expect(externalLinks.map((a) => a.attributes('href'))).toEqual([
      'https://www.mollie.com/de/privacy',
      'https://www.klarna.com/de/agb/',
      'https://www.klarna.com/de/datenschutz/',
    ])
  })

  it('renders localized English payment details and English policy links', () => {
    const wrapper = mountPayment('en')

    expect(wrapper.text()).toContain('Payment Conditions')
    expect(wrapper.text()).toContain('Accepted Payment Methods')
    expect(wrapper.find('a[href="https://www.mollie.com/en/privacy"]').exists()).toBe(true)
  })
})
