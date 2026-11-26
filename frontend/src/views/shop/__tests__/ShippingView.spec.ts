import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ShippingView from '@/views/shop/ShippingView.vue'
import { useCountriesStore } from '@/stores/shop/countries'

const messages = {
  eyebrow: 'Shipping Terms',
  title: 'Clear, fair, and delivered quickly.',
  lead: 'We ship within Germany and to selected EU countries.',
  intro: 'Overview intro',
  sectionOverview: 'Shipping costs by destination',
  countryColumn: 'Destination',
  priceLabel: 'Shipping cost',
  thresholdLabel: 'Free shipping from',
  supportedCountriesLabel: 'Available destinations',
  overviewLabel: 'Shipping partners',
  overviewValue: 'DHL Standard and DHL International Shipping',
  loadingCountries: 'Loading...',
  loadError: 'Could not load shipping costs.',
  retry: 'Try again',
  noCountries: 'No destinations.',
  alwaysFree: 'Always free',
  freeFromAmount: 'Free from {amount}',
  noFreeThreshold: '—',
  requestTitle: 'Other EU countries',
  requestText: 'Request text',
  requestHint: 'Request hint',
  returnsTitle: 'Returns',
  returnsText: 'Returns text',
  returnsAction: 'View returns',
  emailLabel: 'Contact',
  emailValue: 'write-us@voenix.shop',
  legalTitle: 'Pricing notes',
  legalParagraphs: ['Paragraph 1'],
}

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    locale: { value: 'en' },
    t: (key: string, params?: Record<string, string>) => {
      if (key === 'footerPages.shipping.content.freeFromAmount' && params?.amount) {
        return `Free from ${params.amount}`
      }
      return key
    },
    tm: () => messages,
  }),
}))

async function mountShipping() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/shipping', name: 'shipping', component: ShippingView },
      { path: '/returns', name: 'returns', component: { template: '<div />' } },
    ],
  })
  await router.push('/shipping')
  await router.isReady()

  const wrapper = mount(ShippingView, {
    global: {
      plugins: [router],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('ShippingView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders countries, shipping costs, and optional free thresholds dynamically from the store', async () => {
    const store = useCountriesStore()
    vi.spyOn(store, 'fetchCountries').mockImplementation(async () => {
      store.countries = [
        {
          name: 'Austria',
          countryCode: 'AT',
          dialCode: '+43',
          shippingCostCents: 1190,
          freeShippingThresholdCents: null,
        },
        {
          name: 'Germany',
          countryCode: 'DE',
          dialCode: '+49',
          shippingCostCents: 490,
          freeShippingThresholdCents: 5000,
        },
        {
          name: 'Luxembourg',
          countryCode: 'LU',
          dialCode: '+352',
          shippingCostCents: 0,
          freeShippingThresholdCents: null,
        },
      ]
    })

    const wrapper = await mountShipping()

    expect(store.fetchCountries).toHaveBeenCalled()
    expect(wrapper.get('[data-testid="shipping-rate-DE"]').text()).toContain('4,90')
    expect(wrapper.get('[data-testid="shipping-rate-DE"]').text()).toContain('Free from 50,00')
    expect(wrapper.get('[data-testid="shipping-rate-AT"]').text()).toContain('11,90')
    expect(wrapper.get('[data-testid="shipping-rate-AT"]').text()).toContain('—')
    expect(wrapper.get('[data-testid="shipping-rate-LU"]').text()).toContain('cart.shippingFree')
    expect(wrapper.get('[data-testid="shipping-rate-LU"]').text()).toContain('Always free')
  })

  it('shows a retryable alert when loading shipping countries fails', async () => {
    const store = useCountriesStore()
    const fetchCountries = vi.spyOn(store, 'fetchCountries').mockImplementation(async () => {
      store.error = 'HTTP 503'
    })

    const wrapper = await mountShipping()

    expect(wrapper.get('[data-testid="shipping-countries-error"]').text()).toContain(
      'Could not load shipping costs.',
    )
    const retryButton = wrapper.findAll('button').find((b) => b.text().includes('Try again'))
    await retryButton?.trigger('click')
    expect(fetchCountries).toHaveBeenLastCalledWith({ force: true })
  })
})
