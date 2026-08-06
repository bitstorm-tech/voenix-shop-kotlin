import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import CartPromotionForm from '@/components/shop/CartPromotionForm.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

const stubs = {
  Input: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :value="modelValue" v-bind="$attrs" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    template: '<button v-bind="$attrs"><slot /></button>',
  },
  Label: {
    template: '<label v-bind="$attrs"><slot /></label>',
  },
  Alert: {
    template: '<div role="alert"><slot /></div>',
  },
  RouterLink: RouterLinkStub,
}

describe('CartPromotionForm', () => {
  it('submits a trimmed Promotion Code', async () => {
    const wrapper = mount(CartPromotionForm, {
      props: {
        appliedPromotion: null,
        isLoading: false,
        errorCode: null,
      },
      global: { stubs },
    })

    await wrapper.get('input').setValue('  save10  ')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('apply')).toEqual([['save10']])
  })

  it('shows the applied Promotion and emits remove', async () => {
    const wrapper = mount(CartPromotionForm, {
      props: {
        appliedPromotion: {
          id: 9,
          name: 'Summer promotion',
          promotionCode: 'SAVE10',
          discountType: 'PERCENTAGE',
          discountValue: 10,
        },
        isLoading: false,
        errorCode: null,
      },
      global: { stubs },
    })

    expect(wrapper.text()).toContain('Summer promotion')
    expect(wrapper.text()).toContain('SAVE10')
    await wrapper.get('[data-testid="cart-promotion-remove"]').trigger('click')

    expect(wrapper.emitted('remove')).toHaveLength(1)
  })

  it('shows login and register actions for a guest-only rejection', () => {
    const wrapper = mount(CartPromotionForm, {
      props: {
        appliedPromotion: null,
        isLoading: false,
        errorCode: 'PROMOTION_LOGIN_REQUIRED',
      },
      global: { stubs },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('cart.promotion.errors.loginRequired')
    expect(wrapper.findAllComponents(RouterLinkStub).map((link) => link.props('to'))).toEqual([
      { name: 'login' },
      { name: 'register' },
    ])
  })

  it.each([
    ['PROMOTION_INVALID_CODE', 'cart.promotion.errors.invalidCode'],
    ['PROMOTION_INACTIVE', 'cart.promotion.errors.inactive'],
    ['PROMOTION_NOT_STARTED', 'cart.promotion.errors.notStarted'],
    ['PROMOTION_EXPIRED', 'cart.promotion.errors.expired'],
    ['PROMOTION_TOTAL_EXHAUSTED', 'cart.promotion.errors.totalExhausted'],
    ['PROMOTION_PER_USER_EXHAUSTED', 'cart.promotion.errors.perUserExhausted'],
  ] as const)('shows a localized message for %s', (errorCode, expectedMessage) => {
    const wrapper = mount(CartPromotionForm, {
      props: {
        appliedPromotion: null,
        isLoading: false,
        errorCode,
      },
      global: { stubs },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain(expectedMessage)
  })
})
