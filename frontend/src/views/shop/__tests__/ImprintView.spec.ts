import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import ImprintView from '@/views/shop/ImprintView.vue'

function mountImprint(locale: 'de' | 'en' = 'de') {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { de, en },
  })
  return mount(ImprintView, {
    global: {
      plugins: [i18n],
    },
  })
}

describe('ImprintView', () => {
  it('renders provider details including email and phone number links in German', () => {
    const wrapper = mountImprint('de')
    const text = wrapper.text()

    expect(text).toContain('JoTo AI GbR')
    expect(text).toContain('Josef Bauer & Thomas Pränger')
    expect(text).toContain('Wallbergstr. 18')
    expect(text).toContain('83607 Holzkirchen')
    expect(text).toContain('DE455554144')
    expect(wrapper.get('a[href="mailto:write-us@voenix.shop"]').text()).toBe('write-us@voenix.shop')
    expect(wrapper.get('a[href="tel:+49802447732200"]').text()).toBe('+49 (0)8024 477 32 - 200')
  })

  it('localizes headings and labels in English', () => {
    const wrapper = mountImprint('en')

    expect(wrapper.text()).toContain('Publisher')
    expect(wrapper.text()).toContain('Phone number')
    expect(wrapper.get('a[href="tel:+49802447732200"]').text()).toBe('+49 (0)8024 477 32 - 200')
  })
})
