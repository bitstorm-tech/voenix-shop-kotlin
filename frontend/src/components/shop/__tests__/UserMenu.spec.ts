import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserMenu from '@/components/shop/UserMenu.vue'
import { useAuthStore } from '@/stores/shared/auth'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const passthroughStub = { template: '<div><slot /></div>' }

describe('UserMenu', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it.each([
    [['CUSTOMER'], []],
    [['ADMIN'], ['admin']],
    [['SUPPLIER'], ['supplier']],
    [
      ['ADMIN', 'SUPPLIER'],
      ['admin', 'supplier'],
    ],
  ])('offers the area entries for roles %j', (roles, expected) => {
    useAuthStore().user = {
      id: 1,
      email: 'user@example.com',
      roles,
      shippingAddress: null,
      billingAddress: null,
      hasSeparateBillingAddress: false,
      createdAt: '2026-01-01T00:00:00Z',
    }

    const values = mount(UserMenu, {
      global: {
        stubs: {
          Select: passthroughStub,
          SelectTrigger: passthroughStub,
          SelectContent: passthroughStub,
          SelectItem: {
            props: ['value'],
            template: '<div data-item :data-value="value"><slot /></div>',
          },
        },
      },
    })
      .findAll('[data-item]')
      .map((item) => item.attributes('data-value'))
      .filter((value) => value === 'admin' || value === 'supplier')

    expect(values).toEqual(expected)
  })
})
