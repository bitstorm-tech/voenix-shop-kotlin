import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MagicCoinsExhaustedDialog from '@/components/shop/wizard/MagicCoinsExhaustedDialog.vue'
import { resetApiClientForTests } from '@/lib/api'
import { useAuthStore } from '@/stores/shared/auth'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => ({ fullPath: '/wizard' }),
  }
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const dialogStubs = {
  Dialog: {
    props: ['open'],
    emits: ['update:open'],
    template: '<div v-if="open" data-testid="dialog-shell"><slot /></div>',
  },
  DialogContent: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<h3><slot /></h3>' },
  DialogDescription: { template: '<p><slot /></p>' },
  DialogFooter: { template: '<div><slot /></div>' },
  RouterLink: RouterLinkStub,
}

describe('MagicCoinsExhaustedDialog', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('invites an anonymous guest to sign in or register with a redirect back to the wizard', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Authentication required', errors: {} }, 401)),
    )
    const auth = useAuthStore()
    await auth.authReadyPromise

    const wrapper = mount(MagicCoinsExhaustedDialog, {
      props: { open: true },
      global: { stubs: dialogStubs },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('magicCoins.dialog.guestDescription')
    const links = wrapper.findAllComponents(RouterLinkStub).map((link) => link.props('to'))
    expect(links).toEqual([
      { path: '/register', query: { redirect: '/wizard' } },
      { path: '/login', query: { redirect: '/wizard' } },
    ])
    expect(wrapper.find('[data-testid="request-magic-coins-button"]').exists()).toBe(false)
  })

  it('lets a signed-in user submit a one-click refill request and shows the pending confirmation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/me') {
        return jsonResponse({
          id: 7,
          email: 'customer@example.com',
          roles: ['USER'],
          shippingAddress: null,
          billingAddress: null,
          hasSeparateBillingAddress: false,
          createdAt: '2026-01-01T00:00:00Z',
        })
      }
      if (path === '/api/magic-coins/request-status') {
        return jsonResponse({ status: 'CAN_REQUEST' })
      }
      if (path === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-1' })
      }
      if (path === '/api/magic-coins/requests' && init?.method === 'POST') {
        return jsonResponse({ id: 5, status: 'PENDING', createdAt: '2026-11-27T12:00:00Z' }, 201)
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = useAuthStore()
    await auth.authReadyPromise

    const wrapper = mount(MagicCoinsExhaustedDialog, {
      props: { open: true },
      global: { stubs: dialogStubs },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('magicCoins.dialog.requestDescription')
    await wrapper.get('[data-testid="request-magic-coins-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('magicCoins.dialog.pendingTitle')
    expect(wrapper.text()).toContain('magicCoins.dialog.pendingDescription')
    expect(wrapper.find('[data-testid="request-magic-coins-button"]').exists()).toBe(false)
  })

  it('shows the neutral blocked message without a request button when the account is blocked', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input)
        if (path === '/api/auth/me') {
          return jsonResponse({
            id: 7,
            email: 'customer@example.com',
            roles: ['USER'],
            shippingAddress: null,
            billingAddress: null,
            hasSeparateBillingAddress: false,
            createdAt: '2026-01-01T00:00:00Z',
          })
        }
        if (path === '/api/magic-coins/request-status') {
          return jsonResponse({ status: 'BLOCKED' })
        }
        throw new Error(`Unexpected request: ${path}`)
      }),
    )

    const auth = useAuthStore()
    await auth.authReadyPromise

    const wrapper = mount(MagicCoinsExhaustedDialog, {
      props: { open: true },
      global: { stubs: dialogStubs },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('magicCoins.dialog.blockedDescription')
    expect(wrapper.find('[data-testid="request-magic-coins-button"]').exists()).toBe(false)
  })
})
