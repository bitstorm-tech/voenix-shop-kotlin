import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProductPrice from '@/components/shop/ProductPrice.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, values?: Record<string, unknown>) =>
      values ? `${key} ${Object.values(values).join(' ')}` : key,
  }),
}))

function mountPrice(props: { cents: number; regularCents?: number | null }) {
  return mount(ProductPrice, { props })
}

describe('ProductPrice', () => {
  it('renders one price and no discount when there is no regular price', () => {
    const wrapper = mountPrice({ cents: 1990 })

    expect(wrapper.get('[data-testid="product-price-effective"]').text()).toBe('19,90\u00a0€')
    expect(wrapper.find('[data-testid="product-price-regular"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="product-price-badge"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="product-price-saving"]').exists()).toBe(false)
  })

  it('shows the struck-through regular price, the percentage badge, and the saving', () => {
    const wrapper = mountPrice({ cents: 1592, regularCents: 1990 })

    expect(wrapper.get('[data-testid="product-price-effective"]').text()).toBe('15,92\u00a0€')
    expect(wrapper.get('[data-testid="product-price-regular"]').text()).toContain('19,90\u00a0€')
    expect(wrapper.get('[data-testid="product-price-badge"]').text()).toBe('price.discountBadge 20')
    expect(wrapper.get('[data-testid="product-price-saving"]').text()).toBe(
      'price.youSave 3,98\u00a0€',
    )
  })

  it('announces the struck-through amount as the previous price', () => {
    const wrapper = mountPrice({ cents: 1592, regularCents: 1990 })
    const regular = wrapper.get('[data-testid="product-price-regular"]')

    expect(regular.get('.sr-only').text()).toBe('price.previousPrice')
    expect(regular.get('.line-through').text()).toBe('19,90\u00a0€')
  })

  it('is a plain price when the regular price is not higher', () => {
    for (const regularCents of [1990, 1500, null]) {
      const wrapper = mountPrice({ cents: 1990, regularCents })

      expect(wrapper.find('[data-testid="product-price-regular"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="product-price-badge"]').exists()).toBe(false)
    }
  })

  it('shows the saved amount instead of a 0 % badge, and drops the second line with it', () => {
    const wrapper = mountPrice({ cents: 49999, regularCents: 50000 })

    expect(wrapper.get('[data-testid="product-price-badge"]').text()).toBe('−0,01\u00a0€')
    expect(wrapper.find('[data-testid="product-price-saving"]').exists()).toBe(false)
  })
})
