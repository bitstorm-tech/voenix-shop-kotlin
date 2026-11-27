import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import ReturnsView from '@/views/shop/ReturnsView.vue'

function mountReturns(locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { de, en },
  })
  return mount(ReturnsView, {
    global: {
      plugins: [i18n],
    },
  })
}

describe('ReturnsView', () => {
  it('renders the personalized goods exclusion, standard withdrawal policy, and model form with updated company details', () => {
    const wrapper = mountReturns('de')
    const text = wrapper.text()

    expect(wrapper.get('[data-testid="returns-personalized-exclusion"]').text()).toContain(
      '§ 312g Abs. 2 Nr. 1 BGB',
    )
    expect(text).not.toContain('besteht kein besteht kein')
    expect(text).toContain('Wallbergstraße 18, 83607 Holzkirchen')
    expect(text).toContain('write-us@voenix.shop')
    expect(text).not.toContain('Münchner Straße 17')
    expect(text).not.toContain('infos@voenix.shop')
    expect(wrapper.get('[data-testid="returns-withdrawal-form"]').text()).toContain(
      'Muster-Widerrufsformular',
    )
  })

  it('shows English header copy while keeping the binding German legal article', () => {
    const wrapper = mountReturns('en')

    expect(wrapper.get('h1').text()).toBe('Returns & Right of Withdrawal')
    expect(wrapper.get('article').attributes('lang')).toBe('de')
  })
})
